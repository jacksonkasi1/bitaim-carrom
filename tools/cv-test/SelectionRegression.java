import com.bitaim.carromaim.cv.*;
import android.graphics.*;
import java.util.*;

public class SelectionRegression {
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static GameState state(){
        GameState s=new GameState();s.teamKnown=s.strikerDetected=true;s.board=new RectF(0,0,1000,1000);
        s.striker=new Coin(500,850,30,Coin.COLOR_STRIKER,true);
        s.pockets.addAll(Arrays.asList(new PointF(50,50),new PointF(950,50),new PointF(50,950),new PointF(950,950)));return s;
    }
    static Coin add(GameState s,float x,float y,boolean own){Coin c=new Coin(x,y,20,Coin.COLOR_WHITE,false);c.targetEligible=own;s.coins.add(c);return c;}
    public static void main(String[] args){
        GameState gallery=state();gallery.striker.pos.x=545.7f;gallery.striker.pos.y=1573.2f;
        Coin black=add(gallery,432.9f,1221.3f,false);black.color=Coin.COLOR_BLACK;
        Coin oldBlack=new Coin(432.9f,1221.3f,20,Coin.COLOR_BLACK,false);oldBlack.targetEligible=true;
        ShotTracker tracker=new ShotTracker();tracker.lock(oldBlack);
        check(tracker.update(gallery)==null,"Invalid old black lock must not produce a route");
        check(tracker.selectedTarget()==null&&!tracker.isLocked(),"Current ineligible black must clear the stale eligible clone and manual lock");

        GameState queenState=state();Coin last=add(queenState,700,500,true),queen=add(queenState,300,500,false);
        queen.isQueen=true;queen.color=Coin.COLOR_RED;
        tracker.lock(last);tracker.update(queenState);
        queenState.queenRequired=QueenRule.apply(queenState);
        check(queenState.queenRequired,"Fixture must activate last-own queen priority");
        tracker.update(queenState);
        check(!tracker.isLocked()&&tracker.selectedTarget()!=last,"Queen priority must release the now-ineligible last-own lock");
        ShotPlanner.Shot queenShot=tracker.update(queenState);
        check(queenShot!=null&&queenShot.coin==queen,"Released manual lock must allow the required queen on the next update");

        GameState transientState=state();Coin own=add(transientState,300,500,true);
        tracker.lock(own);check(tracker.update(transientState)!=null,"Manual-lock fixture needs a route");
        transientState.coins.clear();
        check(tracker.update(transientState)==null&&tracker.isLocked()&&tracker.selectedTarget()==null,"Missing detection must hide the stale highlight while retaining manual intent");
        Coin recovered=add(transientState,300.5f,500,true);
        ShotPlanner.Shot recoveredShot=tracker.update(transientState);
        check(recoveredShot!=null&&recoveredShot.coin==recovered&&tracker.selectedTarget()==recovered,"Eligible target must recover its manual lock after transient detection loss");
        ShotPlanner.Shot cycled=tracker.next(transientState);
        check(cycled!=null&&cycled.coin==recovered,"Route cycling must stay on the current manually selected target");

        // A current eligible target stays selected while an invalid route is hidden.
        transientState.striker.pos.x=recovered.pos.x;transientState.striker.pos.y=recovered.pos.y;
        check(tracker.update(transientState)==null&&tracker.isLocked()&&tracker.selectedTarget()==recovered,"Geometry uncertainty must preserve a current eligible manual target");
        System.out.println("PASS: stale black selection removal, queen transition, hidden missing detection, manual recovery and same-target cycling");
    }
}
