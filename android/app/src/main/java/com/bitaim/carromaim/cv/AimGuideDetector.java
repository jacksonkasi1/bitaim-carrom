package com.bitaim.carromaim.cv;

import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import java.util.*;

/** Read the game's small white guide dots; never infer a direction from our cyan overlay. */
public final class AimGuideDetector {
    /** Launch direction from the game's guide. The bright white dots are the shot line; the finger's pull-back
     *  trail is dimmer and is not detected. Downward bank shots exist (debug pictures 2026-11-07 21:49 show the
     *  game's arrow and white dots pointing down), so the guide is used as-is. */
    public static float[] shotDirection(float gx,float gy){
        return new float[]{gx,gy};
    }
    /** 4.13.0: length of the game's power arrow in front of the striker along the aim direction, in board widths
     *  (NaN when none). The arrow grows with the pull and changes colour with it (yellow / green soft, orange, red at
     *  full power); it stops growing at about 0.245 board widths (full power; 403 logged pulls). It is a strongly
     *  saturated warm colour (hue 0-27 / 170-180, sat 200+, value 130+), unlike the wood (sat ~100-150). It starts at
     *  the striker's edge and runs without long gaps (BitAim's own cyan line may cover its middle, hence the band
     *  across the line and the bridged gaps); a red disc or painted circle further along the line is a separate run. */
    public static float powerArrow(Mat hsv,float sx,float sy,float ux,float uy,float bw,float strikerR){
        return powerArrow(hsv,sx,sy,ux,uy,bw,strikerR,null);
    }
    /** @param board processing-pixel board rectangle {left, top, right, bottom}; the scan stops at its edge (the power
     *  bar under the board is never read), or null. Lengths over 0.30 board widths are not the game's arrow (it tops out
     *  near 0.25-0.28): NaN. */
    public static float powerArrow(Mat hsv,float sx,float sy,float ux,float uy,float bw,float strikerR,float[] board){
        if(Float.isNaN(ux)||Float.isNaN(uy))return Float.NaN;
        float l=(float)Math.hypot(ux,uy);if(l<1e-3f)return Float.NaN;ux/=l;uy/=l;
        float px=-uy,py=ux,band=Math.max(2f,bw*.008f),gapMax=Math.max(5f,bw*.035f);
        int rows=hsv.rows(),cols=hsv.cols();byte[] pix=new byte[3];
        float last=Float.NaN;int steps=0;
        for(float t=strikerR*.8f;t<bw*.40f;t+=1f){
            if(board!=null){float cx=sx+ux*t,cy=sy+uy*t;if(cx<board[0]||cx>board[2]||cy<board[1]||cy>board[3])break;}
            int hits=0;
            for(float a=-band;a<=band;a+=1f){
                int x=Math.round(sx+ux*t+px*a),y=Math.round(sy+uy*t+py*a);
                if(x<0||y<0||x>=cols||y>=rows)continue;
                hsv.get(y,x,pix);int h=pix[0]&0xff,sat=pix[1]&0xff,v=pix[2]&0xff;
                if((h<=27||h>=170)&&sat>=200&&v>=130)hits++;
            }
            if(hits>=2){last=t;steps++;}
            else if(Float.isNaN(last)){if(t>strikerR*2.5f)return Float.NaN;} // the arrow starts at the striker's edge
            else if(t-last>gapMax)break;
        }
        if(Float.isNaN(last)||steps<Math.max(4f,bw*.02f))return Float.NaN;
        return last/bw>.30f?Float.NaN:last/bw;
    }
    public static float[] detect(Mat hsv,float sx,float sy,float boardWidth,float boardTop){
        Mat mask=new Mat(),hierarchy=new Mat();List<MatOfPoint> contours=new ArrayList<>();
        Core.inRange(hsv,new Scalar(0,0,225),new Scalar(180,50,255),mask);
        Imgproc.findContours(mask,contours,hierarchy,Imgproc.RETR_EXTERNAL,Imgproc.CHAIN_APPROX_SIMPLE);
        List<float[]> dots=new ArrayList<>();
        for(MatOfPoint contour:contours){
            Rect r=Imgproc.boundingRect(contour);float x=r.x+r.width*.5f,y=r.y+r.height*.5f;
            double distance=Math.hypot(x-sx,y-sy),area=Imgproc.contourArea(contour);
            if(r.width>=2&&r.height>=2&&r.width<=boardWidth*.022f&&r.height<=boardWidth*.022f
                    &&r.width<r.height*1.7f&&r.height<r.width*1.7f&&area>=r.width*r.height*.4
                    &&y>boardTop&&y<hsv.rows()*.94f&&distance>boardWidth*.045f&&distance<boardWidth*.95f)
                dots.add(new float[]{x-sx,y-sy});
            contour.release();
        }
        mask.release();hierarchy.release();
        float[] best=null;int bestCount=5;float bestSpan=0;
        for(float[] candidate:dots){
            float length=(float)Math.hypot(candidate[0],candidate[1]),ux=candidate[0]/length,uy=candidate[1]/length;
            int count=0;float min=Float.MAX_VALUE,max=0;
            for(float[] p:dots){float along=p[0]*ux+p[1]*uy,across=Math.abs(p[0]*uy-p[1]*ux);
                if(along>0&&across<boardWidth*.006f){count++;min=Math.min(min,along);max=Math.max(max,along);}
            }
            if(count<6||max-min<boardWidth*.14f)continue;
            if(count>bestCount||count==bestCount&&max-min>bestSpan){bestCount=count;bestSpan=max-min;best=new float[]{ux,uy,count};}
        }
        return best;
    }
}
