package com.bitaim.carromaim.cv;

import android.graphics.PointF;
import java.util.List;

/** Keep a valid route while the player aims; manual picks stay on the chosen target. */
public final class ShotTracker {
    private ShotPlanner.Shot previous;
    private Coin manualTarget;
    // Manual intent may survive a missing detection; only a current match is drawable.
    private Coin resolvedManualTarget;
    private Coin automaticTarget;
    private float anchorX,anchorY;
    private ShotPlanner.Shot challenger;
    private int challengerFrames;
    private int lostFrames;
    private int routeNumber=1,routeCount;
    /** Ranked alternatives from the last full search (the shown route excluded), for faint display. */
    private java.util.List<ShotPlanner.Shot> alternatives=new java.util.ArrayList<>();
    public java.util.List<ShotPlanner.Shot> alternatives(){return new java.util.ArrayList<>(alternatives);}
    private float lastX=Float.NaN,lastY;
    private int stillFrames;
    /** 4.9.1: while auto play is armed, always return the freshest full plan's best route (no hold, no six-pass
     *  challenger rule). The hold keeps the drawn guide steady for a human, but auto play fired the older, weaker
     *  route while a stronger one was only one or two passes away. */
    private volatile boolean alwaysBest;private int missingFrames;
    public void setAlwaysBest(boolean on){alwaysBest=on;}
    public boolean isSteady(){return stillFrames>=3;}
    public String routePosition(){return routeCount>0?" ("+routeNumber+"/"+routeCount+")":"";}
    public void clear(){alternatives=new java.util.ArrayList<>();previous=null;manualTarget=null;resolvedManualTarget=null;automaticTarget=null;challenger=null;challengerFrames=0;lostFrames=0;stillFrames=0;lastX=Float.NaN;}
    public void lock(Coin coin){clear();manualTarget=coin;resolvedManualTarget=coin;}
    public Coin selectedTarget(){return manualTarget!=null?resolvedManualTarget:automaticTarget;}
    public boolean isLocked(){return manualTarget!=null;}
    public ShotPlanner.Shot update(GameState state){
        // 4.10.7 (user: lines blink): one frame without the striker (glow, finger, animation) no longer resets the
        // route; three in a row do.
        if(state==null||!state.teamKnown||!state.strikerDetected){if(++missingFrames>=3)clear();return null;}
        missingFrames=0;
        float x=state.striker.pos.x,y=state.striker.pos.y;
        stillFrames=!Float.isNaN(lastX)&&Math.hypot(x-lastX,y-lastY)<state.striker.radius*.12f?Math.min(30,stillFrames+1):0;
        lastX=x;lastY=y;
        if(manualTarget!=null){
            Coin current=match(state,manualTarget);
            if(current==null){
                resolvedManualTarget=null;previous=null;
                // An observed ownership/rule change invalidates the lock. A missing
                // detection alone hides its highlight while preserving manual intent.
                boolean invalid=state.queenRequired&&!manualTarget.isQueen;
                for(Coin c:state.coins)if(!c.targetEligible&&distance(c.pos,manualTarget.pos)<manualTarget.radius*.75f)invalid=true;
                if(invalid)clear();
                return null;
            }
            manualTarget=resolvedManualTarget=current;
        }
        if(automaticTarget!=null)automaticTarget=match(state,automaticTarget);
        ShotPlanner.Shot held=previous==null?null:RoutePlanner.refresh(state,previous);
        if(previous!=null&&held==null){if(++lostFrames<3)return null;}
        else lostFrames=0;
        if(held!=null && !alwaysBest && (manualTarget!=null || Math.hypot(state.striker.pos.x-anchorX,state.striker.pos.y-anchorY)<state.striker.radius*.5f)){
            previous=held;challengerFrames=0;return held;
        }
        // A colour lock is enforced by eligibility, not by permanently freezing
        // one automatic coin. Repositioning may reveal an easier same-colour pot.
        List<ShotPlanner.Shot> choices=RoutePlanner.routes(state,manualTarget);
        routeCount=choices.size();routeNumber=1;
        ShotPlanner.Shot best=choices.isEmpty()?null:choices.get(0);
        ShotPlanner.Shot shown;
        if(held==null)shown=choose(best,state);
        else if(alwaysBest){
            // 4.10.7: auto play still takes the planner's best, but near-equal options no longer swap every pass (the
            // lines blinked between two routes). The current route, freshly validated on this board, stays unless the
            // best is clearly better (+8 realistic chance) or the current one no longer exists.
            ShotPlanner.Shot fresh=null;for(ShotPlanner.Shot c:choices)if(same(c,held)){fresh=c;break;}
            boolean clearlyBetter=fresh==null||best==null||Float.isNaN(RoutePlanner.rankChance(fresh))&&!Float.isNaN(RoutePlanner.rankChance(best))
                    ||!Float.isNaN(RoutePlanner.rankChance(best))&&!Float.isNaN(RoutePlanner.rankChance(fresh))&&RoutePlanner.rankChance(best)>=RoutePlanner.rankChance(fresh)+8f
                    ||RoutePlanner.compare(best,fresh)<0&&RoutePlanner.tierOf(best)<RoutePlanner.tierOf(fresh);
            shown=clearlyBetter?choose(best,state):choose(fresh,state);
        }
        // Stability: a challenger must beat the held route clearly (25 quality points, about
        // one degree of extra aim tolerance) on six consecutive planner passes.
        else if(best==null||best.quality()<held.quality()+25||same(best,held)){previous=held;challengerFrames=0;shown=held;}
        else{
            challengerFrames=challenger!=null&&same(best,challenger)?challengerFrames+1:1;
            challenger=best;
            if(challengerFrames>=6)shown=choose(best,state);
            else{previous=held;shown=held;}
        }
        java.util.List<ShotPlanner.Shot> others=new java.util.ArrayList<>();
        for(ShotPlanner.Shot c:choices)if(shown==null||!same(c,shown))others.add(c);
        alternatives=others;
        return shown;
    }
    public ShotPlanner.Shot next(GameState state){
        if(state==null||previous==null)return update(state);
        Coin target=match(state,previous.coin);if(target==null)return update(state);
        manualTarget=resolvedManualTarget=target;
        List<ShotPlanner.Shot> choices=RoutePlanner.routes(state,target);
        if(choices.isEmpty()){previous=null;return null;}
        int index=-1;for(int i=0;i<choices.size();i++)if(same(choices.get(i),previous)){index=i;break;}
        routeCount=choices.size();routeNumber=(index+1)%choices.size()+1;
        return choose(choices.get(routeNumber-1),state);
    }
    private ShotPlanner.Shot choose(ShotPlanner.Shot shot,GameState s){
        if(shot!=null&&manualTarget==null)automaticTarget=shot.coin;
        previous=shot;challengerFrames=0;lostFrames=0;challenger=null;anchorX=s.striker.pos.x;anchorY=s.striker.pos.y;return shot;
    }
    private static Coin match(GameState s,Coin old){
        Coin found=null;float best=old.radius*.75f;
        for(Coin c:s.coins){float d=distance(c.pos,old.pos);if(c.targetEligible&&d<best){best=d;found=c;}}
        return found;
    }
    private static boolean same(ShotPlanner.Shot a,ShotPlanner.Shot b){
        if(a.kind!=b.kind||a.wall!=b.wall||a.chain.size()!=b.chain.size()||distance(a.pocket,b.pocket)>8)return false;
        for(int i=0;i<a.chain.size();i++)if(distance(a.chain.get(i).pos,b.chain.get(i).pos)>a.chain.get(i).radius*.75f)return false;
        return true;
    }
    private static float distance(PointF a,PointF b){return (float)Math.hypot(a.x-b.x,a.y-b.y);}
}
