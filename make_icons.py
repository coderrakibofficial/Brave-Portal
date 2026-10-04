"""Regenerates all icon / splash / logo images from the BRAVE source image.
Usage: pip install pillow numpy && python3 make_icons.py path/to/brave_logo.png
"""
import sys, numpy as np
from PIL import Image, ImageDraw, ImageFont
src = sys.argv[1] if len(sys.argv) > 1 else "store-assets/brave_source.png"
RES = "app/src/main/res"
im = Image.open(src).convert("RGB")
# crop crown + wordmark (ignore the outer gold frame)
logo = im.crop((120, 310, 1120, 870))
a = np.asarray(logo).astype(float)
mx = a.max(axis=2)
alpha = np.clip((mx - 14) / 50.0, 0, 1)
rgba = np.dstack([a, alpha * 255]).astype(np.uint8)
mark = Image.fromarray(rgba, "RGBA")
bbox = mark.split()[3].point(lambda v: 255 if v > 40 else 0).getbbox()
mark = mark.crop(bbox)

def fit(img, w):  # scale to width
    return img.resize((w, round(img.height * w / img.width)), Image.LANCZOS)

def canvas(size, width_frac, bg=None, shift=0.0):
    c = Image.new("RGBA", (size, size), bg or (0, 0, 0, 0))
    m = fit(mark, round(size * width_frac))
    c.alpha_composite(m, ((size - m.width) // 2, (size - m.height) // 2 + round(size * shift)))
    return c

def save(img, path):
    import os; os.makedirs(os.path.dirname(path), exist_ok=True); img.save(path)

# in-app logo (loading + offline screens)
save(fit(mark, 720), f"{RES}/drawable-nodpi/brave_logo.png")

# adaptive foreground: logo inside the 66% safe zone
dens = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
for d, k in dens.items():
    fg = canvas(round(108 * k), 0.58)
    save(fg, f"{RES}/mipmap-{d}/ic_launcher_foreground.png")
    # monochrome (themed icons, Android 13+)
    al = fg.split()[3]
    lum = np.asarray(fg.convert("L")).astype(float) / 255
    mono = Image.new("RGBA", fg.size, (255, 255, 255, 0))
    mono.putalpha(Image.fromarray((np.asarray(al) * np.clip(lum * 1.6, 0.35, 1)).astype(np.uint8)))
    save(mono, f"{RES}/mipmap-{d}/ic_launcher_monochrome.png")
    # legacy (API < 26) square + round
    s = round(48 * k)
    leg = canvas(s, 0.80, (0, 0, 0, 255))
    ImageDraw.Draw(leg).rounded_rectangle((0, 0, s - 1, s - 1), round(s * .18), outline=(212, 175, 55, 255), width=max(1, s // 32))
    save(leg, f"{RES}/mipmap-{d}/ic_launcher.png")
    rnd = canvas(s, 0.66, (0, 0, 0, 255))
    mask = Image.new("L", (s, s), 0); ImageDraw.Draw(mask).ellipse((0, 0, s - 1, s - 1), fill=255)
    out = Image.new("RGBA", (s, s), (0, 0, 0, 0)); out.paste(rnd, (0, 0), mask)
    save(out, f"{RES}/mipmap-{d}/ic_launcher_round.png")

# splash icon: logo + PORTAL, kept inside the circular mask of the Android 12 splash
S = 960
sp = Image.new("RGBA", (S, S), (0, 0, 0, 0))
m = fit(mark, round(S * 0.50))
y0 = (S - m.height) // 2 - 36
sp.alpha_composite(m, ((S - m.width) // 2, y0))
d = ImageDraw.Draw(sp)
font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSerif-Bold.ttf", 46)
txt, gap = "PORTAL", 22
w = sum(d.textlength(ch, font=font) for ch in txt) + gap * (len(txt) - 1)
x = (S - w) / 2; y = y0 + m.height + 40
for ch in txt:
    d.text((x, y), ch, font=font, fill=(222, 186, 92, 255)); x += d.textlength(ch, font=font) + gap
save(sp, f"{RES}/drawable-nodpi/splash_icon.png")

# Play Store 512 icon
save(canvas(512, 0.80, (0, 0, 0, 255)).convert("RGB"), "store-assets/play_store_icon_512.png")
print("done")
