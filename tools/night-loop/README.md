# Night loop: unattended practice games

PC-side scripts that keep Carrom Pool practice games (vs the computer) running with BitAim auto play, so every
shot is recorded for tuning. Phone over USB (adb), BitAim 4.13.3+ with gesture control, capture and Debug mode on.

```
python tools/night-loop/night.py 0540     # run until 05:40
```

What `night.py` does every ~10-20 s:
- Works only while Carrom is the focused app (never screenshots or taps another app or system UI).
- Recognises screens by pixel colours: home, settings, Select Mode, game (armed / not armed), Game Over.
- Taps only known spots: gear, Practice Mode Play, Disc Pool / Carrom (alternating), Main Menu, auto play menu.
- Popups: the red round close X (template `xbtn.png`, kept locally, not in git) or the game's back arrow.
  Price / purchase / Challenge buttons are never tapped. The phone Back key is never used (it closes Carrom).
- No shot for 60 s during a game, or an unknown screen for ~2 min: force-stop and relaunch Carrom.
- Pulls the phone logs to `OUTPUTS/phone-data/night-<date>` every hour; stops at the given time.
- Keeps the PC awake while it runs.

Other helpers: `shot.sh` (guarded screenshot), `ph.sh` / `restart.sh` (tap helpers, BitAim capture restart),
`breaks.py` (lists all full-rack shots from saved logs), `stat.py`, `sum.py`, `auto.py`, `deviation.py`.

Logs: `data/phone-logs/` holds the text logs (shots.jsonl, events.log, incidents.jsonl). Pictures stay on the
PC only.
