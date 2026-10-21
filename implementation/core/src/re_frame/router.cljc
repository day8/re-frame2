(ns re-frame.router
  "Per-frame two-lane router and the drain loop. The contract is Spec 002
  §Run-to-completion dispatch (drain semantics) and Spec 002 §Drain-loop
  pseudocode.

  Each frame has an internal lane, for dispatches made inside its in-flight
  event and drained first, and the external `:queue`. A depth halt discards
  only the runaway family's internal work. A successful destroy claim is
  terminal: no later ordinary event or intermediate render begins."
  (:require [re-frame.frame :as rf.frame]
            [re-frame.elision :as rf.elision]
            [re-frame.live-frame :as rf.live-frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.interceptor :as rf.interceptor]
            [re-frame.interceptor-registry :as rf.interceptor-registry]
            [re-frame.error :as rf.error]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.events :as rf.events]
            [re-frame.spec :as rf.spec]
            [re-frame.cofx :as rf.cofx]
            [re-frame.fx :as rf.fx]
            [re-frame.router.diagnostics :as rf.router.diagnostics]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.performance :as rf.performance
             #?@(:cljs [:include-macros true])]
            [re-frame.trace :as rf.trace
             #?@(:cljs [:include-macros true])]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- dispatch-id allocation (Spec 009 §Dispatch correlation) --------------
;;
;; Dev-only: `build-envelope` allocates under `rf.interop/debug-enabled?`, so
;; production never touches the counter. The in-flight id rides
;; `rf.trace/*handler-scope*`, which a child reads for `:parent-dispatch-id`.

(defonce ^:private dispatch-counter (atom 0))

(defn- next-dispatch-id []
  (swap! dispatch-counter inc))


;; ---- lexical-scope fx overrides -------------------------------------------
;;
;; Bound by `re-frame.test-support/with-fx-overrides`, and directly by Story's
;; artifact replay. A plain map rather than a per-frame one, because the
;; macro stubs fx for a block of dispatches whichever frame they land on. Precedence: per-call opt > `*fx-overrides*` > per-frame (Spec 002
;; §Per-frame and per-call overrides).
(def ^:dynamic *fx-overrides* nil)

;; ---- recordable-coeffect stamping (Spec 002 §Envelope stamping) ----------
;;
;; `build-envelope` is the causal boundary: the only place the clock is read
;; for `:rf/time-ms`, which nothing downstream re-reads. Not dev-gated,
;; because the token is durable causal data. Epoch ms, not `now-ms`: CLJS
;; `performance.now()` is origin-relative and would not compare with
;; `js/Date`-based freshness checks. A child dispatch is stamped fresh, which
;; is why `:rf.cofx` is not an inheritable envelope key.

(defn- ensure-cofx
  "The caller-supplied `:rf.cofx` map with `:rf/time-ms` filled from
  `rf.interop/epoch-now-ms` iff absent; every supplied fact is kept verbatim."
  [supplied]
  (if (contains? supplied :rf/time-ms)
    supplied
    (assoc supplied :rf/time-ms (rf.interop/epoch-now-ms))))

(defn- build-envelope
  "Build the dispatch envelope for `event` from dispatch `opts` (Spec 002
  §Routing: the dispatch envelope).

  `:frame` is the explicit opt, else the scope/hold stamp: `with-frame`, a
  `frame-provider` (SCOPE) or a `frame-root` (ENSURE) boundary, or a captured
  `*current-frame*` binding. With none it throws `:rf.error/no-frame-context`
  before any registry lookup, as there is no `:rf/default` floor (Spec 002
  §Frame target resolution — the carried invariant). Throws on a malformed
  `:rf.cofx` before the clock is read. `:dispatch-id`, `:parent-dispatch-id`
  and `:call-site` are dev-only."
  [event opts]
  (let [dispatch-id        (when rf.interop/debug-enabled? (next-dispatch-id))
        parent-dispatch-id (when rf.interop/debug-enabled?
                             (some-> rf.trace/*handler-scope* :dispatch-id))
        ;; Gate the read itself, not only the `cond->` below: the keyword
        ;; literal otherwise survives DCE under :advanced + goog.DEBUG=false.
        call-site          (when rf.interop/debug-enabled?
                             (:rf.trace/call-site opts))
        ;; Always-on, and before the clock read: a malformed causal token
        ;; folds into durable writes, and a dispatch that cannot proceed must
        ;; not read the clock (Spec 002 §The `:rf.cofx` envelope field).
        ;; Retired draft opts get no dedicated error (Conventions §The
        ;; tombstone rule); the unknown-opt warning below names their
        ;; replacement.
         _                  (rf.router.diagnostics/validate-cofx! opts event)
         cofx               (ensure-cofx (:rf.cofx opts))
        ;; Before frame resolution, so a `:fram` typo still gets its warning
        ;; even though the dispatch then fails with `:rf.error/no-frame-context`.
        ;; Every dispatch path funnels through here. Dev-only and warn-only.
         _                  (when-let [unknown (rf.router.diagnostics/unknown-dispatch-opts opts)]
                              (rf.router.diagnostics/emit-unknown-dispatch-opts-warning! unknown event))
        ;; A SHOULD, so a dev-only warning rather than a throw (Conventions
        ;; §Event payloads SHOULD be serialisable data).
         _                  (when rf.interop/debug-enabled?
                              (when-let [bad-path (rf.router.diagnostics/find-non-serialisable-payload-path event)]
                                (rf.router.diagnostics/emit-non-serialisable-event-payload-warning! event bad-path)))
        ;; An explicit `:frame` wins even when unregistered; that fails later
        ;; as `:rf.error/frame-destroyed`, a different category from absence.
        ;; A frame object normalises to its id, so every downstream cascade op
        ;; keys on a keyword.
         frame              (rf.frame/frame-value->id
                               (or (:frame opts)
                                   (rf.frame/require-current-frame!
                                     :dispatch
                                     {:where    're-frame.router/build-envelope
                                      :event-id (first event)})))
        ;; Per-call mint policy (Spec 002 §Mint policies). A correctness
        ;; lever, so never debug-gated; carried only when supplied.
        mint-policy        (:rf.cofx/mint-policy opts)
        ;; The `:initial-events` setup-step index, which
        ;; `emit-dispatched-trace` stamps; carried only when present.
        step-index         (:step-index opts)
        ;; The incarnation a `capture-frame` op pinned; `dispatch!` fences the
        ;; enqueue to it. A correctness lever, so never debug-gated.
        expected-incarnation (:rf.frame/expected-incarnation opts)]
    (cond-> {:event                  event
             :frame                  frame
             ;; The per-call opt wins over the lexical binding; the per-frame
             ;; tier merges later, in `apply-overrides`.
             :fx-overrides           (merge *fx-overrides* (:fx-overrides opts {}))
             :interceptor-overrides  (:interceptor-overrides opts {})
             :trace-id               (:trace-id opts)
             ;; `:unknown`, so an unstamped dispatch is never misattributed as
             ;; UI-driven (Spec 009 §Dispatch source as the functional-origin
             ;; axis).
             :source                 (:source opts :unknown)
             :source-detail          (:source-detail opts)
             :origin                 (:origin opts :app)
             :rf.cofx                cofx}
      call-site          (assoc :call-site         call-site)
      dispatch-id        (assoc :dispatch-id        dispatch-id)
      parent-dispatch-id (assoc :parent-dispatch-id parent-dispatch-id)
      mint-policy        (assoc :rf.cofx/mint-policy mint-policy)
      step-index         (assoc :step-index step-index)
      expected-incarnation (assoc :rf.frame/expected-incarnation expected-incarnation))))

(defn- resolve-handler
  "The event's registration, or nil. A frame made without `:images` throws
  `:rf.error/ambiguous-registration` from the lookup when more than one
  namespace registers `event-id`; that comes back as `{::ambiguous ex}`, so
  the drain reports it and moves on to the next event."
  [event-id]
  (try
    (rf.registrar/lookup :event event-id)
    (catch #?(:clj Throwable :cljs :default) e
      (if (rf.registrar/ambiguous-registration? e)
        {::ambiguous e}
        (throw e)))))

(defn- resolve-unhandled
  "Handler-meta for an event the registrar does not know, or nil: the
  late-bound `:machines/resolve-actor-handler-meta`, which rebuilds a spawned
  actor's handler-meta from its app-db snapshot, so actor liveness is a pure
  function of app-db. A throwing resolver degrades to nil rather than abort
  the drain."
  [event frame]
  (when-let [resolve! (rf.late-bind/get-fn :machines/resolve-actor-handler-meta)]
    (try
      (resolve! event frame)
      (catch #?(:clj Throwable :cljs :default) _ nil))))

(def ^:private empty-fx-overrides
  "Shared sentinel for the override-free hot path, so no dispatch allocates a
  fresh empty map."
  {})

(def ^:private empty-extra-interceptors
  "Shared sentinel for the no-extra-interceptors hot path."
  [])

(def ^:private empty-icpt-overrides
  "Shared sentinel for the no-interceptor-overrides hot path."
  {})

(defn- apply-overrides
  "Merge per-frame and per-call overrides, per-call winning (Spec 002
  §Per-frame and per-call overrides). Returns `{:fx-overrides
  :icpt-overrides :extra-interceptors}`.

  Hot path: the override-free case returns shared empty sentinels rather than
  merging empty maps."
  [envelope frame-record]
  (let [frame-cfg            (:config frame-record)
        per-call-fx          (:fx-overrides envelope)
        per-frame-fx         (:fx-overrides frame-cfg)
        per-call-icpt        (:interceptor-overrides envelope)
        per-frame-icpt       (:interceptor-overrides frame-cfg)
        frame-interceptors   (:interceptors frame-cfg)]
    (if (and (nil? per-call-fx)
             (nil? per-frame-fx)
             (nil? per-call-icpt)
             (nil? per-frame-icpt)
             (nil? frame-interceptors))
      {:fx-overrides       empty-fx-overrides
       :icpt-overrides     empty-icpt-overrides
       :extra-interceptors empty-extra-interceptors}
      {:fx-overrides       (merge per-frame-fx per-call-fx)
       :icpt-overrides     (merge per-frame-icpt per-call-icpt)
       :extra-interceptors (vec frame-interceptors)})))

(defn- throw-override-invalid!
  "Throw `:rf.error/interceptor-override-invalid` for a malformed override key
  or replacement (Spec 002 §Error model)."
  [k v reason]
  (rf.error/throw-error!
    :rf.error/interceptor-override-invalid
    :rf.interceptor/overrides
    reason
    {:recovery :fix-overrides
     :extra    {:key         k
                :replacement v}}))

(defn- override-replacement
  "Return an `:interceptor-overrides` replacement after checking it: nil
  (remove) or an interceptor reference. Anything else throws
  `:rf.error/interceptor-override-invalid`, which keeps override maps
  serializable (Spec 002 §`:interceptor-overrides` — exact-reference
  substitution)."
  [k replacement]
  (cond
    (nil? replacement)                                     nil
    (rf.interceptor-registry/interceptor-ref? replacement) replacement
    :else
    (throw-override-invalid!
      k replacement
      (str "interceptor-override replacement for key `" (pr-str k) "` is neither "
           "an interceptor reference (keyword / `[id arg]`) nor `nil` (remove). "
           "Value-valued overrides are retired (EP-0022); register the "
           "replacement with `reg-interceptor` and reference it by id."))))

(defn- valid-override-key?
  "True when `k` is an interceptor reference: a keyword or an `[id arg]`
  2-vector."
  [k]
  (rf.interceptor-registry/interceptor-ref? k))

(defn- matching-override-key
  "The first `overrides` key matching the authored chain reference `ref`, or
  nil. Shared by `apply-icpt-overrides` and `override-summary`; callers pass
  only references."
  [overrides ref]
  (some (fn [k] (when (rf.interceptor-registry/override-key-matches? k ref) k))
        (keys overrides)))

(defn- apply-icpt-overrides
  "Substitute or remove references in the authored `chain` per `overrides`,
  before any reference resolves, so a removed reference's factory never runs
  (Spec 002 §`:interceptor-overrides` — exact-reference substitution). An
  `[id arg]` key matches only that exact vector. Throws
  `:rf.error/interceptor-override-invalid` on a malformed key or
  replacement. Returns `chain` unchanged when `overrides` is empty."
  [chain overrides]
  (if (empty? overrides)
    chain
    (do
      (doseq [k (keys overrides)]
        (when-not (valid-override-key? k)
          (throw-override-invalid!
            k (get overrides k)
            (str "interceptor-override key `" (pr-str k) "` is not an interceptor "
                 "reference (expected a keyword id or an `[id arg]` 2-vector)."))))
      (into []
            (keep (fn [entry]
                    (if-let [k (when (rf.interceptor-registry/interceptor-ref? entry)
                                 (matching-override-key overrides entry))]
                      (override-replacement k (get overrides k))
                      entry)))
            chain))))

;; ---- envelope override capture for strict replay --------------------------
;;
;; The epoch record carries the envelope's serializable overrides beside
;; `:rf.cofx`, so a strict replay can re-supply them (Spec-Schemas
;; §`:rf/epoch-record`). Only the envelope-carried tier: per-call and lexical
;; `:fx-overrides`, per-call `:interceptor-overrides`. The per-frame tier stays
;; in the replay target's live frame config.

(def ^:private fn-override-sentinel
  "Recorded in place of a CLJS-only fn-valued `:fx-overrides` entry, since a fn
  is not EDN. A strict replay that meets it fails loudly rather than re-run
  without the override (Spec 002 §`:fx-overrides` — replace fx handlers)."
  :rf/fn-override)

(defn- serializable-fx-overrides
  "`overrides` with each fn value replaced by `fn-override-sentinel`, or nil
  when empty so the caller omits the slot. Only `:fx-overrides` needs this:
  `:interceptor-overrides` is EDN by construction."
  [overrides]
  (when (seq overrides)
    (reduce-kv (fn [m k v] (assoc m k (if (fn? v) fn-override-sentinel v)))
               {}
               overrides)))

(defn- override-summary
  "The dev-only `:rf.interceptor/override-summary` tag: which authored
  references `overrides` acted on for this dispatch, as
  `{:matched [...] :replaced [...] :removed [...] :count n}`, or nil when
  `overrides` is empty (Spec 009 §`:tags` is the open-ended bag).

  Ids and counts only, never values, because an `[id arg]` ref's `arg` can
  carry app data. Unmatched keys are not counted: the tag reports what took
  effect."
  [authored-chain overrides]
  (when (seq overrides)
    (let [matched (reduce
                    (fn [acc entry]
                      (if-not (rf.interceptor-registry/interceptor-ref? entry)
                        acc
                        (if-let [k (matching-override-key overrides entry)]
                          (let [removed? (nil? (get overrides k))]
                            (-> acc
                                (update :matched conj k)
                                (update (if removed? :removed :replaced) conj k)))
                          acc)))
                    {:matched [] :replaced [] :removed []}
                    authored-chain)]
      (assoc matched :count (count (:matched matched))))))

(defn- validate-event!
  "Validate the dispatched event against the handler's `:schema` before its
  chain runs (Spec 010 §Validation order on event processing). Returns truthy
  to run the handler; falsy to skip it, after emitting
  `:rf.error/schema-validation-failure` with `:where :event`.

  Two arms at one site. Dev runs the late-bound `:schemas/validate-event!`,
  where a throwing validator passes. Production runs
  `rf.spec/validate-at-boundary!`, which checks only `:boundary? true`
  handlers and sits outside that catch-and-pass, so a throw stays a refusal.
  Without the schemas artefact the dev arm falls through to the production
  one, so dev meets the `:rf.error/schemas-artefact-missing` refusal first.
  `frame` is threaded so the failure trace carries `:frame`, which production
  has no other source for."
  [event-id event handler-meta frame]
  (if (rf.spec/dev-mode?)
    (if-let [validate! (rf.late-bind/get-fn :schemas/validate-event!)]
      (try (validate! event-id event handler-meta frame)
           (catch #?(:clj Throwable :cljs :default) _
             true))
      ;; Outside the catch-and-pass, so a boundary refusal stays a refusal.
      (rf.spec/validate-at-boundary! event-id event handler-meta frame))
    (rf.spec/validate-at-boundary! event-id event handler-meta frame)))

(defn- assemble-initial-ctx
  "The initial interceptor context for `envelope` (Spec 002 §Event context
  threads both partitions). Coeffects: `:db` (app-db only, not the whole
  frame), `:rf.db/runtime` by reference, `:rf.frame/id`, `:rf.cofx`, the
  resolved `:rf.cofx/mint-policy`, `:source` and `:trace-id` when present,
  then the handler's declared cofx.

  `:rf/framework-authority?` is a context flag, not a coeffect. The commit
  site reads it to decide whether a `:rf.db/runtime` effect earns the
  `:rf.warning/app-handler-runtime-effect` dev warning; it is not a
  capability gate (Spec 002 §Write authority is by convention)."
  [envelope frame frame-record handler-meta fx-overrides]
  (let [event       (:event envelope)
        ;; One coherent state value for both partitions.
        frame-state (rf.frame/frame-record-state-value frame-record)
        db-value    (get frame-state rf.frame/app-partition-key)
        runtime-db  (get frame-state rf.frame/runtime-partition-key)
        requires    (:rf.cofx/requires-parsed handler-meta)
        ;; Resolved from the live frame record rather than at enqueue, so a
        ;; frame re-registered with another policy applies it on its next
        ;; dispatch (Spec 002 §Mint policies). Also staged as a coeffect, for a
        ;; machine whose guards declare cofx its outer event does not.
        mint-policy (rf.cofx/resolve-mint-policy
                      (:rf.cofx/mint-policy envelope)
                      (:rf.cofx/mint-policy (:config frame-record)))
        base-cofx   (cond-> {:db              db-value
                             :event           event
                             :rf.db/runtime   runtime-db
                             :rf.frame/id     frame
                             :rf.cofx         (:rf.cofx envelope)
                             :rf.cofx/mint-policy mint-policy}
                      (:source envelope)   (assoc :source (:source envelope))
                      (:trace-id envelope) (assoc :trace-id (:trace-id envelope)))
        ;; Deliver exactly the declared facts (Spec 002 §Declaration and
        ;; delivery — `:rf.cofx/requires`). A throwing supplier or generator
        ;; sets `:rf/skip-handler?` rather than throw out of assembly.
        {:keys [coeffects rf/skip-handler?] :as delivered}
        (if (seq requires)
          (rf.cofx/deliver-declared-cofx
            base-cofx requires (:rf.cofx envelope) (first event) frame
            mint-policy)
          {:coeffects base-cofx :rf.cofx (:rf.cofx envelope) :rf/skip-handler? false})
        ;; Restamp with the delivered record, which carries any generated
        ;; fact.
        coeffects (assoc coeffects :rf.cofx (:rf.cofx delivered))]
    (cond-> {:coeffects coeffects
             :effects {}
             :rf/framework-authority? (rf.events/framework-authority? handler-meta)
             :rf/fx-overrides fx-overrides}
      ;; Delivery sets its skip flag only when a supplier / generator
      ;; threw. `:rf/cofx-failed?` tells `commit-and-flow!` this skip is a
      ;; FAILURE, so the event settles `:error` with nothing installed; an
      ;; interceptor's deliberate `:rf/skip-handler?` carries no marker.
      skip-handler? (assoc :rf/skip-handler? true
                           :rf/cofx-failed? true))))

(def ^:private handler-wrapping-interceptor-ids
  "Ids of the interceptor that wraps the event handler: a captured
  `:rf/interceptor-error` carrying one is the handler itself throwing. Held
  here rather than imported from `events` to keep the router cycle-free."
  #{:rf/event-handler})

(defn- classify-pipeline-exception
  "Classify a captured `:rf/interceptor-error` by its failing component,
  returning `{:operation :failing-id :reason}`. The handler wrapper throwing
  is `:rf.error/handler-exception` attributed to the event id; any other
  interceptor is `:rf.error/interceptor-exception` attributed to its `:id`.
  Coeffect-supplier throws never reach here: context assembly emits them."
  [error event-id]
  (let [id (:id error)]
    (cond
      (contains? handler-wrapping-interceptor-ids id)
      {:operation  :rf.error/handler-exception
       :failing-id event-id
       :reason     "Event handler threw."}

      :else
      {:operation  :rf.error/interceptor-exception
       :failing-id id
       :reason     (str "Interceptor `" id "` threw in its `"
                        (name (:phase error)) "` phase.")})))

(defn- elapsed-ms-from
  "Integer `:elapsed-ms` from two clock reads, floored at 0. Rounded here
  because `now-ms` is a float on CLJS and the record field is an integer. The
  caller passes its own `end-ms` so the emit time and the elapsed share one
  read."
  [start-ms end-ms]
  (long (max 0 (- end-ms start-ms))))

(defn- emit-pipeline-exception!
  "Emit a captured interceptor-chain exception under its failing component's
  category, on the always-on error channel and then the dev-only trace
  (Spec 009 §Error contract). The chain captures rather than rethrows so the
  drain does not abort. `:handler-id` is stamped only when the handler itself
  threw."
  [error event-id frame ctx start-ms]
  (let [exception  (:exception error)
        ;; nil-safe: a thrown non-Error value (legal in CLJS) has no
        ;; `.-message`.
        msg        (rf.error/ex-message-safe exception)
        emit-event (rf.interceptor/get-coeffect ctx :event)
        end-ms     (rf.interop/now-ms)
        elapsed-ms (elapsed-ms-from start-ms end-ms)
        {:keys [operation failing-id reason]}
        (classify-pipeline-exception error event-id)
        handler-throw? (= operation :rf.error/handler-exception)
        ;; The throwing interceptor's definition site, for Xray's
        ;; jump-to-source; absent when the interceptor carries none.
        icpt-coord (:source-coord error)
        tags       (cond-> {:event-id          event-id
                            :event             emit-event
                            :frame             frame
                            :failing-id        failing-id
                            :phase             (:phase error)
                            :exception         exception
                            :exception-message msg
                            :reason            reason
                            :recovery          :no-recovery}
                     handler-throw? (assoc :handler-id event-id)
                     icpt-coord     (assoc :source-coord icpt-coord))]
    ;; `:phase` also rides the always-on record, so a `:before` and an
    ;; `:after` throw stay distinct in production. A handler throw is always
    ;; `:before`.
    (rf.error-emit/emit-error-both!
      operation emit-event event-id frame exception elapsed-ms end-ms tags
      (when-not handler-throw? {:phase (:phase error)}))))

(defn- run-candidate-validation!
  "Validate the complete candidate transition before it is installed (Spec 010
  §Per-step recovery); the container is unwritten while this runs. Returns
  true when every touched partition conforms, false to reject the candidate.

  Each validator sees its own partition's candidate and runs only when this
  transition writes that partition: app schemas on `db-after`, skipping slices
  left `identical?` to `db-before`, and machine `[:schemas :data]` on
  `runtime-db-after`. The app arm also receives `runtime-db-after`, whose
  elision registry already carries this event's classification effects, so a
  failure trace honours a path the same event classifies even though the
  rejection installs nothing. Both arms always run, so every violation emits
  its own trace. A validator-machinery throw also rejects (fail closed), since
  a throwing validator cannot prove conformance."
  [db-before db-after runtime-db-after app-effect? rt-effect? event-id frame]
  (let [emit-throw-reject!
        ;; The always-on record goes first, so the JVM SSR listener's
        ;; last-write-wins buffer keeps the richer trace.
        ;;
        ;; The explicit debug gate is deliberate. Both reachable validators
        ;; return true in production, so this catch is unreachable there; the
        ;; gate makes that structural (Spec 009 §The promotion criterion) and
        ;; lets `check-elision.cjs` prove the reason's tail absent from a
        ;; release bundle.
        ;;
        ;; The `:reason` is a constant: the validator's own message is
        ;; unbounded and author-controlled, so it rides only the dev trace.
        (fn [where ex]
          (when rf.interop/debug-enabled?
            (rf.error-emit/dispatch-error-record!
              {:rf.error/id :rf.error/malformed-schema
               :where      where
               :event-id   event-id
               :failing-id event-id
               :frame      frame
               :rollback?  true
               :recovery   :no-recovery
               ;; ONE string literal for the tail — `str` compiles to a
               ;; runtime concat, so a split tail could never be proven
               ;; absent from a release bundle.
               :reason     (str "A candidate-transition validator threw at the "
                                where
                                " boundary; the candidate transition was rejected fail-closed and nothing was installed.")
               :time       (rf.interop/now-ms)}))
          (rf.trace/emit-error!
            :rf.error/malformed-schema
            (cond-> {:where     where
                     :frame     frame
                     :reason    (str "Candidate-transition validator threw; "
                                     "the transition is REJECTED (fail "
                                     "closed, nothing installed): "
                                     #?(:clj  (.getMessage ^Throwable ex)
                                        :cljs (ex-message ex)))
                     :rollback? true
                     :recovery  :no-recovery}
              event-id (assoc :failing-id event-id))))
        run-partition-validator!
        ;; Runs the late-bound `hook-key` validator only when the partition is
        ;; written and the hook installed; otherwise a pass. A nil return is a
        ;; pass. `before` trails the validator's own arguments.
        (fn [effect? hook-key partition-value where & before]
          (if effect?
            (if-let [validate (rf.late-bind/get-fn hook-key)]
              (try
                (let [result (apply validate partition-value event-id frame before)]
                  (if (nil? result) true result))
                (catch #?(:clj Throwable :cljs :default) ex
                  (emit-throw-reject! where ex)
                  false))
              true)
            true))
        app-ok?
        (run-partition-validator! app-effect? :schemas/validate-app-schema!
                                  db-after :app-db db-before runtime-db-after)
        machines-ok?
        (run-partition-validator! rt-effect? :machines/validate-machine-data!
                                  runtime-db-after :machine-data)]
    (and app-ok? machines-ok?)))

(defn- emit-frame-state-changed!
  "Emit `:rf.event/frame-state-changed` tagged with the partitions in
  `changed` (Spec 009 §Canonical per-event trace sequence). Fires only when a
  partition changed, and only on the forward commit, since a rejected
  candidate never commits. Dev-only."
  [event-id emit-event frame changed]
  (when (seq changed)
    (let [tags (cond-> #{}
                 (contains? changed rf.frame/app-partition-key)     (conj :app-db)
                 (contains? changed rf.frame/runtime-partition-key) (conj :runtime-db))]
      (rf.trace/emit! :rf.event :rf.event/frame-state-changed
                   {:rf.trace/event-id     event-id
                    :rf.event/v            emit-event
                    :frame                 frame
                    :rf.event/partitions   tags}))))

(defn- emit-db-event!
  "Emit an app-db change trace, `op` being `:rf.event/db-changed` or
  `:rf.event/db-noop`, with the standard per-event tags. Dev-only."
  [op event-id emit-event frame]
  (rf.trace/emit! :rf.event op
               {:rf.trace/event-id event-id
                :rf.event/v        emit-event
                :frame             frame}))

(defn- commit-frame-effects!
  "Install the cascade's `:db`, `:rf.db/runtime` and classification effects as
  one atomic `commit-frame-transition!` (Spec 002 §An ordinary `:db` return
  replaces only app-db). Returns true when installed or when there was
  nothing to install; false when candidate validation rejected it.

  Prepare, validate, install: the candidate is built without touching the
  container, so a rejection writes nothing, fires no change trace and needs no
  restore, and no synchronous observer can see an invalid candidate (Spec 010
  §Per-step recovery). `effects` already carries the flow-augmented `:db`.
  Change traces follow Spec 009 §Canonical per-event trace sequence:
  `db-changed` and `db-noop` are app-db only, and `frame-state-changed` names
  the partitions that changed. A `:db nil` effect is coerced to `{}` with a
  dev warning, because it is usually a bug."
  [effects event-id event frame frame-record ctx]
  (let [app-effect?  (contains? effects :db)
        rt-effect?   (contains? effects :rf.db/runtime)
        ;; Classification effects commit with `:db`, as a runtime-db write in
        ;; the same transition, so a same-event classify-then-egress redacts
        ;; (Spec 015 §Durable app-db — the four commit-plane effects). They
        ;; were validated pre-commit in `rf.events/commit-fx-effects`.
        class-effect? (rf.elision/classification-effect? effects)
        ;; The flow pass's exact output claims, a fn of runtime-db, install
        ;; with the outputs they describe (Spec 013 §Failure semantics).
        flow-claims  (:claims (:rf/flow-pass ctx))
        nil-db?      (and app-effect? (nil? (:db effects)))
        new-db       (if nil-db? {} (:db effects))]
    (when (and nil-db? rf.interop/debug-enabled?)
      (rf.trace/emit! :warning :rf.warning/db-nil-coerced
                   {:rf.trace/event-id (when (vector? event) (first event))
                    :rf.event/v        event
                    :frame             frame
                    :recovery          :warned
                    :reason
                    (str "Event `" (when (vector? event) (first event)) "` returned `{:db nil}`. "
                         "app-db is never nil — the nil was coerced to `{}` "
                         "at the commit boundary (the v1 nil-footgun is removed structurally). "
                         "A `{:db nil}` return is usually a BUG (a handler accidentally computed "
                         "nil); for a deliberate clear, return `{:db {}}` (which emits no "
                         "diagnostic).")}))
    (if (or app-effect? rt-effect? class-effect? flow-claims)
      (let [emit-event (rf.interceptor/get-coeffect ctx :event)
            ;; A whole-value `:rf.db/runtime` effect would drop the elision
            ;; registry, which other subsystems write out of band, so the
            ;; effect is reconciled to keep it unless it names the registry
            ;; itself. Reconcile against the LIVE runtime-db, not the
            ;; chain-start snapshot: a reentrant `reg-flow`, an actor spawn or
            ;; a route activation can rewrite the registry mid-cascade, and the
            ;; stale snapshot would let those paths egress raw for one commit.
            live-runtime-result
            (when (or rt-effect? class-effect? flow-claims)
              (rf.frame/frame-record-state-value frame-record))
            live-runtime-db
            (get live-runtime-result rf.frame/runtime-partition-key)
            reconciled-rt  (when rt-effect?
                             (rf.elision/reconcile-runtime-db-effect
                               (:rf.db/runtime effects) live-runtime-db))
            new-runtime-db (cond-> (cond
                                     class-effect?
                                     (rf.elision/apply-classification-effects
                                       (if rt-effect? reconciled-rt live-runtime-db)
                                       effects)
                                     rt-effect? reconciled-rt
                                     flow-claims live-runtime-db
                                     :else nil)
                             flow-claims flow-claims)
            ;; A no-op classification collapses to no change at commit, so a
            ;; stray partition write is harmless.
            rt-partition?  (or rt-effect? class-effect? flow-claims)
            partitions (cond-> {}
                         app-effect?   (assoc rf.frame/app-partition-key     new-db)
                         rt-partition? (assoc rf.frame/runtime-partition-key new-runtime-db))
            validation-result
              (run-candidate-validation!
                (:db (:coeffects ctx)) new-db new-runtime-db app-effect?
                rt-effect? event-id frame)]
        (cond
          (not validation-result)
          ;; Rejected before install; the diagnostics already fired.
          false

          :else
          (let [changed      (rf.frame/commit-frame-transition! frame partitions)
                app-changed? (contains? changed rf.frame/app-partition-key)]
            (when app-changed?
              (emit-db-event!
                :rf.event/db-changed event-id emit-event frame))
            (when (and app-effect? (not app-changed?))
              (emit-db-event!
                :rf.event/db-noop event-id emit-event frame))
            (emit-frame-state-changed!
              event-id emit-event frame changed)
            true)))
        true)))

(def ^:private flows-after-interceptor
  "The framework's outermost `:after` interceptor, which runs the flow
  transform over the pending `:db` effect (Spec 013 §Drain integration).

  Outermost is load-bearing: `path`'s `:after` must splice the handler's slice
  back into the full db before flows read their full-app-db inputs. The pass's
  result rides `:rf/flow-pass`, so its claims install with the candidate and
  its memo publishes only once that candidate has installed. A flow
  throw discards the pending `:db` and stashes the throw under
  `:rf/flow-error`, so the event aborts with nothing installed and no `:fx`
  (Spec 013 §Failure semantics). Emits the dev-only pending-db pair: t1
  `:rf.event/db-pending` when the handler returned `:db`, and t2
  `:rf.event/db-pending-post-flow` only when a flow changed it. Both carry the
  full value, which structural sharing makes pointer-sized."
  (rf.interceptor/->interceptor*
    :id          :rf/flows
    :rf/default? true
    :after
    (fn [ctx]
      ;; The chain already errored, so this event will not commit. Running
      ;; flows anyway would emit spurious traces (Spec 013 §Failure
      ;; semantics).
      (if (:rf/interceptor-error ctx)
        ctx
        (let [frame       (:rf.frame/id (:coeffects ctx))
              effects     (:effects ctx)
              has-db?     (contains? effects :db)
              ;; Flows reading `[:rf.db/runtime ...]` inputs must see the
              ;; pending runtime-db too, or a route- or machine-reading flow
              ;; silently stops updating (Spec 013 §Input partition).
              has-runtime-effect? (contains? effects :rf.db/runtime)
              run-on-db   (rf.late-bind/get-fn :flows/run-flows-on-db)
              pending-db  (if has-db?
                            (:db effects)
                            (when run-on-db (-> ctx :coeffects :db)))
              ;; Read-only for the pass: flow outputs write app-db only.
              pending-runtime-db (when run-on-db
                                   (if has-runtime-effect?
                                     (:rf.db/runtime effects)
                                     (-> ctx :coeffects :rf.db/runtime)))
              ;; The same per-event attribution every other `:rf.event` emit
              ;; carries; epoch assembly relies on it.
              emit-event  (when has-db? (rf.interceptor/get-coeffect ctx :event))
              event-id    (when has-db? (some-> emit-event first))
              ;; t1 and t2 precede the commit that folds this event's
              ;; classification effects into the registry, so carry them under
              ;; a private tag the projection consumes and strips: a path
              ;; classified in this event is redacted on its first egress.
              ;; Dev-only.
              class-effects (when (and rf.interop/debug-enabled?
                                       (rf.elision/classification-effect? effects))
                              (rf.elision/classification-effects effects))]
          (when has-db?
            (rf.trace/emit! :rf.event :rf.event/db-pending
                         (cond-> {:rf.trace/event-id event-id
                                  :rf.event/v        emit-event
                                  :frame             frame
                                  :rf.event/db       pending-db}
                           class-effects
                           (assoc :re-frame.classification/pending-classification-effects
                                  class-effects))))
          (if run-on-db
            (try
              (let [pass   (run-on-db frame pending-db pending-runtime-db)
                    new-db (:db pass)]
                ;; t2 can fire with no handler `:db`, when flows synthesised
                ;; one, so resolve the attribution lazily.
                (when (not (identical? new-db pending-db))
                  (let [t2-event   (or emit-event (rf.interceptor/get-coeffect ctx :event))
                        t2-evt-id  (or event-id (some-> t2-event first))]
                     (rf.trace/emit! :rf.event :rf.event/db-pending-post-flow
                                 (cond-> {:rf.trace/event-id t2-evt-id
                                          :rf.event/v        t2-event
                                          :frame             frame
                                          :rf.event/db       new-db}
                                   class-effects
                                   (assoc :re-frame.classification/pending-classification-effects
                                          class-effects)))))
                (cond-> (assoc ctx :rf/flow-pass pass)
                  (or has-db? (not (identical? new-db pending-db)))
                  (rf.interceptor/assoc-effect :db new-db)))
              (catch #?(:clj Throwable :cljs :default) e
                ;; Dropping `:db` is the whole abort: the install is deferred
                ;; to one write, so there is nothing to unwind.
                (-> (update ctx :effects dissoc :db)
                      (assoc :rf/flow-error e))))
                ctx))))))

(defn- emit-flow-eval-exception!
  "Emit a flow-eval throw stashed under `:rf/flow-error` as
  `:rf.error/flow-eval-exception`, on the always-on channel and then the dev
  trace; the caller skips `:fx` and the drain moves on (Spec 013 §Failure
  semantics). `:where`, `:flow-id` and `:phase` ride the always-on record
  itself so they survive an egress profile that drops `:exception`; `:phase`
  tells a throwing `:derive` from a failed `:output-write` (Spec 009 §Error
  event catalogue)."
  [e event event-id frame start-ms]
  (let [end-ms     (rf.interop/now-ms)
        elapsed-ms (elapsed-ms-from start-ms end-ms)
        data       (ex-data e)
        failed-id  (:rf.flow/failed-id data)
        phase      (:rf.flow/failed-phase data)]
    (rf.error-emit/emit-error-both!
      :rf.error/flow-eval-exception
      event event-id frame e elapsed-ms end-ms
      {:frame frame :event event :exception e}
      (cond-> {:where :flow-eval}
        (some? failed-id) (assoc :flow-id failed-id)
        (some? phase)     (assoc :phase phase)))))

(defn- emit-classification-effect-shape!
  "Emit `:rf.error/classification-effect-shape` for a malformed commit-plane
  classification effect in the final effects map. `defect` is
  `rf.elision/classification-effect-defect`'s map.

  In-band, not a throw: a throw would escape into `drain-emergency-release!`
  and abandon the queue, so only this event aborts, with nothing committed
  and no `:fx`. Only `:offending-key` reaches the always-on record: program
  structure from a closed four-keyword domain, and the one discriminator
  between the axes. `:value` is the rejected payload and `:reason`
  interpolates it, so both stay on the dev trace; the tags carry no
  `:failing-id`, whose lift would drag `:reason` onto the record.
  `re-frame.classification-effect-shape-record-cljs-test` pins the record's
  keys."
  [defect event event-id frame start-ms]
  ;; Build the discriminator ONCE, before either axis sees it — the two axes
  ;; must never be able to disagree about which key was at fault.
  (let [end-ms        (rf.interop/now-ms)
        elapsed-ms    (elapsed-ms-from start-ms end-ms)
        offending-key (:offending-key defect)]
    (rf.error-emit/emit-error-both!
      :rf.error/classification-effect-shape
      event event-id frame nil elapsed-ms end-ms
      ;; Axis 2 — the dev trace. Carries the full diagnosis, DCE'd in prod.
      {:frame             frame
       :rf.trace/event-id event-id
       :rf.event/v        event
       :offending-key     offending-key
       :value             (:value defect)
       :recovery          :fix-effect
       :reason            (:reason defect)}
      ;; Axis 1 — the always-on record: the bounded discriminator only.
      {:offending-key offending-key})))

(defn- emit-effect-map-shape!
  "Emit `:rf.error/effect-map-shape` for a malformed effect-map envelope in the
  final effects map: a foreign top-level key, or a non-sequential `:fx`.
  `defect` is `rf.events/effect-map-defect`'s map.

  In-band like `emit-classification-effect-shape!`, aborting only this event.
  It refuses rather than drops the key, because dropping it would commit `:db`
  while the effect never ran (Conventions §No silent swallow), and does so in
  every build so dev never aborts what production commits. Only
  `:offending-key` reaches the always-on record: app-authored, but program
  structure rather than a runtime value. `:failing-id` equals `:event-id`, so
  the lift cannot drag `:reason` onto the record.
  `re-frame.effect-map-shape-record-cljs-test` pins the record's keys."
  [defect event event-id frame start-ms]
  ;; Build the discriminator ONCE, before either axis sees it — the two axes
  ;; must never be able to disagree about which key was at fault.
  (let [end-ms        (rf.interop/now-ms)
        elapsed-ms    (elapsed-ms-from start-ms end-ms)
        offending-key (:offending-key defect)]
    (rf.error-emit/emit-error-both!
      :rf.error/effect-map-shape
      event event-id frame nil elapsed-ms end-ms
      ;; Axis 2 — the dev trace. Carries the full diagnosis, DCE'd in prod.
      {:failing-id        event-id
       :rf.trace/event-id event-id
       :rf.event/v        event
       :offending-key     offending-key
       :value             (:value defect)
       :recovery          :fix-effect
       :reason            (:reason defect)}
      ;; Axis 1 — the always-on record: the structural discriminator only.
      {:offending-key offending-key})))

(defn- run-fx-effects!
  "Walk `:fx` in source order with the effective `fx-overrides` and the
  frame's `:platform`. The originating envelope rides into `do-fx` so the
  reserved `:dispatch` and `:dispatch-later` can copy its inheritable keys
  onto children (Spec 002 §Run propagation). Returns `:ok` when there is no
  `:fx`."
  [effects frame frame-record fx-overrides envelope]
  (if-let [fx-vec (:fx effects)]
    (let [active-platform (rf.fx/platform-for-frame-record frame-record)
          event           (:event envelope)]
      (rf.fx/do-fx
        frame fx-vec active-platform
        {:overrides       fx-overrides
         :origin-event    event
         :parent-envelope envelope
         :effects         effects}))
    :ok))

;; ---- process-event* phases ------------------------------------------------
;;
;; `process-event*` early-exits on a destroyed frame or a missing handler;
;; otherwise `run-handler-pipeline!` sequences `prepare-handler-ctx`,
;; `run-chain` (flows run inside it, as the outermost `:after`),
;; `commit-and-flow!` and `emit-pipeline-trailers!`.

(defn- emit-frame-destroyed!
  "Emit `:rf.error/frame-destroyed` on the always-on channel and the dev
  trace. Recover-but-emit: the caller no-ops, because a teardown race cannot
  be told from a use-after-destroy bug, and the emit keeps the bug observable
  in production (Spec 009 §Error event catalogue).

  `op` (`:dispatch`, `:dispatch-sync` or `:subscribe`) rides the record's
  `:op` slot and picks the source-coord realm, since an event and a sub may
  share a keyword. `route-frame?` false withholds the frame-owned sink route
  for a captured op whose pinned incarnation is dead: a same-id successor may
  hold that id, and must not receive a dead incarnation's failure."
  [event-id event frame-id op route-frame?]
  ;; No exception and no elapsed time: a refused op, not a throw.
  (rf.error-emit/emit-error-both!
    :rf.error/frame-destroyed
    event event-id frame-id nil 0 (rf.interop/now-ms)
    {:frame frame-id :event event :reason :frame-destroyed :op op}
    {:op op}
    route-frame?))

(defn- handle-frame-destroyed!
  "A frame destroyed between enqueue and dequeue: emit
  `:rf.error/frame-destroyed` and let the drain continue (Spec 002
  §Run-to-completion dispatch (drain semantics)). The queued event names the
  frame by id, so the emit keeps its frame route."
  [event frame]
  (emit-frame-destroyed! (first event) event frame :dispatch true))

(defn ^:no-doc emit-captured-frame-superseded!
  "Recover-but-emit for a `capture-frame` op whose pinned incarnation was
  destroyed, whether the id is now free or a same-id successor holds it. The
  op never reaches the successor, and `:rf.error/frame-destroyed` is emitted
  without the frame route. Called by `make-capture-frame`'s `:dispatch`,
  `:dispatch-sync` and `:subscribe` ops when their pre-check sees the pin
  gone; `op` names which, so the source coord comes from the right realm.
  `opts` carries the dev-only `:rf.trace/call-site`. Returns nil.

  The window between that pre-check and the enqueue is closed by
  `:rf.frame/expected-incarnation`, which `dispatch!` and `dispatch-sync!`
  check against the record they resolve (Spec 002 §`capture-frame` — the
  keystone affordance)."
  [event frame-id op opts]
  (rf.trace/with-call-site (when rf.interop/debug-enabled? (:rf.trace/call-site opts))
    (emit-frame-destroyed! (first event) event frame-id op false))
  nil)

(defn- prepare-handler-ctx
  "Build `{:full-chain :initial-ctx :fx-overrides :override-summary}` for a
  resolved handler (Spec 002 §Effective chain ordering): frame `:interceptors`
  prepended to the handler's chain, `:interceptor-overrides` applied to the
  authored references, the survivors resolved once, and the flows
  interceptor at the head. Hot path: the override-free case reuses the
  handler's own chain."
  [envelope frame frame-record handler-meta]
  (let [{:keys [extra-interceptors fx-overrides icpt-overrides]}
        (apply-overrides envelope frame-record)
        prepended-chain (if (seq extra-interceptors)
                          (vec (concat extra-interceptors (:interceptors handler-meta)))
                          (:interceptors handler-meta))
        ;; Overrides edit the authored references before any resolves, so a
        ;; removed or replaced reference is never built.
        overridden-chain (apply-icpt-overrides prepended-chain icpt-overrides)
        ;; Reference-only: a stale inline interceptor value throws
        ;; `:rf.error/inline-interceptor-removed` (Spec 002 §Validation and
        ;; resolution timing). Skipped when the chain is only the framework's
        ;; handler wrapper.
        base-chain      (if (rf.interceptor-registry/chain-needs-resolution? overridden-chain)
                          (rf.interceptor-registry/resolve-chain overridden-chain)
                          overridden-chain)
        ;; Added at dispatch time rather than baked into the registration, so
        ;; `(handler-meta :event id)` still shows the authored chain (Spec 013
        ;; §Drain integration).
        full-chain      (into [flows-after-interceptor] base-chain)
        initial-ctx     (assemble-initial-ctx
                          envelope frame frame-record handler-meta fx-overrides)]
    {:full-chain   full-chain
     :initial-ctx  initial-ctx
     :fx-overrides fx-overrides
     ;; Walks the pre-override chain: `base-chain` has already lost the
     ;; matched references.
     :override-summary (override-summary prepended-chain icpt-overrides)}))

(defn- run-chain
  "Run `full-chain` inside a performance bracket (Spec 009 §Performance
  instrumentation). A failed event validation sets `:rf/skip-handler?` rather
  than skipping the chain, so entered interceptors still run their `:after`
  (Spec 002 §Interceptor chain execution). A refused boundary-guarded handler
  is also marked `:rf/boundary-rejected?`, as production would mark it; an
  unguarded dev-only `:schema` refusal is not, since production has no such
  signal (Spec 010 §Production builds)."
  [event-id full-chain initial-ctx event-ok? handler-meta]
  (let [ctx (if event-ok?
              initial-ctx
              (cond-> (assoc initial-ctx :rf/skip-handler? true)
                (rf.events/boundary-guarded-handler? handler-meta)
                (assoc :rf/boundary-rejected? true)))]
    (rf.performance/mark-and-measure :event event-id
      (rf.interceptor/execute-chain full-chain ctx))))

(defn- publish-flow-memo!
  "Advance the frame's flow memo to the rows the pass in `ctx` proposed, once
  the candidate they describe has installed (Spec 013 §Failure semantics)."
  [ctx]
  (when-let [publish! (:publish! (:rf/flow-pass ctx))]
    (publish!)))

(defn- settle-flows-after-walk!
  "Run the flow pass again when the `:fx` walk changed the frame's state, and
  install its outputs in this same event (Spec 013 §Sequencing). The pass and
  install run before the walk, so a write during it — a machine lifecycle
  effect, any fx calling `swap-runtime-db!` — would otherwise leave every flow
  reading it stale for the continuations the walk queued. Keyed on the
  container's identity rather than a list of writers, which would be easy to
  leave incomplete; a frame running no flows passes through the pass
  unchanged.

  The install is the event's own commit path: candidate validation, one atomic
  transition, the memo published with it. A flow throw installs nothing and
  reports `:rf.error/flow-eval-exception` against this event; the walk's own
  writes stand, as every effect's do."
  [state-before-walk event-id event frame frame-record start-ms]
  (when-let [run-on-db (rf.late-bind/get-fn :flows/run-flows-on-db)]
    (let [state (rf.frame/frame-state-value frame)]
      (when-not (identical? state state-before-walk)
        (let [db (get state rf.frame/app-partition-key)]
          (try
            (let [pass (run-on-db frame db (get state rf.frame/runtime-partition-key))
                  ctx  {:coeffects    {:db db :event event}
                        :rf/flow-pass pass}]
              (when (commit-frame-effects!
                      (if (identical? (:db pass) db) {} {:db (:db pass)})
                      event-id event frame frame-record ctx)
                (publish-flow-memo! ctx)))
            (catch #?(:clj Throwable :cljs :default) e
              (emit-flow-eval-exception! e event event-id frame start-ms))))))))

(defn- commit-and-flow!
  "Settle the cascade: emit any chain, flow or effect-shape failure, else
  commit the flow-augmented effects, publish the flow memo, walk `:fx` and
  settle the flows over whatever the walk wrote (Spec 002 §Drain-loop
  pseudocode). Returns the outcome for the always-on event-emit record:

    :ok          committed, `:fx` walked
    :error       the chain threw, a coeffect supplier or generator threw, or
                 the effect map was malformed
    :flow-error  a flow threw (Spec 013 §Failure semantics)
    :rolled-back candidate validation rejected the transition; dev-only, as
                 that validation is (Spec 010 §Production builds)
    :rejected    a `:boundary? true` handler refused the payload; production
                 too

  Every non-`:ok` outcome installs nothing, publishes nothing and skips `:fx`,
  at no cost because the install is one deferred write. An `:fx` throw does
  not wind back app-db. `:rejected` has the lowest priority, so a chain throw,
  flow throw or rollback during the unwind still wins."
  [final-ctx event-id event frame frame-record fx-overrides envelope start-ms]
  (let [error        (:rf/interceptor-error final-ctx)
        flow-error   (:rf/flow-error final-ctx)
        ;; Verbatim: `commit-fx-effects` does not clean it, so a foreign key or
        ;; a malformed `:fx` is still here to be refused.
        effects      (:effects final-ctx)
        shape-defect (rf.events/effect-map-defect effects event)
        class-defect (rf.elision/classification-effect-defect effects)]
    (cond
      error
      (do
        (emit-pipeline-exception!
          error event-id frame final-ctx start-ms)
        :error)

      flow-error
      (do
        (emit-flow-eval-exception!
          flow-error event event-id frame start-ms)
        :flow-error)

      ;; The envelope check comes first: when the top level is malformed, no
      ;; diagnosis of what is inside it is trustworthy.
      shape-defect
      (do
        (emit-effect-map-shape!
          shape-defect event event-id frame start-ms)
        :error)

      class-defect
      (do
        (emit-classification-effect-shape!
          class-defect event event-id frame start-ms)
        :error)

      ;; `re-frame.cofx` already emitted `:rf.error/coeffect-exception`. Below
      ;; every emitting arm, so a failure the unwind itself produced still
      ;; reports.
      (:rf/cofx-failed? final-ctx)
      :error

      (not (commit-frame-effects!
             effects event-id event frame frame-record final-ctx))
      :rolled-back

      :else
      (do
        (publish-flow-memo! final-ctx)
        (let [state-before-walk (rf.frame/frame-state-value frame)]
          (run-fx-effects! effects frame frame-record fx-overrides envelope)
          (settle-flows-after-walk!
            state-before-walk event-id event frame frame-record start-ms))
        (if (:rf/boundary-rejected? final-ctx) :rejected :ok)))))

;; ---- the boundary-rejection always-on record ------------------------------
;;
;; `:boundary? true` is the one validation Spec 010 keeps in production, so
;; its refusal needs an always-on record or the gate is invisible to whoever
;; opted in. The record omits every payload-derived slot outright rather than
;; scrubbing: the refused payload is attacker-controlled or private by
;; definition, and may carry secrets in keys no schema anticipated. It stays
;; projection-eligible, unlike the redirect-gate rejections, so a refused SSR
;; request answers 400 rather than a silent 200.

(defn- emit-boundary-rejection-record!
  "Fan the always-on, structural-only record for a boundary rejection. The key
  set is closed and pinned by `re-frame.always-on-validation-production-test`;
  any slot added reaches off-box shippers. Goes through
  `dispatch-error-record!` because a refusal is not an event failure. The one
  emit site for both dev and production, so a rejection never produces two
  records. Returns nil."
  [event-id frame]
  (rf.error-emit/dispatch-error-record!
    {:rf.error/id :rf.error/schema-validation-failure
     :where      :event
     :source     :boundary
     :event-id   event-id
     :failing-id event-id
     :schema-id  event-id
     :frame      frame
     :recovery   :no-recovery
     :time       (rf.interop/now-ms)})
  nil)

(defn- emit-pipeline-trailers!
  "Emit the dev-only `:rf.event/run-end` trace, then fan the always-on
  event-emit record and the frame's handled-event sinks (Spec 015 §Frame-owned
  observability sink policy). Both always-on hooks are late-bound, so the
  router has no static dependency on their namespaces.

  `outcome` is `commit-and-flow!`'s keyword. `handler-elapsed-ms` is the
  chain's duration alone, for the Trace panel, not the whole cascade. The
  run-end trace also carries the user-injected coeffects and the per-`:after`
  ctx deltas; the coeffects ride here rather than on the `:rf.fx/do-fx`
  marker, which a `:db`-only handler never emits."
  [event-id event frame outcome start-ms handler-elapsed-ms final-ctx]
  ;; Dev-only projections, gated here so production DCEs them.
  (let [user-cofx    (when rf.interop/debug-enabled?
                       (rf.fx/user-injected-coeffects (:coeffects final-ctx)))
        after-deltas (when rf.interop/debug-enabled?
                       (not-empty (:rf/interceptor-after-deltas final-ctx)))]
    (rf.trace/emit! :rf.event :rf.event/run-end
                 (cond-> {:rf.trace/event-id event-id
                          :rf.event/v        event
                          :frame             frame
                          :rf.trace/phase    :run-end}
                   (some? handler-elapsed-ms)
                   (assoc :rf.event/elapsed-ms handler-elapsed-ms)
                   (some? user-cofx)
                   (assoc :rf.event/coeffects user-cofx)
                   (some? after-deltas)
                   (assoc :rf.event/after-deltas after-deltas)))
    (let [emit-event!     (rf.late-bind/get-fn :event-emit/dispatch-on-event)
      route-handled! (rf.late-bind/get-fn :observability/route-handled-event)]
  (when (or emit-event! route-handled!)
    (let [end-ms     (rf.interop/now-ms)
          elapsed-ms (elapsed-ms-from start-ms end-ms)]
      (when emit-event!
        (emit-event! event event-id frame end-ms outcome elapsed-ms))
      (when route-handled!
        ;; The dispatch-id is dev-only, so production omits `:correlation`.
        (let [effects     (some-> (:effects final-ctx) keys vec)
              dispatch-id (some-> rf.trace/*handler-scope* :dispatch-id)
              correlation (when dispatch-id {:dispatch-id dispatch-id})]
          (route-handled! event
                           event-id
                           frame
                           end-ms
                           outcome
                           elapsed-ms
                           effects
                           correlation))))))))

(defn- run-handler-pipeline!
  "Run the pipeline phases for one resolved event under the handler's trace
  scope, so every trace inside the cascade names the triggering handler
  (Spec 009 §Handler-scope: the in-scope reading at emit time). The dequeued
  envelope rides the event-owner binding, so framework code in a handler body
  can queue children that inherit run propagation."
  [envelope event-id event frame frame-record handler-meta]
  (rf.frame/call-with-event-owner-token
    frame
    (:drain-lock frame-record)
    envelope
    (fn []
      (let [{:keys [full-chain initial-ctx fx-overrides override-summary]}
        (prepare-handler-ctx envelope frame frame-record handler-meta)
        ;; Marks a machine handler's children `:source :machine-action`
        ;; (Spec 009 §Dispatch source as the functional-origin axis). Trace
        ;; provenance only: never copied to a child and never read by
        ;; `insert-envelope`.
        envelope   (cond-> envelope
                     (:rf/machine? handler-meta)
                     (assoc ::rf.fx/machine-emitter? true))]
    (rf.trace/with-handler-scope
      (rf.trace/handler-scope-from-meta :event event-id handler-meta)
      (let [start-ms  (rf.interop/now-ms)
            ;; The post-delivery `:rf.cofx`, generated facts included. The
            ;; epoch record pins it so a strict replay re-presents exactly
            ;; what this run consumed. Dev-only.
            run-cofx  (when rf.interop/debug-enabled?
                        (-> initial-ctx :coeffects :rf.cofx))
            ;; The envelope's own overrides, never the per-frame tier. Fns are
            ;; replaced by the sentinel here, so none reaches the trace or the
            ;; epoch record.
            override-fx   (when rf.interop/debug-enabled?
                            (serializable-fx-overrides (:fx-overrides envelope)))
            override-icpt (when rf.interop/debug-enabled?
                            (not-empty (:interceptor-overrides envelope)))
            _         (rf.trace/emit! :rf.event :rf.event/run-start
                                   ;; Keyed on the runtime value: a
                                   ;; keyword-literal test would not fold away
                                   ;; under Closure.
                                   (cond-> {:rf.trace/event-id event-id
                                            :rf.event/v        event
                                            :frame             frame
                                            :source            (:source envelope)
                                            :rf.trace/trace-id (:trace-id envelope)
                                            :rf.trace/phase    :run-start}
                                     override-summary
                                     (assoc :rf.interceptor/override-summary
                                            override-summary)
                                     (some? run-cofx)
                                     (assoc :rf.event/cofx run-cofx)
                                     (some? override-fx)
                                     (assoc :rf.event/fx-overrides override-fx)
                                     (some? override-icpt)
                                     (assoc :rf.event/interceptor-overrides override-icpt)))
            event-ok? (validate-event! event-id event handler-meta frame)
            final-ctx (run-chain event-id full-chain initial-ctx event-ok?
                                 handler-meta)
            ;; The chain alone, before commit and the `:fx` walk. Dev-only.
            handler-elapsed-ms (when rf.interop/debug-enabled?
                                 (- (rf.interop/now-ms) start-ms))
            outcome   (commit-and-flow! final-ctx event-id event frame
                                        frame-record fx-overrides envelope start-ms)]
        ;; The cause leads the trailers, which carry only its consequence.
        ;; Keyed on the rejection fact rather than the outcome, so a refusal
        ;; whose unwind then threw reports both.
        (when (:rf/boundary-rejected? final-ctx)
          (emit-boundary-rejection-record! event-id frame))
        (emit-pipeline-trailers! event-id event frame outcome
                                start-ms handler-elapsed-ms final-ctx)))))))

(defn- process-event*
  "Resolve the event's handler and run its pipeline. A destroyed frame, a
  missing handler or an ambiguous one emits its error and returns, and the
  drain continues."
  [envelope frame-record]
  (let [{:keys [event frame]} envelope
        event-id              (first event)]
    (cond
      (nil? frame-record)
      (handle-frame-destroyed! event frame)

      :else
      (let [handler-meta (or (resolve-handler event-id)
                             (resolve-unhandled event frame))]
        (cond
          (nil? handler-meta)
          (rf.router.diagnostics/handle-no-handler! event-id event frame)

          (::ambiguous handler-meta)
          (rf.router.diagnostics/handle-ambiguous-handler!
            (::ambiguous handler-meta) event-id event frame)

          :else
          (run-handler-pipeline! envelope event-id event frame
                                 frame-record handler-meta))))))

(defn- process-event!
  "Run `process-event*` under the cascade's bindings: the trace scope
  carrying `:dispatch-id` and `:call-site` (Spec 009 §Dispatch correlation);
  `*current-frame*` set to the envelope's frame, so a synchronous dispatch or
  subscribe in the handler body targets that frame (Spec 002 §Dispatches
  issued from inside a handler body); and registration resolution through the
  frame's own image generation (Spec 002 §Image resolution and composition).
  None of these survive an async escape; use `capture-frame` or `:fx` there.
  A stale drain whose `owner-token` no longer matches the frame's record runs
  nothing."
  [envelope owner-token]
  (rf.frame/call-with-event-owner-token
    (:frame envelope)
    owner-token
    (fn []
      (rf.trace/with-dispatch-id+call-site (:dispatch-id envelope) (:call-site envelope)
        (binding [rf.frame/*current-frame*          (:frame envelope)
                  rf.frame/*current-frame-bound-in* (rf.frame/rendering-component)]
          ;; Not debug-gated: a frame made with `:images` seals its generation
          ;; in production too, and a skipped flush would leave every
          ;; later-registered handler unresolvable. Looked up by keyword, so a
          ;; bundle that never constructs a frame still DCEs the graph.
          (when-let [flush! (rf.late-bind/get-fn
                              :live-frame/flush-projection!)]
            (flush!))
          ;; Resolve through the dequeuing incarnation's own record, never the
          ;; bare id: a stale drain never runs this envelope in a same-id
          ;; successor.
          (let [active-record (rf.frame/frame (:frame envelope))]
            (when (and active-record
                       (identical? owner-token (:drain-lock active-record)))
              (rf.live-frame/call-with-record-resolution
                active-record
                #(process-event* envelope active-record)))))))))

(def ^:private drain-depth-default
  ;; The most events one family may dequeue: an external event or a
  ;; `dispatch-sync` seed plus everything it dispatches, transitively. Sized
  ;; so a few hundred independent children settle while a runaway cycle
  ;; still halts quickly (Spec 002 §Run-to-completion dispatch (drain
  ;; semantics)).
  1000)

(def ^:private cycle-evidence-depth
  ;; The settled-id ring attached as cycle evidence to the always-on
  ;; `:rf.error/drain-depth-exceeded` record. A runaway is almost always a
  ;; short cycle, so the last few ids name it. Ids only, never args.
  16)

(defn- handle-depth-exceeded!
  "Halt a family that reached the depth limit. Its next event, at the head of
  the internal lane, never runs and the internal lane is discarded; external
  input runs on under a fresh budget. Already-settled events keep their own
  `:ok` epochs, as a family is an ordering unit, not a transaction (Spec 002
  §Drain versus event — the epoch unit).

  Commits a `:halted-depth` epoch record (Spec-Schemas §`:rf/epoch-record`)
  and fans a structural-only always-on record carrying `tail-event-ids` as
  cycle evidence: under `goog.DEBUG=false` the dev trace is gone, and a silent
  runaway compounds (Spec 009 §The promotion criterion)."
  [frame-id router depth last-event tail-event-ids]
  (let [{queue :internal} @router
        queue-size      (count queue)
        ;; No `last-event` fallback: it would name a settled `:ok` event as
        ;; the refused one. With an empty lane `halting-event` is nil and
        ;; `commit-halt-record!` declines, which beats a phantom record that
        ;; blocks `restore-epoch!`.
        halting-envelope (peek queue)
        halting-event   (:event halting-envelope)
        ;; The halting event's causal time, so even this marker's
        ;; `:committed-at` is replayable rather than an ambient read.
        halting-time-ms (-> halting-envelope :rf.cofx :rf/time-ms)
        ;; A fallback: `commit-halt-record!` prefers the last `:ok` record's
        ;; `:frame-state-after`.
        fs-now          (rf.frame/frame-state-value frame-id)
        last-event-id   (when (vector? last-event) (first last-event))
        dropped-event-ids (into []
                                (comp (keep :event)
                                      (map #(when (vector? %) (first %))))
                                queue)
        ;; The last event's id, never its args: `:halt-reason` egresses
        ;; verbatim.
        halt-reason     {:operation     :rf.error/drain-depth-exceeded
                         :depth         depth
                         :queue-size    queue-size
                         :last-event-id last-event-id}]
    (rf.error-emit/dispatch-error-record!
      {:rf.error/id       :rf.error/drain-depth-exceeded
       :frame             frame-id
       :time              (rf.interop/now-ms)
       :depth             depth
       :queue-size        queue-size
       :last-event-id     last-event-id
       :tail-event-ids    tail-event-ids
       :dropped-event-ids dropped-event-ids
       :rollback?         false
       :recovery          :no-recovery})
    ;; The explicit gate is mandatory: the live always-on call above stops
    ;; Closure folding this fn on the emit's nil return, so without the gate
    ;; the `str` / `pr-str` prose would ship in production.
    (when rf.interop/debug-enabled?
      (rf.trace/emit-error! :rf.error/drain-depth-exceeded
                         {:frame             frame-id
                          :depth             depth
                          :queue-size        queue-size
                          :last-event        last-event
                          :last-event-id     last-event-id
                          :tail-event-ids    tail-event-ids
                          :dropped-event-ids dropped-event-ids
                          :reason            (str "Drain depth limit (" depth
                                                  ") exceeded — likely a dispatch"
                                                  " loop. Cycle (last settled ids): "
                                                  (pr-str tail-event-ids))
                          :rollback?         false
                          :recovery          :no-recovery}))
    ;; The internal lane only, so queued external input still runs. `router` is
    ;; this incarnation's private state, so the clear never reaches a same-id
    ;; successor.
    (swap! router assoc :internal rf.interop/empty-queue)
    ;; The halting event never ran, so classify its trigger here, inside the
    ;; frame's resolution scope, as its run-start trace would have.
    (when-let [commit-halt! (rf.late-bind/get-fn :epoch/commit-halt-record!)]
      (let [redact  (rf.late-bind/get-fn
                      :classification/redact-event-by-registration)
            trigger (if redact
                      (rf.live-frame/call-with-frame-resolution
                        frame-id #(redact halting-event))
                      halting-event)]
        (commit-halt! frame-id fs-now halting-time-ms :halted-depth
                      halt-reason trigger)))))

(defn- settle-event-epoch!
  "Commit the just-completed event's epoch (Spec 002 §Drain versus event — the
  epoch unit). The capture buffer holds exactly this event's cascade, a
  machine macrostep included. The settling `:dispatch-id` lets the harvest
  drop a rejected dispatch's own error traces, so a no-handler or
  destroyed-frame exit commits no fake `:ok` record. `committed-at` is the
  event's causal `:rf/time-ms`, not an ambient clock read."
  [frame-id frame-state-before frame-state-after committed-at
   settling-dispatch-id]
  (when-let [settle! (rf.late-bind/get-fn :epoch/settle!)]
    (settle! frame-id frame-state-before frame-state-after committed-at
             settling-dispatch-id)))

;; ---- drain-loop! phases ---------------------------------------------------
;;
;; `drain-loop!` marks the drainer, runs `run-one-pass!` to fixed point, then
;; releases: `force-release-on-halt!` after a destroy halt, else
;; `try-release-on-empty!`, which re-checks both lanes under the lock so a
;; racing submitter is never orphaned.

(defn- mark-drainer!
  "Stamp `:in-drain?` with this thread, so the dispatch-sync guard can tell
  same-thread nesting from a concurrent caller. CLJS is single-threaded, so
  `true` serves."
  [router]
  (swap! router assoc :in-drain? #?(:clj (Thread/currentThread) :cljs true)))

(defn- clear-drainer!
  "Clear `:in-drain?`; the finally-partner of `mark-drainer!`."
  [router]
  (swap! router assoc :in-drain? nil))

(defn- take-event!
  "Pop one envelope, internal lane first. Returns `[envelope external?]`, or
  nil when both lanes are empty; `external?` opens a new family, where
  `run-one-pass!` resets the depth budget.

  One `swap-vals!` per dequeue, reading the head from the pre-swap value. The
  head is stable against enqueues: only the drainer's own thread writes the
  internal lane's head, and other submitters only append to the external
  lane. The `:drain-lock` makes this atomic against other drainers (Spec 002
  §Single-drainer invariant)."
  [router]
  (let [[{:keys [internal queue]} _]
        (swap-vals! router
                    (fn [{:keys [internal queue] :as r}]
                      (cond
                        (seq internal) (assoc r :internal (pop internal))
                        (seq queue)    (assoc r :queue (pop queue))
                        :else          r)))]
    (cond
      (seq internal) [(peek internal) false]
      (seq queue)    [(peek queue) true])))

(defn- lanes-empty?
  "True when neither lane of the router state `state` holds an envelope."
  [state]
  (and (empty? (:internal state)) (empty? (:queue state))))

(defn- queued-count
  "The number of envelopes across both lanes of the router state `state`."
  [state]
  (+ (count (:internal state)) (count (:queue state))))

(defn- call-with-event-in-flight
  "Run `f`, one dequeued event's processing, with the router's `:in-event`
  naming this host thread, restoring the prior value afterwards.

  `insert-envelope` reads the slot to send a dispatch made inside this event
  to the internal lane. Neither the dynamic event-owner binding nor
  `:scheduled?` / `:in-drain?` can answer that: bindings are conveyed into
  futures and `bound-fn`s on other threads, and the flags stay set across the
  whole drain. On CLJS the slot is `true` while the event runs."
  [router f]
  (let [prior (:in-event @router)]
    (swap! router assoc :in-event #?(:clj (Thread/currentThread) :cljs true))
    (try
      (f)
      (finally
        (swap! router assoc :in-event prior)))))

(defn- in-event-here?
  "True when the router state `state` records an event of its frame in
  flight on THIS host thread (see `call-with-event-in-flight`)."
  [state]
  #?(:clj  (identical? (:in-event state) (Thread/currentThread))
     :cljs (true? (:in-event state))))

(defn- handle-drain-interrupted!
  "Destruction owns the frame before the next dequeue: drop both lanes once,
  clear `:scheduled?`, and emit one `:rf.frame/drain-interrupted` carrying
  `:dropped-count` (Spec 002 §Destroy). For a pending destroy this is the
  destroying event's boundary, so its own descendants are dropped too.
  Returns `{:dropped-count n :trigger <next envelope or nil>}` when this call
  reported, nil when an earlier callback already had; the pending destroy's
  teardown commits the `:halted-destroy` record from it."
  [frame-id router]
  (let [report (volatile! nil)]
    ;; Claim can leave more than one already-captured scheduler callback, and a
    ;; post-claim submit may capture another after claim clears `:scheduled?`.
    ;; Compare/mark inside the router swap so all callbacks for this exact
    ;; router generation still clear rejected work, but only the first winner
    ;; consumes the combined evidence and emits.
    (swap! router
           (fn [{:keys [destroy-claim-dropped-count
                        destroy-claim-report-emitted?
                        internal queue]
                 :as state}]
             (let [dropped (+ (queued-count state)
                              (or destroy-claim-dropped-count 0))]
               (when-not destroy-claim-report-emitted?
                 (vreset! report {:dropped-count dropped
                                  :trigger       (if (seq internal)
                                                   (peek internal)
                                                   (peek queue))}))
               (cond-> (-> state
                           (assoc :queue      rf.interop/empty-queue
                                  :internal   rf.interop/empty-queue
                                  :scheduled? false)
                           (dissoc :destroy-claim-dropped-count))
                 (not destroy-claim-report-emitted?)
                 (assoc :destroy-claim-report-emitted? true)))))
    (when-some [{:keys [dropped-count] :as evidence} @report]
      ;; A stale drainer can reach this seam after A's registry slot has been
      ;; reused by B. The lifecycle report is still globally observable, but
      ;; it must never enter B's bare-id epoch buffer.
      (rf.trace/call-with-structural-delivery
        #(rf.trace/emit! :rf.frame :rf.frame/drain-interrupted
                      {:frame         frame-id
                       :dropped-count dropped-count}))
      evidence)))

(defn- classified-trigger
  "The event vector of `envelope`, an event the drain is about to refuse,
  classified by its registration inside `frame-id`'s resolution scope. The
  event never ran, so no run-start trace classified it."
  [frame-id envelope]
  (let [event  (:event envelope)
        redact (rf.late-bind/get-fn
                 :classification/redact-event-by-registration)]
    (if (and redact event)
      (rf.live-frame/call-with-frame-resolution frame-id #(redact event))
      event)))

(defn- halt-at-pending-destroy!
  "The destroying event's boundary for a pending destroy of `frame-record`'s
  incarnation (Spec 002 §Destroy): drop both lanes and report them in one
  `:rf.frame/drain-interrupted`, then run the claimed teardown, `:on-destroy`
  first, against the db the destroying event committed. `last-event` is the
  destroying event's vector, nil when a throw unwound the drain before it
  settled. The caller still holds the frame's drain serialization."
  [frame-id frame-record router last-event]
  (let [token    (:drain-lock frame-record)
        evidence (handle-drain-interrupted! frame-id router)
        dropped  (:dropped-count evidence 0)
        trigger  (:trigger evidence)
        halt     (when (and rf.interop/debug-enabled? (pos? dropped) trigger)
                   {:frame-state   (rf.frame/frame-record-state-value frame-record)
                    :committed-at  (-> trigger :rf.cofx :rf/time-ms)
                    :trigger-event (classified-trigger frame-id trigger)
                    :dropped-count dropped
                    :last-event-id (when (vector? last-event) (first last-event))})]
    (rf.frame/run-pending-destroy! frame-id token halt)))

(defn- run-one-pass!
  "Process events to fixed point, internal lane first. Returns `::settled`
  when both lanes are empty, or `::halt` when destruction owns the frame.

  The depth budget is per family (Spec 002 §Run-to-completion dispatch (drain
  semantics)) and restarts at each external dequeue; a family that exhausts
  it halts, and the pass continues with the next external event. The
  destruction check runs before every dequeue, so a destroy requested during
  the event that just settled stops the drain at that boundary. Each event
  gets its own epoch from its own frame-state snapshots (Spec 002 §Drain
  versus event — the epoch unit); the before-snapshot is also bound to
  `rf.frame/*run-frame-state-before*`, marking the run as open."
  [frame-id frame-record router drain-depth allowed-destroy-token]
  ;; `depth` counts the family's settled events, its root included;
  ;; `tail-ring` holds their last K ids as cycle evidence. Both restart with
  ;; each family.
  (loop [depth      0
         last-event nil
         tail-ring  []]
    (cond
      ;; Before the depth check: a destroy drops both lanes, so the family's
      ;; internal work is reported in the one `:rf.frame/drain-interrupted`
      ;; rather than as a depth halt.
      (and (rf.frame/frame-disposed-for-drain? frame-id)
           ;; Only the teardown cascade presents the claimed token, draining an
           ;; isolated queue; ordinary drains pass nil. If the marker no longer
           ;; names this token, even that cascade halts.
           (not (and (some? allowed-destroy-token)
                     (rf.frame/frame-incarnation-closing?
                       frame-id allowed-destroy-token))))
      (do (if (rf.frame/pending-destroy? frame-id (:drain-lock frame-record))
            (halt-at-pending-destroy! frame-id frame-record router last-event)
            (handle-drain-interrupted! frame-id router))
          ::halt)

      ;; Peek the internal lane before halting. `depth` counts settled events,
      ;; so without the peek a clean family of exactly `drain-depth` events
      ;; would halt as a runaway, naming its own settled event as the refused
      ;; one. The external lane belongs to the next family. Race-free: only
      ;; the drainer enqueues internally or pops, so the lane is still
      ;; non-empty in `handle-depth-exceeded!`.
      (and (>= depth drain-depth)
           (seq (:internal @router)))
      ;; The pass continues; the destruction check above still stops it if a
      ;; depth-error listener destroyed the frame.
      (do (handle-depth-exceeded! frame-id router depth last-event tail-ring)
          (recur 0 nil []))

      :else
      (if-let [[envelope external?] (take-event! router)]
        ;; An external dequeue opens a new family.
        (let [depth       (if external? 0 depth)
              tail-ring   (if external? [] tail-ring)
              owner-token (:drain-lock frame-record)
              fs-before   (rf.frame/frame-record-state-value frame-record)
              time-ms     (-> envelope :rf.cofx :rf/time-ms)]
          ;; Marks this event's run as open on the stack
          ;; (`rf.frame/guard-open-drain!`).
          (binding [rf.frame/*run-frame-state-before* fs-before]
            (call-with-event-in-flight
              router
              #(process-event! envelope owner-token)))
          (settle-event-epoch! frame-id fs-before
                               (rf.frame/frame-record-state-value frame-record)
                               time-ms (:dispatch-id envelope))
          (let [event    (:event envelope)
                event-id (when (vector? event) (first event))
                ring     (conj tail-ring event-id)
                ring     (if (> (count ring) cycle-evidence-depth)
                           (subvec ring (- (count ring) cycle-evidence-depth))
                           ring)]
            (recur (inc depth) event ring)))
        ::settled))))

(defn- force-release-on-halt!
  "Release the drain-lock after `::halt`; the lanes are already dropped.
  Locked to serialize against `ensure-drain-scheduled!`'s flag read. A
  reentrant drain (`hold-lock?`) leaves the lock to the outer cold section."
  [router drain-lock hold-lock?]
  (locking router
    (when-not hold-lock?
      (reset! drain-lock false))))

(defn- try-release-on-empty!
  "Under the submitters' lock, re-check both lanes. Empty: clear `:scheduled?`
  and release the lock together, so a submitter sees both false and
  schedules a fresh drain; returns false. Non-empty: a submitter raced in, so
  return true for another pass. This is the orphan-prevention seam. A
  reentrant drain (`hold-lock?`) keeps the lock for the outer cold section."
  [router drain-lock hold-lock?]
  (locking router
    (let [state @router]
      (if (lanes-empty? state)
        (do (swap! router assoc :scheduled? false)
            (when-not hold-lock?
              (reset! drain-lock false))
            false)
        true))))

(defn- drain-loop!
  "The drain body; the caller holds `:drain-lock`. Runs passes until
  `try-release-on-empty!` finds both lanes empty or a destroy halts the
  drain. `hold-lock?` is true only for `drain-reentrant!`, whose caller
  already owns the lock through a cold `call-serialized-with-drain!` section
  and releases it there."
  [frame-id frame-record router drain-lock drain-depth hold-lock?]
  (loop []
    (let [outcome (try
                    (mark-drainer! router)
                    (run-one-pass! frame-id frame-record router drain-depth nil)
                    (catch #?(:clj Throwable :cljs :default) t
                      ;; A throw escaping the drain ends the event in flight
                      ;; too, so a destroy it requested reaches its boundary
                      ;; here, while this thread still holds the drain. The
                      ;; teardown is best-effort and never masks `t`.
                      (when (rf.frame/pending-destroy? frame-id drain-lock)
                        (try
                          (halt-at-pending-destroy! frame-id frame-record router nil)
                          (catch #?(:clj Throwable :cljs :default) _ nil)))
                      (throw t))
                    (finally
                      (clear-drainer! router)))]
      (case outcome
        ::halt    (force-release-on-halt! router drain-lock hold-lock?)
        ::settled (when (try-release-on-empty! router drain-lock hold-lock?)
                    (recur))))))

(declare drain-try!)

(defn- drain-emergency-release!
  "Panic path for a throw escaping `drain-loop!`: clear the router flags and
  release the lock so the frame is not stuck; the caller rethrows. The
  failing event was already popped, so it is not retried. Other queued work
  would strand with `:scheduled?` false, so a live, non-closing frame with
  work left is re-armed with one fresh `drain-try!`."
  [frame-id frame-record router drain-lock]
  (let [re-arm? (locking router
                  (let [re-arm? (boolean
                                  (and (not (lanes-empty? @router))
                                       (rf.frame/frame-incarnation-live? frame-id drain-lock)
                                       (not (rf.frame/frame-incarnation-closing?
                                              frame-id drain-lock))))]
                    (swap! router assoc :scheduled? re-arm? :in-drain? nil)
                    (reset! drain-lock false)
                    re-arm?))]
    (when re-arm?
      (rf.interop/next-tick
        (fn [] (drain-try! frame-id frame-record))))))

(defn- drain-try!
  "Async drain entry, from `rf.interop/next-tick`. Losing the CAS leaves the
  queue to the active drainer, whose release re-checks under the lock.
  Listener delivery is deferred until the lock is down, so listener code
  never runs under it or beside a sibling frame's drain."
  [frame-id frame-record]
  (rf.trace/call-with-deferred-listener-delivery
    (fn []
      (let [drain-lock  (:drain-lock frame-record)
            router      (:router frame-record)
            drain-depth (get (:config frame-record) :drain-depth drain-depth-default)]
        (when (compare-and-set! drain-lock false true)
          ;; Exact-target revalidation belongs INSIDE the acquired serialization
          ;; boundary.  A scheduled callback for obsolete incarnation A must never
          ;; re-resolve the bare id and become an eager drain of same-id B.
          (if (rf.frame/frame-incarnation-live? frame-id drain-lock)
            (try
              (drain-loop! frame-id frame-record router drain-lock drain-depth false)
              (catch #?(:clj Throwable :cljs :default) t
                (drain-emergency-release! frame-id frame-record router drain-lock)
                (throw t)))
            (reset! drain-lock false)))))))

(defn- drain-block!
  "Synchronous drain entry for `dispatch-sync!`. On CAS loss it spins until
  the active drainer releases, because dispatch-sync must settle before it
  returns. `under-lock-fn`, the seed push to the head of the internal lane,
  runs only once the lock is held: outside it, the push would interleave with
  the active drainer's peek and pop. Returns true iff `under-lock-fn` ran,
  false when the target incarnation was lost during the wait. Listener
  delivery is deferred and flushed before return."
  [frame-id frame-record under-lock-fn]
  (rf.trace/call-with-deferred-listener-delivery
    (fn []
      (let [drain-lock  (:drain-lock frame-record)
            router      (:router frame-record)
            drain-depth (get (:config frame-record) :drain-depth drain-depth-default)]
        ;; The wait ends once other threads stop feeding the frame; CLJS
        ;; acquires on the first attempt.
        (loop []
          (when-not (compare-and-set! drain-lock false true)
            #?(:clj (Thread/yield))
            (recur)))
        ;; The caller accepted THIS record.  Revalidate it only after acquiring its
        ;; lock; never re-resolve by id after a wait in which A may become B.
        (if (rf.frame/frame-incarnation-live? frame-id drain-lock)
          (try
            (under-lock-fn)
            (drain-loop! frame-id frame-record router drain-lock drain-depth false)
            true
            (catch #?(:clj Throwable :cljs :default) t
              (drain-emergency-release! frame-id frame-record router drain-lock)
              (throw t)))
          (do (reset! drain-lock false)
              false))))))

(defn- drain-reentrant!
  "Synchronous drain for a thread that already holds the frame's drain
  serialization through a cold `call-serialized-with-drain!` section, where
  `drain-block!` would deadlock on itself. Seeds and drains without acquiring
  or releasing the lock; on a throw it clears the flags and leaves the lock
  to the cold section. Returns true iff `under-lock-fn` ran, nil when a
  same-id replacement invalidated the target."
  [frame-id frame-record under-lock-fn]
  (let [drain-lock  (:drain-lock frame-record)
        router      (:router frame-record)
        drain-depth (get (:config frame-record) :drain-depth drain-depth-default)]
    ;; The outer cold section owns THIS record's lock.  Same-id replacement
    ;; invalidates the target; it does not retarget the synchronous dispatch.
    (when (rf.frame/frame-incarnation-live? frame-id drain-lock)
      (try
        (under-lock-fn)
        (drain-loop! frame-id frame-record router drain-lock drain-depth true)
        true
        (catch #?(:clj Throwable :cljs :default) t
          (locking router
            (swap! router assoc :scheduled? false :in-drain? nil))
          (throw t))))))

(declare insert-envelope)

(defn- ensure-drain-scheduled!
  "Enqueue `envelope` into the target incarnation's router, arming the async
  drain when this call flips `:scheduled?`. Returns true iff the envelope was
  enqueued; false when the incarnation guard fenced it out, which lets
  `dispatch!` emit exactly once for a captured op that lost its incarnation."
  [frame-id frame-record router envelope]
  (let [outcome
        (locking router
          ;; Target-liveness and enqueue linearize against destroy claim's
          ;; queue cutoff under this same router monitor.
          (when (rf.frame/frame-incarnation-live?
                  frame-id (:drain-lock frame-record))
            (let [{:keys [scheduled?]} @router]
              (swap! router
                     (fn [state]
                       (-> state
                           (insert-envelope envelope)
                           (assoc :scheduled? true))))
              {:enqueued? true :schedule? (not scheduled?)})))]
    (when (:schedule? outcome)
      ;; The scheduled callback belongs to the exact target record that
      ;; accepted the envelope. Never let an obsolete A callback become
      ;; an eager drain of a fresh same-id B.
      (rf.interop/next-tick
        (fn []
          (drain-try! frame-id frame-record))))
    (boolean (:enqueued? outcome))))

(defn- emit-dispatched-trace!
  "Emit `:rf.event/dispatched` for `envelope`, with dispatch correlation,
  `:origin`, `:source` and `:rf.trace/call-site` (Spec 009 §Dispatch
  correlation). Suppressed when the target handler is marked
  `:rf.trace/no-emit?`, read from its registration because no handler scope
  exists yet at enqueue. Dev-only."
  [envelope sync?]
  (let [event        (:event envelope)
        event-id     (when (vector? event) (first event))
        ;; Resolve through the target frame's own view, not a bare lookup:
        ;; an image-loaded frame's inline handler exists only in its
        ;; generation, and a generation-blind lookup would miss its
        ;; `:rf.trace/no-emit?`.
         handler-meta (when event-id
                        (try
                          (rf.live-frame/call-with-frame-resolution
                            (:frame envelope)
                            (fn [] (rf.registrar/lookup :event event-id)))
                          (catch #?(:clj Throwable :cljs :default) e
                            ;; An ambiguous id has no meta to gate on: it is
                            ;; reported once, where the event is processed.
                            (when-not (rf.registrar/ambiguous-registration? e)
                              (throw e)))))
         no-emit?     (rf.trace/no-emit?-from-meta handler-meta)]
    (when-not no-emit?
      (rf.trace/with-call-site (:call-site envelope)
        (rf.trace/emit! :rf.event :rf.event/dispatched
                     ;; The outermost `if` is the Closure-foldable gate: a
                     ;; `cond->` test on a keyword does not fold, so the
                     ;; `:rf.cofx` slot would survive `:advanced` +
                     ;; `goog.DEBUG=false`.
                     (cond-> (if rf.interop/debug-enabled?
                               {:rf.event/v         event
                                :frame              (:frame envelope)
                                :rf.event/origin    (:origin envelope)
                                :source             (:source envelope)
                                :rf.event/sync?     sync?
                                :rf.cofx            (:rf.cofx envelope)}
                               {:rf.event/v         event
                                :frame              (:frame envelope)
                                :rf.event/origin    (:origin envelope)
                                :source             (:source envelope)
                                :rf.event/sync?     sync?})
                       (:dispatch-id envelope)
                       (assoc :rf.trace/dispatch-id (:dispatch-id envelope))
                       (:parent-dispatch-id envelope)
                       (assoc :rf.trace/parent-dispatch-id (:parent-dispatch-id envelope))
                       (:source-detail envelope)
                       (assoc :rf.event/source-detail (:source-detail envelope))
                       (:step-index envelope)
                       (assoc :rf.frame/init-step-index (:step-index envelope))))))))

(defn- head-insert
  "`q` (a PersistentQueue of envelopes, or nil) with `envelope` at its head,
  dequeued next.

  PersistentQueue has no head-push, so the lane is rebuilt. Only the
  `dispatch-sync` seed uses this, and a `dispatch-sync` call pushes exactly
  one seed, so there is no sibling a head-push could reverse."
  [q envelope]
  (into rf.interop/empty-queue (cons envelope q)))

(defn- lane-conj
  "FIFO append onto a lane that may not exist yet."
  [q envelope]
  (conj (or q rf.interop/empty-queue) envelope))

(defn- insert-envelope
  "`state` with `envelope` in its lane. The one ordering rule, shared by both
  enqueue paths so they cannot drift (Spec 002 §Run-to-completion dispatch
  (drain semantics)):

  1. A dispatch made synchronously inside this frame's in-flight event, on
     this host thread, joins the back of the internal lane.
  2. Every other dispatch, `:dispatch-later` at 0 ms included, joins the back
     of the external lane.

  Lanes change order only: every envelope is still its own event and epoch."
  [state envelope]
  (if (in-event-here? state)
    (update state :internal lane-conj envelope)
    (update state :queue lane-conj envelope)))

(defn- enqueue-envelope!
  "Insert `envelope` into the lane of `router` that `insert-envelope` gives
  it."
  [router envelope]
  (swap! router insert-envelope envelope))

;; Private authority for the synchronous `:on-destroy` event and the
;; same-frame child dispatches it intentionally emits. The cascade drains an
;; isolated router, never the dying frame's real queue. The value carries BOTH
;; the incarnation claim token and the actual host thread that entered the
;; cascade: JVM `bound-fn` may convey dynamic bindings to an executor, but it
;; cannot make that executor thread identical to `:owner`. A callback that
;; outlives teardown also loses authority when the claim is compare-removed.
(def ^:dynamic ^:private *frame-destroy-cascade* nil)

(defn- frame-destroy-cascade-router
  "Return the isolated teardown router when this call is an authorised
  same-frame child dispatch; nil for every ordinary dispatch."
  [frame-id]
  (when-let [{cascade-frame :frame
              token         :token
              router        :router
              owner         :owner} *frame-destroy-cascade*]
    (when (and (= cascade-frame frame-id)
               #?(:clj  (identical? owner (Thread/currentThread))
                  :cljs (true? owner))
               (rf.frame/frame-incarnation-closing? frame-id token))
      router)))

(defn dispatch!
  "Enqueue `event` on its target frame: into the internal lane when made
  synchronously inside that frame's in-flight event, else the external lane
  (`insert-envelope`; Spec 002 §Run-to-completion dispatch (drain
  semantics)). A destroyed, unknown or superseded target recovers and emits
  `:rf.error/frame-destroyed`. Returns nil.

  The `dispatch` macro expands to this, stamping `:rf.trace/call-site` onto
  `opts`, and the CLJS `dispatch` value aliases it. For the canonical event
  shape see Conventions §Canonical event-vector shape."
  ([event] (dispatch! event {}))
  ([event opts]
   (when-let [envelope  (build-envelope event opts)]
    (let [
         frame-id       (:frame envelope)
         frame-record   (rf.frame/frame frame-id)
         target-token   (:drain-lock frame-record)
         ;; nil for every ordinary dispatch, which leaves the fence below
         ;; inert.
         expected-incarnation (:rf.frame/expected-incarnation envelope)
         ;; A captured op's pinned incarnation is dead on every rejection
         ;; below, so its emit withholds the frame route; an ordinary
         ;; address-directed dispatch names its frame by id and routes.
         captured?      (some? expected-incarnation)
         cascade-router (frame-destroy-cascade-router frame-id)]
     (cond
   (nil? frame-record)
   ;; Recover-but-emit. The bound call-site serves the dev trace; the
   ;; always-on record takes its coord from the registration.
   (rf.trace/with-call-site (:call-site envelope)
     (emit-frame-destroyed! (first event) event (:frame envelope)
                            :dispatch (not captured?)))

   ;; A captured op pinned to incarnation A resolved a same-id successor B.
   ;; `target-token` comes off the record we would enqueue into, so the
   ;; check is fused with target consumption and A's authority never
   ;; reaches B.
   (and (some? expected-incarnation)
        (not (identical? expected-incarnation target-token)))
   (rf.trace/with-call-site (:call-site envelope)
     (emit-frame-destroyed! (first event) event (:frame envelope) :dispatch false))

   cascade-router
   ;; Cleanup descendants stay on the private teardown queue. No scheduler
   ;; is involved, and no ordinary/racing envelope can join this cascade.
   (do (emit-dispatched-trace! envelope false)
       (enqueue-envelope! cascade-router envelope))

   :else
   (let [router    (:router frame-record)
         enqueued? (do (emit-dispatched-trace! envelope false)
                       (ensure-drain-scheduled! frame-id frame-record router
                                                envelope))]
     ;; A captured dispatch that lost its incarnation between the token check
     ;; and the enqueue: nothing was enqueued, so this is the one emit. An
     ;; ordinary dispatch stays silent on that benign race.
     (when (and captured?
                (not enqueued?)
                (not (rf.frame/frame-incarnation-live? frame-id target-token)))
       (rf.trace/with-call-site (:call-site envelope)
         (emit-frame-destroyed! (first event) event (:frame envelope)
                                :dispatch false)))))))
   nil))

(defn ^:no-doc dispatch-sync-with-id!
  "`dispatch-sync!`, returning the `:dispatch-id` of the dispatch the router
  ran: nil when it refused the dispatch (a destroyed or superseded target, a
  same-frame reentry), and always nil in production, which mints no id.
  Internal. Tool-Pair replay names its own epoch record by this id;
  `dispatch-sync!` discards it, so the public return value stays nil."
  ([event opts]
   (when-let [envelope (build-envelope event opts)]
    (let [frame-record (rf.frame/frame (:frame envelope))
       target-token (:drain-lock frame-record)
       target-live? #(rf.frame/frame-incarnation-live?
                        (:frame envelope) target-token)
       ;; Already debug-gated in `build-envelope`, so the keyword is not
       ;; read a second time.
       call-site    (:call-site envelope)
       expected-incarnation (:rf.frame/expected-incarnation envelope)
       captured?    (some? expected-incarnation)
       ;; Reentry into the SAME frame's running drain: the sync flag, or this
       ;; thread is the drainer. A different thread mid-drain is a concurrent
       ;; caller, which `drain-block!` waits out.
       nested-sync?
       (when frame-record
         (let [router-state @(:router frame-record)
               same-thread-drain?
               #?(:clj  (identical? (:in-drain? router-state) (Thread/currentThread))
                  :cljs (true? (:in-drain? router-state)))]
           (or (:in-sync-drain? router-state) same-thread-drain?)))
       ;; This thread owns the drain serialization through a cold
       ;; `call-serialized-with-drain!` section without being the drainer,
       ;; e.g. a Tool-Pair state write. It must re-enter the drain directly,
       ;; since `drain-block!` would deadlock on its own lock. Checked only
       ;; when not `nested-sync?`, so the drainer axis wins.
       reentrant-cold?
       (when (and frame-record (not nested-sync?))
         (let [holder @(:serialized-holder frame-record)]
           #?(:clj  (identical? holder (Thread/currentThread))
              :cljs (true? holder))))]
   (cond
     (nil? frame-record)
     ;; Recover-but-emit; a dispatch-sync shares the dispatch event realm.
     (rf.trace/with-call-site call-site
       (emit-frame-destroyed! (first event) event (:frame envelope)
                              :dispatch-sync (not captured?))
       nil)

     ;; As in `dispatch!`, and ahead of the drain clauses so a superseded
     ;; capture never enters the successor's drain.
     (and (some? expected-incarnation)
          (not (identical? expected-incarnation target-token)))
     (rf.trace/with-call-site call-site
       (emit-frame-destroyed! (first event) event (:frame envelope) :dispatch-sync false)
       nil)

     nested-sync?
     ;; The event would interleave with the outer handler's run (Spec 002
     ;; §Calling `dispatch-sync` *inside* a handler is an error).
     (rf.trace/with-call-site call-site
       ;; `:rf.event/v` is the schema-required key, not a bare `:event`.
       (rf.trace/emit-error! :rf.error/dispatch-sync-in-handler
                          {:frame      (:frame envelope)
                           :rf.event/v event
                           :reason     "dispatch-sync called from inside a running drain. Use dispatch (the queued form) instead so the event runs after the current drain settles."
                           :recovery   :no-recovery})
       nil)

     :else
     (let [router (:router frame-record)]
       ;; Another frame mid-drain: warn but proceed (Spec 002 §Cross-frame
       ;; `dispatch-sync` during a sibling drain warns but proceeds). Dev-only.
       (when rf.interop/debug-enabled?
         (when-let [other-frame-id (rf.router.diagnostics/find-other-draining-frame-id (:frame envelope))]
           (rf.router.diagnostics/emit-cross-frame-warning! (:frame envelope) other-frame-id event)))
       (let [drained?
       (do (emit-dispatched-trace! envelope true)
        (when (target-live?)
         (try
         ;; The seed push runs inside `drain-block!`'s lock window, so it
         ;; cannot interleave with another drainer's peek and pop.
         ;; `:in-sync-drain?` blocks a concurrent dispatch-sync, and
         ;; `:scheduled?` suppresses async scheduling until the finally. A
         ;; cold serialization holder re-enters through `drain-reentrant!`,
         ;; which neither acquires nor releases the lock.
         (let [seed-push (fn []
                           (swap! router (fn [{:keys [internal] :as r}]
                                           (assoc r
                                                  :internal       (head-insert internal envelope)
                                                  :scheduled?     true
                                                  :in-sync-drain? true))))]
           (if reentrant-cold?
             (drain-reentrant! (:frame envelope) frame-record seed-push)
             (drain-block!     (:frame envelope) frame-record seed-push)))
          (finally
            (swap! router assoc :in-sync-drain? false)))))]
         ;; A captured dispatch-sync that lost its incarnation before the lock
         ;; was acquired: the seed never ran, so this is the one emit.
         (when (and captured?
                    (not drained?)
                    (not (rf.frame/frame-incarnation-live?
                           (:frame envelope) target-token)))
           (rf.trace/with-call-site call-site
             (emit-frame-destroyed! (first event) event (:frame envelope)
                                    :dispatch-sync false)))
         (when drained? (:dispatch-id envelope)))))))))

(defn dispatch-sync!
  "Process `event` now and drain to fixed point before returning, for
  callers outside the runtime such as tests and the REPL (Spec 002
  §`dispatch-sync`). The seed goes to the head of the internal lane, so its
  whole family settles before queued external input, and it counts toward
  the family's `:drain-depth`. From inside a running handler of the same
  frame it is refused with `:rf.error/dispatch-sync-in-handler`; while
  another frame is mid-drain it warns and proceeds. Returns nil. The
  `dispatch-sync` macro stamps `:rf.trace/call-site` onto `opts`."
  ([event] (dispatch-sync! event {}))
  ([event opts]
   (dispatch-sync-with-id! event opts)
   nil))

(defn- run-frame-destroy-event!
  "Run a claimed incarnation's `:on-destroy` event and its same-frame
  descendants to fixed point, on an isolated queue rather than the frame's
  real one (Spec 002 §Destroy). `dispatch!` routes a descendant there only
  while the claim token is current, the frame matches and the host thread is
  the entering one, so neither ordinary queued work nor a `bound-fn` on
  another thread inherits the authority. The real router is marked as
  draining, so a same-frame `dispatch-sync!` from cleanup is still refused."
  [frame-id expected-token event]
  (rf.frame/call-serialized-with-drain!
    frame-id
    (fn []
      (when-let [frame-record (rf.frame/frame frame-id)]
        (when (and (identical? expected-token (:drain-lock frame-record))
                   (rf.frame/frame-incarnation-closing? frame-id expected-token))
          (let [real-router     (:router frame-record)
                drain-depth    (get (:config frame-record) :drain-depth
                                    drain-depth-default)
                envelope       (build-envelope event {:frame frame-id})
                ;; The cleanup seed is the private cascade's one external
                ;; event; the same-frame children it dispatches join this
                ;; router's internal lane, so they are its family.
                teardown-router (atom {:queue            (conj rf.interop/empty-queue
                                                               envelope)
                                       :internal         rf.interop/empty-queue
                                       :scheduled?       true
                                       :in-drain?        nil
                                       :in-sync-drain?   false})
                already-drainer?
                #?(:clj  (identical? (:in-drain? @real-router)
                                     (Thread/currentThread))
                   :cljs (true? (:in-drain? @real-router)))]
            (when-not already-drainer?
              (mark-drainer! real-router))
            (try
              (do
                (emit-dispatched-trace! envelope true)
                (binding [*frame-destroy-cascade*
                          {:frame  frame-id
                           :token  expected-token
                           :router teardown-router
                           :owner  #?(:clj  (Thread/currentThread)
                                      :cljs true)}]
                  (run-one-pass! frame-id frame-record teardown-router
                                 drain-depth expected-token)))
              (finally
                (when-not already-drainer?
                  (clear-drainer! real-router)))))))))
  nil)

;; ---- late-bind hook registration ------------------------------------------
;;
;; `re-frame.frame` and `re-frame.fx` load before this namespace and cannot
;; require it without a cycle, so the router publishes its entry points here.

(rf.late-bind/set-fn! :router/dispatch!       dispatch!)
(rf.late-bind/set-fn! :router/dispatch-sync!  dispatch-sync!)
(rf.late-bind/set-fn! :router/run-frame-destroy-event! run-frame-destroy-event!)

;; Called by `call-serialized-with-drain!`'s cold release when work queued
;; during the hold: that dispatch's `drain-try!` lost the CAS to the cold
;; holder and gave up. Unconditional, since a redundant `drain-try!` only
;; loses the CAS.
(rf.late-bind/set-fn! :router/reschedule-drain!
                   (fn reschedule-drain! [frame-id frame-record]
                     (rf.interop/next-tick
                       (fn [] (drain-try! frame-id frame-record)))))
