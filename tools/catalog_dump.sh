#!/bin/bash
# radijas.lt: find podcast ids (run 29)
set -u
out=tools/catalog_dump.txt; : > $out
python3 - >> $out <<'PY'
import json, urllib.request, uuid, concurrent.futures as cf
UA="Mozilla/5.0"
base="https://production.brandsarelive.com/api/v1.3/app/198"
def req(u, data=None, tok=None):
    h={"User-Agent":UA,"Content-Type":"application/json","App-Language":"lt"}
    if tok: h["Authorization"]="JWT "+tok
    r=urllib.request.Request(u, data=json.dumps(data).encode() if data is not None else None, headers=h)
    with urllib.request.urlopen(r, timeout=20) as f: return json.loads(f.read().decode())
tok=req(base+"/social/guest/login/", {"access_token":str(uuid.uuid4())})["access"]
def probe(i):
    try:
        d=req(base+f"/podcasts/{i}/", tok=tok); return (i, d.get("name"), d.get("mode"), (d.get("updated") or "")[:10])
    except Exception as ex: return None
found=[]
with cf.ThreadPoolExecutor(16) as ex:
    for r in ex.map(probe, range(55000, 57400)):
        if r: found.append(r)
for f in found: print(*f, sep=" | ")
print("TOTAL", len(found))
PY
