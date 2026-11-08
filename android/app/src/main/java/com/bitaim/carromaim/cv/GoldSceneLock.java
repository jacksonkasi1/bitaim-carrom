package com.bitaim.carromaim.cv;

/** Reuse a gold search through coordinate noise, including quantization boundaries. */
public final class GoldSceneLock {
    private float[][] coins;
    private float[][] pockets;
    private float left,top,width,height,strikerY,selectedX=Float.NaN,selectedY;
    private int version;
    public int update(GameState s,Coin selected){
        float tolerance=s.board.width()*.012f;
        boolean same=coins!=null&&coins.length==s.coins.size()
                &&Math.abs(left-s.board.left)<tolerance*2&&Math.abs(top-s.board.top)<tolerance*2
                &&Math.abs(width-s.board.width())<tolerance*2&&Math.abs(height-s.board.height())<tolerance*2
                &&Math.abs(strikerY-s.striker.pos.y)<s.striker.radius*.3f
                &&(selected==null?Float.isNaN(selectedX):!Float.isNaN(selectedX)&&Math.hypot(selected.pos.x-selectedX,selected.pos.y-selectedY)<tolerance);
        boolean[] used=coins==null?new boolean[0]:new boolean[coins.length];
        if(same){
            same=pockets.length==s.pockets.size();
            for(int i=0;same&&i<pockets.length;i++)same=Math.hypot(pockets[i][0]-s.pockets.get(i).x,pockets[i][1]-s.pockets.get(i).y)<tolerance;
        }
        if(same)for(Coin c:s.coins){
            int found=-1;
            for(int i=0;i<coins.length;i++)if(!used[i]&&coins[i][2]==(c.targetEligible?1:0)&&coins[i][3]==(c.transferEligible?1:0)
                    &&Math.hypot(c.pos.x-coins[i][0],c.pos.y-coins[i][1])<c.radius*.35f){found=i;break;}
            if(found<0){same=false;break;}used[found]=true;
        }
        if(same)return version;
        coins=new float[s.coins.size()][4];
        pockets=new float[s.pockets.size()][2];
        for(int i=0;i<pockets.length;i++)pockets[i]=new float[]{s.pockets.get(i).x,s.pockets.get(i).y};
        for(int i=0;i<coins.length;i++){Coin c=s.coins.get(i);coins[i]=new float[]{c.pos.x,c.pos.y,c.targetEligible?1:0,c.transferEligible?1:0};}
        left=s.board.left;top=s.board.top;width=s.board.width();height=s.board.height();strikerY=s.striker.pos.y;
        selectedX=selected==null?Float.NaN:selected.pos.x;selectedY=selected==null?0:selected.pos.y;
        return ++version;
    }
}
