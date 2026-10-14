"""Generates the Wify logo and every icon size. Run: python design/make_logo.py  (needs Pillow)"""
from __future__ import annotations
import math, os
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
S = 2048  # master canvas; everything is drawn big then downsampled for smooth edges
TOP, BOTTOM = (0, 201, 167), (24, 90, 219)     # teal -> blue


def gradient(size: int) -> Image.Image:
    img = Image.new("RGB", (size, size))
    px = img.load()
    for y in range(size):
        for x in range(size):
            t = (x * 0.35 + y * 0.65) / size          # diagonal sweep
            px[x, y] = tuple(int(TOP[i] + (BOTTOM[i] - TOP[i]) * t) for i in range(3))
    return img


def glyph(size: int = S) -> Image.Image:
    """White Wi-Fi arcs + dot, with a green 'logged in' check badge. Transparent background, centred in the safe zone."""
    g = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(g)
    cx, cy = size * 0.46, size * 0.62                 # arcs' common centre
    w = size * 0.075                                 # stroke width
    for r in (size * 0.365, size * 0.24, size * 0.115):       # stroke centre-lines
        steps = int(r * math.pi / 2 / 2)                          # stamp round brush dots along the arc: perfectly round ends
        for i in range(steps + 1):
            a = math.radians(225 + 90 * i / steps)
            x, y = cx + r * math.cos(a), cy + r * math.sin(a)
            d.ellipse((x - w / 2, y - w / 2, x + w / 2, y + w / 2), fill="white")
    dr = size * 0.062
    d.ellipse((cx - dr, cy - dr, cx + dr, cy + dr), fill="white")
    # check badge
    bx, by, br = size * 0.735, size * 0.745, size * 0.12
    d.ellipse((bx - br - w * 0.35, by - br - w * 0.35, bx + br + w * 0.35, by + br + w * 0.35), fill=(24, 90, 219))
    d.ellipse((bx - br, by - br, bx + br, by + br), fill=(34, 197, 94))
    pts = [(bx - br * 0.48, by + br * 0.02), (bx - br * 0.12, by + br * 0.40), (bx + br * 0.52, by - br * 0.34)]
    d.line(pts, fill="white", width=int(br * 0.28), joint="curve")
    for p in (pts[0], pts[-1]):
        rr = br * 0.14
        d.ellipse((p[0] - rr, p[1] - rr, p[0] + rr, p[1] + rr), fill="white")
    return g


def rounded_icon(size: int, radius_frac: float = 0.225) -> Image.Image:
    bg = gradient(S).convert("RGBA")
    mask = Image.new("L", (S, S), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, S - 1, S - 1), radius=int(S * radius_frac), fill=255)
    bg.putalpha(mask)
    bg.alpha_composite(glyph())
    return bg.resize((size, size), Image.LANCZOS)


def save(img: Image.Image, *path: str, rgb: bool = False):
    p = os.path.join(ROOT, *path)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    (img.convert("RGB") if rgb else img).save(p)


def main():
    master = rounded_icon(1024)
    save(master, "docs", "logo.png")
    # iOS: 1024 square, no alpha, no pre-rounded corners (iOS rounds it)
    sq = gradient(S).convert("RGBA"); sq.alpha_composite(glyph())
    save(sq.resize((1024, 1024), Image.LANCZOS), "ios", "App", "Assets.xcassets", "AppIcon.appiconset", "icon.png", rgb=True)
    # Android adaptive icon: 432px layers (108dp @ xxxhdpi); glyph kept inside the 66dp safe zone
    fg = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    small = glyph().resize((int(S * 0.58), int(S * 0.58)), Image.LANCZOS)
    fg.alpha_composite(small, ((S - small.width) // 2, (S - small.height) // 2))
    save(fg.resize((432, 432), Image.LANCZOS), "android", "app", "src", "main", "res", "drawable-nodpi", "ic_launcher_fg.png")
    save(gradient(S).resize((432, 432), Image.LANCZOS), "android", "app", "src", "main", "res", "drawable-nodpi", "ic_launcher_bg.png", rgb=True)
    # Windows: multi-size .ico + PNG used by the tray
    sizes = [16, 24, 32, 48, 64, 128, 256]
    master.save(os.path.join(ROOT, "windows", "wify.ico"), sizes=[(s, s) for s in sizes])
    save(rounded_icon(128), "windows", "assets", "logo.png")
    # social square (no rounded corners) and a wide banner for README
    save(sq.resize((1080, 1080), Image.LANCZOS), "design", "wify-social-1080.png", rgb=True)


if __name__ == "__main__":
    main()
