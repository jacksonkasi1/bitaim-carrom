import com.bitaim.carromaim.cv.*;
import android.graphics.*;
import java.util.*;

public class SpeedBeamRegression {
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static GameState state(float strikerX){
        GameState s=new GameState();s.teamKnown=s.strikerDetected=true;s.board=new RectF(0,0,1000,1000);
        s.striker=new Coin(strikerX,850,30,Coin.COLOR_STRIKER,true);
        s.pockets.addAll(Arrays.asList(new PointF(50,50),new PointF(950,50),new PointF(50,950),new PointF(950,950)));return s;
    }
    static Coin add(GameState s,float x,float y){Coin c=new Coin(x,y,20,Coin.COLOR_WHITE,false);s.coins.add(c);return c;}
    public static GameState fixture(){
        // Discovered in the close-pair sweep: seed16062152, sixth generated world.
        GameState s=state(357);
        float[][] points={{233,303},{649,221},{641.908569f,178.562149f},{196.398529f,328.503906f},{252.831970f,344.458313f},{627.956238f,128.545914f}};
        for(float[] p:points)add(s,p[0],p[1]);return s;
    }
    public static ShotPlanner.Shot route(GameState s){
        Coin target=s.coins.get(2);
        for(ShotPlanner.Shot r:RoutePlanner.routes(s,target))if(r.kind==0&&r.pocket.x==950&&r.pocket.y==50)return r;
        throw new AssertionError("Speed-beam fixture needs its direct target-to-top-right proposal");
    }
    public static void main(String[] args){
        GameState s=fixture();ShotPlanner.Shot r=route(s);
        check(PhysicsVerifier.simulate(s,r,1900),"The first validation speed (4.8.6: 1.9 board widths/s) must still produce a nominal pot");
        check(!r.productivePot&&r.otherCoinsMoved==0&&r.closePairsOpened==0,"Low-speed pot must miss the compound objective");
        check(PhysicsVerifier.validate(s,r),"Bounded speed search must find a validated speed");
        // v4.8.2: physics refitted to live shots; check invariants instead of the old exact speed/stress numbers.
        float chosen=r.assumedLaunchSpeed;int others=r.otherCoinsMoved,pairs=r.closePairsOpened,prod=r.productiveStressPassed,pass=r.robustnessPassed;
        check(chosen>0&&r.robustnessTrials==9&&pass>=1&&pass<=9&&prod<=9,"Selected speed keeps its own stress results");
        check(r.worldSignature==PhysicsVerifier.signature(s)&&r.modelValidated,"Selected speed must retain the current physical-world signature");
        check(PhysicsVerifier.simulate(s,r,chosen)&&r.otherCoinsMoved==others&&r.closePairsOpened==pairs,"Selected metadata must equal an independent nominal replay");
        for(int i=0;i<3;i++){
            check(PhysicsVerifier.validate(s,r)&&r.assumedLaunchSpeed==chosen&&r.otherCoinsMoved==others&&r.closePairsOpened==pairs&&r.productiveStressPassed==prod,"Repeated speed search must be deterministic and preserve nominal metrics");
        }
        GameState simple=state(780);Coin lone=add(simple,585,148);
        // The aim sweep may add its own (better) pot; the fast path concerns the geometric routes.
        ShotPlanner.Shot isolated=RoutePlanner.routes(simple,null).stream().filter(x->!x.sweep).findFirst().orElse(null);
        check(isolated!=null&&isolated.assumedLaunchSpeed==1900&&!isolated.productivePot,"Single-disc scene must retain the first-valid-speed fast path");
        check(lone.targetEligible,"Speed exploration must not mutate target eligibility");
        System.out.println("PASS: higher-speed compound objective, selected-speed nominal/stress metadata, determinism and isolated-scene fast path");
    }
}
