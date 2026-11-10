"""Night summary: per BitAim version window, auto-shot results; break-study results per variant.
usage: python night_report.py <phone-data dir> [since HHMM]"""
import json, glob, sys, os, re, datetime, collections
root = sys.argv[1]
shots, events = {}, {}
for f in glob.glob(os.path.join(root, '**', 'shots.jsonl'), recursive=True):
    for l in open(f, encoding='utf-8'):
        try: d = json.loads(l)
        except Exception: continue
        if not str(d.get('t', '')).startswith('1794'): pass
        shots[(d['t'], bool(d.get('opp')))] = d
for f in glob.glob(os.path.join(root, '**', 'events.log'), recursive=True):
    m = os.path.basename(os.path.dirname(f))
    if not m.startswith('20261110'): continue
    events[m] = open(f, encoding='utf-8', errors='replace').read().splitlines()

def ts(d): return datetime.datetime.fromtimestamp(d['t'] / 1000)
night = [d for d in shots.values() if ts(d).date() == datetime.date(2026, 11, 10)]
WINDOWS = [('4.13.2/4.13.3', '00:00', '01:58'), ('4.14.0', '01:58', '02:40'), ('4.14.1', '02:40', '02:52'),
           ('4.14.2/3', '02:52', '03:20'), ('4.14.4', '03:20', '04:47'), ('4.14.5', '04:47', '05:41')]
print('== Auto shots by version')
print('version      shots  potted-own/shot  any-own%  gave-opp%  striker-in-pocket')
for name, a, b in WINDOWS:
    rows = [d for d in night if d.get('autoPull') is not None and not d.get('opp') and a <= ts(d).strftime('%H:%M') < b]
    if not rows: continue
    own = [(d.get('result') or {}).get('own') or 0 for d in rows]
    oth = [(d.get('result') or {}).get('other') or 0 for d in rows]
    foul = sum(1 for d in rows if (d.get('result') or {}).get('striker') == 'pocketed')
    print(f'{name:12s} {len(rows):5d}  {sum(own)/len(rows):15.2f}  {100*sum(o>0 for o in own)/len(rows):7.0f}%  {100*sum(o>0 for o in oth)/len(rows):8.0f}%  {foul:4d}')
print('\n== Stalls / fallbacks / extra turns per match (events.log)')
tot = collections.Counter()
for m, lines in sorted(events.items()):
    c = collections.Counter()
    for l in lines:
        if ' SHOT ' in l: c['shots'] += 1
        if 'INCIDENT auto-stall' in l: c['stalls'] += 1
        if 'SHOT' in l and 'no route:' in l: c['fallback'] += 1
        if 'extra turn' in l: c['extra'] += 1
    tot.update(c)
print(dict(tot))
print('\n== Break study (first break of each game, full power at the cluster centre)')
study = []
for m, lines in events.items():
    for l in lines:
        g = re.match(r'(\d\d:\d\d:\d\d)\.\d+ SHOT .*break study ([+-]\d+)', l)
        if g: study.append((m, g.group(1), int(g.group(2))))
by = collections.defaultdict(list)
for m, t, off in study:
    hit = [d for d in night if d.get('autoPull') is not None and not d.get('opp') and abs((ts(d) - datetime.datetime.strptime('2026-11-10 ' + t, '%Y-%m-%d %H:%M:%S')).total_seconds()) < 6]
    if not hit: by[off].append(None); continue
    r = hit[0].get('result') or {}
    by[off].append((r.get('own') or 0, r.get('other') or 0, r.get('discsBefore'), r.get('discsAfter')))
print('offset  n  own-potted  opp-potted  results(own,opp)')
for off in sorted(by):
    v = [x for x in by[off] if x]
    if not v: print(f'{off:+4d}°  {len(by[off])}  (no shot record)'); continue
    print(f'{off:+4d}°  {len(v)}  {sum(x[0] for x in v)/len(v):9.2f}  {sum(x[1] for x in v)/len(v):9.2f}   {[(x[0], x[1]) for x in v]}')
