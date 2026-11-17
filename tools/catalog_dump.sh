#!/bin/bash
# Dumps M-1 / Lietus entries from the radio-browser catalog and probes their streams (run 1)
set -u
for s in de1 de2 fi1 at1; do
  curl -sf -m 30 -A "Eteris/1.0 (Android)" "https://$s.api.radio-browser.info/json/stations/bycountrycodeexact/LT?hidebroken=true&order=clickcount&reverse=true&limit=400" -o /tmp/cat.json && break
done
python3 - > tools/catalog_dump.txt <<'PY'
import json,re
d=json.load(open('/tmp/cat.json'))
for o in d:
    n=o['name']
    if re.search(r'm-?1|lietus|plius', n, re.I) or re.search(r'm-?1|lietus', o.get('url','')+o.get('url_resolved',''), re.I):
        print(json.dumps({k:o.get(k) for k in ['name','url','url_resolved','bitrate','codec','hls','homepage','favicon','clickcount','votes','lastcheckok']}, ensure_ascii=False))
PY
# What each M-1 stream actually plays (ICY name)
python3 - >> tools/catalog_dump.txt <<'PY'
import json,re,subprocess
d=json.load(open('/tmp/cat.json'))
print("\n--- ICY headers ---")
for o in d:
    if re.search(r'm-?1|lietus|plius', o['name'], re.I):
        u=o['url_resolved'] or o['url']
        try:
            h=subprocess.run(['curl','-s','-m','8','-D','-','-o','/dev/null','-H','Icy-MetaData: 1','-r','0-2000',u],capture_output=True,text=True,timeout=12).stdout
        except Exception as e: h=str(e)
        icy=[l.strip() for l in h.splitlines() if l.lower().startswith(('icy-name','icy-description','icy-url','location'))]
        print(o['name'],'|',u,'|',icy)
PY
