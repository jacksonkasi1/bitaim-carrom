package com.bitaim.carromaim.capture;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.bitaim.carromaim.MainActivity;
import com.bitaim.carromaim.R;
import com.bitaim.carromaim.cv.BoardDetector;
import com.bitaim.carromaim.cv.GameState;
import com.bitaim.carromaim.cv.TargetColourLock;
import com.bitaim.carromaim.overlay.FloatingOverlayService;

import java.nio.ByteBuffer;

/**
 * ScreenCaptureService  v3
 *
 * Captures the screen at ~30 FPS, runs BoardDetector on each frame,
 * scales the result to screen coordinates, and pushes it to the overlay.
 *
 * Changes from v2:
 *  - Capture resolution increased from 720 to 1080 for higher-fidelity detection.
 *  - Frame throttle reduced from 33 ms to 25 ms for snappier response (~40 FPS).
 *  - scaleState now uses a single uniform scale factor (sx) assuming square pixels.
 */
public class ScreenCaptureService extends Service {

    private static final String TAG              = "BitAim/Capture";
    public  static final String EXTRA_RESULT_CODE = "resultCode";
    public  static final String EXTRA_DATA        = "data";

    private static final String CHANNEL_ID      = "bitaim_capture";
    private static final int    NOTIF_ID        = 2001;
    private static final long   FRAME_INTERVAL_MS = 100; // ~10 FPS: steadier + far less battery/CPU
    private static final long   IDLE_INTERVAL_MS = 330, NO_BOARD_INTERVAL_MS = 500, OUT_OF_GAME_INTERVAL_MS = 1500;
    /** True while debug recording is actually capturing: debug mode on and a board detected in Carrom. */
    public static volatile boolean recordingLive;
    private static final long PC_FRAME_INTERVAL_MS = 33; private static final int PC_FRAME_WIDTH = 1080; private long pcFrameId; // 4.10.6: full resolution (540 px halved position accuracy; PC aims were 1-2 deg off)
    private volatile boolean lastLocalTurn, lastBoardSeen;
    private static final int    CAPTURE_WIDTH   = 1080;

    private MediaProjection mediaProjection;
    private VirtualDisplay  virtualDisplay;
    private ImageReader     imageReader;
    private HandlerThread   workerThread;
    private Handler         workerHandler;
    private final BoardDetector detector = new BoardDetector();
    /** Local-only calibration log: one JSON line per real shot in this app's own files folder. */
    private final com.bitaim.carromaim.cv.ShotLogger shotLogger = new com.bitaim.carromaim.cv.ShotLogger(this::appendShotLine);
    private final com.bitaim.carromaim.cv.OpponentWatcher opponentWatcher = new com.bitaim.carromaim.cv.OpponentWatcher(this::appendShotLine);
    public void markAutoShot(float pull){ shotLogger.markAutoShot(pull); }
    public void markAutoShot(float pull,float ux,float uy){ shotLogger.markAutoShot(pull,ux,uy); }
    public void markAutoShot(float pull,float ux,float uy,String info){ shotLogger.markAutoShot(pull,ux,uy,info); }
    public void updateAutoShot(float pull,float ux,float uy,String info){ shotLogger.updateAutoShot(pull,ux,uy,info); }
    public void markAutoTarget(float tx,float ty,float px,float py){ shotLogger.markTarget(tx,ty,px,py); }
    public void setSuggestion(float ux,float uy,float tx,float ty,float px,float py,String label){ shotLogger.setSuggestion(ux,uy,tx,ty,px,py,label); }
    public void clearSuggestion(){ shotLogger.clearSuggestion(); }

    // ── Automatic incident capture: screenshot(s) + JSON line in files/debug/incidents.jsonl ──────────
    private Bitmap lastAimedFrame;
    private Bitmap lastOppSettledFrame;
    private Bitmap pendingOppBefore;private String pendingOppName;
    private final java.util.concurrent.ConcurrentLinkedQueue<String[]> pendingIncidents=new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final java.util.Map<String,Long> lastIncidentAt=new java.util.HashMap<>();
    /** Thread-safe: the next captured frame is saved with this incident (at most one per type every 15 s). */
    public void requestIncident(String type,String detailsJson){ pendingIncidents.add(new String[]{type,detailsJson}); }
    private void handleIncident(String type,String details,Bitmap frame,String record){
        if(!DebugMode.enabled(this))return;
        if(!com.bitaim.carromaim.overlay.GameGestureService.gameInFront())return;
        long now=System.currentTimeMillis();
        Long last=lastIncidentAt.get(type);if(last!=null&&now-last<15000)return;
        lastIncidentAt.put(type,now);
        String id="incident-"+new java.text.SimpleDateFormat("yyyyMMdd-HHmmss",java.util.Locale.US).format(new java.util.Date(now))+"-"+type;
        if(frame!=null)saveDebugImage(frame,id+"_now");
        if(lastAimedFrame!=null&&type.startsWith("followed-line"))saveDebugImage(lastAimedFrame,id+"_aimed");
        try{
            java.io.File dir=MatchFolder.dir(this);if(dir==null)return;
            StringBuilder b=new StringBuilder();
            b.append("{\"t\":").append(now).append(",\"type\":\"").append(type).append("\",\"id\":\"").append(id).append('"');
            if(details!=null)b.append(",\"details\":").append(details);
            if(record!=null)b.append(",\"shot\":").append(record);
            b.append('}');
            try(java.io.FileWriter w=new java.io.FileWriter(new java.io.File(dir,"incidents.jsonl"),true)){w.write(b.toString());w.write("\n");}
            Log.i(TAG,"Incident captured: "+type);
        }catch(Throwable t){Log.w(TAG,"Incident write failed: "+t.getMessage());}
    }
    /** Local-only debug picture for auto shots: 540 px wide JPEG in files/debug, oldest removed beyond 240 files (4.9.9: opponent shots add pictures; ~15 MB). */
    private void saveDebugImage(Bitmap frame,String name){
        if(!DebugMode.enabled(this))return;
        if(!com.bitaim.carromaim.overlay.GameGestureService.gameInFront())return; // never picture another app
        try{
            if(frame==null)return;
            java.io.File dir=MatchFolder.pictures(this);if(dir==null)return;
            int tw=540,th=Math.max(1,Math.round(frame.getHeight()*(tw/(float)frame.getWidth())));
            Bitmap small=Bitmap.createScaledBitmap(frame,tw,th,true);
            try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(dir,name+".jpg"))){small.compress(Bitmap.CompressFormat.JPEG,72,out);}
            if(small!=frame)small.recycle();
            MatchFolder.prune(this);
        }catch(Throwable t){Log.w(TAG,"Debug image failed: "+t.getMessage());}
    }
    private void appendShotLine(String line){
        if(!DebugMode.enabled(this))return;
        try{
            java.io.File dir=MatchFolder.dir(this);if(dir==null)return;
            java.io.File file=new java.io.File(dir,"shots.jsonl");
            if(file.length()>5_000_000L){java.io.File old=new java.io.File(dir,"shots-old.jsonl");old.delete();file.renameTo(old);}
            try(java.io.FileWriter w=new java.io.FileWriter(file,true)){w.write(line);w.write(System.lineSeparator().equals("\r\n")?"\n":System.lineSeparator());}
        }catch(Throwable t){Log.w(TAG,"Shot log write failed: "+t.getMessage());}
    }

    // Detector settings survive service restarts / arrive-before-start
    private static volatile float  sMinR = -1f, sMaxR = -1f;
    private static volatile double sThreshold = -1;
    public static void saveRadius(float min, float max) { sMinR = min; sMaxR = max; }
    public static void saveThreshold(double v)          { sThreshold = v; }
    public void setAllColours(boolean enabled){
        synchronized(detector){
            android.content.SharedPreferences.Editor settings=getSharedPreferences("bitaim_game_mode",Context.MODE_PRIVATE).edit().putBoolean("allColours",enabled);
            if(enabled){settings.putInt("targetColourKey",0);detector.setTargetColourKey(0);}
            settings.apply();
            detector.setAllColours(enabled);
        }
    }
    public void setTargetColourKey(int key){
        if(!TargetColourLock.valid(key))key=TargetColourLock.AUTO;
        synchronized(detector){
            getSharedPreferences("bitaim_game_mode",Context.MODE_PRIVATE).edit()
                    .putInt("targetColourKey",key).putBoolean("allColours",false).apply();
            detector.setAllColours(false);
            detector.setTargetColourKey(key);
        }
        FloatingOverlayService overlay=FloatingOverlayService.INSTANCE;
        if(overlay!=null)overlay.onDetectedState(null); // re-plan; auto play keeps running (stopped only by its STOP button)
    }

    private int screenWidth, screenHeight, screenDpi;
    private long    lastFrameMs = 0;
    private volatile boolean running = false;

    public static volatile ScreenCaptureService INSTANCE;

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        INSTANCE = this;
        createChannel();
        try {
            // Android 10+: must declare mediaProjection type; mandatory on Android 14+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, buildNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(NOTIF_ID, buildNotification());
            }
        } catch (Throwable t) {
            Log.e(TAG, "startForeground failed: " + t.getMessage());
            stopSelf();
            return;
        }

        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        screenWidth  = dm.widthPixels;
        screenHeight = dm.heightPixels;
        screenDpi    = dm.densityDpi;

        workerThread = new HandlerThread("BitAim-Capture");
        workerThread.start();
        workerHandler = new Handler(workerThread.getLooper());

        if (sMinR > 0) detector.setMinRadiusFrac(sMinR);
        if (sMaxR > 0) detector.setMaxRadiusFrac(sMaxR);
        if (sThreshold > 0) detector.setParam2(sThreshold);
        detector.setAllColours(getSharedPreferences("bitaim_game_mode",Context.MODE_PRIVATE).getBoolean("allColours",false));
        detector.setTargetColourKey(getSharedPreferences("bitaim_game_mode",Context.MODE_PRIVATE).getInt("targetColourKey",0));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (workerHandler == null) { stopSelf(); return START_NOT_STICKY; }
        if (intent == null) return START_NOT_STICKY;
        if (mediaProjection != null) return START_NOT_STICKY;

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent data = intent.getParcelableExtra(EXTRA_DATA);
        if (resultCode == 0 || data == null) {
            Log.w(TAG, "Missing projection extras — stopping");
            stopSelf();
            return START_NOT_STICKY;
        }

        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        try {
            mediaProjection = mpm.getMediaProjection(resultCode, data);
        } catch (Throwable t) {
            Log.e(TAG, "getMediaProjection failed: " + t.getMessage());
            mediaProjection = null;
        }
        if (mediaProjection == null) {
            Log.e(TAG, "Failed to acquire MediaProjection");
            stopSelf();
            return START_NOT_STICKY;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mediaProjection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { stopSelf(); }
            }, workerHandler);
        }

        try {
            startCapture();
        } catch (Throwable t) {
            Log.e(TAG, "startCapture failed: " + t.getMessage());
            stopSelf();
            return START_NOT_STICKY;
        }
        // A projection token can't be reused after a restart, so never auto-restart
        return START_NOT_STICKY;
    }

    private void startCapture() {
        int captureW = Math.min(screenWidth, CAPTURE_WIDTH);
        int captureH = Math.round(screenHeight * (captureW / (float) screenWidth));

        ImageReader oldReader = imageReader;
        imageReader = ImageReader.newInstance(captureW, captureH, PixelFormat.RGBA_8888, 3);
        imageReader.setOnImageAvailableListener(
                reader -> processIfDue(reader, captureW, captureH), workerHandler);

        if (virtualDisplay == null) {
            virtualDisplay = mediaProjection.createVirtualDisplay(
                    "BitAim-Capture",
                    captureW, captureH, screenDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(),
                    null, workerHandler
            );
        } else {
            // Android 14+ allows createVirtualDisplay only ONCE per projection,
            // so on rotation we resize the existing display instead.
            virtualDisplay.resize(captureW, captureH, screenDpi);
            virtualDisplay.setSurface(imageReader.getSurface());
        }
        if (oldReader != null) oldReader.close();
        running = true;
    }

    private void processIfDue(ImageReader reader, int w, int h) {
        long now = System.currentTimeMillis();
        Image img = null;
        Bitmap bmp = null;
        try {
            img = reader.acquireLatestImage();
            if (img == null) return;
            if (!running) return;
            // Full rate only on the local turn; another player's turn or a hidden board needs far fewer frames.
            // Debug recording: while an auto shot moves, detect every frame (motion track for the physics fit).
            // 4.9.7 (user: "record only when it detects the board"): with Carrom not in front nothing is converted,
            // detected or recorded; one cheap check every 1.5 s notices when the game comes back.
            boolean inGame = com.bitaim.carromaim.overlay.GameGestureService.gameInFront();
            boolean debugOn = DebugMode.enabled(this);
            boolean pc = PcLink.connected();
            long interval = !inGame ? OUT_OF_GAME_INTERVAL_MS
                    : pc ? PC_FRAME_INTERVAL_MS
                    : (shotLogger.tracking() || opponentWatcher.tracking()) && debugOn ? 0
                    : lastLocalTurn ? FRAME_INTERVAL_MS
                    : lastBoardSeen ? (debugOn ? FRAME_INTERVAL_MS : IDLE_INTERVAL_MS) // debug: catch the start of other players' shots
                    : NO_BOARD_INTERVAL_MS;
            if (now - lastFrameMs < interval) return;
            lastFrameMs = now;
            if (!inGame) {
                lastBoardSeen = lastLocalTurn = false; recordingLive = false;
                if (lastAimedFrame != null) { lastAimedFrame.recycle(); lastAimedFrame = null; }
                if (lastOppSettledFrame != null) { lastOppSettledFrame.recycle(); lastOppSettledFrame = null; }
                FloatingOverlayService idle = FloatingOverlayService.INSTANCE;
                if (idle != null) idle.onDetectedState(null);
                return;
            }

            bmp = imageToBitmap(img, w, h);
            if (bmp == null) return;
            if (pc) {
                // PC brain: send a small JPEG with the settings the PC needs; detection and planning run on the PC.
                Bitmap small = bmp.getWidth() <= PC_FRAME_WIDTH ? bmp : Bitmap.createScaledBitmap(bmp, PC_FRAME_WIDTH, Math.max(1, Math.round(bmp.getHeight() * (PC_FRAME_WIDTH / (float) bmp.getWidth()))), true);
                java.io.ByteArrayOutputStream jpg = new java.io.ByteArrayOutputStream(64 * 1024);
                small.compress(Bitmap.CompressFormat.JPEG, 85, jpg);
                int fw = small.getWidth(), fh = small.getHeight();
                if (small != bmp) small.recycle();
                android.content.SharedPreferences gm = getSharedPreferences("bitaim_game_mode", Context.MODE_PRIVATE);
                FloatingOverlayService ov = FloatingOverlayService.INSTANCE;
                String header = String.format(java.util.Locale.US,
                        "{\"id\":%d,\"t\":%d,\"w\":%d,\"h\":%d,\"sw\":%d,\"sh\":%d,\"auto\":%b,\"key\":%d,\"allColours\":%b,\"pro\":%b,\"debug\":%b}",
                        ++pcFrameId, now, fw, fh, screenWidth, screenHeight, ov != null && ov.isAutoPlayEnabled(),
                        gm.getInt("targetColourKey", com.bitaim.carromaim.cv.TargetColourLock.AUTO), gm.getBoolean("allColours", false),
                        com.bitaim.carromaim.cv.RoutePlanner.proStyle, debugOn);
                PcLink.offerFrame(header, jpg.toByteArray());
                return;
            }

            GameState state=null;
            if(com.bitaim.carromaim.overlay.GameGestureService.gameInFront())synchronized(detector){
                state=detector.detect(bmp);
                if(state!=null&&state.assignedColours){
                    detector.setAllColours(false);
                    android.content.SharedPreferences preferences=getSharedPreferences("bitaim_game_mode",Context.MODE_PRIVATE);
                    if(preferences.getBoolean("allColours",false)){
                        preferences.edit().putBoolean("allColours",false).apply();
                        FloatingOverlayService overlay=FloatingOverlayService.INSTANCE;
                        // Team badge recognised: ownership changes are applied by re-planning; auto play keeps running.
                    }
                }
            }
            lastBoardSeen = state != null;
            recordingLive = debugOn && state != null;
            if (debugOn) MatchFolder.observe(state == null ? -1 : state.coins.size(), state != null && state.openingCluster, now);
            lastLocalTurn = state != null && state.strikerDetected;
            if (!running) return;
            // Scale captured-coords → real screen-coords
            float sx = screenWidth  / (float) w;
            float sy = screenHeight / (float) h;
            scaleState(state, sx, sy);
            try { shotLogger.feed(state, now); } catch (Throwable t) { Log.w(TAG, "Shot log: " + t.getMessage()); }
            if (DebugMode.enabled(this)) try { opponentWatcher.feed(state, now); } catch (Throwable t) { Log.w(TAG, "Opponent log: " + t.getMessage()); }
            if (debugOn) {
                // 4.9.9: opponent shots get the same before/after pictures as the player's own shots.
                if (opponentWatcher.consumeKeepSettledFrame()) {
                    Bitmap copy = Bitmap.createScaledBitmap(bmp, 540, Math.max(1, Math.round(bmp.getHeight() * (540f / bmp.getWidth()))), true);
                    if (lastOppSettledFrame != null) lastOppSettledFrame.recycle();
                    lastOppSettledFrame = copy;
                }
                String opp = opponentWatcher.consumeSaveRequest();
                if (opp != null) {
                    // 4.9.9b: hold the "before" picture until the record is really written (practice: 49 orphan
                    // before-pictures of shots the watcher dropped), then save the pair.
                    if (opp.endsWith("_before")) {
                        if (pendingOppBefore != null && pendingOppBefore != lastOppSettledFrame) pendingOppBefore.recycle();
                        pendingOppBefore = lastOppSettledFrame; pendingOppName = opp; lastOppSettledFrame = null;
                    } else {
                        String id = opp.substring(0, opp.length() - "_after".length());
                        if (pendingOppBefore != null && (id + "_before").equals(pendingOppName)) saveDebugImage(pendingOppBefore, pendingOppName);
                        saveDebugImage(bmp, opp);
                        if (pendingOppBefore != null) { pendingOppBefore.recycle(); pendingOppBefore = null; pendingOppName = null; }
                    }
                }
            }
            String debugShot = shotLogger.consumeSaveRequest();
            if (debugShot != null) saveDebugImage(bmp, debugShot);
            boolean debug = DebugMode.enabled(this);
            if (debug && shotLogger.lastWasAimed()) {
                // Small copy of the latest aimed frame, kept only so a later incident can show what the player aimed at.
                Bitmap copy = Bitmap.createScaledBitmap(bmp, 540, Math.max(1, Math.round(bmp.getHeight() * (540f / bmp.getWidth()))), true);
                if (lastAimedFrame != null) lastAimedFrame.recycle();
                lastAimedFrame = copy;
            }
            if (shotLogger.consumeNewManualRecord() && debug) {
                // Debug mode: pictures of the player's own shots too (while aiming, and the settled board).
                String id = "manual-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(new java.util.Date());
                if (lastAimedFrame != null) saveDebugImage(lastAimedFrame, id + "_aimed");
                saveDebugImage(bmp, id + "_after");
            }
            String shotIncident = shotLogger.consumeIncident();
            if (shotIncident != null) handleIncident(shotIncident, null, bmp, shotLogger.lastRecord());
            String[] req;
            while ((req = pendingIncidents.poll()) != null) handleIncident(req[0], req[1], bmp, null);

            FloatingOverlayService overlay = FloatingOverlayService.INSTANCE;
            if (overlay != null) overlay.onDetectedState(state);

        } catch (Throwable t) {
            Log.w(TAG, "Frame error: " + t.getMessage());
        } finally {
            if (bmp != null) bmp.recycle();
            if (img != null) img.close();
        }
    }

    private void scaleState(GameState s, float sx, float sy) {
        if (s == null) return;
        if (s.board != null) {
            s.board.left   *= sx; s.board.right  *= sx;
            s.board.top    *= sy; s.board.bottom *= sy;
        }
        if (!Float.isNaN(s.sliderY)) s.sliderY *= sy;
        if (s.striker != null) {
            s.striker.pos.x *= sx; s.striker.pos.y *= sy;
            s.striker.radius *= (sx + sy) * 0.5f;
        }
        for (com.bitaim.carromaim.cv.Coin c : s.coins) {
            c.pos.x  *= sx; c.pos.y  *= sy;
            c.radius *= (sx + sy) * 0.5f;
        }
        for (android.graphics.PointF p : s.pockets) {
            p.x *= sx; p.y *= sy;
        }
    }

    private Bitmap imageToBitmap(Image image, int w, int h) {
        Image.Plane[] planes = image.getPlanes();
        if (planes.length == 0) return null;
        ByteBuffer buffer   = planes[0].getBuffer();
        int pixelStride     = planes[0].getPixelStride();
        int rowStride       = planes[0].getRowStride();
        int rowPadding      = rowStride - pixelStride * w;
        int bw              = w + rowPadding / Math.max(1, pixelStride);
        Bitmap bmp = Bitmap.createBitmap(bw, h, Bitmap.Config.ARGB_8888);
        bmp.copyPixelsFromBuffer(buffer);
        if (rowPadding == 0) return bmp;
        Bitmap cropped = Bitmap.createBitmap(bmp, 0, 0, w, h);
        bmp.recycle();
        return cropped;
    }

    public void setMinRadius(float v)       { detector.setMinRadiusFrac(v); }
    public void setMaxRadius(float v)       { detector.setMaxRadiusFrac(v); }
    public void setDetectionParam(double v) { detector.setParam2(v); }

    @Override
    public void onDestroy() {
        super.onDestroy();
        running = false;
        INSTANCE = null;
        FloatingOverlayService overlay = FloatingOverlayService.INSTANCE;
        if (overlay != null) overlay.onDetectedState(null);
        if (virtualDisplay   != null) { virtualDisplay.release();   virtualDisplay  = null; }
        if (imageReader      != null) { imageReader.close();        imageReader     = null; }
        if (mediaProjection  != null) { mediaProjection.stop();     mediaProjection = null; }
        if (workerHandler != null) workerHandler.post(detector::close);
        else detector.close();
        if (workerThread     != null) { workerThread.quitSafely();  workerThread    = null; }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        screenWidth  = dm.widthPixels;
        screenHeight = dm.heightPixels;
        if (mediaProjection != null && workerHandler != null) {
            workerHandler.post(() -> {
                try { startCapture(); }
                catch (Throwable t) { Log.w(TAG, "Re-config failed: " + t.getMessage()); }
            });
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Bit-Aim Auto-Detect", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Bit-Aim Auto-Detect Running")
                .setContentText("Detecting board size, striker, coins and pockets")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }
}
