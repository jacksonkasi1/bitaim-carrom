import com.bitaim.carromaim.cv.*;
public class AutoPlayRegression {
 static void check(boolean b,String text){if(!b)throw new AssertionError(text);}
 static AutoPlayController.Action settle(AutoPlayController c,GameState s,ShotPlanner.Shot r,float gold,long start){
  AutoPlayController.Action result=null;
  for(int i=0;i<10&&result==null;i++){s.frameId++;result=c.update(s,r,gold,start+i*100);}
  return result;
 }
 public static void main(String[] args){
  GameState s=RouteRegression.state(500,850);Coin target=RouteRegression.add(s,300,400,true);
  ShotPlanner.Shot r=RoutePlanner.best(s);check(r!=null,"Fixture requires model route");
  // Exercise the conservative threshold without relying on a specific stress score.
  r.robustnessTrials=9;r.robustnessPassed=9;
  // Realistic-chance gate: a 9/9 route the reality-gap estimate rates under 70% must never be auto-played.
  // Hand-aim chance does not gate auto play (gestures are exact); a sub-0.5 degree aim window does.
  float margin=r.aimMarginDegrees;r.chancePercent=40;r.aimMarginDegrees=0f;
  {AutoPlayController low=new AutoPlayController();low.setEnabled(true);check(settle(low,s,r,Float.NaN,5000)==null,"A route tighter than 0.5 degrees blocks auto shots");check(low.status().contains("tight"),"Status names the tight route");}
  r.aimMarginDegrees=1f;
  {AutoPlayController wait=new AutoPlayController();wait.setEnabled(true);wait.setGoldReady(false);check(settle(wait,s,r,700,5500)==null&&wait.status().contains("★"),"Auto play waits for the ★ search before acting");
   wait.setGoldReady(true);s.frameId++;AutoPlayController.Action mv=settle(wait,s,r,700,7000);float want=700-s.striker.pos.x,fd=Math.abs(want)<=55f?Math.signum(want)*Math.max(4f,Math.abs(want)-15f):want/.82f;float fx=Math.min(s.board.right-s.board.width()*.02f,s.striker.pos.x+fd);
   check(mv!=null&&mv.move&&Math.abs(mv.x2-fx)<.5f,"Then it slides to the ★ first, finger from the measured two-regime slider model (4.10.4)");
   // The striker lands short (75% of the way): one corrective slide covers the rest, then no more.
   float start=s.striker.pos.x;wait.complete(true);s.striker.pos.x=start+(700-start)*.75f;
   AutoPlayController.Action fix=null;for(int i=0;i<4&&fix==null;i++){s.frameId++;fix=wait.update(s,r,700,7000+1600+i*100);}
   check(fix!=null&&fix.move&&fix.x2>s.striker.pos.x&&wait.status().contains("correcting"),"A short slide gets one corrective slide toward the star ("+wait.status()+")");
   wait.complete(true);AutoPlayController.Action again=null;for(int i=0;i<4&&again==null;i++){s.frameId++;again=wait.update(s,r,700,7000+3300+i*100);}
   check(again==null||!again.move,"At most two slides per turn");s.striker.pos.x=start;}
  {// Turn clock: a sensitive route is refused early but played once the turn is running out.
   ShotPlanner.Shot risky=r;int keep=risky.robustnessPassed;risky.robustnessPassed=3;
   AutoPlayController late=new AutoPlayController();late.setEnabled(true);
   AutoPlayController.Action early=settle(late,s,risky,Float.NaN,8000);check(early==null&&late.status().contains("sensitive"),"Sensitive route waits early in the turn");
   AutoPlayController.Action hurried=null;for(int i=0;i<10&&hurried==null;i++){s.frameId++;hurried=late.update(s,risky,Float.NaN,16000+i*100);}
   check(hurried!=null&&!hurried.move&&late.status().contains("time running out"),"Late in the turn the best verified route is played");
   risky.robustnessPassed=keep;}
  {// After sliding to the ★ the controller fires the route the ★ search validated there, even with no planner route.
   GameState g=RouteRegression.state(500,850);RouteRegression.add(g,300,400,true);
   float[] q=new float[1];ShotPlanner.Shot[] gs=new ShotPlanner.Shot[1];float gx=RoutePlanner.goldX(g,null,q,gs);
   check(!Float.isNaN(gx)&&gs[0]!=null&&Math.abs(gs[0].strikerPath.get(0).x-gx)<1,"★ search returns its validated route at the ★ spot");
   AutoPlayController fast=new AutoPlayController();fast.setEnabled(true);fast.setGoldRoute(gs[0]);
   AutoPlayController.Action slide=settle(fast,g,null,gx,70000);check(slide!=null&&slide.move,"Slides to the ★ first");
   fast.complete(true);g.striker.pos.x=gx;
   AutoPlayController.Action fire=null;for(int i=0;i<12&&fire==null;i++){g.frameId++;fire=fast.update(g,null,Float.NaN,71000+i*100);}
   check(fire!=null&&!fire.move&&fast.status().startsWith("shooting"),"Fires the ★ route right after the slide ("+fast.status()+")");}
  {// Dense-board flicker: one disc appearing/disappearing and 3 px jitter must still count as steady.
   AutoPlayController fl=new AutoPlayController();fl.setEnabled(true);fl.setGoldReady(true);
   Coin extra=new Coin(700,500,20,Coin.COLOR_WHITE,false);extra.targetEligible=false;AutoPlayController.Action act=null;
   for(int i=0;i<12&&act==null;i++){s.frameId++;if(i%2==0)s.coins.add(extra);else s.coins.remove(extra);target.pos.x=300+(i%3)-1;act=fl.update(s,r,Float.NaN,90000+i*100);}
   s.coins.remove(extra);target.pos.x=300;
   check(act!=null,"Disc flicker and pixel jitter must not block auto play forever ("+fl.status()+")");}
  {// Power follows the softest potting speed: a short pot pulls less than a long one, never the old fixed maximum.
   GameState near=RouteRegression.state(500,850);RouteRegression.add(near,120,160,true);
   GameState far=RouteRegression.state(500,850);RouteRegression.add(far,780,300,true);
   ShotPlanner.Shot rn=RoutePlanner.best(near),rf=RoutePlanner.best(far);
   check(rn!=null&&rf!=null&&rn.kind<4&&rf.kind<4&&!Float.isNaN(rn.minPotSpeed)&&!Float.isNaN(rf.minPotSpeed),"Validated pots carry a softest potting speed ("+(rf==null?"none":RoutePlanner.label(rf))+")");
   check(rn.minPotSpeed<=rn.assumedLaunchSpeed+1&&rf.minPotSpeed<=rf.assumedLaunchSpeed+1,"Softest speed never exceeds the validated speed");
   float pn=AutoPlayController.pullFor(rn,1000),pf=AutoPlayController.pullFor(rf,1000);
   check(pn<=pf+1e-6f,"A short pot never pulls more than a long one ("+pn+" vs "+pf+")"); // 4.9.2: equal travel here (0.87 vs 0.87)
   check(pn>=.19f&&pf<=.27f,"Pot pulls stay in the measured 0.19-0.27 band");
   System.out.printf(java.util.Locale.US,"  pull: near pot %.3f, far pot %.3f (board widths)%n",pn,pf);}
  {AutoPlayController ok=new AutoPlayController();ok.setEnabled(true);check(settle(ok,s,r,Float.NaN,6000)!=null,"A 50%-hand-chance route with 1 degree tolerance and 9/9 stress is auto-played");}
  r.chancePercent=90;
  AutoPlayController c=new AutoPlayController();
  check(settle(c,s,r,Float.NaN,10000)==null,"Auto shots OFF by default");
  c.setEnabled(true);check(c.update(s,r,Float.NaN,12000)==null,"No shot on first detection");
  for(int i=0;i<20;i++)check(c.update(s,r,Float.NaN,12000+i)==null,"Redrawing the same captured frame must never count as stability");
  AutoPlayController.Action action=settle(c,s,r,Float.NaN,12100);
  check(action!=null&&!action.move,"Stable verified route produces one pull-release action");
  float ax=r.strikerPath.get(1).x-s.striker.pos.x,ay=r.strikerPath.get(1).y-s.striker.pos.y;
  check((action.x2-action.x1)*ax+(action.y2-action.y1)*ay<0,"Pull direction must oppose intended travel");
  c.complete(true);check(settle(c,s,r,Float.NaN,20000)==null,"Never repeat a shot on an unchanged turn");
  c.setEnabled(true);r.robustnessPassed=4;check(settle(c,s,r,Float.NaN,30000)==null,"Sensitive routes must not auto-fire");
  check(c.status().contains("sensitive"),"Status explains why auto play waits: "+c.status());
  r.robustnessPassed=9;c.setEnabled(true);s.striker.pos.x+=30;
  check(settle(c,s,r,Float.NaN,40000)==null,"Stale model signature must never auto-fire");s.striker.pos.x-=30;
  c.setEnabled(true);action=settle(c,s,r,700,50000);
  check(action!=null&&action.move&&action.x2>=700&&action.y1==1400,"Gold repositioning uses slider before a shot (finger scaled past the spot)");
  c.complete(true);s.frameId++;c.update(s,r,Float.NaN,56000);check(c.enabled(),"A slider that lands off target must not disarm auto play");
  c.setEnabled(true);s.strikerDetected=false;check(settle(c,s,r,700,60000)==null,"Opponent turn must not dispatch gestures");
  s.strikerDetected=true;c.setEnabled(true);c.complete(false);check(c.enabled(),"One cancelled gesture retries");
  c.complete(false);c.complete(false);check(c.enabled()&&c.status().contains("paused"),"Three failed gestures in a row pause (4.14.0: never disarm by itself)");
  GoldSceneLock lock=new GoldSceneLock();int id=lock.update(s,target);
  for(int i=0;i<60;i++){target.pos.x=300+(i%2==0?2:-2);s.striker.pos.x=200+i*8;check(lock.update(s,target)==id,"Slider movement and small coin jitter must keep gold search fixed");}
  target.pos.x=340;check(lock.update(s,target)!=id,"Real coin movement must invalidate gold scene");
  {// No route at all late in the turn (practice 23:31): break at the discs instead of losing the turn.
   GameState g=RouteRegression.state(500,850);RouteRegression.add(g,480,400,true);RouteRegression.add(g,520,420,false);
   AutoPlayController nr=new AutoPlayController();nr.setEnabled(true);nr.setGoldReady(true);
   AutoPlayController.Action early=settle(nr,g,null,Float.NaN,200000);check(early==null,"No route early in the turn: wait");
   AutoPlayController.Action brk=null;for(int i=0;i<10&&brk==null;i++){g.frameId++;brk=nr.update(g,null,Float.NaN,200000+9500+i*100);}
   check(brk!=null&&!brk.move&&brk.y2>850&&nr.status().startsWith("no route:"),"Late with no route: break shot toward the discs ("+nr.status()+")");}
  {// Power check (live 00:01:53): a straight-on pot where the striker follows the coin in at high power.
   GameState g=RouteRegression.state(850,850);Coin pc=RouteRegression.add(g,450,450,true);
   ShotPlanner.Shot straight=RoutePlanner.routes(g,pc).stream().filter(x->x.kind==0).findFirst().orElse(null);
   if(straight!=null){
     float w=g.board.width(),q=AutoPlayController.safePull(g,straight,w,.27f);
     check(q>=.12f&&q<=.27f,"Safe pull stays in range");
     boolean hardOk=PhysicsVerifier.simulate(g,straight,AutoPlayController.LAUNCH_PER_PULL*.27f*w);
     boolean chosenOk=PhysicsVerifier.simulate(g,straight,AutoPlayController.LAUNCH_PER_PULL*q*w);
     check(hardOk?q==.27f:(chosenOk||Math.abs(q-straight.assumedLaunchSpeed/(AutoPlayController.LAUNCH_PER_PULL*w))<.001f||q==.12f),
       "A pull that fouls in the model is replaced by one that pots (hard ok="+hardOk+", chosen "+q+")");
     System.out.println("  power check: straight-on pot, max pull ok="+hardOk+", chosen pull "+q);
   }}
  // Closed-loop aim (recorded 22:17:36 shot: autoAim (0.5705,-0.8213), game aim (0.584,-0.812), about +0.95 degrees).
  float err=AimCorrection.errorDegrees(.5705f,-.8213f,.584f,-.812f);
  check(Math.abs(err-.95f)<.1f,"Aim error must match the recorded +0.95 degrees, got "+err);
  check(Math.abs(AimCorrection.errorDegrees(.584f,-.812f,.5705f,-.8213f)+err)<1e-3,"Aim error must be signed");
  float sx=500,sy=800,len=250,ux=.5705f,uy=-.8213f;float n=(float)Math.hypot(ux,uy);ux/=n;uy/=n;
  float[] fixed=AimCorrection.rotatePull(sx,sy,sx-ux*len,sy-uy*len,err);
  float lx=sx-fixed[0],ly=sy-fixed[1];check(Math.abs(Math.hypot(lx,ly)-len)<.01,"Correction must keep the pull length");
  // The game adds the same error to the corrected drag; the result must be the planned direction.
  double a=Math.toRadians(err),gx=(lx*Math.cos(a)-ly*Math.sin(a))/len,gy=(lx*Math.sin(a)+ly*Math.cos(a))/len;
  check(Math.abs(AimCorrection.errorDegrees(ux,uy,(float)gx,(float)gy))<.02f,"Corrected pull must land on the planned aim");
  check(AimCorrection.shouldCorrect(.95f,6,0),"A 0.95 degree error must be corrected");
  check(!AimCorrection.shouldCorrect(.2f,6,0),"Errors under 0.3 degrees release at once");
  check(!AimCorrection.shouldCorrect(24f,6,0)&&!AimCorrection.shouldCorrect(Float.NaN,6,0),"Misread guides are never corrected");
  check(!AimCorrection.shouldCorrect(1f,2,0),"Too few aim dots must not drive a correction");
  check(AimCorrection.shouldCorrect(1f,6,2)&&!AimCorrection.shouldCorrect(1f,6,3),"At most three correction steps");
  check(Math.abs(AimCorrection.nextGain(-1.52f,-.90f,1f)-1/(.62f/1.52f))<.01f||AimCorrection.nextGain(-1.52f,-.90f,1f)==2f,"Under-response scales the next step up");
  check(AimCorrection.nextGain(-1.5f,-.75f,1f)==2f&&AimCorrection.nextGain(-1f,0f,1f)==1f,"Half response doubles the step; full response keeps it");
  check(AimCorrection.nextGain(-1f,-1.1f,1f)==1f&&AimCorrection.nextGain(Float.NaN,-1f,1f)==1f,"Noise or first step: plain step");
  {// 4.13.3: a route whose plan never matches the settled board ("board changed, re-planning" all turn) must not
   // lose the turn: after 8 s the fallback shot is played (overnight practice 01:11-01:14).
   GameState g=RouteRegression.state(500,850);RouteRegression.add(g,300,400,true);
   ShotPlanner.Shot sr=RoutePlanner.best(g);check(sr!=null,"Fixture requires model route");
   sr.worldSignature^=1;sr.chancePercent=90;
   AutoPlayController ch=new AutoPlayController();ch.setEnabled(true);ch.setGoldReady(true);
   AutoPlayController.Action early=null;for(int i=0;i<60&&early==null;i++){g.frameId++;early=ch.update(g,sr,Float.NaN,100000+i*100);}
   check(early==null&&ch.status().startsWith("board changed"),"Early in the turn a changed board waits ("+ch.status()+")");
   AutoPlayController.Action fb=null;for(int i=0;i<40&&fb==null;i++){g.frameId++;fb=ch.update(g,sr,Float.NaN,106100+i*100);}
   check(fb!=null&&!fb.move,"A board that never settles gets the fallback shot after 8 s ("+ch.status()+")");}
  {// 4.14.0: a route the physics check could not confirm is played late in the turn instead of losing it
   GameState g=RouteRegression.state(500,850);RouteRegression.add(g,300,400,true);
   ShotPlanner.Shot ur=RoutePlanner.best(g);check(ur!=null,"Fixture requires model route");
   ur.modelValidated=false;ur.chancePercent=90;
   AutoPlayController uc=new AutoPlayController();uc.setEnabled(true);uc.setGoldReady(true);
   AutoPlayController.Action e=null;for(int i=0;i<60&&e==null;i++){g.frameId++;e=uc.update(g,ur,Float.NaN,200000+i*100);}
   check(e==null&&uc.status().startsWith("route not verified"),"An unverified route waits early ("+uc.status()+")");
   AutoPlayController.Action late=null;for(int i=0;i<40&&late==null;i++){g.frameId++;late=uc.update(g,ur,Float.NaN,206100+i*100);}
   check(late!=null&&!late.move,"Late in the turn the unverified route is played ("+uc.status()+")");}
  {// 4.14.1: a route planned on a different board ("re-planning on the settled board") falls back after 8 s
   GameState g=RouteRegression.state(500,850);RouteRegression.add(g,300,400,true);
   ShotPlanner.Shot pr=RoutePlanner.best(g);check(pr!=null,"Fixture requires model route");
   GameState other=RouteRegression.state(500,850);RouteRegression.add(other,330,430,true);
   AutoPlayController pc=new AutoPlayController();pc.setEnabled(true);pc.setGoldReady(true);
   AutoPlayController.Action e=null;for(int i=0;i<60&&e==null;i++){g.frameId++;e=pc.update(g,pr,Float.NaN,300000+i*100,other);}
   check(e==null&&pc.status().contains("re-planning"),"A plan from another board waits early ("+pc.status()+")");
   AutoPlayController.Action fb=null;for(int i=0;i<60&&fb==null;i++){g.frameId++;fb=pc.update(g,pr,Float.NaN,306100+i*100,other);}
   check(fb!=null&&!fb.move,"After 8 s the fallback shot is played ("+pc.status()+")");}
  System.out.println("PASS: opt-in only, stable frames, pull geometry, one shot per turn, robustness gate, stale-plan rejection, slider verification, opponent-turn block, cancellation, stable gold scene, closed-loop aim correction");
 }
}
