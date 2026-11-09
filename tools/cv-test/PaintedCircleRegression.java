import com.bitaim.carromaim.cv.*;import org.opencv.core.*;import org.opencv.imgcodecs.Imgcodecs;import org.opencv.imgproc.Imgproc;
/** Live 2026-11-09 22:41 (points mode, green-circle board skin, user: "it's confused with this board style"): the seven
 *  painted green baseline-end circles were read as opponent discs and blocked every opening route. Flat coloured
 *  paint (value sd 4-6) is not a disc; real discs measure 17+, white/black discs are never paint. Board-only crop. */
public class PaintedCircleRegression{static void check(boolean v,String m){if(!v)throw new AssertionError(m);}
 public static void main(String[] a){nu.pattern.OpenCV.loadLocally();
  Mat bgr=Imgcodecs.imread(a[0]),rgba=new Mat();Imgproc.cvtColor(bgr,rgba,Imgproc.COLOR_BGR2RGBA);
  BoardDetector d=new BoardDetector();GameState s=null;for(int i=0;i<2;i++)s=d.detectMat(rgba);
  check(s!=null,"board detected");
  float[][] paint={{260,182},{818,182},{888,252},{888,810},{818,882},{190,252},{185,818}};
  for(float[] p:paint)for(Coin c:s.coins)check(Math.hypot(c.pos.x-p[0],c.pos.y-p[1])>c.radius,"painted circle at "+p[0]+","+p[1]+" must not be a disc");
  int cluster=0;for(Coin c:s.coins)if(Math.hypot(c.pos.x-540,c.pos.y-535)<200)cluster++;
  check(cluster>=24,"the opening cluster's discs are all kept ("+cluster+")");
  System.out.println("PASS: 7 painted green circles rejected, "+cluster+" cluster discs kept");}}
