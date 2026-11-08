package com.bitaim.carromaim.cv;

import android.graphics.PointF;
import android.graphics.RectF;

import java.util.List;
import java.util.ArrayList;

/**
 * ShotPlanner — picks the best straight shot and the best striker spot.
 *
 * For every coin C and pocket P:
 *   direction the coin must travel  d = normalize(P − C)
 *   striker centre at impact ("ghost") G = C − d · (rCoin + rStriker)
 *   the shot is possible when
 *     • striker path S→G is not blocked by another coin,
 *     • coin path C→P is not blocked by another coin,
 *     • the cut angle between S→G and d is not too thin,
 *     • G lies inside the board.
 *   score = straighter cut + shorter distance.
 *
 * The "gold spot" is the striker position along the baseline whose best shot
 * scores highest.
 */
public class ShotPlanner {

    public static class Shot {
        public Coin   coin;
        public PointF pocket;
        public PointF ghost;   // striker centre at impact
        public float  score;
        public int kind = 0; // 0 direct, 1 striker cushion, 2 combination, 3 coin cushion, 4 cluster opening, 5 path opener
        public int clusterMoved;
        /** Nominal settled displacement/opening metrics; final target is excluded. */
        public int otherCoinsMoved,closePairsOpened;
        public boolean productivePot;
        public int productiveStressPassed;
        public PointF targetBounce;
        public int robustnessPassed, robustnessTrials;
        public int wall = -1;
        public List<Coin> chain = new ArrayList<>();
        public List<PointF> strikerPath = new ArrayList<>();
        public List<PointF> coinEndpoints = new ArrayList<>();
        public boolean modelValidated;
        public float assumedLaunchSpeed;
        public long worldSignature;
        /** Largest tested aim error (degrees, either side) that still pots in the nominal model; NaN until verified. */
        public float aimMarginDegrees = Float.NaN;
        /** Realistic pot chance 0-100 from PhysicsVerifier.chance; NaN when not estimated. */
        public float chancePercent = Float.NaN;
        /** 4.9.3: the struck or target disc touches another disc (gap under 8% of the contact distance). */
        public boolean touchingPair;
        /** Softest launch speed (px/s) that still pots in the nominal model; NaN when unknown. Auto play aims a
         *  margin above it so the coin drops instead of rattling out of the pocket at full power. */
        public float minPotSpeed = Float.NaN;
        /** Direct (own coin, pocket) paths from the baseline before and after the settled nominal shot; own coins potted by an opener. */
        public int pathsBefore,pathsAfter,ownPotted;
        /** Cut offset used by a kind-5 opener, as a fraction of the contact radius. */
        public float aimOffset;
        /** Kind 5 fallback: a safe spread that opens no pot path yet. */
        public boolean safeBreakOnly;
        /** Found by the brute-force aim sweep (SweepPlanner), not the geometric ghost-ball builder. */
        public boolean sweep;
        /** Sweep shot whose striker bounces off a rail before the first disc (risky: rail model). */
        public boolean railFirst;
        /** Ranking value: geometric score plus a bonus for aim tolerance (1 degree is worth 30 points, capped at 4). */
        public float quality(){return score+(Float.isNaN(aimMarginDegrees)?0:Math.min(4f,aimMarginDegrees)*30f);}
    }

    private static final float MIN_COS = 0.60f; // conservative cuts: at most ~53 degrees

    /** Best shot from the given striker position, or null if none is clear. */
    public static Shot best(float sx, float sy, float sr, List<Coin> coins,
                            List<PointF> pockets, RectF board) {
        if (coins == null || pockets == null || board == null) return null;
        Shot best = null;
        for (Coin c : coins) {
            if (!c.targetEligible) continue;
            for (PointF p : pockets) {
                Shot s = evaluate(sx, sy, sr, c, p, coins, board);
                if (s != null && (best == null || s.score > best.score)) best = s;
            }
        }
        return best;
    }

    /** Shot at a specific coin (user tapped it): best pocket for that coin. */
    public static Shot bestForCoin(float sx, float sy, float sr, Coin c, List<Coin> coins,
                                   List<PointF> pockets, RectF board) {
        if (c == null || !c.targetEligible || coins == null || pockets == null || board == null) return null;
        Shot best = null;
        for (PointF p : pockets) {
            Shot s = evaluate(sx, sy, sr, c, p, coins, board);
            if (s != null && (best == null || s.score > best.score)) best = s;
        }
        return best;
    }

    public static Shot forPocket(float sx,float sy,float sr,Coin coin,PointF pocket,
                                  List<Coin> coins,RectF board){
        return coin==null || !coin.targetEligible ? null : evaluate(sx,sy,sr,coin,pocket,coins,board);
    }

    /** Best striker x on the baseline (same y), or NaN if no shot exists. */
    public static float goldSpotX(float sy, float sr, List<Coin> coins,
                                  List<PointF> pockets, RectF board, float[] outScore) {
        float w = board.width();
        float minX = board.left + w * 0.21f, maxX = board.right - w * 0.21f;
        float bestX = Float.NaN, bestScore = -1e9f;
        int steps = 40;
        for (int i = 0; i <= steps; i++) {
            float x = minX + (maxX - minX) * i / steps;
            Shot s = best(x, sy, sr, coins, pockets, board);
            if (s != null && s.score > bestScore) { bestScore = s.score; bestX = x; }
        }
        if (outScore != null && outScore.length > 0) outScore[0] = bestScore;
        return bestX;
    }

    private static Shot evaluate(float sx, float sy, float sr, Coin c, PointF p,
                                 List<Coin> coins, RectF board) {
        float cr = c.radius;
        float dx = p.x - c.pos.x, dy = p.y - c.pos.y;
        float cpLen = len(dx, dy);
        if (cpLen < 1) return null;
        dx /= cpLen; dy /= cpLen;
        float gx = c.pos.x - dx * (cr + sr), gy = c.pos.y - dy * (cr + sr);
        if (gx < board.left + sr * 0.5f || gx > board.right - sr * 0.5f
                || gy < board.top + sr * 0.5f || gy > board.bottom - sr * 0.5f) return null;

        float ax = gx - sx, ay = gy - sy;
        float sgLen = len(ax, ay);
        if (sgLen < sr) return null;
        float cos = (ax * dx + ay * dy) / sgLen;
        if (cos < MIN_COS) return null;

        for (Coin o : coins) {
            if (o == c) continue;
            // striker path must clear other coins
            float margin = board.width() * 0.003f;
            if (segDist(o.pos.x, o.pos.y, sx, sy, gx, gy) < o.radius + sr + margin) return null;
            // coin path must clear other coins
            if (segDist(o.pos.x, o.pos.y, c.pos.x, c.pos.y, p.x, p.y) < o.radius + cr + margin) return null;
        }
        Shot s = new Shot();
        s.coin = c; s.pocket = p; s.ghost = new PointF(gx, gy);
        float w = board.width();
        s.score = cos * cos * 100f - (sgLen + cpLen) / w * 12f;
        return s;
    }

    private static float len(float x, float y) { return (float) Math.sqrt(x * x + y * y); }

    /** Distance from point (px,py) to segment A→B. */
    private static float segDist(float px, float py, float ax, float ay, float bx, float by) {
        float vx = bx - ax, vy = by - ay;
        float l2 = vx * vx + vy * vy;
        float t = l2 > 0 ? ((px - ax) * vx + (py - ay) * vy) / l2 : 0;
        t = Math.max(0, Math.min(1, t));
        float cx = ax + t * vx, cy = ay + t * vy;
        return len(px - cx, py - cy);
    }
}
