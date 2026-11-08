import com.bitaim.carromaim.cv.*;
import org.opencv.core.*;import org.opencv.imgcodecs.Imgcodecs;import org.opencv.imgproc.Imgproc;
import java.io.*;import java.util.*;

/**
 * Reality-gap Monte Carlo. For each screenshot, take the production ranking (top three routes) and replay
 * every route under random "real game" conditions the nominal model ignores:
 *   aim error ~ N(0, sigma) degrees (hand alignment), launch speed x U(0.6, 1.6) (player power),
 *   pocket capture radius U(0.032, 0.050) board widths, drag retention U(0.60, 0.80)/s, rail restitution U(0.84, 0.94).
 * Reports per-route success rate next to the app's Model % so over-optimistic route families are visible.
 * Model-only: the distributions are assumptions, not measurements of Carrom Pool.
 */
public class RealityGapBench {
    public static void main(String[] a) throws Exception {
        nu.pattern.OpenCV.loadLocally();
        int samples=Integer.getInteger("gap.samples",300);double sigma=Double.parseDouble(System.getProperty("gap.aimSigma","1.0"));
        String out=System.getProperty("gap.out","out/gap");new File(out).mkdirs();
        PrintWriter csv=new PrintWriter(new FileWriter(out+"/routes.csv"));
        csv.println("image,rank,label,kind,wall,chain,modelPct,tolerance,speedBoards,real,realCaptureTight,realAimOnly,realSpeedOnly");
        Map<String,double[]> byFamily=new TreeMap<>();
        double baseF=PhysicsVerifier.FRICTION,baseC=PhysicsVerifier.CAPTURE_FRAC;
        for(String f:a){
            int key=f.contains("live-")?TargetColourLock.DARK:TargetColourLock.AUTO;
            BoardDetector d=new BoardDetector();d.setTargetColourKey(key);
            Mat bgr=Imgcodecs.imread(f),rgba=new Mat();Imgproc.cvtColor(bgr,rgba,Imgproc.COLOR_BGR2RGBA);
            GameState s=d.detectMat(rgba);d.close();
            String name=new File(f).getName().replace("_18c8b2b8ccca8eded9a08ff8021e764b","");
            if(s==null||!s.strikerDetected||!s.teamKnown)continue;
            List<ShotPlanner.Shot> routes=RoutePlanner.routes(s,null);
            Random rnd=new Random(name.hashCode());
            for(int rank=0;rank<Math.min(3,routes.size());rank++){
                ShotPlanner.Shot r=routes.get(rank);if(r.kind>=4)continue;
                float speed=r.assumedLaunchSpeed;
                int ok=0,okTight=0,okAim=0,okSpeed=0;
                for(int i=0;i<samples;i++){
                    double aim=rnd.nextGaussian()*sigma,mult=.6+rnd.nextDouble()*1.0;
                    double capture=.032+rnd.nextDouble()*.018,keep=.60+rnd.nextDouble()*.20,wall=.84+rnd.nextDouble()*.10;
                    PhysicsVerifier.CAPTURE_FRAC=capture;PhysicsVerifier.FRICTION=-Math.log(keep);
                    if(PhysicsVerifier.simulate(s,r,(float)(speed*mult),aim,wall))ok++;
                    PhysicsVerifier.CAPTURE_FRAC=.036;PhysicsVerifier.FRICTION=baseF;
                    if(PhysicsVerifier.simulate(s,r,speed,0,.90))okTight++;
                    PhysicsVerifier.CAPTURE_FRAC=baseC;
                    if(PhysicsVerifier.simulate(s,r,speed,aim,.90))okAim++;
                    if(PhysicsVerifier.simulate(s,r,(float)(speed*mult),0,.90))okSpeed++;
                }
                PhysicsVerifier.CAPTURE_FRAC=baseC;PhysicsVerifier.FRICTION=baseF;
                int modelPct=Math.round(r.robustnessPassed*100f/Math.max(1,r.robustnessTrials));
                String label=RoutePlanner.label(r);
                csv.printf(Locale.US,"%s,%d,%s,%d,%d,%d,%d,%.1f,%.2f,%.0f,%.0f,%.0f,%.0f%n",name,rank+1,label,r.kind,r.wall,r.chain.size(),modelPct,r.aimMarginDegrees,speed/s.board.width(),ok*100.0/samples,okTight*100.0/samples,okAim*100.0/samples,okSpeed*100.0/samples);
                String fam=r.kind==0?"direct":r.kind==1?(r.chain.size()>1?"cushion+combo":"striker cushion"):r.kind==2?"combination":"coin cushion";
                double[] acc=byFamily.computeIfAbsent(fam,k->new double[6]);
                acc[0]++;acc[1]+=modelPct;acc[2]+=ok*100.0/samples;acc[3]+=okTight*100.0/samples;acc[4]+=okAim*100.0/samples;acc[5]+=okSpeed*100.0/samples;
                System.out.printf(Locale.US,"%-36s #%d %-30s model %3d%%  real %3.0f%%  (tight pocket %3.0f%%, aim-only %3.0f%%, power-only %3.0f%%)  tol %.1f%n",name,rank+1,label,modelPct,ok*100.0/samples,okTight*100.0/samples,okAim*100.0/samples,okSpeed*100.0/samples,r.aimMarginDegrees);
            }
        }
        csv.close();
        System.out.println("\nBY FAMILY (mean over routes): n | app Model % | real % | tight-pocket % | aim-error-only % | power-only %");
        for(Map.Entry<String,double[]> e:byFamily.entrySet()){double[] v=e.getValue();System.out.printf(Locale.US,"  %-16s n=%2.0f  model %3.0f%%  real %3.0f%%  tightPocket %3.0f%%  aimOnly %3.0f%%  powerOnly %3.0f%%%n",e.getKey(),v[0],v[1]/v[0],v[2]/v[0],v[3]/v[0],v[4]/v[0],v[5]/v[0]);}
    }
}
