'use strict';
// THE ISOLATE'S INSIDE — this file runs in the worker thread.
//
// One thread is one isolate: its own heap, module registry and copy of the
// bundle, so the module's process-scoped state is per-isolate. It boots
// once and serves requests one after another, so module-level state persists
// between requests on one isolate; the per-request frame is the module's to
// make and destroy (README §1). Worker threads are not a sandbox, and every
// isolate shares the process's memory.
//
// Each request's `state` and `runtime` arrive by structured clone — the
// isolation — and are frozen before the module sees them, a diagnostic
// that throws on a write in strict mode. Every value is EDN text, so the
// shallow freeze is a deep one.
//
// The returned string is the module's only way out, and this is the one
// place that holds: a return that is not a string is refused by its type
// tag, never coerced or forwarded, and a throw is refused with the
// contract's wording (README §The response leg). A second concurrent
// `render` is refused too, though the pool never sends one.

const { parentPort, workerData } = require('node:worker_threads');
const { CODE, validateModule, RENDER_THREW_REFUSAL } = require('./protocol.cjs');

const postMessage = (message) => parentPort.postMessage(message);

/**
 * The operator's copy of a render exception, stack and all, on the
 * sidecar's stderr under its `[rf.ssr-node]` prefix — the refusal carries
 * none of it. `requestId` is the JVM's request frame id, so a 5xx is
 * matched to its stack by id rather than by clock.
 */
function reportRenderException(entry, err, requestId) {
  const trace = err && err.stack ? err.stack : String(err);
  const request = requestId === undefined ? '' : ` (request ${requestId})`;
  process.stderr.write(`[rf.ssr-node] render threw in entry ${entry}${request}: ${trace}\n`);
}

/**
 * The operator's copy of an exception that stopped this isolate booting —
 * thrown while the module loaded or from its `boot` hook. The `boot-error`
 * post carries `code` and `message` only, so this is where the stack lives.
 */
function reportBootException(modulePath, err) {
  const trace = err && err.stack ? err.stack : String(err);
  process.stderr.write(`[rf.ssr-node] the render module failed to boot from ${modulePath}: ${trace}\n`);
}

let renderModule = null;
let busy = false;

function boot() {
  try {
    // A dynamic require: the service loads the bundle it was pointed at.
    renderModule = validateModule(require(workerData.modulePath), workerData.modulePath);
    if (typeof renderModule.boot === 'function') renderModule.boot();
  } catch (err) {
    // A caught exception raises no `error` on the parent's `Worker`, so this
    // is its only report. No `stack` on the post: the parent reads `code`
    // and `message` only. Nullish-safe, for a module that throws `null`.
    reportBootException(workerData.modulePath, err);
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
    entries: [...renderModule.entries],
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

  // Both partitions frozen, and the call itself, so a module cannot rewrite
  // its own entry id and have anything downstream believe it.
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
    // `await` either way: the deadline is the parent's timer, and it governs
    // a promised body exactly as it does a returned one.
    const html = await renderModule.render(renderCall);
    const renderMs = Number(process.hrtime.bigint() - renderStartedAtNs) / 1e6;

    // THE EGRESS DOOR. A string, the empty one included, is the whole
    // accepted set. The refusal names the type tag, never the value.
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

    // The body, and a clock this file owns — nothing else for the module to fill.
    postMessage({ t: 'complete', id: renderId, html, renderMs });
  } catch (err) {
    // THE EXCEPTION DOOR. The module's `message`, `detail` and `code` stay
    // here — usually built from the data being rendered, and a module-set
    // `code` would choose its own HTTP status — and go to stderr in full.
    // `entry` is the caller's own identifier, handed back.
    reportRenderException(request.entry, err, request.requestId);
    postMessage({
      t: 'error',
      id: renderId,
      code: CODE.RENDER_THREW,
      message: RENDER_THREW_REFUSAL,
      detail: { entry: request.entry },
    });
  } finally {
    busy = false;
  }
}

parentPort.on('message', (message) => {
  if (message && message.t === 'render') {
    // Not awaited: `handleRender` posts its own answer. Anything reaching
    // the catch is a fault in this file, answered in the same wording.
    handleRender(message.id, message.request).catch((err) => {
      reportRenderException(message.request?.entry, err, message.request?.requestId);
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
