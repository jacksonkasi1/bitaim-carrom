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
    public static int zone(float pull,float target){
        if(Float.isNaN(pull)||Float.isNaN(target)||target<=0)return ZONE_RED;
        // the game cannot go past full power: a route verified at or above it is matched by any full pull
        if(target>=FULL_PULL*.98f&&pull>=FULL_PULL*.98f)return ZONE_GREEN;
        float e=Math.abs(pull-Math.min(target,FULL_PULL))/Math.min(target,FULL_PULL);
        return e<=GREEN?ZONE_GREEN:e<=ORANGE?ZONE_ORANGE:ZONE_RED;
    }
    public static float percent(float pull){return pull/FULL_PULL*100f;}
    /** "more" / "less" / "hold" hint for the player. */
    public static String advice(float pull,float target){
        int z=zone(pull,target);
        if(z==ZONE_GREEN)return target>=FULL_PULL*.98f?"FULL power — release":"HOLD — release now";
        return pull<target?(z==ZONE_ORANGE?"a bit more":"pull MORE"):(z==ZONE_ORANGE?"a bit less":"pull LESS");
    }
}
