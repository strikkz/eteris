#!/bin/bash
# PHR schedule parse check (run 17)
set -u
out=tools/catalog_dump.txt; : > $out
curl -s -m 30 -L "https://powerhitradio.tv3.lt/laidos" -o /tmp/l.html
python3 - >> $out <<'PY'
import re, html as H
h=open('/tmp/l.html',encoding='utf-8',errors='ignore').read()
shows={}
for b in h.split('data-test="schedule-show-slot"')[1:]:
    wd=re.search(r'wkd-(\d)',b); on=re.search(r'data-timeon="(\d\d):',b); off=re.search(r'data-timeoff="(\d\d):(\d\d)',b)
    slug=re.search(r'href="/laidos/([a-z0-9-]+)"',b); t=re.search(r'scheduleProgramTxt">([^<]+)<',b); img=re.search(r'<img src="([^"]+)"',b)
    if not all([wd,on,off,slug,t]): print('SKIP', b[:120].replace('\n',' ')); continue
    o=int(off.group(1)); s=int(on.group(1))
    if off.group(2)=='59' or o<=s: o = 24 if o<=s else o+1
    shows.setdefault(slug.group(1),[H.unescape(t.group(1)).strip(), img and img.group(1), []])[2].append((int(wd.group(1)),s,o))
for k,v in shows.items(): print(k,'|',v[0],'|',v[1],'|',v[2])
print(len(shows))
PY
