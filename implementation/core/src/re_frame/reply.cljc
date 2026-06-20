(ns re-frame.reply
  "Internal substrate for the uniform async reply envelope (EP-0011).

  This is the shared, host-handle-free substrate every managed *async*
  effect family lowers its completion onto — HTTP ([014]), resources +
  mutations ([016]), machine async work ([005]), route loaders ([012]),
  and any future managed timer / background-job surface. The canonical
  normative contract is `spec/Managed-Effects.md` §The uniform reply
  envelope; EP-0011 is the rationale record. This namespace is the CLJS
  reference realisation of that contract's *pure* core:

    - target normalization (vector-prefix ↔ descriptor form),
    - completion (append the reply map to the target per `:delivery`),
    - reply-target mapping — the functor law (`map-completed-event`
      changes ONLY the completed event, never issuance / `:work/id` /
      status / cancellation / staleness / tracing),
    - reply-map schema validation (the closed `:status` taxonomy +
      value/error conventions + the data-only invariant),
    - data-only trace summaries (every wire-bearing slot routes through
      the shared `re-frame.elision/elide-wire-value` walker — never a
      family-private elider),
    - the stale-suppression helper (the correctness boundary: validate
      carried-vs-current correlation, and on mismatch produce a
      `:status :stale` reply WITHOUT dispatching the app target).

  INTERNAL. Nothing here is a `re-frame.core` façade export. The public
  surface is `:rf/reply-to` (the target key) and the reply map a reducer
  receives; families consume these helpers to build that surface
  uniformly. Pure — no host handles, no runtime state, no I/O. Host
  resources (AbortControllers, timer handles, transport promises, DOM
  nodes) live in a host-transient side-table keyed by `[frame-id
  work-id]`, NEVER in a reply map or a target.

  Why core. The reply substrate is shared by HTTP (`day8/re-frame2-http`),
  resources (`day8/re-frame2-resources`), machines, routing, and future
  surfaces; pushing it to core lets every feature artefact `:require` it
  without inverting the dependency direction (Spec 006 §Adapter shipping
  convention). It carries no Malli dependency: schema validation routes
  through `re-frame.spec`'s best-effort `(resolve 'malli.core/validate)`
  seam, which no-ops when the schemas artefact is absent (same posture as
  `re-frame.epoch` / `re-frame.http.managed`)."
  (:require [re-frame.elision :as elision]
            [re-frame.error :as error]))

#?(:clj (set! *warn-on-reflection* true))

;; ---------------------------------------------------------------------------
;; The closed status + work-status vocabularies (Managed-Effects §Status
;; taxonomy). Closed by design — a managed async completion is exactly one of
;; these five statuses. Exposed as data so families and conformance pin the
;; same set without re-spelling it.
;; ---------------------------------------------------------------------------

(def statuses
  "The CLOSED reply `:status` vocabulary (Managed-Effects §Status taxonomy).
  Exactly one of these is the reply's status. `:ok`/`:partial` carry
  `:value`; `:error`/`:partial` carry `:error`; `:cancelled` carries
  `:cancel/reason`; `:stale` carries `:stale? true` + `:stale/reason` and
  MUST NOT mutate app state."
  #{:ok :partial :error :cancelled :stale})

(def work-statuses
  "The operational `:work/status` vocabulary on a reply map. Narrower /
  more operational than reply `:status` (the ledger row may carry the same
  value). `:suppressed` is the stale terminal; `:timed-out` is an error
  work-status (timeout is an error KIND + a work status, never a top-level
  reply status)."
  #{:completed :failed :timed-out :suppressed :cancelled})

;; ---------------------------------------------------------------------------
;; Reply target — normalization between the public vector-prefix short form
;; and the internal descriptor form (Managed-Effects §The reply target).
;;
;; A normalized target is a map:
;;   {:event             [:event-id arg ...] ;; the prefix to complete
;;    :delivery          :append             ;; the only public delivery mode
;;    :suppress          {...}               ;; optional data-only gates
;;    :dispatch-stale?   false               ;; framework test/tool opt-in only
;;    ::post             (fn [event] event)  ;; the composed event-transform
;;    ::stale-authority  true}               ;; framework/tool capability marker
;;
;; `::post` is the functor's accumulator: a pure event→event transform that
;; `complete` applies AFTER appending the reply map. It is namespaced-private
;; and is NEVER serialized into an effect map or a durable reply — it exists
;; only on a normalized target while a family is assembling/relocating a
;; continuation, exactly the role Elm's `Cmd.map` plays. Identity target
;; carries no `::post` (treated as identity); composition composes them.
;;
;; `::stale-authority` is the framework/tool CAPABILITY marker for stale
;; delivery (Managed-Effects §The reply target: `:dispatch-stale?` is
;; "restricted to framework test and tool targets"). It is namespaced-private
;; — only framework/tool code reaches it (via `with-stale-authority`); an app
;; target built from `:rf/reply-to` data cannot name it, so it cannot grant
;; itself stale-delivery authority. Like `::post`, it is EPHEMERAL: it rides a
;; normalized target while a family/tool assembles a continuation and MUST NOT
;; be serialized into an effect map or a durable reply target.
;;
;; EPHEMERAL vs DURABLE. `::post` and `::stale-authority` are the two
;; ephemeral, host-/capability-bearing slots a normalized target may carry
;; while in-flight. A target that could become DURABLE (persisted to a ledger
;; row, a stored continuation, a replay log) MUST be data-only: see
;; `durable-target` (strips ephemerals + asserts data-only) and
;; `data-only-target?` (the predicate conformance pins).
;; ---------------------------------------------------------------------------

(def ^:private post-key ::post)

(def ^:private stale-authority-key ::stale-authority)

(def ^:private ephemeral-target-keys
  "The namespaced-private slots a normalized target may carry while in-flight
  that MUST NOT survive into a durable target: the functor accumulator
  (`::post`, a fn) and the stale-delivery capability marker
  (`::stale-authority`). `durable-target` strips these; their presence makes a
  target non-data-only."
  #{post-key stale-authority-key})

(defn descriptor?
  "True when `target` is the descriptor (map) form rather than the
  vector-prefix short form."
  [target]
  (map? target))

(defn- event-vector?
  "True when `event` is a valid event-vector prefix: a NON-EMPTY vector whose
  head (the event id) is a keyword. Managed-Effects §The reply target requires
  `:event` to be an event-vector prefix `[:event-id arg ...]`; a bare keyword,
  an empty vector, a non-vector, or a vector whose head is not a keyword is NOT
  a dispatchable event prefix and must fail closed rather than travel on to
  `complete` and become a bogus dispatch shape."
  [event]
  (and (vector? event)
       (seq event)
       (keyword? (first event))))

;; ---- the canonical thrown-error shape for reply failures (rf2-tqlwzr) ------
;;
;; Reply throws historically carried a reply-specific `:rf.error/kind`
;; discriminator and a human message but no canonical `:rf.error/id` — so a
;; tool classifying thrown errors by Spec 009's `:rf.error/id` could not handle
;; reply failures uniformly with every other framework throw. `reply-error`
;; routes every reply throw through the central builder
;; (`re-frame.error/thrown-ex-info`, Spec 009 §The thrown-error shape): the
;; canonical `:rf.error/id` is the `:rf.error/reply-*` keyword (the SOLE Spec
;; 009 machine discriminator — `:rf.error/`-namespaced so the framework's grep
;; token / conformance machinery handles it like every other throw), the human
;; sentence rides `:reason` and leads the derived message (+ the
;; `[:rf.error/<id>]` token), and the reply-specific `:rf.reply/*` category is
;; PRESERVED in `:rf.error/kind` for callers that already branch on it. The
;; `:rf.error/id` ↔ `:rf.error/kind` pair stays in lockstep by construction.
;; `:where` names the user-facing surface (`:rf/reply-to`).

(defn- reply-category->error-id
  "Map a reply-specific `:rf.reply/<suffix>` category to its canonical
  `:rf.error/reply-<suffix>` Spec 009 discriminator. Keeps the `:rf.error/id`
  in the framework's `:rf.error/*` namespace (so the `[:rf.error/<id>]` grep
  token + conformance predicates apply) while the original category survives
  unchanged in `:rf.error/kind`."
  [category]
  (keyword "rf.error" (str "reply-" (name category))))

(defn- reply-error
  "Build the canonical reply thrown-error `ex-info` (rf2-tqlwzr). `category`
  is the `:rf.reply/*` discriminator: it lands in `:rf.error/kind` (the
  preserved reply-specific slot), and its `:rf.error/reply-*` projection lands
  in `:rf.error/id` (the canonical Spec 009 slot). `reason` is the human
  sentence; `extras` merge on top."
  ([category reason] (reply-error category reason nil))
  ([category reason extras]
   (error/thrown-ex-info (reply-category->error-id category) :rf/reply-to reason
                         {:extra (merge {:rf.error/kind category} extras)})))

(defn normalize-target
  "Normalize a reply target to the canonical descriptor map.

  Accepts either:
    - the public short form: an event-vector prefix `[:event-id arg ...]`;
    - the descriptor form: `{:event [...] :delivery :append :suppress {...}
      :dispatch-stale? bool}`.

  Returns `{:event <vector> :delivery <kw> ...}` with `:delivery` defaulted
  to `:append`. Idempotent: normalizing an already-normalized target is the
  identity (it preserves any accumulated `::post` transform and the gate
  fields). A nil/blank target yields nil (no continuation).

  FAILS CLOSED on a malformed target (Managed-Effects §The reply target —
  `:event` is REQUIRED and is an event-vector prefix). A descriptor missing
  `:event`, carrying a non-vector / empty-vector / non-keyword-headed `:event`,
  or a non-vector/non-map target throws `ex-info`
  `:rf.reply/invalid-target` rather than letting a bogus `{}` / `{:event nil}` /
  `{:event :x}` travel on to `complete` (which would `(vec event)` it into a
  garbage dispatch shape). Validating here means EVERY downstream consumer
  (`complete`, `map-completed-event`, `durable-target`, `target->short-form`) inherits
  the guarantee — the target is either nil or a well-formed descriptor."
  [target]
  (cond
    (nil? target) nil

    (vector? target)
    (if (event-vector? target)
      {:event target :delivery :append}
      (throw (reply-error
               :rf.reply/invalid-target
               "Invalid :rf/reply-to target — an event-vector prefix must be a non-empty vector whose head is a keyword event id."
               {:target target})))

    (descriptor? target)
    (let [{:keys [event delivery]} target]
      (when-not (event-vector? event)
        (throw (reply-error
                 :rf.reply/invalid-target
                 "Invalid :rf/reply-to target — the descriptor's :event must be an event-vector prefix [:event-id arg ...] (a non-empty vector with a keyword head)."
                 {:target target
                  :event  event})))
      (cond-> target
        (nil? delivery) (assoc :delivery :append)
        true            (assoc :event event)))

    :else
    (throw (reply-error
             :rf.reply/invalid-target
             "Invalid :rf/reply-to target — expected an event-vector prefix or a descriptor map."
             {:target target}))))

(defn target->short-form
  "Project a normalized target back to its public short form when it has no
  non-default descriptor fields (no `:suppress`, no `:dispatch-stale?`, no
  accumulated `::post`, no `::stale-authority`, `:delivery :append`).
  Otherwise returns the descriptor unchanged. Lets a family expose the bare
  vector publicly while using the descriptor internally (Managed-Effects: \"A
  family MAY expose only the short vector form publicly while using the
  descriptor form internally\"). The ephemeral capability slot
  (`::stale-authority`) keeps the descriptor too — projecting must never
  silently drop a framework/tool capability."
  [target]
  (let [{:keys [event delivery suppress dispatch-stale?] :as d} (normalize-target target)]
    (if (and (= delivery :append)
             (nil? suppress)
             (not dispatch-stale?)
             (nil? (get d post-key))
             (nil? (get d stale-authority-key)))
      event
      d)))

;; ---------------------------------------------------------------------------
;; Completion — append the reply map to the target's event, then apply the
;; composed event-transform (Managed-Effects §The reply target: "the reply
;; map appended as the final argument").
;; ---------------------------------------------------------------------------

(defn complete
  "Build the completed event for `target` carrying `reply`.

  For `:delivery :append` (the only public mode) the reply map is appended
  as the final argument of the target's event vector; then the target's
  composed event-transform (`::post`, identity when absent) is applied. The
  result is the event vector ready to dispatch.

  This is the pure core the functor law is stated over:

      (complete (map-completed-event f target) reply) == (f (complete target reply))

  `complete` does NOT dispatch, validate, or suppress — issuance, stale
  checks, ledger writes, and tracing are the family/runtime's job and are
  unaffected by `map-completed-event` (the functor law). A nil target yields
  nil (no delivery)."
  [target reply]
  (when-let [{:keys [event delivery] :as d} (normalize-target target)]
    (let [base (case delivery
                 :append (conj (vec event) reply)
                 ;; A non-:append delivery is a compatibility-adapter mode
                 ;; that lowers internally; an unknown one is a contract
                 ;; error rather than a silent fall-through.
                 (throw (reply-error
                          :rf.reply/unknown-delivery
                          (str "Unknown :rf/reply-to :delivery mode " (pr-str delivery)
                               " — :delivery must be :append (the public reply mode) "
                               "or an internal compatibility-adapter mode. Fix the "
                               "descriptor's :delivery on the reply target.")
                          {:delivery delivery
                           :target   d})))
          post (get d post-key)]
      (if post (post base) base))))

;; ---------------------------------------------------------------------------
;; Reply-target mapping — the functor (Managed-Effects §Reply mapping and the
;; functor law). `map-completed-event` rewrites ONLY the completed event; it
;; never touches issuance, `:work/id`, status, cancellation, stale checks, or
;; tracing — those are not stored on the target at all, so the law holds
;; structurally.
;; ---------------------------------------------------------------------------

(defn map-completed-event
  "Map the reply target through an event-transform `f` (an event→event pure
  fn). Returns a normalized target whose completion equals `f` applied to
  the original completion:

      (complete (map-completed-event f target) reply) == (f (complete target reply))

  `f` receives the fully-completed event (the target event with the reply
  map already appended) and returns the relocated/rewrapped event. Mapping
  composes by composing `::post` accumulators, so the functor laws hold:

      (map-completed-event identity target)        == target          ;; identity
      (map-completed-event f (map-completed-event g target)) == (map-completed-event (comp f g) target)

  Mapping a target changes ONLY the completed event — never issuance, work
  id, status classification, cancellation, stale checks, or tracing (none
  of those live on the target). This is the role `Cmd.map` plays in Elm's
  command algebra: relocating or wrapping a continuation is a pure data
  transform.

  A nil target stays nil (no continuation to relocate) — mapping the absence
  of a continuation is still the absence of a continuation, NOT a bogus
  descriptor carrying only the private `::post` accumulator and no `:event`.
  This preserves the no-continuation semantics through a relocation: a family
  that maps a missing target gets nil back, and `complete` on it yields nil
  (no delivery)."
  [f target]
  (when-let [d (normalize-target target)]
    (let [existing (get d post-key)
          composed (if existing (comp f existing) f)]
      (assoc d post-key composed))))

;; ---------------------------------------------------------------------------
;; Stale-delivery authority — the framework/tool capability (Managed-Effects
;; §The reply target). `:dispatch-stale?` is "restricted to framework test and
;; tool targets"; the substrate is pure and has no caller-identity context, so
;; the restriction is enforced STRUCTURALLY by a namespaced-private capability
;; marker that only framework/tool code can attach. An app target built from
;; `:rf/reply-to` data cannot name `::stale-authority`, so it cannot grant
;; itself stale delivery — and `suppress` fails LOUD if it tries.
;; ---------------------------------------------------------------------------

(defn with-stale-authority
  "Stamp a reply `target` as a FRAMEWORK/TOOL target authorised to opt into
  stale delivery via `:dispatch-stale? true` (Managed-Effects §The reply
  target). Returns the normalized descriptor carrying the namespaced-private
  `::stale-authority` capability marker.

  This is the ONLY way `:dispatch-stale? true` is honoured: `suppress` throws
  on a target that sets `:dispatch-stale? true` WITHOUT this marker (an app
  target cannot reach the private key, so it cannot grant itself the
  capability). Framework test/tool callers wrap their target with this helper;
  app code — which builds a target from public `:rf/reply-to` data — never
  does, and so an app reply is never delivered stale.

  Like `::post`, the marker is EPHEMERAL: it rides an in-flight normalized
  target and MUST NOT be serialized into an effect map or a durable target
  (`durable-target` strips it)."
  [target]
  (assoc (normalize-target target) stale-authority-key true))

(defn stale-authority?
  "True when `target` carries the framework/tool `::stale-authority`
  capability marker (set via `with-stale-authority`)."
  [target]
  (true? (get (normalize-target target) stale-authority-key)))

;; ---------------------------------------------------------------------------
;; Host-handle detection — the shared data-only walker (Managed-Effects §The
;; reply map / §The reply target: the data-only invariant). Used by BOTH the
;; durable-target guard and `validate-reply` — a single definition of "what a
;; host handle is" so the reply map and the reply target enforce the same
;; data-only contract.
;; ---------------------------------------------------------------------------

(defn- host-handle?
  "Detector for a host resource that MUST NOT appear in a data-only reply map
  or a durable reply target (Managed-Effects §The reply map / §The reply
  target: the data-only invariant). The CLOSED host-handle set is:

    - a fn (a callback — every family error rides data, never a thunk);
    - on CLJS: a `Promise`, `AbortController`, `AbortSignal`, a DOM node
      (anything exposing a numeric `nodeType`), a `js/Date`, a `js/RegExp`;
    - on the JVM: a `java.util.concurrent.Future`, a `java.lang.Thread`, a
      `java.util.Date`, a `java.util.regex.Pattern`.

  The `Date` / `RegExp` (and their JVM counterparts) belong to the closed set
  because they are NON-EDN host objects: they neither round-trip through the
  EDN reader nor compare by value, so they cannot ride durable reply data (SSR
  / hydration / epoch snapshots / replay) safely — a durable timestamp is an
  epoch-millisecond long under EP-0010, never a host `Date`. Plain EDN data —
  maps, vectors, sets, keywords, strings, numbers, booleans, nil, symbols,
  instants represented as longs — passes. (This detector and its documented
  contract are now ALIGNED — the predicate enforces exactly the set the
  docstring names, on both runtimes.)"
  [v]
  (or (fn? v)
      #?(:cljs (or (and (exists? js/Promise) (instance? js/Promise v))
                   (and (exists? js/AbortController) (instance? js/AbortController v))
                   (and (exists? js/AbortSignal) (instance? js/AbortSignal v))
                   (instance? js/Date v)
                   (instance? js/RegExp v)
                   ;; A DOM node exposes a numeric nodeType.
                   (and (object? v) (number? (.-nodeType v))))
         :clj  (or (instance? java.util.concurrent.Future v)
                   (instance? java.lang.Thread v)
                   (instance? java.util.Date v)
                   (instance? java.util.regex.Pattern v)))))

(defn- walk-find-host-handle
  "Walk `v` (maps / vectors / sets) and return the path to the first host
  handle found, or nil. Bounded by the reply-map shape (replies are small
  data); guards against cyclic refs are unnecessary for EDN data."
  ([v] (walk-find-host-handle v []))
  ([v path]
   (cond
     (host-handle? v) path
     (map? v)         (some (fn [[k vv]] (walk-find-host-handle vv (conj path k))) v)
     (vector? v)      (first (keep-indexed (fn [i vv] (walk-find-host-handle vv (conj path i))) v))
     (set? v)         (some #(walk-find-host-handle % (conj path '*)) v)
     :else            nil)))

;; ---------------------------------------------------------------------------
;; Data-only / durable target invariant (Managed-Effects §The reply target —
;; the reply-target-as-data contract). A normalized target may carry the
;; ephemeral, non-data slots `::post` (a fn) and `::stale-authority` (a
;; capability) WHILE IN-FLIGHT, but a target that can become DURABLE (a stored
;; continuation, a ledger row, a replay log) MUST be data-only. These helpers
;; make that boundary explicit and fail LOUD rather than letting a
;; non-serializable function or a capability marker leak into durable reply
;; data.
;; ---------------------------------------------------------------------------

(defn data-only-target?
  "True when `target` is DATA-ONLY — safe to persist into a durable reply
  target. False when it carries an ephemeral, non-data slot: the functor
  accumulator `::post` (an arbitrary fn) or the `::stale-authority` capability
  marker. (The public data fields `:event` / `:delivery` / `:suppress` /
  `:dispatch-stale?` are all data and pass.) A nil target is data-only
  (nothing to persist)."
  [target]
  (let [d (normalize-target target)]
    (not (some #(contains? d %) ephemeral-target-keys))))

(defn durable-target
  "Project `target` to a DURABLE, data-only normalized descriptor — stripping
  the ephemeral non-data slots (`::post`, `::stale-authority`) — and assert
  the result carries no host handle anywhere. Use before a target could be
  persisted (a stored continuation, a ledger row, a replay log).

  Fails LOUD (throws `ex-info` `:rf.reply/non-data-target`) if, after
  stripping the framework-private slots, the descriptor STILL contains a host
  handle (a fn, Promise, AbortController, …) in a public field — that would be
  an app/family bug smuggling a non-serializable value through `:event` /
  `:suppress`. A nil target yields nil (no continuation to persist)."
  [target]
  (when-let [d (normalize-target target)]
    (let [stripped (apply dissoc d ephemeral-target-keys)]
      (when-let [handle-path (walk-find-host-handle stripped)]
        (throw (reply-error
                 :rf.reply/non-data-target
                 "Durable reply target must be data-only — it carries a host handle (fn / Promise / AbortController / …) in a public field. See :path for the offending slot; replace the handle with its data projection."
                 {:path   handle-path
                  :target stripped})))
      stripped)))

;; ---------------------------------------------------------------------------
;; Reply-map schema validation (Managed-Effects §The reply map / §Status
;; taxonomy). Validates the closed-status invariant, the value/error
;; conventions per status, and the data-only invariant (no host handles).
;;
;; Returns nil when valid; otherwise a vector of problem maps
;; `{:rf.reply/problem <kw> :path <path> :detail ...}`. `valid?` is the
;; boolean sugar. Pure — does not throw on a malformed reply (a malformed
;; reply is data the caller classifies), only on a non-map argument.
;; ---------------------------------------------------------------------------

(defn validate-reply
  "Validate a reply map against the Managed-Effects reply-map contract.
  Returns nil when valid, else a vector of problem maps. Checks:

    - `:status` present and in the CLOSED `statuses` set;
    - per-status value/error conventions:
        `:ok`        — `:value` present, `:error` absent;
        `:partial`   — `:value` present AND `:error` present as a family
                       error MAP carrying a `:kind`;
        `:error`     — `:error` present as a family error MAP carrying a
                       `:kind`;
        `:cancelled` — `:cancel/reason` present AND `:cancelled? true`
                       (the intentional-cancellation marker);
        `:stale`     — `:stale? true` AND `:stale/reason` present;
    - `:work/status` (when present) in the `work-statuses` set;
    - the data-only invariant: no host handles anywhere in the map.

  The `:error`/`:partial` error shape is TIGHT: the spec status taxonomy
  requires `:error` \"present with a family `:kind`\", so a loose scalar
  `:error` (a bare keyword/string) is rejected — every family error rides a
  structured `{:kind … …}` map so downstream classification, tracing, and
  cross-family error handling have a uniform shape to dispatch on (a scalar
  would force each consumer to special-case it). Likewise a `:cancelled`
  reply MUST carry the `:cancelled? true` marker, not merely a
  `:cancel/reason`, so cancellation is an explicit positive fact rather than
  inferred from the presence of a reason.

  Pure; throws only on a non-map argument."
  [reply]
  (when-not (map? reply)
    (throw (reply-error
             :rf.reply/non-map-reply
             (str "Reply must be a map — the uniform reply envelope carries "
                  ":status (the closed taxonomy) plus :value / :error slots "
                  "(Managed-Effects §The reply map), got " (pr-str reply) ". "
                  "Build the reply as a map, not a scalar.")
             {:reply reply})))
  (let [{:keys [status value error]} reply
        problem (fn [kind path detail] {:rf.reply/problem kind :path path :detail detail})
        ;; A family error MUST be a structured map carrying a :kind. A loose
        ;; scalar (bare keyword/string/number) is NOT a valid family error —
        ;; the closed contract demands the uniform {:kind …} shape.
        error-map-with-kind? (fn [e] (and (map? e) (some? (:kind e))))
        ps (cond-> []
             (not (contains? reply :status))
             (conj (problem :rf.reply/missing-status [:status] nil))

             (and (contains? reply :status) (not (contains? statuses status)))
             (conj (problem :rf.reply/invalid-status [:status] status))

             ;; :ok — value present, error ABSENT. The contract says `:error`
             ;; is *absent* for `:ok` (Managed-Effects §Status taxonomy + the
             ;; "omit optional fields when absent" rule), so a PRESENT `:error`
             ;; key — including a nil placeholder — is rejected. Checking
             ;; `contains?` (not `some?`) closes the `{:status :ok :error nil}`
             ;; gap: a nil sentinel is still a present-but-absent-meaning slot
             ;; the family should have omitted.
             (and (= status :ok) (not (contains? reply :value)))
             (conj (problem :rf.reply/ok-missing-value [:value] nil))
             (and (= status :ok) (contains? reply :error))
             (conj (problem :rf.reply/ok-has-error [:error] error))

             ;; :partial — both value and error present; :error is a family
             ;; error MAP carrying a :kind (a loose scalar is rejected).
             (and (= status :partial) (not (contains? reply :value)))
             (conj (problem :rf.reply/partial-missing-value [:value] nil))
             (and (= status :partial) (nil? error))
             (conj (problem :rf.reply/partial-missing-error [:error] nil))
             (and (= status :partial) (some? error) (not (error-map-with-kind? error)))
             (conj (problem :rf.reply/error-not-family-map [:error] error))

             ;; :error — :error present as a family error MAP carrying a :kind
             ;; (a loose scalar is rejected).
             (and (= status :error) (nil? error))
             (conj (problem :rf.reply/error-missing-error [:error] nil))
             (and (= status :error) (some? error) (not (error-map-with-kind? error)))
             (conj (problem :rf.reply/error-not-family-map [:error] error))

             ;; :cancelled — :cancel/reason present AND the :cancelled? true
             ;; intentional-cancellation marker (cancellation is a positive
             ;; fact, not inferred from a stray reason).
             (and (= status :cancelled) (nil? (:cancel/reason reply)))
             (conj (problem :rf.reply/cancelled-missing-reason [:cancel/reason] nil))
             (and (= status :cancelled) (not (true? (:cancelled? reply))))
             (conj (problem :rf.reply/cancelled-missing-marker [:cancelled?] (:cancelled? reply)))

             ;; :stale — :stale? true and :stale/reason present; MUST NOT carry :value.
             (and (= status :stale) (not (true? (:stale? reply))))
             (conj (problem :rf.reply/stale-missing-flag [:stale?] (:stale? reply)))
             (and (= status :stale) (nil? (:stale/reason reply)))
             (conj (problem :rf.reply/stale-missing-reason [:stale/reason] nil))
             (and (= status :stale) (contains? reply :value))
             (conj (problem :rf.reply/stale-has-value [:value] value))

             ;; :work/status closed vocabulary.
             (and (contains? reply :work/status)
                  (not (contains? work-statuses (:work/status reply))))
             (conj (problem :rf.reply/invalid-work-status [:work/status] (:work/status reply))))
        ;; Data-only invariant — no host handles anywhere.
        handle-path (walk-find-host-handle reply)
        ps (cond-> ps
             (some? handle-path)
             (conj (problem :rf.reply/host-handle handle-path :host-handle)))]
    (when (seq ps) (vec ps))))

(defn valid-reply?
  "Boolean sugar over `validate-reply`."
  [reply]
  (nil? (validate-reply reply)))

;; ---------------------------------------------------------------------------
;; Data-only trace summaries (Managed-Effects §Tracing). Every wire-bearing
;; slot (`:value`, `:error`, `:correlation`, `:meta`) routes through the
;; shared `re-frame.elision/elide-wire-value` walker — never a family-private
;; elider — so privacy (`:sensitive?`) and size (`:large?`) elision compose
;; uniformly with the rest of the trace stream. The summary keeps the
;; data-only correlation facts verbatim (work id, frame, status, timestamps)
;; and elides only the user-data slots.
;; ---------------------------------------------------------------------------

(def ^:private wire-slots
  "Reply-map slots that carry user/wire data and so must be elided for trace
  egress. Identity facts (work id, frame, status, timestamps, cancellation /
  staleness reasons) are framework data and ride verbatim."
  [:value :error :correlation :meta])

(defn trace-summary
  "Build a DATA-ONLY trace summary of `reply` for a managed-async trace row.

  Identity / correlation facts (`:status`, `:work/id`, `:work/kind`,
  `:work/status`, `:attempt`, `:rf.frame/id`, the durable timestamps, the
  cancellation / staleness facts) ride verbatim. The wire-bearing slots
  (`:value`, `:error`, `:correlation`, `:meta`) route through the single
  shared `elide-wire-value` walker so `:sensitive?` / `:large?` compose
  exactly as elsewhere — no family-private elision.

  The egress frame resolves from the explicit `(:frame opts)` when given,
  otherwise from the reply map's own carried `:rf.frame/id` stamp. A reply
  carrying its frame identity SELF-SUMMARIZES by default: a caller need not
  thread the identity back through `opts` just to apply the right egress
  policy (rf2-wjo28z). Explicit `:frame` still wins (an inspector may target
  a different policy frame), and resolution still fails closed — if neither
  an explicit `:frame` nor a carried `:rf.frame/id` names a LIVE frame, the
  wire slots redact to the `:rf/redacted` sentinel rather than ship under no
  policy (`elide-wire-value` enforces the live-frame gate; the carried stamp
  is policy-bearing only when it resolves). All other `opts` (size-threshold
  overrides, `:rf.size/include-sensitive?`) forward unchanged. Returns a map
  safe to place under a trace event's `:tags`."
  ([reply] (trace-summary reply nil))
  ([reply opts]
   (let [opts (cond-> opts
                (and (nil? (:frame opts)) (contains? reply :rf.frame/id))
                (assoc :frame (:rf.frame/id reply)))]
     (reduce (fn [m slot]
               (if (contains? m slot)
                 (update m slot #(elision/elide-wire-value % opts))
                 m))
             reply
             wire-slots))))

;; ---------------------------------------------------------------------------
;; Stale suppression — THE correctness boundary (Managed-Effects §Stale
;; suppression). A family supplies the CARRIED correlation (captured at
;; issuance, riding the reply token) and the CURRENT correlation (read from
;; live frame-state at completion). When they diverge the app target MUST NOT
;; run: this helper produces the `:status :stale` reply + the trace facts and
;; signals non-delivery. Cancellation is only an optimization; suppression is
;; the safety rule.
;; ---------------------------------------------------------------------------

(defn stale?
  "Pure correlation-gate check. Returns true when `carried` does NOT match
  `current` (the work was superseded), so its app reply must be suppressed.

  `carried` and `current` are the data-only gate maps a family validates
  (e.g. `{:work/id ... :generation ...}`, `{:route/nav-token ...}`,
  `{:rf/after-epoch ... :path ...}`). Both nil ⇒ not stale (no gate ⇒
  nothing to supersede). Matching is by value equality over the carried
  keys: every key present in `carried` must equal its counterpart in
  `current`. Extra keys in `current` are ignored (the family decides the
  gate's key set; `current` may carry more facts than the gate checks)."
  [carried current]
  (cond
    (nil? carried) false
    (nil? current) true
    :else (not (every? (fn [[k v]] (= v (get current k))) carried))))

(defn suppress
  "Produce the stale-suppression outcome for a superseded completion WITHOUT
  dispatching the app target. This is the correctness boundary made
  concrete: the returned `:reply` is `:status :stale` (no `:value`, no app
  mutation), and `:deliver?` is `false` (unless the target is a FRAMEWORK/TOOL
  target that explicitly opted into stale delivery via `:dispatch-stale? true`
  — see the authority rule below).

  AUTHORITY (Managed-Effects §The reply target — `:dispatch-stale?` is
  \"restricted to framework test and tool targets\"). `:dispatch-stale? true`
  is honoured ONLY when the target also carries the framework/tool
  `::stale-authority` capability marker (stamped via `with-stale-authority`).
  An APP target — built from public `:rf/reply-to` data, which has no way to
  name the namespaced-private marker — cannot grant itself stale delivery: if
  it sets `:dispatch-stale? true` WITHOUT authority this FAILS LOUD (throws
  `ex-info` `:rf.reply/unauthorized-stale-delivery`) rather than silently
  delivering a stale envelope to app state. The default (no `:dispatch-stale?`)
  is non-delivery for every target, app or framework.

  Arguments:
    `target`  — the (optionally normalized) reply target; consulted only for
                its `:dispatch-stale?` opt-in and `::stale-authority`.
    `carried` — the data-only correlation captured at issuance.
    `current` — the data-only correlation read from live frame-state now.
    `extra`   — optional reply fields to carry verbatim (`:work/id`,
                `:work/kind`, `:rf.frame/id`, `:completed-at`, `:meta`, …);
                `:stale/reason` defaults to `:rf.reply/correlation-mismatch`
                when not supplied. The stale boundary is NON-NEGOTIABLE:
                `extra` CANNOT override `:status :stale` / `:stale? true` /
                `:work/status :suppressed`, and a `:value` in `extra` is
                STRIPPED (a stale reply MUST NOT carry `:value` — see
                `validate-reply`). So threading a natural success/error reply
                as `extra` cannot produce a non-stale outcome (rf2-waawic).

  Returns:
    {:deliver? <bool>           ;; false ⇒ DO NOT dispatch the app target
     :reply    <stale reply>    ;; :status :stale, data-only, app-state-safe
     :work/status :suppressed   ;; the ledger terminal for a stale completion
     :trace    <data-only trace facts carrying CARRIED + CURRENT>}

  The caller's contract: when `:deliver?` is false it MUST NOT run the app
  reply target, MUST mark any ledger row `:suppressed`, and SHOULD emit the
  `:trace` facts onto the trace bus (routing wire slots through
  `trace-summary` / `elide-wire-value`). The stale reply MUST NOT produce
  any user-visible app-db / runtime-db mutation beyond framework-owned
  ledger / trace bookkeeping."
  ([target carried current] (suppress target carried current nil))
  ([target carried current extra]
   (let [reason   (or (:stale/reason extra) :rf.reply/correlation-mismatch)
         d        (when target (normalize-target target))
         wants?   (true? (:dispatch-stale? d))
         authorised? (true? (get d stale-authority-key))
         ;; FAIL LOUD: a target asking for stale delivery without the
         ;; framework/tool capability is an app target overreaching. The
         ;; substrate is pure (no caller identity), so the marker IS the
         ;; authority — its absence means "not framework/tool".
         _        (when (and wants? (not authorised?))
                    (throw (reply-error
                             :rf.reply/unauthorized-stale-delivery
                             "Stale delivery (:dispatch-stale? true) is restricted to framework test/tool targets — this target lacks the stale-delivery authority. App reply targets MUST NOT receive stale envelopes. Drop :dispatch-stale? from the target, or use a framework/tool target that carries the stale-delivery capability."
                             {:target         d
                              :stale/reason   reason
                              :stale/authorised? authorised?})))
         opt-in?  (and wants? authorised?)
         ;; rf2-waawic — `suppress` is THE correctness boundary, so "stale
         ;; wins over the natural completion status" (Managed-Effects
         ;; §Status taxonomy) MUST be structurally impossible for a caller
         ;; to violate. Merge `extra` FIRST (its identity facts — `:work/id`,
         ;; `:work/kind`, `:rf.frame/id`, `:completed-at`, `:correlation`,
         ;; `:meta` — ride verbatim), then FORCE the stale invariants on top
         ;; and STRIP `:value`. A caller that accidentally threads a natural
         ;; success/error reply as `extra` (`{:status :ok :value … :work/
         ;; status :completed}`) can no longer produce an invalid non-stale
         ;; reply — the forced fields override and `:value` (which a stale
         ;; reply MUST NOT carry — see `validate-reply`) is dissoc'd.
         carry    (dissoc extra :stale/reason :value)
         ;; `carry` is merged FIRST so the stale-boundary map (the second
         ;; `merge` arg) wins every shared key — `extra` cannot override
         ;; `:status` / `:stale?` / `:work/status`, and `:value` was already
         ;; stripped above.
         reply    (merge carry {:status       :stale
                                :stale?       true
                                :stale/reason reason
                                :work/status  :suppressed})]
     {:deliver?    opt-in?
      :reply       reply
      :work/status :suppressed
      :trace       {:rf.reply/suppressed?  true
                    :stale/reason          reason
                    :work/id               (:work/id reply)
                    :rf.reply/carried      carried
                    :rf.reply/current      current}})))
