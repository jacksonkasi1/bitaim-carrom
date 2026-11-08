import com.bitaim.carromaim.cv.*;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import java.util.*;
public class TeamRegression {
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static Coin nearest(GameState s,float x,float y){return s.coins.stream().min(Comparator.comparingDouble(c->Math.hypot(c.pos.x-x,c.pos.y-y))).get();}
 public static void main(String[] files){nu.pattern.OpenCV.loadLocally();
  int[] counts={5,1,6};
  for(int i=0;i<Math.min(3,files.length);i++){
   Mat bgr=Imgcodecs.imread(files[i]),rgba=new Mat();Imgproc.cvtColor(bgr,rgba,Imgproc.COLOR_BGR2RGBA);BoardDetector d=new BoardDetector();GameState s=d.detectMat(rgba);d.close();
   check(s!=null&&s.teamKnown,"Both colour badges must be recognized");
   long own=s.coins.stream().filter(c->c.targetEligible).count();check(own==counts[i],"Wrong own-coin count in fixture "+i+": "+own);
   check(s.playerHue>95&&s.opponentHue<95,"Blue and teal must remain distinct");
   if(i==0){check(nearest(s,220,694).targetEligible,"Striped blue is ours");check(!nearest(s,282,872).targetEligible,"Teal highlighted by old overlay is the opponent");}
   if(i==1){check(nearest(s,338,755).targetEligible,"Remaining blue is ours");check(!nearest(s,447,894).targetEligible,"Easy teal must not be targeted");Coin rail=nearest(s,89,536);check(Math.hypot(rail.pos.x-89,rail.pos.y-536)<15&&!rail.targetEligible,"Teal at pocket mouth must remain a collision obstacle");}
   if(i==2){check(nearest(s,155,626).targetEligible,"Blue near rail is ours");check(!nearest(s,126,847).targetEligible,"Teal near rail is the opponent");}
   long begin=System.nanoTime();List<ShotPlanner.Shot> routes=RoutePlanner.routes(s,null);long ms=(System.nanoTime()-begin)/1000000;
   for(ShotPlanner.Shot route:routes){check(route.modelValidated,"Every suggested route needs a nominal full-model pot");check(route.coin.targetEligible,"Never target opponent");for(Coin c:route.chain)check(c.transferEligible,"Unknown detections cannot be transfer coins");}
   ShotTracker tracker=new ShotTracker();ShotPlanner.Shot first=tracker.update(s);
   for(int frame=0;frame<30;frame++){ShotPlanner.Shot next=tracker.update(s);check(first==null?next==null:next!=null&&next.coin==first.coin&&next.wall==first.wall,"Stationary scene changed route");}
   System.out.printf("PASS fixture %d: own=%d total=%d routes=%d plan=%dms best=%s%n",i+1,own,s.coins.size(),routes.size(),ms,routes.isEmpty()?"none":RoutePlanner.label(routes.get(0)));
   bgr.release();rgba.release();
  }
  if(files.length>3){
   Mat bgr=Imgcodecs.imread(files[3]),rgba=new Mat();Imgproc.cvtColor(bgr,rgba,Imgproc.COLOR_BGR2RGBA);
   BoardDetector detector=new BoardDetector();GameState s=detector.detectMat(rgba);detector.close();
   check(s!=null&&s.teamKnown&&s.strikerDetected,"Doubles team and real striker must be detected");
   check(s.coins.size()==9,"Doubles screenshot has two white and seven black coins");
   check(s.coins.stream().filter(c->c.targetEligible).count()==2,"Only the two white coins belong to us");
   check(nearest(s,692,980).targetEligible&&nearest(s,701,1617).targetEligible,"Both white coins must be selectable");
   for(Coin c:s.coins)if(c.targetEligible)check(c.color==Coin.COLOR_WHITE,"Black opponent must remain blocked");
   List<ShotPlanner.Shot> routes=RoutePlanner.routes(s,null);
   check(!routes.isEmpty(),"Doubles screenshot must offer a nominally valid white pot");
   for(ShotPlanner.Shot route:routes)check(route.coin.targetEligible&&route.modelValidated,"Only verified own routes");
   System.out.println("PASS: real 2v2 layout, two white targets, seven black obstacles, selectable white, nominal pot");
   bgr.release();rgba.release();
  }
  if(files.length>4){
   Mat bgr=Imgcodecs.imread(files[4]),rgba=new Mat();Imgproc.cvtColor(bgr,rgba,Imgproc.COLOR_BGR2RGBA);
   BoardDetector detector=new BoardDetector();GameState s=detector.detectMat(rgba);detector.close();
   check(s!=null&&s.freestyle&&s.teamKnown&&s.strikerDetected,"Freestyle legend must replace ownership badges");
   check(s.coins.size()==16&&s.coins.stream().allMatch(c->c.targetEligible),"All 16 visible freestyle coins including red must be selectable");
   check(s.coins.stream().anyMatch(c->c.color==Coin.COLOR_RED),"Freestyle includes red");
   check(s.coins.stream().anyMatch(c->c.color==Coin.COLOR_WHITE),"Freestyle includes white");
   check(s.coins.stream().anyMatch(c->c.color==Coin.COLOR_BLACK),"Freestyle includes black");
   check(!Float.isNaN(RoutePlanner.goldX(s,null,new float[1])),"Blocked freestyle position must have a repositioning suggestion");
   // Removing the points legend must not turn an unknown mode into all-targets.
   Imgproc.rectangle(rgba,new Point(300,140),new Point(760,260),new Scalar(80,20,10,255),-1);
   detector=new BoardDetector();GameState hidden=detector.detectMat(rgba);detector.close();
   check(hidden!=null&&!hidden.freestyle&&!hidden.teamKnown,"Missing legend is unknown, never freestyle by default");
   System.out.println("PASS: freestyle legend, red/white/black selection, repositioning route, missing-legend rejection");
   bgr.release();rgba.release();
  }
  if(files.length>5){
   Mat bgr=Imgcodecs.imread(files[5]),rgba=new Mat();Imgproc.cvtColor(bgr,rgba,Imgproc.COLOR_BGR2RGBA);
   BoardDetector detector=new BoardDetector();GameState s=detector.detectMat(rgba);detector.close();
   check(s!=null&&s.teamKnown&&s.strikerDetected,"No-clear-shot feedback must detect real turn and team");
   List<ShotPlanner.Shot> routes=RoutePlanner.routes(s,null);
   check(routes.stream().anyMatch(r->r.chain.size()>1&&r.chain.stream().anyMatch(c->!c.targetEligible)),"Saved no-clear-shot frame must now offer a mixed-colour transfer");
   for(ShotPlanner.Shot r:routes)check(r.modelValidated&&r.coin.targetEligible&&r.robustnessTrials==9,"Saved feedback routes need nine stress checks and own final target");
   System.out.println("PASS: saved no-clear-shot frame now finds own-target combination through opponent coin, with real model score");
   bgr.release();rgba.release();
  }
  for(int i=6;i<Math.min(files.length,8);i++){
   Mat bgr=Imgcodecs.imread(files[i]),rgba=new Mat();Imgproc.cvtColor(bgr,rgba,Imgproc.COLOR_BGR2RGBA);
   BoardDetector detector=new BoardDetector();GameState s=detector.detectMat(rgba);detector.close();
   check(s!=null&&s.teamKnown&&s.strikerDetected,"Visible blue badge must identify the local team");
   int expectedOwn=i==6?3:5,expectedTotal=i==6?9:11;
   check(s.coins.size()==expectedTotal,"Yellow coins must not disappear as decoration");
   check(s.coins.stream().filter(c->c.targetEligible).count()==expectedOwn,"Only blue coins may be final targets");
   check(!nearest(s,678,592).targetEligible&&!nearest(s,686,939).targetEligible,"Yellow rail discs must stay obstacles");
   if(i==6){
    check(s.opponentHue>=25&&s.opponentHue<=35,"Yellow badge must be recognized");
    check(!RoutePlanner.routes(s,null).isEmpty(),"First yellow fixture must give a verified own route");
   }else{
    check(s.opponentHue<0,"Emote must not become an orange opponent badge");
    check(nearest(s,281,764).targetEligible&&nearest(s,327,764).targetEligible,"Striped blue discs remain selectable under badge occlusion");
   }
   System.out.println("PASS: yellow skin fixture "+(i-5)+", correct blue targets, yellow obstacles, emote rejection");
   bgr.release();rgba.release();
  }
 }
}
