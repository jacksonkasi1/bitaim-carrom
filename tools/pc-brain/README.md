# BitAim PC brain (branch `pc-link`, app 4.10.0+)

The PC brain moves BitAim's thinking (board detection, shot planning, auto-play decisions) from the phone to a PC
connected by USB. The phone only streams the screen and performs the gestures. A PC is several times faster than the
phone, so shots are planned in a fraction of a second instead of several seconds, and every frame can be recorded on
the PC for analysis.

It is **optional**. Without it (switch off, PC program not running, or cable unplugged) BitAim plans on the phone
exactly as before.

---

## 1. What you need

| Item | Notes |
|---|---|
| Phone with BitAim **4.10.0 or newer** | The version from the `pc-link` branch / release |
| USB cable + **USB debugging** enabled | `adb devices` on the PC must list the phone |
| PC with **JDK 17** and **Android platform-tools (adb)** | Defaults: `D:\Tools\jdk-17`, `D:\Tools\Android\platform-tools\adb.exe`; override with the `JAVA_HOME` and `ADB` environment variables |
| Internet once | The script downloads the desktop OpenCV library (`opencv-4.5.1-2.jar`) the first time |

Enabling the switch on the phone alone does nothing: **the PC program must be running**.

---

## 2. Step by step

### On the PC
Run (keep the window open while playing):

```
powershell -File tools/pc-brain/pc-brain.ps1
```

The script:
1. compiles BitAim's planning code and the PC brain (`tools/pc-brain/classes/`),
2. runs `adb reverse tcp:8765 tcp:8765` so the phone's `127.0.0.1:8765` reaches the PC over the cable,
3. starts the brain, which prints `PC brain listening on 127.0.0.1:8765`.

### On the phone
1. Open BitAim, switch on **Aim Overlay** and **Auto-Detect**, choose **Entire screen**, then **Cast** (as usual).
2. Switch on **PC brain (USB)**: in the BitAim app (Experimental automatic shots card) or in the **star menu**.
3. Look at the label under the floating star:
   - `• PC…` = switch on, waiting for the PC program,
   - `• PC` = connected; the PC console prints `phone connected`.
4. Open Carrom, and arm auto play from the star menu as usual (`Auto play → Yes`).
   The PC only acts while auto play is armed on the phone.

### Turning it off
- Switch **PC brain** off in the app or star menu at any time, also in the middle of a game, or
- stop the PC program (Ctrl+C), or unplug the cable.

The phone returns to its own planning within about 2 seconds. Nothing else changes.

---

## 3. How it works

```
 phone                                              PC (pc-brain)
 ─────                                              ─────────────
 screen capture ── 540 px JPEG frames, ~30/s ──►    decode → detect board, striker, discs, aim dots
   + settings (auto armed, colour lock,              → plan route and best striker spot (★)
     pro style, debug)                               → auto-play decision (slide / shoot)
                                                     → record frame + shot log
 aim correction ◄── aim-guide reading every frame ── (direction of the game's aim dots)
 gestures       ◄── action: slide or shot ────────── (start, end, aim point, correction budget)
 "done" event   ── gesture result, aim error ────►   controller continues with the next step
```

- Transport: TCP over ADB (`adb reverse`), so it uses the same USB cable as USB debugging.
- The phone keeps only the newest frame if the link is slow (no backlog).
- The closed-loop aim correction still runs on the phone, using the aim-dot readings the PC sends back.
- While connected, the phone's own detection and planning are paused to save battery and heat.

Protocol details: phone → PC messages are `[int length][byte type][payload]`; type `F` = JSON header line + `\n` +
JPEG, type `E` = JSON event (`{"type":"done","id":..,"ok":..,"aim":{...}}`). PC → phone: one JSON object per line,
`type` = `guide` | `action` | `status`.

---

## 4. What is recorded on the PC

`tools/pc-brain/recordings/<session-time>/`

| File | Content |
|---|---|
| `shots.jsonl` | One record per shot, same format as the phone's shot log |
| `events.log` | Status changes, every action sent, gesture results with aim correction, slider landings |
| `frames/` | Every frame received (only while Debug mode is on in BitAim) |

Recordings and compiled classes are git-ignored (never pushed).

---

## 5. Troubleshooting

| Symptom | Fix |
|---|---|
| Label stays `• PC…` | PC program not running, or `adb reverse` missing: rerun the script; check `adb devices` |
| PC prints nothing after `listening` | PC brain switch is off on the phone, or BitAim capture is not running (Aim Overlay / Auto-Detect / Cast) |
| `PC brain compilation failed` | JDK 17 path wrong: set `JAVA_HOME` |
| Phone stops shooting | Auto play not armed on the phone, or Android disabled BitAim's gesture service (re-enable in Accessibility) |
| Want to test without a phone | `java -cp tools/pc-brain/classes PhoneSim frame1.jpg ...` replays pictures as phone frames |

---

## 6. Current status and limits (4.10.0)

Done and tested on the PC (loopback with real game pictures): about 1 s from first frame to a shot decision,
planning about 250 ms, about 20 frames per second through detection.

Not yet done:
1. First real-phone test.
2. In PC mode the phone shows status text only, not the aim lines.
3. The phone's frame-to-frame smoothing of board/disc positions and its route-hold logic are not yet ported to the PC.
4. The planner is the same as on the phone (only faster); deeper search (thousands of noisy trials per shot,
   two-shot look-ahead) is planned.
5. Measuring each shot's real launch direction from the recorded frames is planned.
6. Works only while this PC runs the program and the phone is connected; phones without a PC use their own brain.
