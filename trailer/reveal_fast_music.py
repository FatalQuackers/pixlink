"""Soundtrack for the fast reveal (reveal.html?m=fast): 128 BPM, 24.4 s, hits on every section.
Writes reveal_fast.wav."""
import numpy as np, wave
from scipy.signal import lfilter, butter
SR = 48000; BEAT = 60 / 128; DUR = 52 * BEAT + 0.05; N = int(SR * DUR)
rng = np.random.default_rng(5); mix = np.zeros((N, 2))
def midi(m): return 440 * 2 ** ((m - 69) / 12)
def tt(n): return np.arange(n) / SR
def lp(x, fc): b, a = butter(2, min(fc, SR * .45) / (SR / 2)); return lfilter(b, a, x)
def hp(x, fc): b, a = butter(2, fc / (SR / 2), 'high'); return lfilter(b, a, x)
def add(t0, s, pan=0., g=1.):
    i = int(t0 * SR)
    if i >= N: return
    s = s[:N - i]; l, r = np.cos((pan + 1) * np.pi / 4), np.sin((pan + 1) * np.pi / 4)
    mix[i:i + len(s), 0] += s * l * g * 1.414; mix[i:i + len(s), 1] += s * r * g * 1.414
def saw(f, n, d=.006):
    t = tt(n); return sum(2 * ((t * f * (1 + x)) % 1) - 1 for x in (-d, 0, d)) / 3
def kick(t0, g=.75):
    k = int(.32 * SR); add(t0, np.sin(2 * np.pi * (46 + 110 * np.exp(-tt(k) * 38)) * tt(k)) * np.exp(-tt(k) * 10), g=g)
def clap(t0, g=.2):
    k = int(.18 * SR); add(t0, hp(rng.standard_normal(k), 1300) * np.exp(-tt(k) * 24), pan=.1, g=g)
def hat(t0, g=.08):
    k = int(.05 * SR); add(t0, hp(rng.standard_normal(k), 7500) * np.exp(-tt(k) * 70), pan=.35, g=g)
def hit(t0, g=1.):
    k = int(3 * SR)
    add(t0, np.sin(2 * np.pi * (34 + 90 * np.exp(-tt(k) * 20)) * tt(k)) * np.exp(-tt(k) * 2.2), g=.95 * g)
    add(t0, lp(rng.standard_normal(k), 5500) * np.exp(-tt(k) * 8), pan=-.2, g=.24 * g)
    add(t0, lp(rng.standard_normal(k), 4500) * np.exp(-tt(k) * 7), pan=.2, g=.2 * g)
def riser(t0, dur, g=.12):
    k = int(dur * SR); add(t0, lp(hp(rng.standard_normal(k), 700), 9000) * np.linspace(0, 1, k) ** 2.5, g=g)
    s = np.sin(2 * np.pi * np.cumsum(np.linspace(150, 1200, k)) / SR) * np.linspace(0, 1, k) ** 3; add(t0, s, g=g * .45)
chords = [[50, 57, 62, 66, 69], [47, 54, 59, 62, 66], [43, 50, 55, 59, 62], [45, 52, 57, 61, 64]]
def bar(t0, ch, drums=True, bass=True, padg=.2):
    k = int((4 * BEAT + .6) * SR)
    pad = lp(sum(saw(midi(m), k) for m in ch) / len(ch), 1900) * np.minimum(1, tt(k) / .05) * np.exp(-np.maximum(0, tt(k) - 4 * BEAT) / .3)
    add(t0, pad, g=padg)
    for j in range(8):
        if bass:
            k8 = int(BEAT / 2 * SR); add(t0 + j * BEAT / 2, lp(saw(midi(ch[0] - 12), k8, 0), 600) * np.exp(-tt(k8) * 5), g=.34)
        if drums: hat(t0 + j * BEAT / 2 + BEAT / 4)
    if drums:
        for j in range(4):
            kick(t0 + j * BEAT)
            if j % 2: clap(t0 + j * BEAT)
# 0–4 beats: spark — kick on 1 and 3, rising
hit(0, .55); kick(2 * BEAT, .6)
riser(0, 4 * BEAT, .07)
# 4–8: devices — half-time groove
bar(4 * BEAT, chords[0], drums=True, bass=False, padg=.14)
# 8–12: Pixlink dissolves — snare roll into the name
for i in range(16):
    clap(8 * BEAT + i * BEAT / 4, .05 + .012 * i)
riser(8 * BEAT, 4 * BEAT, .14)
# 12: THE NAME
hit(12 * BEAT, 1.1)
for m, p in ((62, -.4), (66, 0), (69, .4), (74, .15), (78, -.15)):
    k = int(2.4 * SR); add(12 * BEAT, lp(saw(midi(m), k, .004), 3600) * np.exp(-tt(k) * 1.4), pan=p, g=.06)
kick(14 * BEAT, .5)
# 16–28: features, full groove, whoosh every 2 beats
for b in range(3):
    bar((16 + 4 * b) * BEAT, chords[(b + 1) % 4])
for f in range(6):
    t0 = (16 + 2 * f) * BEAT; k = int(.5 * SR)
    add(t0, lp(rng.standard_normal(k), 3000 + f * 400) * np.sin(np.linspace(0, np.pi, k)) ** 3, pan=-.5 + f * .2, g=.16)
# 28–36: every phone — build: plinks + riser, drums thinning to 8ths
for i in range(5):
    k = int(1.2 * SR); add((28 + i * .5) * BEAT + .2, np.sin(2 * np.pi * midi([74, 76, 78, 81, 83][i]) * tt(k)) * np.exp(-tt(k) * 3.5), pan=-.6 + i * .3, g=.1)
bar(28 * BEAT, chords[0], drums=True, padg=.18)
for i in range(8): kick(32 * BEAT + i * BEAT / 2, .55 + .03 * i)
riser(32 * BEAT, 4 * BEAT, .16)
# 36: final hit, ring out
hit(36 * BEAT, 1.0)
k = int(8 * SR); add(36 * BEAT, sum(np.sin(2 * np.pi * midi(m) * tt(k)) for m in (62, 69, 74, 78)) / 4 * np.exp(-tt(k) * .55), g=.13)
k = int(7 * SR); add(36 * BEAT, lp(sum(saw(midi(m), k) for m in (50, 57, 62, 66)) / 4, 1500) * np.exp(-tt(k) * .5), g=.14)
fade = np.ones(N); fl = int(BEAT * SR); fade[-fl:] = np.linspace(1, 0, fl); mix *= fade[:, None]
mix = np.tanh(mix / np.max(np.abs(mix)) * 1.7) / np.tanh(1.7) * .92
with wave.open('reveal_fast.wav', 'wb') as w:
    w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR); w.writeframes((mix * 32767).astype(np.int16).tobytes())
print('reveal_fast.wav', round(DUR, 2))
