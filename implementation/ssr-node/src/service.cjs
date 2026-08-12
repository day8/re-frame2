'use strict';
// THE SERVICE (rf2-hic-056) — validate, admit, render, release.
//
// ## ONE CORE API, AND THE STRING IS A WRAPPER OVER IT
//
// `renderFrames` is an async generator yielding `chunk` frames and then
// one `complete` frame. `renderToString` iterates it and joins. That
// ordering is the separability requirement, made structural rather than
// promised: the string-shaped call is the DERIVED one, so a streaming
// caller is not asking for a second semantics, it is declining a
// convenience. Were the primitive the string, every layer beneath it
// would already hold the assumption that there is exactly one, and
// unwinding that later is the retrofit the bead is trying to avoid
// paying for.
//
// Nothing between the render module and the transport ever holds "the
// body". The isolate posts each chunk as the module emits it, the
// generator yields it, and the buffered HTTP mode is where — and the
// only place where — a complete string comes into existence.
//
// ## REFUSALS COME BEFORE BYTES
//
// `validateRequest` runs to completion before an isolate is acquired, so
// every caller-fault refusal is delivered with no chunk yet emitted. A
// failure that arrives AFTER chunks — the isolate dying under a render,
// or a streaming module throwing halfway — is a TORN response, carries
// `detail.afterChunks`, and the transport is required to treat it as
// unpresentable rather than as a shorter page.

const { CODE, Refusal, validateRequest, completeFrame } = require('./protocol.cjs');
const { Pool } = require('./pool.cjs');
const { PROTOCOL_VERSION } = require('./protocol.cjs');

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

  /**
   * The core call. Yields `{type:'chunk'}` frames as the render produces
   * them, then one `{type:'complete'}`. Throws a `Refusal`.
   */
  async *renderFrames(request) {
    const req = validateRequest(
      request,
      { buildId: this.pool.buildId, entries: this.pool.entries },
      this.limits,
    );

    const isolate = await this.pool.acquire();

    // A hand-rolled bridge from the isolate's callback to this generator,
    // because there is no smaller one: `onChunk` is called from a
    // 'message' handler and the consumer pulls at its own pace.
    const queue = [];
    let wake = null;
    const nudge = () => {
      if (wake) {
        const w = wake;
        wake = null;
        w();
      }
    };

    let finished = false;
    let failure = null;
    let terminal = null;

    const run = isolate
      .render(req, {
        timeoutMs: req.timeoutMs,
        onChunk: (frame) => {
          queue.push(frame);
          nudge();
        },
      })
      .then(
        (done) => {
          terminal = done;
        },
        (err) => {
          failure = err instanceof Refusal ? err : new Refusal(CODE.RENDER_THREW, String(err), {});
        },
      )
      .then(() => {
        finished = true;
        this.pool.release(isolate);
        nudge();
      });

    try {
      for (;;) {
        while (queue.length) yield queue.shift();
        if (finished) break;
        await new Promise((resolve) => {
          wake = resolve;
        });
      }
      await run;
      if (failure) throw failure;
      yield completeFrame({
        chunks: terminal.chunks,
        renderMs: terminal.renderMs,
        buildId: terminal.buildId,
        requestId: req.requestId,
        meta: terminal.meta,
      });
    } finally {
      // A consumer that abandons the iteration must not strand the
      // isolate. `run` releases it whichever way the render went, so this
      // only has to make sure the promise is not left unobserved.
      run.catch(() => {});
    }
  }

  /**
   * The convenience: one string, for the caller that is not streaming.
   * The join happens HERE and nowhere earlier — see the header.
   */
  async renderToString(request) {
    let html = '';
    let complete = null;
    for await (const frame of this.renderFrames(request)) {
      if (frame.type === 'chunk') html += frame.html;
      else complete = frame;
    }
    return { html, ...complete };
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
