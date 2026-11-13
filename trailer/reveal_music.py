"""Soundtrack for the Tethr Link reveal (reveal.html): a dark, keynote-style build into a big hit at
17.4 s (the name), a driving pulse under the features, and a final hit and ring-out for the logo.
Writes reveal.wav (48 kHz stereo)."""
import numpy as np, wave
from scipy.signal import lfilter, butter

SR = 48000
DUR = 57.0
N = int(SR * DUR)
rng = np.random.default_rng(11)
mix = np.zeros((N, 2))

def midi(m): return 440 * 2 ** ((m - 69) / 12)
def tt(n): return np.arange(n) / SR

def lp(x, fc, order=2):
    b, a = butter(order, min(fc, SR * 0.45) / (SR / 2))
    return lfilter(b, a, x)

def hp(x, fc, order=2):
    b, a = butter(order, fc / (SR / 2), 'high')
    return lfilter(b, a, x)

def add(t0, sig, pan=0.0, gain=1.0):
    i = int(t0 * SR)
    if i >= N: return
    sig = sig[:N - i]
    l, r = np.cos((pan + 1) * np.pi / 4), np.sin((pan + 1) * np.pi / 4)
    mix[i:i + len(sig), 0] += sig * l * gain * 1.414
    mix[i:i + len(sig), 1] += sig * r * gain * 1.414

def saw(f, n, det=0.006):
    t = tt(n); out = 0
    for d in (-det, 0, det):
        out = out + 2 * ((t * f * (1 + d)) % 1) - 1
    return out / 3

def env(n, a, r, hold=None):
    t = tt(n); hold = hold if hold is not None else n / SR - r
    e = np.minimum(1, t / max(a, 1e-4))
    e = np.where(t > hold, e * np.exp(-(t - hold) / r), e)
    return e

# ---- 1. Drone (0–17): low fifth, slowly opening filter ----
n = int(17.6 * SR)
drone = (saw(midi(26), n) + saw(midi(33), n) * 0.7 + saw(midi(38), n) * 0.4)
drone = lp(drone, 420)
drone *= np.minimum(1, tt(n) / 3.0) * (0.6 + 0.4 * np.minimum(1, tt(n) / 15))
add(0, drone, gain=0.32)

# shimmer on the spark (0.8 s) and line (2 s)
for t0, f in ((0.8, 88), (2.0, 93), (3.4, 100)):
    k = int(3.5 * SR)
    s = np.sin(2 * np.pi * midi(f) * tt(k)) * env(k, 0.02, 1.2, 0.2)
    add(t0, s, pan=(f - 93) / 20, gain=0.07)

# soft heartbeat pulse from 6 s, speeding into the reveal
t = 6.0
while t < 16.8:
    k = int(0.25 * SR)
    kick = np.sin(2 * np.pi * (50 + 60 * np.exp(-tt(k) * 30)) * tt(k)) * np.exp(-tt(k) * 14)
    add(t, kick, gain=0.55)
    t += max(0.32, 0.9 - (t - 6) * 0.06)

# riser into the hit
k = int(4.2 * SR)
noise = rng.standard_normal(k)
riser = hp(noise, 600) * np.linspace(0, 1, k) ** 2.4
riser = lp(riser, 9000)
add(13.2, riser, gain=0.12)
k2 = int(4.2 * SR); sweep = np.sin(2 * np.pi * np.cumsum(np.linspace(110, 880, k2)) / SR) * np.linspace(0, 1, k2) ** 3
add(13.2, sweep, gain=0.05)
# "Pixlink" dissolves: a reverse-ish whoosh
k = int(1.2 * SR); w = lp(rng.standard_normal(k), 3000) * np.sin(np.linspace(0, np.pi, k)) ** 2
add(15.4, w, pan=-0.3, gain=0.16)

# ---- 2. THE HIT at 17.4 ----
def hit(t0, big=1.0):
    k = int(5.5 * SR)
    sub = np.sin(2 * np.pi * (36 + 80 * np.exp(-tt(k) * 18)) * tt(k)) * np.exp(-tt(k) * 1.4)
    crack = lp(rng.standard_normal(k), 5000) * np.exp(-tt(k) * 9)
    add(t0, sub, gain=0.9 * big)
    add(t0, crack, pan=-0.2, gain=0.22 * big)
    add(t0, lp(rng.standard_normal(k), 4000) * np.exp(-tt(k) * 7), pan=0.2, gain=0.18 * big)
    # bright chord bloom
    for m, p in ((62, -0.4), (66, 0.0), (69, 0.4), (74, 0.15), (78, -0.15)):
        s = lp(saw(midi(m), k, 0.004), 3200) * env(k, 0.01, 2.2, 0.4)
        add(t0, s, pan=p, gain=0.06 * big)
hit(17.4)

# ---- 3. Features (21.6–38.1): 104 BPM pulse + chords ----
bpm = 104; beat = 60 / bpm
chords = [[50, 57, 62, 66, 69], [47, 54, 59, 62, 66], [43, 50, 55, 59, 62], [45, 52, 57, 61, 64]]
start, end = 20.8, 38.6
bar = 4 * beat
i = 0
t = start
while t < end:
    ch = chords[i % 4]
    k = int((bar + 1.2) * SR)
    pad = sum(saw(midi(m), k) for m in ch) / len(ch)
    pad = lp(pad, 1600) * env(k, 0.35, 0.8, bar)
    add(t, pad, gain=0.22)
    # bass on 8ths
    for j in range(8):
        k8 = int(beat / 2 * SR)
        b = lp(saw(midi(ch[0] - 12), k8, 0.0), 500) * np.exp(-tt(k8) * 6)
        add(t + j * beat / 2, b, gain=0.32)
    # kick + clap
    for j in range(4):
        kk = int(0.3 * SR)
        kick = np.sin(2 * np.pi * (48 + 90 * np.exp(-tt(kk) * 35)) * tt(kk)) * np.exp(-tt(kk) * 11)
        add(t + j * beat, kick, gain=0.6)
        if j % 2 == 1:
            cl = hp(rng.standard_normal(int(0.2 * SR)), 1200) * np.exp(-tt(int(0.2 * SR)) * 22)
            add(t + j * beat, cl, pan=0.1, gain=0.16)
        # hats
        hh = hp(rng.standard_normal(int(0.06 * SR)), 7000) * np.exp(-tt(int(0.06 * SR)) * 60)
        add(t + j * beat + beat / 2, hh, pan=0.3, gain=0.08)
    t += bar; i += 1

# whoosh + tick for each feature (every 2.75 s from 21.6)
for f in range(6):
    t0 = 21.6 + f * 2.75
    k = int(0.9 * SR)
    w = lp(rng.standard_normal(k), 2500 + f * 300) * np.sin(np.linspace(0, np.pi, k)) ** 3
    add(t0 + 0.1, w, pan=-0.5 + f * 0.2, gain=0.14)
    kt = int(0.12 * SR)
    tick = np.sin(2 * np.pi * 1800 * tt(kt)) * np.exp(-tt(kt) * 40)
    add(t0 + 1.5, tick, pan=0.4, gain=0.09)

# ---- 4. Every phone (38.6–46): five plinks as phones appear, a lift ----
for j in range(5):
    k = int(1.5 * SR)
    s = np.sin(2 * np.pi * midi([74, 76, 78, 81, 83][j]) * tt(k)) * np.exp(-tt(k) * 3)
    add(39.0 + j * 0.18, s, pan=-0.6 + j * 0.3, gain=0.09)
k = int(8 * SR)
lift = sum(saw(midi(m), k) for m in (55, 62, 67, 71)) / 4
lift = lp(lift, 1300) * env(k, 1.5, 1.0, 7.0)
add(38.4, lift, gain=0.2)
k = int(3.0 * SR)
riser2 = hp(rng.standard_normal(k), 900) * np.linspace(0, 1, k) ** 2.6
add(43.4, lp(riser2, 9000), gain=0.10)

# ---- 5. Final hit (46.2) and ring-out ----
hit(46.2, 0.9)
k = int(10 * SR)
ring = sum(np.sin(2 * np.pi * midi(m) * tt(k)) for m in (62, 69, 74, 78)) / 4
add(46.3, ring * np.exp(-tt(k) * 0.45), gain=0.12)

# ---- master: gentle stereo widen, limit, fade ----
mix[:, 1] = np.concatenate([np.zeros(int(0.012 * SR)), mix[:-int(0.012 * SR), 1]]) * 0.5 + mix[:, 1] * 0.5
fade = np.ones(N); fl = int(1.5 * SR); fade[-fl:] = np.linspace(1, 0, fl)
mix *= fade[:, None]
peak = np.max(np.abs(mix))
mix = np.tanh(mix / peak * 1.6) / np.tanh(1.6) * 0.92
pcm = (mix * 32767).astype(np.int16)
with wave.open('reveal.wav', 'wb') as w:
    w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR); w.writeframes(pcm.tobytes())
print('reveal.wav', DUR, 's')
