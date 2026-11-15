# Фон JARVIS Auto (Buick) — строится вокруг фото пользователя, без вклейки в чужую сцену.
import numpy as np, cv2, sys
from PIL import Image
ROT=10
src=np.array(Image.open('dvor.jpg').convert('RGB')).astype(np.float32)
msk=np.array(Image.open('dvor-mask.png')).astype(np.float32)/255.
H0,W0=msk.shape
# номер — размыть (в обоях не нужен)
pl=np.zeros((H0,W0),np.float32)
quad=np.array([[3872,1218],[3922,1238],[3922,1345],[3724,1756],[3676,1626]])
cv2.fillPoly(pl,[quad],1)
pc=np.median(src[pl>0],axis=0)
pl=cv2.GaussianBlur(pl,(0,0),3)[...,None]
src=src*(1-pl)+pc*pl   # чистая табличка без номера
def rot(a,border=0):
    M=cv2.getRotationMatrix2D((W0/2,H0/2),ROT,1.0)
    return cv2.warpAffine(a,M,(W0,H0),flags=cv2.INTER_CUBIC,borderMode=cv2.BORDER_CONSTANT,borderValue=border)
img=rot(src); m=np.clip(rot(msk),0,1); valid=rot(np.ones((H0,W0),np.float32))
ys,xs=np.where(m>0.5); bx0,by0,bx1,by1=xs.min(),ys.min(),xs.max(),ys.max()
ground_y=by1
def grade_car(c,k=0.8,g=1.25):
    c=255*((np.clip(c,0,255)/255.)**g)*k
    lab=cv2.cvtColor(np.clip(c,0,255).astype('uint8'),cv2.COLOR_RGB2LAB).astype(np.float32)
    lab[...,1]=128+(lab[...,1]-128)*0.55; lab[...,2]=128+(lab[...,2]-128)*0.55-3
    return cv2.cvtColor(np.clip(lab,0,255).astype('uint8'),cv2.COLOR_LAB2RGB).astype(np.float32)
H,W=img.shape[:2]
yy,xx=np.mgrid[0:H,0:W].astype(np.float32)
cx=(bx0+bx1)/2; cy=(by0+by1)/2
def vign(p=1.0):
    d=np.sqrt(((xx-cx)/(W*0.62))**2+((yy-cy*1.05)/(H*0.62))**2)
    return np.clip(1-d**2*p,0,1)
def gold_glow(base,strength=1.0):
    # тёплый отсвет на земле под порогом и колёсами — только НИЖЕ машины, без ореола по контуру
    ml=(m>0.5)
    colb=np.full(W,-1)
    any_=ml.any(0); colb[any_]=H-1-np.argmax(ml[::-1],0)[any_]
    below=np.zeros((H,W),np.float32); line=np.zeros((H,W),np.float32)
    for x in np.where(any_)[0]:
        below[colb[x]-int(0.01*H):,x]=1; line[colb[x]:colb[x]+8,x]=1
    g=cv2.GaussianBlur(line,(0,0),0.02*W)*6+cv2.GaussianBlur(line,(0,0),0.006*W)*2
    g=np.clip(g,0,1)*cv2.GaussianBlur(below,(0,0),0.004*W)*(1-m)*strength
    return base+g[...,None]*np.array([255,160,55],np.float32)*0.6
def comp(bg,car):
    mm=cv2.GaussianBlur(m,(0,0),2)[...,None]
    return bg*(1-mm)+car*mm
_vb=np.pad((valid>0.5).astype(np.uint8),1)
_dist=cv2.distanceTransform(_vb,cv2.DIST_L2,5)[1:-1,1:-1]
EDGE=np.clip((_dist-0.02*W)/(0.12*W),0,1); EDGE=EDGE*EDGE*(3-2*EDGE)
def soft_alpha(k=1.0):
    # сцена фото растворяется в темноту овалом вокруг машины — без косых краёв повёрнутого кадра
    ea=(bx1-bx0)*0.62*k; eb=(by1-by0)*0.85*k
    d=np.sqrt(((xx-cx)/ea)**2+((yy-cy)/eb)**2)
    e=np.clip((1.2-d)/0.6,0,1)**1.3
    v=EDGE
    return np.maximum(np.minimum(e,v), cv2.GaussianBlur(m,(0,0),1.5))
def v_evening():
    bg=grade_car(cv2.GaussianBlur(img,(0,0),4),0.42,1.4)*np.array([0.88,0.97,1.12])
    return comp(bg,grade_car(img,0.88,1.15)), soft_alpha(1.0)
def v_dark():
    near=cv2.GaussianBlur((m>0.3).astype(np.float32),(0,0),0.04*W)
    ga=np.clip(near*2.0,0,1)*np.clip((yy-cy)/(0.15*H)+0.5,0,1)*0.9*EDGE
    bg=grade_car(img,0.3,1.4)
    al=np.maximum(ga,cv2.GaussianBlur(m,(0,0),1.5))
    return comp(bg,grade_car(img,0.88,1.15)), al
def v_day():
    return grade_car(img,0.72,1.12), soft_alpha(1.15)
def frame(layer,w,h,wide):
    out,al=layer
    if wide:
        s=0.65*h/(by1-by0)
        if (bx1-bx0)*s>0.685*w: s=0.685*w/(bx1-bx0)
        ccx=0.5125*w; ccy=0.575*h
    else:
        s=0.95*w/(bx1-bx0); ccx=0.5*w; ccy=h-(by1-by0)*s*0.5-0.12*h
    M=np.float32([[s,0,ccx-cx*s],[0,s,ccy-cy*s]])
    o=cv2.warpAffine(out,M,(w,h),flags=cv2.INTER_AREA,borderValue=0)
    a=cv2.warpAffine(al,M,(w,h),flags=cv2.INTER_AREA,borderValue=0)[...,None]
    Y,X=np.mgrid[0:h,0:w].astype(np.float32)
    spot=np.exp(-(((X-ccx)/(w*0.55))**2+((Y-ccy*0.8)/(h*0.75))**2))
    bg=spot[...,None]*np.array([30,32,42],np.float32)
    o=bg*(1-a)+o*a
    rng=np.random.default_rng(1); o=o+rng.normal(0,2.0,o.shape)
    return np.clip(o,0,255).astype('uint8')
vs={'1':v_evening(),'2':v_dark(),'3':v_day()}
for k,o in vs.items():
    Image.fromarray(frame(o,1920,991,True)).save(f'v{k}.png')
    Image.fromarray(frame(o,1080,1920,False)).save(f'v{k}p.png')
