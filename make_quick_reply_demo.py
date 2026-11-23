from pathlib import Path
from functools import lru_cache
from math import sin, pi
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent
OUT = ROOT / "quick-reply-demo.gif"
W, H, S = 860, 504, 2
MINT = "#94f3d1"
WHITE = "#f4f8fa"
SUBTLE = "#a4b8cd"
STAGES = [
    ("下拉回复", "在顶部横幅直接打开输入框", "松手，留在当前应用"),
    ("再次下拉", "卡片拉伸、下移，松手回弹", "回复框保持展开"),
    ("通知栏内回复", "在原位展开历史和回复框", "卡片没有拉伸和位移"),
]
TIMES = [43, 42, 45]

@lru_cache(maxsize=None)
def font(size, bold=False):
    return ImageFont.truetype(r"C:\Windows\Fonts" + ("\msyhbd.ttc" if bold else "\msyh.ttc"), size*S)

def box(b):
    return tuple(round(v*S) for v in b)

def rr(d, b, r, fill, outline=None, width=1):
    d.rounded_rectangle(box(b), radius=round(r*S), fill=fill, outline=outline, width=width*S)

def line(d, pts, color, width=1):
    d.line([(round(x*S), round(y*S)) for x,y in pts], fill=color, width=width*S)

def tx(d, x, y, s, size, color=WHITE, bold=False, anchor="lt"):
    d.text((round(x*S),round(y*S)), s, font=font(size,bold), fill=color, anchor=anchor)

def ellipse(d, b, color, outline=None, width=1):
    d.ellipse(box(b), fill=color, outline=outline, width=width*S)

def clamp(x):
    return min(1,max(0,x))

def step(t, a, b):
    k = clamp((t-a)/(b-a))
    return k*k*(3-2*k)

def lerp(a,b,p):
    return a+(b-a)*p

def mix(a,b,p):
    a,b = bytes.fromhex(a[1:]),bytes.fromhex(b[1:])
    return tuple(round(lerp(x,y,p)) for x,y in zip(a,b))

bg = Image.new("RGB", (W*S,H*S)); bd = ImageDraw.Draw(bg)
for y in range(H):
    bd.rectangle(box((0,y,W,y+1)), fill=mix("#112139","#071421",y/H))
rr(bd,(461,25,801,479),38,"#07121e","#35495c",1)
rr(bd,(468,32,794,472),31,"#152c43")
rr(bd,(603,39,659,50),5,"#091522")
line(bd,[(611,461),(652,461)],"#8293a4",3)
tx(bd,489,52,"09:41",12,WHITE,True)
tx(bd,775,51,"▂▅  ▰",11,WHITE,False,"rt")

def home(d):
    for i,(x,y) in enumerate([(489,184),(620,184),(489,264),(620,264),(489,345),(620,345)]):
        rr(d,(x,y,x+151,y+66),14,["#1e3d54","#234352","#203b52"][i%3])
        rr(d,(x+12,y+14,x+36,y+38),6,"#36566c")
        rr(d,(x+47,y+18,x+122,y+24),3,"#688299")
        rr(d,(x+47,y+33,x+98,y+37),2,"#4b657d")

def quicksettings(d, height):
    if height <= 0:
        return
    layer = Image.new("RGB",(326*S,413*S),"#1b2c42")
    ld=ImageDraw.Draw(layer)
    for y in range(413):
        ld.rectangle(box((0,y,326,y+1)),fill=mix("#263d54","#14263a",y/413))
    tx(ld,18,17,"通知中心",20,WHITE,True)
    tx(ld,306,26,"周日",12,SUBTLE,False,"rt")
    for i,label in enumerate(["网络","蓝牙","静音"]):
        x=18+i*100
        rr(ld,(x,56,x+89,93),12,("#498479" if i==0 else "#344b63"))
        tx(ld,x+45,75,label,12,WHITE,False,"mm")
    rr(ld,(18,109,306,118),5,"#3d5369")
    rr(ld,(18,109,139,118),5,"#8bdfc6")
    rr(ld,(18,302,306,359),12,"#253d54")
    tx(ld,32,316,"日历",12,WHITE,True)
    tx(ld,32,335,"明天有一项日程",12,SUBTLE)
    return layer.crop((0,0,326*S,round(height)*S))

def message(d,x,y,w,h,reveal,shade,stretch=0):
    rr(d,(x,y+4,x+w,y+h+5),18,"#102034")
    rr(d,(x,y,x+w,y+h),17,mix("#304c65","#2a425b",shade),
       mix("#709aaa","#5d9d95",shade))
    rr(d,(x+11,y+14,x+42,y+45),8,"#73e7c5")
    rr(d,(x+19,y+23,x+34,y+33),4,"#173e45")
    ellipse(d,(x+20,y+29,x+24,y+34),"#173e45")
    tx(d,x+51,y+13,"小林",13,WHITE,True)
    tx(d,x+w-12,y+15,"刚刚",10,SUBTLE,False,"rt")
    tx(d,x+51,y+35+stretch*2,"周末去看展吗？",14,WHITE)
    if reveal > 0:
        textshift=(1-reveal)*9
        c=mix("#304c65",SUBTLE,reveal)
        tx(d,x+51,y+60+stretch*5+textshift,"上一条：展览到周日结束",10,c)
        if reveal > .2:
            ry=y+82+stretch*6+textshift
            rr(d,(x+12,ry,x+w-12,ry+31),11,mix("#39536b","#172d42",reveal))
            tx(d,x+24,ry+9,"快速回复…",12,mix("#405970","#bed0dd",reveal))
            ellipse(d,(x+w-40,ry+5,x+w-19,ry+26),mix("#445d72",MINT,reveal))
            line(d,[(x+w-33,ry+17),(x+w-27,ry+11),(x+w-26,ry+17)],"#214953",2)

def frame(stage,t):
    im=bg.copy()
    d=ImageDraw.Draw(im)
    rr(d,(48,55,69,77),6,MINT)
    rr(d,(54,61,63,68),2,"#214b48")
    ellipse(d,(54,66,57,70),"#214b48")
    tx(d,81,57,"通知快捷回复",15,WHITE,True)
    tx(d,47,111,STAGES[stage][0],36,WHITE,True)
    tx(d,49,176,STAGES[stage][1],18,WHITE)
    tx(d,49,210,STAGES[stage][2],16,SUBTLE)
    for i, label in enumerate(("下拉","再拉","通知栏")):
        x=48+i*92
        active=i==stage
        rr(d,(x,391,x+79,425),12,"#163e43" if active else "#13263a",
           "#5ab99f" if active else "#30475e")
        tx(d,x+39,408,label,13,MINT if active else SUBTLE,active,"mm")
    tx(d,49,449,"COLOROS 17  /  手势动效示意",10,SUBTLE)

    home(d)
    y=79
    amount=0
    reve=0
    shade=0
    touch=0
    cy=115
    if stage==0:
        move=step(t,.13,.44)
        spring=1-step(t,.48,.66)
        amount=.76*step(t,.29,.48)*spring
        reve=step(t,.37,.61)
        touch=step(t,.08,.15)*(1-step(t,.49,.54))
        cy=114+45*move
    elif stage==1:
        move=step(t,.16,.57)
        amount=move*(1-step(t,.57,.88))
        reve=1
        touch=step(t,.1,.19)*(1-step(t,.57,.64))
        cy=114+57*move
    else:
        shade=1
        y=208
        reve=step(t,.38,.63)
        move=step(t,.14,.47)
        touch=step(t,.11,.17)*(1-step(t,.47,.53))
        cy=240+31*move

    if shade > 0:
        layer=quicksettings(d,413*shade)
        if layer:
            im.paste(layer,(468*S,58*S))
        d=ImageDraw.Draw(im)
        if shade > .66:
            line(d,[(611,461),(652,461)],"#8293a4",3)
    y += amount*22
    if reve == 0:
        h=65+amount*10
    else:
        h=max(65,124*reve+16*(1-reve))+amount*13
    x=486+amount*5
    w=290-amount*10
    # Draw reveal controls into the card as their height becomes available.
    visible_reveal = reve if h >= 115+amount*8 else 0
    message(d,x,y,w,h,visible_reveal,shade,amount)
    if touch>.05:
        c=mix("#508d90",MINT,touch)
        ellipse(d,(627-13,cy-13,627+13,cy+13),None,c,2)
        ellipse(d,(623,cy-4,631,cy+4),c)
    return im.resize((W,H),Image.Resampling.LANCZOS)

frames=[]
durations=[]
for stage,n in enumerate(TIMES):
    for i in range(n):
        frames.append(frame(stage,i/(n-1)))
        durations.append(55 if i!=n-1 else 460)
samples=Image.new("RGB",(W*3,H))
for stage, time in enumerate([.68,.51,.83]):
    samples.paste(frame(stage,time),(stage*W,0))
palette=samples.quantize(colors=256,method=Image.Quantize.MEDIANCUT)
frames=[f.quantize(palette=palette,dither=Image.Dither.NONE) for f in frames]
frames[0].save(OUT,save_all=True,append_images=frames[1:],duration=durations,loop=0,optimize=True)
samples.resize((W*3//2,H//2),Image.Resampling.LANCZOS).save(ROOT.parent/"quick-reply-review.png")
with Image.open(OUT) as gif:
    assert gif.size == (W,H) and gif.n_frames >= 70 and gif.info["loop"] == 0
    print(f"{gif.size[0]}x{gif.size[1]}, {gif.n_frames} frames, {OUT.stat().st_size:,} bytes")
