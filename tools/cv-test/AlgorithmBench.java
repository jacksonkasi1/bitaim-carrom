import com.bitaim.carromaim.cv.*;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import android.graphics.PointF;
import java.io.*;
import java.util.*;

/**
 * Exhaustive desktop benchmark of the production planner on real screenshots.
 *
 * For every screenshot with a detected local striker it:
 *   1. enumerates EVERY geometric candidate route (not the bounded per-kind cap used on the phone),
 *   2. validates each with the production physics stress set,
 *   3. measures each validated route's aim-angle tolerance window (how many degrees of aim error
 *      still pot in the nominal model) and its launch-speed window,
 *   4. sweeps all 41 baseline striker positions and records the best route/tolerance per position,
 *   5. compares the production ranking (RoutePlanner.routes / goldX) with a tolerance-first ranking.
 *
 * Outputs: routes.csv, positions.csv, summary.md and annotated PNGs in the chosen out directory.
 * Model-only evidence: nothing here measures the real game engine.
 */
public class AlgorithmBench {
    static final double WALL = 0.90;
    static final float AIM_STEP = 0.25f, AIM_MAX = 8f;
    static final float[] SPEED_FACTORS = {.6f,.7f,.8f,.9f,1f,1.1f,1.2f,1.3f,1.4f,1.5f,1.6f,1.8f,2f};
    static long sims;

    static class Row {
        ShotPlanner.Shot shot; int prodRank=-1; float neg,pos; int speedPasses;
        float margin(){return Math.min(neg,pos);}
    }

    public static void main(String[] args) throws Exception {
        nu.pattern.OpenCV.loadLocally();
        String outDir = System.getProperty("bench.out", "out/bench");
        new File(outDir).mkdirs();
        PrintWriter routes = new PrintWriter(new FileWriter(outDir + "/routes.csv"));
        routes.println("image,prodRank,kind,label,chainLen,wall,score,stressPassed,productive,speedBoards,aimNeg,aimPos,aimMargin,speedPasses");
        PrintWriter positions = new PrintWriter(new FileWriter(outDir + "/positions.csv"));
        positions.println("image,x,validated,bestLabel,bestScore,bestAimMargin,bestKind");
        PrintWriter summary = new PrintWriter(new FileWriter(outDir + "/summary.md"));
        summary.println("# Algorithm bench (model-only)\n");
        summary.println("| Image | Coins own/all | Candidates | Validated | Production best (margin) | Tolerance best (margin) | Same? | Gold x (prod) | Best-tolerance x | ms |");
        summary.println("|---|---|---|---|---|---|---|---|---|---|");
        int images=0, withStriker=0, agree=0, fragileProd=0, prodRoutes=0; double marginSumProd=0, marginSumBest=0;
        List<String> notes = new ArrayList<>();
        for (String file : args) {
            images++;
            BoardDetector det = new BoardDetector();
            Mat bgr = Imgcodecs.imread(file), rgba = new Mat();
            Imgproc.cvtColor(bgr, rgba, Imgproc.COLOR_BGR2RGBA);
            GameState s = det.detectMat(rgba);
            String name = new File(file).getName().replace("_18c8b2b8ccca8eded9a08ff8021e764b", "");
            if (s == null || !s.strikerDetected || !s.teamKnown) {
                summary.printf("| %s | - | - | - | %s | | | | | |%n", name, s == null ? "no board" : !s.strikerDetected ? "opponent turn / no striker" : "team unknown");
                det.close(); continue;
            }
            withStriker++;
            long t0 = System.nanoTime(); sims = 0;
            List<ShotPlanner.Shot> all = RoutePlanner.allCandidates(s, null);
            List<ShotPlanner.Shot> prod = RoutePlanner.routes(s, null);
            List<Row> rows = new ArrayList<>();
            for (ShotPlanner.Shot shot : all) {
                if (!PhysicsVerifier.validate(s, shot)) continue;
                Row r = new Row(); r.shot = shot;
                for (int i = 0; i < prod.size(); i++) if (same(prod.get(i), shot)) { r.prodRank = i; break; }
                measure(s, r);
                rows.add(r);
            }
            // Cluster-opening guides (kind 4) are part of production output; measure them too.
            for (ShotPlanner.Shot shot : ClusterPlanner.candidates(s, null)) {
                if (!PhysicsVerifier.validate(s, shot)) continue;
                Row r = new Row(); r.shot = shot;
                for (int i = 0; i < prod.size(); i++) if (same(prod.get(i), shot)) { r.prodRank = i; break; }
                measure(s, r); rows.add(r);
            }
            for (Row r : rows) {
                ShotPlanner.Shot x = r.shot;
                routes.printf(Locale.US, "%s,%d,%d,%s,%d,%d,%.1f,%d,%b,%.2f,%.2f,%.2f,%.2f,%d%n", name, r.prodRank, x.kind, RoutePlanner.label(x).replace(',', ' '), x.chain.size(), x.wall, x.score, x.robustnessPassed, x.productivePot, x.assumedLaunchSpeed / s.board.width(), r.neg, r.pos, r.margin(), r.speedPasses);
            }
            Row prodBest = null; for (Row r : rows) if (r.prodRank == 0) prodBest = r;
            Row tolBest = null;
            for (Row r : rows) if (r.shot.kind < 4 && (tolBest == null || r.margin() > tolBest.margin() || r.margin() == tolBest.margin() && r.shot.score > tolBest.shot.score)) tolBest = r;
            boolean sameBest = prodBest != null && tolBest != null && same(prodBest.shot, tolBest.shot);
            if (prodBest != null && prodBest.shot.kind < 4) { prodRoutes++; marginSumProd += prodBest.margin(); if (prodBest.margin() < 1f) fragileProd++; if (sameBest) agree++; }
            if (tolBest != null) marginSumBest += tolBest.margin();
            // Baseline sweep: production gold versus tolerance-first.
            float[] goldScore = new float[1];
            float goldX = RoutePlanner.goldX(s, null, goldScore);
            float bestX = Float.NaN, bestMargin = -1; String bestLabel = "";
            float original = s.striker.pos.x;
            try {
                for (int i = 0; i <= 40; i++) {
                    float x = s.board.left + s.board.width() * (.21f + .58f * i / 40f);
                    s.striker.pos.x = x;
                    List<ShotPlanner.Shot> here = RoutePlanner.allCandidates(s, null);
                    int validated = 0; Row top = null;
                    for (ShotPlanner.Shot shot : here) {
                        if (validated >= 24) break;
                        if (!PhysicsVerifier.validate(s, shot)) continue;
                        validated++;
                        Row r = new Row(); r.shot = shot; measure(s, r);
                        if (top == null || r.margin() > top.margin() || r.margin() == top.margin() && shot.score > top.shot.score) top = r;
                    }
                    positions.printf(Locale.US, "%s,%.0f,%d,%s,%.1f,%.2f,%d%n", name, x, validated, top == null ? "" : RoutePlanner.label(top.shot).replace(',', ' '), top == null ? 0 : top.shot.score, top == null ? -1 : top.margin(), top == null ? -1 : top.shot.kind);
                    if (top != null && top.margin() > bestMargin) { bestMargin = top.margin(); bestX = x; bestLabel = RoutePlanner.label(top.shot); }
                }
            } finally { s.striker.pos.x = original; }
            long ms = (System.nanoTime() - t0) / 1000000;
            summary.printf(Locale.US, "| %s | %d/%d | %d | %d | %s | %s | %s | %.0f (%.0f) | %.0f (%s, ±%.2f°) | %d |%n", name,
                    s.coins.stream().filter(c -> c.targetEligible).count(), s.coins.size(), all.size(), rows.size(),
                    describe(prodBest), describe(tolBest), prodBest == null || tolBest == null ? "-" : sameBest ? "yes" : "NO",
                    goldX, goldScore[0], bestX, bestLabel, bestMargin, ms);
            if (prodBest != null && tolBest != null && !sameBest && prodBest.shot.kind < 4)
                notes.add(name + ": production picks " + describe(prodBest) + " but " + describe(tolBest) + " tolerates more aim error.");
            // Annotate: production best cyan, tolerance best magenta.
            Mat o = bgr.clone();
            draw(o, prodBest, new Scalar(255, 229, 0));
            if (!sameBest) draw(o, tolBest, new Scalar(255, 0, 255));
            if (!Float.isNaN(goldX)) Imgproc.circle(o, new Point(goldX, s.striker.pos.y), 10, new Scalar(0, 215, 255), -1);
            if (!Float.isNaN(bestX)) Imgproc.circle(o, new Point(bestX, s.striker.pos.y), 10, new Scalar(255, 0, 255), 2);
            Imgcodecs.imwrite(outDir + "/" + name.replace(".jpg", "").replace(".png", "").replace(".jpeg", "") + ".png", o);
            System.out.printf(Locale.US, "%s candidates=%d validated=%d prodBest=%s tolBest=%s same=%b sims=%d ms=%d%n", name, all.size(), rows.size(), describe(prodBest), describe(tolBest), sameBest, sims, ms);
            det.close(); bgr.release(); rgba.release(); o.release();
        }
        summary.println();
        summary.printf(Locale.US, "Images: %d, with local striker and known team: %d. Production routes (non-cluster): %d; mean aim margin of production best: %.2f°, of tolerance best: %.2f°. Production best agreed with tolerance-first choice %d/%d times; production best with aim margin under 1°: %d.%n%n", images, withStriker, prodRoutes, prodRoutes == 0 ? 0 : marginSumProd / prodRoutes, prodRoutes == 0 ? 0 : marginSumBest / prodRoutes, agree, prodRoutes, fragileProd);
        for (String n : notes) summary.println("- " + n);
        routes.close(); positions.close(); summary.close();
    }

    static void measure(GameState s, Row r) {
        ShotPlanner.Shot x = r.shot; float speed = x.assumedLaunchSpeed;
        int others = x.otherCoinsMoved, pairs = x.closePairsOpened; boolean productive = x.productivePot; int moved = x.clusterMoved;
        r.pos = 0; r.neg = 0;
        for (float d = AIM_STEP; d <= AIM_MAX; d += AIM_STEP) { sims++; if (PhysicsVerifier.simulate(s, x, speed, d, WALL)) r.pos = d; else break; }
        for (float d = AIM_STEP; d <= AIM_MAX; d += AIM_STEP) { sims++; if (PhysicsVerifier.simulate(s, x, speed, -d, WALL)) r.neg = d; else break; }
        for (float f : SPEED_FACTORS) { sims++; if (PhysicsVerifier.simulate(s, x, speed * f, 0, WALL)) r.speedPasses++; }
        x.otherCoinsMoved = others; x.closePairsOpened = pairs; x.productivePot = productive; x.clusterMoved = moved;
    }
    static String describe(Row r) {
        if (r == null) return "none";
        return RoutePlanner.label(r.shot) + String.format(Locale.US, " (score %.0f, stress %d/%d, ±%.2f°, speed %d/%d)", r.shot.score, r.shot.robustnessPassed, r.shot.robustnessTrials, r.margin(), r.speedPasses, SPEED_FACTORS.length);
    }
    static boolean same(ShotPlanner.Shot a, ShotPlanner.Shot b) {
        if (a.kind != b.kind || a.wall != b.wall || a.chain.size() != b.chain.size() || Math.hypot(a.pocket.x - b.pocket.x, a.pocket.y - b.pocket.y) > 8) return false;
        for (int i = 0; i < a.chain.size(); i++) if (Math.hypot(a.chain.get(i).pos.x - b.chain.get(i).pos.x, a.chain.get(i).pos.y - b.chain.get(i).pos.y) > a.chain.get(i).radius * .75f) return false;
        return true;
    }
    static void draw(Mat o, Row r, Scalar colour) {
        if (r == null) return; ShotPlanner.Shot sh = r.shot;
        for (int i = 0; i < sh.strikerPath.size() - 1; i++) { PointF a = sh.strikerPath.get(i), b = sh.strikerPath.get(i + 1); Imgproc.line(o, new Point(a.x, a.y), new Point(b.x, b.y), colour, 3); }
        for (int i = 0; i < sh.chain.size(); i++) { Coin c = sh.chain.get(i); PointF end = sh.coinEndpoints.get(i); Imgproc.line(o, new Point(c.pos.x, c.pos.y), new Point(end.x, end.y), colour, 2); }
        if (sh.targetBounce != null) Imgproc.line(o, new Point(sh.targetBounce.x, sh.targetBounce.y), new Point(sh.pocket.x, sh.pocket.y), colour, 2);
        Imgproc.putText(o, String.format(Locale.US, "%s +-%.2f deg", RoutePlanner.label(sh), r.margin()), new Point(30, colour.val[0] == 255 && colour.val[2] == 255 ? 90 : 60), Imgproc.FONT_HERSHEY_SIMPLEX, .8, colour, 2);
    }
}
