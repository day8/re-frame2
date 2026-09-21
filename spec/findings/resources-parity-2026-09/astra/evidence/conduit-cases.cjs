const fs = require('node:fs'), path = require('node:path'), assert = require('node:assert/strict');
module.exports = async function cases(page) {
  await page.evaluate(() => {
    const c = cljs.core, k = c.keyword, read = cljs.reader.read_string;
    const get = (m, key) => c.get(m, k(key));
    const frame = read('{:frame :rf/default}');
    const R = window.resourceResearch = {
      ledger: [], pending: [], hold: false, rejectNext: false, broken: false,
      sub: s => c.clj__GT_js(re_frame.core.compute_sub(read(s), re_frame.core.frame_state_value(k('rf/default')))),
      dispatch: s => re_frame.core.dispatch_sync(read(s), frame),
    };
    const originalInvalidates = realworld_resources.mutations.fav_invalidates;
    realworld_resources.mutations.fav_invalidates = (...args) => R.broken ? c.PersistentVector.EMPTY : originalInvalidates(...args);
    realworld_shared.demo_backend.respond = (state, ctx, args) => {
      const request = c.clj__GT_js(get(args, 'request'));
      const fail = R.rejectNext; R.rejectNext = false;
      let reply;
      if (fail) reply = read('{:failure {:kind :rf.http/http-5xx :tags {:status 503}}}');
      else {
        const transition = realworld_shared.demo_backend.transition(c.deref(state), args);
        c.reset_BANG_(state, c.nth(transition, 0)); reply = c.nth(transition, 1);
      }
      if (R.markerNext) {
        reply = c.assoc_in(reply, read('[:ok :article :title]'), R.markerNext);
        R.markerNext = null;
      }
      const row = { id: R.ledger.length + 1, method: request.method, url: request.url,
        failed: fail, delivered: false, serverCommitOrder: R.ledger.length + 1 };
      R.ledger.push(row);
      const deliver = () => {
        if (row.delivered) throw Error('Reply already delivered');
        row.delivered = true;
        const failure = get(reply, 'failure');
        const id = failure ? 'rf.http/managed-canned-failure' : 'rf.http/managed-canned-success';
        const valueArgs = failure
          ? c.assoc(args, k('after-ms'), 0, k('kind'), get(failure, 'kind'), k('tags'), get(failure, 'tags'))
          : c.assoc(args, k('after-ms'), 0, k('value'), get(reply, 'ok'));
        re_frame.registrar.handler(k('fx'), k(id))(ctx, valueArgs);
      };
      R.pending.push({ id: row.id, deliver });
      if (!R.hold) deliver();
      return null;
    };
    R.deliver = id => R.pending.find(p => p.id === id).deliver();
    R.metadata = id => c.pr_str(re_frame.core.handler_meta(read(`{:source :store :kind :mutation :id ${id}}`)));
  });
  const sub = s => page.evaluate(s => window.resourceResearch.sub(s), s);
  const dispatch = s => page.evaluate(s => window.resourceResearch.dispatch(s), s);
  const settled = async instance => page.waitForFunction(id => !window.resourceResearch.sub(`[:rf/mutation {:instance ${id}}]`)['pending?'], instance);
  const favorites = '[:rf/resource {:resource :realworld/favorited-articles :params {:username "demo" :page 1}}]';
  const detail = '[:rf/resource {:resource :realworld/article :params {:slug "second-article"}}]';
  await dispatch('[:rf.resource/ensure {:resource :realworld/favorited-articles :params {:username "demo" :page 1} :owner [:app :research/favorites] :cause [:research :favorite-membership]}]');
  await dispatch('[:rf.resource/ensure {:resource :realworld/article :params {:slug "second-article"} :owner [:app :research/detail] :cause [:research :favorite-membership]}]');
  await page.waitForFunction(s => window.resourceResearch.sub(s).status === 'loaded', favorites);
  await page.waitForFunction(s => window.resourceResearch.sub(s).status === 'loaded', detail);
  const before = await sub(favorites);
  assert(!before.data.articles.some(a => a.slug === 'second-article'));
  await page.evaluate(() => { window.resourceResearch.broken = true; });
  await dispatch('[:ui/favorite "second-article" false]');
  await settled('[:favorite "second-article"]');
  const faulty = await sub(favorites);
  assert(!faulty.data.articles.some(a => a.slug === 'second-article'), 'Planted missing invalidation must leave membership stale');
  const metadata = await page.evaluate(() => window.resourceResearch.metadata(':realworld/favorite'));
  await page.evaluate(() => { window.resourceResearch.broken = false; });
  await dispatch('[:ui/favorite "second-article" true]');
  await settled('[:favorite "second-article"]');
  await page.waitForFunction(s => !window.resourceResearch.sub(s)['fetching?'], favorites);
  await dispatch('[:ui/favorite "second-article" false]');
  await settled('[:favorite "second-article"]');
  await page.waitForFunction(s => window.resourceResearch.sub(s).data?.articles.some(a => a.slug === 'second-article'), favorites);
  const repaired = await sub(favorites);
  await dispatch('[:ui/favorite "second-article" true]');
  await settled('[:favorite "second-article"]');
  await page.waitForFunction(s => !window.resourceResearch.sub(s).data?.articles.some(a => a.slug === 'second-article'), favorites);
  await page.evaluate(() => { window.resourceResearch.broken = true; });
  await dispatch('[:ui/favorite "second-article" false]');
  await settled('[:favorite "second-article"]');
  const reintroduced = await sub(favorites);
  assert(!reintroduced.data.articles.some(a => a.slug === 'second-article'));
  await page.evaluate(() => { window.resourceResearch.broken = false; });

  // A failed refresh must preserve existing data and expose the separate failure.
  await page.evaluate(() => { window.resourceResearch.rejectNext = true; });
  await dispatch('[:rf.resource/refetch {:resource :realworld/article :params {:slug "second-article"} :cause [:research :refresh-failure]}]');
  await page.waitForFunction(s => !!window.resourceResearch.sub(s)['refresh-error'], detail);
  const refreshFailure = await sub(detail);
  assert(refreshFailure.data.article.slug === 'second-article');

  // The same fresh entry gets another owner without an extra request.
  const requestsBeforeOwner = await page.evaluate(() => window.resourceResearch.ledger.length);
  await dispatch('[:rf.resource/ensure {:resource :realworld/article :params {:slug "second-article"} :owner [:app :research/second-owner]}]');
  const requestsAfterOwner = await page.evaluate(() => window.resourceResearch.ledger.length);
  // The preceding failure may mark the entry stale; record rather than mislabel that policy as a duplicate.
  await page.waitForFunction(s => !window.resourceResearch.sub(s)['fetching?'], detail);
  const beforeFreshEnsure = await page.evaluate(() => window.resourceResearch.ledger.length);
  await dispatch('[:rf.resource/ensure {:resource :realworld/article :params {:slug "second-article"} :owner [:app :research/third-owner]}]');
  const afterFreshEnsure = await page.evaluate(() => window.resourceResearch.ledger.length);
  assert.equal(afterFreshEnsure, beforeFreshEnsure, 'Fresh ensure must not fetch');

  // Controlled in-flight dedupe: two owners, one new request; late return remains useful.
  await page.evaluate(() => { window.resourceResearch.hold = true; });
  const dedupeStart = await page.evaluate(() => window.resourceResearch.ledger.length);
  await dispatch('[:rf.resource/ensure {:resource :realworld/article :params {:slug "article-20"} :owner [:app :research/a]}]');
  await dispatch('[:rf.resource/ensure {:resource :realworld/article :params {:slug "article-20"} :owner [:app :research/b]}]');
  const dedupeEnd = await page.evaluate(() => window.resourceResearch.ledger.length);
  assert.equal(dedupeEnd - dedupeStart, 1);
  await dispatch('[:rf.resource/release-owner {:owner [:app :research/a]}]');
  await page.evaluate(id => { window.resourceResearch.hold = false; window.resourceResearch.deliver(id); }, dedupeEnd);
  const kept = '[:rf/resource {:resource :realworld/article :params {:slug "article-20"}}]';
  await page.waitForFunction(s => window.resourceResearch.sub(s).status === 'loaded', kept);
  const retainedOwner = await sub(kept);

  // Two independent optimistic writes: newer success, then older rejection.
  await dispatch('[:ui/favorite "second-article" true]');
  await settled('[:favorite "second-article"]');
  await page.waitForFunction(s => window.resourceResearch.sub(s).data?.article.favorited === false, detail);
  await page.evaluate(() => { window.resourceResearch.hold = true; window.resourceResearch.rejectNext = true; });
  await dispatch('[:rf.mutation/execute {:mutation :realworld/favorite :params {:slug "second-article" :username "demo"} :instance :research/older}]');
  const older = await page.evaluate(() => window.resourceResearch.ledger.at(-1).id);
  await dispatch('[:rf.mutation/execute {:mutation :realworld/favorite :params {:slug "second-article" :username "demo"} :instance :research/newer}]');
  const newer = await page.evaluate(() => window.resourceResearch.ledger.at(-1).id);
  await page.evaluate(id => window.resourceResearch.deliver(id), newer);
  await settled(':research/newer');
  const afterNewer = await sub(detail);
  await page.evaluate(id => {
    const R = window.resourceResearch; R.hold = false; R.deliver(id);
    for (const row of R.ledger.filter(row => !row.delivered)) R.deliver(row.id);
  }, older);
  await settled(':research/older');
  await page.waitForFunction(s => !window.resourceResearch.sub(s)['fetching?'], detail);
  const afterOlder = await sub(detail);
  assert.equal(afterNewer.data.article.favorited, true);
  assert.equal(afterOlder.data.article.favorited, true);
  assert.equal(afterOlder.data.article.favoritesCount, 1);

  // Switch the application identity while an old representation is in flight.
  await page.evaluate(() => { const R=window.resourceResearch; R.hold=true; R.markerNext='ALICE-ONLY-SYNTHETIC'; });
  await dispatch('[:rf.resource/ensure {:resource :realworld/article :params {:slug "article-21"} :owner [:app :research/old-viewer]}]');
  const oldViewerReply = await page.evaluate(() => window.resourceResearch.ledger.at(-1).id);
  await page.evaluate(() => {
    const R=window.resourceResearch;
    R.dispatch('[:auth/store-session {:username "bob" :email "bob@conduit.dev" :bio "Synthetic viewer" :image "" :token "synthetic-bob-token"}]');
    R.dispatch('[:rf.route/replan-resources {:cause [:research :viewer-switch]}]');
  });
  await page.evaluate(id => window.resourceResearch.deliver(id), oldViewerReply);
  const switchedQuery = '[:rf/resource {:resource :realworld/article :params {:slug "article-21"}}]';
  const afterSwitch = await sub(switchedQuery);
  assert.equal(afterSwitch.data, null, 'Bob must not project the old viewer representation');
  await page.evaluate(() => {
    const R=window.resourceResearch; R.hold=false;
    for (const row of R.ledger.filter(row => !row.delivered)) R.deliver(row.id);
  });
  await dispatch('[:rf.resource/ensure {:resource :realworld/article :params {:slug "article-21"} :owner [:app :research/bob]}]');
  await page.waitForFunction(s => window.resourceResearch.sub(s).status === 'loaded', switchedQuery);
  const bobResult = await sub(switchedQuery);
  assert.notEqual(bobResult.data.article.title, 'ALICE-ONLY-SYNTHETIC');
  const unresolved = { evidence: 'Separate scope-boundary suite and source trace; a complete cold browser restart is not claimed by this fixture.' };
  const receipt = { membership: { before, faulty, repaired, reintroduced, oracle: 'second-article is a member after accepted favorite', outcomes: ['fail','pass','fail'] },
    refreshFailure, freshEnsure: { beforeFreshEnsure, afterFreshEnsure, requestsBeforeOwner, requestsAfterOwner },
    dedupe: { requests: dedupeEnd - dedupeStart, retainedOwner }, metadata,
    optimisticOverlap: { older, newer, afterNewer, afterOlder },
    viewerSwitch: { oldViewerReply, afterSwitch, bobResult, unresolved, limit: 'Synthetic representation boundary, not server authorization or a full cold restart.' },
    ledger: await page.evaluate(() => window.resourceResearch.ledger) };
  fs.writeFileSync(path.join(__dirname, 'conduit-controlled-cases.json'), JSON.stringify(receipt, null, 2));
  console.log('Conduit controlled cases: missing-invalidation fail/pass/fail, refresh failure, fresh reuse, in-flight dedupe, retained owner');
};
