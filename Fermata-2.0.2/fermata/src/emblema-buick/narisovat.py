"""Свой рисунок эмблемы Buick (хромированное кольцо и три щита) на слое значка JARVIS Auto.
Берёт прежний слой jarvis_opel_znachok.webp, оставляет в нём только слабый огонь снаружи кольца (r >= 104),
внутри рисует хром. Запуск: python3 narisovat.py <прежний znachok.webp> <новый znachok.webp> [превью.png ogon.webp]"""
import sys, math
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

src, dst = sys.argv[1], sys.argv[2]
S, K = 512, 4                      # сторона и сверхвыборка
N = S * K
c = N / 2

def shield(cx, cy, w, h):
    """Щит: прямой верх со скруглёнными углами, бока вниз, остриё внизу."""
    pts = []
    top, mid = cy - h / 2, cy + h * 0.02
    r = w * 0.12
    for a in np.linspace(math.pi, 1.5 * math.pi, 8):           # левый верхний угол
        pts.append((cx - w / 2 + r + r * math.cos(a), top + r + r * math.sin(a)))
    for a in np.linspace(1.5 * math.pi, 2 * math.pi, 8):       # правый верхний угол
        pts.append((cx + w / 2 - r + r * math.cos(a), top + r + r * math.sin(a)))
    pts.append((cx + w / 2, mid))
    for t in np.linspace(0, 1, 16)[1:]:                         # правая дуга к острию
        pts.append((cx + w / 2 * (1 - t) ** 0.7, mid + (cy + h / 2 - mid) * t))
    for t in np.linspace(1, 0, 16)[1:]:                         # левая дуга от острия
        pts.append((cx - w / 2 * (1 - t) ** 0.7, mid + (cy + h / 2 - mid) * t))
    return pts

def mask(draw_fn):
    m = Image.new("L", (N, N), 0)
    draw_fn(ImageDraw.Draw(m))
    return m

u = K
# кольцо чуть шире прежнего кольца Opel (было r 84..100), чтобы щиты поместились
ring = mask(lambda d: (d.ellipse([c - 108 * u, c - 108 * u, c + 108 * u, c + 108 * u], fill=255),
                       d.ellipse([c - 94 * u, c - 94 * u, c + 94 * u, c + 94 * u], fill=0)))
# три щита по диагонали: левый выше, правый ниже
W, H, DX, DY = 38 * u, 112 * u, 48 * u, 10 * u
outer, inner, bars = [], [], []
for i in (-1, 0, 1):
    cx, cy = c + i * DX, c + i * DY
    outer.append(shield(cx, cy, W, H))
    inner.append(shield(cx, cy + 1.5 * u, W - 11 * u, H - 13 * u))
    # косая полоса через щит (сверху слева вниз вправо)
    bars.append([(cx - W / 2, cy - H * 0.20), (cx - W / 2, cy - H * 0.06),
                 (cx + W / 2, cy + H * 0.16), (cx + W / 2, cy + H * 0.02)])
sh_out = mask(lambda d: [d.polygon(p, fill=255) for p in outer])
sh_in = mask(lambda d: [d.polygon(p, fill=255) for p in inner])
bar = mask(lambda d: [d.polygon(p, fill=255) for p in bars])
bar = Image.fromarray((np.asarray(bar) * (np.asarray(sh_in) > 0)).astype(np.uint8))

def chrome(m, flip=False):
    """Хром: полосы по высоте (небо — горизонт — земля) и фаска по краю маски."""
    a = np.asarray(m, dtype=np.float32) / 255
    y = np.linspace(0, 1, N)[:, None] * np.ones((1, N))
    if flip: y = 1 - y
    v = np.interp(y, [0, .30, .46, .50, .56, .75, 1], [.98, .80, .45, .22, .80, .62, .40])
    h = np.asarray(m.filter(ImageFilter.GaussianBlur(5 * u)), dtype=np.float32) / 255
    gy, gx = np.gradient(h)
    light = np.clip(1 + (-gx - gy) * 18 * u, .35, 1.6)          # свет сверху слева
    v = np.clip(v * light, 0, 1)
    rgb = np.stack([v * 235 + 12, v * 238 + 10, v * 245 + 10], -1)
    return rgb, a

layers = []
layers.append(chrome(ring))
o_rgb, o_a = chrome(sh_out)
layers.append((o_rgb, o_a))
# внутренность щита — тёмная сталь (огонь за ней не просвечивает, как и за значком Opel)
i_a = np.asarray(sh_in, dtype=np.float32) / 255
yy = np.linspace(0, 1, N)[:, None] * np.ones((1, N))
dv = np.interp(yy, [0, .5, 1], [.30, .12, .22])
layers.append((np.stack([dv * 200 + 15, dv * 205 + 15, dv * 220 + 20], -1), i_a))
layers.append(chrome(bar, flip=True))

rgb = np.zeros((N, N, 3), np.float32); al = np.zeros((N, N), np.float32)
for lr, la in layers:
    rgb = lr * la[..., None] + rgb * (1 - la[..., None]); al = la + al * (1 - la)
big = Image.fromarray(np.dstack([rgb, al * 255]).clip(0, 255).astype(np.uint8), "RGBA")
emb = big.resize((S, S), Image.LANCZOS)

old = Image.open(src).convert("RGBA")
o = np.asarray(old).copy()
yy, xx = np.mgrid[0:S, 0:S]
rr = np.hypot(xx - S / 2 + .5, yy - S / 2 + .5)
o[rr < 112] = 0                                                      # убрать значок Opel, огонь снаружи оставить
f = o[..., :3].astype(np.int32)
gray = (f.max(-1) - f.min(-1)) < 60                                  # хром серый, огонь оранжевый
o[gray & (rr < 160)] = 0                                             # хвосты молнии Opel за кольцом
base = Image.fromarray(o, "RGBA")
base.alpha_composite(emb)
base.save(dst, "WEBP", lossless=True)
if len(sys.argv) > 4:
    bg = Image.new("RGBA", (S, S), (0, 0, 0, 255))
    bg.alpha_composite(Image.open(sys.argv[4]).convert("RGBA")); bg.alpha_composite(base)
    bg.convert("RGB").save(sys.argv[3])
