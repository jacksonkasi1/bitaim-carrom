package com.bitaim.carromaim.cv;

/**
 * 4.13.0 (user: "while I pull by hand, show the speed: green when it matches the physics, orange / red when not").
 * While the player pulls, the game draws a red power arrow in front of the striker; its length is measured on every
 * frame ({@link AimGuideDetector#powerArrow}) and turned into a pull length, which is compared with the pull the
 * shown route was verified at (same conversion auto play uses: launch speed = LAUNCH_PER_PULL x pull).
 * Green: within 8% of the planned power (the robustness test's power range); orange: within 18%; red beyond.
 */
public final class PowerMeter {
    private PowerMeter(){}
    /** Pull (board widths) = (arrow length - ARROW_OFFSET) / ARROW_PER_PULL; arrow length in board widths.
     *  403 logged pulls: the arrow tops out at ~0.245 board widths where the pull reaches full power (0.25), and the
     *  replayed launch speeds give ~7.3 x arrow against auto play's 8.5 x pull: arrow length ~= pull. Weakly
     *  supported (per-shot spread +-0.8 board widths/s); the new "arrow" field in shot records refines it. */
    public static volatile float ARROW_PER_PULL=1.0f, ARROW_OFFSET=0f;
    /** Pull at the game's full power, board widths (percentages are of this). */
    public static final float FULL_PULL=.25f;
    /** The arrow stops growing here: any pull at or above it is full power. */
    public static final float ARROW_FULL=.245f;
    public static final float GREEN=.08f, ORANGE=.18f;
    public static final int ZONE_GREEN=0, ZONE_ORANGE=1, ZONE_RED=2;

    public static float pullFromArrow(float arrow){
        if(Float.isNaN(arrow))return Float.NaN;
        if(arrow>=ARROW_FULL)return FULL_PULL;
        return Math.max(0f,Math.min(FULL_PULL,(arrow-ARROW_OFFSET)/ARROW_PER_PULL));
    }
    /** Pull the route was verified at (board widths), or NaN for shots without a power promise (cluster breaks). */
    public static float targetPull(ShotPlanner.Shot r,float boardWidth){
        if(r==null||r.kind==4||boardWidth<=0)return Float.NaN;
        if(r.assumedLaunchSpeed>0)return r.assumedLaunchSpeed/(AutoPlayController.LAUNCH_PER_PULL*boardWidth);
        return AutoPlayController.pullFor(r,boardWidth);
    }
    /** 4.13.1 (live 23:00:23, user: "it said small power for the wall shot, I followed and missed"): the target was the
     *  softest speed that pots in the model (chosen so the striker does not follow the coin in), i.e. the weakest edge;
     *  that wall shot asked for 68% and potted two coins at 96%. The meter now shows the whole range of speeds that pot
     *  (wall shots also with a lossier rail, 0.74, the robustness test's low rail) and aims at its middle. Computed on
     *  the planner thread for the shown route; about 15 model shots. */
    public static void computeWindow(GameState s,ShotPlanner.Shot r){
        if(s==null||r==null||r.kind>=4||r.strikerPath.size()<2||r.chain.isEmpty()||r.pocket==null||!Float.isNaN(r.potSpeedMin))return;
        float w=s.board.width();
        boolean wall=r.wall>=0||r.targetBounce!=null||r.railFirst;
        float bestLo=Float.NaN,bestHi=Float.NaN,lo=Float.NaN;boolean containsChosen=false,bestContains=false;
        float chosen=r.assumedLaunchSpeed>0?r.assumedLaunchSpeed/w:Float.NaN;
        for(float v=1.2f;v<=2.61f;v+=.1f){
            if(Thread.currentThread().isInterrupted())return;
            float speed=v*w;
            boolean ok=PhysicsVerifier.simulate(s,r,speed)&&(!wall||PhysicsVerifier.simulate(s,r,speed,0,.74))&&PhysicsVerifier.followSafe(s,r,speed);
            if(ok){if(Float.isNaN(lo)){lo=v;containsChosen=false;}if(!Float.isNaN(chosen)&&Math.abs(v-chosen)<.06f)containsChosen=true;}
            boolean end=!ok||v+.1f>2.61f;
            if(end&&!Float.isNaN(lo)){
                float hi=ok?v:v-.1f;
                boolean better=Float.isNaN(bestLo)||containsChosen&&!bestContains||containsChosen==bestContains&&hi-lo>bestHi-bestLo;
                if(better){bestLo=lo;bestHi=hi;bestContains=containsChosen;}
                lo=Float.NaN;
            }
        }
        if(Float.isNaN(bestLo)){r.potSpeedMin=r.potSpeedMax=-1;return;} // computed, no window (keep the single target)
        // 4.13.2 (live 23:10:58): a combination pulled at ~90% stopped on the pocket lip; each transfer loses more than
        // the model says, so the soft end of a chain's window starts 10% higher.
        if(r.chain.size()>1)bestLo=Math.min(bestHi,bestLo*1.1f);
        r.potSpeedMin=bestLo*w;r.potSpeedMax=bestHi*w;
    }
    /** Pull range [low, high] (board widths) of the green zone, or null when the route has no speed window. */
    public static float[] window(ShotPlanner.Shot r,float boardWidth){
        if(r==null||Float.isNaN(r.potSpeedMin)||r.potSpeedMin<=0||boardWidth<=0)return null;
        float k=AutoPlayController.LAUNCH_PER_PULL*boardWidth;
        return new float[]{r.potSpeedMin/k,r.potSpeedMax/k};
    }
    /** Zone against a speed window: green inside it (a 3% margin from its edges), orange up to 12% outside. */
    public static int zone(float pull,float[] win){
        if(Float.isNaN(pull)||win==null)return ZONE_RED;
        float lo=Math.min(win[0],FULL_PULL),hi=Math.min(win[1],FULL_PULL);
        if(win[1]>=FULL_PULL*.98f&&pull>=FULL_PULL*.98f&&pull>=lo)return ZONE_GREEN;
        if(hi-lo<.012f)return zone(pull,(lo+hi)/2); // a one-step window: the usual +-8% around it
        float span=hi-lo;
        if(pull>=lo+span*.03f&&pull<=hi-span*.03f)return ZONE_GREEN;
        float out=pull<lo?(lo-pull)/lo:(pull-hi)/hi;
        return out<=.12f?ZONE_ORANGE:ZONE_RED;
    }
    public static int zone(float pull,float target){
        if(Float.isNaN(pull)||Float.isNaN(target)||target<=0)return ZONE_RED;
        // the game cannot go past full power: a route verified at or above it is matched by any full pull
        if(target>=FULL_PULL*.98f&&pull>=FULL_PULL*.98f)return ZONE_GREEN;
        float e=Math.abs(pull-Math.min(target,FULL_PULL))/Math.min(target,FULL_PULL);
        return e<=GREEN?ZONE_GREEN:e<=ORANGE?ZONE_ORANGE:ZONE_RED;
    }
    public static String advice(float pull,float[] win){
        int z=zone(pull,win);
        if(z==ZONE_GREEN)return "HOLD — release now";
        float mid=(Math.min(win[0],FULL_PULL)+Math.min(win[1],FULL_PULL))/2;
        return pull<mid?(z==ZONE_ORANGE?"a bit more":"pull MORE"):(z==ZONE_ORANGE?"a bit less":"pull LESS");
    }
    public static float percent(float pull){return pull/FULL_PULL*100f;}
    /** "more" / "less" / "hold" hint for the player. */
    public static String advice(float pull,float target){
        int z=zone(pull,target);
        if(z==ZONE_GREEN)return target>=FULL_PULL*.98f?"FULL power — release":"HOLD — release now";
        return pull<target?(z==ZONE_ORANGE?"a bit more":"pull MORE"):(z==ZONE_ORANGE?"a bit less":"pull LESS");
    }
}
