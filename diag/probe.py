"""Diagnostics: what do Radiocentras and Power Hit Radio streams send as song info?"""
import json, re, threading, time, urllib.request, urllib.parse

UA = "Eteris/1.0 (Android) diag"
LOCK = threading.Lock()


def log(*a):
    with LOCK:
        print(*a, flush=True)


def get(url, headers=None, timeout=15):
    req = urllib.request.Request(url, headers={"User-Agent": UA, **(headers or {})})
    return urllib.request.urlopen(req, timeout=timeout)


# 1. Catalog entries
cat = json.load(get("https://de1.api.radio-browser.info/json/stations/bycountrycodeexact/LT?limit=500"))
want = re.compile(r"radiocentr|power|super fm|european hit", re.I)
targets = []
for s in cat:
    if want.search(s["name"]):
        log("CAT", json.dumps({k: s.get(k) for k in ("name", "url", "url_resolved", "codec", "bitrate", "hls", "homepage", "lastcheckok")}, ensure_ascii=False))
        u = s.get("url_resolved") or s.get("url")
        if u and u not in [t[1] for t in targets]:
            targets.append((s["name"], u))


# 2. Listen to each stream for a few minutes and log every song change
def listen(name, url, seconds=300):
    tag = f"[{name}]"
    try:
        r = get(url, {"Icy-MetaData": "1"}, timeout=20)
    except Exception as e:
        log(tag, "OPEN FAIL", url, e)
        return
    hdr = {k.lower(): v for k, v in r.headers.items()}
    log(tag, "URL", r.geturl())
    log(tag, "HEADERS", json.dumps(hdr))
    metaint = int(hdr.get("icy-metaint", "0") or 0)
    start = time.time()
    first = r.read(16)
    log(tag, "FIRST BYTES", first[:16])
    buf = first
    last = None
    total = len(first)
    if metaint:
        # Proper ICY parsing
        pos = len(first)  # bytes of audio already consumed
        data = first
        while time.time() - start < seconds:
            need = metaint - pos
            while need > 0:
                chunk = r.read(min(need, 65536))
                if not chunk:
                    log(tag, "STREAM ENDED"); return
                need -= len(chunk); total += len(chunk)
            pos = 0
            ln = r.read(1)[0] * 16
            if ln:
                meta = r.read(ln)
                txt = meta.rstrip(b"\0")
                try:
                    txt = txt.decode("utf-8")
                except UnicodeDecodeError:
                    txt = txt.decode("cp1257", "replace")
                if txt != last:
                    log(tag, f"t={int(time.time()-start)}s ICY:", txt)
                    last = txt
    else:
        # No ICY: scan raw bytes for in-band ID3 / StreamTitle
        seen = set()
        while time.time() - start < seconds:
            chunk = r.read(65536)
            if not chunk:
                log(tag, "STREAM ENDED"); return
            total += len(chunk)
            for m in re.finditer(rb"(TIT2|TPE1|StreamTitle=)(.{0,120})", chunk, re.S):
                s = m.group(0)[:140]
                if s not in seen:
                    seen.add(s)
                    log(tag, f"t={int(time.time()-start)}s INBAND:", s)
    log(tag, "DONE bytes", total)


threads = [threading.Thread(target=listen, args=t) for t in targets[:8]]
for t in threads:
    t.start()


# 3. Station websites: look for a "now playing" source
def scan_site(url):
    try:
        r = get(url, timeout=20)
        html = r.read(800_000).decode("utf-8", "replace")
    except Exception as e:
        log("SITE FAIL", url, e)
        return
    log("SITE", url, "->", r.geturl(), len(html), "chars")
    hits = set(re.findall(r"""["'](https?://[^"'\s]{5,200}|/[^"'\s]{3,200})["']""", html))
    for h in sorted(hits):
        if re.search(r"now|play|song|track|current|meta|api|json|stream|radio|onair|eter|grojar", h, re.I) and not re.search(r"\.(png|jpe?g|svg|webp|gif|woff2?|css)(\?|$)", h, re.I):
            log("  LINK", h)
    for m in re.finditer(r"(?i)(now.?playing|currentsong|current_song|onair|grojaraš|playlist)", html):
        log("  TEXT", html[max(0, m.start()-120): m.end()+160].replace("\n", " "))
    # script files
    for js in re.findall(r"""<script[^>]+src=["']([^"']+)["']""", html)[:25]:
        full = urllib.parse.urljoin(r.geturl(), js)
        try:
            code = get(full, timeout=20).read(3_000_000).decode("utf-8", "replace")
        except Exception:
            continue
        for m in re.finditer(r"""["'`](https?://[^"'`\s]{5,200}|/[^"'`\s]{3,150})["'`]""", code):
            h = m.group(1)
            if re.search(r"now.?play|song|track|current|meta|onair|playlist|stream|icecast|api", h, re.I):
                log("  JS", full.split("?")[0][-60:], "->", h)


for site in [
    "https://www.powerhitradio.lt/", "https://powerhitradio.tv3.lt/", "https://www.tv3.lt/radijas",
    "https://www.rc.lt/", "https://www.radiocentras.lt/", "https://radiocentras.tv3.lt/",
]:
    scan_site(site)
for s in cat:
    if re.search(r"radiocentr|power", s["name"], re.I) and s.get("homepage"):
        scan_site(s["homepage"])

for t in threads:
    t.join()
log("ALL DONE")
