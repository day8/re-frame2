(ns re-frame.trace.tooling
  "Trace tooling sibling of `re-frame.trace` — carries the public
  dev-tooling surface (`register-listener!` / `unregister-listener!` /
  `clear-listeners!` / `trace-buffer` / `clear-trace-buffer!` /
  `configure-trace-buffer!`) and the per-frame
  run-keyed trace rings + listener state.

  ## Per-frame trace rings

  Each frame owns its own per-event ring. Storage shape (per frame):

      {:events-retained N               ;; the per-frame ring depth (cap)
       :override?         <boolean>     ;; true iff N came from an explicit
                                        ;; per-frame `:rf.trace/events-retained`
       :run-order [<dispatch-id> ...]  ;; oldest-first per-event slots
       :runs {<dispatch-id> [<event> ...]}}

  - The unit of retention is the EVENT — one dequeued event / pipeline
    run (one `:rf.trace/dispatch-id`) = one slot, regardless of how many
    trace events that run emitted. When event #N+1 arrives, the OLDEST
    slot (and every trace event emitted under its `:dispatch-id`) is
    evicted as a unit.
  - `:events-retained` defaults to 50; per-frame override via
    `:rf.trace/events-retained` on the frame config.
  - `:override?` distinguishes an explicit per-frame override from an
    inherited process default. EVERY ring stores `:events-retained`
    (it is the live cap consulted on each push), so the cap value alone
    cannot tell the two apart — `configure-trace-buffer!` keys on
    `:override?` to decide which rings the new process default may
    retune.
  - `:events-retained 0` disables retention (no slots allocated; the
    live stream still fires).
  - Frameless emits (no `:rf.trace/dispatch-id` in scope) **skip the
    rings entirely** (B3); they stream live to registered listeners
    only.

  `re-frame.trace` itself carries only the hot emit fast
  path (`emit!` / `emit-error!` / `*handler-scope*` + bracket macros).
  The listener registry + ring storage + filter predicate are tooling
  concerns; production counter bundles never touch them.

  Wiring: at ns load this ns publishes the following hooks through
  `re-frame.late-bind`:
    - `:trace.tooling/deliver!`            (emit-time fan-out)
    - `:trace.tooling/release-frame-ring!` (frame-destroy cleanup)
    - `:trace.tooling/configure-trace-buffer!` /
      `:trace.tooling/current-trace-buffer-config` (the write / read pair
                                            behind `re-frame.core`'s
                                            `configure!` + `current-config`)

  `re-frame.core` and `re-frame.trace` both `:require` this ns
  statically, so every bundle loads it and these hooks are always
  published. What keeps a production CLJS bundle clean is var-level: the
  listener / buffer surface is reached only through dev-gated call sites,
  so Closure drops the bodies nothing calls — the `trace-tooling` entry in
  `implementation/scripts/check-bundle-isolation.cjs` pins the
  `trace-buffer` body's absence from the counter bundle. Consumers still
  treat an unpublished hook as inert, so the trace fast path / registrar
  short-circuit cleanly without one.

  Per Spec 009 §Per-frame trace rings."
  (:require [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            ;; `event-bundle` reuses the six-domino fold
            ;; (`absorb` over `empty-event-bundle`) from the projection ns
            ;; rather than re-inlining the classification cond. Both nss
            ;; are dev-side and bundle-isolated from production CLJS (the
            ;; `trace-tooling` bundle-isolation entry pins the tooling
            ;; body's absence from the counter bundle); projection carries no
            ;; requires of its own, so this edge introduces no cycle.
            [re-frame.trace.projection :as rf.trace.projection]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- listener registry ----------------------------------------------------

(defonce ^:private listeners (atom {}))    ;; id → fn

(defn register-listener!
  "Register a listener that receives every trace event. The id can be any
  comparable value; passing the same id twice replaces. Returns the id."
  [id f]
  (swap! listeners assoc id f)
  id)

(defn unregister-listener!
  [id]
  (swap! listeners dissoc id)
  nil)

;; ---- per-run :rf.view/rendered budget (dev-only) -------------------------
;;
;; Per Spec 009 §`:rf.view/rendered`, a frame emits at most
;; `re-frame.views`' cap of `:rf.view/rendered` per run. The count is kept
;; here, on core's own run state, so the cap holds whether or not the
;; optional epoch artefact is loaded. A frame's count resets when a run
;; starts in it (`:rf.event/run-start`, seen by `deliver-to-tooling!`), and
;; renders after the run settles — React's post-settle batch, the common
;; browser case — keep counting against that run until the next one starts.

(defonce ^:private rendered-counts (atom {}))

(defn claim-render!
  "Count one `:rf.view/rendered` against `frame-id`'s current run and
  return how many renders the run had claimed before this one."
  [frame-id]
  (get (first (swap-vals! rendered-counts update frame-id (fnil inc 0)))
       frame-id 0))

(defn- reset-render-budget!
  "Start a fresh render budget for the frame `event` names when `event` is
  a run start. Dev-only, so production keeps no `:rf.event/run-start`
  literal."
  [event]
  (when rf.interop/debug-enabled?
    (when (= :rf.event/run-start (:operation event))
      (swap! rendered-counts dissoc (get-in event [:tags :frame])))))

(defn clear-listeners!
  "Drop every registered listener and every frame's render budget. The
  test-runtime reset calls this between scenarios."
  []
  (reset! listeners {})
  (reset! rendered-counts {})
  nil)

;; ---- per-frame run-keyed trace rings (dev-only) ---------------------
;;
;; Storage shape (per Spec 009 §Per-frame trace rings):
;;
;;   trace-rings  = {<frame-id> {:events-retained N
;;                               :override?         <boolean>
;;                               :run-order [<dispatch-id> ...]
;;                               :runs       {<dispatch-id> [<event>...]}}}
;;
;; - `:run-order` is the oldest-first vector of `:dispatch-id`s
;;   currently held in this frame's ring; eviction pops the head.
;; - `:runs` is a flat map from `:dispatch-id` → trace events
;;   emitted under that dispatch, in arrival order.
;; - The slot count is bounded by `:events-retained` (default 50) — one
;;   slot per EVENT (one dequeued event / pipeline run), regardless of how
;;   many trace events that run emitted.
;; - `:override?` true iff the cap is an explicit per-frame override;
;;   false for an inherited default.
;;   `configure-trace-buffer!` retunes inherited rings, skips overrides.
;; - Frames lazily allocate slots on first emit. Apps with one frame
;;   pay one slot of ring overhead.

(def ^:private default-events-retained
  "Per Spec 009 §Per-frame trace rings — the per-frame retention default
  when neither `:rf.trace/events-retained` on the frame config nor
  `(configure :trace-buffer ...)` supplies one. 50 retained events (one
  slot per dequeued event / pipeline run) — enough to cover a short
  interactive session without per-frame memory pressure."
  50)

(defonce ^:private trace-rings (atom {}))

;; Process-default events-retained — applies to `:rf/default` and any
;; frame that did not set per-frame `:rf.trace/events-retained` metadata.
;; Per Spec 009 §Retention contract — the single knob.
(defonce ^:private process-events-retained (atom default-events-retained))

(defn- empty-ring
  "A fresh ring at cap `retained`. `override?` records whether the cap is
  an explicit per-frame override (default false = inherited process
  default) so `configure-trace-buffer!` can tell the two apart."
  ([retained] (empty-ring retained false))
  ([retained override?]
   {:events-retained retained
    :override?         override?
    :run-order     []
    :runs          {}}))

(defn- effective-retained
  "Return the live events-retained cap for `frame-id`. Honours the
  ring's stored cap (whether it came from an explicit per-frame override
  or a previously-inherited process default — both store
  `:events-retained`) before falling back to the current process
  default for a frame that has no ring yet."
  [rings frame-id]
  (or (get-in rings [frame-id :events-retained])
      @process-events-retained))

(defn- resize-ring
  "Return `existing` resized to `retained`, preserving the newest slots and
  stamping whether the cap is a frame override or the inherited process
  default. Always returns a complete ring."
  [existing retained override?]
  (let [order (or (:run-order existing) [])
        runs  (or (:runs existing) {})]
    (cond
      (zero? retained)
      (empty-ring 0 override?)

      (> (count order) retained)
      (let [drop-n     (- (count order) retained)
            evicted    (subvec order 0 drop-n)
            ;; A fresh vector, not a `subvec` view — see `push-to-ring!`.
            kept-order (into [] (subvec order drop-n))]
        {:events-retained retained
         :override?      override?
         :run-order      kept-order
         :runs           (apply dissoc runs evicted)})

      :else
      {:events-retained retained
       :override?      override?
       :run-order      order
       :runs           runs})))

(defn apply-frame-events-retained-policy!
  "Publish one frame config's retention policy when `current?` still says that
  config owns the authoritative frame record.

  `override? true` applies `retained` as the explicit
  `:rf.trace/events-retained` cap. `override? false` removes any prior override,
  resizes the existing ring to the live process default, and marks it inherited
  so later process-default changes continue to reach it. The liveness predicate
  runs INSIDE the trace-ring atom update; the frame engine serializes auxiliary
  publication around it. This makes the store respect the frame registry's
  winner without a second ownership registry."
  [frame-id override? retained current?]
  (when (and rf.interop/debug-enabled?
             (or (not override?)
                 (and (number? retained) (not (neg? retained)))))
    (swap! trace-rings
           (fn [rings]
             (if-not (current?)
               rings
               (if override?
                 (assoc rings frame-id
                        (resize-ring (get rings frame-id) retained true))
                 (if-let [existing (get rings frame-id)]
                   (assoc rings frame-id
                          (resize-ring existing @process-events-retained false))
                   rings))))))
  nil)

(defn release-frame-ring!
  "Drop `frame-id`'s ring and per-frame retention setting entirely.
  Called from `destroy-frame!` so a destroyed frame leaves no residual
  ring state in memory. Per Spec 002 §Destroy and Spec 009 §Per-frame
  trace rings."
  [frame-id]
  (when rf.interop/debug-enabled?
    (swap! trace-rings dissoc frame-id)
    (swap! rendered-counts dissoc frame-id))
  nil)

(defn- push-to-ring!
  "Append `ev` to its frame's run-keyed ring. Frameless emits (no
  `:rf.trace/dispatch-id` in scope) bypass the ring entirely per B3 —
  they stream live to listeners only and are never retained.

  The destination frame-id is the event's own `[:tags :frame]` — the
  single canonical raw-event frame path (Spec 009 §Frame identity on
  the raw event; `re-frame.trace/trace-event-frame` is the accessor
  consumers outside the trace subsystem read it through, and this ns
  cannot require it — `re-frame.trace` requires THIS ns).

  There is no fallback chain behind the tag (a top-level `:frame` on the
  envelope, then the late-bound `:frame/current-frame-id` hook):
  `build-event` supplies the tag from the ambient frame for every emit
  site that doesn't stamp it (`re-frame.trace/stamp-frame`), so a
  fallback would be the SAME resolution done a second time, and would let
  the ring and the event's own tag disagree about which frame a row
  belongs to. There is no top-level `:frame` on a raw trace event at all.

  No-op in production (production never reaches the emit site)."
  [ev]
  (when rf.interop/debug-enabled?
    (let [dispatch-id (get-in ev [:tags :rf.trace/dispatch-id])
          frame-id    (get-in ev [:tags :frame])]
      ;; Frameless emits (no in-flight run) skip the ring. Per B3,
      ;; frameless events stream live to listeners only
      ;; and are never retained. A `:dispatch-id` without a resolvable
      ;; frame-id (extremely rare — would require a manual emit from
      ;; outside any framework boundary) also skips the ring; there is
      ;; nowhere to put it.
      (when (and dispatch-id frame-id)
        (swap! trace-rings
               (fn [rings]
                 (let [retained  (effective-retained rings frame-id)
                       override? (true? (get-in rings [frame-id :override?]))]
                   (if (zero? retained)
                     ;; Disabled-but-live: live stream still fires
                     ;; (push-to-ring! is one step in the pipeline);
                     ;; just don't allocate. Preserve the override flag so
                     ;; a 0-cap override survives a push attempt.
                     (assoc rings frame-id (empty-ring 0 override?))
                     (let [existing (get rings frame-id (empty-ring retained override?))
                           ;; Defensive nil-coercion: a ring written before
                           ;; this frame's first emit is always complete
                           ;; (`apply-frame-events-retained-policy!` writes
                           ;; a full ring), but coerce anyway so a partial
                           ;; ring can't make `conj` build a list / `subvec`
                           ;; throw.
                           order    (or (:run-order existing) [])
                           runs (or (:runs existing) {})
                           known?   (contains? runs dispatch-id)
                           order'   (if known? order (conj order dispatch-id))
                           runs' (update runs dispatch-id
                                             (fnil conj []) ev)
                           ;; Evict oldest run slots while over cap.
                           [order'' runs'']
                           (loop [o order' c runs']
                             (if (> (count o) retained)
                               (let [oldest (first o)]
                                 (recur (subvec o 1) (dissoc c oldest)))
                               ;; Copy the survivors out of the `subvec`: a
                               ;; later `conj` onto a subvec appends to its
                               ;; BACKING vector, so the spine would retain
                               ;; every evicted dispatch-id.
                               [(if (identical? o order') o (into [] o)) c]))]
                       (assoc rings frame-id
                              {:events-retained retained
                               :override?         override?
                               :run-order     order''
                               :runs          runs''}))))))))))

;; ---- trace-buffer reader -------------------------------------------------
;;
;; Per Spec 009 §`trace-buffer` API — per-frame, event bundles by
;; default. `:flat true` opt returns raw trace events.

(defn- event-bundle
  "Project the events for one pipeline run into a Spec 009 event bundle
  (the `group-by-event` shape, per run / dequeued event).

  Folds with `re-frame.trace.projection/absorb` over
  `rf.trace.projection/empty-event-bundle` — the canonical six-domino classification
  + slot set — rather than re-inlining the bucketing cond + the six
  `*-bucket?`/`*-marker?` predicates a second time. The seed map carries
  the extra `:trace-events` slot the event-bundle wire shape needs;
  `absorb` only writes the domino slots, so `:trace-events` (and the
  pre-seeded `:dispatch-id` / `:frame`) survive the fold untouched."
  [frame-id dispatch-id events]
  (reduce rf.trace.projection/absorb
          (assoc rf.trace.projection/empty-event-bundle
                 :dispatch-id  dispatch-id
                 :frame        frame-id
                 :trace-events events)
          events))

(defn- match-event-bundle?
  "Filter an event bundle against the bundle-level filter keys."
  [bundle {:keys [event-id origin dispatch-id since-ms between pred]}]
  (let [[t0 t1] (when (and (sequential? between)
                           (= 2 (count between)))
                  between)
        events  (:trace-events bundle)
        ;; An event bundle's "time" is its earliest event's time (where present)
        first-time (some :time events)]
    (and (or (nil? event-id)
             (= event-id (first (:event bundle))))
         (or (nil? origin)
             (when-let [d (:dispatched bundle)]
               (= origin (get-in d [:tags :rf.event/origin]))))
         (or (nil? dispatch-id)
             (= dispatch-id (:dispatch-id bundle)))
         (or (nil? since-ms)
             (and (number? first-time) (> first-time since-ms)))
         (or (nil? t0)
             (and (number? first-time)
                  (<= t0 first-time t1)))
         (or (nil? pred) (pred bundle)))))

(defn- match-event?
  "Filter a raw trace event against the event-level filter keys."
  [ev {:keys [operation op-type since severity event-id handler-id source
              origin dispatch-id since-ms between sensitive? pred]
       :as opts}]
  (let [[t0 t1] (when (and (sequential? between)
                           (= 2 (count between)))
                  between)]
    (and (or (nil? operation) (= operation (:operation ev)))
         (or (nil? op-type)   (= op-type   (:op-type ev)))
         (or (nil? since)     (and (number? (:id ev)) (> (:id ev) since)))
         (or (nil? severity)  (= severity (:op-type ev)))
         (or (nil? event-id)
             (= event-id (get-in ev [:tags :rf.trace/event-id])))
         (or (nil? handler-id)
             (= handler-id (get-in ev [:tags :handler-id])))
         (or (nil? source)
             (= source (or (:source ev)
                           (get-in ev [:tags :source]))))
         (or (nil? origin)
             (= origin (get-in ev [:tags :rf.event/origin])))
         (or (nil? dispatch-id)
             (= dispatch-id (get-in ev [:tags :rf.trace/dispatch-id])))
         (or (nil? since-ms)
             (and (number? (:time ev)) (> (:time ev) since-ms)))
         (or (nil? t0)
             (and (number? (:time ev)) (<= t0 (:time ev) t1)))
         (or (nil? sensitive?)
             (= (true? sensitive?) (true? (:sensitive? ev))))
         (or (nil? pred) (pred ev)))))

(defn- frame-ring-event-bundles
  "Materialise `frame-id`'s ring as an oldest-first vector of event
  bundles. Returns an empty vector when the frame has no recorded
  events (including the destroyed-frame / unknown-frame case per
  Spec 009 §Reads against a destroyed / missing frame)."
  [rings frame-id]
  (if-let [ring (get rings frame-id)]
    (let [order    (:run-order ring)
          runs (:runs ring)]
      (mapv (fn [dispatch-id]
              (event-bundle frame-id dispatch-id (get runs dispatch-id)))
            order))
    []))

(defn- frame-ring-flat-events
  "Materialise `frame-id`'s ring as an oldest-first vector of raw trace
  events (the `:flat true` opt-in shape)."
  [rings frame-id]
  (if-let [ring (get rings frame-id)]
    (let [order    (:run-order ring)
          runs (:runs ring)]
      (into [] (mapcat (fn [did] (get runs did))) order))
    []))

(defn trace-buffer
  "Per-frame trace ring reader. Returns the named frame's retained
  events, oldest-first (one bundle per dequeued event / pipeline run).

  Two arities:
    (trace-buffer frame-id)        — event bundles (default)
    (trace-buffer frame-id opts)   — opts filter map

  Event-bundle shape:
    {:dispatch-id <id>
     :frame       <frame-id>
     :trace-events [<ev> ...]
     :event       <event-vector or nil>
     :dispatched  <:rf.event/dispatched event or nil>
     :handler     <:rf.event/run-end event or nil>
     :fx          <:rf.fx/do-fx event or nil>
     :effects     [...]
     :subs        [...]
     :renders     [...]
     :other       [...]
     :parent-dispatch-id <causal-parent-id or nil>}

  Opt keys recognised (per Spec 009 §Filter vocabulary):
    :flat true      — return raw trace events (oldest-first) instead
                      of event bundles. The escape hatch for callers
                      with flat-stream code.
    :operation      — (:flat-only) exact :operation match
    :op-type        — (:flat-only) exact :op-type match
    :since          — (:flat-only) :id strictly greater than this
    :severity       — (:flat-only) :op-type ∈ #{:error :warning :info}
    :handler-id     — (:flat-only) :tags :handler-id match
    :source         — (:flat-only) :source match (the single closed-enum
                      functional-origin axis; there is no separate
                      `:rf/dispatch-origin` filter key)
    :sensitive?     — (:flat-only) :sensitive? match
    :event-id       — bundle :event first-element OR (:flat) :tags :rf.trace/event-id
    :origin         — bundle root :rf.event/origin OR (:flat) per-event
    :dispatch-id    — bundle :dispatch-id OR (:flat) per-event
    :since-ms       — bundle earliest-time OR (:flat) per-event :time
    :between [t0 t1]— same axis, window
    :pred           — arbitrary predicate, receives bundle or event

  Returns `[]` for a destroyed / never-registered frame (per Spec 009
  §Reads against a destroyed / missing frame) and `[]` in production
  (the ring is never allocated under `goog.DEBUG=false`).

  Per Spec 009 §`trace-buffer` API."
  ([frame-id] (trace-buffer frame-id {}))
  ([frame-id opts]
   (if-not rf.interop/debug-enabled?
     []
     (let [rings @trace-rings]
       (if (:flat opts)
         (filterv #(match-event? % opts) (frame-ring-flat-events rings frame-id))
         (filterv #(match-event-bundle? % opts) (frame-ring-event-bundles rings frame-id)))))))

(defn- cleared-ring
  "Return `frame-id`'s ring emptied at its own effective retention cap.
  Preserves the override flag — emptying a buffer must not silently
  downgrade an explicit per-frame override to inherited."
  [rings frame-id]
  (empty-ring (effective-retained rings frame-id)
              (true? (get-in rings [frame-id :override?]))))

(defn clear-trace-buffer!
  "Empty retained trace events. Tooling uses this between sessions.

      (clear-trace-buffer!)           ; every frame's ring
      (clear-trace-buffer! frame-id)  ; the named frame's ring

  A DATA clear, not a fixture reset. Each ring is re-emptied at its own
  effective `:rf.trace/events-retained` cap, so the process default set by
  `(configure! {:trace-buffer {:events-retained N}})` and every frame's
  explicit override both survive. `clear-trace-rings!` is the fixture-grade
  reset that additionally drops both.

  No-op for an unknown frame, no-op in production. Per Spec 009
  §`trace-buffer` API."
  ([]
   (when rf.interop/debug-enabled?
     (swap! trace-rings
            (fn [rings]
              (reduce-kv (fn [acc frame-id _ring]
                           (assoc acc frame-id (cleared-ring rings frame-id)))
                         {}
                         rings))))
   nil)
  ([frame-id]
   (when rf.interop/debug-enabled?
     (swap! trace-rings
            (fn [rings]
              (if (contains? rings frame-id)
                (assoc rings frame-id (cleared-ring rings frame-id))
                rings))))
   nil))

(defn clear-trace-rings!
  "Drop EVERY frame's ring + reset the process-default
  events-retained back to its initial value. Test-fixture / `clear-all!`-shaped helper — symmetric with
  the listener registry's `clear-listeners!`. Production no-op.

  This resets retention POLICY as well as data. A clear that should leave
  the user's configured retention in force — a UI \"clear buffer\" button, a
  privacy scrub — wants the 0-arity `clear-trace-buffer!` instead."
  []
  (when rf.interop/debug-enabled?
    (reset! trace-rings {})
    (reset! process-events-retained default-events-retained)
    (reset! rendered-counts {}))
  nil)

(defn current-trace-buffer-config
  "Return the live PROCESS-DEFAULT retention as `{:events-retained N}` —
  the read twin of `configure-trace-buffer!`, in the same shape
  `re-frame.core/configure!` accepts under its `:trace-buffer` key.

  Process default only. A frame carrying its own
  `:rf.trace/events-retained` metadata override is deliberately not
  reflected: this reads back the one slot `configure-trace-buffer!`
  writes, and per-frame caps are frame metadata rather than process
  config. Published to `re-frame.core/current-config` through
  `:trace.tooling/current-trace-buffer-config`."
  []
  {:events-retained @process-events-retained})

(defn configure-trace-buffer!
  "Apply a process-default ring depth. Per Spec 009 §Retention contract:

      (configure :trace-buffer {:events-retained N})

  Sets the per-process default that applies to `:rf/default` and to
  every frame that did not set its own `:rf.trace/events-retained`
  metadata. Frames with explicit per-frame metadata are NOT overridden.
  The retained unit is one slot per EVENT (one dequeued event / pipeline
  run), regardless of how many trace events that run emitted.

  When `:events-retained 0`, the ring is disabled but the surface
  remains live. Per Spec 009 §Events-retained-zero semantics.

  `:events-retained` is the SOLE recognised opt. An opts map that
  lacks a usable `:events-retained` (e.g. the retired `{:depth N}`
  shape, or a negative / non-numeric value) is a no-op that emits a
  `:rf.warning/trace-buffer-unrecognised-opts` trace rather than
  failing silently — a misconfigured retention knob would otherwise
  leave the ring at its default while the caller believes it was
  tuned (per Spec 009 §Error & warning catalog).

  No-op in production. Returns nil."
  [{:keys [events-retained] :as opts}]
  (when (and rf.interop/debug-enabled?
             (not (and (number? events-retained)
                       (not (neg? events-retained)))))
    ;; Loud-not-silent: a config call that supplied opts we can't apply
    ;; is a misuse the runtime recovers from (the ring stays at its
    ;; current default) but must surface. Routed through the late-bound
    ;; `:trace/emit!` (NOT `:trace/emit-error!`, which hardcodes
    ;; op-type `:error`) so this ns carries no static dep on
    ;; `re-frame.trace`; the category's `:rf.warning/*` root and its
    ;; Spec 009 catalogue row are both `:op-type :warning` — a
    ;; `{:severity :warning}` `trace-buffer` filter must catch this
    ;; emit, which `match-event?`'s `:severity` key matches against
    ;; `(:op-type ev)`.
    (when-let [emit! (rf.late-bind/get-fn :trace/emit!)]
      (emit! :warning :rf.warning/trace-buffer-unrecognised-opts
             {:category :rf.warning/trace-buffer-unrecognised-opts
              :opts     opts
              :reason   (str "(configure! {:trace-buffer ...}) accepts only "
                              "{:events-retained N} where N is a non-negative "
                              "integer; got " (pr-str opts) ". Retention is "
                              "unchanged. The retired {:depth N} shape is not "
                              "supported — use {:events-retained N}.")})))
  (when (and rf.interop/debug-enabled?
             (number? events-retained)
             (not (neg? events-retained)))
    (reset! process-events-retained events-retained)
    ;; Apply the new default to every frame whose cap was INHERITED
    ;; (`:override? false`), retuning + trimming the already-allocated
    ;; ring. Frames carrying an explicit per-frame override
    ;; (`:override? true`) are left untouched. Keying on `:override?`,
    ;; not the always-present `:events-retained` key, is load-bearing:
    ;; every allocated ring stores `:events-retained`, so a
    ;; `(some? (get-in ... :events-retained))` guard would always be true
    ;; and silently skip EVERY existing ring — lowering the default would
    ;; never trim an already-used inherited frame.
    (swap! trace-rings
           (fn [rings]
             (reduce-kv
              (fn [acc frame-id ring]
                (if (true? (:override? ring))
                  acc
                  (let [order    (or (:run-order ring) [])
                        runs (or (:runs ring) {})]
                    (cond
                      (zero? events-retained)
                      (assoc acc frame-id (empty-ring 0))

                      (> (count order) events-retained)
                      (let [drop-n     (- (count order) events-retained)
                            ;; A fresh vector, not a `subvec` view — see
                            ;; `push-to-ring!`.
                            kept-order (into [] (subvec order drop-n))
                            evicted    (subvec order 0 drop-n)]
                        (assoc acc frame-id
                               {:events-retained events-retained
                                :override?         false
                                :run-order     kept-order
                                :runs          (apply dissoc runs evicted)}))

                      :else
                      (assoc acc frame-id
                             {:events-retained events-retained
                              :override?         false
                              :run-order     order
                              :runs          runs})))))
              rings
              rings))))
  nil)

;; ---- delivery hook ------------------------------------------------------
;;
;; `re-frame.trace/deliver!` looks this up via late-bind and calls it
;; once per emitted event (after the epoch-capture fan-out). Centralising
;; the ring-push + listener fan-out in one hook keeps trace.cljc free
;; of any reference to the rings state or listener atom — so a
;; production build that never `:requires` this ns DCEs the whole body.

;; ---- the fan-out queue (emission order, no re-entry) ----------------------
;;
;; A listener callback may itself emit a trace (dispatch, re-register a flow,
;; create/destroy a frame — all emit). Listener delivery is ONE FIFO queue owned
;; by the OUTERMOST deliverer (Spec 009 §Listener invocation rules):
;;
;;   - an emit raised from inside a listener body APPENDS its event to the queue
;;     of the fan-out in progress on this thread and returns at once;
;;   - the outermost deliverer's loop (`drive-fanout!`) delivers each queued
;;     event to every listener in its snapshot before moving on to the next, and
;;     runs until the queue is empty.
;;
;; So every listener sees events in emission order, no listener callback is ever
;; re-entered (a nested event waits until the callback that emitted it has
;; returned), and every event reaches every listener before the outermost
;; delivery returns. A nested `emit!` returns BEFORE its event is delivered: a
;; listener that emits and then inspects another listener's state finds the
;; emission not yet processed. The ring push happens inline in emission order,
;; epoch capture already fired in `re-frame.trace/deliver!` before this hook, and
;; classification ran in `emit!`, so none of them waits for the queue.
;;
;; Each queued entry is `[event continue? entries]`: the event, its OWN
;; authority, and the listener snapshot it is delivered to. A listener that
;; destroys the outer incarnation flips that event's `continue?` false and
;; suppresses its remaining fan-out without touching a later event's authority.
;; A nested event is delivered after the listener body that emitted it has
;; returned, so it is queued exactly as a deferred drain-owned event is — snapshot
;; taken at enqueue, `continue?` read through `deferred-continue` — and the one
;; rule below ("deferral moves only the TIMING of delivery, never its CONTENT")
;; covers both. The listener body already ran under the neutral continuation scope
;; `re-frame.trace/deliver!` established.
;;
;; The queue belongs to a THREAD and is never conveyed to another (`fanout-queue`
;; is a JVM `ThreadLocal`; CLJS uses a volatile). JVM `next-tick` and
;; `set-timeout!` convey dynamic bindings, so a drain a listener schedules runs
;; on the executor with that listener's bindings; had it appended its traces to
;; the scheduling thread's queue, whose loop has already finished, no listener
;; would ever receive them. It opens its own outermost fan-out instead.
;;
;; ---- cross-thread fan-out serialization -----------------------------------
;;
;; The fan-out queue only orders SAME-thread emits. It says nothing about two
;; emits racing on two JVM threads: each opens its OWN outermost fan-out and each
;; would invoke the SAME registered listener callback CONCURRENTLY. That violates
;; the public listener contract (`docs/api/re-frame.core.md`: "Delivery is
;; synchronous: the callback returns before the next record") and Spec 009
;; §Listener invocation rules, which require synchronous, in-order,
;; event-at-a-time delivery PER listener — a callback the tool author was told can
;; never re-enter itself would be run on two threads at once, and B could reach a
;; listener before A even when A's emit began first.
;;
;; A single process-global monitor (`fanout-monitor`) serializes each OUTERMOST
;; fan-out — its whole `drive-fanout!`, every event queued behind the first
;; included. Concurrent clean emits therefore linearize on monitor-acquisition
;; order: the second thread cannot begin its fan-out until the first thread's
;; queue has fully drained. No listener callback ever overlaps itself, records
;; reach each listener in a single defined order, and an outermost emit still
;; returns only after its record has reached every listener (the monitor is held
;; for the whole synchronous drive and released before `emit!` returns).
;;
;; ---- the post-drain deferral seam -----------------------------------------
;;
;; Holding `fanout-monitor` across arbitrary listener bodies is NOT safe for an
;; emit issued while the emitting thread holds a frame's `:drain-lock`. Public
;; trace listeners are allowed to call `dispatch-sync`, which forms a hard AB-BA
;; cycle:
;;   - T1 acquires `fanout-monitor` (a clean emit), enters a listener, calls
;;     `dispatch-sync` into frame F, and spin-waits on F's `:drain-lock`;
;;   - T2 already holds F's `:drain-lock` through `run-one-pass!`, reaches an
;;     ordinary in-run emit (e.g. `:rf.event/run-start`), and would block
;;     acquiring `fanout-monitor`.
;; Neither can progress. `drain-block!`'s bounded-wait assumption is false because
;; the active drainer (T2) is itself waiting on the caller (T1).
;;
;; Routing a drain-owned emit's fan-out INLINE, off the monitor, would break that
;; cycle — whatever discriminates "drain-owned", event shape or real drain-lock
;; OWNERSHIP. But every inline path rests on one false assumption: that an inline
;; drain-owned fan-out is already mutually exclusive, because a frame has a single
;; drainer. It is not. The drain-lock serializes ONE FRAME's drain; the listener
;; registry is PROCESS-global. Two INDEPENDENT frames draining on two JVM threads
;; each own their own lock, so both would qualify for the inline path and both
;; would enter the same arbitrary listener callback at once — the serial law lost
;; again, this time drain-vs-drain, with arbitrary programmer / tool listener code
;; additionally running while the framework held a drain lock.
;;
;; The answer is in the TIMING, not the classifier. A drain-owned emit does not
;; fan out at all while the lock is held: it is APPENDED to a per-thread pending
;; vector and delivered at ONE explicit post-drain boundary
;; (`call-with-deferred-fanout`, established by `re-frame.router/drain-try!` /
;; `drain-block!` and `re-frame.frame/call-serialized-with-drain!` around their
;; whole acquire → run → release region), where the batch is flushed under
;; `fanout-monitor` before the enclosing dispatch / drain call returns — or, when
;; a listener callback started the drain, appended to that fan-out's queue.
;;
;; That one seam discharges both laws at once, and the dynamic scope IS the
;; ownership evidence — no frame resolution, no ownership probe, no shape guess:
;;
;;   - SERIAL. Every listener fan-out — clean or drain-owned — runs under
;;     `fanout-monitor`. No callback overlaps itself and records reach each
;;     listener in one process-wide order.
;;   - DEADLOCK-FREE. The monitor is acquired at exactly two places:
;;     an outermost emit OUTSIDE any deferral scope, and the post-drain flush. The
;;     scope is bound for the entire dynamic extent in which the thread can hold a
;;     drain-lock (entered before the acquire, exited after the release), so an
;;     unscoped emit PROVES its thread holds no drain-lock, and the flush runs
;;     with the lock already dropped and the scope rebound nil. No thread ever
;;     waits on the monitor while owning a drain-lock, so the AB-BA edge cannot
;;     exist — an invariant of the seam's construction, not a property of any
;;     particular interleaving.
;;
;; Deferral moves only the TIMING of delivery, never its CONTENT. Two things are
;; captured at append time so a deferred fan-out delivers exactly what an inline
;; one would have: the listener SNAPSHOT (so a listener registered / unregistered later in
;; the same drain does not gain or lose an earlier event), and a baseline-relative
;; reading of `continue?` (`deferred-continue`) so that only a suppression a
;; listener causes DURING the fan-out stops it — the ordinary post-drain falsity of
;; a context-dependent fence never does. See `deferred-continue` for why a bare
;; live re-read of that fence is unsound once the drain has unwound.
;;
;; Emission ORDER is preserved:
;; the ring push and epoch capture run inline, the pending vector
;; is FIFO, and the whole batch is flushed under one monitor hold, so a drain's
;; own traces reach listeners contiguously and in order. The WHOLE captured batch
;; is queued onto ONE fan-out queue before any callback runs —
;; `run-outermost-fanout!` for a top-level flush, `into` this thread's active
;; queue for a listener-initiated drain — so a listener that authors a later
;; trace while an earlier deferred entry is being fanned out appends BEHIND the
;; still-pending later entries rather than overtaking them, exactly as an inline
;; delivery of the same run would have ordered them.
;;
;; Same-thread reentrancy does NOT re-acquire the monitor: a nested emit finds
;; this thread's fan-out queue and appends to it, so it never reaches the lock.
;; `locking` is a reentrant monitor regardless, so a listener whose
;; `dispatch-sync` drains a frame and flushes that drain's deferred batch while
;; its own fan-out still holds the monitor simply re-enters it (and the flush
;; only appends).
;;
;; CLJS is single-threaded: no concurrent emits and no monitor. It defers all the
;; same: the drain opens the deferral scope through a late-bind hook (a volatile,
;; `deferred-drain-fanout-cljs`, stands in for the ThreadLocal), so a drain-owned
;; emit reaches listeners at the post-drain flush on CLJS exactly as on the JVM,
;; and an emit raised outside any drain fans out inline. Reaching the scope
;; through the hook keeps the static drain path free of this ns, preserving
;; production elision.

#?(:clj
   (def ^:private ^ThreadLocal fanout-queue
     "When set, the queue of the outermost listener fan-out in progress on THIS
     thread: a volatile FIFO vector of `[event continue? entries]` triples (see
     the section note above). A trace emitted from inside a listener body appends
     here and returns; the outermost deliverer's loop delivers it after every
     event ahead of it.

     A `ThreadLocal`, not a `^:dynamic` Var, for the reason given at
     `deferred-drain-fanout`: JVM `next-tick` and `set-timeout!` convey Var
     bindings, and an executor thread appending to a queue whose loop has
     finished would strand its events. The CLJS analogue is `fanout-queue-cljs`."
     (ThreadLocal.)))

#?(:cljs
   (def ^:private fanout-queue-cljs
     "The CLJS fan-out queue: a volatile holding the outermost fan-out's queue
     while one is in progress, else nil. A plain volatile suffices because a
     fan-out runs to completion synchronously and clears it on the way out, so no
     later async task ever sees it."
     (volatile! nil)))

(defn- current-fanout-queue
  "The queue of the outermost listener fan-out in progress on this thread of
  execution, or nil when none is."
  []
  #?(:clj  (.get ^ThreadLocal fanout-queue)
     :cljs @fanout-queue-cljs))

#?(:clj
   (def ^:private ^Object fanout-monitor
     "Process-global JVM monitor serializing EVERY outermost trace-listener
     fan-out across concurrent emits, so no registered
     listener callback is ever invoked on two threads at once and records reach
     each listener in one defined order. Held for the whole synchronous drive of a
     clean emit, and for the whole post-drain flush of a deferred batch. Reentrant
     same-thread emits never acquire it (they append to this thread's
     `fanout-queue`), and a thread that owns a frame's `:drain-lock` never
     acquires it (it appends to `deferred-drain-fanout` instead — see the seam
     note above). CLJS is single-threaded and has no counterpart."
     (Object.)))

#?(:clj
   (def ^:private ^ThreadLocal deferred-drain-fanout
     "When set, the post-drain deferral scope for THIS thread: a volatile FIFO
     vector of `[event continue? entries]` triples awaiting listener delivery.

     `call-with-deferred-fanout` sets it around each region in which the thread
     can hold a frame's `:drain-lock` — `re-frame.router/drain-try!` /
     `drain-block!` and `re-frame.frame/call-serialized-with-drain!`, each
     bracketing its whole acquire → run → release — and flushes the batch under
     `fanout-monitor` on the way out. So `set` is exactly \"this thread may
     currently own a drain-lock\", which is why the outermost emit branch below
     needs no frame resolution and no ownership probe: an emit that finds this nil
     PROVES its thread holds no drain-lock and may safely block on the monitor.

     A `ThreadLocal`, deliberately NOT a `^:dynamic` Var. Drain-lock ownership is
     a property of a THREAD, and a Var binding is a property of a logical call
     context that Clojure will happily CONVEY to other threads: JVM
     `re-frame.interop/next-tick` and `set-timeout!` both wrap their callback in
     `bound-fn`. An `ensure-drain-scheduled!` issued mid-drain would therefore
     hand the executor thread the draining thread's scope, and an armed
     `:dispatch-later` would hand it to the timer thread — each then appending its
     own, entirely unrelated, trace events to a batch whose owner has long since
     flushed and walked away. Those events would never be delivered to any
     listener. A `ThreadLocal` is not conveyed, so a scheduled task starts clean
     and opens its own scope. Cleared with `.remove` on the way out, so the flush
     — and any drain a listener body starts from it — sees no scope. The CLJS
     single-threaded analogue is `deferred-drain-fanout-cljs` below."
     (ThreadLocal.)))

#?(:cljs
   (def ^:private deferred-drain-fanout-cljs
     "The CLJS post-drain deferral scope: a volatile holding this
     drain region's `pending` FIFO vector while a drain is active, else nil. The
     single-threaded analogue of the JVM `deferred-drain-fanout` ThreadLocal.

     A plain top-level volatile suffices where the JVM needs a ThreadLocal
     precisely because JS is single-threaded: a drain runs to completion — its
     post-drain flush included — before any other drain or any scheduled
     `next-tick` / `set-timeout!` callback runs, and `call-with-deferred-fanout`
     clears the volatile synchronously on the way out. So a later async task
     always starts with a clean (nil) scope and opens its own, exactly the
     not-conveyed property the JVM ThreadLocal buys against `bound-fn` conveyance."
     (volatile! nil)))

(defn- current-deferred-fanout-queue
  "The deferred-fanout queue of the post-drain deferral scope active on this thread of
  execution, or nil when none is — i.e. nil PROVES the caller holds no frame
  `:drain-lock` (`call-with-deferred-fanout` brackets every acquire → run →
  release region). The JVM reads its `ThreadLocal`; CLJS reads the
  single-threaded volatile."
  []
  #?(:clj  (.get ^ThreadLocal deferred-drain-fanout)
     :cljs @deferred-drain-fanout-cljs))

(defn- set-current-deferred-fanout-queue! [deferred-queue]
  #?(:clj  (.set ^ThreadLocal deferred-drain-fanout deferred-queue)
     :cljs (vreset! deferred-drain-fanout-cljs deferred-queue)))

(defn- clear-current-deferred-fanout-queue! []
  #?(:clj  (.remove ^ThreadLocal deferred-drain-fanout)
     :cljs (vreset! deferred-drain-fanout-cljs nil)))

;; ---- unobserved no-silent-swallow warnings --------------------------------
;;
;; A `:rf.warning/*` travels the trace axis only, so with no trace listener it
;; reaches nobody — and a boot-time one (`configure!`, a registration) carries
;; no dispatch id, so no ring retains it either. For the warnings that report a
;; recognised input the runtime could not honour, that silence is exactly what
;; Conventions §No silent swallow forbids. So in a dev build, on every host, an
;; event in [[unobserved-warning-ops]] that a fan-out delivers to an EMPTY
;; listener snapshot prints one line: `*err*` on the JVM, `console.warn` on a
;; JS host. Any trace listener — Xray, Story, or one
;; `(rf/register-listener! :trace ::id f)` — receives the warning instead and
;; the line stands down. Every other warning stays trace-only.

(def ^:private unobserved-warning-ops
  "The `:rf.warning/*` ids that print when no trace listener receives them.
  Defined by the per-surface table in Conventions §No silent swallow: each is
  a recognised input the runtime could not honour, and a warning joins this
  set in the same change that gives it a row there."
  #{:rf.warning/unknown-dispatch-opt
    :rf.warning/unknown-configure-key
    :rf.warning/unknown-registration-key
    :rf.warning/failure-swallowed
    :rf.warning/resource-load-more-owner-ignored
    :rf.warning/trace-buffer-unrecognised-opts
    :rf.warning/schema-validator-unavailable
    :rf.warning/can-leave-subs-artefact-missing})

(defn- print-unobserved-warning!
  "Print `[re-frame2] <op> — <:reason, else :hint>` when `event`'s
  `:operation` is in [[unobserved-warning-ops]]. The caller has already
  established that the listener snapshot this event is delivered under is
  empty. Dev builds only; never throws. Returns nil."
  [event]
  (when rf.interop/debug-enabled?
    (try
      (let [op (:operation event)]
        (when (contains? unobserved-warning-ops op)
          (let [text (or (get-in event [:tags :reason])
                         (get-in event [:tags :hint]))
                line (if (some? text) (str op " — " text) (str op))]
            #?(:clj  (binding [*out* *err*]
                       (println "[re-frame2]" line)
                       (flush))
               :cljs (when (and (exists? js/console)
                                (fn? (.-warn js/console)))
                       (.warn js/console "[re-frame2]" line))))))
      (catch #?(:clj Throwable :cljs :default) _ nil))
    nil))

(defn- listener-snapshot
  "The registered listeners, as the `[id f]` entries a queued event is
  delivered to."
  []
  (vec (seq @listeners)))

(defn- drive-fanout!
  "Deliver every event on the fan-out queue `q` — a volatile FIFO vector of
  `[event continue? entries]` triples — to every listener in its `entries`
  snapshot, in queue order, until the queue is empty. Only the outermost
  deliverer runs this loop: an event appended while it runs (a listener body's
  emit, or the flush of a listener-initiated drain) is delivered here once every
  event ahead of it has reached every listener, so no listener callback is ever
  re-entered.

  Per-listener throws are isolated. Each event is delivered under its OWN
  `continue?`: a listener that destroys the outer incarnation flips that event's
  `continue?` false, which stops its remaining fan-out and moves on to the next
  queued event (evaluated under its own predicate)."
  [q]
  (loop [head 0]
    (when (< head (count @q))
      (let [[event continue? entries] (nth @q head)]
        ;; The unobserved-warning print reads THIS snapshot, so "printed" and
        ;; "no listener received it" always agree.
        (when (empty? entries)
          (print-unobserved-warning! event))
        (loop [i 0]
          (when (and (< i (count entries)) (continue?))
            (let [[_ f] (nth entries i)]
              (try
                (f event)
                (catch #?(:clj Throwable :cljs :default) _ nil))
              (recur (inc i)))))
        (recur (inc head))))))

(defn- run-outermost-fanout!
  "Seed ONE fresh fan-out queue with a whole `batch` of `[event continue?
  entries]` triples, install it as this thread's `fanout-queue` so emits from
  listener bodies append to it, and drive it to completion. Always runs under
  `fanout-monitor` on the JVM so concurrent emits serialize — either a single
  clean emit (`batch` is one entry) or the whole post-drain flush of a deferred
  batch; see the `deliver-to-tooling!` outermost branch and
  `drain-deferred-batch!`. Installing the queue and driving it are the whole
  critical section.

  Seeding the WHOLE batch before driving is what keeps a deferred batch FIFO:
  a listener that authors a later trace while an EARLIER deferred entry is being
  fanned out appends BEHIND the still-pending later entries rather than
  overtaking them. Each entry is delivered under the listener snapshot taken when
  it was queued."
  [batch]
  (let [q (volatile! (vec batch))]
    #?(:clj  (.set ^ThreadLocal fanout-queue q)
       :cljs (vreset! fanout-queue-cljs q))
    (try
      (drive-fanout! q)
      (finally
        #?(:clj  (.remove ^ThreadLocal fanout-queue)
           :cljs (vreset! fanout-queue-cljs nil))))))

(def ^:private unread
  "Distinct baseline sentinel for `deferred-continue` — a fresh object, held by
  identity. Deliberately NOT a keyword: CLJS keyword literals are not guaranteed
  `identical?` across evaluation sites, so a keyword sentinel would fail its own
  first-check `identical?` on CLJS and silently break the deferral-continuation
  distinction."
  #?(:clj (Object.) :cljs (js-obj)))

(defn- deferred-continue
  "Wrap an appended event's `continue?` snapshot so deferral cannot change
  WHETHER the event is delivered — only when.

  THE PROBLEM DEFERRAL CREATES. `drive-fanout!` consults `continue?` before
  every listener, and a false reading has ONE meaning: a listener body just
  suppressed this event, so stop fanning it out. That reading is only sound
  while every check is taken in the SAME dynamic context, which is what inline
  delivery guarantees — the whole fan-out runs inside `deliver!`, inside the
  drain.

  A deferred fan-out runs after the drain has unwound, and by then the
  predicate can read false with no listener involved: a destroy requested
  during the drain runs at the event boundary, before the post-drain flush, so
  a trace that a mid-drain effect emitted under its pinned incarnation reaches
  the flush with that incarnation already gone. Under
  a bare live re-read the driver would take that as suppression and abandon
  listeners 2..N of an event that was authorised when it was emitted.
  Level-testing the predicate at the flush is the defect; the reading has to be
  made relative to the moment the fan-out actually starts.

  THE DISTINCTION. Detect a TRANSITION, not a level. The first call — taken in
  the flush, immediately before listener 1 — records the BASELINE and always
  returns true (mirroring inline, whose first check passed in the same breath as
  `deliver!`'s own `(continuing?)` gate). Every check thereafter runs in that
  same flush context, so the only thing that can have changed the reading
  between two consecutive checks is the listener body that ran between them:

    - baseline TRUE  -> consult the live predicate. A later false is a genuine
      mid-fan-out suppression and stops the remainder, exactly as per-event
      authority requires, so a listener that destroys the outer incarnation
      still fences that incarnation's remaining fan-out.
    - baseline FALSE -> the predicate was ALREADY false when the fan-out began,
      before any listener could act. That is the boundary teardown above,
      never evidence of suppression, so it cannot abandon the remaining
      listeners. The event was authorised at emission and is delivered in
      full — just later.

  Deferral therefore changes delivery TIMING only, never the recipient set.
  A nested emit queued behind the event being delivered is read through this
  too: it is delivered after the listener body that emitted it has returned,
  which can likewise have lost its incarnation before its own fan-out starts.
  Cross-platform: CLJS defers drain-owned emits too, and runs the same boundary
  teardown before its flush, so this reasoning is not JVM-specific. The
  `unread` sentinel is a dedicated object, NOT a keyword: CLJS keyword literals
  are not guaranteed `identical?` across evaluation sites, so a keyword
  baseline sentinel would make the first-check branch unreachable there —
  every check would level-test the live predicate and DROP such an event."
  [continue?]
  (let [baseline (volatile! unread)]
    (fn []
      (let [b @baseline]
        (cond
          ;; First check, taken in the flush: record the baseline, always pass.
          (identical? b unread)   (do (vreset! baseline (boolean (continue?)))
                                      true)
          ;; Already false before any listener ran — ordinary post-drain
          ;; falsity, not suppression. Never abandon the remaining listeners.
          (false? b)              true
          ;; Was live when the fan-out began: a false reading now is a
          ;; listener-caused suppression. Honour it.
          :else                   (boolean (continue?)))))))

(defn- drain-deferred-batch!
  "The body of the post-drain flush (see `flush-deferred-fanout!`), factored out
  so the JVM can run it under `fanout-monitor` and CLJS can run it bare. Re-reads
  `deferred-queue` each turn so an append that somehow raced the flush still settles here
  — exactly once — rather than being stranded."
  [deferred-queue]
  (loop []
    (let [batch @deferred-queue]
      (when (seq batch)
        (vreset! deferred-queue [])
        (if-let [q (current-fanout-queue)]
          ;; A listener-initiated `dispatch-sync`: the flush runs inside the
          ;; listener body that opened this drain, while the outer fan-out is
          ;; still delivering. Append the WHOLE captured batch to that queue and
          ;; return: the outermost loop delivers it once the outer event and
          ;; everything queued ahead of it have reached every listener, and a
          ;; listener that authors a later trace while an EARLIER batch item is
          ;; being delivered appends BEHIND the still-pending later items
          ;; (FIFO). Each entry carries its append-time listener snapshot.
          (vswap! q into batch)
          ;; Outermost drain (a top-level dispatch-sync / async drain): seed ONE
          ;; fresh outermost schedule for the WHOLE batch, so the
          ;; entire batch is queued before any callback runs and a listener's
          ;; reentrant emit during an earlier deferred event lands behind the
          ;; later ones rather than overtaking them. Each event is still delivered
          ;; under its own append-time snapshot.
          (run-outermost-fanout! batch))
        (recur)))))

(defn- flush-deferred-fanout!
  "Deliver a drain's appended trace events to their listeners at the post-drain
  boundary. The caller has already released the frame's `:drain-lock`, so this
  is the first point at which arbitrary listener code may run and — on the JVM —
  the first at which blocking on `fanout-monitor` is safe.

  On the JVM the whole batch is flushed under ONE monitor hold, so a drain's
  traces reach every listener contiguously and in emission order rather than
  interleaved with a concurrent drain's. CLJS is single-threaded — no concurrent
  drains, no monitor — so it flushes bare. When this flush runs while
  an outer listener fan-out is still in progress on this thread (a listener-
  initiated `dispatch-sync`), the JVM monitor hold is a reentrant re-
  acquire of the one the outer drive already owns."
  [deferred-queue]
  (when (seq @deferred-queue)
    #?(:clj  (locking fanout-monitor (drain-deferred-batch! deferred-queue))
       :cljs (drain-deferred-batch! deferred-queue))))

(defn ^:no-doc call-with-deferred-fanout
  "Run `f` as one post-drain listener-delivery region: trace events emitted inside
  it while the framework owns a frame's `:drain-lock` are appended rather than
  fanned out, and the batch is delivered here, on the way out (under
  `fanout-monitor` on the JVM) — or, when a listener callback started the drain,
  appended to that fan-out's queue.

  Wrap the WHOLE acquire → run → release region of every path that takes a
  `:drain-lock` (`re-frame.router/drain-try!` / `drain-block!` and
  `re-frame.frame/call-serialized-with-drain!`). Two properties of that placement
  are load-bearing (see the seam note above): the scope must OPEN before the
  acquire, so no drain-owned emit can escape deferral and block on the monitor
  while holding a lock; and it must CLOSE after the release, so the flush — which
  runs arbitrary listener code and does block on the monitor — owns no lock. The
  flush runs in a `finally`, so a drain that throws still settles its traces
  exactly once.

  `flush-scope` receives the flush thunk and must run it under the ORDINARY
  delivery scope `re-frame.trace/deliver!` establishes around an inline fan-out —
  epoch capture, frame policy and ring retention restored to their defaults, and
  the exact-owner continuation neutralised, so a
  listener's nested authored work is not strangled by the scope the DRAIN was
  running under. Inline delivery gets that binding for free because the fan-out
  happens inside `deliver!`; a deferred fan-out happens after `deliver!` has
  returned, so the scope has to be re-established here. It is supplied as an
  argument because those dynamic vars are private to `re-frame.trace`, which this
  sibling deliberately cannot see — the same reason `deliver!` passes `retain?`
  and `continue?` explicitly rather than letting this ns read them.

  Nesting is inherited, not re-established: a reentrant drain (`drain-reentrant!`
  under a cold serialized section, a `destroy-frame!` mid-drain) already runs
  inside its owner's scope and appends to the same batch, which the OWNER flushes
  once it has dropped the lock.

  Cross-platform. CLJS defers exactly as the JVM does — a single-
  threaded post-drain queue with no monitor — so a CLJS listener observes only
  settled state, matching the JVM and the Spec 009 contract. The mechanism differs
  only where the platform forces it: a plain volatile stands in for the JVM
  ThreadLocal, and the flush needs no monitor. Isolation is preserved by the
  CALLER: `re-frame.trace/call-with-deferred-listener-delivery` reaches this fn
  through late-bind on CLJS (never a static reference from the production-reachable
  drain path), so a production bundle that never loads this tooling sibling DCEs
  the whole deferral machinery — and there, with no listener registry, there is
  nothing to defer regardless."
  [f flush-scope]
  (if (or (not rf.interop/debug-enabled?)
          (current-deferred-fanout-queue))
    (f)
    (let [deferred-queue (volatile! [])]
      (try
        (set-current-deferred-fanout-queue! deferred-queue)
        (f)
        (finally
          ;; Clear BEFORE flushing: the flush runs listener bodies, and a
          ;; listener that dispatches must open its own scope rather than
          ;; append to the batch currently draining.
          (clear-current-deferred-fanout-queue!)
          (flush-scope #(flush-deferred-fanout! deferred-queue)))))))

(defn- deliver-to-tooling!
  "Push `event` onto its in-flight frame's run-keyed ring (when the run has a
  `:dispatch-id` and a `:frame`; frameless emits skip the ring per B3),
  hand it to SSR's dev capture hook `:ssr/capture-error-trace` when that is
  published, then fan out to every registered listener. Hook and listener
  throws are isolated. No-op in production.

  `retain?` gates ONLY the ring push. Under retentionless
  structural delivery (`re-frame.trace/call-with-structural-delivery`) it is
  false: a destroyed incarnation's terminal fact still streams live to every
  listener, but no per-frame ring retains it. The fact carries the bare frame id
  after teardown released that id's ring, so a push would recreate a ring for a
  frame that is gone, or, for the old router's late `:rf.frame/drain-interrupted`,
  land in a same-id successor's ring (Spec 009 §Retention boundary). The
  default `true` arity is the ordinary emit
  path. Retention is the ONLY thing gated; listener fan-out is unconditional so
  the required terminal fact reaches live consumers exactly once either way.

  `continue?` is the caller's exact-owner continuation SNAPSHOT,
  consulted per event by the driver so that if a listener destroys the outer
  incarnation A, A's remaining fan-out is suppressed. It is a standalone snapshot
  rather than a live read of the (neutralised) `*continuation-predicate*` so a
  listener BODY's nested authored work runs under ordinary always-continue
  authority, not A's fence.

  A nested emit from inside a listener body is QUEUED, not fanned out (see the
  section note above): it appends to this thread's fan-out queue and returns, and
  the outermost deliverer's loop delivers it after the outer event has reached
  every listener — so every listener observes the outer event first and no
  callback is re-entered. The ring push happens inline (emission order)."
  ([event continue?] (deliver-to-tooling! event continue? true))
  ([event continue? retain?]
   (reset-render-budget! event)
   (when retain? (push-to-ring! event))
   ;; SSR's dev error capture rides this late-bind hook, not the listener
   ;; registry: it sees every event a listener would, synchronously and
   ;; before any deferral, owns neither dev print, and survives
   ;; `clear-listeners!`. Its body is a pure `swap!` on SSR's buffer, so it
   ;; is safe inside a drain-owned delivery.
   (when-some [capture (rf.late-bind/get-fn-cached :ssr/capture-error-trace)]
     (try
       (capture event)
       (catch #?(:clj Throwable :cljs :default) _ nil)))
   (if-let [deferred-queue (current-deferred-fanout-queue)]
     ;; DEFER. A drain-owned emit — one raised
     ;; while the framework owns a frame's `:drain-lock` — is appended, never
     ;; fanned out, until the post-drain boundary.
     ;;
     ;; This check comes before the listener-body branch below: a drain-owned
     ;; emit always defers, whether or not a fan-out is in progress. When one
     ;; is (a listener's `dispatch-sync` opened this drain), the post-drain
     ;; flush appends the batch to that fan-out's queue.
     ;;
     ;; The deferral scope also PROVES lock ownership (`call-with-deferred-fanout`
     ;; brackets every acquire → run → release region), so a drain-owned emit
     ;; never blocks acquiring `fanout-monitor` — the AB-BA edge cannot
     ;; form. Capture the listener snapshot and latch `continue?` so the deferred
     ;; delivery reaches exactly what an inline one would have. CLJS opens the same
     ;; scope, so a single-threaded host defers too and its
     ;; listeners never observe partial state.
     (do (vswap! deferred-queue conj [event (deferred-continue continue?)
                                      (listener-snapshot)])
         nil)
     (if-let [q (current-fanout-queue)]
       ;; An emit from inside a listener body with NO drain scope open: queue it
       ;; behind the event being delivered and return. The outermost loop
       ;; delivers it once the callback that emitted it has returned, under the
       ;; same snapshot-and-latch rule as a deferred event. Ring retention
       ;; already ran above, in emission order. The queue is this thread's
       ;; alone, so this branch keeps a nested emit off `fanout-monitor`.
       (do (vswap! q conj [event (deferred-continue continue?)
                           (listener-snapshot)])
           nil)
       ;; Outermost emit, no drain scope: by the same deferral bracketing this
       ;; thread provably holds no drain-lock — so it serializes on
       ;; `fanout-monitor` across concurrent emits with the monitor
       ;; held for the whole drive, and nothing can wait on it through a
       ;; drain-lock. CLJS is single-threaded and runs every outermost emit inline
       ;; with no monitor.
       #?(:clj  (locking fanout-monitor
                  (run-outermost-fanout! [[event continue? (listener-snapshot)]]))
          :cljs (run-outermost-fanout! [[event continue? (listener-snapshot)]]))))))

(rf.late-bind/set-fn! :trace.tooling/deliver! deliver-to-tooling!)

;; The drain seam reaches the post-drain deferral wrapper through this
;; hook on CLJS. `re-frame.trace/call-with-deferred-listener-delivery` is called
;; from the always-reachable production drain path, so a STATIC reference to this
;; sibling from there would defeat the `:advanced` DCE that keeps the whole tooling
;; body out of the counter bundle (`check-bundle-isolation.cjs`). Late-bind carries
;; no static edge — identical isolation posture to `:trace.tooling/deliver!` above.
;; The JVM drain calls `call-with-deferred-fanout` directly (no DCE concern there).
(rf.late-bind/set-fn! :trace.tooling/call-with-deferred-fanout call-with-deferred-fanout)

;; `re-frame.core/configure!`'s `:trace-buffer` key routes through this hook so
;; consumer call sites don't have to thread the tooling-ns require
;; into the host's boot path. Keeping just THIS one knob hook (vs the
;; full set the listener / ring surface uses) is deliberate: the
;; per-fn hooks would each pay a keyword-intern cost in every
;; consumer of `re-frame.core`, even when the wrappers' bodies were
;; dead code (the keyword constructor runs at module init).
;; `configure` is a low-traffic op so the extra indirection costs
;; nothing on the hot path.

(rf.late-bind/set-fn! :trace.tooling/configure-trace-buffer! configure-trace-buffer!)
(rf.late-bind/set-fn! :trace.tooling/current-trace-buffer-config current-trace-buffer-config)

;; Published hooks for frame-destroy ring cleanup (consulted by
;; `destroy-frame!` per Spec 002 §Destroy) and frame retention policy.

(rf.late-bind/set-fn! :trace.tooling/release-frame-ring! release-frame-ring!)
(rf.late-bind/set-fn! :trace.tooling/apply-frame-events-retained-policy!
                   apply-frame-events-retained-policy!)

;; ---- bundle isolation ----------------------------------------------------
;;
;; `implementation/scripts/check-bundle-isolation.cjs`'s `trace-tooling`
;; entry greps the emitted module for `trace-events`, a projection field of
;; this ns's `trace-buffer` body.
