import com.bitaim.carromaim.cv.*;
import android.graphics.*;
import java.io.*;import java.nio.file.*;import java.util.*;

/**
 * Fits the physics model to real shots recorded by BitAim's ShotLogger (shots.jsonl, pulled with adb from
 * /sdcard/Android/data/com.bitaim.carromaim/files/). For every combination of drag retention, rail restitution
 * and pocket capture radius, each shot's launch speed is fitted; the combination with the lowest total error
 * (mean distance of settled discs plus pot-count mismatch) wins. Automatic shots also fit speed = k x pull.
 * Usage: java ShotLogCalibrator shots.jsonl
 */
public class ShotLogCalibrator {
    static class Shot { GameState before; double ux,uy; double[][] after; double autoPull=Double.NaN; int realPotted,extra; }
    // Start and settled positions cannot separate drag from launch speed (travel scales with v0/k), so drag stays
    // at the model's 0.70/s and the fit reports power in those units, plus rail restitution and pocket capture.
    static final double[] KEEP={.70},WALL={.85,.90,.95},CAPTURE={.035,.040,.045,.050};
    public static class Fit { public double keep,wall,capture,error;public double[] speeds;public double pullK=Double.NaN; }

    public static void main(String[] a) throws Exception {
        List<Shot> shots=new ArrayList<>();
        for(String line:Files.readAllLines(Paths.get(a[0])))if(line.trim().startsWith("{"))shots.add(parse(line));
        System.out.println("shots="+shots.size());
        if(shots.isEmpty())return;
        Fit f=fit(shots);
        System.out.printf(Locale.US,"BEST drag retention=%.2f/s rail=%.2f capture=%.3f board widths  mean error=%.2f%n",f.keep,f.wall,f.capture,f.error/shots.size());
        double[] sorted=f.speeds.clone();Arrays.sort(sorted);
        System.out.printf(Locale.US,"player launch speed (board widths/s): min %.1f  median %.1f  max %.1f%n",sorted[0],sorted[sorted.length/2],sorted[sorted.length-1]);
        if(!Double.isNaN(f.pullK))System.out.printf(Locale.US,"auto shots: speed = %.2f x pull (board widths)%n",f.pullK);
        for(int i=0;i<shots.size();i++)if(!Double.isNaN(shots.get(i).autoPull))System.out.printf(Locale.US,"  auto pull %.3f -> fitted speed %.3f (ratio %.2f) potted %d%n",shots.get(i).autoPull,f.speeds[i],f.speeds[i]/shots.get(i).autoPull,shots.get(i).realPotted);
        System.out.printf(Locale.US,"current model: retention 0.70, rail 0.90, capture 0.050%n");
    }

    public static Fit fit(List<Shot> shots){
        Fit best=null;
        for(double keep:KEEP)for(double wall:WALL)for(double cap:CAPTURE){
            Fit f=new Fit();f.keep=keep;f.wall=wall;f.capture=cap;f.speeds=new double[shots.size()];
            for(int i=0;i<shots.size();i++){double[] r=bestSpeed(shots.get(i),keep,wall,cap);f.error+=r[1];f.speeds[i]=r[0];}
            if(best==null||f.error<best.error)best=f;
        }
        double num=0,den=0;
        for(int i=0;i<shots.size();i++){double p=shots.get(i).autoPull;if(!Double.isNaN(p)&&p>0){num+=best.speeds[i]*p;den+=p*p;}}
        if(den>0)best.pullK=num/den;
        return best;
    }
    static double[] bestSpeed(Shot s,double keep,double wall,double cap){
        double best=1e9,speed=0;GameState g=s.before;
        for(double boards=.05;boards<=3.51;boards+=boards<.5?.025:.1){
            PhysicsVerifier.Outcome o=PhysicsVerifier.run(g,s.ux,s.uy,(float)(g.board.width()*boards),wall,-1,-Math.log(keep),cap);
            if(!o.completed&&!o.aborted)continue;
            int potted=0;List<double[]> sim=new ArrayList<>();
            for(int k=0;k<g.coins.size();k++){if(o.active[k])sim.add(new double[]{o.x[k],o.y[k],g.coins.get(k).color});else potted++;}
            double e=error(sim,s.after,s.extra)/g.striker.radius+Math.abs(potted-s.realPotted)*3;
            if(e<best){best=e;speed=boards;}
        }
        return new double[]{speed,best};
    }
    /** Mean nearest same-colour distance, ignoring the {@code skip} worst-matched observed discs (a resting striker). */
    static double error(List<double[]> sim,double[][] after,int skip){
        if(after.length==0)return 0;double[] d=new double[after.length];
        for(int i=0;i<after.length;i++){double[] c=after[i];double m=1e9;for(double[] p:sim)if((int)p[2]==(int)c[3])m=Math.min(m,Math.hypot(p[0]-c[0],p[1]-c[1]));d[i]=Math.min(m,c[2]*8);}
        Arrays.sort(d);int n=Math.max(1,d.length-skip);double sum=0;for(int i=0;i<n;i++)sum+=d[i];
        return sum/n;
    }

    // ── minimal parser for the fixed ShotLogger format ────────────────────────────────
    public static Shot parse(String line){
        Shot s=new Shot();GameState g=new GameState();
        double[] board=nums(field(line,"board")),st=nums(field(line,"striker")),aim=nums(field(line,"aim"));
        g.board=new RectF((float)board[0],(float)board[1],(float)(board[0]+board[2]),(float)(board[1]+board[3]));
        g.striker=new Coin((float)st[0],(float)st[1],(float)st[2],Coin.COLOR_STRIKER,true);g.strikerDetected=true;g.teamKnown=true;
        double[] pk=nums(field(line,"pockets"));for(int i=0;i+1<pk.length;i+=2)g.pockets.add(new PointF((float)pk[i],(float)pk[i+1]));
        double[] b=nums(field(line,"before"));
        for(int i=0;i+5<b.length;i+=6){Coin c=new Coin((float)b[i],(float)b[i+1],(float)b[i+2],(int)b[i+3],false);c.targetEligible=b[i+4]>0;c.isQueen=b[i+5]>0;c.transferEligible=true;g.coins.add(c);}
        double[] af=nums(field(line,"after"));s.after=new double[af.length/6][];
        for(int i=0;i<s.after.length;i++)s.after[i]=new double[]{af[i*6],af[i*6+1],af[i*6+2],af[i*6+3]};
        s.before=g;s.ux=aim[0];s.uy=aim[1];
        // Guide directions are used as recorded (downward bank shots are real).
        s.realPotted=Math.max(0,g.coins.size()-s.after.length);
        s.extra=Math.max(0,s.after.length-g.coins.size()); // resting striker detected as a disc
        String pull=field(line,"autoPull");if(pull!=null&&!pull.startsWith("null"))s.autoPull=nums(pull)[0];
        // The capture-side opponent-pot guard would abort replays; real outcomes may legitimately pot anything.
        for(Coin c:g.coins)c.targetEligible=true;
        return s;
    }
    static String field(String json,String name){
        int i=json.indexOf("\""+name+"\":");if(i<0)return null;i+=name.length()+3;
        if(json.charAt(i)!='['){int j=i;while(j<json.length()&&",}".indexOf(json.charAt(j))<0)j++;return json.substring(i,j);}
        int depth=0,j=i;for(;j<json.length();j++){char c=json.charAt(j);if(c=='[')depth++;else if(c==']'&&--depth==0)break;}
        return json.substring(i,j+1);
    }
    static double[] nums(String s){
        if(s==null)return new double[0];
        String[] parts=s.replaceAll("[\\[\\]]"," ").trim().split("[,\\s]+");List<Double> out=new ArrayList<>();
        for(String p:parts)if(!p.isEmpty()&&!p.equals("null"))out.add(Double.parseDouble(p));
        double[] r=new double[out.size()];for(int i=0;i<r.length;i++)r[i]=out.get(i);return r;
    }
}
