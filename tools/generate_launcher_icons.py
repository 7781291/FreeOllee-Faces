"""Generate Android launcher icons from the design-system 1024px app icon.

Legacy square/round icons use the full framed art; the adaptive foreground
scales the art into the ~62% safe zone on a transparent canvas (the adaptive
background color matches the art's canvas, so the seam is invisible)."""
import os
from PIL import Image

SRC = "/tmp/design_bundle/extracted/super-freeollee-design-system/project/assets/app-icon-1024.png"
RES = "app/src/androidMain/res"  # relative — run this from the repo root

if not os.path.exists(SRC):
    raise SystemExit(f"Source not found: {SRC} — re-extract the design bundle.")

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
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    content = int(size * 0.62)
    scaled = art.resize((content, content), Image.LANCZOS)
    off = (size - content) // 2
    canvas.alpha_composite(scaled, (off, off))
    save(canvas, density, "ic_launcher_foreground.png")

print("Launcher icons generated.")
