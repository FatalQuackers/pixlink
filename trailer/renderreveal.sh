# Renders the Tethr Link reveal (reveal.html) for one format: bash renderreveal.sh wide|tall
set -e
f=$1; mkdir -p r$f
B=(0 6 13 17 21.6 30 38.6 46 56.5)
for i in $(seq 0 7); do n=$(printf "%02d" $i); [ -f r$f/s$n.mp4 ] || PAGE=reveal.html node render.js $f scene ${B[$i]} ${B[$((i+1))]} r$f/s$n.mp4; done
ls r$f/s*.mp4 | sed "s|r$f/||;s|^|file '|;s|$|'|" > r$f/list.txt
ffmpeg -y -loglevel error -f concat -safe 0 -i r$f/list.txt -c copy r$f/video.mp4
ffmpeg -y -loglevel error -i r$f/video.mp4 -i reveal.wav -map 0:v -map 1:a -c:v copy -c:a aac -b:a 192k -shortest r$f/final.mp4
echo DONE $f
