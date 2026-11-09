import json,glob,datetime,sys,math
seen={};
for f in glob.glob('OUTPUTS/phone-data/**/shots.jsonl',recursive=True)+glob.glob('BitAim-v4.0-source/BitAim-v4.0-source/tools/pc-brain/recordings/**/shots.jsonl',recursive=True):
    for l in open(f,encoding='utf-8'):
        try:d=json.loads(l)
        except: continue
        seen[(d.get('t'),d.get('opp'))]=d
print('unique shots',len(seen))
keys=set()
for d in seen.values(): keys|=set(d.keys())
print(sorted(keys))
rows=[]
for d in seen.values():
    r=d.get('result') or {}
    if (r.get('discsBefore') or 0)>=18:
        rows.append(d)
print('full-rack shots',len(rows))
for d in sorted(rows,key=lambda d:d['t']):
    r=d.get('result') or {}; ar=d.get('autoRoute') or {}
    t=datetime.datetime.fromtimestamp(d['t']/1000).strftime('%m-%d %H:%M:%S')
    print(t,'opp' if d.get('opp') else ('auto' if d.get('autoPull') is not None else 'hand'),'st',[round(v) for v in (d.get('striker') or [])[:2]],'aim',[round(v,3) for v in (d.get('aim') or [])],'pull',d.get('autoPull'),'arrow',d.get('arrow'),'|',(ar.get('label') or d.get('status') or '')[:34],'| own',r.get('own'),'oth',r.get('other'),'q',r.get('queen'),'before',r.get('discsBefore'),'after',r.get('discsAfter'),'st_end',r.get('striker'))
