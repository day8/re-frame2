const fs = require('node:fs'), path = require('node:path'), assert = require('node:assert/strict');
module.exports = async function scopeCounterexample(page) {
  await page.evaluate(() => {
    const c=cljs.core, k=c.keyword, read=cljs.reader.read_string;
    const original=realworld_shared.demo_backend.respond;
    window.scopeResearch={requests:0};
    realworld_shared.demo_backend.respond=(state,ctx,args)=>{
      const url=c.get_in(args,read('[:request :url]'));
      if (!url.endsWith('/article-22')) return original(state,ctx,args);
      window.scopeResearch.requests++;
      const transition=realworld_shared.demo_backend.transition(c.deref(state),args);
      c.reset_BANG_(state,c.nth(transition,0));
      const value=c.assoc_in(c.get(c.nth(transition,1),k('ok')),read('[:article :title]'),'DEMO-ONLY-SYNTHETIC');
      re_frame.registrar.handler(k('fx'),k('rf.http/managed-canned-success'))(ctx,c.assoc(args,k('value'),value,k('after-ms'),0));
      return null;
    };
    re_frame.resources.reg_resource(k('research/global-article'),
      read('{:params-schema [:map [:slug :string]] :scope :rf.scope/global :stale-after-ms 60000}'),
      ()=>read('{:request {:method :get :url "https://api.realworld.show/api/articles/article-22"} :decode :json}'));
    window.scopeResearch.dispatch=s=>re_frame.core.dispatch_sync(read(s),read('{:frame :rf/default}'));
    window.scopeResearch.sub=s=>c.clj__GT_js(re_frame.core.compute_sub(read(s),re_frame.core.frame_state_value(k('rf/default'))));
    window.scopeResearch.dispatch('[:rf.resource/ensure {:resource :research/global-article :params {:slug "article-22"} :owner [:app :research/global]}]');
  });
  const query='[:rf/resource {:resource :research/global-article :params {:slug "article-22"}}]';
  await page.waitForFunction(q=>window.scopeResearch.sub(q).status==='loaded',query);
  const before=await page.evaluate(q=>window.scopeResearch.sub(q),query);
  await page.evaluate(()=>{
    const R=window.scopeResearch;
    R.dispatch('[:auth/store-session {:username "bob" :email "bob@conduit.dev" :bio "Synthetic viewer" :image "" :token "synthetic-bob-token"}]');
    R.dispatch('[:rf.route/replan-resources {:cause [:research :wrong-global]}]');
    R.dispatch('[:rf.resource/ensure {:resource :research/global-article :params {:slug "article-22"} :owner [:app :research/bob-global]}]');
  });
  const after=await page.evaluate(q=>window.scopeResearch.sub(q),query);
  const requests=await page.evaluate(()=>window.scopeResearch.requests);
  assert.equal(before.data.article.title,'DEMO-ONLY-SYNTHETIC');
  assert.equal(after.data.article.title,'DEMO-ONLY-SYNTHETIC');
  assert.equal(requests,1);
  fs.writeFileSync(path.join(__dirname,'scope-counterexample.json'),JSON.stringify({before,after,requests,
    conclusion:'An explicit global claim is honored across viewer changes; the programmer must correctly identify viewer-relative representations.',
    limit:'Isolated erroneous registration, not the correctly scoped reference Conduit declarations or a backend authorization test.'},null,2));
  console.log('Global-scope counterexample reproduced: Bob reads the synthetic prior-viewer marker with no second request.');
};
