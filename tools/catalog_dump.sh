#!/bin/bash
# All stations the catalog lists as Lithuanian, with language and tags (run 6)
set -u
out=tools/catalog_dump.txt; : > $out
curl -sf -m 30 -A "Eteris/1.0 (Android)" "https://de1.api.radio-browser.info/json/stations/bycountrycodeexact/LT?hidebroken=true&order=clickcount&reverse=true&limit=400" -o /tmp/c.json || curl -sf -m 30 "https://fi1.api.radio-browser.info/json/stations/bycountrycodeexact/LT?hidebroken=true&order=clickcount&reverse=true&limit=400" -o /tmp/c.json
python3 - >> $out <<'PY'
import json
for o in json.load(open('/tmp/c.json')):
    print(o['clickcount'],'|',o['name'].strip(),'|',o.get('language',''),'|',o.get('tags','')[:60],'|',o.get('homepage','')[:50],'|',o.get('state',''))
PY
