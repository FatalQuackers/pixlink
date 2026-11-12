import numpy as np, wave
from itertools import accumulate
SR=48000; DUR=29.0; N=int(SR*DUR); B=60/128
rng=np.random.default_rng(3)
mus=np.zeros((N,2)); send=np.zeros((N,2)); duck=np.ones(N)
def midi(m): return 440*2**((m-69)/12)
def lp(x,fc):
    a=np.exp(-2*np.pi*fc/SR); return np.array(list(accumulate(x*(1-a),lambda p,v:p*a+v)))
def add(buf,t0,sig,pan=0.0,g=1.0):
    i=int(round(t0*SR))
    if i>=N or i<0: return
    sig=sig[:N-i]; l=np.cos((pan+1)*np.pi/4)*1.414; r=np.sin((pan+1)*np.pi/4)*1.414
    buf[i:i+len(sig),0]+=sig*l*g; buf[i:i+len(sig),1]+=sig*r*g
def sat(f,n,det=0.008,v=7):
    t=np.arange(n)/SR; o=0
    for d in np.linspace(-det,det,v): o=o+(2*((t*f*(1+d)+rng.random())%1)-1)
    return o/v
def kick(g=1):
    n=int(0.4*SR); t=np.arange(n)/SR; f=45+140*np.exp(-t*35)
    s=np.sin(2*np.pi*np.cumsum(f)/SR)*np.exp(-t*7)
    c=rng.standard_normal(int(0.004*SR))*0.3; s[:len(c)]+=c
    return np.tanh(s*1.6)*g
def clap():
    n=int(0.25*SR); t=np.arange(n)/SR; x=rng.standard_normal(n); x=x-lp(x,900); x=lp(x,6000)
    e=np.exp(-t*22)
    for d in (0.008,0.016): k=int(d*SR); e[:k]=np.maximum(e[:k],0); 
    return x*e
def hat(open_=False):
    n=int((0.18 if open_ else 0.05)*SR); x=rng.standard_normal(n); x=x-lp(x,7000)
    return x*np.exp(-np.arange(n)/SR*(14 if open_ else 80))
def snare():
    n=int(0.2*SR); t=np.arange(n)/SR; x=rng.standard_normal(n); x=x-lp(x,1500)
    return (x*0.8+np.sin(2*np.pi*190*t)*0.6)*np.exp(-t*18)
def impact(g=1):
    n=int(2.5*SR); t=np.arange(n)/SR
    boom=np.sin(2*np.pi*np.cumsum(38+80*np.exp(-t*12))/SR)*np.exp(-t*2.2)
    x=rng.standard_normal(n); noise=lp(x,3000)*np.exp(-t*6)
    return np.tanh((boom*1.2+noise*0.6)*1.5)*g
def riser(dur):
    n=int(dur*SR); t=np.arange(n)/SR; x=rng.standard_normal(n)
    lo=lp(x,500); hi=x-lp(x,1500); k=(t/dur)**2
    tone=np.sin(2*np.pi*np.cumsum(200+1800*(t/dur)**2)/SR)*0.25
    return (lo*(1-k)+hi*k*0.6+tone)*(t/dur)**1.5
def stab(ch,dur=0.4,cut=3500):
    n=int(dur*SR); s=sum(sat(midi(m),n) for m in ch)/len(ch); s=lp(s,cut)
    t=np.arange(n)/SR; return s*np.exp(-t*5)*np.minimum(1,t/0.003)
def sub(f,dur):
    n=int(dur*SR); t=np.arange(n)/SR; return np.tanh(np.sin(2*np.pi*f*t)*1.5)*np.minimum(1,t/0.005)*np.minimum(1,(dur-t)/0.01)
chords=[[53,56,60,65,68],[49,53,56,61,65],[56,60,63,68,72],[51,55,58,63,67]]  # Fm Db Ab Eb
roots=[29,25,32,27]
def bt(b): return b*B
def duckat(t0,depth=0.6,ln=0.3):
    i=int(t0*SR); m=min(int(ln*SR),N-i)
    if m<=0: return
    duck[i:i+m]=np.minimum(duck[i:i+m],1-depth*np.exp(-np.arange(m)/SR*9))

# ---- intro beats 0-8: filtered pulse + ticking hats + word impacts + roll/riser ----
for b in range(0,8):
    ch=chords[0]; s=stab(ch,0.35,900); add(mus,bt(b),s,0,0.18); add(mus,bt(b+0.5),s,0,0.10)
for i in range(32): add(mus,bt(i*0.25),hat(),0.3,0.06+0.06*i/32)
for b in (0,2,4): add(mus,bt(b),impact(0.9),0,0.45); add(send,bt(b),impact(),0,0.12)
for i in range(16): add(mus,bt(6+i/8),snare(),0,0.10+0.25*i/16)
add(mus,bt(4),riser(bt(4)),0,0.35)
# ---- main beats 8-36, rapid 36-42 (kick each beat), build 42-44, montage 44-52 ----
def groove(b0,b1,full=True):
    for b in range(b0,b1):
        add(mus,bt(b),kick(),0,0.75); duckat(bt(b))
        if full:
            if b%2==1: add(mus,bt(b),clap(),0,0.40); add(send,bt(b),clap(),0,0.12)
            add(mus,bt(b+0.5),hat(True),0.25,0.12)
            add(mus,bt(b+0.25),hat(),-0.3,0.05); add(mus,bt(b+0.75),hat(),-0.3,0.05)
        bar=(b//4)%4; f=midi(roots[bar])
        add(mus,bt(b+0.5),sub(f,B*0.45),0,0.32)
        if full:
            ch=chords[bar]
            if b%4==0: s=stab([m+12 for m in ch],1.4,5000); add(mus,bt(b),s,0,0.20); add(send,bt(b),s,0,0.10)
            for q in (0.5,1.5):
                if b%2==0: s=stab([m+12 for m in ch[1:4]],0.18,4000); add(mus,bt(b+q*0.5+0.25),s,0.4*(1 if q<1 else -1),0.10)
groove(8,36)
groove(36,42,full=False)
for b in range(36,42): add(mus,bt(b),impact(0.7),0,0.40); s=stab([m+12 for m in chords[b%4]],0.45,6000); add(mus,bt(b),s,0,0.18); add(send,bt(b),s,0,0.12)
for i in range(32): add(mus,bt(42+i/16),snare(),0,0.10+0.32*i/32)
add(mus,bt(40),riser(bt(4)),0,0.45)
for b in range(0,36,4):
    if b>=8: add(mus,bt(b),impact(0.6),0,0.22)  # each feature card
groove(44,52)
add(mus,bt(44),impact(),0,0.55)
for b in range(44,52): add(mus,bt(b)-0.12,riser(0.12),0,0.10)  # whip per cut
# ---- end beat 52: huge hit + chord tail ----
add(mus,bt(52),impact(1.0),0,0.8); add(send,bt(52),impact(),0,0.2)
add(mus,bt(52),kick(1.2),0,0.8)
s=sum(sat(midi(m),int(4.0*SR),0.01) for m in [53,60,65,68,72,77])/6; s=lp(s,3000)*np.exp(-np.arange(int(4.0*SR))/SR*0.9)
add(mus,bt(52),s,0,0.30); add(send,bt(52),s,0,0.15)
add(mus,bt(52)+0.95,impact(0.5),0,0.25)  # "Download now"
# whoosh into each feature shot (beat after the card)
for i in range(7):
    b=8+i*4+1; add(mus,bt(b)-0.15,riser(0.15),0,0.18)
mus*=duck[:,None]
# reverb
ir_n=int(1.8*SR); tt=np.arange(ir_n)/SR
ir=np.stack([rng.standard_normal(ir_n)*np.exp(-tt*3.5) for _ in range(2)],1); ir/=np.sqrt((ir**2).sum(0))
def conv(a,b):
    L=len(a)+len(b)-1; n=1<<(L-1).bit_length(); return np.fft.irfft(np.fft.rfft(a,n)*np.fft.rfft(b,n),n)[:len(a)]
mix=mus+np.stack([conv(send[:,c],ir[:,c]) for c in range(2)],1)*0.8
fade=np.ones(N); fs=int(27.0*SR); fade[fs:]=np.linspace(1,0,N-fs)**2; mix*=fade[:,None]
mix=np.tanh(mix*1.6)/np.tanh(1.6); mix/=np.max(np.abs(mix))/0.9
with wave.open('hype.wav','wb') as w: w.setnchannels(2);w.setsampwidth(2);w.setframerate(SR);w.writeframes((mix*32767).astype('<i2').tobytes())
print('ok')
