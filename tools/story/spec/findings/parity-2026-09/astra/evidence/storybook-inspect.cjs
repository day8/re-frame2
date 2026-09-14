const fs=require('fs'),path=require('path');
const {chromium}=require(path.resolve('implementation/node_modules/playwright'));
(async()=>{
 const browser=await chromium.launch({headless:true});const page=await browser.newPage({viewport:{width:1440,height:1000}});
 const errors=[];page.on('pageerror',e=>errors.push(String(e)));
 await page.goto('http://localhost:6106/?path=/story/research-login--retry-to-success',{waitUntil:'networkidle'});
 const frame=page.frameLocator('#storybook-preview-iframe');await frame.getByText('Welcome, ada@example.com',{exact:true}).waitFor({timeout:20000});
 await page.screenshot({path:path.join(__dirname,'storybook-success.png'),fullPage:true});
 const result={at:new Date().toISOString(),browser:browser.version(),url:page.url(),errors,manager:await page.locator('body').innerText(),preview:await frame.locator('body').innerText(),buttons:await page.getByRole('button').allTextContents(),inputs:await page.locator('input,textarea').evaluateAll(xs=>xs.map(x=>({type:x.type,value:x.value,placeholder:x.placeholder,aria:x.getAttribute('aria-label')})))};
 fs.writeFileSync(path.join(__dirname,'storybook-initial.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result,null,2));await browser.close();
})().catch(e=>{console.error(e);process.exit(1)});
