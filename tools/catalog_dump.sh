#!/bin/bash
# Station sites: show archives? (run 19)
set -u
out=tools/catalog_dump.txt; : > $out
UA="Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36"
for u in https://www.ziniuradijas.lt https://www.ziniuradijas.lt/laidos https://www.m-1.fm https://www.radiocentras.lt https://www.zipfm.lt https://www.lietus.fm https://www.relaxfm.lt https://www.goldfm.lt https://www.rockfm.lt https://ehr.lt https://www.marijosradijas.lt https://www.radior.lt; do
  curl -s -m 25 -L -A "$UA" "$u" -o /tmp/p.html -w "== $u %{http_code} %{url_effective}\n" >> $out
  grep -oiE '(href|src)="[^"]*(podcast|podkast|irasai|įrašai|archyvas|laidos|\.mp3|spotify\.com/show|podcasts\.apple|soundcloud|mixcloud|youtube\.com/@?[a-z]+)[^"]*"' /tmp/p.html | sort -u | head -8 >> $out
done
