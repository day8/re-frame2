/*
 * Cross-cutting testbed spec helpers (rf2-fe84r).
 *
 * Shared between the six framework-behavior scenarios under
 * `testbeds/<surface>/spec.cjs`. Each scenario asserts against the
 * framework's trace bus and/or epoch history via the Causa preload's
 * mirror — every testbed wires `day8.re-frame2-causa.preload` through
 * shadow-cljs `:devtools/:preloads`, which registers a trace-collector
 * cb against `re-frame.trace/register-listener!`. Reading the
 * Causa-side mirror is therefore reading the framework's emitted
 * stream (Causa's collector is push-on-emit, not opt-in).
 *
 * Helpers are CLJS-aware: each `page.evaluate(...)` invocation uses
 * `cljs.core.pr_str` to materialise CLJS PersistentMap entries into
 * canonical EDN strings the calling JS can regex-match. This avoids
 * the alternative — poking at munged keyword internals from JS — and
 * keeps the assertion vocabulary aligned with the spec docs.
 *
 * The framework's own `re-frame.core/trace-buffer` and
 * `re-frame.core/epoch-history` are also accessible on `window` under
 * their dotted-ns munge in `:browser :dev` builds; the Causa surface
 * is preferred for the trace bus because every testbed already routes
 * through it. For epoch history we use `re_frame.core.epoch_history`
 * directly — epoch records are not buffered by Causa's trace bus.
 */

/**
 * Read the Causa trace bus and return each event as a pr-str EDN
 * string. Returns `{ ok, events?, reason? }`.
 *
 * `events` is oldest-first to match the buffer's append-order, so a
 * caller asserting on ordering can `events.findIndex(...)` and
 * compare positions directly.
 */
async function readTraceEventsAsEdn(page) {
  return page.evaluate(() => {
    const cljs = window.cljs && window.cljs.core;
    const bus =
      window.day8 &&
      window.day8.re_frame2_causa &&
      window.day8.re_frame2_causa.trace_bus;
    if (!cljs || !bus || typeof bus.buffer !== 'function') {
      return {
        ok: false,
        reason: 'cljs.core / day8.re_frame2_causa.trace_bus.buffer not on window',
      };
    }
    const events = [];
    let s = cljs.seq(bus.buffer());
    while (s) {
      const ev = cljs.first(s);
      events.push(cljs.pr_str ? cljs.pr_str(ev) : String(ev));
      s = cljs.next(s);
    }
    return { ok: true, events };
  });
}

/**
 * Clear the Causa trace bus. Useful between sub-scenarios in one
 * spec to keep `events.find(...)` scoped to the events under test
 * without earlier `:counter/initialise` / lifecycle emits in the way.
 *
 * Per `day8.re-frame2-causa.trace-bus/clear-buffer!`: this empties
 * Causa's own buffer; the framework's `re-frame.trace/trace-buffer`
 * is untouched (the two are independent ring buffers per Spec 009
 * §Retain-N trace ring buffer).
 */
async function clearTraceBus(page) {
  return page.evaluate(() => {
    const bus =
      window.day8 &&
      window.day8.re_frame2_causa &&
      window.day8.re_frame2_causa.trace_bus;
    if (!bus || typeof bus.clear_buffer_BANG_ !== 'function') {
      return { ok: false, reason: 'trace_bus.clear_buffer_BANG_ not on window' };
    }
    bus.clear_buffer_BANG_();
    return { ok: true };
  });
}

/**
 * Read `rf/epoch-history` for the given frame keyword (default
 * `:rf/default`). Each record is rendered to a pr-str EDN string.
 * Returns `{ ok, records?, reason? }`.
 */
async function readEpochHistoryAsEdn(page, frameKeyword = ':rf/default') {
  return page.evaluate((kwStr) => {
    const cljs = window.cljs && window.cljs.core;
    const rf = window.re_frame && window.re_frame.core;
    if (!cljs || !rf || typeof rf.epoch_history !== 'function') {
      return {
        ok: false,
        reason: 'cljs.core / re_frame.core.epoch_history not on window',
      };
    }
    // `cljs.core.keyword` accepts a bare string OR a "ns/name" form
    // — it splits on the slash internally (see cljs.core/keyword's
    // arity-1 source). Strip the leading colon if present so
    // "rf/default" and ":rf/default" both work.
    const raw = kwStr.startsWith(':') ? kwStr.slice(1) : kwStr;
    const kw = cljs.keyword.call ? cljs.keyword.call(null, raw) : cljs.keyword(raw);
    const records = [];
    let s = cljs.seq(rf.epoch_history(kw));
    while (s) {
      records.push(cljs.pr_str(cljs.first(s)));
      s = cljs.next(s);
    }
    return { ok: true, records };
  }, frameKeyword);
}

/**
 * Poll `fn` until it returns truthy or the timeout elapses. Returns
 * the truthy result, or throws with `label` on timeout.
 */
async function pollUntil(fn, label, timeoutMs = 10000, intervalMs = 50) {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    const r = await fn();
    if (r) return r;
    await new Promise((res) => setTimeout(res, intervalMs));
  }
  throw new Error(`pollUntil timed out (${timeoutMs}ms): ${label}`);
}

module.exports = {
  readTraceEventsAsEdn,
  clearTraceBus,
  readEpochHistoryAsEdn,
  pollUntil,
};
