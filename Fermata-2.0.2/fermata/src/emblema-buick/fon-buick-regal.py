# Фон JARVIS Auto жены (16.11.2026): из сгенерированной картинки Buick Regal 2021 (1376x768)
import numpy as np, sys
from PIL import Image, ImageFilter
src = Image.open(sys.argv[1]).convert('RGB'); out = sys.argv[2]
W0, H0 = src.size
# Машина — 1920x991: по ширине, срез неба сверху больше, чем снизу (машина ниже центра)
s = src.resize((1920, round(H0 * 1920 / W0)), Image.LANCZOS)
extra = s.height - 991; top = int(extra * 0.65)
s.crop((0, top, 1920, top + 991)).save(out + '/jarvis_wallpaper.webp', 'WEBP', quality=82)
# Телефон — 1080x1920: середина кадра с машиной, внизу; сверху — небо, растянутое и размытое, переход плавный
c = src.crop((240, 0, 1200, H0)).resize((1080, round(H0 * 1080 / 960)), Image.LANCZOS)
sky = src.crop((240, 0, 1200, 220)).resize((1080, 1920), Image.LANCZOS).filter(ImageFilter.GaussianBlur(30))
sky = Image.fromarray((np.asarray(sky, np.float32) * 0.75).astype('uint8'))
canvas = sky.copy(); y0 = 1920 - c.height - 160
mask = np.ones((c.height, 1080), np.float32); f = 260
mask[:f] = np.linspace(0, 1, f)[:, None]
mask[-120:] *= np.linspace(1, 0, 120)[:, None]
canvas.paste(c, (0, y0), Image.fromarray((mask * 255).astype('uint8')))
canvas.save(out + '/jarvis_wallpaper_port.webp', 'WEBP', quality=82)
for n in ('jarvis_wallpaper', 'jarvis_wallpaper_port'):
    Image.open(out + '/' + n + '.webp').save(out + '/' + n + '.jpg')
