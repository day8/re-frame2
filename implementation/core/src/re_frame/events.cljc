(ns re-frame.events
  "Event-handler registration. Per Spec 002 §Event handlers and Spec 001
  §Registry model.

  The ONE public event form is `reg-event` — pure (cofx, event) → a closed
  effects map (EP-0018). Coeffects in, a closed effects map out.

  `reg-event` is the single event-registration form. `reg-event-db` /
  `reg-event-fx` are REMOVED and public `reg-event-ctx` is demoted to a
  framework-internal `context -> context` primitive; calling any of the
  removed public names is a hard error naming the replacement (the
  facade-exported throwing stubs `re-frame.events/reg-event-db` /
  `reg-event-fx` / `reg-event-ctx` raise
  `:rf.error/reg-event-db-removed` / `-fx-removed` / `-ctx-removed`, per
  EP-0007 rule 2 — no working alias). Full-context work in application code
  is expressed with interceptors authored via the public `reg-interceptor`
  form and referenced by id from a `reg-event` registration's
  `:interceptors` chain (`->interceptor` is the internal lowering
  constructor, not the application authoring form).

  All event registrations register under registry kind `:event` and share the
  ONE handler-wrapping interceptor `:rf/event-handler` (`:rf/default? true`);
  there is no `:event/kind` sub-tag and no per-kind `:rf/db-handler` /
  `:rf/fx-handler` / `:rf/ctx-handler` ids (EP-0018 §5). The runtime
  treats every event uniformly during drain.

  Per Spec 015 §1. Event handlers — the registration meta-map accepts
  optional `:sensitive [paths]` and `:large [paths]` keys that index
  into the dispatched event vector's arg-map (the second element).
  The classification is DERIVED from the registrar meta at
  `re-frame.classification/registration-classification` read time; reg-event
  only VALIDATES it fail-loud via
  `re-frame.classification/validate-classification!` — an ALWAYS-ON
  registration-time validator reached by DIRECT REQUIRE. `re-frame.classification`
  is core-owned, lives in the SAME artefact as events/fx/subs, and is pinned into
  every production bundle (it is side-effect-required by `re-frame.core`); the
  validator fail-louds in prod as well as dev. The direct require is cycle-free.
  The dev-gated PROJECTION hook stays late-bound. No imperative stash."
  (:require [re-frame.interop :as interop]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.image-assembly :as image-assembly]
            [re-frame.interceptor :as interceptor]
            [re-frame.interceptor-registry :as icpt-reg]
            [re-frame.late-bind :as late-bind]
            [re-frame.cofx :as cofx]
            [re-frame.error :as error]
            [re-frame.reg-meta :as reg-meta]
            [re-frame.classification :as classification]
            [re-frame.source-coords :as source-coords]
            [re-frame.trace :as trace]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- metadata-map `:interceptors` — the superset middle slot -------------
;;
;; The `reg-event` metadata-map carries a RESERVED `:interceptors` key,
;; making the map the ONE superset middle-slot shape:
;; `{:doc … :schema … :sensitive … :interceptors [i1 i2]}`.
;; The interceptor chain goes under `:interceptors` in the metadata map.
;;
;; `:interceptors` inside the metadata-map is the documented home for the
;; chain, and the chain is honoured.
;;
;; The malformed-value guard below keeps the superset honest: a non-vector
;; `:interceptors` value, or a vector carrying a non-map (non-interceptor)
;; entry, is a LOUD registration error (`:rf.error/reg-event-bad-interceptors`),
;; consistent with the existing reg-event arg policing (bare-interceptor /
;; bad-middle-slot / bad-arity).

(defn- interceptor-entry?
  "True when `x` is a valid `:interceptors` chain entry. REFERENCE-ONLY under
  EP-0022: an entry MUST be an interceptor REFERENCE —
  a bare keyword id or an `[id arg]` 2-vector. Refs resolve to their registered
  values at chain assembly. An INLINE interceptor value (a map carrying `:id` /
  `:before` / `:after`, an `->interceptor` result, a value-Var) is not a
  valid entry — register it with `reg-interceptor` and reference it by id. A
  non-ref entry is rejected at registration by `validate-meta-interceptors!`
  (an inline value gets the `:rf.error/inline-interceptor-removed`-aimed
  message; any other shape the generic bad-interceptors message)."
  [x]
  (icpt-reg/interceptor-ref? x))

(defn- throw-bad-interceptors-value!
  "Raise `:rf.error/reg-event-bad-interceptors` (ex-info) for a malformed
  metadata-map `:interceptors` value — a non-vector, or a vector carrying a
  non-interceptor entry. Loud-fail at registration per Conventions §No silent
  swallow: a malformed chain cannot be honoured and must not be silently
  dropped or coerced."
  [reg-fn-name id value reason]
  (error/throw-error!
    :rf.error/reg-event-bad-interceptors
    'rf/reg-event
    reason
    {:recovery :fix-registration
     :extra    {:reg-fn   reg-fn-name
                :id       id
                :got      value
                :expected "a vector of interceptor references (e.g. [:auth/required [:rf.interceptor/path [:cart]]])"}}))

(defn- throw-inline-interceptor-removed!
  "Raise `:rf.error/inline-interceptor-removed` (ex-info) at registration when a
  metadata-map `:interceptors` chain carries an INLINE interceptor value. Per
  EP-0022 §Event and frame chain grammar: chains carry REFERENCES only. The
  earliest, clearest fail point for a stale inline value (an `->interceptor`
  result, a `(path …)` / `(redact-interceptor …)` value, a value-Var) — a typo
  dies at `reg-event`, not at first dispatch. Mirrors the dispatch-time
  `interceptor-registry/resolve-chain` rejection with the same error id —
  both delegate to the ONE shared `error/throw-inline-interceptor-removed!`
  passing this site's `:where 'rf/reg-event`, reason, and ex-data."
  [reg-fn-name id value offending]
  (error/throw-inline-interceptor-removed!
    'rf/reg-event
    (str reg-fn-name " for `" id "` carried an INLINE interceptor value `"
         (pr-str offending) "` in its metadata `:interceptors` chain. "
         "Interceptor chains are reference-only (EP-0022): register the "
         "interceptor with `rf/reg-interceptor` and reference it by id — "
         "a bare keyword `:my/ic` or an `[id arg]` 2-vector "
         "(e.g. `[:rf.interceptor/path [:cart]]`).")
    {:reg-fn    reg-fn-name
     :id        id
     :got       value
     :offending offending
     :expected  "a vector of interceptor references (keyword ids / [id arg] vectors)"}))

(defn- validate-meta-interceptors!
  "Validate the metadata-map `:interceptors` value at registration. The value
  MUST be a vector, and every entry MUST be an interceptor REFERENCE (a bare
  keyword id or an `[id arg]` 2-vector — per `interceptor-entry?`). A no-op
  (returns `value`) for a well-shaped vector.

  Reference-only (EP-0022):
    - a non-vector value raises `:rf.error/reg-event-bad-interceptors`;
    - an INLINE interceptor value entry (a map carrying `:before` / `:after` /
      `:id`, an `->interceptor` result, a value-Var) raises the dedicated
      `:rf.error/inline-interceptor-removed` — the loud, actionable signal that
      chains are reference-only (vs. the generic bad-interceptors message);
    - any OTHER non-ref entry (a string, number, …) raises the generic
      `:rf.error/reg-event-bad-interceptors`."
  [reg-fn-name id value]
  (cond
    (not (vector? value))
    (throw-bad-interceptors-value!
      reg-fn-name id value
      (str reg-fn-name " for `" id "` carried a non-vector `:interceptors` value in "
           "its metadata-map; `:interceptors` must be a vector of interceptor "
           "references (e.g. `{:interceptors [:auth/required [:rf.interceptor/path [:cart]]]}`)."))

    (not (every? interceptor-entry? value))
    ;; Discriminate a stale INLINE value (the headline reference-only
    ;; footgun) from any other malformed entry, so the developer gets
    ;; the actionable "register + reference by id" message rather than a generic
    ;; bad-interceptors tell.
    (if-let [inline (some (fn [e]
                            (when (and (not (icpt-reg/interceptor-ref? e))
                                       (icpt-reg/interceptor-value? e))
                              e))
                          value)]
      (throw-inline-interceptor-removed! reg-fn-name id value inline)
      (throw-bad-interceptors-value!
        reg-fn-name id value
        (str reg-fn-name " for `" id "` carried a `:interceptors` vector with an "
             "entry that is not an interceptor reference (a keyword id, or an "
             "`[id arg]` 2-vector); register the interceptor with "
             "`rf/reg-interceptor` and reference it by id.")))

    :else value))

(defn- validate-refs-registered!
  "Per Spec 002 §Validation and resolution timing — registration-time
  validation: every interceptor REFERENCE in `chain` must name a registered
  interceptor. A reference to an absent id throws
  `:rf.error/unregistered-interceptor` at registration so typos die before
  dispatch. Only refs are checked here; the appended framework handler-wrapper
  (the one inline value a chain carries) is
  skipped — any other inline value has already been rejected by
  `validate-meta-interceptors!`. Resolution is deferred to chain assembly (the
  router) so hot-reloaded descriptors are picked up on the next dispatch."
  [chain]
  (doseq [entry chain]
    (when (icpt-reg/interceptor-ref? entry)
      ;; resolve-ref throws the structured :rf.error/unregistered-interceptor /
      ;; :rf.error/interceptor-factory-arity if the ref cannot resolve. We
      ;; discard the resolved value — this is a pure existence/shape check; the
      ;; actual resolution rides the dispatch-time chain assembly.
      (icpt-reg/resolve-ref entry)))
  chain)

;; ---- validate-at-boundary-interceptor registration-time validation -------
;;
;; The `:rf.schema/at-boundary` interceptor (per Spec 010 §Production builds)
;; is structurally meaningless without a `:schema` to validate against. The
;; registrar hard-rejects any handler that attaches the interceptor without
;; `:schema` metadata, throwing `:rf.error/at-boundary-missing-schema` at reg
;; time so the developer learns immediately, regardless of dev/prod gate.
;;
;; Detection is by interceptor `:id` (`:rf.schema/at-boundary`), not by var
;; equality — keeps `events` decoupled from `re-frame.spec` (which depends
;; transitively on this ns via core re-exports).

(defn- at-boundary-entry?
  "Truthy when a single RAW chain entry attaches the `:rf.schema/at-boundary`
  interceptor by REFERENCE — the bare keyword `:rf.schema/at-boundary` (the
  only legal chain form, EP-0022; the chain stores refs UNRESOLVED, so the
  bare keyword reaches here).

  The `[:rf.schema/at-boundary arg]` 2-vector form is NOT detected here: it is
  unreachable for this missing-schema check. `:rf.schema/at-boundary` is a
  STATIC interceptor (no `:factory`), so an `[:rf.schema/at-boundary arg]`
  chain ref is rejected at `validate-refs-registered!` with
  `:rf.error/interceptor-factory-arity` BEFORE
  `reject-at-boundary-without-schema!` (which calls this predicate) ever runs.
  The `[id arg]` shape is therefore simply an unregistered-factory-shape
  misuse and fails loud on its own.

  Chains are reference-only, so an inline `:rf.schema/at-boundary` value is
  rejected by `validate-meta-interceptors!` with
  `:rf.error/inline-interceptor-removed` before this runs. Detects by id
  keyword so the check stays cycle-free against `re-frame.spec`."
  [icpt]
  ;; By-ref: bare keyword.
  (= :rf.schema/at-boundary icpt))

(defn- attaches-validate-at-boundary-interceptor?
  "Truthy when the effective user interceptor chain attaches the
  `:rf.schema/at-boundary` interceptor by REF (`[:rf.schema/at-boundary]`, the
  only legal chain form, EP-0022). See
  `at-boundary-entry?`. Detects by id so the check stays cycle-free against
  `re-frame.spec`."
  [interceptors]
  (and (sequential? interceptors)
       (boolean (some at-boundary-entry? interceptors))))

(defn- reject-at-boundary-without-schema!
  "Raise `:rf.error/at-boundary-missing-schema` (ex-info) when the
  metadata-map `:interceptors` chain includes `:rf.schema/at-boundary`
  but the metadata-map carries no `:schema`. Per Spec 010 §Production
  builds: the boundary interceptor is structurally
  meaningless without a `:schema`, so the registrar rejects the call
  at registration time rather than waiting until first dispatch.

  Hard-fail by design (per the pre-alpha posture): no warn-and-accept
  fallback. The two fixes are (1) attach a `:schema` to the metadata
  map, or (2) remove the boundary interceptor."
  [reg-fn-name id meta interceptors]
  (when (and (attaches-validate-at-boundary-interceptor? interceptors)
             (not (and (map? meta) (contains? meta :schema))))
    (error/throw-error!
      :rf.error/at-boundary-missing-schema
      'rf/reg-event
      (str reg-fn-name " for `" id "` attaches the "
           "`:rf.schema/at-boundary` interceptor but the "
           "registration carries no `:schema` metadata. "
           "The boundary interceptor cannot validate "
           "without a schema and is structurally "
           "meaningless without one. Either attach a "
           "`:schema` to the metadata-map "
           "(recommended) or remove the boundary "
           "interceptor from metadata `:interceptors`.")
      {:recovery :no-recovery
       :extra    {:reg-fn reg-fn-name
                  :id     id}}))
  nil)

(defn boundary-guarded-handler?
  "True when a registered event handler's `handler-meta` REFERENCES the
  `:rf.schema/at-boundary` interceptor — i.e. the app opted this handler into
  production-side boundary validation (Spec 010 §Production builds).

  rf2-mwv4e — the DEV half of the boundary-rejection marker. In a dev build the
  boundary interceptor is a no-op and step-1 `validate-event!` does the
  refusing, so the router cannot tell a boundary refusal from an ordinary
  dev-only schema refusal without asking this question. It asks it ONLY on the
  refusal path (`run-chain`, when `event-ok?` is falsy), so the hot path pays
  nothing.

  Reads the AUTHORED chain — the same surface `reject-at-boundary-without-
  schema!` checks at registration time, and the one the docs name as the opt-in
  (`{:interceptors [:rf.schema/at-boundary]}`). A per-frame `:interceptors`
  chain that attaches the interceptor globally is deliberately NOT counted: the
  interceptor is meaningless without the HANDLER's own `:schema`, which is what
  the registration-time check binds it to."
  [handler-meta]
  (attaches-validate-at-boundary-interceptor? (:interceptors handler-meta)))

;; ---- effect-map shape policing (Spec migration M-8) -----------------------
;;
;; Per migration/from-re-frame-v1/README.md §M-8 and Spec-Schemas.md §:rf/effect-map,
;; the effect-map a reg-event handler returns is a CLOSED shape: only :db,
;; :rf.db/runtime, and :fx live at the top level. Legacy v1 top-level keys
;; (:dispatch, :dispatch-later, :dispatch-n, :http, etc.) must move into :fx
;; as [[fx-id args] ...] entries.
;;
;; EP-0001: the closed set is #{:db :rf.db/runtime :fx} (per Spec-Schemas
;; §:rf/effect-map + Spec 002 §Write authority). `:db` is the app-db partition;
;; `:rf.db/runtime` is the runtime-db partition — a state-bearing top-level
;; key, reserved BY CONVENTION for framework-authority writers (NOT a security
;; boundary). `:rf.db/runtime` is NOT a shape error
;; here — a non-framework handler emitting it is surfaced by the
;; `:rf.warning/app-handler-runtime-effect` dev diagnostic (in
;; `commit-fx-effects`), not dropped. All OTHER unknown top-level keys remain
;; shape errors.
;;
;; The runtime polices this contract at the router's FINAL-effects boundary:
;; a top-level key outside the closed set REFUSES the event. Nothing commits —
;; no `:db`, no `:rf.db/runtime`, no classification install, no `:fx` — and
;; :rf.error/effect-map-shape is emitted through the always-on error-emit
;; substrate (Spec 009 §Error contract, recovery :fix-effect). See
;; [[effect-map-defect]].

(def ^:private closed-effect-map-keys
  "The closed set of top-level effect-map keys a `reg-event` handler may
  return. Per Spec-Schemas §:rf/effect-map + Spec 002 §commit-plane effects:

    - `:db`              — app-db partition write;
    - `:rf.db/runtime`   — runtime-db partition write (framework-authority by
                           convention, EP-0001);
    - `:fx`              — everything else (the open do-fx plane);
    - `:sensitive` / `:large` / `:clear-sensitive` / `:clear-large` —
                           the four EP-0025 commit-plane data-classification
                           effects, applied WITH the `:db` write at the commit
                           point (a frame-state transform into the per-frame
                           elision registry), NOT routed through do-fx.

  These are all COMMIT-PLANE effects (state-bearing, applied at the atomic
  commit boundary), distinct from the open `:fx` do-fx plane. The four
  classification keys (EP-0025) join `:db` / `:rf.db/runtime` here so
  [[effect-map-defect]] does NOT report them as foreign top-level keys.
  Widening this set is a Spec change; any other top-level key is a shape
  error."
  #{:db :rf.db/runtime :fx
    :sensitive :large :clear-sensitive :clear-large})

;; ---- the FINAL-effects shape carrier (rf2-04tx) ---------------------------
;;
;; ONE check at ONE boundary. `effect-map-defect` is a PURE first-defect-or-nil
;; validator over the effects map the ROUTER is about to consume — the same
;; shape as `re-frame.elision/classification-effect-defect`, and consumed at the
;; same place (`re-frame.router/commit-and-flow!`'s FINAL-effects boundary,
;; immediately before the commit). The router emits `:rf.error/effect-map-shape`
;; IN-BAND (never a throw — a throw there escapes into
;; `drain-emergency-release!` and abandons the rest of the drained queue) and
;; ABORTS the event: no `:db`, no `:rf.db/runtime`, no classification install,
;; no `:fx`. No partial commit.
;;
;; ## Why refuse rather than drop (rf2-04tx)
;;
;; The runtime RECOGNISES the key — it polices it on every dispatch, in every
;; build — and then declines to honour it. Dropping it is the named violation
;; shape of Conventions §No silent swallow: the `:db` write lands while the
;; effect the programmer wrote never runs, so the handler LOOKS like it worked.
;; That partial-success disguise is what defeats a gate asserting a label plus
;; "nothing has happened yet"; it hid a dead `:dispatch-later` timer in the
;; hicasso testbed's operator instruments, and an eleven-week-dead
;; `persist-chart-collapsed` fx inside Xray itself. Both go red on first run
;; under the refusal.
;;
;; The refusal is UNIFORM ACROSS BUILDS and NOT configurable. Erasing it in
;; production would make dev abort what production commits — a build fork
;; strictly worse than either uniform choice. The DETECTION was already
;; unconditional and free (the zero-allocation `every?` pre-check below), so the
;; refusal hangs off an already-taken cold branch: no hot-path cost.
;;
;; ## What is NOT here
;;
;; Case (c) — a malformed ENTRY inside an otherwise-well-shaped `:fx` vector —
;; is NOT this check. That row lives on the post-commit best-effort do-fx plane
;; (`re-frame.fx/fx-entry-ok?`) and KEEPS `:logged-and-skipped` per-entry
;; recovery: the offending entry is dropped and its siblings still run. The line
;; falls on the commit boundary, matching the standing FX atomicity asymmetry —
;; pre-commit envelope validity is transactional, post-commit fx are
;; best-effort.

(defn effect-map-defect
  "PURE fail-loud-INPUT validator for the TOP-LEVEL shape of the FINAL effects
  map. Returns the FIRST defect as a map `{:offending-key <k> :value <bad>
  :reason <string>}` — or nil when the envelope is well-shaped. Does NOT throw
  and does NOT emit; the caller (the router's FINAL-effects boundary) emits
  `:rf.error/effect-map-shape` in-band and aborts the event pre-commit,
  mirroring the `legacy-runtime-root` / `classification-effect-shape`
  rejections at that same boundary.

  Two defect cases, checked in that order:

    (a) BAD TOP-LEVEL KEY — a key outside `closed-effect-map-keys` (per Spec
        migration M-8 + EP-0001). The commonest v1 reflex: a top-level
        `:dispatch` / `:dispatch-later` / `:http` / a user-registered fx-id
        sitting beside `:db`.
    (b) BAD `:fx` VALUE — a non-`nil`, non-sequential `:fx` (`{:fx :oops}`,
        `{:fx {…}}` — the forgot-the-outer-vector typo). Per Spec-Schemas
        §:rf/effect-map the `:fx` value is `[:vector [:tuple :keyword :any]]`.
        `nil` stays the legal \"no fx\" no-op (equivalent to an absent `:fx`).

  A non-map `effects` (the `nil` / no-effects-produced case) has no top-level
  shape to police and yields nil — the non-map HANDLER RETURN is a different
  category (`:rf.error/effect-handler-bad-return`, emitted at the handler-return
  site).

  `event` (the originating event vector) names the offending handler in the
  `:reason`; the caller supplies the event-id tags.

  Hot-path short-circuit: the well-shaped case is the overwhelming majority —
  handlers return `{}`, `{:db …}`, `{:fx …}`, or `{:db … :fx …}`. The key check
  is an allocation-free `every?` and the `:fx` check a single `sequential?`, so
  a clean effect map costs two predicate walks and allocates nothing."
  [effects event]
  (when (map? effects)
    (let [event-id (when (vector? event) (first event))]
      (if-not (every? closed-effect-map-keys (keys effects))
        ;; (a) — the first foreign key in the map's own key order (source order
        ;; for the small maps handlers return). One defect, one message: naming
        ;; every foreign key at once would bury the first mistake.
        (let [k (first (remove closed-effect-map-keys (keys effects)))]
          {:offending-key k
           :value         (get effects k)
           :reason        (str "Effect-map for `" event-id "` returned top-level key `" k
                               "`; the effect-map is closed — `:db`, `:rf.db/runtime`"
                               " and `:fx` (plus the four commit-plane"
                               " classification keys) are the only top-level keys."
                               " Move `" k "` into `:fx` (e.g. `{:fx [[" k " …]]}`)."
                               " The event is refused until corrected — nothing"
                               " committed.")})
        ;; (b) — the whole-`:fx` value.
        (let [fx-val (:fx effects)]
          (when-not (or (not (contains? effects :fx))
                        (nil? fx-val)
                        (sequential? fx-val))
            {:offending-key :fx
             :value         fx-val
             :reason        (str "Effect-map for `" event-id "` returned a `:fx` value of type `"
                                 (pr-str (type fx-val))
                                 "`; `:fx` must be a vector of `[fx-id args]` pairs"
                                 " (e.g. `[[:dispatch [:saved]]]`) — did you forget the"
                                 " outer vector? The event is refused until corrected —"
                                 " nothing committed.")}))))))

;; ---- handler-as-interceptor wrapper ---------------------------------------
;;
;; EP-0018: there is ONE event form, so the handler-wrapping interceptor is
;; ONE shape too. Its `:before`:
;;   1. honours :rf/skip-handler? (Spec 010 §Validation order),
;;   2. invokes the user handler with the coeffects map + the event vector
;;      (`(fn [coeffects event] effect-map)`),
;;   3. projects the returned closed effects map into the context via
;;      `commit-fx-effects` (the :db / :rf.db/runtime / :fx commit + shape
;;      policing).
;; The interceptor stamps the single id `:rf/event-handler` and carries
;; `:rf/default? true` so tools filter the framework auto-wrapper without an
;; id allowlist. There are no per-kind handler ids and no `:event/kind`
;; sub-tag.

(defn- police-runtime-effect-authority!
  "EP-0001: when `effects` carries a `:rf.db/runtime` effect AND
  the running handler does NOT have framework-write authority, emit the
  `:rf.warning/app-handler-runtime-effect` dev diagnostic. `:rf.db/runtime`
  is reserved BY CONVENTION for framework / runtime-extension code — NOT a
  security boundary: the effect is STILL applied (recovery
  `:warned`); the diagnostic names the runtime-db ownership rule rather than
  enforcing a capability or silently dropping the effect.

  Framework-minted handlers — those whose registration meta satisfies
  `framework-authority?` (the reserved `:rf/framework-authority? true` key,
  or the `:rf/machine?` implication), stamped onto the context as
  `:rf/framework-authority?` by `assemble-initial-ctx` — write
  `:rf.db/runtime` legitimately and DO NOT fire this diagnostic.

  Dev-only — gated on `interop/debug-enabled?` so production DCE-elides the
  whole check, the reason string, and the emit (per Spec 009 §Production
  builds)."
  [ctx event effects]
  (when (and interop/debug-enabled?
             (contains? effects :rf.db/runtime)
             (not (:rf/framework-authority? ctx)))
    (let [event-id (when (vector? event) (first event))]
      (trace/emit! :warning :rf.warning/app-handler-runtime-effect
                   {:rf.trace/event-id event-id
                    :rf.event/v        event
                    :frame             (interceptor/get-coeffect ctx :rf.frame/id)
                    :recovery          :warned
                    :reason
                    (str "Event `" event-id "` returned a reserved `:rf.db/runtime` effect, "
                         "but is not a framework-authority handler. `:rf.db/runtime` is the "
                         "framework-owned runtime-db partition — reserved by convention for "
                         "framework / runtime-extension code, not application handlers. The "
                         "effect is still applied (convention, not enforcement); ordinary app "
                         "code should reach subsystem state through public framework "
                         "subscriptions and effects, and write application data via `:db`.")}))))

;; ---- legacy :rf/runtime root — HARD ERROR (EP-0001) -----------------------
;;
;; Per Conventions §The legacy `:rf/runtime` root — hard error in final form
;; + 009 §Error event catalogue: the `:rf/runtime` app-db root key is reserved
;; and rejected. Framework durable state lives in the runtime-db partition
;; (`:rf.db/runtime`), addressed by `:rf.runtime/*` children. A stray
;; `:rf/runtime` root at the TOP of app-db is a HARD ERROR
;; (`:rf.error/legacy-runtime-root`); there is no migration alias.
;;
;; Under the two-partition contract an ordinary `:db` return replaces ONLY
;; app-db and CANNOT touch runtime-db (the clobber footgun is structurally
;; absent), so the only way `:rf/runtime` reaches app-db is a handler that
;; EXPLICITLY writes it — which this guard rejects loudly.

(def ^:private legacy-runtime-root-key
  "The reserved app-db root key this guard rejects. Framework durable state
  lives in the runtime-db partition; a stray `:rf/runtime` at the top of
  app-db is the legacy-shaped write this guard rejects."
  :rf/runtime)

(defn legacy-runtime-root?
  "True iff `app-db` carries the reserved `:rf/runtime` root key at its top
  level (per Conventions §The legacy `:rf/runtime` root). The
  pure detector both `reject-legacy-runtime-root!` (the in-chain throw) and
  the router's final-effects boundary (the in-band abort) share
  so the rejection rule has a single definition. Cheap on the hot path — a
  single `contains?` over the top-level keys."
  [app-db]
  (and (map? app-db) (contains? app-db legacy-runtime-root-key)))

(defn legacy-runtime-root-ex-data
  "The shared `:rf.error/legacy-runtime-root` ex-data / error-trace tag map
  for the offending `event`. Used both by `reject-legacy-runtime-root!`
  (carried on the thrown ex-info) and by the router's final-effects
  boundary emit so the two rejection sites surface an identical
  payload."
  [event]
  (let [event-id (when (vector? event) (first event))]
    {:rf.error/id   :rf.error/legacy-runtime-root
     :where         'rf/reg-event
     :event-id      event-id
     :event         event
     :recovery      :no-recovery
     :reason
     (str "Event `" event-id "` returned a `:db` value carrying the "
          "retired `:rf/runtime` app-db root. Framework runtime state "
          "now lives in the runtime-db partition (the reserved "
          "`:rf.db/runtime` effect, addressed by `:rf.runtime/*` "
          "children) — NOT under an app-db `:rf/runtime` root, which "
          "is retired. Move framework/runtime writes to the "
          "`:rf.db/runtime` effect; keep application data under `:db`.")
     :offending-key legacy-runtime-root-key}))

(defn reject-legacy-runtime-root!
  "Throw `:rf.error/legacy-runtime-root` (ex-info) when `app-db` carries the
  reserved `:rf/runtime` root key at its top level. Per Conventions §The
  legacy `:rf/runtime` root: a HARD ERROR — framework runtime state lives in
  the runtime-db partition, never under an app-db `:rf/runtime` root. A no-op
  (returns `app-db`) for any value that does not carry the key, so it is cheap
  on the hot path.

  Always-on (NOT dev-gated): a legacy-shaped write is a structural contract
  violation that must surface in every build, not a dev-only advisory.

  This is the IN-CHAIN guard (runs in the handler-wrapping interceptor's
  `:before`, so a handler-RETURN legacy root is captured by the interceptor
  machinery and surfaced as `:rf.error/handler-exception`). The symmetric
  FINAL-effects boundary check — covering a legacy root inserted by an
  `:after` interceptor AFTER this ran — lives in the router and
  emits in-band rather than throwing, so the drain is not aborted."
  [app-db event]
  (when (legacy-runtime-root? app-db)
    (throw (error/ex-info-from-data (legacy-runtime-root-ex-data event))))
  app-db)

(defn- commit-fx-effects
  "fx-kind commit: project the handler's returned effect map into the context.
  Bad-return / nil-return policy lives here — `nil` is the documented legal
  no-op; any non-map return emits :rf.error/effect-handler-bad-return with
  :no-recovery and the dispatch becomes a no-op.

  ONE CHECK AT ONE BOUNDARY (rf2-04tx). This site does NOT police the
  effect-map's top-level shape and is NOT an abort site. It projects the
  returned map VERBATIM — foreign top-level keys and a malformed `:fx` value
  included — so the shape the programmer actually wrote reaches the router's
  FINAL-effects boundary, where `effect-map-defect` decides ONCE. That is what
  makes the decision uniform across the two routes a top-level key can arrive
  by: a handler return (here) and an `:after`-interceptor insertion (which
  lands after this `:before` has already run). A key projected here is never
  ACTED on — the router refuses the whole event before any commit, and only
  closed-set keys are read downstream.

  EP-0001: `:db` targets the app-db partition; `:rf.db/runtime`
  targets the runtime-db partition (reserved by convention — a non-framework
  handler emitting it fires `:rf.warning/app-handler-runtime-effect` via
  `police-runtime-effect-authority!`, but the effect is still committed). The
  `:rf.db/runtime` effect is assoc'd into the context here; the partitioned
  commit (scoping `:db` to app-db, installing the runtime-db / full-frame
  write as one atomic frame-state transition) happens downstream."
  [ctx event effects]
  (let [frame-id (:rf.frame/id (:coeffects ctx))
        live?    #(or (nil? (frame/current-event-owner-token))
                      (frame/event-owner-live? frame-id))]
    (cond
      (nil? effects) ctx
      (not (map? effects))
      (do
        (trace/emit-error! :rf.error/effect-handler-bad-return
                           {:event-id      (when (vector? event) (first event))
                            :event         event
                            :returned      effects
                            :returned-type (type effects)
                            :reason        "reg-event handler returned a non-map; expected {:db ... :fx [...]}."
                            :recovery      :no-recovery})
        (if (live?) ctx (assoc ctx :rf/stale-incarnation? true)))
      :else
      (do
        (when (live?)
          (police-runtime-effect-authority! ctx event effects))
        (if-not (live?)
          (assoc ctx :rf/stale-incarnation? true)
          (cond-> ctx
            (seq effects) (update :effects merge effects)))))))

(def event-handler-interceptor-id
  "The single `:id` the framework stamps on the handler-wrapping interceptor
  (the terminal `:before` that invokes the user event handler). There is ONE
  such id (EP-0018) — a captured `:rf/interceptor-error` whose `:id` is this
  is the EVENT HANDLER itself throwing (vs. a user interceptor). A stable
  framework-owned contract."
  :rf/event-handler)

(defn- wrap-event-handler
  "Wrap `handler-fn` into the ONE event-handler interceptor whose :before
  runs the handler (EP-0018 — one wrapper shape for every event).

  The :before:
    (a) honours :rf/skip-handler? (Spec 010 steps 1-2 recovery — schema
        validation has already emitted its failure trace);
    (b) pulls the event vector from the coeffects;
    (c) invokes the user handler with the coeffects map + the event vector
        (`(fn [coeffects event] effect-map)`);
    (d) commits the returned closed effects map via `commit-fx-effects`.

  The interceptor stamps `:rf/event-handler` and carries `:rf/default? true`
  so tools (Xray, Story, the Event lens) can filter the
  framework's auto-wrapper without a hardcoded id allowlist. Self-describing:
  the meta lives on the interceptor map itself."
  [handler-fn]
  (interceptor/->interceptor*
    :id          event-handler-interceptor-id
    :rf/default? true
    :before
    (fn [ctx]
      (if (:rf/skip-handler? ctx)
        ctx
        (let [event       (interceptor/get-coeffect ctx :event)
              effects     (handler-fn (interceptor/get-coeffect ctx) event)
              frame-id    (:rf.frame/id (:coeffects ctx))
              ;; Authority is bound outside the authored context.  An
              ;; interceptor may rebuild or tamper with `ctx`, but cannot forge
              ;; ownership of a fresh same-id incarnation.
              owner-token (frame/current-event-owner-token)]
          ;; Option A terminal-incarnation law: the authored handler has
          ;; already entered, so it is allowed to return.  But if it destroyed
          ;; A (and perhaps installed a fresh same-id B), its returned value is
          ;; inert.  In particular, do not run handler-return shape policing or
          ;; legacy-root diagnostics against a value which can no longer have a
          ;; commit target.  `execute-chain` still unwinds every already-entered
          ;; authored `:after`; the framework-owned outer flows interceptor
          ;; observes this marker and fences the remaining tail.
          (if-not (frame/event-continuation-live? frame-id owner-token)
            (assoc ctx :rf/stale-incarnation? true)
            (let [new-ctx (commit-fx-effects ctx event effects)]
              ;; A trace/error listener reached by handler-return policing can
              ;; itself synchronously destroy A.  Recheck before the final
              ;; legacy-root diagnostic; loss makes the partially-produced
              ;; context inert just like direct loss in the handler body.
              (if-not (frame/event-continuation-live? frame-id owner-token)
                (assoc ctx :rf/stale-incarnation? true)
                (do
                  ;; EP-0001: a `:db` effect carrying the retired
                  ;; `:rf/runtime` app-db root is a HARD ERROR — reject it at
                  ;; the single post-commit chokepoint. No-op when absent.
                  (reject-legacy-runtime-root!
                    (interceptor/get-effect new-ctx :db) event)
                  new-ctx)))))))))

(defn event-handler-meta
  "Build the registrar-shaped handler-meta map for an event handler from a
  raw `handler-fn`, WITHOUT registering it. Returns the same shape
  `register-event!` installs: `meta` merged with `:handler-fn` and the
  `:interceptors` vector carrying the `:rf/event-handler` wrapping
  interceptor at its tail.

  The single source of truth for the handler-meta shape,
  shared by `register-event!` (the registration path) AND the machines
  lazy-actor-handler resolver (which materialises a spawned actor's
  handler-meta on demand from its app-db snapshot rather than from a
  per-instance registrar entry). Factoring the shape here keeps the two
  paths from drifting — the cascade in `re-frame.router/process-event*`
  drives whatever this returns, registered or lazily-resolved.

  `meta` is the registration metadata map (for a machine:
  `{:rf/machine? true :rf/machine <spec>}`); `interceptors` is the
  already-resolved interceptor prefix (empty for machines)."
  ([handler-fn] (event-handler-meta {} [] handler-fn))
  ([meta interceptors handler-fn]
   (assoc meta
          :handler-fn   handler-fn
          :interceptors (-> [] (into interceptors) (conj (wrap-event-handler handler-fn))))))

(defn framework-authority?
  "True when a handler's registration `meta` carries framework-write
  authority over the reserved `:rf.db/runtime` partition — i.e. the
  handler may legitimately return a `:rf.db/runtime` effect without
  tripping the `:rf.warning/app-handler-runtime-effect` dev diagnostic.

  EP-0001 — the GENERAL minting mechanism. A registration
  site mints authority by stamping the reserved `:rf/framework-authority?
  true` registration-meta key (see Conventions §Reserved registration
  meta). Spec 002 §Write authority names machines, routing, elision, and
  ssr as the legitimate runtime-db writers; each framework registrar
  stamps the key (the routing façade stamps it on its `reg-event`
  registrations; elision / ssr write the partition through privileged
  frame-state helpers, not event effects, so they need no event-handler
  authority — they never reach this predicate).

  Machine handlers imply authority from the framework-owned `:rf/machine?`
  stamp (the machine registrar `reg-machine*` already stamps `:rf/machine?
  true`), so they need no separate `:rf/framework-authority?` key — this
  predicate folds the implication in, keeping the machine contract
  unchanged.

  Reserved BY CONVENTION, not a capability gate: the
  effect is applied either way; the flag only governs the dev diagnostic."
  [meta]
  (boolean (or (:rf/framework-authority? meta)
               (:rf/machine? meta))))

;; ---- :rf.cofx/requires parsing (EP-0017 §4 / Spec 001 §The declaration key) -
;;
;; `:rf.cofx/requires` is a standard registration-metadata key (Spec 001 middle
;; slot) on `reg-event` declaring the handler's consumed coeffect ids. The
;; runtime delivers EXACTLY the declared facts, flat, into the handler's
;; coeffects map (declared-only delivery — Spec 002 §Satisfaction). Every
;; event takes the one coeffects-in form, so `:rf.cofx/requires` is uniformly
;; available to every event. The parsing / shape
;; validation / duplicate-id check lives in `re-frame.cofx/parse-requires`; the
;; parsed entries are stored on the registration under
;; `:rf.cofx/requires-parsed` for the satisfaction step
;; (`assemble-initial-ctx`) and the raw value is retained under
;; `:rf.cofx/requires` so `handler-meta` surfaces it exactly as authored
;; (Spec 009 §9).

;; ---- registration ---------------------------------------------------------

;; ---- bare-interceptor detection ------------------------------------------
;;
;; The `reg-event` interceptor chain lives under metadata `:interceptors`
;; and MUST be a VECTOR (per Spec 001 §Allowed forms of the middle slot).
;; A BARE interceptor —
;; `(reg-event id mw/some-interceptor handler)` rather than
;; `(reg-event id {:interceptors [mw/some-interceptor]} handler)` — is a
;; recognised-but-unhonourable input: an interceptor built by `->interceptor` /
;; `->interceptor*` is a *map* (`{:id … :before … :after …}`), so without this
;; detection `normalise-args`' two-arg branch would read it as the
;; metadata-map, the chain would never reach the registrar, and the
;; interceptor would never run — a silent drop.
;;
;; Per Conventions §No silent swallow — recognised input MUST signal: a
;; bare interceptor is a recognised-but-unhonourable input, and the cascade
;; cannot continue meaningfully (the user's chain is simply absent). We
;; therefore FAIL LOUD at registration with `:rf.error/reg-event-bare-interceptor`
;; — an ERROR, not a warning, consistent with the sibling registration-time
;; throws (`:rf.error/reg-event-bad-middle-slot`, `:rf.error/reg-event-bad-arity`,
;; `:rf.error/at-boundary-missing-schema`). We do NOT silently coerce
;; `bare → {:interceptors [bare]}`: that would be magic; the caller wraps it.

(defn- bare-interceptor-map?
  "True when `x` is a map carrying interceptor fn keys (`:before` / `:after`)
  — i.e. a bare interceptor passed where the metadata-map was expected.
  A legitimate registration metadata-map (`:doc`,
  `:schema`, `:tags`, `:platforms`, …) never carries `:before` / `:after`,
  so those keys are an unambiguous bare-interceptor tell. Detection is by
  shape (not by `->interceptor` provenance) so it catches HoF / hand-rolled
  interceptor maps too."
  [x]
  (and (map? x)
       (or (contains? x :before)
           (contains? x :after))))

(defn- throw-bare-interceptor!
  "Raise `:rf.error/reg-event-bare-interceptor` (ex-info) for a bare
  interceptor handed to `reg-event` where a metadata map was required.
  Loud-fail at registration per Conventions §No silent swallow — the
  interceptor would otherwise be silently dropped and never run."
  [reg-fn-name slot offending args]
  (error/throw-error!
    :rf.error/reg-event-bare-interceptor
    'rf/reg-event
    (str reg-fn-name " received a BARE interceptor in the " (name slot)
         " slot; the interceptor chain belongs in metadata "
         "`:interceptors` and MUST be a vector. A bare interceptor map "
         "would be silently dropped (never run). Wrap it: `("
         reg-fn-name " id {:interceptors [the-interceptor]} handler)` "
         "— not `(" reg-fn-name " id the-interceptor handler)`.")
    {:recovery :fix-registration
     :extra    {:reg-fn   reg-fn-name
                :slot     slot
                :got      offending
                :expected "metadata-map with :interceptors (e.g. {:interceptors [:my/ic]})"
                :args     args}}))

(defn- normalise-args
  "Accept the two documented shapes for the variadic tail of `reg-event`:
    (handler)          — bare handler
    (metadata handler) — metadata-map, optionally carrying `:interceptors`
  Per Spec 001 §Allowed forms of the middle slot. Returns
  `[metadata handler]`.

  Loud-fails on a BARE interceptor: an interceptor map handed
  where a metadata-map was expected is rejected with
  `:rf.error/reg-event-bare-interceptor` rather than silently dropped — the
  two-arg branch catches `(reg-event id bare-icpt handler)` (a map with
  `:before` / `:after`)."
  [reg-fn-name args]
  (case (count args)
    1 [{} (first args)]
    2 (let [[middle handler] args]
        (cond
          (bare-interceptor-map? middle)
          (throw-bare-interceptor! reg-fn-name :middle middle args)
          (map? middle)    [middle handler]
          :else            (error/throw-error!
                             :rf.error/reg-event-bad-middle-slot
                             'rf/reg-event
                             "the middle slot of a reg-event call must be a metadata-map (e.g. {:doc \"...\" :interceptors [:my/ic]}); the positional interceptor vector is retired"
                             {:recovery :fix-registration
                              :extra    {:args     args
                                         :got      middle
                                         :expected "metadata-map (e.g. {:doc \"...\" :interceptors [:my/ic]})"}})))
    (error/throw-error!
      :rf.error/reg-event-bad-arity
      'rf/reg-event
      "reg-event expects (id handler) or (id metadata handler); put interceptor chains in metadata :interceptors"
      {:recovery :fix-registration
       :extra    {:args  args
                  :count (count args)}})))

(defn- merge-form-source
  "Merge `*pending-form-source*` into `m` under `:rf.handler/source`
  (Spec 009 §`:rf.handler/source`, Xray Spec 021 §11.2 B.7 stretch).
  User-supplied `:rf.handler/source` overrides the auto-
  captured value (mirrors `source-coords/merge-coords` semantics — so
  tooling that synthesises registrations from another source can stamp
  the original form-source). Returns `m` unchanged when no source is
  pending (programmatic / REPL registrations that bypass the macro
  path).

  Production elision: the whole body is gated on
  `interop/debug-enabled?`. Under `:advanced` + `goog.DEBUG=false`
  Closure constant-folds the gate to `false` and DCEs the entire merge
  — both the literal `:rf.handler/source` keyword's reachability from
  this slot AND the dynamic-var lookup. Layered with the macro-emitted
  `(if interop/debug-enabled? ~src-string nil)` gate on the bound
  value, the source-string bytes themselves never reach the bundle.
  JVM/SSR/test builds (where `interop/debug-enabled?` is true by
  default) always capture."
  [m]
  (if-not interop/debug-enabled?
    m
    (let [src source-coords/*pending-form-source*]
      (if (and src (not (contains? m :rf.handler/source)))
        (assoc m :rf.handler/source src)
        m))))

(defn- resolve-interceptors
  "Resolve the metadata-map `:interceptors` superset form into the effective
  user-supplied interceptor chain, returning `[clean-meta effective-interceptors]`.

  The metadata-map carries a reserved `:interceptors` key. A non-vector /
  non-interceptor value raises `:rf.error/reg-event-bad-interceptors`.

  RETAIN-vs-STRIP: the raw `:interceptors`
  key is STRIPPED from the stored metadata. The registrar entry already stores
  the EFFECTIVE chain (user interceptors + the framework wrapper) under
  `:interceptors`; retaining the raw user value under the same key would collide
  with — and shadow — that authoritative introspection surface, while a
  different key would duplicate it. Tooling answers \"which interceptors does
  this handler carry?\" from the one
  effective `:interceptors` chain the registrar already holds (the user chain is
  recoverable as `(remove :rf/default? interceptors)`)."
  [reg-fn-name id meta]
  (if-not (and (map? meta) (contains? meta :interceptors))
    [meta []]
    (let [meta-interceptors (validate-meta-interceptors!
                              reg-fn-name id (:interceptors meta))]
      ;; Per Spec 002 §Validation and resolution timing: validate that every
      ;; REFERENCE resolves at registration (typos die before dispatch), but
      ;; store the chain UNRESOLVED — the router resolves refs at chain
      ;; assembly so a hot-reloaded interceptor descriptor is picked up on the
      ;; next dispatch (EP-0022).
      (validate-refs-registered! meta-interceptors)
      [(dissoc meta :interceptors) meta-interceptors])))

;; ---- reserved framework-standard event ids (EP-0027) ----------------------
;;
;; A small closed set of `:rf/*` event ids are FRAMEWORK STANDARDS the
;; framework itself owns and registers (into both the regular registrar and the
;; EP-0023 image standard registry). App code MUST NOT re-register one through
;; the public `reg-event` — a re-registration is a RESERVED-ID COLLISION that
;; fails loud (`:rf.error/reserved-event-id`), per Conventions §Reserved
;; namespaces ("A user may not `(reg-event :rf/hydrate ...)`") and EP-0027
;; §`:rf/set-db`. The framework's OWN registration goes through the private
;; `registrar/register!` path (see `register-set-db-standard!`), so this guard —
;; which fires only on the public `register-event!` entry — does not reject the
;; framework's own seeding. The set is deliberately narrow (today only
;; `:rf/set-db`): the OTHER framework `:rf/*` events (`:rf/hydrate`,
;; `:rf.route/url-requested`, `:rf/server-init`, …) are still registered through the
;; public `reg-event` by their owning feature artefacts, so they are NOT listed
;; here; widening the set is a Spec change.

(def reserved-event-ids
  "The closed set of framework-standard `:event` ids the public `reg-event`
  refuses to register over (EP-0027 §`:rf/set-db`). A reserved id is owned and
  seeded by the framework via the private registrar path; an app `reg-event`
  targeting one is a loud reserved-id collision (`:rf.error/reserved-event-id`).
  Today: `#{:rf/set-db}`. Fixed-and-additive — adding a member is a Spec change."
  #{:rf/set-db})

(defn- reject-reserved-event-id!
  "Throw `:rf.error/reserved-event-id` (ex-info) when a PUBLIC `reg-event` names
  a framework-standard reserved event id (`reserved-event-ids`). Per EP-0027
  §`:rf/set-db` + Conventions §Reserved namespaces — the `:rf/*` single-root is
  framework-owned; re-registering a reserved standard in app code is a
  collision that fails loud rather than silently shadowing framework behaviour.
  A no-op for any non-reserved id (the overwhelming common case), so the public
  registration hot path pays one set membership check."
  [reg-fn-name id]
  (when (contains? reserved-event-ids id)
    (error/throw-error!
      :rf.error/reserved-event-id
      'rf/reg-event
      (str reg-fn-name " cannot register `" id "` — it is a RESERVED "
           "framework-standard event id (EP-0027). The `:rf/*` single-root "
           "namespace is framework-owned; `" id "` is registered by the "
           "framework itself (in both the regular registrar and the image "
           "standard registry) and may not be re-registered in app code. "
           "Choose an application-namespaced id (e.g. `:my-app/" (name id) "`).")
      {:recovery :fix-registration
       :extra    {:reg-fn reg-fn-name
                  :id     id}}))
  nil)

(defn- register-event!
  "Registration body for `reg-event` — the one public event form (EP-0018).

  Steps:
    0. reject a RESERVED framework-standard event id (EP-0027 — e.g.
       `:rf/set-db`): the `:rf/*` single-root is framework-owned, so an app
       `reg-event` over one is a loud reserved-id collision;
    1. parse the variadic tail into [metadata handler];
    2. resolve the interceptor chain from metadata `:interceptors` via
       `resolve-interceptors`: validates the map value and strips
       the raw key from the stored meta;
    3. wrap the user handler into the `:rf/event-handler` interceptor via
       `wrap-event-handler`;
    4. register under `:event` with `:handler-fn` retained for tooling
       introspection and `:rf.handler/source` carrying the macro-captured
       form-source string when present (Spec 009);
    5. return the event id. Path-D schema-first privacy has no
       user-facing redaction interceptor to police at registration time.

  Returns the event id."
  [reg-fn-name id args]
  (reject-reserved-event-id! reg-fn-name id)
  (let [[raw-meta handler-fn] (normalise-args reg-fn-name args)
        ;; rf2-x68lzo — no-silent-swallow on the registration metadata KEYS:
        ;; a retired bare key (`:spec`) hard-errors, an unknown bare key warns,
        ;; namespaced/known keys pass. Runs on the raw user meta (which still
        ;; carries `:interceptors`, a known `:event` key) BEFORE `:interceptors`
        ;; is stripped by `resolve-interceptors`.
        _ (reg-meta/validate-registration-metadata! :event 'rf/reg-event id raw-meta)
        [meta interceptors] (resolve-interceptors reg-fn-name id raw-meta)
        wrapped (wrap-event-handler handler-fn)]
    ;; Per Spec 010 §Production builds: reject the
    ;; registration when `:rf.schema/at-boundary` is attached but no
    ;; `:schema` is declared on the metadata-map. The boundary
    ;; interceptor is structurally meaningless without a schema, so
    ;; surface the misconfiguration at the moment of registration
    ;; (always — both dev and prod) rather than waiting for the first
    ;; dispatch in production.
    (reject-at-boundary-without-schema! reg-fn-name id meta interceptors)
    ;; EP-0017 §4: parse + validate `:rf.cofx/requires` into the normalised
    ;; entry vector the satisfaction step consumes (`cofx/deliver-declared-cofx`,
    ;; via `assemble-initial-ctx`), raising `:rf.error/cofx-request-invalid` /
    ;; `:rf.error/cofx-name-collision` on a malformed / duplicate declaration.
    ;; EP-0018: `:rf.cofx/requires` is uniformly available — the one form is
    ;; coeffects-in.
    ;; Per Spec 015 §1. Event handlers: VALIDATE any declared `:sensitive` /
    ;; `:large` classification fail-loud BEFORE the registrar write; the
    ;; classification itself is DERIVED from the registrar meta at
    ;; `registration-classification` read time, no imperative stash. DIRECT
    ;; REQUIRE: `classification` is core-owned, same-artefact, pinned into the
    ;; prod bundle, and always-on, so the require needs no late-bind hop and is
    ;; cycle-free. Runs for MACHINE registrations too: a machine's author
    ;; classification rides its `:event` reg meta (via `reg-machine` opts);
    ;; `registration-classification :event <id>` returns those registrar-derived
    ;; author paths (EP-0025 — frame-declared paths are the sole app-db
    ;; classification mechanism).
    (classification/validate-classification! :event meta)
    ;; rf2-kqxe6.20: replacing a framework REPLACEABLE DEFAULT
    ;; (`:rf.route/entry-denied` / `:rf.route/navigation-blocked`) replaces
    ;; BEHAVIOUR — not the framework's own payload shape. The carriers the
    ;; framework CONSTRUCTS in that payload (`:requested-url` / `:destination` /
    ;; `:target`) embed query values and path params, so their `:sensitive`
    ;; classification is the framework's own fact and rides forward across the
    ;; override, unioned with anything this registration declared. Runs AFTER
    ;; `validate-classification!` so a malformed app declaration still fails on
    ;; its own terms; a no-op for every id that is not a framework default.
    (let [meta            (image-assembly/retain-framework-default-classification
                            :event id meta)
          requires-parsed (cofx/parse-requires id (:rf.cofx/requires meta))]
      (registrar/register! :event id
        (cond-> (assoc (-> meta source-coords/merge-coords merge-form-source)
                       :handler-fn   handler-fn
                       :interceptors (-> [] (into interceptors) (conj wrapped)))
          (seq requires-parsed)
          (assoc :rf.cofx/requires-parsed requires-parsed)))
      ;; rf2-kqxe6.20, the INVERSE half of the retention above: namespace load
      ;; order must not decide the effective classification. When THIS
      ;; registration is the framework seeding its replaceable default, an
      ;; application override for the same id may ALREADY be in the source store
      ;; (an app namespace that registers `:rf.route/entry-denied` and never
      ;; requires `re-frame.routing` loads first whenever something else requires
      ;; the facade later) — and that descriptor, recorded before the framework's
      ;; own existed, carries none of the framework's carriers. Reconcile it here
      ;; so BOTH orders converge; a no-op (one source-store read) for every id
      ;; that is not a framework replaceable default.
      (image-assembly/reconcile-framework-default-classification! :event id))
    id))

(defn reg-event
  "Register a `(fn [coeffects event-vec] effect-map)` event handler under
  `id` — the ONE public event-registration form (EP-0018). Coeffects in, a
  closed effects map out.

  The handler is **pure** — it receives a coeffect map (carrying `:db`,
  `:event`, plus exactly the facts it declared via `:rf.cofx/requires`,
  delivered flat) and the event vector, and returns the effect-map (or
  `nil`). The runtime walks the effects in order:

  1. `:db`  — atomic swap to the frame's `app-db` (Spec 002 §`:fx`
     ordering, rule 1). The db write is an explicit effect like any other;
     there is no db-only return shape.
  2. `:fx`  — vector of `[fx-id args]` pairs, processed in source order
     by the registered fx handlers (see `reg-fx`).

  The effect-map is a **closed shape** — `#{:db :rf.db/runtime :fx}` at
  the top level (per migration M-8 + EP-0001). App handlers return only
  `:db` and `:fx`; `:rf.db/runtime` (the runtime-db partition) is
  reserved by convention for framework / runtime-extension authority and
  is not part of an ordinary handler's vocabulary. Legacy v1 top-level
  keys (`:dispatch`, `:dispatch-later`, `:http`, ...) wrap as `:fx`
  entries — `{:fx [[:dispatch event] ...]}`.

  The handler is **two-arg** `(fn [coeffects event-vec] …)` (EP-0018 D4).
  Handlers that do not need the event use `_` for the second argument;
  `(:event coeffects)` is the same value.

  Shapes (the optional middle slot is the **superset** metadata map —
  it carries reflection metadata **and** a reserved `:interceptors`
  chain in one map):

      (reg-event :id                              (fn [cofx ev] {...}))
      (reg-event :id {:doc \"...\"}                 (fn [cofx ev] {...}))
      (reg-event :id {:rf.cofx/requires [:rf/time-ms]}
                                                 (fn [cofx ev] {...}))
      (reg-event :id {:doc \"...\" :interceptors [:my/ic]}
                                                 (fn [cofx ev] {...}))

  Coeffects are declared via `:rf.cofx/requires` on the metadata map (the
  value arrives FLAT in the cofx map under its id) — uniformly available
  to every event. `inject-cofx` is not available. Put the interceptor chain
  under metadata `:interceptors`.

  Returns `id`. Returning `nil` from the handler is a documented no-op.

  Full-context work that a `(fn [context] context)` handler expresses
  is done with a registered interceptor (authored with `reg-interceptor`,
  the public `context -> context` form) referenced by id from a `reg-event`
  registration's `:interceptors` chain.

  Example — a pure db update (the common case):

      (rf/reg-event :counter/inc
        (fn [{:keys [db]} _]
          {:db (update db :n inc)}))

      (rf/dispatch [:counter/inc])

  Example — coeffects + effects:

      (rf/reg-event :user/save
        {:rf.cofx/requires [:rf/time-ms]}
        (fn [{:keys [db rf/time-ms]} [_ user]]
          {:db (assoc db :user/pending? true :user/saved-at time-ms)
           :fx [[:dispatch [:analytics/track :user-save]]
                [:rf.http/managed {:method :post
                                   :url    \"/api/users\"
                                   :body   user
                                   :on-success [:user/saved]
                                   :on-failure [:user/save-failed]}]]}))

  See also: `reg-fx` (register a custom fx), `reg-cofx` (register a
  coeffect supplier; declare consumption via `:rf.cofx/requires`),
  `reg-interceptor` (the public `context -> context` form for
  full-context work), `dispatch`, `dispatch-sync`."
  [id & args]
  (register-event! "reg-event" id args))

;; ---- :rf/set-db — the framework-standard app-db seeding event (EP-0027) ----
;;
;; EP-0027 §`:rf/set-db`: the framework registers ONE standard event for
;; seeding app-db. Construction is events-only (there is no `:initial-db` data
;; key) — seeding app-db is itself an ordinary, traceable event, `[:rf/set-db
;; {…}]`.
;;
;;   - The handler returns `{:db new-db}`, so it replaces the APP-DB PARTITION
;;     ONLY (it cannot touch runtime-db) and rides the NORMAL post-commit app-db
;;     schema validation / rollback like any `:db` effect — no special-cased
;;     direct write.
;;   - It validates EXACTLY ONE MAP ARGUMENT: a missing, `nil`, non-map, or
;;     extra-trailing argument fails with `:rf.error/set-db-bad-value`, raised
;;     through `error/throw-error!` so it THROWS (EP-0027 §Failure — a bad
;;     `[:rf/set-db x]` argument is a setup-step throw). `:rf/set-db` takes a
;;     single map and has no second-argument meaning, so `[:rf/set-db {} :junk]`
;;     is a mis-call (rf2-izy3b2), not a silently-ignored extra. Set app-db
;;     empty with `[:rf/set-db {}]`.
;;   - It REPLACES all of app-db (it is NOT a merge); for partial updates, write
;;     an ordinary event.
;;
;; It is registered as a framework standard in BOTH the regular registrar AND
;; the EP-0023 image standard registry (`register-set-db-standard!`), so it
;; resolves whether or not a frame's image generation is in scope and every
;; frame can dispatch it. Re-registering `:rf/set-db` in app code is a
;; reserved-id collision (`reserved-event-ids` → `:rf.error/reserved-event-id`).

(def set-db-event-id
  "The framework-standard app-db-seeding event id (EP-0027 §`:rf/set-db`). Lives
  in the single-root `:rf/*` namespace; `:rf.db/*` stays reserved for partition
  slots."
  :rf/set-db)

(defn- valid-set-db-arg?
  "True iff `x` is the EXACTLY-one-map argument `:rf/set-db` accepts — a map
  (`{}` included; that is the legal empty-app-db case). A missing arg arrives
  here as `nil`; `nil` and any non-map value are rejected. Per EP-0027
  §`:rf/set-db`."
  [x]
  (map? x))

(defn set-db-handler
  "The `:rf/set-db` event handler (EP-0027 §`:rf/set-db`). A pure `(fn
  [coeffects event-vec] effect-map)`: it reads the SECOND element of the event
  vector (`[:rf/set-db new-db]`) and returns `{:db new-db}`, replacing the whole
  app-db partition.

  Validates EXACTLY one MAP argument: a missing / `nil` / non-map `new-db`, OR
  any extra trailing args (`[:rf/set-db {} :junk]`), raises
  `:rf.error/set-db-bad-value` through `error/throw-error!` (so it THROWS — a
  setup-step failure per EP-0027 §Failure). A valid map (including `{}`)
  returns `{:db new-db}` and rides the normal post-commit app-db schema
  validation / rollback. It REPLACES app-db (not a merge)."
  [_coeffects event]
  (let [new-db (second event)
        ;; EP-0027 §`:rf/set-db`: EXACTLY ONE argument. The event vector is
        ;; `[:rf/set-db new-db]` (count 2); any trailing args (count > 2) are a
        ;; mis-call — `:rf/set-db` REPLACES the whole app-db with its single map
        ;; argument and has no second-argument meaning, so extra args were
        ;; previously SILENTLY IGNORED (a fail-open gap on the framework's own
        ;; reserved seed event, against the EP's clarity-over-leniency posture).
        ;; rf2-izy3b2: reject them LOUD with the same discriminator. (count 1 —
        ;; the no-argument case — falls through to `valid-set-db-arg?` below,
        ;; which rejects the `nil` it reads as the missing arg.)
        extra-args? (> (count event) 2)]
    (when (or extra-args? (not (valid-set-db-arg? new-db)))
      (error/throw-error!
        :rf.error/set-db-bad-value
        'rf/set-db
        (str "`[:rf/set-db x]` requires EXACTLY ONE MAP argument — the new "
             "app-db. Got "
             (cond
               extra-args? (str (dec (count event)) " arguments ("
                                (pr-str (vec (rest event)))
                                ") — `:rf/set-db` takes a single map and has no "
                                "second-argument meaning")
               (nil? new-db) "no argument (or nil)"
               :else         (str "`" (pr-str new-db) "` (a "
                                  (pr-str (type new-db)) ")"))
             ". `:rf/set-db` REPLACES the whole app-db partition; pass a map "
             "(use `[:rf/set-db {}]` to empty app-db).")
        {:recovery :no-recovery
         :extra    {:event new-db
                    :rf.event/v event}}))
    {:db new-db}))

(def ^:private set-db-standard-meta
  "The Spec 001 registration metadata the `:rf/set-db` standard ships. Shared by
  the regular-registrar registration and the EP-0023 framework-standard registry
  descriptor so both surfaces carry an identical `:doc` (rf2-v1xzoo). NOT
  marked replaceable / invariant-coupled: it is an ordinary developer-friendly
  standard (default non-replaceable, no conformance invariant)."
  {:doc "Framework-standard app-db seeding event (EP-0027). `[:rf/set-db
        {…}]` REPLACES the whole app-db partition with the supplied map and
        rides normal post-commit schema validation / rollback. Validates
        exactly one map argument (missing / nil / non-map / extra trailing args
        → throws :rf.error/set-db-bad-value). Use `[:rf/set-db {}]` to empty
        app-db."})

(defn register-set-db-standard!
  "Register the framework-standard `:rf/set-db` event (EP-0027 §`:rf/set-db`)
  into the active registrar AND the EP-0023 framework-standard registry.
  Idempotent — called at namespace load AND from `re-frame.core/init!` so the
  standard survives a test fixture's `registrar/clear-all!` (which wipes the
  `:event` kind). Mirrors `std-interceptors/register-standard-interceptors!`.

  TWO surfaces, ONE handler:

    * the REGULAR registrar (the default-image / no-generation resolution path,
      where `registrar/lookup` reads the registrar atom directly). The
      framework registers via the PRIVATE `registrar/register!` path — NOT the
      public `reg-event` — so the `reserved-event-ids` guard (which fires only
      on the public `register-event!` entry) does not reject the framework's
      own seeding while still rejecting an app `(reg-event :rf/set-db …)`.

    * the EP-0023 FRAMEWORK-STANDARD registry (`image-assembly/register-
      standard!`) — so the descriptor is unioned into EVERY resolved image
      generation. Without this, an image-loaded frame whose construction
      dispatches `[:rf/set-db …]` under a bound `*generation*` could not
      resolve it (generation-routed `lookup` reads ONLY the generation's
      resolver — no fallback to the registrar atom).

  Both surfaces carry the SAME runnable descriptor shape `register-event!`
  installs — `:handler-fn` + the `:interceptors` chain whose tail is the
  `:rf/event-handler` wrapper (built by `event-handler-meta`) — so a
  generation-routed resolution returns a value byte-shape-identical to the
  registrar path and the handler RUNS through a frame-targeted dispatch."
  []
  (let [descriptor (event-handler-meta set-db-standard-meta [] set-db-handler)]
    ;; Regular registrar — private path (bypasses the public reserved-id guard).
    (registrar/register! :event set-db-event-id descriptor)
    ;; EP-0023 framework-standard registry — same descriptor, unioned into every
    ;; resolved image generation. Default non-replaceable, no conformance
    ;; invariant (an ordinary developer-friendly standard).
    (image-assembly/register-standard! :event set-db-event-id descriptor))
  set-db-event-id)

;; Register at namespace load so standalone require'rs (no `init!`) can dispatch
;; `[:rf/set-db …]`; `init!` re-registers (idempotent) for the post-clear-all!
;; test path. Mirrors `std-interceptors`' load-time + `init!` re-seed.
(register-set-db-standard!)

;; ---- retired public names — facade-exported throwing stubs ----------------
;;
;; EP-0018 §2/§3 + EP-0007 rule 2: `reg-event-db` / `reg-event-fx` are REMOVED
;; and public `reg-event-ctx` is demoted to a framework-internal primitive.
;; There is NO working alias (an alias would preserve exactly the vocabulary
;; the EP removes). Instead the retired public NAMES survive ONLY as throwing
;; stubs so a stale `(rf/reg-event-db …)` call site fails LOUDLY with an
;; actionable error naming the replacement — never an opaque "no such var".
;;
;; These are FACADE-EXPORTED (resolvable as `rf/reg-event-db` / `-fx` / `-ctx`
;; via the `re-frame.core` `def` aliases below) but `^:no-doc`, so the API
;; manifest generator + the CLJS publics probe DROP them — they carry no
;; manifest row and do not appear in the public API surface. They register
;; nothing; calling one raises its naming hard error. Each error rides the
;; always-on observability channel (Spec 009 catalogue) — it is a correctness
;; contract that fires in production too.

(defn- raise-removed-reg-event!
  "Fan out + throw the EP-0018 retired-name hard error `error-kw` for a stale
  call to a removed public event registrar `reg-fn-name` (a string like
  \"reg-event-db\"), naming `replacement` and showing `fix` (the actionable
  conversion). The `inject-cofx` removed-stub twin: composes the EP-0018
  reason then delegates to the ONE shared `cofx/raise-removed!`,
  which surfaces on the always-on error-emit listener (production-survivable)
  AND the dev trace bus, then throws the ex-info carrying the same
  `:rf.error/id` — the offending `id` rides the trace + ex-data under `:id`."
  [error-kw reg-fn-name where id replacement fix]
  (let [reason (str "`" reg-fn-name "` is REMOVED in EP-0018 (no alias). "
                    "Use `" replacement "` instead — " fix)]
    (cofx/raise-removed! error-kw where reason id :id)))

;; ---- data-driven removed event-registration names ------------------------
;;
;; The three retired public event registrars are described ONCE in the data
;; table below, not as three near-identical bespoke stubs. Each row carries the
;; behavioural facts the stub needs — the facade symbol it answers to, the
;; exact `:rf.error/*` id it raises, the replacement surface it names, and the
;; actionable conversion `:fix` that lands in the error `:reason`. The thin
;; `^:no-doc` stubs that follow each delegate to `raise-removed-reg-event-by-
;; row!`, which resolves the row and fans out on the always-on error channel +
;; dev trace bus before throwing (via the shared `raise-removed-reg-event!`).
;; Adding / retiring a name is a one-row edit, and the audit surface is the
;; single literal vector — exactly the data-over-functions shape the
;; removed-symbol audit wants. The stubs are plain `defn`s (NOT macro-
;; generated), so they compile identically on JVM and CLJS.
;;
;; `:fix` is either a plain string or a `(fn [id] …)` of the offending id (one
;; row needs the id woven into its message); the by-row thrower normalises both.

(def ^:private removed-reg-event-names
  "The EP-0018 retired public event-registration names, one row each — the
  single audit surface for the removed event registrars. A row is
  `{:sym :error-kw :where :replacement :fix}`:
    - `:sym`         the bare var symbol exported `^:no-doc` from this ns and
                     aliased onto the `re-frame.core` facade;
    - `:error-kw`    the exact `:rf.error/*` id the stub raises;
    - `:where`       the `'rf/<name>` symbol the hard error attributes to;
    - `:replacement` the public surface the error names as the fix;
    - `:fix`         the actionable conversion woven into the error `:reason`
                     (a string, or a `(fn [id] string)` when the id matters).
  Adding / retiring a name is a one-row edit here; the per-name stub bodies
  below are thin lookups into this table and never change."
  [{:sym         'reg-event-db
    :error-kw    :rf.error/reg-event-db-removed
    :where       'rf/reg-event-db
    :replacement "reg-event"
    :fix         (str "destructure `:db` from the coeffects map and wrap the "
                      "return in `{:db …}`: "
                      "`(reg-event id (fn [{:keys [db]} ev] {:db BODY}))`.")}
   {:sym         'reg-event-fx
    :error-kw    :rf.error/reg-event-fx-removed
    :where       'rf/reg-event-fx
    :replacement "reg-event"
    :fix         "it is the identical shape — just rename the call."}
   {:sym         'reg-event-ctx
    :error-kw    :rf.error/reg-event-ctx-removed
    :where       'rf/reg-event-ctx
    :replacement "reg-interceptor"
    :fix         (str "express full-context work as a registered interceptor "
                      "(`rf/reg-interceptor` with `:before` / `:after`) and "
                      "reference it by id from a `reg-event` registration's "
                      "`:interceptors` chain.")}])

(def ^:private removed-reg-event-by-sym
  "`removed-reg-event-names` indexed by bare `:sym` for the stubs' lookup."
  (into {} (map (juxt :sym identity)) removed-reg-event-names))

(defn ^:no-doc raise-removed-reg-event-by-row!
  "Raise the EP-0018 retired-name hard error for `row` (one entry of
  `removed-reg-event-names`) on a stale call carrying `args`. Resolves the
  row's `:fix` (string or `(fn [id] string)`) against the offending id, then
  fans out + throws via `raise-removed-reg-event!`. Every stub delegates here
  so the throw path + per-name facts live in ONE place (the data table), not
  in three near-duplicate bodies."
  [row args]
  (let [{:keys [error-kw sym where replacement fix]} row
        id  (first args)
        fix (if (fn? fix) (fix id) fix)]
    (raise-removed-reg-event! error-kw (name sym) where id replacement fix)))

;; The three retired names survive as thin `^:no-doc` facade stubs — each body
;; is a one-line lookup into `removed-reg-event-names`, so the behavioural
;; facts (error id, attributed symbol, replacement, conversion `:fix`) live
;; ONCE in the data table rather than in three hand-maintained bodies. Plain
;; `defn`s (not macro-generated) so they compile identically on JVM and CLJS —
;; the data table, not codegen, is what makes this data-driven and keeps the
;; removed-symbol surface auditable from one literal vector. Each var stays
;; `^:no-doc` (dropped from the manifest generator + CLJS publics probe — no
;; manifest row). Per spec/001-Registration.md §The retired event-registration
;; names.

(defn ^:no-doc reg-event-db
  "REMOVED in EP-0018 (no alias). Calling `reg-event-db` is the hard error
  `:rf.error/reg-event-db-removed`, naming `reg-event` as the replacement.
  See spec/001-Registration.md §The retired event-registration names."
  [& args]
  (raise-removed-reg-event-by-row! (get removed-reg-event-by-sym 'reg-event-db) args))

(defn ^:no-doc reg-event-fx
  "REMOVED in EP-0018 (no alias). Calling `reg-event-fx` is the hard error
  `:rf.error/reg-event-fx-removed`, naming `reg-event` as the replacement
  (the identical shape under the bare name). See spec/001-Registration.md
  §The retired event-registration names."
  [& args]
  (raise-removed-reg-event-by-row! (get removed-reg-event-by-sym 'reg-event-fx) args))

(defn ^:no-doc reg-event-ctx
  "DEMOTED to a framework-internal primitive in EP-0018 (off the public
  surface). Calling public `reg-event-ctx` is the hard error
  `:rf.error/reg-event-ctx-removed`, naming `reg-interceptor` as the public
  replacement for application full-context work. See spec/001-Registration.md
  §The retired event-registration names."
  [& args]
  (raise-removed-reg-event-by-row! (get removed-reg-event-by-sym 'reg-event-ctx) args))

(defn clear-event
  "Unregister an event handler. Zero-arity clears every registered
  event handler in the registrar; one-arity clears the named one.

  Hot-reload tools and test fixtures call this between rebuilds to
  drop stale handlers; production code rarely needs it. Returns nil.

  See also: `reg-event`."
  ([] (registrar/clear-kind! :event))
  ([id] (registrar/unregister! :event id)))

;; ---- EP-0023 inline-registration lowering --------------------------------
;;
;; An image's inline `:registrations` `:reg-event` entry carries the raw
;; handler fn under `:impl` (image lowering is the pure `re-frame.image`
;; slice, which has no access to the event wrapper). For the inline handler
;; to RUN through a frame-targeted dispatch, the assembled image generation's
;; resolver descriptor must carry the SAME runnable slots `register-event!`
;; installs — `:handler-fn` + the `:interceptors` chain whose tail is the
;; `:rf/event-handler` wrapper. This lowering closes the EP-0023 §Image
;; Fragments contract: "Both paths should lower to the same runtime descriptor
;; shape." Published via late-bind (image-assembly cannot static-require this
;; ns — `subs` requires `live-frame` which requires `image-assembly`, so the
;; whole inline-lowering family rides the same forward-reference seam).

(defn lower-inline-event
  "Lower an inline `:reg-event` descriptor's raw fn body into the runnable
  event-handler slots — the same shape `register-event!` stores and
  `event-handler-meta` produces: `:handler-fn` + the `:interceptors` chain
  carrying the `:rf/event-handler` wrapper, PLUS `:rf.cofx/requires-parsed`
  when the inline entry declared `:rf.cofx/requires`.

  `meta` is the inline entry's metadata map. It is MERGED onto the runnable
  slots at TOP LEVEL — exactly as `register-event!` stores the registration
  metadata (`event-handler-meta` `assoc`s the runnable slots onto `meta`) — so
  the inline path lowers to the SAME runtime descriptor shape a registered
  handler carries (EP-0023 §Image Fragments: \"both paths should lower to the
  same runtime descriptor shape\"). Without the merge, top-level registration-
  meta the runtime reads at run/enqueue time was silently dropped for inline
  image handlers — e.g. `:rf.trace/no-emit?` (rf2-x76af2.25): the enqueue-time
  `:rf.event/dispatched` gate and the handler-scope run-trace gate both read the
  flag at TOP LEVEL, so an inline image handler marked `:rf.trace/no-emit?`
  still flooded the trace stream because the flag lived only under the
  descriptor's nested `:metadata`. Symmetric with `lower-inline-cofx`, which
  likewise hoists the cofx grade flags the delivery step reads.

  The cofx-requirements are additionally parsed via `cofx/parse-requires` into
  the TOP-LEVEL `:rf.cofx/requires-parsed` slot exactly as `register-event!`
  does (EP-0017 §4/§5) — load-bearing: the satisfaction step
  (`router/assemble-initial-ctx`) reads it to deliver the declared coeffects,
  so an inline image-loaded event MUST carry it or its declared facts are
  silently dropped. A malformed / duplicate declaration fails loud at lowering
  (`:rf.error/cofx-request-invalid` / `:rf.error/cofx-name-collision`),
  mirroring registration.

  The other event-meta slot affecting the runnable chain — author-declared
  `:interceptors` references — remains an advanced inline shape out of this
  slice's scope (the descriptor already carries the inline `:metadata` for
  introspection). `impl` is the raw handler fn.

  Returns the runnable slots so image-assembly merges them onto the
  descriptor, preserving `:impl` + provenance for replacement-winner
  coordinates and dedupe."
  [meta impl]
  (let [requires-parsed (cofx/parse-requires :rf/image-inline-event
                                             (:rf.cofx/requires meta))]
    (cond-> (event-handler-meta meta [] impl)
      (seq requires-parsed) (assoc :rf.cofx/requires-parsed requires-parsed))))

(late-bind/set-fn! :image/lower-inline-event lower-inline-event)
