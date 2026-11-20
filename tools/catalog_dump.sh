#!/bin/bash
# CORS for podcast endpoints (run 8)
set -u
out=tools/catalog_dump.txt; : > $out
for u in "https://itunes.apple.com/search?media=podcast&entity=podcast&country=lt&limit=2&term=LRT" \
         "https://itunes.apple.com/lookup?id=1434811262&entity=podcastEpisode&limit=3&country=lt" \
         "https://itunes.apple.com/lt/rss/toppodcasts/limit=5/json" \
         "https://www.spreaker.com/show/3085666/episodes/feed"; do
  echo "== $u" >> $out
  curl -s -m 20 -D - -o /tmp/b -H "Origin: https://strikkz.github.io" "$u" | tr -d '\r' | grep -iE "^(HTTP|access-control|content-type)" >> $out
done
curl -s -m 20 "https://itunes.apple.com/lookup?id=1434811262&entity=podcastEpisode&limit=3&country=lt" | python3 -c "
import json,sys; d=json.load(sys.stdin)
for r in d['results']: print({k:(str(v)[:80]) for k,v in r.items() if k in ('wrapperType','kind','trackName','episodeUrl','releaseDate','trackTimeMillis','artworkUrl600','artworkUrl160','description','episodeGuid','collectionId')})" >> $out
