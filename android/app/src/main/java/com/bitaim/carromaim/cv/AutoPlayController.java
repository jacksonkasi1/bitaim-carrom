package com.bitaim.carromaim.cv;

import android.graphics.PointF;

/** Explicitly armed gesture planner. No Android input is sent by this class.
 *  v4.6: reports a human-readable status, plays breaks/openers/queen shots too, and never disarms silently. */
public final class AutoPlayController {
    public static final class Action {
        public final boolean move;
        public final float x1,y1,x2,y2;
        /** Shot only: time (ms) the held pull may spend reading the game's aim dots and correcting; 0 = single drag. */
        public final int aimBudgetMs;
        public final boolean correctAim;
        /** Point the launch must pass through (contact/ghost or rail point); NaN when unknown. The closed-loop aim
         *  re-derives the intended direction from the striker seen during the hold (live 00:44: the striker was
         *  still settling 6 px away from where the direction was computed, a 2.6° miss on a 91% pot). */
        public float aimX=Float.NaN,aimY=Float.NaN;
        /** PC brain: an action decided on the PC, executed by the phone's gesture service. */
        public static Action remote(boolean move,float x1,float y1,float x2,float y2,int aimBudgetMs,float aimX,float aimY){Action a=new Action(move,x1,y1,x2,y2,aimBudgetMs);a.aimX=aimX;a.aimY=aimY;return a;}
        Action(boolean move,float x1,float y1,float x2,float y2){this(move,x1,y1,x2,y2,0);}
        Action(boolean move,float x1,float y1,float x2,float y2,int aimBudgetMs){this.move=move;this.x1=x1;this.y1=y1;this.x2=x2;this.y2=y2;this.aimBudgetMs=aimBudgetMs;correctAim=aimBudgetMs>0;}
    }
    /** The displayed "Chance" includes a human's ~1 degree release error, which an exact gesture does not have.
     *  Auto play therefore gates on the stress samples (small aim, power and rail changes) and aim tolerance. */
    // User choice (2026-11-07): play anything that works in about half of the stress samples.
    static final float MIN_TOLERANCE=.25f; static final int MIN_STRESS=5, MAX_FAILURES=3;
    private boolean goldReady=true;
    /** Turn clock (the game gives only a few seconds): skip the ★ wait after GOLD_WAIT_MS, relax gates after HURRY_MS. */
    static final long GOLD_WAIT_MS=4000, HURRY_MS=7000, MOVE_SETTLE_MS=900, MOVE_VERIFY_MS=1500; static final int STEADY_FRAMES=4;
    /** Debug recorder, 40 slides (2026-11-07): the striker always landed at ~0.75 of the dragged distance (median
     *  0.747, both directions, 35-295 px). The slide drag is scaled by 1/0.75 and, when it still lands more than a
     *  striker radius off, one corrective slide covers the remainder (before the relaxed-gate time). */
    static final float SLIDER_GAIN=.88f; static final int MAX_SLIDES=2; // practice 23:09-23:13: 0.92 still landed ~10% short
    // v4.7.3: the 0.75 came mostly from ★ spots beyond the slider's reach (see RoutePlanner.REACH_MIN); slides
    // inside the reachable range land at 0.8-0.97 of the distance, so a mild 0.85 gain plus a slower drag is used.
    /** Aim-correction time for a normal shot and for a hurried one (hurried shots after LATE_MS use a single drag). */
    /** With no route at all this far into the turn, a straight break is fired rather than losing the turn. */
    static final long BREAK_FALLBACK_MS=9000;
    static final int AIM_BUDGET_MS=3000, HURRIED_AIM_BUDGET_MS=2300; static final long LATE_MS=14000;
    // Practice 22:58:57: a hurried shot read a 4.2° aim error but the 1.5 s budget allowed no correction step.
    private long turnStart=-1;
    /** Launch speed (board widths/s) per unit pull (board widths), fitted from logged auto shots. */
    /** Live evidence (2026-11-07): pull 0.09 too soft, 0.16-0.22 mostly slow but sometimes right, 0.30 (every shot of
     *  4.6.3/4.6.4) too fast so pots rattled out. Picture-based fits could not separate power, and the model's coins
     *  glide much further than the game's. Pull = 0.14 + 0.10 x total route travel (board widths), 0.14-0.26;
     *  cluster breaks and openers use 0.26. The user's pull setting stays a fine-tune multiplier (12% = 1.0). */
    // Debug recorder evidence (21:29-21:38): pots at 0.22-0.23 went in 5/6; 0.19 stopped on the pocket lip.
    static final float BASE_PULL=.17f, PULL_PER_TRAVEL=.10f, MIN_PULL=.17f, MAX_PULL=.27f, BREAK_PULL=.27f;
    /** Total travel of a route in board widths: striker legs plus every coin leg (and a coin's cushion leg). */
    public static float travel(ShotPlanner.Shot r,float w){
        float d=0;
        for(int i=0;i<r.strikerPath.size()-1;i++)d+=Math.hypot(r.strikerPath.get(i+1).x-r.strikerPath.get(i).x,r.strikerPath.get(i+1).y-r.strikerPath.get(i).y);
        for(int i=0;i<r.chain.size()&&i<r.coinEndpoints.size();i++)d+=Math.hypot(r.coinEndpoints.get(i).x-r.chain.get(i).pos.x,r.coinEndpoints.get(i).y-r.chain.get(i).pos.y);
        if(r.targetBounce!=null&&r.pocket!=null)d+=Math.hypot(r.pocket.x-r.targetBounce.x,r.pocket.y-r.targetBounce.y);
        return d/w;
    }
    public static float pullFor(ShotPlanner.Shot r,float w){
        if(r.kind>=4)return BREAK_PULL;
        // Each extra coin in the chain and each cushion loses energy (practice log: complex shots arrived weak).
        // v4.7.6: halved (practice 23:09-23:35: pulls under 0.25 potted 4/5, pulls at the 0.27 cap 7/18).
        float extra=Math.max(0,r.chain.size()-1)*.015f+(r.wall>=0?.01f:0f);
        return Math.max(MIN_PULL,Math.min(MAX_PULL,BASE_PULL+PULL_PER_TRAVEL*travel(r,w)+extra));
    }
    private boolean lastWasMove;
    private ShotPlanner.Shot lastShotRoute;
    /** Last slide {fromX, toX, sliderY} and last shot gesture {x1,y1,x2,y2}; NaN when none. */
    private float[] lastSlide={Float.NaN,Float.NaN,Float.NaN},lastGesture={Float.NaN,Float.NaN,Float.NaN,Float.NaN};
    public float[] lastSlide(){return lastSlide.clone();}
    public float[] lastGesture(){return lastGesture.clone();}
    /** The route actually fired by the last shot action (the ★ route right after a slide). */
    public ShotPlanner.Shot lastShotRoute(){return lastShotRoute;}
    private ShotPlanner.Shot goldRoute,slideRoute;
    /** 4.9.2: a ★ route that failed the pre-fire check on the current board; never offered again. */
    private ShotPlanner.Shot rejectedRoute;
    /** The route the ★ search already validated at the ★ spot; fired right after the slide instead of re-planning. */
    public void setGoldRoute(ShotPlanner.Shot route){goldRoute=route;}
    /** False while the background ★ search is still running; auto play waits so it can move there first. */
    public void setGoldReady(boolean ready){goldReady=ready;}
    private boolean enabled,busy,shotSent,moved;private int slides;
    /** The player slid the striker during this turn (not BitAim): play from there, do not slide away. */
    private boolean userPlaced;private float turnStrikerX=Float.NaN;
    private int stable,absent,failures;
    private long lastAction;
    private float[][] lastCoins;
    private long lastFrame=-1;
    private float lastX=Float.NaN,lastY,pendingX=Float.NaN,pull=.12f;
    private String status="";
    public boolean enabled(){return enabled;}
    /** What auto play is doing or waiting for, for the on-screen line. */
    private String note;private float lastFingerDx=Float.NaN;
    /** One-shot diagnostic line for the event log (slider landings). */
    public String consumeNote(){String n=note;note=null;return n;}
    public String status(){return status;}
    public void setEnabled(boolean value){lastSlide=new float[]{Float.NaN,Float.NaN,Float.NaN};slideRoute=null;enabled=value;busy=shotSent=moved=false;slides=0;stable=absent=failures=0;turnStart=-1;lastX=Float.NaN;pendingX=Float.NaN;lastCoins=null;lastFrame=-1;status=value?"starting":"";}
    public void setPull(float fraction){pull=Math.max(.06f,Math.min(.25f,fraction));}
    /** Gesture result. Three failed or cancelled gestures in a row disarm auto play. */
    public void complete(boolean success){
        busy=false;
        if(success){failures=0;return;}
        if(++failures>=MAX_FAILURES){enabled=false;status="stopped: gesture failed "+failures+" times";}
        else{shotSent=false;status="gesture cancelled, retrying";}
    }
    public Action update(GameState s,ShotPlanner.Shot route,float goldX,long now){return update(s,route,goldX,now,s);}
    /** {@code planned} is the frame the route was validated on; it must match the route signature. */
    public Action update(GameState s,ShotPlanner.Shot route,float goldX,long now,GameState planned){
        if(planned==null)planned=s;
        if(!enabled||busy)return null;
        if(s!=null){if(s.frameId==lastFrame)return null;lastFrame=s.frameId;}
        if(s==null||s.board==null||!s.teamKnown||s.striker==null||!s.strikerDetected){
            // One or two missed striker frames (end circle, finger, animation) must not reset the steady count.
            if(absent>=2){stable=0;lastX=Float.NaN;}
            if(++absent>=3){if(shotSent){shotSent=false;moved=false;slides=0;pendingX=Float.NaN;slideRoute=null;}turnStart=-1;}
            status=s==null||s.board==null?"waiting for the board":!s.teamKnown?"waiting: own colour unknown":"waiting for your turn";
            return null;
        }
        absent=0;
        if(turnStart<0){turnStart=now;slides=0;userPlaced=false;turnStrikerX=s.striker.pos.x;lastRouteAt=now;}
        // User 00:50: "if I give the correct position it goes to the wrong position". A striker moved more than a
        // radius while BitAim has not slid it this turn was placed by the player.
        if(!moved&&!busy&&!Float.isNaN(turnStrikerX)&&Math.abs(s.striker.pos.x-turnStrikerX)>s.striker.radius)userPlaced=true;
        long turn=now-turnStart;boolean hurry=turn>=HURRY_MS;turnNow=now;
        if(shotSent){status="shot sent, waiting for the board to settle";return null;}
        // After a slide only a short settle is needed; the longer cooldown is for the turn after a shot.
        if(now-lastAction<(lastWasMove?MOVE_SETTLE_MS:2500)){status=lastWasMove?"striker moved, settling":"waiting after last action";return null;}
        if(!Float.isNaN(pendingX)){
            // 4.9.9 (live 11:24:57): the slider landed 15 px from the ★ (732 -> 717); within the old one-radius
            // tolerance, so no correction, but the ★ pot no longer worked from there and the turn fell back to a blast.
            // Correct any landing more than a quarter radius off while slides remain.
            float off=Math.abs(s.striker.pos.x-pendingX);
            // 4.9.11: slider calibration data (start, target, finger travel, where it really stopped).
            if(!Float.isNaN(lastSlide[0])&&!(off>s.striker.radius&&now-lastAction<MOVE_VERIFY_MS))note=String.format(java.util.Locale.US,"SLIDE_LANDED start=%.1f target=%.1f finger=%.1f landed=%.1f off=%.1f",lastSlide[0],pendingX,lastFingerDx,s.striker.pos.x,s.striker.pos.x-pendingX);
            // 4.10.4: the slider cannot move less than ~15 px precisely (logged slides); no correction under 12 px.
            if(off>Math.max(12f,s.striker.radius*.25f)&&(off>s.striker.radius||slides<MAX_SLIDES&&turn<HURRY_MS)){
                if(off>s.striker.radius&&now-lastAction<MOVE_VERIFY_MS){status="moving striker";return null;}
                if(slides<MAX_SLIDES&&turn<HURRY_MS){
                    // Corrective slide for the remainder: the ★ is often the only spot that works on a busy board.
                    float target=pendingX;
                    return slide(s,target,now,"correcting striker spot (★)");
                }
                status="slider landed off target, playing from here"; // never disarm for this
            }
            pendingX=Float.NaN;stable=0;lastX=Float.NaN;
        }
        // Steady = striker still and discs not really moving. Live frames (20:16) showed the old exact key (8 px grid,
        // disc count, route identity) reset every frame on a dense opening, so auto play never got past this step.
        boolean same=!Float.isNaN(lastX)&&Math.hypot(s.striker.pos.x-lastX,s.striker.pos.y-lastY)<s.striker.radius*.30f&&layoutSteady(lastCoins,s); // practice striker has an animated glow ring
        stable=same?stable+1:0;lastX=s.striker.pos.x;lastY=s.striker.pos.y;lastCoins=snapshot(s);
        if(stable<STEADY_FRAMES){status="waiting for a steady board ("+stable+"/"+STEADY_FRAMES+")";return null;}
        // 4.9.1 (user 09:0x: "with autoplay it hits a weaker path; stop auto and within a second a stronger path appears;
        // the weak path has a blocker in the middle"): only fire a plan made on this settled board. A plan from a frame
        // where discs were still sliding can route through a disc that has since stopped in the way.
        if(route!=null&&planned!=s&&!sameNearRoute(route,planned,s)){status="re-planning on the settled board";return null;}
        if(!moved&&!userPlaced&&!goldReady&&turn<GOLD_WAIT_MS){status="finding the best striker spot (★)";return null;}
        // Live 00:00:55: re-armed mid-turn, the ★ search was not ready after 4 s and a 41% coin cushion was fired from
        // the centre; the ★ spot had an 81% direct pot. A weak shot from here waits for the ★ until relaxed mode.
        // 4.9.0: openers potted 2/11 in practice (08:04-08:50) while the ★ spot often had a direct pot: with only an opener
        // here, wait for the ★ up to OPENER_WAIT_MS.
        boolean onlyOpener=route==null||route.kind>=4;
        if(!moved&&!userPlaced&&!goldReady&&(!hurry||onlyOpener&&turn<OPENER_WAIT_MS)&&(route==null||Float.isNaN(route.chancePercent)||RoutePlanner.rankChance(route)<60)){
            status="finding a better striker spot (★)";return null;
        }
        float sx=s.striker.pos.x,sy=s.striker.pos.y,w=s.board.width();
        if(!moved&&!userPlaced&&!Float.isNaN(goldX)&&goldX>s.board.left+w*(RoutePlanner.REACH_MIN-.02f)&&goldX<s.board.right-w*(RoutePlanner.REACH_MIN-.02f)&&Math.abs(goldX-sx)>s.striker.radius){
            slideRoute=goldRoute; // freeze: a restarting ★ search must not wipe the shot prepared for this spot
            return slide(s,goldX,now,"moving striker to ★");
        }
        // Just slid to the ★: fire the shot the ★ search already simulated there (no full re-plan, no timer loss).
        ShotPlanner.Shot ready=readyGoldRoute(s);
        // 4.8.8 (practice 06:41:44, user "still missing easy"): the ★ route was a 47% coin-cushion pot while the live plan
        // at the same spot (with the aim sweep) had a 56% direct pot. Fire the better of the two when the live plan is
        // verified on this board and was made from this striker spot.
        if(ready!=null&&route!=null&&planned!=null&&route.modelValidated&&route.kind<4&&route.strikerPath.size()>=2
                &&Math.abs(route.strikerPath.get(0).x-s.striker.pos.x)<=s.striker.radius*.5f
                &&route.worldSignature==PhysicsVerifier.signature(planned)&&RoutePlanner.compare(route,ready)<0)ready=null;
        if(ready!=null){route=ready;planned=null;}
        String reject=ready!=null?null:hurry?rejectHurried(route,planned):reject(route,planned);
        // Opening cluster with no route (practice 01:31: 5 s of ★ search found nothing, then a rushed shot): break
        // at once instead of waiting for the fallback time.
        // 4.9.3 (practice 09:42:06-11): the break fired while a fresh plan was still being computed (route briefly
        // null). Break only after 2.5 s with no route at all.
        if(route!=null)lastRouteAt=now;
        boolean noRouteLong=now-lastRouteAt>=NO_ROUTE_BREAK_MS;
        if(reject!=null&&route==null&&noRouteLong&&!s.coins.isEmpty()&&(turn>=BREAK_FALLBACK_MS||s.openingCluster&&turn>=1500)){
            // Practice 23:31 (opening): no route at all after the ★ slide, so the whole turn was lost. A lost turn is
            // the worst outcome: break straight at the middle of the discs at break power instead.
            // 4.9.9 (live 11:25:01, mid-game): the full-power blast at the centre of every disc hit the own disc touching
            // the striker straight up and wasted the turn. Mid-game the fallback aims at the own discs at medium power.
            boolean midGame=!s.openingCluster;
            float cx=0,cy=0;int cn=0;for(Coin c:s.coins)if(!midGame||c.targetEligible){cx+=c.pos.x;cy+=c.pos.y;cn++;}
            if(cn==0){for(Coin c:s.coins){cx+=c.pos.x;cy+=c.pos.y;}cn=s.coins.size();}
            cx/=cn;cy/=cn;
            float bx=cx-sx,by=cy-sy,bl=(float)Math.hypot(bx,by);
            // 4.8.9: aim into the side of the cluster the simulation pots best from here (straight at the centre was the
            // weakest break in the local study), at the speed this pull produces.
            float breakPull=midGame?.21f:Math.max(.06f,Math.min(.30f,BREAK_PULL*(pull/.12f)));
            float[] safe=midGame?ClusterPlanner.safeFallback(s,w,350_000_000L):null;
            if(safe!=null){bx=safe[0];by=safe[1];bl=1f;breakPull=Math.max(MIN_PULL,Math.min(MAX_PULL,safe[2]/LAUNCH_PER_PULL));}
            else{
                float off=(float)Math.toRadians(ClusterPlanner.breakOffsetDegrees(s,LAUNCH_PER_PULL*breakPull*w,300_000_000L));
                if(off!=0&&bl>1){float c0=(float)Math.cos(off),s0=(float)Math.sin(off),nx=bx*c0-by*s0,ny=bx*s0+by*c0;bx=nx;by=ny;}
            }
            if(bl>.5f&&by<0){
                float distance=w*breakPull;
                lastShotRoute=null;lastGesture=new float[]{sx,sy,sx-bx/bl*distance,sy-by/bl*distance};slideRoute=null;
                busy=shotSent=true;lastAction=now;lastWasMove=false;stable=0;status=midGame?"no route: medium shot at own discs":"no route: break shot at the discs";
                return new Action(false,sx,sy,sx-bx/bl*distance,sy-by/bl*distance,turn<LATE_MS?HURRIED_AIM_BUDGET_MS:0);
            }
        }
        if(reject!=null){status=reject+(hurry?"":" ("+Math.max(0,(HURRY_MS-turn)/1000)+" s to relaxed mode)");return null;}
        // 4.9.2 (live 09:13:26): on a 40 px aim leg, recomputing the direction from a striker detected 3.6 px off the
        // planned spot turned the shot 2.4 degrees. When the striker is where the plan put it (detection jitter only),
        // fire along the plan's own line.
        PointF p0=route.strikerPath.get(0),p1=route.strikerPath.get(1);
        boolean onPlan=Math.hypot(p0.x-sx,p0.y-sy)<s.striker.radius*.35f;
        float dx=onPlan?p1.x-p0.x:p1.x-sx,dy=onPlan?p1.y-p0.y:p1.y-sy;
        float length=(float)Math.hypot(dx,dy);if(length<1){status="no aim direction";return null;}
        // Measured from the shot log (3 auto shots, 2026-11-07 20:17-20:18): launch speed ~= 5.0 x pull (both in
        // board widths). Pull = planned speed / 5, times the user's fine-tune (12% setting = 1.0), up to the game's
        // full-power ring (~0.22-0.25 board widths).
        // 4.8.6: the pull is the verified launch speed divided by the measured launch per pull (one physics for
        // planning and shooting); routes without a verified speed keep the travel rule.
        float base=route.assumedLaunchSpeed>0?route.assumedLaunchSpeed/(LAUNCH_PER_PULL*w):pullFor(route,w);
        float chosenPull=safePull(s,route,w,base*(pull/.12f));
        if(Float.isNaN(chosenPull)){
            if(turn<LATE_MS){
                rejectedRoute=route;goldRoute=null;slideRoute=null; // never offer this (stale) route again
                status="route no longer pots on this board, re-planning";return null;
            }
            chosenPull=Math.max(MIN_PULL,Math.min(MAX_PULL,base*(pull/.12f))); // timer almost out: shoot anyway
        }
        float distance=w*Math.max(.06f,Math.min(.30f,chosenPull));
        lastShotRoute=route;
        lastGesture=new float[]{sx,sy,sx-dx/length*distance,sy-dy/length*distance};
        slideRoute=null;
        busy=shotSent=true;lastAction=now;lastWasMove=false;stable=0;status=(hurry?"shooting (time running out): ":"shooting: ")+RoutePlanner.label(route);
        int aimBudget=!hurry?AIM_BUDGET_MS:turn<LATE_MS?HURRIED_AIM_BUDGET_MS:0;
        Action shot=new Action(false,sx,sy,sx-dx/length*distance,sy-dy/length*distance,aimBudget);
        shot.aimX=route.strikerPath.get(1).x;shot.aimY=route.strikerPath.get(1).y;
        return shot;
    }
    /** Launch speed in board widths per second per board width of pull (3 auto shots, 2026-11-07 20:17-20:18). */
    /** 4.8.6: 8.5 under the constant-friction model (motion tracks gave 10.3, pot/miss labels 7; same agreement). */
    static final long OPENER_WAIT_MS=10_000;
    public static final float LAUNCH_PER_PULL=8.5f;
    /** Live 00:01:53: a soft-touch combination (verified at 1.1 board widths/s) was pulled at the 0.27 maximum and
     *  the striker followed the coins into the pocket (foul). The pull is now checked in the model at the speed it
     *  produces; a failing pull steps down (or slightly up), else falls back to the verified speed. */
    public static float safePull(GameState s,ShotPlanner.Shot r,float w,float p){
        if(s==null||r==null||r.kind>=4||r.strikerPath.size()<2||r.chain.isEmpty()||r.pocket==null)return p;
        ShotPlanner.Shot local=onBoard(s,r);if(local==null)return p;
        // v4.8.1: never below MIN_PULL (0.19 stopped on the pocket lip live; the model is optimistic about soft shots,
        // user 00:50 "uses less force when full force is needed"); try a little more power before less.
        // 4.9.2: softer pulls are tried before harder ones, and a pull must be follow-through safe (live 09:21:49:
        // the striker followed a short straight pot into the pocket).
        for(float d:new float[]{0f,-.02f,.02f,-.04f,-.06f}){
            float q=Math.max(MIN_PULL,Math.min(MAX_PULL,p+d));
            if(PhysicsVerifier.simulate(s,local,LAUNCH_PER_PULL*q*w)&&PhysicsVerifier.followSafe(s,local,LAUNCH_PER_PULL*q*w))return q;
        }
        // 4.9.2: nothing pots on the board as it is now (a disc moved into the path after the plan, live 09:20:25):
        // tell the caller instead of firing a route that cannot work.
        return Float.NaN;
    }
    /** The route rebuilt on this frame's discs (★ routes reference discs of the search copy). */
    private static ShotPlanner.Shot onBoard(GameState s,ShotPlanner.Shot r){
        ShotPlanner.Shot c=new ShotPlanner.Shot();
        c.kind=r.kind;c.pocket=r.pocket;c.ghost=r.ghost;c.wall=r.wall;c.strikerPath.addAll(r.strikerPath);c.coinEndpoints.addAll(r.coinEndpoints);
        c.assumedLaunchSpeed=r.assumedLaunchSpeed;
        for(Coin old:r.chain){Coin m=nearest(s,old);if(m==null)return null;c.chain.add(m);}
        c.coin=nearest(s,r.coin);return c.coin==null?null:c;
    }
    private static Coin nearest(GameState s,Coin old){
        if(old==null)return null;if(s.coins.contains(old))return old;
        Coin best=null;float bd=old.radius*.75f;
        for(Coin o:s.coins){float d=(float)Math.hypot(o.pos.x-old.pos.x,o.pos.y-old.pos.y);if(d<bd){bd=d;best=o;}}
        return best;
    }
    /** Slide the striker toward {@code targetX}: the finger travels (target - striker) / SLIDER_GAIN. */
    private Action slide(GameState s,float targetX,long now,String text){
        float sx=s.striker.pos.x,w=s.board.width();
        float controlY=Float.isNaN(s.sliderY)?s.board.bottom+w*.40f:s.sliderY;
        float[] reach=RoutePlanner.reachable(s);
        targetX=Math.max(reach[0],Math.min(reach[1],targetX));
        float fingerX=Math.max(s.board.left+w*.02f,Math.min(s.board.right-w*.02f,sx+fingerFor(targetX-sx)));
        lastFingerDx=fingerX-sx;
        moved=busy=true;slides++;lastAction=now;lastWasMove=true;pendingX=targetX;stable=0;status=text;
        lastSlide=new float[]{sx,targetX,controlY};
        return new Action(true,sx,controlY,fingerX,controlY);
    }
    /** 4.10.4: finger travel for a wanted striker move, from 16 logged slides (4.9.11 SLIDE_LANDED): small drags jump
     *  about 15 px further than the finger (18 px finger -> 32 px), large drags move about 0.82 x the finger. */
    static float fingerFor(float want){
        float a=Math.abs(want),sign=Math.signum(want);
        if(a<=55f)return sign*Math.max(4f,a-15f);
        return want/.82f;
    }
    private long turnNow=-1;
    private boolean hurried(){return turnStart>=0&&turnNow-turnStart>=HURRY_MS;}
    private ShotPlanner.Shot readyGoldRoute(GameState s){
        ShotPlanner.Shot g=slideRoute!=null?slideRoute:goldRoute;
        if(g!=null&&g==rejectedRoute)return null;
        if(!moved||g==null||!g.modelValidated||g.strikerPath.size()<2)return null;
        if(Math.abs(g.strikerPath.get(0).x-s.striker.pos.x)>s.striker.radius)return null; // not at the ★ (slider landed off)
        if(g.robustnessTrials>0&&g.robustnessPassed<MIN_STRESS)return null;
        if(g.kind<4&&!Float.isNaN(g.chancePercent)&&RoutePlanner.rankChance(g)<25)return null; // fragile ★ shot: re-plan here
        if((g.kind==1||g.railFirst)&&!hurried())return null; // striker wall shot: last resort only
        // The board must still be the one the search used: every disc still in place.
        for(Coin c:g.chain){boolean found=false;for(Coin o:s.coins)if(Math.hypot(o.pos.x-c.pos.x,o.pos.y-c.pos.y)<c.radius*.5f){found=true;break;}if(!found)return null;}
        return g;
    }
    private long lastRouteAt=-1;static final long NO_ROUTE_BREAK_MS=2500;
    /** 4.9.3: the settled-board check looks only at discs near the route (striker leg, every disc leg, target to
     *  pocket). One flickering disc elsewhere kept "re-planning on the settled board" until the turn ran out (09:42,
     *  09:43). Near the route, every disc now must match the plan within 0.35 radius, and the striker too. */
    static boolean sameNearRoute(ShotPlanner.Shot r,GameState planned,GameState now){
        if(planned==null||now==null||planned.striker==null||now.striker==null)return false;
        if(Math.hypot(planned.striker.pos.x-now.striker.pos.x,planned.striker.pos.y-now.striker.pos.y)>now.striker.radius*.35f)return false;
        java.util.List<float[]> legs=new java.util.ArrayList<>();
        for(int i=0;i+1<r.strikerPath.size();i++)legs.add(new float[]{r.strikerPath.get(i).x,r.strikerPath.get(i).y,r.strikerPath.get(i+1).x,r.strikerPath.get(i+1).y});
        for(int i=0;i<r.chain.size()&&i<r.coinEndpoints.size();i++)legs.add(new float[]{r.chain.get(i).pos.x,r.chain.get(i).pos.y,r.coinEndpoints.get(i).x,r.coinEndpoints.get(i).y});
        float reach=now.striker.radius*2.6f;
        for(GameState[] pair:new GameState[][]{{now,planned},{planned,now}})for(Coin c:pair[0].coins){
            boolean near=false;for(float[] l:legs)if(segDist(c.pos.x,c.pos.y,l)<reach){near=true;break;}
            if(!near)continue;
            boolean ok=false;for(Coin p:pair[1].coins)if(Math.hypot(p.pos.x-c.pos.x,p.pos.y-c.pos.y)<c.radius*.35f){ok=true;break;}
            if(!ok)return false;
        }
        return true;
    }
    private static float segDist(float px,float py,float[] l){
        float dx=l[2]-l[0],dy=l[3]-l[1],len2=dx*dx+dy*dy;float t=len2<1e-6f?0:Math.max(0,Math.min(1,((px-l[0])*dx+(py-l[1])*dy)/len2));
        return (float)Math.hypot(px-l[0]-t*dx,py-l[1]-t*dy);
    }
    /** Every disc of {@code now} within 0.35 radius of a disc in {@code planned}, same disc count, striker in place. */
    static boolean sameBoard(GameState planned,GameState now){
        if(planned==null||now==null||planned.coins.size()!=now.coins.size())return false;
        if(planned.striker==null||now.striker==null||Math.hypot(planned.striker.pos.x-now.striker.pos.x,planned.striker.pos.y-now.striker.pos.y)>now.striker.radius*.35f)return false;
        for(Coin c:now.coins){boolean ok=false;for(Coin p:planned.coins)if(Math.hypot(p.pos.x-c.pos.x,p.pos.y-c.pos.y)<c.radius*.35f){ok=true;break;}if(!ok)return false;}
        return true;
    }
    private static float[][] snapshot(GameState s){float[][] out=new float[s.coins.size()][];for(int i=0;i<out.length;i++){Coin c=s.coins.get(i);out[i]=new float[]{c.pos.x,c.pos.y,c.radius};}return out;}
    /** At most two discs (or 15% on a busy board) may flicker or shift; the rest must be within 0.6 radius of before. */
    static boolean layoutSteady(float[][] prev,GameState s){
        if(prev==null)return false;
        if(Math.abs(prev.length-s.coins.size())>2)return false;
        if(s.coins.isEmpty())return true;
        int matched=0;
        for(Coin c:s.coins){for(float[] p:prev)if(Math.hypot(p[0]-c.pos.x,p[1]-c.pos.y)<c.radius*.6f){matched++;break;}}
        return s.coins.size()-matched<=Math.max(2,Math.round(s.coins.size()*.15f));
    }
    /** Late in the turn: any verified route the model pots or spreads with, rather than letting the timer expire. */
    static String rejectHurried(ShotPlanner.Shot route,GameState planned){
        if(route==null)return "no route from here";
        if(!route.modelValidated||route.strikerPath.size()<2)return "route not verified";
        if(route.worldSignature!=PhysicsVerifier.signature(planned))return "board changed, re-planning";
        return null;
    }
    /** Null when the route may be auto-played, otherwise the reason it is not. */
    static String reject(ShotPlanner.Shot route,GameState planned){
        if(route==null)return "no route from here";
        if(!route.modelValidated||route.strikerPath.size()<2)return "route not verified";
        if(route.worldSignature!=PhysicsVerifier.signature(planned))return "board changed, re-planning";
        if(route.robustnessTrials>0&&route.robustnessPassed<MIN_STRESS)return "route too sensitive ("+route.robustnessPassed+"/"+route.robustnessTrials+"), waiting";
        if(route.kind<4&&!Float.isNaN(route.aimMarginDegrees)&&route.aimMarginDegrees<MIN_TOLERANCE)return "route too tight (<0.25°), waiting";
        // Striker wall shots (live 00:47: aligned, yet off the rail at the wrong angle; 1/5 potted) only when hurried.
        if(route.kind==1||route.railFirst)return "wall shot: waiting for a better route";
        return null;
    }
}
