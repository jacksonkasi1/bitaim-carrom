package com.bitaim.carromaim.cv;

/**
 * 4.9.8: two discs can never be closer than the sum of their radii, but detection placed a black and the queen
 * 49 px apart with radii of 31 (screenshot 10:54:30): every shot involving that touching pair was planned on an
 * impossible layout. Overlapping discs are pushed apart symmetrically to touching distance before planning.
 */
public final class DiscSeparation {
    private DiscSeparation(){}
    /** Returns the number of overlapping pairs corrected. */
    public static int apply(GameState s){
        if(s==null||s.coins.size()<2)return 0;
        int fixed=0;
        for(int it=0;it<6;it++){
            boolean any=false;
            for(int i=0;i<s.coins.size();i++)for(int j=i+1;j<s.coins.size();j++){
                Coin a=s.coins.get(i),b=s.coins.get(j);
                float dx=b.pos.x-a.pos.x,dy=b.pos.y-a.pos.y,d=(float)Math.hypot(dx,dy),m=a.radius+b.radius;
                // Only real overlaps (>= 0.15 radius deep, <= 1 radius): deeper means a duplicate detection, not two discs.
                if(d>=m-a.radius*.15f||d<a.radius*.9f)continue;
                float k=(m-d)/2f/d;a.pos.x-=dx*k;a.pos.y-=dy*k;b.pos.x+=dx*k;b.pos.y+=dy*k;any=true;if(it==0)fixed++;
            }
            if(!any)break;
        }
        return fixed;
    }
}
