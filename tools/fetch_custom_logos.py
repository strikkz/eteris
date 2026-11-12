"""Downloads hand-picked logo URLs (tools/custom_logo_urls.json) into tools/custom_logos/<key>.png
and renders them into the app's logo pack, plus a small review sheet docs/custom_sheet.png."""
import io, json, os, sys
sys.path.insert(0, "tools")
from PIL import Image, ImageDraw
import logos

urls = json.load(open("tools/custom_logo_urls.json"))
os.makedirs("tools/custom_logos", exist_ok=True)
done = []
for key, url in urls.items():
    try:
        _, ctype, data = logos.get(url, total=20, timeout=15)
        im = Image.open(io.BytesIO(data)).convert("RGBA")
        im.save(f"tools/custom_logos/{key}.png")
        logos.render(im).save(f"{logos.OUT}/{key}.png", optimize=True)
        done.append(key)
        print("ok", key, im.size, flush=True)
    except Exception as e:
        print("FAIL", key, e, flush=True)

cell = 128
sheet = Image.new("RGB", (cell * 7, ((len(done) + 6) // 7) * (cell + 16)), (70, 74, 80))
d = ImageDraw.Draw(sheet)
for i, k in enumerate(done):
    x, y = (i % 7) * cell, (i // 7) * (cell + 16)
    sheet.paste(Image.open(f"{logos.OUT}/{k}.png").resize((cell - 6, cell - 6)), (x + 3, y + 3))
    d.text((x + 3, y + cell), k[:18], fill=(255, 255, 255))
sheet.save("docs/custom_sheet.png")
