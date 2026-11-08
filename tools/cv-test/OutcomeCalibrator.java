import com.bitaim.carromaim.cv.*;
import java.nio.file.*;import java.util.*;

/**
 * Pot-or-miss calibration against real auto shots (shots.jsonl). Each shot is replayed with the game's own aim
 * direction and launch speed = k x pull; the model's pot/miss for the planned target is compared with what really
 * happened. Searches k, rail restitution, striker along-rail loss and pocket capture for the best agreement.
 * Usage: java OutcomeCalibrator shots.jsonl
 */
public class OutcomeCalibrator {
    static class S{GameState g;double ux,uy,pull;int target;boolean real;String label;int kind;}
    static double[] parse(String s){String[] p=s.split(",");double[] r=new double[p.length];for(int i=0;i<p.length;i++)r[i]=Double.parseDouble(p[i]);return r;}
    public static void main(String[] a)throws Exception{
        List<S> shots=new ArrayList<>();
        for(String line:Files.readAllLines(Paths.get(a[0]))){
            if(!line.startsWith("{")||!line.contains("\"autoRoute\":{"))continue;
            long t=(long)ShotLogCalibrator.nums(ShotLogCalibrator.field(line,"t"))[0];
            int key=(int)ShotLogCalibrator.nums(ShotLogCalibrator.field(line,"key"))[0];
            // reliable labels: black/white practice (key 1/2) or after the appearance-key outcome fix (00:10)
            if(!(key==1||key==2||t>=1794078600000L))continue;
            String pull=ShotLogCalibrator.field(line,"autoPull");if(pull==null||pull.startsWith("null"))continue;
            String own=ShotLogCalibrator.field(line,"ownPotted");if(own==null)continue;
            int ri=line.indexOf("\"autoRoute\":");String route=line.substring(ri);
            double[] tg=ShotLogCalibrator.nums(ShotLogCalibrator.field(route,"target"));
            String kind=ShotLogCalibrator.field(route,"kind");
            ShotLogCalibrator.Shot sh=ShotLogCalibrator.parse(line);
            S s=new S();s.g=sh.before;s.ux=sh.ux;s.uy=sh.uy;s.pull=ShotLogCalibrator.nums(pull)[0];
            if(Double.isNaN(s.ux)||Math.hypot(s.ux,s.uy)<.5)continue;
            s.kind=kind==null?-1:(int)ShotLogCalibrator.nums(kind)[0];if(s.kind>=4)continue; // openers have no pot target
            double bd=1e9;for(int i=0;i<s.g.coins.size();i++){Coin c=s.g.coins.get(i);double d=Math.hypot(c.pos.x-tg[0],c.pos.y-tg[1]);if(d<bd){bd=d;s.target=i;}}
            if(bd>20)continue;
            s.real=ShotLogCalibrator.nums(own)[0]>=1&&!line.contains("\"target\":\"not-moved\"");
            shots.add(s);
        }
        int realPots=0;for(S s:shots)if(s.real)realPots++;
        System.out.println("auto pot attempts with reliable labels: "+shots.size()+", really potted: "+realPots);
        double[] K={3.0,3.5,4.0,4.5,5.0,5.5,6.0,7.0},WALL={.80,.85,.90},TAN={1.0,.90,.76},CAP={.035,.042,.050},A={0};
        double drag=-Math.log(.70);
        if(System.getProperty("coulomb")!=null){A=parse(System.getProperty("coulomb"));K=parse(System.getProperty("K","8,9,10,11,12,13"));drag=Double.parseDouble(System.getProperty("drag","0"));
            WALL=parse(System.getProperty("wall","0.7,0.8,0.9"));}
        double best=-1;String bestDesc="";
        List<String> rows=new ArrayList<>();
        for(double ca:A)for(double k:K)for(double wall:WALL)for(double tan:TAN)for(double cap:CAP){
            PhysicsVerifier.STRIKER_RAIL_TANGENT=tan;
            int agree=0,tp=0,fp=0,fn=0;
            for(S s:shots){
                float speed=(float)(k*s.pull*s.g.board.width());
                PhysicsVerifier.Outcome o=PhysicsVerifier.run(s.g,s.ux,s.uy,speed,wall,-1,drag,cap,ca);
                boolean model=o.completed&&!o.aborted&&!o.active[s.target];
                if(model==s.real)agree++;if(model&&s.real)tp++;if(model&&!s.real)fp++;if(!model&&s.real)fn++;
            }
            double acc=agree/(double)shots.size();
            rows.add(String.format(Locale.US,"%.3f a=%.2f k=%.1f rail=%.2f strikerAlong=%.2f capture=%.3f  (model pots: right %d, wrong %d, missed real %d)",acc,ca,k,wall,tan,cap,tp,fp,fn));
            if(acc>best){best=acc;bestDesc=rows.get(rows.size()-1);}
        }
        Collections.sort(rows,Collections.reverseOrder());
        for(int i=0;i<Math.min(8,rows.size());i++)System.out.println("  "+rows.get(i));
        for(String r:rows)if(r.contains("k=5.0 rail=0.90 strikerAlong=1.00 capture=0.050"))System.out.println("current model: "+r);
        PhysicsVerifier.STRIKER_RAIL_TANGENT=1.0;
    }
}
