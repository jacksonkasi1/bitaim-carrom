import com.bitaim.carromaim.cv.*;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

/** 4.11.0 (live 19:28-19:30): the in-game chat panel over the left half of the board hid half the discs; that frame
 *  gave a false "8 potted" shot result and a debug picture with the chat. A board that suddenly shows much less wood
 *  than a moment ago is flagged as covered; boards with darker skins (less wood from the start) are not. */
public class CoveredBoardRegression {
    static void check(boolean v,String m){if(!v)throw new AssertionError(m);}
    public static void main(String[] a){
        nu.pattern.OpenCV.loadLocally();
        Mat bgr=Imgcodecs.imread(a[0]),rgba=new Mat();Imgproc.cvtColor(bgr,rgba,Imgproc.COLOR_BGR2RGBA);
        BoardDetector d=new BoardDetector();
        GameState clean=d.detectMat(rgba);check(clean!=null&&!clean.covered,"clean board is not covered");
        check(!d.detectMat(rgba).covered,"same clean board again is not covered");
        // Chat-like panel: dark translucent box over the left 55% of the board, top to bottom of the coin area.
        Mat chat=rgba.clone();
        float l=clean.board.left,t=clean.board.top,w=clean.board.width(),h=clean.board.height();
        Imgproc.rectangle(chat,new Point(l+w*.02,t+h*.18),new Point(l+w*.57,t+h*1.15),new Scalar(60,40,35,255),-1);
        GameState covered=d.detectMat(chat);
        check(covered!=null&&covered.covered,"board half covered by a panel is flagged");
        check(!d.detectMat(rgba).covered,"panel closed: board is clean again");
        // A darker board skin from the first frame on (less wood everywhere) is never 'covered'.
        BoardDetector fresh=new BoardDetector();Mat dark=new Mat();rgba.convertTo(dark,-1,1,0);
        Imgproc.rectangle(dark,new Point(l+w*.30,t+h*.30),new Point(l+w*.45,t+h*.45),new Scalar(150,40,40,255),-1);
        GameState first=fresh.detectMat(dark);check(first!=null&&!first.covered&&!fresh.detectMat(dark).covered,"a board that always looks the same is not covered");
        System.out.println("PASS: chat-like panel over the board flagged as covered, clean and constant-skin boards not flagged");
    }
}
