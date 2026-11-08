package com.bitaim.carromaim.cv;

import org.opencv.core.Mat;

/** Compare each puck's coloured rim with the two score-badge pucks.
 * The local player is displayed at the left. Unknown ownership never means all targets. */
public final class TeamMatcher {
    private static final int UNKNOWN=0, CHROMATIC=1, DARK=2, LIGHT=3;
    /** {@code striped}: a white-and-colour striped skin (live game 2026-11-07: opponent blue/white stripes vs our solid
     *  blue). Measured on 3 frames / 23 discs: striped discs have no deep colour in the centre (0%) and 41-62% bright
     *  ring pixels; solid discs have >=38% deep centre (even under a white overlay wedge) and <=25% bright ring. */
    private static class Profile { int kind; float hue,colourFraction,neutralFraction,brightFraction,coreDeep,value; boolean striped; }
    private final Profile own, opponent;
    public final boolean known;
    public final boolean freestyle;
    public TeamMatcher(Mat hsv) {
        float r=hsv.cols()*.024f;
        Profile left=badge(hsv,hsv.cols()*.11f,hsv.rows()*.168f,r);
        Profile right=badge(hsv,hsv.cols()*.89f,hsv.rows()*.168f,r);
        // Doubles displays the shared team badges below the inner two avatars.
        // Do not infer ownership from board colours or treat missing badges as all coins.
        if(!distinct(left,right)) {
            Profile doublesLeft=badge(hsv,hsv.cols()*.315f,hsv.rows()*.168f,r);
            Profile doublesRight=badge(hsv,hsv.cols()*.685f,hsv.rows()*.168f,r);
            if(distinct(doublesLeft,doublesRight)||left.kind==UNKNOWN&&doublesLeft.kind!=UNKNOWN){
                left=doublesLeft;right=doublesRight;
            }
        }
        // Freestyle has a red/white/black points legend instead of team badges.
        // Require all three circular swatches; absent team badges alone are not evidence.
        // The red swatch shares the red background hue; validate circular edges
        // with the contrasting white and black swatches instead.
        freestyle=freestyleLegend(hsv,.083f,r)||freestyleLegend(hsv,.271f,r);
        own=left;opponent=right;
        // A visible local badge is sufficient to match our colour when an
        // emote hides the opponent. Unmatched discs remain conservative obstacles.
        known=freestyle||own.kind!=UNKNOWN&&(opponent.kind==UNKNOWN||distinct(own,opponent));
    }
    private static boolean freestyleLegend(Mat hsv,float row,float r){
        Profile red=profile(hsv,hsv.cols()*.352f,hsv.rows()*row,r);
        Profile first=badge(hsv,hsv.cols()*.470f,hsv.rows()*row,r);
        Profile second=badge(hsv,hsv.cols()*.589f,hsv.rows()*row,r);
        return red.kind==CHROMATIC&&hueDistance(red.hue,0)<12
                &&(first.kind==LIGHT&&second.kind==DARK||first.kind==DARK&&second.kind==LIGHT);
    }
    private static boolean distinct(Profile a,Profile b){
        return a.kind!=UNKNOWN && b.kind!=UNKNOWN
                && (a.kind!=b.kind || a.kind==CHROMATIC && (hueDistance(a.hue,b.hue)>=7 || a.striped!=b.striped));
    }
    public float playerHue(){ return own.kind==CHROMATIC?own.hue:-1; }
    public float opponentHue(){ return opponent.kind==CHROMATIC?opponent.hue:-1; }
    public boolean distinctChromaticTeams(){return known&&own.kind==CHROMATIC&&opponent.kind==CHROMATIC;}
    public boolean eligible(Mat hsv,float x,float y,float r){
        return owner(hsv,x,y,r)==1;
    }
    public boolean isRedCoin(Mat hsv,float x,float y,float r){
        Profile coin=profile(hsv,x,y,r);
        return coin.kind==CHROMATIC&&hueDistance(coin.hue,0)<12;
    }
    public boolean isPlainQueen(Mat hsv,float x,float y,float r){
        float inner=r*.60f;int seen=0,red=0,dark=0;
        for(int py=Math.max(0,(int)(y-inner));py<=Math.min(hsv.rows()-1,(int)(y+inner));py++)
            for(int px=Math.max(0,(int)(x-inner));px<=Math.min(hsv.cols()-1,(int)(x+inner));px++){
                if((px-x)*(px-x)+(py-y)*(py-y)>inner*inner)continue;
                double[] c=hsv.get(py,px);seen++;
                if((c[0]<12||c[0]>168)&&c[1]>=90&&c[2]>=70)red++;
                if(c[2]<60)dark++;
            }
        return seen>=20&&red>seen*.65f&&dark<seen*.25f;
    }
    public int appearanceKey(Mat hsv,float x,float y,float r){
        Profile p=profile(hsv,x,y,r);
        // A thin green overlay from a neighbour must not turn a black puck green.
        if(p.neutralFraction>.60f&&p.brightFraction<.35f)return TargetColourLock.DARK;
        if(p.kind==DARK)return TargetColourLock.DARK;
        if(p.kind==LIGHT)return TargetColourLock.LIGHT;
        if(p.kind==CHROMATIC)return TargetColourLock.chromatic(p.hue,p.striped);
        return TargetColourLock.AUTO;
    }
    /** Mean brightness of a solid chromatic disc's colour pixels, or -1 (used to tell two shades of one hue apart). */
    public float appearanceValue(Mat hsv,float x,float y,float r){
        Profile p=profile(hsv,x,y,r);if(p.kind!=CHROMATIC||p.striped)return -1f;
        // The disc face (0.2-0.55 r), not the rim: both blues share a bright bevelled rim (ring 226-234) while the faces
        // differ (light 255, dark 140).
        float sum=0;int n=0;float r0=r*.2f,r1=r*.55f;
        for(int py=Math.max(0,(int)(y-r1));py<=Math.min(hsv.rows()-1,(int)(y+r1));py++)
            for(int px=Math.max(0,(int)(x-r1));px<=Math.min(hsv.cols()-1,(int)(x+r1));px++){
                float d=(px-x)*(px-x)+(py-y)*(py-y);if(d<r0*r0||d>r1*r1)continue;
                double[] c=hsv.get(py,px);if(c[1]>=90&&hueDistance((float)c[0],p.hue)<=15){sum+=(float)c[2];n++;}
            }
        return n<6?-1f:sum/n;
    }
    public boolean isYellowCoin(Mat hsv,float x,float y,float r){
        Profile coin=profile(hsv,x,y,r);
        return coin.kind==CHROMATIC&&coin.hue>=25&&coin.hue<=40;
    }
    public int owner(Mat hsv,float x,float y,float r){
        if(!known)return 0;
        Profile coin=profile(hsv,x,y,r);
        if(freestyle)return coin.kind==LIGHT||coin.kind==DARK
                ||coin.kind==CHROMATIC&&hueDistance(coin.hue,0)<12?1:0;
        if(matches(coin,own,opponent))return 1;
        if(matches(coin,opponent,own))return 2;
        return 0;
    }
    private static boolean matches(Profile coin,Profile team,Profile other){
        if(coin.kind!=team.kind)return false;
        if(coin.kind!=CHROMATIC)return coin.kind!=UNKNOWN;
        if(coin.striped!=team.striped)return false; // same hue, different skin: striped vs solid
        float d=hueDistance(coin.hue,team.hue);
        return d<=12 && (other.kind!=CHROMATIC || d+3<hueDistance(coin.hue,other.hue));
    }
    private static float hueDistance(float a,float b){ float d=Math.abs(a-b);return Math.min(d,180-d); }
    private static Profile badge(Mat hsv,float x,float y,float r){
        Profile result=profile(hsv,x,y,r);if(result.kind==UNKNOWN)return result;
        if(result.kind==CHROMATIC&&result.colourFraction<(result.striped?.25f:.45f)){result.kind=UNKNOWN;return result;}
        int different=0,seen=0;
        for(int i=0;i<24;i++){
            double angle=i*Math.PI/12;int px=Math.round(x+r*1.5f*(float)Math.cos(angle)),py=Math.round(y+r*1.5f*(float)Math.sin(angle));
            if(px<0||py<0||px>=hsv.cols()||py>=hsv.rows())continue;
            double[] c=hsv.get(py,px);seen++;
            if(result.kind==CHROMATIC?(c[1]<90||hueDistance((float)c[0],result.hue)>15)
                    :result.kind==LIGHT?(c[1]>=90||c[2]<160):(c[1]>=90||c[2]>165))different++;
        }
        // A flat chat bubble is not a circular score puck.
        if(seen==0||different<seen*.45f)result.kind=UNKNOWN;
        return result;
    }
    /** White/colour changes on a circle at 0.62 r (72 samples). */
    private static int stripeCrossings(Mat hsv,float x,float y,float r){
        int changes=0;Boolean previous=null;
        for(int i=0;i<72;i++){
            double angle=i*Math.PI/36;int px=Math.round(x+r*.62f*(float)Math.cos(angle)),py=Math.round(y+r*.62f*(float)Math.sin(angle));
            if(px<0||py<0||px>=hsv.cols()||py>=hsv.rows())continue;
            double[] c=hsv.get(py,px);boolean white=c[1]<90&&c[2]>165;
            if(previous!=null&&white!=previous)changes++;previous=white;
        }
        return changes;
    }
    private static Profile profile(Mat hsv,float x,float y,float r){
        Profile result=new Profile();float[] histogram=new float[36],valueSum=new float[36];int[] valueCount=new int[36];
        int total=0,colour=0,neutral=0,bright=0,dark=0,core=0,deep=0;
        int left=Math.max(0,(int)(x-r)),right=Math.min(hsv.cols()-1,(int)(x+r));
        int top=Math.max(0,(int)(y-r)),bottom=Math.min(hsv.rows()-1,(int)(y+r));
        for(int py=top;py<=bottom;py++)for(int px=left;px<=right;px++){
            float d=(px-x)*(px-x)+(py-y)*(py-y);
            if(d<r*r*.16f){double[] c=hsv.get(py,px);core++;if(c[1]>=90&&c[2]<150)deep++;continue;}
            if(d<r*r*.20f || d>r*r*.90f)continue;
            total++;double[] c=hsv.get(py,px);float h=(float)c[0],s=(float)c[1],v=(float)c[2];
            if(s>=90 && v>=70 && !(h>=6&&h<25)){
                int bin=Math.min(35,(int)(h/5));histogram[bin]+=s*v;valueSum[bin]+=v;valueCount[bin]++;colour++;
            }else if(s<90){neutral++;if(v>165)bright++;if(v<70)dark++;}
        }
        if(total==0)return result;
        result.colourFraction=colour/(float)total;
        result.neutralFraction=neutral/(float)total;result.brightFraction=bright/(float)total;
        if(colour>total*.14f){
            int peak=0;float weight=-1;
            for(int i=0;i<36;i++){
                float sum=histogram[(i+35)%36]+histogram[i]*2+histogram[(i+1)%36];
                if(sum>weight){weight=sum;peak=i;}
            }
            result.kind=CHROMATIC;result.hue=peak*5+2.5f;
            {float vs=0;int vc=0;for(int k=-1;k<=1;k++){int b=(peak+k+36)%36;vs+=valueSum[b];vc+=valueCount[b];}result.value=vc==0?0:vs/vc;}
            result.coreDeep=core==0?1f:deep/(float)core;
            // A single white overlay band or wedge across a solid disc also hides its centre: real stripes cross the
            // ring 6-10 times (one band 2-4) and leave <=39% colour (one band 55%).
            result.striped=result.coreDeep<.20f&&result.brightFraction>=.35f&&(stripeCrossings(hsv,x,y,r)>=6||result.colourFraction<.45f);
        }else if(neutral>total*.45f){
            // Black discs can carry a large white highlight (spin/shine): measured on live frames,
            // white discs have <=5% very dark ring pixels and black discs >=44%.
            result.kind=dark>total*.25f?DARK:bright>total*.35f?LIGHT:DARK;
        }
        return result;
    }
}
