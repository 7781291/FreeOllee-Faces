"""Render the SUPER FREE wordmark lockup to a transparent PNG.

SUPER in Anton (Everforest green), FREE in Permanent Marker (marker red) with
the 100-style double underline. Mirrors guidelines/brand/brand-wordmark.html."""
import os
import tempfile
from fontTools.ttLib.woff2 import decompress
from PIL import Image, ImageDraw, ImageFont

SRC = "/tmp/design_bundle/extracted/super-freeollee-design-system/project/assets/fonts"
OUT = "app/src/commonMain/composeResources/drawable/wordmark_super_free.png"  # relative — run from repo root

for woff2 in ("Anton-400.woff2", "PermanentMarker-400.woff2"):
    if not os.path.exists(f"{SRC}/{woff2}"):
        raise SystemExit(f"Font not found: {SRC}/{woff2} — re-extract the design bundle.")

tmp = tempfile.mkdtemp()
anton = os.path.join(tmp, "anton.ttf")
marker = os.path.join(tmp, "marker.ttf")
decompress(f"{SRC}/Anton-400.woff2", anton)
decompress(f"{SRC}/PermanentMarker-400.woff2", marker)

GREEN = (167, 192, 128, 255)
RED = (248, 85, 82, 255)
W, H = 900, 520
img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
d = ImageDraw.Draw(img)
f_super = ImageFont.truetype(anton, 170)
f_free = ImageFont.truetype(marker, 210)

sw = d.textlength("SUPER", font=f_super)
d.text(((W - sw) / 2, 30), "SUPER", font=f_super, fill=GREEN)

fw = d.textlength("FREE", font=f_free)
fx = (W - fw) / 2
d.text((fx, 210), "FREE", font=f_free, fill=RED)

y = 470
d.line([(fx, y), (fx + fw, y)], fill=RED, width=14)
d.line([(fx + 20, y + 34), (fx + fw + 20, y + 34)], fill=RED, width=14)

os.makedirs(os.path.dirname(OUT), exist_ok=True)
img.save(OUT)
print("Wordmark generated:", OUT)
