package com.bitaim.carromaim.cv;

import android.graphics.PointF;
import java.util.*;

/**
 * Brute-force aim sweep. The geometric route builder only proposes exact ghost-ball contacts, so it misses pots
 * that the full model (and players) find: a manual-play review on 2026-11-07 showed boards where the user potted
 * through an opponent disc while the builder produced no pot candidate at all. This planner launches the striker
 * every STEP degrees over the whole arc at a few powers, keeps every direction that pots an own disc without a
 * scratch or an opponent pot, groups neighbouring successes into aim windows, and returns the centre of the
 * widest windows (the most forgiving aim). Model-only: power and spin are not calibrated.
 */
public final class SweepPlanner {
    private SweepPlanner(){}
    private static final double WALL=.80;
    /** Time-budget multiplier (phone 1; the PC brain raises it). */
    public static volatile double BUDGET_SCALE=1;

    /** Up to {@code limit} pots, widest aim window first; {@code quick} uses 2-degree steps and one power. */
    public static List<ShotPlanner.Shot> candidates(GameState s,Coin selected,boolean quick,int limit){
        List<ShotPlanner.Shot> out=new ArrayList<>();
        if(s==null||s.board==null||!s.teamKnown||!s.strikerDetected||s.striker==null||s.coins.isEmpty())return out;
        float w=s.board.width();
        float step=quick?2f:1f;float[] powers=quick?new float[]{1.9f}:new float[]{1.9f,1.5f,2.3f}; // 4.8.6: real pull range under constant friction // normal power first: a slow phone may hit the time budget
        long deadline=System.nanoTime()+(long)((quick?120_000_000L:(s.openingCluster?250_000_000L:600_000_000L))*BUDGET_SCALE); // phone-time budget (PC brain scales it)
        long signature=PhysicsVerifier.signature(s);
        for(float boards:powers){
            // success[a] = index of the own disc potted at angle a (or -1); pocket[a] = its pocket
            int n=(int)(360/step);int[] success=new int[n],pocketOf=new int[n],firstOf=new int[n];
            Arrays.fill(success,-1);
            for(int k=0;k<n;k++){
                if(Thread.currentThread().isInterrupted()||System.nanoTime()>deadline)return finish(out,limit);
                double ang=Math.toRadians(k*step);double ux=Math.cos(ang),uy=Math.sin(ang);
                // Keep the striker on the board: skip directions pointing straight into the near rail.
                if(uy>.97)continue;
                PhysicsVerifier.Outcome o=PhysicsVerifier.run(s,ux,uy,w*boards,WALL,-1);
                if(o.aborted||!o.completed||!o.strikerActive||o.firstContact<0)continue;
                int potted=-1;
                for(int i=0;i<s.coins.size();i++)if(!o.active[i]){Coin c=s.coins.get(i);if(c.targetEligible&&(selected==null||c==selected)){potted=i;break;}}
                if(potted<0)continue;
                // An unknown obstacle (not confirmed as a disc we may play off) is never an intended first contact.
                if(o.firstContact!=potted&&!s.coins.get(o.firstContact).transferEligible)continue;
                success[k]=potted;pocketOf[k]=o.pocket[potted];firstOf[k]=o.firstContact;
            }
            // Windows of consecutive angles that pot the same disc into the same pocket.
            for(int k=0;k<n;k++){
                if(success[k]<0||(success[(k-1+n)%n]==success[k]&&pocketOf[(k-1+n)%n]==pocketOf[k]))continue;
                int len=0;while(len<n&&success[(k+len)%n]==success[k]&&pocketOf[(k+len)%n]==pocketOf[k])len++;
                int mid=(k+len/2)%n;
                ShotPlanner.Shot shot=build(s,mid*step,boards*w,success[mid],firstOf[mid],pocketOf[mid],signature);
                if(shot==null)continue;
                // 4.9.2: never keep a sweep pot whose striker follows the disc in with a heavier striker.
                if(!PhysicsVerifier.followSafe(s,shot,shot.assumedLaunchSpeed))continue;
                // Honest tolerance: the 2-degree sweep window overstated rail-hugging cuts ("EASY ±10°", live 09:13:26
                // missed at 3.8°); test both sides like every other route and keep the smaller value.
                shot.aimMarginDegrees=Math.min(Math.max(0,(len-1)*step/2f),PhysicsVerifier.aimMargin(s,shot,shot.assumedLaunchSpeed));
                out.add(shot);
            }
        }
        return finish(out,limit);
    }
    private static List<ShotPlanner.Shot> finish(List<ShotPlanner.Shot> out,int limit){
        Collections.sort(out,(a,b)->Float.compare(b.aimMarginDegrees,a.aimMarginDegrees));
        // one entry per target disc and pocket
        List<ShotPlanner.Shot> unique=new ArrayList<>();
        for(ShotPlanner.Shot s:out){boolean dup=false;for(ShotPlanner.Shot u:unique)if(u.coin==s.coin&&u.pocket==s.pocket){dup=true;break;}if(!dup)unique.add(s);if(unique.size()>=limit)break;}
        return unique;
    }
    private static ShotPlanner.Shot build(GameState s,float degrees,float speed,int target,int first,int pocket,long signature){
        if(pocket<0||pocket>=s.pockets.size())return null;
        Coin tc=s.coins.get(target),fc=s.coins.get(first);
        double ang=Math.toRadians(degrees);float ux=(float)Math.cos(ang),uy=(float)Math.sin(ang);
        // Where the striker centre meets the first disc along this line (for drawing and the aim leg).
        float sx=s.striker.pos.x,sy=s.striker.pos.y,r=s.striker.radius+fc.radius;
        float px=fc.pos.x-sx,py=fc.pos.y-sy,along=px*ux+py*uy,perp2=px*px+py*py-along*along;
        float back=(float)Math.sqrt(Math.max(0,r*r-perp2));float t=Math.max(s.striker.radius,along-back);
        PointF ghost=new PointF(sx+ux*t,sy+uy*t);
        ShotPlanner.Shot shot=new ShotPlanner.Shot();
        // Live 00:46:13: a sweep pot that only worked off the bottom rail was labelled "Direct route". When the straight
        // line does not reach the first disc the striker bounces first: mark it a striker cushion (ranked as risky).
        boolean straight=along>0&&perp2<r*r;
        shot.kind=first==target?0:2;shot.coin=tc;shot.pocket=s.pockets.get(pocket);shot.ghost=ghost;shot.wall=-1;shot.railFirst=!straight;
        shot.strikerPath.add(new PointF(sx,sy));shot.strikerPath.add(ghost);
        PointF touch=shot.pocket;
        if(first!=target){ // first disc drawn to where it touches the target (exact contact distance)
            float dx=tc.pos.x-fc.pos.x,dy=tc.pos.y-fc.pos.y,len=(float)Math.max(1e-3,Math.hypot(dx,dy)),rr=fc.radius+tc.radius;
            touch=new PointF(tc.pos.x-dx/len*rr,tc.pos.y-dy/len*rr);
        }
        shot.chain.add(fc);shot.coinEndpoints.add(touch);
        if(first!=target){shot.chain.add(tc);shot.coinEndpoints.add(shot.pocket);}
        shot.assumedLaunchSpeed=speed;shot.modelValidated=true;shot.worldSignature=signature;shot.sweep=true;
        // Same nine stress samples as the production verifier, then the realistic chance.
        int passed=0;
        for(double[] st:PhysicsVerifier.STRESS)if(PhysicsVerifier.simulate(s,shot,(float)(speed*st[0]),st[1],st[2]))passed++;
        if(passed==0)return null;
        shot.robustnessTrials=PhysicsVerifier.STRESS.length;shot.robustnessPassed=passed;
        shot.score=60-(first==target?0:18)-t/s.board.width()*8;
        shot.chancePercent=PhysicsVerifier.chance(s,shot,RoutePlanner.CHANCE_SAMPLES);
        RoutePlanner.markTouching(s,shot);
        return shot;
    }
}
