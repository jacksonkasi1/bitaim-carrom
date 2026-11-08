package com.bitaim.carromaim.overlay;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import com.bitaim.carromaim.capture.MediaProjectionRequestActivity;
import com.bitaim.carromaim.capture.ScreenCaptureService;
import com.bitaim.carromaim.cv.TargetColourLock;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;

import androidx.annotation.NonNull;

/**
 * OverlayModule  v3.1
 * Added: setSnapMode() bridge method.
 */
public class OverlayModule extends ReactContextBaseJavaModule {

    public OverlayModule(ReactApplicationContext ctx) { super(ctx); }
    @NonNull @Override public String getName() { return "OverlayModule"; }

    @ReactMethod
    public void canDrawOverlays(Promise p) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            p.resolve(Settings.canDrawOverlays(getReactApplicationContext()));
        else p.resolve(true);
    }

    @ReactMethod
    public void requestOverlayPermission() {
        Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getReactApplicationContext().getPackageName()));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getReactApplicationContext().startActivity(i);
    }

    @ReactMethod
    public void startOverlay(Promise p) {
        try {
            Intent i = new Intent(getReactApplicationContext(), FloatingOverlayService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                getReactApplicationContext().startForegroundService(i);
            else
                getReactApplicationContext().startService(i);
            p.resolve(true);
        } catch (Exception e) { p.reject("ERR_START", e.getMessage()); }
    }

    @ReactMethod
    public void stopOverlay(Promise p) {
        try {
            Intent i = new Intent(getReactApplicationContext(), FloatingOverlayService.class);
            getReactApplicationContext().stopService(i);
            Intent c = new Intent(getReactApplicationContext(), ScreenCaptureService.class);
            getReactApplicationContext().stopService(c);
            p.resolve(true);
        } catch (Exception e) { p.reject("ERR_STOP", e.getMessage()); }
    }

    @ReactMethod
    public void requestScreenCapture(Promise p) {
        try {
            if (FloatingOverlayService.INSTANCE == null) {
                p.reject("ERR_CAPTURE", "Start the Aim Overlay first.");
                return;
            }
            Intent i = new Intent(getReactApplicationContext(), MediaProjectionRequestActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getReactApplicationContext().startActivity(i);
            p.resolve(true);
        } catch (Exception e) { p.reject("ERR_CAPTURE", e.getMessage()); }
    }

    @ReactMethod
    public void stopScreenCapture(Promise p) {
        try {
            Intent c = new Intent(getReactApplicationContext(), ScreenCaptureService.class);
            getReactApplicationContext().stopService(c);
            p.resolve(true);
        } catch (Exception e) { p.reject("ERR_STOP_CAPTURE", e.getMessage()); }
    }

    @ReactMethod
    public void isAutoDetectActive(Promise p) {
        p.resolve(ScreenCaptureService.INSTANCE != null);
    }

    @ReactMethod
    public void isOverlayActive(Promise p) {
        p.resolve(FloatingOverlayService.INSTANCE != null);
    }
    @ReactMethod public void openGestureSettings(){
        Intent i=new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);getReactApplicationContext().startActivity(i);
    }
    @ReactMethod public void isAutoPlayEnabled(Promise p){
        FloatingOverlayService s=FloatingOverlayService.INSTANCE;p.resolve(s!=null&&s.isAutoPlayEnabled());
    }
    @ReactMethod public void setAutoPlay(boolean enabled,Promise p){
        if(enabled&&(Build.VERSION.SDK_INT<24||GameGestureService.INSTANCE==null)){
            p.reject("GESTURES_DISABLED","Enable BitAim optional move and shoot in Android Accessibility settings first.");return;
        }
        FloatingOverlayService s=FloatingOverlayService.INSTANCE;
        if(s==null){p.reject("OVERLAY_DISABLED","Start the Aim Overlay first.");return;}
        s.setAutoPlay(enabled);p.resolve(true);
    }
    @ReactMethod public void setAutoPull(float fraction){
        FloatingOverlayService s=FloatingOverlayService.INSTANCE;if(s!=null)s.setAutoPull(fraction);
    }
    @ReactMethod public void getDebugMode(Promise promise){
        promise.resolve(com.bitaim.carromaim.capture.DebugMode.enabled(getReactApplicationContext()));
    }
    @ReactMethod public void setDebugMode(boolean enabled){
        com.bitaim.carromaim.capture.DebugMode.set(getReactApplicationContext(),enabled);
    }
    /** 4.9.8: "shots|pictures|bytes" of the debug recordings on this phone. */
    @ReactMethod public void getRecordingStats(Promise promise){
        long[] st=com.bitaim.carromaim.capture.RecordingData.stats(getReactApplicationContext());
        promise.resolve(st[0]+"|"+st[1]+"|"+st[2]+"|"+st[3]);
    }
    /** Zips the recordings and opens the Android share sheet (WhatsApp, Drive, mail...). */
    @ReactMethod public void shareRecordings(Promise promise){
        try{
            android.content.Context c=getReactApplicationContext();
            java.io.File zip=com.bitaim.carromaim.capture.RecordingData.exportZip(c);
            if(zip==null){promise.reject("empty","No recordings yet. Turn on Debug mode and play a game first.");return;}
            Uri uri=androidx.core.content.FileProvider.getUriForFile(c,c.getPackageName()+".recordings",zip);
            Intent send=new Intent(Intent.ACTION_SEND);send.setType("application/zip");
            send.putExtra(Intent.EXTRA_STREAM,uri);send.putExtra(Intent.EXTRA_SUBJECT,"BitAim recordings");
            send.putExtra(Intent.EXTRA_TEXT,"BitAim game recordings ("+zip.getName()+")");
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent chooser=Intent.createChooser(send,"Share BitAim recordings");
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_GRANT_READ_URI_PERMISSION);
            c.startActivity(chooser);
            promise.resolve(zip.getName()+"|"+zip.length());
        }catch(Exception e){promise.reject("share",e.getMessage());}
    }
    /** Deletes all recordings (shot log, pictures, exported zips). */
    @ReactMethod public void clearRecordings(Promise promise){
        com.bitaim.carromaim.capture.RecordingData.clear(getReactApplicationContext());promise.resolve(true);
    }
    /** PC brain over USB (adb reverse tcp:8765): enable/disable and connection state. */
    @ReactMethod public void setPcLink(boolean enabled){
        getReactApplicationContext().getSharedPreferences("bitaim_game_mode",android.content.Context.MODE_PRIVATE).edit().putBoolean("pcLink",enabled).apply();
        com.bitaim.carromaim.capture.PcLink.setEnabled(enabled);
    }
    @ReactMethod public void getPcLink(Promise promise){
        promise.resolve((com.bitaim.carromaim.capture.PcLink.enabled()?"on":"off")+"|"+(com.bitaim.carromaim.capture.PcLink.connected()?"connected":"waiting"));
    }
    @ReactMethod public void getAllColours(Promise promise){
        promise.resolve(getReactApplicationContext().getSharedPreferences("bitaim_game_mode",0).getBoolean("allColours",false));
    }
    @ReactMethod public void setAllColours(boolean enabled){
        FloatingOverlayService overlay=FloatingOverlayService.INSTANCE;
        if(overlay!=null)overlay.disableAutoPlay();
        android.content.SharedPreferences.Editor settings=getReactApplicationContext().getSharedPreferences("bitaim_game_mode",0).edit().putBoolean("allColours",enabled);
        if(enabled)settings.putInt("targetColourKey",0);
        settings.apply();
        ScreenCaptureService capture=ScreenCaptureService.INSTANCE;if(capture!=null)capture.setAllColours(enabled);
        if(overlay!=null)overlay.onDetectedState(null);
    }
    @ReactMethod public void getTargetColourKey(Promise promise){
        promise.resolve(getReactApplicationContext().getSharedPreferences("bitaim_game_mode",0).getInt("targetColourKey",0));
    }
    @ReactMethod public void setTargetColourKey(int key){
        if(!TargetColourLock.valid(key))key=TargetColourLock.AUTO;
        FloatingOverlayService overlay=FloatingOverlayService.INSTANCE;
        if(overlay!=null){overlay.setTargetColourKey(key);return;}
        ScreenCaptureService capture=ScreenCaptureService.INSTANCE;
        if(capture!=null){capture.setTargetColourKey(key);return;}
        getReactApplicationContext().getSharedPreferences("bitaim_game_mode",0).edit()
                .putInt("targetColourKey",key).putBoolean("allColours",false).apply();
    }

    @ReactMethod public void setShotMode(String m) {
        FloatingOverlayService.saveShotMode(m);
        FloatingOverlayService s = FloatingOverlayService.INSTANCE;
        if (s != null) s.setShotMode(m);
    }
    @ReactMethod public void setMarginOffset(float dx, float dy) {
        FloatingOverlayService.saveMarginOffset(dx, dy);
        FloatingOverlayService s = FloatingOverlayService.INSTANCE;
        if (s != null) s.setMarginOffset(dx, dy);
    }
    @ReactMethod public void setSensitivity(float v) {
        FloatingOverlayService.saveSensitivity(v);
        FloatingOverlayService s = FloatingOverlayService.INSTANCE;
        if (s != null) s.setSensitivity(v);
    }
    @ReactMethod public void setStrikerMoveable(boolean m) {
        FloatingOverlayService.saveStrikerMoveable(m);
        FloatingOverlayService s = FloatingOverlayService.INSTANCE;
        if (s != null) s.setStrikerMoveable(m);
    }
    /** v3.1: toggle snap-to-coin mode. */
    @ReactMethod public void setSnapMode(boolean on) {
        FloatingOverlayService.saveSnapMode(on);
        FloatingOverlayService s = FloatingOverlayService.INSTANCE;
        if (s != null) s.setSnapMode(on);
    }
    @ReactMethod public void setDetectionRadius(float minFrac, float maxFrac) {
        ScreenCaptureService.saveRadius(minFrac, maxFrac);
        ScreenCaptureService c = ScreenCaptureService.INSTANCE;
        if (c != null) { c.setMinRadius(minFrac); c.setMaxRadius(maxFrac); }
    }
    @ReactMethod public void setDetectionThreshold(double v) {
        ScreenCaptureService.saveThreshold(v);
        ScreenCaptureService c = ScreenCaptureService.INSTANCE;
        if (c != null) c.setDetectionParam(v);
    }
}
