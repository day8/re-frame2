'use strict';
// THE EGRESS CONTROL — application data cannot cross outside body markup.
//
// Checked on the FRAMES `renderFrames()` yields, because that is the
// in-process API and the surface every transport adapts; HTTP dropping a
// field would be a fact about HTTP, not a guarantee. On the response leg:
//
//   1. the `complete` frame carries exactly the service-owned roster;
//   2. nothing the render module handled appears outside `chunk.html`;
//   3. a module that RETURNS a value (`null` included) is refused, and the
//      refusal carries none of it;
//   4. a module that THROWS is refused with service-owned wording, code and
//      detail, and cannot choose its HTTP status;
//   5. the same holds for an exception that ESCAPES the render call;
//   6. a REPLACEMENT isolate that cannot boot tells a waiting caller nothing
//      it authored;
//   7. a rejection that is not a `Refusal` carries nothing the caller sent.
//
// Every absence check has a control showing its sentinel was really there.

const test = require('node:test');
const assert = require('node:assert');

const { withService, collect, observed, refusalOf, post } = require('./_support.cjs');
const {
  CODE,
  MODULE_RETURN_REFUSAL,
  RENDER_THREW_REFUSAL,
  ISOLATE_LOST_REFUSAL,
  REPLACEMENT_FAILED_REFUSAL,
  isRefusalCode,
} = require('../src/protocol.cjs');
const { Service } = require('../src/service.cjs');
const { serve, statusFor } = require('../src/http.cjs');
const LEAKY = require('./fixtures/leaky.cjs');
const NULL_RETURN = require('./fixtures/null-return.cjs');
const THROWS_DATA = require('./fixtures/throws-data.cjs');
const THROWS_ASYNC = require('./fixtures/throws-async.cjs');

/**
 * Every place a sentinel appears in a frame sequence OUTSIDE body markup.
 * Chunks are stripped of `html` rather than skipped, so a field sprouting
 * on a chunk frame is seen too.
 */
function scanForEgress(frames, sentinels) {
  const findings = [];
  for (const frame of frames) {
    const rest = { ...frame };
    delete rest.html;
    const text = JSON.stringify(rest);
    for (const s of sentinels) {
      if (text.includes(s)) findings.push({ type: frame.type, sentinel: s, in: text });
    }
  }
  return findings;
}

/** Install a capture on this process's stderr; returns `[captured, restore]`. */
function captureStderr() {
  const captured = [];
  const realWrite = process.stderr.write;
  process.stderr.write = function (chunk, ...rest) {
    captured.push(String(chunk));
    return realWrite.call(this, chunk, ...rest);
  };
  return [captured, () => (process.stderr.write = realWrite)];
}

const prefixed = (log) => log.split('\n').some((line) => line.includes('[rf.ssr-node]'));

// Distinctive sentinels, so a hit can only be a leak.
const STATE = {
  ':todos': '"rf2-hic-056-todos-4b19ae"',
  ':route': '{:name :rf2-hic-056-route-7c02fd}',
};
const ARGS = '{:page "rf2-hic-056-args-51d8b0"}';
const SENTINELS = [
  STATE[':todos'].replace(/"/g, ''),
  'rf2-hic-056-route-7c02fd',
  'rf2-hic-056-args-51d8b0',
];

const req = (extra = {}) => ({
  protocol: 1,
  entry: 'app/root',
  state: STATE,
  args: ARGS,
  ...extra,
});

// ---------------------------------------------------------------------------
// 1. The roster
// ---------------------------------------------------------------------------

test('the complete frame carries EXACTLY the service-owned roster', async () => {
  await withService('reference', { isolates: 1 }, async (service) => {
    for (const echoed of [{}, { requestId: 'corr-1' }]) {
      const { renderMs, ...rest } = (await collect(service, req(echoed))).complete;
      assert.strictEqual(typeof renderMs, 'number');
      assert.deepStrictEqual(rest, { type: 'complete', chunks: 1, buildId: 'reference-build-1', ...echoed });
    }
  });
});

// ---------------------------------------------------------------------------
// 2. The scan
// ---------------------------------------------------------------------------

test('CONTROL — the scan finds a planted leak, in each of its two shapes', () => {
  const onComplete = [
    { type: 'chunk', seq: 0, html: `<p>${SENTINELS[0]}</p>` },
    { type: 'complete', chunks: 1, renderMs: 1, buildId: 'b', meta: { readTodos: SENTINELS[0] } },
  ];
  assert.strictEqual(scanForEgress(onComplete, SENTINELS).length, 1, 'a leak on the terminal frame');

  const onChunk = [
    { type: 'chunk', seq: 0, html: '<p>ok</p>', note: SENTINELS[2] },
    { type: 'complete', chunks: 1, renderMs: 1, buildId: 'b' },
  ];
  assert.strictEqual(scanForEgress(onChunk, SENTINELS).length, 1, 'a leak beside body markup');

  const clean = [
    { type: 'chunk', seq: 0, html: `<p>${SENTINELS[0]}${SENTINELS[1]}${SENTINELS[2]}</p>` },
    { type: 'complete', chunks: 1, renderMs: 1, buildId: 'b' },
  ];
  assert.deepStrictEqual(scanForEgress(clean, SENTINELS), [], 'a sentinel inside html is not a leak');
});

test('no value the render module handled crosses outside body markup', async () => {
  await withService('reference', { isolates: 1 }, async (service) => {
    const frames = [];
    for await (const frame of service.renderFrames(req({ requestId: 'corr-2' }))) {
      frames.push(frame);
    }
    // The module renders its todos, so the first sentinel is reachable.
    const body = frames.filter((f) => f.type === 'chunk').map((f) => f.html).join('');
    assert.ok(body.includes(SENTINELS[0]), 'the module did read and render the state');
    assert.deepStrictEqual(scanForEgress(frames, SENTINELS), [], 'application data reached a public frame outside body markup');
  });
});

test('the HTTP transport carries none of it either — corollary, not evidence', async () => {
  // Headers are their own egress surface.
  await withService('reference', { isolates: 1 }, async (service) => {
    const http = await serve({ service, port: 0 });
    try {
      const res = await post(`http://127.0.0.1:${http.port}/render`, req({ requestId: 'corr-3' }));
      assert.strictEqual(res.status, 200);
      assert.ok(res.text.includes(SENTINELS[0]), 'the body is the channel and it is carrying');

      const headers = JSON.stringify(Object.fromEntries(res.headers.entries()));
      for (const s of SENTINELS) {
        assert.ok(!headers.includes(s), `header carried ${s}`);
      }
    } finally {
      await http.close();
    }
  });
});

// ---------------------------------------------------------------------------
// 3. The refusal — a module that RETURNS
// ---------------------------------------------------------------------------

test('CONTROL — the leaky fixture really does return a payload', () => {
  const emitted = [];
  const out = LEAKY.render({ entry: 'app/root', state: { ':todos': '[1]' } }, (h) => emitted.push(h));
  assert.strictEqual(emitted.length, 1, 'it emits body markup like any other module');
  assert.strictEqual(out.meta.secret, LEAKY.SECRET);
  assert.strictEqual(out.meta.readTodos, '[1]', 'and it reads application state into the payload');
});

test('a render module that returns a value is REFUSED, not silently dropped', async () => {
  // The refusal names the SHAPE; echoing the value would be the same egress
  // through the error channel.
  await withService('leaky', { isolates: 1 }, async (service) => {
    const err = await refusalOf(() =>
      collect(service, { protocol: 1, entry: 'app/root', state: { ':todos': '"secret-state-3ab1"' } }),
    );
    assert.strictEqual(err.code, CODE.RENDER_THREW);
    assert.strictEqual(err.message, MODULE_RETURN_REFUSAL, 'the contract owns the wording');
    // It emitted before it returned, so the response is torn.
    assert.deepStrictEqual(err.detail, { entry: 'app/root', returned: '[object Object]', afterChunks: 1 });
    const text = `${err.message}${JSON.stringify(err.detail)}${err.stack ?? ''}`;
    assert.ok(!text.includes(LEAKY.SECRET), 'the refusal echoed the module’s own value');
    assert.ok(!text.includes('secret-state-3ab1'), 'the refusal echoed the request state');
  });
});

test('CONTROL — the null fixture really does return null, and not nothing', () => {
  const emitted = [];
  const out = NULL_RETURN.render({ entry: 'app/root', state: { ':todos': '[1]' } }, (h) => emitted.push(h));
  assert.strictEqual(emitted.length, 1, 'it emits body markup like any other module');
  assert.strictEqual(out, null);
});

test('a module that returns null is REFUSED too — `undefined` is the whole accepted set', async () => {
  // `return null` is the likeliest deliberate return a module has, so a door
  // that admitted it would fail open on the most probable path.
  await withService('null-return', { isolates: 1 }, async (service) => {
    const err = await refusalOf(() =>
      collect(service, { protocol: 1, entry: 'app/root', state: { ':todos': '[1]' } }),
    );
    assert.strictEqual(err.code, CODE.RENDER_THREW);
    assert.strictEqual(err.message, MODULE_RETURN_REFUSAL);
    assert.deepStrictEqual(err.detail, { entry: 'app/root', returned: '[object Null]', afterChunks: 1 });
  });
});

// ---------------------------------------------------------------------------
// 4. The OTHER door — a render module that THROWS
//
// An Error built from the value being processed is what every renderer
// produces on an ordinary bug, and `error.code` would choose the HTTP status.
// ---------------------------------------------------------------------------

// One sentinel per field of the thrown Error, each from its own state key.
const THROW_STATE = {
  ':for-code': '"rf2-c38b-code-1e4a77"',
  ':for-message': '"rf2-c38b-message-8b30d2"',
  ':for-detail': '"rf2-c38b-detail-c519f0"',
};
const THROW_SENTINELS = [
  'rf2-c38b-code-1e4a77',
  'rf2-c38b-message-8b30d2',
  'rf2-c38b-detail-c519f0',
];

const throwReq = (entry) => ({ protocol: 1, entry, state: THROW_STATE });

/** Sentinels on the refusal's wire frame or on its stack. */
const refusalLeaks = (err, sentinels = THROW_SENTINELS) => [
  ...scanForEgress([err.toFrame('corr-throw')], sentinels),
  ...sentinels.filter((s) => (err.stack ?? '').includes(s)).map((s) => ({ type: 'stack', sentinel: s, in: err.stack })),
];

test('CONTROL — the throwing fixture really does put all three sentinels on the Error', () => {
  let thrown = null;
  try {
    THROWS_DATA.render({ entry: 'app/plain', state: THROW_STATE }, () => {});
  } catch (err) {
    thrown = err;
  }
  assert.ok(thrown.message.includes(THROW_SENTINELS[1]), 'message carries its sentinel');
  assert.ok(String(thrown.code).includes(THROW_SENTINELS[0]), 'code carries its sentinel');
  assert.ok(thrown.detail.echoed.includes(THROW_SENTINELS[2]), 'detail carries its sentinel');
  assert.ok(thrown.detail.nested.deeper.includes(THROW_SENTINELS[2]), 'and it is NESTED, so a shallow scan would miss it');
  assert.strictEqual(new Set(THROW_SENTINELS).size, 3);
});

test('CONTROL — the spoofed codes really are members of the refusal family', () => {
  // Rename a member of `CODE` and the fixture's literals would map to 500 on
  // their own: the status row would stay green and stop testing a spoof.
  const members = new Set(Object.values(CODE));
  for (const [entry, code] of Object.entries(THROWS_DATA.SPOOFED_CODE)) {
    assert.ok(members.has(code), `${entry} spoofs ${code}, which is not a real code`);
    assert.notStrictEqual(
      statusFor(code),
      statusFor(CODE.RENDER_THREW),
      `${code} must map to a DIFFERENT status than render-threw, or there is nothing to spoof`,
    );
  }
  assert.strictEqual(Object.keys(THROWS_DATA.SPOOFED_CODE).length, 3, '400, 503 and 504');
});

test('a render that THROWS is refused with service-owned wording, carrying nothing it authored', async () => {
  await withService('throws-data', { isolates: 1 }, async (service) => {
    const err = await refusalOf(() => collect(service, throwReq('app/plain')));
    assert.strictEqual(err.code, CODE.RENDER_THREW, 'the documented code, not the module’s');
    assert.strictEqual(err.message, RENDER_THREW_REFUSAL, 'the contract owns the wording');
    assert.deepStrictEqual(err.detail, { entry: 'app/plain', afterChunks: 0 }, 'the detail is service-owned');
    assert.deepStrictEqual(refusalLeaks(err), [], 'the module’s exception reached the public refusal');
  });
});

test('a THROW after emitting is still a torn response, and still carries nothing', async () => {
  await withService('throws-data', { isolates: 1 }, async (service) => {
    const chunks = [];
    const err = await refusalOf(async () => {
      for await (const frame of service.renderFrames(throwReq('app/torn'))) {
        if (frame.type === 'chunk') chunks.push(frame.html);
      }
    });
    assert.deepStrictEqual(chunks, ['<p>first</p>'], 'the bytes really did leave');
    assert.strictEqual(err.code, CODE.RENDER_THREW);
    assert.strictEqual(err.message, RENDER_THREW_REFUSAL);
    assert.deepStrictEqual(err.detail, { entry: 'app/torn', afterChunks: 1 }, 'the tear is still named');
    assert.deepStrictEqual(refusalLeaks(err), []);
  });
});

test('and it cannot choose the HTTP status either — every throw is a 500', async () => {
  await withService('throws-data', { isolates: 1 }, async (service) => {
    const http = await serve({ service, port: 0 });
    try {
      const entries = ['app/plain', ...Object.keys(THROWS_DATA.SPOOFED_CODE)];
      for (const entry of entries) {
        const res = await post(`http://127.0.0.1:${http.port}/render`, throwReq(entry));
        assert.strictEqual(res.status, 500, `${entry} chose its own HTTP status`);
        assert.strictEqual(res.headers.get('x-rf-ssr-refusal'), CODE.RENDER_THREW, `${entry} chose its own refusal header`);

        const headers = JSON.stringify(Object.fromEntries(res.headers.entries()));
        for (const s of THROW_SENTINELS) {
          assert.ok(!res.text.includes(s), `${entry} leaked ${s} into the JSON body`);
          assert.ok(!headers.includes(s), `${entry} leaked ${s} into a header`);
        }
        assert.ok(JSON.parse(res.text).message === RENDER_THREW_REFUSAL, 'the body carries the contract’s wording');
      }
    } finally {
      await http.close();
    }
  });
});

test('a streaming response torn by a throw is DESTROYED, not completed', async () => {
  // Headers are already sent, so the socket is destroyed: the caller must
  // see a broken transfer, not a shorter page it would cache and serve.
  await withService('throws-data', { isolates: 1 }, async (service) => {
    const http = await serve({ service, port: 0 });
    try {
      const res = await fetch(`http://127.0.0.1:${http.port}/render?stream=1`, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify(throwReq('app/torn')),
      });
      assert.strictEqual(res.status, 200);
      const read = await res.text().then(
        (text) => ({ ok: true, text }),
        (err) => ({ ok: false, err }),
      );
      assert.strictEqual(read.ok, false, 'a torn stream must not read as a complete body');
      assert.ok(!String(read.err).includes(THROW_SENTINELS[1]), 'not even the transport error may carry the module’s wording');
    } finally {
      await http.close();
    }
  });
});

// ---------------------------------------------------------------------------
// 5. The SECOND RECEIVER — an exception that escapes the render call
//
// A throw from a callback the render scheduled has no `try` above it: the
// thread dies and `isolate.cjs`'s `worker.on('error')` builds the refusal.
// ---------------------------------------------------------------------------

const ASYNC_STATE = { ':for-uncaught': '"rf2-c38b-async-9f31c4"' };
const ASYNC_SENTINEL = 'rf2-c38b-async-9f31c4';

const asyncReq = (entry) => ({ protocol: 1, entry, state: ASYNC_STATE });

test('CONTROL — the scheduled callback really does throw the sentinel-bearing Error', () => {
  // The throw is uncaught by design, so the callback is captured instead of
  // allowed to fire, and thrown here on purpose.
  const scheduled = [];
  const realSetImmediate = globalThis.setImmediate;
  globalThis.setImmediate = (fn) => {
    scheduled.push(fn);
    return { unref() {} };
  };
  try {
    THROWS_ASYNC.render({ entry: 'app/uncaught', state: ASYNC_STATE }, () => {});
  } finally {
    globalThis.setImmediate = realSetImmediate;
  }

  assert.strictEqual(scheduled.length, 1, 'the render must schedule exactly one callback');
  let thrown = null;
  try {
    scheduled[0]();
  } catch (err) {
    thrown = err;
  }
  assert.ok(thrown.message.includes(ASYNC_SENTINEL), 'its message carries the sentinel');
  assert.strictEqual(thrown.code, THROWS_ASYNC.SPOOFED_CODE, 'it also types a `code`');
  assert.ok(new Set(Object.values(CODE)).has(THROWS_ASYNC.SPOOFED_CODE), 'the spoofed code must still be a real member of the family');
});

test('an exception that ESCAPES the render call carries nothing the module authored', async () => {
  await withService('throws-async', { isolates: 1 }, async (service) => {
    const err = await refusalOf(() => collect(service, asyncReq('app/uncaught')));
    // A crashed worker is not a reusable one, so the code stays isolate-lost.
    assert.strictEqual(err.code, CODE.ISOLATE_LOST, 'the fault is what it is');
    assert.strictEqual(err.message, ISOLATE_LOST_REFUSAL, 'the contract owns the wording');
    assert.deepStrictEqual(
      Object.keys(err.detail).sort(),
      ['afterChunks', 'isolate', 'threadId'],
      'the detail is service-owned: which isolate died, its thread, and the tear count',
    );
    assert.strictEqual(typeof err.detail.threadId, 'number');
    assert.strictEqual(err.detail.afterChunks, 0, 'nothing was written, so nothing is torn');
    assert.deepStrictEqual(refusalLeaks(err, [ASYNC_SENTINEL]), [], 'the escaped exception reached the public refusal');
    // A stack would also name the deployment's filesystem.
    assert.ok(!JSON.stringify(err.toFrame()).includes('throws-async.cjs'), 'a serialised stack names the server’s own filesystem');
  });
});

test('and the OPERATOR still gets the exception, in full, on the sidecar stderr', async () => {
  // The worker never caught this one, so the parent's write is the only copy.
  const [captured, restore] = captureStderr();
  try {
    await withService('throws-async', { isolates: 1 }, async (service) => {
      await refusalOf(() => collect(service, asyncReq('app/uncaught')));
    });
  } finally {
    restore();
  }
  const log = captured.join('');
  assert.ok(prefixed(log), 'the operator was told nothing at all');
  assert.ok(log.includes(ASYNC_SENTINEL), 'the operator copy must be the REAL exception');
  assert.ok(log.includes('throws-async.cjs'), 'and it must carry the stack, which is the point');
});

test('an escaped NULLISH throw is refused like any other, and the sidecar survives it', async () => {
  // CLJS emits `throw null` for `(throw nil)`; read unsafely in the parent's
  // `'error'` listener it would kill the whole process, not this row.
  await withService('throws-async', { isolates: 1 }, async (service) => {
    const err = await refusalOf(() => collect(service, asyncReq('app/uncaught-null')));
    assert.strictEqual(err.code, CODE.ISOLATE_LOST, 'the fault is what it is');
    assert.strictEqual(err.message, ISOLATE_LOST_REFUSAL, 'the contract owns the wording');

    const next = await refusalOf(() => collect(service, asyncReq('app/rejected')));
    assert.strictEqual(next.code, CODE.RENDER_THREW, 'the next render is answered by a live replacement isolate');
  });
});

// ---------------------------------------------------------------------------
// 6. The THIRD RECEIVER — a REPLACEMENT isolate that cannot boot
//
// A boot refusal carries the module's message, path and own `code` for the
// operator at a process that would not start. A replacement boots while the
// service is live, and its failure goes to callers queued in `acquire()`.
// ---------------------------------------------------------------------------

const FLAKY_BOOT = require('./fixtures/flaky-boot.cjs');
const { FAIL_FLAG, BOOT_SENTINEL, BOOT_SPOOF_CODE } = FLAKY_BOOT;

const tick = () => new Promise((resolve) => setImmediate(resolve));

/**
 * Hang the single isolate past its deadline with a second caller queued
 * behind it, arm the fixture's boot failure, and report what the queued
 * caller is handed when the replacement will not boot. The admission
 * timeout outlasts the deadline, so the waiter is still waiting.
 */
async function replacementBootFailure() {
  const [captured, restore] = captureStderr();
  try {
    return await withService(
      'flaky-boot',
      { isolates: 1, admissionTimeoutMs: 5000, defaultTimeoutMs: 300, maxTimeoutMs: 5000 },
      async (service) => {
        const hung = refusalOf(() => collect(service, { protocol: 1, entry: 'app/hang' }));
        await tick();
        await tick();
        const queued = refusalOf(() => collect(service, { protocol: 1, entry: 'app/root' }));
        await tick();
        await tick();
        const statsWhileQueued = service.stats();
        // Running isolates took their copy of `process.env` at construction,
        // so this reaches the replacement only.
        process.env[FAIL_FLAG] = '1';
        const [hungRefusal, queuedRefusal] = await Promise.all([hung, queued]);
        return {
          hungRefusal,
          queuedRefusal,
          statsWhileQueued,
          statsAfter: service.stats(),
          stderr: captured.join(''),
        };
      },
    );
  } finally {
    delete process.env[FAIL_FLAG];
    restore();
  }
}

test('CONTROL — the flaky fixture really does refuse to boot, with all three payloads', () => {
  const modulePath = require.resolve('./fixtures/flaky-boot.cjs');
  process.env[FAIL_FLAG] = '1';
  delete require.cache[modulePath];
  let thrown = null;
  try {
    require('./fixtures/flaky-boot.cjs');
  } catch (err) {
    thrown = err;
  } finally {
    delete process.env[FAIL_FLAG];
    delete require.cache[modulePath];
    require('./fixtures/flaky-boot.cjs'); // leave the cache holding the good one
  }
  assert.ok(thrown.message.includes(BOOT_SENTINEL), 'the armed fixture throws, carrying the sentinel on its message');
  assert.strictEqual(thrown.code, BOOT_SPOOF_CODE, 'and carry the spoofed code');
  assert.ok(isRefusalCode(BOOT_SPOOF_CODE), 'which must be a real member, or nothing is spoofed');
});

test('a replacement that cannot boot tells a WAITING CALLER nothing it authored, and tells the OPERATOR all of it', async () => {
  const run = await replacementBootFailure();
  // The scenario, read off the service's own counters.
  assert.strictEqual(run.statsWhileQueued.waiting, 1, 'a caller must be queued in acquire()');
  assert.strictEqual(run.statsWhileQueued.busy, 1, 'and the only isolate must be held by the hang');
  assert.strictEqual(run.hungRefusal.code, CODE.RENDER_TIMEOUT, 'the deadline is what kills it');
  assert.strictEqual(run.statsAfter.replacements, 1, 'and the pool must have tried to replace it');

  const text = JSON.stringify(run.queuedRefusal.toFrame('corr-boot'));
  assert.ok(!text.includes(BOOT_SENTINEL), 'the module\'s boot wording must not cross to a caller');
  assert.ok(!/[A-Za-z]:[\\/]|\/(?:home|srv|usr|opt)\//.test(text), `no absolute deployment path may cross either; got ${text}`);
  assert.strictEqual(run.queuedRefusal.message, REPLACEMENT_FAILED_REFUSAL, 'the wording is this contract\'s');
  // Not the module's spoofed code, so not a 503 a retry policy sleeps on.
  assert.strictEqual(run.queuedRefusal.code, CODE.ISOLATE_LOST, 'an isolate was lost and not replaced');
  assert.strictEqual(statusFor(run.queuedRefusal.code), 500);

  // The POOL's own line, written unconditionally: with no waiter queued the
  // refusal would reach no one. The worker's boot reporter writes a line too.
  const poolLine = run.stderr.split('\n').find((line) => line.includes('[rf.ssr-node] a replacement isolate failed to boot'));
  assert.ok(
    poolLine?.includes('flaky-boot.cjs') && poolLine.includes(BOOT_SENTINEL),
    `the operator must be told the REAL failure and the module that would not load: ${run.stderr}`,
  );
});

/** The fixture's own `throw` frame — the refusal's stack never carries it. */
const FLAKY_CALL_SITE = /flaky-boot\.cjs:\d+:\d+/;

test('a module that throws while LOADING leaves its own stack on the sidecar stderr, and none on its refusal', async () => {
  const { Isolate } = require('../src/isolate.cjs');
  const [captured, restore] = captureStderr();
  process.env[FAIL_FLAG] = '1'; // read when the worker thread is constructed
  let error;
  try {
    const isolate = new Isolate({ modulePath: require.resolve('./fixtures/flaky-boot.cjs') });
    const started = isolate.start();
    // The thread's stderr arrives through a pipe, so read it once the thread has exited.
    const exited = new Promise((resolve) => isolate.worker.once('exit', resolve));
    error = await started.then(
      () => null,
      (err) => err,
    );
    if (!error) await isolate.close();
    await exited;
    await tick();
  } finally {
    delete process.env[FAIL_FLAG];
    restore();
  }
  const stderr = captured.join('');
  assert.strictEqual(error.name, 'Refusal', 'the boot receiver builds a Refusal');
  assert.ok(prefixed(stderr), 'the operator was told nothing at all');
  assert.ok(stderr.includes(BOOT_SENTINEL), 'the operator copy must be the REAL exception');
  assert.ok(FLAKY_CALL_SITE.test(stderr), `the operator copy must carry the application's call site; got ${stderr}`);
  assert.ok(!FLAKY_CALL_SITE.test(error.stack), 'the refusal is a new Error with frames of its own');
  assert.deepStrictEqual(Object.keys(error.detail), ['modulePath'], 'the boot refusal names the module path only');
  assert.ok(!FLAKY_CALL_SITE.test(JSON.stringify(error.toFrame())), 'no application frame reaches the refusal frame');
});

// ---------------------------------------------------------------------------
// 7. The FOURTH RECEIVER — a rejection that is not a `Refusal` at all
//
// A `DataCloneError` from `postMessage` names the value it choked on. The
// first row shows no caller can reach it (the clone reads the validator's
// copy); the second drives the last-resort arm at its own seam.
// ---------------------------------------------------------------------------

const CLONE_SENTINEL = 'rf2-2hmg-caller-7c19ab';

/** A `state` whose one value is a string on its first read and a sentinel-bearing Symbol after. */
function twoFacedState() {
  const state = {};
  let reads = 0;
  Object.defineProperty(state, ':route', {
    enumerable: true,
    configurable: true,
    get() {
      reads += 1;
      return reads === 1 ? '{:name :ok}' : Symbol(CLONE_SENTINEL);
    },
  });
  return { state, reads: () => reads };
}

test('the CLONE gets no second say — the validator keeps what it checked', async () => {
  // A second read would mean the caller's own object reached `postMessage`.
  const twoFaced = twoFacedState();
  const run = await withService('reference', { isolates: 1 }, (service) =>
    collect(service, { protocol: 1, entry: 'app/root', state: twoFaced.state }),
  );
  assert.strictEqual(twoFaced.reads(), 1, 'nothing may read the caller\'s value a second time');
  assert.strictEqual(run.chunks.length, 1, 'and the request must render rather than refuse');
  assert.strictEqual(observed(run).readRoute, '{:name :ok}', 'the module must be handed the value the validator approved');
});

/** A `Service` over a stand-in pool whose one isolate rejects with a raw `error`. */
function serviceOverRejectingIsolate(error) {
  const pool = {
    buildId: 'reference-build-1',
    entries: { 'app/root': { stateAllowlist: [':route'], runtimeAllowlist: [] } },
    acquire: async () => ({ render: () => Promise.reject(error) }),
    release: () => {},
  };
  return new Service(pool, { defaultTimeoutMs: 1000, maxTimeoutMs: 5000, maxRequestBytes: 1 << 20 });
}

test('a rejection that is not a Refusal carries nothing the CALLER authored, and the OPERATOR still gets the fault', async () => {
  const [captured, restore] = captureStderr();
  let refusal;
  try {
    // The wording a real `DataCloneError` carries: the caller's own value.
    const service = serviceOverRejectingIsolate(new TypeError(`Symbol(${CLONE_SENTINEL}) could not be cloned.`));
    refusal = await refusalOf(() =>
      collect(service, { protocol: 1, entry: 'app/root', state: { ':route': '{:name :ok}' } }),
    );
  } finally {
    restore();
  }
  const stderr = captured.join('');
  assert.strictEqual(refusal.code, CODE.RENDER_THREW, 'refused BY the last-resort arm');
  assert.ok(!JSON.stringify(refusal.toFrame('corr-clone')).includes(CLONE_SENTINEL), 'the caller\'s own value must not be reflected back');
  assert.strictEqual(refusal.message, RENDER_THREW_REFUSAL, 'the wording is this contract\'s');
  assert.ok(prefixed(stderr), 'the operator was told nothing at all');
  assert.ok(stderr.includes(CLONE_SENTINEL), 'the operator copy must be the REAL fault');
});

// ---------------------------------------------------------------------------
// 8. The EXIT arm of `isolate-lost` — a thread that exits rather than crashing
//
// `exits.cjs` throws nothing, so only `worker.on('exit')` can answer. A
// consumer tells the isolate-lost causes apart by detail shape alone.
// ---------------------------------------------------------------------------

const exitReq = (entry) => ({ protocol: 1, entry, state: { ':for-exit': 'x' } });

test('an isolate that EXITS mid-render names WHICH isolate and WHICH thread', async () => {
  await withService('exits', { isolates: 1 }, async (service) => {
    const err = await refusalOf(() => collect(service, exitReq('app/exits')));
    assert.strictEqual(err.code, CODE.ISOLATE_LOST, 'the fault is what it is');
    assert.deepStrictEqual(
      Object.keys(err.detail).sort(),
      ['afterChunks', 'isolate', 'threadId'],
      'the exit arm must identify the isolate the way its sibling arm does',
    );
    assert.strictEqual(typeof err.detail.isolate, 'number', 'which isolate went');
    assert.strictEqual(typeof err.detail.threadId, 'number', 'and the thread an operator will look for');
    assert.strictEqual(err.detail.afterChunks, 0, 'nothing was written, so nothing is torn');
    assert.strictEqual(err.message, 'the isolate exited mid-render', 'the exit arm keeps its own wording');
  });
});

test('and a TORN exit still names the count beside the identifying fields', async () => {
  // The count comes from `_failPendingRender`: the worker is gone and cannot report it.
  await withService('exits', { isolates: 1 }, async (service) => {
    const chunks = [];
    let complete = null;
    const err = await refusalOf(async () => {
      for await (const frame of service.renderFrames(exitReq('app/exits-torn'))) {
        if (frame.type === 'chunk') chunks.push(frame.html);
        else complete = frame;
      }
    });
    assert.strictEqual(chunks.length, 1, 'the chunk really did reach the caller');
    assert.strictEqual(complete, null, 'no success completion follows the failure');
    assert.strictEqual(err.code, CODE.ISOLATE_LOST, 'the distinction is kept');
    assert.strictEqual(err.detail.afterChunks, 1, 'the tear is named, with its exact count');
    assert.deepStrictEqual(
      Object.keys(err.detail).sort(),
      ['afterChunks', 'isolate', 'threadId'],
      'identifying the isolate must not have added a field beyond the three',
    );
  });
});
