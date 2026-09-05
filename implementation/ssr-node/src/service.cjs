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
//
// ## THE PUBLIC FRAMES CARRY NO APPLICATION DATA
//
// `renderFrames` is the widest surface this package has: every transport
// is an adapter over it and the in-process caller reads it raw, so
// whatever it yields IS the egress. What it yields is body markup in
// `chunk` frames and `COMPLETE_FIELDS` in the terminal one — nothing
// else, and nothing the application's render module authored outside the
// markup it emitted. `test/egress.test.cjs` is that property's witness,
// and it checks the frames rather than the HTTP response, because HTTP
// dropping a field is a fact about HTTP and not a guarantee about this.

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
 * wording handed to the caller would again be the only copy in existence,
 * which is the trade `isolate.cjs`'s `reportIsolateFault` exists to refuse.
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

  /**
   * The core call. Yields `{type:'chunk'}` frames as the render produces
   * them, then one `{type:'complete'}`. Throws a `Refusal`.
   */
  async *renderFrames(request) {
    const validatedRequest = validateRequest(
      request,
      { buildId: this.pool.buildId, entries: this.pool.entries },
      this.limits,
    );

    const isolate = await this.pool.acquire();

    // A hand-rolled bridge from the isolate's callback to this generator,
    // because there is no smaller one: `onChunk` is called from a
    // 'message' handler and the consumer pulls at its own pace.
    const pendingFrames = [];
    let resumeConsumer = null;
    const wakeConsumer = () => {
      if (resumeConsumer) {
        const resume = resumeConsumer;
        resumeConsumer = null;
        resume();
      }
    };

    let renderFinished = false;
    let renderFailure = null;
    let renderResult = null;

    const renderCompletion = isolate
      .render(validatedRequest, {
        timeoutMs: validatedRequest.timeoutMs,
        onChunk: (frame) => {
          pendingFrames.push(frame);
          wakeConsumer();
        },
      })
      .then(
        (result) => {
          renderResult = result;
        },
        (error) => {
          if (error instanceof Refusal) {
            renderFailure = error;
            return;
          }
          // THE LAST RECEIVER, and it states the same law as the other
          // three (rf2-2hmg). This arm used to put `String(error)` on the
          // public refusal, on the reading that it was unreachable and
          // would carry only this package's own text if it were not.
          // Neither half survived being measured.
          //
          // It was reached by an ORDINARY IN-PROCESS CALL. `validatePartition`
          // used to return the caller's own partition object rather than a
          // copy, so the object the validator inspected and the object
          // `postMessage` structured-clones were one object read twice — and
          // anything with an accessor on it (a getter, a Proxy, a lazily
          // materialised row out of a serializer) could be a string on the
          // first read and unclonable on the second. `postMessage` then threw
          // `DataCloneError` synchronously inside `render()`'s executor,
          // which never passed through a receiver that could have made it a
          // `Refusal`.
          //
          // And the text was the CALLER'S, not ours: a `DataCloneError` names
          // the value it choked on, so the caller's own value was
          // interpolated into a message published on the widest surface this
          // package has — the egress this file's header says does not exist.
          //
          // THAT ROUTE IS CLOSED (rf2-ey07): `validateRequest` now reads every
          // field exactly once and returns what it read, so a request that
          // validates is a request that clones.
          //
          // THE ARM ITSELF WAS NEVER THE DEFECT AND IS NOT REMOVED, and being
          // unreachable from a caller is not a reason to remove it. It is what
          // guarantees `renderFrames` throws nothing but a `Refusal`, which
          // is the property every transport is written against; deleting it
          // would let a raw `Error` reach `statusFor` with no code at all —
          // and `isolate.cjs`'s `render` deliberately lets a `postMessage`
          // that throws reject RAW rather than dressing it as a `Refusal`,
          // precisely so this arm is what states the wording and writes the
          // operator's copy. Only the wording was ever wrong, and the
          // contract already owns the wording.
          reportUncontractedFault(error);
          renderFailure = new Refusal(CODE.RENDER_THREW, RENDER_THREW_REFUSAL, {});
        },
      )
      .then(() => {
        renderFinished = true;
        this.pool.release(isolate);
        wakeConsumer();
      });

    try {
      for (;;) {
        while (pendingFrames.length) yield pendingFrames.shift();
        if (renderFinished) break;
        await new Promise((resolve) => {
          resumeConsumer = resolve;
        });
      }
      await renderCompletion;
      if (renderFailure) throw renderFailure;
      yield completeFrame({
        chunks: renderResult.chunks,
        renderMs: renderResult.renderMs,
        buildId: renderResult.buildId,
        requestId: validatedRequest.requestId,
      });
    } finally {
      // A consumer that abandons the iteration must not strand the
      // isolate. `renderCompletion` releases it whichever way the render
      // went, so this only has to make sure the promise is not left
      // unobserved.
      renderCompletion.catch(() => {});
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
