(ns day8.re-frame2-causa.trace-bus
  "Causa-side trace ring buffer. Per rf2-n6x4q §4 + tools/causa/spec/
  007-UX-IA.md the panel needs a buffer of trace events to render
  the event-detail / causality / trace panels against. The buffer is
  *Causa's own* — separate from the framework's retain-N ring at
  `re-frame.trace/trace-buffer`. Two buffers exist because:

    1. The framework buffer's depth is tuned for the framework's own
       consumers (200 by default). Causa wants a deeper history once
       it's open without forcing the framework default deeper.

    2. Causa applies its own filter projections on push (e.g. group-
       cascades from re-frame.trace.projection per rf2-wvzgd) so the
       UI reads pre-shaped data rather than re-deriving on every
       render.

  The buffer is pure-data (a vector held under an atom); push +
  evict-oldest is conj + subvec. Capped by `default-buffer-depth`
  (1000 events — five times the framework default; matches the
  expectation in tools/causa/spec/007-UX-IA.md §Performance budget
  that 'the causality graph caps at the last 200 dispatches', with
  headroom for non-dispatch trace events that share the same buffer).

  The buffer is gated on `re-frame.interop/debug-enabled?` (the
  universal `goog.DEBUG` gate) — production builds drop the buffer
  entirely. CLJC so the pure-data shape is JVM-runnable for tests
  (the CLJS-only side-effect bits live in preload.cljs)."
  (:require [re-frame.interop :as interop]))

;; ---- ring-buffer state ----------------------------------------------------

(def ^:private default-buffer-depth
  "Five times the framework default (`re-frame.trace/default-buffer-
  depth` = 200). Tuneable via `set-buffer-depth!`."
  1000)

(defonce ^:private buffer-depth (atom default-buffer-depth))

(defonce ^:private buffer-state
  ;; A plain vector under an atom; same shape as the framework's
  ;; `re-frame.trace/trace-buffer-state`. Oldest entries at the head
  ;; of the vector; conj appends and subvec evicts the head.
  (atom []))

;; ---- pure-data ring-buffer helpers (JVM-runnable) -------------------------

(defn push
  "Append `event` to `buffer` (a plain vector), evicting the oldest
  entry when `buffer`'s count exceeds `depth`. Pure fn; no atoms.

  JVM-runnable so the eviction algebra is testable without a CLJS
  runtime. Used both by `collect-trace!` (the CLJS-only swap! over
  `buffer-state`) and the JVM test suite."
  [buffer depth event]
  (let [buffer' (conj buffer event)
        n      (count buffer')]
    (if (> n depth)
      (subvec buffer' (- n depth))
      buffer')))

;; ---- collector --------------------------------------------------------

(defn collect-trace!
  "Append a trace event to Causa's ring buffer. Registered with
  `(rf/register-trace-cb! :rf.causa/trace-collector ...)` at preload
  time. Production builds elide the call (the framework's trace
  emission is gated on `interop/debug-enabled?` and never invokes the
  callback)."
  [event]
  (when interop/debug-enabled?
    (swap! buffer-state push @buffer-depth event)))

;; ---- read-side accessors -------------------------------------------

(defn buffer
  "Return the buffer's current contents, oldest first. Empty in
  production (the buffer never receives events when
  `interop/debug-enabled?` is false at compile time)."
  []
  @buffer-state)

(defn clear-buffer!
  "Empty the buffer. Tooling uses this between sessions. No-op in
  production."
  []
  (when interop/debug-enabled?
    (reset! buffer-state []))
  nil)

(defn set-buffer-depth!
  "Set the buffer's depth. `depth=0` keeps the collector wired (so the
  callback can be replaced or augmented) but flushes the buffer to
  empty and prevents further accumulation. No-op in production."
  [depth]
  (when (and interop/debug-enabled? (number? depth) (not (neg? depth)))
    (reset! buffer-depth depth)
    (swap! buffer-state
           (fn [v]
             (let [n (count v)]
               (cond
                 (zero? depth) []
                 (> n depth)   (subvec v (- n depth))
                 :else         v)))))
  nil)
