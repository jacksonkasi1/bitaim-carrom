package android.util;
public class Log { public static int w(String t,String m){System.err.println(t+": "+m);return 0;} public static int e(String t,String m){return w(t,m);} public static int i(String t,String m){return 0;} }
