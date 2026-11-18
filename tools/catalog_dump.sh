#!/bin/bash
# Logo sources for Europa Plus (run 4)
set -u
out=tools/catalog_dump.txt; : > $out
UA="Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36"
for u in https://liveam.tv/img/2494.jpg http://liveam.tv/img/2494.jpg; do
  echo "$u app-UA: $(curl -s -m 10 -A 'Eteris/1.0 (Android)' -o /dev/null -w '%{http_code} %{size_download}' $u)  browser-UA: $(curl -s -m 10 -A "$UA" -o /dev/null -w '%{http_code} %{size_download}' $u)" >> $out
  curl -s -m 10 -A "$UA" -o /tmp/l.jpg $u; python3 -c "
from PIL import Image; im=Image.open('/tmp/l.jpg'); print('size',im.size)" >> $out 2>&1
done
for h in https://europaplus.ru/ https://www.europaplus.ru/; do
  echo "== $h" >> $out
  curl -s -m 15 -L -A "$UA" $h | grep -oiE '<(link|meta)[^>]+(apple-touch-icon|icon|og:image)[^>]*>' | head -12 >> $out
done
