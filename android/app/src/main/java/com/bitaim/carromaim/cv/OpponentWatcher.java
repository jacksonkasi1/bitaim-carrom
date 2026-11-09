package com.bitaim.carromaim.cv;

import java.util.Locale;

/**
 * 4.9.5: records other players' shots (debug recording only). While it is not the local turn, the last settled board
 * is kept; when its discs start to move, every frame is tracked for up to TRACK_MS and, once the board settles again,
 * one record is written to shots.jsonl with "opp":true, the board before and after, and the motion track in the same
 * format as auto shots. Uses: friction/collision fits from many more real shots (TrackFit/MathFit read "track"),
 * and the shot choices, power and success of strong players and bots, to compare with BitAim's own choices.
 */
public final class OpponentWatcher {
    private static final long SETTLE_MS=600,REST_MS=900,LOCAL_QUIET_MS=3000,GIVE_UP_MS=12_000;public static final long TRACK_MS=4000;
    private static final int MAX_FRAMES=160;
    private final ShotLogger.Sink sink;
    private GameState settled,last;private long lastChangeAt=-1,shotAt=-1,localAt=-1;
    private StringBuilder track;private int frames;private int written;
    /** 4.9.9 (user: "for the opponent capture the same as for me"): picture requests, like auto shots get. */
    private String shotId,saveRequest;private boolean keepSettledFrame;
    /** The capture service keeps a small copy of this frame: it may become an opponent shot's "before" picture. */
    public synchronized boolean consumeKeepSettledFrame(){boolean r=keepSettledFrame;keepSettledFrame=false;return r;}
    /** "<id>_before" (save the kept settled frame) or "<id>_after" (save the current frame), or null. */
    public synchronized String consumeSaveRequest(){String r=saveRequest;saveRequest=null;return r;}
    public OpponentWatcher(ShotLogger.Sink sink){this.sink=sink;}
    public int written(){return written;}
    /** True while another player's shot is moving: the capture service then detects every frame. */
    private long lastFeedAt=-1;
    public synchronized boolean tracking(){return shotAt>=0&&frames<MAX_FRAMES&&(lastFeedAt<0||lastFeedAt-shotAt<=TRACK_MS);}
    public synchronized void feed(GameState s,long now){
        lastFeedAt=now;
        if(s==null||s.board==null||s.strikerDetected){ // local turn or no board: nothing to watch
            if(s!=null&&s.strikerDetected){settled=null;last=null;shotAt=-1;track=null;localAt=now;}
            return;
        }
        if(shotAt>=0){
            if(frames<MAX_FRAMES&&now-shotAt<=TRACK_MS)appendFrame(s,now);
            if(now-shotAt>GIVE_UP_MS){shotAt=-1;track=null;settled=null;last=null;return;}
            if(last==null||!ShotLogger.sameLayout(last,s)){last=ShotLogger.copy(s);lastChangeAt=now;return;}
            if(now-lastChangeAt>=SETTLE_MS){
                if(!ShotLogger.sameLayout(settled,s))emit(settled,s,now);
                settled=ShotLogger.copy(s);keepSettledFrame=true;last=null;shotAt=-1;track=null;shotId=null;
            }
            return;
        }
        if(last==null||!ShotLogger.sameLayout(last,s)){
            boolean moved=settled!=null&&!ShotLogger.sameLayout(settled,s)&&s.coins.size()>=settled.coins.size()-1;
            if(moved&&settled.board!=null&&Math.abs(settled.board.width()-s.board.width())<s.board.width()*.03f){
                shotAt=now;track=new StringBuilder("[");frames=0;appendFrame(s,now);last=ShotLogger.copy(s);lastChangeAt=now;
                shotId="opp-"+new java.text.SimpleDateFormat("yyyyMMdd-HHmmss",Locale.US).format(new java.util.Date());saveRequest=shotId+"_before";return;
            }
            last=ShotLogger.copy(s);lastChangeAt=now;return;
        }
        // 4.9.7: 900 ms / 3 s (1.5 s / 4 s missed a fast opponent run, live 10:43-10:44). The board must rest REST_MS, and not right after the local turn: the local shot's discs still
        // creeping to a stop were recorded as an opponent shot (live 10:30:05).
        if(now-lastChangeAt>=REST_MS&&(localAt<0||now-localAt>=LOCAL_QUIET_MS)){if(settled!=last)keepSettledFrame=true;settled=last;}
    }
    private void appendFrame(GameState s,long now){
        if(frames>0)track.append(',');
        track.append('[').append(now-shotAt).append(",-1,-1");
        for(Coin c:s.coins)track.append(',').append(Math.round(c.pos.x)).append(',').append(Math.round(c.pos.y)).append(',').append(c.color);
        track.append(']');frames++;
    }
    private void emit(GameState before,GameState after,long now){
        // Only a striker sliding along a baseline (no disc left its spot, or one disc in a baseline band): not a shot.
        int left=0;Coin moved=null;
        for(Coin c:before.coins){if(c.color==Coin.COLOR_RED)continue; // 4.9.9: queen colour flickers on some skins (live 11:21:05-24: six false records)
            boolean f=false;for(Coin d:after.coins)if(Math.hypot(c.pos.x-d.pos.x,c.pos.y-d.pos.y)<c.radius*.5f){f=true;break;}if(!f){left++;moved=c;}}
        if(left==0)return;
        float h=before.board.height();
        if(left==1&&(moved.pos.y<before.board.top+h*.2f||moved.pos.y>before.board.bottom-h*.2f)&&after.coins.size()>=before.coins.size())return;
        StringBuilder b=new StringBuilder(1024);
        b.append(String.format(Locale.US,"{\"t\":%d,\"opp\":true,\"board\":[%.1f,%.1f,%.1f,%.1f],\"striker\":[-1,-1,0],\"aim\":[0,0],\"autoAim\":null,\"autoPull\":null,",
                now,before.board.left,before.board.top,before.board.width(),before.board.height()));
        b.append("\"pockets\":[");for(int i=0;i<before.pockets.size();i++){if(i>0)b.append(',');b.append(String.format(Locale.US,"[%.1f,%.1f]",before.pockets.get(i).x,before.pockets.get(i).y));}b.append("],");
        b.append("\"before\":");discs(b,before);b.append(",\"after\":");discs(b,after);
        // Potted per colour: count drop (a disc knocked far still counts once it is gone from the board).
        java.util.Map<Integer,Integer> drop=new java.util.TreeMap<>();
        for(Coin c:before.coins)drop.merge(c.color,1,Integer::sum);for(Coin c:after.coins)drop.merge(c.color,-1,Integer::sum);
        b.append(",\"pottedByColour\":{");boolean first=true;
        for(java.util.Map.Entry<Integer,Integer> e:drop.entrySet())if(e.getValue()>0){if(!first)b.append(',');first=false;b.append('"').append(e.getKey()).append("\":").append(e.getValue());}
        b.append('}');
        if(track!=null&&frames>0)b.append(",\"track\":").append(track).append(']');
        b.append(",\"settledMs\":").append(now-shotAt);
        if(shotId!=null){b.append(",\"images\":\"").append(shotId).append('"');saveRequest=shotId+"_after";}
        b.append('}');
        try{sink.write(b.toString());written++;}catch(Throwable ignored){}
    }
    private static void discs(StringBuilder b,GameState s){
        b.append('[');
        for(int i=0;i<s.coins.size();i++){Coin c=s.coins.get(i);if(i>0)b.append(',');
            b.append(String.format(Locale.US,"[%.1f,%.1f,%.1f,%d,%d,%d]",c.pos.x,c.pos.y,c.radius,c.color,c.targetEligible?1:0,c.isQueen?1:0));}
        b.append(']');
    }
}
