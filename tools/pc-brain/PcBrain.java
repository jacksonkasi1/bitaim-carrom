import com.bitaim.carromaim.cv.*;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/**
 * BitAim PC brain. The phone (PC brain switch ON, "adb reverse tcp:8765 tcp:8765") streams frames here; this program
 * runs the same detector, planner and auto-play decision code as the phone and sends back aim-guide readings and
 * slide/shot actions. Every frame (debug mode) and a shot log are saved under recordings/<session>/ on the PC.
 * Usage: java PcBrain [outDir]
 */
public class PcBrain {
    static final int PORT=Integer.getInteger("port",8765);
    public static void main(String[] args)throws Exception{
        nu.pattern.OpenCV.loadLocally();
        // Deeper search than the phone can afford (same code, larger limits).
        // 4.10.4: 200/40/1/24/3 took 2.1 s per plan on a real board and the turn ran out; this keeps a plan well under 1 s.
        RoutePlanner.CHANCE_SAMPLES=96;RoutePlanner.GOLD_STEPS=30;RoutePlanner.GOLD_SWEEP_EVERY=2;RoutePlanner.GOLD_VALIDATE=16;
        SweepPlanner.BUDGET_SCALE=1.5;
        File root=new File(args.length>0?args[0]:"recordings");
        try(ServerSocket server=new ServerSocket(PORT,1,InetAddress.getByName("127.0.0.1"))){
            log("PC brain listening on 127.0.0.1:"+PORT+" (adb reverse tcp:"+PORT+" tcp:"+PORT+")");
            while(true){
                Socket s=server.accept();s.setTcpNoDelay(true);
                log("phone connected");
                try{new Session(s,new File(root,new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()))).run();}
                catch(Throwable t){log("session ended: "+t);}
                log("phone disconnected");
            }
        }
    }
    static void log(String m){System.out.println(new SimpleDateFormat("HH:mm:ss.SSS").format(new Date())+" "+m);}

    static final class Plan{GameState planned;ShotPlanner.Shot route;float goldX=Float.NaN;ShotPlanner.Shot goldRoute;boolean goldReady;long signature;}

    static final class Session{
        final Socket socket;final File dir;final DataInputStream in;final PrintWriter out;
        final BoardDetector detector=new BoardDetector();final AutoPlayController auto=new AutoPlayController();
        final StrikerBaseline baseline=new StrikerBaseline();
        final ExecutorService planner=Executors.newSingleThreadExecutor();Future<?> planning;
        volatile Plan plan=new Plan();long planSignature=0;
        final ShotLogger logger;final PrintWriter events;
        long actionId=0;String lastStatus="";boolean armed=false;long lastFpsAt=System.currentTimeMillis();int fpsCount=0;
        double detectMs=0,planMs=0,quickMs=0;
        Session(Socket s,File dir)throws IOException{
            socket=s;this.dir=dir;new File(dir,"frames").mkdirs();
            in=new DataInputStream(new BufferedInputStream(s.getInputStream(),1<<16));
            out=new PrintWriter(new OutputStreamWriter(s.getOutputStream(),StandardCharsets.UTF_8),true);
            PrintWriter shots=new PrintWriter(new FileWriter(new File(dir,"shots.jsonl"),true),true);
            logger=new ShotLogger(shots::println);
            events=new PrintWriter(new FileWriter(new File(dir,"events.log"),true),true);
            log("recording to "+dir.getAbsolutePath());
        }
        void event(String m){events.println(new SimpleDateFormat("HH:mm:ss.SSS").format(new Date())+" "+m);}
        void send(String json){synchronized(out){out.println(json);}}
        void run()throws IOException{
            try{
                while(true){
                    int len=in.readInt();
                    if(len<2||len>8_000_000)throw new IOException("bad message length "+len+" (not a BitAim phone?)");
                    int type=in.readUnsignedByte();byte[] payload=new byte[len-1];in.readFully(payload);
                    if(type=='F')onFrame(payload);else if(type=='E')onEvent(new String(payload,StandardCharsets.UTF_8));
                }
            }finally{planner.shutdownNow();events.close();}
        }
        static String str(String json,String key){Matcher m=Pattern.compile("\""+key+"\":(\"[^\"]*\"|[^,}]+)").matcher(json);return m.find()?m.group(1).replace("\"",""):null;}
        static double num(String json,String key,double def){String v=str(json,key);if(v==null||v.equals("null"))return def;try{return Double.parseDouble(v);}catch(Exception e){return def;}}
        void onEvent(String json){
            if("done".equals(str(json,"type"))){
                boolean ok="true".equals(str(json,"ok"));auto.complete(ok);
                event("DONE "+json);
            }
        }
        void onFrame(byte[] payload){
            int nl=0;while(nl<payload.length&&payload[nl]!='\n')nl++;
            String header=new String(payload,0,nl,StandardCharsets.UTF_8);
            byte[] jpeg=Arrays.copyOfRange(payload,nl+1,payload.length);
            long now=System.currentTimeMillis();
            long seq=(long)num(header,"seq",0);
            try{onFrameBody(header,jpeg,now);}finally{if(seq>0)send("{\"type\":\"ack\",\"seq\":"+seq+"}");}
        }
        void onFrameBody(String header,byte[] jpeg,long now){
            long id=(long)num(header,"id",0);float w=(float)num(header,"w",540),h=(float)num(header,"h",1186),sw=(float)num(header,"sw",1080),sh=(float)num(header,"sh",2372);
            boolean wantAuto="true".equals(str(header,"auto")),debug="true".equals(str(header,"debug"));
            detector.setAllColours("true".equals(str(header,"allColours")));
            detector.setTargetColourKey((int)num(header,"key",TargetColourLock.AUTO));
            RoutePlanner.proStyle="true".equals(str(header,"pro"));
            if(wantAuto!=armed){armed=wantAuto;auto.setEnabled(armed);event("auto "+(armed?"ARMED":"off"));log("auto play "+(armed?"armed":"off"));}
            if(debug)try(FileOutputStream f=new FileOutputStream(new File(dir,"frames/"+id+"_"+now+".jpg"))){f.write(jpeg);}catch(IOException ignored){}
            long t0=System.nanoTime();
            Mat bgr=Imgcodecs.imdecode(new MatOfByte(jpeg),Imgcodecs.IMREAD_COLOR);if(bgr.empty())return;
            Mat rgba=new Mat();Imgproc.cvtColor(bgr,rgba,Imgproc.COLOR_BGR2RGBA);
            GameState s=detector.detectMat(rgba);
            detectMs=detectMs*.9+(System.nanoTime()-t0)/1e6*.1;
            if(s!=null){
                scale(s,sw/w,sh/h);s.frameId=id;
                steady(s);baseline.apply(s);DiscSeparation.apply(s);prev=s;
                if(s.aimGuideDots>0&&!Float.isNaN(s.aimGuideX)){
                    float[] d=AimGuideDetector.shotDirection(s.aimGuideX,s.aimGuideY);
                    send(String.format(Locale.US,"{\"type\":\"guide\",\"ux\":%.5f,\"uy\":%.5f,\"dots\":%d,\"sx\":%s,\"sy\":%s}",d[0],d[1],s.aimGuideDots,
                            s.strikerDetected?String.format(Locale.US,"%.1f",s.striker.pos.x):"null",s.strikerDetected?String.format(Locale.US,"%.1f",s.striker.pos.y):"null"));
                }
            }
            try{logger.feed(s,now);}catch(Throwable ignored){}
            if(s!=null&&s.strikerDetected&&s.teamKnown)maybePlan(s);
            Plan p=plan;
            if(p!=sentPlan){sentPlan=p;sendRoute(p.goldRoute!=null&&p.goldReady?p.goldRoute:p.route);}
            auto.setGoldReady(p.goldReady&&p.planned!=null);auto.setGoldRoute(p.goldRoute);
            AutoPlayController.Action a=auto.update(s,p.route,p.goldX,now,p.planned);
            String st=auto.status();
            if(!st.equals(lastStatus)){lastStatus=st;send("{\"type\":\"status\",\"text\":\""+st.replace("\"","'")+"\"}");event("status "+st);}
            String note=auto.consumeNote();if(note!=null)event(note);
            if(a!=null){
                long aid=++actionId;
                if(!a.move&&p.route!=null)logger.markAutoShot(Float.NaN,Float.NaN,Float.NaN,"{\"label\":\""+RoutePlanner.label(p.route).replace("\"","'")+"\",\"chance\":"+(Float.isNaN(p.route.chancePercent)?"null":String.valueOf(Math.round(p.route.chancePercent)))+",\"source\":\"pc\"}");
                send(String.format(Locale.US,"{\"type\":\"action\",\"id\":%d,\"move\":%b,\"x1\":%.1f,\"y1\":%.1f,\"x2\":%.1f,\"y2\":%.1f,\"aimBudget\":%d,\"aimX\":%s,\"aimY\":%s,\"status\":\"%s\"}",
                        aid,a.move,a.x1,a.y1,a.x2,a.y2,a.aimBudgetMs,Float.isNaN(a.aimX)?"null":String.format(Locale.US,"%.1f",a.aimX),Float.isNaN(a.aimY)?"null":String.format(Locale.US,"%.1f",a.aimY),st.replace("\"","'")));
                event(String.format(Locale.US,"ACTION %d %s from (%.0f,%.0f) to (%.0f,%.0f) %s",aid,a.move?"SLIDE":"SHOT",a.x1,a.y1,a.x2,a.y2,st));
                log((a.move?"slide ":"SHOT ")+st);
            }
            fpsCount++;
            if(now-lastFpsAt>=5000){log(String.format(Locale.US,"%.1f fps, detect %.0f ms, route %.0f ms, full plan %.0f ms, %s",fpsCount*1000.0/(now-lastFpsAt),detectMs,quickMs,planMs,st));fpsCount=0;lastFpsAt=now;}
        }
        GameState prev;Plan sentPlan;
        /** The chosen route as points for the phone overlay (striker path, coin legs, pocket, label with chance). */
        void sendRoute(ShotPlanner.Shot r){
            if(r==null||r.strikerPath.isEmpty()){send("{\"type\":\"route\",\"label\":\"\"}");return;}
            StringBuilder b=new StringBuilder("{\"type\":\"route\",\"striker\":[");
            for(int i=0;i<r.strikerPath.size();i++){if(i>0)b.append(',');b.append(String.format(Locale.US,"%.1f,%.1f",r.strikerPath.get(i).x,r.strikerPath.get(i).y));}
            b.append("],\"legs\":[");
            for(int i=0;i<r.chain.size()&&i<r.coinEndpoints.size();i++){if(i>0)b.append(',');b.append(String.format(Locale.US,"[%.1f,%.1f,%.1f,%.1f]",r.chain.get(i).pos.x,r.chain.get(i).pos.y,r.coinEndpoints.get(i).x,r.coinEndpoints.get(i).y));}
            if(r.targetBounce!=null&&r.pocket!=null)b.append(String.format(Locale.US,",[%.1f,%.1f,%.1f,%.1f]",r.targetBounce.x,r.targetBounce.y,r.pocket.x,r.pocket.y));
            b.append(']');
            if(r.pocket!=null)b.append(String.format(Locale.US,",\"pocket\":[%.1f,%.1f]",r.pocket.x,r.pocket.y));
            String label=RoutePlanner.label(r)+(Float.isNaN(r.chancePercent)?"":" • ~"+Math.round(r.chancePercent)+"%");
            b.append(",\"label\":\"").append(label.replace("\"","'")).append("\"}");
            send(b.toString());
        }
        /** Same frame-to-frame steadying as the phone overlay: an unchanged board edge, striker and discs keep their
         *  previous exact positions, so detection jitter does not look like movement (no endless re-planning). */
        void steady(GameState s){
            GameState p=prev;if(p==null||p.board==null||s.board==null)return;
            float w=s.board.width(),noise=w*.025f;
            if(Math.abs(s.board.width()-p.board.width())>p.board.width()*.25f)return;
            if(Math.abs(s.board.left-p.board.left)<noise&&Math.abs(s.board.top-p.board.top)<noise&&Math.abs(s.board.right-p.board.right)<noise&&Math.abs(s.board.bottom-p.board.bottom)<noise)
                s.board.set(p.board.left,p.board.top,p.board.right,p.board.bottom);
            if(s.striker!=null&&p.striker!=null&&s.strikerDetected&&p.strikerDetected){
                float d=(float)Math.hypot(s.striker.pos.x-p.striker.pos.x,s.striker.pos.y-p.striker.pos.y);
                if(d<s.striker.radius*.12f){s.striker.pos.set(p.striker.pos.x,p.striker.pos.y);s.striker.radius=p.striker.radius;}
            }
            for(Coin c:s.coins){Coin best=null;float bd=c.radius*.25f;
                for(Coin o:p.coins){float d=(float)Math.hypot(c.pos.x-o.pos.x,c.pos.y-o.pos.y);if(d<bd&&c.targetEligible==o.targetEligible){bd=d;best=o;}}
                if(best!=null){c.pos.set(best.pos.x,best.pos.y);c.radius=best.radius;}}
            if(s.pockets.size()==p.pockets.size())for(int i=0;i<s.pockets.size();i++){android.graphics.PointF a=s.pockets.get(i),b=p.pockets.get(i);if(Math.hypot(a.x-b.x,a.y-b.y)<w*.01f)a.set(b.x,b.y);}
        }
        void maybePlan(GameState s){
            long sig=PhysicsVerifier.signature(s)*31+Math.round(s.striker.pos.x/4f);
            if(sig==planSignature||(planning!=null&&!planning.isDone()))return;
            planSignature=sig;final GameState snap=s;
            planning=planner.submit(()->{
                long t0=System.nanoTime();
                Plan np=new Plan();np.planned=snap;np.signature=sig;
                List<ShotPlanner.Shot> routes=RoutePlanner.routes(snap,null);
                np.route=routes.isEmpty()?null:routes.get(0);
                // Two stages: the route from here is usable at once; the ★ search follows (goldReady=false meanwhile).
                plan=np;quickMs=quickMs*.7+(System.nanoTime()-t0)/1e6*.3;
                Plan full=new Plan();full.planned=snap;full.signature=sig;full.route=np.route;
                ShotPlanner.Shot[] best=new ShotPlanner.Shot[1];float[] score=new float[1];
                full.goldX=RoutePlanner.goldX(snap,null,score,best);full.goldRoute=best[0];full.goldReady=true;
                if(plan==np)plan=full;planMs=planMs*.7+(System.nanoTime()-t0)/1e6*.3;
            });
        }
        static void scale(GameState s,float sx,float sy){
            if(s.board!=null){s.board.left*=sx;s.board.right*=sx;s.board.top*=sy;s.board.bottom*=sy;}
            if(!Float.isNaN(s.sliderY))s.sliderY*=sy;
            if(s.striker!=null){s.striker.pos.x*=sx;s.striker.pos.y*=sy;s.striker.radius*=(sx+sy)*.5f;}
            for(Coin c:s.coins){c.pos.x*=sx;c.pos.y*=sy;c.radius*=(sx+sy)*.5f;}
            for(android.graphics.PointF p:s.pockets){p.x*=sx;p.y*=sy;}
        }
    }
}
