import com.bitaim.carromaim.cv.*;
import org.opencv.core.*;import org.opencv.imgcodecs.Imgcodecs;import org.opencv.imgproc.Imgproc;
import java.util.*;

/** Live game 2026-11-07 22:33: our coins solid blue, opponent's blue/white striped. The Blue lock matched both, so
 *  auto play aimed at an opponent striped coin through a combination instead of an easy solid-blue pot. */
public class StripedSkinRegression {
    static void check(boolean v,String m){if(!v)throw new AssertionError(m);}
    static GameState detect(String file,int key){
        Mat bgr=Imgcodecs.imread(file),rgba=new Mat();Imgproc.cvtColor(bgr,rgba,Imgproc.COLOR_BGR2RGBA);
        BoardDetector d=new BoardDetector();d.setTargetColourKey(key);GameState s=d.detectMat(rgba);d.close();return s;
    }
    public static void main(String[] a){
        nu.pattern.OpenCV.loadLocally();
        int blue=TargetColourLock.chromatic(105,false); // the lock saved on the phone (205, "Blue")
        check(blue==205&&TargetColourLock.label(blue).equals("Blue"),"Solid blue key unchanged");
        check(TargetColourLock.label(TargetColourLock.chromatic(110,true)).equals("Blue striped"),"Striped label");
        check(!TargetColourLock.matches(205,310)&&!TargetColourLock.matches(310,205)&&TargetColourLock.matches(310,305),"Striped and solid never match");
        int[] expectOwn={1,4,6},expectStriped={2,3,6};
        for(int i=0;i<3;i++){
            GameState s=detect(a[i],blue);
            check(s!=null,"Frame "+i+" detected");
            long own=s.coins.stream().filter(c->c.targetEligible).count();
            long striped=s.coins.stream().filter(c->TargetColourLock.striped(c.appearanceKey)).count();
            check(own==expectOwn[i],"Frame "+i+": Blue lock must take only solid blue coins, expected "+expectOwn[i]+" got "+own);
            check(striped==expectStriped[i],"Frame "+i+": striped opponent coins, expected "+expectStriped[i]+" got "+striped);
            for(Coin c:s.coins)if(TargetColourLock.striped(c.appearanceKey))check(!c.targetEligible,"A striped coin is never a Blue target");
            if(s.strikerDetected)for(ShotPlanner.Shot r:RoutePlanner.routes(s,null))check(!TargetColourLock.striped(r.coin.appearanceKey),"No route may target a striped coin");
        }
        if(a.length>3){ // practice 22:49: a white coin on the top-left pocket lip used to reject every shot on the board
            GameState lip=detect(a[3],TargetColourLock.DARK);
            check(lip!=null&&lip.strikerDetected&&lip.coins.stream().filter(c->c.targetEligible).count()==2,"Pocket-lip frame: two black coins");
            List<ShotPlanner.Shot> routes=RoutePlanner.routes(lip,null);
            check(routes.stream().anyMatch(r->r.kind<4),"A coin resting on a pocket lip must not block every route");
            for(ShotPlanner.Shot r:routes)check(Math.hypot(r.pocket.x-lip.pockets.get(0).x,r.pocket.y-lip.pockets.get(0).y)>5||r.kind>=4,"The blocked top-left pocket is never a pot target");
        }
        if(a.length>4){ // live 23:42: last own coin resting on the bottom rail behind the baseline showed "no route"
            GameState rail=detect(a[4],TargetColourLock.chromatic(105,false));
            List<ShotPlanner.Shot> routes=RoutePlanner.routes(rail,null);
            check(!routes.isEmpty()&&routes.get(0).kind<4&&routes.get(0).chancePercent>=50,"A coin resting on the rail must get a real pot");
        }
        if(a.length>5){ // purple theme 00:29: solid red queen among red-and-yellow opponent coins
            GameState pq=detect(a[5],TargetColourLock.chromatic(105,false));
            check(pq.coins.stream().filter(c->c.isQueen).count()==1,"The lone plain-red disc is the queen");
            Coin last=pq.coins.stream().filter(c->c.targetEligible).findFirst().get();
            pq.coins.removeIf(c->c.targetEligible&&c!=last);
            pq.queenRequired=QueenRule.apply(pq);
            check(pq.queenRequired,"Queen plus last own coin: queen first");
            for(ShotPlanner.Shot r:RoutePlanner.routes(pq,null))check(r.coin.isQueen,"Every route targets the queen before the last coin");
        }
        System.out.println("PASS: purple queen first, rail coin potted, pocket-lip coin blocks only its pocket; striped skin separated from solid of the same hue (3 live frames), Blue lock takes solid only, no striped targets");
    }
}
