package com.bitaim.carromaim.cv;

import java.util.ArrayList;
import java.util.List;

/**
 * 4.12.1 (live 23:33:51, "green route but it fouls"): a white disc next to the target was missing from the frames the
 * route was planned on, so BitAim drew a coin-cushion route whose striker path ran through it (42 px from its centre,
 * 70 px needed); the striker hit the white, potted it and sent the target away. On the recorded board the planner
 * rejects that route. Our own drawing (contact ring and route line over the disc) and the game's aim dots can hide a
 * disc for a few frames, and the route then keeps itself alive.
 *
 * During the local turn nothing on the board moves until the shot, so a disc seen steadily a moment ago that is
 * missing now, while every other disc is where it was, is kept for planning. Any real motion (a shot) or the end of
 * the turn clears the memory.
 */
public final class DiscMemory {
    static final long KEEP_MS=15000; static final int MIN_SEEN=3,MAX_RESTORED=3; // a turn lasts ~15 s; nothing leaves a still board
    private static final class Seen{Coin c;int count;long lastAt;}
    private final List<Seen> seen=new ArrayList<>();
    private int restoredLast;
    public int restoredLast(){return restoredLast;}
    public synchronized void reset(){seen.clear();restoredLast=0;}

    /** Adds remembered discs to {@code s.coins}; returns how many were restored. */
    public synchronized int apply(GameState s,long now){
        restoredLast=0;
        if(s==null||s.board==null||!s.strikerDetected||s.striker==null){seen.clear();return 0;}
        float tol=s.coins.isEmpty()?20:s.coins.get(0).radius*.35f;
        boolean[] matched=new boolean[seen.size()];int stillMatched=0,fresh=0;
        for(Coin c:s.coins){
            int best=-1;float bd=Float.MAX_VALUE;
            for(int i=0;i<seen.size();i++){if(matched[i])continue;Seen m=seen.get(i);
                float d=(float)Math.hypot(m.c.pos.x-c.pos.x,m.c.pos.y-c.pos.y);if(d<bd){bd=d;best=i;}}
            if(best>=0&&bd<Math.max(tol,c.radius*.35f)){matched[best]=true;Seen m=seen.get(best);m.count++;m.lastAt=now;m.c=copy(c);stillMatched++;}
            else fresh++;
        }
        // Several discs at new places = the board changed (shot, opponent turn, new rack): start over from this frame.
        if(fresh>=2||(seen.size()>0&&stillMatched<seen.size()*.6f-1)){seen.clear();for(Coin c:s.coins)add(c,now);return 0;}
        List<Coin> restore=new ArrayList<>();
        for(int i=0;i<seen.size();i++){
            if(matched[i])continue;Seen m=seen.get(i);
            if(now-m.lastAt>KEEP_MS||m.count<MIN_SEEN)continue;
            // the striker standing on that spot is the striker, not a hidden disc
            if(Math.hypot(m.c.pos.x-s.striker.pos.x,m.c.pos.y-s.striker.pos.y)<m.c.radius+s.striker.radius*.8f)continue;
            boolean overlaps=false;for(Coin c:s.coins)if(Math.hypot(c.pos.x-m.c.pos.x,c.pos.y-m.c.pos.y)<c.radius*1.2f){overlaps=true;break;}
            if(!overlaps&&restore.size()<MAX_RESTORED)restore.add(copy(m.c));
        }
        for(Coin c:s.coins){boolean known=false;for(Seen m:seen)if(Math.hypot(m.c.pos.x-c.pos.x,m.c.pos.y-c.pos.y)<c.radius*.35f){known=true;break;}if(!known)add(c,now);}
        seen.removeIf(m->now-m.lastAt>KEEP_MS);
        s.coins.addAll(restore);restoredLast=restore.size();
        return restoredLast;
    }
    private void add(Coin c,long now){Seen m=new Seen();m.c=copy(c);m.count=1;m.lastAt=now;seen.add(m);}
    static Coin copy(Coin c){Coin k=new Coin(c.pos.x,c.pos.y,c.radius,c.color,false);k.targetEligible=c.targetEligible;k.transferEligible=c.transferEligible;k.isQueen=c.isQueen;k.appearanceKey=c.appearanceKey;return k;}
}
