import com.bitaim.carromaim.cv.*;
import android.graphics.PointF;
import java.nio.file.*;import java.util.*;

/**
 * Physics-form study on real shots (shots.jsonl): which equations of motion reproduce where the discs really settled?
 * Generalised model: drag dv/dt = -k v - a v^ (viscous + Coulomb), disc-disc restitution e and contact friction mu
 * (cut-shot throw), striker/coin mass ratio m, rail restitution, rail tangent retention for coins and striker,
 * pocket capture. Auto shots use launch speed = K x pull. Fits by coordinate descent on half the shots and reports
 * the other half (held out) so a better number is not just over-fitting.
 * Usage: java [-Dfree=k,a,...] MathFit shots.jsonl...
 */
public class MathFit {
    static final class P implements Cloneable{
        double k=-Math.log(.70),a=0,e=.96,mu=0,m=1.2,wall=.80,coinTan=1.0,strTan=.76,cap=.042,K=5.0;
        P copy(){try{return (P)clone();}catch(Exception x){throw new RuntimeException(x);}}
        double get(int i){switch(i){case 0:return k;case 1:return a;case 2:return e;case 3:return mu;case 4:return m;case 5:return wall;case 6:return coinTan;case 7:return strTan;case 8:return cap;default:return K;}}
        void set(int i,double v){switch(i){case 0:k=v;break;case 1:a=v;break;case 2:e=v;break;case 3:mu=v;break;case 4:m=v;break;case 5:wall=v;break;case 6:coinTan=v;break;case 7:strTan=v;break;case 8:cap=v;break;default:K=v;}}
        public String toString(){return String.format(Locale.US,"drag k=%.3f (keep %.2f/s) coulomb a=%.3f e=%.2f mu=%.2f mass=%.2f rail=%.2f coinTan=%.2f strikerTan=%.2f capture=%.3f K=%.2f",k,Math.exp(-k),a,e,mu,m,wall,coinTan,strTan,cap,K);}
    }
    static final String[] NAMES={"k","a","e","mu","m","wall","coinTan","strTan","cap","K"};
    static final double[][] RANGE={{0,1.5},{0,1.5},{.5,1},{0,.6},{.8,3.5},{.5,.98},{.5,1},{.5,1},{.03,.06},{1,16}};

    static final class Body{double x,y,vx,vy,r,mass;boolean active=true,striker;int pocket=-1;}
    /** Substepped event model (2 ms steps, exact linear collision times inside a step). Units: board widths. */
    static double[][] sim(GameState g,double ux,double uy,double speedBoards,P p,boolean[] activeOut){return sim(g,ux,uy,speedBoards,p,activeOut,null,null);}
    /** snapTimes (seconds, ascending) -> snaps[i] = {x0,y0,x1,y1,...} for striker then discs (NaN when potted). */
    static double[][] sim(GameState g,double ux,double uy,double speedBoards,P p,boolean[] activeOut,double[] snapTimes,double[][] snaps){
        double w=g.board.width();
        List<Body> bs=new ArrayList<>();
        Body s=new Body();s.x=g.striker.pos.x/w;s.y=g.striker.pos.y/w;s.r=g.striker.radius/w;s.mass=p.m;s.striker=true;s.vx=ux*speedBoards;s.vy=uy*speedBoards;bs.add(s);
        for(Coin c:g.coins){Body b=new Body();b.x=c.pos.x/w;b.y=c.pos.y/w;b.r=c.radius/w;b.mass=1;bs.add(b);}
        double off=.05,left=1e9,top=1e9,right=-1e9,bottom=-1e9;
        double[][] holes=new double[g.pockets.size()][];
        for(int i=0;i<holes.length;i++){PointF q=g.pockets.get(i);holes[i]=new double[]{q.x/w,q.y/w};left=Math.min(left,q.x/w-off);right=Math.max(right,q.x/w+off);top=Math.min(top,q.y/w-off);bottom=Math.max(bottom,q.y/w+off);}
        for(Body b:bs){b.x=Math.max(left+b.r,Math.min(right-b.r,b.x));b.y=Math.max(top+b.r,Math.min(bottom-b.r,b.y));}
        double dt=.002;int n=bs.size();int snap=0;
        for(int step=0;step<6000;step++){
            if(snapTimes!=null)while(snap<snapTimes.length&&snapTimes[snap]<=step*dt){double[] o=new double[2*n];for(int i=0;i<n;i++){Body q=bs.get(i);o[2*i]=q.active?q.x*w:Double.NaN;o[2*i+1]=q.active?q.y*w:Double.NaN;}snaps[snap++]=o;}
            boolean moving=false;for(Body b:bs)if(b.active&&(b.vx!=0||b.vy!=0)){moving=true;break;}
            if(!moving)break;
            double rem=dt;
            for(int guard=0;guard<64&&rem>0;guard++){
                double best=rem;int kind=0,ia=-1,ib=-1;
                for(int i=0;i<n;i++){Body A=bs.get(i);if(!A.active)continue;
                    for(int j=i+1;j<n;j++){Body B=bs.get(j);if(!B.active)continue;double t=entry(B.x-A.x,B.y-A.y,B.vx-A.vx,B.vy-A.vy,A.r+B.r);if(t<best){best=t;kind=1;ia=i;ib=j;}}
                    if(A.vx==0&&A.vy==0)continue;
                    for(int q=0;q<holes.length;q++){double t=entry(A.x-holes[q][0],A.y-holes[q][1],A.vx,A.vy,p.cap);if(t<best){best=t;kind=2;ia=i;ib=q;}}
                    if(A.vx<0){double t=(left+A.r-A.x)/A.vx;if(t>=0&&t<best){best=t;kind=3;ia=i;}}
                    if(A.vx>0){double t=(right-A.r-A.x)/A.vx;if(t>=0&&t<best){best=t;kind=3;ia=i;}}
                    if(A.vy<0){double t=(top+A.r-A.y)/A.vy;if(t>=0&&t<best){best=t;kind=4;ia=i;}}
                    if(A.vy>0){double t=(bottom-A.r-A.y)/A.vy;if(t>=0&&t<best){best=t;kind=4;ia=i;}}
                }
                best=Math.max(0,best);
                for(Body b:bs)if(b.active){b.x+=b.vx*best;b.y+=b.vy*best;}
                rem-=best;
                if(kind==0)break;
                Body A=bs.get(ia);
                if(kind==1)collide(A,bs.get(ib),p);
                else if(kind==2){A.active=false;A.pocket=ib;A.vx=A.vy=0;}
                else{double tan=A.striker?p.strTan:p.coinTan;if(kind==3){A.vx*=-p.wall;A.vy*=tan;}else{A.vy*=-p.wall;A.vx*=tan;}}
            }
            double keep=Math.exp(-p.k*dt);
            for(Body b:bs){if(!b.active)continue;double v=Math.hypot(b.vx,b.vy);if(v==0)continue;double nv=Math.max(0,v*keep-p.a*dt);if(nv<.002)nv=0;b.vx*=nv/v;b.vy*=nv/v;}
        }
        if(snapTimes!=null)while(snap<snapTimes.length){double[] o=new double[2*n];for(int i=0;i<n;i++){Body q=bs.get(i);o[2*i]=q.active?q.x*w:Double.NaN;o[2*i+1]=q.active?q.y*w:Double.NaN;}snaps[snap++]=o;}
        double[][] out=new double[g.coins.size()][];
        for(int i=0;i<out.length;i++){Body b=bs.get(i+1);out[i]=new double[]{b.x*w,b.y*w};if(activeOut!=null)activeOut[i]=b.active;}
        if(activeOut!=null&&activeOut.length>out.length)activeOut[out.length]=bs.get(0).active;
        return out;
    }
    static void collide(Body a,Body b,P p){
        double dx=b.x-a.x,dy=b.y-a.y,d=Math.hypot(dx,dy);if(d<1e-9)return;
        double nx=dx/d,ny=dy/d,rvx=b.vx-a.vx,rvy=b.vy-a.vy,vn=rvx*nx+rvy*ny;if(vn>=0)return;
        double inv=1/a.mass+1/b.mass,jn=-(1+p.e)*vn/inv;
        double tx=-ny,ty=nx,vt=rvx*tx+rvy*ty;
        // Coulomb contact friction, capped at the impulse that stops the sliding of two solid discs (effective 1/3).
        double jt=-Math.signum(vt)*Math.min(p.mu*jn,Math.abs(vt)/(3*inv));
        double ix=jn*nx+jt*tx,iy=jn*ny+jt*ty;
        a.vx-=ix/a.mass;a.vy-=iy/a.mass;b.vx+=ix/b.mass;b.vy+=iy/b.mass;
    }
    static double entry(double x,double y,double vx,double vy,double r){
        double A=vx*vx+vy*vy,B=x*vx+y*vy,C=x*x+y*y-r*r;
        if(A==0||B>=0)return Double.POSITIVE_INFINITY;if(C<=1e-12)return 0;
        double D=B*B-A*C;return D<0?Double.POSITIVE_INFINITY:(-B-Math.sqrt(D))/A;
    }

    static final class Shot{ShotLogCalibrator.Shot s;double pull;int target=-1;boolean targetPotted;List<double[]> track;}
    static boolean TRACKS=Boolean.getBoolean("tracks");
    /** Trajectory loss: detected moving discs at each tracked frame vs the model at that time (release offset fitted
     *  per shot over 0-300 ms), in striker radii, capped at 4. */
    static double trackLoss(Shot sh,P p,double speedBoards){
        GameState g=sh.s.before;double R=g.striker.radius,best=1e9;
        int nf=sh.track.size();double[] times=new double[nf];
        for(double t0=0;t0<=.301;t0+=.05){
            for(int i=0;i<nf;i++)times[i]=Math.max(0,sh.track.get(i)[0]/1000.0-t0);
            double[][] snaps=new double[nf][];sim(g,sh.s.ux,sh.s.uy,speedBoards,p,null,times,snaps);
            double sum=0;int cnt=0;
            for(int f=1;f<nf;f++){double[] fr=sh.track.get(f),sn=snaps[f];
                for(int i=3;i+2<fr.length;i+=3){double x=fr[i],y=fr[i+1];int col=(int)fr[i+2];
                    boolean moved=true;for(Coin c:g.coins)if(Math.hypot(c.pos.x-x,c.pos.y-y)<R*.5){moved=false;break;}
                    if(!moved)continue;
                    double m=4*R;for(int k=0;k<sn.length/2;k++){if(Double.isNaN(sn[2*k]))continue;if(k>0&&g.coins.get(k-1).color!=col)continue;m=Math.min(m,Math.hypot(sn[2*k]-x,sn[2*k+1]-y));}
                    sum+=m/R;cnt++;}}
            double l=cnt==0?0:sum/cnt;if(l<best)best=l;
        }
        return best;
    }
    static double loss(Shot sh,P p,double speedBoards,double[] potAgree){
        GameState g=sh.s.before;boolean[] act=new boolean[g.coins.size()+1];
        double[][] pos=sim(g,sh.s.ux,sh.s.uy,speedBoards,p,act);
        List<double[]> left=new ArrayList<>();int potted=0;
        for(int i=0;i<pos.length;i++){if(act[i])left.add(new double[]{pos[i][0],pos[i][1],g.coins.get(i).color});else potted++;}
        double e=ShotLogCalibrator.error(left,sh.s.after,sh.s.extra)/g.striker.radius+Math.abs(potted-sh.s.realPotted)*3;
        if(potAgree!=null&&sh.target>=0){boolean model=!act[sh.target];potAgree[0]+=model==sh.targetPotted?1:0;potAgree[1]++;}
        return e;
    }
    static double total(List<Shot> shots,P p,double[] agree){
        if(TRACKS){double s=shots.parallelStream().mapToDouble(sh->trackLoss(sh,p,p.K*sh.pull)).sum();if(agree!=null){agree[0]=0;agree[1]=0;}return s/shots.size();}
        double[] a=new double[2];
        double sum=shots.parallelStream().mapToDouble(sh->{double[] q=new double[2];double l=loss(sh,p,p.K*sh.pull,q);synchronized(a){a[0]+=q[0];a[1]+=q[1];}return l;}).sum();
        if(agree!=null){agree[0]=a[0];agree[1]=a[1];}
        return sum/shots.size();
    }
    public static void main(String[] args)throws Exception{
        List<Shot> shots=new ArrayList<>();Set<Long> seen=new HashSet<>();
        for(String file:args)for(String line:Files.readAllLines(Paths.get(file))){
            if(!line.startsWith("{"))continue;
            String pull=ShotLogCalibrator.field(line,"autoPull");if(pull==null||pull.startsWith("null"))continue;
            long t=(long)ShotLogCalibrator.nums(ShotLogCalibrator.field(line,"t"))[0];if(!seen.add(t))continue;
            Shot sh=new Shot();sh.s=ShotLogCalibrator.parse(line);double[] pv=ShotLogCalibrator.nums(pull);if(pv.length==0)continue;sh.pull=pv[0];
            if(Double.isNaN(sh.s.ux)||Math.hypot(sh.s.ux,sh.s.uy)<.5||sh.pull<=0||sh.s.after.length==0)continue;
            int ri=line.indexOf("\"autoRoute\":{");
            if(ri>=0)try{String route=line.substring(ri);double[] tg=ShotLogCalibrator.nums(ShotLogCalibrator.field(route,"target"));String own=ShotLogCalibrator.field(line,"ownPotted");
                if(tg.length>=2&&own!=null){double bd=1e9;GameState g=sh.s.before;for(int i=0;i<g.coins.size();i++){double d=Math.hypot(g.coins.get(i).pos.x-tg[0],g.coins.get(i).pos.y-tg[1]);if(d<bd){bd=d;sh.target=i;}}
                    if(bd>20)sh.target=-1;else sh.targetPotted=ShotLogCalibrator.nums(own)[0]>=1&&!line.contains("\"target\":\"not-moved\"");}}catch(NumberFormatException ignored){sh.target=-1;}
            if(TRACKS){String tr=ShotLogCalibrator.field(line,"track");if(tr==null)continue;sh.track=new ArrayList<>();int depth=0,st=-1;for(int i=0;i<tr.length();i++){char ch=tr.charAt(i);if(ch=='['){depth++;if(depth==2)st=i;}else if(ch==']'){if(depth==2)sh.track.add(ShotLogCalibrator.nums(tr.substring(st,i+1)));depth--;}}if(sh.track.size()<4)continue;}
            shots.add(sh);
        }
        List<Shot> train=new ArrayList<>(),test=new ArrayList<>();
        for(int i=0;i<shots.size();i++)(i%2==0?train:test).add(shots.get(i));
        System.out.println("auto shots with settled positions: "+shots.size()+" (train "+train.size()+", held out "+test.size()+")");
        P cur=new P();cur.K=fitOneValue(train,cur,9);
        report("shipping form (K refit)",cur,train,test);
        for(int i=0;i<10;i++){String v=System.getProperty("init."+NAMES[i]);if(v!=null)cur.set(i,Double.parseDouble(v));}
        if(System.getProperty("init.a")!=null)cur.K=fitOneValue(train,cur,9);
        report("start",cur,train,test);
        boolean[] free=new boolean[10];
        for(String arg:System.getProperty("free","k,a,e,mu,m,wall,coinTan,strTan,cap,K").split(","))for(int i=0;i<10;i++)if(NAMES[i].equals(arg))free[i]=true;
        P p=cur.copy();
        double prev=total(train,p,null);
        for(int round=0;round<4;round++){
            for(int i=0;i<10;i++)if(free[i])p.set(i,fitOneValue(train,p,i));
            double now=total(train,p,null);System.out.printf(Locale.US,"round %d train loss %.3f  %s%n",round,now,p);
            if(prev-now<.005)break;prev=now;
        }
        report("generalised fit",p,train,test);
    }
    /** 1-D search: grid of 9 over the range, then two refinements around the best. */
    static double fitOneValue(List<Shot> shots,P p,int i){
        double lo=RANGE[i][0],hi=RANGE[i][1],bestV=p.get(i);P q=p.copy();double bestL=total(shots,q,null);
        for(int level=0;level<3;level++){
            for(int g=0;g<=8;g++){double v=lo+(hi-lo)*g/8;q.set(i,v);double l=total(shots,q,null);if(l<bestL-1e-9){bestL=l;bestV=v;}}
            double span=(hi-lo)/8;lo=Math.max(RANGE[i][0],bestV-span);hi=Math.min(RANGE[i][1],bestV+span);
        }
        return bestV;
    }
    static void report(String name,P p,List<Shot> train,List<Shot> test){
        double[] a=new double[2],b=new double[2];double lt=total(train,p,a),lh=total(test,p,b);
        System.out.printf(Locale.US,"%s: %s%n   settle error train %.3f held-out %.3f (radii + 3/pot mismatch); target pot agreement train %.0f/%.0f held-out %.0f/%.0f%n",name,p,lt,lh,a[0],a[1],b[0],b[1]);
    }
}
