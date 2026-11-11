"""Diagnostics 2: Radiocentras stream over time + now-playing endpoints of Radiocentras and Power Hit Radio."""
import re, threading, time, urllib.request

UA = "Mozilla/5.0 (Linux; Android 14) Eteris/1.0"
LOCK = threading.Lock()
DURATION = 420


def log(*a):
    with LOCK:
        print(*a, flush=True)


def fetch(url, headers=None, data=None, timeout=15):
    req = urllib.request.Request(url, data=data, headers={"User-Agent": UA, **(headers or {})})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.status, dict(r.headers), r.read(200_000)


def listen(name, url):
    tag = f"[{name}]"
    try:
        req = urllib.request.Request(url, headers={"User-Agent": UA, "Icy-MetaData": "1"})
        r = urllib.request.urlopen(req, timeout=20)
    except Exception as e:
        log(tag, "OPEN FAIL", e); return
    hdr = {k.lower(): v for k, v in r.headers.items()}
    log(tag, "URL", r.geturl())
    log(tag, "HEADERS", hdr)
    mi = int(hdr.get("icy-metaint", "0") or 0)
    start, last, seen = time.time(), None, set()
    if not mi:
        log(tag, "NO ICY")
    while time.time() - start < DURATION:
        if mi:
            audio = b""
            need = mi
            while need > 0:
                c = r.read(min(need, 65536))
                if not c:
                    log(tag, "ENDED"); return
                audio += c; need -= len(c)
            ln = r.read(1)[0] * 16
            meta = r.read(ln).rstrip(b"\0") if ln else b""
            if ln:
                t = meta.decode("utf-8", "replace")
                if t != last:
                    log(tag, f"t={int(time.time()-start)}s ICY:", t); last = t
        else:
            audio = r.read(65536)
            if not audio:
                log(tag, "ENDED"); return
        for m in re.finditer(rb"(ID3[\x02-\x04]|TIT2|TPE1|StreamTitle=)(.{0,100})", audio, re.S):
            s = m.group(0)
            if s not in seen:
                seen.add(s); log(tag, f"t={int(time.time()-start)}s INBAND:", s[:130])
    log(tag, "DONE")


streams = [
    ("RC stream2.rc.lt", "https://stream2.rc.lt/rc128.mp3"),
    ("RC rockfm host", "https://stream2.rockfm.lt/crf128.mp3"),
]
ths = [threading.Thread(target=listen, args=s) for s in streams]
for t in ths:
    t.start()

# Now-playing endpoints, polled every 45 s
ENDPOINTS = [
    ("RC api", "https://rc.lt/api/network-now-playing.php", None, {}),
    ("PHR lastSong GET", "https://powerhitradio.tv3.lt/Pwr/lastSong/", None, {"X-Requested-With": "XMLHttpRequest"}),
    ("PHR lastSong POST", "https://powerhitradio.tv3.lt/Pwr/lastSong/", b"", {"X-Requested-With": "XMLHttpRequest"}),
    ("ZIP api", "https://zipfm.lt/api/network-now-playing.php", None, {}),
    ("ROCK api", "https://rockfm.lt/api/network-now-playing.php", None, {}),
]
prev = {}
start = time.time()
first = True
while time.time() - start < DURATION:
    for name, url, data, h in ENDPOINTS:
        try:
            st, hd, body = fetch(url, h, data)
            txt = body.decode("utf-8", "replace")
            short = re.sub(r"\s+", " ", txt)
            if first:
                log(f"<{name}> status={st} type={hd.get('Content-Type')} cors={hd.get('Access-Control-Allow-Origin')}")
                log(f"<{name}> BODY:", short[:2500])
            elif prev.get(name) != txt:
                log(f"<{name}> t={int(time.time()-start)}s CHANGED:", short[:700])
            prev[name] = txt
        except Exception as e:
            if first:
                log(f"<{name}> FAIL", e)
    first = False
    time.sleep(45)

for t in ths:
    t.join()

# Power Hit site scripts: how /Pwr/lastSong/ is called
try:
    _, _, html = fetch("https://powerhitradio.tv3.lt/")
    html = html.decode("utf-8", "replace")
    for m in re.finditer(r"lastSong", html):
        log("PHR HTML:", re.sub(r"\s+", " ", html[max(0, m.start()-600): m.end()+600]))
except Exception as e:
    log("PHR HTML FAIL", e)
log("ALL DONE")
