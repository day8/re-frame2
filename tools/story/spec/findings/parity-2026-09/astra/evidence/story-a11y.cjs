const fs=require('fs'),path=require('path');
const {chromium}=require(path.resolve('implementation/node_modules/playwright'));
(async()=>{
 const browser=await chromium.launch({headless:true});const page=await browser.newPage({viewport:{width:1440,height:1000}});const errors=[];page.on('pageerror',e=>errors.push(String(e)));
 await page.goto('http://localhost:8043/index.html?variant=story.login-form%2Ferror#/stories',{waitUntil:'networkidle'});await page.getByRole('button',{name:'Got it',exact:true}).click();
 const colors=await page.locator('[data-test="login-card"], [data-test="login-heading"]').evaluateAll(xs=>xs.map(x=>({element:x.getAttribute('data-test'),color:getComputedStyle(x).color,background:getComputedStyle(x).backgroundColor,text:x.textContent})));
 await page.getByRole('button',{name:'run',exact:true}).first().click();
 const consent=page.getByRole('button',{name:'enable axe-core + scan',exact:true});
 await consent.first().waitFor({state:'visible',timeout:5000});
 await consent.first().click();
 let loadError=null;
 try {await page.waitForFunction(()=>typeof window.axe!=='undefined',null,{timeout:20000});}catch(e){loadError=String(e);}
 if(loadError){const result={at:new Date().toISOString(),colors,loadError,errors,text:await page.locator('body').innerText()};fs.writeFileSync(path.join(__dirname,'story-a11y.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result,null,2));await browser.close();return;}
 await page.waitForFunction(()=>cljs.core.pr_str(re_frame.story.ui.a11y.status_for(cljs.reader.read_string(':story.login-form/error')))===':done',null,{timeout:20000});
 const axe=await page.evaluate(()=>{const r=cljs.core.get(cljs.core.deref(re_frame.story.ui.a11y.violations_by_frame),cljs.reader.read_string(':story.login-form/error'));return {version:window.axe.version,scope:'Story UI variant scan',violations:Array.from(cljs.core.to_array(r)).map(v=>({id:v.id,impact:v.impact,description:v.description,help:v.help,helpUrl:v.helpUrl,nodes:v.nodes.map(n=>({html:n.html,target:n.target,failureSummary:n.failureSummary}))}))};});
 const direct=await page.evaluate(async()=>{const r=await window.axe.run(document.querySelector('[data-test="login-card"]'));return {scope:'explicit login-card after UI scan',violations:r.violations.map(v=>({id:v.id,impact:v.impact,nodes:v.nodes.map(n=>({html:n.html,target:n.target,failureSummary:n.failureSummary}))})),incomplete:r.incomplete.map(v=>({id:v.id,nodes:v.nodes.length})),uiText:document.body.innerText};});
 const result={at:new Date().toISOString(),colors,axe,direct,errors};fs.writeFileSync(path.join(__dirname,'story-a11y.json'),JSON.stringify(result,null,2));console.log(JSON.stringify({...result,direct:{...direct,uiText:'saved in JSON'}},null,2));await browser.close();
})().catch(e=>{console.error(e);process.exit(1)});
