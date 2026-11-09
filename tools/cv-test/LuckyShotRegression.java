import com.bitaim.carromaim.cv.*;
import android.graphics.*;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import java.util.*;

/** 4.11.0: Lucky Shot mini-game (live 2026-11-08 20:31): detect board, ring target, gold puck, blocker and striker,
 *  then plan a shot whose modelled resting point is on the target. */
public class LuckyShotRegression {
    static void check(boolean v,String m){if(!v)throw new AssertionError(m);}
    public static void main(String[] a){
        nu.pattern.OpenCV.loadLocally();
        Mat bgr=Imgcodecs.imread(a[0]);check(!bgr.empty(),"fixture missing: "+a[0]);
        float scale=720f/bgr.cols();
        Mat small=new Mat(),hsv=new Mat(),gray=new Mat();
        Imgproc.resize(bgr,small,new org.opencv.core.Size(720,Math.round(bgr.rows()*scale)),0,0,Imgproc.INTER_AREA);
        Imgproc.cvtColor(small,hsv,Imgproc.COLOR_BGR2HSV);Imgproc.cvtColor(small,gray,Imgproc.COLOR_BGR2GRAY);
        Imgproc.GaussianBlur(gray,gray,new org.opencv.core.Size(5,5),1.5);
        LuckyShot.Scene s=LuckyShot.detect(hsv,gray,scale);
        check(s!=null,"Lucky Shot board detected");
        float k=1.186f; // screenshot display -> source pixels
        System.out.printf(Locale.US,"board %.0f,%.0f-%.0f,%.0f target %.0f,%.0f R%.0f puck %.0f,%.0f r%.0f striker %.0f,%.0f(%b) blockers %d%n",
                s.board.left,s.board.top,s.board.right,s.board.bottom,s.target.x,s.target.y,s.ringR,s.puck.x,s.puck.y,s.puckR,s.striker.x,s.striker.y,s.strikerFound,s.blockers.size());
        check(Math.hypot(s.target.x-704*k,s.target.y-1272*k)<12,"target centre found");
        check(Math.abs(s.ringR-155*k)<20,"teal ring radius");
        check(Math.hypot(s.puck.x-330*k,s.puck.y-973*k)<10,"gold puck found");
        check(s.blockers.size()==1&&Math.hypot(s.blockers.get(0).x-420*k,s.blockers.get(0).y-973*k)<10,"one black blocker found: "+s.blockers.size());
        check(s.strikerFound&&Math.hypot(s.striker.x-240*k,s.striker.y-1325*k)<25,"striker found on the baseline");
        check(Math.hypot(s.striker.x-s.target.x,s.striker.y-s.target.y)>s.ringR,"the target centre is never taken as the striker (live 12:12)");
        long t0=System.nanoTime();
        LuckyShot.Plan p=LuckyShot.plan(s);
        long ms=(System.nanoTime()-t0)/1_000_000;
        check(p!=null,"a shot is planned");
        System.out.printf(Locale.US,"plan %s striker x %.0f dir %.3f,%.3f speed %.2f w/s power %.0f%% rest %.0f,%.0f err %.1f px spread %.1f zone %s (%d ms)%n",
                p.kind,p.strikerX,p.ux,p.uy,p.speed/s.board.width(),p.powerPercent,p.rest.x,p.rest.y,p.error,p.spread,p.zone,ms);
        check(p.error<s.ringR*LuckyShot.YELLOW_FRAC,"modelled rest in the yellow centre");
        check(p.spread<s.ringR*.8f,"small aim/power/rail errors still land on the target on average (rms "+p.spread+")");
        check(p.strikerPath.size()>=2&&p.puckPath.size()>=2,"paths for the overlay");
        check(Math.abs(p.strikerX-s.striker.x)<1,"planned (and auto-played) from where the striker is");
        LuckyShot.Scene again=LuckyShot.detect(hsv,gray,scale);
        check(s.same(again),"the same frame is the same board (plan reused)");
        again.striker.x+=100;check(!s.same(again),"a moved striker gets a new plan");
        again.striker.x-=100;again.puck.x+=40;check(!s.same(again),"a moved puck is a new board");
        // Auto play: waits for a still board, then one held shot with the planned pull; watches the puck afterwards.
        LuckyAuto auto=new LuckyAuto();
        check(auto.update(s,null,true,0)==null,"no shot while planning");
        check(auto.update(s,p,false,100)==null&&auto.update(s,p,false,500)==null,"no shot before the board has settled");
        AutoPlayController.Action shot=auto.update(s,p,false,900);
        check(shot!=null&&!shot.move&&shot.aimBudgetMs>0,"held, aim-corrected shot after the board settled");
        float dx=shot.x1-shot.x2,dy=shot.y1-shot.y2,len=(float)Math.hypot(dx,dy);
        check(Math.abs(len-p.pullPx)<.5f&&Math.abs(dx/len-p.ux)<1e-3&&Math.abs(dy/len-p.uy)<1e-3,"pull length and launch direction match the plan");
        check(!Float.isNaN(shot.aimX),"contact point passed for the closed-loop aim");
        check(auto.consumeNote().startsWith("LUCKY SHOT"),"shot logged");
        check(auto.update(s,p,false,1000)==null,"no second shot while the first is in flight");
        // A misread striker inside the target is never pulled from.
        {LuckyShot.Scene bad=LuckyShot.detect(hsv,gray,scale);bad.striker.set(bad.target.x,bad.target.y);LuckyAuto a2=new LuckyAuto();
         a2.update(bad,p,false,0);check(a2.update(bad,p,false,2000)==null,"no shot from a striker inside the target");}
        auto.complete(true);
        PointF leg0=p.puckPath.get(p.puckPath.size()-2);float lx=p.rest.x-leg0.x,ly=p.rest.y-leg0.y,ll=(float)Math.hypot(lx,ly);
        LuckyShot.Scene after=LuckyShot.detect(hsv,gray,scale);after.puck.set(p.rest.x+20*lx/ll,p.rest.y+20*ly/ll); // 20 px too long
        check(auto.update(after,p,false,1500)==null&&auto.update(after,p,false,2200)==null,"watching the puck");
        String result=auto.consumeNote();
        check(result!=null&&result.startsWith("LUCKY RESULT")&&result.contains("along +20 across +0")||result.contains("along +20 across -0"),"real resting point logged against the plan: "+result);
        System.out.printf(Locale.US,"PASS: Lucky Shot board, ring target, gold puck, blocker, striker; planned rest %.0f px from centre (%s), rms %.0f px under aim/power error, %d ms; auto play waits, shoots once with the planned pull, logs the result%n",p.error,p.zone,p.spread,ms);
    }
}
