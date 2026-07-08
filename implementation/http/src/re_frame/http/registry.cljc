(ns re-frame.http.registry
  "In-flight request registries for `:rf.http/managed`.

  Extracted from `re-frame.http.managed` per rf2-3i9b. Two indexes
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
  (:require [re-frame.http.privacy :as privacy]
            [re-frame.http.reply   :as http-reply]
            [re-frame.interop      :as interop]
            [re-frame.late-bind    :as late-bind]
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

  rf2-ee38b.7 — `record-in-flight!`'s own convenience 2-arity
  `[request-id handle]` was dropped (this is distinct from
  `clear-in-flight!`'s live 2-arity below, which is retained); it had no
  production caller (the single call site in
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

(defn abort-in-flight!
  "Best-effort abort the in-flight managed request registered under
  `request-id`, firing its `:abort-fn` with `reason` (default `:user`).
  No-op when nothing is registered under `request-id` (the reply already
  landed, or the id is unknown / nil) — cancellation is opportunistic.

  Per rf2-plngk the in-flight registry cleanup is owned by
  `finalise-failure!` (the abort-fn closure calls into it), so this fn
  only fires the abort-fn and never touches the index directly. Never
  throws (a throwing abort-fn is swallowed). Returns true iff a handle was
  found and its abort-fn fired, false otherwise.

  This is the shared abort-by-request-id seam: the `:rf.http/managed-abort`
  fx handler routes through it (`managed-abort-handler`), AND the resources
  out-of-cascade teardown paths (clear-resource / frame destroy) reach it
  through the published `:http/abort-in-flight!` late-bind hook so they can
  abort a managed request by its frame-qualified request-id without the
  resources artefact statically `:require`ing the http transport (rf2-rak684)."
  ([request-id] (abort-in-flight! request-id :user))
  ([request-id reason]
   (boolean
     (when-let [handle (lookup-in-flight request-id)]
       (try ((:abort-fn handle) reason) true
            (catch #?(:clj Throwable :cljs :default) _ false))))))

;; ---- per-request-id issuance generation (rf2-azcmd3) -----------------------
;;
;; EP-0011 §Work-id correlation: "one attempt has one work id". HTTP has no
;; generation counter of its own — supersession is keyed on `:request-id`
;; equality — so two requests issued under the SAME `:request-id` both reset
;; their retry `:attempt` to 1 and, before this counter, computed the SAME
;; work-id `[:rf.work/http logical-id 1]`. Tooling and conformance could then
;; not distinguish the superseded attempt from the superseding one by
;; `:work/id`, and EP-0011's single-attempt identity was broken for HTTP
;; supersession (Managed-Effects §Work-id correlation — §184: "one attempt has
;; one work id").
;;
;; This monotonic per-request-id ISSUANCE counter discriminates them: each
;; fresh issuance under a given request-id bumps the counter, so the
;; superseded request (issuance N) and the superseding one (issuance N+1) get
;; distinct work-id tuples. The retry `:attempt` still discriminates retries
;; WITHIN one issuance; the issuance discriminates re-issuances ACROSS
;; supersessions. An anonymous request (no `:request-id`) never supersedes, so
;; it stays at issuance 1.

(defonce ^:private issuance-counters
  ;; request-id → highest issuance number allocated so far. Monotonic per
  ;; request-id WHILE THE ID IS LIVE: `next-issuance!` only ever advances it,
  ;; so a superseded attempt (issuance N) and its superseder (N+1) carry
  ;; distinct `:work/id`s, and a late completion of an old attempt cannot reuse
  ;; a live issuance. rf2-k47b3d — the entry is EVICTED (not decremented) when
  ;; the id's final attempt terminates with the counter still AT that attempt's
  ;; issuance (`evict-issuance-on-completion!`), so an app minting an UNBOUNDED
  ;; distinct-id space (e.g. `[:load-item item-id]` over an unbounded item
  ;; space) no longer accumulates one permanent entry per id ever requested on
  ;; a long-running JVM. A re-issuance after a full eviction restarts at 1 —
  ;; safe because the prior attempt is already terminal (its trace/reply
  ;; emitted), so there is no live work-id collision to discriminate.
  (atom {}))

(defn next-issuance!
  "Allocate and return the next monotonic issuance number for `request-id`
  (rf2-azcmd3). The FIRST issuance under a request-id is 1; each subsequent
  re-issuance (the caller bumps this before `supersede!`) returns the next
  integer, so the superseded and superseding attempts carry distinct
  `:work/id`s. Returns 1 for a nil `request-id` (an anonymous request never
  supersedes — there is nothing to discriminate)."
  [request-id]
  (if (nil? request-id)
    1
    (get (swap! issuance-counters update request-id (fnil inc 0)) request-id)))

(defn evict-issuance-on-completion!
  "Evict `request-id`'s issuance counter when the attempt that carried
  `issuance` reaches a TERMINAL completion (rf2-k47b3d), bounding the
  `issuance-counters` map for apps minting UNBOUNDED distinct request-ids.

  Eviction is CONDITIONAL and atomic: the counter is dropped ONLY when it
  still equals `issuance` — no fresh request has re-issued under the same id
  since this attempt was allocated. That single-`swap!` compare-and-drop is
  what preserves the rf2-azcmd3 anti-collision invariant the monotonic counter
  exists for: when a supersede is in flight, `next-issuance!` has ALREADY
  bumped the counter PAST the superseded attempt's `issuance` (it bumps before
  `supersede!`, handlers.cljc), so this evict sees `counter > issuance`, skips,
  and the counter survives for the live successor. A single-issuance request
  (the leak vector) satisfies `counter == issuance`, so it evicts cleanly on
  completion. A `nil` `request-id` never had a counter (an anonymous request
  stays at issuance 1 without touching the map) — no-op.

  Called ONLY at the terminal-completion sites (`finalise-success!`,
  `finalise-failure!`, `dispatch-aborted!`), NEVER on the retry-clear or
  supersede-clear `clear-in-flight!` paths — those are not terminal (the
  attempt continues under the same issuance, or a live successor owns the id)."
  [request-id issuance]
  (when (and (some? request-id) (some? issuance))
    (swap! issuance-counters
           (fn [m]
             (if (= issuance (get m request-id))
               (dissoc m request-id)
               m))))
  nil)

(defn reset-issuance-counters-for-test!
  "Test-time helper (rf2-azcmd3): drop the per-request-id issuance counters so
  a fresh test run starts every request-id at issuance 1. Not part of the
  user-facing API."
  []
  (reset! issuance-counters {})
  nil)

(defn issuance-counter-count
  "Test/introspection helper (rf2-k47b3d): the number of resident per-request-id
  issuance counters. Lets a leak test assert the map does not grow unbounded
  across completed requests. Not part of the user-facing API."
  []
  (count @issuance-counters))

(defn supersede!
  "If a request is already in flight under `request-id`, abort it with
  `:reason :request-id-superseded`. Per Spec 014 §`:request-id` (internal).

  rf2-azcmd3 — returns the superseded handle (or nil when nothing was in
  flight) so the caller can emit the canonical `:status :stale` /
  `:work/status :suppressed` reply-envelope trace for the OLD attempt
  (Managed-Effects §Stale suppression). The handle carries the old attempt's
  identity facts (`:work/id`, `:request-id`, `:origin-event`, `:attempt`,
  `:frame`) the stale-reply trace needs."
  [request-id]
  (when-let [prev (lookup-in-flight request-id)]
    (clear-in-flight! request-id)
    (try
      ((:abort-fn prev) :request-id-superseded)
      (catch #?(:clj Throwable :cljs :default) _ nil))
    prev))

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
  ;; rf2-azcmd3 — drop the per-request-id issuance counters too, so the next
  ;; test run starts every request-id at issuance 1.
  (reset! issuance-counters {})
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
;; :spawn-all join resolution, frame destroy, imperative destroy),
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
                         (true? (:sensitive? handle))
                         ;; The handle carries its originating frame for the
                         ;; trace stamp; HTTP carrier redaction is process-global
                         ;; now (the :rf.http/managed `:carriers` registration,
                         ;; EP-0025), so it no longer depends on the frame.
                         {:frame (:frame handle)})))
        (try
          ((:abort-fn handle) :actor-destroyed)
          (catch #?(:clj Throwable :cljs :default) _ nil)))))
  nil)

;; ---- abort-in-flight-for-frame! (rf2-u5kmf8) ------------------------------
;;
;; Epoch-restore host-transient quiesce for NON-resource managed HTTP. The epoch
;; restore boundary (`perform-restore!`) installs the captured durable
;; frame-state WHOLESALE, but a plain `:rf.http/managed` request in flight is
;; host work — an AbortController / CompletableFuture + an in-flight registry
;; slot, NOT frame-state — so the wholesale install leaves it attached to the
;; pre-restore timeline. Unlike resource-backed HTTP (whose work-ledger row the
;; resources reconcile dangles), a plain managed request has no ledger gate: its
;; late completion would still deliver to its original `:rf/reply-to` target
;; against the restored state.
;;
;; This aborts every in-flight managed request the restored frame issued,
;; suppressing the app reply (the abort fires with `:reason :epoch-restored`,
;; which `http-transport` treats as a reply-suppressing reason — no delivery to
;; `:rf/reply-to`) and emitting the EP-0011 `:status :stale` /
;; `:work/status :suppressed` envelope facts for the suppressed attempt
;; (Managed-Effects §restore: "epoch restore MUST NOT revive host work" —
;; clauses 2/3/4 of §Stale suppression). It is the non-resource counterpart of
;; the resources reconcile, published as the `:http/abort-in-flight-for-frame!`
;; late-bind hook the epoch boundary fires AFTER a successful install.

(defn- emit-restore-stale-trace!
  "Emit the canonical EP-0011 `:status :stale` / `:work/status :suppressed`
  reply-envelope trace for one managed HTTP attempt aborted because epoch
  restore unwound its timeline (rf2-u5kmf8), WITHOUT dispatching any app target.
  Mirrors `http-transport/emit-superseded-stale-trace!` (the supersede sibling):
  the carried work-id is the aborted attempt's identity; there is no current
  work-id (restore replaces the attempt with nothing) so the gate suppresses the
  carried id against a nil current. Gated on `debug-enabled?` like the other
  `:rf.http/*` trace rows."
  [handle]
  (when interop/debug-enabled?
    (let [stale-ctx {:request-id   (:request-id handle)
                     :origin-event (:origin-event handle)
                     :issuance     (:issuance handle)
                     :attempt      (:attempt handle)
                     :frame        (:frame handle)}
          {:keys [reply trace]} (http-reply/suppress stale-ctx nil)
          summary (http-reply/trace-reply
                    reply
                    (cond-> {:sensitive? (true? (:sensitive? handle))}
                      (:frame handle) (assoc :frame (:frame handle))))]
      (trace/emit! :info :rf.http/stale-suppressed
                   (cond-> {:rf.reply/status       (:status summary)
                            :rf.reply/work-status  (:work/status summary)
                            :rf.reply/stale-reason (:stale/reason summary)
                            :rf.reply/work-id      (:work/id summary)
                            :work/kind             :http
                            :rf.reply/carried      (:rf.reply/carried trace)
                            :rf.reply/current      (:rf.reply/current trace)
                            :recovery              :suppressed-on-epoch-restore}
                     (:frame handle) (assoc :frame (:frame handle)))))))

(defn abort-in-flight-for-frame!
  "Abort every in-flight managed HTTP request issued by `frame-id`, because epoch
  restore unwound that frame's timeline (rf2-u5kmf8). For each matching handle:
  emit the EP-0011 stale-suppression envelope facts, then fire its `:abort-fn`
  with `:reason :epoch-restored` — a reply-suppressing reason, so the late
  completion does NOT deliver to its original `:rf/reply-to` target
  (Managed-Effects §restore). The abort-fn cascade clears the registry slot via
  `clear-in-flight!`.

  Walks BOTH indexes (an anonymous-from-actor request is only in
  `actor-in-flight`), filtering on the handle's `:frame` stamp, and dedupes by
  identity so a handle present in both indexes fires once. Snapshots the handles
  before firing so an abort-fn's own `clear-in-flight!` swap cannot trip a
  concurrent-modification on the iteration. Each abort-fn is fired defensively —
  a throwing one must not strand the rest. Idempotent and a no-op for a frame
  with no in-flight managed HTTP (an app that issues none pays nothing). Returns
  nil."
  [frame-id]
  (when frame-id
    (let [handles (->> (concat (vals @in-flight)
                               (mapcat val @actor-in-flight))
                       (filter #(= frame-id (:frame %)))
                       (reduce (fn [acc h]
                                 (if (some #(identical? % h) acc) acc (conj acc h)))
                               []))]
      (doseq [handle handles]
        (emit-restore-stale-trace! handle)
        (when-let [abort-fn (:abort-fn handle)]
          (try (abort-fn :epoch-restored)
               (catch #?(:clj Throwable :cljs :default) _ nil))))))
  nil)

;; ---- spawned-actor detection (rf2-ma0wvq inversion) -----------------------
;;
;; Per Spec 014 §Abort on actor destroy: a managed request "belongs to"
;; spawned actor `<spawned-id>` iff its originating event vector's first
;; element is `<spawned-id>` AND that id is registered as a spawned actor
;; in the frame's runtime-db spawn registry (per Spec 005 §Declarative
;; :spawn). That ownership decision is MACHINES-OWNED: machines knows the
;; registry shape, so it publishes the `:machines/owning-actor-id` late-
;; bind hook `(fn [frame-id event-id]) -> actor-id|nil`. http no longer
;; re-states the registry path or walks the registry itself — it asks
;; machines through the hook and treats a nil/absent hook (no machines
;; artefact on the classpath) as "not owned by any actor". This keeps the
;; structural dependency on the `:spawned` runtime-db layout entirely
;; inside the machines artefact that owns it (rf2-ma0wvq inverts the old
;; coupling, where http peeked inside machines state to classify ownership
;; while machines already called http through `:http/abort-on-actor-destroy`
;; for the destroy side).

(defn compute-actor-id
  "Resolve the spawned-actor-id for the request at hand, given the frame
  id and the originating event vector. Returns the actor-id (a keyword,
  the spawned actor's machine address) when the originating event-id is
  owned by a spawned actor, otherwise nil — meaning the request is NOT
  subject to actor-destroy cancellation (it was dispatched from an
  ordinary event handler, not from inside a spawned actor).

  Ownership is decided by the machines artefact via the
  `:machines/owning-actor-id` late-bind hook (rf2-ma0wvq): http asks
  machines whether the originating event-id belongs to a spawned actor
  rather than re-stating the registry path and walking it itself. When
  the machines artefact is absent the hook is unregistered, this returns
  nil, and apps that don't use state machines pay nothing.

  The `:rf.http/managed` guard short-circuits before the hook call: the
  framework-stamped fx event-id is never an actor address, so there is no
  point asking machines about it."
  [frame-id origin-event]
  (let [event-id (when (vector? origin-event) (first origin-event))]
    (when (and event-id
               (not= event-id :rf.http/managed))
      (when-let [owning-actor-id (late-bind/get-fn :machines/owning-actor-id)]
        (owning-actor-id frame-id event-id)))))
