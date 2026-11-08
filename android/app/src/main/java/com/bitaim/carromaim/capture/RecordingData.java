package com.bitaim.carromaim.capture;

import android.content.Context;
import java.io.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 4.9.8/4.9.9: the debug recordings as one shareable zip, and a way to clear them. 4.9.9 keeps one folder per match
 * (matches/<time>/shots.jsonl, events.log, incidents.jsonl, pictures/); files from older versions (shots.jsonl and
 * debug/ at the top) are included too. Everything stays on the phone until the user shares it.
 */
public final class RecordingData {
    private RecordingData(){}
    static File base(Context c){return c.getExternalFilesDir(null);}
    /** [shot records, pictures, total bytes, matches]. */
    public static long[] stats(Context c){
        long[] st=new long[4];File b=base(c);if(b==null)return st;
        File[] ms=new File(b,"matches").listFiles();
        if(ms!=null)for(File m:ms)if(m.isDirectory()){st[3]++;count(m,st);}
        File legacy=new File(b,"shots.jsonl");if(legacy.exists()){st[0]+=lines(legacy);st[2]+=legacy.length();}
        File[] old=new File(b,"debug").listFiles();if(old!=null)for(File f:old){st[2]+=f.length();if(f.getName().endsWith(".jpg"))st[1]++;}
        return st;
    }
    private static void count(File dir,long[] st){
        File[] fs=dir.listFiles();if(fs==null)return;
        for(File f:fs){if(f.isDirectory()){count(f,st);continue;}st[2]+=f.length();if(f.getName().endsWith(".jpg"))st[1]++;if(f.getName().equals("shots.jsonl"))st[0]+=lines(f);}
    }
    private static long lines(File f){long n=0;try(BufferedReader r=new BufferedReader(new FileReader(f))){while(r.readLine()!=null)n++;}catch(IOException ignored){}return n;}
    /** Writes cache/exports/bitaim-recordings-<model>-<time>.zip and returns it, or null when there is nothing to share. */
    public static File exportZip(Context c)throws IOException{
        File b=base(c);if(b==null)return null;
        if(stats(c)[2]==0)return null;
        File dir=new File(c.getCacheDir(),"exports");if(!dir.exists()&&!dir.mkdirs())throw new IOException("no cache folder");
        File[] old=dir.listFiles();if(old!=null)for(File f:old)f.delete();
        String stamp=new java.text.SimpleDateFormat("yyyyMMdd-HHmmss",java.util.Locale.US).format(new java.util.Date());
        File zip=new File(dir,"bitaim-recordings-"+android.os.Build.MODEL.replaceAll("[^A-Za-z0-9]","")+"-"+stamp+".zip");
        try(ZipOutputStream out=new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zip)))){
            long[] st=stats(c);
            String info="{\"app\":\"BitAim\",\"version\":\""+versionName(c)+"\",\"model\":\""+android.os.Build.MODEL+"\",\"exported\":\""+stamp+"\",\"matches\":"+st[3]+",\"shots\":"+st[0]+",\"pictures\":"+st[1]+"}\n";
            out.putNextEntry(new ZipEntry("info.json"));out.write(info.getBytes("UTF-8"));out.closeEntry();
            addTree(out,new File(b,"matches"),"matches/");
            File legacy=new File(b,"shots.jsonl");if(legacy.exists())add(out,legacy,"older/shots.jsonl");
            addTree(out,new File(b,"debug"),"older/debug/");
        }
        return zip;
    }
    /** Deletes every recording (all matches, older files) and any exported zip. */
    public static void clear(Context c){
        File b=base(c);
        if(b!=null){delete(new File(b,"matches"));new File(b,"shots.jsonl").delete();new File(b,"shots-old.jsonl").delete();delete(new File(b,"debug"));}
        delete(new File(c.getCacheDir(),"exports"));
        MatchFolder.reset();
    }
    private static void delete(File f){File[] k=f.listFiles();if(k!=null)for(File x:k)delete(x);f.delete();}
    private static void addTree(ZipOutputStream out,File dir,String prefix)throws IOException{
        File[] fs=dir.listFiles();if(fs==null)return;
        java.util.Arrays.sort(fs);
        for(File f:fs){if(f.isDirectory())addTree(out,f,prefix+f.getName()+"/");else add(out,f,prefix+f.getName());}
    }
    private static void add(ZipOutputStream out,File f,String name)throws IOException{
        out.putNextEntry(new ZipEntry(name));
        try(InputStream in=new BufferedInputStream(new FileInputStream(f))){byte[] buf=new byte[16384];int n;while((n=in.read(buf))>0)out.write(buf,0,n);}
        out.closeEntry();
    }
    private static String versionName(Context c){try{return c.getPackageManager().getPackageInfo(c.getPackageName(),0).versionName;}catch(Exception e){return "?";}}
}
