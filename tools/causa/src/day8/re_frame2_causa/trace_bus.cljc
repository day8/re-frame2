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
  true})`.

  ## Reactivity (rf2-in6l2 / rf2-wq6gx)

  The buffer lives in two places that move in lockstep:

    1. The `buffer-state` atom (this ns) — the JVM-runnable data
       primitive AND the canonical write surface. Every push,
       clear, and depth-shrink mutates the atom synchronously.
       Tests over push algebra / sensitive-trace privacy /
       filter-vocab consumer use the atom directly; CLJC so the
       shape runs under both runtimes.

    2. Causa's app-db `:trace-buffer` slot (lazy-registered by
       `mount.cljs/open!` at first Ctrl+Shift+C, refreshed by a
       coalesced microtask sync). Panels subscribe to
       `:rf.causa/trace-buffer` which reads this slot — the layer-1
       sub re-fires on the standard app-db-write reactive path so
       panels re-render on the next microtask after a flush.

  ### Coalesced mirror (rf2-wq6gx)

  `request-mirror-sync!` schedules a single
  `:rf.causa/sync-trace-buffer` dispatch carrying the atom's
  current snapshot per JS tick — same-tick callers (e.g. a flood
  of 1000 collect-trace! calls from a synthetic-load test) collapse
  to ONE dispatch. This caps the mirror cascade depth at 1
  regardless of host trace-event volume, structurally eliminating
  the drain-depth saturation that the original per-event mirror
  exhibited (`re-frame.router/drain-depth-default = 100` triggered
  a rollback that snapped the mirror back to a small count, and
  the slot stalled far below the atom's true contents). Snapshot
  semantics also obviate the rf2-z4fza per-event seed-race dedup
  walk in production — every mirror is a wholesale overwrite of
  the slot with the atom's snapshot, so duplicate-`:id` rows are
  structurally impossible.

  Why both surfaces: the atom is the JVM-runnable primitive and
  the pre-mount fallback (`collect-trace!` can fire before `open!`
  has registered the frame; the atom still accumulates and the
  seed lands when the user opens Causa). The app-db slot is the
  reactive surface that reg-view-wrapped panels read via the
  `:rf/causa` frame.

  Per rf2-e9s81 the parallel-app-db approach was attempted at preload
  time and reverted because the preload couldn't register `:rf/causa`
  in that window (no substrate adapter yet) and chain-resolving to
  `:rf/default` polluted the host's app-db / consumed drain-depth
  headroom. The rf2-in6l2 design lands the dispatch under `:rf/causa`
  proper, registered lazily at mount time — closing the gap without
  the layering hazards."
  (:require [re-frame.interop :as interop]
            #?(:cljs [re-frame.core :as rf])
            #?(:cljs [re-frame.frame :as frame])
            [day8.re-frame2-causa.config :as config]))

;; ---- ring-buffer state ----------------------------------------------------

(def ^:private default-buffer-depth
  "Five times the framework default (`re-frame.trace/default-buffer-
  depth` = 200). Tuneable via `set-buffer-depth!`."
  1000)

(defonce ^:private buffer-depth (atom default-buffer-depth))

(defonce ^:private buffer-state
  ;; A plain vector under an atom; same shape as the framework's
  ;; `re-frame.trace.tooling/trace-buffer-state` (sibling ns split per
  ;; rf2-qwm0a). Oldest entries at the head
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

;; ---- causa-frame mirror dispatch helper (rf2-in6l2 / rf2-wq6gx) ----------
;;
;; CLJS-only — the framework dispatch surface is CLJS-only on this code
;; path (the JVM `re-frame.frame/frame` lookup is also CLJC but `rf/
;; dispatch` is the gating call). When the `:rf/causa` frame is
;; registered (`mount.cljs/open!` ran), the per-event collector / the
;; clear path / the depth-shrink path each request a mirror sync; a
;; coalescing scheduler collapses every same-tick request into ONE
;; `:rf.causa/sync-trace-buffer` dispatch that overwrites `:rf/causa`'s
;; `:trace-buffer` slot with the CURRENT `buffer-state` snapshot.
;; Pre-mount the frame is absent; the request is a silent no-op and the
;; atom still accumulates (the eventual `mount.cljs/open!` seeds the
;; slot from the atom). Per `mount.cljs/ensure-causa-frame!` for the
;; seed.
;;
;; ## Why a coalesced snapshot, not per-event dispatch (rf2-wq6gx)
;;
;; The original rf2-in6l2 design dispatched `:rf.causa/note-trace-event`
;; once per push so the layer-1 sub re-fired in lockstep with the atom.
;; That works for typical event volume; it breaks under saturation
;; (a synchronous JS task that emits ≥`re-frame.router/drain-depth-
;; default` = 100 trace events into the queue): the router's
;; depth-exceeded rollback restores `db-before`, the mirror snaps back
;; to a small count, successive drain attempts repeat the rollback, and
;; the slot stalls far below the atom's true contents. Coalescing every
;; same-tick mirror request into one snapshot dispatch caps the cascade
;; depth at 1 regardless of host trace-event volume — drain-depth
;; pressure is structurally eliminated, not just postponed.
;;
;; Snapshot semantics also obviate the per-event seed-race dedup walk
;; (rf2-z4fza): `ensure-causa-frame!` seeds the slot via
;; `dispatch-sync :rf.causa/sync-trace-buffer (trace-bus/buffer)`; the
;; coalesced post-seed sync writes the SAME snapshot (or a later one) —
;; never an append, so no duplicate-`:id` rows can land in the vector.
;;
;; `goog.async.nextTick` (re-exported via `re-frame.interop/next-tick`)
;; provides the microtask scheduler: same-tick requests batch; the tick
;; runs after the current JS task completes.

#?(:cljs
   (defonce ^:private mirror-sync-scheduled?
     ;; `compare-and-set!` sentinel — `true` while a microtask is queued
     ;; and not yet drained; reset to `false` immediately before the
     ;; queued tick reads the atom snapshot, so a request that arrives
     ;; after the snapshot read enqueues a fresh tick rather than
     ;; merging silently with one that's already in flight.
     (atom false)))

#?(:cljs
   (defn- request-mirror-sync!
     "Schedule a coalesced `:rf.causa/sync-trace-buffer` dispatch into
     the `:rf/causa` frame. Same-tick callers collapse to a single
     dispatch carrying the atom's current snapshot — capping the
     cascade depth at 1 regardless of how many `collect-trace!` /
     `clear-buffer!` / `set-buffer-depth!` calls land in this task.

     Pre-mount the frame is absent; the tick still drains (clears the
     sentinel) but the dispatch path no-ops — the atom keeps
     accumulating until `mount.cljs/open!` seeds via
     `dispatch-sync :rf.causa/sync-trace-buffer`."
     []
     (when (compare-and-set! mirror-sync-scheduled? false true)
       (interop/next-tick
         (fn []
           (reset! mirror-sync-scheduled? false)
           (when (some? (frame/frame :rf/causa))
             (rf/with-frame :rf/causa
               (rf/dispatch [:rf.causa/sync-trace-buffer @buffer-state]))))))))

;; ---- self-noise guard (rf2-xs8vu) ---------------------------------------
;;
;; Causa's own panels render INSIDE the host app. Every host dispatch
;; dirties the host app-db → the layer-1 `:rf.causa/trace-buffer` sub
;; re-fires → every Causa panel that derefs it re-renders → every
;; `:rf/causa`-frame sub it reads emits `:sub/run` → every re-render
;; emits `:view/render`. Without a guard, those self-induced trace
;; events flow back through the framework's trace-cb fan-out, through
;; THIS collector, into the buffer, and (because they fire outside a
;; host dispatch) bucket as `:ungrouped :ungrounded` — drowning the
;; host event the user actually cared about under a cascade of
;; `:rf.causa/*` sub-reads.
;;
;; The fix is at INGEST (not at READ time — readers shouldn't have to
;; know about internals). Any trace event whose `:frame` slot resolves
;; to `:rf/causa` is Causa's own machinery; we drop it before it ever
;; enters the buffer. The framework's `:rf.trace/no-emit?` flag on
;; Causa's registry handlers already silences `:event/dispatched` etc.
;; from Causa's bookkeeping cascade — this filter handles the
;; remaining sub-read + view-render emits that fire reactively from
;; panel re-renders.
;;
;; Pre-alpha posture: drop unconditionally. No "show internals" toggle
;; — if Causa needs to introspect its own machinery later, that's a
;; separate feature (a parallel Causa-internal buffer would be the
;; right shape), not an opt-out on the user-facing trace feed.

(defn causa-internal-event?
  "True when `event`'s `:frame` slot is `:rf/causa` — i.e. the trace
  event was emitted by Causa's own subscriptions, views, or any other
  machinery running under `(rf/with-frame :rf/causa ...)`.

  Reads top-level `:frame` first, falling back to `(:tags :frame)`,
  matching the resolution order `filter-events` already uses for the
  `:frame` filter axis. Both keys can carry the frame id depending on
  emit site (Spec 009 §Core fields hoists some, leaves others under
  `:tags`).

  Pure-data + JVM-runnable so the predicate is testable without a CLJS
  runtime."
  [event]
  (= :rf/causa (or (:frame event) (get-in event [:tags :frame]))))

;; ---- causa-internal event-id guard (rf2-g1pt8) --------------------------
;;
;; The ingest-side `causa-internal-event?` predicate above filters trace
;; events by `:frame`. It catches every emit that originates inside a
;; `(rf/with-frame :rf/causa ...)` scope — the dominant self-noise case
;; (panel re-render `:sub/run` + `:view/render` floods).
;;
;; But it misses one cleanup pattern: Causa-internal events (`:rf.causa/
;; focus-cascade`, `:rf.causa/select-tab`, `:rf.causa/open-settings`,
;; etc.) dispatched WITHOUT a `:frame :rf/causa` option. Those land on
;; the host's `:rf/default` frame (re-frame chain-resolution per
;; rf2-higwg), so the trace envelope carries `:frame :rf/default` —
;; the ingest filter waves them through into Causa's buffer, and the
;; L2 event list shows Causa instrumenting itself in the user-facing
;; cascade list. Concrete sites (as of this fix): the causality popover
;; node click in `popover/causality_graph.cljs:255`, the palette's
;; `:palette/select-panel` dispatch in `palette/events.cljs:175`, and
;; the headless `core/select-panel!` helper.
;;
;; Tightening every call site to pass `{:frame :rf/causa}` would be a
;; manual sweep with no structural guarantee. The data-layer guard
;; here is the single point of truth: cascades whose event vector's
;; head is in the `rf.causa` namespace are filtered out of the shared
;; `:rf.causa/cascades` sub, and every downstream consumer (the
;; filtered-cascades facade, the L2 event list, the spine, the
;; causality popover, performance / event-detail / trace / issues
;; tabs) inherits the filter automatically. Single point of truth;
;; readers stay simple.
;;
;; Pre-alpha posture mirrors `causa-internal-event?`: drop
;; unconditionally, no opt-out toggle. Causa's internals are
;; structurally invisible to the user.

(defn causa-internal-event-id?
  "True when `event-id` is a keyword in the `rf.causa` namespace
  (spec/Conventions.md §Reserved namespaces — Causa's canonical
  devtool prefix).

  Pure-data + JVM-runnable; nil-safe.

  Examples:
    (causa-internal-event-id? :rf.causa/focus-cascade)  ;; true
    (causa-internal-event-id? :rf.causa/select-tab)     ;; true
    (causa-internal-event-id? :cart/add-item)           ;; false
    (causa-internal-event-id? :rf/init)                 ;; false
    (causa-internal-event-id? nil)                      ;; false
    (causa-internal-event-id? \"rf.causa/x\")           ;; false (non-kw)"
  [event-id]
  (and (keyword? event-id)
       (= "rf.causa" (namespace event-id))))

(defn causa-internal-cascade?
  "True when `cascade`'s `:event` vector's head is a Causa-internal
  event-id (see `causa-internal-event-id?`). False for cascades whose
  event vector is absent (e.g. the `:ungrouped` bucket — those are
  filtered separately by `cascade-has-event?` at the L2 boundary).

  Pure-data + JVM-runnable. Used by the `:rf.causa/cascades` sub to
  hard-filter Causa's own events out of every downstream consumer
  per rf2-g1pt8."
  [cascade]
  (let [ev (:event cascade)]
    (and (vector? ev)
         (causa-internal-event-id? (first ev)))))

;; ---- collector --------------------------------------------------------

(defn collect-trace!
  "Append a trace event to Causa's ring buffer. Registered with
  `(rf/register-trace-cb! :rf.causa/trace-collector ...)` at preload
  time. Production builds elide the call (the framework's trace
  emission is gated on `interop/debug-enabled?` and never invokes the
  callback).

  Per rf2-xs8vu: trace events whose `:frame` is `:rf/causa` are
  dropped at ingest — Causa's own panels render inside the host app,
  so every host dispatch reactively re-fires Causa's subs and re-
  renders Causa's views; without the filter, those self-induced
  `:sub/run` + `:view/render` emits would land in Causa's own trace
  buffer as `:ungrouped :ungrounded` (they fire outside a host
  dispatch) and drown the host event the user clicked. See
  `causa-internal-event?`. Pre-alpha — no opt-out toggle; Causa-
  internal introspection is a separate feature surface if needed.

  Per Spec 009 §Privacy + rf2-azls9: events whose `:sensitive?` flag
  is true are dropped before the buffer push when the global
  `:trace/show-sensitive?` flag is false (the default). The
  suppressed-events counter bumps for the targeted frame so the
  shell can surface a `[● REDACTED N]` hint. Opt in via
  `(causa-config/configure! {:trace/show-sensitive? true})` to
  surface the raw cascade while debugging redaction policy.

  Per rf2-0vxdn the indicator is fully reactive: `config/note-
  suppressed!` itself dispatches `:rf.causa/note-sensitive-suppressed`
  (chain-resolved to `:rf/default` via rf2-higwg) so the sub reads
  `:suppressed-counters` off the host's app-db on the standard
  write path. The buffer state here is unchanged; the dispatch
  happens one stack frame deeper.

  Per rf2-e9s81 (supersedes rf2-iw5ym's parallel app-db write
  path): the trace-buffer's reactive surface is delivered by the
  layer-1 `:rf.causa/trace-buffer` sub's re-fire on the host's
  next app-db change — Causa is rendered inside an active host
  app, and every host dispatch dirties the resolved frame's
  app-db, so the sub re-fires and reads the latest buffer atom
  contents. The iw5ym dispatch path was removed because it could
  not target a Causa-owned frame at preload time (the host's
  `rf/init!` has not yet installed the substrate adapter) AND
  chain-resolving to `:rf/default` polluted the host's app-db
  with `:trace-buffer` noise while consuming drain-depth headroom
  on every emitted trace event — surfacing as spurious
  `:rf.error/drain-depth-exceeded` failures in conformance
  fixtures (the rf2-e9s81 follow-on). Per the ns docstring
  §Reactivity for the trade-off + the planned wider refactor."
  [event]
  (when interop/debug-enabled?
    (cond
      ;; rf2-xs8vu — drop Causa's own machinery before anything else.
      ;; Self-emitted sub-reads / view-renders from Causa's own panels
      ;; would otherwise drown the host event in `:ungrouped` noise.
      ;; Sits above the privacy gate so internal-frame sensitive events
      ;; (none expected today, but symmetrical) don't bump the host's
      ;; REDACTED counter. See `causa-internal-event?` for the contract.
      (causa-internal-event? event)
      nil

      (config/suppress-sensitive? event)
      (config/note-suppressed! (get-in event [:tags :frame]))

      :else
      (do
        (swap! buffer-state push @buffer-depth event)
        ;; Per rf2-wq6gx — request a coalesced snapshot sync into
        ;; `:rf/causa`'s `:trace-buffer` slot. Same-tick callers
        ;; collapse to ONE dispatch carrying the atom's current
        ;; contents, so a synthetic-saturation flood of 1000 pushes
        ;; queues one mirror event, not 1000 — the router's
        ;; drain-depth limit can never gate the mirror cascade
        ;; regardless of host trace-event volume. CLJS only — the JVM
        ;; build does not run panels. Pre-mount the request is a
        ;; silent no-op (frame absent); the atom keeps accumulating
        ;; and `mount.cljs/open!`'s seed lifts the contents at first
        ;; Ctrl+Shift+C.
        #?(:cljs (request-mirror-sync!))))))

;; ---- read-side accessors -------------------------------------------

(defn buffer
  "Return the buffer's current contents, oldest first. Empty in
  production (the buffer never receives events when
  `interop/debug-enabled?` is false at compile time)."
  []
  @buffer-state)

(defn current-depth
  "Return the configured buffer depth (`default-buffer-depth` until
  `set-buffer-depth!` rewrites it)."
  []
  @buffer-depth)

(defn seed-buffer-for-test!
  "Push `event` straight into the buffer atom, bypassing
  `collect-trace!`'s ingest gates (privacy filter, self-noise filter,
  app-db mirror dispatch). Test-only — callers that want to drive
  the public ingest path use `collect-trace!`.

  Lifted from the consumer-test suite (`filter_vocab_consumer_cljs_
  test.cljc`'s `seed!`) which seeds synthetic events into the buffer
  to exercise `filter-events`. Those tests need events shaped like
  `{:frame :rf/causa}` to land in the buffer to verify the `:frame`
  filter axis — but `collect-trace!` now (rf2-xs8vu) drops those
  before the buffer push, so the consumer-test suite cannot reach the
  buffer through the public collector. This helper preserves the
  consumer-test contract without weakening the production ingest
  guard.

  Pure mutation, no privacy / no mirror / no debug gate — strictly
  for assembling buffer fixtures in tests."
  [event]
  (swap! buffer-state push @buffer-depth event)
  nil)

;; ---- consumer-side filter ------------------------------------------------
;;
;; Causa's panels slice the buffer by the same filter vocabulary the
;; framework exposes on `(rf/trace-buffer opts)` per Spec 009 §Retain-N
;; trace ring buffer + §Filter vocabulary — the 13-axis vocabulary
;; (:operation / :op-type / :since / :frame / :severity / :event-id /
;; :handler-id / :source / :origin / :dispatch-id / :since-ms / :between
;; / :pred). The framework's filter is private inside `trace-buffer`;
;; consumers wanting the same algebra against a buffer they hold
;; themselves (Causa's deeper ring; a snapshot lifted out of a session)
;; would otherwise duplicate the predicate. `filter-events` exposes that
;; algebra as a pure-data fn against an arbitrary event vector, locking
;; the consumer contract: when Causa panels begin slicing the buffer,
;; they use the same vocabulary the framework does.
;;
;; Pure-data + JVM-runnable so the JVM test suite can drive every axis
;; without booting a CLJS runtime. No atoms, no interop, no swap!.

(defn build-filter-predicate
  "Compile the 13-axis filter map into a single predicate `(fn [ev] truthy)`.

  Lifted from `filter-events` (rf2-7mwc8 / audit 2a) so the trace
  panel's `project-feed` can fold filtering into a single walk over
  the buffer rather than running a separate `filter-events` pass.
  Pure data → fn; JVM-runnable.

  Returns a fn that always returns true when `opts` is empty or nil
  — callers MAY skip the filter when no axis is active."
  [opts]
  (if (empty? opts)
    (fn [_ev] true)
    (let [{:keys [operation op-type since frame
                  severity event-id handler-id source origin
                  dispatch-id since-ms between pred]} opts
          [between-t0 between-t1] (when (and (sequential? between)
                                             (= 2 (count between)))
                                    between)]
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
             (or (nil? pred) (pred ev)))))))

(defn filter-events
  "Apply the trace-buffer filter vocabulary to an arbitrary event vector.
  Returns a vector containing only events where every supplied filter
  key matches. Filters compose AND-wise; an absent key means
  'no constraint on that axis'. Mirrors `re-frame.trace/trace-buffer`'s
  filter algebra so Causa-side consumers slice the buffer using the
  same vocabulary the framework exposes.

  Recognised keys (the 13-axis vocabulary per Spec 009 §Retain-N
  trace ring buffer + §Filter vocabulary):

      :operation     — keep events with this :operation value.
      :op-type       — keep events with this :op-type value.
      :since         — keep events whose :id is strictly greater.
      :frame         — keep events whose :frame (top-level or :tags) matches.
      :severity      — keep events whose :op-type matches the tier
                       (:error / :warning / :info). Synonym for :op-type
                       restricted to those three values.
      :event-id      — keep events whose :tags :event-id matches.
      :handler-id    — keep events whose :tags :handler-id matches.
      :source        — keep events whose :source (top-level, hoisted
                       from :tags by emit!) matches.
      :origin        — keep events whose :tags :origin matches.
      :dispatch-id   — keep events whose :tags :dispatch-id matches
                       (cascade-wide).
      :since-ms      — keep events whose :time is strictly greater than
                       this host-clock timestamp.
      :between       — `[t0 t1]` two-element vector — keep events whose
                       :time falls in [t0, t1] inclusive.
      :pred          — `(fn [ev] -> truthy)` arbitrary predicate.

  Returns `events` unchanged (as a vector) when `opts` is empty or nil.

  Locks the consumer contract against the framework's filter
  vocabulary."
  ([events] (filter-events events nil))
  ([events opts]
   (if (empty? opts)
     (vec events)
     (filterv (build-filter-predicate opts) events))))

(defn clear-buffer!
  "Empty the buffer. Tooling uses this between sessions. No-op in
  production. Per rf2-azls9, also resets the per-frame
  suppressed-sensitive counters so the bottom-rail `[● REDACTED N]`
  hint disappears alongside the cleared events — clearing the buffer
  is the natural moment to drop the 'you missed N events' overhang.

  Per rf2-0vxdn `config/reset-suppressed-count!` itself dispatches
  `:rf.causa/reset-suppressed-counters` in CLJS so the reactive
  app-db slot clears in lockstep with the atom (the indicator path
  is genuinely on app-db, separate from the trace buffer)."
  []
  (when interop/debug-enabled?
    (reset! buffer-state [])
    ;; Per rf2-wq6gx — request a coalesced snapshot sync; the
    ;; microtask will write the now-empty atom into the slot,
    ;; clearing the reactive surface in lockstep with the atom.
    ;; Pre-mount this is a silent no-op (frame absent).
    #?(:cljs (request-mirror-sync!))
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
                 :else         v))))
    ;; Per rf2-wq6gx — request a coalesced snapshot sync; the
    ;; microtask will write the post-shrink atom contents into the
    ;; slot.
    #?(:cljs (request-mirror-sync!)))
  nil)

;; ---- retroactive scrub on set-show-sensitive! false (rf2-lqmje) ---------
;;
;; Per Spec 009 §Privacy §Retroactive-scrub on `set-show-sensitive!`
;; false: toggling the flag from true → false clears the trace buffer.
;; The collector only gates at ingest (`collect-trace!` consults
;; `config/suppress-sensitive?`), so without this hook a sensitive
;; cascade emitted while the flag was true would remain visible in
;; every panel after the user expected privacy to be restored.
;;
;; The clear cascades through `clear-buffer!`:
;;   - empties the atom + mirrors `:rf.causa/clear-trace-buffer` into
;;     `:rf/causa` so the reactive `:trace-buffer` sub drops in lockstep,
;;   - calls `config/reset-suppressed-count!` so the `[● REDACTED N]`
;;     hint resets (the bottom rail's counter is conceptually
;;     "since-last-clear", not "since-process-start").
;;
;; The hook registers at load time via a top-level form (guarded by
;; `interop/debug-enabled?` — production builds keep the registration
;; out of the bundle alongside the rest of the trace-bus surface).

(when interop/debug-enabled?
  (config/register-toggle-off-callback! ::clear-on-toggle-off clear-buffer!))
