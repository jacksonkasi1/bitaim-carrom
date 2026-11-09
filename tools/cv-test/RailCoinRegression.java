import com.bitaim.carromaim.cv.*;import org.opencv.core.*;import org.opencv.imgcodecs.Imgcodecs;import org.opencv.imgproc.Imgproc;
import java.util.List;
/** Live 2026-11-09 12:17:32 (lost match, auto play idle, "no verified route"): the last own blue coin touched the left
 *  rail, where this skin's rail band is darker than the wood, and was dropped; the painted red circles of the board
 *  were read as discs. With the playing area taken from the pockets (two are enough) and flat red paint rejected,
 *  the blue coin is the one target and a route to it exists. Board-only crop, no player names. */
public class RailCoinRegression{static void check(boolean v,String m){if(!v)throw new AssertionError(m);}
 public static void main(String[] a){nu.pattern.OpenCV.loadLocally();
  Mat bgr=Imgcodecs.imread(a[0]),rgba=new Mat();Imgproc.cvtColor(bgr,rgba,Imgproc.COLOR_BGR2RGBA);
  BoardDetector det=new BoardDetector();det.setTargetColourKey(205);GameState s=det.detectMat(rgba);
  check(s!=null&&s.strikerDetected,"board and striker detected");
  int own=0;Coin target=null;for(Coin c:s.coins)if(c.targetEligible){own++;target=c;}
  check(own==1,"one own blue coin, got "+own);
  check(target.pos.x<s.board.left,"the own coin is the one against the left rail ("+target.pos.x+" vs board "+s.board.left+")");
  check(s.coins.size()==4,"1 blue + 3 opponent discs, no painted circles; got "+s.coins.size());
  List<ShotPlanner.Shot> routes=RoutePlanner.routes(s,null);
  check(!routes.isEmpty(),"a route to the rail coin exists");
  System.out.println("PASS: rail-touching own coin found from 2+ pockets, painted red circles rejected, route: "+RoutePlanner.label(routes.get(0))+" ("+routes.size()+" routes)");}}
