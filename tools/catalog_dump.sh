#!/bin/bash
# PHR laidos list structure + CORS (run 15)
set -u
out=tools/catalog_dump.txt; : > $out
UA="Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36"
curl -s -m 20 -D - -o /dev/null -H "Origin: https://strikkz.github.io" -A "$UA" "https://powerhitradio.tv3.lt/laidos/sok-i-kelnes" | tr -d '\r' | grep -iE "^(HTTP|access-control)" >> $out
curl -s -m 30 -L -A "$UA" "https://powerhitradio.tv3.lt/laidos" -o /tmp/l.html
python3 - >> $out <<'PY'
import re
h=open('/tmp/l.html',encoding='utf-8',errors='ignore').read()
i=h.find('/laidos/driule-the-gang')
print(re.sub(r'\s+',' ',h[i-1500:i+1200]))
PY
for s in megahitai ten-walls driule-the-gang; do
curl -s -m 30 -L -A "$UA" "https://powerhitradio.tv3.lt/laidos/$s" -o /tmp/p.html
python3 - "$s" >> $out <<'PY'
import re,sys
h=open('/tmp/p.html',encoding='utf-8',errors='ignore').read()
print(sys.argv[1], 'records', len(re.findall(r'/record/pwr_\d+\.mp3',h)), re.findall(r'djLaidosFonasLine">([^<]*(?:<span>[^<]*</span>[^<]*)*)',h)[:1])
i=h.find('Laidos įrašai'); print('has section', i>0)
PY
done
