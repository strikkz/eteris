#!/bin/bash
# LRT radioteka API for Rapsodija (run 10)
set -u
out=tools/catalog_dump.txt; : > $out
UA="Mozilla/5.0"
curl -s -m 20 -A "$UA" "https://www.lrt.lt/api/search?category_id=6559&count=4&order=desc" > /tmp/s.json
python3 - >> $out <<'PY'
import json
d=json.load(open('/tmp/s.json'))
print('keys',list(d.keys())[:10])
for it in d.get('items',[])[:4]:
    print(json.dumps({k:it.get(k) for k in ['id','title','url','date','item_date','duration','media_duration','photo','img_path_prefix','img_path_postfix','is_audio','category_title','content','summary']},ensure_ascii=False)[:600])
PY
u=$(python3 -c "import json;d=json.load(open('/tmp/s.json'));print(d['items'][0]['url'])")
echo "url $u" >> $out
curl -s -m 20 -A "$UA" "https://www.lrt.lt/servisai/stream_url/vod/media_info/?url=$u" | head -c 1500 >> $out
echo >> $out
