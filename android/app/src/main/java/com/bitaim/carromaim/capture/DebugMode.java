package com.bitaim.carromaim.capture;

import android.content.Context;

/** User-controlled debug recording (default OFF). When ON: shot records, pictures of auto and manual shots,
 *  automatic incident captures and the auto-play timeline are saved locally under the app's files folder. */
public final class DebugMode {
    private DebugMode(){}
    private static volatile boolean enabled;
    private static volatile boolean loaded;
    public static boolean enabled(Context c){
        if(!loaded&&c!=null){enabled=c.getSharedPreferences("bitaim_game_mode",Context.MODE_PRIVATE).getBoolean("debugMode",false);loaded=true;}
        return enabled;
    }
    public static void set(Context c,boolean on){
        enabled=on;loaded=true;
        if(c!=null)c.getSharedPreferences("bitaim_game_mode",Context.MODE_PRIVATE).edit().putBoolean("debugMode",on).apply();
    }
}
