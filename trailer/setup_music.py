import numpy as np, wave
SR=48000; DUR=68.0; N=int(SR*DUR)
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
chords=[[50,57,62,64,66,69],[47,54,59,61,62,66],[43,50,55,57,59,62],[45,52,57,59,61,64]]
roots=[38,35,31,33]; cl=5.0
for k in range(14):
    t0=k*cl; ch=chords[k%4]
    if t0>=DUR: break
    n=int((cl+2.0)*SR); sig=sum(saw(midi(m),n,0.004) for m in ch)/len(ch); sig=lp(sig,1100)
    e=env_adsr(n,1.2,0.5,0.8,0.9,cl); add(mus,t0,sig*e,0,0.16); add(send,t0,sig*e,0,0.08)
for k in range(1,13):
    t0=k*cl; n=int(cl*SR); t=np.arange(n)/SR; f=midi(roots[k%4])
    e=np.minimum(1,t/0.3)*np.minimum(1,(cl-t)/0.2)
    if t0+cl>61: e*=(t<(61-t0))
    add(mus,t0,np.sin(2*np.pi*f*t)*e,0,0.12)
def pluck(f,dur=0.6):
    n=int(dur*SR); t=np.arange(n)/SR
    return (np.sin(2*np.pi*f*t)+0.3*np.sin(4*np.pi*f*t)*np.exp(-t*12))*np.exp(-t*6)*np.minimum(1,t/0.004)
pattern=[0,2,4,5,3,4,2,5]; t=5.5; i=0
while t<60.8:
    ch=chords[int(t//cl)%4]; m=ch[pattern[i%8]]+12
    add(mus,t,pluck(midi(m)),pan=0.35*np.sin(i*0.9),gain=0.08); add(send,t,pluck(midi(m)),0,0.05)
    t+=beat/2; i+=1
def kick():
    n=int(0.3*SR); t=np.arange(n)/SR; f=50+70*np.exp(-t*30); return np.sin(2*np.pi*np.cumsum(f)/SR)*np.exp(-t*10)
t=5.5
while t<60.8:
    add(mus,t,kick(),0,0.22); t+=beat*2
def bell(f,dur=2.5):
    n=int(dur*SR); t=np.arange(n)/SR
    return (np.sin(2*np.pi*f*t)+0.4*np.sin(2*np.pi*f*2.76*t)*np.exp(-t*4)+0.2*np.sin(2*np.pi*f*5.4*t)*np.exp(-t*8))*np.exp(-t*2.2)*np.minimum(1,t/0.003)
for tt,m in [(0.3,74),(0.8,78)]: add(mus,tt,bell(midi(m),1.6),0,0.10); add(send,tt,bell(midi(m),1.6),0,0.08)
for m in [62,66,69,74,78]: add(mus,61.4,bell(midi(m),5),0,0.09); add(send,61.4,bell(midi(m),5),0,0.10)
def click(gain=1):
    n=int(0.03*SR); t=np.arange(n)/SR; x=rng.standard_normal(n); x=x-lp(x,2500); return (x*0.5+np.sin(2*np.pi*1800*t))*np.exp(-t*220)*gain
def tap():
    n=int(0.05*SR); t=np.arange(n)/SR; return np.sin(2*np.pi*(900-400*t/0.05)*t)*np.exp(-t*90)
def whoosh(dur=0.8):
    n=int(dur*SR); t=np.arange(n)/SR; x=rng.standard_normal(n); lo=lp(x,600); hi=lp(x,4000); k=t/dur
    return (lo*(1-k)+hi*k)*np.sin(np.pi*t/dur)**2
def ding(f1,f2): return np.pad(bell(f1,1.2),(0,int(0.09*SR)))+np.pad(bell(f2,1.2),(int(0.09*SR),0))
for tt in [7.6,10.2,12.4,13.8,16.9,18.8,19.7,20.9,22.6,23.7,25.3,26.1,53.4,56,56.35,56.7,57.05,57.4]: add(sfx,tt,tap(),-0.1,0.18)
for tt in [30,31.8,34.2,35.2,37.4,39.6,40.9,42.2,45,46.4]: add(sfx,tt,click(),0.15,0.30)
add(sfx,39.75,click(),0.15,0.30)
for tt in [5.0,27.6,47.9,60.8]: add(sfx,tt,whoosh(0.9),0,0.10)
for tt in [24.75,26.3,58.0]: add(sfx,tt,ding(midi(81),midi(88)),0,0.09); add(send,tt,ding(midi(81),midi(88)),0,0.05)
add(sfx,59.4,ding(midi(86),midi(81)),0.2,0.08)
# ---------- reverb ----------
ir_n=int(2.2*SR); tt=np.arange(ir_n)/SR
ir=np.stack([rng.standard_normal(ir_n)*np.exp(-tt*3.0) for _ in range(2)],1); ir/=np.sqrt((ir**2).sum(0))
def conv(a,b):
    L=len(a)+len(b)-1; nfft=1<<(L-1).bit_length()
    return np.fft.irfft(np.fft.rfft(a,nfft)*np.fft.rfft(b,nfft),nfft)[:len(a)]
wet=np.stack([conv(send[:,c],ir[:,c]) for c in range(2)],1)
mix=mus+sfx+wet*0.9
# master fade out + soft clip
fade=np.ones(N); fs=int(66.0*SR); fade[fs:]=np.linspace(1,0,N-fs)**1.5
mix*=fade[:,None]
mix=np.tanh(mix*1.2)/np.tanh(1.2)
mix/=np.max(np.abs(mix))/0.89
pcm=(mix*32767).astype('<i2')
with wave.open('setup.wav','wb') as w: w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR); w.writeframes(pcm.tobytes())
print('ok',mix.std())
