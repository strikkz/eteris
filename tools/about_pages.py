"""Collects how the main stations describe themselves on their own websites:
meta/og description of the home page and text paragraphs of their "Apie" (about) pages.
Output: tools/about_raw.json, reviewed by hand before anything goes into the app."""
import concurrent.futures as cf, html, json, re, sys, time, urllib.parse
sys.path.insert(0, "tools")
import logos  # same station keys and safe download helper

WANTED = ["lrt", "lrtklasika", "lrtopus", "m1", "lietus", "m1plius", "m1dance", "powerhit", "powergold", "radiocentras",
          "zip", "rock", "ziniu", "relax", "europeanhit", "gold", "kelyje", "top", "laluna", "pukas", "extra", "saules",
          "marijos", "vilnius", "easy", "geras", "super", "start942mhzvilnius", "3", "fiesta", "jazz", "vaiku", "lrtlituanica", "russkojebaltija"]
EXTRA_HOMES = {"radiocentras": ["https://rc.lt/"], "powerhit": ["https://powerhitradio.tv3.lt/apie-pwr"], "zip": ["https://zipfm.lt/"],
               "m1": ["https://m-1.fm/"], "lietus": ["https://lietus.fm/"], "laluna": ["https://laluna.lt/"], "relax": ["https://relaxfm.lt/"],
               "ziniu": ["https://www.ziniuradijas.lt/"], "lrt": ["https://www.lrt.lt/radioteka/lrt-radijas"], "kelyje": ["https://kelyje.lt/"],
               "pukas": ["https://pukas.lt/"], "gold": ["https://goldfm.lt/"], "rock": ["https://rockfm.lt/"]}

def text_of(fragment):
    t = re.sub(r"<(script|style|noscript)[\s\S]*?</\1>", " ", fragment, flags=re.I)
    t = re.sub(r"<[^>]+>", " ", t)
    return re.sub(r"\s+", " ", html.unescape(t)).strip()

def page(url):
    try:
        final, ctype, data = logos.get(url, limit=1_500_000, timeout=8, total=12)
        if "html" not in ctype and not data.lstrip()[:20].lower().startswith((b"<!doctype", b"<html")):
            return None, None
        return final, data.decode("utf-8", "replace")
    except Exception as e:
        return None, None

def describe(final, doc):
    out = {"url": final}
    for name in ("description", "og:description", "twitter:description"):
        m = re.search(r'<meta[^>]+(?:name|property)=["\']%s["\'][^>]*content=["\']([^"\']{20,})["\']' % re.escape(name), doc, re.I) or \
            re.search(r'<meta[^>]+content=["\']([^"\']{20,})["\'][^>]*(?:name|property)=["\']%s["\']' % re.escape(name), doc, re.I)
        if m:
            out[name] = html.unescape(m.group(1)).strip()
    paras = [text_of(p) for p in re.findall(r"<p[^>]*>([\s\S]*?)</p>", doc, re.I)]
    out["paragraphs"] = [p for p in paras if len(p) > 70][:12]
    links = set()
    for href, label in re.findall(r'<a[^>]+href=["\']([^"\'#]+)["\'][^>]*>([\s\S]{0,80}?)</a>', doc, re.I):
        lab = text_of(label).lower()
        if re.search(r"apie|about|istorij|mūsų|musu", lab + " " + href.lower()) and not re.search(r"facebook|instagram|youtube|mailto", href):
            links.add(urllib.parse.urljoin(final, href))
    return out, sorted(links)[:4]

def station(item):
    key, name, homes = item
    res = {"name": name, "homes": homes, "pages": []}
    seen = set()
    for home in homes[:3]:
        final, doc = page(home)
        if not doc or final in seen:
            continue
        seen.add(final)
        info, about_links = describe(final, doc)
        res["pages"].append(info)
        for link in about_links:
            if link in seen:
                continue
            seen.add(link)
            f2, d2 = page(link)
            if d2:
                res["pages"].append(describe(f2, d2)[0])
    return key, res

_, _, raw = logos.get("https://de1.api.radio-browser.info/json/stations/bycountrycodeexact/LT?hidebroken=true&limit=400", total=30, timeout=15)
groups = {}
for s in json.loads(raw):
    k = logos.norm(s.get("name") or "")
    if k in WANTED:
        g = groups.setdefault(k, [k, s["name"], list(EXTRA_HOMES.get(k, []))])
        h = (s.get("homepage") or "").strip()
        if h.startswith("http") and h not in g[2]:
            g[2].append(h)
for k in WANTED:
    if k not in groups and k in EXTRA_HOMES:
        groups[k] = [k, k, EXTRA_HOMES[k]]
result = {}
ex = cf.ThreadPoolExecutor(16)
futs = [ex.submit(station, g) for g in groups.values()]
try:
    for f in cf.as_completed(futs, timeout=300):
        k, r = f.result()
        result[k] = r
        print("done", k, len(r["pages"]), flush=True)
except cf.TimeoutError:
    print("time limit", flush=True)
json.dump(result, open("tools/about_raw.json", "w"), ensure_ascii=False, indent=1)
import os; os._exit(0)
