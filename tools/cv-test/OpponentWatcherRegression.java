import com.bitaim.carromaim.cv.*;
import android.graphics.*;
import java.util.*;

/** 4.9.5: other players' shots are recorded with a motion track; striker slides and the local turn are not. */
public class OpponentWatcherRegression {
    static void check(boolean v,String m){if(!v)throw new AssertionError(m);}
    static GameState frame(float[][] discs){
        GameState s=new GameState();s.board=new RectF(52,738,1030,1714);s.strikerDetected=false;
        for(float[] d:discs)s.coins.add(new Coin(d[0],d[1],28,(int)d[2],false));
        for(float[] p:new float[][]{{85,768},{995,768},{85,1680},{995,1680}})s.pockets.add(new PointF(p[0],p[1]));
        return s;
    }
    public static void main(String[] a){
        List<String> out=new ArrayList<>();OpponentWatcher w=new OpponentWatcher(out::add);
        float[][] rest={{500,1200,0},{560,1200,1},{300,900,0},{520,850,2}}; // last: opponent striker on its baseline
        long t=0;
        for(int i=0;i<20;i++)w.feed(frame(rest),t+=100);
        // striker slides along the top baseline only
        for(int i=0;i<10;i++)w.feed(frame(new float[][]{{500,1200,0},{560,1200,1},{300,900,0},{520+i*20,850,2}}),t+=100);
        for(int i=0;i<20;i++)w.feed(frame(new float[][]{{500,1200,0},{560,1200,1},{300,900,0},{700,850,2}}),t+=100);
        check(out.isEmpty(),"a striker slide is not a shot: "+out);
        // real shot: the disc at (500,1200) travels toward a pocket and is potted
        for(int i=1;i<=12;i++){
            float k=i/12f;List<float[]> d=new ArrayList<>(Arrays.asList(new float[]{560,1200,1},new float[]{300,900,0},new float[]{700,850,2}));
            if(i<12)d.add(new float[]{500-k*400,1200+k*460,0});
            check(i==1||w.tracking()||i==12,"tracking during the shot");
            w.feed(frame(d.toArray(new float[0][])),t+=50);
        }
        for(int i=0;i<20;i++)w.feed(frame(new float[][]{{560,1200,1},{300,900,0},{700,850,2}}),t+=100);
        check(out.size()==1,"one record per opponent shot, got "+out.size());
        String r=out.get(0);
        check(r.contains("\"images\":\"opp-")&&w.consumeSaveRequest().endsWith("_after"),"opponent shot has picture id and an after-picture request");
        check(r.contains("\"opp\":true")&&r.contains("\"track\":[[")&&r.contains("\"pottedByColour\":{\"0\":1}"),"record has track and potted colour: "+r);
        // local turn clears the watcher
        GameState mine=frame(rest);mine.strikerDetected=true;mine.striker=new Coin(540,1571,35,Coin.COLOR_STRIKER,true);w.feed(mine,t+=100);
        check(!w.tracking(),"local turn stops opponent tracking");
        System.out.println("PASS: opponent shot recorded once with motion track, potted colour and pictures; striker slide and local turn ignored");
    }
}
