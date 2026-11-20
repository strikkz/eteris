#!/bin/bash
# Save station archive pages for study (run 22)
set -u
out=tools/catalog_dump.txt; : > $out
mkdir -p tools/pages
UA="Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36"
while read name url; do
  [ -z "$name" ] && continue
  code=$(curl -s -m 30 -L -A "$UA" -D tools/pages/$name.headers -o tools/pages/$name.html -w "%{http_code} %{size_download} %{url_effective}" "$url")
  cors=$(curl -s -m 20 -D - -o /dev/null -H "Origin: https://strikkz.github.io" -A "$UA" "$url" | tr -d '\r' | grep -i "^access-control-allow-origin" )
  echo "$name $code CORS:[$cors]" >> $out
done < tools/fetch_list.txt
