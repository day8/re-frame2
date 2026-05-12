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
  (:require [re-frame.frame   :as frame]
            [re-frame.interop :as interop]
            [re-frame.trace   :as trace]))

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
  path. `request-id` and `actor-id` are both optional. When both are
  nil the handle is unindexed and only reachable via natural
  completion."
  ([request-id handle]
   (record-in-flight! request-id nil handle))
  ([request-id actor-id handle]
   (let [stamped (cond-> handle
                   request-id (assoc :request-id request-id)
                   actor-id   (assoc :actor-id actor-id))]
     (when request-id
       (swap! in-flight assoc request-id stamped))
     (when actor-id
       (swap! actor-in-flight update actor-id (fnil conj []) stamped))
     stamped)))

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

   - 1-arg `[request-id]` — the legacy form. Resolves the handle from
     the request-id index and walks both indexes (the handle stores
     `:actor-id` so the actor-index slot can be located by identity).
     No-op when `request-id` is nil — anonymous requests use the 2-arg
     form below.
   - 2-arg `[request-id handle]` — the natural-completion form used by
     the per-host attempt loops. Both args are taken from the captured
     ctx + handle pair, so the cleanup is index-walks by identity and
     does not depend on `request-id` being non-nil. This arity covers
     anonymous-request natural completion from inside spawned actors."
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

(defn lookup-in-flight [request-id]
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
  "Test-time helper: drop the in-flight registry. Test fixtures use this
  between runs."
  []
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

;; ---- abort-on-actor-destroy (rf2-wvkn) ------------------------------------
;;
;; Per Spec 014 §Abort on actor destroy: when a spawned state-machine
;; actor is destroyed (parent state exit, parent's :after firing,
;; :invoke-all cancel-on-decision, frame destroy, imperative destroy),
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
  the second call sees the already-cleared slot and does nothing."
  [actor-id]
  (when actor-id
    (let [handles (get @actor-in-flight actor-id)]
      ;; Atomically clear the slot first, so any in-flight failure
      ;; dispatch path that observes the removal won't re-walk the
      ;; same handles (the abort-fn closure also calls clear-in-flight!
      ;; on its way through finalise-failure!, so we want a single
      ;; source of truth for "is this still tracked").
      (swap! actor-in-flight dissoc actor-id)
      (doseq [handle handles]
        (when interop/debug-enabled?
          (trace/emit! :info :rf.http/aborted-on-actor-destroy
                       {:request-id (:request-id handle)
                        :actor-id   actor-id
                        :url        (:url handle)}))
        ;; Remove from the request-id index too so the natural failure
        ;; path doesn't try to clear a slot we already swept.
        (when-let [rid (:request-id handle)]
          (swap! in-flight dissoc rid))
        (try
          ((:abort-fn handle) :actor-destroyed)
          (catch #?(:clj Throwable :cljs :default) _ nil)))))
  nil)

;; ---- spawned-actor detection ----------------------------------------------
;;
;; Per Spec 014 §Abort on actor destroy: a managed request "belongs to"
;; spawned actor `<spawned-id>` iff its originating event vector's first
;; element is `<spawned-id>` AND that id appears as a value somewhere
;; under [:rf/spawned ...] in the frame's app-db (the runtime-owned
;; spawn registry per Spec 005 §Declarative :invoke (sugar over spawn)).
;; Detection is structural: walk the registry, look for the originating
;; id as either a leaf value (declarative :invoke) or as a value under
;; :children (declarative :invoke-all).

(defn- spawned-actor-id?
  "Return true if `event-id` is currently bound somewhere under
  `[:rf/spawned <parent> <invoke-id>]` in `db` — either as the leaf
  value (declarative `:invoke`) or as a value in the `:children` map
  of the join-state record (declarative `:invoke-all`)."
  [db event-id]
  (when (and event-id (map? db))
    (some (fn [[_parent inner]]
            (some (fn [[_invoke-id v]]
                    (or (= v event-id)                      ;; :invoke leaf
                        (and (map? v)                       ;; :invoke-all join state
                             (some (fn [[_cid cid-val]]
                                     (= cid-val event-id))
                                   (:children v)))))
                  inner))
          (get db :rf/spawned))))

(defn compute-actor-id
  "Resolve the spawned-actor-id for the request at hand, given the frame
  id and the originating event vector. Returns the actor-id (a keyword,
  the spawned actor's machine address) when the originating event-id is
  currently registered in the frame's `[:rf/spawned ...]` slot, otherwise
  nil — meaning the request is NOT subject to actor-destroy cancellation
  (it was dispatched from an ordinary event handler, not from inside a
  spawned actor)."
  [frame-id origin-event]
  (let [event-id (when (vector? origin-event) (first origin-event))]
    (when (and event-id
               (not= event-id :rf.http/managed))
      (let [db (frame/frame-app-db-value frame-id)]
        (when (spawned-actor-id? db event-id)
          event-id)))))
