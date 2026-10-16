'use strict';
// THE ISOLATE'S INSIDE.
//
// One worker thread is one isolate: its own V8 heap, its own module
// registry, its own copy of the application's server bundle. That is not
// an implementation detail, it is the reason the design works. The
// Fresco render entry opens a MODULE-LEVEL adoption-window flag around
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
// every value in `state` — and in `runtime`, the other partition of the
// same settled frame — is a STRING, and strings are immutable. There is
// no nested object to walk, which is exactly why the wire carries EDN text
// per key rather than decoded application data.
//
// ## ONE IN-FLIGHT RENDER, ENFORCED HERE TOO
//
// The pool never dispatches to a busy isolate, so a second `render`
// arriving here is a bug in the pool rather than a caller's mistake. It is
// still refused rather than queued: a guard that trusts its one caller is
// a guard that stops holding the day a second caller appears.
//
// ## THE RETURNED STRING IS THE MODULE'S ONLY WAY OUT, AND THIS FILE IS WHERE THAT IS TRUE
//
// Everything else in this package can only carry what this file posts, so
// the topology — *Node returns the body markup, and nothing else* — is
// enforceable in exactly one place and it is here. `render` returns the
// body markup as a string, or a promise of one. That string is the `html`
// of the `complete` message below, and every other field on it is a clock
// this file owns.
//
// Anything other than a string is REFUSED rather than coerced or dropped,
// and that distinction matters. A returned object forwarded to the caller —
// as `meta`, say — over an HTTP transport that happens not to serialise it
// would look fine, with "the current transport drops it" silently standing
// in for a guarantee, while the in-process `renderToString` API, and any
// future socket or pipe adapter, carried it in full. `String()` would
// publish whatever the object prints as. A channel that refuses is a
// channel that stays closed.

const { parentPort, workerData } = require('node:worker_threads');
const { CODE, validateModule, RENDER_THREW_REFUSAL } = require('./protocol.cjs');

const postMessage = (message) => parentPort.postMessage(message);

/**
 * The operator's copy of a render exception — everything the refusal
 * deliberately does not carry.
 *
 * TWO AUDIENCES, TWO CHANNELS, and the distinction is the reason closing
 * the refusal costs nothing. The caller across the wire is a different
 * process on a different machine holding a contract; the operator is
 * inside this one, already reading its output. So the exception goes where
 * an operator already looks and never where a contract is published.
 *
 * NO SEPARATE SUBSYSTEM. `bin/serve.cjs` writes `[rf.ssr-node] …` to
 * stderr, and this is that stream under that prefix — a worker thread's
 * stderr is piped to the parent process's, so a line written here lands in
 * the sidecar's log beside the rest. There is no flag: a diagnostic that
 * can be switched off is one that is off on the day it is wanted, and this
 * is the only copy of the exception.
 *
 * The stack, not the message alone. It opens with the error's own name and
 * message, so nothing is dropped by preferring it, and it adds the frames
 * — which is the half a refusal could never have carried anyway.
 */
function reportRenderException(entry, err) {
  const trace = err && err.stack ? err.stack : String(err);
  process.stderr.write(`[rf.ssr-node] render threw in entry ${entry}: ${trace}\n`);
}

/**
 * The operator's copy of an exception that stopped this isolate booting —
 * thrown while the module was loaded or from its `boot` hook. The same
 * stream under the same prefix as `reportRenderException`, and for the same
 * reason: the `boot-error` post carries `code` and `message` only, so this
 * write is the one place the application's stack and call site survive.
 */
function reportBootException(modulePath, err) {
  const trace = err && err.stack ? err.stack : String(err);
  process.stderr.write(`[rf.ssr-node] the render module failed to boot from ${modulePath}: ${trace}\n`);
}

let renderModule = null;
let busy = false;

function boot() {
  try {
    // A dynamic require, and necessarily so: the whole design is that the
    // service loads an application's bundle it was pointed at rather than
    // one it was compiled against.
    renderModule = validateModule(require(workerData.modulePath), workerData.modulePath);
    if (typeof renderModule.boot === 'function') renderModule.boot();
  } catch (err) {
    // The stack goes to the operator first, on stderr; see
    // `reportBootException`. A caught exception raises no `error` event on
    // the parent's `Worker`, so nothing else ever sees it.
    reportBootException(workerData.modulePath, err);
    // NO `stack` on the post. The parent's boot receiver builds its
    // `Refusal` from `code` and `message` and names the module path itself;
    // a stack posted here would be serialised across the thread boundary and
    // dropped, while reading as though the application's trace survived
    // into the refusal.
    //
    // Nullish-safe for the same reason the parent's `error` handler is: a
    // module that throws `null` at boot would otherwise be refused with
    // "Cannot read properties of null", which says nothing about the module.
    postMessage({
      t: 'boot-error',
      code: err?.code ?? CODE.MALFORMED_MODULE,
      message: err?.message ?? String(err),
    });
    return;
  }
  postMessage({
    t: 'ready',
    buildId: renderModule.buildId,
    entries: Object.fromEntries(
      Object.entries(renderModule.entries).map(([entryId, entryConfig]) => [
        entryId,
        {
          stateAllowlist: [...entryConfig.stateAllowlist],
          runtimeAllowlist: [...entryConfig.runtimeAllowlist],
        },
      ]),
    ),
  });
}

async function handleRender(renderId, request) {
  if (busy) {
    postMessage({
      t: 'error',
      id: renderId,
      code: CODE.SERVICE_SATURATED,
      message: 'this isolate already has a render in flight; one at a time',
      detail: {},
    });
    return;
  }
  busy = true;

  // Frozen before the module sees it — see the header; both partitions,
  // because each is a snapshot of the same settled frame. The request
  // object itself is frozen too, so a module cannot rewrite its own entry
  // id or policy and have anything downstream believe it.
  const stateSnapshot = Object.freeze({ ...request.state });
  const runtimeSnapshot = Object.freeze({ ...request.runtime });
  const renderCall = Object.freeze({
    entry: request.entry,
    state: stateSnapshot,
    runtime: runtimeSnapshot,
    args: request.args,
  });

  const renderStartedAtNs = process.hrtime.bigint();
  try {
    // `await` even for a synchronous module, so a module that returns a
    // promise of its body is governed identically — the deadline is the
    // PARENT's timer, and it reaches both.
    const html = await renderModule.render(renderCall);
    const renderMs = Number(process.hrtime.bigint() - renderStartedAtNs) / 1e6;

    // THE EGRESS DOOR. See the header.
    //
    // A string is the whole of the accepted set — the empty string
    // included, because an empty body is a body. `undefined` (a function
    // that fell off its end), `null`, a number, an object and a promise of
    // any of those are refused alike.
    //
    // The refusal names the TYPE TAG and never the value. A diagnostic that
    // echoed what the module returned would be the same egress wearing a
    // different message type.
    if (typeof html !== 'string') {
      postMessage({
        t: 'error',
        id: renderId,
        code: CODE.RENDER_THREW,
        message: 'the render module returned no body markup: `render` must return a string, or a promise of one',
        detail: {
          entry: request.entry,
          returned: Object.prototype.toString.call(html),
        },
      });
      return;
    }

    // The body, and a clock this file owns. There is no field here for the
    // module to fill in beyond its markup, which is the point.
    postMessage({ t: 'complete', id: renderId, html, renderMs });
  } catch (err) {
    // THE EXCEPTION DOOR, and it is the same door as the egress door
    // above — a render module leaves this isolate by returning or by
    // throwing, and both are watched.
    //
    // Copying `err.code`, `err.message` and `err.detail` onto the protocol
    // would put three application-authored fields into the public
    // `Refusal` and the HTTP JSON body. That reads as diagnostic
    // generosity and it is the very leak the return door refuses, arriving
    // through the other one. It is also the LIKELIER of the two, which is
    // the part worth internalising: returning a payload takes a module
    // doing something unusual, whereas an exception built from the value
    // being processed is what every renderer already produces — React
    // names the undefined property, a validator quotes the input, a
    // template interpolates the row.
    //
    // `code` is a second, distinct failure. `http.cjs` maps the refusal
    // code to a status, so a module that set `err.code` to a member of
    // this service's own closed family could present its own bug as a 400
    // the caller blames itself for, a 503 a retry policy sleeps on, or a
    // 504 — the refusal taxonomy describes THIS SERVICE'S crossing, and
    // the application is not one of its authors.
    //
    // So: one code, one wording, and a `detail` this file builds. The
    // `entry` is the caller's own identifier handed back — the same
    // reasoning `requestId` gets on the `complete` frame — and it does not
    // originate in the module.
    //
    // NOTHING IS LOST BY IT, because a refusal is not where an operator
    // reads a stack anyway. The real exception goes to stderr below, in
    // full; see `reportRenderException`.
    reportRenderException(request.entry, err);
    postMessage({
      t: 'error',
      id: renderId,
      code: CODE.RENDER_THREW,
      message: RENDER_THREW_REFUSAL,
      detail: { entry: request.entry },
      // NO `stack`, for the reason the boot path gives: the render
      // receiver reads `code`, `message` and `detail`, so a stack posted
      // here would cross the boundary only to be dropped — a path-bearing
      // application trace serialised on every render throw, and a field
      // that would make it look as though the trace reached the refusal. It
      // does not, and the egress rows are why it must not.
    });
  } finally {
    busy = false;
  }
}

parentPort.on('message', (message) => {
  if (message && message.t === 'render') {
    // Deliberately not awaited: `handleRender` posts its own answer, and an
    // unhandled rejection here would be a second failure channel with no
    // request id on it.
    handleRender(message.id, message.request).catch((err) => {
      // Service-owned here too. `handleRender` catches the module's own
      // throw, so anything reaching this last-resort arm is a fault in
      // THIS file rather than in the render — but `String(err)` would still
      // be an unbounded string on the response protocol, and a law with one
      // arm that states it differently is a law with a way around it. The
      // operator gets the real one, as everywhere else on this path.
      reportRenderException(message.request?.entry, err);
      postMessage({
        t: 'error',
        id: message.id,
        code: CODE.RENDER_THREW,
        message: RENDER_THREW_REFUSAL,
        detail: {},
      });
    });
  }
});

boot();
