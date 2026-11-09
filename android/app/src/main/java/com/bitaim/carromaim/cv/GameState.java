package com.bitaim.carromaim.cv;

import android.graphics.PointF;
import android.graphics.RectF;

import java.util.ArrayList;
import java.util.List;

public class GameState {
    public long frameId;
    public int targetColourKey = TargetColourLock.AUTO;
    public float aimGuideX=Float.NaN,aimGuideY=Float.NaN;
    /** Screen y of the power slider knob under the striker, or NaN when not found. */
    public float sliderY=Float.NaN;
    public int aimGuideDots;
    /** 4.13.0: length of the game's red power arrow while the player pulls, in board widths; NaN when not seen. */
    public float aimPower=Float.NaN;
    public Coin         striker; // may be null before detection
    public List<Coin>   coins   = new ArrayList<>();
    public List<PointF> pockets = new ArrayList<>();
    public RectF        board;   // auto-detected board rectangle
    /** true when a real striker was found on the bottom baseline (= your turn) */
    public boolean      strikerDetected = false;
    public boolean teamKnown = false;
    public boolean freestyle = false;
    /** 4.11.0: much less of the board shows than a moment ago (chat panel or a popup over it): no pictures, no shot
     *  results, no planning from this frame. */
    public boolean covered = false;
    /** A validated assignment is visible or retained for the current board session. */
    public boolean assignedColours = false;
    public boolean queenRequired = false;
    public boolean openingCluster = false;
    public float playerHue = -1, opponentHue = -1;
}
