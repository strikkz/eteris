#!/bin/bash
# PHR show page structure around recordings (run 14)
set -u
out=tools/catalog_dump.txt; : > $out
UA="Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36"
curl -s -m 30 -L -A "$UA" "https://powerhitradio.tv3.lt/laidos/sok-i-kelnes" -o /tmp/p.html
python3 - >> $out <<'PY'
import re
h=open('/tmp/p.html',encoding='utf-8',errors='ignore').read()
i=h.find('/record/pwr_')
print(re.sub(r'\s+',' ',h[i-2500:i+1500]))
print('COUNT', len(re.findall(r'/record/pwr_\d+\.mp3',h)))
print(sorted(set(re.findall(r'/record/pwr_(\d{8})',h)))[-20:])
m=re.search(r'<meta property="og:image" content="([^"]+)"',h); print('OG', m and m.group(1))
m=re.search(r'<meta property="og:description" content="([^"]+)"',h); print('DESC', m and m.group(1))
PY
curl -s -m 20 -I -A "$UA" "https://powerhitradio.tv3.lt$(grep -oE '/record/pwr_[0-9]+\.mp3' /tmp/p.html | head -1)" | tr -d '\r' | grep -iE "^(HTTP|content-type|content-length|accept-ranges|access-control)" >> $out
