import com.bitaim.carromaim.cv.*;import org.opencv.core.*;import org.opencv.imgproc.Imgproc;
/** 4.13.0: the game's red power arrowhead is measured along the aim line (not the board's painted red circles, not a
 *  red disc off the line), and the meter zones follow the planned power: green within 8%, orange within 18%. */
public class PowerMeterRegression{static void check(boolean v,String m){if(!v)throw new AssertionError(m);}
 static Mat scene(float sx,float sy,float ux,float uy,float tip,boolean paint){
  Mat bgr=new Mat(900,720,CvType.CV_8UC3,new Scalar(120,175,215)); // light wood
  Imgproc.circle(bgr,new Point(sx,sy),18,new Scalar(60,60,60),-1); // striker
  // saturated shaft from the striker's edge (yellow when soft, orange-red when hard), BitAim's cyan line over its middle
  Scalar shaft=tip<100?new Scalar(0,210,250):new Scalar(0,110,255);
  Imgproc.line(bgr,new Point(sx+ux*20,sy+uy*20),new Point(sx+ux*(tip-10),sy+uy*(tip-10)),shaft,7);
  Imgproc.line(bgr,new Point(sx+ux*20,sy+uy*20),new Point(sx+ux*(tip+150),sy+uy*(tip+150)),new Scalar(255,229,0),2);
  float px=-uy,py=ux;
  MatOfPoint head=new MatOfPoint(new Point(sx+ux*tip,sy+uy*tip),new Point(sx+ux*(tip-14)+px*7,sy+uy*(tip-14)+py*7),new Point(sx+ux*(tip-14)-px*7,sy+uy*(tip-14)-py*7));
  Imgproc.fillPoly(bgr,java.util.Arrays.asList(head),new Scalar(20,20,250));
  if(paint)Imgproc.circle(bgr,new Point(sx+ux*(tip+60),sy+uy*(tip+60)),14,new Scalar(40,40,150),-1); // painted circle beyond the tip (dark, flat)
  Imgproc.circle(bgr,new Point(sx+px*120,sy+py*120),14,new Scalar(20,20,250),-1); // a red disc off the aim line
  Mat hsv=new Mat();Imgproc.cvtColor(bgr,hsv,Imgproc.COLOR_BGR2HSV);return hsv;}
 public static void main(String[] a){nu.pattern.OpenCV.loadLocally();
  float bw=600;
  for(float tip:new float[]{60,110,170}){
   float ux=.42f,uy=-.9075f;Mat hsv=scene(360,700,ux,uy,tip,true);
   float got=AimGuideDetector.powerArrow(hsv,360,700,ux,uy,bw,18);
   check(!Float.isNaN(got)&&Math.abs(got*bw-tip)<4,"arrowhead at "+tip+" px measured "+got*bw);
  }
  Mat none=new Mat(900,720,CvType.CV_8UC3,new Scalar(120,175,215));Mat h2=new Mat();Imgproc.cvtColor(none,h2,Imgproc.COLOR_BGR2HSV);
  check(Float.isNaN(AimGuideDetector.powerArrow(h2,360,700,0,-1,bw,18)),"no arrow, no power");
  float target=.20f;
  check(PowerMeter.zone(.205f,target)==PowerMeter.ZONE_GREEN,"within 8% is green");
  check(PowerMeter.zone(.17f,target)==PowerMeter.ZONE_ORANGE,"15% weak is orange");
  check(PowerMeter.zone(.25f,target)==PowerMeter.ZONE_RED,"25% strong is red");
  check(PowerMeter.pullFromArrow(.26f)==PowerMeter.FULL_PULL&&PowerMeter.zone(.25f,.27f)==PowerMeter.ZONE_GREEN,"the arrow tops out at full power; a full pull matches a route verified above it");
  check(PowerMeter.advice(.15f,target).contains("MORE")&&PowerMeter.advice(.25f,target).contains("LESS")&&PowerMeter.advice(.2f,target).startsWith("HOLD"),"advice direction");
  ShotPlanner.Shot r=new ShotPlanner.Shot();r.kind=0;r.assumedLaunchSpeed=AutoPlayController.LAUNCH_PER_PULL*.2f*600;
  check(Math.abs(PowerMeter.targetPull(r,600)-.2f)<1e-4,"target pull from the verified launch speed");
  System.out.println("PASS: power arrow measured at 60 (yellow) / 110 / 170 px (orange-red) under BitAim's cyan line (painted circle and off-line red disc ignored), meter zones green/orange/red, full-power cap and advice");}}
