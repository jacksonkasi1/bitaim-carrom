package com.bitaim.carromaim.cv;

/**
 * 4.9.4: the striker never leaves its baseline while it is placed and aimed, but its detected centre jitters under the
 * glow ring: 400+ logged auto shots on this phone show y clustered at 1571-1572 px with frequent readings at 1563-1564
 * and 1578-1581. With the first disc 120 px away, 8 px is a 4 degree aim error (live 10:12:50, "100%" pot measured
 * 6.9 degrees off and missed). The running median of recent baseline readings replaces any reading close to it.
 */
public final class StrikerBaseline {
    private final float[] ys=new float[31];
    private int n,idx;private float boardW=-1;
    public synchronized void reset(){n=idx=0;boardW=-1;}
    /** Snaps {@code s.striker.pos.y} to the baseline median; returns the correction applied in pixels. */
    public synchronized float apply(GameState s){
        if(s==null||s.board==null||s.striker==null||!s.strikerDetected)return 0f;
        float w=s.board.width(),y=s.striker.pos.y,r=s.striker.radius;
        if(y<(s.board.top+s.board.bottom)/2f)return 0f; // only the bottom (own) baseline
        if(boardW<0||Math.abs(w-boardW)>w*.08f){n=idx=0;boardW=w;}
        float med=n>=5?median():Float.NaN;
        // Keep the history clean: a reading far from the baseline (striker moving, finger) is not recorded.
        if(Float.isNaN(med)||Math.abs(y-med)<r*.6f){ys[idx]=y;idx=(idx+1)%ys.length;if(n<ys.length)n++;}
        if(n<5)return 0f;
        med=median();
        if(Math.abs(y-med)>=r*.45f)return 0f;
        s.striker.pos.y=med;return med-y;
    }
    private float median(){float[] c=java.util.Arrays.copyOf(ys,n);java.util.Arrays.sort(c);return n%2==1?c[n/2]:(c[n/2-1]+c[n/2])/2f;}
}
