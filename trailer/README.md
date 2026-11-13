# Tethr Link trailer

The release trailer is a time-driven HTML page (`trailer.html`). `render.js` films it frame by frame with Puppeteer and ffmpeg, and `music.py` generates the soundtrack and UI sounds with NumPy.

- Preview any moment: open `trailer.html?f=wide&t=20` (or `f=tall` for the vertical cut).
- Render: `npm i puppeteer`, then `bash renderall.sh` (writes `wide/video.mp4` and `tall/video.mp4`).
- Sound: `python3 music.py` (writes `audio.wav`), then mux it with `ffmpeg -i wide/video.mp4 -i audio.wav -map 0:v -map 1:a -c:v copy -c:a aac -b:a 192k -shortest out.mp4`.

## Hype cut

A 29-second, 128 BPM sizzle version uses the same scenes with beat-synced word slams and zoom cuts.

- Preview: `trailer.html?f=wide&m=hype&t=12`
- Render: `bash renderhype.sh` (writes `hwide/video.mp4` and `htall/video.mp4`), sound: `python3 hype.py` (writes `hype.wav`).

## Setup video

A 68-second how-to-set-up walkthrough: `setup.html` (preview with `setup.html?f=wide&t=40`), render with `bash rendersetup.sh`, sound with `python3 setup_music.py` (writes `setup.wav`).

## Tethr Link reveal

A 56-second, keynote-style reveal of the new name: `reveal.html` (preview with `reveal.html?f=wide&t=18`). Sound: `python3 reveal_music.py` (writes `reveal.wav`). Render and mux: `bash renderreveal.sh wide` and `bash renderreveal.sh tall` (write `rwide/final.mp4` and `rtall/final.mp4`). Without the full `puppeteer` package, `render.js` uses `puppeteer-core` with Chrome at `/usr/bin/google-chrome` (or `$CHROME`).
