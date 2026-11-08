# BitAim PC brain (branch `pc-link`)

Runs BitAim's detection, planning and auto-play decisions on the PC instead of the phone, over the USB debugging
(ADB) connection. The phone streams small frames; the PC sends back slide/shot actions and live aim-guide readings;
the phone performs the gestures (with its normal closed-loop aim correction) and reports when each one is done.

## Use

1. Phone: USB debugging on, cable connected (`adb devices` shows it).
2. PC: `powershell -File tools/pc-brain/pc-brain.ps1` (compiles, runs `adb reverse tcp:8765 tcp:8765`, starts the brain).
3. Phone: BitAim → Aim Overlay + Auto-Detect on → **PC brain (USB)** on (also in the star menu, any time, mid-game too).
   The label under the star shows `• PC` when connected, `• PC…` while waiting.
4. Arm auto play from the star menu as usual. The PC only acts while auto play is armed on the phone.

Turning **PC brain** off (app or star menu), stopping the PC program or pulling the cable returns the phone to its own
brain within about 2 seconds; nothing else changes.

## What is recorded on the PC

`tools/pc-brain/recordings/<session>/`: `shots.jsonl` (same format as the phone log), `events.log` (status, actions,
gesture results with aim correction) and, when Debug mode is on, every received frame in `frames/`.
Recordings and compiled classes are git-ignored.

## Protocol

Phone → PC: `[int length][byte type][payload]`; `F` = JSON header line + `\n` + JPEG (540 px wide), `E` = JSON event
(`{"type":"done","id":..,"ok":..,"aim":{...}}`). PC → phone: one JSON object per line, `type` = `guide` | `action` | `status`.

`PhoneSim.java` replays JPEG files as phone frames for a loopback test without a phone.
