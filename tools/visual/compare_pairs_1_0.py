import sys, json
from PIL import Image, ImageDraw
import numpy as np
from scipy.ndimage import gaussian_filter
# usage: python3 compare_pairs_1_0.py <04_REFERENCIAS dir> <captures dir> <output.jpg>
# Puts each original reference next to the 1.0 capture; SSIM is only a rough guide.
R=sys.argv[1].rstrip('/')+'/'; B=sys.argv[2].rstrip('/')+'/'; A=R
pairs=[
 ("Inicio","@INICIO_ACTUAL_0_9_0.png","90_home_font1.0.png"),
 ("Insertar · Crop (paso 1)","@INSERTAR_ORIGINAL_06.png","51_insert_crop.png"),
 ("Insertar · Crop recortado","@INSERTAR_ORIGINAL_09.png","52_insert_cropped.png"),
 ("Insertar · Place Picture","@INSERTAR_ORIGINAL_04.png","53_place_picture.png"),
 ("Insertar · menú ⋮","@INSERTAR_ORIGINAL_07.png","54_place_menu.png"),
 ("Insertar · Perspective Tool","@INSERTAR_ORIGINAL_08.png","57_perspective_diamonds.png"),
 ("Insertar · Eraser","@INSERTAR_ORIGINAL_02.png","59_cutout_eraser_panel.png"),
 ("Insertar · Restore Brush","@INSERTAR_ORIGINAL_01.png","61_restore_panel.png"),
 ("Title Style","@FUENTES_ORIGINAL_TITLE_STYLE.jpg","80_title_style_panel.png"),
 ("More Fonts","@FUENTES_ORIGINAL_MORE_FONTS.jpg","81_more_fonts.png"),
]
W,H=330,705
def ssim(a,b):
    a=a.astype(float);b=b.astype(float);C1=(0.01*255)**2;C2=(0.03*255)**2
    m1=gaussian_filter(a,1.5);m2=gaussian_filter(b,1.5)
    s11=gaussian_filter(a*a,1.5)-m1*m1;s22=gaussian_filter(b*b,1.5)-m2*m2;s12=gaussian_filter(a*b,1.5)-m1*m2
    return (((2*m1*m2+C1)*(2*s12+C2))/((m1**2+m2**2+C1)*(s11+s22+C2))).mean()
res=[];tiles=[]
for name,ref,app in pairs:
    r=Image.open((R+ref[1:]) if ref.startswith('@') else (A+ref)).convert('RGB'); a=Image.open(B+app).convert('RGB')
    # drop the system navigation strips (different phones) before comparing
    r=r.crop((0,0,r.width,int(r.height*0.955))); a=a.crop((0,0,a.width,int(a.height*0.97)))
    r=r.resize((W,H),Image.LANCZOS); a=a.resize((W,H),Image.LANCZOS)
    s=ssim(np.asarray(r.convert('L')),np.asarray(a.convert('L')))
    res.append((name,round(float(s),3)))
    t=Image.new('RGB',(W*2+10,H+26),(30,30,34)); t.paste(r,(0,26)); t.paste(a,(W+10,26))
    ImageDraw.Draw(t).text((6,6),(f"{name}  ·  0.9.0 | 1.0  ·  SSIM {s:.2f}" if name.startswith("Inicio") else f"{name}  ·  PicSay Pro | MAC Editor 1.0  ·  SSIM {s:.2f}"),fill=(255,255,255)); tiles.append(t)
cols=3; tw=tiles[0].width; th=tiles[0].height
sheet=Image.new('RGB',(cols*(tw+12),((len(tiles)+cols-1)//cols)*(th+12)),(18,18,20))
for i,t in enumerate(tiles): sheet.paste(t,((i%cols)*(tw+12),(i//cols)*(th+12)))
sheet.save(sys.argv[3],quality=88)
json.dump(res,open(sys.argv[3]+'.json','w'),ensure_ascii=False,indent=1)
for n,v in res: print(f"{n:24s} {v}")
