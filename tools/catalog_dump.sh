#!/bin/bash
# Verify station show parsers (run 30)
set -u
out=tools/catalog_dump.txt; : > $out
python3 - >> $out <<'PY'
import json, re, urllib.request, uuid, html as H
UA="Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36"
def get(u, post=None, headers={}):
    h={"User-Agent":UA, **headers}
    if post is not None: h.setdefault("Content-Type","application/json; charset=utf-8")
    r=urllib.request.Request(u, data=post.encode() if post is not None else None, headers=h)
    with urllib.request.urlopen(r, timeout=30) as f: return f.read().decode('utf-8','ignore')
# ZINIU
ZR="https://www.ziniuradijas.lt"; shows=[]
for L in ["ac","df","gy","jl","mo","ps","tu","vz"]:
    h=get(f"{ZR}/laidos?letter={L}")
    for b in h.split('class="show-item')[1:]:
        p=re.search(r'href="(/laidos/[a-z0-9-]+)"',b); t=re.search(r'<h2>([^<]+)</h2>',b)
        if p and t: shows.append((p.group(1),H.unescape(t.group(1)).strip()))
print("ZINIU shows", len(shows), shows[:6])
withaudio=0
for path,t in shows[:12]:
    h=get(ZR+path); eps=[]
    for b in h.split('class="small-new')[1:]:
        if "soundtrack=1" not in b: continue
        l=re.search(r'href="('+re.escape(path)+r'/[a-z0-9-]+)',b); ti=re.search(r'<h3>([^<]+)</h3>',b); d=re.search(r'class="date">([^<]+)<',b)
        if l and ti: eps.append((l.group(1),ti.group(1)[:50],d and d.group(1)))
    if eps: withaudio+=1
    print("  ",t,"eps",len(eps),eps[:1])
    if eps and withaudio==1:
        pg=get(ZR+eps[0][0]+"?soundtrack=1"); m=re.search(r'/assets/Uploads/Episodes/Audio/[^"\']+\.mp3',pg); print("   audio", m and m.group(0))
# MARIJOS
MRP="https://www.marijosradijas.lt/irasai/naujausi-laidu-irasai.html"
h=get(MRP); tok=re.search(r"var ajaxData = \{'([0-9a-f]+)':'1'\}",h).group(1)
cats=re.findall(r'class="filter-option" data-filter="(\d+)" data-title="([^"]+)"',h); print("MARIJOS cats", len(cats), cats[:5])
for f,t in cats[:3]:
    body=json.dumps({tok:"1","task":"getnextpage","page":0,"filter":f,"date":""})
    x=get(MRP, body); cards=re.findall(r'<span class="listen-btn"([^>]+)>',x)
    a=cards[0] if cards else ""
    print("  ",t,"cards",len(cards), re.search(r'data-audio="([^"]*)"',a) and re.search(r'data-audio="([^"]*)"',a).group(1), re.search(r'data-broadcast="([^"]*)"',a) and re.search(r'data-broadcast="([^"]*)"',a).group(1))
# GOLD
h=get("https://goldfm.lt/podcasts/")
g=re.findall(r'value="([a-z0-9-]+)" name="_sft_podcastfilter\[\]"[^>]*><label[^>]*>([^<]+)</label>',h); print("GOLD shows", g)
for slug,t in g[:2]:
    x=get(f"https://goldfm.lt/filtered-podcasts/?_sft_podcastfilter={slug}")
    eps=re.findall(r'<div class="sec2"><a href="(https://goldfm\.lt/podcast/[^"]+)">([^<]+)</a></div>\s*<div class="sec6">([^<]+)<',x)
    print("  ",t,"eps",len(eps), eps[:1])
    if eps:
        pg=get(eps[0][0]); m=re.search(r'https://goldfm\.lt/wp-content/uploads/[^"\'\s]+\.mp3',pg); print("   audio", m and m.group(0))
# BAL
BAL="https://production.brandsarelive.com/api/v1.3/app/198"
tok=json.loads(get(BAL+"/social/guest/login/", json.dumps({"access_token":str(uuid.uuid4())})))["access"]
hd={"Authorization":"JWT "+tok,"App-Language":"lt"}
for i in [55814,55813,55846]:
    o=json.loads(get(f"{BAL}/podcasts/{i}/",headers=hd)); e=json.loads(get(f"{BAL}/podcasts/{i}/episodes/?page=1",headers=hd))
    r=e["results"][0]; a=json.loads(get(f"{BAL}/audios/{r['id']}/",headers=hd))
    print("BAL",i,o["name"],"eps",e["count"],r["title"][:40],r["published_date"],"->",a.get("file_url","")[:90])
PY
