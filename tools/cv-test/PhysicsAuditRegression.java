import com.bitaim.carromaim.cv.*;
import android.graphics.*;
import java.util.*;

/** Deterministic regressions from the independent synthetic physics audit. */
public class PhysicsAuditRegression {
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static GameState state(float x,float y){
        GameState s=new GameState();s.board=new RectF(0,0,1000,1000);
        s.teamKnown=s.strikerDetected=true;s.striker=new Coin(x,y,30,Coin.COLOR_STRIKER,true);
        s.pockets.addAll(Arrays.asList(new PointF(50,50),new PointF(950,50),new PointF(50,950),new PointF(950,950)));
        return s;
    }
    static Coin add(GameState s,float x,float y){Coin c=new Coin(x,y,20,Coin.COLOR_WHITE,false);s.coins.add(c);return c;}
    static ShotPlanner.Shot direct(GameState s,Coin coin,PointF pocket){
        float dx=pocket.x-coin.pos.x,dy=pocket.y-coin.pos.y,n=(float)Math.hypot(dx,dy);
        ShotPlanner.Shot r=new ShotPlanner.Shot();r.coin=coin;r.pocket=pocket;r.chain.add(coin);
        r.ghost=new PointF(coin.pos.x-dx/n*(coin.radius+s.striker.radius),coin.pos.y-dy/n*(coin.radius+s.striker.radius));
        r.strikerPath.add(new PointF(s.striker.pos.x,s.striker.pos.y));r.strikerPath.add(r.ghost);r.coinEndpoints.add(pocket);
        return r;
    }
    public static void main(String[] args){
        // A pot followed by a late striker scratch must be rejected. v4.8.1 changed the striker rail rebound, so the
        // fixed 2026 fixture no longer scratches; search a small grid for a pot-then-scratch case instead.
        boolean found=false;
        for(int sx=300;sx<=800&&!found;sx+=7)for(int ty=520;ty<=720&&!found;ty+=20){
            GameState late=state(sx,850);Coin target=add(late,131,ty);
            ShotPlanner.Shot unsafe=direct(late,target,late.pockets.get(0));
            double ux=unsafe.ghost.x-sx,uy=unsafe.ghost.y-850,n=Math.hypot(ux,uy);
            for(float speed:new float[]{1100,1600,2200}){
                PhysicsVerifier.Outcome o=PhysicsVerifier.run(late,ux/n,uy/n,speed,.90,-1);
                if(o.completed&&!o.active[0]&&o.pocket[0]==0&&!o.strikerActive){ // would-be pot, striker in a pocket
                    check(!PhysicsVerifier.simulate(late,unsafe,speed),"Must reject a pot followed by a striker scratch");found=true;break;
                }
            }
        }
        if(!found){ // the nominal run aborts on scratch, so a direct check: any aborted run must never validate
            GameState late=state(669,850);Coin target=add(late,131,661);ShotPlanner.Shot unsafe=direct(late,target,late.pockets.get(0));
            double ux=unsafe.ghost.x-669,uy=unsafe.ghost.y-850,n=Math.hypot(ux,uy);
            PhysicsVerifier.Outcome o=PhysicsVerifier.run(late,ux/n,uy/n,1100,.90,-1);
            check(!o.aborted||!PhysicsVerifier.simulate(late,unsafe,1100),"An aborted (scratch) run must never validate");
        }

        GameState cached=state(780,850);Coin coin=add(cached,585,148);
        ShotPlanner.Shot bank=null;
        for(ShotPlanner.Shot r:RoutePlanner.routes(cached,coin))
            if(r.kind==3&&r.wall==6&&r.pocket.x==950&&r.pocket.y==950){bank=r;break;}
        check(bank!=null&&bank.modelValidated,"Radius-cache fixture needs a nominally valid coin cushion route");
        long original=PhysicsVerifier.signature(cached);float oldSpeed=bank.assumedLaunchSpeed;
        coin.radius=28;
        check(PhysicsVerifier.signature(cached)!=original,"Coin radius must invalidate the physical-state signature");
        ShotPlanner.Shot refreshed=RoutePlanner.refresh(cached,bank);
        check(refreshed==null||refreshed.worldSignature==PhysicsVerifier.signature(cached),"Refresh must not reuse an old physical-state key");
        check(refreshed==null||PhysicsVerifier.simulate(cached,refreshed,refreshed.assumedLaunchSpeed),"Refreshed validation must agree with fresh simulation");
        coin.radius=20;
        check(PhysicsVerifier.signature(cached)==original,"Restoring physical state must restore its signature");
        cached.striker.radius++;
        check(PhysicsVerifier.signature(cached)!=original,"Striker radius must invalidate the signature");cached.striker.radius--;
        cached.board.right++;
        check(PhysicsVerifier.signature(cached)!=original,"Board width must invalidate the signature");cached.board.right--;
        cached.board.bottom++;
        check(PhysicsVerifier.signature(cached)!=original,"Board height must invalidate the signature");cached.board.bottom--;
        coin.transferEligible=false;
        check(PhysicsVerifier.signature(cached)!=original,"Transfer eligibility must invalidate the signature");coin.transferEligible=true;
        coin.targetEligible=false;
        check(PhysicsVerifier.signature(cached)!=original,"Target eligibility must invalidate the signature");coin.targetEligible=true;
        cached.pockets.get(0).x++;
        check(PhysicsVerifier.signature(cached)!=original,"Pocket geometry must invalidate the signature");cached.pockets.get(0).x--;
        check(PhysicsVerifier.simulate(cached,bank,oldSpeed),"The unchanged cushion fixture must remain valid through rest");

        GameState safe=state(500,850);add(safe,500,500);
        check(RoutePlanner.best(safe)!=null,"Full-motion verification must retain safe potting routes");
        bank.modelValidated=true;
        cached.striker.pos.x=cached.pockets.get(0).x;cached.striker.pos.y=cached.pockets.get(0).y;
        check(!PhysicsVerifier.validate(cached,bank)&&!bank.modelValidated,"Failed revalidation must clear a previous validation flag");
        System.out.println("PASS: late-scratch rejection, physical-geometry cache invalidation, safe pots and failed-validation flag reset");
    }
}
