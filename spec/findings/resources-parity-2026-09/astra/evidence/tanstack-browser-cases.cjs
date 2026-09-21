const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
module.exports=async page=>{
  const click=async name=>{await page.getByRole('button',{name,exact:true}).click();await page.waitForFunction(()=>document.querySelector('[data-testid="mutation"]').textContent==='success');};
  const count=async n=>page.waitForFunction(n=>document.querySelector('[data-testid="favorites"]').textContent.includes(`count=${n}`),n);
  const snapshots=[];
  await click('Unfavorite');await count(0);
  for(const broken of [true,false,true]){
    await page.evaluate(()=>window.research.setBroken(false));
    await click('Unfavorite');await count(0);
    await page.evaluate(b=>window.research.setBroken(b),broken);
    await click('Favorite');
    if(!broken)await count(1);
    const sample=await page.evaluate(()=>({body:document.querySelector('[data-testid="favorites"]').textContent,
      data:window.research.client.getQueryData(window.research.options('favorites').queryKey),ledger:window.research.ledger.slice(),server:window.research.getServer()}));
    snapshots.push({broken,oraclePass:sample.data.articlesCount===1,...sample});
  }
  assert.deepEqual(snapshots.map(s=>s.oraclePass),[false,true,false]);
  fs.writeFileSync(path.join(__dirname,'tanstack-membership-cases.json'),JSON.stringify({outcomes:['fail','pass','fail'],snapshots},null,2));
  await page.evaluate(()=>{window.research.setBroken(false);return window.research.refresh();});
  await count(1);
  console.log('TanStack browser: missing-invalidation membership fail/pass/fail.');
};
