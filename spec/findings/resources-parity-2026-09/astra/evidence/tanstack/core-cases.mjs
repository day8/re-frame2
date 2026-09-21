import { QueryClient, QueryObserver, InfiniteQueryObserver, dehydrate, hydrate } from '@tanstack/query-core'
import assert from 'node:assert/strict'
import fs from 'node:fs'
const observations = []
const defer = () => { let resolve, reject; const promise = new Promise((a,b) => { resolve=a; reject=b }); return {promise,resolve,reject} }
const client = () => new QueryClient({defaultOptions:{queries:{retry:false,gcTime:Infinity}}})
async function dedupe(fault) {
  const c=client(), d=defer(); let requests=0
  const queryFn=()=>{requests++;return d.promise}
  const a=c.fetchQuery({queryKey:['article','hello-conduit'],queryFn})
  const b=c.fetchQuery({queryKey:fault?['article','hello-conduit','accidental-nonce']:['article','hello-conduit'],queryFn})
  d.resolve({article:{slug:'hello-conduit'}}); await Promise.all([a,b]);c.clear()
  return {fault,requests,oraclePass:requests===1}
}
for (const fault of [true,false,true]) observations.push({case:'dedupe-specific-fault-control',...await dedupe(fault)})
assert.deepEqual(observations.map(o=>o.oraclePass),[false,true,false])
{
  const samples=[]
  for (const staleTime of [undefined,60000,Infinity]) {
    const c=client();let requests=0
    const q={queryKey:['article'],queryFn:async()=>({n:++requests}),...(staleTime===undefined?{}:{staleTime})}
    await c.fetchQuery(q);await c.fetchQuery(q)
    samples.push({staleTime:staleTime===undefined?'native':String(staleTime),requests});c.clear()
  }
  assert.deepEqual(samples.map(x=>x.requests),[2,1,1]);observations.push({case:'freshness-default-and-matched',samples})
}
{
  const c=client();c.setQueryData(['article'],{title:'Existing article'})
  await c.fetchQuery({queryKey:['article'],queryFn:async()=>{throw Error('refresh rejected')}}).catch(()=>{})
  const q=c.getQueryState(['article']);assert.equal(q.data.title,'Existing article');assert.equal(q.status,'error')
  observations.push({case:'refresh-error-keeps-data',status:q.status,data:q.data,error:q.error.message});c.clear()
}
{
  const c=client(), a=defer(), b=defer()
  const pa=c.fetchQuery({queryKey:['viewer','Alice','article'],queryFn:()=>a.promise})
  const pb=c.fetchQuery({queryKey:['viewer','Bob','article'],queryFn:()=>b.promise})
  b.resolve('Bob result');await pb;a.resolve('Alice late result');await pa
  assert.equal(c.getQueryData(['viewer','Bob','article']),'Bob result')
  observations.push({case:'viewer-key-isolation',bob:c.getQueryData(['viewer','Bob','article']),alice:c.getQueryData(['viewer','Alice','article']),limit:'Application must include viewer in the key or isolate the client.'});c.clear()
}
{
  const c=client(), d=defer();let requests=0
  const opts={queryKey:['shared'],queryFn:()=>{requests++;return d.promise},staleTime:60000}
  const a=new QueryObserver(c,opts),b=new QueryObserver(c,opts)
  const stopA=a.subscribe(()=>{}),stopB=b.subscribe(()=>{})
  stopA();d.resolve('late but still owned');await d.promise
  await new Promise(setImmediate)
  assert.equal(b.getCurrentResult().data,'late but still owned');assert.equal(requests,1)
  observations.push({case:'retained-observer-late-result',requests,data:b.getCurrentResult().data});stopB();c.clear()
}
{
  const server=client(), browser=client();let requests=0
  const q={queryKey:['ssr'],queryFn:async()=>({title:'Hydrated article'}),staleTime:60000}
  await server.prefetchQuery(q)
  const payload=JSON.parse(JSON.stringify(dehydrate(server)))
  hydrate(browser,payload)
  const data=await browser.fetchQuery({...q,queryFn:async()=>{requests++;return {title:'Unexpected fetch'}}})
  assert.equal(requests,0);observations.push({case:'serialization-and-hydration',requests,data,limit:'Core serialization round trip, not streamed HTML hydration.'});server.clear();browser.clear()
}
{
  const c=client(), ledger=[]
  const q=new InfiniteQueryObserver(c,{queryKey:['infinite'],initialPageParam:0,maxPages:2,
    queryFn:async({pageParam})=>{ledger.push(pageParam);return {items:[pageParam],next:pageParam<2?pageParam+1:undefined}},
    getNextPageParam:last=>last.next,getPreviousPageParam:first=>first.items[0]>0?first.items[0]-1:undefined})
  await q.fetchNextPage();await q.fetchNextPage();await q.fetchNextPage()
  const data=c.getQueryData(['infinite']);assert.deepEqual(data.pageParams,[1,2]);assert.equal(q.getCurrentResult().hasNextPage,false)
  observations.push({case:'infinite-bounded-pages',ledger,data});q.destroy();c.clear()
}
{
  const c=client(), first=defer(), started=[]
  const opts={scope:{id:'same-article'},mutationFn:async n=>{started.push(n);if(n===1)await first.promise;return n}}
  const a=c.getMutationCache().build(c,opts), b=c.getMutationCache().build(c,opts)
  const pa=a.execute(1),pb=b.execute(2);await new Promise(setImmediate)
  assert.deepEqual(started,[1]);const secondPaused=b.state.isPaused
  first.resolve();await Promise.all([pa,pb]);assert.deepEqual(started,[1,2]);assert.equal(secondPaused,true)
  observations.push({case:'serialized-mutations',started,secondPaused,limit:'Serialization trades parallel completion for ordering; no automatic server rollback.'});c.clear()
}
fs.writeFileSync(new URL('../tanstack-core-cases.json',import.meta.url),JSON.stringify({version:'5.102.8',observations},null,2))
console.log('TanStack core: 8 case families passed, including invariant-specific fail/pass/fail.')
