package com.bitaim.carromaim.cv;

import android.graphics.Bitmap;
import android.graphics.PointF;
import android.graphics.RectF;
import android.util.Log;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.List;

/**
 * BoardDetector v4 — tuned on real Carrom Pool screenshots.
 *
 *  1. BOARD  = largest light-wood region (the playing surface). The old version
 *              looked for red-brown wood, but the game's whole background is
 *              red-brown, so it picked the entire screen.
 *  2. POCKETS = the four dark circles near the board corners (fallback: corners).
 *  3. PIECES  = HoughCircles inside the board, then filtered by colour:
 *              surface wood, orange decoration dots and the yellow star badge
 *              are rejected. Any other colour is a coin (white/black/blue/
 *              striped/red — colour doesn't matter for aiming).
 *  4. STRIKER = round piece on the bottom baseline (any colour); fallback:
 *              centre of the baseline so the user can drag it.
 */
public class BoardDetector {
    // OpenCV otherwise spreads HoughCircles over every core; measured on the phone at ~240% CPU
    // during other players' turns. Two workers keep detection inside the 100 ms frame budget.
    static { try { Core.setNumThreads(2); } catch (Throwable ignored) {} }

    private static final String TAG = "BoardDetector";
    private static final int PROC_WIDTH = 720;

    // Geometry of a carrom board relative to the playing-surface width
    private static final float COIN_R_FRAC     = 0.029f;
    // Physical puck edge, excluding the animated power-up halo.
    private static final float STRIKER_R_FRAC  = 0.036f;
    private static final float BASELINE_Y_FRAC = 0.845f; // bottom baseline
    private static final float POCKET_INSET    = 0.033f;

    // Tunable from the UI (kept for compatibility)
    private double param2 = 18;
    private static final java.util.concurrent.atomic.AtomicLong frameCounter=new java.util.concurrent.atomic.AtomicLong();
    private boolean allColours;
    private int targetColourKey=TargetColourLock.AUTO;
    public synchronized void setTargetColourKey(int key){targetColourKey=TargetColourLock.valid(key)?key:TargetColourLock.AUTO;if(targetColourKey!=TargetColourLock.AUTO)allColours=false;}
    private TeamMatcher assignedTeams;
    public synchronized void setAllColours(boolean enabled){
        allColours=enabled;
        // A fallback selection cannot broaden an established assignment while
        // its badge is hidden. Board loss or a freestyle legend ends the session.
    }

    public void setMinRadiusFrac(float v) { /* radii now derived from board size */ }
    public void setMaxRadiusFrac(float v) { /* radii now derived from board size */ }
    public synchronized void setParam2(double v) { param2 = Math.max(12, Math.min(v, 40)); }

    private LuckyShot.Scene lucky;
    /** Lucky Shot scene found by the last {@link #detectMat} call (source pixels), or null. */
    public synchronized LuckyShot.Scene lucky() { return lucky; }

    private final Mat frame = new Mat(), small = new Mat(), gray = new Mat(),
                      bgr = new Mat(), hsv = new Mat();

    public synchronized void close() {
        assignedTeams=null;
        frame.release(); small.release(); gray.release(); bgr.release(); hsv.release();
    }

    public synchronized GameState detect(Bitmap bitmap) {
        if (bitmap == null || bitmap.getWidth() == 0 || bitmap.getHeight() == 0) {assignedTeams=null;return null;}
        Utils.bitmapToMat(bitmap, frame); // RGBA
        return detectMat(frame);
    }

    /** Core detection on an RGBA Mat. Returns coordinates in the Mat's pixels. */
    public synchronized GameState detectMat(Mat rgba) {
        int srcW = rgba.cols(), srcH = rgba.rows();
        if (srcW == 0 || srcH == 0) {assignedTeams=null;return null;}
        float scale = (float) PROC_WIDTH / srcW;
        int procH = Math.round(srcH * scale);
        Imgproc.resize(rgba, small, new Size(PROC_WIDTH, procH), 0, 0, Imgproc.INTER_AREA);
        Imgproc.cvtColor(small, bgr, Imgproc.COLOR_RGBA2BGR);
        Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV);
        Imgproc.cvtColor(small, gray, Imgproc.COLOR_RGBA2GRAY);
        Imgproc.GaussianBlur(gray, gray, new Size(5, 5), 1.5);

        // ── 1. Board ────────────────────────────────────────────────────────
        Rect b = findSurface(procH);
        lucky = null;
        if (b == null) {
            assignedTeams=null;
            // 4.11.0: no light-wood carrom board: maybe the Lucky Shot mini-game (dark red board, ring target).
            try { lucky = LuckyShot.detect(hsv, gray, scale); } catch (Throwable t) { lucky = null; }
            return null;
        }
        float bw = b.width;
        TeamMatcher teams = new TeamMatcher(hsv);
        if(teams.freestyle)assignedTeams=null;
        else if(teams.known)assignedTeams=teams;
        else if(assignedTeams!=null)teams=assignedTeams;
        final boolean effectiveAllColours=allColours&&assignedTeams==null;

        // ── 2/3. Circles inside the board ───────────────────────────────────
        int minR = Math.round(bw * 0.020f), maxR = Math.round(bw * 0.033f);
        Mat circles = new Mat();
        Imgproc.HoughCircles(gray, circles, Imgproc.HOUGH_GRADIENT, 1.2,
                bw * 0.03, 90, param2, minR, maxR);

        List<float[]> pockets = findPocketCenters(b); // x, y
        List<float[]> coins   = new ArrayList<>();   // x, y, r, color
        float[] striker = null; float strikerScore = -1;
        float baseY = b.y + b.height * BASELINE_Y_FRAC;

        // Large-circle candidates can suppress touching coins in a single Hough
        // pass. Search for the larger striker separately, only on its baseline.
        int stripX = Math.max(0, (int) (b.x + bw * 0.12f));
        int stripY = Math.max(0, (int) (baseY - bw * 0.09f));
        int stripRight = Math.min(gray.cols(), (int) (b.x + bw * 0.88f));
        int stripBottom = Math.min(gray.rows(), (int) (baseY + bw * 0.09f));
        Mat strip = gray.submat(stripY, stripBottom, stripX, stripRight);
        Mat strikerCircles = new Mat();
        Imgproc.HoughCircles(strip, strikerCircles, Imgproc.HOUGH_GRADIENT, 1.2,
                bw * 0.06, 90, param2, Math.round(bw * 0.032f), Math.round(bw * 0.056f));
        strip.release();
        int coinCount = circles.cols();
        int denseX=Math.max(0,(int)(b.x+bw*.30f)),denseY=Math.max(0,(int)(b.y+b.height*.30f));
        int denseRight=Math.min(gray.cols(),(int)(b.x+bw*.70f)),denseBottom=Math.min(gray.rows(),(int)(b.y+b.height*.70f));
        int central=0;
        for(int i=0;i<coinCount;i++){double[] c=circles.get(0,i);if(c!=null&&c[0]>=denseX&&c[0]<=denseRight&&c[1]>=denseY&&c[1]<=denseBottom)central++;}
        Mat denseCircles=new Mat();
        if(central>=8){
            Mat dense=gray.submat(denseY,denseBottom,denseX,denseRight);
            Imgproc.HoughCircles(dense,denseCircles,Imgproc.HOUGH_GRADIENT,1.0,bw*.03,90,Math.min(param2,16),minR,Math.round(bw*.028f));
            dense.release();
        }
        int denseCount=denseCircles.cols();
        // The light-wood mask stops at the shaded rail. Pieces touching that
        // rail still occupy the board and must remain collision obstacles.
        float railAllowance = bw * 0.035f;

        for (int i = 0; i < denseCount+coinCount + strikerCircles.cols(); i++) {
            boolean densePass=i<denseCount;
            boolean strikerPass = i >= denseCount+coinCount;
            double[] d = densePass?denseCircles.get(0,i):strikerPass ? strikerCircles.get(0, i-denseCount-coinCount) : circles.get(0,i-denseCount);
            if (d == null || d.length < 3) continue;
            float cx = (float) d[0], cy = (float) d[1], r = (float) d[2];
            if (strikerPass) { cx += stripX; cy += stripY; }
            if(densePass){cx+=denseX;cy+=denseY;}
            if (cx < b.x - railAllowance || cx > b.x + b.width + railAllowance
                    || cy < b.y - railAllowance || cy > b.y + b.height + railAllowance) continue;
            boolean fullyInside = cx - r * 0.8f > b.x - railAllowance
                    && cx + r * 0.8f < b.x + b.width + railAllowance
                    && cy - r * 0.8f > b.y - railAllowance
                    && cy + r * 0.8f < b.y + b.height + railAllowance;
            double[] c = meanHsv((int) cx, (int) cy, Math.max(2, (int) (r * 0.35f)));
            if (c == null) continue;
            double h = c[0], s = c[1], v = c[2];
            boolean yellowDisc=!strikerPass&&teams.isYellowCoin(hsv,cx,cy,bw*COIN_R_FRAC);
            // The game rings a returned or highlighted queen with yellow glow; a power-up badge is never red inside.
            boolean redCentre=!strikerPass&&teams.isRedCoin(hsv,cx,cy,bw*COIN_R_FRAC);
            // 4.9.2 (live 09:2x, purple theme, online): the opponent's black coins with an orange centre were never
            // detected; the centre (hue 14) reads as wood, so every shot planned straight through them. A disc whose
            // ring is dark (value ~70) while its centre is saturated (wood centre s~130, these s~220) is a coin.
            boolean darkRingCoin=!strikerPass&&darkRingCoin(cx,cy,bw*COIN_R_FRAC,s,v);
            if(darkRingCoin)redCentre=true; // same exemptions as the two-tone red coins below
            // A dark baseline stroke can form a large circle too. Its warm
            // brown centre is wood, rather than the actual striker puck.
            if (strikerPass && h >= 6 && h <= 24 && s >= 45) continue;
            if (strikerPass && strikerRingSupport(cx, cy, bw) < 0.55f) continue;

            boolean nearCorner = Math.min(cx - b.x, b.x + b.width - cx) < bw * 0.12f
                              && Math.min(cy - b.y, b.y + b.height - cy) < bw * 0.12f;
            boolean insideHole=false;
            for(float[] hole:pockets)if(dist(cx,cy,hole[0],hole[1])<bw*.05f*.65f){insideHole=true;break;}
            if(insideHole)continue;

            // v4.8.4: a red-and-yellow two-tone coin with its yellow band over the centre averages to "board wood"
            // (hue ~23); the red ring check already knows it is a coin. Purple-theme recall 37/77 -> 76/77.
            if ((!redCentre&&isSurface(h, s, v)) || isDecoration(h, s, v) || !yellowDisc&&!redCentre&&isStarBadge(h, s, v)) continue;
            // Hough can fit an off-centre circle to the painted baseline rings.
            // Reject warm wood there, without discarding cream coins in the cluster.
            if (!redCentre&&nearDecoration(cx, cy, b) && h >= 6 && h <= 24 && s >= 45 && v >= 140) continue;
            if (!yellowDisc&&!redCentre&&yellowFraction((int) cx, (int) cy, (int) r) > 0.12) continue; // ⭐ power-up badge

            // Striker candidate: on the bottom baseline and striker-sized
            if (strikerPass && Math.abs(cy - baseY) < bw * 0.045f && r >= bw * 0.032f
                    && cx > b.x + bw * 0.15f && cx < b.x + bw * 0.85f) {
                float score = r - Math.abs(cy - baseY) * 0.3f + (h > 85 && h < 135 ? bw * 0.02f : 0);
                if (score > strikerScore) {
                    if (striker != null && striker[2] <= bw * 0.042f) coins.add(new float[]{striker[0], striker[1], striker[2], striker[3]});
                    strikerScore = score; striker = new float[]{cx, cy, r, colorOf(h, s, v)};
                } else if (r <= bw * 0.042f && fullyInside) {
                    coins.add(new float[]{cx, cy, r, colorOf(h, s, v)}); // a coin on the baseline
                }
                continue;
            }
            if (strikerPass) continue;
            if (r > bw * 0.042f || !fullyInside) continue; // false circle
            coins.add(new float[]{cx, cy, r, colorOf(h, s, v)});
        }
        // Fallback: at either end of the baseline the striker sits on the painted end circle, which can defeat
        // the ring check; it then shows up as a "coin". White and black coins and the red queen are neutral or red,
        // so a coloured disc lying on our baseline, with the game's aim dots radiating from it, is our striker
        // skin (live frame 2026-11-07 19:31). A coloured team coin resting there on the opponent's turn has no dots.
        if (striker == null) {
            float[] best = null;
            for (float[] c : coins) {
                if (Math.abs(c[1] - baseY) > bw * 0.035f || c[0] < b.x + bw * 0.12f || c[0] > b.x + bw * 0.88f) continue;
                int key = teams.appearanceKey(hsv, c[0], c[1], bw * COIN_R_FRAC);
                if (key < 100 || teams.isRedCoin(hsv, c[0], c[1], bw * COIN_R_FRAC)) continue;
                // Only the player who is aiming has the game's white guide dots radiating from the striker.
                float[] dots = AimGuideDetector.detect(hsv, c[0], c[1], bw, b.y);
                if (dots == null || dots[2] < 6) continue;
                if (best == null || Math.abs(c[1] - baseY) < Math.abs(best[1] - baseY)) best = c;
            }
            if (best != null) { coins.remove(best); striker = new float[]{best[0], best[1], Math.max(best[2], bw * STRIKER_R_FRAC), Coin.COLOR_STRIKER}; }
        }
        circles.release();
        strikerCircles.release();
        denseCircles.release();

        // ── Remove duplicate detections of the same coin ────────────────────
        float coinR = bw * COIN_R_FRAC;
        List<float[]> unique = new ArrayList<>();
        for (float[] c : coins) {
            boolean dup = false;
            for (float[] u : unique) {
                if (dist(c[0], c[1], u[0], u[1]) < coinR * 1.3f) { dup = true; break; }
            }
            if (striker != null && dist(c[0], c[1], striker[0], striker[1]) < bw * 0.05f) dup = true;
            if (!dup) unique.add(c);
        }

        // ── Build result (scaled back to source pixels) ─────────────────────
        GameState st = new GameState();
        st.frameId=frameCounter.incrementAndGet();
        st.board = new RectF(b.x / scale, b.y / scale, (b.x + b.width) / scale, (b.y + b.height) / scale);
        float sbw = st.board.width();

        float sx = striker != null ? striker[0] : b.x + b.width / 2f;
        float sy = striker != null ? striker[1] : baseY;
        st.striker = new Coin(sx / scale, sy / scale, sbw * STRIKER_R_FRAC, Coin.COLOR_STRIKER, true);
        st.strikerDetected = striker != null;
        if(striker!=null){float knob=findSliderKnob(gray,striker[0],striker[2],b.y+b.height,bw);if(!Float.isNaN(knob))st.sliderY=knob/scale;}
        if(striker!=null){
            float[] guide=AimGuideDetector.detect(hsv,striker[0],striker[1],bw,b.y);
            if(guide!=null){st.aimGuideX=guide[0];st.aimGuideY=guide[1];st.aimGuideDots=(int)guide[2];}
        }
        st.teamKnown = targetColourKey!=TargetColourLock.AUTO||effectiveAllColours||teams.known;
        st.targetColourKey=targetColourKey;
        st.freestyle = effectiveAllColours||teams.freestyle;
        st.assignedColours = assignedTeams!=null;
        st.playerHue = teams.playerHue(); st.opponentHue = teams.opponentHue();

        java.util.List<Coin> plainRed=new java.util.ArrayList<>();java.util.Map<Coin,Float> shadeValue=new java.util.HashMap<>();
        for (float[] c : unique) {
            Coin coin = new Coin(c[0] / scale, c[1] / scale, sbw * COIN_R_FRAC, (int) c[3], false);
            // Hue is circular: averaging red pixels around 0/180 can look blue.
            if(teams.isRedCoin(hsv,c[0],c[1],bw*COIN_R_FRAC))coin.color=Coin.COLOR_RED;
            int owner=effectiveAllColours?1:teams.owner(hsv,c[0],c[1],bw*COIN_R_FRAC);
            boolean plain=!st.freestyle&&teams.isPlainQueen(hsv,c[0],c[1],bw*COIN_R_FRAC);
            coin.isQueen = plain&&owner==0;
            coin.appearanceKey=teams.appearanceKey(hsv,c[0],c[1],bw*COIN_R_FRAC);
            if(coin.color!=Coin.COLOR_RED){if(coin.appearanceKey==TargetColourLock.DARK)coin.color=Coin.COLOR_BLACK;else if(coin.appearanceKey==TargetColourLock.LIGHT)coin.color=Coin.COLOR_WHITE;}
            // Overlay strokes and painted circles must not become phantom blockers.
            if(targetColourKey==TargetColourLock.AUTO&&!effectiveAllColours&&teams.distinctChromaticTeams() && owner==0 && coin.color!=Coin.COLOR_RED)continue;
            coin.targetEligible = owner==1; // colour-lock eligibility is set after the shade split below
            coin.transferEligible = owner!=0||coin.appearanceKey!=TargetColourLock.AUTO;
            st.coins.add(coin);shadeValue.put(coin,teams.appearanceValue(hsv,c[0],c[1],bw*COIN_R_FRAC));
            if(plain)plainRed.add(coin);
        }
        splitShades(st.coins,shadeValue);
        if(targetColourKey!=TargetColourLock.AUTO)for(Coin coin:st.coins)coin.targetEligible=!coin.isQueen&&TargetColourLock.matches(targetColourKey,coin.appearanceKey);
        // Live 2026-11-08 00:29 (purple theme): the opponent's coins are red-and-yellow and their score badge matched
        // the solid red queen, so the queen counted as an opponent coin and "queen before the last coin" never applied.
        // Exactly one plain solid-red disc among other team discs is the queen whatever the badge says.
        boolean anyQueen=false;for(Coin c:st.coins)if(c.isQueen)anyQueen=true;
        if(!anyQueen&&plainRed.size()==1&&st.coins.size()>=3){
            Coin q=plainRed.get(0);q.isQueen=true;q.targetEligible=false;q.transferEligible=true;q.color=Coin.COLOR_RED;
        }
        st.queenRequired=QueenRule.apply(st);
        st.pockets.addAll(choosePockets(pockets, st.board, scale));
        int packed=0;
        float centreX=(st.board.left+st.board.right)*.5f,centreY=(st.board.top+st.board.bottom)*.5f;
        for(Coin coin:st.coins)if(dist(coin.pos.x,coin.pos.y,centreX,centreY)<sbw*.18f)packed++;
        st.openingCluster=packed>=12;
        return st;
    }

    // ── Board surface ───────────────────────────────────────────────────────
    private List<float[]> findPocketCenters(Rect board) {
        Mat mask = new Mat(), hierarchy = new Mat();
        Core.inRange(hsv, new Scalar(0, 0, 0), new Scalar(180, 80, 85), mask);
        List<MatOfPoint> contours = new ArrayList<>();
        Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_EXTERNAL,
                Imgproc.CHAIN_APPROX_SIMPLE);
        List<float[]> out = new ArrayList<>();
        for (MatOfPoint contour : contours) {
            Rect r = Imgproc.boundingRect(contour);
            float x = r.x + r.width * 0.5f, y = r.y + r.height * 0.5f;
            float diameter = (r.width + r.height) * 0.5f;
            boolean corner = x >= board.x && x <= board.x + board.width
                    && y >= board.y && y <= board.y + board.height
                    && Math.min(x - board.x, board.x + board.width - x) < board.width * 0.12f
                    && Math.min(y - board.y, board.y + board.height - y) < board.width * 0.12f;
            if (corner && diameter > board.width * 0.065f && diameter < board.width * 0.14f
                    && r.width > r.height * 0.8f && r.width < r.height * 1.25f
                    && Imgproc.contourArea(contour) > r.width * r.height * 0.6f) {
                out.add(new float[]{x, y});
            }
            contour.release();
        }
        mask.release(); hierarchy.release();
        return out;
    }

    private Rect findSurface(int procH) {
        Mat mask = new Mat();
        Core.inRange(hsv, new Scalar(6, 55, 170), new Scalar(24, 165, 255), mask);
        Mat k = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(25, 25));
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, k);
        List<MatOfPoint> contours = new ArrayList<>();
        Mat hier = new Mat();
        Imgproc.findContours(mask, contours, hier, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
        double best = 0; Rect r = null;
        for (MatOfPoint c : contours) {
            double a = Imgproc.contourArea(c);
            if (a > best) { best = a; r = Imgproc.boundingRect(c); }
            c.release();
        }
        mask.release(); k.release(); hier.release();
        if (r == null || best < PROC_WIDTH * procH * 0.08) return null;
        // A carrom board is square — reject odd shapes (menus, banners)
        float ratio = r.width / (float) r.height;
        if (ratio < 0.8f || ratio > 1.25f) return null;
        return r;
    }

    /** Use the 4 detected dark pockets if they look right, else the corners. */
    private List<PointF> choosePockets(List<float[]> found, RectF board, float scale) {
        float in = board.width() * POCKET_INSET;
        PointF[] corners = {
                new PointF(board.left + in,  board.top + in),
                new PointF(board.right - in, board.top + in),
                new PointF(board.left + in,  board.bottom - in),
                new PointF(board.right - in, board.bottom - in)};
        List<PointF> out = new ArrayList<>();
        for (PointF c : corners) {
            PointF best = c; float bd = board.width() * 0.08f;
            for (float[] p : found) {
                float px = p[0] / scale, py = p[1] / scale;
                float d = dist(px, py, c.x, c.y);
                if (d < bd) { bd = d; best = new PointF(px, py); }
            }
            out.add(best);
        }
        return out;
    }

    // ── Colour helpers ──────────────────────────────────────────────────────
    /** Verify that a striker-sized disk exists, rather than an inflated Hough
     * fit around a smaller blue/black coin sitting on the baseline. */
    private float strikerRingSupport(float x, float y, float boardWidth) {
        int support = 0, samples = 24;
        float radius = boardWidth * 0.032f;
        for (int i = 0; i < samples; i++) {
            double angle = i * Math.PI * 2 / samples;
            int px = Math.round(x + radius * (float) Math.cos(angle));
            int py = Math.round(y + radius * (float) Math.sin(angle));
            if (px < 0 || py < 0 || px >= hsv.cols() || py >= hsv.rows()) continue;
            double[] pixel = hsv.get(py, px);
            boolean wood = pixel[0] >= 6 && pixel[0] <= 24 && pixel[1] >= 45;
            if (!wood) support++;
        }
        return support / (float) samples;
    }

    /** Mean value (brightness) on the ring 0.55-0.85 r is dark while the centre is saturated and bright. */
    private boolean darkRingCoin(float x,float y,float r,double centreS,double centreV){
        if(centreS<170||centreV<150)return false;
        double sum=0;int n=0;
        for(int k=0;k<24;k++){
            double a=k*Math.PI/12;
            for(float f:new float[]{.55f,.7f,.85f}){
                int px=(int)(x+Math.cos(a)*r*f),py=(int)(y+Math.sin(a)*r*f);
                if(px<0||py<0||px>=hsv.cols()||py>=hsv.rows())continue;
                sum+=hsv.get(py,px)[2];n++;
            }
        }
        return n>40&&sum/n<120;
    }
    private double[] meanHsv(int x, int y, int s) {
        int x0 = Math.max(0, x - s), y0 = Math.max(0, y - s);
        int x1 = Math.min(hsv.cols() - 1, x + s), y1 = Math.min(hsv.rows() - 1, y + s);
        if (x1 <= x0 || y1 <= y0) return null;
        Mat patch = hsv.submat(y0, y1, x0, x1);
        Scalar m = Core.mean(patch);
        double hueX=0,hueY=0;
        for(int py=0;py<patch.rows();py++)for(int px=0;px<patch.cols();px++){
            double[] pixel=patch.get(py,px);double angle=pixel[0]*Math.PI/90;
            hueX+=Math.cos(angle)*pixel[1];hueY+=Math.sin(angle)*pixel[1];
        }
        if(Math.hypot(hueX,hueY)>0.001){
            double hue=Math.atan2(hueY,hueX)*90/Math.PI;m.val[0]=hue<0?hue+180:hue;
        }
        patch.release();
        return m.val;
    }
    /** Share of bright-yellow pixels around a circle (the game's star badge). */
    private double yellowFraction(int x, int y, int r) {
        int x0 = Math.max(0, x - r), y0 = Math.max(0, y - r);
        int x1 = Math.min(hsv.cols() - 1, x + r), y1 = Math.min(hsv.rows() - 1, y + r);
        if (x1 <= x0 || y1 <= y0) return 0;
        Mat patch = hsv.submat(y0, y1, x0, x1), m = new Mat();
        Core.inRange(patch, new Scalar(20, 170, 200), new Scalar(35, 255, 255), m);
        double frac = Core.countNonZero(m) / (double) (m.rows() * m.cols());
        patch.release(); m.release();
        return frac;
    }

    private static final float[][] DECORATIONS = {
            {.21f, .14f}, {.79f, .14f}, {.14f, .21f}, {.86f, .21f},
            {.14f, .79f}, {.86f, .79f}, {.21f, .855f}, {.79f, .855f}};
    private static boolean nearDecoration(float x, float y, Rect board) {
        for (float[] spot : DECORATIONS) {
            if (dist(x, y, board.x + board.width * spot[0], board.y + board.height * spot[1])
                    < board.width * 0.055f) return true;
        }
        return false;
    }

    private static boolean isSurface(double h, double s, double v)    { return h >= 6 && h <= 24 && s >= 55; }
    private static boolean isDecoration(double h, double s, double v) { return h >= 10 && h <= 22 && s >= 235 && v >= 180; }
    private static boolean isStarBadge(double h, double s, double v)  { return h > 20 && h <= 35 && s >= 200 && v >= 220; }

    /** The game's power slider knob sits directly under the striker's x; its height depends on the layout
     *  (measured 0.33 to 0.41 board widths below the detected board), so it is searched, not assumed. */
    private static float findSliderKnob(Mat gray,float sx,float sr,float boardBottom,float bw){
        // Live 2026-11-07 23:37: a circle 0.21 board widths below the board was taken for the knob and the slide
        // missed; every recorded slider row is 0.33-0.35 board widths below the board.
        int y0=Math.max(0,(int)(boardBottom+bw*.27f)),y1=Math.min(gray.rows(),(int)(boardBottom+bw*.50f));
        int x0=Math.max(0,(int)(sx-sr*2.5f)),x1=Math.min(gray.cols(),(int)(sx+sr*2.5f));
        if(y1-y0<sr*2||x1-x0<sr*2)return Float.NaN;
        Mat roi=gray.submat(y0,y1,x0,x1),blur=new Mat(),found=new Mat();
        try{
            Imgproc.medianBlur(roi,blur,5);
            Imgproc.HoughCircles(blur,found,Imgproc.HOUGH_GRADIENT,1,sr*2,100,20,Math.round(sr*.55f),Math.round(sr*1.15f));
            float best=Float.NaN,bestDx=sr*.6f;
            for(int i=0;i<found.cols();i++){double[] c=found.get(0,i);float dx=Math.abs((float)c[0]+x0-sx);if(dx<bestDx){bestDx=dx;best=(float)c[1]+y0;}}
            return best;
        }finally{blur.release();found.release();roi.release();}
    }

    private static int colorOf(double h, double s, double v) {
        if (v < 80) return Coin.COLOR_BLACK;
        if (s < 80 && v > 170) return Coin.COLOR_WHITE;
        if (s > 120 && (h < 8 || h > 165)) return Coin.COLOR_RED;
        return v < 150 ? Coin.COLOR_BLACK : Coin.COLOR_WHITE; // blue / striped: any team colour
    }

    private static float dist(float x1, float y1, float x2, float y2) {
        float dx = x2 - x1, dy = y2 - y1;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    /** 4.10.6 (live 15:3x: light blue vs dark blue discs, hue 96 vs 110, ring brightness 255 vs 140-195; the Blue lock
     *  took both). Within one hue family on this board, a clear brightness gap (>= 45) splits the discs into two
     *  shades and the brighter shade gets the "light" key. A board with one shade per hue is unchanged. */
    static void splitShades(java.util.List<Coin> coins,java.util.Map<Coin,Float> value){
        java.util.List<Coin> solid=new java.util.ArrayList<>();
        for(Coin c:coins){Float v=value.get(c);if(v!=null&&v>=0&&c.appearanceKey>=100&&c.appearanceKey<280&&c.color!=Coin.COLOR_RED)solid.add(c);}
        java.util.Set<Coin> done=new java.util.HashSet<>();
        for(Coin seed:solid){
            if(done.contains(seed))continue;
            java.util.List<Coin> group=new java.util.ArrayList<>();
            for(Coin c:solid){int d=Math.abs(c.appearanceKey-seed.appearanceKey);if(Math.min(d,180-d)<=10&&!done.contains(c))group.add(c);}
            done.addAll(group);if(group.size()<2)continue;
            group.sort((x,y)->Float.compare(value.get(x),value.get(y)));
            if(System.getProperty("bitaim.shades")!=null)for(Coin c:group)System.out.printf("shade key %d value %.0f at (%.0f,%.0f)%n",c.appearanceKey,value.get(c),c.pos.x,c.pos.y);
            float bestGap=0;int split=-1;
            for(int i=0;i+1<group.size();i++){float g=value.get(group.get(i+1))-value.get(group.get(i));if(g>bestGap){bestGap=g;split=i;}}
            if(bestGap<45f)continue;
            for(int i=split+1;i<group.size();i++){Coin c=group.get(i);c.appearanceKey=TargetColourLock.BRIGHT+(c.appearanceKey-100);}
        }
    }
}
