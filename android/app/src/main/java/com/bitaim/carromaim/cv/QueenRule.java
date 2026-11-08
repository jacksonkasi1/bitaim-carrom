package com.bitaim.carromaim.cv;

/** Applies the selected last-own-coin queen priority to verified detections only. */
public final class QueenRule {
    private QueenRule(){}

    public static boolean apply(GameState s){
        if(s==null||s.freestyle||!s.teamKnown)return false;
        Coin queen=null,lastOwn=null;
        int queens=0,own=0;
        for(Coin c:s.coins){
            if(c.isQueen){queens++;queen=c;}
            else if(c.targetEligible){own++;lastOwn=c;}
        }
        if(queens!=1||own!=1)return false;
        // A red skin is not evidence of queen identity. Classification owns isQueen.
        for(Coin c:s.coins)c.targetEligible=c==queen;
        queen.transferEligible=true;
        lastOwn.transferEligible=true;
        return true;
    }
}
