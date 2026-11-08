import com.bitaim.carromaim.cv.*;
import android.graphics.*;

/** 4.9.4: striker baseline lock and honest ranking of aim-sweep routes. */
public class BaselineSweepRegression {
    static void check(boolean v,String m){if(!v)throw new AssertionError(m);}
    static GameState frame(float y){GameState s=new GameState();s.board=new RectF(52,738,1030,1714);s.strikerDetected=true;s.striker=new Coin(540,y,35,Coin.COLOR_STRIKER,true);return s;}
    public static void main(String[] a){
        StrikerBaseline b=new StrikerBaseline();
        float[] live={1571.4f,1572.0f,1563.6f,1571.7f,1580.7f,1571.1f,1572.3f,1564.2f,1571.4f,1578.6f,1571.8f,1572.0f};
        for(float y:live)b.apply(frame(y));
        GameState s=frame(1563.6f);b.apply(s);
        check(Math.abs(s.striker.pos.y-1571.7f)<1f,"jittered reading snaps to the baseline median, got "+s.striker.pos.y);
        GameState far=frame(1500f);b.apply(far);
        check(far.striker.pos.y==1500f,"a striker away from the baseline is left alone");
        GameState top=frame(900f);top.striker.pos.y=900f;b.apply(top);
        check(top.striker.pos.y==900f,"opponent side untouched");
        ShotPlanner.Shot geo=new ShotPlanner.Shot(),sw=new ShotPlanner.Shot();
        geo.coin=sw.coin=new Coin(500,1000,28,Coin.COLOR_WHITE,false);geo.chain.add(geo.coin);sw.chain.add(sw.coin);
        geo.chancePercent=60;sw.chancePercent=90;sw.sweep=true;
        check(RoutePlanner.rankChance(sw)<RoutePlanner.rankChance(geo),"a 90% sweep route ranks below a 60% geometric pot");
        GameState o=frame(1571f);Coin bl=new Coin(561,1272,31,Coin.COLOR_BLACK,false),qu=new Coin(608,1285,31,Coin.COLOR_RED,false);o.coins.add(bl);o.coins.add(qu);
        check(DiscSeparation.apply(o)==1&&Math.abs(Math.hypot(qu.pos.x-bl.pos.x,qu.pos.y-bl.pos.y)-62)<1,"overlapping black/queen pushed to touching distance");
        System.out.println("PASS: overlap separation; striker baseline lock (live jitter 1563-1581 -> median), off-baseline untouched; sweep routes ranked at 0.4x");
    }
}
