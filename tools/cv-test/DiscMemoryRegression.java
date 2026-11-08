import com.bitaim.carromaim.cv.*;
import java.nio.file.*;import java.util.*;

/** 4.12.1 (live 23:33:51): a white disc beside the target was missing from the planning frames, and the offered route's
 *  striker path ran through it. Discs seen steadily during the turn are kept when they vanish on a still board. */
public class DiscMemoryRegression {
    static void check(boolean v,String m){if(!v)throw new AssertionError(m);}
    static GameState board(String line,boolean withWhite){
        GameState g=ShotLogCalibrator.parse(line).before;double[] b=ShotLogCalibrator.nums(ShotLogCalibrator.field(line,"before"));
        for(int i=0;i<g.coins.size();i++)g.coins.get(i).targetEligible=b[i*6+4]>0;
        if(!withWhite)g.coins.removeIf(c->Math.hypot(c.pos.x-188.1,c.pos.y-1502.1)<5);
        g.teamKnown=true;g.strikerDetected=true;return g;
    }
    static double clearance(ShotPlanner.Shot s,float x,float y){
        double best=1e9;
        for(int i=0;i+1<s.strikerPath.size();i++){
            android.graphics.PointF a=s.strikerPath.get(i),b=s.strikerPath.get(i+1);double dx=b.x-a.x,dy=b.y-a.y,l2=dx*dx+dy*dy;
            double t=l2<1e-6?0:Math.max(0,Math.min(1,((x-a.x)*dx+(y-a.y)*dy)/l2));best=Math.min(best,Math.hypot(x-(a.x+dx*t),y-(a.y+dy*t)));
        }
        return best;
    }
    public static void main(String[] a)throws Exception{
        String line=Files.readAllLines(Paths.get(a[0])).get(0);
        // Without the white the planner offers routes through its spot (what was drawn live).
        GameState missing=board(line,false);float need=missing.striker.radius+31.3f;
        int through=0;for(ShotPlanner.Shot s:RoutePlanner.routes(missing,null))if(clearance(s,188.1f,1502.1f)<need)through++;
        check(through>0,"reproduces: with the white missing a route runs through its spot");
        // Memory: four full frames, then frames without the white -> the white is kept and no route runs through it.
        DiscMemory mem=new DiscMemory();
        for(int k=0;k<4;k++)check(mem.apply(board(line,true),k*150)==0,"full frames add nothing");
        GameState hidden=board(line,false);int restored=mem.apply(hidden,700);
        check(restored==1&&hidden.coins.size()==6,"hidden white restored ("+restored+")");
        for(ShotPlanner.Shot s:RoutePlanner.routes(hidden,null))check(clearance(s,188.1f,1502.1f)>=need-1,"no route through the kept white: "+RoutePlanner.label(s));
        // Still kept a moment later, forgotten after KEEP_MS without being seen.
        check(mem.apply(board(line,false),1500)==1,"kept while the board is still");
        check(mem.apply(board(line,false),9000)==1,"still kept 8 s later while hidden (our ring stays on it)");
        check(mem.apply(board(line,false),17000)==0,"forgotten after 15 s unseen");
        // A shot (several discs moved) resets the memory: nothing is restored from the old layout.
        DiscMemory m2=new DiscMemory();for(int k=0;k<4;k++)m2.apply(board(line,true),k*150);
        GameState moved=board(line,false);for(Coin c:moved.coins){c.pos.x+=90;c.pos.y-=60;}
        check(m2.apply(moved,700)==0,"moved board: nothing restored");
        // Opponent's turn clears it.
        DiscMemory m3=new DiscMemory();for(int k=0;k<4;k++)m3.apply(board(line,true),k*150);
        GameState opp=board(line,true);opp.strikerDetected=false;m3.apply(opp,700);
        check(m3.apply(board(line,false),800)==0,"cleared by the opponent's turn");
        System.out.println("PASS: hidden disc beside the target kept on a still board (no route through it), kept through the turn, forgotten after 15 s, reset by a shot or the opponent's turn");
    }
}
