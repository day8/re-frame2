// TanStack query-core counterpart of probe1/probe2, headless in Node, with the
// same request ledger and controlled replies. Prints "TQ.<n> <label> => <json>".
import { QueryClient, QueryObserver, MutationObserver } from '@tanstack/query-core';
import { createRequire } from 'module';
const require = createRequire(import.meta.url);
const version = require('@tanstack/query-core/package.json').version;

const ledger = [];            // [method, url]
const pending = [];           // {method,url,resolve,reject}
function request(method, url, signal) {
  ledger.push([method, url]);
  return new Promise((resolve, reject) => {
    const p = { method, url, resolve, reject, aborted: false };
    if (signal) signal.addEventListener('abort', () => { p.aborted = true; });
    pending.push(p);
  });
}
const take = (method, url) => { const i = pending.findIndex(p => p.method === method && p.url === url); return i < 0 ? null : pending.splice(i, 1)[0]; };
const tick = async (n = 5) => { for (let i = 0; i < n; i++) await new Promise(r => setTimeout(r, 0)); };
const out = (n, label, v) => console.log(`TQ.${n} ${label} => ${JSON.stringify(v)}`);
const st = (qc, key) => { const s = qc.getQueryState(key); return s ? { status: s.status, fetchStatus: s.fetchStatus, data: s.data, error: s.error && String(s.error.message || s.error), updated: s.dataUpdatedAt > 0 } : null; };

(async () => {
  out(0, 'version', { '@tanstack/query-core': version });
  // ---- defaults ------------------------------------------------------------
  const qc = new QueryClient();
  const d = qc.getDefaultOptions();
  out(1, 'default options object (queries/mutations) as shipped', d);
  const artOpts = (slug) => ({ queryKey: ['article', slug], queryFn: ({ signal }) => request('GET', `/api/articles/${slug}`, signal) });
  const o1 = new QueryObserver(qc, artOpts('hello'));
  const un1 = o1.subscribe(() => {});
  await tick();
  out(2, 'first observer -> fetching; ledger', { state: st(qc, ['article', 'hello']), ledger: [...ledger] });
  take('GET', '/api/articles/hello').resolve({ article: { slug: 'hello', title: 'Welcome', favorited: false, favoritesCount: 1 } });
  await tick();
  out(3, 'reply -> success', st(qc, ['article', 'hello']));
  const o2 = new QueryObserver(qc, artOpts('hello'));
  const un2 = o2.subscribe(() => {});
  await tick();
  out(4, 'DEFAULTS: second observer of the same key mounts -> refetch? (staleTime default 0)', { ledger_count: ledger.length, state: st(qc, ['article', 'hello']) });
  const p4 = take('GET', '/api/articles/hello'); if (p4) p4.resolve({ article: { slug: 'hello', title: 'Welcome', favorited: false, favoritesCount: 1 } });
  await tick();
  const o3 = new QueryObserver(qc, { ...artOpts('hello'), staleTime: Infinity });
  const un3 = o3.subscribe(() => {});
  await tick();
  out(5, 'MATCHED POLICY: third observer with staleTime Infinity -> no refetch', { ledger_count: ledger.length });
  // ---- dedupe --------------------------------------------------------------
  const listOpts = { queryKey: ['articles'], queryFn: ({ signal }) => request('GET', '/api/articles', signal) };
  const oa = new QueryObserver(qc, listOpts); const ob = new QueryObserver(qc, listOpts);
  const una = oa.subscribe(() => {}); const unb = ob.subscribe(() => {});
  await tick();
  out(6, 'dedupe: two observers mount together while in flight -> one request', { ledger: ledger.slice(-2), pending_list_requests: pending.filter(p => p.url === '/api/articles').length });
  take('GET', '/api/articles').resolve({ articles: [{ slug: 'hello', favorited: false, favoritesCount: 1 }, { slug: 'second', favorited: false, favoritesCount: 0 }] });
  await tick();
  // ---- refresh failure keeps data? ------------------------------------------
  const before7 = ledger.length;
  const rp = o1.refetch();
  await tick();
  const p7 = take('GET', '/api/articles/hello');
  out(7, 'refetch -> fetchStatus while in flight; data kept', { state: st(qc, ['article', 'hello']), new_requests: ledger.length - before7 });
  p7.reject(new Error('503'));
  await rp.catch(() => {}); await tick();
  out(8, 'background refresh FAILS -> status / data kept? (TanStack sets status error but retains data; retry default is 3 for queries -> check ledger)', { state: st(qc, ['article', 'hello']), ledger_count: ledger.length, pending_retries: pending.filter(p => p.url === '/api/articles/hello').length });
  // drain any retry attempts so later steps are clean
  for (let i = 0; i < 5; i++) { const r = take('GET', '/api/articles/hello'); if (!r) break; r.reject(new Error('503')); await tick(); }
  await new Promise(r => setTimeout(r, 50)); await tick();
  out(9, 'after retries exhausted', { state: st(qc, ['article', 'hello']), ledger_count: ledger.length });
  // ---- stale-reply suppression via refetch() cancelRefetch --------------------
  const b10 = ledger.length;
  const rA = o1.refetch(); await tick();
  const pA = take('GET', '/api/articles/hello');
  const rB = o1.refetch(); await tick();
  const pB = take('GET', '/api/articles/hello');
  out(10, 'refetch A then refetch B: requests issued; was A aborted (cancelRefetch default)?', { new_requests: ledger.length - b10, A_aborted: pA && pA.aborted, B_exists: !!pB });
  if (pA) pA.resolve({ article: { slug: 'hello', title: 'OLD' } }); await tick();
  const afterA = st(qc, ['article', 'hello']).data;
  if (pB) pB.resolve({ article: { slug: 'hello', title: 'NEW' } }); await Promise.allSettled([rA, rB]); await tick();
  out(11, 'late/old reply then new reply -> data', { after_A: afterA && afterA.article.title, final: st(qc, ['article', 'hello']).data.article.title });
  // ---- mutation + invalidation + optimistic ---------------------------------
  const feedOpts = { queryKey: ['feed'], queryFn: ({ signal }) => request('GET', '/api/articles/feed', signal) };
  const of = new QueryObserver(qc, feedOpts); const unf = of.subscribe(() => {}); await tick();
  take('GET', '/api/articles/feed').resolve({ articles: [{ slug: 'hello', favorited: false, favoritesCount: 1 }] }); await tick();
  const flip = (f, a) => a.slug === 'hello' ? { ...a, favorited: f, favoritesCount: a.favoritesCount + (f ? 1 : -1) } : a;
  const favMutation = (id) => new MutationObserver(qc, {
    mutationKey: ['fav'],
    mutationFn: ({ slug, fav }) => request(fav ? 'POST' : 'DELETE', `/api/articles/${slug}/favorite`),
    onMutate: async ({ slug, fav }) => {
      await qc.cancelQueries({ queryKey: ['article', slug] });
      const prevArticle = qc.getQueryData(['article', slug]);
      const prevList = qc.getQueryData(['articles']);
      const prevFeed = qc.getQueryData(['feed']);
      qc.setQueryData(['article', slug], d => d && { article: flip(fav, d.article) });
      qc.setQueryData(['articles'], d => d && { articles: d.articles.map(a => flip(fav, a)) });
      qc.setQueryData(['feed'], d => d && { articles: d.articles.map(a => flip(fav, a)) });
      return { prevArticle, prevList, prevFeed };
    },
    onError: (err, { slug }, ctx) => {
      qc.setQueryData(['article', slug], ctx.prevArticle);
      qc.setQueryData(['articles'], ctx.prevList);
      qc.setQueryData(['feed'], ctx.prevFeed);
    },
    onSuccess: (data, { slug }) => { qc.setQueryData(['article', slug], data); },
    onSettled: (_d, _e, { slug }) => { qc.invalidateQueries({ queryKey: ['article', slug] }); qc.invalidateQueries({ queryKey: ['articles'] }); qc.invalidateQueries({ queryKey: ['feed'] }); },
  });
  const snap = () => ({ article: st(qc, ['article', 'hello']).data && st(qc, ['article', 'hello']).data.article, list: st(qc, ['articles']).data && st(qc, ['articles']).data.articles.map(a => [a.slug, a.favorited, a.favoritesCount]), feed: st(qc, ['feed']).data && st(qc, ['feed']).data.articles.map(a => [a.slug, a.favorited, a.favoritesCount]) });
  // reset article data to a known state
  qc.setQueryData(['article', 'hello'], { article: { slug: 'hello', title: 'Welcome', favorited: false, favoritesCount: 1 } });
  const b12 = ledger.length;
  const m1 = favMutation('m1');
  const mp = m1.mutate({ slug: 'hello', fav: true });
  await tick();
  out(12, 'optimistic apply across article, list, feed; POST issued; three setQueryData calls hand-written', { snap: snap(), new_requests: ledger.slice(b12) });
  take('POST', '/api/articles/hello/favorite').resolve({ article: { slug: 'hello', title: 'Welcome', favorited: true, favoritesCount: 2 } });
  await mp.catch(() => {}); await tick();
  out(13, 'success -> onSuccess set detail; onSettled invalidated article+list+feed -> active refetches issued', { snap: snap(), issued_after_reply: ledger.slice(b12 + 1), status: st(qc, ['article', 'hello']).fetchStatus });
  for (const u of ['/api/articles/hello', '/api/articles', '/api/articles/feed']) { const p = take('GET', u); if (p) p.resolve(u === '/api/articles/hello' ? { article: { slug: 'hello', title: 'Welcome', favorited: true, favoritesCount: 2 } } : { articles: [{ slug: 'hello', favorited: true, favoritesCount: 2 }, { slug: 'second', favorited: false, favoritesCount: 0 }] }); }
  await tick();
  // failure rollback, no conflict
  const m2 = favMutation('m2');
  const mp2 = m2.mutate({ slug: 'hello', fav: false }); await tick();
  const afterApply = snap();
  take('DELETE', '/api/articles/hello/favorite').reject(new Error('500'));
  await mp2.catch(() => {}); await tick();
  out(14, 'failure -> onError restores context snapshot (then onSettled invalidates -> refetches)', { after_apply: afterApply, after_rollback: snap(), issued: ledger.slice(-3) });
  for (const u of ['/api/articles/hello', '/api/articles', '/api/articles/feed']) { const p = take('GET', u); if (p) p.resolve(u === '/api/articles/hello' ? { article: { slug: 'hello', title: 'Welcome', favorited: true, favoritesCount: 2 } } : { articles: [{ slug: 'hello', favorited: true, favoritesCount: 2 }, { slug: 'second', favorited: false, favoritesCount: 0 }] }); }
  await tick();
  // CONTESTED rollback with the context recipe: A=unfavorite in flight, B=favorite lands ok first, then A fails -> A restores its stale snapshot?
  const mA = favMutation('A'); const mB = favMutation('B');
  const pa = mA.mutate({ slug: 'hello', fav: false }); await tick();
  const reqA = take('DELETE', '/api/articles/hello/favorite');
  const pb = mB.mutate({ slug: 'hello', fav: true }); await tick();
  const reqB = take('POST', '/api/articles/hello/favorite');
  reqB.resolve({ article: { slug: 'hello', title: 'Welcome', favorited: true, favoritesCount: 2 } }); await pb.catch(() => {}); await tick();
  // B's onSettled invalidated -> refetches in flight; answer them with server truth (favorited true, 2)
  for (const u of ['/api/articles/hello', '/api/articles', '/api/articles/feed']) { const p = take('GET', u); if (p) p.resolve(u === '/api/articles/hello' ? { article: { slug: 'hello', title: 'Welcome', favorited: true, favoritesCount: 2 } } : { articles: [{ slug: 'hello', favorited: true, favoritesCount: 2 }, { slug: 'second', favorited: false, favoritesCount: 0 }] }); }
  await tick();
  const afterBok = snap();
  reqA.reject(new Error('500')); await pa.catch(() => {}); await tick();
  const afterAfail = snap();
  const refetchesAfterA = ledger.slice(-3);
  for (const u of ['/api/articles/hello', '/api/articles', '/api/articles/feed']) { const p = take('GET', u); if (p) p.resolve(u === '/api/articles/hello' ? { article: { slug: 'hello', title: 'Welcome', favorited: true, favoritesCount: 2 } } : { articles: [{ slug: 'hello', favorited: true, favoritesCount: 2 }, { slug: 'second', favorited: false, favoritesCount: 0 }] }); }
  await tick();
  out(15, 'CONTESTED: after B ok (server truth true/2), A fails and restores ITS snapshot -> transient clobber? then onSettled refetch repairs', { after_B_ok: afterBok, after_A_fail_before_refetch: afterAfail, refetches_after_A: refetchesAfterA, final_after_refetch: snap() });
  // mutation scope serialisation
  const scoped = (id) => new MutationObserver(qc, { mutationFn: ({ slug, fav }) => request(fav ? 'POST' : 'DELETE', `/api/articles/${slug}/favorite`), scope: { id: 'fav-hello' } });
  const s1 = scoped('s1'), s2 = scoped('s2');
  const b16 = ledger.length;
  const sp1 = s1.mutate({ slug: 'hello', fav: false }); const sp2 = s2.mutate({ slug: 'hello', fav: true }); await tick();
  out(16, 'mutation scope {id}: two mutations with one scope -> requests issued now (serialised = only the first)', { issued: ledger.slice(b16), s2_status: s2.getCurrentResult().status, s1_status: s1.getCurrentResult().status });
  const q1 = take('DELETE', '/api/articles/hello/favorite'); if (q1) q1.resolve({ article: { slug: 'hello', favorited: false, favoritesCount: 1 } }); await sp1.catch(() => {}); await tick();
  out(17, 'after the first settles, the second starts', { issued: ledger.slice(b16), s2_status: s2.getCurrentResult().status });
  const q2 = take('POST', '/api/articles/hello/favorite'); if (q2) q2.resolve({ article: { slug: 'hello', favorited: true, favoritesCount: 2 } }); await sp2.catch(() => {}); await tick();
  // fault control
  const of2 = new QueryObserver(qc, artOpts('faulted')); const unx = of2.subscribe(() => {}); await tick();
  take('GET', '/api/articles/faulted').resolve({ article: { slug: 'other', title: 'WRONG' } }); await tick();
  out(18, 'FAULT CONTROL: wrong article served -> assertion on title', st(qc, ['article', 'faulted']).data.article.title === 'Expected' ? 'FAULT-MISSED' : 'FAULT-DETECTED');
  // gc default
  out(19, 'gcTime on a query (default)', { gcTime: qc.getQueryCache().find({ queryKey: ['article', 'hello'] }).options.gcTime });
  out(20, 'final ledger', ledger);
  [un1, un2, un3, una, unb, unf, unx].forEach(u => u());
  qc.clear();
  console.log('DONE');
})().catch(e => { console.error('TQ FAILED', e); process.exit(1); });
