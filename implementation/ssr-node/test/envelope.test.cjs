'use strict';
// GUARANTEE 5 — THE CALLER LATENCY ENVELOPE, MEASURED AGAINST CEILINGS
// THAT WERE REGISTERED FIRST.
//
// The ceilings live in `src/envelope.cjs`; this file reads them and never
// moves them. Measured: total elapsed minus the module's own `renderMs`,
// over a warm pool, sequential requests and a request inside the
// registered byte budget. A shape claim, never a benchmark to diff.

const test = require('node:test');
const assert = require('node:assert');
const { withService } = require('./_support.cjs');
const { ENVELOPE, requestBytes, judge, percentile } = require('../src/envelope.cjs');
const { validateRequest, Refusal } = require('../src/protocol.cjs');

const WARMUP = 20;

/** The tables the `reference` fixture publishes. */
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
  const verdict = (xs) => {
    const v = judge(xs);
    return [v.ok, v.breaches.map((b) => b.split(' ')[0])];
  };
  assert.deepStrictEqual(verdict([1, 1, 1, 1]), [true, []]);
  // Just over one ceiling is a breach of that ceiling alone.
  assert.deepStrictEqual(verdict(Array(ENVELOPE.samples).fill(ENVELOPE.p50Ms + 0.01)), [false, ['p50']]);
  assert.deepStrictEqual(verdict([1000, 1000, 1000, 1000]), [false, ['p50', 'p95', 'max']]);
});

test('the budget counts a request the way the validator counts it — keys, values, both partitions', () => {
  // Exactly N bytes is admitted at a ceiling of N and refused at N - 1 only
  // if the two counts agree key for key.
  const request = {
    protocol: 1,
    entry: 'app/root',
    state: { ':todos': '[1 2 3]', ':route': '{:name :home}' },
    runtime: { ':rf.runtime/routing': '{:name :home}' },
  };
  const n = requestBytes(request);
  assert.doesNotThrow(
    () => validateRequest(request, REFERENCE_TABLES, { maxRequestBytes: n }),
    'a request of exactly N bytes must be admitted at a ceiling of N',
  );
  assert.throws(
    () => validateRequest(request, REFERENCE_TABLES, { maxRequestBytes: n - 1 }),
    (err) => err instanceof Refusal && /are \d+ bytes, over the/.test(err.message),
    'and refused one byte lower — so the two counts are the same count',
  );
  assert.ok(requestBytes({ ...request, runtime: {} }) < n, 'runtime bytes must count');
  assert.ok(requestBytes({ ...request, state: {} }) < n, 'state bytes must count');
  assert.strictEqual(requestBytes({ state: { ab: 'cd' } }), 4, 'two key bytes and two value bytes');
});

test(`the service clears its pre-registered envelope over ${ENVELOPE.samples} samples`, async (t) => {
  await withService('reference', { isolates: 2, admissionTimeoutMs: 10000 }, async (service) => {
    // Both partitions, inside the registered budget.
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

    // Worker boot is a deployment cost the envelope does not bound.
    for (let i = 0; i < WARMUP; i += 1) await service.renderToString(request);

    const overheads = [];
    for (let i = 0; i < ENVELOPE.samples; i += 1) {
      const started = process.hrtime.bigint();
      const out = await service.renderToString(request);
      const totalMs = Number(process.hrtime.bigint() - started) / 1e6;
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
