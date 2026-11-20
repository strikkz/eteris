#!/bin/bash
# Power Hit Radio show pages: where are recordings (run 13)
set -u
out=tools/catalog_dump.txt; : > $out
UA="Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36"
for s in driule-the-gang megahitai ten-walls power-savaitgalis jovaniclub sok-i-kelnes; do
  curl -s -m 30 -L -A "$UA" "https://powerhitradio.tv3.lt/laidos/$s" -o /tmp/p.html
  echo "== $s $(wc -c </tmp/p.html)" >> $out
  grep -oiE '(https?:)?//[^"'"'"' )]*(mixcloud|soundcloud|spotify|\.mp3|\.m4a|\.aac|podbean|spreaker|anchor|youtube\.com/(embed|watch)|youtu\.be|wp-content/uploads[^"'"'"' ]*\.(mp3|m4a))[^"'"'"' )<]*' /tmp/p.html | sort -u | head -12 >> $out
  grep -oiE '<(audio|iframe|source)[^>]{0,200}' /tmp/p.html | head -5 >> $out
done
echo "== podbean 'Power Hit Radio svečiai' feed" >> $out
curl -s -m 20 https://feed.podbean.com/infoutn/feed.xml | grep -oE "<(title|pubDate|itunes:author)>[^<]{0,90}" | head -12 >> $out
