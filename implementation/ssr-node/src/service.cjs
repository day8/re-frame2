'use strict';
// THE SERVICE — validate, admit, render, release.
//
// `renderToString` validates a request, borrows an isolate, and resolves
// with the `complete` response, or throws a `Refusal` and nothing else.
// Every transport is an adapter over it and the in-process caller reads it
// raw, so what it returns IS the egress: `COMPLETE_FIELDS`, of which only
// `html` comes from the render module (`test/egress.test.cjs` pins it here,
// not over HTTP).

const {
  CODE,
  PROTOCOL_VERSION,
  Refusal,
  RENDER_THREW_REFUSAL,
  validateRequest,
  completeFrame,
} = require('./protocol.cjs');
const { Pool } = require('./pool.cjs');

/**
 * The operator's line for a render that rejected with something other than
 * a `Refusal` — a fault in the sidecar itself, which nothing upstream has
 * logged.
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

  /**
   * The longest a request admitted now waits for its answer: the admission
   * budget, then the deadline ceiling. A graceful close drains for this long.
   */
  get answerWithinMs() {
    return this.limits.admissionTimeoutMs + this.limits.maxTimeoutMs;
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
      // The last receiver, which keeps "throws nothing but a `Refusal`" true
      // for every transport. Never `String(error)`: a `DataCloneError` from
      // `postMessage` names the value it could not clone.
      reportUncontractedFault(error);
      throw new Refusal(CODE.RENDER_THREW, RENDER_THREW_REFUSAL, {});
    } finally {
      this.pool.release(isolate, validatedRequest.requestId);
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
 * Boot a service against an application's server bundle. `modulePath` must
 * be absolute: a relative one would resolve against the worker file's
 * directory, not the caller's.
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
  return new Service(pool, { defaultTimeoutMs, maxTimeoutMs, maxRequestBytes, admissionTimeoutMs });
}

module.exports = { createService, Service };
