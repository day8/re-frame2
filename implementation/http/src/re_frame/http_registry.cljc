(ns re-frame.http-registry
  "In-flight request registries for `:rf.http/managed`.

  Extracted from `re-frame.http-managed` per rf2-3i9b. Two indexes
  coexist:

   - `in-flight`        — request-id → request-handle. Per Spec 014
                         §Aborts: a `:rf.http/managed-abort` resolves
                         the abort-fn through this index, and a fresh
                         request with the same `:request-id` supersedes
                         the previous one.
   - `actor-in-flight`  — actor-id → [request-handle ...]. Per Spec 014
                         §Abort on actor destroy (rf2-wvkn): requests
                         whose originating event-id is a spawned state-
                         machine actor's address are ALSO indexed by
                         that actor-id so a `:rf.machine/destroy`
                         cascade can abort every in-flight request the
                         actor had issued.

  Handles carry `:abort-fn` (the no-arg fn the runtime calls to
  cancel), `:url`, plus the framework-stamped `:request-id` and
  `:actor-id` when applicable so subsequent `clear-in-flight!` calls
  can locate them in either index by identity.

  `abort-on-actor-destroy` lives here (rather than next to the machine-
  shape wrapper) because the operation is atomic state — it walks
  both atoms and mutates them under one `swap!` per slot. Keeping it
  next to the atoms makes the invariant local."
  (:require [re-frame.frame        :as frame]
            [re-frame.http-privacy :as privacy]
            [re-frame.interop      :as interop]
            [re-frame.trace        :as trace]))

;; ---- in-flight request registry -------------------------------------------

(defonce in-flight
  ;; request-id → request-handle map. The handle is implementation-specific
  ;; (CLJS: AbortController; JVM: CompletableFuture). The :abort-fn value
  ;; is the no-arg fn the runtime calls to cancel.
  (atom {}))

(defonce actor-in-flight
  ;; actor-id → vector of {:abort-fn :request-id :url}.
  ;;
  ;; Index by actor-id — populated when a managed request's originating
  ;; event-id is a spawned actor's address (per Spec 014 §Abort on actor
  ;; destroy, rf2-wvkn). Each entry carries the same :abort-fn the
  ;; request-id index would carry; the actor-destroy hook walks the
  ;; vector, fires each :abort-fn, and clears the index slot. Multiple
  ;; in-flight requests from the same actor accumulate as separate
  ;; entries; sibling actors keep independent slots.
  (atom {}))

(defn record-in-flight!
  "Record a request handle. `handle` is the abort-handle map (carries
  `:abort-fn`, `:url`, plus the framework stamps `:request-id` and
  `:actor-id` when applicable so subsequent `clear-in-flight!` calls
  can locate it in either index by identity).

  Returns the (possibly-stamped) handle so the natural-completion
  sites can hold a reference for the 2-arg `clear-in-flight!` cleanup
  path. `request-id` and `actor-id` are both optional (pass nil). When
  both are nil the handle is unindexed and only reachable via natural
  completion.

  rf2-ee38b.7 — the convenience 2-arity `[request-id handle]` was
  dropped; it had no production caller (the single call site in
  `http-transport/run-attempt!` always passes the 3-arity with an
  actor-id, possibly nil) and no test depended on it."
  [request-id actor-id handle]
  (let [stamped (cond-> handle
                  request-id (assoc :request-id request-id)
                  actor-id   (assoc :actor-id actor-id))]
    (when request-id
      (swap! in-flight assoc request-id stamped))
    (when actor-id
      (swap! actor-in-flight update actor-id (fnil conj []) stamped))
    stamped))

(defn- remove-from-actor-index! [actor-id handle]
  (when actor-id
    (swap! actor-in-flight
           (fn [m]
             (let [v  (get m actor-id [])
                   v' (vec (remove #(identical? % handle) v))]
               (if (seq v')
                 (assoc m actor-id v')
                 (dissoc m actor-id))))))
  nil)

(defn clear-in-flight!
  "Clear a request handle from both indexes. Two arities:

   - 1-arg `[request-id]` — the resolve-by-id form. Resolves the handle
     from the request-id index and walks both indexes (the handle stores
     `:actor-id` so the actor-index slot can be located by identity).
     No-op when `request-id` is nil — anonymous requests use the 2-arg
     form below.
   - 2-arg `[request-id handle]` — the natural-completion form used by
     the per-host attempt loops. Both args are taken from the captured
     ctx + handle pair, so the cleanup is index-walks by identity and
     does not depend on `request-id` being non-nil. This arity covers
     anonymous-request natural completion from inside spawned actors.

  Per rf2-plngk this is THE single source of truth for in-flight
  cleanup on per-request termination. The natural-completion sites
  (`finalise-success!`, `finalise-failure!`, retry-clear in
  `maybe-retry!`) call this directly; the abort sites
  (`managed-abort-handler`, `abort-on-actor-destroy`) rely on the
  abort-fn → `finalise-failure!` cascade to reach here. Idempotent
  against already-gone state — the swap!s no-op on absent slots."
  ([request-id]
   (when request-id
     (let [handle (get @in-flight request-id)]
       (swap! in-flight dissoc request-id)
       (when handle
         (remove-from-actor-index! (:actor-id handle) handle))))
   nil)
  ([request-id handle]
   (when request-id
     (swap! in-flight dissoc request-id))
   (when handle
     (remove-from-actor-index! (:actor-id handle) handle))
   nil))

(defn lookup-in-flight
  "Return the request-handle currently registered under `request-id`, or
  nil (when absent, or when `request-id` is nil). The handle carries the
  `:abort-fn` the abort / supersede paths fire. Read-only — does not
  mutate either index."
  [request-id]
  (when request-id
    (get @in-flight request-id)))

(defn supersede!
  "If a request is already in flight under `request-id`, abort it with
  `:reason :request-id-superseded`. Per Spec 014 §`:request-id` (internal)."
  [request-id]
  (when-let [prev (lookup-in-flight request-id)]
    (clear-in-flight! request-id)
    (try
      ((:abort-fn prev) :request-id-superseded)
      (catch #?(:clj Throwable :cljs :default) _ nil))))

(defn clear-all-in-flight!
  "Test-time helper: cancel every in-flight managed request, then drop the
  registry. Test fixtures use this between runs.

  rf2-adcmk8 — symmetric with `:machines/reset-timers!` (0-arity): a
  request sleeping in a `schedule-backoff-handle!` retry window holds an
  armed `js/setTimeout` / `ScheduledFuture` backoff timer that would
  otherwise outlive the synchronous test, fire `run-attempt!`, and
  dispatch into a runtime the next test owns. Firing each handle's
  `:abort-fn` runs the once-only cancel cascade (`interop/clear-timeout!`
  / `AbortController.abort` → `clear-in-flight!`), releasing the host-
  clock handle so no timer is left armed. Each abort-fn also clears its
  own slot from both indexes; the trailing `reset!`s mop up any handle
  that was unindexed or that an abort-fn left behind, and guarantee a
  clean registry regardless.

  Walk BOTH indexes: an anonymous-from-actor request (request-id nil) is
  indexed only in `actor-in-flight`, so iterating `in-flight` alone would
  miss its armed timer. Dedupe by identity (the same stamped handle sits
  in both indexes when it carries request-id + actor-id) so each abort-fn
  fires once — the once-only CAS inside the abort-fn already makes a
  double-fire a harmless no-op, but the dedupe keeps the work minimal.

  Snapshot the handles before firing so an abort-fn's own
  `swap!`/`clear-in-flight!` cannot trip a concurrent-modification on the
  iteration. Each abort-fn is fired defensively — a throwing one must not
  strand the remaining handles or leave the registry undropped. The abort
  reason `:test-reset` marks these as fixture-teardown cancellations
  rather than real runtime aborts."
  []
  (let [handles (->> (concat (vals @in-flight)
                             (mapcat val @actor-in-flight))
                     (reduce (fn [acc h]
                               (if (some #(identical? % h) acc)
                                 acc
                                 (conj acc h)))
                             []))]
    (doseq [handle handles]
      (when-let [abort-fn (:abort-fn handle)]
        (try
          (abort-fn :test-reset)
          (catch #?(:clj Throwable :cljs :default) _ nil)))))
  (reset! in-flight {})
  (reset! actor-in-flight {})
  nil)

(defn in-flight-snapshot
  "Test-time helper: read the current value of the request-id-keyed
  in-flight map. Inspecting state in tests; not part of the user-facing
  API."
  []
  @in-flight)

(defn actor-in-flight-snapshot
  "Test-time helper: read the current value of the actor-id-keyed
  in-flight map (per rf2-wvkn). Inspecting state in tests; not part
  of the user-facing API."
  []
  @actor-in-flight)

(defn seed-in-flight-for-test!
  "Test-time helper (rf2-hp772l): register a fabricated in-flight handle
  through the SAME `record-in-flight!` path production uses, so both the
  request-id and actor-id indexes stay consistent (the actor-index slot,
  the `:request-id` / `:actor-id` stamps) rather than a raw `swap!` of the
  `in-flight` atom that bypasses those invariants.

  For fixtures that need an in-flight slot present WITHOUT issuing a real
  request (e.g. asserting that a navigation-cancel does not abort an
  active-route request). `handle` is the abort-handle map — it MUST carry
  an `:abort-fn` (the no-arg/one-arg fn the abort path fires) and typically
  `:url`. `request-id` / `actor-id` are optional (pass nil for an
  unindexed / anonymous handle). Returns the stamped handle.

  Not part of the user-facing API — production code routes through
  `:rf.http/managed`."
  ([handle] (seed-in-flight-for-test! (:request-id handle) (:actor-id handle) handle))
  ([request-id actor-id handle]
   (record-in-flight! request-id actor-id handle)))

;; ---- abort-on-actor-destroy (rf2-wvkn) ------------------------------------
;;
;; Per Spec 014 §Abort on actor destroy: when a spawned state-machine
;; actor is destroyed (parent state exit, parent's :after firing,
;; :spawn-all cancel-on-decision, frame destroy, imperative destroy),
;; the runtime invokes this fn with the destroyed actor's address. We
;; walk the actor-in-flight index, abort each in-flight request (which
;; cascades into the natural-failure-dispatch path with :reason
;; :actor-destroyed), and clear the slot.
;;
;; Discovered through the late-bind hook table at :http/abort-on-actor-destroy
;; — re-frame.machines does NOT statically :require this namespace; the
;; destroy path looks up this fn at call time. When the http artefact is
;; not on the classpath the hook resolves to nil and the destroy proceeds
;; without aborting any HTTP (apps that don't issue managed-HTTP pay
;; nothing).

(defn abort-on-actor-destroy
  "Per Spec 014 §Abort on actor destroy (rf2-wvkn). Abort every in-flight
  `:rf.http/managed` request that was issued from inside spawned actor
  `actor-id`. Each abort emits a `:rf.http/aborted-on-actor-destroy`
  trace event and dispatches a standard `:rf.http/aborted` reply with
  `:reason :actor-destroyed`.

  Idempotent: invoking against an actor with no in-flight HTTP is a
  no-op. Tolerant of repeated invocations against the same actor —
  the actor-side slot is cleared atomically first so a re-entry sees
  an empty registry.

  Per rf2-plngk the per-handle request-id cleanup is owned by
  `clear-in-flight!` (called inside the abort-fn closure via
  `finalise-failure!`). The earlier shape pre-walked the request-id
  index here AND cleared inside `finalise-failure!`, doubling the
  `swap!` traffic per actor destroy. The actor-side eager dissoc
  remains: it pins the idempotency guarantee against re-entry, and
  it's a single `swap!` regardless of handle count."
  [actor-id]
  (when actor-id
    (let [handles (get @actor-in-flight actor-id)]
      ;; Atomically clear the slot first so a re-entry sees no handles.
      (swap! actor-in-flight dissoc actor-id)
      (doseq [handle handles]
        (when interop/debug-enabled?
          ;; rf2-bma05 — the handle carries the originating request's
          ;; effective :sensitive? flag; stamp the trace event so off-box
          ;; consumers honour the privacy contract on actor-destroy aborts.
          (trace/emit! :info :rf.http/aborted-on-actor-destroy
                       (privacy/prepare-emit-tags
                         {:request-id (:request-id handle)
                          :actor-id   actor-id
                          :url        (:url handle)}
                         (true? (:sensitive? handle)))))
        (try
          ((:abort-fn handle) :actor-destroyed)
          (catch #?(:clj Throwable :cljs :default) _ nil)))))
  nil)

;; ---- spawned-actor detection ----------------------------------------------
;;
;; Per Spec 014 §Abort on actor destroy: a managed request "belongs to"
;; spawned actor `<spawned-id>` iff its originating event vector's first
;; element is `<spawned-id>` AND that id appears as a value somewhere
;; under [:rf.runtime/machines :spawned ...] in the frame's app-db (the runtime-owned
;; spawn registry per Spec 005 §Declarative :spawn (sugar over spawn)).
;; Detection is structural: walk the registry, look for the originating
;; id as either a leaf value (declarative :spawn) or as a value under
;; :children (declarative :spawn-all).

(def ^:private spawned-registry-path
  "The runtime-owned spawn-registry slot in a frame's **runtime-db** partition,
  per Spec 005 §Declarative :spawn (mirrors `re-frame.machines.paths/spawned-path`
  — the authoritative owner). Pinned here as a single named constant so the
  structural coupling to the machines runtime's runtime-db layout has ONE site
  rather than being inlined at each reader (rf2-b7h0q).

  EP-0001 (rf2-vzld77): machine spawn-registry state is durable runtime-db
  state, so the slot is `[:rf.runtime/machines :spawned]` in the runtime-db
  partition (was the app-db `:rf/runtime` root).

  NOTE on the late-bind boundary: the http artefact does not (and must not)
  statically `:require` `re-frame.machines`, so it cannot reference
  `machines.paths/spawned-path` directly — it re-states the path here. The
  cleaner long-term shape is to invert the existing
  `:http/abort-on-actor-destroy` hook direction and have machines PUBLISH a
  `:machines/spawned-actor-id?` membership test (so the structural walk
  lives next to the registry shape it depends on); that inversion is a
  cross-artefact change tracked separately and out of scope for this
  http-only fix."
  [:rf.runtime/machines :spawned])

(defn- read-spawned-registry
  "Read ONLY the runtime-owned spawn-registry slot from `frame-id`'s
  **runtime-db** — `(get-in runtime-db spawned-registry-path)` — without
  retaining the whole map.

  PERF (rf2-b7h0q): `frame/frame-runtime-db-value` is a substrate `deref` that
  returns the persistent runtime-db map BY REFERENCE (no structural copy, no
  scan), so the read is genuinely O(1) regardless of size; the `get-in` that
  follows is a fixed two-key descent. This is the load-bearing assumption that
  makes calling this once per managed request (`compute-actor-id`) cheap. Apps
  with no state machines carry no `:rf.runtime/machines` slot, so this returns
  nil and the caller's `(seq …)` test short-circuits before any structural walk.

  EP-0001 (rf2-vzld77): machine snapshots / spawn-registry moved to the
  runtime-db partition, so this reads the runtime-db value.

  Returns the spawned registry map, or nil when the frame is unregistered /
  carries no machines runtime."
  [frame-id]
  (let [rt (frame/frame-runtime-db-value frame-id)]
    (when (map? rt)
      (get-in rt spawned-registry-path))))

(defn- spawned-actor-id?
  "Return true if `event-id` is currently bound somewhere under
  `[:rf.runtime/machines :spawned <parent> <invoke-id>]` in `spawned`
  — either as the leaf value (declarative `:spawn`) or as a value in
  the `:children` map of the join-state record (declarative
  `:spawn-all`)."
  [spawned event-id]
  (some (fn [[_parent inner]]
          (some (fn [[_invoke-id v]]
                  (or (= v event-id)                      ;; :spawn leaf
                      (and (map? v)                       ;; :spawn-all join state
                           (some (fn [[_cid cid-val]]
                                   (= cid-val event-id))
                                 (:children v)))))
                inner))
        spawned))

(defn compute-actor-id
  "Resolve the spawned-actor-id for the request at hand, given the frame
  id and the originating event vector. Returns the actor-id (a keyword,
  the spawned actor's machine address) when the originating event-id is
  currently registered in the frame's `[:rf.runtime/machines :spawned ...]` slot, otherwise
  nil — meaning the request is NOT subject to actor-destroy cancellation
  (it was dispatched from an ordinary event handler, not from inside a
  spawned actor).

  Per rf2-hzn1a — fast path for the common case (no spawned actors).
  Apps that don't use state machines never carry a populated `:spawned`
  registry; we read only that slot via `read-spawned-registry` (an O(1)
  by-reference db deref + a fixed three-key descent — see that fn's PERF
  note, rf2-b7h0q) and the `(seq …)` test short-circuits before the
  O(parents × invokes) structural walk. This keeps the cost on the
  no-machines hot path at one by-reference deref + one path lookup + one
  seq-check, rather than a full structural walk against the (always
  empty) registry."
  [frame-id origin-event]
  (let [event-id (when (vector? origin-event) (first origin-event))]
    (when (and event-id
               (not= event-id :rf.http/managed))
      (let [spawned (read-spawned-registry frame-id)]
        (when (seq spawned)
          (when (spawned-actor-id? spawned event-id)
            event-id))))))
