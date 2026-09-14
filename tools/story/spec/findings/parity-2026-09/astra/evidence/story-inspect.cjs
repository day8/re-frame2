const fs = require('fs');
const path = require('path');
const {chromium} = require(path.resolve('implementation/node_modules/playwright'));
(async () => {
  const browser = await chromium.launch({headless:true});
  const page = await browser.newPage({viewport:{width:1440,height:1000}});
  const errors=[];
  page.on('pageerror',e=>errors.push(String(e)));
  await page.goto('http://localhost:8043/index.html?variant=story.login-form%2Ferror#/stories',{waitUntil:'networkidle', timeout: 30000 });
  await page.waitForTimeout(1000);
  await page.screenshot({path:path.join(__dirname,'story-initial.png'),fullPage:true});
  const result={at:new Date().toISOString(),browser:browser.version(),url:page.url(),errors,text:await page.locator('body').innerText(),inputs:await page.locator('input').evaluateAll(xs=>xs.map(x=>({type:x.type,value:x.value,placeholder:x.placeholder,aria:x.getAttribute('aria-label'),'data-test':x.getAttribute('data-test')}))),buttons:await page.getByRole('button').allTextContents(),dataTests:await page.locator('[data-test]').evaluateAll(xs=>[...new Set(xs.map(x=>x.getAttribute('data-test')))])};
  fs.writeFileSync(path.join(__dirname,'story-initial.json'),JSON.stringify(result,null,2));
  console.log(JSON.stringify(result,null,2));
  await browser.close();
})().catch(e=>{console.error(e);process.exit(1)});
