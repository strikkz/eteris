#!/bin/bash
# radijas.lt screens list + irasai screen (run 27)
set -u
out=tools/catalog_dump.txt; : > $out
python3 - >> $out <<'PY'
import json, urllib.request, uuid, re
UA="Mozilla/5.0"
base="https://production.brandsarelive.com/api/v1.3/app/198"
def req(u, data=None, tok=None):
    h={"User-Agent":UA,"Content-Type":"application/json","App-Language":"lt"}
    if tok: h["Authorization"]="JWT "+tok
    r=urllib.request.Request(u, data=json.dumps(data).encode() if data is not None else None, headers=h)
    with urllib.request.urlopen(r, timeout=30) as f: return json.loads(f.read().decode())
tok=req(base+"/social/guest/login/", {"access_token":str(uuid.uuid4())})["access"]
sc=req(base+"/choices/screens/", tok=tok); print("SCREENS", json.dumps(sc,ensure_ascii=False)[:1500])
items=sc if isinstance(sc,list) else sc.get("results",[])
for s in items:
    sid=s.get("id") if isinstance(s,dict) else s
    name=json.dumps(s,ensure_ascii=False).lower()
    if "įraš" in name or "irasai" in name or "podcast" in name or "laid" in name:
        d=req(base+f"/screens/home-screen/?screen_id={sid}", tok=tok); t=json.dumps(d,ensure_ascii=False)
        print("SCREEN", sid, t[:3000])
        print("podcast ids", sorted(set(re.findall(r'"id": (\d+), "(?:name|title)"',t)))[:80])
PY
