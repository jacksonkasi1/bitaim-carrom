import com.bitaim.carromaim.cv.*;
import java.nio.file.*;import java.util.*;

/** Live 16:51:58 (4.10.7): a green 91% "Direct route" shot backwards at a coin behind the striker line, into the
 *  bottom-right pocket, fouled. Such routes must not be offered; other routes on the same board still are. */
public class BackwardFoulRegression {
    public static void main(String[] a)throws Exception{
        String line=Files.readAllLines(Paths.get(a.length>0?a[0]:"tools/cv-test/fixtures/backward-direct-foul-165158.jsonl")).get(0);
        GameState g=ShotLogCalibrator.parse(line).before;
        double[] b=ShotLogCalibrator.nums(ShotLogCalibrator.field(line,"before"));
        for(int i=0;i<g.coins.size();i++)g.coins.get(i).targetEligible=b[i*6+4]>0;
        List<ShotPlanner.Shot> routes=RoutePlanner.routes(g,null);
        int bad=0;
        for(ShotPlanner.Shot s:routes){
            boolean cushion=s.kind==1||s.kind==3||s.targetBounce!=null;
            if(s.kind<4&&!cushion&&s.ghost!=null&&s.pocket.y>g.striker.pos.y&&s.ghost.y>g.striker.pos.y+g.striker.radius*.25f){
                bad++;System.out.println("  offered: "+RoutePlanner.label(s)+" to ("+s.coin.pos.x+","+s.coin.pos.y+")");
            }
        }
        if(bad>0){System.out.println("FAIL: "+bad+" backward direct route(s) behind the striker line offered");System.exit(1);}
        if(routes.isEmpty()){System.out.println("FAIL: no route at all on a board with 9 own coins");System.exit(1);}
        System.out.println("PASS: no backward direct route behind the striker line; best is "+RoutePlanner.label(routes.get(0))+" ("+routes.size()+" routes)");
    }
}
