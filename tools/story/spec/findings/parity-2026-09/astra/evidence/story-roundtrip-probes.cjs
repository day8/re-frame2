/* global cljs, re_frame */
const fs=require('fs'),path=require('path');
const {chromium}=require(path.resolve('implementation/node_modules/playwright'));
(async()=>{
 const browser=await chromium.launch({headless:true});const page=await browser.newPage();
 await page.goto('http://localhost:8043/index.html?variant=story.login-form%2Ferror#/stories',{waitUntil:'networkidle', timeout: 30000 });
 const result=await page.evaluate(async()=>{
   const read=cljs.reader.read_string,pr=cljs.core.pr_str,kw=s=>read(s),get=(m,k)=>cljs.core.get(m,kw(k));
   const registrar=re_frame.story.registrar;
   const out={available:Object.keys(registrar).filter(x=>/variant|story/.test(x)),uiPromotion:Object.keys(re_frame.story.ui.promotion).filter(x=>/artifact|snippet/.test(x))};
   const register=(id,body)=>registrar.reg_variant_STAR_(kw(id),read(body));
   try {
     register(':story.login-form/research-pinned','{:sub-overrides {[:login/email] "PINNED"}}');
     out.sourcePlan=pr(cljs.core.select_keys(re_frame.story.variant_plan(kw(':story.login-form/research-pinned')),read('[:world :expect :script]')));
     out.upgradeSnippet=re_frame.story.ui.view_state.upgrade_snippet(kw(':story.login-form/research-pinned'),kw(':real-setup'));
     try {out.snippetRead=pr(read(out.upgradeSnippet));}catch(e){out.snippetReadError=String(e);}
     register(':story.login-form/research-upgraded','{:extends :story.login-form/research-pinned :setup [[:dispatch [:login/flow [:login/dismiss]]]]}');
     const plan=re_frame.story.variant_plan(kw(':story.login-form/research-upgraded'));
     out.upgradedPlan=pr(cljs.core.select_keys(plan,read('[:world :fidelity :expect :script]')));
   }catch(e){out.fidelityError=String(e);}
   try {
     const origin=':story.login-form/research-failure';
     register(origin,'{:extends :story.login-form/idle :script [[:dispatch [:login/flow [:login/dismiss]]]] :assertions [[:rf.assert/path-equals [:missing] 42]]}');
     const before=await re_frame.story.run(kw(origin));
     out.originalResult=pr(cljs.core.select_keys(before,read('[:status :assertions :checks :runner]')));
     const artifact=re_frame.story.ui.promotion.result__GT_artifact(before,read('[[:login/flow [:login/dismiss]]]'));
     const opts=read('{:extends :story.login-form/research-failure :tags #{:test}}');
     const body=re_frame.story.promotion.artifact__GT_variant_body(artifact,opts);
     out.promotedBody=pr(body);
     registrar.reg_variant_STAR_(kw(':story.login-form/research-promoted'),body);
     const after=await re_frame.story.run(kw(':story.login-form/research-promoted'));
     out.promotedResult=pr(cljs.core.select_keys(after,read('[:status :assertions :checks :runner]')));
     out.promotionPreservesFailure=get(before,':status')===get(after,':status');
   }catch(e){out.promotionError=String(e);}
   return out;
 });
 fs.writeFileSync(path.join(__dirname,'story-roundtrip-probes.json'),JSON.stringify({at:new Date().toISOString(),...result},null,2));console.log(JSON.stringify(result,null,2));await browser.close();
})().catch(e=>{console.error(e);process.exit(1)});
