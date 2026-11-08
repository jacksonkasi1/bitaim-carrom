import com.bitaim.carromaim.cv.*;import org.opencv.core.*;import org.opencv.imgcodecs.Imgcodecs;import org.opencv.imgproc.Imgproc;
/** Live 2026-11-08 09:2x (purple theme, online): black opponent coins with an orange centre were invisible to the
 *  detector, so routes went straight through them. Blue lock: 4 own blue targets and 3 opponent obstacles. */
public class OrangeSkinRegression{static void check(boolean v,String m){if(!v)throw new AssertionError(m);}
 public static void main(String[] a){nu.pattern.OpenCV.loadLocally();
  Mat bgr=Imgcodecs.imread(a[0]),rgba=new Mat();Imgproc.cvtColor(bgr,rgba,Imgproc.COLOR_BGR2RGBA);
  BoardDetector det=new BoardDetector();det.setTargetColourKey(205);GameState s=det.detectMat(rgba);
  check(s!=null,"board detected");int own=0,opp=0;
  for(Coin c:s.coins){if(c.targetEligible)own++;else opp++;}
  float[][] oppAt={{609,1176},{788,1339},{325,1652}};
  for(float[] p:oppAt){boolean found=false;for(Coin c:s.coins)if(!c.targetEligible&&Math.hypot(c.pos.x-p[0],c.pos.y-p[1])<20)found=true;check(found,"orange-centre opponent coin at "+p[0]+","+p[1]+" must be an obstacle");}
  check(own==4,"four blue targets, got "+own);check(opp==3,"three opponent obstacles, got "+opp);
  System.out.println("PASS: orange-centre opponent coins detected as obstacles (3/3), blue targets 4/4");}}
