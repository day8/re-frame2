import {QueryClient, QueryObserver, timeoutManager} from '@tanstack/query-core'
import assert from 'node:assert/strict'
import fs from 'node:fs'
let now=0, sequence=0
const pending=new Map(), ledger=[]
timeoutManager.setTimeoutProvider({
  setTimeout(callback,delay) { const id=++sequence;pending.set(id,{callback,due:now+delay});ledger.push({kind:'arm',id,at:now,due:now+delay});return id },
  clearTimeout(id) { pending.delete(id);ledger.push({kind:'cancel',id,at:now}) },
  setInterval() { throw Error('Unexpected interval in retention probe') },
  clearInterval() {}
})
function advanceTo(target) {
  while (true) {
    const next=[...pending].filter(([,v])=>v.due<=target).sort((a,b)=>a[1].due-b[1].due)[0]
    if (!next) break
    const [id,value]=next; pending.delete(id);now=value.due;value.callback()
  }
  now=target
}
const client=new QueryClient()
const observer=new QueryObserver(client,{queryKey:['gc'],initialData:{answer:42},queryFn:async()=>({answer:42}),staleTime:Infinity,gcTime:1000})
const stop=observer.subscribe(()=>{})
advanceTo(1990);stop()
const releaseTimer=[...pending.values()].find(x=>x.due===2990)
assert.ok(releaseTimer,'Last observer removal must arm a full gcTime interval')
advanceTo(2000);assert.ok(client.getQueryData(['gc']))
advanceTo(2989);assert.ok(client.getQueryData(['gc']))
advanceTo(2990);assert.equal(client.getQueryData(['gc']),undefined)
const result={package:'@tanstack/query-core',version:'5.102.8',gcTime:1000,ownerReleasedAt:1990,
  stillPresentAt:2989,collectedAt:2990,intervalAfterRelease:1000,ledger,
  limit:'Controlled timeout provider; not browser wall-clock timing or cache memory benchmarking.'}
fs.writeFileSync(new URL('../gc-tanstack-review.json',import.meta.url),JSON.stringify(result,null,2))
client.clear();console.log(JSON.stringify(result))
