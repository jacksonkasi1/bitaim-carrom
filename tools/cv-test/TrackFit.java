import java.nio.file.*;import java.util.*;

/**
 * Equations of motion from BitAim's own motion tracks (shots.jsonl "track", debug recording, 4.8.6+).
 * 1) Friction law, model-free: for a disc sliding freely to rest, speed v and remaining distance D obey
 *    v = k D (viscous drag, exponent 1) or v^2 = 2 a D (constant Coulomb friction, exponent 0.5). Fits the exponent.
 * 2) Launch speed per pull: the striker's straight first leg extrapolated back to release.
 * Units: board widths and seconds. Usage: java TrackFit shots.jsonl
 */
public class TrackFit {
    static final class Det{double x,y;int c;Det(double x,double y,int c){this.x=x;this.y=y;this.c=c;}}
    static final class Track{List<double[]> p=new ArrayList<>();int c;boolean striker;double[] last(){return p.get(p.size()-1);}}
    public static void main(String[] a)throws Exception{
        List<double[]> pairs=new ArrayList<>(); // log D, log v
        List<String> launches=new ArrayList<>();
        int records=0,framesTotal=0;List<Double> gaps=new ArrayList<>();
        for(String line:Files.readAllLines(Paths.get(a[0]))){
            if(!line.startsWith("{")||!line.contains("\"track\":"))continue;
            records++;
            double[] board=ShotLogCalibrator.nums(ShotLogCalibrator.field(line,"board"));double w=board[2];
            double[] st=ShotLogCalibrator.nums(ShotLogCalibrator.field(line,"striker"));
            double[] aim=ShotLogCalibrator.nums(ShotLogCalibrator.field(line,"autoAim"));if(aim.length<2)aim=ShotLogCalibrator.nums(ShotLogCalibrator.field(line,"aim"));
            String pv=ShotLogCalibrator.field(line,"autoPull");double pull=pv==null||pv.startsWith("null")?Double.NaN:ShotLogCalibrator.nums(pv)[0];
            String tr=ShotLogCalibrator.field(line,"track");
            List<double[]> frames=new ArrayList<>();
            // split "[[..],[..]]" into frames
            int depth=0,start=-1;for(int i=0;i<tr.length();i++){char ch=tr.charAt(i);if(ch=='['){depth++;if(depth==2)start=i;}else if(ch==']'){if(depth==2)frames.add(ShotLogCalibrator.nums(tr.substring(start,i+1)));depth--;}}
            framesTotal+=frames.size();
            for(int i=1;i<frames.size();i++)gaps.add(frames.get(i)[0]-frames.get(i-1)[0]);
            // ---- tracking: nearest neighbour with constant-velocity prediction, same colour for discs
            List<Track> tracks=new ArrayList<>();
            for(double[] f:frames){
                double t=f[0]/1000.0;List<Det> dets=new ArrayList<>();
                if(f[1]>=0)dets.add(new Det(f[1]/w,f[2]/w,-999));
                for(int i=3;i+2<f.length;i+=3)dets.add(new Det(f[i]/w,f[i+1]/w,(int)f[i+2]));
                boolean[] used=new boolean[dets.size()];
                List<double[]> cand=new ArrayList<>();
                for(int ti=0;ti<tracks.size();ti++){Track k=tracks.get(ti);double[] L=k.last();if(t-L[0]>.6)continue;
                    double px=L[1],py=L[2];if(k.p.size()>=2){double[] M=k.p.get(k.p.size()-2);double dt=L[0]-M[0];if(dt>0){px+=(L[1]-M[1])/dt*(t-L[0]);py+=(L[2]-M[2])/dt*(t-L[0]);}}
                    for(int di=0;di<dets.size();di++){Det d=dets.get(di);if(d.c!=k.c&&!(k.striker||d.c==-999))continue;double dd=Math.hypot(d.x-px,d.y-py);if(dd<.12)cand.add(new double[]{dd,ti,di});}}
                cand.sort(Comparator.comparingDouble(x->x[0]));
                boolean[] tUsed=new boolean[tracks.size()];
                for(double[] c:cand){int ti=(int)c[1],di=(int)c[2];if(tUsed[ti]||used[di])continue;tUsed[ti]=used[di]=true;Det d=dets.get(di);tracks.get(ti).p.add(new double[]{t,d.x,d.y});}
                for(int di=0;di<dets.size();di++)if(!used[di]){Det d=dets.get(di);Track k=new Track();k.c=d.c;k.striker=d.c==-999;k.p.add(new double[]{t,d.x,d.y});tracks.add(k);}
            }
            // ---- 1) free slide to rest: from the last sharp turn (collision) to the stop
            for(Track k:tracks){
                List<double[]> p=k.p;if(p.size()<5)continue;
                double total=0;for(int i=1;i<p.size();i++)total+=Math.hypot(p.get(i)[1]-p.get(i-1)[1],p.get(i)[2]-p.get(i-1)[2]);
                if(total<.05)continue;
                int begin=0;
                for(int i=p.size()-2;i>=1;i--){
                    double ax=p.get(i)[1]-p.get(i-1)[1],ay=p.get(i)[2]-p.get(i-1)[2],bx=p.get(i+1)[1]-p.get(i)[1],by=p.get(i+1)[2]-p.get(i)[2];
                    double la=Math.hypot(ax,ay),lb=Math.hypot(bx,by);
                    if(la>.004&&lb>.004&&(ax*bx+ay*by)/(la*lb)<Math.cos(Math.toRadians(12))){begin=i;break;}
                    // speed jump up = a hit
                    double dta=p.get(i)[0]-p.get(i-1)[0],dtb=p.get(i+1)[0]-p.get(i)[0];if(dta>0&&dtb>0&&lb/dtb>1.3*la/dta+.05){begin=i;break;}
                }
                double[] rest=p.get(p.size()-1);
                for(int i=begin+1;i<p.size()-1;i++){
                    double dt=p.get(i+1)[0]-p.get(i-1)[0];if(dt<=0)continue;
                    double v=Math.hypot(p.get(i+1)[1]-p.get(i-1)[1],p.get(i+1)[2]-p.get(i-1)[2])/dt;
                    double rem=0;for(int j=i;j<p.size()-1;j++)rem+=Math.hypot(p.get(j+1)[1]-p.get(j)[1],p.get(j+1)[2]-p.get(j)[2]);
                    if(v>.03&&rem>.01)pairs.add(new double[]{Math.log(rem),Math.log(v),rem,v});
                }
            }
            // ---- 2) striker launch: track starting near the recorded striker that moves along the aim
            Track best=null;double bestD=1e9;
            for(Track k:tracks){double[] f=k.p.get(0);double d=Math.hypot(f[1]-st[0]/w,f[2]-st[1]/w);if(k.p.size()>=3&&d<bestD){bestD=d;best=k;}}
            if(best!=null&&!Double.isNaN(pull)){
                List<double[]> leg=new ArrayList<>();double sx=st[0]/w,sy=st[1]/w;
                for(double[] q:best.p){double along=(q[1]-sx)*aim[0]+(q[2]-sy)*aim[1],perp=Math.abs(-(q[1]-sx)*aim[1]+(q[2]-sy)*aim[0]);if(perp>.03)break;leg.add(new double[]{q[0],along});}
                if(leg.size()>=3){double[] f0=leg.get(0),f1=leg.get(leg.size()-1);double v=(f1[1]-f0[1])/(f1[0]-f0[0]);
                    launches.add(String.format(Locale.US,"pull %.3f  first-leg mean speed %.2f bw/s over %d frames (%.0f ms)",pull,v,leg.size(),(f1[0]-f0[0])*1000));}
            }
        }
        Collections.sort(gaps);
        System.out.printf(Locale.US,"records with tracks %d, frames %d, median frame gap %.0f ms%n",records,framesTotal,gaps.isEmpty()?Double.NaN:gaps.get(gaps.size()/2));
        if(pairs.size()>=5){
            double mx=0,my=0;for(double[] q:pairs){mx+=q[0];my+=q[1];}mx/=pairs.size();my/=pairs.size();
            double sxy=0,sxx=0;for(double[] q:pairs){sxy+=(q[0]-mx)*(q[1]-my);sxx+=(q[0]-mx)*(q[0]-mx);}
            double alpha=sxy/sxx,c=Math.exp(my-alpha*mx);
            System.out.printf(Locale.US,"free-slide samples %d: v = %.3f * D^%.2f  (1.0 = viscous drag, 0.5 = constant friction)%n",pairs.size(),c,alpha);
            // both pure models with their best constant
            double kv=0,n=0,av=0;for(double[] q:pairs){kv+=q[3]/q[2];av+=q[3]*q[3]/(2*q[2]);n++;}
            double ev=0,ea=0;kv/=n;av/=n;for(double[] q:pairs){ev+=Math.pow(Math.log(q[3]/(kv*q[2])),2);ea+=Math.pow(Math.log(q[3]/Math.sqrt(2*av*q[2])),2);}
            System.out.printf(Locale.US,"   viscous k=%.2f/s rms log error %.3f;  constant friction a=%.2f bw/s^2 rms log error %.3f%n",kv,Math.sqrt(ev/n),av,Math.sqrt(ea/n));
        }
        for(String s:launches)System.out.println("  "+s);
    }
}
