# Renders the fast reveal (reveal.html?m=fast): bash renderfast.sh wide|tall
set -e
f=$1; d=rf$f; mkdir -p $d
B=(0 6 12 18 24.4)
for i in 0 1 2 3; do n=$(printf "%02d" $i); [ -f $d/s$n.mp4 ] || M=fast PAGE=reveal.html node render.js $f scene ${B[$i]} ${B[$((i+1))]} $d/s$n.mp4; done
ls $d/s*.mp4 | sed "s|$d/||;s|^|file '|;s|$|'|" > $d/list.txt
ffmpeg -y -loglevel error -f concat -safe 0 -i $d/list.txt -c copy $d/video.mp4
ffmpeg -y -loglevel error -i $d/video.mp4 -i reveal_fast.wav -map 0:v -map 1:a -c:v copy -c:a aac -b:a 192k -shortest $d/final.mp4
echo DONE $f
