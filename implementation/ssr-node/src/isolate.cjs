'use strict';
// ONE ISOLATE — a worker thread, its deadline, and its one in-flight
// render (rf2-hic-056, guarantees 1, 3 and 4).
//
// ## WHY A THREAD AND NOT A FUNCTION CALL
//
// Because of guarantee 4, and only because of it. Everything else this
// file does could be done in-process; hard termination cannot.
//
// `react-dom/server`'s `renderToString` is SYNCHRONOUS. A render that
// will not finish — an accidental infinite loop in a view, a pathological
// input, a regex that backtracks — cannot be interrupted by anything
// cooperative. There is no promise to reject, no abort signal anyone is
// polling, and no timer that will fire, because the timer's own callback
// is queued behind the loop. In-process, a hung render hangs the process.
//
// `worker.terminate()` reaches V8's execution terminator and stops
// synchronous JavaScript mid-instruction. That is the whole reason an
// isolate here is a worker thread. It also buys, for free, the isolation
// guarantee 1 wants: a separate V8 isolate is a separate module registry,
// so the render module's process-scoped state — the substrate Spec 006
// allows one of, the framework registrar, the module-level adoption
// window the Hicasso entry opens around its render — is per-isolate.
//
// ## A TERMINATED ISOLATE IS NEVER REUSED
//
// After `terminate()` the worker's heap is in whatever state the
// interrupted instruction left it. It is not restarted here and not
// drained; it is marked dead and the POOL replaces it. Reusing it would
// be handing the next request a renderer that was killed halfway through
// someone else's, which is the failure this guarantee exists to prevent
// rather than a saving.

const path = require('node:path');
const { Worker } = require('node:worker_threads');
const {
  CODE,
  Refusal,
  chunkFrame,
  isRefusalCode,
  ISOLATE_LOST_REFUSAL,
} = require('./protocol.cjs');

const WORKER_PATH = path.join(__dirname, 'worker.cjs');

/**
 * The operator's copy of the fault that killed an isolate mid-render —
 * everything the refusal deliberately does not carry.
 *
 * The counterpart of `worker.cjs`'s `reportRenderException`, on this side
 * of the thread boundary and for the faults that side never sees: an
 * exception thrown from a callback the render scheduled is never caught by
 * anything in the worker, so its `try/catch` — and therefore its stderr
 * write — never runs. This is the only place the exception exists.
 *
 * WHICH IS WHY THIS FUNCTION IS PART OF THE FIX RATHER THAN A COURTESY.
 * Closing the refusal's wording without opening this would have traded a
 * leak for a silence, and a failure nobody can diagnose is its own defect.
 *
 * NOT A NEW SUBSYSTEM and no flag, for the reasons `reportRenderException`
 * gives: `bin/serve.cjs` already writes `[rf.ssr-node] …` here, so this is
 * that stream under that prefix, and a diagnostic that can be switched off
 * is off on the day it is wanted.
 */
function reportIsolateFault(isolateSeq, err) {
  const trace = err && err.stack ? err.stack : String(err);
  process.stderr.write(`[rf.ssr-node] isolate ${isolateSeq} died mid-render: ${trace}\n`);
}

let nextIsolateSeq = 0;

class Isolate {
  constructor({ modulePath, bootTimeoutMs = 30000 }) {
    this.seq = nextIsolateSeq++;
    this.modulePath = modulePath;
    this.bootTimeoutMs = bootTimeoutMs;
    this.worker = null;
    this.threadId = null;
    this.buildId = null;
    this.entries = null;
    this.dead = false;
    /** The one in-flight render, or null. Guarantee 3, in one field. */
    this.pendingRender = null;
    this._nextRenderId = 0;
  }

  get busy() {
    return this.pendingRender !== null;
  }

  /** Boot the worker and wait for it to publish its tables. */
  start() {
    return new Promise((resolve, reject) => {
      const worker = new Worker(WORKER_PATH, {
        workerData: { modulePath: this.modulePath },
      });
      this.worker = worker;
      this.threadId = worker.threadId;

      const bootTimer = setTimeout(() => {
        this._terminateWorker();
        reject(
          new Refusal(
            CODE.MALFORMED_MODULE,
            `the render module did not become ready within ${this.bootTimeoutMs} ms`,
            { modulePath: this.modulePath },
          ),
        );
      }, this.bootTimeoutMs);
      bootTimer.unref();

      const handleBootMessage = (message) => {
        if (message.t === 'ready') {
          clearTimeout(bootTimer);
          worker.off('message', handleBootMessage);
          this.buildId = message.buildId;
          this.entries = message.entries;
          worker.on('message', (renderMessage) => this._handleRenderMessage(renderMessage));
          resolve(this);
        } else if (message.t === 'boot-error') {
          clearTimeout(bootTimer);
          this._terminateWorker();
          reject(new Refusal(message.code, message.message, { modulePath: this.modulePath }));
        }
      };
      worker.on('message', handleBootMessage);
      worker.on('error', (err) => {
        clearTimeout(bootTimer);
        // ONE HANDLER, TWO PHASES, AND THEY ARE NOT THE SAME AUDIENCE. It
        // stays registered for the worker's whole life, so before boot
        // resolves the `reject` below is live and `_failPendingRender` is a
        // no-op; afterwards the promise is settled and it is the other way
        // round. The two arms answer different people and that is why only
        // one of them changed.
        //
        // THE RENDER ARM — a fault that reached a CALLER. `handleRender`'s
        // own catch never saw this one: an exception thrown from a callback
        // the render scheduled runs on a later tick with no `try` above it,
        // so Node killed the thread and the `Error` arrived here instead.
        // That made this the second receiver of the response law, and for a
        // while the only one not stating it — `err.message` was the module's
        // wording and `err.stack` was that wording plus every absolute path
        // in the deployment, both published on the public `Refusal` and
        // serialised into the HTTP body. So: the contract's wording, and a
        // `detail` this file builds. `isolate` and `threadId` are the same
        // two service-owned facts the deadline refusal above carries, and
        // for the same reason — they name the thread an operator is about
        // to go looking for, and neither originates in the module.
        //
        // The real exception goes to stderr first, and on this path that is
        // the only copy that has ever existed; see `reportIsolateFault`.
        if (this.pendingRender) reportIsolateFault(this.seq, err);
        this._failPendingRender(
          new Refusal(CODE.ISOLATE_LOST, ISOLATE_LOST_REFUSAL, {
            isolate: this.seq,
            threadId: this.threadId,
          }),
        );
        // THE BOOT ARM — untouched, deliberately. Boot fails before the
        // service listens, so this refusal is read by the operator standing
        // at the process they just started rather than by a caller across a
        // wire; `worker.cjs`'s boot post names this handler as where the
        // real stack survives, and it is the diagnostic for a module that
        // cannot be loaded at all. Two audiences, and this one is already
        // the operator.
        reject(new Refusal(CODE.MALFORMED_MODULE, err.message, { stack: err.stack }));
      });
      worker.on('exit', () => {
        clearTimeout(bootTimer);
        this._failPendingRender(
          new Refusal(CODE.ISOLATE_LOST, 'the isolate exited mid-render', {}),
        );
      });
    });
  }

  /**
   * Render one request. `onChunk(frame)` is called as each body chunk
   * arrives — never buffered here, because a layer that buffers is a layer
   * that has to be rewritten when a streaming module lands.
   *
   * Resolves with the terminal facts; rejects with a `Refusal`.
   */
  render(request, { timeoutMs, onChunk }) {
    if (this.dead) {
      return Promise.reject(new Refusal(CODE.ISOLATE_LOST, 'this isolate is dead', {}));
    }
    if (this.busy) {
      // See the header of `worker.cjs`: the pool never does this, and the
      // guard holds anyway.
      return Promise.reject(
        new Refusal(
          CODE.SERVICE_SATURATED,
          'this isolate already has a render in flight; one at a time',
          { isolate: this.seq },
        ),
      );
    }

    const renderId = this._nextRenderId++;

    return new Promise((resolve, reject) => {
      const deadlineTimer = setTimeout(() => {
        // HARD TERMINATION. Not a cancel, not a reject-and-hope: the
        // thread is stopped, because a synchronous render cannot be asked
        // to stop.
        const isolateSeq = this.seq;
        const threadId = this.threadId;
        this._settlePendingRender(renderId, () =>
          reject(
            new Refusal(
              CODE.RENDER_TIMEOUT,
              `render exceeded its ${timeoutMs} ms deadline; the isolate was terminated`,
              { timeoutMs, isolate: isolateSeq, threadId, entry: request.entry },
            ),
          ),
        );
        this._terminateWorker();
      }, timeoutMs);
      deadlineTimer.unref();

      this.pendingRender = {
        renderId,
        deadlineTimer,
        resolve,
        reject,
        onChunk,
        chunkCount: 0,
      };
      this.worker.postMessage({ t: 'render', id: renderId, request });
    });
  }

  _handleRenderMessage(message) {
    const pendingRender = this.pendingRender;
    if (!pendingRender || message.id !== pendingRender.renderId) return;

    if (message.t === 'chunk') {
      pendingRender.chunkCount += 1;
      // Through the protocol's own constructor, and named fields either
      // way — the worker's message carries `t` and `id` too, and the
      // public frame is the three fields `chunkFrame` names. It is the
      // chunk half of the pair `completeFrame` is the terminal half of;
      // the boundary spelling the shape a second time by hand is a field
      // list that can drift from the one the contract publishes.
      pendingRender.onChunk(chunkFrame(message.seq, message.html));
      return;
    }
    if (message.t === 'complete') {
      // Named fields, never a spread of `message`. The worker refuses to put
      // application data on this message at all (see its header), and
      // this end declines to carry a field it was not expecting even if
      // one somehow arrived — two independent readings of the same
      // property, which is what makes it a boundary rather than a habit.
      this._settlePendingRender(message.id, () =>
        pendingRender.resolve({
          chunks: message.chunks,
          renderMs: message.renderMs,
          buildId: this.buildId,
        }),
      );
      return;
    }
    if (message.t === 'error') {
      // THE SECOND READING, and the same discipline the `complete` branch
      // above states: this is the boundary that BUILDS the public
      // `Refusal`, so the closed family is checked here as well as at the
      // worker that posts it. The worker is the enforcement — it is what
      // stops a module's own `code` being believed at all — and this is
      // the reading that stops any code the family does not contain from
      // reaching `http.cjs`'s `statusFor` and choosing a status of its
      // own. Two independent readings of one property, which is what makes
      // it a boundary rather than a habit.
      this._settlePendingRender(message.id, () =>
        pendingRender.reject(
          new Refusal(isRefusalCode(message.code) ? message.code : CODE.RENDER_THREW, message.message, {
            ...(message.detail ?? {}),
            // A caller that already saw chunks has a TORN response. It is
            // named rather than smoothed over: the transport must not
            // present a torn response as a complete one.
            afterChunks: message.afterChunks ?? pendingRender.chunkCount,
          }),
        ),
      );
    }
  }

  _settlePendingRender(renderId, settle) {
    const pendingRender = this.pendingRender;
    if (!pendingRender || pendingRender.renderId !== renderId) return;
    clearTimeout(pendingRender.deadlineTimer);
    this.pendingRender = null;
    settle();
  }

  /** Reject whatever is in flight — the isolate died under it. */
  _failPendingRender(refusal) {
    const pendingRender = this.pendingRender;
    this.dead = true;
    if (pendingRender) {
      clearTimeout(pendingRender.deadlineTimer);
      this.pendingRender = null;
      pendingRender.reject(refusal);
    }
  }

  _terminateWorker() {
    this.dead = true;
    const worker = this.worker;
    this.worker = null;
    if (worker) worker.terminate().catch(() => {});
  }

  /** Orderly shutdown. Nothing in flight is waited for. */
  async close() {
    const worker = this.worker;
    this.dead = true;
    this.worker = null;
    if (this.pendingRender) {
      clearTimeout(this.pendingRender.deadlineTimer);
      const pendingRender = this.pendingRender;
      this.pendingRender = null;
      pendingRender.reject(
        new Refusal(CODE.SERVICE_CLOSED, 'the service is shutting down', {}),
      );
    }
    if (worker) await worker.terminate().catch(() => {});
  }
}

module.exports = { Isolate };
