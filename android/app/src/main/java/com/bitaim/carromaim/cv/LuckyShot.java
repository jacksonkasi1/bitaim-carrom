package com.bitaim.carromaim.cv;

import android.graphics.PointF;
import android.graphics.RectF;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.imgproc.Moments;

import java.util.ArrayList;
import java.util.List;

/**
 * 4.11.0: "Lucky Shot" mini-game (live screenshot 2026-11-08 20:31): a dark red board with no pockets, one gold puck,
 * blocker discs, and a ring target (teal outer ring, red middle ring, yellow centre). The goal is to make the gold puck
 * STOP on the target, so the planner searches striker spot, direction and power in the physics model and keeps the shot
 * whose resting point stays closest to the centre when power and aim are a little off.
 */
public final class LuckyShot {
    private LuckyShot(){}

    public static final class Scene {
        public RectF board;
        public PointF target; public float ringR;
        public PointF puck; public float puckR;
        public final List<PointF> blockers=new ArrayList<>(); public float blockerR;
        public PointF striker; public float strikerR; public boolean strikerFound; public float baseY;
        /** The game's white aim dots while aiming: launch direction and dot count (0 = not seen). */
        public float guideX=Float.NaN,guideY=Float.NaN;public int guideDots;
        public void scale(float sx,float sy){
            float k=(sx+sy)*.5f;
            board=new RectF(board.left*sx,board.top*sy,board.right*sx,board.bottom*sy);
            target=new PointF(target.x*sx,target.y*sy);ringR*=k;
            puck=new PointF(puck.x*sx,puck.y*sy);puckR*=k;
            for(PointF p:blockers)p.set(p.x*sx,p.y*sy);blockerR*=k;
            striker=new PointF(striker.x*sx,striker.y*sy);strikerR*=k;baseY*=sy;
        }
        /** Same board and same striker spot (the plan is made from where the striker is). */
        public boolean same(Scene o){
            if(!sameBoard(o)||o.strikerFound!=strikerFound)return false;
            return !strikerFound||Math.abs(o.striker.x-striker.x)<=strikerR*.3f;
        }
        /** Same puck, target and blockers (striker ignored). */
        public boolean sameBoard(Scene o){
            if(o==null||o.blockers.size()!=blockers.size())return false;
            float tol=board.width()*.012f;
            if(d(o.puck,puck)>tol||d(o.target,target)>tol||Math.abs(o.board.left-board.left)>tol*2||Math.abs(o.board.width()-board.width())>tol*2)return false;
            for(PointF p:blockers){boolean found=false;for(PointF q:o.blockers)if(d(p,q)<tol){found=true;break;}if(!found)return false;}
            return true;
        }
    }

    public static final class Plan {
        public float strikerX,baseY,ux,uy,speed;
        /** Pull-back distance in screen pixels (behind the striker, opposite the aim) and as a share of full power. */
        public float pullPx,powerPercent;
        public final List<PointF> strikerPath=new ArrayList<>(),puckPath=new ArrayList<>();
        public PointF rest;
        public float error,spread;
        public String zone,kind;
        /** Plan from a better striker spot when that is clearly more forgiving (null otherwise). */
        public Plan better;
    }

    /** Plans each new board once on a low-priority worker; the striker moving does not restart it. */
    public static final class Planner {
        private Scene planned,pending;private Plan plan;private Thread worker;
        /** The plan for this board, or null while it is being searched (see {@link #busy()}) or when none exists. */
        public synchronized Plan offer(Scene s){
            if(s==null)return null;
            if(planned!=null&&planned.same(s))return plan;
            // 4.12.2: a striker missed for a frame or two (animated halo) must not cancel a search on the same board.
            if(worker!=null&&worker.isAlive()){if(pending!=null&&(pending.same(s)||pending.sameBoard(s)&&(!s.strikerFound||!pending.strikerFound)))return null;worker.interrupt();}
            planned=null;plan=null;pending=s;
            final Scene job=s;
            worker=new Thread(()->{
                Plan p=null;try{p=LuckyShot.plan(job);}catch(Throwable ignored){}
                synchronized(Planner.this){if(pending==job&&!Thread.currentThread().isInterrupted()){planned=job;plan=p;pending=null;}}
            },"BitAim-lucky");
            worker.setPriority(Thread.MIN_PRIORITY);worker.start();
            return null;
        }
        public synchronized boolean busy(){return worker!=null&&worker.isAlive();}
        public synchronized void reset(){if(worker!=null)worker.interrupt();worker=null;planned=pending=null;plan=null;}
    }

    static float d(PointF a,PointF b){return (float)Math.hypot(a.x-b.x,a.y-b.y);}

    /** Ring sizes relative to the teal ring (measured on the 20:31 screenshot: 155 / 87 / 30 px). */
    public static final float RED_FRAC=.56f,YELLOW_FRAC=.20f;
    /** Full power pull in board widths (the game's power ring, see AutoPlayController). */
    public static final float FULL_PULL=.25f;

    /** Detect a Lucky Shot board on the processed HSV/gray frame; coordinates returned in source pixels. */
    public static Scene detect(Mat hsv,Mat gray,float scale){
        int W=hsv.cols(),H=hsv.rows();
        Mat m1=new Mat(),m2=new Mat();
        try{
            Core.inRange(hsv,new Scalar(0,105,84),new Scalar(9,220,185),m1);
            Core.inRange(hsv,new Scalar(172,105,84),new Scalar(180,220,185),m2);
            Core.bitwise_or(m1,m2,m1);
            Mat k=Imgproc.getStructuringElement(Imgproc.MORPH_RECT,new Size(15,15));
            Imgproc.morphologyEx(m1,m1,Imgproc.MORPH_CLOSE,k);k.release();
            Rect b=largest(m1);
            if(b==null||b.area()<W*H*.08)return null;
            float ratio=b.width/(float)b.height;if(ratio<.8f||ratio>1.25f)return null;
            float w=b.width;
            // Target: the teal ring must be on the board (the reward panel above shows a small copy of it).
            Rect roi=clip(new Rect(b.x,b.y,b.width,b.height),W,H);
            Core.inRange(hsv.submat(roi),new Scalar(78,110,110),new Scalar(96,255,255),m2);
            List<MatOfPoint> cs=new ArrayList<>();Mat hier=new Mat();
            Imgproc.findContours(m2,cs,hier,Imgproc.RETR_EXTERNAL,Imgproc.CHAIN_APPROX_SIMPLE);hier.release();
            float[] ring=null;double ringPix=0;
            for(MatOfPoint c:cs){
                MatOfPoint2f f=new MatOfPoint2f(c.toArray());Point ctr=new Point();float[] rr=new float[1];
                Imgproc.minEnclosingCircle(f,ctr,rr);f.release();
                double pix=c.total();
                if(rr[0]>w*.07f&&rr[0]<w*.32f&&pix>ringPix){ringPix=pix;ring=new float[]{(float)ctr.x+roi.x,(float)ctr.y+roi.y,rr[0]};}
                c.release();
            }
            if(ring==null)return null;
            Scene s=new Scene();
            s.board=new RectF(b.x,b.y,b.x+b.width,b.y+b.height);
            s.ringR=ring[2];s.target=new PointF(ring[0],ring[1]);
            // Centre: the yellow spot inside the ring is more exact than the ring outline (a rail can clip the ring).
            Mat yellow=new Mat();Core.inRange(hsv,new Scalar(14,120,110),new Scalar(36,255,255),yellow);
            float[] spot=blob(yellow,ring[0],ring[1],ring[2]*.45f);
            if(spot!=null&&spot[3]>3)s.target.set(spot[0],spot[1]);
            // Gold puck: the largest round yellow blob away from the target.
            float[] puck=null;
            Mat ysub=yellow.submat(roi).clone();
            Imgproc.circle(ysub,new Point(s.target.x-roi.x,s.target.y-roi.y),Math.round(s.ringR*1.04f),new Scalar(0),-1);
            cs.clear();hier=new Mat();Imgproc.findContours(ysub,cs,hier,Imgproc.RETR_EXTERNAL,Imgproc.CHAIN_APPROX_SIMPLE);hier.release();ysub.release();
            double bestA=0;
            for(MatOfPoint c:cs){
                MatOfPoint2f f=new MatOfPoint2f(c.toArray());Point ctr=new Point();float[] rr=new float[1];
                Imgproc.minEnclosingCircle(f,ctr,rr);f.release();double a=Imgproc.contourArea(c);c.release();
                if(rr[0]<w*.017f||rr[0]>w*.05f||a<Math.PI*rr[0]*rr[0]*.45)continue;
                if(a>bestA){bestA=a;puck=new float[]{(float)ctr.x+roi.x,(float)ctr.y+roi.y,rr[0]};}
            }
            yellow.release();
            if(puck==null)return null;
            s.puck=new PointF(puck[0],puck[1]);s.puckR=puck[2];
            // Striker on the baseline.
            s.baseY=b.y+b.height*.845f;
            s.strikerR=w*.036f;s.striker=new PointF(b.x+w*.5f,s.baseY);
            int sy0=Math.max(0,(int)(s.baseY-w*.08f)),sy1=Math.min(H,(int)(s.baseY+w*.08f));
            int sx0=Math.max(0,(int)(b.x+w*.05f)),sx1=Math.min(W,(int)(b.x+w*.95f));
            Mat sc=new Mat();Imgproc.HoughCircles(gray.submat(sy0,sy1,sx0,sx1),sc,Imgproc.HOUGH_GRADIENT,1.2,w*.08,90,18,Math.round(w*.030f),Math.round(w*.058f));
            // 4.12.2 (live 2026-11-09 12:12): the target's yellow centre lies in this strip when the target is near the
            // baseline and was taken as the striker, so auto play pulled from the target and nothing happened. Nothing
            // inside the target rings is the striker; of the rest, the disc closest to the baseline wins.
            float bestOff=Float.MAX_VALUE;
            for(int i=0;i<sc.cols();i++){
                double[] c=sc.get(0,i);float cx=(float)c[0]+sx0,cy=(float)c[1]+sy0;
                PointF p=new PointF(cx,cy);
                if(d(p,s.target)<s.ringR*1.05f)continue;
                double[] hv=meanHsv(hsv,cx,cy,(float)c[2]*.55f);
                if(wood(hv)||d(p,s.puck)<s.puckR*1.5f)continue;
                if(hv[1]>100&&hv[0]>=13&&hv[0]<=37)continue; // yellow: puck or target centre, never the striker
                float off=Math.abs(cy-(b.y+b.height*.845f));
                if(off<bestOff){bestOff=off;s.striker=p;s.strikerR=Math.max(w*.03f,Math.min(w*.045f,(float)c[2]));s.strikerFound=true;s.baseY=cy;}
            }
            sc.release();
            if(s.strikerFound){float[] g=AimGuideDetector.detect(hsv,s.striker.x,s.striker.y,w,b.y);if(g!=null){s.guideX=g[0];s.guideY=g[1];s.guideDots=(int)g[2];}}
            // Blockers: round discs that are not wood, not yellow (puck / target centre), not teal, not the striker.
            Mat cc=new Mat();
            Imgproc.HoughCircles(gray.submat(roi),cc,Imgproc.HOUGH_GRADIENT,1.2,w*.035,90,18,Math.round(w*.019f),Math.round(w*.040f));
            float rs=0;int n=0;
            for(int i=0;i<cc.cols();i++){
                double[] c=cc.get(0,i);PointF p=new PointF((float)c[0]+roi.x,(float)c[1]+roi.y);float r=(float)c[2];
                if(d(p,s.puck)<s.puckR*1.6f)continue;
                if(Math.abs(p.y-s.baseY)<w*.06f&&d(p,s.striker)<s.strikerR*1.6f)continue;
                double[] hv=meanHsv(hsv,p.x,p.y,r*.55f);
                if(wood(hv))continue;
                if(hv[1]>100&&hv[0]>=13&&hv[0]<=37)continue; // yellow
                if(hv[1]>90&&hv[0]>=76&&hv[0]<=98)continue;  // teal ring
                boolean dup=false;for(PointF q:s.blockers)if(d(q,p)<r)dup=true;if(dup)continue;
                s.blockers.add(p);rs+=r;n++;
            }
            cc.release();
            s.blockerR=n>0?rs/n:s.puckR;
            float inv=1f/scale;
            s.scale(inv,inv);
            return s;
        }finally{m1.release();m2.release();}
    }
    static boolean wood(double[] hv){return hv[1]>100&&(hv[0]<=11||hv[0]>=170)&&hv[2]<200;}
    static Rect clip(Rect r,int W,int H){int x=Math.max(0,r.x),y=Math.max(0,r.y);return new Rect(x,y,Math.max(1,Math.min(W,r.x+r.width)-x),Math.max(1,Math.min(H,r.y+r.height)-y));}
    static Rect largest(Mat mask){
        List<MatOfPoint> cs=new ArrayList<>();Mat hier=new Mat();
        Imgproc.findContours(mask,cs,hier,Imgproc.RETR_EXTERNAL,Imgproc.CHAIN_APPROX_SIMPLE);hier.release();
        double best=0;Rect r=null;
        for(MatOfPoint c:cs){double a=Imgproc.contourArea(c);if(a>best){best=a;r=Imgproc.boundingRect(c);}c.release();}
        return r;
    }
    static double[] meanHsv(Mat hsv,float x,float y,float r){
        int x0=Math.max(0,(int)(x-r)),y0=Math.max(0,(int)(y-r)),x1=Math.min(hsv.cols(),(int)(x+r)+1),y1=Math.min(hsv.rows(),(int)(y+r)+1);
        if(x1<=x0||y1<=y0)return new double[]{0,0,0};
        Scalar m=Core.mean(hsv.submat(y0,y1,x0,x1));return new double[]{m.val[0],m.val[1],m.val[2]};
    }
    /** Centroid {x,y,r,area} of mask pixels within radius of (cx,cy). */
    static float[] blob(Mat mask,float cx,float cy,float r){
        int x0=Math.max(0,(int)(cx-r)),y0=Math.max(0,(int)(cy-r)),x1=Math.min(mask.cols(),(int)(cx+r)+1),y1=Math.min(mask.rows(),(int)(cy+r)+1);
        if(x1<=x0||y1<=y0)return null;
        Mat sub=mask.submat(y0,y1,x0,x1).clone();
        Mat circ=Mat.zeros(sub.size(),sub.type());Imgproc.circle(circ,new Point(cx-x0,cy-y0),Math.round(r),new Scalar(255),-1);
        Core.bitwise_and(sub,circ,sub);circ.release();
        Moments m=Imgproc.moments(sub,true);sub.release();
        if(m.m00<1)return null;
        return new float[]{(float)(m.m10/m.m00)+x0,(float)(m.m01/m.m00)+y0,(float)Math.sqrt(m.m00/Math.PI),(float)m.m00};
    }

    // ── Planning ─────────────────────────────────────────────────────────────

    static GameState model(Scene sc,float strikerX){
        GameState g=new GameState();RectF b=sc.board;float w=b.width();g.board=new RectF(b.left,b.top,b.right,b.bottom);
        float in=w*.05f; // PhysicsVerifier puts the rails 0.05 board widths outside the pockets: rails = board edges
        g.pockets.add(new PointF(b.left+in,b.top+in));g.pockets.add(new PointF(b.right-in,b.top+in));
        g.pockets.add(new PointF(b.left+in,b.bottom-in));g.pockets.add(new PointF(b.right-in,b.bottom-in));
        g.striker=new Coin(strikerX,sc.baseY,sc.strikerR,Coin.COLOR_STRIKER,true);g.strikerDetected=true;
        Coin puck=new Coin(sc.puck.x,sc.puck.y,sc.puckR,Coin.COLOR_WHITE,false);puck.targetEligible=true;g.coins.add(puck);
        for(PointF p:sc.blockers){Coin c=new Coin(p.x,p.y,sc.blockerR,Coin.COLOR_BLACK,false);c.targetEligible=false;g.coins.add(c);}
        return g;
    }
    /** Resting distance of the puck from the target centre, or +inf when the striker does not hit the puck first. */
    static float rest(GameState g,Scene sc,double ux,double uy,float speed,float[] out){return rest(g,sc,ux,uy,speed,.80,out);}
    static float rest(GameState g,Scene sc,double ux,double uy,float speed,double wall,float[] out){
        PhysicsVerifier.Outcome o=PhysicsVerifier.run(g,ux,uy,speed,wall,0,0,0);
        if(o.aborted||!o.completed||o.firstContact!=0||!o.active[0])return Float.POSITIVE_INFINITY;
        if(out!=null){out[0]=(float)o.x[0];out[1]=(float)o.y[0];}
        return (float)Math.hypot(o.x[0]-sc.target.x,o.y[0]-sc.target.y);
    }
    static float[] rot(float ux,float uy,double deg){double a=Math.toRadians(deg),c=Math.cos(a),s=Math.sin(a);return new float[]{(float)(ux*c-uy*s),(float)(ux*s+uy*c)};}

    public static boolean DEBUG=false;
    private static final class Cand{float x,ux,uy,speed,err,score;int puckWall,strikerWall;}

    /** Plan from the striker where it is (auto play shoots this); when a clearly more forgiving spot exists along the
     *  baseline (if the striker can be moved), it is attached as {@link Plan#better}. Run off the UI thread. */
    public static Plan plan(Scene sc){
        // 4.12.2: Lucky Shot keeps the striker where the game puts it (same spot in every round seen), so only that spot
        // is searched; the all-spots search doubled the phone's planning time. Without a striker, every spot is searched.
        return sc.strikerFound?search(sc,sc.striker.x):search(sc,Float.NaN);
    }
    /** Search striker spots (or only {@code fixedX}), directions and power; a few thousand short simulations. */
    static Plan search(Scene sc,float fixedX){
        RectF b=sc.board;float w=b.width();
        float maxSpeed=AutoPlayController.LAUNCH_PER_PULL*FULL_PULL*w,minSpeed=w*.35f;
        float rs=sc.strikerR,rp=sc.puckR;
        List<Cand> cands=new ArrayList<>();
        boolean fixed=!Float.isNaN(fixedX);
        List<Float> xs=new ArrayList<>();
        if(fixed)xs.add(fixedX);else for(float x=b.left+w*.16f;x<=b.right-w*.16f+1;x+=w*.025f)xs.add(x);
        for(float x:xs){
            if(Math.hypot(x-sc.puck.x,sc.baseY-sc.puck.y)<rs+rp+2)continue;
            boolean blocked=false;for(PointF p:sc.blockers)if(Math.hypot(x-p.x,sc.baseY-p.y)<rs+sc.blockerR+2)blocked=true;if(blocked)continue;
            if(Thread.currentThread().isInterrupted())return null;
            GameState g=model(sc,x);
            for(int pw=-1;pw<4;pw++){ // puck: straight, or off one rail
                PointF t=mirror(sc.target,pw,b,rp);
                float nx=t.x-sc.puck.x,ny=t.y-sc.puck.y,nl=(float)Math.hypot(nx,ny);if(nl<1)continue;nx/=nl;ny/=nl;
                PointF ghost=new PointF(sc.puck.x-nx*(rs+rp),sc.puck.y-ny*(rs+rp));
                for(int sw=-1;sw<4;sw++){ // striker: direct, or off the left, right, top or bottom rail
                    PointF gm=mirror(ghost,sw,b,rs);
                    float ux=gm.x-x,uy=gm.y-sc.baseY,ul=(float)Math.hypot(ux,uy);if(ul<1)continue;ux/=ul;uy/=ul;
                    // 4.12.3 (user: puck in the bottom-left corner not handled): backward shots (toward the bottom rail) are
                    // allowed; the striker can be aimed in any direction.
                    Cand best=null;
                    for(float v=minSpeed;v<=maxSpeed;v+=w*.05f){
                        float e=rest(g,sc,ux,uy,v,null);
                        if(e<Float.POSITIVE_INFINITY&&(best==null||e<best.err)){best=new Cand();best.x=x;best.ux=ux;best.uy=uy;best.speed=v;best.err=e;best.puckWall=pw;best.strikerWall=sw;}
                    }
                    if(best!=null)cands.add(best);
                }
            }
        }
        if(fixed&&!xs.isEmpty()){
            // The striker cannot be placed: also sweep every upward direction (multi-rail routes the geometry misses).
            GameState g=model(sc,fixedX);
            for(float deg=-180;deg<180;deg+=.75f){
                if(Thread.currentThread().isInterrupted())return null;
                float ux=(float)Math.cos(Math.toRadians(deg)),uy=(float)Math.sin(Math.toRadians(deg));
                Cand best=null;
                for(float v=minSpeed;v<=maxSpeed;v+=w*.06f){
                    float e=rest(g,sc,ux,uy,v,null);
                    if(e<Float.POSITIVE_INFINITY&&(best==null||e<best.err)){best=new Cand();best.x=fixedX;best.ux=ux;best.uy=uy;best.speed=v;best.err=e;best.puckWall=-2;best.strikerWall=-2;}
                }
                if(best!=null)cands.add(best);
            }
        }
        if(cands.isEmpty())return null;
        cands.sort((a,c)->Float.compare(a.err,c.err));
        // Keep the best few of every route type (puck rail x striker rail), so a short forgiving route is not crowded out
        // by long multi-rail routes that happen to stop closer in the nominal model.
        List<Cand> top=new ArrayList<>();int[] perType=new int[25];
        for(Cand c:cands){int t=c.puckWall<-1?24:(c.puckWall+1)*5+(c.strikerWall+1);if(c.err<sc.ringR*RED_FRAC&&perType[t]<(t==24?8:4)){perType[t]++;top.add(c);}if(top.size()>=40)break;}
        if(top.isEmpty())top.addAll(cands.subList(0,Math.min(10,cands.size())));
        // Refine aim and power locally, then score by how the result holds up when the shot is a little off.
        for(Cand c:top){
            if(Thread.currentThread().isInterrupted())return null;
            GameState g=model(sc,c.x);
            for(int pass=0;pass<2;pass++){
                float step=pass==0?.4f:.12f,vstep=pass==0?w*.04f:w*.012f;
                float bestErr=c.err,bux=c.ux,buy=c.uy,bv=c.speed;
                for(int da=-3;da<=3;da++)for(int dv=-3;dv<=3;dv++){
                    float[] u=rot(c.ux,c.uy,da*step);float v=Math.max(minSpeed,Math.min(maxSpeed,c.speed+dv*vstep));
                    float e=rest(g,sc,u[0],u[1],v,null);
                    if(e<bestErr){bestErr=e;bux=u[0];buy=u[1];bv=v;}
                }
                c.ux=bux;c.uy=buy;c.speed=bv;c.err=bestErr;
            }
            // Robustness: power +-8%, aim +-0.35 deg, and rail bounce 0.74-0.86 (Lucky Shot rails are not measured yet,
            // so routes that need several exact rail bounces are trusted less).
            float sum=0;int k=0;
            for(float fs:new float[]{.92f,1f,1.08f})for(float da:new float[]{-.35f,0f,.35f}){
                float[] u=rot(c.ux,c.uy,da);float e=rest(g,sc,u[0],u[1],Math.min(maxSpeed*1.1f,c.speed*fs),null);
                if(e==Float.POSITIVE_INFINITY)e=w;sum+=e*e;k++;
            }
            for(double wall:new double[]{.74,.86}){float e=rest(g,sc,c.ux,c.uy,c.speed,wall,null);if(e==Float.POSITIVE_INFINITY)e=w;sum+=e*e*2;k+=2;}
            c.score=(float)Math.sqrt(sum/k);
        }
        top.sort((a,c)->Float.compare(a.score,c.score));
        if(DEBUG)for(Cand t:top)System.out.printf(java.util.Locale.US,"cand x %.0f pw %d sw %d speed %.2f err %.0f score %.0f%n",t.x,t.puckWall,t.strikerWall,t.speed/w,t.err,t.score);
        Cand c=top.get(0);
        Plan p=new Plan();
        p.strikerX=c.x;p.baseY=sc.baseY;p.ux=c.ux;p.uy=c.uy;p.speed=c.speed;p.error=c.err;p.spread=c.score;
        float pull=c.speed/(AutoPlayController.LAUNCH_PER_PULL*w);
        p.pullPx=pull*w;p.powerPercent=Math.min(100f,100f*pull/FULL_PULL);
        // Drawn paths come from the modelled motion itself: striker up to its first touch of the puck, puck to rest.
        List<double[]> trace=new ArrayList<>();
        PhysicsVerifier.Outcome o=PhysicsVerifier.run(model(sc,c.x),c.ux,c.uy,c.speed,.80,0,0,0,PhysicsVerifier.COULOMB,1.2,trace);
        p.rest=new PointF((float)o.x[0],(float)o.y[0]);
        p.strikerPath.add(new PointF(c.x,sc.baseY));p.puckPath.add(new PointF(sc.puck.x,sc.puck.y));
        boolean touched=false;int puckRails=0,strikerRails=0;
        for(double[] e:trace){
            int body=(int)e[0];boolean contact=e[3]==1;PointF q=new PointF((float)e[1],(float)e[2]);
            if(body==0&&!touched){p.strikerPath.add(q);if(contact&&e[4]==1)touched=true;else if(!contact)strikerRails++;}
            else if(body==1){p.puckPath.add(q);if(!contact)puckRails++;}
        }
        p.puckPath.add(p.rest);
        float ringR=sc.ringR;
        p.zone=p.error<ringR*YELLOW_FRAC?"centre":p.error<ringR*RED_FRAC?"red ring":p.error<ringR?"teal ring":"outside";
        p.kind=(puckRails==0?"puck straight":"puck off "+puckRails+(puckRails==1?" rail":" rails"))+(strikerRails>0?", striker off "+strikerRails+(strikerRails==1?" rail":" rails"):"");
        return p;
    }
    static final String[] WALLS={"left","right","top","bottom"};
    /** Mirror image of p across a rail (0 left, 1 right, 2 top, 3 bottom) for a disc of radius r; -1 = p itself. */
    static PointF mirror(PointF p,int wall,RectF b,float r){
        switch(wall){
            case 0:return new PointF(2*(b.left+r)-p.x,p.y);
            case 1:return new PointF(2*(b.right-r)-p.x,p.y);
            case 2:return new PointF(p.x,2*(b.top+r)-p.y);
            case 3:return new PointF(p.x,2*(b.bottom-r)-p.y);
            default:return new PointF(p.x,p.y);
        }
    }
    static PointF railHit(PointF a,float ux,float uy,int wall,RectF b,float r){
        float t;
        switch(wall){
            case 0:if(ux>=0)return null;t=(b.left+r-a.x)/ux;break;
            case 1:if(ux<=0)return null;t=(b.right-r-a.x)/ux;break;
            case 2:if(uy>=0)return null;t=(b.top+r-a.y)/uy;break;
            default:if(uy<=0)return null;t=(b.bottom-r-a.y)/uy;break;
        }
        return t>0?new PointF(a.x+ux*t,a.y+uy*t):null;
    }
    /** Striker centre where it first touches the puck, following the (reflected) aim line. */
    static PointF contact(Scene sc,PointF s0,float ux,float uy,int wall,RectF b){
        PointF a=s0;float dx=ux,dy=uy;
        if(wall>=0){PointF h=railHit(s0,ux,uy,wall,b,sc.strikerR);if(h==null)return null;a=h;if(wall<2)dx=-dx;else dy=-dy;}
        float R=sc.strikerR+sc.puckR,fx=a.x-sc.puck.x,fy=a.y-sc.puck.y;
        float bq=fx*dx+fy*dy,cq=fx*fx+fy*fy-R*R,disc=bq*bq-cq;if(disc<0)return null;
        float t=-bq-(float)Math.sqrt(disc);if(t<0)return null;
        return new PointF(a.x+dx*t,a.y+dy*t);
    }
}
