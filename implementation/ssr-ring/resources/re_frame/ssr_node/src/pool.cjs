'use strict';
// THE ISOLATE POOL (guarantees 3 and 4).
//
// One isolate renders one request at a time, so the pool size IS the
// service's concurrency. Three jobs:
//
//   ADMISSION   hand a caller an idle isolate, or refuse. Waiting is bounded
//               in time by the admission budget and in length by the pool
//               size, so a caller's own timeout never decides the outcome.
//   REPLACEMENT a dead isolate is never reused; the pool spawns a fresh one.
//               A replacement that will not boot ends the service, because
//               the pool can never reach its size again and a supervisor
//               restart is the recovery.
//   IDENTITY    a replacement that loads a DIFFERENT buildId means the bundle
//               changed on disk under a running service, and is refused.

const { CODE, Refusal, REPLACEMENT_FAILED_REFUSAL } = require('./protocol.cjs');
const { Isolate } = require('./isolate.cjs');

/**
 * The operator's line for a replacement that would not boot. Unconditional:
 * with no caller waiting, the refusal reaches nobody, and this is the only
 * record of why the service ended. `detail` is printed because for a fault
 * the worker did not catch it holds the stack; a caught boot exception's
 * stack is already on stderr from `worker.cjs`. `requestId` names the
 * request whose isolate died and set off the replacement.
 */
function reportReplacementFault(modulePath, err, requestId) {
  const trace = err && err.stack ? err.stack : String(err);
  const detail =
    err && err.detail && Object.keys(err.detail).length ? ` ${JSON.stringify(err.detail)}` : '';
  const request = requestId === undefined ? '' : ` (request ${requestId})`;
  process.stderr.write(
    `[rf.ssr-node] a replacement isolate failed to boot from ${modulePath}${request}: ${trace}${detail}\n`,
  );
}

class Pool {
  constructor({ modulePath, size = 2, admissionTimeoutMs = 250, bootTimeoutMs = 30000 }) {
    if (!Number.isInteger(size) || size < 1) {
      throw new Error(`pool size must be a positive integer; got ${size}`);
    }
    this.modulePath = modulePath;
    this.size = size;
    this.admissionTimeoutMs = admissionTimeoutMs;
    this.bootTimeoutMs = bootTimeoutMs;
    this.idleIsolates = [];
    this.isolates = new Set();
    this.waiters = [];
    /**
     * Replacements currently booting. `close()` waits on these: one that
     * finished booting after `close()` snapshotted `isolates` would leave a
     * live thread holding the process open.
     */
    this.startingReplacements = new Set();
    this.closed = false;
    this.buildId = null;
    this.entries = null;
    /** Replacements performed. A rising count is a service killing renders. */
    this.replacementCount = 0;
    /** True once a replacement failed to boot; the pool is then closed for good. */
    this.failed = false;
    /** Resolves once a failed replacement has closed the pool. */
    this.whenFailed = new Promise((resolve) => {
      this._signalFailed = resolve;
    });
  }

  async start() {
    // `allSettled`, so a bundle that refuses in every isolate does not leave
    // the siblings of the first refusal running.
    const startResults = await Promise.allSettled(
      Array.from({ length: this.size }, () => this._startIsolate()),
    );
    const startedIsolates = startResults
      .filter((result) => result.status === 'fulfilled')
      .map((result) => result.value);
    const failedStart = startResults.find((result) => result.status === 'rejected');
    if (failedStart) {
      await Promise.all(startedIsolates.map((isolate) => isolate.close()));
      this.isolates.clear();
      throw failedStart.reason;
    }
    for (const isolate of startedIsolates) this._offer(isolate);
    return this;
  }

  async _startIsolate() {
    const isolate = await new Isolate({
      modulePath: this.modulePath,
      bootTimeoutMs: this.bootTimeoutMs,
      onLost: (lost) => this._lost(lost),
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
    this.isolates.add(isolate);
    return isolate;
  }

  /**
   * Start one replacement, tracked and self-cancelling if the service
   * closed while it was booting. Resolves to `null` in that case — there is
   * a fresh isolate, and it is closed rather than offered.
   */
  _startReplacement() {
    const replacementStart = this._startIsolate().then(
      (isolate) => {
        this.startingReplacements.delete(replacementStart);
        if (this.closed) {
          this.isolates.delete(isolate);
          isolate.close().catch(() => {});
          return null;
        }
        return isolate;
      },
      (err) => {
        this.startingReplacements.delete(replacementStart);
        throw err;
      },
    );
    this.startingReplacements.add(replacementStart);
    return replacementStart;
  }

  /**
   * An idle isolate, or a `Refusal`. A caller that finds none waits, for at
   * most `admissionTimeoutMs`, and at most one caller per isolate waits at
   * all: past that the refusal is immediate. So the waiting line, and the
   * validated request each waiter holds, never outgrow the pool.
   */
  acquire() {
    if (this.failed) return Promise.reject(this._replacementFailedRefusal());
    if (this.closed) {
      return Promise.reject(new Refusal(CODE.SERVICE_CLOSED, 'the service is closed', {}));
    }
    const idleIsolate = this.idleIsolates.pop();
    if (idleIsolate) return Promise.resolve(idleIsolate);
    if (this.waiters.length >= this.size) {
      return Promise.reject(
        new Refusal(
          CODE.SERVICE_SATURATED,
          `${this.waiters.length} requests already wait for a pool of ${this.size}`,
          { poolSize: this.size, waiting: this.waiters.length },
        ),
      );
    }

    return new Promise((resolve, reject) => {
      const waiter = { resolve, reject, timer: null };
      waiter.timer = setTimeout(() => {
        const waiterIndex = this.waiters.indexOf(waiter);
        if (waiterIndex >= 0) this.waiters.splice(waiterIndex, 1);
        reject(
          new Refusal(
            CODE.SERVICE_SATURATED,
            `no isolate became free within ${this.admissionTimeoutMs} ms (pool of ${this.size})`,
            { poolSize: this.size, admissionTimeoutMs: this.admissionTimeoutMs },
          ),
        );
      }, this.admissionTimeoutMs);
      waiter.timer.unref();
      this.waiters.push(waiter);
    });
  }

  /**
   * Give an isolate back. A dead one is replaced rather than returned —
   * asynchronously, because a caller finishing a request should not wait
   * on the next isolate's boot. `requestId` is the id of the request the
   * isolate just served, if it carried one; it reaches only the operator's
   * line should the replacement fail.
   */
  release(isolate, requestId) {
    if (isolate.dead) {
      this.isolates.delete(isolate);
      this.replacementCount += 1;
      if (this.closed) return;
      this._startReplacement().then(
        (replacement) => {
          if (replacement) this._offer(replacement);
        },
        // The pool ENDS rather than shrinks: every waiter and later caller
        // is refused with one service-owned refusal, carrying none of the
        // boot refusal — whose message, path and module-chosen code were
        // written for an operator at a process that would not start, not
        // for a caller across a wire.
        (err) => {
          reportReplacementFault(this.modulePath, err, requestId);
          if (this.failed) return;
          this.failed = true;
          for (const waiter of this.waiters.splice(0)) {
            clearTimeout(waiter.timer);
            waiter.reject(this._replacementFailedRefusal());
          }
          this.close().then(this._signalFailed, this._signalFailed);
        },
      );
      return;
    }
    this._offer(isolate);
  }

  /**
   * An isolate died with no render in flight. An idle one leaves the ready
   * list and is replaced now; one a caller holds is replaced when released.
   */
  _lost(isolate) {
    const idleIndex = this.idleIsolates.indexOf(isolate);
    if (idleIndex < 0) return;
    this.idleIsolates.splice(idleIndex, 1);
    this.release(isolate);
  }

  _replacementFailedRefusal() {
    return new Refusal(CODE.ISOLATE_LOST, REPLACEMENT_FAILED_REFUSAL, { poolSize: this.size });
  }

  /** Hand a live isolate to the first waiter, or make it idle; a dead one is replaced. */
  _offer(isolate) {
    if (isolate.dead) {
      this.release(isolate);
      return;
    }
    const waiter = this.waiters.shift();
    if (waiter) {
      clearTimeout(waiter.timer);
      waiter.resolve(isolate);
    } else {
      this.idleIsolates.push(isolate);
    }
  }

  stats() {
    return {
      total: this.isolates.size,
      ready: this.idleIsolates.length,
      busy: this.isolates.size - this.idleIsolates.length,
      waiting: this.waiters.length,
      replacements: this.replacementCount,
    };
  }

  async close() {
    this.closed = true;
    for (const waiter of this.waiters.splice(0)) {
      clearTimeout(waiter.timer);
      waiter.reject(new Refusal(CODE.SERVICE_CLOSED, 'the service is closing', {}));
    }
    // Replacements still booting close themselves (see `_startReplacement`),
    // but only once they finish booting — so wait for them before
    // believing the pool is empty.
    await Promise.allSettled([...this.startingReplacements]);
    const isolatesToClose = [...this.isolates];
    this.isolates.clear();
    this.idleIsolates.length = 0;
    await Promise.all(isolatesToClose.map((isolate) => isolate.close()));
  }
}

module.exports = { Pool };
