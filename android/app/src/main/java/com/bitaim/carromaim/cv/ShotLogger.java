package com.bitaim.carromaim.cv;

import java.util.Locale;

/**
 * Records real shots for calibration. Fed every detected frame by the capture service:
 * remembers the last local frame showing the game's white aim dots (the real launch direction),
 * detects the release (striker leaves the baseline or jumps), waits for the discs to stop moving,
 * then writes one JSON line with the board, striker, aim, discs before and discs after.
 * No network access: the sink is a local file chosen by the caller.
 */
public final class ShotLogger {
    public interface Sink { void write(String line); }
    private static final long RELEASE_WINDOW_MS=1500, MOTION_MS=1200, SETTLE_MS=600, GIVE_UP_MS=12000;
    private final Sink sink;
    private GameState aimed, lastSeen;
    private long aimedAt, shotAt, lastChangeAt;
    private boolean waiting;
    private float autoPull=Float.NaN,autoUx=Float.NaN,autoUy=Float.NaN;
    /** Debug capture for auto shots: route details and a pending image request ("<id>_before" / "<id>_after"). */
    private String autoInfo,shotId,saveRequest;
    /** Motion track of an auto shot (4.8.6): every detected frame for TRACK_MS after release, as
     *  [ms since release, striker x, y (or -1), then x,y,colour per disc]. The fit of friction, launch speed per pull
     *  and rail loss needs positions over time; start and settled pictures cannot separate power from drag. */
    public static final long TRACK_MS=4000;private StringBuilder track;private int trackFrames;private long trackAt=-1;
    /** True while an auto shot is being tracked: the capture service then detects every frame it can. */
    /** 4.11.2: only inside the TRACK_MS window (an unreleased auto marker kept "tracking" and so full-rate detection
     *  on). */
    private long lastFeedAt=-1;
    public synchronized boolean tracking(){return track!=null&&trackAt>=0&&trackFrames<160&&(lastFeedAt<0||lastFeedAt-trackAt<=TRACK_MS);}
    private float targetX=Float.NaN,targetY,pocketX,pocketY;
    /** Current on-screen suggestion (launch direction, target, pocket, label), refreshed by the overlay. */
    private volatile float[] suggestion;private volatile String suggestionLabel;
    private float[] aimedSuggestion;private String aimedSuggestionLabel;
    private String incident;private boolean lastWasAimed;
    private GameState cleanBefore,cleanCandidate;private long cleanAt=-1;
    public void setSuggestion(float ux,float uy,float tx,float ty,float px,float py,String label){suggestion=new float[]{ux,uy,tx,ty,px,py};suggestionLabel=label;}
    public void clearSuggestion(){suggestion=null;suggestionLabel=null;}
    /** Incident type to capture (screenshot + record), or null; clears it. */
    public synchronized String consumeIncident(){String r=incident;incident=null;return r;}
    /** True when the last fed frame was a local aimed frame (the capture service keeps a copy for incidents). */
    public synchronized boolean lastWasAimed(){return lastWasAimed;}
    private String lastRecord;private boolean newManualRecord;
    /** True once after a manual (non-auto) shot record was written. */
    public synchronized boolean consumeNewManualRecord(){boolean r=newManualRecord;newManualRecord=false;return r;}
    public synchronized String lastRecord(){return lastRecord;}
    /** Target coin and pocket of the auto shot, so the record can say whether that exact coin went in. */
    public synchronized void markTarget(float tx,float ty,float px,float py){targetX=tx;targetY=ty;pocketX=px;pocketY=py;}
    /** Burst of pictures while the discs move (ms after the shot), to see the striker path and any scratch. */
    private static final long[] BURST_MS={400,900,1600};
    private long autoShotAt=-1;private int motionIndex;
    /** Image the capture service should save from the current frame, or null; clears the request. */
    public synchronized String consumeSaveRequest(){String r=saveRequest;saveRequest=null;return r;}
    private int written;
    public ShotLogger(Sink sink){this.sink=sink;}
    public int written(){return written;}
    /** Called when BitAim itself releases a shot; pull is the drag length as a fraction of board width. */
    public synchronized void markAutoShot(float pull){autoPull=pull;}
    /** Auto shot with the launch direction BitAim intended, to compare with the game's own aim dots. */
    public synchronized void markAutoShot(float pull,float ux,float uy){markAutoShot(pull,ux,uy,null);}
    /** {@code info} is a JSON object describing the route BitAim chose; a "before" image is requested at once. */
    public synchronized void markAutoShot(float pull,float ux,float uy,String info){
        // Practice 23:34: a quick follow-up auto shot arrived while the previous record was still waiting to settle,
        // so the new route was written into the old record (false "target not moved" and stall reports). Close the
        // pending record first with the last board seen.
        if(waiting&&aimed!=null){
            if(lastSeen!=null&&!sameLayout(aimed,lastSeen))emit(aimed,lastSeen,System.currentTimeMillis());
            reset();
        }
        autoPull=pull;autoUx=ux;autoUy=uy;autoInfo=info;
        shotId="auto-"+new java.text.SimpleDateFormat("yyyyMMdd-HHmmss",Locale.US).format(new java.util.Date());
        saveRequest=shotId+"_before";
        autoShotAt=-1;motionIndex=0; // the clock starts on the next frame (feed has the capture time)
    }
    /** Released after closed-loop aim: same shot id and "before" picture; final pull, direction and route info;
     *  the motion pictures restart from the release (the hold before it is not motion). */
    public synchronized void updateAutoShot(float pull,float ux,float uy,String info){
        if(shotId==null){markAutoShot(pull,ux,uy,info);return;}
        autoPull=pull;autoUx=ux;autoUy=uy;autoInfo=info;autoShotAt=-1;motionIndex=0;
    }
    public synchronized void feed(GameState s,long now){
        lastFeedAt=now;
        if(shotId!=null&&autoShotAt<0){autoShotAt=now;track=new StringBuilder("[");trackFrames=0;trackAt=now;}
        // 4.10.9: hand-played shots are tracked too (from the release), so the result can follow moved coins and
        // tell a potted coin from one knocked across the board, and find the resting striker.
        if(track!=null&&trackAt>=0){
            if(now-trackAt<=TRACK_MS&&trackFrames<160&&s!=null&&s.board!=null){
                if(trackFrames>0)track.append(',');
                track.append('[').append(now-trackAt).append(',');
                if(s.strikerDetected&&s.striker!=null)track.append(Math.round(s.striker.pos.x)).append(',').append(Math.round(s.striker.pos.y));else track.append("-1,-1");
                for(Coin c:s.coins)track.append(',').append(Math.round(c.pos.x)).append(',').append(Math.round(c.pos.y)).append(',').append(c.color);
                track.append(']');trackFrames++;
            }
            else if(shotId!=null&&saveRequest==null&&motionIndex<BURST_MS.length&&now-autoShotAt>=BURST_MS[motionIndex])saveRequest=shotId+"_motion"+(++motionIndex);
        }
        lastWasAimed=false;
        if(s==null||s.board==null||s.striker==null)return;
        // 4.9.11: a clean pre-shot board (no aim dots hiding discs), accepted when two consecutive frames agree.
        // 4.10.5: the clean board must come from this turn: another player's turn (no own striker) clears it, else
        // their pots were credited to our shot ("own 7" in one shot, PC session 15:10:23).
        if(!waiting&&!s.strikerDetected){cleanBefore=null;cleanCandidate=null;}
        if(!waiting&&s.aimGuideDots<3&&s.strikerDetected){
            if(cleanCandidate!=null&&sameLayout(cleanCandidate,s)){cleanBefore=cleanCandidate;cleanAt=now;}
            cleanCandidate=copy(s);
        }
        if(!waiting){
            // 4.11.0 (live 19:27:18): an auto shot that is never seen leaving the baseline must not stay pending: 163 s
            // later the player's own shot was written under the stale auto route. Drop the auto marker after GIVE_UP_MS.
            if(shotId!=null&&autoShotAt>=0&&now-autoShotAt>GIVE_UP_MS){reset();aimed=null;}
            if(s.strikerDetected&&s.aimGuideDots>=6&&!Float.isNaN(s.aimGuideX)){aimed=copy(s);aimedAt=now;lastWasAimed=true;aimedSuggestion=suggestion;aimedSuggestionLabel=suggestionLabel;return;}
            // An auto shot is held by BitAim itself: its release is the striker leaving the baseline, even when the game's
            // aim dots were not read during the hold (that shot: "dots 0", so no aimed board and no record at all).
            if(shotId!=null&&s.strikerDetected&&(aimed==null||now-aimedAt>200)){aimed=copy(s);aimedAt=now;return;}
            if(aimed==null)return;
            if(now-aimedAt>RELEASE_WINDOW_MS){aimed=null;autoPull=Float.NaN;return;} // aim dropped without a shot
            boolean released=!s.strikerDetected||Math.hypot(s.striker.pos.x-aimed.striker.pos.x,s.striker.pos.y-aimed.striker.pos.y)>aimed.striker.radius;
            if(released){waiting=true;shotAt=now;lastSeen=null;
                if(shotId==null){track=new StringBuilder("[");trackFrames=0;trackAt=now;
                    // first entries: the aimed board (negative time) and this release frame
                    track.append('[').append(aimedAt-now).append(',').append(Math.round(aimed.striker.pos.x)).append(',').append(Math.round(aimed.striker.pos.y));
                    for(Coin c:aimed.coins)track.append(',').append(Math.round(c.pos.x)).append(',').append(Math.round(c.pos.y)).append(',').append(c.color);
                    track.append("],[0,-1,-1");
                    for(Coin c:s.coins)track.append(',').append(Math.round(c.pos.x)).append(',').append(Math.round(c.pos.y)).append(',').append(c.color);
                    track.append(']');trackFrames=2;}}
            return;
        }
        if(now-shotAt>GIVE_UP_MS){reset();return;}
        if(now-shotAt<MOTION_MS){
            // A finger covering the striker while pulling back looks like a release; if the striker is back
            // where it was and still aimed, keep aiming instead of logging an unchanged board.
            if(s.strikerDetected&&s.aimGuideDots>=6&&!Float.isNaN(s.aimGuideX)
                    &&Math.hypot(s.striker.pos.x-aimed.striker.pos.x,s.striker.pos.y-aimed.striker.pos.y)<aimed.striker.radius*.5f){
                waiting=false;aimed=copy(s);aimedAt=now;
                if(shotId==null){track=null;trackFrames=0;trackAt=-1;}
            }
            return;
        }
        if(lastSeen==null||!sameLayout(lastSeen,s)){lastSeen=copy(s);lastChangeAt=now;return;}
        if(now-lastChangeAt>=SETTLE_MS){
            // Nothing moved: either no real shot or a complete miss; neither tells us anything about power.
            if(!sameLayout(aimed,s))emit(aimed,s,now);
            reset();
        }
    }
    private static boolean ownLike(Coin c,java.util.List<Integer> ownKeys,java.util.Set<Integer> ownColours){
        if(!ownKeys.isEmpty()&&c.appearanceKey!=TargetColourLock.AUTO){for(int k:ownKeys)if(TargetColourLock.matches(k,c.appearanceKey))return true;return false;}
        return ownColours.contains(c.color);
    }
    private void reset(){waiting=false;aimed=null;lastSeen=null;autoPull=Float.NaN;autoUx=autoUy=Float.NaN;autoInfo=null;shotId=null;autoShotAt=-1;track=null;trackFrames=0;trackAt=-1;motionIndex=0;targetX=Float.NaN;}
    private void emit(GameState before,GameState after,long now){
        StringBuilder b=new StringBuilder(512);
        b.append(String.format(Locale.US,"{\"t\":%d,\"board\":[%.1f,%.1f,%.1f,%.1f],\"striker\":[%.1f,%.1f,%.1f],\"aim\":[%.4f,%.4f],\"dots\":%d,\"autoPull\":%s,\"autoAim\":%s,\"key\":%d,",
                now,before.board.left,before.board.top,before.board.width(),before.board.height(),before.striker.pos.x,before.striker.pos.y,before.striker.radius,
                AimGuideDetector.shotDirection(before.aimGuideX,before.aimGuideY)[0],AimGuideDetector.shotDirection(before.aimGuideX,before.aimGuideY)[1],before.aimGuideDots,Float.isNaN(autoPull)?"null":String.format(Locale.US,"%.4f",autoPull),Float.isNaN(autoUx)?"null":String.format(Locale.US,"[%.4f,%.4f]",autoUx,autoUy),before.targetColourKey));
        if(!Float.isNaN(before.aimPower))b.append(String.format(Locale.US,"\"arrow\":%.4f,",before.aimPower)); // 4.13.0: red power arrow, board widths
        b.append("\"pockets\":[");for(int i=0;i<before.pockets.size();i++){if(i>0)b.append(',');b.append(String.format(Locale.US,"[%.1f,%.1f]",before.pockets.get(i).x,before.pockets.get(i).y));}b.append("],");
        b.append("\"before\":");discs(b,before);b.append(",\"after\":");discs(b,after);
        if(shotId!=null){
            // Count by colour (the "own" flag is unreliable on the settled frame), queen excluded.
            // v4.7.7: match on the appearance key when known (striped vs solid of the same hue share Coin.color;
            // live 23:44 records showed "10 own potted" in a solid-blue vs striped-blue game).
            java.util.Set<Integer> ownColours=new java.util.HashSet<>();java.util.List<Integer> ownKeys=new java.util.ArrayList<>();
            for(Coin c:before.coins)if(c.targetEligible&&!c.isQueen){ownColours.add(c.color);if(c.appearanceKey!=TargetColourLock.AUTO&&!ownKeys.contains(c.appearanceKey))ownKeys.add(c.appearanceKey);}
            int ownBefore=0,ownAfter=0;for(Coin c:before.coins)if(!c.isQueen&&ownLike(c,ownKeys,ownColours))ownBefore++;for(Coin c:after.coins)if(!c.isQueen&&ownLike(c,ownKeys,ownColours))ownAfter++;
            int restingStriker=Math.max(0,after.coins.size()-before.coins.size()); // the striker at rest is detected as a disc
            if(ownKeys.isEmpty())ownAfter=Math.max(0,ownAfter-restingStriker); // a striker skin never matches an appearance key
            b.append(",\"autoRoute\":").append(autoInfo==null?"null":autoInfo);
            if(track!=null&&trackFrames>0)b.append(",\"track\":").append(track).append(']');
            b.append(",\"ownPotted\":").append(Math.max(0,ownBefore-ownAfter));
            // Discs present before with no disc of the same colour within one radius after: potted or knocked far.
            int oppPotted=0;boolean queenGone=false;StringBuilder gone=new StringBuilder("[");
            for(Coin c:before.coins){
                boolean still=false;for(Coin d:after.coins)if(d.color==c.color&&Math.hypot(d.pos.x-c.pos.x,d.pos.y-c.pos.y)<c.radius){still=true;break;}
                if(still)continue;
                if(gone.length()>1)gone.append(',');
                gone.append(String.format(Locale.US,"[%.1f,%.1f,%d,%d,%d]",c.pos.x,c.pos.y,c.color,c.targetEligible?1:0,c.isQueen?1:0));
            }
            gone.append(']');
            int countDrop=Math.max(0,before.coins.size()-after.coins.size());
            for(Coin c:before.coins)if(c.isQueen){boolean q=false;for(Coin d:after.coins)if(d.isQueen)q=true;queenGone=!q;}
            int ownDrop=Math.max(0,ownBefore-ownAfter);oppPotted=Math.max(0,countDrop-ownDrop-(queenGone?1:0));
            b.append(",\"opponentPotted\":").append(oppPotted).append(",\"queenPotted\":").append(queenGone);
            b.append(",\"movedOrGone\":").append(gone);
            b.append(",\"settledMs\":").append(autoShotAt<0?-1:now-autoShotAt);
            if(!Float.isNaN(targetX)){
                Coin tc=null;float bd=Float.MAX_VALUE;
                for(Coin c:before.coins){float d=(float)Math.hypot(c.pos.x-targetX,c.pos.y-targetY);if(d<bd){bd=d;tc=c;}}
                if(tc!=null){
                    boolean stayed=false,nearPocket=false;
                    for(Coin d:after.coins){
                        if(d.color!=tc.color)continue;
                        if(Math.hypot(d.pos.x-tc.pos.x,d.pos.y-tc.pos.y)<tc.radius)stayed=true;
                        if(Math.hypot(d.pos.x-pocketX,d.pos.y-pocketY)<tc.radius*3)nearPocket=true;
                    }
                    // potted = gone from its spot and not resting at the pocket lip; "moved" means hit but not potted is still possible
                    String fate=stayed?"not-moved":nearPocket?"at-pocket-lip":"gone";
                    b.append(",\"target\":\"").append(fate).append('"');
                    if(!"gone".equals(fate))incident="auto-target-"+fate;
                }
            }
            b.append(",\"images\":\"").append(shotId).append('"');
            saveRequest=shotId+"_after";
        }
        if(shotId==null&&aimedSuggestion!=null){
            // Manual shot: did the player follow BitAim's line, and did the suggested target go in?
            float[] g=AimGuideDetector.shotDirection(before.aimGuideX,before.aimGuideY),sg=aimedSuggestion;
            double diff=Math.toDegrees(Math.acos(Math.max(-1,Math.min(1,g[0]*sg[0]+g[1]*sg[1]))));
            Coin tc=null;float bd=Float.MAX_VALUE;
            for(Coin c:before.coins){float d=(float)Math.hypot(c.pos.x-sg[2],c.pos.y-sg[3]);if(d<bd){bd=d;tc=c;}}
            String fate="unknown";
            if(tc!=null){boolean stayed=false,lip=false;
                for(Coin d:after.coins){if(d.color!=tc.color)continue;if(Math.hypot(d.pos.x-tc.pos.x,d.pos.y-tc.pos.y)<tc.radius)stayed=true;if(Math.hypot(d.pos.x-sg[4],d.pos.y-sg[5])<tc.radius*3)lip=true;}
                fate=stayed?"not-moved":lip?"at-pocket-lip":"gone";}
            b.append(String.format(Locale.US,",\"suggestion\":{\"label\":\"%s\",\"dir\":[%.4f,%.4f],\"target\":[%.1f,%.1f],\"pocket\":[%.1f,%.1f],\"aimDiffDeg\":%.2f,\"targetFate\":\"%s\"}",
                    aimedSuggestionLabel==null?"":aimedSuggestionLabel.replace("\"","'"),sg[0],sg[1],sg[2],sg[3],sg[4],sg[5],diff,fate));
            if(diff<=2.5&&!"gone".equals(fate))incident="followed-line-"+fate;
        }
        appendResult(b,before,after,now);
        b.append('}');
        lastRecord=b.toString();
        if(shotId==null)newManualRecord=true;
        try{sink.write(lastRecord);written++;}catch(Throwable ignored){}
    }
    private static void discs(StringBuilder b,GameState s){
        b.append('[');
        for(int i=0;i<s.coins.size();i++){Coin c=s.coins.get(i);if(i>0)b.append(',');
            b.append(String.format(Locale.US,"[%.1f,%.1f,%.1f,%d,%d,%d]",c.pos.x,c.pos.y,c.radius,c.color,c.targetEligible?1:0,c.isQueen?1:0));}
        b.append(']');
    }
    static boolean sameLayout(GameState x,GameState y){
        if(x.coins.size()!=y.coins.size())return false;
        for(Coin c:x.coins){boolean f=false;for(Coin d:y.coins)if(Math.hypot(c.pos.x-d.pos.x,c.pos.y-d.pos.y)<c.radius*.3f){f=true;break;}if(!f)return false;}
        return true;
    }
    static GameState copy(GameState s){
        GameState c=new GameState();
        c.frameId=s.frameId;c.targetColourKey=s.targetColourKey;c.aimGuideX=s.aimGuideX;c.aimGuideY=s.aimGuideY;c.aimGuideDots=s.aimGuideDots;c.aimPower=s.aimPower;
        c.board=new android.graphics.RectF(s.board.left,s.board.top,s.board.right,s.board.bottom);
        if(s.striker!=null)c.striker=new Coin(s.striker.pos.x,s.striker.pos.y,s.striker.radius,Coin.COLOR_STRIKER,true);
        c.strikerDetected=s.strikerDetected;c.teamKnown=s.teamKnown;c.freestyle=s.freestyle;c.queenRequired=s.queenRequired;c.sliderY=s.sliderY;
        for(Coin o:s.coins){Coin n=new Coin(o.pos.x,o.pos.y,o.radius,o.color,false);n.targetEligible=o.targetEligible;n.transferEligible=o.transferEligible;n.isQueen=o.isQueen;n.appearanceKey=o.appearanceKey;c.coins.add(n);}
        for(android.graphics.PointF p:s.pockets)c.pockets.add(new android.graphics.PointF(p.x,p.y));
        return c;
    }
    /** 4.9.11: "result" from the clean pre-shot board (falls back to the aimed frame): every disc gone from the
     *  settled board with its colour and the pocket it most likely entered (followed through the motion track). */
    private void appendResult(StringBuilder b,GameState aimedBefore,GameState after,long now){
        GameState before=cleanBefore!=null&&now-cleanAt<30_000?cleanBefore:aimedBefore;
        java.util.List<double[]> frames=new java.util.ArrayList<>();
        if(track!=null&&trackFrames>0){
            String t=track.toString()+"]";int depth=0,start=-1;
            for(int i=0;i<t.length();i++){char ch=t.charAt(i);if(ch=='['){depth++;if(depth==2)start=i+1;}else if(ch==']'){if(depth==2){String[] p=t.substring(start,i).split(",");double[] v=new double[p.length];for(int k=0;k<p.length;k++)v[k]=Double.parseDouble(p[k]);frames.add(v);}depth--;}}
        }
        float w=before.board.width();
        boolean tracked=frames.size()>=3;
        // 4.10.9: follow the striker itself. After the release it is no longer detected as the striker but as a disc
        // of any colour. Start at the baseline spot, take the disc nearest to where it should be (own speed), and
        // see where it ends: on an after disc (resting), back on the baseline, or gone near a pocket (pocketed = foul).
        String striker="unknown";float sx=Float.NaN,sy=Float.NaN;int strikerDisc=-1;
        if(tracked&&before.striker!=null){
            double x=before.striker.pos.x,y=before.striker.pos.y,prevT=Double.NaN,vx=Double.NaN,vy=0;boolean started=false,back=false;
            for(double[] f:frames){
                double dt=Double.isNaN(prevT)?0:Math.max(0,f[0]-prevT)/1000;prevT=f[0];
                if(f[1]>=0){if(started&&f[0]>0)back=true;continue;}
                double px=x,py=y,bd=w*Math.max(.12,Math.min(.4,2.4*Math.max(dt,.1)));
                if(!Double.isNaN(vx)){px=x+vx*dt;py=y+vy*dt;bd=w*.10+.5*Math.hypot(vx,vy)*dt;}
                double nx=Double.NaN,ny=0;
                for(int k=3;k+2<f.length;k+=3){
                    // a disc still sitting where a before disc was is that disc, not the striker
                    boolean old=false;if(!started)for(Coin c:before.coins)if(Math.hypot(f[k]-c.pos.x,f[k+1]-c.pos.y)<c.radius*.6f){old=true;break;}
                    if(old)continue;
                    double d=Math.hypot(f[k]-px,f[k+1]-py);if(d<bd){bd=d;nx=f[k];ny=f[k+1];}
                }
                if(Double.isNaN(nx))continue;
                if(dt>0){vx=(nx-x)/dt;vy=(ny-y)/dt;}
                x=nx;y=ny;started=true;
            }
            if(started){
                for(int k=0;k<after.coins.size();k++){Coin d=after.coins.get(k);if(Math.hypot(d.pos.x-x,d.pos.y-y)<d.radius*1.6f){strikerDisc=k;break;}}
                                if(strikerDisc>=0){striker="resting";sx=after.coins.get(strikerDisc).pos.x;sy=after.coins.get(strikerDisc).pos.y;}
                else if(back||after.strikerDetected)striker="baseline";
                // Pocketed only when it vanished while still moving toward the pocket next to it; a striker that stopped
                // and then disappeared was handed to the next player (the game moves it to their baseline).
                else{
                    boolean heading=false;
                    if(!Double.isNaN(vx)&&Math.hypot(vx,vy)>w*.3)for(android.graphics.PointF p:before.pockets)
                        if(Math.hypot(p.x-x,p.y-y)<w*.12&&((p.x-x)*vx+(p.y-y)*vy)>0)heading=true;
                    // 4.12.1: at the phone's ~6 fps the follow loses a fast striker; 4 of 5 "gone" shots (23:17-23:33)
                    // were clean pots with the striker resting on the board. Say so instead of implying a foul.
                    striker=heading?"pocketed":"lost";
                }
            }
        }
        // Counts per colour (a coin knocked across the board keeps its colour count): potted = before - after,
        // with the resting striker's disc left out. The list names each disc gone from its spot and, from the track,
        // the pocket it was heading for (-1: unknown).
        java.util.Map<Integer,Integer> drop=new java.util.HashMap<>();
        for(Coin c:before.coins)if(!c.isQueen)drop.merge(c.color,1,Integer::sum);
        for(int k=0;k<after.coins.size();k++){Coin d=after.coins.get(k);if(k==strikerDisc||d.isQueen)continue;drop.merge(d.color,-1,Integer::sum);}
        java.util.Set<Integer> ownColours=new java.util.HashSet<>();for(Coin c:before.coins)if(c.targetEligible&&!c.isQueen)ownColours.add(c.color);
        int own=0,other=0;for(java.util.Map.Entry<Integer,Integer> en:drop.entrySet()){int n=Math.max(0,en.getValue());if(ownColours.contains(en.getKey()))own+=n;else other+=n;}
        boolean queenBefore=false,queenAfter=false;for(Coin c:before.coins)if(c.isQueen)queenBefore=true;for(Coin d:after.coins)if(d.isQueen)queenAfter=true;
        boolean queen=queenBefore&&!queenAfter;
        // colour labels flicker between frames; the per-colour drops can never exceed the real drop in disc count
        int total=Math.max(0,before.coins.size()-(after.coins.size()-(strikerDisc>=0?1:0))-(queen?1:0));
        own=Math.min(own,total);other=Math.min(other,total-own);
        StringBuilder g=new StringBuilder("[");
        for(Coin c:before.coins){
            boolean still=false;for(Coin d:after.coins)if(d.color==c.color&&Math.hypot(d.pos.x-c.pos.x,d.pos.y-c.pos.y)<c.radius){still=true;break;}
            if(still)continue;
            double x=c.pos.x,y=c.pos.y,prevT=Double.NaN,vx=Double.NaN,vy=0;
            for(double[] f:frames){double dt=Double.isNaN(prevT)?0:Math.max(0,f[0]-prevT)/1000;prevT=f[0];
                double px=x,py=y,bd=w*Math.max(.09,Math.min(.35,2.0*dt));
                if(!Double.isNaN(vx)){px=x+vx*dt;py=y+vy*dt;bd=w*.09+.5*Math.hypot(vx,vy)*dt;}
                double nx=Double.NaN,ny=0;
                for(int k=3;k+2<f.length;k+=3){if((int)f[k+2]!=c.color)continue;double d=Math.hypot(f[k]-px,f[k+1]-py);if(d<bd){bd=d;nx=f[k];ny=f[k+1];}}
                if(!Double.isNaN(nx)){if(dt>0){vx=(nx-x)/dt;vy=(ny-y)/dt;}x=nx;y=ny;}}
            boolean onBoard=false;for(int k=0;k<after.coins.size();k++){Coin d=after.coins.get(k);if(k!=strikerDisc&&d.color==c.color&&Math.hypot(d.pos.x-x,d.pos.y-y)<c.radius*1.5f){onBoard=true;break;}}
            if(onBoard)continue;
            int pocket=-1;double pd=w*.12;
            for(int k=0;k<before.pockets.size();k++){android.graphics.PointF p=before.pockets.get(k);double d=Math.hypot(p.x-x,p.y-y);if(d<pd){pd=d;pocket=k;}}
            if(g.length()>1)g.append(',');
            g.append(String.format(Locale.US,"[%.1f,%.1f,%d,%d,%d,%d]",c.pos.x,c.pos.y,c.color,c.targetEligible?1:0,c.isQueen?1:0,pocket));
        }
        g.append(']');
        if("pocketed".equals(striker)&&incident==null)incident="striker-pocketed"; // foul: keep pictures
        int discsAfter=after.coins.size()-(strikerDisc>=0?1:0);
        b.append(",\"result\":{\"clean\":").append(before==cleanBefore).append(",\"potted\":").append(g)
         .append(",\"own\":").append(own).append(",\"other\":").append(other).append(",\"queen\":").append(queen)
         .append(",\"tracked\":").append(frames.size()).append(",\"striker\":\"").append(striker).append('"');
        if(!Float.isNaN(sx))b.append(String.format(Locale.US,",\"strikerRest\":[%.1f,%.1f]",sx,sy));
        b.append(",\"discsBefore\":").append(before.coins.size()).append(",\"discsAfter\":").append(discsAfter)
         .append(",\"discsAfterRaw\":").append(after.coins.size()).append('}');
    }
}
