(ns re-frame.resources.registry
  "Resource registration: `reg-resource` / `clear-resource`, the registry
  read accessors, params validation and scope resolution. `clear-resource`
  also disposes the id's live cache state in every frame, so a late reply
  cannot recreate the cleared entries. Spec 016 §Registration.
  Spec 016 §Resource registration spec."
  (:require [re-frame.error :as rf.error]
            [re-frame.frame :as rf.frame]
            [re-frame.reg-meta :as rf.reg-meta]
            [re-frame.registrar :as rf.registrar]
            [re-frame.resources.classification :as rf.resources.classification]
            [re-frame.resources.params :as rf.resources.params]
            [re-frame.resources.scope-registry :as rf.resources.scope-registry]
            [re-frame.resources.state :as rf.resources.state]
            [re-frame.resources.timers :as rf.resources.timers]
            [re-frame.resources.work-ledger :as rf.resources.work-ledger]
            [re-frame.source-coords :as rf.source-coords]
            [re-frame.trace :as rf.trace]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- the :resource registrar kind ----------------------------------------

(def resource-kind
  "The registrar kind resources register under. Spec 016 §Registration."
  :resource)

(def resource-metadata-keys
  "The bare keys a `reg-resource` metadata map may carry. Any other bare key
  warns `:rf.warning/unknown-registration-key`; namespaced and shared
  registration-metadata keys always pass. `:request` is absent because it is
  the third slot, and inside the map it is a loud `:rf.error/resource-bad-spec`.
  Spec 016 §Resource registration spec."
  #{:doc :scope :params-schema :data-schema :tags
    :stale-after-ms :gc-after-ms :poll-interval-ms :timeout-ms
    :sensitive :large :sensitive? :large?
    :infinite :next-page-param :initial-page-param
    :page->items :refetch})

;; ---- scope-policy validation (fail-closed) -------------------------------

(def ^:private global-scope-policy
  "The explicit, auditable global-scope claim; never a default."
  :rf.scope/global)

(defn- valid-scope-policy?
  "True only for `:rf.scope/global` or a `{:from-db <id>}` reference. The
  closed two-shape set makes a typo such as `:rf.scope/glabal` a registration
  error rather than a silent literal scope. The resolver id need not be
  registered yet: references resolve at use time.
  Spec 016 §Every resource declares a scope policy (required, fail-closed)."
  [scope]
  (boolean
    (or (= scope global-scope-policy)
        (rf.resources.scope-registry/from-db-reference? scope))))

(defn- registration-error
  "The canonical thrown ex-info for a resource registration or lookup failure.
  Spec 009 §The thrown-error shape — the `:rf.error/id` ex-data contract."
  [error-id where reason extra]
  (rf.error/thrown-ex-info
    error-id
    where
    reason
    {:recovery :fix-registration
     :extra    extra}))

;; ---- :infinite registration validation ------------------------------------
;; The per-page cursor is never a registration key: `:request` reads it from
;; its reserved ctx. A non-vector page with no `:page->items` is detected at
;; the subs merge site (`state/merge-pages->items`), because registration has
;; no page to inspect. Spec 016 §Registration — `:infinite`.

(defn infinite-resource?
  "True when `spec` declares `:infinite true`. Registration and runtime gate
  the infinite slice on this one predicate."
  [spec]
  (true? (:infinite spec)))

(defn- validate-infinite-spec!
  "Throws on a malformed `:infinite` slice: `:infinite` present but not
  `true`, a missing or non-fn `:next-page-param`
  (`:rf.error/infinite-missing-next-page-param`), or a mistyped
  `:page->items` or `:refetch`. No-op for an ordinary resource.
  Spec 016 §Registration — `:infinite`."
  [resource-id spec]
  (when (contains? spec :infinite)
    (when-not (true? (:infinite spec))
      (throw (registration-error
               :rf.error/resource-bad-spec
               'rf/reg-resource
               (str "resource " resource-id " declares :infinite "
                    (pr-str (:infinite spec)) " — the :infinite flag is the "
                    "literal `true` selector for the load-more feed kind. Omit "
                    "it for an ordinary resource. Per Spec 016 §Infinite "
                    "resources and load-more feeds.")
               {:resource-id resource-id :infinite (:infinite spec)})))
    (when (infinite-resource? spec)
      (when-not (rf.resources.state/callable? (:next-page-param spec))
        (throw (registration-error
                 :rf.error/infinite-missing-next-page-param
                 'rf/reg-resource
                 (str "infinite resource " resource-id " declares no valid "
                      ":next-page-param. :infinite true makes :next-page-param "
                      "REQUIRED — a pure fn (last-page all-pages) → next-param | "
                      "nil (nil = the single terminal, no more pages). Per Spec "
                      "016 §Registration — :infinite (R8).")
                 {:resource-id resource-id :next-page-param (:next-page-param spec)})))
      (when (and (contains? spec :page->items)
                 (not (or (keyword? (:page->items spec))
                          (rf.resources.state/callable? (:page->items spec)))))
        (throw (registration-error
                 :rf.error/resource-bad-spec
                 'rf/reg-resource
                 (str "infinite resource " resource-id " declares a :page->items "
                      "that is neither a keyword nor a fn (got "
                      (pr-str (:page->items spec)) "). :page->items is the R3 "
                      "merge accessor for a non-vector / enveloped page — a "
                      "keyword key (e.g. :items) or (fn [page] → seq-of-items). "
                      "Per Spec 016 §Subscription contract (R3).")
                 {:resource-id resource-id :page->items (:page->items spec)})))
      (when (contains? spec :refetch)
        (let [refetch (:refetch spec)
              {:keys [refetch-all-pages? refetch-window]} refetch]
          (when-not (map? refetch)
            (throw (registration-error
                     :rf.error/resource-bad-spec
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
                     :rf.error/resource-bad-spec
                     'rf/reg-resource
                     (str "infinite resource " resource-id "'s :refetch "
                          ":refetch-all-pages? is not a boolean (got "
                          (pr-str refetch-all-pages?) "). Per Spec 016 §Refetch "
                          "and invalidation of an infinite feed (R6).")
                     {:resource-id resource-id :refetch refetch})))
          (when (and (contains? refetch :refetch-window)
                     (not (integer? refetch-window)))
            (throw (registration-error
                     :rf.error/resource-bad-spec
                     'rf/reg-resource
                     (str "infinite resource " resource-id "'s :refetch "
                          ":refetch-window is not an integer (got "
                          (pr-str refetch-window) "). It bounds how much of the "
                          "accumulation is refreshed. Per Spec 016 §Refetch and "
                          "invalidation of an infinite feed (R6).")
                     {:resource-id resource-id :refetch refetch}))))))
  nil))

(defn- validate-resource-spec!
  "Throws at the authoring boundary on a malformed resource spec: not a map,
  the retired `:page-data-schema`, a missing scope policy
  (`:rf.error/resource-missing-scope-policy`), a missing or non-callable
  `:request`, mistyped freshness keys, a malformed `:infinite` slice, or a
  malformed or identity-rooted classification declaration. Fails in dev and
  prod. Spec 016 §Resource registration spec."
  [resource-id spec]
  (when-not (map? spec)
    (throw (registration-error
             :rf.error/resource-bad-spec
             'rf/reg-resource
             (str "resource " resource-id "'s spec must be a map, got "
                  (pr-str (type spec)))
             {:resource-id resource-id :value spec})))
  ;; `:page-data-schema` drives neither validation nor egress, so accepting it
  ;; would silently drop the redaction an author meant its `:sensitive?` slots
  ;; to give.
  (when (contains? spec :page-data-schema)
    (throw (registration-error
             :rf.error/resource-bad-spec
             'rf/reg-resource
             (str "resource " resource-id " declares :page-data-schema — a "
                  "RETIRED key that is no longer accepted (it drives neither "
                  "validation nor egress). Move per-page VALIDATION to the "
                  ":request's :decode (a Malli schema on the returned "
                  "managed-HTTP args validates each page before it settles), "
                  "and move durable per-page egress CLASSIFICATION to "
                  "projection-relative :sensitive / :large path declarations on "
                  "the resource (e.g. {:sensitive [[:data :author-email]]} — "
                  "the index-free walk classifies the field on every page). Per "
                  "Spec 016 §Registration — :infinite and "
                  "EP-0025.")
             {:resource-id resource-id :key :page-data-schema})))
  (when-not (valid-scope-policy? (:scope spec))
    (throw (registration-error
             :rf.error/resource-missing-scope-policy
             'rf/reg-resource
             (str "resource " resource-id " declares no valid :scope policy. "
                  ":scope is REQUIRED (fail-closed) and is EXACTLY one of two "
                  "shapes: :rf.scope/global (an explicit, auditable global "
                  "claim) or {:from-db <resource-scope-id>} (a named resolver "
                  "registered with rf/reg-resource-scope). There is no implicit "
                  "default; a user-scoped read must say so. Per Spec 016 §Every "
                  "resource declares a scope policy.")
             {:resource-id resource-id :scope (:scope spec)})))
  (when-not (contains? spec :request)
    (throw (registration-error
             :rf.error/resource-bad-spec
             'rf/reg-resource
             (str "resource " resource-id " declares no :request. "
                  ":request returns a Spec 014 managed-HTTP args map. Per "
                  "Spec 016 §Resource registration spec.")
             {:resource-id resource-id})))
  ;; Presence is not enough: a non-callable `:request` would fail at the first
  ;; read as a raw host cast error, or (a keyword or map is `ifn?`) silently
  ;; lower a nil request.
  (when-not (rf.resources.state/callable? (:request spec))
    (throw (registration-error
             :rf.error/resource-bad-spec
             'rf/reg-resource
             (str "resource " resource-id "'s :request (the THIRD slot) must "
                  "be a fn, got " (pr-str (type (:request spec))) ". The "
                  "grammar is (reg-resource " resource-id " {…} "
                  "(fn [params ctx] -> managed-http-args)); a Var (#'my-fetch) "
                  "is also accepted. A non-callable :request registers cleanly "
                  "and then fails at the first read — as a raw host cast error, "
                  "or (for a keyword/map) silently as a nil request. Per Spec "
                  "016 §Resource registration spec.")
             {:resource-id resource-id :value (:request spec)})))
  ;; Called as `(tags-fn params data)` on every reply. A static set registers
  ;; cleanly otherwise and then throws at the first reply on the JVM, or on
  ;; CLJS answers `data` as the tags, so no invalidation ever matches.
  (when (and (contains? spec :tags) (not (rf.resources.state/callable? (:tags spec))))
    (throw (registration-error
             :rf.error/resource-bad-spec
             'rf/reg-resource
             (str "resource " resource-id "'s :tags must be a fn "
                  "(fn [params data] -> #{tag …}), got "
                  (pr-str (:tags spec)) ". For tags that do not depend on the "
                  "params or data, write (constantly #{…}). Per Spec 016 "
                  "§Resource registration spec.")
             {:resource-id resource-id :tags (:tags spec)})))
  ;; Checked for the reason `:stale-after-ms` is: it feeds `:deadline-at`
  ;; arithmetic at every attempt start.
  (when (contains? spec :timeout-ms)
    (let [v (:timeout-ms spec)]
      (when-not (and (number? v) (not (neg? v)))
        (throw (registration-error
                 :rf.error/resource-bad-spec
                 'rf/reg-resource
                 (str "resource " resource-id " declares a :timeout-ms that is "
                      "neither absent nor a non-negative number of milliseconds "
                      "(got " (pr-str v) "). It stamps each attempt's "
                      ":deadline-at. Per Spec 016 §Resource registration spec.")
                 {:resource-id resource-id :timeout-ms v})))))
  ;; A non-numeric interval is a typo, refused rather than silently never
  ;; polling; a non-positive one means no polling. Spec 016 §Polling.
  (when (and (contains? spec :poll-interval-ms)
             (some? (:poll-interval-ms spec))
             (not (number? (:poll-interval-ms spec))))
    (throw (registration-error
             :rf.error/resource-bad-spec
             'rf/reg-resource
             (str "resource " resource-id " declares a :poll-interval-ms that "
                  "is not a number (got " (pr-str (:poll-interval-ms spec))
                  "). :poll-interval-ms is a positive integer of milliseconds "
                  "(while actively owned + visible the entry revalidates every "
                  "N ms); a non-positive or absent value means no polling. Per "
                  "Spec 016 §Polling.")
             {:resource-id resource-id :poll-interval-ms (:poll-interval-ms spec)})))
  ;; Checked here because an unchecked value fails later in `:stale-at`
  ;; arithmetic as a host cast error (or CLJS string concatenation).
  ;; Spec 016 §Freshness clock contract.
  (when (contains? spec :stale-after-ms)
    (let [v (:stale-after-ms spec)]
      (when-not (and (number? v) (not (neg? v)))
        (throw (registration-error
                 :rf.error/resource-bad-spec
                 'rf/reg-resource
                 (str "resource " resource-id " declares a :stale-after-ms that "
                      "is neither absent nor a non-negative number of "
                      "milliseconds (got " (pr-str v) "). Absent means the "
                      "entry never goes stale on a timer; 0 means it is stale "
                      "the instant it loads. Per Spec 016 §Freshness clock "
                      "contract.")
                 {:resource-id resource-id :stale-after-ms v})))))
  (validate-infinite-spec! resource-id spec)
  (when-let [{:keys [axis reason]} (rf.resources.classification/classification-declaration-defect spec)]
    (throw (registration-error
             :rf.error/resource-bad-spec
             'rf/reg-resource
             (str "resource " resource-id " declares a malformed " axis
                  " data-classification: " reason ". Per EP-0025 a resource "
                  "declares :sensitive / :large as a vector of projection-relative "
                  "`:rf/path` vectors (e.g. {:sensitive [[:data :ssn]]}).")
             {:resource-id resource-id :axis axis :value (get spec axis)})))
  (when-let [[axis path] (rf.resources.classification/identity-rooted-declaration spec)]
    (throw (registration-error
             :rf.error/resource-bad-spec
             'rf/reg-resource
             (str "resource " resource-id " declares " (pr-str path) " under " axis
                  ", but a resource's params and scope are its identity, not a "
                  "classified slot. Off-box its scope is always tokenised, and its "
                  "params ride unless the resource claims the whole entry: declare "
                  ":sensitive? true (or :large? true) on the resource instead. "
                  "Per Spec 016 §Resource registration spec.")
             {:resource-id resource-id :axis axis :path path})))
  nil)

;; ---- :gc-after-ms normalization ------------------------------------------

(def default-gc-after-ms
  "The `:gc-after-ms` an owner-free entry gets when the resource declares
  none: 300000ms. Spec 016 §Resource cache retention defaults — `:resolved`."
  300000)

(defn- normalize-gc-after-ms
  "The stored `:gc-after-ms`: `default-gc-after-ms` when absent, `:never`
  (the explicit pinning opt-out) or a positive number unchanged. Anything else,
  including an explicit nil, throws `:rf.error/resource-bad-spec`, so an
  ambiguous value never reads as unbounded retention.
  Spec 016 §Resource registration spec."
  [resource-id spec]
  (let [present? (contains? spec :gc-after-ms)
        v        (:gc-after-ms spec)]
    (cond
      (not present?)             default-gc-after-ms
      (= v :never)               :never
      (and (number? v) (pos? v)) v
      :else
      (throw (registration-error
               :rf.error/resource-bad-spec
               'rf/reg-resource
               (str "resource " resource-id " declares a :gc-after-ms that is "
                    "neither absent, :never, nor a positive number of "
                    "milliseconds (got " (pr-str v) "). Absent defaults to "
                    default-gc-after-ms "ms (5min); :never is the "
                    "explicit, auditable opt-out for intentional "
                    "unowned-entry pinning; otherwise declare a positive "
                    "number of milliseconds. Per Spec 016 §Resource "
                    "registration spec.")
               {:resource-id resource-id :gc-after-ms v})))))

;; ---- reg-resource / clear-resource ---------------------------------------

(defn reg-resource
  "Registers a resource: `(reg-resource id metadata request-fn)`, where
  `request-fn` is `(fn [params ctx] -> managed-http-args)`. Keeping the
  handler out of the metadata map keeps that slot pure metadata for doc-DCE.
  Throws `:rf.error/resource-bad-spec` on non-map metadata or a `:request`
  inside it, and on any spec defect `validate-resource-spec!` names. Emits
  `:rf.resource/registered` on first registration. Returns `resource-id`.
  Spec 016 §Registration."
  [resource-id metadata request-fn]
  ;; Before any `assoc`, so non-map metadata raises the public error rather
  ;; than a raw host exception.
  (when-not (map? metadata)
    (throw (registration-error
             :rf.error/resource-bad-spec
             'rf/reg-resource
             (str "resource " resource-id "'s metadata (the MIDDLE slot) must "
                  "be a map, got " (pr-str (type metadata)) ". The grammar is "
                  "(reg-resource " resource-id " {…} "
                  "request-fn): the reflection/config metadata map is the "
                  "SECOND slot, the :request handler is the THIRD.")
             {:resource-id resource-id :value metadata})))
  (when (contains? metadata :request)
    (throw (registration-error
             :rf.error/resource-bad-spec
             'rf/reg-resource
             (str "resource " resource-id " declares :request inside its "
                  "metadata map — the request handler is the "
                  "THIRD slot: (reg-resource " resource-id " {…} request-fn). "
                  "Move the fetch fn out of the metadata map into the value "
                  "slot.")
             {:resource-id resource-id :value (:request metadata)})))
  (let [resource-spec (-> metadata
                          (assoc :request request-fn)
                          ;; Normalized before validation and storage, so no
                          ;; reader ever sees an absent or typo value.
                          (assoc :gc-after-ms
                                 (normalize-gc-after-ms resource-id metadata)))]
  (validate-resource-spec! resource-id resource-spec)
  ;; After the spec checks, so a key they refuse throws before anything warns.
  (rf.reg-meta/validate-registration-metadata!
    resource-kind 'rf/reg-resource resource-id metadata resource-metadata-keys)
  (let [previous (rf.registrar/lookup resource-kind resource-id)]
    (rf.registrar/register!
      resource-kind
      resource-id
      (rf.source-coords/merge-coords
        (merge {:doc (:doc resource-spec)}
               ;; The registrar map is built from the canonical spec, so the
               ;; caller's image-selection stamp would otherwise be dropped and
               ;; a programmatic registration would not be `:select-ns`-
               ;; selectable. Spec 001 §Production elision contract.
               (select-keys metadata [:ns :rf.provenance/ns])
               {:rf/resource resource-spec
                :handler-fn  (:request resource-spec)})))
    ;; First registration only: re-registration rides the registrar's
    ;; `:rf.registry/handler-replaced`. Spec 001 §Hot-reload trace surface.
    (when (nil? previous)
      (rf.trace/emit! :rf.event :rf.resource/registered
                   {:resource-id      resource-id
                    :scope-policy     (:scope resource-spec)
                    :stale-after-ms   (:stale-after-ms resource-spec)
                    :gc-after-ms      (:gc-after-ms resource-spec)
                    :poll-interval-ms (:poll-interval-ms resource-spec)})))
  resource-id))

(defn- entry-keys-for-resource
  "The scoped-key vectors of `runtime-db`'s entries for `resource-id`, read
  from each entry's stored `:resource/key` because `:entries` is keyed on the
  opaque byte key-id."
  [runtime-db resource-id]
  (into []
        (comp (map val)
              (filter (fn [entry] (= resource-id (second (:resource/key entry)))))
              (map :resource/key))
        (get-in runtime-db (rf.resources.state/entries-path))))

(defn- dispose-resource-runtime!
  "Disposes `resource-id`'s live entries in one frame. One atomic runtime-db
  swap removes the entries, their attempts with them, and recomputes the
  indexes; then the host side cancels timers and best-effort aborts in-flight
  attempts. Emits
  `:rf.resource/removed` when anything was disposed. Returns the disposed
  scoped keys. Spec 016 §Cancellation is opportunistic; stale suppression is mandatory."
  [frame-id resource-id]
  (let [runtime-db (or (rf.frame/frame-runtime-db-value frame-id) {})
        keys'      (entry-keys-for-resource runtime-db resource-id)]
    (when (seq keys')
      (let [in-flight (into []
                            (keep (fn [k]
                                    (let [e   (get-in runtime-db (rf.resources.state/entry-path k))
                                          wid (:current-work e)]
                                      (when wid
                                        [wid (:transport (rf.resources.work-ledger/live-attempt e wid))]))))
                            keys')]
        (rf.frame/swap-runtime-db!
          frame-id
          (fn [rdb]
            (-> (or rdb {})
                (update-in (rf.resources.state/entries-path)
                           (fn [es] (reduce dissoc es (map rf.resources.state/key-id keys'))))
                (update rf.resources.state/resources-key rf.resources.state/recompute-indexes))))
        ;; Correctness rests on the removed entry, not on the abort landing.
        (doseq [k keys']
          (rf.resources.timers/cancel-for-key! frame-id k))
        (doseq [[wid _transport] in-flight]
          (rf.resources.work-ledger/opportunistic-abort! frame-id wid))
        (rf.trace/emit! :rf.event :rf.resource/removed
                     {:rf.frame/id frame-id :resource-id resource-id
                      :reason :clear-resource :removed (vec keys')
                      :aborted (mapv first in-flight)})))
    keys'))

(defn clear-resource
  "Unregisters `resource-id` and disposes its live cache state in every
  frame, since entries with no registration would be an unrefreshable cache.
  Returns `resource-id`; a no-op for an unknown id. Spec 016 §Registration."
  [resource-id]
  (rf.registrar/unregister! resource-kind resource-id)
  (doseq [frame-id (rf.frame/frame-ids)]
    (dispose-resource-runtime! frame-id resource-id))
  resource-id)

;; ---- registry-side introspection -----------------------------------------
;; Artefact-internal: outside callers spell this
;;   (:rf/resource (rf/handler-meta {:kind :resource :id id}))

(defn resource-meta
  "The registered spec map for `resource-id`, or nil. Source coords live on
  the outer registrar entry, not here.
  Spec 016 §Introspection and projection (tool/test only)."
  [resource-id]
  (:rf/resource (rf.registrar/lookup resource-kind resource-id)))

(defn require-resource-spec!
  "The spec for `resource-id`; throws `:rf.error/resource-not-registered`
  when none is registered. `where` names the calling surface.
  Spec 016 §Public API."
  [resource-id where]
  (or (resource-meta resource-id)
      (throw (registration-error
               :rf.error/resource-not-registered
               where
               (str "no resource is registered under " resource-id
                    " — call rf/reg-resource before ensuring / subscribing. "
                    "Per Spec 016 §Public API.")
               {:resource-id resource-id}))))

;; ---- params validation + canonicalization ---------------------------------

(defn validate+canonicalize-params
  "The canonical params for `resource-id`, validated against its
  `:params-schema`; throws `:rf.error/resource-invalid-params` on a failure,
  carrying the slots `re-frame.resources.params` builds from the seam's
  diagnosis (`:reason :malformed-schema` when the schema itself is broken).
  An explicit nil is passed to the schema, not defaulted, so `{:params nil}`
  and an omitted `:params` stay distinct identities. The pipeline is shared
  with mutations. Spec 016 §Resource identity."
  [resource-id spec params where]
  (rf.resources.params/validate+canonicalize
    resource-id spec params where :resource-params
    (fn [slots]
      (registration-error
        :rf.error/resource-invalid-params
        where
        (if (= :malformed-schema (:reason slots))
          (str "resource " resource-id "'s :params-schema is malformed: the "
               "validator threw, so its params could not be checked. "
               "Per Spec 010 §Per-step recovery.")
          (str "resource " resource-id " params do not conform to "
               ":params-schema. Per Spec 016 §Resource identity."))
        (assoc slots :resource-id resource-id)))))

;; ---- scope resolution -----------------------------------------------------
;; Scope is the cache's leak boundary, so neither path ever falls through to
;; `:rf.scope/global`. Spec 016 §Scope resolution.

(defn- canonical-scope!
  "Canonicalizes a concrete resolved scope through the shared
  `state/canonicalize-scope`, which refuses reserved-namespace typos, host
  values and the wrapped `[:rf.scope/global]`. Spec 016 §Resource identity."
  [resource-id scope where]
  (rf.resources.state/canonicalize-scope scope where resource-id))

(defn- require-resolved-reference!
  "Resolves a `{:from-db <id>}` reference against `db`; throws
  `:rf.error/resource-scope-unresolved-reference` when it resolves nil, since
  a derived scope that cannot resolve must never fall through to another
  tier. Spec 016 §Resolver references — `{:from-db <id>}`."
  [resource-id reference db where]
  (or (rf.resources.scope-registry/resolve-from-db-reference reference db where)
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
  "The canonical scope for a resource event, in precedence order: payload
  `:scope`, then the route layer's `route-scope`, then the spec policy. A
  `{:from-db …}` reference at any tier resolves against `db` (the app-db
  coeffect; nil reads as `{}`) and throws when it resolves nil.
  Spec 016 §Resolution precedence (for events; no global fallthrough)."
  [resource-id spec {:keys [payload-scope route-scope db]} where]
  (let [policy (:scope spec)
        resolve-ref (fn [reference] (require-resolved-reference! resource-id reference db where))]
    (cond
      (rf.resources.scope-registry/from-db-reference? payload-scope)
      (canonical-scope! resource-id (resolve-ref payload-scope) where)
      (some? payload-scope) (canonical-scope! resource-id payload-scope where)
      ;; The route layer resolves references before threading them here; this
      ;; arm is defensive.
      (rf.resources.scope-registry/from-db-reference? route-scope)
      (canonical-scope! resource-id (resolve-ref route-scope) where)
      (some? route-scope)   (canonical-scope! resource-id route-scope where)
      (rf.resources.scope-registry/from-db-reference? policy)
      (canonical-scope! resource-id (resolve-ref policy) where)
      (= policy global-scope-policy) global-scope-policy
      ;; Registration admits no third shape; fail closed rather than key the
      ;; cache on nil.
      :else
      (throw (registration-error
               :rf.error/resource-missing-scope-policy
               where
               (str "resource " resource-id " carries an invalid :scope policy "
                    (pr-str policy) ". A scope policy is EXACTLY :rf.scope/global "
                    "or {:from-db <resource-scope-id>}. Per Spec 016 §Every "
                    "resource declares a scope policy.")
               {:resource-id resource-id :scope policy})))))

(defn- resolve-sub-reference
  "Resolves a `{:from-db <id>}` reference for a subscription, or nil. A nil
  resolution is ordinary app state (no principal yet), so the sub projects
  `:status :unresolved` instead of throwing; an unregistered or failing
  resolver stays loud. Spec 016 §Subscription-side scope resolution."
  [reference db where]
  ;; The trace-free evaluator: a sub re-keys on every frame-state change and
  ;; must not emit `:rf.resource/scope-resolved`.
  (rf.resources.scope-registry/resolve-from-db-reference-pure reference db where))

(defn resolve-scope-for-sub
  "The canonical scope for a resource subscription: payload `:scope`, then
  the spec policy; a sub never sees the route tier. Returns nil when a
  `{:from-db …}` reference resolves nil, so the sub reads no entry rather than
  global. `db` is the frame app-db; pass `{}` when no reference is involved.
  Spec 016 §Subscription-side scope resolution."
  [resource-id spec payload-scope where db]
  (let [policy (:scope spec)]
    (cond
      (rf.resources.scope-registry/from-db-reference? payload-scope)
      (when-let [scope (resolve-sub-reference payload-scope db where)]
        (canonical-scope! resource-id scope where))
      (some? payload-scope) (canonical-scope! resource-id payload-scope where)
      (rf.resources.scope-registry/from-db-reference? policy)
      (when-let [scope (resolve-sub-reference policy db where)]
        (canonical-scope! resource-id scope where))
      (= policy global-scope-policy) global-scope-policy
      ;; Registration admits no third shape; fail closed rather than read a
      ;; nil-scoped entry.
      :else
      (throw (registration-error
               :rf.error/resource-missing-scope-policy
               where
               (str "resource " resource-id " carries an invalid :scope policy "
                    (pr-str policy) ". A scope policy is EXACTLY :rf.scope/global "
                    "or {:from-db <resource-scope-id>}. Per Spec 016 §Every "
                    "resource declares a scope policy.")
               {:resource-id resource-id :scope policy})))))
