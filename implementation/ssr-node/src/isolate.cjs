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
const { CODE, Refusal } = require('./protocol.cjs');

const WORKER_PATH = path.join(__dirname, 'worker.cjs');

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
    this.pending = null;
    this.renders = 0;
    this._nextRequestSeq = 0;
  }

  get busy() {
    return this.pending !== null;
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
        this._die();
        reject(
          new Refusal(
            CODE.MALFORMED_MODULE,
            `the render module did not become ready within ${this.bootTimeoutMs} ms`,
            { modulePath: this.modulePath },
          ),
        );
      }, this.bootTimeoutMs);
      bootTimer.unref?.();

      const onReady = (msg) => {
        if (msg.t === 'ready') {
          clearTimeout(bootTimer);
          worker.off('message', onReady);
          this.buildId = msg.buildId;
          this.entries = msg.entries;
          worker.on('message', (m) => this._onMessage(m));
          resolve(this);
        } else if (msg.t === 'boot-error') {
          clearTimeout(bootTimer);
          this._die();
          reject(new Refusal(msg.code, msg.message, { modulePath: this.modulePath }));
        }
      };
      worker.on('message', onReady);
      worker.on('error', (err) => {
        clearTimeout(bootTimer);
        this._fail(new Refusal(CODE.ISOLATE_LOST, err.message, { stack: err.stack }));
        reject(new Refusal(CODE.MALFORMED_MODULE, err.message, { stack: err.stack }));
      });
      worker.on('exit', () => {
        clearTimeout(bootTimer);
        this._fail(new Refusal(CODE.ISOLATE_LOST, 'the isolate exited mid-render', {}));
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

    const id = this._nextRequestSeq++;
    this.renders += 1;

    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        // HARD TERMINATION. Not a cancel, not a reject-and-hope: the
        // thread is stopped, because a synchronous render cannot be asked
        // to stop.
        const isolateSeq = this.seq;
        const threadId = this.threadId;
        this._settle(id, () =>
          reject(
            new Refusal(
              CODE.RENDER_TIMEOUT,
              `render exceeded its ${timeoutMs} ms deadline; the isolate was terminated`,
              { timeoutMs, isolate: isolateSeq, threadId, entry: request.entry },
            ),
          ),
        );
        this._die();
      }, timeoutMs);
      timer.unref?.();

      this.pending = { id, timer, resolve, reject, onChunk, chunks: 0 };
      this.worker.postMessage({ t: 'render', id, request });
    });
  }

  _onMessage(msg) {
    const p = this.pending;
    if (!p || msg.id !== p.id) return;

    if (msg.t === 'chunk') {
      p.chunks += 1;
      p.onChunk({ type: 'chunk', seq: msg.seq, html: msg.html });
      return;
    }
    if (msg.t === 'complete') {
      // Named fields, never a spread of `msg`. The worker refuses to put
      // application data on this message at all (see its header), and
      // this end declines to carry a field it was not expecting even if
      // one somehow arrived — two independent readings of the same
      // property, which is what makes it a boundary rather than a habit.
      this._settle(msg.id, () =>
        p.resolve({
          chunks: msg.chunks,
          renderMs: msg.renderMs,
          buildId: this.buildId,
        }),
      );
      return;
    }
    if (msg.t === 'error') {
      this._settle(msg.id, () =>
        p.reject(
          new Refusal(msg.code ?? CODE.RENDER_THREW, msg.message, {
            ...(msg.detail ?? {}),
            // A caller that already saw chunks has a TORN response. It is
            // named rather than smoothed over: the transport must not
            // present a torn response as a complete one.
            afterChunks: msg.afterChunks ?? p.chunks,
          }),
        ),
      );
    }
  }

  _settle(id, act) {
    const p = this.pending;
    if (!p || p.id !== id) return;
    clearTimeout(p.timer);
    this.pending = null;
    act();
  }

  /** Reject whatever is in flight — the isolate died under it. */
  _fail(refusal) {
    const p = this.pending;
    this.dead = true;
    if (p) {
      clearTimeout(p.timer);
      this.pending = null;
      p.reject(refusal);
    }
  }

  _die() {
    this.dead = true;
    const w = this.worker;
    this.worker = null;
    if (w) w.terminate().catch(() => {});
  }

  /** Orderly shutdown. Nothing in flight is waited for. */
  async close() {
    const w = this.worker;
    this.dead = true;
    this.worker = null;
    if (this.pending) {
      clearTimeout(this.pending.timer);
      const p = this.pending;
      this.pending = null;
      p.reject(new Refusal(CODE.SERVICE_CLOSED, 'the service is shutting down', {}));
    }
    if (w) await w.terminate().catch(() => {});
  }
}

module.exports = { Isolate, WORKER_PATH };
