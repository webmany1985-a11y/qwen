# Фон Buick (15.11.2026): фото пользователя (android-team tasks/2026-11-15-jarvis-auto-zhena-buick/vhodyashchie) на обоях Opel.
# Маски — rembg isnet-general-use (buick-foto-mask.png и т. п.), затем этот сборщик. Наклейки на капоте затёрты заранее (cv2.inpaint).
import numpy as np, cv2
from PIL import Image, ImageFilter
def bbox(m,t=200):
    ys,xs=np.where(m>t); return xs.min(),ys.min(),xs.max(),ys.max()
b=np.array(Image.open('buick-foto.webp').convert('RGB'))
bm=np.array(Image.open('buick-foto-mask.png'))
bm[:120]=0                      # логотип салона сверху
bm[bm<40]=0
# отражение на полу — ниже колёс (низ колёс ~y=368 в исходнике): отрезать
bx0,by0,bx1,by1=bbox(bm); print('buick',bx0,by0,bx1,by1)
car=b[by0:by1+1,bx0:bx1+1]; cm=bm[by0:by1+1,bx0:bx1+1]
for name,out in [('jarvis_wallpaper','wide'),('jarvis_wallpaper_port','port')]:
    w=np.array(Image.open(name+'.webp').convert('RGB')); om=np.array(Image.open(name+'-mask.png'))
    ox0,oy0,ox1,oy1=bbox(om,230); print(name,'opel',ox0,oy0,ox1,oy1)
    H,W=om.shape
    # убрать Opel: заливка по маске на уменьшенной копии
    k=4; mk=(om>60).astype('uint8')*255; mk=cv2.dilate(mk,np.ones((25,25),'uint8'))
    small=cv2.resize(w,(W//k,H//k),interpolation=cv2.INTER_AREA); ms=cv2.resize(mk,(W//k,H//k))
    f=cv2.inpaint(small,ms,15,cv2.INPAINT_TELEA); f=cv2.GaussianBlur(f,(9,9),0)
    f=cv2.resize(f,(W,H),interpolation=cv2.INTER_CUBIC)
    a=cv2.GaussianBlur(mk,(41,41),0)[...,None]/255.
    bg=(w*(1-a)+f*a)
    # Buick: ширина как у Opel, низ колёс — по низу Opel
    tw=int((ox1-ox0)*1.0); s=tw/car.shape[1]; th=int(car.shape[0]*s)
    C=np.array(Image.fromarray(car).resize((tw,th),Image.LANCZOS).filter(ImageFilter.UnsharpMask(2,80,2))).astype(float)
    M=np.array(Image.fromarray(cm).resize((tw,th),Image.LANCZOS)).astype(float)/255.
    C=C*0.62; C[...,2]*=1.06                          # притемнить под ночную сцену
    x=ox0+((ox1-ox0)-tw)//2; y=oy1-th
    # тень под машиной
    sh=np.zeros((H,W),'float32'); cv2.ellipse(sh,(x+tw//2,oy1-8),(int(tw*0.5),int(th*0.07)),0,0,360,1,-1)
    sh=cv2.GaussianBlur(sh,(0,0),25)[...,None]; bg=bg*(1-0.75*sh)
    reg=bg[y:y+th,x:x+tw]; M3=M[...,None]
    bg[y:y+th,x:x+tw]=reg*(1-M3)+C*M3
    o=np.clip(bg,0,255).astype('uint8')
    Image.fromarray(o).save(f'buick_{out}.webp',quality=88); Image.fromarray(o).save(f'buick_{out}.png')
    print(out, o.shape)
