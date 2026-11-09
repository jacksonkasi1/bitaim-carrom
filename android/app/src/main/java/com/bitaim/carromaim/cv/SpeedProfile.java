package com.bitaim.carromaim.cv;

/**
 * 4.12.0 (user: "I need ultra speed ... give me full control, default as it is"): one speed level for all planning
 * work, plus the frame rate on the player's turn. Every level keeps the same 9-trial robustness test, so green /
 * yellow colours and the auto-play safety gates mean the same at every level; faster levels only search less.
 */
public final class SpeedProfile {
    private SpeedProfile(){}
    public static final int DEEP=0,FAST=1,ULTRA=2,MAX=3;
    public static final int DEFAULT_LEVEL=FAST;
    public static final String[] NAMES={"Deep (slowest, most routes)","Fast (default)","Ultra","Max (fastest)"};
    public static final String[] SHORT={"Deep","Fast","Ultra","Max"};
    /** Frame rates offered on the player's turn; 10 fps is the default. More frames = quicker reaction, more CPU. */
    public static final int[] FPS={5,7,10,15,20,30};
    public static final int DEFAULT_FPS=10;

    private static volatile int level=DEFAULT_LEVEL;
    private static volatile long frameIntervalMs=1000/DEFAULT_FPS;
    /** Quick pass only (no full pass after it), in Pro and Safe style. */
    private static volatile boolean quickOnly=false;
    /** Pro style: quick pass first, then the full pass on the same board. */
    private static volatile boolean twoPass=true;

    public static int level(){return level;}
    public static long frameIntervalMs(){return frameIntervalMs;}
    public static int fps(){return (int)Math.round(1000.0/frameIntervalMs);}
    public static boolean quickOnly(){return quickOnly;}
    public static boolean twoPass(){return twoPass;}

    public static void setFps(int fps){frameIntervalMs=Math.max(20,1000/Math.max(1,Math.min(60,fps)));}

    /** Apply a level to every planner knob (phone values; the PC brain sets its own). */
    public static void setLevel(int l){
        if(l<DEEP||l>MAX)l=DEFAULT_LEVEL;
        level=l;
        switch(l){
            case DEEP:
                quickOnly=false;twoPass=false;
                RoutePlanner.CHANCE_SAMPLES=32;RoutePlanner.GOLD_STEPS=20;RoutePlanner.GOLD_SWEEP_EVERY=4;RoutePlanner.GOLD_VALIDATE=12;
                SweepPlanner.BUDGET_SCALE=1;OpeningPlanner.BUDGET_NANOS=1200_000_000L;
                PhysicsVerifier.SPEED_BEAM=true;PhysicsVerifier.STRESS_CANDIDATES=2;
                RoutePlanner.PER_KIND=8;RoutePlanner.CHANCE_ROUTES=6;RoutePlanner.CLUSTER_ROUTES=true;
                break;
            case ULTRA:
                quickOnly=true;twoPass=false;
                RoutePlanner.CHANCE_SAMPLES=16;RoutePlanner.GOLD_STEPS=14;RoutePlanner.GOLD_SWEEP_EVERY=5;RoutePlanner.GOLD_VALIDATE=8;
                SweepPlanner.BUDGET_SCALE=.6;OpeningPlanner.BUDGET_NANOS=500_000_000L;
                PhysicsVerifier.SPEED_BEAM=true;PhysicsVerifier.STRESS_CANDIDATES=1;
                RoutePlanner.PER_KIND=5;RoutePlanner.CHANCE_ROUTES=3;RoutePlanner.CLUSTER_ROUTES=true;
                break;
            case MAX:
                quickOnly=true;twoPass=false;
                RoutePlanner.CHANCE_SAMPLES=8;RoutePlanner.GOLD_STEPS=10;RoutePlanner.GOLD_SWEEP_EVERY=6;RoutePlanner.GOLD_VALIDATE=5;
                SweepPlanner.BUDGET_SCALE=.4;OpeningPlanner.BUDGET_NANOS=200_000_000L;
                PhysicsVerifier.SPEED_BEAM=false;PhysicsVerifier.STRESS_CANDIDATES=1;
                RoutePlanner.PER_KIND=3;RoutePlanner.CHANCE_ROUTES=2;RoutePlanner.CLUSTER_ROUTES=false;
                break;
            default: // FAST: 4.11.2 behaviour
                quickOnly=false;twoPass=true;
                RoutePlanner.CHANCE_SAMPLES=32;RoutePlanner.GOLD_STEPS=20;RoutePlanner.GOLD_SWEEP_EVERY=4;RoutePlanner.GOLD_VALIDATE=12;
                SweepPlanner.BUDGET_SCALE=1;OpeningPlanner.BUDGET_NANOS=1200_000_000L;
                PhysicsVerifier.SPEED_BEAM=true;PhysicsVerifier.STRESS_CANDIDATES=2;
                RoutePlanner.PER_KIND=8;RoutePlanner.CHANCE_ROUTES=6;RoutePlanner.CLUSTER_ROUTES=true;
        }
    }
}
