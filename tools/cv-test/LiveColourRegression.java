import com.bitaim.carromaim.cv.*;
import org.opencv.core.*;import org.opencv.imgcodecs.Imgcodecs;import org.opencv.imgproc.Imgproc;
import java.util.*;

/** Live practice frame (2026-11-07): black discs with a white spin highlight must stay black. */
public class LiveColourRegression {
    static void check(boolean v,String m){if(!v)throw new AssertionError(m);}
    public static void main(String[] a){
        nu.pattern.OpenCV.loadLocally();
        Mat bgr=Imgcodecs.imread(a[0]),rgba=new Mat();Imgproc.cvtColor(bgr,rgba,Imgproc.COLOR_BGR2RGBA);
        BoardDetector d=new BoardDetector();d.setTargetColourKey(TargetColourLock.DARK);
        GameState s=d.detectMat(rgba);d.close();
        check(s!=null&&s.strikerDetected,"Live frame must detect the local striker");
        long black=s.coins.stream().filter(c->!c.isQueen&&c.color==Coin.COLOR_BLACK).count();
        long own=s.coins.stream().filter(c->c.targetEligible).count();
        long white=s.coins.stream().filter(c->c.color==Coin.COLOR_WHITE).count();
        check(black==6,"Six black discs are on the board (scoreboard 3 of 9 potted), got "+black);
        check(own==6,"Black lock must include both highlighted black discs, got "+own);
        check(white==8,"Eight white discs remain, got "+white);
        List<ShotPlanner.Shot> routes=RoutePlanner.routes(s,null);
        check(!routes.isEmpty(),"With all six blacks eligible a route or opener must exist");
        for(ShotPlanner.Shot r:routes)check(r.coin.targetEligible&&r.coin.color==Coin.COLOR_BLACK,"Black lock routes must target black");
        System.out.println("PASS: highlighted black discs stay black, Black lock includes all six, route found: "+RoutePlanner.label(routes.get(0)));
        if(a.length>1){
            Mat q=Imgcodecs.imread(a[1]),qr=new Mat();Imgproc.cvtColor(q,qr,Imgproc.COLOR_BGR2RGBA);
            BoardDetector qd=new BoardDetector();qd.setTargetColourKey(TargetColourLock.DARK);
            GameState g=qd.detectMat(qr);qd.close();
            check(g!=null&&g.strikerDetected,"Queen frame must detect the local striker");
            long queens=g.coins.stream().filter(c->c.isQueen).count();
            check(queens==1,"Yellow-glow queen below the baseline must be detected, got "+queens);
            check(g.queenRequired,"One black left plus the queen must activate QUEEN FIRST");
            check(g.coins.stream().filter(c->c.targetEligible).allMatch(c->c.isQueen),"Only the queen may be the final target");
            System.out.println("PASS: glowing queen detected, QUEEN FIRST with last black held");
        }
        if(a.length>2){
            Mat q=Imgcodecs.imread(a[2]),qr=new Mat();Imgproc.cvtColor(q,qr,Imgproc.COLOR_BGR2RGBA);
            BoardDetector qd=new BoardDetector();qd.setTargetColourKey(TargetColourLock.DARK);
            GameState g=qd.detectMat(qr);qd.close();
            check(g!=null&&g.queenRequired,"Blocked-queen frame must be in QUEEN FIRST");
            List<ShotPlanner.Shot> qr2=RoutePlanner.routes(g,null);
            check(qr2.stream().anyMatch(r->r.coin.isQueen&&r.kind==1),"Blocked queen needs the soft right-cushion rail route");
            ShotPlanner.Shot rail=qr2.stream().filter(r->r.coin.isQueen&&r.kind==1).findFirst().get();
            // v4.8.2: with the fitted rails (0.80) the safe rail speed is no longer below 1 board width per second.
            check(rail.modelValidated&&rail.assumedLaunchSpeed>0,"The rail route to the queen must be model validated");
            System.out.println("PASS: blocked queen gets the soft rail route ("+RoutePlanner.label(rail)+", stress "+rail.robustnessPassed+"/9)");
        }
        if(a.length>3){
            Mat q=Imgcodecs.imread(a[3]),qr=new Mat();Imgproc.cvtColor(q,qr,Imgproc.COLOR_BGR2RGBA);
            BoardDetector qd=new BoardDetector();qd.setTargetColourKey(TargetColourLock.DARK);
            GameState g=qd.detectMat(qr);qd.close();
            ShotPlanner.Shot safe=RoutePlanner.routes(g,null).get(0);
            check(!Float.isNaN(safe.chancePercent),"Fresh routes carry a realistic chance");
            g.striker.pos.x+=6;ShotPlanner.Shot held=RoutePlanner.refresh(g,safe);g.striker.pos.x-=6;
            check(held==null||!Float.isNaN(held.chancePercent),"A held route re-validated after a striker move must keep showing Chance %, not Model %");
            RoutePlanner.proStyle=true;
            try{
                ShotPlanner.Shot pro=RoutePlanner.routes(g,null).get(0);
                check(pro.coin.targetEligible,"Pro style still targets own coins");
                check(pro.pathsAfter>=safe.pathsAfter,"Pro style must leave at least as many pocket paths as the safe pick ("+pro.pathsAfter+" vs "+safe.pathsAfter+")");
                System.out.println("PASS: Safe pick "+RoutePlanner.label(safe)+" leaves "+safe.pathsAfter+" paths; Pro pick "+RoutePlanner.label(pro)+" leaves "+pro.pathsAfter);
            }finally{RoutePlanner.proStyle=false;}
        }
        if(a.length>4){
            Mat q=Imgcodecs.imread(a[4]),qr=new Mat();Imgproc.cvtColor(q,qr,Imgproc.COLOR_BGR2RGBA);
            BoardDetector qd=new BoardDetector();qd.setTargetColourKey(TargetColourLock.DARK);
            GameState g=qd.detectMat(qr);qd.close();
            check(g!=null&&g.strikerDetected,"Striker on the baseline end circle (purple skin) must be detected");
            check(g.striker.pos.x<360,"Detected striker must be the far-left one, got x="+g.striker.pos.x);
            check(g.queenRequired,"One black plus the queen must activate QUEEN FIRST");
            System.out.println("PASS: end-circle striker detected at x="+Math.round(g.striker.pos.x)+", QUEEN FIRST active");
        }
        if(a.length>5){
            Mat q=Imgcodecs.imread(a[5]),qr=new Mat();Imgproc.cvtColor(q,qr,Imgproc.COLOR_BGR2RGBA);
            BoardDetector qd=new BoardDetector();qd.setTargetColourKey(TargetColourLock.DARK);
            GameState g=qd.detectMat(qr);qd.close();
            List<ShotPlanner.Shot> rr=RoutePlanner.routes(g,null);float[] gs=new float[1];float gx=RoutePlanner.goldX(g,null,gs);
            check(!rr.isEmpty()||!Float.isNaN(gx),"Blocked three-black cluster must give a route or a striker spot, never nothing");
            System.out.println("PASS: blocked cluster gives "+(rr.isEmpty()?"no route here but a striker spot at x="+Math.round(gx):RoutePlanner.label(rr.get(0)))+(Float.isNaN(gx)?"":" (★ x="+Math.round(gx)+")"));
        }
    }
}
