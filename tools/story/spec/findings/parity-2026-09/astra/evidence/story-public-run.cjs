/* global cljs, re_frame */
const fs=require('fs'),path=require('path');
const {chromium}=require(path.resolve('implementation/node_modules/playwright'));
(async()=>{
 const browser=await chromium.launch({headless:true});const page=await browser.newPage();
 await page.goto('http://localhost:8043/index.html?variant=story.login-form%2Ferror#/stories',{waitUntil:'networkidle', timeout: 30000 });
 const observations=await page.evaluate(async()=>{
   const read=cljs.reader.read_string, pr=cljs.core.pr_str;
   const results=[];
   for(const [name,target,opts] of [
     ['registered-idle',':story.login-form/idle','{}'],
     ['false-terminal','{:setup [] :assertions [[:rf.assert/path-equals [:missing] 42]]}','{}'],
     ['true-terminal','{:setup [] :assertions [[:rf.assert/path-equals [:missing] nil]]}','{}'],
     ['DOM-under-headless','{:script [[:click "button"]]}','{:runner :headless}'],
   ]){
     try {const r=await re_frame.story.run(read(target),read(opts));results.push({name,target,opts,result:pr(r)});}
     catch(e){results.push({name,target,opts,error:String(e),data:e.data?pr(e.data):null});}
   }
   return results;
 });
 fs.writeFileSync(path.join(__dirname,'story-public-run.json'),JSON.stringify({at:new Date().toISOString(),observations},null,2));
 console.log(JSON.stringify(observations,null,2));await browser.close();
})().catch(e=>{console.error(e);process.exit(1)});
