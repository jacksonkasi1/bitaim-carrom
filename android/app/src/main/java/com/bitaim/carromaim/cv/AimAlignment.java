package com.bitaim.carromaim.cv;

/** Confirm alignment using separate captures, not redraws or nominal pot probability. */
public final class AimAlignment {
    private long frame=-1;
    private int matching;
    private float ghostX=Float.NaN,ghostY,error=Float.NaN;
    private float pathX,pathY;
    private int kind,wall;
    public void update(GameState s,ShotPlanner.Shot route){
        boolean fresh=s!=null&&s.frameId>frame;
        boolean replay=s!=null&&s.frameId<frame;
        if(fresh)frame=s.frameId;
        if(s==null||s.striker==null||route==null||route.ghost==null||s.aimGuideDots<6
                ||Float.isNaN(s.aimGuideX)||Float.isInfinite(s.aimGuideX)
                ||Float.isNaN(s.aimGuideY)||Float.isInfinite(s.aimGuideY)||route.strikerPath.size()<2){
            matching=0;error=Float.NaN;ghostX=Float.NaN;return;
        }
        float dx=route.strikerPath.get(1).x-s.striker.pos.x,dy=route.strikerPath.get(1).y-s.striker.pos.y;
        double length=Math.hypot(dx,dy);if(length<1){matching=0;error=Float.NaN;return;}
        float ux=(float)(dx/length),uy=(float)(dy/length);
        // A route can change between redraws of one capture. Clear its old
        // confidence immediately, while only fresh captures may add confidence.
        if(!Float.isNaN(ghostX)&&(kind!=route.kind||wall!=route.wall
                ||Math.hypot(ghostX-route.ghost.x,ghostY-route.ghost.y)>s.striker.radius*.25f
                ||ux*pathX+uy*pathY<Math.cos(Math.toRadians(1.5))))matching=0;
        ghostX=route.ghost.x;ghostY=route.ghost.y;pathX=ux;pathY=uy;kind=route.kind;wall=route.wall;
        float[] shot=AimGuideDetector.shotDirection(s.aimGuideX,s.aimGuideY);
        double dot=(dx*shot[0]+dy*shot[1])/length;
        error=(float)Math.toDegrees(Math.acos(Math.max(-1,Math.min(1,dot))));
        if(replay||!(error<=1.5f))matching=0;
        else if(fresh)matching++;
    }
    public boolean aligned(){return matching>=3;}
    public boolean visible(){return !Float.isNaN(error);}
    public float errorDegrees(){return error;}
    public String label(){return aligned()?"AIM ALIGNED":visible()?"Aim error "+Math.round(error)+"°":"Drag aim to compare";}
}
