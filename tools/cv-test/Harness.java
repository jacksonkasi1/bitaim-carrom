import com.bitaim.carromaim.cv.*;
import org.opencv.core.*; import org.opencv.imgcodecs.Imgcodecs; import org.opencv.imgproc.Imgproc;
import android.graphics.PointF;
public class Harness {
  static Point p(float x,float y){return new Point(x,y);}
  public static void main(String[] a){
    nu.pattern.OpenCV.loadLocally();
    BoardDetector det=new BoardDetector();
    String threshold = System.getProperty("bitaim.threshold");
    if (threshold != null) det.setParam2(Double.parseDouble(threshold));
    for(String f:a){
      Mat bgr=Imgcodecs.imread(f), rgba=new Mat(); Imgproc.cvtColor(bgr,rgba,Imgproc.COLOR_BGR2RGBA);
      long t0=System.nanoTime(); GameState s=det.detectMat(rgba); long t1=System.nanoTime();
      if(s==null){System.out.println(f+": NO BOARD");continue;}
      System.out.printf("%s: board=(%.0f,%.0f)-(%.0f,%.0f) strikerDetected=%b striker=(%.0f,%.0f) coins=%d pockets=%d  detect=%dms%n",
        f,s.board.left,s.board.top,s.board.right,s.board.bottom,s.strikerDetected,s.striker.pos.x,s.striker.pos.y,s.coins.size(),s.pockets.size(),(t1-t0)/1000000);
      System.out.printf("   teamKnown=%b ownHue=%.1f opponentHue=%.1f eligible=%d%n",s.teamKnown,s.playerHue,s.opponentHue,s.coins.stream().filter(c->c.targetEligible).count());
      System.out.printf("   gameGuide=(%.3f,%.3f) dots=%d%n",s.aimGuideX,s.aimGuideY,s.aimGuideDots);
      Mat o=bgr.clone();
      Imgproc.rectangle(o,p(s.board.left,s.board.top),p(s.board.right,s.board.bottom),new Scalar(0,255,0),2);
      for(PointF q:s.pockets) Imgproc.circle(o,p(q.x,q.y),14,new Scalar(0,255,0),3);
      for(Coin c:s.coins) Imgproc.circle(o,p(c.pos.x,c.pos.y),(int)c.radius,new Scalar(255,0,255),2);
      if(s.strikerDetected) Imgproc.circle(o,p(s.striker.pos.x,s.striker.pos.y),(int)s.striker.radius,new Scalar(0,215,255),3);
      long t2=System.nanoTime();
      ShotPlanner.Shot sh=s.strikerDetected ? RoutePlanner.best(s) : null;
      float[] gs=new float[1]; float gx=s.strikerDetected ? RoutePlanner.goldX(s,sh==null?null:sh.coin,gs) : Float.NaN;
      long t3=System.nanoTime();
      System.out.printf("   shot=%s gold=%.0f (score %.1f) plan=%dms%n", sh==null?"none":String.format("coin(%.0f,%.0f)->pocket(%.0f,%.0f) score %.1f",sh.coin.pos.x,sh.coin.pos.y,sh.pocket.x,sh.pocket.y,sh.score), gx, gs[0], (t3-t2)/1000000);
      if(sh!=null){
        System.out.printf("   route=%s ownTarget=%b nominalModel=%b%n",RoutePlanner.label(sh),sh.coin.targetEligible,sh.modelValidated);
        System.out.printf("   modelStressTests=%d/%d%n",sh.robustnessPassed,sh.robustnessTrials);
        for(int i=0;i<sh.strikerPath.size()-1;i++){PointF a0=sh.strikerPath.get(i),a1=sh.strikerPath.get(i+1);Imgproc.line(o,p(a0.x,a0.y),p(a1.x,a1.y),new Scalar(255,229,0),3);}
        Imgproc.circle(o,p(sh.ghost.x,sh.ghost.y),(int)s.striker.radius,new Scalar(255,229,0),1);
        for(int i=0;i<sh.chain.size();i++){Coin c=sh.chain.get(i);PointF end=sh.coinEndpoints.get(i);Imgproc.line(o,p(c.pos.x,c.pos.y),p(end.x,end.y),i==sh.chain.size()-1?new Scalar(110,255,34):new Scalar(0,138,255),3);}
        if(sh.targetBounce!=null)Imgproc.line(o,p(sh.targetBounce.x,sh.targetBounce.y),p(sh.pocket.x,sh.pocket.y),new Scalar(110,255,34),3);
      }
      if(!Float.isNaN(gx)) Imgproc.circle(o,p(gx,s.striker.pos.y),10,new Scalar(0,215,255),-1);
      Imgcodecs.imwrite("out/"+new java.io.File(f).getName().replace(".jpg",".png"),o);
    }
  }
}
