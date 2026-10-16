'use strict';
// THE SERVICE — validate, admit, render, release.
//
// ## ONE CALL, ONE BODY
//
// `renderToString` validates a request, borrows an isolate, and resolves
// with the `complete` response: the body markup the render module
// returned, beside the facts this service owns about the crossing. Or it
// throws a `Refusal`. That is the whole API, because one buffered body is
// the whole of what either side of this service reads — see
// `protocol.cjs`.
//
// ## REFUSALS COME BEFORE THE ISOLATE
//
// `validateRequest` runs to completion before an isolate is acquired, so
// every caller-fault refusal is delivered without one being borrowed.
//
// ## THE RESPONSE CARRIES NO APPLICATION DATA OUTSIDE `html`
//
// `renderToString` is the widest surface this package has: every transport
// is an adapter over it and the in-process caller reads it raw, so
// whatever it returns IS the egress. What it returns is `COMPLETE_FIELDS` —
// the body markup in `html`, and nothing else the application's render
// module authored. `test/egress.test.cjs` is that property's witness, and
// it checks this response rather than the HTTP one, because HTTP dropping a
// field is a fact about HTTP and not a guarantee about this.

const {
  CODE,
  Refusal,
  RENDER_THREW_REFUSAL,
  validateRequest,
  completeFrame,
} = require('./protocol.cjs');
const { Pool } = require('./pool.cjs');
const { PROTOCOL_VERSION } = require('./protocol.cjs');

/**
 * The operator's copy of a render that rejected with something this
 * package's own contract does not describe.
 *
 * A fault reaching this is a fault in the SIDECAR rather than in the
 * application — every receiver between here and the module builds a
 * `Refusal`, so anything else got past all of them — and it is the one
 * class of failure nothing upstream will have logged. Without this the
 * wording handed to the caller would be the only copy in existence, which
 * is the trade `isolate.cjs`'s `reportIsolateFault` exists to refuse.
 */
function reportUncontractedFault(err) {
  const trace = err && err.stack ? err.stack : String(err);
  process.stderr.write(
    `[rf.ssr-node] a render rejected with something other than a Refusal — this is a fault in ` +
      `the sidecar, not in the application: ${trace}\n`,
  );
}

class Service {
  constructor(pool, limits) {
    this.pool = pool;
    this.limits = limits;
    this.protocol = PROTOCOL_VERSION;
  }

  get buildId() {
    return this.pool.buildId;
  }

  get entries() {
    return this.pool.entries;
  }

  stats() {
    return this.pool.stats();
  }

  /** True once a replacement isolate failed to boot: the service has closed for good. */
  get failed() {
    return this.pool.failed === true;
  }

  /** Resolves once a failed replacement has closed the service. */
  get whenFailed() {
    return this.pool.whenFailed;
  }

  /**
   * The one call. Resolves with the `complete` response — `html` and the
   * rest of `COMPLETE_FIELDS`. Throws a `Refusal`, and nothing else.
   */
  async renderToString(request) {
    const validatedRequest = validateRequest(
      request,
      { buildId: this.pool.buildId, entries: this.pool.entries },
      this.limits,
    );

    const isolate = await this.pool.acquire();
    let rendered;
    try {
      rendered = await isolate.render(validatedRequest, { timeoutMs: validatedRequest.timeoutMs });
    } catch (error) {
      if (error instanceof Refusal) throw error;
      // THE LAST RECEIVER, and it states the same law as the other three:
      // the contract's wording, never `String(error)`. What arrives here is
      // not guaranteed to be this package's own text. A request value that
      // fails to structured-clone makes `postMessage` throw `DataCloneError`
      // synchronously inside `render()`'s executor, which passes through no
      // receiver that could make it a `Refusal` — and a `DataCloneError`
      // names the value it choked on, so `String(error)` would interpolate
      // the CALLER'S value into a message published on the widest surface
      // this package has, the egress this file's header says does not exist.
      //
      // A caller cannot reach this that way: `validateRequest` reads every
      // field exactly once and returns what it read, so a request that
      // validates is a request that clones.
      //
      // BEING UNREACHABLE FROM A CALLER IS NOT A REASON TO REMOVE THE ARM.
      // It is what guarantees `renderToString` throws nothing but a
      // `Refusal`, which is the property every transport is written
      // against; deleting it would let a raw `Error` reach `statusFor` with
      // no code at all — and `isolate.cjs`'s `render` deliberately lets a
      // `postMessage` that throws reject RAW rather than dressing it as a
      // `Refusal`, precisely so this arm is what states the wording and
      // writes the operator's copy.
      reportUncontractedFault(error);
      throw new Refusal(CODE.RENDER_THREW, RENDER_THREW_REFUSAL, {});
    } finally {
      // Whichever way the render went: a dead isolate is replaced by the
      // pool, a live one is offered to the next caller.
      this.pool.release(isolate);
    }

    return completeFrame({
      html: rendered.html,
      renderMs: rendered.renderMs,
      buildId: rendered.buildId,
      requestId: validatedRequest.requestId,
    });
  }

  async close() {
    await this.pool.close();
  }
}

/**
 * Boot a service against an application's server bundle.
 *
 * `modulePath` must be absolute — a relative path would resolve against
 * the worker file's directory rather than the caller's, which is the kind
 * of surprise that only shows up in someone else's deployment.
 */
async function createService({
  modulePath,
  isolates = 2,
  defaultTimeoutMs = 1000,
  maxTimeoutMs = 5000,
  admissionTimeoutMs = 250,
  maxRequestBytes = 1 << 20,
  bootTimeoutMs = 30000,
}) {
  if (typeof modulePath !== 'string' || !modulePath.length) {
    throw new Error('createService needs a `modulePath` to the application server bundle');
  }
  const pool = await new Pool({
    modulePath,
    size: isolates,
    admissionTimeoutMs,
    bootTimeoutMs,
  }).start();
  return new Service(pool, { defaultTimeoutMs, maxTimeoutMs, maxRequestBytes });
}

module.exports = { createService, Service };
