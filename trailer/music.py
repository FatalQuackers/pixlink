import numpy as np, wave
SR=48000; DUR=59.0; N=int(SR*DUR)
rng=np.random.default_rng(7)
mus=np.zeros((N,2)); sfx=np.zeros((N,2)); send=np.zeros((N,2))
def midi(m): return 440*2**((m-69)/12)
def env_adsr(n,a,d,s,r,sus_len):
    t=np.arange(n)/SR; e=np.zeros(n)
    e=np.where(t<a,t/a,np.where(t<a+d,1-(1-s)*(t-a)/d,s))
    rel=t>sus_len; e[rel]*=np.exp(-(t[rel]-sus_len)/r)
    return e
def add(buf,t0,sig,pan=0.0,gain=1.0):
    i=int(t0*SR); 
    if i>=N: return
    sig=sig[:N-i]; l=np.cos((pan+1)*np.pi/4); r=np.sin((pan+1)*np.pi/4)
    buf[i:i+len(sig),0]+=sig*l*gain*1.414; buf[i:i+len(sig),1]+=sig*r*gain*1.414
def lp(x,fc):  # one-pole lowpass
    a=np.exp(-2*np.pi*fc/SR); y=np.empty_like(x); acc=0.0
    # vectorised via scipy-free recursion in chunks
    from itertools import accumulate
    y=np.array(list(accumulate(x*(1-a),lambda p,v:p*a+v)))
    return y
def saw(f,n,det=0.0):
    t=np.arange(n)/SR; out=0
    for d in (-det,0,det):
        ph=(t*f*(1+d))%1; out=out+ (2*ph-1)
    return out/3

beat=60/96
chords=[[50,57,62,64,66,69],[47,54,59,61,62,66],[43,50,55,57,59,62],[45,52,57,59,61,64]] # Dmaj9, Bm9, Gmaj7(9), A6/9
roots=[38,35,31,33]
cl=5.0  # seconds per chord
# --- pad ---
for k in range(12):
    t0=k*cl; ch=chords[k%4]
    if t0>=DUR: break
    n=int((cl+2.0)*SR)
    sig=sum(saw(midi(m),n,0.004) for m in ch)/len(ch)
    sig=lp(sig,1400 if 5<=t0<50 else 900)
    e=env_adsr(n,1.2,0.5,0.8,0.9,cl)
    g=0.22 if t0<5 else 0.17
    add(mus,t0,sig*e,0,g); add(send,t0,sig*e,0,0.08)
# --- sub bass from 5s to 49.5 ---
for k in range(1,10):
    t0=k*cl; n=int(cl*SR); t=np.arange(n)/SR
    f=midi(roots[k%4]); sig=np.sin(2*np.pi*f*t)+0.25*np.sin(4*np.pi*f*t)
    e=np.minimum(1,t/0.05)*np.minimum(1,(cl-t)/0.1)
    if t0+cl>49.5: e*= (t< (49.5-t0))
    add(mus,t0,sig*e,0,0.16)
# --- pluck arpeggio from 5 to 49.5 (8ths), and gently from 50 ---
def pluck(f,dur=0.6):
    n=int(dur*SR); t=np.arange(n)/SR
    s=np.sin(2*np.pi*f*t)+0.35*np.sin(4*np.pi*f*t)*np.exp(-t*12)+0.12*np.sin(6*np.pi*f*t)*np.exp(-t*18)
    return s*np.exp(-t*6)*np.minimum(1,t/0.004)
pattern=[0,2,4,5,3,4,2,5]
t=5.0; i=0
while t<49.4:
    ch=chords[int(t//cl)%4]; m=ch[pattern[i%8]]+12
    add(mus,t,pluck(midi(m)),pan=0.35*np.sin(i*0.9),gain=0.10 if t<9 else 0.13)
    add(send,t,pluck(midi(m)),0,0.06)
    t+=beat/2; i+=1
# --- kick from 9 to 49.4 ---
def kick():
    n=int(0.35*SR); t=np.arange(n)/SR
    f=50+90*np.exp(-t*30); ph=2*np.pi*np.cumsum(f)/SR
    return np.sin(ph)*np.exp(-t*9)
duck=np.ones(N)
t=9.0
while t<49.4:
    add(mus,t,kick(),0,0.42)
    i=int(t*SR); m=int(0.25*SR); duck[i:i+m]=np.minimum(duck[i:i+m],1-0.35*np.exp(-np.arange(min(m,N-i))/SR*10))
    t+=beat
# --- hats offbeat from 17 ---
def hat():
    n=int(0.06*SR); x=rng.standard_normal(n); x=x-lp(x,6000); return x*np.exp(-np.arange(n)/SR*70)
t=17.0+beat/2
while t<49.4:
    add(mus,t,hat(),0.2,0.05); t+=beat
# --- section 9: chord hits on words, end chord ---
def bell(f,dur=2.5):
    n=int(dur*SR); t=np.arange(n)/SR
    return (np.sin(2*np.pi*f*t)+0.4*np.sin(2*np.pi*f*2.76*t)*np.exp(-t*4)+0.2*np.sin(2*np.pi*f*5.4*t)*np.exp(-t*8))*np.exp(-t*2.2)*np.minimum(1,t/0.003)
for tt,m in [(50.45,74),(51.05,76),(51.65,78)]:
    add(mus,tt,bell(midi(m)),0,0.16); add(send,tt,bell(midi(m)),0,0.14)
for m in [62,66,69,74,78]:
    add(mus,54.25,bell(midi(m),4.5),0,0.10); add(send,54.25,bell(midi(m),4.5),0,0.10)
add(mus,54.25,kick(),0,0.5)
# opening word ticks
for tt,m in [(0.35,74),(1.15,76),(1.95,81)]:
    add(mus,tt,bell(midi(m),1.6),0,0.11); add(send,tt,bell(midi(m),1.6),0,0.10)
mus[:,0]*=duck; mus[:,1]*=duck

# ---------- SFX ----------
def click(gain=1):
    n=int(0.03*SR); t=np.arange(n)/SR; x=rng.standard_normal(n)
    x=x-lp(x,2500); return (x*0.5+np.sin(2*np.pi*1800*t))*np.exp(-t*220)*gain
def whoosh(dur=0.8,up=True):
    n=int(dur*SR); t=np.arange(n)/SR; x=rng.standard_normal(n)
    fc=np.linspace(400,4000,n) if up else np.linspace(4000,400,n)
    # time-varying lowpass approx: blend two filters
    lo=lp(x,600); hi=lp(x,4000); k=(fc-400)/3600; y=lo*(1-k)+hi*k
    e=np.sin(np.pi*t/dur)**2
    return y*e
def ding(f1,f2,gain=1):
    return (np.pad(bell(f1,1.2),(0,int(0.09*SR)))+np.pad(bell(f2,1.2),(int(0.09*SR),0)))*gain
for tt in [21.0,23.0,27.0,28.4,30.9,33.6,40.4,47.2]: add(sfx,tt,click(),0.15,0.35)
for tt in [10.2,10.55,10.9,11.25,11.6]: add(sfx,tt,click(0.8),-0.2,0.25)
msg='On my way! Save me a seat.'
for i in range(len(msg)):
    if msg[i]!=' ': add(sfx,21.2+1.4*i/len(msg),click(0.5),0.1,0.14)
for tt in [12.0,35.4,48.35]: add(sfx,tt,ding(midi(81),midi(88)),0,0.10); add(send,tt,ding(midi(81),midi(88)),0,0.05)
add(sfx,17.8,ding(midi(86),midi(81)),0.2,0.09)
add(sfx,23.05,whoosh(0.35,True),0,0.10)
add(sfx,4.6,whoosh(1.0,True),0,0.16); add(sfx,49.3,whoosh(1.0,False),0,0.14)
for tt in [8.8,16.8,24.8,31.8,37.8,43.8]: add(sfx,tt,whoosh(0.5,True),0,0.07)
# phone ring 38.8 -> 40.4 (marimba pattern)
for k,tt in enumerate(np.arange(38.85,40.35,0.18)):
    add(sfx,tt,bell(midi([88,85,81,85][k%4]),0.4),0.4,0.06)
# soft riser before devices (3.0-5.0)
n=int(2.0*SR); t=np.arange(n)/SR; x=rng.standard_normal(n); r=lp(x,2500)*(t/2.0)**2
add(sfx,3.0,r,0,0.05)

# ---------- reverb ----------
ir_n=int(2.2*SR); tt=np.arange(ir_n)/SR
ir=np.stack([rng.standard_normal(ir_n)*np.exp(-tt*3.0) for _ in range(2)],1); ir/=np.sqrt((ir**2).sum(0))
def conv(a,b):
    L=len(a)+len(b)-1; nfft=1<<(L-1).bit_length()
    return np.fft.irfft(np.fft.rfft(a,nfft)*np.fft.rfft(b,nfft),nfft)[:len(a)]
wet=np.stack([conv(send[:,c],ir[:,c]) for c in range(2)],1)
mix=mus+sfx+wet*0.9
# master fade out + soft clip
fade=np.ones(N); fs=int(56.5*SR); fade[fs:]=np.linspace(1,0,N-fs)**1.5
mix*=fade[:,None]
mix=np.tanh(mix*1.2)/np.tanh(1.2)
mix/=np.max(np.abs(mix))/0.89
pcm=(mix*32767).astype('<i2')
with wave.open('audio.wav','wb') as w: w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR); w.writeframes(pcm.tobytes())
print('ok',mix.std())
