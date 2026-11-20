#!/bin/bash
# Rapsodija: Apple entry, feed dates, LRT site (run 9)
set -u
out=tools/catalog_dump.txt; : > $out
curl -s -m 20 "https://itunes.apple.com/search?media=podcast&entity=podcast&country=lt&limit=5&term=Rapsodija" | python3 -c "
import json,sys
for x in json.load(sys.stdin)['results']: print(x['collectionId'],'|',x['collectionName'],'|',x.get('artistName'),'|',x.get('feedUrl'),'|',x.get('releaseDate'))" >> $out
f=$(curl -s -m 20 "https://itunes.apple.com/search?media=podcast&entity=podcast&country=lt&limit=5&term=Rapsodija" | python3 -c "import json,sys;r=[x for x in json.load(sys.stdin)['results'] if x.get('artistName')=='LRT'];print(r[0]['feedUrl'] if r else '')")
echo "feed: $f" >> $out
curl -s -m 30 -L -A "BANGA/2.0" -D /tmp/h -o /tmp/feed.xml "$f"; grep -iE "^(HTTP|location|cache|last-mod|etag|age)" /tmp/h >> $out
grep -o "<pubDate>[^<]*</pubDate>" /tmp/feed.xml | head -5 >> $out
grep -o "<lastBuildDate>[^<]*" /tmp/feed.xml | head -2 >> $out
echo "== feed with cache-buster" >> $out
curl -s -m 30 -L "$f?nocache=$(date +%s)" | grep -o "<pubDate>[^<]*</pubDate>" | head -3 >> $out
echo "== apple episodes" >> $out
id=$(curl -s -m 20 "https://itunes.apple.com/search?media=podcast&entity=podcast&country=lt&limit=5&term=Rapsodija" | python3 -c "import json,sys;r=[x for x in json.load(sys.stdin)['results'] if x.get('artistName')=='LRT'];print(r[0]['collectionId'] if r else '')")
curl -s -m 20 "https://itunes.apple.com/lookup?id=$id&entity=podcastEpisode&limit=4&country=lt" | python3 -c "
import json,sys
for x in json.load(sys.stdin)['results'][1:]: print(x.get('releaseDate'),'|',x.get('trackName'),'|',x.get('episodeUrl','')[:80])" >> $out
echo "== LRT mediateka search" >> $out
curl -s -m 20 -A "Mozilla/5.0" "https://www.lrt.lt/api/search?q=Rapsodija&count=3&order=desc" | head -c 1500 >> $out
echo >> $out
curl -s -m 20 -A "Mozilla/5.0" "https://www.lrt.lt/mediateka/irasai/rapsodija" -o /tmp/l.html -w "%{http_code} %{url_effective}\n" >> $out
grep -o 'api/[a-z/_]*[^"]\{0,60\}' /tmp/l.html | sort -u | head -15 >> $out
