import sys, json
from PIL import Image, ImageDraw
import numpy as np
from scipy.ndimage import gaussian_filter
S='/tmp/claude-0/-home-claude/2e587704-a2ad-5954-a17a-49532ce3fb69/scratchpad/'
A=S+'atlas/'; B=S+sys.argv[1]+'/'
pairs=[
 ("Inicio","01_MENUS_PRINCIPALES/Inicio/IMG-20261110-WA0144.jpg","01_home.png"),
 ("Get Picture from","06_PINTURA_IMPORTACION_Y_OTROS/Importar_imagen/IMG-20261110-WA0141.jpg","01b_get_picture.png"),
 ("Import large picture","06_PINTURA_IMPORTACION_Y_OTROS/Importar_imagen/IMG-20261110-WA0130.jpg","41_import_large.png"),
 ("Adjust","01_MENUS_PRINCIPALES/Men_s_de_ajustes_y_efectos/IMG-20261110-WA0005.jpg","03_adjust.png"),
 ("Effect","01_MENUS_PRINCIPALES/Men_Effect/IMG-20261110-WA0093.jpg","05_effect.png"),
 ("Export / compartir","01_MENUS_PRINCIPALES/Compartir/IMG-20261110-WA0107.jpg","13_export.png"),
 ("Exposure / Contrast","04_ADJUST_Y_TRANSFORM/Contrast/IMG-20261110-WA0110.jpg","04_exposure.png"),
 ("Crop","04_ADJUST_Y_TRANSFORM/Crop/IMG-20261110-WA0117.jpg","14_crop.png"),
 ("Resize · Set Size","04_ADJUST_Y_TRANSFORM/Resize/IMG-20261110-WA0112.jpg","16_resize.png"),
 ("Paint","06_PINTURA_IMPORTACION_Y_OTROS/Paint/IMG-20261110-WA0140.jpg","12_paint.png"),
 ("Paint · Brush Settings","06_PINTURA_IMPORTACION_Y_OTROS/Paint/IMG-20261110-WA0136.jpg","12b_brush_settings.png"),
 ("Paint · Brush","06_PINTURA_IMPORTACION_Y_OTROS/Paint/IMG-20261110-WA0137.jpg","12c_brush.png"),
 ("Paint · Color","06_PINTURA_IMPORTACION_Y_OTROS/Paint/IMG-20261110-WA0138.jpg","12d_color.png"),
 ("New Title","02_STICKER_HERRAMIENTAS_ESPECIALES/Title/IMG-20261110-WA0013.jpg","10_new_title.png"),
 ("Font","02_STICKER_HERRAMIENTAS_ESPECIALES/Font/IMG-20261110-WA0097.jpg","10b_font_list.png"),
 ("Formas de globo","02_STICKER_HERRAMIENTAS_ESPECIALES/Word_Balloon/IMG-20261110-WA0017.jpg","08_balloon_shapes.png"),
 ("New Word Balloon","02_STICKER_HERRAMIENTAS_ESPECIALES/Word_Balloon/IMG-20261110-WA0011.jpg","09_new_word_balloon.png"),
 ("Set Shape Style","02_STICKER_HERRAMIENTAS_ESPECIALES/Set_Shape_Style/IMG-20261110-WA0096.jpg","42_set_shape_style.png"),
 ("Set Line Style","02_STICKER_HERRAMIENTAS_ESPECIALES/Set_Line_Style/IMG-20261110-WA0100.jpg","43_set_line_style.png"),
]
W,H=330,705
def ssim(a,b):
    a=a.astype(float);b=b.astype(float);C1=(0.01*255)**2;C2=(0.03*255)**2
    m1=gaussian_filter(a,1.5);m2=gaussian_filter(b,1.5)
    s11=gaussian_filter(a*a,1.5)-m1*m1;s22=gaussian_filter(b*b,1.5)-m2*m2;s12=gaussian_filter(a*b,1.5)-m1*m2
    return (((2*m1*m2+C1)*(2*s12+C2))/((m1**2+m2**2+C1)*(s11+s22+C2))).mean()
res=[];tiles=[]
for name,ref,app in pairs:
    r=Image.open(A+ref).convert('RGB'); a=Image.open(B+app).convert('RGB')
    # drop the system navigation strips (different phones) before comparing
    r=r.crop((0,0,r.width,int(r.height*0.955))); a=a.crop((0,0,a.width,int(a.height*0.97)))
    r=r.resize((W,H),Image.LANCZOS); a=a.resize((W,H),Image.LANCZOS)
    s=ssim(np.asarray(r.convert('L')),np.asarray(a.convert('L')))
    res.append((name,round(float(s),3)))
    t=Image.new('RGB',(W*2+10,H+26),(30,30,34)); t.paste(r,(0,26)); t.paste(a,(W+10,26))
    ImageDraw.Draw(t).text((6,6),f"{name}  ·  PicSay Pro | MAC Editor  ·  SSIM {s:.2f}",fill=(255,255,255)); tiles.append(t)
cols=3; tw=tiles[0].width; th=tiles[0].height
sheet=Image.new('RGB',(cols*(tw+12),((len(tiles)+cols-1)//cols)*(th+12)),(18,18,20))
for i,t in enumerate(tiles): sheet.paste(t,((i%cols)*(tw+12),(i//cols)*(th+12)))
sheet.save(S+sys.argv[2],quality=88)
json.dump(res,open(S+sys.argv[2]+'.json','w'),ensure_ascii=False,indent=1)
for n,v in res: print(f"{n:24s} {v}")
