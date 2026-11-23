# Regenerate the README GIFs on Windows with Pillow and Microsoft YaHei.
from pathlib import Path
from functools import lru_cache
from PIL import Image, ImageDraw, ImageFont, ImageFilter

ROOT = Path(__file__).resolve().parent
MEDIA = ROOT / 'docs' / 'media'
MEDIA.mkdir(parents=True, exist_ok=True)
W, H, S = 492, 668, 2
SPECS = [
    ('banner-reply.gif', '横幅下拉，即时回复', '手指到达门槛，回复框立即展开'),
    ('shade-reply.gif', '通知栏长按回复', '长按对应通知，在卡片原位回复'),
    ('swipe-to-dismiss.gif', '上滑收起，再滑关闭', '第一次收起内容，第二次带走整张卡片'),
]
@lru_cache
def font(size, bold=False):
    return ImageFont.truetype('C:/Windows/Fonts/' + ('msyhbd.ttc' if bold else 'msyh.ttc'), size*S)
def scale(coords):
    return tuple(int(round(value*S)) for value in coords)
def rect(draw, box, radius, color, outline=None):
    draw.rounded_rectangle(scale(box), radius*S, fill=color, outline=outline, width=S)
def circle(draw, x, y, r, color, outline=None):
    draw.ellipse(scale((x-r,y-r,x+r,y+r)), fill=color, outline=outline, width=S)
def text(draw, xy, label, size, color='#14233A', bold=False, anchor=None):
    draw.text(scale(xy), label, font=font(size,bold), fill=color, anchor=anchor)
def line(draw, coords, color, width=1):
    draw.line(scale(coords), fill=color, width=width*S)
def progress(t, a, b):
    return max(0, min(1, (t-a)/(b-a)))
def smooth(t, a, b):
    x = progress(t,a,b)
    return x*x*(3-2*x)
def mix(a,b,t):
    return a+(b-a)*t

SCREEN=(59,153,433,591)
@lru_cache
def wallpaper():
    img=Image.new('RGB',(W*S,H*S),'#09162B')
    draw=ImageDraw.Draw(img)
    for y in range(H*S):
        f=y/(H*S)
        draw.line((0,y,W*S,y),fill=(int(11+14*f),int(23+16*f),int(43+21*f)))
    rect(draw,(38,133,454,610),34,'#12243B', '#304767')
    rect(draw,SCREEN,25,'#1D3B58')
    mask=Image.new('L',img.size)
    rect(ImageDraw.Draw(mask),SCREEN,25,255)
    art=Image.new('RGB',img.size,'#163958')
    a=ImageDraw.Draw(art)
    a.ellipse(scale((130,223,560,779)),fill='#2A677D')
    a.ellipse(scale((-160,336,385,785)),fill='#328080')
    a.ellipse(scale((154,388,571,760)),fill='#4C9B97')
    a.ellipse(scale((276,427,615,810)),fill='#81AAA3')
    img.paste(art.filter(ImageFilter.GaussianBlur(12*S)),mask=mask)
    return img

def textentry(draw,x,y,w,message):
    rect(draw,(x,y,x+w,y+39),13,'#E6F0F4')
    text(draw,(x+12,y+8),message,15,'#284457')
    rect(draw,(x+w-34,y+5,x+w-5,y+34),9,'#107864')
    line(draw,(x+w-27,y+20,x+w-12,y+14,x+w-18,y+27,x+w-20,y+21,x+w-27,y+20),'white',1)
def notification(canvas, y, height, detail=False, entry=False, composing='', sent=False):
    x,w = 75,342
    layer=Image.new('RGBA',canvas.size)
    d=ImageDraw.Draw(layer)
    rect(d,(x+2,y+3,x+w+2,y+height+5),18,'#11233D')
    rect(d,(x,y,x+w,y+height),18,'#F8FCFF')
    rect(d,(x+15,y+14,x+49,y+48),11,'#107864')
    rect(d,(x+23,y+23,x+40,y+35),5,'#F7FFFB')
    d.polygon([scale((x+25,y+33)),scale((x+25,y+38)),scale((x+30,y+34))],fill='#F7FFFB')
    text(d,(x+59,y+13),'阿柚',16,'#15283A',True)
    text(d,(x+59,y+36),'周末还去看展吗？',15,'#4D6072')
    text(d,(x+w-16,y+15),'刚刚',12,'#7A8FA1',anchor='ra')
    if detail:
        text(d,(x+15,y+69),'周六下午有一场，我把地点发你。',14,'#445D6E')
    if entry:
        textentry(d,x+14,y+100,w-28,composing)
    if sent:
        rect(d,(x+111,y+98,x+w-15,y+136),12,'#D8F6EA')
        text(d,(x+w-25,y+106),'我  好呀，周六见！',14,'#136851',anchor='ra')
    mask=Image.new('L',canvas.size)
    rect(ImageDraw.Draw(mask),(x,y,x+w,y+height),18,255)
    canvas.paste(layer,mask=mask)

def touch(draw, x,y, strength=1.0, trail=0):
    if trail:
        line(draw,(x,y+trail,x,y),'#42BFA9',2)
    circle(draw,x,y,13+3*strength,'#0D796D','#E2FFFA')
    circle(draw,x,y,5,'#DBFFF6')
def shade(draw):
    rect(draw,(59,153,433,591),25,'#20364E')
    text(draw,(80,185),'通知',22,'#F2F8FF',True)
    text(draw,(404,190),'周六',14,'#B8D4E6',anchor='ra')
    for i in range(4):
        x=80+i*86
        rect(draw,(x,225,x+75,271),15,'#365671' if i>=2 else '#23776B')
        text(draw,(x+37,248),('网络','声音','蓝牙','亮度')[i],14,'#F3FBFF',anchor='mm')
def keyboard(draw,y):
    rect(draw,(60,y,432,590),20,'#C2D5E3')
    for iy,row in enumerate(('QWERTYUIOP','ASDFGHJKL','ZXCVBNM')):
        x0=72+(10-len(row))*17
        for ix,letter in enumerate(row):
            x=x0+ix*34
            rect(draw,(x,y+10+iy*35,x+29,y+38+iy*35),7,'#E9F4FA')
            text(draw,(x+14,y+24+iy*35),letter,12,'#36536A',anchor='mm')
    rect(draw,(139,y+119,351,y+143),8,'#E9F4FA')
    text(draw,(245,y+130),'空格',11,'#36536A',anchor='mm')

def frame(kind,t):
    img=wallpaper().copy()
    d=ImageDraw.Draw(img)
    title,subtitle=SPECS[kind][1:]
    text(d,(37,24),'ShadeReply',18,'#84E5CE',True)
    text(d,(455,28),'手势示意',13,'#A6BED2',anchor='ra')
    text(d,(36,57),title,34,'#F4F8FF',True)
    text(d,(38,109),subtitle,15,'#B9D2E1')
    d=ImageDraw.Draw(img)
    text(d,(79,166),'10:42',12,'#EDF8FF',True)
    text(d,(412,166),'5G  ▪',12,'#EDF8FF',anchor='ra')
    phase=''
    if kind==0:
        expand=smooth(t,.18,.39)
        y=191+mix(0,23,smooth(t,.15,.34))-23*smooth(t,.34,.46)
        sent=t>=.81
        if .45 <= t < .81: keyboard(d,440)
        message='好呀，周六见！'
        typed=message[:round(len(message)*progress(t,.51,.73))]
        notification(img,y,round(mix(69,153,expand)),expand>.10,expand>.35 and not sent,typed or '输入回复内容',sent)
        if .14<t<.44:
            touch(ImageDraw.Draw(img),259,222+73*smooth(t,.14,.38),trail=-23*expand)
        elif .76<t<.82:
            touch(ImageDraw.Draw(img),388,y+119,.5)
        phase='下拉横幅' if t<.29 else ('到达门槛，打开回复框' if t<.81 else '回复显示在通知里')
    elif kind==1:
        shade(d)
        expand=smooth(t,.33,.49)
        if t>=.50: keyboard(d,440)
        notification(img,293,round(mix(70,145,expand)),expand>.10,expand>.40,'输入回复内容')
        if .13<t<.43:
            hold=progress(t,.13,.38)
            d=ImageDraw.Draw(img)
            if hold>0.04:
                d.arc(scale((282,305,327,350)),-90,-90+360*hold,fill='#107864',width=3*S)
            touch(d,304,328,hold)
        phase='在通知栏找到消息' if t<.15 else ('长按通知卡片' if t<.40 else '在原位打开回复框')
    else:
        collapse=smooth(t,.13,.40)
        dismiss=smooth(t,.58,.76)
        exit=smooth(t,.76,.85)
        scene=wallpaper().crop(scale(SCREEN))
        blur=scene.filter(ImageFilter.GaussianBlur((8*(1-collapse))*S))
        img.paste(blur,scale(SCREEN)[:2])
        d=ImageDraw.Draw(img)
        text(d,(79,166),'10:42',12,'#EDF8FF',True)
        text(d,(412,166),'5G  ▪',12,'#EDF8FF',anchor='ra')
        if t<.85:
            layer=img.copy()
            notification(layer,204-45*dismiss-160*exit,round(mix(153,69,collapse)),True,True,'输入回复内容')
            blur_screen=layer.crop(scale((59,153,433,591)))
            img.paste(blur_screen,scale(SCREEN)[:2])
        if .13<t<.41:
            touch(ImageDraw.Draw(img),270,340-100*collapse,trail=22*collapse)
        if .56<t<.77:
            touch(ImageDraw.Draw(img),271,244-62*dismiss,trail=22*dismiss)
        phase='第一次上滑 · 收起内容和模糊' if t<.42 else ('松手后 · 横幅保持紧凑' if t<.56 else ('第二次上滑 · 卡片跟手上移' if t<.77 else '越过门槛后松手关闭'))
    d=ImageDraw.Draw(img)
    rect(d,(37,625,455,653),12,'#1B3A4B')
    text(d,(246,639),phase,13,'#C9F5EB',True,anchor='mm')
    return img.resize((W,H),Image.Resampling.LANCZOS)

for i,(name,_,_) in enumerate(SPECS):
    images=[frame(i,j/60) for j in range(61)]
    samples=[images[j] for j in (0,14,26,39,50,60)]
    palette=Image.new('RGB',(W*3,H*2))
    for j,image in enumerate(samples):
        palette.paste(image,((j%3)*W,(j//3)*H))
    palette=palette.quantize(colors=112)
    frames=[image.quantize(palette=palette,dither=Image.Dither.NONE) for image in images]
    durations=[55]*len(frames)
    durations[0]=310
    durations[-1]=1000
    path=MEDIA/name
    frames[0].save(path,save_all=True,append_images=frames[1:],duration=durations,loop=0,optimize=True,disposal=1)
    with Image.open(path) as result:
        result.seek(result.n_frames-1)
        print(name,result.size,result.n_frames,path.stat().st_size)
