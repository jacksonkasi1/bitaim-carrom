import json,sys,math,collections
rows=[]
for l in open(sys.argv[1],encoding='utf-8'):
    try:d=json.loads(l)
    except: continue
    r=d.get('autoRoute') or {}
    if 'label' not in r: continue
    tgt=r['target'];ch=r.get('chain',[]);g=r.get('ghost')
    rad=d['striker'][2]
    sweep=False
    if ch and g:
        f=ch[0]['from'];to=ch[0]['to'];dx,dy=to[0]-f[0],to[1]-f[1];L=math.hypot(dx,dy) or 1
        # geometric ghost = from - dir*(sr+cr)
        gx=f[0]-dx/L*(rad+28.9);gy=f[1]-dy/L*(rad+28.9)
        sweep=math.hypot(gx-g[0],gy-g[1])>8
    after=d.get('after',[])
    gone=not any(math.hypot(c[0]-tgt[0],c[1]-tgt[1])<20 for c in after)
    # target potted: not anywhere near? approximate: own count drop
    own=d.get('ownPotted') or 0
    kind=r['kind']
    cat=('sweep-' if sweep else '')+{0:'direct',1:'scush',2:'combo',3:'ccush',4:'cluster',5:'opener'}[kind]
    rows.append((cat,own>0,r.get('chance'),d['t']))
agg=collections.defaultdict(lambda:[0,0,0.0])
for cat,ok,chance,t in rows:
    a=agg[cat];a[0]+=1;a[1]+=ok;a[2]+=(chance or 0)
for k,(n,ok,c) in sorted(agg.items()):print(f"{k:15s} n={n:3d} potted={ok:3d} ({100*ok/n:4.0f}%) mean model chance {c/n:4.0f}")
