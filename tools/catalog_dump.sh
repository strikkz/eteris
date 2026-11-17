#!/bin/bash
# Probes whether M-1 group streams exist in 320 kbps (run 2)
set -u
out=tools/catalog_dump.txt; : > $out
for p in m1 m1plius lietus laluna m-1dance; do for f in MP3320 mp3320 mp3 aac high; do
  u="https://radio.m-1.fm/$p/$f"
  h=$(curl -s -m 8 -D - -o /dev/null -r 0-2000 "$u" | tr -d '\r' | grep -iE '^(HTTP|icy-name|icy-br|content-type)' | tr '\n' ' ')
  echo "$u | $h" >> $out
done; done
