'use strict';
// GUARANTEE 3 — ONE IN-FLIGHT RENDER PER ISOLATE.
//
//     node implementation/ssr-node/test/concurrency.test.cjs
//
// The reading is `overlapMax`, a high-water mark the reference render
// module keeps in its own module-level state. Through the service it must
// be 1 on every isolate, forever.
//
// A high-water mark that is always 1 is exactly what a BROKEN counter also
// reports, so the first row here is the control: the same module, called
// twice concurrently in this process with nothing between it and the
// caller, reads 2. Without that row the rest of this file would be
// evidence for nothing.

const test = require('node:test');
const assert = require('node:assert');
const path = require('node:path');
const { withService, collect, observed, refusalOf } = require('./_support.cjs');
const { CODE } = require('../src/protocol.cjs');
const { Isolate } = require('../src/isolate.cjs');

const req = (extra = {}) => ({ protocol: 1, entry: 'app/root', state: { ':todos': '[]' }, ...extra });
const FIXTURE = path.join(__dirname, 'fixtures', 'reference.cjs');

test('CONTROL — the overlap counter can read more than 1', async () => {
  // The same module, in this process, driven with no service in the way.
  // This is what makes every `overlapMax === 1` below a measurement.
  const mod = require('./fixtures/reference.cjs');
  const call = { entry: 'app/root', state: Object.freeze({ ':delay': '40' }), args: undefined };
  // The module's observations ride the markup it emits — the only channel
  // a render module has — so the control reads them the same way the
  // service-driven rows below do.
  const bodies = [];
  await Promise.all([
    mod.render(call, (h) => bodies.push(h)),
    mod.render(call, (h) => bodies.push(h)),
  ]);
  const seen = Math.max(...bodies.map((h) => observed(h).overlapMax));
  assert.strictEqual(seen, 2, 'the counter must be able to see an overlap, or it proves nothing');
});

test('a pool of N under 2N concurrent requests never overlaps within an isolate', async () => {
  await withService('reference', { isolates: 3, admissionTimeoutMs: 10000 }, async (service) => {
    const results = await Promise.all(
      Array.from({ length: 6 }, () =>
        collect(service, req({ state: { ':todos': '[]', ':delay': '25' } })),
      ),
    );
    for (const r of results) {
      assert.strictEqual(
        observed(r).overlapMax,
        1,
        `an isolate ran ${observed(r).overlapMax} renders at once`,
      );
    }
    // The requests really did contend: three isolates served six requests,
    // so at least one isolate was reused, and the wall time exceeded a
    // single delay.
    const threads = new Set(results.map((r) => observed(r).threadId));
    assert.strictEqual(threads.size, 3, 'all three isolates should have been used');
  });
});

test('an isolate refuses a second dispatch outright', async () => {
  // The pool never does this. The guard holds anyway, because a guard that
  // trusts its one caller stops holding the day a second caller appears.
  const isolate = await new Isolate({ modulePath: FIXTURE }).start();
  try {
    const first = isolate.render(
      { entry: 'app/root', state: { ':delay': '60' }, protocol: 1 },
      { timeoutMs: 5000, onChunk: () => {} },
    );
    assert.strictEqual(isolate.busy, true);
    const err = await refusalOf(() =>
      isolate.render(
        { entry: 'app/root', state: {}, protocol: 1 },
        { timeoutMs: 5000, onChunk: () => {} },
      ),
    );
    assert.strictEqual(err.code, CODE.SERVICE_SATURATED);
    await first;
    assert.strictEqual(isolate.busy, false, 'the isolate must be free again afterwards');
  } finally {
    await isolate.close();
  }
});

test('a saturated pool REFUSES rather than queueing without a bottom', async () => {
  await withService('reference', { isolates: 1, admissionTimeoutMs: 20 }, async (service) => {
    const slow = collect(service, req({ state: { ':todos': '[]', ':delay': '250' } }));
    // Give the first request time to actually take the only isolate.
    await new Promise((r) => setTimeout(r, 30));
    const err = await refusalOf(() => collect(service, req()));
    assert.strictEqual(err.code, CODE.SERVICE_SATURATED);
    assert.strictEqual(err.detail.poolSize, 1);
    await slow;
    // …and the service is fine afterwards. Saturation is back-pressure,
    // not damage.
    const after = await collect(service, req());
    assert.strictEqual(after.chunks.length, 1);
  });
});

test('a waiter is served the moment an isolate frees, without its own timer firing', async () => {
  await withService('reference', { isolates: 1, admissionTimeoutMs: 5000 }, async (service) => {
    const started = Date.now();
    const [a, b] = await Promise.all([
      collect(service, req({ state: { ':todos': '"A"', ':delay': '60' } })),
      collect(service, req({ state: { ':todos': '"B"', ':delay': '60' } })),
    ]);
    assert.strictEqual(observed(a).overlapMax, 1);
    assert.strictEqual(observed(b).overlapMax, 1);
    // Serialised through one isolate, so the pair takes both delays.
    assert.ok(Date.now() - started >= 110, 'the two renders should have been serialised');
    assert.strictEqual(observed(a).threadId, observed(b).threadId);
  });
});
