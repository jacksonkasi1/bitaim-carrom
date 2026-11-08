package com.bitaim.carromaim.overlay;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import com.bitaim.carromaim.cv.AimCorrection;
import com.bitaim.carromaim.cv.AutoPlayController;

/** Optional gesture control. User enables this service in Android Settings. */
public final class GameGestureService extends AccessibilityService {
    public interface Completion {void accept(boolean success);}
    public static volatile GameGestureService INSTANCE;
    @Override protected void onServiceConnected(){INSTANCE=this;}
    @Override public void onAccessibilityEvent(AccessibilityEvent event){}
    @Override public void onInterrupt(){disarm();}
    @Override public void onDestroy(){INSTANCE=null;disarm();super.onDestroy();}
    private void disarm(){FloatingOverlayService s=FloatingOverlayService.INSTANCE;if(s!=null)s.disableAutoPlay();}
    /** Latest aim dots the detector saw: {gx, gy, dots} and the uptime they were detected at, or null. */
    /** guide(): {gx, gy, dots, strikerX, strikerY} from one frame (striker NaN when unknown). */
    public interface AimSource {float[] guide();long guideAt();}
    /** Result of a corrected shot: first and last measured error (degrees, NaN if unread), steps, final pull end. */
    public static final class AimResult {public float initialDeg=Float.NaN,finalDeg=Float.NaN,x2,y2;public int steps,dots;}
    public interface CorrectedCompletion {void accept(boolean success,AimResult result);}
    private static final long HOLD_MS=650,STEP_MS=120,FIRST_FRAME_MS=250,READ_TIMEOUT_MS=700;

    /** Shot with closed-loop aim (Android 8+): drag to the pull point and hold, read the game's aim dots on the next
     *  one or two frames, rotate the pull by the error (at most twice), then release. Falls back to the single drag
     *  on older Android; releases at once when no aim dots are read. */
    public boolean sendCorrected(AutoPlayController.Action action,AimSource source,CorrectedCompletion completion){
        if(Build.VERSION.SDK_INT<26||source==null||action.aimBudgetMs<=0||action.move){
            AimResult plain=new AimResult();plain.x2=action.x2;plain.y2=action.y2;
            return send(action,success->completion.accept(success,plain));
        }
        if(!gameWindowContains(action))return false;
        return new HeldShot(action,source,completion).start();
    }
    private boolean gameWindowContains(AutoPlayController.Action action){
        if(Build.VERSION.SDK_INT<24)return false;
        AccessibilityNodeInfo root=getRootInActiveWindow();
        if(root==null)return false;
        Rect bounds=new Rect();root.getBoundsInScreen(bounds);
        boolean game="com.miniclip.carrom".contentEquals(root.getPackageName()==null?"":root.getPackageName());root.recycle();
        return game&&bounds.contains((int)action.x1,(int)action.y1)&&bounds.contains((int)action.x2,(int)action.y2);
    }
    @androidx.annotation.RequiresApi(26)
    private final class HeldShot {
        final AutoPlayController.Action action;final AimSource source;final CorrectedCompletion completion;
        final android.os.Handler handler=new android.os.Handler(android.os.Looper.getMainLooper());
        final AimResult result=new AimResult();
        final float ux,uy; // intended launch direction = opposite of the pull
        GestureDescription.StrokeDescription stroke;float endX,endY,prevX=Float.NaN,prevY=Float.NaN,prevError=Float.NaN,gain=1f;long startedAt,heldAt;boolean finished,reverting;
        HeldShot(AutoPlayController.Action a,AimSource s,CorrectedCompletion c){
            action=a;source=s;completion=c;endX=a.x2;endY=a.y2;
            float dx=a.x1-a.x2,dy=a.y1-a.y2,len=(float)Math.max(1e-3,Math.hypot(dx,dy));ux=dx/len;uy=dy/len;
        }
        boolean start(){
            startedAt=android.os.SystemClock.uptimeMillis();
            Path path=new Path();path.moveTo(action.x1,action.y1);path.lineTo(endX,endY);
            stroke=new GestureDescription.StrokeDescription(path,0,HOLD_MS,true);
            return dispatch(stroke,this::onHeld);
        }
        boolean dispatch(GestureDescription.StrokeDescription s,Runnable next){
            return dispatchGesture(new GestureDescription.Builder().addStroke(s).build(),new GestureResultCallback(){
                @Override public void onCompleted(GestureDescription d){next.run();}
                @Override public void onCancelled(GestureDescription d){finish(false);}
            },null);
        }
        void onHeld(){heldAt=android.os.SystemClock.uptimeMillis();readGuide(new float[]{0,0,0,0},new int[]{0,0},-1);}
        /** Intended launch direction, fixed once from the striker averaged over the first readings (v4.8.2: re-deriving
         *  it every frame from a striker that jitters ±6 px under its glow ring made steps chase noise). */
        float fixUx=Float.NaN,fixUy=Float.NaN;
        /** Average guide direction (sum[0..1]) and striker position (sum[2..3]) over up to two fresh frames. */
        void readGuide(float[] sum,int[] count,long lastAt){
            if(finished)return;
            long now=android.os.SystemClock.uptimeMillis();
            float[] g=source.guide();long at=source.guideAt();
            if(g!=null&&at>=heldAt+FIRST_FRAME_MS&&at!=lastAt&&g[2]>=AimCorrection.MIN_DOTS){
                sum[0]+=g[0];sum[1]+=g[1];count[0]++;result.dots=(int)g[2];lastAt=at;
                if(g.length>=5&&!Float.isNaN(g[3])&&Math.hypot(g[3]-action.x1,g[4]-action.y1)<40){sum[2]+=g[3];sum[3]+=g[4];count[1]++;}
            }
            boolean timedOut=now-heldAt>READ_TIMEOUT_MS||now-startedAt>action.aimBudgetMs;
            if(count[0]>=2||(timedOut&&count[0]>0)){
                if(Float.isNaN(fixUx)){
                    fixUx=ux;fixUy=uy;
                    // The striker settled away from where the shot was planned (live 00:44: 6 px, a 2.6° miss): aim
                    // from its averaged position at the planned contact point.
                    if(count[1]>0&&!Float.isNaN(action.aimX)){
                        float sx=sum[2]/count[1],sy=sum[3]/count[1];
                        if(Math.hypot(sx-action.x1,sy-action.y1)>3){
                            float ax=action.aimX-sx,ay=action.aimY-sy,al=(float)Math.hypot(ax,ay);
                            if(al>60){fixUx=ax/al;fixUy=ay/al;}
                        }
                    }
                }
                float gl=(float)Math.hypot(sum[0],sum[1]);
                decide(gl<1e-3f?Float.NaN:AimCorrection.errorDegrees(fixUx,fixUy,sum[0]/gl,sum[1]/gl));
                return;
            }
            if(timedOut){release();return;} // no aim dots: shoot as dragged
            final long seen=lastAt;handler.postDelayed(()->readGuide(sum,count,seen),40);
        }
        void decide(float error){
            if(Float.isNaN(result.initialDeg))result.initialDeg=error;
            // Practice 23:12:18 (downward bank shot): two steps took 0.68° to 3.63°. A step that makes the aim worse
            // is undone (back to the previous pull) and the shot is released there.
            if(!Float.isNaN(prevError)&&Math.abs(error)>Math.abs(prevError)+.2f){
                result.finalDeg=prevError;
                Path back=new Path();back.moveTo(endX,endY);back.lineTo(prevX,prevY);endX=prevX;endY=prevY;
                stroke=stroke.continueStroke(back,0,STEP_MS,true);
                if(!dispatch(stroke,this::release))finish(false);
                return;
            }
            result.finalDeg=error;
            // A step is made only if it can be dispatched within the budget; the re-read is cut short by the budget.
            boolean budget=android.os.SystemClock.uptimeMillis()-startedAt+STEP_MS<action.aimBudgetMs;
            // 4.9.2 (live 09:13:26): a downward shot's dots run over the bottom cushion; with only 8 dots read the
            // "correction" turned a planned line 3-4 degrees off. Downward shots need at least 12 dots to be corrected.
            boolean weakDownward=uy>.2f&&result.dots<12;
            if(!budget||weakDownward||!AimCorrection.shouldCorrect(error,result.dots,result.steps)){release();return;}
            gain=AimCorrection.nextGain(prevError,error,gain);
            prevX=endX;prevY=endY;prevError=error;
            float[] p=AimCorrection.rotatePull(action.x1,action.y1,endX,endY,error*gain);
            Path path=new Path();path.moveTo(endX,endY);path.lineTo(p[0],p[1]);
            endX=p[0];endY=p[1];result.steps++;
            // The launch direction now intended by the corrected pull is unchanged (the plan); the error is re-read.
            stroke=stroke.continueStroke(path,0,STEP_MS,true);
            if(!dispatch(stroke,this::onHeld))finish(false);
        }
        void release(){
            // Lift without changing the angle: a one-pixel step further along the pull direction.
            Path path=new Path();path.moveTo(endX,endY);path.lineTo(endX-ux,endY-uy);
            stroke=stroke.continueStroke(path,0,40,false);
            if(!dispatch(stroke,()->finish(true)))finish(false);
        }
        void finish(boolean ok){
            if(finished)return;finished=true;handler.removeCallbacksAndMessages(null);
            result.x2=endX;result.y2=endY;completion.accept(ok,result);
        }
    }
    private static volatile long frontCheckedAt;private static volatile boolean frontIsGame=true;
    /** True while Carrom Pool is the active window (checked at most twice a second). Unknown (service off) = true.
     *  Live 2026-11-08 00:17: with the photo gallery open, carrom thumbnails were taken for a board, auto play kept
     *  planning and the debug recorder saved a picture of the user's gallery. Nothing runs outside the game now. */
    public static boolean gameInFront(){
        GameGestureService s=INSTANCE;if(s==null||Build.VERSION.SDK_INT<16)return true;
        long now=android.os.SystemClock.uptimeMillis();
        if(now-frontCheckedAt<500)return frontIsGame;
        frontCheckedAt=now;
        try{
            AccessibilityNodeInfo root=s.getRootInActiveWindow();
            if(root==null)return frontIsGame; // transient (animation); keep the last answer
            CharSequence pkg=root.getPackageName();root.recycle();
            frontIsGame=pkg!=null&&("com.miniclip.carrom".contentEquals(pkg)||"com.bitaim.carromaim".contentEquals(pkg)&&frontIsGame);
        }catch(RuntimeException e){/* keep the last answer */}
        return frontIsGame;
    }
    public boolean send(AutoPlayController.Action action,Completion completion){
        if(Build.VERSION.SDK_INT<24)return false;
        return sendOnSupportedAndroid(action,completion);
    }
    @androidx.annotation.RequiresApi(24)
    private boolean sendOnSupportedAndroid(AutoPlayController.Action action,Completion completion){
        AccessibilityNodeInfo root=getRootInActiveWindow();
        if(root==null)return false;
        Rect bounds=new Rect();root.getBoundsInScreen(bounds);
        boolean game="com.miniclip.carrom".contentEquals(root.getPackageName()==null?"":root.getPackageName());root.recycle();
        if(!game||!bounds.contains((int)action.x1,(int)action.y1)||!bounds.contains((int)action.x2,(int)action.y2))return false;
        Path path=new Path();path.moveTo(action.x1,action.y1);path.lineTo(action.x2,action.y2);
        GestureDescription gesture=new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(path,0,action.move?1000:650)).build();
        return dispatchGesture(gesture,new GestureResultCallback(){
            @Override public void onCompleted(GestureDescription description){completion.accept(true);}
            @Override public void onCancelled(GestureDescription description){completion.accept(false);}
        },null);
    }
}
