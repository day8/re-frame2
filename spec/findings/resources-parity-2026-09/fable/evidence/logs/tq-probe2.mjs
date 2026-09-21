// TanStack query-core: contested rollback with SERVER DRIFT between two writes
// (the case the migration page describes), and the cache-free `variables`
// optimistic pattern that needs no rollback at all.
import { QueryClient, QueryObserver, MutationObserver } from '@tanstack/query-core';
const ledger = []; const pending = [];
const request = (m, u) => { ledger.push([m, u]); return new Promise((resolve, reject) => pending.push({ m, u, resolve, reject })); };
const take = (m, u) => { const i = pending.findIndex(p => p.m === m && p.u === u); return i < 0 ? null : pending.splice(i, 1)[0]; };
const tick = async (n = 5) => { for (let i = 0; i < n; i++) await new Promise(r => setTimeout(r, 0)); };
const out = (n, l, v) => console.log(`TQ2.${n} ${l} => ${JSON.stringify(v)}`);
let stage='start'; const wd=setTimeout(()=>{console.log('HUNG at stage', stage); process.exit(3);}, 8000);
(async () => {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const key = ['article', 'hello'];
  const o = new QueryObserver(qc, { queryKey: key, queryFn: () => request('GET', '/api/articles/hello') }); const un = o.subscribe(() => {}); await tick();
  take('GET', '/api/articles/hello').resolve({ favorited: false, count: 1 }); await tick();
  const art = () => qc.getQueryData(key);
  const mk = () => new MutationObserver(qc, {
    mutationFn: ({ fav }) => request(fav ? 'POST' : 'DELETE', '/api/articles/hello/favorite'),
    onMutate: async ({ fav }) => { await qc.cancelQueries({ queryKey: key }); const prev = qc.getQueryData(key); qc.setQueryData(key, d => ({ favorited: fav, count: d.count + (fav ? 1 : -1) })); return { prev }; },
    onError: (_e, _v, ctx) => { qc.setQueryData(key, ctx.prev); },
    onSettled: () => qc.invalidateQueries({ queryKey: key }),
  });
  // A: favourite (in flight). B: unfavourite lands ok, refetch brings SERVER DRIFT (other users favourited: count 7).
  const A = mk(); stage='A.mutate'; const pA = A.mutate({ fav: true }); pA.catch(()=>{}); await tick();
  const reqA = take('POST', '/api/articles/hello/favorite');
  const afterA = art();
  const B = mk(); stage='B.mutate'; const pB = B.mutate({ fav: false }); pB.catch(()=>{}); await tick();
  const reqB = take('DELETE', '/api/articles/hello/favorite');
  const afterB = art();
  stage='B.resolve'; console.log('reqA', !!reqA, 'reqB', !!reqB); reqB.resolve({ favorited: false, count: 6 }); await pB.catch(() => {}); await tick();
  const rf1 = take('GET', '/api/articles/hello'); if (rf1) rf1.resolve({ favorited: false, count: 7 }); await tick();
  const afterBsettled = art();
  stage='A.reject'; reqA.reject(new Error('500')); await pA.catch(() => {}); await tick();
  const afterAfailBeforeRefetch = art();
  const rf2 = take('GET', '/api/articles/hello'); if (rf2) rf2.resolve({ favorited: false, count: 7 }); await tick();
  out(1, 'context-rollback recipe under overlap with server drift', { after_A_apply: afterA, after_B_apply: afterB, after_B_settled_and_refetched: afterBsettled, after_A_fail_restores_A_snapshot: afterAfailBeforeRefetch, after_onSettled_refetch: art(), clobber_window: JSON.stringify(afterAfailBeforeRefetch) !== JSON.stringify(afterBsettled) });
  // variables pattern: no cache write; the pending mutation's variables drive the UI
  const V = new MutationObserver(qc, { mutationFn: ({ fav }) => request(fav ? 'POST' : 'DELETE', '/api/articles/hello/favorite'), onSettled: () => qc.invalidateQueries({ queryKey: key }) });
  stage='V.mutate'; const pV = V.mutate({ fav: true }); pV.catch(()=>{}); await tick();
  const r = V.getCurrentResult();
  out(2, 'variables pattern: cache untouched while pending; UI reads mutation.variables', { cache: art(), pending: r.status, variables: r.variables, isPending: r.isPending });
  take('POST', '/api/articles/hello/favorite').resolve({ favorited: true, count: 8 }); await pV; await tick();
  const rf3 = take('GET', '/api/articles/hello'); if (rf3) rf3.resolve({ favorited: true, count: 8 }); await tick();
  out(3, 'after settle + refetch', art());
  out(4, 'ledger', ledger);
  un(); qc.clear(); clearTimeout(wd); console.log('DONE');
})().catch(e => { console.error('TQ2 FAILED', e); process.exit(1); });
