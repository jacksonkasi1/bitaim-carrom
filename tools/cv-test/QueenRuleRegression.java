import com.bitaim.carromaim.cv.*;

public class QueenRuleRegression {
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static GameState state(){GameState s=new GameState();s.teamKnown=true;return s;}
    static Coin add(GameState s,boolean own,boolean queen,int colour){
        Coin c=new Coin(100+s.coins.size()*60,300,20,colour,false);
        c.targetEligible=own;c.transferEligible=own;c.isQueen=queen;s.coins.add(c);return c;
    }
    public static void main(String[] args){
        GameState s=state();Coin own=add(s,true,false,Coin.COLOR_WHITE);
        Coin queen=add(s,false,true,Coin.COLOR_RED),opponent=add(s,false,false,Coin.COLOR_BLACK);
        check(QueenRule.apply(s),"One own coin plus one verified queen must require queen first");
        check(queen.targetEligible&&queen.transferEligible,"Required queen must be a final target and valid transfer");
        check(!own.targetEligible&&own.transferEligible&&s.coins.contains(own),"Last own coin must remain a transfer obstacle without becoming the final target");
        check(!opponent.targetEligible&&!opponent.transferEligible,"Opponent eligibility must not be promoted");
        check(s.coins.stream().filter(c->c.targetEligible).count()==1,"Only the verified queen may be a final target");

        s=state();own=add(s,true,false,Coin.COLOR_WHITE);Coin otherOwn=add(s,true,false,Coin.COLOR_WHITE);
        queen=add(s,false,true,Coin.COLOR_RED);
        check(!QueenRule.apply(s)&&own.targetEligible&&otherOwn.targetEligible&&!queen.targetEligible,"Multiple own coins must leave eligibility unchanged");

        s=state();s.freestyle=true;own=add(s,true,false,Coin.COLOR_WHITE);queen=add(s,true,true,Coin.COLOR_RED);
        check(!QueenRule.apply(s)&&own.targetEligible&&queen.targetEligible,"Freestyle must retain its existing target eligibility");

        s=state();s.teamKnown=false;own=add(s,true,false,Coin.COLOR_WHITE);queen=add(s,false,true,Coin.COLOR_RED);
        check(!QueenRule.apply(s)&&own.targetEligible&&!queen.targetEligible,"Unknown team must never force queen priority");

        s=state();own=add(s,true,false,Coin.COLOR_WHITE);queen=add(s,false,true,Coin.COLOR_RED);
        Coin secondQueen=add(s,false,true,Coin.COLOR_RED);
        check(!QueenRule.apply(s)&&own.targetEligible&&!queen.targetEligible&&!secondQueen.targetEligible,"Ambiguous multiple queens must leave eligibility unchanged");

        s=state();own=add(s,true,false,Coin.COLOR_WHITE);Coin redSkin=add(s,false,false,Coin.COLOR_RED);
        check(!QueenRule.apply(s)&&own.targetEligible&&!redSkin.targetEligible&&!redSkin.isQueen,"Opponent red skin must not be inferred to be queen");

        s=state();queen=add(s,false,true,Coin.COLOR_RED);
        check(!QueenRule.apply(s)&&!queen.targetEligible,"No own coin must leave eligibility unchanged");
        check(!QueenRule.apply(null),"Missing state must not force a queen target");
        System.out.println("PASS: last-own queen priority, unchanged normal/freestyle/unknown-team targets, ambiguous queen rejection and red-skin rejection");
    }
}
