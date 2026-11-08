package com.bitaim.carromaim.cv;

import android.graphics.PointF;
import java.util.*;

/**
 * Positional ("pro") play: when no safe pot exists, pick the contact that opens the most pocket paths.
 *
 * For every known disc the striker can reach cleanly, several cut offsets and launch speeds are settled
 * in the nominal model. The settled board is scored by how many (own coin, pocket) direct paths exist from
 * five baseline striker positions, compared with the current board. A shot counts only if it adds at least
 * one path or pots an own coin, never scratches, never pots an opponent, and settles within the model budget.
 * The result is a kind-5 route: first contact, predicted coin travel, +N paths, manual power.
 *
 * Model-only evidence: nothing here is calibrated against the real game's power or spin.
 */
public final class OpeningPlanner {
    private static final double WALL=.80;
    private static final float[] FULL_OFFSETS={0f,.45f,-.45f,.8f,-.8f},QUICK_OFFSETS={0f,.5f,-.5f};
    private static final float[] FULL_SPEEDS={1.6f,1.9f,2.3f},QUICK_SPEEDS={2.2f}; // 4.8.6: speeds = 8.5 x pull, pull 0.19-0.27
    private static final float[] SAMPLE_X={.25f,.375f,.5f,.625f,.75f};
    /** Wall-clock cap for the full search; desktop tests may raise it with -Dbitaim.openerBudgetMs. */
    static long BUDGET_NANOS=Long.getLong("bitaim.openerBudgetMs",1200L)*1_000_000L;
    private OpeningPlanner(){}

    /** Number of (own coin, pocket) pairs with a clear direct path from at least one baseline sample now. */
    public static int pathsNow(GameState s){return count(s,s.coins);}
    /** The same count for the settled outcome of one simulated shot. */
    public static int pathsAfter(GameState s,PhysicsVerifier.Outcome o){
        List<Coin> after=new ArrayList<>();
        for(int i=0;i<s.coins.size();i++){
            if(!o.active[i])continue;Coin c=s.coins.get(i);
            Coin moved=new Coin((float)o.x[i],(float)o.y[i],c.radius,c.color,false);
            moved.targetEligible=c.targetEligible;moved.transferEligible=c.transferEligible;moved.isQueen=c.isQueen;after.add(moved);
        }
        return count(s,after);
    }
    private static int count(GameState s,List<Coin> coins){
        if(s.board==null||s.striker==null||s.pockets.isEmpty())return 0;
        int paths=0;float sy=s.striker.pos.y,sr=s.striker.radius;
        for(Coin c:coins){
            if(!c.targetEligible)continue;
            for(PointF pocket:s.pockets){
                for(float fx:SAMPLE_X){
                    float sx=s.board.left+s.board.width()*fx;
                    if(ShotPlanner.forPocket(sx,sy,sr,c,pocket,coins,s.board)!=null){paths++;break;}
                }
            }
        }
        return paths;
    }

    /** Ranked opening shots; {@code quick} limits the search for the background gold sweep. */
    public static List<ShotPlanner.Shot> candidates(GameState s,Coin selected,boolean quick){
        List<ShotPlanner.Shot> out=new ArrayList<>();
        if(s==null||s.board==null||!s.teamKnown||!s.strikerDetected||s.striker==null||s.coins.isEmpty())return out;
        int before=pathsNow(s);long signature=PhysicsVerifier.signature(s);
        float[] offsets=quick?QUICK_OFFSETS:FULL_OFFSETS,speeds=quick?QUICK_SPEEDS:FULL_SPEEDS;
        // Phone budget: a dense opening has ~9 own coins x 15 settles plus stress tests.
        long deadline=System.nanoTime()+(quick?150_000_000L:BUDGET_NANOS);
        ShotPlanner.Shot safeBreak=null;
        // Own coins first, then known opponent discs: a pro often spreads the cluster by hitting another disc.
        for(int pass=0;pass<2;pass++)for(int index=0;index<s.coins.size();index++){
            if(Thread.currentThread().isInterrupted()||System.nanoTime()>deadline)break;
            Coin c=s.coins.get(index);
            if(selected!=null&&c!=selected&&pass==0)continue;
            if(pass==0?!c.targetEligible:c.targetEligible||!c.transferEligible)continue;
            ShotPlanner.Shot best=null;
            for(float offset:offsets)for(float boards:speeds){
                ShotPlanner.Shot shot=forCoin(s,index,offset,s.board.width()*boards,before,signature);
                if(shot==null)continue;
                if(shot.safeBreakOnly){if(safeBreak==null||shot.score>safeBreak.score)safeBreak=shot;continue;}
                if(best==null||shot.score>best.score)best=shot;
            }
            if(best==null)continue;
            if(!quick)stress(s,best);
            out.add(best);
        }
        // Nothing opens a pot: still offer the safest real spread instead of "no route".
        if(out.isEmpty()&&safeBreak!=null){if(!quick)stress(s,safeBreak);out.add(safeBreak);}
        Collections.sort(out,(a,b)->{int order=Integer.compare(gain(b),gain(a));return order!=0?order:Float.compare(b.score,a.score);});
        return out.size()>3?new ArrayList<>(out.subList(0,3)):out;
    }
    /** Re-evaluate a previously suggested opener on a fresh frame with the same first coin, offset and speed. */
    public static ShotPlanner.Shot refresh(GameState s,Coin coin,ShotPlanner.Shot previous){
        int index=s.coins.indexOf(coin);if(index<0)return null;
        if(!coin.transferEligible)return null;
        ShotPlanner.Shot shot=forCoin(s,index,previous.aimOffset,previous.assumedLaunchSpeed,pathsNow(s),PhysicsVerifier.signature(s));
        if(shot==null)return null;
        if(previous.worldSignature==shot.worldSignature){shot.robustnessPassed=previous.robustnessPassed;shot.robustnessTrials=previous.robustnessTrials;shot.aimMarginDegrees=previous.aimMarginDegrees;}
        else stress(s,shot);
        return shot;
    }
    public static int gain(ShotPlanner.Shot s){return s.pathsAfter-s.pathsBefore+s.ownPotted*2;}

    private static ShotPlanner.Shot forCoin(GameState s,int index,float offset,float speed,int before,long signature){
        Coin c=s.coins.get(index);float sr=s.striker.radius,reach=sr+c.radius;
        float dx=c.pos.x-s.striker.pos.x,dy=c.pos.y-s.striker.pos.y,length=(float)Math.hypot(dx,dy);
        if(length<reach*1.2f)return null;
        float ux=dx/length,uy=dy/length,px=-uy,py=ux,off=offset*reach;
        float back=(float)Math.sqrt(Math.max(0,reach*reach-off*off));
        PointF ghost=new PointF(c.pos.x+px*off-ux*back,c.pos.y+py*off-uy*back);
        float gx=ghost.x-s.striker.pos.x,gy=ghost.y-s.striker.pos.y,gl=(float)Math.hypot(gx,gy);if(gl<sr)return null;
        if(ghost.x<s.board.left+sr||ghost.x>s.board.right-sr||ghost.y<s.board.top+sr||ghost.y>s.board.bottom-sr)return null;
        for(Coin o:s.coins)if(o!=c&&segment(o.pos,s.striker.pos,ghost)<sr+o.radius+s.board.width()*.003f)return null;
        for(PointF hole:s.pockets)if(segment(hole,s.striker.pos,ghost)<s.board.width()*.05f)return null;
        PhysicsVerifier.Outcome o=PhysicsVerifier.run(s,gx/gl,gy/gl,speed,WALL,index);
        if(o.aborted||!o.completed||o.firstContact!=index||!o.strikerActive)return null;
        int after=pathsAfter(s,o),ownPotted=0,moved=o.moved(s);
        for(int i=0;i<s.coins.size();i++)if(!o.active[i]&&s.coins.get(i).targetEligible)ownPotted++;
        boolean gains=after-before>=1||ownPotted>=1;
        // A spread that opens nothing is kept only as a fallback "safe break": it must move at least three discs.
        if(!gains&&moved<4)return null;
        ShotPlanner.Shot shot=new ShotPlanner.Shot();
        shot.kind=5;shot.safeBreakOnly=!gains;
        // The route belongs to an own coin (tracker, colour lock and ownership rules); the first contact may differ.
        Coin owner=c;
        if(!c.targetEligible){float bd=Float.MAX_VALUE;for(Coin own:s.coins)if(own.targetEligible){float d=(float)Math.hypot(own.pos.x-c.pos.x,own.pos.y-c.pos.y);if(d<bd){bd=d;owner=own;}}}
        if(!owner.targetEligible)return null;
        shot.coin=owner;shot.ghost=ghost;shot.aimOffset=offset;shot.assumedLaunchSpeed=speed;shot.worldSignature=signature;shot.modelValidated=true;
        shot.chain.add(c); // first contact (own or opponent)
        shot.strikerPath.add(new PointF(s.striker.pos.x,s.striker.pos.y));shot.strikerPath.add(ghost);
        PointF end=o.active[index]?new PointF((float)o.x[index],(float)o.y[index]):s.pockets.get(Math.max(0,o.pocket[index]));
        shot.coinEndpoints.add(end);
        PointF nearest=s.pockets.get(0);for(PointF p:s.pockets)if(Math.hypot(p.x-end.x,p.y-end.y)<Math.hypot(nearest.x-end.x,nearest.y-end.y))nearest=p;
        shot.pocket=nearest; // identity only: openers never draw a coin-to-pocket promise
        shot.pathsBefore=before;shot.pathsAfter=after;shot.ownPotted=ownPotted;shot.clusterMoved=moved;shot.otherCoinsMoved=Math.max(0,moved-1);
        shot.score=(after-before)*20+ownPotted*60+Math.min(moved,8)*2-gl/s.board.width()*8-Math.abs(offset)*4-(c.targetEligible?0:5);
        return shot;
    }
    /** The production nine-sample stress set (speed, aim and wall perturbations) plus a tolerance bracket. */
    private static void stress(GameState s,ShotPlanner.Shot shot){
        int index=s.coins.indexOf(shot.chain.get(0));shot.robustnessTrials=PhysicsVerifier.STRESS.length;shot.robustnessPassed=0;
        for(double[] sample:PhysicsVerifier.STRESS)if(holds(s,shot,index,sample[1],(float)(shot.assumedLaunchSpeed*sample[0]),sample[2]))shot.robustnessPassed++;
        float margin=0;
        for(float degrees:new float[]{.5f,1f,2f}){
            if(!holds(s,shot,index,degrees,shot.assumedLaunchSpeed,WALL)||!holds(s,shot,index,-degrees,shot.assumedLaunchSpeed,WALL))break;
            margin=degrees;
        }
        shot.aimMarginDegrees=margin;
    }
    private static boolean holds(GameState s,ShotPlanner.Shot shot,int index,double degrees,float speed,double wall){
        double[] u=PhysicsVerifier.direction(s,shot,degrees);if(u==null)return false;
        PhysicsVerifier.Outcome o=PhysicsVerifier.run(s,u[0],u[1],speed,wall,index);
        if(o.aborted||!o.completed||!o.strikerActive)return false;
        int ownPotted=0;for(int i=0;i<s.coins.size();i++)if(!o.active[i]&&s.coins.get(i).targetEligible)ownPotted++;
        if(shot.safeBreakOnly)return o.moved(s)>=4;
        return pathsAfter(s,o)-shot.pathsBefore>=1||ownPotted>=1;
    }
    private static float segment(PointF p,PointF a,PointF b){
        float x=b.x-a.x,y=b.y-a.y,d=x*x+y*y,t=d==0?0:Math.max(0,Math.min(1,((p.x-a.x)*x+(p.y-a.y)*y)/d));
        return (float)Math.hypot(p.x-a.x-t*x,p.y-a.y-t*y);
    }
}
