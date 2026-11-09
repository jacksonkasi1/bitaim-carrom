import json,math,glob,statistics as st,collections
files=glob.glob('D:/WORK/HACK/HACKTHON/carrom-auto/OUTPUTS/phone-data/*/shots.jsonl')+glob.glob('D:/Tools/tmp/m1/matches/*/shots.jsonl')+['D:/Tools/tmp/loop/cur.jsonl']
u={}
for f in files:
    for l in open(f,encoding='utf-8'):
        try:d=json.loads(l);u[d['t']]=d
        except:pass
res=[]
for d in u.values():
    r=d.get('autoRoute') or {}
    if r.get('kind') not in (0,2,3) or 'track' not in d or r.get('sweep'):continue
    if r['kind']==3 and not r.get('targetBounce'):continue
    tgt=r['target'];aimto=r['targetBounce'] if r['kind']==3 else r['pocket']
    planned=math.atan2(aimto[1]-tgt[1],aimto[0]-tgt[0])
    # find target's first clear displacement in track
    pts=[]
    for f in d['track']:
        best=None;bd=1e9
        for k in range(3,len(f)-2,3):
            dd=math.hypot(f[k]-tgt[0],f[k+1]-tgt[1])
            if dd<bd:bd=dd;best=(f[k],f[k+1])
        pts.append((f[0],best,bd))
    # take first frame where target moved 25..120 px from start and was matched
    dep=None
    for t,p,bd in pts:
        if p is None:continue
        m=math.hypot(p[0]-tgt[0],p[1]-tgt[1])
        if 25<m<160:dep=math.atan2(p[1]-tgt[1],p[0]-tgt[0]);break
    if dep is None:continue
    err=math.degrees((dep-planned+math.pi)%(2*math.pi)-math.pi)
    if abs(err)>40:continue
    ok=(d.get('ownPotted') or 0)>0
    res.append((err,ok,r['kind'],r.get('chance'),math.hypot(aimto[0]-tgt[0],aimto[1]-tgt[1])/d['board'][2]))
print('auto pots with a tracked target departure:',len(res))
e=[x[0] for x in res]
print(f'target direction error vs plan: mean {st.mean(e):+.2f} deg, spread (SD) {st.pstdev(e):.2f} deg, median |err| {st.median([abs(x) for x in e]):.2f} deg')
for lo,hi in [(0,1),(1,2),(2,4),(4,8),(8,40)]:
    s=[x for x in res if lo<=abs(x[0])<hi]
    if s:print(f'  |error| {lo}-{hi} deg: {len(s):3d} shots, potted {sum(x[1] for x in s):3d} ({100*sum(x[1] for x in s)/len(s):.0f}%)')
# allowed error: pocket half-width ~ 0.045 board / distance
need=[math.degrees(math.atan(0.03/max(.05,x[4]))) for x in res]
print(f'typical allowed target error for these pots (pocket half-width / distance): median {st.median(need):.1f} deg')
