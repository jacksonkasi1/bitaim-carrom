import json,glob,datetime,os,sys
for f in sorted(glob.glob(sys.argv[1]+'/*/shots.jsonl')):
    print('==',os.path.basename(os.path.dirname(f)))
    for l in open(f,encoding='utf-8'):
        d=json.loads(l);t=datetime.datetime.fromtimestamp(d['t']/1000).strftime('%H:%M:%S')
        if d.get('opp') or d.get('autoPull') is None:continue
        r=d.get('autoRoute') or {};ac=r.get('aimCorrection') or {}
        print('  ',t,'own',d.get('ownPotted'),'|',r.get('label','(fallback)')[:26].ljust(26),'sw',str(r.get('sweep'))[0],'ch',r.get('chance'),'st',r.get('stress'),'tol',r.get('tolerance'),'spd',r.get('plannedSpeed'),'aim',ac.get('initialDeg'),'->',ac.get('finalDeg'),'dots',ac.get('dots'),d.get('images'))
