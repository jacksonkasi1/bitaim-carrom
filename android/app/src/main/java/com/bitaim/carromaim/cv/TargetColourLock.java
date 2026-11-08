package com.bitaim.carromaim.cv;

/** Explicit user colour choice; independent of ownership badge recognition. */
public final class TargetColourLock {
    public static final int AUTO=0,LIGHT=1,DARK=2;
    /** Chromatic keys: 100+hue for solid skins, 300+hue for white-and-colour striped skins (hue 0-179, step 5). */
    public static final int STRIPED=300;
    public static boolean valid(int key){return key==AUTO||key==LIGHT||key==DARK||key>=100&&key<280||key>=STRIPED&&key<STRIPED+180;}
    public static int chromatic(float hue,boolean striped){return (striped?STRIPED:100)+(Math.round(hue/5)*5)%180;}
    public static boolean striped(int key){return key>=STRIPED;}
    public static boolean matches(int selected,int appearance){
        if(selected==AUTO||appearance==AUTO)return false;
        if(selected<100||appearance<100)return selected==appearance;
        if(striped(selected)!=striped(appearance))return false; // solid blue never matches blue/white stripes
        int delta=Math.abs(hue(selected)-hue(appearance));return Math.min(delta,180-delta)<=10;
    }
    private static int hue(int key){return striped(key)?key-STRIPED:key-100;}
    public static String label(int key){
        if(key==LIGHT)return "White";if(key==DARK)return "Black";if(key<100)return "Auto";
        if(striped(key))return label(hue(key)+100)+" striped";
        int hue=key-100;
        if(hue<12||hue>=168)return "Red";
        if(hue<25)return "Orange";if(hue<40)return "Yellow";
        if(hue<80)return "Green";if(hue<95)return "Teal";
        if(hue<130)return "Blue";return "Purple";
    }
}
