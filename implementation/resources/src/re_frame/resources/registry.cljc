(ns re-frame.resources.registry
  "Resource registration — `reg-resource` / `clear-resource` and the
  registry-side introspection accessors. Per Spec 016 §Public API
  §Registration and §Resource registration spec.

  A resource is a registrar entry under the `:resource` kind (Spec 016
  §Registration: \"A `:resource` registrar kind is added; do NOT add a
  `:query` public kind\"). `reg-resource` validates the spec — crucially
  the REQUIRED, fail-closed `:scope` policy (Spec 016 §Scope resolution:
  no policy is a loud `:rf.error/resource-missing-scope-policy`) — and
  writes the entry; `clear-resource` is a registration-lifecycle removal
  (distinct from the data-lifecycle `:rf.resource/invalidate-tags` /
  `:rf.resource/remove` / `:rf.resource/clear-scope` events).

  This namespace owns the registration lifecycle: validation + the registry
  write/read (so an app can register a resource and Xray can enumerate the
  static registry) PLUS, for `clear-resource`, the per-frame runtime
  disposal of that resource id's live cache state. Per Spec 016
  §clear-resource MUST-dispose (rf2-m9h5iq) `clear-resource` removes the
  registrar entry AND disposes every live `:rf.runtime/resources` entry for
  the id across registered frames (entries + reverse indexes recomputed,
  stale / GC timers cancelled, in-flight work marked terminal + best-effort
  aborted) so a late reply cannot recreate the cleared entries. The
  data-lifecycle events (`:rf.resource/remove` / `:rf.resource/release-owner`
  / `:rf.resource/clear-scope`) remain the in-cascade, scope/instance-grained
  disposal surfaces."
  (:require [re-frame.error :as error]
            [re-frame.frame :as frame]
            [re-frame.late-bind :as late-bind]
            [re-frame.registrar :as registrar]
            [re-frame.resources.classification :as classification]
            [re-frame.resources.scope-registry :as scope-registry]
            [re-frame.resources.state :as state]
            [re-frame.resources.timers :as timers]
            [re-frame.resources.work-ledger :as work-ledger]
            [re-frame.source-coords :as source-coords]
            [re-frame.trace :as trace]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- the :resource registrar kind ----------------------------------------

(def resource-kind
  "The registrar kind for resources (`:resource`). Per Spec 016
  §Registration. Added to the core registrar's closed kind set (a Spec
  change); resources register their spec under this kind."
  :resource)

;; ---- scope-policy validation (fail-closed) -------------------------------

(def ^:private global-scope-policy
  "The explicit, auditable global-scope claim. Per Spec 016 §Scope
  resolution: `:scope :rf.scope/global` is a CLAIM (\"same params produce
  the same data for every user/tenant/permission/locale/impersonation\"),
  never a framework default."
  :rf.scope/global)

(def ^:private from-caller-scope-policy
  "Scope required from the use site. Per Spec 016 §Scope resolution:
  every ensure/refetch/state call MUST supply `:scope`, or a route-
  resource resolver MUST."
  :rf.scope/from-caller)

(defn- reserved-scope-namespace?
  "True when `scope` is a bare keyword in the framework-reserved
  `:rf.scope/*` namespace — i.e. a candidate for the closed scope-policy
  enum. A non-keyword (a `[:rf.scope/session …]` tuple, a map, a string)
  is NOT in the bare-keyword reserved slot. Reuses the shared
  `state/reserved-scope-ns` constant (one source of truth for the reserved
  namespace across the policy gate here and the concrete-scope gate in
  `state`)."
  [scope]
  (and (keyword? scope) (= state/reserved-scope-ns (namespace scope))))

(defn- valid-scope-policy?
  "A scope policy is one of the reserved enum keywords
  (`:rf.scope/global`, `:rf.scope/from-caller`), a resolver (a fn), or a
  literal scope value (any non-keyword EDN data value, or an
  app-namespaced keyword). Per Spec 016 §Scope resolution. `nil` /
  missing is NOT valid — it is a loud registration error.

  FAIL-CLOSED reserved-namespace gate (rf2-y7lcqy): a bare keyword in the
  framework-reserved `:rf.scope/*` namespace that is NOT one of the closed
  enum is a TYPO (e.g. `:rf.scope/glabal`), and a typo in the framework
  namespace is a registration error — it MUST NOT be silently accepted as
  a literal scope (which would resolve to `[:rf.scope/glabal]`, a silent
  wrong scope). App-namespaced keywords (`:my.app/whatever`) and
  data-value scopes (`[:rf.scope/session {…}]`, maps, strings) remain
  valid literal scopes.

  NAMED-RESOLVER REFERENCE (EP-0016 D3 slice 3): a `{:from-db <id>}` map
  is a valid policy — a DERIVED-scope reference resolved against db at use
  time, NOT a literal scope value. It is recognised here so the resolver
  id need not be registered yet at `reg-resource` time (the reference is
  resolved at use time, the single use-time rule); registration-time the
  reference shape is enough."
  [scope]
  (and (some? scope)
       (cond
         ;; the closed reserved enum
         (= scope global-scope-policy)      true
         (= scope from-caller-scope-policy) true
         ;; a `{:from-db <id>}` named-resolver reference — a derived-scope
         ;; policy resolved at use time, NOT a literal map scope.
         (scope-registry/from-db-reference? scope) true
         ;; a bare `:rf.scope/*` keyword outside the enum is a typo —
         ;; reject (fail closed), do NOT accept as a literal scope.
         (reserved-scope-namespace? scope)  false
         ;; a fn resolver (route/spec/fn-of-nothing)
         (fn? scope)                        true
         ;; any other value — an app-namespaced keyword or a non-keyword
         ;; data value — is a legitimate literal scope / data-value
         ;; resolver.
         :else                              true)))

(defn- registration-error
  "Build the canonical thrown-error shape (Spec 009 §The thrown-error
  shape) for a `reg-resource` validation failure."
  [error-id where reason extra]
  (error/thrown-ex-info
    error-id
    where
    reason
    {:recovery :fix-registration
     :extra    extra}))

;; ---- :infinite registration validation (Spec 016 §Infinite resources) -----
;;
;; The `:infinite`-only additive slice of the `reg-resource` args-map
;; (`:rf/infinite-resource-args`, Spec-Schemas; EP-0021 R1–R8). `:infinite
;; true` selects the slice and makes `:next-page-param` REQUIRED — a fail-closed
;; gate symmetric with the `:scope` / `:params-schema` / `:request` gates above.
;; The per-page cursor is NEVER a registration key (R8): it is the
;; runtime-threaded page-param the `:request` fn reads from its RESERVED ctx
;; (`{:rf.resource/page-param p :rf.resource/page-index i}`); a non-infinite
;; `:request` still receives a nil/empty ctx (NO new 3-arity). The
;; `:rf.error/infinite-missing-page-accessor` error is RUNTIME-detected at the
;; first non-vector page in the merge layer (wave 4), not here — at registration
;; we have no page to inspect; here we shape-validate `:page->items` (a keyword
;; or fn) when present.

(defn infinite-resource?
  "True iff `spec` declares an infinite feed (`:infinite true`). The single
  predicate the registry validation and the runtime read so the `:infinite`
  slice is gated by the SAME marker. Per Spec 016 §Registration — :infinite."
  [spec]
  (true? (:infinite spec)))

(defn- validate-infinite-spec!
  "Validate the `:infinite`-only slice of a `reg-resource` spec (Spec 016
  §Infinite resources and load-more feeds, EP-0021). A no-op when the resource
  is NOT infinite (`:infinite` absent / not `true`). When `:infinite true`:

    - `:next-page-param` is REQUIRED and MUST be a fn — a missing / non-fn
      value raises `:rf.error/infinite-missing-next-page-param` (R8 gate,
      symmetric with `:scope` / `:params-schema` / `:request`);
    - the optional `:prev-page-param` (R7 mirror) MUST be a fn when present;
    - the optional `:page->items` (R3 accessor) MUST be a keyword or fn when
      present (a non-vector page with NO `:page->items` is the RUNTIME-detected
      `:rf.error/infinite-missing-page-accessor`, raised at the merge site in
      wave 4 — not here);
    - the optional `:refetch` policy (R6) MUST be a map with a boolean
      `:refetch-all-pages?` and/or an integer `:refetch-window` when present.

  Also rejects an `:infinite` value that is present but not literally `true`
  (`:infinite false` is meaningless — a resource is infinite or it is an
  ordinary resource; the flag is `[:= true]` per `:rf/infinite-resource-args`).
  Fails in dev AND prod (a caller bug)."
  [resource-id spec]
  (when (contains? spec :infinite)
    ;; `:infinite` is the `[:= true]` selector — present-but-not-true is a typo.
    (when-not (true? (:infinite spec))
      (throw (registration-error
               :rf.error/invalid-resource-spec
               'rf/reg-resource
               (str "resource " resource-id " declares :infinite "
                    (pr-str (:infinite spec)) " — the :infinite flag is the "
                    "literal `true` selector for the load-more feed kind. Omit "
                    "it for an ordinary resource. Per Spec 016 §Infinite "
                    "resources and load-more feeds.")
               {:resource-id resource-id :infinite (:infinite spec)})))
    (when (infinite-resource? spec)
      ;; `:next-page-param` is REQUIRED + MUST be a fn (the R8 gate).
      (when-not (fn? (:next-page-param spec))
        (throw (registration-error
                 :rf.error/infinite-missing-next-page-param
                 'rf/reg-resource
                 (str "infinite resource " resource-id " declares no valid "
                      ":next-page-param. :infinite true makes :next-page-param "
                      "REQUIRED — a pure fn (last-page all-pages) → next-param | "
                      "nil (nil = the single terminal, no more pages). Per Spec "
                      "016 §Registration — :infinite (R8).")
                 {:resource-id resource-id :next-page-param (:next-page-param spec)})))
      ;; `:prev-page-param` (R7 mirror) MUST be a fn when present.
      (when (and (contains? spec :prev-page-param)
                 (not (fn? (:prev-page-param spec))))
        (throw (registration-error
                 :rf.error/invalid-resource-spec
                 'rf/reg-resource
                 (str "infinite resource " resource-id " declares a "
                      ":prev-page-param that is not a fn (got "
                      (pr-str (:prev-page-param spec)) "). :prev-page-param is "
                      "the optional R7 bidirectional mirror — a pure fn "
                      "(first-page all-pages) → prev-param | nil. Per Spec 016 "
                      "§Causal event — load-more (R7).")
                 {:resource-id resource-id :prev-page-param (:prev-page-param spec)})))
      ;; `:page->items` (R3 accessor) MUST be a keyword or fn when present.
      (when (and (contains? spec :page->items)
                 (not (or (keyword? (:page->items spec))
                          (fn? (:page->items spec)))))
        (throw (registration-error
                 :rf.error/invalid-resource-spec
                 'rf/reg-resource
                 (str "infinite resource " resource-id " declares a :page->items "
                      "that is neither a keyword nor a fn (got "
                      (pr-str (:page->items spec)) "). :page->items is the R3 "
                      "merge accessor for a non-vector / enveloped page — a "
                      "keyword key (e.g. :items) or (fn [page] → seq-of-items). "
                      "Per Spec 016 §Subscription contract (R3).")
                 {:resource-id resource-id :page->items (:page->items spec)})))
      ;; `:refetch` (R6 policy) MUST be a well-formed map when present.
      (when (contains? spec :refetch)
        (let [refetch (:refetch spec)
              {:keys [refetch-all-pages? refetch-window]} refetch]
          (when-not (map? refetch)
            (throw (registration-error
                     :rf.error/invalid-resource-spec
                     'rf/reg-resource
                     (str "infinite resource " resource-id " declares a :refetch "
                          "that is not a map (got " (pr-str refetch) "). :refetch "
                          "is the R6 policy map {:refetch-all-pages? bool "
                          ":refetch-window int}; omitted ⇒ the window-preserving "
                          "default. Per Spec 016 §Refetch and invalidation of an "
                          "infinite feed (R6).")
                     {:resource-id resource-id :refetch refetch})))
          (when (and (contains? refetch :refetch-all-pages?)
                     (not (boolean? refetch-all-pages?)))
            (throw (registration-error
                     :rf.error/invalid-resource-spec
                     'rf/reg-resource
                     (str "infinite resource " resource-id "'s :refetch "
                          ":refetch-all-pages? is not a boolean (got "
                          (pr-str refetch-all-pages?) "). Per Spec 016 §Refetch "
                          "and invalidation of an infinite feed (R6).")
                     {:resource-id resource-id :refetch refetch})))
          (when (and (contains? refetch :refetch-window)
                     (not (integer? refetch-window)))
            (throw (registration-error
                     :rf.error/invalid-resource-spec
                     'rf/reg-resource
                     (str "infinite resource " resource-id "'s :refetch "
                          ":refetch-window is not an integer (got "
                          (pr-str refetch-window) "). It bounds how much of the "
                          "accumulation is refreshed. Per Spec 016 §Refetch and "
                          "invalidation of an infinite feed (R6).")
                     {:resource-id resource-id :refetch refetch}))))))
  nil))

(defn- validate-resource-spec!
  "Fail loudly at the authoring boundary when a resource spec omits the
  REQUIRED keys (Spec 016 §Resource registration spec). The fail-closed
  `:scope` policy is the load-bearing one: a `reg-resource` with no scope
  policy throws `:rf.error/resource-missing-scope-policy` so \"I forgot
  this read is user-scoped\" is unrepresentable at registration rather
  than an Xray heuristic. Fails in dev AND prod (caller bug)."
  [resource-id spec]
  (when-not (map? spec)
    (throw (registration-error
             :rf.error/invalid-resource-spec
             'rf/reg-resource
             (str "resource " resource-id "'s spec must be a map, got "
                  (pr-str (type spec)))
             {:resource-id resource-id :value spec})))
  ;; `:scope` is REQUIRED, fail-closed (rf2-6rrz53). No silent global default.
  (when-not (valid-scope-policy? (:scope spec))
    (throw (registration-error
             :rf.error/resource-missing-scope-policy
             'rf/reg-resource
             (str "resource " resource-id " declares no valid :scope policy. "
                  ":scope is REQUIRED (fail-closed) — one of :rf.scope/global "
                  "(an explicit, auditable global claim), a resolver, or "
                  ":rf.scope/from-caller. There is no implicit default; a "
                  "user-scoped read must say so. Per Spec 016 §Scope resolution.")
             {:resource-id resource-id :scope (:scope spec)})))
  ;; `:params-schema` is REQUIRED — validates + canonicalizes params.
  (when-not (contains? spec :params-schema)
    (throw (registration-error
             :rf.error/invalid-resource-spec
             'rf/reg-resource
             (str "resource " resource-id " declares no :params-schema. "
                  ":params-schema is REQUIRED — it validates and canonicalizes "
                  "params (the resource's identity). Per Spec 016 §Resource "
                  "registration spec.")
             {:resource-id resource-id})))
  ;; `:request` is REQUIRED for the only initial-scope transport.
  (when-not (contains? spec :request)
    (throw (registration-error
             :rf.error/invalid-resource-spec
             'rf/reg-resource
             (str "resource " resource-id " declares no :request. For "
                  ":transport :rf.http/managed (the only initial-scope "
                  "transport) :request returns a Spec 014 managed-HTTP args "
                  "map. Per Spec 016 §Resource registration spec.")
             {:resource-id resource-id})))
  ;; `:poll-interval-ms` is OPTIONAL (EP-0020 §Polling). When present it MUST
  ;; be a number of milliseconds — a non-positive / absent value means NO
  ;; polling (the disarm rule `timers/schedule!` already applies to a
  ;; non-positive stale/GC delay), parallel to `:stale-after-ms` /
  ;; `:gc-after-ms`. A non-numeric value (a string, keyword, etc.) is a typo
  ;; in a freshness-policy key — fail-closed loudly at the authoring boundary
  ;; rather than silently never poll.
  (when (and (contains? spec :poll-interval-ms)
             (some? (:poll-interval-ms spec))
             (not (number? (:poll-interval-ms spec))))
    (throw (registration-error
             :rf.error/invalid-resource-spec
             'rf/reg-resource
             (str "resource " resource-id " declares a :poll-interval-ms that "
                  "is not a number (got " (pr-str (:poll-interval-ms spec))
                  "). :poll-interval-ms is a positive integer of milliseconds "
                  "(while actively owned + visible the entry revalidates every "
                  "N ms); a non-positive or absent value means no polling. Per "
                  "Spec 016 §Polling.")
             {:resource-id resource-id :poll-interval-ms (:poll-interval-ms spec)})))
  ;; `:infinite` is OPTIONAL (Spec 016 §Infinite resources and load-more feeds,
  ;; EP-0021). When declared it gates the `:infinite`-only slice
  ;; (`:rf/infinite-resource-args`): `:infinite true` makes `:next-page-param`
  ;; REQUIRED, and shape-validates the other infinite-only keys. Reject a
  ;; malformed `:infinite` shape loudly at the authoring boundary (R1–R8 are
  ;; the binding rulings).
  (validate-infinite-spec! resource-id spec)
  nil)

;; ---- reg-resource / clear-resource ---------------------------------------

(defn reg-resource
  "Register a resource under `resource-id` with `resource-spec`. Per
  Spec 016 §Public API §Registration.

  Validates the spec (the REQUIRED, fail-closed `:scope` policy first;
  then `:params-schema` and `:request`) and writes a `:resource`-kind
  registrar entry carrying the spec plus any captured source coords.

  Returns `resource-id` per the `reg-*` return-value convention
  ([Conventions §reg-* return-value convention])."
  [resource-id resource-spec]
  (validate-resource-spec! resource-id resource-spec)
  (let [previous (registrar/lookup resource-kind resource-id)]
    (registrar/register!
      resource-kind
      resource-id
      (source-coords/merge-coords
        (merge {:doc (:doc resource-spec)}
               {:rf/resource resource-spec
                :handler-fn  (:request resource-spec)})))
    ;; `:rf.resource/registered` fires on FIRST-TIME registration so tools
    ;; subscribing to "all resource lifecycle events" (the Xray Resources
    ;; tab / lifecycle timeline) see one row per fresh resource — the
    ;; registration anchor of the `:rf.resource/*` trace family (Spec 016
    ;; §Xray and AI tooling). Re-registration rides the cross-kind
    ;; `:rf.registry/handler-replaced` trace (emitted by `registrar/register!`
    ;; per Spec 001 §Hot-reload trace surface); not re-emitted here. Mirrors
    ;; the `:rf.route/registered` / `:rf.flow/registered` symmetry. The row is
    ;; frame-agnostic (registration is a load-time act, not a per-frame event).
    (when (nil? previous)
      (trace/emit! :rf.event :rf.resource/registered
                   {:resource-id      resource-id
                    :scope-policy     (:scope resource-spec)
                    :transport        (:transport resource-spec)
                    :stale-after-ms   (:stale-after-ms resource-spec)
                    :gc-after-ms      (:gc-after-ms resource-spec)
                    :poll-interval-ms (:poll-interval-ms resource-spec)})))
  resource-id)

(defn- entry-keys-for-resource
  "The scoped-key VECTORS in `runtime-db`'s `:entries` whose resource id (the
  SECOND element of `[scope resource-id params]`) is `resource-id`. rf2-9e0tyq:
  `:entries` is keyed on the opaque byte `key-id`, so the resource-id filter
  reads each entry's stored `:resource/key` vector (NOT the map key), and the
  returned keys are the kind-preserving VECTORS the timer-cancel / abort / trace
  consumers name resources by. The disposal `dissoc` maps these through
  `state/key-id` to address the byte-keyed `:entries` slots."
  [runtime-db resource-id]
  (into []
        (comp (map val)
              (filter (fn [entry] (= resource-id (second (:resource/key entry)))))
              (map :resource/key))
        (get-in runtime-db (state/entries-path))))

(defn- dispose-resource-runtime!
  "Dispose every live runtime entry for `resource-id` in ONE frame (Spec 016
  §clear-resource MUST-dispose, rf2-m9h5iq). Atomically (through
  `frame/swap-runtime-db!`, the framework-authority out-of-cascade runtime-db
  write surface routing / machine spawn use): removes the resource's
  `:entries`, marks each in-flight work record terminal `:suppressed` (so a
  late reply's `live-entry-for-reply` existence check finds nothing to write
  into — the entry is gone — and the ledger row is settled), and recomputes
  `:tag-index` / `:owner-index` from what remains. Then, host-side: cancels
  each entry's advisory stale / GC timers and best-effort aborts each
  in-flight attempt (opportunistic; stale suppression by removed-entry is the
  correctness boundary). Emits a `:rf.resource/removed` trace row
  (`:reason :clear-resource`) for the frame when anything was disposed.
  Returns the disposed scoped keys (possibly empty)."
  [frame-id resource-id]
  (let [runtime-db (or (frame/frame-runtime-db-value frame-id) {})
        keys'      (entry-keys-for-resource runtime-db resource-id)]
    (when (seq keys')
      (let [in-flight (into []
                            (keep (fn [k]
                                    (let [e   (get-in runtime-db (state/entry-path k))
                                          wid (:current-work e)]
                                      (when wid
                                        [wid (:transport (work-ledger/get-record runtime-db wid))]))))
                            keys')]
        ;; durable disposal — atomic on the frame's runtime-db partition
        (frame/swap-runtime-db!
          frame-id
          (fn [rdb]
            (-> (or rdb {})
                ;; rf2-9e0tyq — `keys'` are scoped-key VECTORS; the byte-keyed
                ;; `:entries` map is dissoc'd by their `key-id`s.
                (update-in (state/entries-path)
                           (fn [es] (reduce dissoc es (map state/key-id keys'))))
                (as-> db (reduce (fn [d [wid _]]
                                   (work-ledger/update-record
                                     d wid work-ledger/mark-terminal
                                     :suppressed {:reason :clear-resource}))
                                 db in-flight))
                (update state/resources-key state/recompute-indexes))))
        ;; host-side disposal — release advisory timers + opportunistically
        ;; abort each in-flight attempt. Correctness rests on the removed
        ;; entry (a late reply's existence check finds nothing), NOT on the
        ;; abort landing; `opportunistic-abort!` fires any direct host handle
        ;; and drops the side-table slot — the SAME best-effort path the
        ;; frame-destroy teardown uses (Spec 016 §Cancellation is
        ;; opportunistic).
        (doseq [k keys']
          (timers/cancel-for-key! frame-id k))
        (doseq [[wid _transport] in-flight]
          (work-ledger/opportunistic-abort! frame-id wid))
        (trace/emit! :rf.event :rf.resource/removed
                     {:rf.frame/id frame-id :resource-id resource-id
                      :reason :clear-resource :removed (vec keys')
                      :aborted (mapv first in-flight)})))
    keys'))

(defn clear-resource
  "Remove a registered resource AND dispose its live per-frame runtime state.
  Per Spec 016 §Public API §Registration / §clear-resource MUST-dispose
  (rf2-m9h5iq).

  Clears the registrar entry, then for every registered frame disposes the
  resource id's live cache state: removes its `:rf.runtime/resources`
  `:entries`, recomputes the reverse `:tag-index` / `:owner-index`, cancels
  its stale / GC timers and host handles, marks any in-flight work terminal
  `:suppressed`, and best-effort aborts those attempts. A late reply cannot
  recreate the cleared entries — its existence check finds the entry gone.

  The scope/instance-grained data-lifecycle events
  (`:rf.resource/invalidate-tags` / `:rf.resource/remove` /
  `:rf.resource/clear-scope`) remain the in-cascade disposal surfaces;
  `clear-resource` is the process-level registration removal that ALSO
  disposes (the registration is gone, so leaving live entries would strand a
  permanent unrefreshable cache).

  No-op (returns `resource-id`) when the id is not registered and no frame
  holds a live entry for it."
  [resource-id]
  (registrar/unregister! resource-kind resource-id)
  (doseq [frame-id (frame/frame-ids)]
    (dispose-resource-runtime! frame-id resource-id))
  resource-id)

;; ---- registry-side introspection -----------------------------------------

(defn resource-meta
  "Return the registered resource's spec map (`:params-schema`,
  `:data-schema`, `:request`, `:scope`, `:transport`, `:stale-after-ms`,
  `:gc-after-ms`, `:poll-interval-ms`, `:tags`, `:doc`, source coords) for
  `resource-id`, or nil if no resource is registered under that id. Per Spec
  016 §Introspection."
  [resource-id]
  (:rf/resource (registrar/lookup resource-kind resource-id)))

(defn resource-ids
  "Return a vector of every registered resource id. The registry-side
  half of `resources` (the runtime-side per-frame instance table lives in
  the runtime). Per Spec 016 §Xray and AI tooling (the static
  resource registry)."
  []
  (vec (registrar/ids resource-kind)))

(defn require-resource-spec!
  "Look the resource spec up by `resource-id`, throwing
  `:rf.error/resource-not-registered` (the loud, fail-closed boundary) when
  no `:resource`-kind registrar entry exists. `where` names the call-site
  public surface. Returns the spec map. Per Spec 016 §Public API."
  [resource-id where]
  (or (resource-meta resource-id)
      (throw (registration-error
               :rf.error/resource-not-registered
               where
               (str "no resource is registered under " resource-id
                    " — call rf/reg-resource before ensuring / subscribing. "
                    "Per Spec 016 §Public API.")
               {:resource-id resource-id}))))

;; ---- params validation + canonicalization (Spec 016 §Resource identity) ---

(defn validate+canonicalize-params
  "Validate `params` against the resource's REQUIRED `:params-schema`
  (pluggable late-bound Malli validator, exactly as routing validates route
  params — `:schemas/validate-with-registered-fn`; no static schemas dep),
  reject non-EDN / host values loudly (`state/reject-non-edn!`), then return
  the canonical params (`state/canonicalize`). Throws
  `:rf.error/resource-invalid-params` on a schema-conformance failure.
  Per Spec 016 §Resource identity / §Canonicalization rule.

  `nil` vs missing is schema-defined (rf2-hgy5kf): a caller threads
  `state/missing-params` for an ABSENT `:params` slot (the documented
  omitted-default lowers it to `{}` via `state/default-omitted-params`), while
  a PRESENT explicit `nil` is passed THROUGH to `:params-schema` validation +
  canonicalization unchanged — the schema (not a blanket `(or params {})`)
  decides whether nil conforms. This keeps the validation boundary from
  silently performing an accidental API default, so `{:params nil}` and an
  omitted `:params` stay distinct identities (Spec 016: \"nil vs missing MUST
  be schema-defined, not accidental\"; EP-0012 §canonical-forms)."
  [resource-id spec params where]
  (let [params (state/default-omitted-params params)
        schema (:params-schema spec)]
    ;; host / opaque values are rejected at the cache-key boundary
    (state/reject-non-edn! params where :params resource-id)
    ;; schema conformance (pluggable; no-op when no validator is registered)
    (when schema
      (let [validate (late-bind/get-fn-cached :schemas/validate-with-registered-fn)]
        (when (and validate (not (validate schema params)))
          (let [explain (late-bind/get-fn-cached :schemas/explain-with-registered-fn)
                ;; rf2-99j4e4 — the thrown error data + downstream egress must
                ;; not leak a `:sensitive?` params slot (nor ride a `:large?`
                ;; slot raw). Route `:params` + the explainer `:error` through
                ;; the resources-family classification projection (the SAME
                ;; per-slot owner surface SSR key egress uses + the shared
                ;; schemas redaction seam), so the owner's `:params-schema`
                ;; marks govern the error payload as they govern wire egress.
                redacted (classification/redact-invalid-params-error
                           params (when explain (explain schema params)) spec)]
            (throw (registration-error
                     :rf.error/resource-invalid-params
                     where
                     (str "resource " resource-id " params do not conform to "
                          ":params-schema. Per Spec 016 §Resource identity.")
                     {:resource-id resource-id
                      :params      (:params redacted)
                      :error       (:error redacted)}))))))
    (state/canonicalize params)))

;; ---- scope resolution (Spec 016 §Scope resolution) ------------------------
;;
;; Scope is the cache's tenant / user / permission / locale / impersonation
;; / SSR leak boundary, and a resolved scope can carry PII. A boundary that
;; critical MUST fail closed: it never silently defaults to \"shared\".
;; Resolution differs between EVENTS (which may run a (route, ctx) resolver
;; and have an event context) and SUBSCRIPTIONS (pure — no routing match,
;; no event context). There is NO `[:rf.scope/global]` fallthrough on
;; either path.

(defn- canonical-scope!
  "Route a CONCRETE resolved scope through the single shared concrete-scope
  validation path (`state/canonicalize-scope`, rf2-lzv9xc): reject a
  reserved-namespace typo fail-closed (rf2-pd7akw), reject a host / opaque
  value, reject the global scope wrapped as the singleton `[:rf.scope/global]`
  in favour of the canonical bare `:rf.scope/global` (rf2-bwwk6l), then
  canonicalize. Per
  Spec 016 §Resource identity / §Scope resolution. Used by both event and
  sub scope resolution, so a misspelled reserved `:rf.scope/*` in a payload /
  route-resolver / fn-of-nothing / pure-data policy is caught at the concrete
  boundary, not silently accepted as a literal scope."
  [resource-id scope where]
  (state/canonicalize-scope scope where resource-id))

(defn- require-resolved-reference!
  "A `{:from-db <id>}` reference at a scope-REQUIRING event/route site
  (EP-0016 D3 slice 3) resolves against `db` at use time. A reference that
  resolves NIL is FAIL-CLOSED — it INTENDED to derive the tenant / user /
  leak-boundary scope and could not, which must NEVER silently fall through
  to global or another tier (Spec 016 §Resolver references — nil at a
  scope-requiring site is fail-closed). Throws
  `:rf.error/resource-scope-unresolved-reference` naming the resolver id;
  returns the resolved concrete scope otherwise."
  [resource-id reference db where]
  (or (scope-registry/resolve-from-db-reference reference db where)
      (throw (registration-error
               :rf.error/resource-scope-unresolved-reference
               where
               (str "resource " resource-id " referenced named scope resolver "
                    (pr-str (:from-db reference)) " via {:from-db …}, but it "
                    "resolved NIL against the current db — FAIL-CLOSED. A "
                    "derived scope that cannot resolve is the unresolved "
                    "condition, never permission to read global or fall through "
                    "to another tier. The resolver's declared :inputs are not "
                    "present in db (e.g. no logged-in user). Per Spec 016 "
                    "§Resolver references.")
               {:resource-id resource-id :from-db (:from-db reference)}))))

(defn resolve-scope-for-event
  "Resolve the concrete cache scope for a resource EVENT, fail-closed, in
  Spec 016 §Resolution precedence order — NO `[:rf.scope/global]`
  fallthrough:

    1. `:scope` supplied on the event payload;
    2. (route-resource `:scope` resolver — supplied by the route slice,
       not this runtime slice; threaded in as `route-scope`);
    3. the resource-spec `:scope` resolver, but ONLY when it resolves to a
       concrete value without an event context — an explicit
       `:rf.scope/global` claim, a `{:from-db …}` named-resolver reference,
       or a pure-data / fn-of-nothing resolver.

  A `{:from-db <id>}` reference at ANY tier (payload, route, or spec policy)
  is resolved against `db` at use time (EP-0016 D3 slice 3, the single
  use-time rule). A reference that resolves NIL FAILS CLOSED
  (`:rf.error/resource-scope-unresolved-reference`) — never a fall-through
  to global. `db` is the handler's app-db coeffect (the causal world input);
  a nil db (a legacy/direct test call) resolves references against `{}`.

  A `:rf.scope/global` policy resolves to `:rf.scope/global` ONLY because
  that is its declared explicit policy. A `:rf.scope/from-caller` resource
  reached with no payload `:scope` and no route resolver is a loud
  use-time error (`:rf.error/resource-scope-required-from-caller`). Returns
  the canonical scope."
  [resource-id spec {:keys [payload-scope route-scope db]} where]
  (let [policy (:scope spec)
        ;; resolve a {:from-db …} reference at the tier it appears (use-time)
        resolve-ref (fn [reference] (require-resolved-reference! resource-id reference db where))]
    (cond
      ;; 1. payload scope (highest precedence) — a {:from-db …} reference
      ;; resolves at use time + fails closed on nil; a concrete scope is
      ;; canonicalized as before.
      (scope-registry/from-db-reference? payload-scope)
      (canonical-scope! resource-id (resolve-ref payload-scope) where)
      (some? payload-scope) (canonical-scope! resource-id payload-scope where)
      ;; 2. route-resource resolver result (threaded in by the route slice).
      ;; The route slice already resolves a {:from-db …} route-resource
      ;; `:scope` to a concrete value before threading it here, so route-scope
      ;; is concrete; resolve defensively if a reference still arrives.
      (scope-registry/from-db-reference? route-scope)
      (canonical-scope! resource-id (resolve-ref route-scope) where)
      (some? route-scope)   (canonical-scope! resource-id route-scope where)
      ;; 3a. a {:from-db …} spec policy — the declared derived-scope policy,
      ;; resolved against db at use time, fail-closed on nil.
      (scope-registry/from-db-reference? policy)
      (canonical-scope! resource-id (resolve-ref policy) where)
      ;; 3b. explicit global claim — the resource's declared policy
      (= policy global-scope-policy) global-scope-policy
      ;; 3c. from-caller with no payload/route scope — loud use-time error
      (= policy from-caller-scope-policy)
      (throw (registration-error
               :rf.error/resource-scope-required-from-caller
               where
               (str "resource " resource-id " declares :scope "
                    ":rf.scope/from-caller — every ensure / refetch / state "
                    "call MUST supply :scope on the payload (or a route "
                    "resolver must). There is no silent global read. Per Spec "
                    "016 §Scope resolution.")
               {:resource-id resource-id}))
      ;; 3d. a fn-of-nothing resolver (pure data resolvable without ctx)
      (fn? policy)
      (canonical-scope! resource-id (policy) where)
      ;; 3e. a pure data-value resolver (a concrete scope value declared
      ;; directly as the policy)
      :else (canonical-scope! resource-id policy where))))

(defn- sub-unresolved-reference!
  "A `{:from-db <id>}` reference at a SUBSCRIPTION site that resolves NIL
  against the frame app-db is the sub-side fail-closed unresolved condition
  (EP-0016 D3 slice 3 / rf2-616xa6): the resolver's declared inputs are not
  present (e.g. no logged-in user), so the scope is genuinely \"unresolved\"
  — NEVER a silent global read and NEVER a silent `:idle`. Raises
  `:rf.error/resource-sub-unresolved-scope` naming the resolver id, the
  read-side counterpart of the event-side fail-closed throw. Returns the
  resolved concrete scope otherwise."
  [resource-id reference db where]
  ;; rf2-ru73k6 F3 — a subscription is a PASSIVE read advertised as pure; it
  ;; resolves its `{:from-db …}` scope through the trace-FREE evaluator so a
  ;; sub re-key (which fires on every frame-state change) never emits
  ;; `:rf.resource/scope-resolved` observability state. The causal write-side
  ;; resolution (event ensure / route / mutation settle) keeps its traced
  ;; evidence via `resolve-from-db-reference`.
  (or (scope-registry/resolve-from-db-reference-pure reference db where)
      (throw (registration-error
               :rf.error/resource-sub-unresolved-scope
               where
               (str "resource " resource-id " subscription referenced named "
                    "scope resolver " (pr-str (:from-db reference)) " via "
                    "{:from-db …}, but it resolved NIL against the frame db — "
                    "the scope is UNRESOLVED. A sub never reads global / a "
                    "different cache entry / a silent :idle when its derived "
                    "scope cannot resolve; the view should render the "
                    "\"scope unresolved\" state until the resolver's :inputs "
                    "(e.g. a logged-in user) appear. Per Spec 016 §Resolver "
                    "references / §Subscription-side scope resolution.")
               {:resource-id resource-id :from-db (:from-db reference)
                :policy :unresolved-reference}))))

(defn resolve-scope-for-sub
  "Resolve the cache scope for a resource SUBSCRIPTION, fail-closed (Spec
  016 §Subscription-side scope resolution). A sub is PURE — it cannot run a
  `(route, ctx)` resolver. Resolution order:

    1. `:scope` supplied on the subscription payload;
    2. the resource spec's `:scope` ONLY if a pure sub can evaluate it
       without an event context — an explicit `:rf.scope/global` claim, a
       `{:from-db …}` named-resolver reference, or a pure-data /
       fn-of-nothing resolver.

  A `{:from-db <id>}` reference (on the payload OR as the spec policy) is
  resolved against `db` (the frame app-db value) at use time (EP-0016 D3
  slice 3). A reference that resolves NIL raises
  `:rf.error/resource-sub-unresolved-scope` — the sub-side fail-closed
  \"scope unresolved\" condition (rf2-616xa6), never a global / wrong-entry
  / silent-`:idle` read. `db` is the frame app-db value the sub layer reads;
  a nil db resolves references against `{}`.

  A sub that CANNOT resolve a scope raises `:rf.error/resource-sub-
  unresolved-scope` (carrying the resource id + the unresolvable policy) —
  NEVER a silent `[:rf.scope/global]` read and NEVER a silent `:idle`. A
  `:rf.scope/from-caller` policy or a multi-arg `(route, ctx)` resolver is
  not sub-resolvable. Returns the canonical scope.

  Every caller supplies the frame `db` explicitly (rf2-bwwk6l): a caller
  that resolves no `{:from-db …}` scope passes `{}`, where references resolve
  fail-closed."
  [resource-id spec payload-scope where db]
  (let [policy (:scope spec)]
    (cond
      ;; 1. payload scope — a {:from-db …} reference resolves against the
      ;; frame db at use time + fails closed on nil (sub-side).
      (scope-registry/from-db-reference? payload-scope)
      (canonical-scope! resource-id
                        (sub-unresolved-reference! resource-id payload-scope db where)
                        where)
      (some? payload-scope) (canonical-scope! resource-id payload-scope where)
      ;; 2a. a {:from-db …} spec policy — the declared derived-scope policy,
      ;; resolved against the frame db at use time (rf2-616xa6: the sub
      ;; re-keys reactively when the resolver's app-db inputs change).
      (scope-registry/from-db-reference? policy)
      (canonical-scope! resource-id
                        (sub-unresolved-reference! resource-id policy db where)
                        where)
      (= policy global-scope-policy) global-scope-policy
      ;; a fn resolver is sub-resolvable ONLY when it is a fn-of-nothing
      ;; (a route (route, ctx) resolver needs an event context a pure sub
      ;; lacks). We treat a 0-arg-callable fn as sub-resolvable; a fn that
      ;; throws on 0-args is not sub-resolvable and falls to the loud error.
      (fn? policy)
      (let [resolved (try (policy) (catch #?(:clj Throwable :cljs :default) _ ::not-sub-resolvable))]
        (if (= resolved ::not-sub-resolvable)
          (throw (registration-error
                   :rf.error/resource-sub-unresolved-scope
                   where
                   (str "resource " resource-id " has a scope policy that a "
                        "pure subscription cannot resolve (a (route, ctx) "
                        "resolver or :rf.scope/from-caller). Pass :scope on the "
                        "subscription payload (the same scope the owning "
                        "route/event ensured under), or re-declare the resource "
                        "with a sub-resolvable scope policy. Per Spec 016 "
                        "§Subscription-side scope resolution.")
                   {:resource-id resource-id :policy :resolver}))
          (canonical-scope! resource-id resolved where)))
      (= policy from-caller-scope-policy)
      (throw (registration-error
               :rf.error/resource-sub-unresolved-scope
               where
               (str "resource " resource-id " declares :scope "
                    ":rf.scope/from-caller — a subscription MUST supply :scope "
                    "on its payload. Per Spec 016 §Subscription-side scope "
                    "resolution.")
               {:resource-id resource-id :policy from-caller-scope-policy}))
      ;; a pure data-value policy is sub-resolvable
      :else (canonical-scope! resource-id policy where))))
