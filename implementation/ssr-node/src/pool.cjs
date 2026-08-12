'use strict';
// THE ISOLATE POOL (rf2-hic-056, guarantees 3 and 4).
//
// One isolate renders one request at a time, so the pool size IS the
// service's concurrency and there is no second knob pretending otherwise.
// Everything here is one of three jobs:
//
//   ADMISSION   hand a caller an idle isolate, or refuse. A request that
//               waits forever for capacity is a request whose caller's
//               own timeout decides the outcome, which is the outcome
//               being decided by the wrong process.
//   REPLACEMENT a terminated isolate is never reused (see `isolate.cjs`),
//               so the pool spawns a fresh one and the caller after next
//               never notices.
//   IDENTITY    every isolate loads the same bundle from the same path.
//               A replacement that comes back with a DIFFERENT buildId
//               means the artefact changed on disk under a running
//               service — two applications answering one service's
//               requests, which is precisely the skew the build identity
//               exists to catch. It is refused rather than absorbed.

const { CODE, Refusal } = require('./protocol.cjs');
const { Isolate } = require('./isolate.cjs');

class Pool {
  constructor({ modulePath, size = 2, admissionTimeoutMs = 250, bootTimeoutMs = 30000 }) {
    if (!Number.isInteger(size) || size < 1) {
      throw new Error(`pool size must be a positive integer; got ${size}`);
    }
    this.modulePath = modulePath;
    this.size = size;
    this.admissionTimeoutMs = admissionTimeoutMs;
    this.bootTimeoutMs = bootTimeoutMs;
    this.idle = [];
    this.all = new Set();
    this.waiters = [];
    /**
     * Replacements currently booting. `close()` waits on these before it
     * believes it has closed anything.
     *
     * WITHOUT THIS THE PROCESS NEVER EXITS. A replacement is spawned
     * asynchronously so the caller that just timed out does not wait on a
     * worker boot — which means a `close()` racing that boot snapshots
     * `all` before the fresh isolate joins it, closes everything it can
     * see, and leaves a live worker thread behind holding the event loop
     * open. The symptom is a suite that passes every assertion and then
     * hangs forever, which reads as a broken test rather than a leaked
     * thread. Measured, not theorised: the timeout witness hit exactly
     * this the first time it ran.
     */
    this.spawning = new Set();
    this.closed = false;
    this.buildId = null;
    this.entries = null;
    /** Replacements performed. A rising count is a service killing renders. */
    this.replacements = 0;
  }

  async start() {
    // `allSettled`, not `all`: a bundle that refuses validation refuses in
    // every isolate at once, and a bare `all` would reject on the first
    // while leaving its siblings' worker threads running. A boot failure
    // that leaks threads turns a red assertion into a hung test process,
    // which is a far worse thing to debug than the failure it is hiding.
    const settled = await Promise.allSettled(
      Array.from({ length: this.size }, () => this._spawn()),
    );
    const started = settled.filter((r) => r.status === 'fulfilled').map((r) => r.value);
    const failed = settled.find((r) => r.status === 'rejected');
    if (failed) {
      await Promise.all(started.map((i) => i.close()));
      this.all.clear();
      throw failed.reason;
    }
    this.idle.push(...started);
    return this;
  }

  async _spawn() {
    const isolate = await new Isolate({
      modulePath: this.modulePath,
      bootTimeoutMs: this.bootTimeoutMs,
    }).start();

    if (this.buildId === null) {
      this.buildId = isolate.buildId;
      this.entries = isolate.entries;
    } else if (isolate.buildId !== this.buildId) {
      await isolate.close();
      throw new Refusal(
        CODE.BUILD_IDENTITY_MISMATCH,
        `a replacement isolate loaded build ${JSON.stringify(isolate.buildId)} where this ` +
          `service is serving ${JSON.stringify(this.buildId)} — the bundle changed on disk ` +
          `under a running service`,
        { serving: this.buildId, loaded: isolate.buildId, modulePath: this.modulePath },
      );
    }
    this.all.add(isolate);
    return isolate;
  }

  /**
   * `_spawn`, tracked, and self-cancelling if the service closed while it
   * was booting. Resolves to `null` in that case — there is a fresh
   * isolate, and it is closed rather than offered.
   */
  _spawnTracked() {
    const tracked = this._spawn().then(
      (isolate) => {
        this.spawning.delete(tracked);
        if (this.closed) {
          this.all.delete(isolate);
          isolate.close().catch(() => {});
          return null;
        }
        return isolate;
      },
      (err) => {
        this.spawning.delete(tracked);
        throw err;
      },
    );
    this.spawning.add(tracked);
    return tracked;
  }

  /** An idle isolate, or a `Refusal`. Never a queue with no bottom. */
  acquire() {
    if (this.closed) {
      return Promise.reject(new Refusal(CODE.SERVICE_CLOSED, 'the service is closed', {}));
    }
    const ready = this.idle.pop();
    if (ready) return Promise.resolve(ready);

    return new Promise((resolve, reject) => {
      const waiter = { resolve, reject, timer: null };
      waiter.timer = setTimeout(() => {
        const i = this.waiters.indexOf(waiter);
        if (i >= 0) this.waiters.splice(i, 1);
        reject(
          new Refusal(
            CODE.SERVICE_SATURATED,
            `no isolate became free within ${this.admissionTimeoutMs} ms (pool of ${this.size})`,
            { poolSize: this.size, admissionTimeoutMs: this.admissionTimeoutMs },
          ),
        );
      }, this.admissionTimeoutMs);
      waiter.timer.unref?.();
      this.waiters.push(waiter);
    });
  }

  /**
   * Give an isolate back. A dead one is replaced rather than returned —
   * asynchronously, because a caller finishing a request should not wait
   * on the next isolate's boot.
   */
  release(isolate) {
    if (isolate.dead) {
      this.all.delete(isolate);
      this.replacements += 1;
      if (this.closed) return;
      this._spawnTracked().then(
        (fresh) => {
          if (fresh) this._offer(fresh);
        },
        // A pool that cannot replace an isolate is a pool that shrinks.
        // Every waiter is refused rather than left holding a promise that
        // will only ever be settled by its own admission timer.
        (err) => {
          for (const w of this.waiters.splice(0)) {
            clearTimeout(w.timer);
            w.reject(
              err instanceof Refusal
                ? err
                : new Refusal(CODE.ISOLATE_LOST, `could not replace an isolate: ${err.message}`, {}),
            );
          }
        },
      );
      return;
    }
    this._offer(isolate);
  }

  _offer(isolate) {
    const waiter = this.waiters.shift();
    if (waiter) {
      clearTimeout(waiter.timer);
      waiter.resolve(isolate);
    } else {
      this.idle.push(isolate);
    }
  }

  stats() {
    return {
      total: this.all.size,
      ready: this.idle.length,
      busy: this.all.size - this.idle.length,
      waiting: this.waiters.length,
      replacements: this.replacements,
    };
  }

  async close() {
    this.closed = true;
    for (const w of this.waiters.splice(0)) {
      clearTimeout(w.timer);
      w.reject(new Refusal(CODE.SERVICE_CLOSED, 'the service is closing', {}));
    }
    // Replacements still booting close themselves (see `_spawnTracked`),
    // but only once they finish booting — so wait for them before
    // believing the pool is empty.
    await Promise.allSettled([...this.spawning]);
    const all = [...this.all];
    this.all.clear();
    this.idle.length = 0;
    await Promise.all(all.map((i) => i.close()));
  }
}

module.exports = { Pool };
