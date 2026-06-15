"""Generate Android launcher icons from the design-system 1024px app icon.

The design tile is a complete square icon (rounded plate on a gradient grid),
so both the legacy icons and the adaptive foreground use the full tile
full-bleed; the launcher mask rounds the corners. The flat adaptive background
only shows under parallax and is color-matched to the tile's canvas."""
import os
from PIL import Image

SRC = "tools/brand/app-icon-1024.png"  # committed design-system source art — run from repo root
RES = "app/src/androidMain/res"  # relative — run this from the repo root

if not os.path.exists(SRC):
    raise SystemExit(f"Source not found: {SRC} — run from the repo root.")

art = Image.open(SRC).convert("RGBA")

LEGACY = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
FOREGROUND = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}


def save(img, density, name):
    d = os.path.join(RES, f"mipmap-{density}")
    os.makedirs(d, exist_ok=True)
    img.save(os.path.join(d, name))


for density, size in LEGACY.items():
    im = art.resize((size, size), Image.LANCZOS)
    save(im, density, "ic_launcher.png")
    save(im, density, "ic_launcher_round.png")

for density, size in FOREGROUND.items():
    # Full-bleed: the design tile is already a complete square icon (rounded
    # plate on a gradient grid), so the adaptive foreground IS the whole tile.
    # The launcher mask rounds the corners; the flat adaptive background only
    # peeks through under parallax and is color-matched to the tile's canvas.
    im = art.resize((size, size), Image.LANCZOS)
    save(im, density, "ic_launcher_foreground.png")

print("Launcher icons generated.")
