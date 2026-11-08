import android.graphics.*;
import com.bitaim.carromaim.cv.*;
import java.util.*;

public class PlannerRegression {
    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        RectF board = new RectF(0, 0, 1000, 1000);
        Coin target = new Coin(500, 500, 20, Coin.COLOR_WHITE, false);
        List<Coin> coins = new ArrayList<>(Arrays.asList(target));
        List<PointF> pockets = Arrays.asList(new PointF(500, 50));
        ShotPlanner.Shot direct = ShotPlanner.bestForCoin(500, 850, 30, target, coins, pockets, board);
        check(direct != null && Math.abs(direct.ghost.y - 550) < 0.01,
                "Direct impact must use the sum of physical radii");
        coins.add(new Coin(500, 700, 20, Coin.COLOR_BLACK, false));
        check(ShotPlanner.bestForCoin(500, 850, 30, target, coins, pockets, board) == null,
                "Manual aim must reject a blocked striker path");
        coins.remove(1);
        coins.add(new Coin(538, 250, 20, Coin.COLOR_BLACK, false));
        check(ShotPlanner.bestForCoin(500, 850, 30, target, coins, pockets, board) == null,
                "Manual aim must account for both coin radii along the pocket path");
        coins.remove(1);
        float gold = ShotPlanner.goldSpotX(850, 30, coins, pockets, board, new float[1]);
        check(!Float.isNaN(gold) && ShotPlanner.best(gold, 850, 30, coins, pockets, board) != null,
                "A gold spot must produce a valid shot when a clear shot exists");
        check(ShotPlanner.bestForCoin(500, 150, 30, target, coins, pockets, board) == null,
                "A striker cannot push the target backwards into the pocket");
        System.out.println("PASS: physical impact radius, blocked striker path, blocked coin path, gold spot, impossible cut");
    }
}
