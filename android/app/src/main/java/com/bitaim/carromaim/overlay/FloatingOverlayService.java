package com.bitaim.carromaim.overlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.FrameLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.view.ContextThemeWrapper;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.bitaim.carromaim.MainActivity;
import com.bitaim.carromaim.R;
import com.bitaim.carromaim.capture.ScreenCaptureService;
import com.bitaim.carromaim.cv.GameState;
import com.bitaim.carromaim.cv.AutoPlayController;
import com.bitaim.carromaim.cv.Coin;
import com.bitaim.carromaim.cv.TargetColourLock;

/**
 * FloatingOverlayService  v3.1
 * Added: setSnapMode() forwarded to AimOverlayView.
 */
public class FloatingOverlayService extends Service {

    private static final String CHANNEL_ID = "bitaim_channel";
    private static final int    NOTIF_ID   = 1001;
    public static volatile FloatingOverlayService INSTANCE;

    private WindowManager windowManager;
    private View floatingBtnView;
    private AimOverlayView aimOverlayView;
    private TextView colourLockLabel;
    private PopupMenu colourMenu;
    private GameState latestState;
    private WindowManager.LayoutParams floatingBtnParams, overlayParams;

    private float touchStartX, touchStartY;
    private int   viewStartX, viewStartY;
    // Floating button cycles: OFF -> AUTO (best shot shown automatically,
    // touches go to the game) -> AIM (tap a coin yourself) -> OFF
    private static final int MODE_OFF = 0, MODE_AUTO = 1, MODE_AIM = 2;
    private int overlayMode = MODE_AUTO;
    private final AutoPlayController autoPlay=new AutoPlayController();
    private String lastAutoStatus="";
    public boolean isAutoPlayEnabled(){return autoPlay.enabled();}
    public void disableAutoPlay(){setAutoPlay(false);}
    public void setAutoPlay(boolean enabled){runOnMain(()->{
        if(enabled){overlayMode=MODE_AUTO;applyOverlayMode();}
        autoPlay.setEnabled(enabled);if(aimOverlayView!=null)aimOverlayView.setAutoPlayArmed(enabled);
        updateColourLockLabel();
        showStopButton(enabled);
    });}

    // ── Incident watch: no route on our turn, auto play stuck, app errors ─────────────────────
    private long localTurnSince=-1,noRouteSince=-1,lastShotActionAt=-1;
    private boolean noRouteReported,stallReported;
    private void reportIncident(String type,String details){
        if(!com.bitaim.carromaim.capture.DebugMode.enabled(this))return;
        ScreenCaptureService capture=ScreenCaptureService.INSTANCE;
        if(capture!=null)capture.requestIncident(type,details);
        appendEvent("INCIDENT "+type+(details==null?"":" "+details));
    }
    private void watchIncidents(GameState state,com.bitaim.carromaim.cv.ShotPlanner.Shot route){
        long now=android.os.SystemClock.uptimeMillis();
        ScreenCaptureService capture=ScreenCaptureService.INSTANCE;
        boolean local=state!=null&&state.board!=null&&state.strikerDetected&&state.teamKnown;
        if(!local){localTurnSince=-1;noRouteSince=-1;noRouteReported=stallReported=false;if(capture!=null)capture.clearSuggestion();return;}
        if(localTurnSince<0)localTurnSince=now;
        if(route!=null&&route.strikerPath.size()>=2&&capture!=null){
            float dx=route.strikerPath.get(1).x-state.striker.pos.x,dy=route.strikerPath.get(1).y-state.striker.pos.y,l=(float)Math.hypot(dx,dy);
            if(l>0)capture.setSuggestion(dx/l,dy/l,route.coin.pos.x,route.coin.pos.y,route.pocket==null?0f:route.pocket.x,route.pocket==null?0f:route.pocket.y,com.bitaim.carromaim.cv.RoutePlanner.label(route));
        }else if(capture!=null)capture.clearSuggestion();
        if(route==null){
            if(noRouteSince<0)noRouteSince=now;
            if(!noRouteReported&&now-noRouteSince>4000){noRouteReported=true;
                reportIncident("no-route",String.format(java.util.Locale.US,"{\"coins\":%d,\"own\":%d,\"striker\":[%.0f,%.0f],\"colourKey\":%d,\"queenRequired\":%b}",
                        state.coins.size(),countOwn(state),state.striker.pos.x,state.striker.pos.y,state.targetColourKey,state.queenRequired));}
        }else noRouteSince=-1;
        if(autoPlay.enabled()&&!stallReported&&now-localTurnSince>10000&&(lastShotActionAt<localTurnSince)){stallReported=true;
            reportIncident("auto-stall","{\"status\":\""+autoPlay.status().replace("\"","'")+"\",\"turnMs\":"+(now-localTurnSince)+"}");}
    }
    private static int countOwn(GameState s){int n=0;for(Coin c:s.coins)if(c.targetEligible)n++;return n;}
    /** Called by the overlay when drawing or planning throws. */
    public void reportAppError(String where,Throwable t){
        reportIncident("app-error","{\"where\":\""+where+"\",\"error\":\""+String.valueOf(t).replace("\"","'").replace("\n"," ")+"\"}");
    }

    /** Everything the debug record needs about the fired route, as a JSON object. */
    private String routeJson(com.bitaim.carromaim.cv.ShotPlanner.Shot r,GameState state,float pullPx){
        java.util.Locale L=java.util.Locale.US;StringBuilder b=new StringBuilder("{");
        b.append("\"sweep\":").append(r.sweep).append(',');
        b.append(String.format(L,"\"label\":\"%s\",\"kind\":%d,\"wall\":%d,",com.bitaim.carromaim.cv.RoutePlanner.label(r).replace("\"","'"),r.kind,r.wall));
        b.append("\"chance\":").append(Float.isNaN(r.chancePercent)?"null":String.valueOf(Math.round(r.chancePercent)));
        b.append(String.format(L,",\"stress\":\"%d/%d\",\"tolerance\":%s,\"travel\":%.3f,\"plannedSpeed\":%.3f,\"minPotSpeed\":%s,",
                r.robustnessPassed,r.robustnessTrials,Float.isNaN(r.aimMarginDegrees)?"null":String.valueOf(r.aimMarginDegrees),
                AutoPlayController.travel(r,state.board.width()),r.assumedLaunchSpeed/state.board.width(),
                Float.isNaN(r.minPotSpeed)?"null":String.format(L,"%.3f",r.minPotSpeed/state.board.width())));
        b.append(String.format(L,"\"target\":[%.1f,%.1f],\"pocket\":[%.1f,%.1f],\"ghost\":[%.1f,%.1f],",r.coin.pos.x,r.coin.pos.y,
                r.pocket==null?0f:r.pocket.x,r.pocket==null?0f:r.pocket.y,r.ghost==null?0f:r.ghost.x,r.ghost==null?0f:r.ghost.y));
        b.append("\"strikerPath\":[");for(int i=0;i<r.strikerPath.size();i++){if(i>0)b.append(',');b.append(String.format(L,"[%.1f,%.1f]",r.strikerPath.get(i).x,r.strikerPath.get(i).y));}b.append("],");
        b.append("\"chain\":[");for(int i=0;i<r.chain.size();i++){if(i>0)b.append(',');Coin c=r.chain.get(i);android.graphics.PointF e=i<r.coinEndpoints.size()?r.coinEndpoints.get(i):c.pos;
            b.append(String.format(L,"{\"from\":[%.1f,%.1f],\"to\":[%.1f,%.1f],\"own\":%b}",c.pos.x,c.pos.y,e.x,e.y,c.targetEligible));}b.append("],");
        b.append("\"targetBounce\":").append(r.targetBounce==null?"null":String.format(L,"[%.1f,%.1f]",r.targetBounce.x,r.targetBounce.y)).append(',');
        float[] sl=autoPlay.lastSlide(),g=autoPlay.lastGesture();
        b.append("\"slide\":").append(Float.isNaN(sl[0])?"null":String.format(L,"{\"fromX\":%.1f,\"toX\":%.1f,\"y\":%.1f}",sl[0],sl[1],sl[2])).append(',');
        b.append("\"gesture\":").append(Float.isNaN(g[0])?"null":String.format(L,"{\"from\":[%.1f,%.1f],\"to\":[%.1f,%.1f],\"pullPx\":%.1f,\"durationMs\":650}",g[0],g[1],g[2],g[3],pullPx)).append(',');
        float gx=aimOverlayView==null?Float.NaN:aimOverlayView.currentGoldX();
        b.append("\"goldX\":").append(Float.isNaN(gx)?"null":String.format(L,"%.1f",gx)).append(',');
        b.append("\"alternatives\":[");
        java.util.List<com.bitaim.carromaim.cv.ShotPlanner.Shot> alts=aimOverlayView==null?new java.util.ArrayList<>():aimOverlayView.currentAlternatives();
        for(int i=0;i<Math.min(3,alts.size());i++){com.bitaim.carromaim.cv.ShotPlanner.Shot a=alts.get(i);if(i>0)b.append(',');
            b.append(String.format(L,"{\"label\":\"%s\",\"chance\":%s,\"target\":[%.1f,%.1f]}",com.bitaim.carromaim.cv.RoutePlanner.label(a).replace("\"","'"),
                    Float.isNaN(a.chancePercent)?"null":String.valueOf(Math.round(a.chancePercent)),a.coin.pos.x,a.coin.pos.y));}
        b.append("],");
        b.append(String.format(L,"\"pullSetting\":%.3f,\"proStyle\":%b,\"colourKey\":%d}",len2pull(pullPx,state),com.bitaim.carromaim.cv.RoutePlanner.proStyle,state.targetColourKey));
        return b.toString();
    }
    private static float len2pull(float pullPx,GameState s){return s.board==null?0f:pullPx/s.board.width();}
    /** Auto-play timeline: one timestamped line per step, local file debug/events.log (rotated at 2 MB). */
    private void appendEvent(String line){
        if(!com.bitaim.carromaim.capture.DebugMode.enabled(this))return;
        try{
            java.io.File dir=com.bitaim.carromaim.capture.MatchFolder.dir(this);if(dir==null)return;
            java.io.File file=new java.io.File(dir,"events.log");
            if(file.length()>2_000_000L){java.io.File old=new java.io.File(dir,"events-old.log");old.delete();file.renameTo(old);}
            String ts=new java.text.SimpleDateFormat("HH:mm:ss.SSS",java.util.Locale.US).format(new java.util.Date());
            try(java.io.FileWriter w=new java.io.FileWriter(file,true)){w.write(ts+" "+line+"\n");}
        }catch(Throwable ignored){}
    }

    // ── Red STOP AUTO button: its own small overlay window next to the star ─────────────
    private TextView stopButton;
    private WindowManager.LayoutParams stopParams;
    private void showStopButton(boolean show){
        if(windowManager==null)return;
        if(!show){
            if(stopButton!=null){try{windowManager.removeView(stopButton);}catch(Exception ignored){}stopButton=null;}
            return;
        }
        if(stopButton!=null){positionStopButton();return;}
        float density=getResources().getDisplayMetrics().density;
        stopButton=new TextView(this);
        stopButton.setText("■ STOP AUTO");
        stopButton.setTextColor(android.graphics.Color.WHITE);
        stopButton.setTextSize(12);
        stopButton.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        int padH=Math.round(12*density),padV=Math.round(8*density);
        stopButton.setPadding(padH,padV,padH,padV);
        android.graphics.drawable.GradientDrawable bg=new android.graphics.drawable.GradientDrawable();
        bg.setColor(android.graphics.Color.parseColor("#E6D32F2F"));bg.setCornerRadius(18*density);
        bg.setStroke(Math.round(1.5f*density),android.graphics.Color.WHITE);
        stopButton.setBackground(bg);
        stopButton.setContentDescription("Stop auto play");
        stopButton.setOnClickListener(v->{
            setAutoPlay(false);
            try{android.widget.Toast.makeText(this,"■ Auto play stopped",android.widget.Toast.LENGTH_SHORT).show();}catch(Exception ignored){}
        });
        int type=Build.VERSION.SDK_INT>=Build.VERSION_CODES.O?WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY:WindowManager.LayoutParams.TYPE_PHONE;
        stopParams=new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,
                type,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT);
        stopParams.gravity=Gravity.TOP|Gravity.START;
        positionStopButton();
        try{windowManager.addView(stopButton,stopParams);}catch(Exception e){Log.w("BitAim/Overlay","Could not add stop button: "+e.getMessage());stopButton=null;}
    }
    /** Keep the STOP button just right of the star (or left of it near the right edge). */
    private void positionStopButton(){
        if(stopParams==null||floatingBtnParams==null||floatingBtnView==null)return;
        DisplayMetrics metrics=new DisplayMetrics();windowManager.getDefaultDisplay().getMetrics(metrics);
        float density=metrics.density;int starW=Math.max(floatingBtnView.getWidth(),Math.round(56*density));
        int gap=Math.round(6*density),estimatedW=Math.round(120*density);
        int x=floatingBtnParams.x+starW+gap;
        if(x+estimatedW>metrics.widthPixels)x=Math.max(0,floatingBtnParams.x-estimatedW-gap);
        stopParams.x=x;stopParams.y=floatingBtnParams.y+Math.round(10*density);
        if(stopButton!=null&&stopButton.getParent()!=null){try{windowManager.updateViewLayout(stopButton,stopParams);}catch(Exception ignored){}}
    }
    public void setAutoPull(float fraction){runOnMain(()->autoPlay.setPull(fraction));}

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        INSTANCE = this;
        com.bitaim.carromaim.cv.RoutePlanner.proStyle=getSharedPreferences("bitaim_game_mode",MODE_PRIVATE).getBoolean("proStyle",false);
        createNotificationChannel();
        try {
            // Android 14+ (targetSdk 34) requires the service type here as well as
            // in the manifest, otherwise the app is killed with
            // MissingForegroundServiceTypeException.
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, buildNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIF_ID, buildNotification());
            }
        } catch (Throwable t) {
            Log.e("BitAim/Overlay", "startForeground failed: " + t.getMessage());
            stopSelf();
            return;
        }
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        try {
            // Aim layer first, button second: windows added later sit on top, so
            // the floating button stays tappable while the full-screen aim layer is shown.
            setupAimOverlay();
            setupFloatingButton();
            applyOverlayMode();
            applySavedSettings();
        } catch (Throwable t) {
            // e.g. "Display over other apps" was revoked — stop cleanly, don't crash
            Log.e("BitAim/Overlay", "Could not add overlay windows: " + t.getMessage());
            stopSelf();
        }
    }

    private void setupFloatingButton() {
        floatingBtnView = LayoutInflater.from(this)
                .inflate(R.layout.view_floating_button, null);
        if(floatingBtnView instanceof FrameLayout){
            colourLockLabel=new TextView(this);
            colourLockLabel.setTextSize(9);
            colourLockLabel.setTextColor(android.graphics.Color.WHITE);
            colourLockLabel.setBackgroundColor(android.graphics.Color.parseColor("#E6111827"));
            colourLockLabel.setGravity(Gravity.CENTER);
            colourLockLabel.setMaxLines(1);
            colourLockLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
            ((FrameLayout)floatingBtnView).addView(colourLockLabel,new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,Math.round(17*getResources().getDisplayMetrics().density),Gravity.BOTTOM));
            updateColourLockLabel();
        }
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        floatingBtnParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        floatingBtnParams.gravity = Gravity.TOP | Gravity.START;
        floatingBtnParams.x = 50; floatingBtnParams.y = 300;
        floatingBtnView.setOnTouchListener(new View.OnTouchListener() {
            boolean wasDrag = false;
            long pressedAt;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        pressedAt=android.os.SystemClock.uptimeMillis();
                        touchStartX = e.getRawX(); touchStartY = e.getRawY();
                        viewStartX = floatingBtnParams.x; viewStartY = floatingBtnParams.y;
                        wasDrag = false; return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX()-touchStartX, dy = e.getRawY()-touchStartY;
                        if (Math.abs(dx)>5||Math.abs(dy)>5) wasDrag = true;
                        floatingBtnParams.x=(int)(viewStartX+dx);
                        floatingBtnParams.y=(int)(viewStartY+dy);
                        clampFloatingButton();
                        windowManager.updateViewLayout(floatingBtnView, floatingBtnParams);
                        positionStopButton();
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!wasDrag) {
                            if(android.os.SystemClock.uptimeMillis()-pressedAt>=450){
                                showColourMenu();
                            }else toggleAimOverlay();
                        }
                        return true;
                } return false;
            }
        });
        windowManager.addView(floatingBtnView, floatingBtnParams);
    }

    private void updateColourLockLabel(){
        if(colourLockLabel==null)return;
        android.content.SharedPreferences settings=getSharedPreferences("bitaim_game_mode",MODE_PRIVATE);
        int key=settings.getInt("targetColourKey",TargetColourLock.AUTO);
        String label=key==TargetColourLock.AUTO?(settings.getBoolean("allColours",false)?"All":"Auto"):TargetColourLock.label(key);
        if(com.bitaim.carromaim.cv.RoutePlanner.proStyle)label=label+" • Pro";
        if(autoPlay.enabled())label=label+" • AUTO";
        if(com.bitaim.carromaim.capture.PcLink.enabled())label=label+(com.bitaim.carromaim.capture.PcLink.connected()?" • PC":" • PC…");
        // 4.9.7: "● REC" only while a board is detected and recorded; debug mode on but idle shows nothing.
        if(com.bitaim.carromaim.capture.DebugMode.enabled(this)&&com.bitaim.carromaim.capture.ScreenCaptureService.recordingLive)label=label+" • ● REC";
        if(!android.text.TextUtils.equals(colourLockLabel.getText(),label))colourLockLabel.setText(label);
        floatingBtnView.setContentDescription("BitAim: "+label+". Hold to choose target colour.");
    }

    public void setTargetColourKey(int requestedKey){
        final int key=TargetColourLock.valid(requestedKey)?requestedKey:TargetColourLock.AUTO;
        runOnMain(()->{
        ScreenCaptureService capture=ScreenCaptureService.INSTANCE;
        if(capture!=null)capture.setTargetColourKey(key);
        else getSharedPreferences("bitaim_game_mode",MODE_PRIVATE).edit()
                .putInt("targetColourKey",key).putBoolean("allColours",false).apply();
        if(aimOverlayView!=null&&floatingBtnView!=null){overlayMode=MODE_AUTO;applyOverlayMode();}
        onDetectedState(null);
        updateColourLockLabel();
    });}

    private void selectAllColours(){
        ScreenCaptureService capture=ScreenCaptureService.INSTANCE;
        if(capture!=null)capture.setAllColours(true);
        else getSharedPreferences("bitaim_game_mode",MODE_PRIVATE).edit()
                .putInt("targetColourKey",TargetColourLock.AUTO).putBoolean("allColours",true).apply();
        overlayMode=MODE_AUTO;applyOverlayMode();
        onDetectedState(null);
        updateColourLockLabel();
    }

    private void showColourMenu(){
        final boolean autoWasOn=autoPlay.enabled();
        // v4.6.3: opening the menu no longer stops auto play; only the red STOP button (or the menu item) does.
        if(colourMenu!=null)colourMenu.dismiss();
        colourMenu=new PopupMenu(new ContextThemeWrapper(this,android.R.style.Theme_Material_Light),floatingBtnView);
        android.view.Menu menu=colourMenu.getMenu();
        int current=getSharedPreferences("bitaim_game_mode",MODE_PRIVATE).getInt("targetColourKey",TargetColourLock.AUTO);
        boolean all=getSharedPreferences("bitaim_game_mode",MODE_PRIVATE).getBoolean("allColours",false);
        menu.add(1,1,0,"Auto team").setCheckable(true).setChecked(current==TargetColourLock.AUTO&&!all);
        java.util.List<Integer> keys=new java.util.ArrayList<>();
        if(latestState!=null)for(Coin coin:latestState.coins){
            if(coin.isQueen||coin.appearanceKey==TargetColourLock.AUTO)continue;
            boolean duplicate=false;
            for(int key:keys)if(TargetColourLock.matches(key,coin.appearanceKey)){duplicate=true;break;}
            if(!duplicate)keys.add(coin.appearanceKey);
        }
        if(current!=TargetColourLock.AUTO){
            boolean present=false;for(int key:keys)if(TargetColourLock.matches(key,current)){present=true;break;}
            if(!present)keys.add(current);
        }
        for(int i=0;i<keys.size();i++){
            int key=keys.get(i);
            menu.add(1,1000+i,i+1,TargetColourLock.label(key)).setCheckable(true)
                    .setChecked(current!=TargetColourLock.AUTO&&TargetColourLock.matches(key,current));
        }
        menu.add(1,2,keys.size()+1,"All colours / Freestyle").setCheckable(true).setChecked(all);
        menu.add(2,4,keys.size()+2,"Pro style: break + rail shots").setCheckable(true).setChecked(com.bitaim.carromaim.cv.RoutePlanner.proStyle);
        menu.add(2,6,keys.size()+3,"Debug recording (shots, pictures, incidents)").setCheckable(true).setChecked(com.bitaim.carromaim.capture.DebugMode.enabled(this));
        boolean pcOn=com.bitaim.carromaim.capture.PcLink.enabled();
        menu.add(2,7,keys.size()+4,pcOn?(com.bitaim.carromaim.capture.PcLink.connected()?"PC brain: ON (connected)":"PC brain: ON (waiting for PC)"):"PC brain (USB): OFF").setCheckable(true).setChecked(pcOn);
        menu.add(0,5,0,autoWasOn?"■ Stop auto play":"▶ Auto play (drags and shoots)");
        menu.add(0,3,keys.size()+2,"Next route").setEnabled(latestState!=null&&latestState.strikerDetected);
        colourMenu.setOnMenuItemClickListener(item->{
            int id=item.getItemId();
            if(id==1)setTargetColourKey(TargetColourLock.AUTO);
            else if(id==2)selectAllColours();
            else if(id==5){
                if(autoWasOn){setAutoPlay(false);android.widget.Toast.makeText(this,"■ Auto play stopped",android.widget.Toast.LENGTH_SHORT).show();}
                else if(GameGestureService.INSTANCE==null){
                    android.widget.Toast.makeText(this,"Enable \"BitAim optional move and shoot\" in Android Accessibility first",android.widget.Toast.LENGTH_LONG).show();
                }else confirmAutoPlay();
            }
            else if(id==7){
                // PC brain on/off at any time, also mid-game: the phone switches back to its own brain instantly.
                boolean on=!com.bitaim.carromaim.capture.PcLink.enabled();
                getSharedPreferences("bitaim_game_mode",MODE_PRIVATE).edit().putBoolean("pcLink",on).apply();
                com.bitaim.carromaim.capture.PcLink.setEnabled(on);updateColourLockLabel();
                android.widget.Toast.makeText(this,on?"PC brain ON: plans on the PC over USB when the PC program runs (phone brain until then)":"PC brain OFF: the phone plans by itself",android.widget.Toast.LENGTH_SHORT).show();
            }
            else if(id==6){
                boolean on=!com.bitaim.carromaim.capture.DebugMode.enabled(this);
                com.bitaim.carromaim.capture.DebugMode.set(this,on);updateColourLockLabel();
                android.widget.Toast.makeText(this,on?"● Debug recording ON: shots, pictures and incidents are saved on this phone":"Debug recording OFF",android.widget.Toast.LENGTH_SHORT).show();
            }
            else if(id==4){
                boolean pro=!com.bitaim.carromaim.cv.RoutePlanner.proStyle;
                com.bitaim.carromaim.cv.RoutePlanner.proStyle=pro;
                getSharedPreferences("bitaim_game_mode",MODE_PRIVATE).edit().putBoolean("proStyle",pro).apply();
                onDetectedState(null); // re-plan from scratch with the new ranking
                updateColourLockLabel();
                android.widget.Toast.makeText(this,pro?"Pro style ON: pot + best next position, rail and break shots":"Safe style: easiest aim first",android.widget.Toast.LENGTH_SHORT).show();
            }
            else if(id==3){
                overlayMode=MODE_AUTO;applyOverlayMode();
                aimOverlayView.cycleRoute(route -> {
                    if(INSTANCE!=this)return;
                    try{android.widget.Toast.makeText(this,route,android.widget.Toast.LENGTH_SHORT).show();}catch(Exception ignored){}
                });
            }else if(id>=1000&&id<1000+keys.size())setTargetColourKey(keys.get(id-1000));
            return true;
        });
        try{colourMenu.show();}
        catch(android.view.WindowManager.BadTokenException e){
            Log.w("BitAim/Overlay","Could not open colour menu",e);
            android.widget.Toast.makeText(this,"Colour chooser unavailable — reopen the overlay",android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    /** Second step before auto play starts: an explicit Yes / No next to the star. */
    private void confirmAutoPlay(){
        if(floatingBtnView==null)return;
        PopupMenu confirm=new PopupMenu(new ContextThemeWrapper(this,android.R.style.Theme_Material_Light),floatingBtnView);
        confirm.getMenu().add(0,1,0,"Start auto play? Yes");
        confirm.getMenu().add(0,2,1,"No");
        confirm.setOnMenuItemClickListener(item->{
            if(item.getItemId()==1){
                setAutoPlay(true);
                android.widget.Toast.makeText(this,"▶ Auto play ON: shoots only ~70%+ chance routes. Tap the star once to stop.",android.widget.Toast.LENGTH_LONG).show();
            }
            return true;
        });
        try{confirm.show();}catch(android.view.WindowManager.BadTokenException e){Log.w("BitAim/Overlay","Could not open auto play confirmation",e);}
    }

    private void clampFloatingButton() {
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getMetrics(metrics);
        floatingBtnParams.x = Math.max(0, Math.min(floatingBtnParams.x,
                Math.max(0, metrics.widthPixels - floatingBtnView.getWidth())));
        floatingBtnParams.y = Math.max(0, Math.min(floatingBtnParams.y,
                Math.max(0, metrics.heightPixels - floatingBtnView.getHeight())));
    }

    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        if (floatingBtnView != null) {
            floatingBtnView.post(() -> {
                if (INSTANCE != this) return;
                clampFloatingButton();
                try { windowManager.updateViewLayout(floatingBtnView, floatingBtnParams); }
                catch (IllegalArgumentException e) { Log.w("BitAim/Overlay", "Button removed", e); }
            });
        }
        onDetectedState(null);
    }

    // ── PC brain link (branch pc-link) ──────────────────────────────────────────────────────────────
    private final GameGestureService.AimSource pcAimSource=new GameGestureService.AimSource(){
        @Override public float[] guide(){return com.bitaim.carromaim.capture.PcLink.guide();}
        @Override public long guideAt(){return com.bitaim.carromaim.capture.PcLink.guideAt();}
    };
    private void setupPcLink(){
        com.bitaim.carromaim.capture.PcLink.setListener(new com.bitaim.carromaim.capture.PcLink.Listener(){
            @Override public void onStatus(String text){mainHandler.post(()->{updateColourLockLabel();if(aimOverlayView!=null){aimOverlayView.setRemoteMode(com.bitaim.carromaim.capture.PcLink.connected());aimOverlayView.setAutoStatus("PC • "+text);}});}
            @Override public void onRoute(org.json.JSONObject r){
                float[] st=arr(r.optJSONArray("striker"));float[] pk=arr(r.optJSONArray("pocket"));
                org.json.JSONArray lg=r.optJSONArray("legs");float[][] legs=null;
                if(lg!=null){legs=new float[lg.length()][];for(int i=0;i<lg.length();i++)legs[i]=arr(lg.optJSONArray(i));}
                final float[][] fl=legs;String label=r.optString("label");
                mainHandler.post(()->{if(aimOverlayView!=null)aimOverlayView.setRemoteRoute(st,fl,pk,label);});
            }
            private float[] arr(org.json.JSONArray a){if(a==null)return null;float[] f=new float[a.length()];for(int i=0;i<f.length;i++)f[i]=(float)a.optDouble(i);return f;}
            @Override public void onAction(org.json.JSONObject a){
                final long id=a.optLong("id");
                AutoPlayController.Action action=AutoPlayController.Action.remote(a.optBoolean("move"),(float)a.optDouble("x1"),(float)a.optDouble("y1"),
                        (float)a.optDouble("x2"),(float)a.optDouble("y2"),a.optInt("aimBudget"),(float)a.optDouble("aimX",Double.NaN),(float)a.optDouble("aimY",Double.NaN));
                appendEvent(String.format(java.util.Locale.US,"PC %s from (%.0f,%.0f) to (%.0f,%.0f) %s",action.move?"SLIDE":"SHOT",action.x1,action.y1,action.x2,action.y2,a.optString("status")));
                // 4.10.5: our drawn route lay exactly on the game's aim dots during the hold, so the PC could not read
                // them and every PC shot released uncorrected (live 15:10, "dots 0"). Hide the lines while shooting.
                if(!action.move)mainHandler.post(()->{if(aimOverlayView!=null)aimOverlayView.hideRemoteLines();});
                mainHandler.post(()->{
                    GameGestureService gestures=GameGestureService.INSTANCE;
                    // Only while the player has auto play armed on the phone.
                    boolean sent=autoPlay.enabled()&&gestures!=null&&gestures.sendCorrected(action,pcAimSource,(success,aim)->{
                        String aimJson=aim==null?"null":String.format(java.util.Locale.US,"{\"initialDeg\":%s,\"finalDeg\":%s,\"steps\":%d,\"dots\":%d,\"x2\":%.1f,\"y2\":%.1f}",
                                Float.isNaN(aim.initialDeg)?"null":String.format(java.util.Locale.US,"%.3f",aim.initialDeg),Float.isNaN(aim.finalDeg)?"null":String.format(java.util.Locale.US,"%.3f",aim.finalDeg),aim.steps,aim.dots,aim.x2,aim.y2);
                        com.bitaim.carromaim.capture.PcLink.sendEvent("{\"type\":\"done\",\"id\":"+id+",\"ok\":"+success+",\"aim\":"+aimJson+"}");
                        if(aim!=null)appendEvent(String.format(java.util.Locale.US,"PC AIM initial=%.2f° final=%.2f° steps=%d dots=%d",aim.initialDeg,aim.finalDeg,aim.steps,aim.dots));
                    });
                    if(!sent)com.bitaim.carromaim.capture.PcLink.sendEvent("{\"type\":\"done\",\"id\":"+id+",\"ok\":false,\"aim\":null}");
                });
            }
        });
        com.bitaim.carromaim.capture.PcLink.setEnabled(getSharedPreferences("bitaim_game_mode",MODE_PRIVATE).getBoolean("pcLink",false));
    }

    private void setupAimOverlay() {
        aimOverlayView = new AimOverlayView(this);
        AimOverlayView.errorHook=this::reportAppError;
        setupPcLink();
        aimOverlayView.setPlanListener((state,route,gold,planned)->{
            if(com.bitaim.carromaim.capture.PcLink.connected())return; // the PC brain decides while linked
            aimOverlayView.setAutoPlayArmed(autoPlay.enabled());
            watchIncidents(state,route);
            autoPlay.setGoldReady(aimOverlayView.goldReady());
            autoPlay.setGoldRoute(aimOverlayView.goldRoute());
            AutoPlayController.Action action=autoPlay.update(state,route,gold,android.os.SystemClock.uptimeMillis(),planned);
            String status=autoPlay.status();
            if(autoPlay.enabled()&&!status.equals(lastAutoStatus)){lastAutoStatus=status;Log.i("BitAim/Auto","status: "+status);appendEvent("status "+status);}
            {String note=autoPlay.consumeNote();if(note!=null)appendEvent(note);}
            aimOverlayView.setAutoStatus(status);
            if(action==null)return;
            Log.i("BitAim/Auto",(action.move?"move ":"shoot ")+autoPlay.status());
            appendEvent(String.format(java.util.Locale.US,"%s from (%.0f,%.0f) to (%.0f,%.0f) %s",action.move?"SLIDE":"SHOT",action.x1,action.y1,action.x2,action.y2,autoPlay.status()));
            if(!action.move){lastShotActionAt=android.os.SystemClock.uptimeMillis();aimOverlayView.setFiringRoute(autoPlay.lastShotRoute());}
            mainHandler.post(()->{
                if(INSTANCE!=this||!autoPlay.enabled()){autoPlay.complete(false);return;}
                GameGestureService gestures=GameGestureService.INSTANCE;
                final String[] shotInfo={null};final float[] shotScale={state!=null&&state.board!=null?state.board.width():Float.NaN};
                boolean sent=gestures!=null&&gestures.sendCorrected(action,aimSource,(success,aim)->{
                    if(!action.move&&aim!=null){
                        appendEvent(String.format(java.util.Locale.US,"AIM initial=%.2f° final=%.2f° steps=%d dots=%d release=(%.0f,%.0f)",aim.initialDeg,aim.finalDeg,aim.steps,aim.dots,aim.x2,aim.y2));
                        ScreenCaptureService capture=ScreenCaptureService.INSTANCE;
                        if(capture!=null&&!Float.isNaN(shotScale[0])){
                            // Re-mark with the released pull and the closed-loop aim result (merged into autoRoute).
                            float dx=action.x1-aim.x2,dy=action.y1-aim.y2,len=(float)Math.hypot(dx,dy);
                            String corr=String.format(java.util.Locale.US,"\"aimCorrection\":{\"initialDeg\":%s,\"finalDeg\":%s,\"steps\":%d,\"dots\":%d,\"corrected\":%b}",
                                    Float.isNaN(aim.initialDeg)?"null":String.format(java.util.Locale.US,"%.3f",aim.initialDeg),
                                    Float.isNaN(aim.finalDeg)?"null":String.format(java.util.Locale.US,"%.3f",aim.finalDeg),aim.steps,aim.dots,action.correctAim);
                            String info=shotInfo[0]==null?"{"+corr+"}":shotInfo[0].substring(0,shotInfo[0].lastIndexOf('}'))+","+corr+"}";
                            capture.updateAutoShot(len/shotScale[0],len>0?dx/len:Float.NaN,len>0?dy/len:Float.NaN,info);
                        }
                    }
                    autoPlay.complete(success);
                    if(!action.move&&aimOverlayView!=null)aimOverlayView.setFiringRoute(null);
                    Log.i("BitAim/Auto","gesture "+(success?"completed":"cancelled")+": "+autoPlay.status());
                    if(!success)reportIncident("gesture-cancelled","{\"status\":\""+autoPlay.status().replace("\"","'")+"\"}");
                    if(!autoPlay.enabled())runOnMain(()->{setAutoPlay(false);try{android.widget.Toast.makeText(this,"■ Auto play stopped: "+autoPlay.status(),android.widget.Toast.LENGTH_LONG).show();}catch(Exception ignored){}});
                });
                if(sent&&!action.move&&state!=null&&state.board!=null){
                    ScreenCaptureService capture=ScreenCaptureService.INSTANCE;
                    if(capture!=null){
                        float dx=action.x1-action.x2,dy=action.y1-action.y2,len=(float)Math.hypot(dx,dy); // launch = opposite of the pull
                        com.bitaim.carromaim.cv.ShotPlanner.Shot fired=autoPlay.lastShotRoute()!=null?autoPlay.lastShotRoute():route;
                        String info=fired==null?null:routeJson(fired,state,len);shotInfo[0]=info;
                        capture.markAutoShot(len/state.board.width(),len>0?dx/len:Float.NaN,len>0?dy/len:Float.NaN,info);
                        if(fired!=null&&fired.coin!=null&&fired.pocket!=null)capture.markAutoTarget(fired.coin.pos.x,fired.coin.pos.y,fired.pocket.x,fired.pocket.y);
                    }
                }
                if(!sent){
                    autoPlay.complete(false);Log.i("BitAim/Auto","gesture not sent (game window or gesture service unavailable)");
                    reportIncident("gesture-not-sent","{\"status\":\""+autoPlay.status().replace("\"","'")+"\"}");
                    if(!autoPlay.enabled()){setAutoPlay(false);android.widget.Toast.makeText(this,"■ Auto play stopped — game or gesture control unavailable",android.widget.Toast.LENGTH_LONG).show();}
                }
            });
        });
        aimOverlayView.setOnCoinPicked(selected -> {
            if(INSTANCE!=this)return;
            overlayMode=MODE_AUTO;
            applyOverlayMode();
            android.widget.Toast.makeText(this,aimOverlayView.getPickResult(),android.widget.Toast.LENGTH_SHORT).show();
        });
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        overlayParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        // Android 12+ blocks touches that pass through another app's full-screen
        // window unless its opacity is <= 0.8 — without this the game "freezes".
        overlayParams.alpha = 0.8f;
        // Detection uses full-display pixels, including the status bar/cutout.
        // ColorOS otherwise insets this window even with LAYOUT_IN_SCREEN.
        if (Build.VERSION.SDK_INT >= 30) overlayParams.setFitInsetsTypes(0);
        if (Build.VERSION.SDK_INT >= 28) {
            overlayParams.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        overlayParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        aimOverlayView.setVisibility(View.GONE);
        windowManager.addView(aimOverlayView, overlayParams);
    }

    public void toggleAimOverlay() {
        if (aimOverlayView == null || floatingBtnView == null) return;
        if (autoPlay.enabled()) {
            // Auto play keeps running: only the red STOP AUTO button next to the star stops it.
            try { android.widget.Toast.makeText(this, "Auto play is on — use the red ■ STOP AUTO button to stop", android.widget.Toast.LENGTH_SHORT).show(); } catch (Exception ignored) {}
            return;
        }
        overlayMode = (overlayMode + 1) % 3;
        applyOverlayMode();
        String msg = overlayMode == MODE_AUTO ? "⚡ AUTO — best shot shows on your turn. Play normally."
                   : overlayMode == MODE_AIM  ? "🎯 PICK — tap your coin once, then play normally"
                   :                            "Lines OFF";
        try { android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show(); }
        catch (Exception ignored) {}
    }

    private void applyOverlayMode() {
        if(overlayMode!=MODE_AUTO&&autoPlay.enabled()){autoPlay.setEnabled(false);aimOverlayView.setAutoPlayArmed(false);showStopButton(false);updateColourLockLabel();}
        boolean visible   = overlayMode != MODE_OFF;
        boolean touchable = overlayMode == MODE_AIM;
        aimOverlayView.setVisibility(visible ? View.VISIBLE : View.GONE);
        aimOverlayView.setAutoMode(overlayMode == MODE_AUTO);
        aimOverlayView.setPlayMode(false);
        int f = overlayParams.flags;
        if (touchable) f &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        else           f |=  WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        overlayParams.flags = f;
        try { windowManager.updateViewLayout(aimOverlayView, overlayParams); }
        catch (Exception e) { Log.w("BitAim/Overlay", "updateViewLayout: " + e.getMessage()); }
        ImageView icon = floatingBtnView.findViewById(R.id.floating_icon);
        if (icon != null) icon.setAlpha(overlayMode == MODE_OFF ? 0.5f : 1.0f);
        floatingBtnView.setScaleX(overlayMode == MODE_AIM ? 1.15f : 1f);
        floatingBtnView.setScaleY(overlayMode == MODE_AIM ? 1.15f : 1f);
    }

    // Settings are remembered statically so values sent from the app before the
    // service finished starting are not lost.
    private static volatile String  sShotMode  = AimOverlayView.MODE_ALL;
    private static volatile float   sMarginX = 0f, sMarginY = 0f, sSensitivity = 1f;
    private static volatile boolean sStrikerMoveable = true, sSnapMode = false;

    public static void saveShotMode(String m)            { sShotMode = m; }
    public static void saveMarginOffset(float x, float y) { sMarginX = x; sMarginY = y; }
    public static void saveSensitivity(float v)           { sSensitivity = v; }
    public static void saveStrikerMoveable(boolean m)     { sStrikerMoveable = m; }
    public static void saveSnapMode(boolean on)           { sSnapMode = on; }

    private void applySavedSettings() {
        runOnMain(() -> {
            if (aimOverlayView == null) return;
            if (sShotMode != null) aimOverlayView.setShotMode(sShotMode);
            aimOverlayView.setMarginOffset(sMarginX, sMarginY);
            aimOverlayView.setSensitivity(sSensitivity);
            aimOverlayView.setStrikerMoveable(sStrikerMoveable);
            aimOverlayView.setSnapMode(sSnapMode);
        });
    }

    private final android.os.Handler mainHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private void runOnMain(Runnable r) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) r.run();
        else mainHandler.post(r);
    }

    public void setShotMode(String mode)            { saveShotMode(mode); runOnMain(() -> { if (aimOverlayView!=null) aimOverlayView.setShotMode(mode); }); }
    public void setMarginOffset(float dx, float dy) { saveMarginOffset(dx, dy); runOnMain(() -> { if (aimOverlayView!=null) aimOverlayView.setMarginOffset(dx, dy); }); }
    public void setSensitivity(float v)             { saveSensitivity(v); runOnMain(() -> { if (aimOverlayView!=null) aimOverlayView.setSensitivity(v); }); }
    public void setStrikerMoveable(boolean m)       { saveStrikerMoveable(m); runOnMain(() -> { if (aimOverlayView!=null) aimOverlayView.setStrikerMoveable(m); }); }
    public void setSnapMode(boolean on)             { saveSnapMode(on); runOnMain(() -> { if (aimOverlayView!=null) aimOverlayView.setSnapMode(on); }); }
    /** Called from the capture worker thread — hand the result to the UI thread. */
    // Latest aim dots for closed-loop auto aim (written on the capture thread, read by the held gesture).
    private volatile float[] latestGuide;private volatile long latestGuideAt;
    private final GameGestureService.AimSource aimSource=new GameGestureService.AimSource(){
        @Override public float[] guide(){return latestGuide;}
        @Override public long guideAt(){return latestGuideAt;}
    };
    private final com.bitaim.carromaim.cv.StrikerBaseline strikerBaseline=new com.bitaim.carromaim.cv.StrikerBaseline();
    /** 4.11.0: Lucky Shot scene and its plan (screen pixels); scene null when no Lucky Shot board is visible. */
    private final com.bitaim.carromaim.cv.LuckyAuto luckyAuto=new com.bitaim.carromaim.cv.LuckyAuto();
    public void onLuckyShot(com.bitaim.carromaim.cv.LuckyShot.Scene scene,com.bitaim.carromaim.cv.LuckyShot.Plan plan,boolean planning){
        // The game's aim dots feed the closed-loop aim of a held auto shot, as in carrom play.
        if(scene!=null&&scene.guideDots>0&&!Float.isNaN(scene.guideX)){
            latestGuide=new float[]{scene.guideX,scene.guideY,scene.guideDots,scene.striker.x,scene.striker.y};latestGuideAt=android.os.SystemClock.uptimeMillis();
        }
        runOnMain(() -> {
            if(aimOverlayView==null)return;
            aimOverlayView.setLuckyShot(scene,plan,planning);
            if(!autoPlay.enabled()||com.bitaim.carromaim.capture.PcLink.connected()){if(scene==null)luckyAuto.reset();return;}
            AutoPlayController.Action action=luckyAuto.update(scene,plan,planning,android.os.SystemClock.uptimeMillis());
            String note=luckyAuto.consumeNote();if(note!=null){appendEvent(note);Log.i("BitAim/Lucky",note);}
            if(scene!=null)aimOverlayView.setAutoStatus(luckyAuto.status());
            if(action==null)return;
            GameGestureService gestures=GameGestureService.INSTANCE;
            aimOverlayView.setLuckyFiring(true); // our lines must not cover the game's aim dots during the hold
            boolean sent=gestures!=null&&gestures.sendCorrected(action,aimSource,(success,aim)->{
                runOnMain(()->{if(aimOverlayView!=null)aimOverlayView.setLuckyFiring(false);});
                if(aim!=null)appendEvent(String.format(java.util.Locale.US,"LUCKY AIM initial=%.2f° final=%.2f° steps=%d dots=%d release=(%.0f,%.0f)",aim.initialDeg,aim.finalDeg,aim.steps,aim.dots,aim.x2,aim.y2));
                runOnMain(()->luckyAuto.complete(success));
            });
            if(!sent){aimOverlayView.setLuckyFiring(false);luckyAuto.complete(false);appendEvent("LUCKY gesture not sent (game window or gesture service unavailable)");}
        });
    }

    public void onDetectedState(GameState s) {
        strikerBaseline.apply(s);
        com.bitaim.carromaim.cv.DiscSeparation.apply(s);
        if(s!=null&&s.aimGuideDots>0&&!Float.isNaN(s.aimGuideX)){
            float[] d=com.bitaim.carromaim.cv.AimGuideDetector.shotDirection(s.aimGuideX,s.aimGuideY);
            float stx=s.striker!=null&&s.strikerDetected?s.striker.pos.x:Float.NaN,sty=s.striker!=null&&s.strikerDetected?s.striker.pos.y:Float.NaN;
            latestGuide=new float[]{d[0],d[1],s.aimGuideDots,stx,sty};latestGuideAt=android.os.SystemClock.uptimeMillis();
        }
        runOnMain(() -> {
            // A frame already in flight when the player changed colours must
            // never repopulate the old target below the newly selected lock.
            if(s!=null&&s.targetColourKey!=getSharedPreferences("bitaim_game_mode",MODE_PRIVATE).getInt("targetColourKey",TargetColourLock.AUTO))return;
            latestState=s;
            updateColourLockLabel();
            if(aimOverlayView!=null){
                aimOverlayView.setCaptureActive(ScreenCaptureService.INSTANCE!=null);
                aimOverlayView.setDetectedState(s);
            }
        });
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Bit-Aim Running", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Aim assist overlay is active");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        int piFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_IMMUTABLE : 0;
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 1, openIntent, piFlags);
        Intent stopIntent = new Intent(this, FloatingOverlayService.class);
        stopIntent.setAction("ACTION_STOP");
        PendingIntent stopPi = PendingIntent.getService(this, 0, stopIntent, piFlags);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Bit-Aim Running")
                .setContentText("Tap floating icon to toggle aim lines")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(openPi)
                .addAction(0, "Stop", stopPi)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "ACTION_STOP".equals(intent.getAction())) stopSelf();
        // NOT_STICKY: never let Android auto-restart the service in a loop
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        autoPlay.setEnabled(false);
        if(colourMenu!=null)colourMenu.dismiss();
        INSTANCE = null;
        // Overlay stopped (e.g. "Stop" in the notification) → stop screen capture too
        try { stopService(new Intent(this, ScreenCaptureService.class)); } catch (Exception ignored) {}
        try { if (stopButton!=null) windowManager.removeView(stopButton); } catch (Exception ignored) {}
        try { if (floatingBtnView!=null) windowManager.removeView(floatingBtnView); } catch (Exception ignored) {}
        try { if (aimOverlayView!=null)  windowManager.removeView(aimOverlayView);  } catch (Exception ignored) {}
    }
}
