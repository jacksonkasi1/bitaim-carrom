import com.bitaim.carromaim.cv.*;
import org.opencv.core.*;import org.opencv.imgcodecs.Imgcodecs;import org.opencv.imgproc.Imgproc;

/** 4.10.6: light blue (opponent) and dark blue (own) discs on one board (live 2026-11-08 15:3x). Hue alone differs by
 *  ~15 (detector bins 100 vs 105, inside the lock's 10-step window); brightness 255 vs 140 separates them. */
public class TwoBluesRegression {
    static void check(boolean v,String m){if(!v)throw new AssertionError(m);}
    public static void main(String[] a){
        nu.pattern.OpenCV.loadLocally();
        Mat bgr=Imgcodecs.imread(a[0]),rgba=new Mat();Imgproc.cvtColor(bgr,rgba,Imgproc.COLOR_BGR2RGBA);
        GameState s=new BoardDetector().detectMat(rgba);
        check(s!=null,"board found");
        int light=0,dark=0,lightKey=0,darkKey=0;
        for(Coin c:s.coins){
            if(TargetColourLock.bright(c.appearanceKey)&&TargetColourLock.label(c.appearanceKey).toLowerCase().contains("blue")){light++;lightKey=c.appearanceKey;}
            else if(!TargetColourLock.bright(c.appearanceKey)&&TargetColourLock.label(c.appearanceKey).equals("Blue")){dark++;darkKey=c.appearanceKey;}
        }
        check(light>=2,"the two light-blue discs get the bright key, got "+light);
        check(dark>=2,"the dark-blue discs keep the solid key, got "+dark);
        check(!TargetColourLock.matches(darkKey,lightKey)&&!TargetColourLock.matches(lightKey,darkKey),"a dark-blue lock never accepts light blue and vice versa");
        BoardDetector locked=new BoardDetector();locked.setTargetColourKey(darkKey);GameState l=locked.detectMat(rgba);
        for(Coin c:l.coins)if(c.targetEligible)check(!TargetColourLock.bright(c.appearanceKey),"dark-blue lock: no light-blue disc is a target");
        System.out.println("PASS: two blues separated by brightness ("+light+" light "+TargetColourLock.label(lightKey)+", "+dark+" dark "+TargetColourLock.label(darkKey)+"), dark lock targets only dark");
    }
}
