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
    - reply-target mapping — the functor law (`map-target` changes ONLY
      the completed event, never issuance / `:work/id` / status /
      cancellation / staleness / tracing),
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
  `re-frame.epoch` / `re-frame.http-managed`)."
  (:require [re-frame.elision :as elision]))

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

(defn normalize-target
  "Normalize a reply target to the canonical descriptor map.

  Accepts either:
    - the public short form: an event-vector prefix `[:event-id arg ...]`;
    - the descriptor form: `{:event [...] :delivery :append :suppress {...}
      :dispatch-stale? bool}`.

  Returns `{:event <vector> :delivery <kw> ...}` with `:delivery` defaulted
  to `:append`. Idempotent: normalizing an already-normalized target is the
  identity (it preserves any accumulated `::post` transform and the gate
  fields). A nil/blank target yields nil (no continuation)."
  [target]
  (cond
    (nil? target) nil

    (vector? target)
    {:event target :delivery :append}

    (descriptor? target)
    (let [{:keys [event delivery]} target]
      (cond-> target
        (nil? delivery) (assoc :delivery :append)
        true            (assoc :event event)))

    :else
    (throw (ex-info "Invalid :rf/reply-to target — expected an event-vector prefix or a descriptor map."
                    {:rf.error/kind :rf.reply/invalid-target
                     :target        target}))))

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

      (complete (map-target f target) reply) == (f (complete target reply))

  `complete` does NOT dispatch, validate, or suppress — issuance, stale
  checks, ledger writes, and tracing are the family/runtime's job and are
  unaffected by `map-target` (the functor law). A nil target yields nil (no
  delivery)."
  [target reply]
  (when-let [{:keys [event delivery] :as d} (normalize-target target)]
    (let [base (case delivery
                 :append (conj (vec event) reply)
                 ;; A non-:append delivery is a compatibility-adapter mode
                 ;; that lowers internally; an unknown one is a contract
                 ;; error rather than a silent fall-through.
                 (throw (ex-info "Unknown :rf/reply-to :delivery mode."
                                 {:rf.error/kind :rf.reply/unknown-delivery
                                  :delivery      delivery
                                  :target        d})))
          post (get d post-key)]
      (if post (post base) base))))

;; ---------------------------------------------------------------------------
;; Reply-target mapping — the functor (Managed-Effects §Reply mapping and the
;; functor law). `map-target` rewrites ONLY the completed event; it never
;; touches issuance, `:work/id`, status, cancellation, stale checks, or
;; tracing — those are not stored on the target at all, so the law holds
;; structurally.
;; ---------------------------------------------------------------------------

(defn map-target
  "Map the reply target through an event-transform `f` (an event→event pure
  fn). Returns a normalized target whose completion equals `f` applied to
  the original completion:

      (complete (map-target f target) reply) == (f (complete target reply))

  `f` receives the fully-completed event (the target event with the reply
  map already appended) and returns the relocated/rewrapped event. Mapping
  composes by composing `::post` accumulators, so the functor laws hold:

      (map-target identity target)        == target          ;; identity
      (map-target f (map-target g target)) == (map-target (comp f g) target)

  Mapping a target changes ONLY the completed event — never issuance, work
  id, status classification, cancellation, stale checks, or tracing (none
  of those live on the target). This is the role `Cmd.map` plays in Elm's
  command algebra: relocating or wrapping a continuation is a pure data
  transform."
  [f target]
  (let [d        (normalize-target target)
        existing (get d post-key)
        composed (if existing (comp f existing) f)]
    (assoc d post-key composed)))

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
  "Best-effort detector for a host resource that MUST NOT appear in a
  data-only reply map: a fn (callback), or a known host object (Promise,
  AbortController, AbortSignal, a DOM node, a JS Date / RegExp). Plain EDN
  data — maps, vectors, sets, keywords, strings, numbers, booleans, nil,
  symbols, instants represented as longs — passes."
  [v]
  (or (fn? v)
      #?(:cljs (or (instance? js/Promise v)
                   (and (exists? js/AbortController) (instance? js/AbortController v))
                   (and (exists? js/AbortSignal) (instance? js/AbortSignal v))
                   ;; A DOM node exposes a numeric nodeType.
                   (and (object? v) (number? (.-nodeType v))))
         :clj  (or (instance? java.util.concurrent.Future v)
                   (instance? java.lang.Thread v)))))

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
        (throw (ex-info "Durable reply target must be data-only — it carries a host handle (fn / Promise / AbortController / …) in a public field."
                        {:rf.error/kind :rf.reply/non-data-target
                         :path          handle-path
                         :target        stripped})))
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
    (throw (ex-info "Reply must be a map." {:rf.error/kind :rf.reply/non-map-reply :reply reply})))
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

             ;; :ok — value present, error absent.
             (and (= status :ok) (not (contains? reply :value)))
             (conj (problem :rf.reply/ok-missing-value [:value] nil))
             (and (= status :ok) (some? error))
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

  `opts` is forwarded to `elide-wire-value` (e.g. `:frame`, size-threshold
  overrides); the carried-frame default applies when omitted. Returns a map
  safe to place under a trace event's `:tags`."
  ([reply] (trace-summary reply nil))
  ([reply opts]
   (reduce (fn [m slot]
             (if (contains? m slot)
               (update m slot #(elision/elide-wire-value % opts))
               m))
           reply
           wire-slots)))

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
                when not supplied.

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
                    (throw (ex-info "Stale delivery (:dispatch-stale? true) is restricted to framework test/tool targets — this target lacks the stale-delivery authority. App reply targets MUST NOT receive stale envelopes."
                                    {:rf.error/kind :rf.reply/unauthorized-stale-delivery
                                     :target        d})))
         opt-in?  (and wants? authorised?)
         carry    (dissoc extra :stale/reason)
         reply    (merge {:status       :stale
                          :stale?       true
                          :stale/reason reason
                          :work/status  :suppressed}
                         carry)]
     {:deliver?    opt-in?
      :reply       reply
      :work/status :suppressed
      :trace       {:rf.reply/suppressed?  true
                    :stale/reason          reason
                    :work/id               (:work/id reply)
                    :rf.reply/carried      carried
                    :rf.reply/current      current}})))
