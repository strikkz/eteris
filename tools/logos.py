"""
Builds the station logo pack for the app.

For every Lithuanian station in the radio-browser catalog it collects logo candidates
(catalog favicon, the station website's apple-touch-icon / icons / og:image, Google's favicon service),
picks the best one, and renders it as a clean 256x256 square PNG:
    app/src/main/assets/logos/<station-key>.png
Also writes logos/index.json (which source was used) and docs/logos_sheet.png (a contact sheet to review).
The station key must match StationRepository.norm() in the app.
"""
import concurrent.futures as cf
import io
import json
import os
import re
import unicodedata
import urllib.parse
import urllib.request

from PIL import Image, ImageChops, ImageDraw, ImageFont, ImageOps

try:
    import cairosvg
except Exception:  # SVG support is optional
    cairosvg = None

UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Mobile Safari/537.36"
OUT = "app/src/main/assets/logos"
SIZE = 256
os.makedirs(OUT, exist_ok=True)
os.makedirs("docs", exist_ok=True)

# Manual overrides: station key -> logo URL (or "none" to force the generated tile).
OVERRIDES = json.load(open("tools/logo_overrides.json")) if os.path.exists("tools/logo_overrides.json") else {}

BAD_URL = re.compile(r"w-logo|wordpress|wp-includes|default|placeholder|blank|spacer|gravatar", re.I)


def norm(s):
    t = unicodedata.normalize("NFD", s.lower())
    t = "".join(c for c in t if not unicodedata.combining(c))
    t = re.sub(r"\[[^\]]*\]|\([^)]*\)", " ", t)
    t = re.sub(r"\b\d+\s*k(bps|b/s)?\b", " ", t)
    t = re.sub(r"\b(lt|lietuva|radijas|radio|fm|online|aac\+?|aacp|he-?aac|mp3|ogg|flac|hq|hd|high|low|stream)\b", " ", t)
    key = re.sub(r"[^a-z0-9]+", "", t)
    key = key or "".join(c for c in s.lower() if c.isalnum())
    return ALIASES.get(key, key)


# Must match StationRepository.ALIASES in the app.
ALIASES = {
    "powerhitradiolithuania": "powerhit", "zipfm": "zip", "radijostotism1": "m1",
    "radiostotislaluna": "laluna", "radijostotisradio3": "3", "radijostotiskelyje": "kelyje",
    "goldfm": "gold", "pmrworldmusic": "pmr", "puko": "pukas",
}


def get(url, limit=3_000_000, timeout=5, total=8):
    """Download with a hard time limit (some "logo" or "homepage" links are endless audio streams)."""
    import time
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "*/*"})
    start = time.time()
    with urllib.request.urlopen(req, timeout=timeout) as r:
        ctype = r.headers.get("Content-Type", "")
        if ctype.startswith(("audio/", "video/", "application/ogg")) or "mpegurl" in ctype:
            raise ValueError("stream, not a page or image")
        buf = bytearray()
        while len(buf) < limit:
            if time.time() - start > total:
                raise TimeoutError("too slow")
            chunk = r.read(65536)
            if not chunk:
                break
            buf += chunk
        return r.geturl(), ctype, bytes(buf)


def load_image(data, ctype, url):
    if (b"<svg" in data[:2000] or "svg" in ctype or url.lower().split("?")[0].endswith(".svg")):
        if not cairosvg:
            return None
        data = cairosvg.svg2png(bytestring=data, output_width=512)
    im = Image.open(io.BytesIO(data))
    if getattr(im, "format", "") == "ICO":
        sizes = sorted(im.info.get("sizes", []) or [im.size])
        im.size = sizes[-1]
    im.load()
    return im.convert("RGBA")


def trim(im):
    """Cut transparent or uniform borders."""
    a = im.getchannel("A")
    if a.getextrema()[0] < 250:
        bbox = a.point(lambda v: 255 if v > 20 else 0).getbbox()
    else:
        bg = Image.new("RGBA", im.size, im.getpixel((0, 0)))
        diff = ImageChops.difference(im.convert("RGB"), bg.convert("RGB")).convert("L").point(lambda v: 255 if v > 18 else 0)
        bbox = diff.getbbox()
    return im.crop(bbox) if bbox else im


def homepage_candidates(home):
    out = []
    try:
        final, ctype, html = get(home, limit=600_000)
    except Exception:
        return out
    html = html.decode("utf-8", "replace")
    for tag in re.findall(r"<link[^>]+>", html, re.I):
        rel = re.search(r'rel=["\']([^"\']+)["\']', tag, re.I)
        href = re.search(r'href=["\']([^"\']+)["\']', tag, re.I)
        if not rel or not href or "icon" not in rel.group(1).lower():
            continue
        kind = "apple" if "apple" in rel.group(1).lower() else "icon"
        out.append((kind, urllib.parse.urljoin(final, href.group(1))))
    for prop in ("og:image", "twitter:image"):
        for m in re.finditer(r'<meta[^>]+(?:property|name)=["\']%s["\'][^>]*>' % prop, html, re.I):
            c = re.search(r'content=["\']([^"\']+)["\']', m.group(0), re.I)
            if c:
                out.append(("og", urllib.parse.urljoin(final, c.group(1))))
    m = re.search(r'<link[^>]+rel=["\']manifest["\'][^>]*href=["\']([^"\']+)["\']', html, re.I) or \
        re.search(r'<link[^>]+href=["\']([^"\']+)["\'][^>]*rel=["\']manifest["\']', html, re.I)
    if m:
        try:
            murl = urllib.parse.urljoin(final, m.group(1))
            _, _, mj = get(murl, limit=200_000)
            for ic in json.loads(mj).get("icons", []):
                out.append(("manifest", urllib.parse.urljoin(murl, ic.get("src", ""))))
        except Exception:
            pass
    host = urllib.parse.urlparse(final).netloc
    out.append(("apple", f"https://{host}/apple-touch-icon.png"))
    out.append(("google", f"https://www.google.com/s2/favicons?domain={host}&sz=256"))
    return out


BONUS = {"override": 10_000, "apple": 70, "manifest": 60, "catalog": 40, "icon": 20, "google": -30, "og": -90}


def evaluate(kind, url):
    if kind != "override" and BAD_URL.search(url):
        return None
    try:
        final, ctype, data = get(url)
        im = load_image(data, ctype, final)
    except Exception:
        return None
    w, h = trim(im).size
    side = min(w, h)
    if side < 48 and kind != "override":
        return None
    aspect = max(w, h) / max(1, min(w, h))
    if aspect > 2.6 and kind != "override":
        return None
    score = min(side, 400) + BONUS.get(kind, 0) - (aspect - 1) * 60
    return score, kind, final, im


def luminance(rgb):
    r, g, b = [c / 255 for c in rgb[:3]]
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def render(orig):
    """Background and transparency are judged on the original image, placement on the trimmed one."""
    w, h = orig.size
    alpha = orig.getchannel("A")
    transparent = sum(1 for v in alpha.resize((64, 64)).getdata() if v < 200) / 4096 > 0.04
    im = trim(orig)
    if transparent:
        px = [p for p in im.resize((64, 64)).getdata() if p[3] > 200]
        lum = sum(luminance(p) for p in px) / max(1, len(px))
        bg = (24, 29, 34, 255) if lum > 0.72 else (255, 255, 255, 255)
        pad = 0.14
    else:
        tw, th = im.size
        if max(tw, th) / min(tw, th) < 1.18 and im.size == orig.size:
            return ImageOps.fit(im, (SIZE, SIZE), Image.LANCZOS).convert("RGB")
        rgb = orig.convert("RGB")
        edge = [rgb.getpixel((x, y)) for x in range(0, w, max(1, w // 40)) for y in (0, h - 1)] + \
               [rgb.getpixel((x, y)) for y in range(0, h, max(1, h // 40)) for x in (0, w - 1)]
        edge.sort(key=luminance)
        bg = tuple(edge[len(edge) // 2]) + (255,)
        pad = 0.12
    canvas = Image.new("RGBA", (SIZE, SIZE), bg)
    inner = int(SIZE * (1 - 2 * pad))
    logo = im.copy()
    f = inner / max(logo.size)
    logo = logo.resize((max(1, round(logo.width * f)), max(1, round(logo.height * f))), Image.LANCZOS)
    canvas.alpha_composite(logo, ((SIZE - logo.width) // 2, (SIZE - logo.height) // 2))
    return canvas.convert("RGB")


def process(station):
    key, name, favicons, homes = station
    custom = f"tools/custom_logos/{key}.png"
    if os.path.exists(custom):  # hand-picked logo
        render(Image.open(custom).convert("RGBA")).save(f"{OUT}/{key}.png", optimize=True)
        return key, name, {"kind": "custom", "url": custom, "score": 0}
    cands = []
    ov = OVERRIDES.get(key)
    if ov == "none":
        return key, name, None
    if ov:
        cands.append(("override", ov))
    cands += [("catalog", f) for f in favicons]
    for home in homes[:2]:
        cands += homepage_candidates(home)
    import time
    deadline = time.time() + 25
    seen, best = set(), None
    for kind, url in cands[:12]:
        if time.time() > deadline:
            break
        if not url.startswith("http") or url in seen:
            continue
        seen.add(url)
        r = evaluate(kind, url)
        if r and (best is None or r[0] > best[0]):
            best = r
    if not best:
        return key, name, None
    tile = render(best[3])
    tile.save(f"{OUT}/{key}.png", optimize=True)
    print("ok", name, best[1], flush=True)
    return key, name, {"kind": best[1], "url": best[2], "score": round(best[0])}


def main():
    _, _, raw = get("https://de1.api.radio-browser.info/json/stations/bycountrycodeexact/LT?hidebroken=true&order=clickcount&reverse=true&limit=400")
    groups = {}
    for s in json.loads(raw):
        key = norm(s["name"] or "")
        if not key or not (s.get("url_resolved") or s.get("url")):
            continue
        g = groups.setdefault(key, [key, s["name"].strip(), [], []])
        if (s.get("favicon") or "").startswith("http") and s["favicon"] not in g[2]:
            g[2].append(s["favicon"])
        if (s.get("homepage") or "").startswith("http") and s["homepage"] not in g[3]:
            g[3].append(s["homepage"])
    for f in os.listdir(OUT):
        os.remove(os.path.join(OUT, f))
    # Whatever is not finished in 6 minutes gets the generated tile in the app.
    ex = cf.ThreadPoolExecutor(32)
    futures = {ex.submit(process, g): g for g in groups.values()}
    results = []
    try:
        for f in cf.as_completed(futures, timeout=360):
            try:
                results.append(f.result())
            except Exception:
                g = futures[f]
                results.append((g[0], g[1], None))
    except cf.TimeoutError:
        print("time limit reached", flush=True)
    done = {r[0] for r in results}
    results += [(g[0], g[1], None) for g in groups.values() if g[0] not in done]
    ex.shutdown(wait=False, cancel_futures=True)
    index = {k: v for k, _, v in results if v}
    os.makedirs("logos", exist_ok=True)
    json.dump({"found": index, "missing": [n for k, n, v in results if not v]}, open("logos/index.json", "w"), indent=1, ensure_ascii=False)
    print(f"logos: {len(index)} of {len(results)}")

    # Contact sheet for review
    cols, cell, label = 10, 128, 30
    items = sorted(results, key=lambda r: r[1].lower())
    rows = (len(items) + cols - 1) // cols
    sheet = Image.new("RGB", (cols * cell, rows * (cell + label)), (70, 74, 80))
    d = ImageDraw.Draw(sheet)
    try:
        font = ImageFont.truetype("DejaVuSans.ttf", 11)
    except Exception:
        font = ImageFont.load_default()
    for i, (k, n, v) in enumerate(items):
        x, y = (i % cols) * cell, (i // cols) * (cell + label)
        p = f"{OUT}/{k}.png"
        if os.path.exists(p):
            sheet.paste(Image.open(p).resize((cell - 8, cell - 8)), (x + 4, y + 4))
        else:
            d.rectangle([x + 4, y + 4, x + cell - 4, y + cell - 4], outline=(255, 90, 90), width=2)
        d.text((x + 4, y + cell), n[:20], fill=(255, 255, 255), font=font)
        d.text((x + 4, y + cell + 13), (v or {}).get("kind", "—"), fill=(200, 200, 200), font=font)
    sheet.save("docs/logos_sheet.png", optimize=True)
    os._exit(0)  # do not wait for stuck downloads


if __name__ == "__main__":
    main()
