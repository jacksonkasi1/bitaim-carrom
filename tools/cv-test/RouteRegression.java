import com.bitaim.carromaim.cv.*;
import android.graphics.*;
import java.util.*;
public class RouteRegression {
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static GameState state(float x,float y){GameState s=new GameState();s.board=new RectF(0,0,1000,1000);s.teamKnown=s.strikerDetected=true;s.striker=new Coin(x,y,30,3,true);s.pockets.addAll(Arrays.asList(new PointF(50,50),new PointF(950,50),new PointF(50,950),new PointF(950,950)));return s;}
 static Coin add(GameState s,float x,float y,boolean own){Coin c=new Coin(x,y,20,0,false);c.targetEligible=own;s.coins.add(c);return c;}
 public static void main(String[] args){
  GameState bank=state(500,850);Coin target=add(bank,500,400,true);Coin block=add(bank,500,650,false);
  List<ShotPlanner.Shot> options=RoutePlanner.routes(bank,target);
  check(options.stream().anyMatch(r->r.kind==1),"One-cushion route must exist around a blocked direct path");
  for(ShotPlanner.Shot route:options)if(route.kind==1){
   PointF a=route.strikerPath.get(0),b=route.strikerPath.get(1),g=route.strikerPath.get(2);
   float ix=b.x-a.x,iy=b.y-a.y,ox=g.x-b.x,oy=g.y-b.y;
   float il=(float)Math.hypot(ix,iy),ol=(float)Math.hypot(ox,oy);
   // v4.8.2: striker rebound fitted to 128 live shots: normal x0.80, along-rail x0.76.
   float k=.76f,px=route.wall<2?-.8f*ix:k*ix,py=route.wall<2?k*iy:-.8f*iy,pl=(float)Math.hypot(px,py);
   check(Math.abs(px/pl-ox/ol)<.003&&Math.abs(py/pl-oy/ol)<.003,"Cushion reflection must use the measured striker rebound");
  }
  GameState combo=state(200,800);Coin first=add(combo,350,650,true),second=add(combo,550,450,true),last=add(combo,750,250,true);
  List<ShotPlanner.Shot> combinations=RoutePlanner.routes(combo,last);
  check(combinations.stream().anyMatch(r->r.chain.size()==3),"Two-transfer combination must be found");
  GameState mixed=state(200,800);Coin white=add(mixed,350,650,false),black=add(mixed,750,250,true);
  List<ShotPlanner.Shot> mixedRoutes=RoutePlanner.routes(mixed,black);
  ShotPlanner.Shot mixedRoute=mixedRoutes.stream().filter(r->r.chain.size()==2&&r.chain.get(0)==white).findFirst().orElse(null);
  check(mixedRoute!=null&&mixedRoute.modelValidated&&mixedRoute.coin==black,"Opponent-colour transfer to own final target must be model validated");
  check(mixedRoute.robustnessTrials==9&&mixedRoute.robustnessPassed>=1&&mixedRoute.robustnessPassed<=9,"Model score counts actual stress-test outcomes");
  check(RoutePlanner.refresh(mixed,mixedRoute)!=null,"Mixed-colour transfer must survive route refresh");
  white.transferEligible=false;
  check(RoutePlanner.routes(mixed,black).stream().noneMatch(r->r.chain.contains(white)),"Unknown obstacles must never become intended transfer coins");
  GameState coinBank=state(500,850);Coin bouncing=add(coinBank,200,150,true);
  ShotPlanner.Shot bankedCoin=RoutePlanner.routes(coinBank,bouncing).stream().filter(r->r.kind==3).findFirst().orElse(null);
  check(bankedCoin!=null&&bankedCoin.targetBounce!=null&&bankedCoin.modelValidated,"Coin cushion route needs a bounce and nominal pot");
  PointF bp=bankedCoin.targetBounce;float ix=bp.x-bouncing.pos.x,iy=bp.y-bouncing.pos.y;
  float ox=bankedCoin.pocket.x-bp.x,oy=bankedCoin.pocket.y-bp.y;
  float expectedX=bankedCoin.wall-4<2?-.8f*ix:ix,expectedY=bankedCoin.wall-4<2?iy:-.8f*iy;
  float norm=(float)Math.hypot(expectedX,expectedY),outNorm=(float)Math.hypot(ox,oy);
  check(Math.abs(expectedX/norm-ox/outNorm)<.001&&Math.abs(expectedY/norm-oy/outNorm)<.001,"Coin cushion must use restitution, not a perfect mirror");
  // An opponent coin in the target pocket's mouth must reject the pot; one in an unused pocket must not (practice 22:49).
  PointF unused=coinBank.pockets.stream().filter(p->Math.hypot(p.x-bankedCoin.pocket.x,p.y-bankedCoin.pocket.y)>5&&Math.hypot(p.x-500,p.y-850)>300).findFirst().get();
  Coin far=add(coinBank,unused.x,unused.y,false);
  check(PhysicsVerifier.validate(coinBank,bankedCoin),"A coin resting in another pocket must not reject every route");
  coinBank.coins.remove(far);
  Coin foul=add(coinBank,bankedCoin.pocket.x,bankedCoin.pocket.y,false);
  check(!PhysicsVerifier.validate(coinBank,bankedCoin),"Any nominal opponent pot must reject the suggested route");
  for(ShotPlanner.Shot route:combinations)for(int i=0;i<route.chain.size()-1;i++){
   PointF end=route.coinEndpoints.get(i);Coin next=route.chain.get(i+1);
   check(Math.abs(Math.hypot(end.x-next.pos.x,end.y-next.pos.y)-route.chain.get(i).radius-next.radius)<.01,"Combination contact radii must touch exactly");
  }
  ShotTracker tracker=new ShotTracker();tracker.lock(last);ShotPlanner.Shot picked=tracker.update(combo);check(picked!=null&&picked.coin==last,"Manual target must stay selected");
  ShotPlanner.Shot alternate=tracker.next(combo);check(alternate!=null&&alternate.coin==last,"Route cycling must preserve selected target");
  last.targetEligible=false;check(tracker.update(combo)==null,"Manual lock must never fall back to an opponent coin");
  GameState stable=state(500,850);Coin left=add(stable,300,500,true),right=add(stable,700,500,true);
  tracker.clear();ShotPlanner.Shot chosen=tracker.update(stable);check(chosen!=null,"Initial target required");
  for(int i=0;i<60;i++){right.pos.x=700+(i%2==0?.5f:-.5f);ShotPlanner.Shot held=tracker.update(stable);check(held!=null&&held.coin==chosen.coin,"Subpixel score variation must not change target");}
  check(tracker.isSteady(),"Stationary striker must show Steady after consecutive frames");
  stable.striker.pos.x+=20;tracker.update(stable);check(!tracker.isSteady(),"Moving striker must reset the steady indicator");
  chosen.coin.targetEligible=false;check(tracker.update(stable)==null,"Brief uncertain detection must hide instead of jumping to another coin");
  chosen.coin.targetEligible=true;check(tracker.update(stable).coin==chosen.coin,"Recovered detection must restore the held target");
  System.out.println("PASS: striker and coin cushion reflection, mixed-colour transfers, opponent-pot rejection, unknown-obstacle rejection, two-transfer geometry, exact contacts, manual lock, cycling, stability");
 }
}
