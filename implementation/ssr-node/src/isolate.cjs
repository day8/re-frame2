'use strict';
// ONE ISOLATE — a worker thread, its deadline, and its one in-flight render
// (README §The five guarantees, 1, 3 and 4).
//
// A thread rather than a function call because `renderToString` is
// synchronous: nothing cooperative can stop a render stuck inside it, and
// `worker.terminate()` can. The thread is also its own V8 isolate, so the
// module's process-scoped state (the substrate, the registrar) is
// per-isolate. A terminated isolate is never reused — its heap is whatever
// the interrupted instruction left — so the pool replaces it.

const path = require('node:path');
const { Worker } = require('node:worker_threads');
const { CODE, Refusal, isRefusalCode, ISOLATE_LOST_REFUSAL } = require('./protocol.cjs');

const WORKER_PATH = path.join(__dirname, 'worker.cjs');

/**
 * The operator's line for a thread that died without being asked to. The
 * caller's refusal carries none of the fault (README §The response leg),
 * and for a fault thrown outside the render call this is the only copy.
 */
function reportIsolateFault(isolateSeq, what, fault) {
  process.stderr.write(`[rf.ssr-node] isolate ${isolateSeq} ${what}: ${fault}\n`);
}

const traceOf = (err) => (err && err.stack ? err.stack : String(err));

let nextIsolateSeq = 0;

class Isolate {
  /**
   * `onLost(isolate)` is called once if the thread dies with no render in
   * flight, so the pool stops offering it.
   */
  constructor({ modulePath, bootTimeoutMs = 30000, onLost = () => {} }) {
    this.seq = nextIsolateSeq++;
    this.modulePath = modulePath;
    this.bootTimeoutMs = bootTimeoutMs;
    this.onLost = onLost;
    this.worker = null;
    this.threadId = null;
    this.buildId = null;
    this.entries = null;
    this.booted = false;
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
          this.booted = true;
          worker.on('message', (renderMessage) => this._handleRenderMessage(renderMessage));
          resolve(this);
        } else if (message.t === 'boot-error') {
          clearTimeout(bootTimer);
          this._terminateWorker();
          reject(new Refusal(message.code, message.message, { modulePath: this.modulePath }));
        }
      };
      worker.on('message', handleBootMessage);

      // Both listeners stay registered for the worker's whole life. Before
      // `ready`, their `reject` settles startup and the module's own wording
      // goes to the operator starting the process; afterwards `reject` is a
      // no-op and `_died` answers. A fault the worker catches at boot never
      // reaches them: `worker.cjs` posts `boot-error` instead.
      worker.on('error', (err) => {
        clearTimeout(bootTimer);
        // Nullish-safe: CLJS `(throw nil)` arrives here as `null`, and a
        // throw in this listener would take the whole sidecar down.
        reject(new Refusal(CODE.MALFORMED_MODULE, err?.message ?? String(err), { stack: err?.stack }));
        this._died('died', traceOf(err));
      });
      worker.on('exit', (exitCode) => {
        clearTimeout(bootTimer);
        // A worker that exits before `ready` raises no `error` and posts no
        // `boot-error`, so without this startup would never settle.
        reject(
          new Refusal(
            CODE.MALFORMED_MODULE,
            `the render module's worker exited (code ${exitCode}) before it became ready`,
            { modulePath: this.modulePath, exitCode },
          ),
        );
        this._died('exited', `code ${exitCode}`);
      });
    });
  }

  /**
   * Render one request. Resolves with `{ html, renderMs, buildId }`; rejects
   * with a `Refusal`, except that a `postMessage` that throws rejects with
   * the raw fault, so `service.cjs`'s last receiver writes the operator's
   * copy and the contract's wording.
   */
  render(request, { timeoutMs }) {
    if (this.dead) {
      return Promise.reject(
        new Refusal(CODE.ISOLATE_LOST, 'this isolate is dead', {
          isolate: this.seq,
          threadId: this.threadId,
        }),
      );
    }
    if (this.busy) {
      // The pool never does this; the guard holds anyway.
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
      // Posted BEFORE anything is marked: a structured clone that throws
      // unwinds this executor with no timer armed and `pendingRender`
      // untouched, so the isolate stays free. No reply can arrive before
      // the executor ends, so nothing observes the gap.
      this.worker.postMessage({ t: 'render', id: renderId, request });

      const deadlineTimer = setTimeout(() => {
        // Hard termination: a synchronous render cannot be asked to stop.
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

      this.pendingRender = { renderId, deadlineTimer, resolve, reject };
    });
  }

  _handleRenderMessage(message) {
    const pendingRender = this.pendingRender;
    if (!pendingRender || message.id !== pendingRender.renderId) return;

    if (message.t === 'complete') {
      // Named fields, never a spread of `message`: the response leg is
      // `COMPLETE_FIELDS` and nothing the module adds.
      this._settlePendingRender(message.id, () =>
        pendingRender.resolve({
          html: message.html,
          renderMs: message.renderMs,
          buildId: this.buildId,
        }),
      );
      return;
    }
    if (message.t === 'error') {
      // The closed code family is checked here as well as in the worker, so
      // no code outside it reaches `http.cjs`'s `statusFor`.
      this._settlePendingRender(message.id, () =>
        pendingRender.reject(
          new Refusal(
            isRefusalCode(message.code) ? message.code : CODE.RENDER_THREW,
            message.message,
            message.detail ?? {},
          ),
        ),
      );
    }
  }

  /** Clear the in-flight record and its deadline, then run `settle`. */
  _settlePendingRender(renderId, settle) {
    const pendingRender = this.pendingRender;
    if (!pendingRender || pendingRender.renderId !== renderId) return;
    clearTimeout(pendingRender.deadlineTimer);
    this.pendingRender = null;
    settle();
  }

  /**
   * The thread died without being asked to — `error` or `exit` after boot,
   * on a thread this side did not terminate. The operator gets a line; the
   * render in flight, if any, is refused `isolate-lost` with service-owned
   * detail; with none in flight, the pool is told.
   */
  _died(how, fault) {
    if (!this.booted || this.dead) return;
    const pendingRender = this.pendingRender;
    this.dead = true;
    reportIsolateFault(this.seq, `${how} ${pendingRender ? 'mid-render' : 'while idle'}`, fault);
    if (!pendingRender) {
      this.onLost(this);
      return;
    }
    clearTimeout(pendingRender.deadlineTimer);
    this.pendingRender = null;
    pendingRender.reject(
      new Refusal(CODE.ISOLATE_LOST, ISOLATE_LOST_REFUSAL, {
        isolate: this.seq,
        threadId: this.threadId,
      }),
    );
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
      pendingRender.reject(new Refusal(CODE.SERVICE_CLOSED, 'the service is shutting down', {}));
    }
    if (worker) await worker.terminate().catch(() => {});
  }
}

module.exports = { Isolate };
