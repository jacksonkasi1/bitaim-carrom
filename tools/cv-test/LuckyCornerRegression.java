import com.bitaim.carromaim.cv.*;
import org.opencv.core.*;import org.opencv.imgcodecs.Imgcodecs;import org.opencv.imgproc.Imgproc;

/** 4.12.3 (user: "left bottom it can't"): the 2026-11-08 Lucky Shot board with the gold puck moved to the bottom-left
 *  (below the striker line, and into the corner). Only upward shots were searched: the corner plan stopped 532 px off.
 *  Backward shots (off the bottom rail) put it on the target. */
public class LuckyCornerRegression {
    static void check(boolean v,String m){if(!v)throw new AssertionError(m);}
    public static void main(String[] a){
        nu.pattern.OpenCV.loadLocally();
        for(String f:a){
            Mat bgr=Imgcodecs.imread(f);check(!bgr.empty(),"fixture "+f);float scale=720f/bgr.cols();
            Mat small=new Mat(),hsv=new Mat(),gray=new Mat();
            Imgproc.resize(bgr,small,new Size(720,Math.round(bgr.rows()*scale)),0,0,Imgproc.INTER_AREA);
            Imgproc.cvtColor(small,hsv,Imgproc.COLOR_BGR2HSV);Imgproc.cvtColor(small,gray,Imgproc.COLOR_BGR2GRAY);Imgproc.GaussianBlur(gray,gray,new Size(5,5),1.5);
            LuckyShot.Scene s=LuckyShot.detect(hsv,gray,scale);
            check(s!=null&&s.puck.x<s.board.left+s.board.width()*.15f&&s.puck.y>s.striker.y-s.strikerR,"puck found bottom-left, at or below the striker line: "+f);
            LuckyShot.Plan p=LuckyShot.plan(s);
            check(p!=null&&p.error<s.ringR,"planned rest on the target ("+(p==null?"none":p.error+" px, "+p.zone)+"): "+f);
            System.out.printf(java.util.Locale.US,"  %s: %s, power %.0f%%, %s (%.0f px)%n",new java.io.File(f).getName(),p.kind,p.powerPercent,p.zone,p.error);
        }
        System.out.println("PASS: Lucky Shot puck in the bottom-left / corner below the striker line lands on the target (backward shots searched)");
    }
}
