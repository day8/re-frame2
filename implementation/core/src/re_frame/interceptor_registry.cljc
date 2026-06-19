(ns re-frame.interceptor-registry
  "Registered interceptors — the `:interceptor` registrar kind and the
  by-reference chain resolution (EP-0022; reference-only since rf2-0adhqs.9).

  Per [Spec 001 §Interceptors](../../../spec/001-Registration.md) and
  [Spec 002 §Registered interceptors and the chain grammar](../../../spec/002-Frames.md):

  An interceptor is a first-class *registered* program member — keyed by a
  qualified-keyword id, carrying source coordinates + metadata, and referenced
  from event/frame `:interceptors` chains by id. Chains carry REFERENCES ONLY;
  an inline interceptor value in a chain is `:rf.error/inline-interceptor-removed`.

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
    `:id` it MUST match the positional registration id. This is the AUTHORING
    INPUT — `(reg-interceptor :my/ic <inline-value-or-descriptor>)` — and is
    the ONLY place an inline interceptor value is legal. Chains never carry one.

  - `interceptor-ref?` / ref resolution — turn a reference (bare keyword, or
    `[id arg]` 2-vector) into an executable interceptor value, looked up
    through the active (realm-aware) registrar. A bare keyword resolves a
    static descriptor; an `[id arg]` vector resolves a `:factory` and builds
    for the arg.

  - `resolve-chain` — the chain-assembly seam (EP-0022 reference-only flip,
    rf2-0adhqs.9). Walk an event/frame `:interceptors` chain and resolve every
    REFERENCE to its registered interceptor value. A stale INLINE interceptor
    value (a map carrying `:before` / `:after` / `:id`, a `->interceptor` call
    result, a value-Var) in a chain position fails LOUD with
    `:rf.error/inline-interceptor-removed` — chains are reference-only. The ONE
    inline value that flows through is the framework's own appended handler-
    wrapper (`:rf/default? true`), which is framework machinery, not an
    application-authored chain entry. (The additive window where refs and
    inline values coexisted is closed — mirrors EP-0018 B-add→Z.)

  ## Realm-awareness (rf2-a15n62)

  Resolution goes through `registrar/lookup`, which reads the active
  registrar (`registrar/*registrar*` when an explicit-realm seating is in
  flight, else the process-default). Refs therefore resolve through the owning
  realm's registrar wherever an explicit realm is bound — no realm argument is
  threaded here (per Spec 002 §Effective chain ordering — the lookup direction
  is recorded; no realm-patching API is added).

  ## The framework default-wrapper carve-out

  The framework appends its handler-wrapping interceptor (`:rf/event-handler`,
  stamped `:rf/default? true`) to the tail of every event's stored
  `:interceptors` chain (see `re-frame.events/register-event!`). That entry is
  an inline interceptor VALUE, but it is FRAMEWORK machinery — not an
  application-authored chain entry — so `resolve-chain` lets a `:rf/default?
  true` value pass through untouched. Every OTHER inline value in a chain is
  application-authored and rejected `:rf.error/inline-interceptor-removed`."
  (:require [re-frame.registrar :as registrar]
            [re-frame.interceptor :as interceptor]
            [re-frame.identity :as identity]
            [re-frame.error :as error]
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
  (error/throw-error!
    :rf.error/invalid-interceptor
    'rf/reg-interceptor
    reason
    {:recovery :fix-registration
     :extra    {:id       id
                :got      descriptor
                :expected "one of {:before f} / {:after f} / {:before f :after g} / {:factory f}"}}))

(defn- throw-interceptor-id-mismatch!
  [id descriptor value-id]
  (error/throw-error!
    :rf.error/invalid-interceptor
    'rf/reg-interceptor
    (str "reg-interceptor for `" id "` received an interceptor "
         "VALUE carrying `:id " value-id "`, which does NOT match "
         "the positional registration id `" id "`. The migration "
         "boundary requires the value's `:id` (when present) to "
         "match the registration id.")
    {:recovery :fix-registration
     :extra    {:id  id
                :got descriptor}}))

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
  "True when `x` is an interceptor VALUE — a map carrying an executable slot
  (`:before` / `:after`) or an `:id`. Distinguishes an interceptor value from a
  reference (a keyword / `[id arg]` vector).

  An interceptor value is legal ONLY at the `reg-interceptor` REGISTRATION
  boundary (the authoring input) and as the framework's own appended handler-
  wrapper. In an event/frame `:interceptors` CHAIN an application-authored
  interceptor value is `:rf.error/inline-interceptor-removed` (EP-0022
  reference-only flip, rf2-0adhqs.9) — chains carry references only. This
  predicate is the shape detector both the registration boundary and the
  chain's stale-inline rejection share."
  [x]
  (and (map? x)
       (or (contains? x :before)
           (contains? x :after)
           (contains? x :id))))

;; `framework-default-interceptor?` (the `(map? x)`-guarded `:rf/default?`
;; predicate) is the ONE inline interceptor value `resolve-chain` lets pass
;; through a chain untouched: it is framework machinery (the terminal
;; `:before` that invokes the user handler), not an application-authored
;; chain entry, so the reference-only flip (rf2-0adhqs.9) does not reject
;; it. The predicate lives in `re-frame.interceptor` (already required here)
;; and is shared with that ns's `invoke-after` ctx-delta-capture gate —
;; one copy, not two (rf2-ih437c). Call it as
;; `interceptor/framework-default-interceptor?`.

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

;; ---- exact-reference matching (Spec 002 §`:interceptor-overrides`) ---------
;;
;; The reserved slot a resolved chain entry carries so its AUTHORED reference
;; survives resolution — `:interceptor-overrides` exact-reference matching
;; (EP-0022 Slice C) keys on this. A bare-keyword ref stamps the keyword; an
;; `[id arg]` ref stamps the full vector. An entry without an authored ref
;; (the framework default-wrapper, which is the only inline value a chain
;; carries since the reference-only flip) matches overrides by `:id` only.

(def authored-ref-key
  "The reserved key under which a resolved chain entry carries its AUTHORED
  interceptor reference (the bare keyword or `[id arg]` vector that produced
  it). Read by the override matcher for exact-reference matching."
  :rf/interceptor-ref)

(defn ref=
  "True when interceptor references `a` and `b` denote the SAME reference under
  CEDN-1 canonical identity (EP-0012). A fast structural `=` short-circuits the
  common case (both refs are already canonical EDN data — a keyword or an
  `[id arg]` vector of EDN); a canonical-bytes comparison is the fallback that
  makes two arg spellings differing only in map-key order match. Neither ref
  needs to be a keyword — this is plain reference identity, not id identity."
  [a b]
  (or (= a b)
      (try
        (identity/identical-identity? a b)
        ;; A non-EDN arg can never appear in a serializable override key /
        ;; authored ref, but fail-soft to "not equal" rather than letting a
        ;; canonicalization throw escape the override walk.
        (catch #?(:clj Throwable :cljs :default) _ false))))

(defn override-key-matches?
  "True when `override-key` (an `:interceptor-overrides` map key — a bare
  keyword or `[id arg]` ref) matches the resolved chain `entry` per Spec 002
  §`:interceptor-overrides` exact-reference matching:

    - a bare KEYWORD key matches when it equals the entry's AUTHORED ref (a
      bare-keyword ref) OR the entry's `:id` (the `:id` the resolver stamps —
      so a bare-keyword key can match a `[id arg]`-resolved entry by its id);
    - an `[id arg]` VECTOR key matches ONLY the entry whose AUTHORED ref is
      `ref=` to that exact `[id arg]` vector — so `{[:rf.interceptor/path
      [:cart]] nil}` removes only that exact reference and leaves a sibling
      `[:rf.interceptor/path [:cart :items]]` intact.

  `entry` is a resolved executable interceptor value; its authored ref (when
  it came from a reference) rides `authored-ref-key`."
  [override-key entry]
  (let [authored (get entry authored-ref-key)]
    (cond
      (keyword? override-key)
      (or (and (keyword? authored) (= override-key authored))
          (= override-key (:id entry)))
      ;; An `[id arg]` key matches ONLY by exact authored-ref identity.
      (and (vector? override-key) (= 2 (count override-key)))
      (and (some? authored) (ref= override-key authored))
      :else false)))

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
  (error/throw-error!
    :rf.error/unregistered-interceptor
    'rf/resolve-interceptor-ref
    (str "interceptor reference `" (pr-str ref) "` names id `"
         id "`, which is not registered. Register it with "
         "`reg-interceptor` before referencing it from an "
         "event/frame `:interceptors` chain.")
    {:recovery :fix-registration
     :extra    {:ref ref
                :id  id}}))

(defn- throw-factory-arity!
  [ref id reason]
  (error/throw-error!
    :rf.error/interceptor-factory-arity
    'rf/resolve-interceptor-ref
    reason
    {:recovery :fix-registration
     :extra    {:ref ref
                :id  id}}))

(defn- throw-invalid-ref!
  [ref]
  (error/throw-error!
    :rf.error/invalid-interceptor-ref
    'rf/resolve-interceptor-ref
    (str "interceptor chain entry `" (pr-str ref) "` is neither a "
         "keyword id nor an `[id arg]` 2-vector reference. "
         "Interceptor chains are reference-only (EP-0022): register "
         "the interceptor with `reg-interceptor` and reference it by id.")
    {:recovery :fix-registration
     :extra    {:ref      ref
                :expected "a keyword id or an [id arg] vector"}}))

(defn- throw-inline-interceptor-removed!
  "Raise `:rf.error/inline-interceptor-removed` (ex-info) for an INLINE
  interceptor value found in a chain position. Per EP-0022 §Event and frame
  chain grammar (the reference-only flip, rf2-0adhqs.9): event/frame
  `:interceptors` chains carry REFERENCES only — a bare keyword id or an
  `[id arg]` 2-vector. An inline interceptor map / value / Var (an
  `->interceptor` result, a `(path …)` value, a `(redact-interceptor …)`
  value, a locally-bound interceptor symbol) is no longer accepted; it must be
  registered with `reg-interceptor` and referenced by id. Loud-fail at chain
  assembly rather than a silent no-op (Conventions §No silent swallow).
  Delegates to the ONE shared `error/throw-inline-interceptor-removed!`
  (rf2-8au0w6) passing this site's `:where 'rf/resolve-chain`, reason, and
  ex-data — the registration-time twin (`re-frame.events`) shares the same
  thrower."
  [entry]
  (error/throw-inline-interceptor-removed!
    'rf/resolve-chain
    (str "an event/frame `:interceptors` chain carried an INLINE "
         "interceptor value `" (pr-str entry) "`. Interceptor "
         "chains are reference-only (EP-0022): register the "
         "interceptor with `reg-interceptor` and reference it by id "
         "— a bare keyword `:my/ic` or an `[id arg]` 2-vector "
         "(e.g. `[:rf.interceptor/path [:cart]]`).")
    {:entry    entry
     :expected "a keyword id or an [id arg] vector reference"}))

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
                  ;; A factory MAY raise its own structured `:rf.error/*`
                  ;; ex-info (e.g. the standard path factory's
                  ;; `:rf.error/path-interceptor-bad-path` on a non-vector arg
                  ;; — Spec 002 §Error model). Let such deliberate, classified
                  ;; errors propagate VERBATIM rather than masking them under
                  ;; `:rf.error/interceptor-factory-arity`; only genuinely
                  ;; unexpected throws are wrapped as a factory-arity failure.
                  (if (and (instance? #?(:clj clojure.lang.ExceptionInfo
                                         :cljs cljs.core.ExceptionInfo) e)
                           (:rf.error/id (ex-data e)))
                    (throw e)
                    (throw-factory-arity!
                      ref id
                      (str "the `:factory` for interceptor `" id "` threw while building "
                           "for arg `" (pr-str arg) "`: " #?(:clj (.getMessage ^Throwable e)
                                                             :cljs (.-message e)) ".")))))]
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
  interceptor values. REFERENCE-ONLY (EP-0022 flip, rf2-0adhqs.9): a REFERENCE
  (keyword / `[id arg]`) resolves to its registered interceptor value. A stale
  INLINE interceptor value (a map carrying `:before` / `:after` / `:id`, an
  `->interceptor` result, a value-Var) in a chain position fails LOUD with
  `:rf.error/inline-interceptor-removed` — chains are reference-only. A
  structurally-malformed entry (neither a ref, an inline value, nor the
  framework default) is `:rf.error/invalid-interceptor-ref`.

  The ONE inline value that passes through untouched is the framework's own
  appended handler-wrapper (`:rf/default? true` — `interceptor/framework-default-interceptor?`):
  it is framework machinery, not an application-authored chain entry.

  `chain` is a sequential of refs (+ the framework default tail); returns a
  vector of executable interceptor values suitable for
  `re-frame.interceptor/execute-chain`.

  A resolved-from-reference entry is stamped with its AUTHORED reference under
  `authored-ref-key` (`:rf/interceptor-ref`) so `:interceptor-overrides`
  exact-reference matching (Spec 002 §`:interceptor-overrides`) can match the
  full `[id arg]` it came from — not merely its `:id`.

  Refs resolve through the active (realm-aware) registrar — see the ns docstring."
  [chain]
  (if-not (seq chain)
    (vec chain)
    (mapv (fn [entry]
            (cond
              ;; A reference — resolve it and stamp the AUTHORED ref so exact-
              ;; reference override matching can key on the full `[id arg]`.
              ;; (Checked FIRST: a ref is a keyword / [id arg] vector, never a
              ;; map, so there is no ambiguity with an inline value.)
              (interceptor-ref? entry)
              (assoc (resolve-ref entry) authored-ref-key entry)
              ;; The framework's own appended handler-wrapper (`:rf/default?
              ;; true`) — framework machinery, not an authored chain entry;
              ;; passes through untouched (the reference-only carve-out).
              (interceptor/framework-default-interceptor? entry) entry
              ;; A stale INLINE interceptor value — reference-only flip rejects
              ;; it LOUD (EP-0022, rf2-0adhqs.9): register it + reference by id.
              (interceptor-value? entry) (throw-inline-interceptor-removed! entry)
              :else (throw-invalid-ref! entry)))
          chain)))

(defn chain-needs-resolution?
  "True when `chain` carries at least one entry `resolve-chain` must act on —
  an interceptor REFERENCE (resolved) OR a non-framework-default inline VALUE
  (rejected `:rf.error/inline-interceptor-removed`). A hot-path predicate the
  resolution seams use to skip the walk when a chain is nothing but the
  framework's appended handler-wrapper (the common all-default shape — an event
  with no authored chain). Reference-only since EP-0022 (rf2-0adhqs.9): a
  chain carrying ONLY the framework default needs no resolution; the moment it
  carries a ref OR a stale inline value, `resolve-chain` must walk it (to
  resolve the ref, or to reject the inline value loudly)."
  [chain]
  (boolean (some (fn [entry]
                   (and (not (interceptor/framework-default-interceptor? entry))
                        (or (interceptor-ref? entry)
                            (interceptor-value? entry))))
                 chain)))
