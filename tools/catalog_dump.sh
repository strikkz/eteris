#!/bin/bash
# radijas.lt playlists + audio (run 28)
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
for path in ["/playlists/?page=1","/podcasts/?page=1&page_size=50","/audios/1926979/"]:
    try:
        d=req(base+path, tok=tok)
        if isinstance(d,dict) and "results" in d:
            print("OK", path, "count", d.get("count"))
            for x in d["results"][:60]: print("   ", x.get("id"), "|", x.get("name") or x.get("title"), "|", x.get("mode"), "|", (x.get("updated") or "")[:10])
        else: print("OK", path, json.dumps(d,ensure_ascii=False)[:1200])
    except Exception as ex: print("ERR", path, ex)
PY
