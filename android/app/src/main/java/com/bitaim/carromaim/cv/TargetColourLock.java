package com.bitaim.carromaim.cv;

/** Explicit user colour choice; independent of ownership badge recognition. */
public final class TargetColourLock {
    public static final int AUTO=0,LIGHT=1,DARK=2;
    /** Chromatic keys: 100+hue for solid skins, 300+hue for white-and-colour striped skins (hue 0-179, step 5). */
    public static final int STRIPED=300;
    /** 4.10.6: bright solid skins (median value >= BRIGHT_VALUE) get 500+hue, so light blue never matches dark blue. */
    public static final int BRIGHT=500;public static final float BRIGHT_VALUE=200f;
    public static boolean valid(int key){return key==AUTO||key==LIGHT||key==DARK||key>=100&&key<280||key>=STRIPED&&key<STRIPED+180||key>=BRIGHT&&key<BRIGHT+180;}
    public static int chromatic(float hue,boolean striped){return chromatic(hue,striped,false);}
    public static int chromatic(float hue,boolean striped,boolean bright){return (striped?STRIPED:bright?BRIGHT:100)+(Math.round(hue/5)*5)%180;}
    public static boolean bright(int key){return key>=BRIGHT&&key<BRIGHT+180;}
    public static boolean striped(int key){return key>=STRIPED&&key<STRIPED+180;}
    public static boolean matches(int selected,int appearance){
        if(selected==AUTO||appearance==AUTO)return false;
        if(selected<100||appearance<100)return selected==appearance;
        if(striped(selected)!=striped(appearance))return false; // solid blue never matches blue/white stripes
        if(bright(selected)!=bright(appearance))return false; // light blue never matches dark blue
        int delta=Math.abs(hue(selected)-hue(appearance));return Math.min(delta,180-delta)<=10;
    }
    private static int hue(int key){return striped(key)?key-STRIPED:bright(key)?key-BRIGHT:key-100;}
    public static String label(int key){
        if(key==LIGHT)return "White";if(key==DARK)return "Black";if(key<100)return "Auto";
        if(striped(key))return label(hue(key)+100)+" striped";
        if(bright(key))return "Light "+label(hue(key)+100).toLowerCase(java.util.Locale.US);
        int hue=key-100;
        if(hue<12||hue>=168)return "Red";
        if(hue<25)return "Orange";if(hue<40)return "Yellow";
        if(hue<80)return "Green";if(hue<95)return "Teal";
        if(hue<130)return "Blue";return "Purple";
    }
}
