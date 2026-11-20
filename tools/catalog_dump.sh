#!/bin/bash
# Power Hit Radio shows (run 12)
set -u
out=tools/catalog_dump.txt; : > $out
UA="Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36"
echo "== apple search" >> $out
for q in "Power Hit Radio" "powerhitradio" "PHR" "tv3 podcast"; do
curl -s -m 20 "https://itunes.apple.com/search?media=podcast&entity=podcast&country=lt&limit=15&term=$(python3 -c "import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1]))" "$q")" | python3 -c "
import json,sys
for x in json.load(sys.stdin)['results']: print('$q','|',x['collectionId'],'|',x['collectionName'],'|',x.get('artistName'),'|',x.get('feedUrl'),'|',x.get('releaseDate'))" >> $out
done
echo "== laidos page" >> $out
curl -s -m 30 -L -A "$UA" "https://powerhitradio.tv3.lt/laidos" -o /tmp/l.html -w "%{http_code} %{size_download}\n" >> $out
grep -oE 'href="[^"]*(laid|podcast|spotify|apple|youtube|mp3|audio|irasai)[^"]*"' /tmp/l.html | sort -u | head -60 >> $out
grep -oE '<h[1-4][^>]*>[^<]{2,80}</h[1-4]>' /tmp/l.html | head -40 >> $out
