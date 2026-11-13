set -e
B=(0 5 9 17 25 32 38 44 50 54 59)
for f in wide tall; do mkdir -p $f
 for i in $(seq 0 9); do n=$(printf "%02d" $i); node render.js $f scene ${B[$i]} ${B[$((i+1))]} $f/s$n.mp4; done
 ls $f/s*.mp4 | sed "s|$f/||;s|^|file '|;s|$|'|" > $f/list.txt
 ffmpeg -y -loglevel error -f concat -safe 0 -i $f/list.txt -c copy $f/video.mp4
done
echo DONE
