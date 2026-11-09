import json,sys,collections,datetime
lines=open(sys.argv[1],encoding='utf-8').read().splitlines()
start=int(sys.argv[2]) if len(sys.argv)>2 else 0
for i,l in enumerate(lines[start:],start+1):
    try:d=json.loads(l)
    except Exception: continue
    b=collections.Counter(c[3] for c in d.get('before',[])); a=collections.Counter(c[3] for c in d.get('after',[]))
    r=d.get('autoRoute') or {}
    t=datetime.datetime.fromtimestamp(d['t']/1000).strftime('%H:%M:%S')
    keys={k:r[k] for k in r if k not in('aimCorrection','strikerPath','coinPath','path')}
    print(i,t,'pull',d.get('autoPull'),'before',dict(b),'after',dict(a),'own',d.get('ownPotted'),'opp',d.get('opponentPotted'),'img',d.get('images'))
    print('   route',json.dumps(keys)[:400])
