'use strict';
// GUARANTEE 5 — THE CALLER LATENCY ENVELOPE, MEASURED AGAINST CEILINGS
// THAT WERE REGISTERED FIRST.
//
//     node implementation/ssr-node/test/envelope.test.cjs
//
// The ceilings live in `src/envelope.cjs` and were committed on their own,
// with no measurement code in the tree, so `git log --follow` on that file
// is the witness that they were stated rather than fitted. This file may
// read them and must never move them: a run that adjusted its own ceiling
// would be a run describing itself.
//
// ## What is measured
//
//     overhead = total elapsed for the call - the module's own renderMs
//
// The render is SUBTRACTED, not budgeted. SSR speed is off this
// programme's bar (HD-012) and an envelope that budgeted the render would
// quietly re-open it. What is left is what the service is: admission,
// validation, two structured-clone crossings, the isolate handshake, the
// frame plumbing.
//
// ## The conditions, which are part of the registration
//
// Warm pool, a free isolate at dispatch, sequential requests, and a small
// request — `state` and `runtime` together inside 64 KiB, counted as
// `protocol.cjs` counts them. The warm-up is here for the first of those:
// worker boot is a deployment cost, not a per-request one, and folding it
// in would measure the wrong thing while looking rigorous.
//
// THE SIZE CONDITION IS A PRECONDITION, NOT A COMMENT. The measured
// request is asserted inside the budget before a single sample is taken,
// with a control row that shows a request over it being turned away —
// because a condition nothing enforces is a condition the witness can
// drift out of, which is how this one came to bound `state` alone while
// the wire grew a second partition (rf2-6r9j.71).
//
// ## How to read the numbers
//
// As a SHAPE claim — the service's overhead is single-digit milliseconds
// at the median and does not run away — and never as a benchmark to diff a
// future run against. This box is shared with other work, and the repo has
// already measured what that does: the X3 adoption witness published two
// runs at one commit whose phase maxima differed by more than twofold.
// The figures are printed as diagnostics either way, because a breach that
// prints no number is not a witness.

const test = require('node:test');
const assert = require('node:assert');
const { withService } = require('./_support.cjs');
const { ENVELOPE, requestBytes, judge, percentile } = require('../src/envelope.cjs');
const { validateRequest, Refusal } = require('../src/protocol.cjs');

const WARMUP = 20;

/** The tables the `reference` fixture publishes, as the validator wants them. */
const REFERENCE_TABLES = {
  buildId: 'reference-build-1',
  entries: {
    'app/root': {
      stateAllowlist: [':todos', ':route', ':delay'],
      runtimeAllowlist: [':rf.runtime/routing'],
    },
  },
};

test('percentile is nearest-rank, so every figure is a sample some request took', () => {
  const xs = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10];
  assert.strictEqual(percentile(xs, 50), 5);
  assert.strictEqual(percentile(xs, 95), 10);
  assert.strictEqual(percentile(xs, 100), 10);
  assert.strictEqual(percentile([7], 50), 7);
  assert.throws(() => percentile([], 50));
});

test('judge names every ceiling it breaches, and stays silent when it clears them', () => {
  const clean = judge([1, 1, 1, 1]);
  assert.strictEqual(clean.ok, true);
  assert.deepStrictEqual(clean.breaches, []);
  const bad = judge([1000, 1000, 1000, 1000]);
  assert.strictEqual(bad.ok, false);
  assert.strictEqual(bad.breaches.length, 3, 'p50, p95 and max all breached');
});

test('the budget counts a request the way the validator counts it — keys, values, both partitions', () => {
  // The condition is only as good as its byte definition, and this one is
  // not asserted in prose: it is pinned against `protocol.cjs`, the thing
  // that actually enforces a ceiling over the same text. A request of
  // exactly N bytes must be admitted at a ceiling of N and refused at
  // N - 1, which is only true if both counts agree key for key.
  //
  // The witness used to count two VALUES of one partition and call that
  // the request's size. It omitted every key and the whole `runtime`
  // partition, so it could clear a budget the validator would have read
  // as larger (rf2-6r9j.71).
  const request = {
    protocol: 1,
    entry: 'app/root',
    state: { ':todos': '[1 2 3]', ':route': '{:name :home}' },
    runtime: { ':rf.runtime/routing': '{:name :home}' },
  };
  const n = requestBytes(request);
  assert.ok(n > 0);

  assert.doesNotThrow(
    () => validateRequest(request, REFERENCE_TABLES, { maxRequestBytes: n }),
    'a request of exactly N bytes must be admitted at a ceiling of N',
  );
  assert.throws(
    () => validateRequest(request, REFERENCE_TABLES, { maxRequestBytes: n - 1 }),
    (err) => err instanceof Refusal && /are \d+ bytes, over the/.test(err.message),
    'and refused one byte lower — so the two counts are the same count',
  );

  // Neither partition is free, and neither are the keys: dropping any one
  // of the three moves the number.
  assert.ok(requestBytes({ ...request, runtime: {} }) < n, 'runtime bytes must count');
  assert.ok(requestBytes({ ...request, state: {} }) < n, 'state bytes must count');
  assert.strictEqual(
    requestBytes({ state: { ab: 'cd' } }),
    4,
    'two key bytes and two value bytes',
  );
});

test('a request over the combined condition is refused by the witness precondition', () => {
  // THE CONTROL FOR THE ROW BELOW. The measured request clearing the
  // budget says nothing unless a request that busts it would be turned
  // away — and the interesting one busts it on `runtime` alone, which is
  // the partition the condition did not used to see. It is a request the
  // SERVICE would happily accept (one allowlisted key, far under the
  // 1 MiB protocol ceiling); it is the envelope's narrower sampling
  // condition that refuses it, which is the distinction being pinned.
  const request = {
    protocol: 1,
    entry: 'app/root',
    state: { ':todos': '[]' },
    runtime: { ':rf.runtime/routing': 'x'.repeat(ENVELOPE.requestBudgetBytes) },
  };
  assert.ok(
    requestBytes(request) > ENVELOPE.requestBudgetBytes,
    'a runtime-heavy request must be measured as over the combined budget',
  );
  assert.doesNotThrow(
    () => validateRequest(request, REFERENCE_TABLES, {}),
    'and it must be one the service itself would serve — otherwise the row is about the protocol ceiling',
  );
});

test(`the service clears its pre-registered envelope over ${ENVELOPE.samples} samples`, async (t) => {
  await withService('reference', { isolates: 2, admissionTimeoutMs: 10000 }, async (service) => {
    // A request comfortably inside the registered budget, and carrying
    // BOTH partitions — the condition is stated over the request, so a
    // sample that sent one of them would be measuring a narrower request
    // than the envelope claims to cover (rf2-6r9j.71).
    const request = {
      protocol: 1,
      entry: 'app/root',
      state: { ':todos': JSON.stringify(Array.from({ length: 40 }, (_, i) => i)), ':route': '{:name :home}' },
      runtime: { ':rf.runtime/routing': '{:name :home :params {} :query {}}' },
    };
    assert.ok(
      requestBytes(request) <= ENVELOPE.requestBudgetBytes,
      `the sample request must sit inside the registered ${ENVELOPE.requestBudgetBytes}-byte budget; ` +
        `it is ${requestBytes(request)} bytes`,
    );
    assert.ok(
      Object.keys(request.runtime).length > 0 && Object.keys(request.state).length > 0,
      'both partitions must be non-empty, or the combined condition is untested',
    );

    // WARM. Worker boot is a deployment cost; folding it in would measure
    // something the envelope explicitly does not bound.
    for (let i = 0; i < WARMUP; i += 1) await service.renderToString(request);

    const overheads = [];
    for (let i = 0; i < ENVELOPE.samples; i += 1) {
      const started = process.hrtime.bigint();
      const out = await service.renderToString(request);
      const totalMs = Number(process.hrtime.bigint() - started) / 1e6;
      // The module's own render time, measured inside the isolate around
      // the module's `render` call, comes back on the terminal frame.
      overheads.push(Math.max(0, totalMs - out.renderMs));
    }

    const verdict = judge(overheads);
    t.diagnostic(
      `service overhead over ${verdict.n} samples: ` +
        `p50 ${verdict.p50.toFixed(2)} ms (ceiling ${ENVELOPE.p50Ms}), ` +
        `p95 ${verdict.p95.toFixed(2)} ms (ceiling ${ENVELOPE.p95Ms}), ` +
        `max ${verdict.max.toFixed(2)} ms (ceiling ${ENVELOPE.maxMs})`,
    );
    assert.strictEqual(verdict.ok, true, `envelope breached: ${verdict.breaches.join('; ')}`);
  });
});

test('the envelope is a real constraint — a slow enough service would breach it', async (t) => {
  // The control. `judge` clearing a real sample says nothing unless it
  // would refuse a bad one, and "would refuse" is cheaper to demonstrate
  // than to argue: the same sample shifted by one ceiling's worth breaches.
  const shifted = Array.from({ length: ENVELOPE.samples }, () => ENVELOPE.p50Ms + 0.01);
  const verdict = judge(shifted);
  assert.strictEqual(verdict.ok, false);
  assert.match(verdict.breaches[0], /^p50 /);
  t.diagnostic(`control: ${verdict.breaches.join('; ')}`);
});
