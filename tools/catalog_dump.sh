#!/bin/bash
# PHR record retention (run 16)
set -u
out=tools/catalog_dump.txt; : > $out
for d in 0 1 3 6 7 8 10 14; do
  day=$(TZ=Europe/Vilnius date -d "-$d day" +%Y%m%d)
  code=$(curl -s -m 15 -o /dev/null -w "%{http_code}" -I "https://powerhitradio.tv3.lt/record/pwr_${day}080000.mp3")
  echo "$d days ago $day 08h: $code" >> $out
done
curl -s -m 30 -L "https://powerhitradio.tv3.lt/laidos" | grep -oE 'scheduleItemBlock wkd-[0-9]' | sort | uniq -c >> $out
