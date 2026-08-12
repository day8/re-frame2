'use strict';
// GUARANTEE 4 — TIMEOUT AND HARD TERMINATION.
//
//     node implementation/ssr-node/test/timeout.test.cjs
//
// The fault is a SYNCHRONOUS infinite loop, and that choice is the whole
// witness. An `await`-based hang would be stopped by any cooperative
// cancel — a rejected promise, an abort signal, a timer — and passing
// against it would prove nothing about the real case, because
// `react-dom/server`'s `renderToString` is synchronous and a render stuck
// inside it is reachable by nothing cooperative. The only thing that
// stops it is terminating the thread.
//
// Every row below therefore has to hold against a render that will never
// return: the refusal arrives inside the budget, the pool recovers, and
// the isolate that serves the next request is a DIFFERENT THREAD, because
// a terminated isolate is never reused.

const test = require('node:test');
const assert = require('node:assert');
const { withService, collect, refusalOf } = require('./_support.cjs');
const { CODE } = require('../src/protocol.cjs');

const hang = (extra = {}) => ({ protocol: 1, entry: 'app/root', state: {}, ...extra });
const quick = () => ({ protocol: 1, entry: 'app/quick', state: {} });

test('a render that never returns is refused inside its budget', async () => {
  await withService('hang', { isolates: 1, admissionTimeoutMs: 10000 }, async (service) => {
    const started = Date.now();
    const err = await refusalOf(() => collect(service, hang({ timeoutMs: 200 })));
    const elapsed = Date.now() - started;

    assert.strictEqual(err.code, CODE.RENDER_TIMEOUT);
    assert.strictEqual(err.detail.timeoutMs, 200);
    assert.ok(elapsed >= 150, `refused after ${elapsed} ms — suspiciously early for a 200 ms budget`);
    // Generous, deliberately: the claim is BOUNDED, not fast, and a tight
    // upper bound here would be a gate that reds on somebody else's
    // compile. An unterminated hang never returns at all, so any finite
    // number is the whole result.
    assert.ok(elapsed < 5000, `refused after ${elapsed} ms — the deadline is not bounding anything`);
  });
});

test('the pool recovers, and the replacement is a DIFFERENT thread', async () => {
  await withService('hang', { isolates: 1, admissionTimeoutMs: 10000 }, async (service) => {
    const before = await collect(service, quick());
    const firstThread = before.complete.meta.threadId;

    const err = await refusalOf(() => collect(service, hang({ timeoutMs: 150 })));
    assert.strictEqual(err.code, CODE.RENDER_TIMEOUT);
    assert.strictEqual(err.detail.threadId, firstThread, 'the terminated isolate is the one we had');

    const after = await collect(service, quick());
    assert.strictEqual(after.chunks.length, 1, 'the service must serve the next request normally');
    assert.notStrictEqual(
      after.complete.meta.threadId,
      firstThread,
      'a terminated isolate must never be reused — this must be a fresh thread',
    );
    assert.strictEqual(service.stats().replacements, 1);
    assert.strictEqual(service.stats().total, 1, 'the pool must be back to its configured size');
  });
});

test('a hung isolate does not take the rest of the pool with it', async () => {
  await withService('hang', { isolates: 2, admissionTimeoutMs: 10000 }, async (service) => {
    const [refused, served] = await Promise.all([
      refusalOf(() => collect(service, hang({ timeoutMs: 200 }))),
      collect(service, quick()),
    ]);
    assert.strictEqual(refused.code, CODE.RENDER_TIMEOUT);
    assert.strictEqual(served.chunks.length, 1);
  });
});

test('the service ceiling binds a caller that asks for longer', async () => {
  await withService(
    'hang',
    { isolates: 1, maxTimeoutMs: 200, admissionTimeoutMs: 10000 },
    async (service) => {
      const started = Date.now();
      const err = await refusalOf(() => collect(service, hang({ timeoutMs: 60000 })));
      assert.strictEqual(err.code, CODE.RENDER_TIMEOUT);
      assert.strictEqual(err.detail.timeoutMs, 200, 'the request asked for 60 s; the service says 200 ms');
      assert.ok(Date.now() - started < 5000);
    },
  );
});

test('a render that throws BEFORE emitting is a clean refusal', async () => {
  await withService('throws', { isolates: 1 }, async (service) => {
    const err = await refusalOf(() =>
      collect(service, { protocol: 1, entry: 'app/before', state: {} }),
    );
    assert.strictEqual(err.code, CODE.RENDER_THREW);
    assert.match(err.message, /fell over immediately/);
    assert.strictEqual(err.detail.afterChunks, 0, 'nothing was written, so nothing is torn');
  });
});

test('a render that throws AFTER emitting is a TORN response, and says so', async () => {
  // The one failure a transport must not smooth over: chunks are already
  // on their way, so there is no status code left to send and the caller
  // must not be handed a well-formed shorter page.
  await withService('throws', { isolates: 1 }, async (service) => {
    const chunks = [];
    const err = await refusalOf(async () => {
      for await (const frame of service.renderFrames({
        protocol: 1,
        entry: 'app/after',
        state: {},
      })) {
        if (frame.type === 'chunk') chunks.push(frame.html);
      }
    });
    assert.strictEqual(chunks.length, 1, 'the first chunk did reach the caller');
    assert.strictEqual(err.code, CODE.RENDER_THREW);
    assert.strictEqual(err.detail.afterChunks, 1, 'the tear must be named, with its chunk count');
  });
});

test('a render that emits nothing at all is refused rather than served empty', async () => {
  await withService('throws', { isolates: 1 }, async (service) => {
    const err = await refusalOf(() =>
      collect(service, { protocol: 1, entry: 'app/silent', state: {} }),
    );
    assert.strictEqual(err.code, CODE.RENDER_THREW);
    assert.match(err.message, /without emitting any body markup/);
  });
});

test('an isolate that throws is not poisoned — the next request is served', async () => {
  await withService('throws', { isolates: 1 }, async (service) => {
    await refusalOf(() => collect(service, { protocol: 1, entry: 'app/before', state: {} }));
    const err = await refusalOf(() =>
      collect(service, { protocol: 1, entry: 'app/before', state: {} }),
    );
    assert.strictEqual(err.code, CODE.RENDER_THREW, 'a throw is not a terminal isolate condition');
    assert.strictEqual(service.stats().replacements, 0, 'a throw must not cost an isolate');
  });
});

test('CONTROL — with no deadline in reach, the fault really does run forever', async () => {
  // Every row above is a claim that the DEADLINE ended something. This is
  // the row that shows there was something to end: given a deadline far
  // out of reach, the same render is still going long after the budgets
  // used above would have expired, and only `close()` ends it. Without
  // this, a fixture that quietly returned early would have satisfied all
  // four and proved none of them.
  const { createService } = require('../src/service.cjs');
  const path = require('node:path');
  const service = await createService({
    modulePath: path.join(__dirname, 'fixtures', 'hang.cjs'),
    isolates: 1,
    admissionTimeoutMs: 10000,
  });

  let settled = false;
  const inFlight = refusalOf(() => collect(service, hang({ timeoutMs: 30000 }))).then((e) => {
    settled = true;
    return e;
  });

  await new Promise((r) => setTimeout(r, 400));
  assert.strictEqual(settled, false, 'the render must still be running after 400 ms');

  await service.close();
  const err = await inFlight;
  assert.strictEqual(err.code, CODE.SERVICE_CLOSED, 'closing must refuse what is in flight');
});
