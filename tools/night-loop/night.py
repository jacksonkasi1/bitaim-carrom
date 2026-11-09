"""Overnight practice supervisor for BitAim (Carrom Pool practice mode vs computer only).

Rules it keeps:
- Screenshots only while Carrom is the focused app (never system UI or any other app); pictures stay in memory.
- Taps only on recognised screens at known spots (no shop / offer / price buttons); unknown screens get BACK.
- Never touches other apps; stops at STOP_AT (before the 06:00 focus app) and goes to the phone home screen.
Usage: python night.py [STOP_HHMM]
"""
import subprocess, time, io, sys, os, json, datetime
from PIL import Image

ADB = ['D:/Tools/Android/platform-tools/adb.exe', '-s', '9DCYBIDMRCOJQSMN']
MATCHES = '/sdcard/Android/data/com.bitaim.carromaim/files/matches'
LOG = 'D:/Tools/tmp/loop/night.log'
STATE = 'D:/Tools/tmp/loop/night-state.json'
STOP_AT = sys.argv[1] if len(sys.argv) > 1 else '0540'
MODES = ['disc', 'carrom']          # alternate practice modes each game
STAR = (126, 492)
STALL_S = 60                        # user: restart after 1 min without any shot (ours or the computer's)                   # BitAim floating star (full-res px)

def log(*a):
    s = datetime.datetime.now().strftime('%H:%M:%S') + ' ' + ' '.join(str(x) for x in a)
    print(s, flush=True)
    with open(LOG, 'a', encoding='utf-8') as f: f.write(s + '\n')

def sh(*cmd, timeout=30):
    try:
        return subprocess.run(ADB + ['shell'] + list(cmd), capture_output=True, text=True, timeout=timeout).stdout
    except Exception as e:
        log('adb error', e); return ''

def tap(x, y): sh('input', 'tap', str(int(x)), str(int(y))); time.sleep(1.5)
def hold(x, y, ms=1200): sh('input', 'swipe', *[str(int(v)) for v in (x, y, x, y)], str(ms)); time.sleep(1.5)
def back(): sh('input', 'keyevent', '4'); time.sleep(2)

def focus():
    w = sh('dumpsys', 'window')
    cur = app = ''
    for line in w.splitlines():
        if 'mCurrentFocus=' in line and '=null' not in line and not cur: cur = line
        if 'mFocusedApp=' in line and '=null' not in line and not app: app = line
    if any(k in cur for k in ('NotificationShade', 'StatusBar', 'com.android.systemui')): return 'system'
    if 'com.miniclip.carrom' in app and 'com.miniclip.carrom' in cur: return 'carrom'
    if 'com.miniclip.carrom' in app: return 'carrom-popup'
    if 'com.bitaim.carromaim' in app: return 'bitaim'
    if 'launcher' in app.lower(): return 'launcher'
    return 'other'

def screen():
    """Guarded capture: returns a 360-wide RGB image, or None if Carrom is not in front."""
    if focus() != 'carrom': return None
    try:
        png = subprocess.run(ADB + ['exec-out', 'screencap', '-p'], capture_output=True, timeout=30).stdout
        im = Image.open(io.BytesIO(png)).convert('RGB')
        return im.resize((360, im.height * 360 // im.width))
    except Exception as e:
        log('capture error', e); return None

def near(c, ref, tol=60): return sum(abs(a - b) for a, b in zip(c, ref)) <= tol
def px(im, x, y): return im.getpixel((x, y))

def classify(im):
    if im is None: return 'none'
    p = lambda x, y: px(im, x, y)
    # practice board: wood at centre-left and centre-right, red STOP AUTO pill when armed
    pts = [(x, y) for x in (40, 100, 260, 320) for y in (300, 450, 540)] + [(180, 260), (180, 560)]
    wood = sum(near(p(x, y), (226, 175, 130), 90) for x, y in pts) >= 5
    if wood:
        red = sum(1 for x in range(82, 180, 4) for y in range(154, 180, 4)
                  if (lambda c: c[0] > 170 and c[1] < 90 and c[2] < 90)(p(x, y)))
        armed = red >= 20
        return 'game-armed' if armed else 'game'
    # Game Over popup: orange Main Menu and green Play Again side by side, brown title bar
    if near(p(150, 497), (255, 122, 36), 70) and near(p(270, 497), (124, 229, 48), 70) and near(p(100, 318), (121, 57, 29), 60):
        return 'game-over'
    # Select Mode popup: blue, orange, green, blue buttons stacked in the middle
    if near(p(180, 345), (33, 70, 141), 60) and near(p(180, 413), (212, 101, 32), 60) and near(p(180, 483), (60, 158, 97), 60) and near(p(180, 555), (27, 116, 182), 60):
        return 'select-mode'
    # Settings page: green Play buttons at the right on Tutorial and Practice rows, orange Account ribbon
    if near(p(300, 259), (80, 190, 50), 100) and near(p(300, 321), (80, 190, 50), 100) and near(p(60, 124), (240, 120, 30), 90) and near(p(290, 124), (240, 120, 30), 90):
        return 'settings'
    # Home: blue Play Disc Pool, green Play Carrom, orange Play Freestyle buttons
    if near(p(110, 312), (40, 80, 160), 120) and near(p(110, 410), (40, 140, 60), 120) and near(p(110, 508), (230, 140, 30), 120):
        return 'home'
    return 'unknown'

import numpy as np
XT = np.asarray(Image.open('D:/Tools/tmp/loop/xbtn.png').convert('RGB')).astype(int)

def find_x(im):
    """Red round close (X) button of a popup, searched in the right half. Returns full-res tap point or None."""
    a = np.asarray(im).astype(int); h, w = XT.shape[:2]; best = (1e9, None)
    for y in range(40, min(a.shape[0] - h, 800), 2):
        for x in range(180, a.shape[1] - w, 2):
            c = a[y + h // 2, x + w // 2]
            if not (c[0] > 200 and c[1] > 200): continue          # white cross centre
            d = np.abs(a[y:y + h, x:x + w] - XT).mean()
            if d < best[0]: best = (d, ((x + w / 2) * 3, (y + h / 2) * 3))
    return best[1] if best[0] < 30 else None

def back_arrow(im):
    """Orange square back arrow at the bottom-left of full-page menus."""
    org = sum(1 for x in range(14, 52, 3) for y in range(740, 776, 3)
              if (lambda c: c[0] > 140 and 60 < c[1] < 160 and c[2] < 80)(im.getpixel((x, y))))
    w = im.getpixel((32, 757)); return org >= 60 and min(w) > 200

def restart_carrom():
    log('restarting Carrom')
    sh('am', 'force-stop', 'com.miniclip.carrom'); time.sleep(3)
    sh('monkey', '-p', 'com.miniclip.carrom', '-c', 'android.intent.category.LAUNCHER', '1'); time.sleep(25)

def newest_shot_age():
    d = sh(f'ls -t {MATCHES} | head -1').strip()
    if not d: return 1e9, ''
    out = sh(f'stat -c %Y {MATCHES}/{d}/shots.jsonl 2>/dev/null; date +%s').split()
    if len(out) < 2: return 1e9, d
    return int(out[1]) - int(out[0]), d

def arm():
    hold(*STAR); tap(300, 795)          # star menu -> "Auto play"
    im = screen()
    tap(300, 648)                        # "Start auto play? Yes"
    log('armed auto play')

def pull_logs():
    dst = 'D:/WORK/HACK/HACKTHON/carrom-auto/OUTPUTS/phone-data/night-' + datetime.date.today().strftime('%Y%m%d')
    os.makedirs(dst, exist_ok=True)
    r = subprocess.run(ADB + ['pull', MATCHES, dst], capture_output=True, text=True, timeout=600)
    log('pulled logs ->', dst, (r.stdout.strip().splitlines() or [''])[-1])

def main():
    st = {'games': 0, 'mode': 0}
    if os.path.exists(STATE):
        try: st.update(json.load(open(STATE)))
        except Exception: pass
    last_pull = time.time(); unknown = 0; in_game_since = None; away = 0
    import ctypes; ctypes.windll.kernel32.SetThreadExecutionState(0x80000001)   # keep the PC awake while this runs
    log('night supervisor start, stop at', STOP_AT)
    while True:
        if datetime.datetime.now().strftime('%H%M') >= STOP_AT and datetime.datetime.now().hour < 12:
            log('stop time reached')
            if focus() in ('carrom', 'carrom-popup'): sh('input', 'keyevent', '3')   # HOME, leaves the game paused
            pull_logs(); return
        if time.time() - last_pull > 3600: pull_logs(); last_pull = time.time()
        f = focus()
        if f not in ('carrom',):
            away += 1
            if f == 'other' or f == 'system':
                if away % 10 == 1: log('not in Carrom (', f, ') - waiting, touching nothing')
                time.sleep(30); continue
            if f == 'carrom-popup': time.sleep(10); continue
            if away >= 4 and f in ('launcher', 'bitaim'):
                log('relaunching Carrom from', f)
                sh('monkey', '-p', 'com.miniclip.carrom', '-c', 'android.intent.category.LAUNCHER', '1'); time.sleep(20)
            else: time.sleep(15)
            continue
        away = 0
        im = screen(); kind = classify(im)
        age, d = newest_shot_age()
        if kind == 'game-armed':
            unknown = 0
            in_game_since = in_game_since or time.time()
            quiet = min(age, time.time() - in_game_since)   # a new game has no shot file yet
            if quiet > STALL_S:
                log('no shot for', int(quiet), 's, restarting'); restart_carrom(); in_game_since = None
            time.sleep(10); continue
        in_game_since = None
        if kind == 'game':
            unknown = 0; time.sleep(4)
            if classify(screen()) == 'game': arm()
            continue
        if kind == 'game-over':
            unknown = 0; st['finished'] = st.get('finished', 0) + 1; json.dump(st, open(STATE, 'w'))
            log('game over, finished games', st['finished'], '- Main Menu'); tap(360, 1515); time.sleep(4); continue
        if kind == 'home':
            unknown = 0; tap(237, 183); continue                       # gear
        if kind == 'settings':
            unknown = 0; tap(810, 966); continue                       # Practice Mode: Play
        if kind == 'select-mode':
            unknown = 0
            m = MODES[st['mode'] % len(MODES)]
            tap(540, 969 if m == 'disc' else 1389)                     # Disc Pool / Carrom
            st['mode'] += 1; st['games'] += 1; json.dump(st, open(STATE, 'w'))
            log('started practice game', st['games'], m); time.sleep(8)
            if classify(screen()) == 'game': arm()
            continue
        unknown += 1
        os.makedirs('D:/Tools/tmp/loop/unknown', exist_ok=True)
        if im is not None: im.save('D:/Tools/tmp/loop/unknown/u%d.jpg' % min(unknown, 9), quality=70)
        if unknown <= 2:                     # be patient: animations, popups fading, loading
            log('unknown screen, waiting', unknown); time.sleep(10); continue
        xp = find_x(im) if im is not None else None
        if xp and unknown <= 12:
            log('closing popup X at', [int(v) for v in xp]); tap(*xp); time.sleep(2); continue
        if im is not None and back_arrow(im) and unknown <= 12:
            log('tapping menu back arrow'); tap(84, 2268); time.sleep(2); continue
        if unknown <= 8:
            log('unknown screen, waiting', unknown); time.sleep(15); continue
        restart_carrom(); unknown = 0
        st['restarts'] = st.get('restarts', 0) + 1; json.dump(st, open(STATE, 'w'))
        if st['restarts'] > 12:
            log('too many restarts, stopping for a look'); sys.exit(3)

if __name__ == '__main__':
    main()
