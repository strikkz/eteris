#!/bin/bash
# M-1 DVR window (run 32)
set -u
out=tools/catalog_dump.txt; : > $out
UA="Mozilla/5.0"
m=$(curl -s -m 20 -A "$UA" "https://video.m-1.fm/m-1_audio/m-1_audio.stream/playlist.m3u8?DVR")
echo "$m" | head -8 >> $out
ch=$(echo "$m" | grep -v '^#' | head -1)
base="https://video.m-1.fm/m-1_audio/m-1_audio.stream/"
c=$(curl -s -m 20 -A "$UA" "$base$ch")
echo "chunklist lines: $(echo "$c" | wc -l)" >> $out
echo "$c" | head -8 >> $out
echo "$c" | grep -c EXTINF >> $out
echo "$c" | grep -E "EXTINF" | head -2 >> $out
python3 - "$c" >> $out <<'PY'
import sys,re
s=sys.argv[1]; d=sum(float(x) for x in re.findall(r'#EXTINF:([\d.]+)',s)); print('total hours', round(d/3600,2))
PY
