const puppeteer=require('puppeteer');
const {spawn}=require('child_process');const fs=require('fs');
const [,,fmt,mode,...rest]=process.argv; // mode: shots t1,t2.. | scene start end out
(async()=>{
 const W=fmt==='wide'?1920:1080,H=fmt==='wide'?1080:1920;
 const b=await puppeteer.launch({args:['--no-sandbox','--disable-gpu','--font-render-hinting=none']});
 const p=await b.newPage();await p.setViewport({width:W,height:H,deviceScaleFactor:1});
 await p.goto('file://'+__dirname+'/trailer.html?f='+fmt,{waitUntil:'networkidle0'});
 await p.evaluate(()=>document.fonts.ready);
 if(mode==='shots'){for(const t of rest[0].split(',')){await p.evaluate(t=>render(t),+t);await p.screenshot({path:`shot_${fmt}_${t}.jpg`,type:'jpeg',quality:85});}}
 else{const [s,e,out]=rest;const fps=30;const n=Math.round((e-s)*fps);
  const ff=spawn('ffmpeg',['-y','-loglevel','error','-f','image2pipe','-framerate','30','-c:v','mjpeg','-i','-','-c:v','libx264','-preset','veryfast','-crf','18','-pix_fmt','yuv420p','-r','30',out+'.tmp.mp4'],{stdio:['pipe','inherit','inherit']});
  for(let i=0;i<n;i++){await p.evaluate(t=>render(t),+s+i/fps);const buf=await p.screenshot({type:'jpeg',quality:92});if(!ff.stdin.write(buf))await new Promise(r=>ff.stdin.once('drain',r));}
  ff.stdin.end();await new Promise(r=>ff.on('close',c=>{if(c===0)fs.renameSync(out+'.tmp.mp4',out);r()}));}
 await b.close();})();
