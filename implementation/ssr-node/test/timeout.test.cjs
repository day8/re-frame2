'use strict';
// GUARANTEE 4 — TIMEOUT AND HARD TERMINATION.
//
// The fault is a SYNCHRONOUS infinite loop, because `renderToString` is
// synchronous and nothing cooperative can stop a render stuck inside it;
// only terminating the thread does. So the refusal must arrive inside the
// budget, the pool must recover, and the next request must be served by a
// DIFFERENT thread.

const test = require('node:test');
const assert = require('node:assert');
const { withService, collect, observed, refusalOf, fixture } = require('./_support.cjs');
const { CODE } = require('../src/protocol.cjs');
const { createService } = require('../src/service.cjs');
const { Isolate } = require('../src/isolate.cjs');
// Required while the flag is DISARMED — see the fixture.
const { EXIT_AT_FLAG } = require('./fixtures/exits.cjs');

const hang = (extra = {}) => ({ protocol: 1, entry: 'app/root', state: {}, ...extra });
const quick = () => ({ protocol: 1, entry: 'app/quick', state: {} });
// The same runaway loop with two chunks already emitted.
const torn = (extra = {}) => ({ protocol: 1, entry: 'app/torn', state: {}, ...extra });

test('a render that never returns is refused inside its budget', async () => {
  await withService('hang', { isolates: 1, admissionTimeoutMs: 10000 }, async (service) => {
    const started = Date.now();
    const err = await refusalOf(() => collect(service, hang({ timeoutMs: 200 })));
    const elapsed = Date.now() - started;

    assert.strictEqual(err.code, CODE.RENDER_TIMEOUT);
    assert.strictEqual(err.detail.timeoutMs, 200);
    assert.ok(elapsed >= 150, `refused after ${elapsed} ms — suspiciously early for a 200 ms budget`);
    // Generous on purpose: the claim is BOUNDED, not fast.
    assert.ok(elapsed < 5000, `refused after ${elapsed} ms — the deadline is not bounding anything`);
    assert.strictEqual(err.detail.afterChunks, 0, 'nothing was written, so nothing is torn');
    assert.strictEqual(err.detail.entry, 'app/root');
    assert.strictEqual(typeof err.detail.isolate, 'number');
  });
});

test('the pool recovers, and the replacement is a DIFFERENT thread', async () => {
  await withService('hang', { isolates: 1, admissionTimeoutMs: 10000 }, async (service) => {
    const firstThread = observed(await collect(service, quick())).threadId;

    const err = await refusalOf(() => collect(service, hang({ timeoutMs: 150 })));
    assert.strictEqual(err.code, CODE.RENDER_TIMEOUT);
    assert.strictEqual(err.detail.threadId, firstThread, 'the terminated isolate is the one we had');

    const after = await collect(service, quick());
    assert.strictEqual(after.chunks.length, 1, 'the service must serve the next request normally');
    assert.notStrictEqual(observed(after).threadId, firstThread, 'a terminated isolate must never be reused');
    assert.strictEqual(service.stats().replacements, 1);
    assert.strictEqual(service.stats().total, 1, 'the pool must be back to its configured size');
  });
});

test('the service ceiling binds a caller that asks for longer', async () => {
  await withService('hang', { isolates: 1, maxTimeoutMs: 200, admissionTimeoutMs: 10000 }, async (service) => {
    const started = Date.now();
    const err = await refusalOf(() => collect(service, hang({ timeoutMs: 60000 })));
    assert.strictEqual(err.code, CODE.RENDER_TIMEOUT);
    assert.strictEqual(err.detail.timeoutMs, 200, 'the request asked for 60 s; the service says 200 ms');
    assert.ok(Date.now() - started < 5000);
  });
});

test('a TIMEOUT after chunks is a TORN response, names the exact count, and never completes', async () => {
  // The deadline builds its own refusal, so the count comes from the
  // isolate's record rather than from the worker.
  await withService('hang', { isolates: 1, admissionTimeoutMs: 10000 }, async (service) => {
    const chunks = [];
    let complete = null;
    const err = await refusalOf(async () => {
      for await (const frame of service.renderFrames(torn({ timeoutMs: 400 }))) {
        if (frame.type === 'chunk') chunks.push(frame.html);
        else complete = frame;
      }
    });
    assert.strictEqual(chunks.length, 2, 'both chunks really did reach the caller');
    assert.strictEqual(complete, null, 'a torn stream must never yield a complete frame');
    assert.strictEqual(err.code, CODE.RENDER_TIMEOUT, 'still a timeout, not reclassified');
    assert.strictEqual(err.detail.afterChunks, 2, 'the tear is named, with its exact count');
    assert.strictEqual(err.detail.timeoutMs, 400, 'the rest of the detail is intact');
    assert.strictEqual(err.detail.entry, 'app/torn', 'and the entry, so an operator can still name the render');
  });
});

test('a render that emits nothing at all is refused rather than served empty', async () => {
  await withService('throws', { isolates: 1 }, async (service) => {
    const err = await refusalOf(() => collect(service, { protocol: 1, entry: 'app/silent', state: {} }));
    assert.strictEqual(err.code, CODE.RENDER_THREW);
    assert.match(err.message, /without emitting any body markup/);
  });
});

test('an isolate that throws is not poisoned — the next request is served', async () => {
  await withService('throws', { isolates: 1 }, async (service) => {
    await refusalOf(() => collect(service, { protocol: 1, entry: 'app/before', state: {} }));
    const err = await refusalOf(() => collect(service, { protocol: 1, entry: 'app/before', state: {} }));
    assert.strictEqual(err.code, CODE.RENDER_THREW, 'a throw is not a terminal isolate condition');
    assert.strictEqual(service.stats().replacements, 0, 'a throw must not cost an isolate');
  });
});

test('CONTROL — with no deadline in reach, the fault really does run forever', async () => {
  // Without this, a fixture that quietly returned early would satisfy every
  // deadline row above.
  const service = await createService({ modulePath: fixture('hang'), isolates: 1, admissionTimeoutMs: 10000 });

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
  assert.strictEqual(err.detail.afterChunks, 0, 'a close refusal names the tear count too');
});

// ---------------------------------------------------------------------------
// A worker that EXITS before it is ready raises no `error`, posts no
// `boot-error`, and clears the boot timer, so only the exit can settle
// startup. `bootTimeoutMs` stays at its 30 s default, far past every bound
// below, so a green row is the exit settling startup.
// ---------------------------------------------------------------------------

/** `promise`'s outcome within `ms`, or `pending`. */
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
  // CONTROL — disarmed, the same module boots.
  const healthy = await settledWithin(createService({ modulePath: fixture('exits'), isolates: 1 }));
  assert.strictEqual(healthy.state, 'resolved');
  await healthy.value.close();
});

test('a pool start with one sibling exiting rejects, and leaves no thread running', async () => {
  // Each started isolate's thread and exit code are recorded, so the row
  // shows the healthy sibling booted and was TERMINATED by the pool (1)
  // rather than exiting by itself (0).
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
