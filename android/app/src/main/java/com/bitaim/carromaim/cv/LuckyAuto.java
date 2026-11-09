package com.bitaim.carromaim.cv;

import android.graphics.PointF;

import java.util.Locale;

/**
 * 4.11.0: auto play for the Lucky Shot mini-game. Shoots the planned shot from where the striker is, once the board
 * has been still for a moment: exact pull length for the planned power, launch direction corrected by the game's own
 * aim dots while the pull is held (same closed loop as carrom auto play). After each shot it watches the gold puck
 * until it stops and logs planned vs real resting point, so the power model can be calibrated from real attempts.
 */
public final class LuckyAuto {
    static final long SETTLE_MS=700,WATCH_MS=6000,REST_MS=600;
    private long stillSince=-1,shotAt=-1,puckStillSince=-1;
    private float lastX=Float.NaN,lastPuckX=Float.NaN,lastPuckY=Float.NaN;
    private boolean busy;
    private LuckyShot.Plan shotPlan;private LuckyShot.Scene shotScene;
    private String status="",note;

    public String status(){return status;}
    /** One event-log line (shot sent / result seen), or null. */
    public String consumeNote(){String n=note;note=null;return n;}
    public void reset(){stillSince=shotAt=puckStillSince=-1;lastX=lastPuckX=lastPuckY=Float.NaN;busy=false;shotPlan=null;shotScene=null;status="";}

    public AutoPlayController.Action update(LuckyShot.Scene sc,LuckyShot.Plan p,boolean planning,long now){
        if(busy){status="shooting";return null;}
        if(shotAt>=0){
            // Watch the puck after the shot until it rests (or the game shows the next attempt).
            if(sc!=null){
                boolean still=Math.hypot(sc.puck.x-lastPuckX,sc.puck.y-lastPuckY)<sc.puckR*.1f;
                if(!still){puckStillSince=now;lastPuckX=sc.puck.x;lastPuckY=sc.puck.y;}
                boolean moved=Math.hypot(sc.puck.x-shotScene.puck.x,sc.puck.y-shotScene.puck.y)>sc.puckR;
                if(moved&&still&&now-puckStillSince>=REST_MS){logResult(sc.puck,sc);shotAt=-1;}
            }
            if(shotAt>=0&&now-shotAt>WATCH_MS){note="LUCKY RESULT puck rest not seen";shotAt=-1;}
            if(shotAt>=0){status="watching the shot";return null;}
            stillSince=-1;
        }
        if(sc==null){status="";stillSince=-1;return null;}
        if(!sc.strikerFound){status="waiting for the striker";stillSince=-1;return null;}
        // 4.12.2 (live 12:12): never pull from a spot inside the target or off the baseline (a misread striker).
        if(Math.hypot(sc.striker.x-sc.target.x,sc.striker.y-sc.target.y)<sc.ringR||Math.abs(sc.striker.y-(sc.board.top+sc.board.height()*.845f))>sc.board.width()*.06f){
            status="striker not on the baseline, waiting";stillSince=-1;return null;}
        if(Float.isNaN(lastX)||Math.abs(sc.striker.x-lastX)>sc.strikerR*.08f){lastX=sc.striker.x;stillSince=now;}
        if(planning||p==null){status=planning?"finding the best shot":"no shot found";return null;}
        if(Math.abs(p.strikerX-sc.striker.x)>sc.strikerR*.3f){status="re-planning for the striker spot";return null;}
        if(stillSince<0||now-stillSince<SETTLE_MS){status="waiting for the board to settle";return null;}
        float sx=sc.striker.x,sy=sc.striker.y;
        AutoPlayController.Action a=new AutoPlayController.Action(false,sx,sy,sx-p.ux*p.pullPx,sy-p.uy*p.pullPx,AutoPlayController.AIM_BUDGET_MS);
        if(p.strikerPath.size()>=2){PointF aim=p.strikerPath.get(1);a.aimX=aim.x;a.aimY=aim.y;}
        busy=true;shotAt=now;puckStillSince=now;shotPlan=p;shotScene=sc;lastPuckX=sc.puck.x;lastPuckY=sc.puck.y;
        status="shooting: "+p.kind+" • power "+Math.round(p.powerPercent)+"%";
        note=String.format(Locale.US,"LUCKY SHOT striker (%.0f,%.0f) dir (%.4f,%.4f) pull %.4f w (%.0f%%) speed %.3f w/s puck (%.0f,%.0f) target (%.0f,%.0f) ring %.0f planned rest (%.0f,%.0f) %s",
                sx,sy,p.ux,p.uy,p.pullPx/sc.board.width(),p.powerPercent,p.speed/sc.board.width(),sc.puck.x,sc.puck.y,sc.target.x,sc.target.y,sc.ringR,p.rest.x,p.rest.y,p.kind);
        return a;
    }
    /** Gesture finished (or failed): the shot is being watched, or the next frame may try again. */
    public void complete(boolean success){busy=false;if(!success){shotAt=-1;stillSince=-1;}}

    private void logResult(PointF rest,LuckyShot.Scene sc){
        LuckyShot.Plan p=shotPlan;if(p==null)return;
        // Error split along / across the puck's last planned leg: "along" > 0 means too much power.
        PointF a=p.puckPath.get(Math.max(0,p.puckPath.size()-2)),b=p.rest;
        float lx=b.x-a.x,ly=b.y-a.y,l=(float)Math.max(1e-3,Math.hypot(lx,ly));lx/=l;ly/=l;
        float ex=rest.x-b.x,ey=rest.y-b.y;
        float target=(float)Math.hypot(rest.x-shotScene.target.x,rest.y-shotScene.target.y);
        note=String.format(Locale.US,"LUCKY RESULT puck rest (%.0f,%.0f) planned (%.0f,%.0f) along %+.0f across %+.0f px, %.0f px from target (%s)",
                rest.x,rest.y,b.x,b.y,ex*lx+ey*ly,-ex*ly+ey*lx,target,
                target<shotScene.ringR*LuckyShot.YELLOW_FRAC?"centre":target<shotScene.ringR*LuckyShot.RED_FRAC?"red ring":target<shotScene.ringR?"teal ring":"outside");
    }
}
