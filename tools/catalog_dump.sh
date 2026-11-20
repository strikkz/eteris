#!/bin/bash
# radijas.lt navigation + screens (run 25)
set -u
out=tools/catalog_dump.txt; : > $out
python3 - >> $out <<'PY'
import json, urllib.request, uuid
UA="Mozilla/5.0"
base="https://production.brandsarelive.com/api/v1.3/app/198"
def req(u, data=None, tok=None):
    h={"User-Agent":UA,"Content-Type":"application/json","App-Language":"lt"}
    if tok: h["Authorization"]="JWT "+tok
    r=urllib.request.Request(u, data=json.dumps(data).encode() if data is not None else None, headers=h)
    with urllib.request.urlopen(r, timeout=30) as f: return json.loads(f.read().decode())
cfg=json.loads(urllib.request.urlopen(urllib.request.Request("https://www.radijas.lt/config/",headers={"User-Agent":UA}),timeout=30).read())
print("NAV", json.dumps(cfg.get("navigation_config"),ensure_ascii=False)[:2500])
tok=req(base+"/social/guest/login/", {"access_token":str(uuid.uuid4())})["access"]
for path in ["/screens/home-screen/","/screens/home-screen/irasai","/sections/","/podcasts/","/screens/podcasts/"]:
    try: d=req(base+path, tok=tok); print("OK", path, json.dumps(d,ensure_ascii=False)[:1500])
    except Exception as ex: print("ERR", path, ex)
PY
