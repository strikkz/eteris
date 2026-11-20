#!/bin/bash
# radijas.lt API with guest login (run 24)
set -u
out=tools/catalog_dump.txt; : > $out
python3 - >> $out <<'PY'
import json, urllib.request, uuid
UA="Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36"
base="https://production.brandsarelive.com/api/v1.3/app/198"
def req(u, data=None, tok=None, origin=None):
    h={"User-Agent":UA,"Content-Type":"application/json","App-Language":"lt"}
    if tok: h["Authorization"]="JWT "+tok
    if origin: h["Origin"]=origin
    r=urllib.request.Request(u, data=json.dumps(data).encode() if data is not None else None, headers=h)
    with urllib.request.urlopen(r, timeout=30) as f: return json.loads(f.read().decode()), dict(f.headers)
try:
    d,h=req(base+"/social/guest/login/", {"access_token":str(uuid.uuid4())}, origin="https://strikkz.github.io")
    tok=d["access"]; print("guest ok", list(d), "CORS", h.get("Access-Control-Allow-Origin"))
    p,h=req(base+"/podcasts/?page=1", tok=tok, origin="https://strikkz.github.io"); print("CORS2", h.get("Access-Control-Allow-Origin"))
    res=p.get("results",p); print("count", p.get("count"), "next", p.get("next"))
    for x in res: print("  ", json.dumps({k:x.get(k) for k in ("id","title","name","author","image","description")},ensure_ascii=False)[:260])
    pid=res[0]["id"]
    e,_=req(base+f"/podcasts/{pid}/episodes/?page=1", tok=tok); er=e.get("results",e)
    print("episodes", e.get("count")); print(json.dumps(er[0],ensure_ascii=False)[:1600])
    s,_=req(base+"/screens/podcasts/"+str(pid)+"/", tok=tok); print("screen", json.dumps(s,ensure_ascii=False)[:600])
except Exception as ex:
    import traceback; print("ERR", ex); 
    try: print(ex.read()[:500])
    except: pass
PY
