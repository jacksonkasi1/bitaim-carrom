package com.bitaim.carromaim.cv;

import android.graphics.PointF;
import java.util.*;

/** First-contact guides for opening a connected cluster. Not pocket predictions. */
public final class ClusterPlanner {
    public static List<ShotPlanner.Shot> candidates(GameState s,Coin selected){
        List<ShotPlanner.Shot> out=new ArrayList<>();
        if(s==null||s.board==null||!s.teamKnown||!s.strikerDetected||s.striker==null)return out;
        for(Coin c:s.coins){
            if(!c.targetEligible||selected!=null&&selected!=c)continue;
            ShotPlanner.Shot shot=forCoin(s,c);if(shot!=null)out.add(shot);
        }
        Collections.sort(out,(a,b)->Float.compare(b.score,a.score));return out;
    }
    public static ShotPlanner.Shot forCoin(GameState s,Coin coin){
        if(!coin.targetEligible||s.pockets.isEmpty())return null;
        Set<Coin> cluster=new HashSet<>();cluster.add(coin);
        boolean changed=true;
        while(changed){
            changed=false;
            for(Coin c:s.coins)if(!cluster.contains(c))for(Coin member:new ArrayList<>(cluster)){
                if(Math.hypot(c.pos.x-member.pos.x,c.pos.y-member.pos.y)<=c.radius+member.radius+s.board.width()*.018f){cluster.add(c);changed=true;break;}
            }
        }
        if(cluster.size()<3)return null;
        float dx=coin.pos.x-s.striker.pos.x,dy=coin.pos.y-s.striker.pos.y;
        float length=(float)Math.hypot(dx,dy);if(length<s.striker.radius+coin.radius)return null;
        float ux=dx/length,uy=dy/length;
        PointF ghost=new PointF(coin.pos.x-ux*(s.striker.radius+coin.radius),coin.pos.y-uy*(s.striker.radius+coin.radius));
        for(Coin c:s.coins)if(c!=coin&&segment(c.pos,s.striker.pos,ghost)<s.striker.radius+c.radius+s.board.width()*.001f)return null;
        for(PointF hole:s.pockets)if(segment(hole,s.striker.pos,ghost)<s.board.width()*.05f)return null;
        ShotPlanner.Shot shot=new ShotPlanner.Shot();shot.kind=4;shot.coin=coin;shot.ghost=ghost;
        // Identity only: break guides never draw a coin-to-pocket leg.
        shot.pocket=s.pockets.get(0);shot.chain.add(coin);shot.coinEndpoints.add(new PointF(coin.pos.x,coin.pos.y));
        shot.strikerPath.add(new PointF(s.striker.pos.x,s.striker.pos.y));shot.strikerPath.add(ghost);
        shot.score=-100+cluster.size()-length/s.board.width()*10;
        return shot;
    }
    private static float segment(PointF p,PointF a,PointF b){
        float x=b.x-a.x,y=b.y-a.y,d=x*x+y*y,t=d==0?0:Math.max(0,Math.min(1,((p.x-a.x)*x+(p.y-a.y)*y)/d));
        return (float)Math.hypot(p.x-a.x-t*x,p.y-a.y-t*y);
    }

    /** 4.8.9 break search. Local simulation of a logged opening rack under the measured physics: straight at the
     *  cluster centre potted 0.25 own discs per break, aims 4-8 degrees into one side 0.8-1.0 (24 noisy trials each).
     *  Tries aim offsets around the line to the cluster centre from the current striker spot (seeded aim and power
     *  noise, so the same board gives the same answer) and returns the best offset in degrees; 0 when nothing helps
     *  or the time budget runs out. Score per trial: own discs (and queen) potted - 0.5 x opponent discs - 1.5 x foul. */
    public static float breakOffsetDegrees(GameState s,float speed,long budgetNanos){
        if(s==null||s.board==null||s.striker==null||s.coins.isEmpty())return 0;
        long deadline=System.nanoTime()+budgetNanos;
        float cx=0,cy=0;for(Coin c:s.coins){cx+=c.pos.x;cy+=c.pos.y;}cx/=s.coins.size();cy/=s.coins.size();
        double base=Math.atan2(cy-s.striker.pos.y,cx-s.striker.pos.x);
        GameState sim=ShotLogger.copy(s);
        boolean[] own=new boolean[s.coins.size()];
        for(int i=0;i<own.length;i++){Coin c=s.coins.get(i);own[i]=c.targetEligible||c.isQueen;sim.coins.get(i).targetEligible=true;} // count, never abort
        float bestOffset=0;double bestScore=-1e9;
        for(float off:new float[]{0,6,-6,3,-3,8,-8,4.5f,-4.5f}){
            java.util.Random r=new java.util.Random(0xB4EA4L+(long)(off*10));
            double score=0;int trials=0;
            for(int t=0;t<6;t++){
                if(System.nanoTime()>deadline||Thread.currentThread().isInterrupted())break;
                double ang=base+Math.toRadians(off+r.nextGaussian());
                PhysicsVerifier.Outcome o=PhysicsVerifier.run(sim,Math.cos(ang),Math.sin(ang),(float)(speed*(.9+.2*r.nextDouble())),.80,-1);
                if(!o.completed&&!o.aborted)continue;
                double v=o.strikerActive?0:-1.5;
                for(int i=0;i<own.length;i++)if(!o.active[i])v+=own[i]?1:-.5;
                score+=v;trials++;
            }
            if(trials<6)break; // budget ran out: keep the best fully tested offset
            if(score/trials>bestScore+1e-9){bestScore=score/trials;bestOffset=off;}
        }
        return bestOffset;
    }
    /** 4.9.9b (practice 12:04:41: the mid-game fallback potted the striker): when no route exists mid-game, search
     *  the whole upward arc every 3 degrees at two medium speeds, score own pots +1, opponent pots -0.6 and a striker
     *  foul -3, then re-test the best five with aim/power noise. Returns {ux,uy,speed} or null. */
    public static float[] safeFallback(GameState s,float w,long budgetNanos){
        if(s==null||s.board==null||s.striker==null||s.coins.isEmpty())return null;
        long deadline=System.nanoTime()+budgetNanos;
        GameState sim=ShotLogger.copy(s);
        boolean[] own=new boolean[s.coins.size()];
        for(int i=0;i<own.length;i++){Coin c=s.coins.get(i);own[i]=c.targetEligible||c.isQueen;sim.coins.get(i).targetEligible=true;}
        java.util.List<double[]> cand=new java.util.ArrayList<>();
        for(float sp:new float[]{1.6f,1.9f})for(double deg=195;deg<=345;deg+=3){
            if(System.nanoTime()>deadline)break;
            double a=Math.toRadians(deg);PhysicsVerifier.Outcome o=PhysicsVerifier.run(sim,Math.cos(a),Math.sin(a),sp*w,.80,-1);
            if(!o.completed||o.firstContact<0)continue;
            cand.add(new double[]{score(o,own),deg,sp});
        }
        if(cand.isEmpty())return null;
        cand.sort((x,y)->Double.compare(y[0],x[0]));
        double[] best=null;double bestScore=-1e9;
        for(int k=0;k<Math.min(5,cand.size());k++){
            double[] c=cand.get(k);java.util.Random r=new java.util.Random(0x5AFEL+k);double sum=0;int n=0;
            for(int t=0;t<5&&System.nanoTime()<deadline+budgetNanos/2;t++){
                double a=Math.toRadians(c[1]+r.nextGaussian());
                PhysicsVerifier.Outcome o=PhysicsVerifier.run(sim,Math.cos(a),Math.sin(a),(float)(c[2]*w*(.9+.2*r.nextDouble())),.80,-1);
                if(!o.completed)continue;sum+=score(o,own);n++;
            }
            double v=n==0?c[0]:sum/n;if(v>bestScore){bestScore=v;best=c;}
        }
        if(best==null)return null;
        double a=Math.toRadians(best[1]);return new float[]{(float)Math.cos(a),(float)Math.sin(a),(float)best[2]};
    }
    private static double score(PhysicsVerifier.Outcome o,boolean[] own){
        double v=o.strikerActive?0:-3;
        for(int i=0;i<own.length;i++)if(!o.active[i])v+=own[i]?1:-.6;
        return v;
    }
}
