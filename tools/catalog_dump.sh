#!/bin/bash
# Podcasts of Lithuanian stations in Apple's directory (run 18)
set -u
out=tools/catalog_dump.txt; : > $out
python3 - >> $out <<'PY'
import json, urllib.request, urllib.parse
qs=["Žinių radijas","Ziniu radijas","M-1","M-1 Plius","Radiocentras","ZIP FM","Lietus","Relax FM","Gold FM","Rock FM","European Hit Radio","Laisvoji banga","Pūkas","Marijos radijas","Radio R","Kelyje","TOP FM","Easy FM","Extra FM","Saulės radijas","Russkoje Radio Baltija","Laluna","Power Hit Radio","Vilniaus FM","Radijo stotis","radijas podkastas","tv3 podkastas","LNK podkastas","Delfi podkastas","15min podkastas"]
seen=set()
for q in qs:
    u="https://itunes.apple.com/search?media=podcast&entity=podcast&country=lt&limit=25&term="+urllib.parse.quote(q)
    try: d=json.load(urllib.request.urlopen(u,timeout=20))
    except Exception as e: print(q,'ERR',e); continue
    rows=[]
    for x in d['results']:
        a=(x.get('artistName') or ''); n=x['collectionName']
        if x['collectionId'] in seen: continue
        low=(a+' '+n).lower()
        key=q.lower().replace('ž','z').replace('ū','u')
        if any(w in low for w in [q.lower(), key, q.lower().split()[0]]) or 'podkast' in q:
            rows.append(f"   {x['collectionId']} | {n} | {a} | {(x.get('releaseDate') or '')[:10]} | {x.get('trackCount')}")
            seen.add(x['collectionId'])
    print(f"== {q}: {len(rows)}"); print('\n'.join(rows[:12]))
PY
