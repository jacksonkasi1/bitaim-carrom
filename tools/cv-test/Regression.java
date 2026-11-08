import com.bitaim.carromaim.cv.*;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

/** Real-device fixtures: opening board, opponent turn, settings screen. */
public class Regression {
    static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    static GameState detect(String path) {
        Mat bgr = Imgcodecs.imread(path), rgba = new Mat();
        check(!bgr.empty(), "Missing fixture: " + path);
        Imgproc.cvtColor(bgr, rgba, Imgproc.COLOR_BGR2RGBA);
        BoardDetector detector = new BoardDetector();
        GameState state = detector.detectMat(rgba);
        detector.close();
        bgr.release(); rgba.release();
        return state;
    }
    public static void main(String[] args) {
        nu.pattern.OpenCV.loadLocally();
        GameState opening = detect(args[0]);
        check(opening != null && opening.strikerDetected, "Opening striker missing");
        check(Math.hypot(opening.striker.pos.x - 540, opening.striker.pos.y - 1573) < 15,
                "Striker centre must match screen coordinates");
        check(opening.coins.size() == 19, "Opening cluster must detect all 19 real coins");
        check(opening.openingCluster,"Packed opening must be distinguished from detection failure");
        for (Coin coin : opening.coins) {
            check(coin.pos.x > 410 && coin.pos.x < 670 && coin.pos.y > 1080 && coin.pos.y < 1360,
                    "Opening coins must be in the real cluster, not on baseline decorations");
        }
        float[][] holes = {{84,767},{996,767},{84,1680},{996,1680}};
        for (int i = 0; i < 4; i++) {
            check(Math.hypot(opening.pockets.get(i).x - holes[i][0],
                    opening.pockets.get(i).y - holes[i][1]) < 12,
                    "Pocket " + i + " must be centred on the real hole");
        }
        check(ShotPlanner.best(opening.striker.pos.x, opening.striker.pos.y,
                opening.striker.radius, opening.coins, opening.pockets, opening.board) == null,
                "Opening cluster has no clear centre shot");
        GameState opponent = detect(args[1]);
        check(opponent != null && !opponent.strikerDetected,
                "Ordinary coins on the bottom baseline must not count as the striker");
        for (Coin coin : opponent.coins) {
            check(Math.hypot(coin.pos.x - 294, coin.pos.y - 1583) > 35,
                    "Baseline decoration must not be detected as a coin");
        }
        check(detect(args[2]) == null, "Settings screen must not count as a board");
        if (args.length >= 8) {
            check(!detect(args[3]).strikerDetected, "Blue baseline coin must not become a striker");
            GameState right = detect(args[4]);
            check(right.strikerDetected && Math.hypot(right.striker.pos.x - 818,
                    right.striker.pos.y - 1573) < 15, "Real striker at the right must be detected");
            check(right.coins.size() == 14, "Score 1+3 leaves 14 real discs, including the rail coin");
            check(!detect(args[5]).strikerDetected, "Opponent-turn frame must hide guidance");
            GameState aiming = detect(args[6]);
            check(aiming.strikerDetected && Math.hypot(aiming.striker.pos.x - 461,
                    aiming.striker.pos.y - 1573) < 15, "Striker must remain visible during the game's aim gesture");
            check(aiming.coins.size() == 11, "Score 4+3 leaves 11 real discs; aim graphics are not discs");
            check(ShotPlanner.best(aiming.striker.pos.x, aiming.striker.pos.y, aiming.striker.radius,
                    aiming.coins, aiming.pockets, aiming.board) != null, "Aiming frame must offer a reachable shot");
            check(detect(args[7]) == null, "Obscured board must not produce guidance");
            System.out.println("PASS: blue baseline coin rejection, 14-disc layout, 11-disc aiming layout, rail coin, two real striker positions, opponent turn, obscured board");
        }
        if (args.length >= 10) {
            check(!detect(args[8]).strikerDetected, "Latest opponent frame must have no guidance");
            GameState latest = detect(args[9]);
            check(latest.strikerDetected && Math.hypot(latest.striker.pos.x - 417,
                    latest.striker.pos.y - 1573) < 15, "Latest aiming striker must match the real puck");
            check(latest.coins.size() == 10, "Latest aiming frame must contain 10 real discs");
            System.out.println("PASS: additional opponent frame, third real striker position, 10-disc aiming layout");
        }
        System.out.println("PASS: striker, all 19 opening coins, 4 pocket centres, blocked opening, decoration rejection, opponent turn, no-board screen");
    }
}
