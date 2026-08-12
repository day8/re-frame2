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
// Warm pool, a free isolate at dispatch, sequential requests, small state.
// The warm-up is here for the first of those: worker boot is a deployment
// cost, not a per-request one, and folding it in would measure the wrong
// thing while looking rigorous.
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
const { ENVELOPE, judge, percentile } = require('../src/envelope.cjs');

const WARMUP = 20;

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

test(`the service clears its pre-registered envelope over ${ENVELOPE.samples} samples`, async (t) => {
  await withService('reference', { isolates: 2, admissionTimeoutMs: 10000 }, async (service) => {
    // A request whose state is comfortably inside the registered budget.
    const request = {
      protocol: 1,
      entry: 'app/root',
      state: { ':todos': JSON.stringify(Array.from({ length: 40 }, (_, i) => i)), ':route': '{:name :home}' },
    };
    const stateBytes =
      Buffer.byteLength(request.state[':todos'], 'utf8') +
      Buffer.byteLength(request.state[':route'], 'utf8');
    assert.ok(
      stateBytes <= ENVELOPE.stateBudgetBytes,
      `the sample request must sit inside the registered ${ENVELOPE.stateBudgetBytes}-byte budget`,
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
