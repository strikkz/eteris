#!/bin/bash
# radijas.lt podcasts by id (run 26)
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
home=json.dumps(req(base+"/screens/home-screen/", tok=tok),ensure_ascii=False)
print("component types", sorted(set(re.findall(r'"type": "([a-z-]+)"',home))))
print("podcast ids", sorted(set(re.findall(r'/podcasts/[a-z0-9-]*/?(\d+)',home)))[:40])
print("section refs", sorted(set(re.findall(r'"(?:remote_data_url|url|link|slug)": "([^"]{3,120})"',home)))[:40])
for path in ["/sections/irasai/","/podcasts/56137/","/podcasts/56137/episodes/?page=1","/screens/podcasts/56137/"]:
    try: d=req(base+path, tok=tok); print("OK", path, json.dumps(d,ensure_ascii=False)[:1800])
    except Exception as ex: print("ERR", path, ex)
PY
