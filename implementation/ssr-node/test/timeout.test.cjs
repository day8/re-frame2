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
const { withService, observed, refusalOf, fixture } = require('./_support.cjs');
const { CODE } = require('../src/protocol.cjs');
const { createService } = require('../src/service.cjs');
const { Isolate } = require('../src/isolate.cjs');
const { EXIT_AT_FLAG } = require('./fixtures/exits.cjs');

const hang = (extra = {}) => ({ protocol: 1, entry: 'app/root', state: {}, ...extra });
const quick = () => ({ protocol: 1, entry: 'app/quick', state: {} });

test('a render that never returns is refused inside its budget, and the service ceiling binds a caller that asks for longer', async () => {
  await withService('hang', { isolates: 1, maxTimeoutMs: 200, admissionTimeoutMs: 10000 }, async (service) => {
    const started = Date.now();
    const err = await refusalOf(() => service.renderToString(hang({ timeoutMs: 60000 })));
    const elapsed = Date.now() - started;

    assert.strictEqual(err.code, CODE.RENDER_TIMEOUT);
    // The request asked for 60 s; the service says 200 ms.
    assert.deepStrictEqual(
      { ...err.detail, isolate: typeof err.detail.isolate, threadId: typeof err.detail.threadId },
      { timeoutMs: 200, isolate: 'number', threadId: 'number', entry: 'app/root' },
    );
    assert.ok(elapsed >= 150 && elapsed < 5000, `refused after ${elapsed} ms; expected 150–5000 ms`);
  });
});

test('the pool recovers, and the replacement is a DIFFERENT thread', async () => {
  await withService('hang', { isolates: 1, admissionTimeoutMs: 10000 }, async (service) => {
    const firstThread = observed(await service.renderToString(quick())).threadId;

    const err = await refusalOf(() => service.renderToString(hang({ timeoutMs: 150 })));
    assert.deepStrictEqual([err.code, err.detail.threadId], [CODE.RENDER_TIMEOUT, firstThread]);

    const after = await service.renderToString(quick());
    assert.deepStrictEqual([service.stats().replacements, service.stats().total], [1, 1]);
    assert.notStrictEqual(observed(after).threadId, firstThread, 'a terminated isolate must never be reused');
  });
});

test('a render that returns no body is refused rather than served empty', async () => {
  await withService('throws', { isolates: 1 }, async (service) => {
    const err = await refusalOf(() => service.renderToString({ protocol: 1, entry: 'app/silent', state: {} }));
    assert.deepStrictEqual(
      [err.code, err.detail],
      [CODE.RENDER_THREW, { entry: 'app/silent', returned: '[object Undefined]' }],
    );
  });
});

test('an isolate that throws is not poisoned — the next request is served', async () => {
  await withService('throws', { isolates: 1 }, async (service) => {
    await refusalOf(() => service.renderToString({ protocol: 1, entry: 'app/before', state: {} }));
    const err = await refusalOf(() => service.renderToString({ protocol: 1, entry: 'app/before', state: {} }));
    assert.deepStrictEqual([err.code, service.stats().replacements], [CODE.RENDER_THREW, 0]);
  });
});

test('closing the service refuses an in-flight render', async () => {
  const service = await createService({ modulePath: fixture('hang'), isolates: 1, admissionTimeoutMs: 10000 });
  const inFlight = refusalOf(() => service.renderToString(hang({ timeoutMs: 30000 })));
  await new Promise((resolve) => setImmediate(resolve));
  await service.close();
  const err = await inFlight;
  assert.deepStrictEqual([err.code, err.detail], [CODE.SERVICE_CLOSED, {}]);
});

// ---------------------------------------------------------------------------
// A worker that EXITS before it is ready raises no `error`, posts no
// `boot-error`, and clears the boot timer, so only the exit can settle
// startup. `bootTimeoutMs` stays at its 30 s default, far past the bound
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
    assert.deepStrictEqual([outcome.state, outcome.error.code], ['rejected', CODE.MALFORMED_MODULE]);
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
