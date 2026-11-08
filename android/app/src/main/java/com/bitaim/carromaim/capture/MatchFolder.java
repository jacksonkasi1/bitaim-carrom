package com.bitaim.carromaim.capture;

import android.content.Context;
import java.io.File;

/**
 * 4.9.9 (user: "capture the records match by match, organised"): every recording goes to
 * files/matches/<yyyyMMdd-HHmmss>/ (shots.jsonl, events.log, incidents.jsonl, pictures/). A new folder starts when a
 * fresh opening board appears (17+ discs in the opening cluster after a smaller count) or when the board returns
 * after more than a minute away.
 */
public final class MatchFolder {
    private MatchFolder(){}
    public static final int MAX_PICTURES=240;
    private static volatile String current;
    private static int lastCount=-1;private static long boardGoneAt=-1,startedAt=0;
    private static String stamp(){return new java.text.SimpleDateFormat("yyyyMMdd-HHmmss",java.util.Locale.US).format(new java.util.Date());}
    public static File root(Context c){File b=c.getExternalFilesDir(null);return b==null?null:new File(b,"matches");}
    /** Folder of the current match (created on first use). */
    public static synchronized File dir(Context c){
        File r=root(c);if(r==null)return null;
        if(current==null)current=stamp();
        File d=new File(r,current);if(!d.exists())d.mkdirs();return d;
    }
    public static File pictures(Context c){File d=dir(c);if(d==null)return null;File p=new File(d,"pictures");if(!p.exists())p.mkdirs();return p;}
    /** Called for every analysed frame (state may be null when no board is visible). */
    public static synchronized void observe(int coins,boolean openingCluster,long now){
        if(coins<0){if(boardGoneAt<0)boardGoneAt=now;return;}
        boolean back=boardGoneAt>=0&&now-boardGoneAt>60_000;boardGoneAt=-1;
        // 4.9.9b: the opening-cluster count flickers 15-19 during the first shots (a false second folder 1.5 min in);
        // a new match needs a real drop (<= 12 discs before) and a current match at least 90 s old.
        boolean fresh=openingCluster&&coins>=17&&lastCount>=0&&lastCount<=12&&now-startedAt>90_000;
        if(back||fresh){current=stamp();startedAt=now;}
        lastCount=coins;
    }
    public static synchronized void reset(){current=null;lastCount=-1;boardGoneAt=-1;startedAt=0;}
    /** Keeps at most MAX_PICTURES pictures over all matches (oldest first) and removes empty match folders. */
    public static void prune(Context c){
        File r=root(c);if(r==null)return;File[] ms=r.listFiles();if(ms==null)return;
        java.util.List<File> pics=new java.util.ArrayList<>();
        for(File m:ms){File[] p=new File(m,"pictures").listFiles();if(p!=null)java.util.Collections.addAll(pics,p);}
        if(pics.size()>MAX_PICTURES){
            pics.sort((x,y)->Long.compare(x.lastModified(),y.lastModified()));
            for(int i=0;i<pics.size()-MAX_PICTURES;i++)pics.get(i).delete();
        }
    }
}
