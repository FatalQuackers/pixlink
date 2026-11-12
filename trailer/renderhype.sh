set -e
B=(0 3.75 7.5 11.25 16.875 20.625 24.375 29)
for f in wide tall; do mkdir -p h$f
 for i in $(seq 0 6); do M=hype node render.js $f scene ${B[$i]} ${B[$((i+1))]} h$f/s0$i.mp4; done
 ls h$f/s*.mp4 | sed "s|h$f/||;s|^|file '|;s|$|'|" > h$f/list.txt
 ffmpeg -y -loglevel error -f concat -safe 0 -i h$f/list.txt -c copy h$f/video.mp4
done
echo DONE
