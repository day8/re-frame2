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
  (the CLJS-only side-effect bits live in preload.cljs).

  ## Privacy gate (rf2-azls9 — :sensitive? trace events)

  Per Spec 009 §Privacy (resolved by rf2-a32kd): framework-published
  trace-consuming integrations MUST default-suppress `:sensitive? true`
  events. `collect-trace!` gates each incoming event on
  `config/suppress-sensitive?` before any buffer push; suppressed
  events bump a per-frame counter that drives the bottom-rail's
  `[● REDACTED N]` hint. An engineer debugging redaction policy
  opts in via `(causa-config/configure! {:trace/show-sensitive?
  true})`."
  (:require [re-frame.interop :as interop]
            [day8.re-frame2-causa.config :as config]))

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
  callback).

  Per Spec 009 §Privacy + rf2-azls9: events whose `:sensitive?` flag
  is true are dropped before the buffer push when the global
  `:trace/show-sensitive?` flag is false (the default). The
  suppressed-events counter bumps for the targeted frame so the
  shell can surface a `[● REDACTED N]` hint. Opt in via
  `(causa-config/configure! {:trace/show-sensitive? true})` to
  surface the raw cascade while debugging redaction policy."
  [event]
  (when interop/debug-enabled?
    (cond
      (config/suppress-sensitive? event)
      (config/note-suppressed! (get-in event [:tags :frame]))

      :else
      (swap! buffer-state push @buffer-depth event))))

;; ---- read-side accessors -------------------------------------------

(defn buffer
  "Return the buffer's current contents, oldest first. Empty in
  production (the buffer never receives events when
  `interop/debug-enabled?` is false at compile time)."
  []
  @buffer-state)

;; ---- consumer-side filter (rf2-qi8au) -----------------------------------
;;
;; Causa's panels slice the buffer by the same filter vocabulary the
;; framework exposes on `(rf/trace-buffer opts)` per Spec 009 §Retain-N
;; trace ring buffer (rf2-97ah0 extended the vocab with nine axes —
;; :severity / :event-id / :handler-id / :source / :origin / :dispatch-id /
;; :since-ms / :between / :pred — on top of the original :operation /
;; :op-type / :since / :frame). The framework's filter is private inside
;; `trace-buffer`; consumers wanting the same algebra against a buffer
;; they hold themselves (Causa's deeper ring; a snapshot lifted out of a
;; session) would otherwise duplicate the predicate. `filter-events`
;; exposes that algebra as a pure-data fn against an arbitrary event
;; vector, locking the consumer contract: when Causa panels begin
;; slicing the buffer, they use the same vocabulary the framework does.
;;
;; Pure-data + JVM-runnable so the JVM test suite can drive every axis
;; without booting a CLJS runtime. No atoms, no interop, no swap!.

(defn filter-events
  "Apply the trace-buffer filter vocabulary to an arbitrary event vector.
  Returns a vector containing only events where every supplied filter
  key matches. Filters compose AND-wise; an absent key means
  'no constraint on that axis'. Mirrors `re-frame.trace/trace-buffer`'s
  filter algebra so Causa-side consumers slice the buffer using the
  same vocabulary the framework exposes.

  Recognised keys (per Spec 009 §Retain-N trace ring buffer + rf2-97ah0):

    Pre-rf2-97ah0:
      :operation     — keep events with this :operation value.
      :op-type       — keep events with this :op-type value.
      :since         — keep events whose :id is strictly greater.
      :frame         — keep events whose :frame (top-level or :tags) matches.

    rf2-97ah0 extensions:
      :severity      — keep events whose :op-type matches the tier
                       (:error / :warning / :info). Synonym for :op-type
                       restricted to those three values.
      :event-id      — keep events whose :tags :event-id matches.
      :handler-id    — keep events whose :tags :handler-id matches.
      :source        — keep events whose :source (top-level, hoisted
                       from :tags by emit!) matches.
      :origin        — keep events whose :tags :origin matches.
      :dispatch-id   — keep events whose :tags :dispatch-id matches
                       (cascade-wide per rf2-g6ih4).
      :since-ms      — keep events whose :time is strictly greater than
                       this host-clock timestamp.
      :between       — `[t0 t1]` two-element vector — keep events whose
                       :time falls in [t0, t1] inclusive.
      :pred          — `(fn [ev] -> truthy)` arbitrary predicate.

  Returns `events` unchanged (as a vector) when `opts` is empty or nil.

  Per rf2-qi8au — locks the consumer contract against the framework's
  filter vocabulary."
  ([events] (filter-events events nil))
  ([events opts]
   (if (empty? opts)
     (vec events)
     (let [{:keys [operation op-type since frame
                   severity event-id handler-id source origin
                   dispatch-id since-ms between pred]} opts
           [between-t0 between-t1] (when (and (sequential? between)
                                              (= 2 (count between)))
                                     between)
           predicate
           (fn [ev]
             (and (or (nil? operation) (= operation (:operation ev)))
                  (or (nil? op-type)   (= op-type   (:op-type ev)))
                  (or (nil? since)     (and (number? (:id ev))
                                            (> (:id ev) since)))
                  (or (nil? frame)
                      (= frame (or (:frame ev)
                                   (get-in ev [:tags :frame]))))
                  (or (nil? severity) (= severity (:op-type ev)))
                  (or (nil? event-id)
                      (= event-id (get-in ev [:tags :event-id])))
                  (or (nil? handler-id)
                      (= handler-id (get-in ev [:tags :handler-id])))
                  (or (nil? source)
                      (= source (or (:source ev)
                                    (get-in ev [:tags :source]))))
                  (or (nil? origin)
                      (= origin (get-in ev [:tags :origin])))
                  (or (nil? dispatch-id)
                      (= dispatch-id (get-in ev [:tags :dispatch-id])))
                  (or (nil? since-ms)
                      (and (number? (:time ev))
                           (> (:time ev) since-ms)))
                  (or (nil? between-t0)
                      (and (number? (:time ev))
                           (<= between-t0 (:time ev) between-t1)))
                  (or (nil? pred) (pred ev))))]
       (filterv predicate events)))))

(defn clear-buffer!
  "Empty the buffer. Tooling uses this between sessions. No-op in
  production. Per rf2-azls9, also resets the per-frame
  suppressed-sensitive counters so the bottom-rail `[● REDACTED N]`
  hint disappears alongside the cleared events — clearing the buffer
  is the natural moment to drop the 'you missed N events' overhang."
  []
  (when interop/debug-enabled?
    (reset! buffer-state [])
    (config/reset-suppressed-count!))
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
