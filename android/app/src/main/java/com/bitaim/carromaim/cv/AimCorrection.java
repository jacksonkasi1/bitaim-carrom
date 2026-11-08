package com.bitaim.carromaim.cv;

/**
 * Closed-loop aim for auto play. Recorder evidence (28 auto shots, 2026-11-07): the game's aim landed a mean
 * -0.83 degrees (SD 1.14) from the dragged direction even though the gesture vector matched exactly, which uses up
 * a +/-1 degree pot tolerance. Auto play therefore holds the pull, reads the game's aim dots, rotates the pull by
 * the measured error and releases only when the error is small. Pure math here so the desktop harness can test it.
 */
public final class AimCorrection {
    private AimCorrection(){}
    /** Release once the game's aim is within this many degrees of the plan. */
    public static final float DONE_DEG=.3f;
    /** Larger readings are treated as a misread guide (pull-back rows, outliers) and never corrected. */
    public static final float MAX_DEG=6f;
    // v4.7.8: three steps (live 23:53:39: two steps left 0.67° on a ±0.5° pot, which rimmed out).
    public static final int MAX_STEPS=3,MIN_DOTS=4;
    /** Next-step gain from the last step's response: a step that removed only part of the error is scaled up
     *  (128 logged corrections removed 85% of the error on average), never more than doubled. */
    public static float nextGain(float previousError,float error,float appliedGain){
        if(Float.isNaN(previousError)||Float.isNaN(error)||Math.abs(previousError)<.05f)return 1f;
        float response=(previousError-error)/(previousError*appliedGain); // fraction of the applied rotation that showed up
        if(response<.3f||response>1.2f)return 1f; // noisy reading or overshoot: plain step
        return Math.max(1f,Math.min(2f,1f/response));
    }
    /** Signed angle in degrees from the intended launch (ux,uy) to the game's aim (gx,gy); NaN when unusable. */
    public static float errorDegrees(float ux,float uy,float gx,float gy){
        if(Float.isNaN(ux)||Float.isNaN(uy)||Float.isNaN(gx)||Float.isNaN(gy))return Float.NaN;
        double cross=ux*gy-uy*gx,dot=ux*gx+uy*gy;
        if(Math.hypot(cross,dot)<1e-6)return Float.NaN;
        return (float)Math.toDegrees(Math.atan2(cross,dot));
    }
    /** True when another correction step should be made for this reading. */
    public static boolean shouldCorrect(float errorDeg,int dots,int stepsDone){
        return !Float.isNaN(errorDeg)&&dots>=MIN_DOTS&&stepsDone<MAX_STEPS&&Math.abs(errorDeg)>=DONE_DEG&&Math.abs(errorDeg)<=MAX_DEG;
    }
    /** Pull end point (x2,y2) rotated by -errorDeg around the striker (x1,y1); the pull length is kept. */
    public static float[] rotatePull(float x1,float y1,float x2,float y2,float errorDeg){
        double a=Math.toRadians(-errorDeg),dx=x2-x1,dy=y2-y1,c=Math.cos(a),s=Math.sin(a);
        return new float[]{(float)(x1+dx*c-dy*s),(float)(y1+dx*s+dy*c)};
    }
}
