package com.bitaim.carromaim.cv;

import android.graphics.PointF;
import java.util.*;

/** Nominal circle model, not a calibrated prediction of the game's engine.
 * Continuous collision times prevent stepping through small pieces. */
public final class PhysicsVerifier {
    /** Rails, striker along-rail loss and pocket capture fitted to 128 real auto shots (OutcomeCalibrator, 2026-11-08):
     *  pot/miss agreement 62% -> 72%, model-says-pot-but-missed 39 -> 25. */
    private static final double RESTITUTION=.96,WALL=.80;
    /** Drag constant (velocity retention 0.70/s by default) and pocket capture radius as a fraction of board
     *  width. Rails stay at pocket centre +/- 0.05 board widths regardless. Desktop reality-gap runs vary these. */
    /** Live 2026-11-08 00:47:18 (motion picture): the striker met the right rail at 26° and left at 22°
     *  (tan ratio 0.84), where the mirror-with-restitution model (normal ×0.9, along-rail ×1) gives 30° (ratio 1.11).
     *  That suggests along-rail ×0.76 for the striker, but it is one sample: the model keeps 1.0 until more bounces
     *  are measured, and auto play treats striker wall shots as a last resort instead (striker cushions potted 1/5). */
    public static volatile double STRIKER_RAIL_TANGENT=.76;
    public static volatile double FRICTION=0,CAPTURE_FRAC=.042;
    /** Constant (Coulomb) sliding friction in board widths/s^2 (4.8.6). Motion tracks of real shots showed speed falling
     *  with the square root of the remaining distance (exponent 0.56; 0.5 = constant friction, 1.0 = drag) and
     *  every shot still moving after 3-4 s, which drag-only cannot do. Deceleration = COULOMB + FRICTION x speed. */
    public static volatile double COULOMB=.60;
    /** Time slice for the friction update; collisions inside a slice are solved exactly for straight motion.
     *  4.9.0: 1/20 s (2.5x faster than 1/60; on 720 logged-board launches discs differ by 0.12 radius and pot/miss by
     *  0.7%). The ★ search had become slower than the 7 s wait on the phone, so openers were fired instead. */
    public static volatile double SLICE=1/20.0;
    private static class Body {
        double x,y,vx,vy,r,mass;boolean active=true,own=true;int pocket=-1;
    }
    private static class Event {double tau;int kind,a,b;Event(double t,int k,int x,int y){tau=t;kind=k;a=x;b=y;}}
    static final double[][] STRESS={{1,0,.80},{.9,0,.80},{1.1,0,.80},{1,-.35,.80},{1,.35,.80},{1,0,.77},{1,0,.83},{.9,-.35,.77},{1.1,.35,.83}};
    private static class SpeedCandidate {
        final float speed;final int moved,others,pairs;boolean productive;
        int potPasses,productivePasses,gain;
        SpeedCandidate(float speed,ShotPlanner.Shot route){
            this.speed=speed;moved=route.clusterMoved;others=route.otherCoinsMoved;pairs=route.closePairsOpened;productive=route.productivePot;
        }
        boolean robustProductive(){return productive&&productivePasses>=7;}
        void restore(ShotPlanner.Shot route,long signature){
            route.modelValidated=true;route.assumedLaunchSpeed=speed;route.worldSignature=signature;
            route.clusterMoved=moved;route.otherCoinsMoved=others;route.closePairsOpened=pairs;route.productivePot=productive;
            route.robustnessTrials=STRESS.length;route.robustnessPassed=potPasses;route.productiveStressPassed=productivePasses;
        }
    }
    /** 4.12.0 speed level (SpeedProfile): try every power (true) or stop at the first valid one; how many powers get
     *  the 9-trial stress test (2, or 1 on the faster levels). */
    public static volatile boolean SPEED_BEAM=true;public static volatile int STRESS_CANDIDATES=2;
    public static boolean validate(GameState state,ShotPlanner.Shot route){
        if(route.kind==5||route.sweep)return route.modelValidated&&route.worldSignature==signature(state); // OpeningPlanner and SweepPlanner validate their own shots
        route.modelValidated=false;
        route.robustnessPassed=route.robustnessTrials=0;
        route.productiveStressPassed=0;
        long signature=signature(state);
        // 4.9.8 (user: "hit the easy coin hard so the striker breaks the cluster"): every power is tried, not only in
        // close-pair scenes. Logged geometric "Pot + open cluster" shots potted 7/9 against ~65% for plain pots.
        boolean speedBeam=SPEED_BEAM;int pathsNow=OpeningPlanner.pathsNow(state);
        List<SpeedCandidate> candidates=new ArrayList<>();
        // Complex scene: three nominal solves, then at most two nine-trial sets.
        // Isolated scenes retain the first-valid-speed fast path (at most 12 solves).
        // soft touches last: 0.8 and (manual-play review 2026-11-07) 0.6 keep the striker or a carom out of a pocket
        // 4.8.6: speeds the auto player can really produce (8.5 x pull 0.17-0.27, AutoPlayController.LAUNCH_PER_PULL);
        // under constant friction travel grows with speed squared, so 1.45 is a soft touch and 2.3 is full power.
        // 4.9.2: softest first (a soft pot cannot follow the disc in); a speed must also be follow-through safe.
        for(float boardsPerSecond:new float[]{1.45f,1.6f,1.9f,2.1f,2.3f}){
            if(Thread.currentThread().isInterrupted())return false;
            float speed=state.board.width()*boardsPerSecond;
            if(simulate(state,route,speed)&&followSafe(state,route,speed)){
                SpeedCandidate c=new SpeedCandidate(speed,route);
                // Position play: own pocket paths the settled board gains at this power (striker and discs moved on).
                double[] u=direction(state,route,0);
                if(u!=null&&route.kind<4){Outcome o=run(state,u[0],u[1],speed,WALL,state.coins.indexOf(route.chain.get(0)));
                    if(!o.aborted&&o.completed)c.gain=OpeningPlanner.pathsAfter(state,o)-pathsNow;}
                if(c.gain>=1&&c.others>=1)c.productive=true;
                candidates.add(c);
                if(!speedBeam)break;
            }
        }
        Collections.sort(candidates,(a,b)->{
            int order=Boolean.compare(b.productive,a.productive);if(order!=0)return order;
            if(a.productive){order=Integer.compare(b.gain,a.gain);if(order!=0)return order;order=Integer.compare(b.pairs,a.pairs);if(order!=0)return order;order=Integer.compare(b.others,a.others);if(order!=0)return order;}
            return Float.compare(a.speed,b.speed);
        });
        SpeedCandidate best=null;
        // The softest valid power always stays in the stress test, so a fragile hard pot never beats a safe soft one.
        if(candidates.size()>2){SpeedCandidate soft=candidates.get(0);for(SpeedCandidate c:candidates)if(c.speed<soft.speed)soft=c;candidates.remove(soft);candidates.add(1,soft);}
        for(int i=0;i<Math.min(Math.max(1,STRESS_CANDIDATES),candidates.size());i++){
            SpeedCandidate candidate=candidates.get(i);
            for(double[] sample:STRESS){
                if(Thread.currentThread().isInterrupted())return false;
                if(simulate(state,route,(float)(candidate.speed*sample[0]),sample[1],sample[2])){
                    candidate.potPasses++;if(route.productivePot||(candidate.gain>=1&&candidate.productive))candidate.productivePasses++;
                }
            }
            if(best==null||better(candidate,best))best=candidate;
        }
        if(best==null)return false;
        // Aim tolerance: the bench on real screenshots showed stress 9/9 routes that fail at 0.25 degrees
        // of aim error while alternatives tolerate 3-8 degrees. Bracket both sides (at most 8 solves).
        float margin=aimMargin(state,route,best.speed);
        best.restore(route,signature);route.aimMarginDegrees=margin;
        route.minPotSpeed=route.kind<4?softestPot(state,route,best.speed):Float.NaN;
        // One-ply positional look-ahead: how many direct pocket paths remain after this pot settles.
        double[] u=direction(state,route,0);
        if(u!=null){
            Outcome o=run(state,u[0],u[1],best.speed,WALL,state.coins.indexOf(route.chain.get(0)));
            route.pathsBefore=OpeningPlanner.pathsNow(state);
            route.pathsAfter=o.aborted||!o.completed?route.pathsBefore:OpeningPlanner.pathsAfter(state,o);
        }
        return true;
    }
    /** True when a heavier striker (FOLLOW_MASS) does not follow the route into a pocket at this speed. */
    public static boolean followSafe(GameState s,ShotPlanner.Shot route,float speed){
        if(route.kind>=4)return true;
        double[] u=direction(s,route,0);if(u==null)return true;
        Outcome o=run(s,u[0],u[1],speed,WALL,-1,FRICTION,CAPTURE_FRAC,COULOMB,FOLLOW_MASS);
        return o.strikerActive;
    }
    /** Scan upward from 0.5 board widths/s in 0.1 steps; the first speed that pots (at most the validated speed). */
    static float softestPot(GameState state,ShotPlanner.Shot route,float validatedSpeed){
        int others=route.otherCoinsMoved,pairs=route.closePairsOpened;boolean productive=route.productivePot;
        float w=state.board.width(),found=validatedSpeed;
        for(float boards=1.4f;boards*w<validatedSpeed-1e-3f;boards+=.1f){
            if(Thread.currentThread().isInterrupted())break;
            if(simulate(state,route,boards*w,0,WALL)){found=boards*w;break;}
        }
        route.otherCoinsMoved=others;route.closePairsOpened=pairs;route.productivePot=productive;
        return found;
    }
    /** Largest tested symmetric aim error that still pots: one of 0, .5, 1, 2 or 4 degrees. */
    static float aimMargin(GameState state,ShotPlanner.Shot route,float speed){
        float margin=0;
        for(float degrees:new float[]{.5f,1f,2f,4f}){
            if(Thread.currentThread().isInterrupted())break;
            if(!simulate(state,route,speed,degrees,WALL)||!simulate(state,route,speed,-degrees,WALL))break;
            margin=degrees;
        }
        return margin;
    }
    private static boolean better(SpeedCandidate a,SpeedCandidate b){
        if(a.robustProductive()!=b.robustProductive())return a.robustProductive();
        if(a.robustProductive()&&a.productivePasses!=b.productivePasses)return a.productivePasses>b.productivePasses;
        if(a.potPasses!=b.potPasses)return a.potPasses>b.potPasses;
        return a.speed<b.speed;
    }
    private static boolean hasClosePair(GameState state){
        for(int i=0;i<state.coins.size();i++)for(int j=i+1;j<state.coins.size();j++){
            Coin a=state.coins.get(i),b=state.coins.get(j);
            if(distance(a.pos.x,a.pos.y,b.pos.x,b.pos.y)<=a.radius+b.radius+state.board.width()*.018)return true;
        }
        return false;
    }
    public static long signature(GameState s){
        long out=0xcbf29ce484222325L;
        out=mix(out,Float.floatToIntBits(s.board.left));out=mix(out,Float.floatToIntBits(s.board.top));
        out=mix(out,Float.floatToIntBits(s.board.right));out=mix(out,Float.floatToIntBits(s.board.bottom));
        out=mix(out,s.teamKnown?1:0);out=mix(out,s.strikerDetected?1:0);
        out=mix(out,s.freestyle?1:0);out=mix(out,s.openingCluster?1:0);
        out=mix(out,s.queenRequired?1:0);out=mix(out,s.assignedColours?1:0);out=mix(out,s.targetColourKey);
        out=coinSignature(out,s.striker);out=mix(out,s.coins.size());
        for(Coin c:s.coins)out=coinSignature(out,c);
        out=mix(out,s.pockets.size());
        for(PointF p:s.pockets){out=mix(out,Float.floatToIntBits(p.x));out=mix(out,Float.floatToIntBits(p.y));}
        return out;
    }
    private static long mix(long hash,int value){return (hash^(value&0xffffffffL))*0x100000001b3L;}
    private static long coinSignature(long hash,Coin c){
        hash=mix(hash,Float.floatToIntBits(c.pos.x));hash=mix(hash,Float.floatToIntBits(c.pos.y));
        hash=mix(hash,Float.floatToIntBits(c.radius));hash=mix(hash,c.color);
        hash=mix(hash,c.targetEligible?1:0);hash=mix(hash,c.transferEligible?1:0);
        hash=mix(hash,c.isQueen?1:0);
        return mix(hash,c.isStriker?1:0);
    }
    public static boolean simulate(GameState s,ShotPlanner.Shot route,float speed){
        return simulate(s,route,speed,0,WALL);
    }
    /** Settled result of one launch: positions of every disc, pockets, first contact and abort reasons. */
    public static final class Outcome {
        public boolean completed,aborted,strikerActive=true;
        /** Index into {@code GameState.coins} of the first disc the striker touched, or -1. */
        public int firstContact=-1;
        public double[] x,y;public boolean[] active;public int[] pocket;
        public int moved(GameState s){int n=0;for(int i=0;i<s.coins.size();i++){Coin c=s.coins.get(i);if(!active[i]||Math.hypot(x[i]-c.pos.x,y[i]-c.pos.y)>c.radius)n++;}return n;}
    }
    /** Launch direction of a route rotated by {@code aimDegrees}; null when the route has no aim leg. */
    static double[] direction(GameState s,ShotPlanner.Shot route,double aimDegrees){
        if(route.strikerPath.size()<2)return null;
        PointF aim=route.strikerPath.get(1);double dx=aim.x-s.striker.pos.x,dy=aim.y-s.striker.pos.y,length=Math.hypot(dx,dy);
        if(length<1e-6)return null;
        double angle=Math.toRadians(aimDegrees),ux=dx/length,uy=dy/length;
        return new double[]{ux*Math.cos(angle)-uy*Math.sin(angle),ux*Math.sin(angle)+uy*Math.cos(angle)};
    }
    /** Run the nominal model from the current striker position along unit direction (ux,uy).
     * Aborts (never a valid recommendation) on a scratch, an opponent pot, or a first contact other than
     * {@code intendedFirst} when that coin index is non-negative. */
    public static Outcome run(GameState s,double ux,double uy,float speed,double wall,int intendedFirst){
        return run(s,ux,uy,speed,wall,intendedFirst,FRICTION,CAPTURE_FRAC);
    }
    /** Same model with explicit drag constant and pocket capture radius; thread-safe (no shared state). */
    public static Outcome run(GameState s,double ux,double uy,float speed,double wall,int intendedFirst,double friction,double captureFrac){
        return run(s,ux,uy,speed,wall,intendedFirst,friction,captureFrac,COULOMB);
    }
    /** {@code coulomb} is in board widths/s^2; zero keeps the exact drag-only solution. */
    public static Outcome run(GameState s,double ux,double uy,float speed,double wall,int intendedFirst,double friction,double captureFrac,double coulomb){
        return run(s,ux,uy,speed,wall,intendedFirst,friction,captureFrac,coulomb,1.2);
    }
    /** 4.9.2: striker mass relative to a disc. Live 09:21:49 (online game): a near-straight short pot rated 88% at
     *  1.9 board widths/s; the striker followed the disc into the pocket (foul). The nominal 1.2 nearly stops the
     *  striker on a straight hit; FOLLOW_MASS is the pessimistic check for follow-through scratches (1.6: 2.2 rejected ordinary straight pots near a pocket). */
    public static volatile double FOLLOW_MASS=1.6;
    public static Outcome run(GameState s,double ux,double uy,float speed,double wall,int intendedFirst,double friction,double captureFrac,double coulomb,double strikerMass){
        return run(s,ux,uy,speed,wall,intendedFirst,friction,captureFrac,coulomb,strikerMass,null);
    }
    /** 4.11.0: optional trace of the motion, {body, x, y, kind (1 contact, 3/4 rail), other body} at every contact and rail hit (body 0 = striker, i+1 = coin i),
     *  used to draw the real modelled paths (Lucky Shot). */
    public static Outcome run(GameState s,double ux,double uy,float speed,double wall,int intendedFirst,double friction,double captureFrac,double coulomb,double strikerMass,List<double[]> trace){
        Outcome out=new Outcome();
        List<Body> bodies=new ArrayList<>();Body striker=body(s.striker,strikerMass);bodies.add(striker);
        for(Coin c:s.coins){Body b=body(c,1);b.own=c.targetEligible;bodies.add(b);}
        boolean[] blockedPocket=new boolean[s.pockets.size()];
        for(int i=0;i<bodies.size();i++)for(int p=0;p<s.pockets.size();p++){
            Body b=bodies.get(i);PointF hole=s.pockets.get(p);
            // An opponent coin resting in a pocket mouth blocks only that pocket: anything entering it knocks the
            // lip coin in (opponent pot), so such pots are rejected, but every other pocket stays playable.
            if(i>0&&!b.own&&distance(b.x,b.y,hole.x,hole.y)<s.board.width()*.05)blockedPocket[p]=true;
            // Only a striker in a pocket mouth invalidates the scene. A coin resting on a pocket lip (common in
            // practice, live frame 2026-11-07 22:49) is not potted unless something moves it toward the hole; the
            // event loop handles that as an ordinary (opponent) pot. The old rule rejected every shot on the board.
            if(i==0&&distance(b.x,b.y,hole.x,hole.y)<s.board.width()*.05){out.aborted=true;return out;}
        }
        striker.vx=speed*ux;striker.vy=speed*uy;
        double railOffset=s.board.width()*.05,pocketRadius=s.board.width()*captureFrac,left=1e9,top=1e9,right=-1e9,bottom=-1e9;
        for(PointF p:s.pockets){left=Math.min(left,p.x-railOffset);right=Math.max(right,p.x+railOffset);top=Math.min(top,p.y-railOffset);bottom=Math.max(bottom,p.y+railOffset);}
        // Live 2026-11-07 23:42: the last own coin rested on the bottom rail and its detected edge sat 4 px past the
        // model's rail line, so it escaped through the rail in every simulation and no route was found. Detection
        // noise must not put a disc outside the table: clamp every body just inside the rails.
        for(Body b:bodies){b.x=Math.max(left+b.r,Math.min(right-b.r,b.x));b.y=Math.max(top+b.r,Math.min(bottom-b.r,b.y));}
        boolean first=false;
        double decelConst=coulomb*s.board.width(),stopSpeed=.002*s.board.width();
        boolean sliced=decelConst>0;
        for(int iteration=0;iteration<(sliced?6000:1024);iteration++){
            if(Thread.currentThread().isInterrupted()){out.aborted=true;break;}
            // Drag only: x(t)=x0+v0*(1-exp(-k*t))/k is straight in tau, so the whole remaining displacement is
            // searched at once. With constant friction, motion is advanced in short slices (straight inside a slice).
            double maxTau=sliced?SLICE:1/friction;
            if(sliced){boolean moving=false;for(Body b:bodies)if(b.active&&(b.vx!=0||b.vy!=0)){moving=true;break;}if(!moving){out.completed=true;break;}}
            Event next=new Event(maxTau,0,-1,-1);
            for(int i=0;i<bodies.size();i++){
                Body a=bodies.get(i);if(!a.active)continue;
                for(int j=i+1;j<bodies.size();j++){
                    Body b=bodies.get(j);if(b.active)next=earlier(next,entry(b.x-a.x,b.y-a.y,b.vx-a.vx,b.vy-a.vy,a.r+b.r),1,i,j,maxTau);
                }
                for(int p=0;p<s.pockets.size();p++){PointF hole=s.pockets.get(p);next=earlier(next,entry(a.x-hole.x,a.y-hole.y,a.vx,a.vy,pocketRadius),2,i,p,maxTau);}
                if(a.vx<0)next=earlier(next,(left+a.r-a.x)/a.vx,3,i,0,maxTau);
                if(a.vx>0)next=earlier(next,(right-a.r-a.x)/a.vx,3,i,0,maxTau);
                if(a.vy<0)next=earlier(next,(top+a.r-a.y)/a.vy,4,i,0,maxTau);
                if(a.vy>0)next=earlier(next,(bottom-a.r-a.y)/a.vy,4,i,0,maxTau);
            }
            if(sliced){
                for(Body b:bodies)if(b.active){
                    b.x+=b.vx*next.tau;b.y+=b.vy*next.tau;
                    double v=Math.hypot(b.vx,b.vy);if(v==0)continue;
                    double nv=v-(decelConst+friction*v)*next.tau;if(nv<stopSpeed)nv=0;
                    b.vx*=nv/v;b.vy*=nv/v;
                }
                if(next.kind==0)continue;
            }else{
                double decay=Math.max(0,1-friction*next.tau);
                for(Body b:bodies)if(b.active){b.x+=b.vx*next.tau;b.y+=b.vy*next.tau;b.vx*=decay;b.vy*=decay;}
                if(next.kind==0){out.completed=true;break;}
            }
            Body a=bodies.get(next.a);
            if(next.kind==1){
                if(!first&&(next.a==0||next.b==0)){
                    first=true;out.firstContact=(next.a==0?next.b:next.a)-1;
                    if(intendedFirst>=0&&out.firstContact!=intendedFirst){out.aborted=true;break;}
                }
                if(trace!=null){Body o=bodies.get(next.b);trace.add(new double[]{next.a,a.x,a.y,1,next.b});trace.add(new double[]{next.b,o.x,o.y,1,next.a});}
                impulse(a,bodies.get(next.b));
            }else if(next.kind==2){
                a.active=false;a.pocket=next.b;a.vx=a.vy=0;
                if(next.a==0||!a.own||blockedPocket[next.b]){out.aborted=true;break;} // reject nominal scratches/opponent pots
            }else if(next.kind==3){if(trace!=null)trace.add(new double[]{next.a,a.x,a.y,3,-1});a.vx*=-wall;if(next.a==0)a.vy*=STRIKER_RAIL_TANGENT;}
            else{if(trace!=null)trace.add(new double[]{next.a,a.x,a.y,4,-1});a.vy*=-wall;if(next.a==0)a.vx*=STRIKER_RAIL_TANGENT;}
        }
        int n=s.coins.size();out.x=new double[n];out.y=new double[n];out.active=new boolean[n];out.pocket=new int[n];
        for(int i=0;i<n;i++){Body b=bodies.get(i+1);out.x[i]=b.x;out.y[i]=b.y;out.active[i]=b.active;out.pocket[i]=b.pocket;}
        out.strikerActive=striker.active;
        return out;
    }
    public static boolean simulate(GameState s,ShotPlanner.Shot route,float speed,double aimDegrees,double wall){
        return simulate(s,route,speed,aimDegrees,wall,FRICTION,CAPTURE_FRAC);
    }
    /** Realistic chance (0-100) that the route pots under conditions the nominal model ignores, from a desktop
     *  reality-gap study over 48 suggested routes: hand aim error N(0,1 degree), player power x0.6-1.6,
     *  pocket capture 0.032-0.050 board widths, drag retention 0.60-0.80/s, rails 0.84-0.94. Aim error dominated.
     *  Seeded so the same scene always shows the same number. Nominal opening metrics are restored afterwards. */
    public static float chance(GameState s,ShotPlanner.Shot route,int samples){
        if(route.kind>=4||route.strikerPath.size()<2||route.assumedLaunchSpeed<=0)return Float.NaN;
        int others=route.otherCoinsMoved,pairs=route.closePairsOpened;boolean productive=route.productivePot;
        Random rnd=new Random(0x5eedL);int ok=0;
        for(int i=0;i<samples;i++){
            if(Thread.currentThread().isInterrupted())break;
            // 4.8.6: friction varies around the measured constant (0.50-0.70); power x0.8-1.2 (hand play varies more,
            // auto pulls are computed from the verified speed).
            double aim=rnd.nextGaussian(),mult=.8+rnd.nextDouble()*.4,capture=.032+rnd.nextDouble()*.018,coulomb=.50+rnd.nextDouble()*.20,wall=.74+rnd.nextDouble()*.12;
            if(simulate(s,route,(float)(route.assumedLaunchSpeed*mult),aim,wall,FRICTION,capture,coulomb))ok++;
        }
        route.otherCoinsMoved=others;route.closePairsOpened=pairs;route.productivePot=productive;
        return ok*100f/samples;
    }
    public static boolean simulate(GameState s,ShotPlanner.Shot route,float speed,double aimDegrees,double wall,double friction,double captureFrac){
        return simulate(s,route,speed,aimDegrees,wall,friction,captureFrac,COULOMB);
    }
    public static boolean simulate(GameState s,ShotPlanner.Shot route,float speed,double aimDegrees,double wall,double friction,double captureFrac,double coulomb){
        route.otherCoinsMoved=route.closePairsOpened=0;route.productivePot=false;
        if(route.strikerPath.size()<2||route.chain.isEmpty())return false;
        int intendedFirst=s.coins.indexOf(route.chain.get(0)),target=s.coins.indexOf(route.coin),goal=-1;
        for(int i=0;i<s.pockets.size();i++)if(distance(s.pockets.get(i).x,s.pockets.get(i).y,route.pocket.x,route.pocket.y)<1)goal=i;
        if(target<0||intendedFirst<0||goal<0)return false;
        double[] u=direction(s,route,aimDegrees);if(u==null)return false;
        Outcome o=run(s,u[0],u[1],speed,wall,intendedFirst,friction,captureFrac,coulomb);
        if(o.aborted||!o.completed)return false; // Unresolved event-budget exhaustion is never a validated shot.
        boolean first=o.firstContact>=0;
        if(route.kind==4){
            int moved=o.moved(s);
            route.clusterMoved=moved;
            return first&&o.strikerActive&&moved>=3;
        }
        boolean potted=first&&o.pocket[target]==goal;
        if(potted){
            for(int i=0;i<s.coins.size();i++){
                Coin original=s.coins.get(i);
                if(i!=target&&distance(o.x[i],o.y[i],original.pos.x,original.pos.y)>original.radius)route.otherCoinsMoved++;
                if(i==target||!o.active[i])continue;
                for(int j=i+1;j<s.coins.size();j++){
                    Coin other=s.coins.get(j);
                    if(j==target||!o.active[j])continue;
                    // A useful opening separates two surviving non-target discs,
                    // rather than counting the target's disappearance as an opening.
                    double close=original.radius+other.radius+s.board.width()*.018;
                    if(distance(original.pos.x,original.pos.y,other.pos.x,other.pos.y)<=close
                            &&distance(o.x[i],o.y[i],o.x[j],o.y[j])>close+Math.min(original.radius,other.radius))route.closePairsOpened++;
                }
            }
            route.productivePot=route.otherCoinsMoved>=2&&route.closePairsOpened>=1;
        }
        return potted;
    }
    private static Body body(Coin c,double mass){Body b=new Body();b.x=c.pos.x;b.y=c.pos.y;b.r=c.radius;b.mass=mass;return b;}
    private static Event earlier(Event old,double t,int kind,int a,int b,double maxTau){return t>=-1e-8&&t<maxTau&&t<=old.tau?new Event(Math.max(0,t),kind,a,b):old;}
    private static double entry(double x,double y,double vx,double vy,double r){
        double a=vx*vx+vy*vy,b=x*vx+y*vy,c=x*x+y*y-r*r;
        if(a==0||b>=0)return Double.POSITIVE_INFINITY;if(c<=1e-7)return 0;
        double d=b*b-a*c;return d<0?Double.POSITIVE_INFINITY:(-b-Math.sqrt(d))/a;
    }
    private static void impulse(Body a,Body b){
        double dx=b.x-a.x,dy=b.y-a.y,d=Math.hypot(dx,dy);if(d<1e-8)return;
        double nx=dx/d,ny=dy/d,vn=(b.vx-a.vx)*nx+(b.vy-a.vy)*ny;if(vn>=0)return;
        double j=-(1+RESTITUTION)*vn/(1/a.mass+1/b.mass);
        a.vx-=j*nx/a.mass;a.vy-=j*ny/a.mass;b.vx+=j*nx/b.mass;b.vy+=j*ny/b.mass;
        double overlap=a.r+b.r-d;if(overlap>0){a.x-=nx*overlap*b.mass/(a.mass+b.mass);a.y-=ny*overlap*b.mass/(a.mass+b.mass);b.x+=nx*overlap*a.mass/(a.mass+b.mass);b.y+=ny*overlap*a.mass/(a.mass+b.mass);}
    }
    private static double distance(double x,double y,double a,double b){return Math.hypot(x-a,y-b);}
}
