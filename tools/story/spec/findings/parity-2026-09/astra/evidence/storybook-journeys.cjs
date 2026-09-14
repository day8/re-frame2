const fs=require('fs'),path=require('path');
const {chromium}=require(path.resolve('implementation/node_modules/playwright'));
const {expect}=require(path.resolve('implementation/node_modules/playwright/test'));
(async()=>{
 const browser=await chromium.launch({headless:true});const page=await browser.newPage({viewport:{width:1440,height:1000}});
 const result={at:new Date().toISOString(),browser:browser.version(),checks:[],pageErrors:[]};page.on('pageerror',e=>result.pageErrors.push(String(e)));
 async function check(name,fn){const start=Date.now();try{const value=await fn();result.checks.push({name,status:'passed',ms:Date.now()-start,result:value});}catch(e){result.checks.push({name,status:'failed',error:String(e)});}}
 await page.goto('http://localhost:6106/?path=/story/research-login--idle',{waitUntil:'networkidle', timeout: 30000 });
 const frame=page.frameLocator('#storybook-preview-iframe');
 await check('inferred heading control updates real view',async()=>{
   await page.getByPlaceholder('Edit string...').fill('Research sign in');
   await expect(frame.getByRole('heading',{name:'Research sign in'})).toBeVisible();
   return await frame.locator('body').innerText();
 });
 await page.screenshot({path:path.join(__dirname,'storybook-controls.png'),fullPage:true});
 await check('asynchronous retry succeeds and exposes interaction steps',async()=>{
   await page.goto('http://localhost:6106/?path=/story/research-login--retry-to-success',{waitUntil:'networkidle', timeout: 30000 });
   await expect(frame.getByText('Welcome, ada@example.com',{exact:true})).toBeVisible();
   await page.getByRole('tab',{name:/Interactions/}).click();
   return {manager:await page.locator('body').innerText(),preview:await frame.locator('body').innerText()};
 });
 await page.screenshot({path:path.join(__dirname,'storybook-interactions.png'),fullPage:true});
 await check('deliberate expectation fault is visible with expected and actual values',async()=>{
   await page.goto('http://localhost:6106/?path=/story/research-login--deliberate-failure',{waitUntil:'networkidle', timeout: 30000 });
   await page.getByRole('tab',{name:/Interactions/}).click();
   await expect(page.locator('body')).toContainText('grace@example.com',{timeout:15000});
   return {manager:await page.locator('body').innerText(),preview:await frame.locator('body').innerText()};
 });
 await page.screenshot({path:path.join(__dirname,'storybook-failure.png'),fullPage:true});
 fs.writeFileSync(path.join(__dirname,'storybook-journeys.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result,null,2));await browser.close();
})().catch(e=>{console.error(e);process.exit(1)});
