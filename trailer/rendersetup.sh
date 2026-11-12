set -e
B=(0 5.5 11 15.5 21.5 28 33 38.5 43.5 48.5 55.5 61 68)
for f in ${FMTS:-wide tall}; do mkdir -p s$f
 for i in $(seq 0 11); do n=$(printf "%02d" $i); PAGE=setup.html node render.js $f scene ${B[$i]} ${B[$((i+1))]} s$f/s$n.mp4; done
 ls s$f/s*.mp4 | sed "s|s$f/||;s|^|file '|;s|$|'|" > s$f/list.txt
 ffmpeg -y -loglevel error -f concat -safe 0 -i s$f/list.txt -c copy s$f/video.mp4
done
echo DONE
