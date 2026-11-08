package com.bitaim.carromaim.capture;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import org.json.JSONObject;

/**
 * PC brain link (branch pc-link). With "adb reverse tcp:8765 tcp:8765" the phone's 127.0.0.1:8765 reaches the PC.
 * The phone streams small JPEG frames (plus settings) and executes the slide/shot actions the PC sends back; the PC
 * also sends the aim-guide reading of every frame for the closed-loop aim correction. If nothing listens on the PC,
 * connecting fails at once and is retried every 2 s; the phone then keeps its own brain.
 *
 * Phone -> PC: [int length][byte type][payload]. 'F' = frame: JSON header line + '\n' + JPEG. 'E' = JSON event.
 * PC -> phone: one JSON object per line: {"type":"guide"|"action"|"status", ...}.
 */
public final class PcLink {
    private PcLink(){}
    public static final int PORT=8765;
    public interface Listener{void onAction(JSONObject a);void onStatus(String text);default void onRoute(JSONObject r){}}
    private static volatile boolean enabled,connected;
    private static volatile Listener listener;
    private static volatile float[] guide;private static volatile long guideAt;
    /** 4.10.2 flow control: one frame in flight; the PC acks each processed frame (live: frames queued in the ADB
     *  buffers and every reply, even "slide done", arrived 9 s late). */
    private static volatile long sentId,ackedId,sentAt;private static long frameSeq;
    private static final long ACK_TIMEOUT_MS=600;
    private static final Object lock=new Object();
    private static byte[] pendingFrame;private static String pendingHeader;private static final java.util.ArrayDeque<String> events=new java.util.ArrayDeque<>();
    private static Thread worker;
    public static boolean connected(){return enabled&&connected;}
    public static boolean enabled(){return enabled;}
    public static void setListener(Listener l){listener=l;}
    public static float[] guide(){return guide;}
    public static long guideAt(){return guideAt;}
    public static synchronized void setEnabled(boolean on){
        enabled=on;
        if(on&&worker==null){worker=new Thread(PcLink::loop,"BitAim-PcLink");worker.setDaemon(true);worker.start();}
        if(!on){connected=false;synchronized(lock){lock.notifyAll();}}
    }
    /** Keeps only the newest frame: a slow link drops frames instead of building a backlog. */
    public static void offerFrame(String header,byte[] jpeg){synchronized(lock){pendingHeader=header;pendingFrame=jpeg;lock.notifyAll();}}
    public static void sendEvent(String json){synchronized(lock){events.add(json);lock.notifyAll();}}
    private static void loop(){
        while(true){
            if(!enabled){synchronized(PcLink.class){if(!enabled){worker=null;return;}}}
            try(Socket s=new Socket()){
                s.connect(new InetSocketAddress("127.0.0.1",PORT),800);
                s.setTcpNoDelay(true);
                DataOutputStream out=new DataOutputStream(new BufferedOutputStream(s.getOutputStream(),1<<16));
                Thread reader=new Thread(()->read(s),"BitAim-PcLink-read");reader.setDaemon(true);reader.start();
                sentId=ackedId=0;frameSeq=0;
                connected=true;{Listener l=listener;if(l!=null)l.onStatus("connected to PC");}
                while(enabled&&!s.isClosed()&&reader.isAlive()){
                    byte[] frame=null;String header=null;String event=null;
                    synchronized(lock){
                        boolean canSend=sentId<=ackedId||android.os.SystemClock.uptimeMillis()-sentAt>ACK_TIMEOUT_MS;
                        if(events.isEmpty()&&(pendingFrame==null||!canSend))lock.wait(canSend?500:20);
                        canSend=sentId<=ackedId||android.os.SystemClock.uptimeMillis()-sentAt>ACK_TIMEOUT_MS;
                        if(!events.isEmpty())event=events.poll();
                        else if(pendingFrame!=null&&canSend){frame=pendingFrame;header=pendingHeader;pendingFrame=null;sentId=++frameSeq;sentAt=android.os.SystemClock.uptimeMillis();header="{\"seq\":"+sentId+","+header.substring(1);}
                    }
                    if(event!=null){byte[] e=event.getBytes("UTF-8");out.writeInt(e.length+1);out.writeByte('E');out.write(e);out.flush();}
                    else if(frame!=null){byte[] h=(header+"\n").getBytes("UTF-8");out.writeInt(h.length+frame.length+1);out.writeByte('F');out.write(h);out.write(frame);out.flush();}
                }
            }catch(Throwable ignored){}
            boolean was=connected;connected=false;guide=null;
            if(was){Listener l=listener;if(l!=null)l.onStatus("PC disconnected, phone brain");}
            try{Thread.sleep(2000);}catch(InterruptedException e){return;}
        }
    }
    private static void read(Socket s){
        try(BufferedReader in=new BufferedReader(new InputStreamReader(s.getInputStream(),"UTF-8"))){
            String line;
            while((line=in.readLine())!=null){
                JSONObject o=new JSONObject(line);String type=o.optString("type");
                if("ack".equals(type)){ackedId=Math.max(ackedId,o.optLong("seq"));synchronized(lock){lock.notifyAll();}continue;}
                if("guide".equals(type)){
                    guide=new float[]{(float)o.optDouble("ux"),(float)o.optDouble("uy"),(float)o.optDouble("dots"),(float)o.optDouble("sx",Double.NaN),(float)o.optDouble("sy",Double.NaN)};
                    guideAt=android.os.SystemClock.uptimeMillis();
                }else{
                    Listener l=listener;if(l==null)continue;
                    if("action".equals(type))l.onAction(o);else if("status".equals(type))l.onStatus(o.optString("text"));else if("route".equals(type))l.onRoute(o);
                }
            }
        }catch(Throwable ignored){}
        try{s.close();}catch(IOException ignored){}
    }
}
