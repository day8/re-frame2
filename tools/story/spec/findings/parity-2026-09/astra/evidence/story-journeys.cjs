const fs=require('fs'),path=require('path');
const {chromium}=require(path.resolve('implementation/node_modules/playwright'));
const {expect}=require(path.resolve('implementation/node_modules/playwright/test'));
(async()=>{
 const browser=await chromium.launch({headless:true});
 const page=await browser.newPage({viewport:{width:1440,height:1000}});
 const result={at:new Date().toISOString(),browser:browser.version(),checks:[],pageErrors:[]};
 page.on('pageerror',e=>result.pageErrors.push(String(e)));
 async function check(name,fn){const start=Date.now();try{const value=await fn();result.checks.push({name,status:'passed',ms:Date.now()-start,result:value});}catch(e){result.checks.push({name,status:'failed',error:String(e)});} }
 await page.goto('http://localhost:8043/index.html?variant=story.login-form%2Ferror#/stories',{waitUntil:'networkidle'});
 await page.getByRole('button',{name:'Got it',exact:true}).click();
 await check('controls update the real view',async()=>{
   await page.getByRole('textbox',{name:':heading',exact:true}).fill('Research sign in');
   await expect(page.locator('[data-test="login-heading"]')).toHaveText('Research sign in');
   return {heading:await page.locator('[data-test="login-heading"]').innerText()};
 });
 await page.screenshot({path:path.join(__dirname,'story-controls.png'),fullPage:true});
 await check('Test mode executes the current variant',async()=>{
   await page.getByRole('tab',{name:'Tests',exact:true}).click();
   await page.locator('[data-test="story-test-rerun"]').click();
   await expect(page.locator('[data-test="story-test-status-pill"]')).toContainText(/pass/i,{timeout:15000});
   return {testText:await page.locator('[data-test="story-test-view"]').innerText(),sidebar:await page.locator('[data-test="story-test-widget"]').innerText()};
 });
 await page.screenshot({path:path.join(__dirname,'story-tests.png'),fullPage:true});
 await check('all five registered scenarios run',async()=>{
   await page.locator('[data-test="story-test-widget-run-all"]').click();
   await expect(page.locator('[data-test="story-test-widget-headline"]')).toContainText('✓ 5',{timeout:20000});
   return await page.locator('[data-test="story-test-widget"]').innerText();
 });
 await check('five application states render side by side',async()=>{
   await page.locator('[data-test="story-sidebar-workspace-row"]').filter({hasText:':Workspace.login-form/all-states'}).click();
   await expect(page.locator('[data-test="login-card"]')).toHaveCount(5,{timeout:15000});
   return {cards:await page.locator('[data-test="login-card"]').allTextContents(),frames:await page.locator('[data-test="story-canvas-frame"]').evaluateAll(xs=>xs.map(x=>Object.fromEntries([...x.attributes].map(a=>[a.name,a.value]))))};
 });
 await page.screenshot({path:path.join(__dirname,'story-workspace.png'),fullPage:true});
 await check('public runtime JavaScript bridge available',async()=>page.evaluate(()=>({story:typeof re_frame.story.run,read:typeof cljs.reader.read_string,plan:typeof re_frame.story.variant_plan})));
 fs.writeFileSync(path.join(__dirname,'story-journeys.json'),JSON.stringify(result,null,2));
 console.log(JSON.stringify(result,null,2));await browser.close();
})().catch(e=>{console.error(e);process.exit(1)});
