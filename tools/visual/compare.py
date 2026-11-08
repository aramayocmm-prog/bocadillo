import sys, json
from PIL import Image, ImageDraw, ImageFont
import numpy as np
D=sys.argv[1]
ref=Image.open(f'{D}/ref.png').convert('RGB')
cols=[(9,257),(264,514),(519,767),(774,1022),(1029,1277),(1284,1531)]
rows=[(6,379),(430,717),(766,987)]
names=["01_home","02_editor","03_adjust","04_exposure","05_effect","06_twirl","07_sticker","08_balloon_shapes","09_balloon_edit","10_title_editor","11_text_styles","12_paint","13_export","14_crop","15_rotate","16_resize","17_color_filters","18_more"]
def ssim(a,b):
    from scipy.ndimage import gaussian_filter
    a=a.astype(float); b=b.astype(float)
    C1=(0.01*255)**2; C2=(0.03*255)**2
    mu1=gaussian_filter(a,1.5); mu2=gaussian_filter(b,1.5)
    s11=gaussian_filter(a*a,1.5)-mu1*mu1; s22=gaussian_filter(b*b,1.5)-mu2*mu2; s12=gaussian_filter(a*b,1.5)-mu1*mu2
    m=((2*mu1*mu2+C1)*(2*s12+C2))/((mu1**2+mu2**2+C1)*(s11+s22+C2))
    return m
res={}
tiles=[]
for k,n in enumerate(names):
    r,c=divmod(k,6)
    box=(cols[c][0],rows[r][0],cols[c][1],rows[r][1])
    rp=ref.crop(box); w,h=rp.size
    app=Image.open(f'{D}/app/{n}.png').convert('RGB')
    if r==0:
        # first row of the reference shows whole screens: whole capture scaled to the panel
        ap=app.resize((w,h),Image.LANCZOS)
    else:
        # rows 2-3 show the top of the screen only: same-aspect top crop
        ch=int(app.width*h/w)
        ap=app.crop((0,0,app.width,min(ch,app.height))).resize((w,h),Image.LANCZOS)
    g1=np.asarray(rp.convert('L')); g2=np.asarray(ap.convert('L'))
    m=ssim(g1,g2)
    # regions: top bar (status + title bar ~ first 12%), content
    tb=int(h*0.13)
    res[n]={"whole":float(m.mean()),"top_bar":float(m[:tb].mean()),"rest":float(m[tb:].mean())}
    t=Image.new('RGB',(w*2+12,h+22),(40,40,44)); t.paste(rp,(0,22)); t.paste(ap,(w+12,22))
    d=ImageDraw.Draw(t); d.text((4,4),f"{n}  ref | app   SSIM {m.mean():.2f}",fill=(255,255,255))
    tiles.append(t)
json.dump(res,open(f'{D}/ssim.json','w'),indent=1)
for n,v in res.items(): print(f"{n:20s} whole {v['whole']:.3f}  bar {v['top_bar']:.3f}  rest {v['rest']:.3f}")
# sheet 3 per row
per=3
wmax=max(t.width for t in tiles); 
rowsimg=[]
for i in range(0,len(tiles),per):
    grp=tiles[i:i+per]; hh=max(t.height for t in grp)
    im=Image.new('RGB',(wmax*per+10*(per-1),hh),(25,25,28))
    for j,t in enumerate(grp): im.paste(t,(j*(wmax+10),0))
    rowsimg.append(im)
H=sum(r.height+10 for r in rowsimg)
sheet=Image.new('RGB',(rowsimg[0].width,H),(25,25,28)); y=0
for r in rowsimg: sheet.paste(r,(0,y)); y+=r.height+10
sheet.save(f'{D}/side_by_side.png')
