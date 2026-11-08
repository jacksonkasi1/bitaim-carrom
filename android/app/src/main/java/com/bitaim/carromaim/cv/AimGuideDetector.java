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
