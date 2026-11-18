#!/bin/bash
# Europa Plus favicons in the world catalog and what each favicon address returns (run 3)
set -u
out=tools/catalog_dump.txt; : > $out
curl -sf -m 30 -A "Eteris/1.0 (Android)" "https://de1.api.radio-browser.info/json/stations/search?name=europa%20plus&hidebroken=true&order=clickcount&reverse=true&limit=40" -o /tmp/e.json || curl -sf -m 30 "https://fi1.api.radio-browser.info/json/stations/search?name=europa%20plus&hidebroken=true&order=clickcount&reverse=true&limit=40" -o /tmp/e.json
python3 - >> $out <<'PY'
import json,subprocess
for o in json.load(open('/tmp/e.json')):
    f=o.get('favicon') or ''
    info=''
    if f:
        r=subprocess.run(['curl','-s','-m','10','-L','-A','Eteris/1.0 (Android)','-o','/tmp/f','-w','%{http_code} %{content_type} %{size_download} %{url_effective} redirects=%{num_redirects}',f],capture_output=True,text=True)
        info=r.stdout
        t=subprocess.run(['file','-b','/tmp/f'],capture_output=True,text=True).stdout.strip()
        info+=' | '+t[:60]
    print(o['countrycode'],'|',o['name'],'|',o['clickcount'],'|',f,'|',info)
PY
