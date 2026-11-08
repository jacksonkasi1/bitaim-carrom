import com.bitaim.carromaim.cv.*;
import org.opencv.core.*;import org.opencv.imgcodecs.Imgcodecs;import org.opencv.imgproc.Imgproc;
import java.io.*;import java.util.*;

/**
 * Real-shot calibration from a screenshot sequence (about one frame per second).
 * For every local turn: the last pre-shot frame with the game's white aim dots gives the real launch
 * direction; the first settled frame after the striker leaves gives the real outcome. The direction is
 * replayed through the production physics model at launch speeds 0.4 to 3.2 board widths per second;
 * the best-matching speed, the remaining position error and whether the model reproduces the real pots
 * are reported. Usage: java ShotOutcomeAnalyzer <frameDir> [targetColourKey]
 */
public class ShotOutcomeAnalyzer {
    static class Frame { String name; GameState s; }
    public static void main(String[] a) throws Exception {
        nu.pattern.OpenCV.loadLocally();
        File dir=new File(a[0]);int key=a.length>1?Integer.parseInt(a[1]):TargetColourLock.AUTO;
        File[] files=dir.listFiles((d,n)->n.endsWith(".png")||n.endsWith(".jpg"));Arrays.sort(files);
        BoardDetector det=new BoardDetector();det.setTargetColourKey(key);
        List<Frame> frames=new ArrayList<>();
        for(File f:files){Mat bgr=Imgcodecs.imread(f.getPath());if(bgr.empty())continue;Mat rgba=new Mat();Imgproc.cvtColor(bgr,rgba,Imgproc.COLOR_BGR2RGBA);
            Frame fr=new Frame();fr.name=f.getName();fr.s=det.detectMat(rgba);frames.add(fr);bgr.release();rgba.release();}
        det.close();
        System.out.println("frames="+frames.size()+" withBoard="+frames.stream().filter(f->f.s!=null).count()+" localTurn="+frames.stream().filter(f->f.s!=null&&f.s.strikerDetected).count());
        PrintWriter csv=new PrintWriter(new FileWriter(new File(dir,"shots.csv")));
        csv.println("before,after,dots,bestSpeedBoards,meanErrorRadii,realPotted,modelPotted,potsMatch,suggested,suggestedTargetPotted");
        int shots=0;double errSum=0;int potMatch=0;List<Double> speeds=new ArrayList<>();
        for(int i=0;i<frames.size();i++){
            Frame f=frames.get(i);
            if(f.s==null||!f.s.strikerDetected||f.s.aimGuideDots<6)continue;
            // last aimed frame of this turn: the next frame no longer shows a local, aimed, unmoved striker
            Frame nx=i+1<frames.size()?frames.get(i+1):null;
            boolean stillAiming=nx!=null&&nx.s!=null&&nx.s.strikerDetected&&nx.s.aimGuideDots>=6&&dist(nx.s.striker,f.s.striker)<f.s.striker.radius*.5f;
            if(stillAiming)continue;
            // settled outcome: first later frame (within 8 s) whose disc layout equals the following frame's
            Frame after=null;
            for(int j=i+2;j<Math.min(frames.size()-1,i+10);j++){
                GameState x=frames.get(j).s,y=frames.get(j+1).s;
                if(x!=null&&y!=null&&sameLayout(x,y)){after=frames.get(j);break;}
            }
            if(after==null)continue;
            GameState s=f.s;float[] shotDir=AimGuideDetector.shotDirection(s.aimGuideX,s.aimGuideY);double ux=shotDir[0],uy=shotDir[1];
            List<ShotPlanner.Shot> suggested=RoutePlanner.routes(s,null);
            ShotPlanner.Shot top=suggested.isEmpty()?null:suggested.get(0);
            int realPotted=Math.max(0,s.coins.size()-after.s.coins.size());
            double best=1e9,bestSpeed=0;int bestModelPotted=0;
            for(double boards=.4;boards<=3.21;boards+=.1){
                PhysicsVerifier.Outcome o=PhysicsVerifier.run(s,ux,uy,(float)(s.board.width()*boards),.90,-1);
                if(!o.completed&&!o.aborted)continue;
                int modelPotted=0;List<double[]> sim=new ArrayList<>();
                for(int k=0;k<s.coins.size();k++){if(o.active[k])sim.add(new double[]{o.x[k],o.y[k],s.coins.get(k).color});else modelPotted++;}
                double e=layoutError(sim,after.s)/s.striker.radius*1.25+Math.abs(modelPotted-realPotted)*3;
                if(e<best){best=e;bestSpeed=boards;bestModelPotted=modelPotted;}
            }
            boolean targetPotted=false;
            if(top!=null){targetPotted=true;for(Coin c:after.s.coins)if(Math.hypot(c.pos.x-top.coin.pos.x,c.pos.y-top.coin.pos.y)<c.radius)targetPotted=false;}
            shots++;errSum+=best;if(bestModelPotted==realPotted)potMatch++;speeds.add(bestSpeed);
            csv.printf(Locale.US,"%s,%s,%d,%.1f,%.2f,%d,%d,%b,%s,%b%n",f.name,after.name,s.aimGuideDots,bestSpeed,best,realPotted,bestModelPotted,bestModelPotted==realPotted,top==null?"none":RoutePlanner.label(top).replace(',',' '),targetPotted);
            System.out.printf(Locale.US,"SHOT %s -> %s  dots=%d  bestSpeed=%.1f boards/s  error=%.2f  real potted=%d model potted=%d  suggestion=%s targetPotted=%b%n",f.name,after.name,s.aimGuideDots,bestSpeed,best,realPotted,bestModelPotted,top==null?"none":RoutePlanner.label(top),targetPotted);
        }
        csv.close();
        Collections.sort(speeds);
        System.out.printf(Locale.US,"SUMMARY shots=%d meanError=%.2f potCountMatch=%d/%d medianSpeed=%.1f%n",shots,shots==0?0:errSum/shots,potMatch,shots,speeds.isEmpty()?0:speeds.get(speeds.size()/2));
    }
    static float dist(Coin a,Coin b){return (float)Math.hypot(a.pos.x-b.pos.x,a.pos.y-b.pos.y);}
    static boolean sameLayout(GameState x,GameState y){
        if(x.coins.size()!=y.coins.size()||x.coins.isEmpty())return false;
        for(Coin c:x.coins){boolean f=false;for(Coin d:y.coins)if(Math.hypot(c.pos.x-d.pos.x,c.pos.y-d.pos.y)<c.radius*.3f){f=true;break;}if(!f)return false;}
        return true;
    }
    /** Mean distance from each observed disc to the nearest simulated disc of the same colour (pixels). */
    static double layoutError(List<double[]> sim,GameState after){
        if(after.coins.isEmpty())return 0;double sum=0;
        for(Coin c:after.coins){double m=1e9;for(double[] p:sim)if((int)p[2]==c.color)m=Math.min(m,Math.hypot(p[0]-c.pos.x,p[1]-c.pos.y));sum+=Math.min(m,c.radius*8);}
        return sum/after.coins.size();
    }
}
