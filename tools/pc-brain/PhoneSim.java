import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Loopback test for the PC brain protocol: sends JPEG files as phone frames (repeating each to look "steady")
 *  with auto play armed, and prints every reply. Usage: java PhoneSim frame1.jpg [frame2.jpg ...] */
public class PhoneSim {
    public static void main(String[] a)throws Exception{
        try(Socket s=new Socket("127.0.0.1",Integer.getInteger("port",8765))){
            s.setTcpNoDelay(true);
            DataOutputStream out=new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
            BufferedReader in=new BufferedReader(new InputStreamReader(s.getInputStream(),StandardCharsets.UTF_8));
            Thread reader=new Thread(()->{try{String l;while((l=in.readLine())!=null)System.out.println("PC> "+(l.length()>220?l.substring(0,220)+"...":l));}catch(IOException ignored){}});
            reader.setDaemon(true);reader.start();
            long id=0;
            for(String f:a){
                byte[] jpg=Files.readAllBytes(Paths.get(f));
                for(int k=0;k<120;k++){ // 4 s of identical frames at 30 fps
                    String h=String.format(Locale.US,"{\"id\":%d,\"t\":%d,\"w\":540,\"h\":1186,\"sw\":1080,\"sh\":2372,\"auto\":true,\"key\":0,\"allColours\":false,\"pro\":true,\"debug\":false}\n",++id,System.currentTimeMillis());
                    byte[] hb=h.getBytes(StandardCharsets.UTF_8);
                    out.writeInt(hb.length+jpg.length+1);out.writeByte('F');out.write(hb);out.write(jpg);out.flush();
                    Thread.sleep(33);
                }
                // report any action as done so the controller continues
                byte[] e="{\"type\":\"done\",\"id\":0,\"ok\":true,\"aim\":null}".getBytes(StandardCharsets.UTF_8);
                out.writeInt(e.length+1);out.writeByte('E');out.write(e);out.flush();
            }
            Thread.sleep(500);
        }
    }
}
