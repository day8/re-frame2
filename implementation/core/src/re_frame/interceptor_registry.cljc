(ns re-frame.interceptor-registry
  "Registered interceptors — the `:interceptor` registrar kind and the
  by-reference chain resolution (EP-0022, rf2-0adhqs.2 Slice B, ADDITIVE).

  Per [Spec 001 §Interceptors](../../../spec/001-Registration.md) and
  [Spec 002 §Registered interceptors and the chain grammar](../../../spec/002-Frames.md):

  An interceptor is now a first-class *registered* program member — keyed by a
  qualified-keyword id, carrying source coordinates + metadata, and referenced
  from event/frame `:interceptors` chains by id rather than embedded inline.

  ## What this ns owns

  - `reg-interceptor*` — register a DESCRIPTOR under the `:interceptor` kind.
    The four descriptor forms (per Spec 001 §`reg-interceptor`):
      {:before f}            — static
      {:after f}             — static
      {:before f :after g}   — static
      {:factory f}           — parameterized family; `f` receives ONE arg and
                               returns a descriptor / executable interceptor
    A malformed descriptor is `:rf.error/invalid-interceptor` at registration.
    The migration boundary (Spec 001) also accepts an existing interceptor
    VALUE here (a map carrying `:before` / `:after` / `:id`); if it carries an
    `:id` it MUST match the positional registration id.

  - `interceptor-ref?` / ref resolution — turn a reference (bare keyword, or
    `[id arg]` 2-vector) into an executable interceptor value, looked up
    through the active (realm-aware) registrar. A bare keyword resolves a
    static descriptor; an `[id arg]` vector resolves a `:factory` and builds
    for the arg.

  - `resolve-chain` — the chain-assembly seam. Walk an event/frame
    `:interceptors` chain and resolve every REFERENCE to its registered
    interceptor value, leaving INLINE interceptor values untouched (the
    ADDITIVE window — refs and inline values coexist; the reference-only flip
    that rejects inline values is a LATER slice, mirroring EP-0018 B-add→Z).

  ## Realm-awareness (rf2-a15n62)

  Resolution goes through `registrar/lookup`, which reads the active
  registrar (`registrar/*registrar*` when an explicit-realm seating is in
  flight, else the process-default). Refs therefore resolve through the owning
  realm's registrar wherever an explicit realm is bound — no realm argument is
  threaded here (per Spec 002 §Effective chain ordering — the lookup direction
  is recorded; no realm-patching API is added).

  ## Deferred to later slices (NOT here)

  - The standard `:rf.interceptor/path` impl preserving the frame-commit
    `identical?` no-op (a MINIMAL path factory is registered in
    `re-frame.std-interceptors` so `[:rf.interceptor/path …]` refs resolve —
    noted there as minimal).
  - `:interceptor-overrides` exact-reference (`[id arg]`) matching — overrides
    still match by `:id` (the existing additive behaviour); refs in an
    override map's keys/values ARE resolved here, but the canonical-arg exact
    match is a later slice.
  - `->interceptor` internal-only lowering changes; the reference-only removal
    flip."
  (:require [re-frame.registrar :as registrar]
            [re-frame.interceptor :as interceptor]
            [re-frame.source-coords :as source-coords]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- the interceptor registry kind ----------------------------------------

(def interceptor-kind
  "The registrar kind registered interceptors live under (Spec 001
  §Interceptors). A constant so the resolver + the reg path agree."
  :interceptor)

;; ---- descriptor validation (Spec 001 §`reg-interceptor`) ------------------
;;
;; `descriptor` is one of the four canonical shapes, OR (the migration
;; boundary) an existing interceptor VALUE map. We accept any map carrying at
;; least one executable slot (`:before` / `:after`) or `:factory`. An `:id`-only
;; / `:before`+`:after`+`:factory` mix / non-map is `:rf.error/invalid-interceptor`.

(defn- factory-descriptor? [descriptor]
  (and (map? descriptor) (contains? descriptor :factory)))

(defn- static-descriptor? [descriptor]
  (and (map? descriptor)
       (or (contains? descriptor :before)
           (contains? descriptor :after))))

(defn- valid-descriptor?
  "True when `descriptor` is one of the accepted `reg-interceptor` shapes:
  a `{:factory f}` parameterized descriptor, OR a static descriptor /
  interceptor value carrying `:before` / `:after`. A `:factory` and a
  `:before` / `:after` in the SAME map is ambiguous and rejected."
  [descriptor]
  (cond
    (not (map? descriptor)) false
    (factory-descriptor? descriptor) (not (static-descriptor? descriptor))
    :else (static-descriptor? descriptor)))

(defn- throw-invalid-interceptor!
  [id descriptor reason]
  (throw (ex-info ":rf.error/invalid-interceptor"
                  {:rf.error/id :rf.error/invalid-interceptor
                   :where       'rf/reg-interceptor
                   :id          id
                   :recovery    :fix-registration
                   :reason      reason
                   :got         descriptor
                   :expected    "one of {:before f} / {:after f} / {:before f :after g} / {:factory f}"})))

(defn- throw-interceptor-id-mismatch!
  [id descriptor value-id]
  (throw (ex-info ":rf.error/invalid-interceptor"
                  {:rf.error/id :rf.error/invalid-interceptor
                   :where       'rf/reg-interceptor
                   :id          id
                   :recovery    :fix-registration
                   :reason      (str "reg-interceptor for `" id "` received an interceptor "
                                     "VALUE carrying `:id " value-id "`, which does NOT match "
                                     "the positional registration id `" id "`. The migration "
                                     "boundary requires the value's `:id` (when present) to "
                                     "match the registration id.")
                   :got         descriptor})))

;; ---- registration ---------------------------------------------------------

(defn reg-interceptor*
  "Programmatic / REPL form of `reg-interceptor` (the `*`-suffix fn, per
  Conventions §`*`-suffix naming) — no macro source-coordinate capture.

  Arities:
    (reg-interceptor* id descriptor)
    (reg-interceptor* id metadata descriptor)

  Registers `descriptor` (one of `{:before}` / `{:after}` / `{:before :after}`
  / `{:factory}`, or — at the migration boundary — an existing interceptor
  value) under the `:interceptor` kind keyed by the qualified-keyword `id`.
  `metadata` is the standard Spec 001 registration metadata map (`:doc`,
  `:schema`, `:tags`, source coords, …).

  A malformed descriptor is `:rf.error/invalid-interceptor` at registration.
  An interceptor value carrying an `:id` that disagrees with `id` is rejected.

  Returns `id` (the `reg-*` return-value convention, Spec 001 §Return value)."
  ([id descriptor] (reg-interceptor* id {} descriptor))
  ([id metadata descriptor]
   (when-not (valid-descriptor? descriptor)
     (throw-invalid-interceptor!
       id descriptor
       (str "reg-interceptor for `" id "` received a malformed descriptor; "
            "expected one of {:before f} / {:after f} / {:before f :after g} "
            "/ {:factory f} (or, at the migration boundary, an interceptor "
            "value carrying `:before` / `:after`).")))
   ;; Migration boundary: an interceptor VALUE carrying an `:id` must agree
   ;; with the positional registration id (Spec 001 §`reg-interceptor`).
   (let [value-id (when (and (map? descriptor) (not (factory-descriptor? descriptor)))
                    (:id descriptor))]
     (when (and (some? value-id) (not= value-id id))
       (throw-interceptor-id-mismatch! id descriptor value-id)))
   (registrar/register! interceptor-kind id
                        (-> metadata
                            source-coords/merge-coords
                            (assoc :rf/interceptor-descriptor descriptor)))
   id))

;; ---- reference detection + resolution (Spec 002 §Interceptor references) ---

(defn interceptor-value?
  "True when `x` is an INLINE interceptor value — a map carrying an executable
  slot (`:before` / `:after`). These are the values that flow through the chain
  unchanged during the ADDITIVE window. Distinguishes an inline interceptor map
  from a registered DESCRIPTOR / a reference; an `:id`-only map (a named no-op
  interceptor) also counts (it is a runnable value, not a ref)."
  [x]
  (and (map? x)
       (or (contains? x :before)
           (contains? x :after)
           (contains? x :id))))

(defn interceptor-ref?
  "True when `x` is an interceptor REFERENCE (Spec 002 §Interceptor
  references): a bare keyword id, or a 2-element `[id arg]` vector whose head
  is a keyword. A reference resolves to a registered interceptor value at
  chain assembly."
  [x]
  (or (keyword? x)
      (and (vector? x)
           (= 2 (count x))
           (keyword? (first x)))))

(defn- descriptor->interceptor
  "Lower a registered static descriptor (`{:before}` / `{:after}` /
  `{:before :after}`, or an interceptor value carrying `:before` / `:after`)
  into an executable interceptor value, stamping `id` so chain tooling +
  override-by-id matching see the registered id. An interceptor value that
  already carries its own `:id` keeps it (the migration boundary guarantees it
  equals `id`)."
  [id descriptor]
  (if (contains? descriptor :id)
    descriptor
    (interceptor/->interceptor*
      :id     id
      :before (:before descriptor)
      :after  (:after descriptor))))

(defn- throw-unregistered-interceptor!
  [ref id]
  (throw (ex-info ":rf.error/unregistered-interceptor"
                  {:rf.error/id :rf.error/unregistered-interceptor
                   :where       'rf/resolve-interceptor-ref
                   :ref         ref
                   :id          id
                   :recovery    :fix-registration
                   :reason      (str "interceptor reference `" (pr-str ref) "` names id `"
                                     id "`, which is not registered. Register it with "
                                     "`reg-interceptor` before referencing it from an "
                                     "event/frame `:interceptors` chain.")})))

(defn- throw-factory-arity!
  [ref id reason]
  (throw (ex-info ":rf.error/interceptor-factory-arity"
                  {:rf.error/id :rf.error/interceptor-factory-arity
                   :where       'rf/resolve-interceptor-ref
                   :ref         ref
                   :id          id
                   :recovery    :fix-registration
                   :reason      reason})))

(defn- throw-invalid-ref!
  [ref]
  (throw (ex-info ":rf.error/invalid-interceptor-ref"
                  {:rf.error/id :rf.error/invalid-interceptor-ref
                   :where       'rf/resolve-interceptor-ref
                   :ref         ref
                   :recovery    :fix-registration
                   :reason      (str "interceptor chain entry `" (pr-str ref) "` is neither a "
                                     "keyword id nor an `[id arg]` 2-vector reference, nor an "
                                     "inline interceptor value.")
                   :expected    "a keyword id, an [id arg] vector, or an interceptor value"})))

(defn- resolve-factory
  "Resolve a parameterized `[id arg]` ref against its registered `:factory`
  descriptor. The factory receives the single `arg` and returns a static
  descriptor or an executable interceptor value, which we lower to an
  executable interceptor (stamped with `id`)."
  [ref id arg descriptor]
  (when-not (factory-descriptor? descriptor)
    (throw-factory-arity!
      ref id
      (str "parameterized interceptor reference `" (pr-str ref) "` targets id `"
           id "`, which is registered as a STATIC interceptor (no `:factory`). "
           "Only `:factory` interceptors accept an `[id arg]` reference.")))
  (let [built (try
                ((:factory descriptor) arg)
                (catch #?(:clj Throwable :cljs :default) e
                  (throw-factory-arity!
                    ref id
                    (str "the `:factory` for interceptor `" id "` threw while building "
                         "for arg `" (pr-str arg) "`: " #?(:clj (.getMessage ^Throwable e)
                                                           :cljs (.-message e)) "."))))]
    (cond
      ;; Factory returned an executable interceptor value (a map with
      ;; :before / :after / :id) — STAMP the registry id over it so the
      ;; resolved chain entry carries the registered factory id (e.g.
      ;; `:rf.interceptor/path`) for override-by-id matching + tooling, even
      ;; when the built interceptor stamped its own internal id.
      (interceptor-value? built) (assoc built :id id)
      ;; Factory returned a static descriptor — lower it.
      (static-descriptor? built) (descriptor->interceptor id built)
      :else
      (throw-factory-arity!
        ref id
        (str "the `:factory` for interceptor `" id "` returned a value that is "
             "neither a static descriptor (`{:before}` / `{:after}`) nor an "
             "executable interceptor for arg `" (pr-str arg) "`: " (pr-str built) ".")))))

(defn resolve-ref
  "Resolve an interceptor REFERENCE (bare keyword or `[id arg]` 2-vector) to an
  executable interceptor value, looked up through the active (realm-aware)
  registrar. Throws `:rf.error/unregistered-interceptor` when the id is absent,
  `:rf.error/interceptor-factory-arity` when an `[id arg]` ref targets a
  non-factory (or the factory cannot build), and `:rf.error/invalid-interceptor-ref`
  for a structurally-malformed entry.

  Per Spec 002 §Validation and resolution timing: resolution happens at
  chain assembly, so a hot-reloaded interceptor descriptor is picked up on the
  next dispatch without re-registering the event."
  [ref]
  (cond
    (keyword? ref)
    (let [meta (registrar/lookup interceptor-kind ref)]
      (when (nil? meta)
        (throw-unregistered-interceptor! ref ref))
      (let [descriptor (:rf/interceptor-descriptor meta)]
        (when (factory-descriptor? descriptor)
          (throw-factory-arity!
            ref ref
            (str "interceptor reference `" ref "` is a bare keyword, but id `" ref
                 "` is registered as a `:factory` interceptor — a factory MUST be "
                 "referenced as `[" ref " arg]`.")))
        (descriptor->interceptor ref descriptor)))

    (and (vector? ref) (= 2 (count ref)) (keyword? (first ref)))
    (let [[id arg] ref
          meta     (registrar/lookup interceptor-kind id)]
      (when (nil? meta)
        (throw-unregistered-interceptor! ref id))
      (resolve-factory ref id arg (:rf/interceptor-descriptor meta)))

    :else
    (throw-invalid-ref! ref)))

;; ---- chain resolution (the chain-assembly seam) ---------------------------

(defn resolve-chain
  "Resolve an event/frame `:interceptors` chain entry-by-entry into executable
  interceptor values. ADDITIVE (Slice B): a REFERENCE (keyword / `[id arg]`)
  resolves to its registered interceptor value; an INLINE interceptor value (a
  map carrying `:before` / `:after` / `:id`) flows through UNCHANGED (the
  additive window — refs and old inline values coexist). A structurally-
  malformed entry (neither a ref nor an inline value) is
  `:rf.error/invalid-interceptor-ref`.

  `chain` is a sequential of refs/values; returns a vector of executable
  interceptor values suitable for `re-frame.interceptor/execute-chain`.

  Refs resolve through the active (realm-aware) registrar — see the ns docstring."
  [chain]
  (if-not (seq chain)
    (vec chain)
    (mapv (fn [entry]
            (cond
              ;; Inline interceptor value — additive window; passes through.
              ;; (Checked BEFORE ref-detection: an inline value is a map, never
              ;; a keyword or [id arg] vector, so there is no ambiguity.)
              (interceptor-value? entry) entry
              ;; A reference — resolve it.
              (interceptor-ref? entry)   (resolve-ref entry)
              :else                      (throw-invalid-ref! entry)))
          chain)))

(defn chain-has-ref?
  "True when `chain` carries at least one interceptor REFERENCE (so it needs
  ref resolution). A hot-path predicate the resolution seams use to skip the
  walk when a chain is all inline values (the common pre-EP-0022 shape)."
  [chain]
  (boolean (some interceptor-ref? chain)))

;; ---- handler-meta surface (Spec 001 §Source coords, metadata, handler-meta)-

(defn interceptor-handler-meta
  "The `handler-meta :interceptor` surface — the registered interceptor's
  metadata + source coordinate by id, looked up through the active registrar
  (Spec 001 §Source coords, metadata, and `handler-meta`). Returns nil when no
  interceptor is registered under `id`. The stored `:rf/interceptor-descriptor`
  is retained on the returned meta so tooling can introspect the descriptor
  shape; production strips the pure-documentation `:doc` per the registrar
  policy."
  [id]
  (registrar/lookup interceptor-kind id))
