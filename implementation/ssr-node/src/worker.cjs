'use strict';
// THE ISOLATE'S INSIDE (rf2-hic-056).
//
// One worker thread is one isolate: its own V8 heap, its own module
// registry, its own copy of the application's server bundle. That is not
// an implementation detail, it is the reason the design works. The
// Hicasso render entry opens a MODULE-LEVEL adoption-window flag around
// its `renderToString`, the reactive substrate is installed exactly once
// per process (Spec 006), and the framework's registrar is likewise
// process-scoped. Every one of those is per-ISOLATE here, so "per-request
// isolation" does not have to mean "the render module was careful".
//
// This file runs INSIDE the worker. It never touches the network or the
// filesystem beyond requiring the module it was pointed at.
//
// ## What "immutable snapshot" means, stated exactly
//
// Two mechanisms, and they are not the same strength:
//
//   1. THE CLONE. `state` arrives by structured clone across the thread
//      boundary, so the isolate's copy shares no memory with the parent's
//      and no memory with any other request's. This is the guarantee. It
//      does not depend on the render module behaving.
//   2. THE FREEZE. The clone is frozen before the module sees it, so a
//      module that writes to its snapshot finds out. In a strict-mode
//      module — which every shadow-cljs output and every ES module is —
//      that is a `TypeError` at the write. In a sloppy-mode CommonJS
//      module the write fails SILENTLY. The freeze is therefore a
//      diagnostic, not the isolation; both cases are witnessed in
//      `test/isolation.test.cjs`, the second one by showing that the
//      mutation reaches nothing even when nothing threw.
//
// A shallow freeze is a deep freeze here, and that is by construction
// rather than by luck: `validateRequest` has already established that
// every value in `state` is a STRING, and strings are immutable. There is
// no nested object to walk, which is exactly why the wire carries EDN text
// per key rather than decoded application data.
//
// ## ONE IN-FLIGHT RENDER, ENFORCED HERE TOO
//
// The pool never dispatches to a busy isolate, so a second `render`
// arriving here is a bug in the pool rather than a caller's mistake. It is
// still refused rather than queued: a guard that trusts its one caller is
// a guard that stops holding the day a second caller appears.

const { parentPort, workerData } = require('node:worker_threads');
const { CODE, validateModule } = require('./protocol.cjs');

const post = (msg) => parentPort.postMessage(msg);

let mod = null;
let busy = false;

function boot() {
  try {
    // eslint-disable-next-line import/no-dynamic-require
    mod = validateModule(require(workerData.modulePath), workerData.modulePath);
    if (typeof mod.boot === 'function') mod.boot();
  } catch (err) {
    post({
      t: 'boot-error',
      code: err.code ?? CODE.MALFORMED_MODULE,
      message: err.message,
      stack: err.stack,
    });
    return;
  }
  post({
    t: 'ready',
    buildId: mod.buildId,
    entries: Object.fromEntries(
      Object.entries(mod.entries).map(([id, e]) => [id, { stateAllowlist: [...e.stateAllowlist] }]),
    ),
  });
}

async function render(id, request) {
  if (busy) {
    post({
      t: 'error',
      id,
      code: CODE.SERVICE_SATURATED,
      message: 'this isolate already has a render in flight; one at a time',
      detail: {},
    });
    return;
  }
  busy = true;

  let seq = 0;
  let emitted = false;
  const emit = (html) => {
    if (typeof html !== 'string') {
      throw new TypeError(
        `emit() takes body markup as a string; got ${Object.prototype.toString.call(html)}`,
      );
    }
    emitted = true;
    // Straight out, unjoined and unbuffered. A chunk held here so that
    // the isolate could hand back "the body" would be exactly the
    // one-complete-string assumption this protocol exists without.
    post({ t: 'chunk', id, seq: seq++, html });
  };

  // Frozen before the module sees it — see the header. The request object
  // itself is frozen too, so a module cannot rewrite its own entry id or
  // policy and have anything downstream believe it.
  const snapshot = Object.freeze({ ...request.state });
  const call = Object.freeze({
    entry: request.entry,
    state: snapshot,
    args: request.args,
  });

  const started = process.hrtime.bigint();
  try {
    // `await` even for a synchronous module: a module that returns a
    // promise is the streaming shape's, and the deadline governs both
    // identically because it is the PARENT's timer.
    const out = (await mod.render(call, emit)) ?? {};
    const renderMs = Number(process.hrtime.bigint() - started) / 1e6;

    if (!emitted) {
      post({
        t: 'error',
        id,
        code: CODE.RENDER_THREW,
        message: 'the render module returned without emitting any body markup',
        detail: { entry: request.entry },
      });
      return;
    }
    post({
      t: 'complete',
      id,
      chunks: seq,
      renderMs,
      meta: out.meta ?? {},
    });
  } catch (err) {
    post({
      t: 'error',
      id,
      code: err.code ?? CODE.RENDER_THREW,
      message: err.message ?? String(err),
      detail: err.detail ?? {},
      stack: err.stack,
      // A caller that already received chunks cannot be told "instead of"
      // any more, and this says so rather than pretending otherwise. The
      // service turns a post-chunk failure into a torn response the
      // transport must not present as complete.
      afterChunks: seq,
    });
  } finally {
    busy = false;
  }
}

parentPort.on('message', (msg) => {
  if (msg && msg.t === 'render') {
    // Deliberately not awaited: `render` posts its own frames, and an
    // unhandled rejection here would be a second failure channel with no
    // request id on it.
    render(msg.id, msg.request).catch((err) => {
      post({ t: 'error', id: msg.id, code: CODE.RENDER_THREW, message: String(err) });
    });
  }
});

boot();
