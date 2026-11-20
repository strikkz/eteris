#!/bin/bash
# Podcast sources check (run 7)
set -u
out=tools/catalog_dump.txt; : > $out
echo "== Apple top LT" >> $out
curl -s -m 20 "https://rss.applemarketingtools.com/api/v2/lt/podcasts/top/25/podcasts.json" | python3 -c "
import json,sys
try:
  d=json.load(sys.stdin); r=d['feed']['results']; print(len(r)); [print(x['id'],'|',x['name'],'|',x.get('artistName'),'|',x.get('artworkUrl100','')[:60]) for x in r[:25]]
except Exception as e: print('ERR',e)" >> $out
echo "== itunes top charts LT (older rss)" >> $out
curl -s -m 20 "https://itunes.apple.com/lt/rss/toppodcasts/limit=25/json" | python3 -c "
import json,sys
try:
  d=json.load(sys.stdin); e=d['feed'].get('entry',[]); print(len(e)); [print(x['id']['attributes']['im:id'],'|',x['im:name']['label']) for x in e[:25]]
except Exception as ex: print('ERR',ex)" >> $out
echo "== search LRT country=lt" >> $out
curl -s -m 20 "https://itunes.apple.com/search?media=podcast&entity=podcast&country=lt&limit=15&term=LRT" | python3 -c "
import json,sys
d=json.load(sys.stdin); print(d.get('resultCount'))
for x in d['results']: print(x['collectionId'],'|',x['collectionName'],'|',x.get('artistName'),'|',x.get('feedUrl'),'|',x.get('artworkUrl600','')[:50])" >> $out
echo "== search 'podkastas'" >> $out
curl -s -m 20 "https://itunes.apple.com/search?media=podcast&entity=podcast&country=lt&limit=15&term=podkastas" | python3 -c "
import json,sys
d=json.load(sys.stdin); print(d.get('resultCount'))
for x in d['results']: print(x['collectionId'],'|',x['collectionName'],'|',x.get('artistName'),'|',x.get('feedUrl'))" >> $out
echo "== a feed" >> $out
f=$(curl -s -m 20 "https://itunes.apple.com/search?media=podcast&entity=podcast&country=lt&limit=1&term=LRT" | python3 -c "import json,sys;print(json.load(sys.stdin)['results'][0]['feedUrl'])")
echo "$f" >> $out
curl -s -m 20 -L "$f" | head -c 2500 >> $out
