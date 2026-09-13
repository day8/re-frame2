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
const { withService, collect, observed, refusalOf } = require('./_support.cjs');
const { CODE, RENDER_THREW_REFUSAL } = require('../src/protocol.cjs');

const hang = (extra = {}) => ({ protocol: 1, entry: 'app/root', state: {}, ...extra });
const quick = () => ({ protocol: 1, entry: 'app/quick', state: {} });
// rf2-kirm — the same runaway loop, but with two chunks already emitted, so
// the deadline lands on a TORN response rather than a clean one.
const torn = (extra = {}) => ({ protocol: 1, entry: 'app/torn', state: {}, ...extra });

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
    const firstThread = observed(before).threadId;

    const err = await refusalOf(() => collect(service, hang({ timeoutMs: 150 })));
    assert.strictEqual(err.code, CODE.RENDER_TIMEOUT);
    assert.strictEqual(err.detail.threadId, firstThread, 'the terminated isolate is the one we had');

    const after = await collect(service, quick());
    assert.strictEqual(after.chunks.length, 1, 'the service must serve the next request normally');
    assert.notStrictEqual(
      observed(after).threadId,
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

// ---------------------------------------------------------------------------
// rf2-kirm — THE TORN-RESPONSE COUNT SURVIVES A TIMEOUT.
//
// `README.md` §refusals and `service.cjs`'s own header both promise that a
// failure arriving AFTER chunks is a torn response carrying
// `detail.afterChunks`, and name "the isolate dying under a render" as one of
// the two ways it happens. The isolate counted chunks on `pendingRender` and
// attached the count for worker-REPORTED errors — but the deadline rejection
// and `_failPendingRender` built their refusals without it, so a transport or
// consumer branching on the advertised discriminator saw `undefined` while
// body bytes had already left. The throw rows below this block are the shape
// that always worked; these are the same claim on the paths that dropped it.
// ---------------------------------------------------------------------------

test('a TIMEOUT before any chunk carries afterChunks 0', async () => {
  await withService('hang', { isolates: 1, admissionTimeoutMs: 10000 }, async (service) => {
    const err = await refusalOf(() => collect(service, hang({ timeoutMs: 200 })));
    assert.strictEqual(err.code, CODE.RENDER_TIMEOUT);
    assert.strictEqual(err.detail.afterChunks, 0, 'nothing was written, so nothing is torn');
    // The distinctions the repair must not cost.
    assert.strictEqual(err.detail.timeoutMs, 200);
    assert.strictEqual(err.detail.entry, 'app/root');
    assert.strictEqual(typeof err.detail.isolate, 'number');
  });
});

test('a TIMEOUT after chunks is a TORN response, and names the exact count', async () => {
  await withService('hang', { isolates: 1, admissionTimeoutMs: 10000 }, async (service) => {
    const chunks = [];
    const err = await refusalOf(async () => {
      for await (const frame of service.renderFrames(torn({ timeoutMs: 400 }))) {
        if (frame.type === 'chunk') chunks.push(frame.html);
      }
    });
    assert.strictEqual(chunks.length, 2, 'both chunks really did reach the caller');
    assert.strictEqual(err.code, CODE.RENDER_TIMEOUT, 'still a timeout, not reclassified');
    assert.strictEqual(err.detail.afterChunks, 2, 'the tear is named, with its exact count');
    assert.strictEqual(err.detail.timeoutMs, 400, 'the existing detail is intact');
    assert.strictEqual(
      err.detail.entry,
      'app/torn',
      'and the entry, so an operator can still name the render',
    );
  });
});

test('no success completion follows a torn timeout', async () => {
  // The half a count alone would not prove: the caller must not be handed a
  // `complete` frame describing the chunks it did get.
  await withService('hang', { isolates: 1, admissionTimeoutMs: 10000 }, async (service) => {
    let complete = null;
    const err = await refusalOf(async () => {
      for await (const frame of service.renderFrames(torn({ timeoutMs: 400 }))) {
        if (frame.type === 'complete') complete = frame;
      }
    });
    assert.ok(err, 'the render must not have succeeded');
    assert.strictEqual(complete, null, 'a torn stream must never yield a complete frame');
  });
});

test('a render that throws BEFORE emitting is a clean refusal', async () => {
  await withService('throws', { isolates: 1 }, async (service) => {
    const err = await refusalOf(() =>
      collect(service, { protocol: 1, entry: 'app/before', state: {} }),
    );
    assert.strictEqual(err.code, CODE.RENDER_THREW);
    // The wording is the CONTRACT'S, not the module's. This row asserted
    // `/fell over immediately/` — the exact string authored at
    // `fixtures/throws.cjs:20` — which made it a standing witness that the
    // module's own message crossed into the public refusal. That was the
    // leak rather than a feature of it: an exception's message is built
    // from the value being processed in any real renderer, so the string
    // this row was pinning is the shape request state travels in. The
    // operator still gets the original, with its stack, on the sidecar's
    // stderr; `egress.test.cjs` §4 is where the absence is measured with
    // planted sentinels.
    assert.strictEqual(err.message, RENDER_THREW_REFUSAL);
    assert.ok(!err.message.includes('fell over immediately'), 'the authored string must not cross');
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
  // rf2-kirm — the third terminal path that clears `pendingRender`. Nothing
  // was emitted here, so the count is 0; what matters is that the field is
  // PRESENT, since a consumer branching on `detail.afterChunks` cannot tell an
  // untorn response from a path that forgot to say.
  assert.strictEqual(err.detail.afterChunks, 0, 'a close refusal names the tear count too');
});

// ---------------------------------------------------------------------------
// A worker that EXITS before it is ready (rf2-gwye.23)
//
// The boot deadline had a hole. A module that calls `process.exit()` while it
// is evaluated, or from its `boot` hook, raises no `error` and posts no
// `boot-error` — and the exit CLEARED the boot timer, the only other thing
// that could settle startup. So startup stayed pending for ever, and a pool
// start and a replacement both inherited that. `bootTimeoutMs` is left at its
// 30 s default, far past every bound below, so a green row is the EXIT
// settling startup rather than the boot timer standing in for it — and a red
// one reads as a failed assertion, never as a hung file.
// ---------------------------------------------------------------------------

const { createService } = require('../src/service.cjs');
const { Isolate } = require('../src/isolate.cjs');
const { REPLACEMENT_FAILED_REFUSAL } = require('../src/protocol.cjs');
const { fixture } = require('./_support.cjs');
// Required while the flag is DISARMED — see the fixture's header.
const { EXIT_AT_FLAG } = require('./fixtures/exits.cjs');

/** `promise`'s outcome within `ms`, or `pending`. The timer is cleared rather than left holding the loop. */
async function settledWithin(promise, ms = 3000) {
  let timer;
  const outcome = await Promise.race([
    promise.then(
      (value) => ({ state: 'resolved', value }),
      (error) => ({ state: 'rejected', error }),
    ),
    new Promise((resolve) => {
      timer = setTimeout(() => resolve({ state: 'pending' }), ms);
    }),
  ]);
  clearTimeout(timer);
  return outcome;
}

/** Run `fn` with the fixture's exit armed, disarming it whichever way `fn` went. */
async function withExitAt(exitAt, fn) {
  process.env[EXIT_AT_FLAG] = exitAt;
  try {
    return await fn();
  } finally {
    delete process.env[EXIT_AT_FLAG];
  }
}

test('a module that EXITS while booting rejects startup promptly — clean exit or not', async () => {
  for (const [exitAt, exitCode] of [
    ['eval', 7],
    ['boot', 0],
  ]) {
    const outcome = await withExitAt(exitAt, () =>
      settledWithin(createService({ modulePath: fixture('exits'), isolates: 1 })),
    );
    if (outcome.state === 'resolved') await outcome.value.close();
    assert.strictEqual(outcome.state, 'rejected', `${exitAt}: startup must settle, and must not succeed`);
    assert.strictEqual(outcome.error.code, CODE.MALFORMED_MODULE, exitAt);
    assert.strictEqual(outcome.error.detail.exitCode, exitCode, `${exitAt}: the exit is what settled it`);
  }
  // CONTROL — disarmed, the same module boots, so the rejections above are
  // the exits and not a module this service would never have accepted.
  const healthy = await settledWithin(createService({ modulePath: fixture('exits'), isolates: 1 }));
  assert.strictEqual(healthy.state, 'resolved');
  await healthy.value.close();
});

test('a pool start with one sibling exiting rejects, and leaves no thread running', async () => {
  // Every isolate the pool starts is recorded with its thread and that
  // thread's exit code, so the row can show the healthy sibling really
  // booted — it is TERMINATED by the pool (1) rather than exiting by itself
  // (0) — and really is gone.
  const started = [];
  const realStart = Isolate.prototype.start;
  Isolate.prototype.start = function start() {
    const booting = realStart.call(this);
    const record = { worker: this.worker, exitCode: null };
    this.worker.once('exit', (code) => {
      record.exitCode = code;
    });
    started.push(record);
    return booting;
  };
  try {
    const outcome = await withExitAt('boot-even-thread', () =>
      settledWithin(createService({ modulePath: fixture('exits'), isolates: 2 })),
    );
    if (outcome.state === 'resolved') await outcome.value.close();
    assert.strictEqual(started.length, 2, 'both isolates were started');
    assert.strictEqual(outcome.state, 'rejected', 'one sibling exiting must fail the pool start');
    assert.strictEqual(outcome.error.code, CODE.MALFORMED_MODULE);
    assert.deepStrictEqual(
      started.map((s) => s.exitCode).sort(),
      [0, 1],
      'one isolate exited by itself (0), and the pool terminated its healthy sibling (1)',
    );
    assert.deepStrictEqual(started.map((s) => s.worker.threadId), [-1, -1], 'and no thread is left running');
  } finally {
    Isolate.prototype.start = realStart;
    // A red row must not become a hung file.
    await Promise.all(started.map((s) => s.worker.terminate()));
  }
});

test('a REPLACEMENT that exits before it is ready refuses its waiter, tells the operator, and lets close finish', async () => {
  const captured = [];
  const realWrite = process.stderr.write;
  process.stderr.write = function (chunk, ...rest) {
    captured.push(String(chunk));
    return realWrite.call(this, chunk, ...rest);
  };
  const service = await createService({ modulePath: fixture('exits'), isolates: 1, admissionTimeoutMs: 10000 });
  let closed;
  try {
    const exits = { protocol: 1, entry: 'app/exits' };
    // Back to back, in one synchronous run: the first takes the only
    // isolate, and the second is queued behind it before anything settles.
    const dying = refusalOf(() => collect(service, exits));
    const queued = refusalOf(() => collect(service, exits));
    // CONTROL — the scenario is the one claimed: a caller really is waiting.
    assert.strictEqual(service.stats().waiting, 1, 'a caller must be queued for the replacement');
    // Armed only now: the running isolate took its copy of `process.env` at
    // construction, so this reaches the replacement and nothing else.
    process.env[EXIT_AT_FLAG] = 'boot';

    assert.strictEqual((await dying).code, CODE.ISOLATE_LOST, 'the render-time exit is unchanged');
    const waiter = await settledWithin(queued);
    assert.strictEqual(waiter.state, 'resolved', 'the waiter must be answered, not left to its admission timer');
    assert.strictEqual(waiter.value?.code, CODE.ISOLATE_LOST);
    assert.strictEqual(waiter.value.message, REPLACEMENT_FAILED_REFUSAL);
    assert.strictEqual(service.stats().replacements, 1, 'the pool did try to replace it');
  } finally {
    delete process.env[EXIT_AT_FLAG];
    closed = await settledWithin(service.close());
    process.stderr.write = realWrite;
  }
  assert.strictEqual(closed.state, 'resolved', 'close must not wait for ever on a replacement that exited');
  assert.ok(
    captured.join('').includes('[rf.ssr-node] a replacement isolate failed to boot'),
    'the operator is told, as for any replacement that will not boot',
  );
});
