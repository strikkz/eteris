#!/bin/bash
# CORS of lrt.lt APIs (run 11)
set -u
out=tools/catalog_dump.txt; : > $out
for u in "https://www.lrt.lt/api/search?count=1&q=Rapsodija" "https://www.lrt.lt/api/search?count=2&order=desc&category_id=6559" "https://www.lrt.lt/servisai/stream_url/vod/media_info/?url=%2Fradioteka%2Firasas%2F2000929126%2Frapsodija-namiskiai-erke" "https://stream-vod3.lrt.lt/AUDIO/2026/11/2026_11_18_3475_1_748c5b33-9382-4f22-907b-b4fd45a441e9.m4a/playlist.m3u8"; do
  echo "== $u" >> $out
  curl -s -m 20 -D - -o /dev/null -H "Origin: https://strikkz.github.io" "$u" | tr -d '\r' | grep -iE "^(HTTP|access-control)" >> $out
done
