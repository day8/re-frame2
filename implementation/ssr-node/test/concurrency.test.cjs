'use strict';
// GUARANTEE 3 — ONE IN-FLIGHT RENDER PER ISOLATE.
//
// The reading is `overlapMax`, a high-water mark the reference module keeps
// in module state; through the service it must be 1. A broken counter also
// reads 1, so the first row shows it reading 2 when nothing stands between
// the module and its callers.

const test = require('node:test');
const assert = require('node:assert');
const { withService, collect, observed, refusalOf, fixture } = require('./_support.cjs');
const { CODE } = require('../src/protocol.cjs');
const { Isolate } = require('../src/isolate.cjs');

const req = (extra = {}) => ({ protocol: 1, entry: 'app/root', state: { ':todos': '[]' }, ...extra });

test('CONTROL — the overlap counter can read more than 1', async () => {
  const mod = require('./fixtures/reference.cjs');
  const call = { entry: 'app/root', state: Object.freeze({ ':delay': '40' }), args: undefined };
  const bodies = [];
  await Promise.all([
    mod.render(call, (h) => bodies.push(h)),
    mod.render(call, (h) => bodies.push(h)),
  ]);
  const seen = Math.max(...bodies.map((h) => observed(h).overlapMax));
  assert.strictEqual(seen, 2, 'the counter must be able to see an overlap, or it proves nothing');
});

test('a pool of N under 2N concurrent requests never overlaps within an isolate', async () => {
  // Six requests on three isolates, the rest waiting in `acquire()` under a
  // 10 s admission timeout, so each is served the moment a release frees one.
  await withService('reference', { isolates: 3, admissionTimeoutMs: 10000 }, async (service) => {
    const results = await Promise.all(
      Array.from({ length: 6 }, () => collect(service, req({ state: { ':todos': '[]', ':delay': '25' } }))),
    );
    for (const r of results) {
      assert.strictEqual(observed(r).overlapMax, 1, `an isolate ran ${observed(r).overlapMax} renders at once`);
    }
    const threads = new Set(results.map((r) => observed(r).threadId));
    assert.strictEqual(threads.size, 3, 'all three isolates should have been used');
  });
});

test('an isolate refuses a second dispatch outright', async () => {
  // The pool never does this; the guard holds anyway.
  const isolate = await new Isolate({ modulePath: fixture('reference') }).start();
  try {
    const first = isolate.render(
      { entry: 'app/root', state: { ':delay': '60' }, protocol: 1 },
      { timeoutMs: 5000, onChunk: () => {} },
    );
    assert.strictEqual(isolate.busy, true);
    const err = await refusalOf(() =>
      isolate.render({ entry: 'app/root', state: {}, protocol: 1 }, { timeoutMs: 5000, onChunk: () => {} }),
    );
    assert.strictEqual(err.code, CODE.SERVICE_SATURATED);
    await first;
    assert.strictEqual(isolate.busy, false, 'the isolate must be free again afterwards');
  } finally {
    await isolate.close();
  }
});

test('a post that THROWS leaves the isolate free, not stuck busy', async () => {
  // The structured clone throws on a value it cannot copy. Marked busy
  // before the post, the isolate would stay busy until the deadline
  // terminated a healthy thread. At the isolate rather than the service,
  // because `validateRequest` never lets such a request through.
  const isolate = await new Isolate({ modulePath: fixture('reference') }).start();
  try {
    const err = await refusalOf(() =>
      isolate.render(
        { entry: 'app/root', state: { ':route': Symbol('rf2-ey07') }, protocol: 1 },
        { timeoutMs: 5000, onChunk: () => {} },
      ),
    );
    assert.ok(err, 'the render must fail — the request cannot cross the thread boundary');
    assert.strictEqual(isolate.busy, false, 'a post that threw must leave nothing in flight');
    assert.strictEqual(isolate.dead, false, 'and must not have cost the isolate its life either');

    const chunks = [];
    await isolate.render(
      { entry: 'app/root', state: { ':todos': '[]' }, protocol: 1 },
      { timeoutMs: 5000, onChunk: (frame) => chunks.push(frame) },
    );
    assert.strictEqual(chunks.length, 1, 'the isolate must still serve the request after it');
  } finally {
    await isolate.close();
  }
});

test('a saturated pool REFUSES rather than queueing without a bottom', async () => {
  await withService('reference', { isolates: 1, admissionTimeoutMs: 20 }, async (service) => {
    const slow = collect(service, req({ state: { ':todos': '[]', ':delay': '250' } }));
    // Let the first request actually take the only isolate.
    await new Promise((r) => setTimeout(r, 30));
    const err = await refusalOf(() => collect(service, req()));
    assert.strictEqual(err.code, CODE.SERVICE_SATURATED);
    assert.strictEqual(err.detail.poolSize, 1);
    await slow;
    const after = await collect(service, req());
    assert.strictEqual(after.chunks.length, 1, 'saturation is back-pressure, not damage');
  });
});
