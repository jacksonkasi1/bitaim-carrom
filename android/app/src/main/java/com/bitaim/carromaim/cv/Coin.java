package com.bitaim.carromaim.cv;

import android.graphics.PointF;

public class Coin {
    public static final int COLOR_WHITE   = 0;
    public static final int COLOR_BLACK   = 1;
    public static final int COLOR_RED     = 2; // queen
    public static final int COLOR_STRIKER = 3;

    public PointF pos;
    public float  radius;
    public int    color;
    public boolean isStriker;
    /** Kept in the obstacle list even when it belongs to the opponent. */
    public boolean targetEligible = true;
    /** Known disc that may transfer an impact; unknown detections stay obstacles. */
    public boolean transferEligible = true;
    /** Plain red queen, explicitly distinguished from patterned red team skins. */
    public boolean isQueen = false;
    public int appearanceKey = TargetColourLock.AUTO;

    public Coin(float x, float y, float radius, int color, boolean isStriker) {
        this.pos      = new PointF(x, y);
        this.radius   = radius;
        this.color    = color;
        this.isStriker = isStriker;
    }
}
