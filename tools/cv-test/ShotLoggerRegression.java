import com.bitaim.carromaim.cv.*;
import android.graphics.*;
import java.util.*;

/** Shot logger emits one record per real shot; the calibrator recovers launch speed and rail restitution. */
public class ShotLoggerRegression {
    static void check(boolean v,String m){if(!v)throw new AssertionError(m);}
    static GameState base(){
        GameState s=new GameState();s.board=new RectF(0,0,1000,1000);s.teamKnown=true;
        s.pockets.addAll(Arrays.asList(new PointF(50,50),new PointF(950,50),new PointF(50,950),new PointF(950,950)));
        s.striker=new Coin(500,850,30,Coin.COLOR_STRIKER,true);return s;
    }
    public static void main(String[] a){
        GameState s=base();s.strikerDetected=true;s.aimGuideX=0.3f;s.aimGuideY=-0.954f;s.aimGuideDots=12;
        Coin c1=new Coin(605,515,20,Coin.COLOR_BLACK,false),c2=new Coin(300,400,20,Coin.COLOR_WHITE,false);
        for(Coin c:new Coin[]{c1,c2}){c.targetEligible=true;c.transferEligible=true;s.coins.add(c);}
        double truthSpeed=1.5,truthWall=.90;
        PhysicsVerifier.Outcome o=PhysicsVerifier.run(s,s.aimGuideX,s.aimGuideY,(float)(1000*truthSpeed),truthWall,-1,-Math.log(.70),.05);
        check(o.completed,"Synthetic truth shot must settle");
        GameState after=base();after.strikerDetected=false;
        for(int k=0;k<s.coins.size();k++)if(o.active[k]){Coin c=s.coins.get(k);after.coins.add(new Coin((float)o.x[k],(float)o.y[k],c.radius,c.color,false));}
        GameState moving=base();moving.strikerDetected=false;moving.coins.add(new Coin(560,700,20,Coin.COLOR_BLACK,false));moving.coins.add(new Coin(300,400,20,Coin.COLOR_WHITE,false));
        List<String> lines=new ArrayList<>();ShotLogger log=new ShotLogger(lines::add);
        for(long t=0;t<=300;t+=100)log.feed(s,t);                  // aiming at 10 fps
        log.feed(moving,400);                                       // release: striker left the baseline
        log.feed(moving,1000);log.feed(after,1700);log.feed(after,2000);log.feed(after,2400); // settle
        log.feed(after,3000);
        check(lines.size()==1,"Exactly one record per shot, got "+lines.size());
        GameState noShot=base();noShot.strikerDetected=true;noShot.aimGuideX=0;noShot.aimGuideY=-1;noShot.aimGuideDots=10;noShot.coins.addAll(s.coins);
        log.feed(noShot,5000);GameState idle=base();idle.strikerDetected=true;idle.coins.addAll(s.coins);log.feed(idle,8000);
        check(lines.size()==1,"Aim dropped without release must not create a record");
        // Finger occlusion: striker vanishes for one frame, then is back in place and aimed -> keep aiming.
        GameState hidden=base();hidden.strikerDetected=false;hidden.coins.addAll(s.coins);
        for(long t=10000;t<=10300;t+=100)log.feed(s,t);log.feed(hidden,10400);for(long t=10500;t<=10800;t+=100)log.feed(s,t);
        log.feed(s,11000);log.feed(s,12000);log.feed(s,13000);
        check(lines.size()==1,"Finger occlusion must not log an unchanged board");
        // Pull-back guide (pointing down the board) is stored as the real launch direction.
        GameState pull=base();pull.strikerDetected=true;pull.aimGuideX=-0.3f;pull.aimGuideY=0.954f;pull.aimGuideDots=12;pull.coins.addAll(s.coins);
        for(long t=20000;t<=20300;t+=100)log.feed(pull,t);log.feed(moving,20400);log.feed(moving,21000);log.feed(after,21700);log.feed(after,22000);log.feed(after,22400);
        check(lines.size()==2,"Shot aimed with the pull-back line is logged");
        check(ShotLogCalibrator.parse(lines.get(1)).uy>0,"A downward guide is stored as recorded (bank shot)");
        // Auto shot debug capture: "before" image requested at the shot, route record + "after" image at settle.
        for(long t=30000;t<=30300;t+=100)log.feed(s,t);
        log.markAutoShot(.2f,.3f,-.954f,"{\"label\":\"Direct route\",\"kind\":0}");log.markTarget(605,515,950,50);
        String beforeImg=log.consumeSaveRequest();check(beforeImg!=null&&beforeImg.endsWith("_before"),"Auto shot requests a before image");
        log.feed(moving,30400);log.feed(moving,31000);log.feed(after,31700);log.feed(after,32000);log.feed(after,32400);
        check(lines.get(2).contains("\"target\":\""),"Auto shot record says what happened to the target coin");
        check(lines.size()==3&&lines.get(2).contains("\"autoRoute\":{\"label\":\"Direct route\"")&&lines.get(2).contains("\"ownPotted\":")&&lines.get(2).contains("\"images\":\"auto-")&&lines.get(2).contains("\"result\":{\"clean\":"),"Auto shot record carries route, own-potted count, image id and the clean-board result");
        String afterImg=log.consumeSaveRequest();check(afterImg!=null&&afterImg.endsWith("_after")&&afterImg.startsWith(beforeImg.replace("_before","")),"After image shares the shot id");
        check(log.consumeSaveRequest()==null,"Image requests are consumed once");
        log.consumeIncident();
        // Manual shot that follows BitAim's line exactly but leaves the suggested target in place -> incident.
        GameState missFrame=base();missFrame.strikerDetected=false;
        for(Coin c:s.coins)missFrame.coins.add(new Coin(c.pos.x,c.pos.y,c.radius,c.color,false));
        missFrame.coins.get(1).pos.x+=60; // something moved, but not the target at (605,515)
        log.setSuggestion(.3f,-.954f,605,515,950,50,"Direct route");
        for(long t=40000;t<=40300;t+=100)log.feed(s,t);
        log.feed(moving,40400);log.feed(moving,41000);log.feed(missFrame,41700);log.feed(missFrame,42000);log.feed(missFrame,42400);
        check(lines.size()==4&&lines.get(3).contains("\"suggestion\":{\"label\":\"Direct route\"")&&lines.get(3).contains("\"targetFate\":\"not-moved\""),"Manual record compares the shot with the suggestion");
        check("followed-line-not-moved".equals(log.consumeIncident()),"Following the line without potting the target raises an incident");
        check(log.consumeNewManualRecord(),"Manual record is flagged for debug pictures");
        // 4.10.9: hand-played shot with a motion track. The own black coin goes into the top-left pocket, the white
        // coin is knocked across the board (moved, not potted) and the striker comes to rest mid-board, where it is
        // detected as a black disc (live 16:51:58). Then the same shot with the striker gone (pocketed = foul).
        for(boolean strikerRests:new boolean[]{true,false}){
            GameState aim=base();aim.strikerDetected=true;aim.aimGuideX=-.6f;aim.aimGuideY=-.8f;aim.aimGuideDots=12;
            Coin own=new Coin(300,300,20,Coin.COLOR_BLACK,false);own.targetEligible=true;Coin opp=new Coin(700,500,20,Coin.COLOR_WHITE,false);
            aim.coins.add(own);aim.coins.add(opp);
            GameState[] motion=new GameState[3];float[][] ownAt={{220,220},{120,120},null},oppAt={{720,440},{750,370},{780,300}};
            for(int k=0;k<3;k++){motion[k]=base();motion[k].strikerDetected=false;
                if(ownAt[k]!=null)motion[k].coins.add(new Coin(ownAt[k][0],ownAt[k][1],20,Coin.COLOR_BLACK,false));
                motion[k].coins.add(new Coin(oppAt[k][0],oppAt[k][1],20,Coin.COLOR_WHITE,false));
                float[] sp=strikerRests?new float[]{500-60*k,700-60*k}:new float[]{600+150*k,880+30*k};
                motion[k].coins.add(new Coin(sp[0],sp[1],20,Coin.COLOR_BLACK,false));} // the moving striker, seen as a disc
            GameState rest=base();rest.strikerDetected=false;rest.coins.add(new Coin(785,290,20,Coin.COLOR_WHITE,false));
            if(strikerRests)rest.coins.add(new Coin(380,580,20,Coin.COLOR_BLACK,false));
            long t0=strikerRests?100000:200000;int n=lines.size();log.consumeIncident();
            log.setSuggestion(-.6f,-.8f,300,300,50,50,"Direct route");
            for(long t=t0;t<=t0+300;t+=100)log.feed(aim,t);
            log.feed(motion[0],t0+400);log.feed(motion[1],t0+600);log.feed(motion[2],t0+900);
            log.feed(rest,t0+1700);log.feed(rest,t0+2000);log.feed(rest,t0+2400);
            check(lines.size()==n+1,"Hand-played shot gives one record");
            String r=lines.get(n);r=r.substring(r.indexOf("\"result\":"));
            check(r.contains("\"own\":1,")&&r.contains("\"other\":0,"),"Own coin potted, knocked white coin counted as moved: "+r);
            check(r.contains("[300.0,300.0,"+Coin.COLOR_BLACK+",1,0,0]"),"Potted coin traced to the top-left pocket: "+r);
            if(strikerRests){
                check(r.contains("\"striker\":\"resting\"")&&r.contains("\"strikerRest\":[380.0,580.0]")&&r.contains("\"discsAfter\":1,")&&r.contains("\"discsAfterRaw\":2"),"Resting striker found and left out of the disc count: "+r);
            }else{
                check(r.contains("\"striker\":\"pocketed\"")&&r.contains("\"discsAfter\":1,"),"Striker followed into the bottom-right pocket is reported: "+r);
                check("striker-pocketed".equals(log.consumeIncident()),"Striker pocketed (foul) raises an incident with pictures");
            }
        }
        // 4.11.0 (live 19:27:18): auto shot whose hold showed no aim dots is still recorded at release.
        {GameState plain=base();plain.strikerDetected=true;plain.coins.addAll(s.coins);
         int n=lines.size();
         for(long t=300000;t<=300300;t+=100)log.feed(plain,t);
         log.markAutoShot(.2f,.3f,-.954f,"{\"label\":\"Open paths\",\"kind\":5}");log.consumeSaveRequest();
         for(long t=300400;t<=301000;t+=150)log.feed(plain,t);   // held pull: striker on the baseline, no dots
         log.feed(moving,301100);log.feed(moving,301700);log.feed(after,302400);log.feed(after,302800);log.feed(after,303200);
         check(lines.size()==n+1&&lines.get(n).contains("\"label\":\"Open paths\""),"auto shot without aim dots is recorded");
         log.consumeSaveRequest();log.consumeIncident();
         // Auto marker never released (gesture lost): a later hand shot is not written under the stale auto route.
         log.markAutoShot(.2f,.3f,-.954f,"{\"label\":\"Stale route\",\"kind\":0}");log.consumeSaveRequest();
         log.feed(null,310000);                                   // board hidden (chat keyboard) for a long time
         for(long t=330000;t<=330300;t+=100)log.feed(s,t);       // the player's own aimed shot, 20 s later
         log.feed(moving,330400);log.feed(moving,331000);log.feed(missFrame,331700);log.feed(missFrame,332000);log.feed(missFrame,332400);
         check(lines.size()==n+2&&!lines.get(n+1).contains("Stale route"),"stale auto marker dropped before the next hand shot");
         log.consumeSaveRequest();log.consumeIncident();log.consumeNewManualRecord();}
        ShotLogCalibrator.Shot shot=ShotLogCalibrator.parse(lines.get(0));
        check(shot.before.coins.size()==2&&Math.abs(shot.ux-0.3)<1e-3,"Record round-trips board, discs and aim");
        check(shot.realPotted==2-after.coins.size(),"Potted count recorded");
        ShotLogCalibrator.Fit f=ShotLogCalibrator.fit(Collections.singletonList(shot));
        check(Math.abs(f.speeds[0]-truthSpeed)<=.21,"Calibrator recovers launch speed ("+f.speeds[0]+" vs "+truthSpeed+")");
        System.out.printf(Locale.US,"PASS: one record per shot, no record without release, finger occlusion ignored, downward guide kept, auto debug pictures/route record, followed-line incident, hand-shot track (potted vs moved, resting striker, striker-pocketed foul), dot-less auto shot recorded, stale auto marker dropped, round-trip parse, speed %.1f (truth %.1f), rail %.2f, potted %d%n",f.speeds[0],truthSpeed,f.wall,shot.realPotted);
    }
}
