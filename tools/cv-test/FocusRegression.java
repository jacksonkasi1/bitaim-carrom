import com.bitaim.carromaim.cv.*;
import java.util.*;
public class FocusRegression {
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
 public static void main(String[] files){
  nu.pattern.OpenCV.loadLocally();GameState opening=Regression.detect(files[0]);
  List<ShotPlanner.Shot> routes=RoutePlanner.routes(opening,null);
  ShotPlanner.Shot spread=routes.stream().filter(r->r.kind==4).findFirst().orElse(null);
  check(spread!=null&&spread.modelValidated&&spread.clusterMoved>=3,"Real opening must have a completed-model cluster-opening guide");
  check(spread.coin.targetEligible&&spread.strikerPath.size()==2,"First-contact guide must identify an own reachable outer coin");
  check(RoutePlanner.refresh(opening,spread)!=null,"Cluster guide must refresh without becoming a pot route");
  AutoPlayController auto=new AutoPlayController();auto.setEnabled(true);
  // v4.6: auto play may play a verified, robust opening break (the user asked for breaks to be automatic too).
  AutoPlayController.Action breakShot=null;
  for(int i=0;i<20&&breakShot==null;i++){opening.frameId++;breakShot=auto.update(opening,spread,Float.NaN,10000+i*100);}
  check(spread.robustnessPassed<7?breakShot==null:breakShot!=null&&!breakShot.move,"Auto play breaks only when the opening is robust ("+spread.robustnessPassed+"/9)");
  Coin blocker=new Coin((opening.striker.pos.x+spread.ghost.x)*.5f,(opening.striker.pos.y+spread.ghost.y)*.5f,spread.coin.radius,0,false);
  blocker.targetEligible=blocker.transferEligible=false;opening.coins.add(blocker);
  check(ClusterPlanner.forCoin(opening,spread.coin)==null,"Blocked first-contact path cannot be recommended");
  GameState forward=Regression.detect(files[1]),backward=Regression.detect(files[2]);
  check(forward.aimGuideDots>=6&&forward.aimGuideX*(-.304)+forward.aimGuideY*(-.953)>.995,"Read forward white guide direction");
  check(backward.aimGuideDots>=6&&backward.aimGuideX*(-.238)+backward.aimGuideY*.971>.995,"Read backward white guide direction outside lower rail");
  check(Regression.detect(files[0]).aimGuideDots==0,"Clean board must not invent an aiming guide");
  GameState live=Regression.detect(files[3]);
  check(live!=null&&live.freestyle&&live.teamKnown&&live.coins.size()==18&&live.coins.stream().allMatch(c->c.targetEligible),"Lower red/black/white legend must allow all 18 real freestyle coins");
  check(RoutePlanner.routes(live,null).stream().anyMatch(r->r.kind!=4&&r.modelValidated),"New live colour failure must now produce a verified pot route");
  org.opencv.core.Mat bgr=org.opencv.imgcodecs.Imgcodecs.imread(files[3]),rgba=new org.opencv.core.Mat();
  org.opencv.imgproc.Imgproc.cvtColor(bgr,rgba,org.opencv.imgproc.Imgproc.COLOR_BGR2RGBA);
  org.opencv.imgproc.Imgproc.rectangle(rgba,new org.opencv.core.Point(330,600),new org.opencv.core.Point(750,685),new org.opencv.core.Scalar(100,20,25,255),-1);
  BoardDetector detector=new BoardDetector();GameState unknown=detector.detectMat(rgba);
  check(unknown!=null&&!unknown.teamKnown,"Occluding the full legend must leave automatic ownership unknown");
  detector.setAllColours(true);GameState forced=detector.detectMat(rgba);
  check(forced.freestyle&&forced.teamKnown&&forced.coins.size()==18&&forced.coins.stream().allMatch(c->c.targetEligible),"Explicit all-colour mode must work with no badge or legend");
  detector.setAllColours(false);check(!detector.detectMat(rgba).teamKnown,"Turning all-colour override off restores conservative automatic recognition");
  detector.close();bgr.release();rgba.release();
  GameState bank=RouteRegression.state(500,850);RouteRegression.add(bank,500,650,false);Coin last=RouteRegression.add(bank,200,200,true);
  check(RoutePlanner.routes(bank,last).stream().anyMatch(r->r.kind==1&&r.chain.size()==2&&r.modelValidated),"Striker cushion then mixed-colour combination must be supported");
  GameState s=RouteRegression.state(500,850);RouteRegression.add(s,300,400,true);ShotPlanner.Shot r=RoutePlanner.best(s);
  float dx=r.strikerPath.get(1).x-s.striker.pos.x,dy=r.strikerPath.get(1).y-s.striker.pos.y,n=(float)Math.hypot(dx,dy);
  s.aimGuideX=dx/n;s.aimGuideY=dy/n;s.aimGuideDots=10;
  AimAlignment alignment=new AimAlignment();s.frameId=1;alignment.update(s,r);
  for(int i=0;i<20;i++)alignment.update(s,r);check(!alignment.aligned(),"Redraws must not create green alignment");
  s.frameId++;alignment.update(s,r);s.frameId++;alignment.update(s,r);check(alignment.aligned(),"Three separate aligned captures turn the first path green");
  {float gx=s.aimGuideX,gy=s.aimGuideY,c=(float)Math.cos(Math.toRadians(20)),sn=(float)Math.sin(Math.toRadians(20));s.aimGuideX=gx*c-gy*sn;s.aimGuideY=gx*sn+gy*c;}s.frameId++;alignment.update(s,r);
  check(!alignment.aligned()&&alignment.errorDegrees()>15,"A guide 20 degrees off must never be called aligned");
  // A guide pointing the opposite way is a different shot (downward bank shots exist), never the same aim.
  s.aimGuideX=-dx/n;s.aimGuideY=-dy/n;
  AimAlignment opposite=new AimAlignment();for(int i=0;i<3;i++){s.frameId++;opposite.update(s,r);}
  check(!opposite.aligned(),"An opposite guide never confirms alignment");
  s.aimGuideDots=0;alignment.update(s,r);check(!alignment.visible()&&!alignment.aligned(),"Missing guide clears alignment");
  System.out.println("PASS: real opening spread, blocked-contact rejection, manual-only break, forward/backward dot guides, no phantom guide, lower freestyle legend, live colour failure regression, cushion-plus-combination, three-frame green alignment, opposite/missing-guide rejection");
 }
}
