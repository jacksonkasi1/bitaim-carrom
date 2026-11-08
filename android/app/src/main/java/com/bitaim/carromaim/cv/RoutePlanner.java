package com.bitaim.carromaim.cv;

import android.graphics.PointF;
import android.graphics.RectF;
import java.util.*;

/** Bounded straight, one-cushion and up-to-two-transfer routes.
 * Conditional geometric predictions: no spin or calibrated power model.
 *
 * Ranking (v4.5): the desktop bench over real screenshots showed that routes passing 9/9 stress
 * samples could still fail at 0.25 degrees of aim error while alternatives tolerated 3-8 degrees.
 * Routes are therefore ordered by aim tolerance first (weighted by family, since rail rebounds are
 * modelled less reliably than direct contact), then by the previous complexity/robustness/score order.
 */
public final class RoutePlanner {
    private static final float MIN_COS=.35f;
    /** Tolerance a productive (pot + open) route needs before it may outrank simpler pots. */
    private static final float PRODUCTIVE_MIN_MARGIN=1f;
    /** Pro style: rank by "pot now + position for the next shot" (cluster breaks, rail pots) instead of easiest aim. */
    public static volatile boolean proStyle=false;
    /** Noisy trials per realistic-chance estimate (phone 32; the PC brain raises it). */
    public static volatile int CHANCE_SAMPLES=32;
    /** ★ search: baseline samples (phone 20 intervals), aim sweep every Nth sample (phone 4), candidates validated (phone 12).
     *  The PC brain raises these for a finer, deeper search; the phone keeps its time budget. */
    public static volatile int GOLD_STEPS=20,GOLD_SWEEP_EVERY=4,GOLD_VALIDATE=12;
    /** The striker centre can only reach this fraction of the board width from either side. Practice test drags
     *  2026-11-07 23:05: a slow drag puts the slider knob (directly under the striker) at its stop at ~0.24 of the
     *  board; fast 450 ms drags lagged and stopped near 0.29, which is why recorded slides seemed capped there. */
    public static final float REACH_MIN=.24f;
    /** Striker-centre x range reachable along the baseline from where it is: the slider stops at REACH_MIN and the
     *  striker can neither overlap nor pass a disc lying on its line (live 00:29: a coin beside the striker stopped
     *  two slides at x 401/409 while the ★ was at 252). Returns {min,max}. */
    public static float[] reachable(GameState s){
        float w=s.board.width(),min=s.board.left+w*REACH_MIN,max=s.board.right-w*REACH_MIN;
        if(s.striker==null)return new float[]{min,max};
        float sx=s.striker.pos.x,sy=s.striker.pos.y,rs=s.striker.radius;
        for(Coin c:s.coins){
            float R=rs+c.radius,dy=Math.abs(c.pos.y-sy);if(dy>=R)continue;
            float hw=(float)Math.sqrt(R*R-dy*dy),lo=c.pos.x-hw,hi=c.pos.x+hw;
            if(hi<=sx+rs*.25f)min=Math.max(min,hi+1);else if(lo>=sx-rs*.25f)max=Math.min(max,lo-1);
        }
        return new float[]{min,Math.max(min,max)};
    }
    public static List<ShotPlanner.Shot> routes(GameState s,Coin selected){
        List<ShotPlanner.Shot> pool=candidates(s,selected),out=new ArrayList<>();
        int[] tried=new int[8],kept=new int[8];
        for(ShotPlanner.Shot route:pool){
            int kind=complexity(route);
            if(tried[kind]>=8)continue;
            tried[kind]++;
            if(PhysicsVerifier.validate(s,route)){applyObjectiveScore(route);out.add(route);}
        }
        for(ShotPlanner.Shot route:ClusterPlanner.candidates(s,selected)){
            if(tried[6]++>=6||kept[6]>=2)break;
            if(PhysicsVerifier.validate(s,route)){out.add(route);kept[6]++;}
        }
        // Positional play: path-opening contacts are always offered; the comparator places them
        // below tolerant pots and above risky ones, so a blocked board still gets a plan.
        boolean forgiving=false;for(ShotPlanner.Shot route:out)if(route.kind<4&&tier(route)<=1)forgiving=true;
        // No reasonable geometric pot: brute-force aim sweep over the whole arc before any chain-reaction gamble
        // (manual-play review 2026-11-07: the user potted through an opponent disc where no candidate existed).
        if(!forgiving&&!Thread.currentThread().isInterrupted()){
            for(ShotPlanner.Shot sw:SweepPlanner.candidates(s,selected,false,3)){applyObjectiveScore(sw);out.add(sw);if(tier(sw)<=1)forgiving=true;}
        }
        // (openers are still searched in Pro style or when no reasonable pot exists from here)
        if((proStyle||!forgiving)&&!Thread.currentThread().isInterrupted())out.addAll(OpeningPlanner.candidates(s,selected,false));
        Collections.sort(out,RoutePlanner::compare);
        // Realistic chance for the leading pots (24 seeded samples each), then rank again with it.
        int estimated=0;
        for(ShotPlanner.Shot route:out){
            if(estimated>=6||Thread.currentThread().isInterrupted())break;
            if(route.kind>=4)continue;
            route.chancePercent=PhysicsVerifier.chance(s,route,CHANCE_SAMPLES);markTouching(s,route);estimated++;
        }
        Collections.sort(out,RoutePlanner::compare);
        List<ShotPlanner.Shot> retained=new ArrayList<>();Arrays.fill(kept,0);
        for(ShotPlanner.Shot route:out){int kind=complexity(route);if(kept[kind]++<(kind>=6?2:3))retained.add(route);}
        return retained;
    }
    /** Production ordering shared by live routes, route cycling and the gold search. */
    public static int compare(ShotPlanner.Shot a,ShotPlanner.Shot b){
        if(proStyle){
            boolean potA=a.kind<4&&tier(a)<=1,potB=b.kind<4&&tier(b)<=1;
            if(potA!=potB)return potA?-1:1; // a reasonable real pot always beats a chain-reaction gamble
            // v4.8.4: Pro style ranked by raw model chance plus path/tolerance bonuses, so combinations beat easier
            // single-coin pots (offline replay of 26 live boards: 2 wrong picks -> 0). Ranked chance first.
            if(potA&&!Float.isNaN(a.chancePercent)&&!Float.isNaN(b.chancePercent)){
                int o=Integer.compare((int)(easeRank(b)/10),(int)(easeRank(a)/10));if(o!=0)return o;
            }
            int order=Float.compare(proValue(b),proValue(a));if(order!=0)return order;
        }
        int ta=tier(a),tb=tier(b),order=Integer.compare(ta,tb);if(order!=0)return order;
        if(ta==2){ // openers: most new paths first, then tolerance, then score
            order=Integer.compare(OpeningPlanner.gain(b),OpeningPlanner.gain(a));if(order!=0)return order;
            order=Float.compare(margin(b),margin(a));if(order!=0)return order;
            return Float.compare(b.score,a.score);
        }
        if(ta==4)return Float.compare(b.score,a.score);
        // Safe style is about not missing: a realistically likelier pot wins before any cluster bonus
        // (reality-gap study: productive picks potted less often than plain pots on the same frames).
        if(a.kind<4&&b.kind<4&&!Float.isNaN(a.chancePercent)&&!Float.isNaN(b.chancePercent)){
            order=Integer.compare((int)(easeRank(b)/10),(int)(easeRank(a)/10));if(order!=0)return order;
        }
        boolean pa=robustProductive(a)&&margin(a)>=PRODUCTIVE_MIN_MARGIN,pb=robustProductive(b)&&margin(b)>=PRODUCTIVE_MIN_MARGIN;
        order=Boolean.compare(pb,pa);if(order!=0)return order;
        if(pa){
            order=Float.compare(bucket(b),bucket(a));if(order!=0)return order;
            order=Integer.compare(b.productiveStressPassed,a.productiveStressPassed);if(order!=0)return order;
            return Float.compare(b.score,a.score);
        }
        // Among real pots prefer the highest realistic chance (10% steps), then the widest effective aim
        // window, then robustness, then the better position left behind (one-ply look-ahead), then simplicity.
        if(!Float.isNaN(a.chancePercent)&&!Float.isNaN(b.chancePercent)){
            order=Integer.compare((int)(b.chancePercent/10),(int)(a.chancePercent/10));if(order!=0)return order;
        }
        order=Float.compare(bucket(b),bucket(a));if(order!=0)return order;
        order=Integer.compare(b.robustnessPassed,a.robustnessPassed);if(order!=0)return order;
        order=Integer.compare(b.pathsAfter,a.pathsAfter);if(order!=0)return order;
        order=Integer.compare(complexity(a),complexity(b));if(order!=0)return order;
        return Float.compare(b.score,a.score);
    }
    /** 4.8.9 (user "still missing easy"; local audit of 120 logged auto boards): a coin-cushion or combination pot
     *  within 10% of a direct pot tied in the 10% chance bands, and path/cluster bonuses then picked the bank shot on
     *  12 boards (twice with the lower chance). A verified direct pot with a usable chance (>= 40% ranked) is ranked
     *  15 points higher. A per-route key (not a pairwise override), so the sort order stays consistent. */
    static final float EASY_FIRST_GAP=15f;
    static float easeRank(ShotPlanner.Shot s){
        float r=rankChance(s);
        return s.kind==0&&r>=40?r+EASY_FIRST_GAP:r;
    }
    /** Pro value: a pot (or opener that pots) is worth 100, each pocket path left for the next shot 15,
     *  broken close pairs and moved discs a little, aim tolerance 10 per degree; unreliable shots sink. */
    static float proValue(ShotPlanner.Shot s){
        if(s.kind==4)return -1000;
        boolean pots=s.kind<4||s.kind==5&&s.ownPotted>0;
        // A pot is worth 100 times its realistic chance when known (an unlikely pot is not a plan).
        float potWeight=!Float.isNaN(s.chancePercent)?s.chancePercent/100f:s.kind==5&&s.robustnessTrials>0?s.robustnessPassed/(float)s.robustnessTrials:1f;
        float value=(pots?100*potWeight:0)+s.pathsAfter*15+Math.min(8,s.otherCoinsMoved)*2+Math.min(6,s.closePairsOpened)*3+Math.min(4,margin(s))*10;
        if(s.robustnessTrials>0&&s.robustnessPassed<5)value-=150;
        return value;
    }
    /** 0 pot tolerating >=1 degree effective, 1 pot tolerating >=0.5, 2 path opener, 3 risky pot, 4 manual cluster guide. */
    /** Ranking tier (0 best) for display stability checks. */
    public static int tierOf(ShotPlanner.Shot s){return tier(s);}
    private static int tier(ShotPlanner.Shot s){
        if(s.kind==4)return 4;if(s.kind==5)return 2;
        // Practice log 2026-11-07 21:29-21:33 (all auto): real pots potted 4/5, chain-reaction openers 0/9.
        // Any verified pot with >= 25% realistic chance therefore ranks above openers.
        if(!Float.isNaN(s.chancePercent)){float c=rankChance(s);return c>=70?0:c>=25?1:3;}
        float b=bucket(s);return b>=1f?0:b>=.5f?1:3;
    }
    /** Chance used for ranking only (the HUD shows the model chance). Live auto play on 2026-11-07 failed most
     *  combination and rail-combination shots that the model rated 53-78%, while direct pots succeeded: each extra
     *  disc in the chain costs 15% and a cushion 8%, so a simple pot wins when the chances are close. */
    /** Flags a route whose first struck disc or target touches another disc. */
    static void markTouching(GameState s,ShotPlanner.Shot r){
        r.touchingPair=false;if(s==null||r==null)return;
        List<Coin> keys=new ArrayList<>(r.chain);if(r.coin!=null&&!keys.contains(r.coin))keys.add(r.coin);
        for(Coin k:keys)for(Coin o:s.coins){
            if(o==k||r.chain.contains(o))continue;
            if(Math.hypot(o.pos.x-k.pos.x,o.pos.y-k.pos.y)<(o.radius+k.radius)*1.08f){r.touchingPair=true;return;}
        }
    }
    public static float rankChance(ShotPlanner.Shot s){
        if(Float.isNaN(s.chancePercent))return s.chancePercent;
        // Practice auto play 23:09-23:35 (34 shots that moved their target): coin cushion 7/8, direct 6/10,
        // combination 2/10, striker cushion 1/5, while the model rated those combinations 60-90%.
        float f=(float)Math.pow(.5,Math.max(0,s.chain.size()-1))*(s.kind==1||s.railFirst?.40f:s.wall>=0?.92f:1f);
        if(s.touchingPair)f*=.75f; // 4.9.3: touching pairs scatter unpredictably (practice 09:42:41, "100%" missed)
        // 4.9.4: aim-sweep routes are found by searching the model for any angle that pots, so they exploit the
        // model's own errors: across 461 logged auto shots they potted 19/71 (27%) while rated 62-71%, against 175/284
        // (62-70%) for geometric routes rated the same. Rank them at their real rate.
        if(s.sweep)f*=.40f;
        return s.chancePercent*f;
    }
    private static float margin(ShotPlanner.Shot s){return Float.isNaN(s.aimMarginDegrees)?0:s.aimMarginDegrees;}
    /** Aim tolerance weighted by how much of the route depends on the uncalibrated rail model, in half-degree steps. */
    public static float effectiveMargin(ShotPlanner.Shot s){
        float weight=s.kind==0?1f:s.kind==2?.75f:s.kind==1?(s.chain.size()>1?.4f:.6f):s.kind==3?.5f:0f;
        return Math.min(4f,margin(s))*weight;
    }
    private static float bucket(ShotPlanner.Shot s){return (float)Math.floor(effectiveMargin(s)*2f)/2f;}
    /** Player-facing tolerance wording. */
    public static String toleranceLabel(ShotPlanner.Shot s){
        if(s==null||s.kind==4||Float.isNaN(s.aimMarginDegrees))return "";
        if(s.kind==5)return s.aimMarginDegrees>=1f?"OPENER ±"+Math.round(s.aimMarginDegrees)+"°":s.aimMarginDegrees>=.5f?"OPENER ±0.5°":"OPENER tight";
        float m=s.aimMarginDegrees;
        return m>=2f?"EASY ±"+Math.round(m)+"°":m>=1f?"OK ±1°":m>=.5f?"TIGHT ±0.5°":"RISKY <0.5°";
    }
    private static boolean robustProductive(ShotPlanner.Shot shot){return shot.kind!=4&&shot.modelValidated&&shot.productivePot&&shot.productiveStressPassed>=7;}
    private static void applyObjectiveScore(ShotPlanner.Shot shot){
        if(robustProductive(shot))shot.score+=200+Math.min(10,shot.otherCoinsMoved)*2+Math.min(20,shot.closePairsOpened);
    }
    /** Every geometric candidate before physics validation; for desktop benchmarking. */
    public static List<ShotPlanner.Shot> allCandidates(GameState s,Coin selected){return candidates(s,selected);}
    private static List<ShotPlanner.Shot> candidates(GameState s,Coin selected){
        List<ShotPlanner.Shot> out=new ArrayList<>();
        if(s==null||!s.teamKnown||s.striker==null||!s.strikerDetected)return out;
        List<Coin> own=new ArrayList<>();for(Coin c:s.coins)if(c.targetEligible)own.add(c);
        for(Coin target:own){
            if(selected!=null&&target!=selected)continue;
            for(PointF pocket:s.pockets){
                add(out,evaluate(s,Arrays.asList(target),pocket,-1));
                for(int wall=0;wall<4;wall++)add(out,evaluate(s,Arrays.asList(target),pocket,wall));
                for(int wall=4;wall<8;wall++)add(out,evaluate(s,Arrays.asList(target),pocket,wall));
                for(Coin first:s.coins){
                    if(!first.transferEligible)continue;
                    if(first==target)continue;
                    add(out,evaluate(s,Arrays.asList(first,target),pocket,-1));
                    for(int wall=0;wall<4;wall++)add(out,evaluate(s,Arrays.asList(first,target),pocket,wall));
                    for(Coin second:s.coins){
                        if(!second.transferEligible)continue;
                        if(second==first||second==target)continue;
                        add(out,evaluate(s,Arrays.asList(first,second,target),pocket,-1));
                    }
                }
            }
        }
        Collections.sort(out,(a,b)->{int order=Integer.compare(complexity(a),complexity(b));return order!=0?order:Float.compare(b.score,a.score);});
        return out;
    }
    private static void add(List<ShotPlanner.Shot> out,ShotPlanner.Shot shot){if(shot!=null)out.add(shot);}
    private static int complexity(ShotPlanner.Shot s){return s.kind==5?7:s.kind==4?6:s.kind==0?0:s.kind==1?(s.chain.size()>1?5:1):s.kind==3?2:s.chain.size()+1;}
    public static ShotPlanner.Shot best(GameState s){List<ShotPlanner.Shot> all=routes(s,null);return all.isEmpty()?null:all.get(0);}
    public static ShotPlanner.Shot refresh(GameState s,ShotPlanner.Shot previous){
        // A swept aim is only valid for the board it was found on; anything else re-plans.
        if(previous.sweep)return previous.worldSignature==PhysicsVerifier.signature(s)?previous:null;
        List<Coin> chain=new ArrayList<>();
        for(int index=0;index<previous.chain.size();index++){
            Coin old=previous.chain.get(index);boolean finalCoin=index==previous.chain.size()-1;
            Coin found=null;float d=old.radius*.75f;
            for(Coin c:s.coins){float delta=distance(c.pos,old.pos);if((finalCoin&&previous.kind!=5?c.targetEligible:c.transferEligible)&&delta<d){d=delta;found=c;}}
            if(found==null||chain.contains(found))return null;chain.add(found);
        }
        if(chain.isEmpty())return null;
        PointF pocket=null;float pd=s.board.width()*.06f;
        for(PointF p:s.pockets){float d=distance(p,previous.pocket);if(d<pd){pd=d;pocket=p;}}
        if(pocket==null)return null;
        if(previous.kind==5)return OpeningPlanner.refresh(s,chain.get(0),previous);
        ShotPlanner.Shot refreshed=previous.kind==4?ClusterPlanner.forCoin(s,chain.get(0)):evaluate(s,chain,pocket,previous.wall);
        if(refreshed==null)return null;
        if(previous.modelValidated&&previous.worldSignature==PhysicsVerifier.signature(s)){
            refreshed.modelValidated=true;refreshed.assumedLaunchSpeed=previous.assumedLaunchSpeed;refreshed.worldSignature=previous.worldSignature;
            refreshed.robustnessPassed=previous.robustnessPassed;refreshed.robustnessTrials=previous.robustnessTrials;
            refreshed.clusterMoved=previous.clusterMoved;
            refreshed.otherCoinsMoved=previous.otherCoinsMoved;refreshed.closePairsOpened=previous.closePairsOpened;
            refreshed.productivePot=previous.productivePot;refreshed.productiveStressPassed=previous.productiveStressPassed;
            refreshed.aimMarginDegrees=previous.aimMarginDegrees;
            refreshed.pathsBefore=previous.pathsBefore;refreshed.pathsAfter=previous.pathsAfter;
            refreshed.chancePercent=previous.chancePercent;refreshed.minPotSpeed=previous.minPotSpeed;
            applyObjectiveScore(refreshed);
            return refreshed;
        }
        if(!PhysicsVerifier.validate(s,refreshed))return null;applyObjectiveScore(refreshed);
        refreshed.chancePercent=PhysicsVerifier.chance(s,refreshed,CHANCE_SAMPLES); // keep the HUD on realistic chance
        markTouching(s,refreshed);
        return refreshed;
    }
    /** Best baseline x. {@code score[0]} receives the chosen route's quality (score plus tolerance bonus). */
    public static float goldX(GameState s,Coin target,float[] score){return goldX(s,target,score,null);}
    /** As above; {@code bestShot[0]} receives the validated route at the chosen spot (its first point is that spot). */
    /** Parallel ★ search: the baseline samples and the validations run on several cores (timing matters in a live turn). */
    private static final int GOLD_THREADS=Math.max(1,Math.min(4,Runtime.getRuntime().availableProcessors()-1));
    private static final java.util.concurrent.ExecutorService GOLD_POOL=java.util.concurrent.Executors.newFixedThreadPool(GOLD_THREADS,r->{Thread th=new Thread(r,"BitAim-GoldPool");th.setDaemon(true);return th;});
    public static float goldX(GameState s,Coin target,float[] score,ShotPlanner.Shot[] bestShot){
        float bestX=Float.NaN,bestQuality=-1e9f;ShotPlanner.Shot best=null;
        List<java.util.concurrent.Future<List<GoldCandidate>>> sampled=new ArrayList<>();
        List<GoldCandidate> options=new ArrayList<>();
        List<java.util.concurrent.Future<Boolean>> checks=new ArrayList<>();
        try{
            // 21 baseline samples, each on its own copy of the board (the copy carries that striker x).
            final float[] reach=reachable(s);
            final int steps=Math.max(4,GOLD_STEPS),sweepEvery=Math.max(1,GOLD_SWEEP_EVERY);
            for(int i=0;i<=steps;i++){
                final int idx=i;final float x=s.board.left+s.board.width()*(REACH_MIN+(1-2*REACH_MIN)*i/(float)steps);
                if(x<reach[0]||x>reach[1])continue; // the striker cannot get there
                final Coin tgt=target;
                sampled.add(GOLD_POOL.submit(()->{
                    GameState c=ShotLogger.copy(s);c.striker.pos.x=x;
                    Coin local=tgt==null?null:match(c,tgt);
                    if(tgt!=null&&local==null)return new ArrayList<GoldCandidate>();
                    List<ShotPlanner.Shot> all=candidates(c,local);
                    // 4.8.8: the aim sweep joins every other spot (not only empty ones): it finds easy direct pots the
                    // ghost-ball builder misses, and the ★ search preferred a weaker cushion pot without it (06:41:44).
                    if(idx%sweepEvery==0){List<ShotPlanner.Shot> sw=SweepPlanner.candidates(c,local,true,2);if(!sw.isEmpty()){all=new ArrayList<>(all);all.addAll(sw);Collections.sort(all,(p,q)->Float.compare(q.score,p.score));}}
                    if(all.isEmpty())all=ClusterPlanner.candidates(c,local);
                    if(all.isEmpty()&&idx%sweepEvery==0)all=OpeningPlanner.candidates(c,local,true); // blocked board: still suggest where an opener exists
                    List<GoldCandidate> out=new ArrayList<>();
                    for(int k=0;k<Math.min(4,all.size());k++)out.add(new GoldCandidate(x,all.get(k),c));
                    return out;
                }));
            }
            for(java.util.concurrent.Future<List<GoldCandidate>> f:sampled)options.addAll(f.get());
            Collections.sort(options,(a,b)->Float.compare(b.shot.score,a.shot.score));
            // The bench showed the top-scoring spot can tolerate 0.25 degrees while another tolerates 8:
            // validate a wider set in parallel, then rank validated spots by the production comparator.
            List<GoldCandidate> top=options.subList(0,Math.min(Math.max(4,GOLD_VALIDATE),options.size()));
            // Practice 23:21-23:25: ★ shots with 0.0° tolerance were fired because validated ★ candidates had no chance
            // estimate; each validated candidate now gets the realistic chance (in parallel) before ranking.
            for(GoldCandidate c:top)checks.add(GOLD_POOL.submit(()->{
                if(!PhysicsVerifier.validate(c.state,c.shot))return false;
                if(c.shot.kind<4&&Float.isNaN(c.shot.chancePercent))c.shot.chancePercent=PhysicsVerifier.chance(c.state,c.shot,CHANCE_SAMPLES);
                markTouching(c.state,c.shot);
                return true;
            }));
            for(int i=0;i<top.size();i++){
                GoldCandidate c=top.get(i);
                if(!checks.get(i).get())continue;
                applyObjectiveScore(c.shot);
                if(best==null||compare(c.shot,best)<0){best=c.shot;bestX=c.x;bestQuality=c.shot.quality();}
            }
        }catch(InterruptedException e){
            for(java.util.concurrent.Future<?> f:sampled)f.cancel(true);
            for(java.util.concurrent.Future<?> f:checks)f.cancel(true);
            Thread.currentThread().interrupt();
        }catch(java.util.concurrent.ExecutionException e){
            android.util.Log.w("BitAim/Plan","Gold search task failed: "+e.getCause());
        }
        if(score!=null&&score.length>0)score[0]=bestQuality;
        if(bestShot!=null&&bestShot.length>0)bestShot[0]=best;
        return bestX;
    }
    private static Coin match(GameState c,Coin target){
        for(Coin o:c.coins)if(Math.hypot(o.pos.x-target.pos.x,o.pos.y-target.pos.y)<1f)return o;
        return null;
    }
    private static class GoldCandidate {float x;ShotPlanner.Shot shot;GameState state;GoldCandidate(float p,ShotPlanner.Shot s,GameState st){x=p;shot=s;state=st;}}
    public static String label(ShotPlanner.Shot shot){
        if(shot==null)return "No clear route";
        if(shot.railFirst)return "Off the wall first (model)";
        if(shot.kind==4)return "Open cluster — manual shot";
        if(shot.kind==5&&shot.safeBreakOnly)return "Safe break — spread the cluster";
        if(shot.kind==5){int opened=Math.max(0,shot.pathsAfter-shot.pathsBefore);return opened==0?"Pot via cascade (model)":(shot.ownPotted>0?"Pot + open paths +":"Open paths +")+opened+" (model)";}
        if(shot.modelValidated&&shot.productivePot)return shot.kind==1?"Rail pot + break cluster (model)":"Pot + open cluster (model)";
        if(shot.kind==1)return shot.chain.size()>1?"Cushion + combination":"Cushion route";
        if(shot.kind==3)return "Coin cushion route";
        if(shot.kind==2)return (shot.chain.size()-1)+"-coin combination";
        return "Direct route";
    }
    private static ShotPlanner.Shot evaluate(GameState s,List<Coin> chain,PointF pocket,int wall){
        int n=chain.size();
        if(!chain.get(n-1).targetEligible)return null;
        for(int i=0;i<n-1;i++)if(!chain.get(i).transferEligible)return null;
        PointF[] directions=new PointF[n],ends=new PointF[n];
        ends[n-1]=pocket;
        RectF rails=rails(s);PointF targetBounce=null;
        if(wall>=4){
            if(n!=1)return null;
            Coin target=chain.get(0);float r=target.radius;
            RectF coinCentre=new RectF(rails.left+r,rails.top+r,rails.right-r,rails.bottom-r);
            // 4.9.9 (live 11:26:46, "81%" miss): a disc touching a rail cannot be banked off that same rail; it was
            // planned to bounce off the bottom rail it rested on, all the way to the far corner.
            int side=wall-4;float gap=side==0?target.pos.x-coinCentre.left:side==1?coinCentre.right-target.pos.x:side==2?target.pos.y-coinCentre.top:coinCentre.bottom-target.pos.y;
            if(gap<r*.6f)return null;
            targetBounce=bounce(target.pos,pocket,coinCentre,wall-4);
            if(targetBounce==null)return null;ends[n-1]=targetBounce;
        }
        for(int i=n-1;i>=0;i--){
            Coin c=chain.get(i);
            if(i<n-1){Coin next=chain.get(i+1);PointF d=directions[i+1];ends[i]=new PointF(next.pos.x-d.x*(c.radius+next.radius),next.pos.y-d.y*(c.radius+next.radius));}
            directions[i]=unit(ends[i].x-c.pos.x,ends[i].y-c.pos.y);
            if(directions[i]==null)return null;
        }
        Coin first=chain.get(0),target=chain.get(n-1);float sr=s.striker.radius;
        PointF ghost=new PointF(first.pos.x-directions[0].x*(sr+first.radius),first.pos.y-directions[0].y*(sr+first.radius));
        RectF centre=new RectF(rails.left+sr,rails.top+sr,rails.right-sr,rails.bottom-sr);
        if(!centre.contains(ghost.x,ghost.y)||!centre.contains(s.striker.pos.x,s.striker.pos.y))return null;
        List<PointF> striker=new ArrayList<>();striker.add(new PointF(s.striker.pos.x,s.striker.pos.y));
        if(wall>=0&&wall<4){
            PointF bounce=strikerBounce(s.striker.pos,ghost,centre,wall);if(bounce==null)return null;
            if(!clear(s,s.striker.pos,bounce,sr,null,null))return null;
            striker.add(bounce);
        }
        PointF last=striker.get(striker.size()-1);
        PointF incoming=unit(ghost.x-last.x,ghost.y-last.y);if(incoming==null||distance(last,ghost)<sr)return null;
        float cosine=dot(incoming,directions[0]);if(cosine<MIN_COS)return null;
        float retained=cosine;
        if(!clear(s,last,ghost,sr,first,null))return null;
        striker.add(ghost);
        for(int i=0;i<n;i++){
            Coin c=chain.get(i),next=i<n-1?chain.get(i+1):null;
            if(i<n-1){float cut=dot(directions[i],directions[i+1]);if(cut<MIN_COS)return null;retained*=cut;}
            if(!clear(s,c.pos,ends[i],c.radius,c,next))return null;
            if(!insideUntilPocket(c.pos,ends[i],c.radius,rails,pocket,s.board.width()*.05f,i==n-1&&targetBounce==null))return null;
        }
        if(targetBounce!=null){
            if(!clear(s,targetBounce,pocket,target.radius,target,null)
                    ||!insideUntilPocket(targetBounce,pocket,target.radius,rails,pocket,s.board.width()*.05f,true))return null;
        }
        if(retained<MIN_COS)return null;
        // Avoid recommending a striker scratch on the approach to the first coin.
        for(int i=0;i<striker.size()-1;i++)for(PointF hole:s.pockets)
            if(segmentDistance(hole,striker.get(i),striker.get(i+1))<s.board.width()*.045f)return null;
        // 4.10.8 (live 16:51:58): a direct shot backwards at a coin behind the striker line, into a bottom pocket,
        // showed a green 91% route and fouled. Today's logs had 9 such shots; 7 lost the coin without the disc count
        // dropping (coin returned = foul), while backward coin-cushion shots scored. The model cannot see why, so
        // these routes are not offered: no striker cushion, no coin cushion, contact behind the baseline.
        if(wall<0&&targetBounce==null&&pocket.y>s.striker.pos.y&&ghost.y>s.striker.pos.y+sr*.25f)return null;
        float length=0;for(int i=0;i<striker.size()-1;i++)length+=distance(striker.get(i),striker.get(i+1));
        for(int i=0;i<n;i++)length+=distance(chain.get(i).pos,ends[i]);
        if(targetBounce!=null)length+=distance(targetBounce,pocket);
        ShotPlanner.Shot shot=new ShotPlanner.Shot();shot.coin=target;shot.pocket=pocket;shot.ghost=ghost;
        shot.kind=wall>=4?3:wall>=0?1:n>1?2:0;shot.targetBounce=targetBounce;shot.wall=wall;shot.chain.addAll(chain);shot.strikerPath.addAll(striker);shot.coinEndpoints.addAll(Arrays.asList(ends));
        shot.score=retained*retained*100-length/s.board.width()*12-(wall>=0?18:0)-(n-1)*18;
        return shot;
    }
    private static boolean insideUntilPocket(PointF a,PointF b,float r,RectF rails,PointF pocket,float pocketR,boolean finalLeg){
        for(int i=0;i<=24;i++){
            float t=i/24f,x=a.x+(b.x-a.x)*t,y=a.y+(b.y-a.y)*t;
            if(finalLeg&&Math.hypot(x-pocket.x,y-pocket.y)<pocketR)continue;
            if(x-r<rails.left-.05f||x+r>rails.right+.05f||y-r<rails.top-.05f||y+r>rails.bottom+.05f)return false;
        }return true;
    }
    private static boolean clear(GameState s,PointF a,PointF b,float radius,Coin skip,Coin next){
        for(Coin c:s.coins)if(c!=skip&&c!=next&&segmentDistance(c.pos,a,b)<radius+c.radius+s.board.width()*.003f)return false;
        return true;
    }
    /** Striker rail point that sends it to {@code g} with the model's striker rebound: normal ×0.80 and along-rail
     *  ×STRIKER_RAIL_TANGENT (no closed-form mirror point, so a secant search on the rail coordinate). */
    private static PointF strikerBounce(PointF s,PointF g,RectF b,int wall){
        PointF p0=bounce(s,g,b,wall);if(p0==null)return null;
        final double e=.80,k=PhysicsVerifier.STRIKER_RAIL_TANGENT;final boolean vertical=wall<2;
        final double plane=wall==0?b.left:wall==1?b.right:wall==2?b.top:b.bottom;
        double u0=vertical?p0.y:p0.x,u1=u0+3,f0=bounceMiss(s,g,vertical,plane,e,k,u0),f1=bounceMiss(s,g,vertical,plane,e,k,u1);
        for(int i=0;i<20&&Math.abs(f1)>.2;i++){
            if(Math.abs(f1-f0)<1e-9)break;
            double u2=u1-f1*(u1-u0)/(f1-f0);u0=u1;f0=f1;u1=u2;f1=bounceMiss(s,g,vertical,plane,e,k,u1);
        }
        if(Math.abs(f1)>1)return null;
        PointF p=vertical?new PointF((float)plane,(float)u1):new PointF((float)u1,(float)plane);
        if(p.x<b.left-.01f||p.x>b.right+.01f||p.y<b.top-.01f||p.y>b.bottom+.01f)return null;
        if(Math.min(p.x-b.left,b.right-p.x)<15&&Math.min(p.y-b.top,b.bottom-p.y)<15)return null;
        // the outgoing leg must head toward g, not away
        double vx=p.x-s.x,vy=p.y-s.y,ox=vertical?-e*vx:k*vx,oy=vertical?k*vy:-e*vy;
        if(ox*(g.x-p.x)+oy*(g.y-p.y)<=0)return null;
        return p;
    }
    /** Signed distance of g from the striker's outgoing line after bouncing at rail coordinate u. */
    private static double bounceMiss(PointF s,PointF g,boolean vertical,double plane,double e,double k,double u){
        double px=vertical?plane:u,py=vertical?u:plane,vx=px-s.x,vy=py-s.y;
        double ox=vertical?-e*vx:k*vx,oy=vertical?k*vy:-e*vy,gx=g.x-px,gy=g.y-py;
        return (ox*gy-oy*gx)/Math.max(1e-6,Math.hypot(ox,oy));
    }
    private static PointF bounce(PointF s,PointF g,RectF b,int wall){
        float rx=g.x,ry=g.y,plane;
        if(wall==0){plane=b.left;rx=plane-(g.x-plane)/.80f;}
        else if(wall==1){plane=b.right;rx=plane-(g.x-plane)/.80f;}
        else if(wall==2){plane=b.top;ry=plane-(g.y-plane)/.80f;}
        else{plane=b.bottom;ry=plane-(g.y-plane)/.80f;}
        float divisor=wall<2?rx-s.x:ry-s.y;if(Math.abs(divisor)<.001f)return null;
        float t=(plane-(wall<2?s.x:s.y))/divisor;if(t<=0||t>=1)return null;
        PointF p=new PointF(s.x+(rx-s.x)*t,s.y+(ry-s.y)*t);
        if(p.x<b.left-.01f||p.x>b.right+.01f||p.y<b.top-.01f||p.y>b.bottom+.01f)return null;
        // Corner rebounds are too sensitive to pocket and rail estimates.
        if(Math.min(p.x-b.left,b.right-p.x)<15&&Math.min(p.y-b.top,b.bottom-p.y)<15)return null;
        return p;
    }
    private static RectF rails(GameState s){
        float l=Float.MAX_VALUE,t=l,r=-l,b=-l;
        for(PointF p:s.pockets){l=Math.min(l,p.x);r=Math.max(r,p.x);t=Math.min(t,p.y);b=Math.max(b,p.y);}
        float pr=s.board.width()*.05f;return new RectF(l-pr,t-pr,r+pr,b+pr);
    }
    private static PointF unit(float x,float y){float n=(float)Math.hypot(x,y);return n<1?null:new PointF(x/n,y/n);}
    private static float dot(PointF a,PointF b){return a.x*b.x+a.y*b.y;}
    private static float distance(PointF a,PointF b){return (float)Math.hypot(a.x-b.x,a.y-b.y);}
    private static float segmentDistance(PointF p,PointF a,PointF b){float x=b.x-a.x,y=b.y-a.y,d=x*x+y*y;float t=d==0?0:Math.max(0,Math.min(1,((p.x-a.x)*x+(p.y-a.y)*y)/d));return (float)Math.hypot(p.x-a.x-t*x,p.y-a.y-t*y);}
}
