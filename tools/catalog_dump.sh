#!/bin/bash
# Marijos filter via nextlatest (run 31)
set -u
out=tools/catalog_dump.txt; : > $out
python3 - >> $out <<'PY'
import json, re, urllib.request
UA="Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36"
def get(u, post=None):
    h={"User-Agent":UA}
    if post is not None: h["Content-Type"]="application/json; charset=utf-8"
    r=urllib.request.Request(u, data=post.encode() if post is not None else None, headers=h)
    with urllib.request.urlopen(r, timeout=30) as f: return f.read().decode('utf-8','ignore')
MRP="https://www.marijosradijas.lt/irasai/naujausi-laidu-irasai.html"
h=get(MRP); tok=re.search(r"var ajaxData = \{'([0-9a-f]+)':'1'\}",h).group(1)
for f in ["30","38","21"]:
  for url,task in [("https://www.marijosradijas.lt/dummy-laidos.html","nextlatest"),(MRP,"nextlatest"),("https://www.marijosradijas.lt/irasai/laidos-nuo-a-iki-z.html","getnextpage")]:
    try:
        x=get(url, json.dumps({tok:"1","task":task,"page":0,"filter":f,"date":""}))
        t=re.findall(r'data-title="([^"]*)"',x); a=re.findall(r'data-audio="([^"]*)"',x)
        print(f,url.split('/')[-1],task,len(a),t[:3],a[:1])
    except Exception as e: print(f,url,task,"ERR",e)
PY
