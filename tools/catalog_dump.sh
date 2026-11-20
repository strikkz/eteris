#!/bin/bash
# Archive APIs round 4 (run 23)
set -u
out=tools/catalog_dump.txt; : > $out
python3 - >> $out <<'PY'
import json, re, urllib.request, http.cookiejar
UA="Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36"
cj=http.cookiejar.CookieJar(); op=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))
def get(u, data=None, headers={}):
    r=urllib.request.Request(u, data=data, headers={"User-Agent":UA, **headers})
    with op.open(r, timeout=30) as f: return f.read().decode('utf-8','ignore'), dict(f.headers)
# radijas.lt
try:
    c,_=get("https://www.radijas.lt/config/"); cfg=json.loads(c); print("RADIJAS config keys", list(cfg)[:30]); print("base", cfg.get("base_url"), "app", cfg.get("app_id"))
    base=cfg["base_url"]+str(cfg["app_id"])
    p,h=get(base+"/podcasts/?page=1"); d=json.loads(p); print("podcasts keys", list(d)[:10] if isinstance(d,dict) else type(d)); 
    res=d.get("results", d) if isinstance(d,dict) else d
    print("count", d.get("count") if isinstance(d,dict) else len(res)); print("CORS", h.get("Access-Control-Allow-Origin"))
    for x in res[:40]: print("  ", json.dumps({k:x.get(k) for k in ("id","title","name","slug","image","cover","description")},ensure_ascii=False)[:220])
    pid=res[0]["id"]
    e,_=get(base+f"/podcasts/{pid}/episodes/?page=1"); ed=json.loads(e); er=ed.get("results",ed)
    print("episodes count", ed.get("count") if isinstance(ed,dict) else len(er)); print(json.dumps(er[0],ensure_ascii=False)[:1500])
except Exception as ex: print("RADIJAS ERR", ex)
# marijos
try:
    page,_=get("https://www.marijosradijas.lt/irasai/naujausi-laidu-irasai.html")
    m=re.search(r"var ajaxData = \{'([0-9a-f]+)':'1'\}", page); tok=m.group(1); print("MARIJOS token", tok)
    body=json.dumps({tok:"1","task":"getnextpage","page":0,"filter":"30","date":""}).encode()
    t,_=get("https://www.marijosradijas.lt/irasai/naujausi-laidu-irasai.html", body, {"Content-Type":"application/json; charset=utf-8"})
    cards=re.findall(r'data-audio="([^"]+)" data-broadcast="([^"]+)"[^>]*data-title="([^"]*)"',t); print("filter 30 cards", len(cards), cards[:3])
    body=json.dumps({tok:"1","task":"getnextpage","page":1,"filter":"30","date":""}).encode()
    t,_=get("https://www.marijosradijas.lt/irasai/naujausi-laidu-irasai.html", body, {"Content-Type":"application/json; charset=utf-8"})
    print("page1 cards", len(re.findall(r'data-audio="',t)))
    # without cookies (new opener)
    r=urllib.request.Request("https://www.marijosradijas.lt/irasai/naujausi-laidu-irasai.html", data=json.dumps({tok:"1","task":"getnextpage","page":0,"filter":"30","date":""}).encode(), headers={"User-Agent":UA,"Content-Type":"application/json; charset=utf-8"})
    t2=urllib.request.urlopen(r,timeout=30).read().decode('utf-8','ignore'); print("no-cookie cards", len(re.findall(r'data-audio="',t2)))
except Exception as ex: print("MARIJOS ERR", ex)
# gold podcast page
try:
    t,_=get("https://goldfm.lt/podcast/lina-matiukaite-knygu-serija-kake-make-mokausi-skaityti-palepes-paslaptis-pirmas-zingsnis-salcio-pokstas-ledu-ant-pagaliuko-isradimo-istorija-antras-zingsnis/")
    print("GOLD mp3", sorted(set(re.findall(r'https?://[^"\'\s]+\.mp3',t)))[:5])
except Exception as ex: print("GOLD ERR", ex)
# ziniu letter page
try:
    t,_=get("https://www.ziniuradijas.lt/laidos?letter=ac"); print("ZINIU ac shows", len(re.findall('class="show-item',t)), re.findall(r'<h2>([^<]+)</h2>',t)[:12])
except Exception as ex: print("ZINIU ERR", ex)
PY
