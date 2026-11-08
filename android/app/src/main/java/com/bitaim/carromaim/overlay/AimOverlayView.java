package com.bitaim.carromaim.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import com.bitaim.carromaim.cv.Coin;
import com.bitaim.carromaim.cv.GameState;
import com.bitaim.carromaim.cv.ShotPlanner;
import com.bitaim.carromaim.cv.ShotTracker;
import com.bitaim.carromaim.cv.RoutePlanner;
import com.bitaim.carromaim.cv.GoldSceneLock;
import com.bitaim.carromaim.cv.AimAlignment;
import com.bitaim.carromaim.cv.TargetColourLock;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

/** Screen-coordinate aim guidance based only on a detected board.
 * AUTO passes touches through; AIM uses the same collision checks for a selected coin.
 *
 * v4.5: route planning (up to dozens of physics solves per frame) runs on a background planner
 * thread with frame coalescing. The UI thread only smooths detections and draws the last plan,
 * so the game never stutters while the planner works. Drawing is also guarded so a planner or
 * rendering fault can never close the overlay.
 */
public class AimOverlayView extends View {

    public static final String MODE_DIRECT = "DIRECT";
    public static final String MODE_AI     = "AI";
    public static final String MODE_GOLDEN = "GOLDEN";
    public static final String MODE_LUCKY  = "LUCKY";
    public static final String MODE_ALL    = "ALL";


    private String  shotMode        = MODE_ALL;
    private PointF  targetPos;
    private PointF  manualStriker;
    private boolean draggingStriker = false;
    private boolean strikerMoveable = true;
    private boolean snapMode        = false;   // NEW: snap-to-coin

    // Snap state
    private Coin   snapCoin;       // coin that was snapped to
    private PointF snapPocket;     // best pocket for that coin
    private PointF snapHit;        // striker centre at the moment of impact

    private GameState detected;
    private int missingStrikerFrames;
    private float marginOffsetX = 0f, marginOffsetY = 0f;
    private float sensitivity   = 1.0f;
    private final float dp;

    // ── Paints ────────────────────────────────────────────────────────────────
    private final Paint aimLinePaint;
    private final Paint coinPathPaint;
    private final Paint snapLinePaint;       // dotted lime — coin → pocket
    private final Paint snapCoinRingPaint;   // ring around snapped coin
    private final Paint pocketGlowPaint;
    private final Paint strikerFillPaint, strikerRingPaint;
    private final Paint coinOutlinePaint;
    private final Paint blackFillPaint, whiteFillPaint, redFillPaint;
    private final Paint pocketFillPaint;
    private final Paint targetCrossPaint;
    private final Paint textPaint, smallTextPaint;
    private final Paint boardOutlinePaint;
    private final Paint dragHintPaint;
    private final Paint ghostPaint, pocketRingPaint, goldPaint, goldDashPaint, goldTextPaint;
    /** Faint "alternative route" paints, like the grey alternatives on a map. Outlines only. */
    private final Paint altStrikerPaint, altCoinPaint, altTextPaint;

    public AimOverlayView(Context context) {
        super(context);
        dp = context.getResources().getDisplayMetrics().density;

        aimLinePaint      = stroke(Color.parseColor("#00E5FF"), 2.0f);

        coinPathPaint     = stroke(Color.parseColor("#FF8A00"), 1.6f);

        // Snap: bright lime dashes from coin to pocket
        snapLinePaint     = stroke(Color.parseColor("#22FF6E"), 1.6f);
        snapLinePaint.setPathEffect(new DashPathEffect(new float[]{10*dp, 6*dp}, 0));

        // Snap: thick lime ring around the targeted coin
        snapCoinRingPaint = stroke(Color.parseColor("#22FF6E"), 1.6f);

        pocketGlowPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
        pocketGlowPaint.setColor(Color.parseColor("#22C55E"));
        pocketGlowPaint.setStyle(Paint.Style.FILL);
        pocketGlowPaint.setShadowLayer(16 * dp, 0, 0, Color.parseColor("#AA22C55E"));

        strikerFillPaint  = fill(Color.parseColor("#66FFFFFF"));
        strikerRingPaint  = stroke(Color.parseColor("#FFD700"), 2.4f);

        coinOutlinePaint  = stroke(Color.parseColor("#99FFFFFF"), 1.5f);
        blackFillPaint    = fill(Color.parseColor("#AA111111"));
        whiteFillPaint    = fill(Color.parseColor("#55FFFFFF"));
        redFillPaint      = fill(Color.parseColor("#AAFF3D71"));

        pocketFillPaint   = fill(Color.parseColor("#6622C55E"));

        targetCrossPaint  = stroke(Color.WHITE, 1.8f);
        targetCrossPaint.setAlpha(200);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(14 * dp);
        textPaint.setShadowLayer(4 * dp, 0, 0, Color.BLACK);

        smallTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        smallTextPaint.setColor(Color.parseColor("#DDDDDD"));
        smallTextPaint.setTextSize(12 * dp);
        smallTextPaint.setShadowLayer(4 * dp, 0, 0, Color.BLACK);

        boardOutlinePaint = stroke(Color.parseColor("#44FFD700"), 1.2f);
        boardOutlinePaint.setPathEffect(new DashPathEffect(new float[]{8*dp, 6*dp}, 0));

        dragHintPaint     = stroke(Color.parseColor("#88FFD700"), 1.0f);
        dragHintPaint.setPathEffect(new DashPathEffect(new float[]{5*dp, 4*dp}, 0));

        ghostPaint = stroke(Color.parseColor("#00E5FF"), 1.0f);
        ghostPaint.setPathEffect(new DashPathEffect(new float[]{4*dp, 4*dp}, 0));
        pocketRingPaint = stroke(Color.parseColor("#22FF6E"), 1.8f);
        goldPaint     = stroke(Color.parseColor("#FFD700"), 1.6f);
        goldDashPaint = stroke(Color.parseColor("#FFD700"), 1.6f);
        goldDashPaint.setPathEffect(new DashPathEffect(new float[]{6*dp, 6*dp}, 0));
        goldTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        goldTextPaint.setColor(Color.parseColor("#FFD700"));
        goldTextPaint.setTextSize(16 * dp);

        altStrikerPaint = stroke(Color.parseColor("#9900E5FF"), 1.6f);
        altStrikerPaint.setPathEffect(new DashPathEffect(new float[]{6*dp, 6*dp}, 0));
        altCoinPaint = stroke(Color.parseColor("#99FFFFFF"), 1.6f);
        altCoinPaint.setPathEffect(new DashPathEffect(new float[]{6*dp, 6*dp}, 0));
        altTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        altTextPaint.setColor(Color.parseColor("#EEFFFFFF"));
        altTextPaint.setTextSize(13 * dp);
        altTextPaint.setShadowLayer(3 * dp, 0, 0, Color.BLACK);

        // Hardware drawing (software layer on a full-screen view was very slow)
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void setShotMode(String mode)             { shotMode = mode; postInvalidate(); }
    public void setMarginOffset(float dx, float dy)  { marginOffsetX = dx; marginOffsetY = dy; postInvalidate(); }
    public void setSensitivity(float v)              { sensitivity = Math.max(0.3f, Math.min(v, 3.0f)); postInvalidate(); }
    public void setStrikerMoveable(boolean m)        { strikerMoveable = m; postInvalidate(); }
    public void setSnapMode(boolean on)              { snapMode = on; clearSnap(); postInvalidate(); }

    private boolean playMode = false;
    private boolean captureActive;
    public void setCaptureActive(boolean active){captureActive=active;postInvalidate();}
    private boolean autoPlayArmed;
    public void setAutoPlayArmed(boolean armed){
        if(autoPlayArmed!=armed){
            autoPlayArmed=armed;
            shotTracker.setAlwaysBest(armed); // volatile flag: never wait for the planner thread here
            if(armed)lastPlanned=null; // re-plan the current board fully instead of reusing a held route
            postInvalidate();
        }
    }
    /** Receives drawing/planning failures (debug incidents); set by the overlay service. */
    public interface ErrorHook { void report(String where,Throwable t); }
    public static volatile ErrorHook errorHook;
    private String autoStatus="";
    private ShotPlanner.Shot goldRoute;
    /** Validated route at the current ★ spot, or null. */
    public ShotPlanner.Shot goldRoute(){return Float.isNaN(goldX)?null:goldRoute;}
    /** Current ranked alternatives (excluding the shown route), for the debug record. */
    public List<ShotPlanner.Shot> currentAlternatives(){PlanResult p=plan;return p==null?new java.util.ArrayList<>():new java.util.ArrayList<>(p.alternatives);}
    public float currentGoldX(){return goldX;}
    /** True when no ★ search is running for the current scene. */
    public boolean goldReady(){return plannerStopped||goldTask==null||goldTask.isDone();}
    private volatile ShotPlanner.Shot firingRoute;
    /** The route auto play is firing (null when none); drawn instead of the current-spot suggestion. */
    public void setFiringRoute(ShotPlanner.Shot r){firingRoute=r;postInvalidate();}
    // ── PC brain route (drawn without the phone's own detection, which pauses in PC mode) ──
    private volatile float[] remoteStriker;private volatile float[][] remoteLegs;private volatile float[] remotePocket;private volatile String remoteLabel;
    private Paint remotePaint;
    private volatile boolean remoteMode;
    /** PC brain connected: draw the PC's route (the phone's own detection is paused). */
    public void setRemoteMode(boolean on){if(remoteMode!=on){remoteMode=on;if(!on){remoteStriker=null;remoteLegs=null;remotePocket=null;remoteLabel=null;}postInvalidate();}}
    /** Keeps the label, removes the lines (they must not cover the game's aim dots during a shot). */
    public void hideRemoteLines(){remoteStriker=null;remoteLegs=null;remotePocket=null;postInvalidate();}
    public void setRemoteRoute(float[] striker,float[][] legs,float[] pocket,String label){remoteStriker=striker;remoteLegs=legs;remotePocket=pocket;remoteLabel=label;postInvalidate();}
    private void drawRemote(Canvas canvas){
        float[] st=remoteStriker;float[][] legs=remoteLegs;float[] pk=remotePocket;
        if(st==null&&legs==null)return;
        if(remotePaint==null){remotePaint=new Paint(Paint.ANTI_ALIAS_FLAG);remotePaint.setStyle(Paint.Style.STROKE);remotePaint.setStrokeCap(Paint.Cap.ROUND);}
        remotePaint.setStrokeWidth(3*dp);
        if(st!=null){remotePaint.setColor(0xE600E5FF);for(int i=0;i+3<st.length;i+=2)canvas.drawLine(st[i],st[i+1],st[i+2],st[i+3],remotePaint);}
        if(legs!=null){remotePaint.setColor(0xE622FF6E);for(float[] l:legs)if(l!=null&&l.length>=4)canvas.drawLine(l[0],l[1],l[2],l[3],remotePaint);}
        if(pk!=null){remotePaint.setColor(0xE622FF6E);canvas.drawCircle(pk[0],pk[1],18*dp,remotePaint);}
        String lb=remoteLabel;if(lb!=null&&!lb.isEmpty())canvas.drawText("PC • "+lb,24*dp,getHeight()-150*dp,textPaint);
    }
    public void setAutoStatus(String status){if(status!=null&&!status.equals(autoStatus)){autoStatus=status;postInvalidate();}}
    public void setPlayMode(boolean on) { playMode = on; postInvalidate(); }

    /** AUTO: best shot + gold spot computed automatically, no touching needed. */
    private boolean autoMode = false;
    public void setAutoMode(boolean on) {
        autoMode = on;
        if(!on)pickHint="Tap your coin once — picking ends when you lift your finger";
        else if(detected!=null&&detected.strikerDetected&&detected.teamKnown)requestPlan(detected);
        postInvalidate();
    }

    /**
     * New detection result. Positions are smoothed against the previous frame
     * so lines don't jump around, and frames with no striker are ignored.
     */
    public void setDetectedState(GameState s) {
        if (s == null || s.board == null) {
            missingStrikerFrames=0;
            detected = null;
            resetPlan();
            manualStriker = null;
            clearSnap();
            postInvalidate();
            return;
        }
        if (s.striker == null || !s.strikerDetected) {   // not your turn → hide auto lines
            if(++missingStrikerFrames>3)resetPlan();
            // 4.10.7: a single missed striker frame keeps the plan and the ★ (they blinked off and the ★ search restarted).
            else if(missingStrikerFrames>=2){cancelPlanning();goldSignature=0;goldX=Float.NaN;goldVisible=false;}
            manualStriker = null;
            clearSnap();
            this.detected = s;
            postInvalidate();
            return;
        }
        missingStrikerFrames=0;
        if (detected != null && detected.board != null && detected.striker != null
                && detected.strikerDetected) {
            final float a = 0.35f; // smoothing factor
            RectF ob = detected.board, nb = s.board;
            // ignore wild board jumps (bad frame)
            if (Math.abs(nb.width() - ob.width()) > ob.width() * 0.25f) return;
            float noise=ob.width()*.025f;
            if(Math.abs(nb.left-ob.left)<noise&&Math.abs(nb.top-ob.top)<noise
                    &&Math.abs(nb.right-ob.right)<noise&&Math.abs(nb.bottom-ob.bottom)<noise){
                nb.set(ob.left,ob.top,ob.right,ob.bottom);
                s.striker.radius=detected.striker.radius;
            }
            else nb.set(lerp(ob.left, nb.left, a), lerp(ob.top, nb.top, a),
                   lerp(ob.right, nb.right, a), lerp(ob.bottom, nb.bottom, a));
            PointF op = detected.striker.pos, np = s.striker.pos;
            if (dist(op.x, op.y, np.x, np.y) < s.striker.radius * 1.5f) {
                if (dist(op.x, op.y, np.x, np.y) < s.striker.radius * .12f) np.set(op.x, op.y);
                else np.set(lerp(op.x, np.x, .6f), lerp(op.y, np.y, .6f));
            } else if (!draggingStriker) {
                manualStriker = null; // striker really moved in the game → follow it
            }
            // smooth the detected pockets against the previous frame
            if (detected.pockets.size() == s.pockets.size()) {
                for (int i = 0; i < s.pockets.size(); i++) {
                    PointF o = detected.pockets.get(i), n = s.pockets.get(i);
                    if (dist(o.x,o.y,n.x,n.y)<s.board.width()*.01f) n.set(o.x,o.y);
                    else n.set(lerp(o.x,n.x,a),lerp(o.y,n.y,a));
                }
            }
            // Keep stationary piece centres fixed through small Hough/overlay jitter.
            for (Coin coin : s.coins) {
                Coin nearest = null; float dmin = coin.radius * 0.25f;
                for (Coin old : detected.coins) {
                    float d = dist(coin.pos.x, coin.pos.y, old.pos.x, old.pos.y);
                    if (d < dmin && coin.targetEligible == old.targetEligible) { dmin=d; nearest=old; }
                }
                if (nearest != null){coin.pos.set(nearest.pos.x, nearest.pos.y);coin.radius=nearest.radius;}
            }
        }
        this.detected = s;
        if (snapCoin != null && !draggingStriker) {
            Coin updated = findNearestCoin(s.coins, snapCoin.pos.x, snapCoin.pos.y);
            if (updated != null && dist(updated.pos.x, updated.pos.y,
                    snapCoin.pos.x, snapCoin.pos.y) < snapCoin.radius) {
                snapCoin = updated;
                reSnap(s);
            } else {
                clearSnap();
            }
        }
        // From here the state is immutable: the planner thread may read it concurrently.
        if (autoMode && s.teamKnown) requestPlan(s);
        postInvalidate();
    }

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    private void clearSnap() { snapCoin = null; snapPocket = null; snapHit = null; targetPos = null; }

    // ── Touch ─────────────────────────────────────────────────────────────────

    public interface PickListener { void onPicked(boolean selected); }
    public interface PlanListener { void onPlan(GameState state,ShotPlanner.Shot route,float suggestedX,GameState planned); }
    private PlanListener planListener;
    public void setPlanListener(PlanListener listener){planListener=listener;}
    private PickListener onCoinPicked;
    private float pickX,pickY;
    private boolean pickTap;
    private String pickHint = "Tap one of your coins — then play normally";
    public void setOnCoinPicked(PickListener callback) { onCoinPicked=callback; }
    public String getPickResult() { return pickResult; }
    private String pickResult="Play restored";
    /** Cycle to the next verified route on the planner thread; the label arrives asynchronously. */
    public void cycleRoute(RouteLabelListener listener) {
        final GameState state=detected;
        if(plannerStopped||state==null){if(listener!=null)listener.onLabel(RoutePlanner.label(null));return;}
        cancelPlanning();
        planTask=planExecutor.submit(() -> {
            PlanResult result=new PlanResult();result.state=state;
            lastPlanned=null;
            try{
                synchronized(shotTracker){result.shot=shotTracker.next(state);result.selected=shotTracker.selectedTarget();result.position=shotTracker.routePosition();result.alternatives=shotTracker.alternatives();}
                result.label=RoutePlanner.label(result.shot)+result.position;
            }catch(Throwable t){android.util.Log.w("BitAim/Plan","Route cycle failed: "+t);result.shot=null;result.label=RoutePlanner.label(null);}
            final PlanResult done=result;
            post(() -> {if(plannerStopped)return;applyPlan(done);if(listener!=null)listener.onLabel(done.label);});
        });
    }
    public interface RouteLabelListener { void onLabel(String label); }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (autoMode) return false;
        switch(event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                pickX=event.getX();pickY=event.getY();pickTap=true;return true;
            case MotionEvent.ACTION_MOVE:
                if(dist(pickX,pickY,event.getX(),event.getY())>8*dp)pickTap=false;
                return true;
            case MotionEvent.ACTION_UP:
                boolean selected=false;
                pickResult=detected==null?missingBoardHint()
                        :!detected.teamKnown?"Team badges hidden — cannot identify your colour"
                        :"No own coin selected — play restored";
                if(pickTap && detected!=null && detected.teamKnown){
                    Coin coin=findNearestCoin(detected.coins,event.getX(),event.getY());
                    if(coin!=null && coin.targetEligible){
                        cancelPlanning();
                        synchronized(shotTracker){shotTracker.lock(coin);}
                        plan=null;autoShot=null;autoState=null;lastPlanned=null;
                        selected=true;
                        pickResult="Coin selected — play normally. Hold icon: next route.";
                        if(detected.strikerDetected)requestPlan(detected);
                    }else{pickHint="Choose your colour, not the opponent's";postInvalidate();}
                }
                // Always leave the one-shot picker. Never trap subsequent drags.
                final boolean picked=selected;
                if(onCoinPicked!=null)post(() -> onCoinPicked.onPicked(picked));
                return true;
            case MotionEvent.ACTION_CANCEL:
                pickTap=false;
                if(onCoinPicked!=null)post(() -> onCoinPicked.onPicked(false));
                return true;
            default:return true;
        }
    }

    /** Use the detected coin under the finger, or — if detection missed it —
     *  treat the tapped spot itself as the coin. */
    private void selectCoinAt(GameState s, PointF strikerPos, float x, float y) {
        if (s == null || strikerPos == null) return;
        float sr = s.striker != null ? s.striker.radius : 18 * dp;
        Coin c = findNearestCoin(s.coins, x, y);
        if (c == null) { clearSnap(); return; }
        applySnap(c, s.pockets, strikerPos, sr);
    }

    private void reSnap(GameState s) {
        PointF sp = effectiveStriker(s);
        if (s == null || sp == null || snapCoin == null) return;
        applySnap(snapCoin, s.pockets, sp, s.striker != null ? s.striker.radius : 18 * dp);
    }

    private float typicalCoinRadius(GameState s, float strikerR) {
        if (s.coins != null && !s.coins.isEmpty()) {
            float sum = 0; for (Coin c : s.coins) sum += c.radius;
            return sum / s.coins.size();
        }
        return strikerR * 0.8f;
    }

    /**
     * Find the coin whose center is within 2.5× its radius of the touch point.
     * Returns null if none is close enough.
     */
    private Coin findNearestCoin(List<Coin> coins, float tx, float ty) {
        Coin best = null;
        float bestDist = Float.MAX_VALUE;
        for (Coin c : coins) {
            float d = dist(tx, ty, c.pos.x, c.pos.y);
            float threshold = c.radius * 1.35f;
            if (d < threshold && d < bestDist) {
                bestDist = d;
                best = c;
            }
        }
        return best;
    }

    /** Select a physically reachable straight shot for the requested coin. */
    private void applySnap(Coin coin, List<PointF> pockets, PointF strikerPos, float strikerR) {
        GameState state = currentState();
        clearSnap();
        if (state == null || pockets == null || pockets.isEmpty()) return;
        ShotPlanner.Shot shot = ShotPlanner.bestForCoin(strikerPos.x, strikerPos.y,
                strikerR, coin, state.coins, pockets, state.board);
        snapCoin = coin;
        if (shot == null) return;
        snapPocket = shot.pocket;
        snapHit = shot.ghost;
    }

    // ── Draw ──────────────────────────────────────────────────────────────────

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        try {
            if(autoPlayArmed)canvas.drawText("AUTO • "+(autoStatus.isEmpty()?"armed":autoStatus)+" • tap ★ to stop",24*dp,65*dp,textPaint);
            if(remoteMode){drawRemote(canvas);return;}
            if(autoMode){drawAuto(canvas,detected);return;}
            drawHint(canvas,detected==null?missingBoardHint()
                    :!detected.teamKnown?"Team badges hidden — tap icon to return to play":pickHint);
        } catch (Throwable t) {
            // The overlay must never take the game down: log and show nothing for this frame.
            android.util.Log.w("BitAim/Overlay", "Draw failed: " + t);
            ErrorHook hook=errorHook;if(hook!=null)hook.report("draw",t);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Hints go near the bottom so they never cover the game's top bar. */
    private void drawHint(Canvas canvas, String msg) {
        canvas.drawText(msg, 24 * dp, getHeight() - 150 * dp, textPaint);
    }

    private String missingBoardHint() {
        return captureActive?"Board obscured or not visible — waiting":"Enable Auto-Detect • Entire screen";
    }

    // ── AUTO mode ─────────────────────────────────────────────────────────────
    /** Immutable result of one planner pass, applied on the UI thread. */
    private static final class PlanResult {
        GameState state; ShotPlanner.Shot shot; Coin selected; String position=""; String label;
        List<ShotPlanner.Shot> alternatives=new java.util.ArrayList<>();
    }
    private GameState autoState;
    private ShotPlanner.Shot autoShot;
    private PlanResult plan;
    private final ShotTracker shotTracker = new ShotTracker();
    private final AimAlignment aimAlignment=new AimAlignment();
    private float goldX = Float.NaN, goldScore;
    private boolean goldVisible;
    private int goldSignature;
    private final GoldSceneLock goldSceneLock=new GoldSceneLock();
    private final ExecutorService planExecutor=Executors.newSingleThreadExecutor(r -> {Thread t=new Thread(r,"BitAim-Plan");t.setDaemon(true);t.setPriority(Thread.NORM_PRIORITY-1);return t;});
    private final ExecutorService goldExecutor=Executors.newSingleThreadExecutor(r -> {Thread t=new Thread(r,"BitAim-Gold");t.setDaemon(true);t.setPriority(Thread.MIN_PRIORITY+1);return t;});
    private final AtomicReference<GameState> pendingPlan=new AtomicReference<>();
    private volatile PlanResult lastPlanned;
    private Future<?> planTask, goldTask;
    private boolean plannerStopped;
    private long planStartedMs,planAppliedMs;

    /** Forget the current turn's plan: tracker, gold search and displayed route. */
    private void resetPlan(){
        cancelPlanning();
        synchronized(shotTracker){shotTracker.clear();}
        plan=null;autoShot=null;autoState=null;lastPlanned=null;goldSignature=0;goldX=Float.NaN;goldVisible=false;
    }
    private void cancelPlanning(){
        pendingPlan.set(null);
        if(planTask!=null)planTask.cancel(true);
        if(goldTask!=null)goldTask.cancel(true);
    }

    /** Queue the newest frame; the planner thread always works on the latest one only. */
    private void requestPlan(GameState s){
        if(plannerStopped)return;
        pendingPlan.set(s);
        if(planTask!=null&&!planTask.isDone())return; // the running pass picks up the newest frame when it finishes
        planStartedMs=android.os.SystemClock.uptimeMillis();
        planTask=planExecutor.submit(this::planLoop);
    }
    private void planLoop(){
        GameState s;
        while((s=pendingPlan.getAndSet(null))!=null&&!plannerStopped&&!Thread.currentThread().isInterrupted()){
            PlanResult result=new PlanResult();result.state=s;
            PlanResult last=lastPlanned;
            if(last!=null&&sameScene(last.state,s)){
                // Unchanged board and striker: re-planning would only burn CPU and battery.
                result.shot=last.shot;result.selected=last.selected;result.position=last.position;result.alternatives=last.alternatives;
                final PlanResult done=result;
                post(() -> {if(!plannerStopped)applyPlan(done);});
                continue;
            }
            try{
                synchronized(shotTracker){
                    result.shot=shotTracker.update(s);
                    result.selected=shotTracker.selectedTarget();
                    result.position=shotTracker.routePosition();
                    result.alternatives=shotTracker.alternatives();
                }
                Coin goldTarget=result.selected;
                if(goldTarget==null&&result.shot!=null)goldTarget=result.shot.coin;
                int signature=goldSceneLock.update(s,goldTarget);
                lastPlanned=result;
                final PlanResult done=result;final int sig=signature;final Coin target=goldTarget;final GameState frame=s;
                post(() -> {
                    if(plannerStopped)return;
                    applyPlan(done);
                    if(sig!=goldSignature){goldSignature=sig;requestGold(frame,target,sig);}
                });
            }catch(Throwable t){
                android.util.Log.w("BitAim/Plan","Planner failed: "+t);
                ErrorHook hook=errorHook;if(hook!=null)hook.report("planner",t);
                final PlanResult done=result;done.shot=null;
                post(() -> {if(!plannerStopped)applyPlan(done);});
            }
        }
    }
    /** UI thread: show a plan only if it belongs to the frame currently displayed. */
    private void applyPlan(PlanResult result){
        GameState now=detected;
        // Capture delivers a new frame every 100 ms while a dense plan takes longer:
        // accept the plan whenever the board on screen is still the one it was planned for.
        if(result.state!=now&&!stillClose(result.state,now))return;
        plan=result;autoShot=result.shot;autoState=now;planAppliedMs=android.os.SystemClock.uptimeMillis();
        postInvalidate();
    }
    /** Strict identity for skipping a re-plan: striker essentially still and every disc matched in place. */
    private static boolean sameScene(GameState a,GameState b){
        if(a==null||b==null||a.striker==null||b.striker==null||a.board==null||b.board==null)return false;
        if(a.targetColourKey!=b.targetColourKey||a.queenRequired!=b.queenRequired)return false;
        if(dist(a.striker.pos.x,a.striker.pos.y,b.striker.pos.x,b.striker.pos.y)>b.striker.radius*.12f)return false;
        return discsSimilar(a,b,.3f);
    }

    // 4.10.7 (user: lines blink): the last frame that had a board and our striker, kept briefly for drawing.
    private GameState lastGoodState;private long lastGoodMs;
    private static final long HOLD_LINES_MS=700;
    private void drawAuto(Canvas canvas, GameState s) {
        long nowMs=android.os.SystemClock.uptimeMillis();
        boolean good=s!=null&&s.board!=null&&s.striker!=null&&s.strikerDetected;
        if(good){lastGoodState=s;lastGoodMs=nowMs;}
        else if(lastGoodState!=null&&nowMs-lastGoodMs<HOLD_LINES_MS&&(s==null||s.board==null||sameDiscs(lastGoodState,s))){
            // One or two frames without the board or striker (finger, glow, animation): redraw the last good frame's
            // lines instead of blanking them. Auto play ignores it (same frame id).
            s=lastGoodState;
        }
        if (s == null || s.board == null) {
            aimAlignment.update(null,null);
            if(planListener!=null)planListener.onPlan(null,null,Float.NaN,null);
            drawHint(canvas,missingBoardHint());return;
        }
        if (s.striker == null || !s.strikerDetected) {
            aimAlignment.update(null,null);
            if(planListener!=null)planListener.onPlan(s,null,Float.NaN,null);return;
        }
        if (!s.teamKnown) {
            aimAlignment.update(null,null);
            if(planListener!=null)planListener.onPlan(s,null,Float.NaN,null);
            drawHint(canvas,s.openingCluster?"Opening cluster — waiting for team colour":"Your colour badge is hidden — waiting");
            return;
        }
        float sx = s.striker.pos.x, sy = s.striker.pos.y, sr = s.striker.radius;
        boolean provisional=false;
        if (autoState != s) {
            // No plan for this frame yet. Keep the previous lines while the striker is still, and
            // for a short while during a slide (same discs), so the guide never blinks out.
            boolean recent=plan!=null&&android.os.SystemClock.uptimeMillis()-planAppliedMs<2000&&sameDiscs(plan.state,s); // 4.10.7: 1.2 s -> 2 s
            provisional=plan!=null&&!stillClose(plan.state,s)&&recent;
            if (plan == null || !stillClose(plan.state, s) && !recent) {
                aimAlignment.update(null,null);
                if(planListener!=null)planListener.onPlan(s,null,Float.NaN,null);
                if(android.os.SystemClock.uptimeMillis()-planStartedMs>400)drawHint(canvas,"Planning…");
                return;
            }
        }

        // ⭐ Gold spot: best place for the striker on your baseline
        float cur = autoShot != null ? autoShot.quality() : -1e9f;
        goldVisible = !Float.isNaN(goldX) && Math.abs(goldX-sx)>sr*.6f
                && goldScore>cur+(goldVisible?5f:8f);
        // Auto play takes the best simulated baseline spot whenever it is better at all (the on-screen ★ keeps
        // its stricter margin so the hint does not flicker): slide there, wait for a steady board, then shoot.
        // Compare with the production ranking (a real pot beats a chain-reaction gamble), not raw score.
        boolean goldBetter=goldRoute!=null?autoShot==null||RoutePlanner.compare(goldRoute,autoShot)<0:goldScore>=cur;
        float autoGold=!Float.isNaN(goldX)&&Math.abs(goldX-sx)>sr*.6f&&goldBetter?goldX:Float.NaN;
        if(planListener!=null)planListener.onPlan(s,autoShot,autoGold,plan==null?s:plan.state);
        // Auto play: draw the route it will actually fire (user 00:30: "it shows one line but hits another"). That is
        // the shot being fired during the hold, else the ★ route once the striker stands on the ★.
        ShotPlanner.Shot shown=autoShot;
        if(autoPlayArmed){
            ShotPlanner.Shot fire=firingRoute!=null?firingRoute:goldRoute;
            if(fire!=null&&fire.strikerPath.size()>1&&fire.ghost!=null&&Math.abs(fire.strikerPath.get(0).x-sx)<sr*1.2f)shown=fire;
        }
        if (goldVisible) {
            canvas.drawCircle(goldX, sy, sr, goldPaint);
            canvas.drawLine(sx, sy, goldX - Math.signum(goldX - sx) * sr, sy, goldDashPaint);
            canvas.drawText("★", goldX - 6 * dp, sy - sr - 6 * dp, goldTextPaint);
        }

        if (shown == null) {
            aimAlignment.update(null,null);
            Coin selected=plan==null?null:plan.selected;
            Coin currentSelected=selected==null?null:findNearestCoin(s.coins,selected.pos.x,selected.pos.y);
            if(currentSelected!=null&&currentSelected.targetEligible&&dist(currentSelected.pos.x,currentSelected.pos.y,selected.pos.x,selected.pos.y)<currentSelected.radius*.75f)
                canvas.drawCircle(currentSelected.pos.x,currentSelected.pos.y,currentSelected.radius+3*dp,snapCoinRingPaint);
            String prefix=s.targetColourKey==TargetColourLock.AUTO?"":TargetColourLock.label(s.targetColourKey)+" LOCK • ";
            if(s.queenRequired){drawHint(canvas,"QUEEN FIRST • last own coin held");return;}
            if(s.openingCluster&&Float.isNaN(goldX)){
                drawHint(canvas,"Opening cluster — no verified pot yet");return;
            }
            drawHint(canvas, prefix+(Float.isNaN(goldX) ? "No verified route — move striker or choose a coin" : "Slide striker to ★ for a verified route"));
            return;
        }
        if(!provisional&&plan!=null)drawAlternatives(canvas,plan.alternatives,sr);
        aimAlignment.update(s,shown);
        for(int i=0;i<shown.strikerPath.size()-1;i++){
            PointF a=shown.strikerPath.get(i),b=shown.strikerPath.get(i+1);
            canvas.drawLine(a.x,a.y,b.x,b.y,aimAlignment.aligned()?snapLinePaint:aimLinePaint);
        }
        if(shown.kind==1){PointF bounce=shown.strikerPath.get(1);canvas.drawCircle(bounce.x,bounce.y,sr,ghostPaint);}
        canvas.drawCircle(shown.ghost.x,shown.ghost.y,sr,aimAlignment.aligned()?snapCoinRingPaint:ghostPaint);
        String position=plan==null?"":plan.position;
        if(shown.kind==4){
            canvas.drawCircle(shown.coin.pos.x,shown.coin.pos.y,shown.coin.radius+3*dp,coinPathPaint);
            drawHint(canvas,"OPEN CLUSTER • "+aimAlignment.label());
            canvas.drawText("Model spread: "+shown.clusterMoved+" coins • manual power",24*dp,getHeight()-130*dp,smallTextPaint);
            canvas.drawText("Hold star: colour lock / next route"+position,24*dp,getHeight()-110*dp,smallTextPaint);
            return;
        }
        if(shown.kind==5){
            // Positional shot: first contact ring, predicted travel of that coin, no pocket promise.
            Coin first=shown.chain.isEmpty()?shown.coin:shown.chain.get(0);
            canvas.drawCircle(first.pos.x,first.pos.y,first.radius+3*dp,coinPathPaint);
            PointF end=shown.coinEndpoints.get(0);
            canvas.drawLine(first.pos.x,first.pos.y,end.x,end.y,coinPathPaint);
            canvas.drawCircle(end.x,end.y,first.radius,ghostPaint);
            String tolerance=RoutePlanner.toleranceLabel(shown);
            drawHint(canvas,(shown.safeBreakOnly?"SAFE BREAK • ":"OPEN PATHS • ")+aimAlignment.label()+(tolerance.isEmpty()?"":" • "+tolerance));
            int opened=Math.max(0,shown.pathsAfter-shown.pathsBefore);
            canvas.drawText(RoutePlanner.label(shown)+position+" • "+shown.pathsBefore+" → "+shown.pathsAfter+" pocket paths • "+shown.clusterMoved+" coins move",24*dp,getHeight()-130*dp,smallTextPaint);
            canvas.drawText((opened>0?"Positional shot: medium power, then pot next turn":"Positional shot")+" • hold star for next route",24*dp,getHeight()-110*dp,smallTextPaint);
            return;
        }
        for(int i=0;i<shown.chain.size();i++){
            Coin coin=shown.chain.get(i);PointF end=shown.coinEndpoints.get(i);
            boolean last=i==shown.chain.size()-1;
            boolean robust=shown.robustnessPassed>=7;
            canvas.drawLine(coin.pos.x,coin.pos.y,end.x,end.y,last&&robust?snapLinePaint:coinPathPaint);
            canvas.drawCircle(coin.pos.x,coin.pos.y,coin.radius+3*dp,last&&robust?snapCoinRingPaint:coinPathPaint);
        }
        if(shown.targetBounce!=null){
            canvas.drawLine(shown.targetBounce.x,shown.targetBounce.y,shown.pocket.x,shown.pocket.y,shown.robustnessPassed>=7?snapLinePaint:coinPathPaint);
            canvas.drawCircle(shown.targetBounce.x,shown.targetBounce.y,shown.coin.radius,ghostPaint);
        }
        canvas.drawCircle(shown.pocket.x,shown.pocket.y,14*dp,pocketRingPaint);
        int modelScore=Math.round(shown.robustnessPassed*100f/Math.max(1,shown.robustnessTrials));
        String tolerance=RoutePlanner.toleranceLabel(shown);
        drawHint(canvas,(s.queenRequired?"QUEEN FIRST • ":"")+aimAlignment.label()+(tolerance.isEmpty()?"":" • "+tolerance));
        String power=shown.assumedLaunchSpeed>0&&shown.assumedLaunchSpeed<s.board.width()*1.6f?" • SOFT touch":shown.assumedLaunchSpeed>s.board.width()*2.2f?" • HARD":"";
        String odds=Float.isNaN(shown.chancePercent)?"Model "+modelScore+"%":"Chance ~"+Math.round(shown.chancePercent)+"%";
        canvas.drawText(RoutePlanner.label(shown)+position+" • "+odds+power,24*dp,getHeight()-130*dp,smallTextPaint);
        int altCount=plan==null?0:Math.min(2,plan.alternatives.size());
        String extra=s.queenRequired?"Manual queen shot • last own coin held":altCount>0&&!shown.productivePot&&s.targetColourKey==TargetColourLock.AUTO?"Faint lines: "+altCount+" other option"+(altCount>1?"s":"")+" • hold star: next route":shown.productivePot?"Pot + open: "+shown.otherCoinsMoved+" other coins • model":s.targetColourKey!=TargetColourLock.AUTO?TargetColourLock.label(s.targetColourKey)+" LOCK • hold star to change":"Hold star: colour lock / next route";
        canvas.drawText(extra,24*dp,getHeight()-110*dp,smallTextPaint);
    }

    /** Up to two ranked alternatives, thin and faint, numbered 2 and 3 like map alternatives. */
    private void drawAlternatives(Canvas canvas,List<ShotPlanner.Shot> alternatives,float sr){
        int shown=0;
        for(ShotPlanner.Shot alt:alternatives){
            if(shown>=2)break;
            if(alt.kind==4||alt.strikerPath.size()<2)continue;
            for(int i=0;i<alt.strikerPath.size()-1;i++){
                PointF a=alt.strikerPath.get(i),b=alt.strikerPath.get(i+1);
                canvas.drawLine(a.x,a.y,b.x,b.y,altStrikerPaint);
            }
            canvas.drawCircle(alt.ghost.x,alt.ghost.y,sr,altStrikerPaint);
            for(int i=0;i<alt.chain.size()&&i<alt.coinEndpoints.size();i++){
                Coin c=alt.chain.get(i);PointF end=alt.coinEndpoints.get(i);
                canvas.drawLine(c.pos.x,c.pos.y,end.x,end.y,altCoinPaint);
            }
            if(alt.targetBounce!=null)canvas.drawLine(alt.targetBounce.x,alt.targetBounce.y,alt.pocket.x,alt.pocket.y,altCoinPaint);
            shown++;
            canvas.drawText(String.valueOf(shown+1),alt.ghost.x+sr+3*dp,alt.ghost.y-sr,altTextPaint);
        }
    }
    /** Same disc layout regardless of the striker: used to keep lines during a slide. */
    private static boolean sameDiscs(GameState a,GameState b){
        if(a==null||b==null)return false;
        return discsSimilar(a,b,.5f);
    }
    /** Disc layouts match despite detection flicker in busy clusters: at most two discs (or 15%) may differ.
     *  Live frames showed one or two discs blinking in and out, which made the lines drop and re-plan. */
    private static boolean discsSimilar(GameState a,GameState b,float tolerance){
        int n=b.coins.size(),allowed=Math.max(2,Math.round(n*.15f));
        if(Math.abs(a.coins.size()-n)>allowed)return false;
        int unmatched=0;
        for(Coin c:b.coins){
            boolean found=false;
            for(Coin o:a.coins)if(dist(o.pos.x,o.pos.y,c.pos.x,c.pos.y)<c.radius*tolerance){found=true;break;}
            if(!found&&++unmatched>allowed)return false;
        }
        return true;
    }

    /** Same board and a striker that has barely moved: the previous plan is still worth showing. */
    private static boolean stillClose(GameState a,GameState b){
        if(a==null||b==null||a.striker==null||b.striker==null||a.board==null||b.board==null)return false;
        if(dist(a.striker.pos.x,a.striker.pos.y,b.striker.pos.x,b.striker.pos.y)>=b.striker.radius*.5f
                ||Math.abs(a.board.width()-b.board.width())>=b.board.width()*.02f)return false;
        return discsSimilar(a,b,.5f);
    }

    private void requestGold(GameState source,Coin selected,int signature){
        if(plannerStopped)return;
        if(goldTask!=null)goldTask.cancel(true);
        goldX=Float.NaN;goldVisible=false;goldRoute=null;
        GameState copy=new GameState();copy.teamKnown=source.teamKnown;copy.freestyle=source.freestyle;copy.openingCluster=source.openingCluster;copy.strikerDetected=source.strikerDetected;
        copy.assignedColours=source.assignedColours;copy.queenRequired=source.queenRequired;copy.targetColourKey=source.targetColourKey;
        copy.board=new RectF(source.board.left,source.board.top,source.board.right,source.board.bottom);
        copy.striker=new Coin(source.striker.pos.x,source.striker.pos.y,source.striker.radius,3,true);
        Coin copiedTarget=null;
        for(Coin c:source.coins){Coin cloned=new Coin(c.pos.x,c.pos.y,c.radius,c.color,false);cloned.targetEligible=c.targetEligible;cloned.transferEligible=c.transferEligible;cloned.isQueen=c.isQueen;cloned.appearanceKey=c.appearanceKey;copy.coins.add(cloned);if(c==selected)copiedTarget=cloned;}
        for(PointF p:source.pockets)copy.pockets.add(new PointF(p.x,p.y));
        final Coin target=copiedTarget;
        goldTask=goldExecutor.submit(() -> {
            try{
                float[] score=new float[1];ShotPlanner.Shot[] shot=new ShotPlanner.Shot[1];float x=RoutePlanner.goldX(copy,target,score,shot);
                if(Thread.currentThread().isInterrupted())return;
                post(() -> {if(!plannerStopped&&goldSignature==signature){goldX=x;goldScore=score[0];goldRoute=shot[0];postInvalidate();}});
            }catch(Throwable t){android.util.Log.w("BitAim/Plan","Gold search failed: "+t);}
        });
    }

    @Override protected void onDetachedFromWindow(){
        plannerStopped=true;cancelPlanning();planExecutor.shutdownNow();goldExecutor.shutdownNow();
        super.onDetachedFromWindow();
    }

    private GameState currentState() { return detected; }

    private PointF effectiveStriker(GameState s) {
        if (manualStriker != null) return manualStriker;
        if (s != null && s.striker != null) return s.striker.pos;
        return null;
    }

    private static float dist(float x1, float y1, float x2, float y2) {
        float dx = x2-x1, dy = y2-y1;
        return (float) Math.sqrt(dx*dx + dy*dy);
    }

    private Paint stroke(int color, float w) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color); p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(w*dp); p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        return p;
    }

    private Paint fill(int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color); p.setStyle(Paint.Style.FILL);
        return p;
    }
}
