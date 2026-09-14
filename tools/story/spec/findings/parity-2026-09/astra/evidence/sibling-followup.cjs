/* global cljs, re_frame */
const fs=require('fs'),path=require('path');
const {chromium}=require(path.resolve('implementation/node_modules/playwright'));
const {expect}=require(path.resolve('implementation/node_modules/playwright/test'));
(async()=>{
 const browser=await chromium.launch({headless:true});const page=await browser.newPage({viewport:{width:1440,height:1000}});
 const result={at:new Date().toISOString(),errors:[],routes:[]};page.on('pageerror',e=>result.errors.push(String(e)));
 for(const suffix of ['/','/index.html']){const r=await fetch('http://localhost:8043'+suffix);result.routes.push({url:r.url,status:r.status});await r.arrayBuffer();}
 await page.goto('http://localhost:8043/index.html?variant=story.login-form%2Ferror#/stories',{waitUntil:'networkidle', timeout: 30000 });
 await page.getByRole('button',{name:'Got it',exact:true}).click();
 result.args=await page.evaluate(async()=>{
  const read=cljs.reader.read_string,pr=cljs.core.pr_str,id=read(':story.login-form/error');
  const run=await re_frame.story.run(id);
  return {publicExplain:pr(cljs.core.select_keys(re_frame.story.explain(id),read('[:args :effective-args]'))),publicResolveArgs:pr(re_frame.story.resolve_args(id)),runEffectiveArgs:pr(cljs.core.get(run,read(':effective-args'))),runStatus:pr(cljs.core.get(run,read(':status')))};
 });
 result.initialHeading=await page.locator('[data-test="login-heading"]').innerText();
 await page.evaluate(()=>re_frame.story.registrar.reg_variant_STAR_(cljs.reader.read_string(':story.login-form/research-evidence'),cljs.reader.read_string('{:extends :story.login-form/idle :script [[:dispatch [:login/flow [:login/dismiss]]]] :assertions [[:rf.assert/path-equals [:missing] 42]]}')));
 await page.evaluate(()=>re_frame.story.ui.state.swap_state_BANG_(s=>re_frame.story.ui.state.select_variant(s,cljs.reader.read_string(':story.login-form/research-evidence'))));
 await page.getByRole('tab',{name:'Tests',exact:true}).click();await page.locator('[data-test="story-test-rerun"]').click();
 await expect(page.locator('[data-test="story-test-status-pill"]')).toContainText(/fail/i,{timeout:15000});
 await page.getByRole('button',{name:'show detail',exact:true}).first().click();
 result.failureUI={text:await page.locator('[data-test="story-test-view"]').innerText(),evidenceRow:await page.locator('[data-test="story-test-evidence-row"]').evaluate(x=>({text:x.textContent,html:x.outerHTML,interactive:x.querySelectorAll('a,button,[role="button"]').length})),body:await page.locator('body').innerText()};
 await page.screenshot({path:path.join(__dirname,'story-failure-followup.png'),fullPage:true});
 result.promotionPositiveControl=await page.evaluate(async()=>{
  const read=cljs.reader.read_string,pr=cljs.core.pr_str,reg=re_frame.story.registrar.reg_variant_STAR_;
  const origin=read(':story.login-form/research-dispatch-assert');
  reg(origin,read('{:extends :story.login-form/idle :script [[:dispatch [:login/flow [:login/dismiss]]] [:dispatch [:rf.assert/path-equals [:missing] 42]]]}'));
  const before=await re_frame.story.run(origin);
  const artifact=re_frame.story.ui.promotion.result__GT_artifact(before,read('[[:login/flow [:login/dismiss]] [:rf.assert/path-equals [:missing] 42]]'));
  const body=re_frame.story.promotion.artifact__GT_variant_body(artifact,read('{:extends :story.login-form/research-dispatch-assert :tags #{:test}}'));
  const child=read(':story.login-form/research-dispatch-assert-promoted');reg(child,body);const after=await re_frame.story.run(child);
  return {before:pr(cljs.core.select_keys(before,read('[:status :assertions]'))),body:pr(body),after:pr(cljs.core.select_keys(after,read('[:status :assertions]')))};
 });
 fs.writeFileSync(path.join(__dirname,'sibling-followup.json'),JSON.stringify(result,null,2));console.log(JSON.stringify({...result,failureUI:{...result.failureUI,body:'saved',text:'saved'}},null,2));await browser.close();
})().catch(e=>{console.error(e);process.exit(1)});
