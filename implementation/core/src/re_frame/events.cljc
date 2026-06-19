(ns re-frame.events
  "Event-handler registration. Per Spec 002 §Event handlers and Spec 001
  §Registry model.

  The ONE public event form is `reg-event` — pure (cofx, event) → a closed
  effects map (EP-0018, ruled 2026-06-14; final removal flip rf2-xhfxcs.14).
  Coeffects in, a closed effects map out.

  EP-0018 collapsed the historical three-form family to one. `reg-event-db` /
  `reg-event-fx` are REMOVED and public `reg-event-ctx` is demoted to a
  framework-internal `context -> context` primitive; calling any of the
  retired public names is a hard error naming the replacement (the
  facade-exported throwing stubs `re-frame.events/reg-event-db` /
  `reg-event-fx` / `reg-event-ctx` raise
  `:rf.error/reg-event-db-removed` / `-fx-removed` / `-ctx-removed`, per
  EP-0007 rule 2 — no working alias). Full-context work in application code
  is expressed with interceptors authored via the public `reg-interceptor`
  form and referenced by id from a `reg-event` registration's
  `:interceptors` chain (`->interceptor` is the internal lowering
  constructor post-EP-0022, not the application authoring form).

  All event registrations register under registry kind `:event` and share the
  ONE handler-wrapping interceptor `:rf/event-handler` (`:rf/default? true`);
  the historical `:event/kind` sub-tag and the per-kind `:rf/db-handler` /
  `:rf/fx-handler` / `:rf/ctx-handler` ids are gone (EP-0018 §5). The runtime
  treats every event uniformly during drain.

  Per Spec 015 §1. Event handlers — the registration meta-map accepts
  optional `:sensitive [paths]` and `:large [paths]` keys that index
  into the dispatched event vector's arg-map (the second element).
  The marks are DERIVED from the registrar meta at `re-frame.marks/marks-for`
  read time (rf2-ehexnw); reg-event only VALIDATES them fail-loud via
  `re-frame.marks/validate-marks!` (called through the late-bind hook to keep
  events decoupled from the optional marks artefact), no imperative stash."
  (:require [re-frame.interop :as interop]
            [re-frame.registrar :as registrar]
            [re-frame.interceptor :as interceptor]
            [re-frame.interceptor-registry :as icpt-reg]
            [re-frame.late-bind :as late-bind]
            [re-frame.cofx :as cofx]
            [re-frame.error :as error]
            [re-frame.source-coords :as source-coords]
            [re-frame.trace :as trace]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- metadata-map `:interceptors` — the superset middle slot (rf2-bpmszk) -
;;
;; The `reg-event` metadata-map carries a RESERVED `:interceptors` key,
;; making the map the ONE superset middle-slot shape:
;; `{:doc … :schema … :sensitive … :interceptors [i1 i2]}`.
;; The historical positional interceptor VECTOR form (`[i1 i2]`) is retired:
;; put the chain under `:interceptors` in the metadata map.
;;
;; This SUPERSEDES the former rf2-bbea warning
;; (`:rf.warning/interceptors-in-metadata-map`): `:interceptors` inside the
;; metadata-map is now the documented home, not a typo. The Circle-Drawer
;; footgun rf2-w3vn flagged (a silently-dropped chain) is structurally gone —
;; the chain is now honoured, not dropped.
;;
;; The malformed-value guard below keeps the superset honest: a non-vector
;; `:interceptors` value, or a vector carrying a non-map (non-interceptor)
;; entry, is a LOUD registration error (`:rf.error/reg-event-bad-interceptors`),
;; consistent with the existing reg-event arg policing (bare-interceptor /
;; bad-middle-slot / bad-arity).

(defn- interceptor-entry?
  "True when `x` is a valid `:interceptors` chain entry. REFERENCE-ONLY under
  EP-0022 (the flip, rf2-0adhqs.9): an entry MUST be an interceptor REFERENCE —
  a bare keyword id or an `[id arg]` 2-vector. Refs resolve to their registered
  values at chain assembly. An INLINE interceptor value (a map carrying `:id` /
  `:before` / `:after`, an `->interceptor` result, a value-Var) is no longer a
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
  EP-0022 §Event and frame chain grammar (the reference-only flip,
  rf2-0adhqs.9): chains carry REFERENCES only. The earliest, clearest fail
  point for a stale inline value (an `->interceptor` result, a `(path …)` /
  `(redact-interceptor …)` value, a value-Var) — a typo dies at `reg-event`,
  not at first dispatch. Mirrors the dispatch-time
  `interceptor-registry/resolve-chain` rejection with the same error id."
  [reg-fn-name id value offending]
  (error/throw-error!
    :rf.error/inline-interceptor-removed
    'rf/reg-event
    (str reg-fn-name " for `" id "` carried an INLINE interceptor value `"
         (pr-str offending) "` in its metadata `:interceptors` chain. "
         "Interceptor chains are reference-only (EP-0022): register the "
         "interceptor with `rf/reg-interceptor` and reference it by id — "
         "a bare keyword `:my/ic` or an `[id arg]` 2-vector "
         "(e.g. `[:rf.interceptor/path [:cart]]`).")
    {:recovery :fix-registration
     :extra    {:reg-fn    reg-fn-name
                :id        id
                :got       value
                :offending offending
                :expected  "a vector of interceptor references (keyword ids / [id arg] vectors)"}}))

(defn- validate-meta-interceptors!
  "Validate the metadata-map `:interceptors` value at registration. The value
  MUST be a vector, and every entry MUST be an interceptor REFERENCE (a bare
  keyword id or an `[id arg]` 2-vector — per `interceptor-entry?`). A no-op
  (returns `value`) for a well-shaped vector.

  Reference-only since EP-0022 (the flip, rf2-0adhqs.9):
    - a non-vector value raises `:rf.error/reg-event-bad-interceptors`;
    - an INLINE interceptor value entry (a map carrying `:before` / `:after` /
      `:id`, an `->interceptor` result, a value-Var) raises the dedicated
      `:rf.error/inline-interceptor-removed` — the loud, actionable signal that
      chains are now reference-only (vs. the generic bad-interceptors message);
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
    ;; Discriminate a stale INLINE value (the EP-0022 reference-only flip's
    ;; headline footgun) from any other malformed entry, so the developer gets
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
  (the one inline value a chain carries since the reference-only flip) is
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

;; ---- validate-at-boundary-interceptor registration-time validation (rf2-iftj4) -----------------
;;
;; The `:rf.schema/at-boundary` interceptor (per Spec 010 §Production builds)
;; is structurally meaningless without a `:schema` to validate against. Per
;; rf2-ycqtv finding #8 (Mike-pick option (b)), the registrar hard-rejects
;; any handler that attaches the interceptor without `:schema` metadata,
;; throwing `:rf.error/at-boundary-missing-schema` at reg time so the
;; developer learns immediately, regardless of dev/prod gate.
;;
;; Detection is by interceptor `:id` (`:rf.schema/at-boundary`), not by var
;; equality — keeps `events` decoupled from `re-frame.spec` (which depends
;; transitively on this ns via core re-exports).

(defn- at-boundary-entry?
  "Truthy when a single RAW chain entry attaches the `:rf.schema/at-boundary`
  interceptor by REFERENCE — the bare keyword `:rf.schema/at-boundary` (the
  only legal chain form since the EP-0022 reference-only flip, rf2-0adhqs.9;
  the chain stores refs UNRESOLVED, so the bare keyword reaches here).

  The `[:rf.schema/at-boundary arg]` 2-vector form is NOT detected here: it is
  unreachable for this missing-schema check. `:rf.schema/at-boundary` is a
  STATIC interceptor (no `:factory`), so an `[:rf.schema/at-boundary arg]`
  chain ref is rejected at `validate-refs-registered!` with
  `:rf.error/interceptor-factory-arity` BEFORE
  `reject-at-boundary-without-schema!` (which calls this predicate) ever runs
  (rf2-48ypb6.1, rf2-wjr8ow). The `[id arg]` shape is therefore simply an
  unregistered-factory-shape misuse and fails loud on its own.

  The former INLINE-VALUE migration form — the `validate-at-boundary-interceptor`
  Var dropped directly into the chain — is no longer accepted (chains are
  reference-only; `validate-meta-interceptors!` rejects it
  `:rf.error/inline-interceptor-removed` before this runs). Detects by id
  keyword so the check stays cycle-free against `re-frame.spec`."
  [icpt]
  ;; By-ref: bare keyword.
  (= :rf.schema/at-boundary icpt))

(defn- attaches-validate-at-boundary-interceptor?
  "Truthy when the effective user interceptor chain attaches the
  `:rf.schema/at-boundary` interceptor by REF (`[:rf.schema/at-boundary]`, the
  only legal chain form since the EP-0022 reference-only flip). See
  `at-boundary-entry?`. Detects by id so the check stays cycle-free against
  `re-frame.spec`."
  [interceptors]
  (and (sequential? interceptors)
       (boolean (some at-boundary-entry? interceptors))))

(defn- reject-at-boundary-without-schema!
  "Raise `:rf.error/at-boundary-missing-schema` (ex-info) when the
  metadata-map `:interceptors` chain includes `:rf.schema/at-boundary`
  but the metadata-map carries no `:schema`. Per Spec 010 §Production
  builds + rf2-iftj4: the boundary interceptor is structurally
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

;; ---- effect-map shape policing (Spec migration M-8) -----------------------
;;
;; Per migration/from-re-frame-v1/README.md §M-8 and Spec-Schemas.md §:rf/effect-map,
;; the effect-map a reg-event handler returns is a CLOSED shape: only :db,
;; :rf.db/runtime, and :fx live at the top level. Legacy v1 top-level keys
;; (:dispatch, :dispatch-later, :dispatch-n, :http, etc.) must move into :fx
;; as [[fx-id args] ...] entries.
;;
;; EP-0001 (rf2-bvwoi4): the partition split widened the closed set from
;; #{:db :fx} to #{:db :rf.db/runtime :fx} (per Spec-Schemas §:rf/effect-map +
;; Spec 002 §Write authority). `:db` is the app-db partition (still targets
;; app-db); `:rf.db/runtime` is the runtime-db partition — the one new
;; state-bearing top-level key, reserved BY CONVENTION for framework-authority
;; writers (NOT a security boundary). `:rf.db/runtime` is NOT a shape error
;; here — a non-framework handler emitting it is surfaced by the
;; `:rf.warning/app-handler-runtime-effect` dev diagnostic (in
;; `commit-fx-effects`), not dropped. All OTHER unknown top-level keys remain
;; shape errors.
;;
;; The runtime polices this contract: any top-level key outside the closed set
;; emits a structured :rf.error/effect-map-shape trace per Spec 009 §Error
;; contract, with :recovery :logged-and-skipped. The offending key is dropped
;; (NOT merged silently nor routed through the fx machinery).

(def ^:private closed-effect-map-keys
  "The closed set of top-level effect-map keys a `reg-event` handler may
  return. Per Spec-Schemas §:rf/effect-map — `:db` (app-db partition),
  `:rf.db/runtime` (runtime-db partition, framework-authority by convention,
  EP-0001 rf2-bvwoi4), and `:fx` (everything else). Widening this set is a
  Spec change; any other top-level key is a shape error."
  #{:db :rf.db/runtime :fx})

(defn- police-effect-map-shape!
  "Emit :rf.error/effect-map-shape for each top-level key in `effects`
  outside `closed-effect-map-keys`. Per Spec migration M-8 + EP-0001 the
  effect-map is closed at the top level (`#{:db :rf.db/runtime :fx}`).
  Returns the list of offending keys (which the caller drops).

  Hot-path short-circuit (rf2-4ymm0 EV4): the well-shaped case is the
  overwhelming majority — handlers return `{}`, `{:db ...}`, `{:fx ...}`,
  or `{:db ... :fx ...}`. Allocating an `offending` vector per dispatch
  for the every-key-walks-the-closed-set check is wasted work. Pre-check
  via `every?` (no allocation), and fall through to the doseq/vec build
  only when at least one key is offending."
  [effects event]
  (if (every? closed-effect-map-keys (keys effects))
    nil
    (let [event-id (when (vector? event) (first event))
          offending (->> (keys effects)
                         (remove closed-effect-map-keys)
                         (vec))]
      (doseq [k offending]
        (let [v      (get effects k)
              reason (str "Effect-map for `" event-id "` returned top-level key `" k
                          "`; only `:db`, `:rf.db/runtime`, and `:fx` are allowed at the top level.")]
          (trace/emit-error! :rf.error/effect-map-shape
                             {:failing-id        event-id
                              :rf.trace/event-id event-id
                              :rf.event/v        event
                              :offending-key     k
                              :value             v
                              :reason            reason
                              :recovery          :logged-and-skipped})))
      offending)))

;; ---- :fx VALUE-shape policing (rf2-24zly) ---------------------------------
;;
;; `police-effect-map-shape!` above polices the top-level KEYS only. The
;; one shape it does NOT check is the :fx VALUE: per Spec-Schemas §:rf/effect-map
;; the :fx value is `[:vector [:tuple :keyword :any]]` — a sequential of
;; [fx-id args] pairs. A handler that returns a non-sequential :fx value
;; (e.g. `{:fx :oops}` or `{:fx {:dispatch [...]}}` — a plausible
;; forgot-the-outer-vector typo) used to escape policing entirely: the value
;; was assoc'd straight into the effects map, and `fx/do-fx`'s
;; `(doseq [pair fx-vec] ...)` then threw an uncaught host exception
;; ("Don't know how to create ISeq from: …"). Because :fx runs AFTER the :db
;; commit, that throw escaped `process-event!` into the drain's emergency
;; release: app-db left mutated, no structured trace, no `:on-error` fire,
;; downstream queued events abandoned.
;;
;; We close the gap symmetric with M-8: a non-sequential (and non-nil) :fx
;; value emits :rf.error/effect-map-shape (recovery :logged-and-skipped) and
;; is DROPPED — `:db` still commits, the cascade is not aborted, and there is
;; no low-level throw. `nil` stays the legal "no fx" no-op (equivalent to an
;; absent :fx).

(defn- fx-value-ok?
  "True iff the `:fx` value in `effects` is shaped well enough to walk — i.e.
  absent, `nil` (legal no-op), or sequential (the documented
  `[[fx-id args] ...]` vector). A non-nil, non-sequential `:fx` value is the
  forgot-the-outer-vector typo: emit :rf.error/effect-map-shape naming the
  offending handler and return false so the caller drops `:fx`."
  [effects event]
  (let [fx-val (:fx effects)]
    (if (or (not (contains? effects :fx))
            (nil? fx-val)
            (sequential? fx-val))
      true
      (let [event-id (when (vector? event) (first event))
            reason   (str "Effect-map for `" event-id "` returned a `:fx` value of type `"
                          (pr-str (type fx-val))
                          "`; `:fx` must be a vector of `[fx-id args]` pairs"
                          " (e.g. `[[:dispatch [:saved]]]`) — did you forget the outer vector?")]
        (trace/emit-error! :rf.error/effect-map-shape
                           {:failing-id        event-id
                            :rf.trace/event-id event-id
                            :rf.event/v        event
                            :offending-key     :fx
                            :value             fx-val
                            :reason            reason
                            :recovery          :logged-and-skipped})
        false))))

;; ---- final-effects boundary policing (rf2-u1kdvg) -------------------------
;;
;; `commit-fx-effects` (below) polices the effect-map shape + the whole-`:fx`
;; value for a `reg-event` HANDLER RETURN — at the moment the handler-wrapping
;; interceptor's `:before` commits the returned effects map. That is BEFORE the
;; `:after` interceptor pass runs (`execute-chain` runs every `:before` then
;; every `:after` — see `re-frame.interceptor/execute-chain`).
;;
;; So the per-handler-return checks DO NOT cover effects that arrive at the
;; commit boundary by another route: a framework / user `:after` interceptor
;; mutates `[:effects …]` after the handler-return checks have already run
;; (docs/guide §09 documents `:after` interceptors adding/modifying
;; `:effects`/`:fx`).
;;
;; The router consumes the FINAL `(:effects final-ctx)` after the whole chain
;; (every `:before` AND every `:after`) has run. `police-final-effects!` is
;; the single authoritative shape gate applied THERE, so the closed
;; effect-map + whole-`:fx` contract holds uniformly regardless of how an
;; effect reached the final context (the handler return, framework
;; interceptors, or a user `:after`). It returns the CLEANED effects map —
;; foreign top-level keys dropped, a non-sequential `:fx` dropped — so a
;; malformed final effect never reaches `commit-frame-effects!` (silent
;; ignore of a foreign key) nor `fx/do-fx` (a raw host throw after the db
;; commit). Both drops emit `:rf.error/effect-map-shape`
;; (`:logged-and-skipped`) exactly like the per-handler-return path.

(defn police-final-effects!
  "Police the FINAL effects map the router is about to consume against the
  closed effect-map contract (per Spec-Schemas §:rf/effect-map + EP-0001):
  top-level keys are `#{:db :rf.db/runtime :fx}` and the `:fx` value is a
  sequential of `[fx-id args]` pairs. Returns the CLEANED effects map with
  any offending top-level key and a non-sequential `:fx` value dropped;
  each drop emits `:rf.error/effect-map-shape` (`:logged-and-skipped`).

  This is the boundary gate covering effects that bypass the
  `commit-fx-effects` per-handler-return checks — an `:after`-interceptor
  mutation (rf2-u1kdvg). `nil` / a non-map effects value (no effects
  produced) passes through untouched.

  Hot-path short-circuit: when the effects map is already well-shaped (the
  overwhelming majority — `{}`, `{:db …}`, `{:fx [...]}`, `{:db … :fx …}`),
  `police-effect-map-shape!` allocates nothing and `fx-value-ok?` is a
  single `sequential?` check, so the map is returned unchanged with no
  reconstruction."
  [effects event]
  (if-not (map? effects)
    effects
    (let [offending (police-effect-map-shape! effects event)
          cleaned   (if (seq offending) (apply dissoc effects offending) effects)]
      (if (fx-value-ok? cleaned event)
        cleaned
        (dissoc cleaned :fx)))))

;; ---- handler-as-interceptor wrapper ---------------------------------------
;;
;; EP-0018 (rf2-xhfxcs.14): the historical db/fx/ctx triple collapsed to ONE
;; event form, so the handler-wrapping interceptor is ONE shape too. Its
;; `:before`:
;;   1. honours :rf/skip-handler? (Spec 010 §Validation order, rf2-7leq/jwm4),
;;   2. invokes the user handler with the coeffects map + the event vector
;;      (`(fn [coeffects event] effect-map)`),
;;   3. projects the returned closed effects map into the context via
;;      `commit-fx-effects` (the :db / :rf.db/runtime / :fx commit + shape
;;      policing).
;; The interceptor stamps the single id `:rf/event-handler` and carries
;; `:rf/default? true` so tools filter the framework auto-wrapper without an
;; id allowlist. (The former per-kind `:rf/db-handler` / `:rf/fx-handler` /
;; `:rf/ctx-handler` ids + the `:event/kind` sub-tag are gone.)

(defn- police-runtime-effect-authority!
  "EP-0001 (rf2-bvwoi4): when `effects` carries a `:rf.db/runtime` effect AND
  the running handler does NOT have framework-write authority, emit the
  `:rf.warning/app-handler-runtime-effect` dev diagnostic. `:rf.db/runtime`
  is reserved BY CONVENTION for framework / runtime-extension code (Mike
  ruling #4) — NOT a security boundary: the effect is STILL applied (recovery
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

;; ---- legacy :rf/runtime root — HARD ERROR (EP-0001 rf2-tfepxu) -------------
;;
;; Per Conventions §The legacy `:rf/runtime` root — hard error in final form
;; (decision #8) + 009 §Error event catalogue: the former single reserved
;; app-db root `:rf/runtime` is RETIRED. Framework durable state now lives in
;; the runtime-db partition (`:rf.db/runtime`), addressed by `:rf.runtime/*`
;; children. A stray `:rf/runtime` root surviving at the TOP of app-db —
;; whether written by user code or carried over from v1-shaped code — is a
;; HARD ERROR (`:rf.error/legacy-runtime-root`) in the final form (pre-alpha:
;; no long-lived migration alias; the temporary migration WARNING is removed
;; now that all in-repo consumers are on runtime-db).
;;
;; This supersedes the retired `:rf.warning/runtime-state-dropped` containment
;; diagnostic: under the two-partition contract an ordinary `:db` return
;; replaces ONLY app-db and CANNOT touch runtime-db (the clobber footgun is
;; structurally gone), so the only remaining way `:rf/runtime` reaches app-db
;; is a handler that EXPLICITLY writes it — which this guard rejects loudly.

(def ^:private legacy-runtime-root-key
  "The retired app-db root key. Framework durable state moved to the
  runtime-db partition; a stray `:rf/runtime` at the top of app-db is the
  legacy-shaped write this guard rejects."
  :rf/runtime)

(defn legacy-runtime-root?
  "True iff `app-db` carries the retired `:rf/runtime` root key at its top
  level (per Conventions §The legacy `:rf/runtime` root + decision #8). The
  pure detector both `reject-legacy-runtime-root!` (the in-chain throw) and
  the router's final-effects boundary (rf2-u1kdvg, the in-band abort) share
  so the rejection rule has a single definition. Cheap on the hot path — a
  single `contains?` over the top-level keys."
  [app-db]
  (and (map? app-db) (contains? app-db legacy-runtime-root-key)))

(defn legacy-runtime-root-ex-data
  "The shared `:rf.error/legacy-runtime-root` ex-data / error-trace tag map
  for the offending `event`. Used both by `reject-legacy-runtime-root!`
  (carried on the thrown ex-info) and by the router's final-effects
  boundary emit (rf2-u1kdvg) so the two rejection sites surface an identical
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
  retired `:rf/runtime` root key at its top level. Per Conventions §The
  legacy `:rf/runtime` root + decision #8: a HARD ERROR in the final form —
  framework runtime state lives in the runtime-db partition now, never under
  an app-db `:rf/runtime` root. A no-op (returns `app-db`) for any value that
  does not carry the legacy key, so it is cheap on the hot path.

  Always-on (NOT dev-gated): a legacy-shaped write is a structural contract
  violation that must surface in every build, not a dev-only advisory.

  This is the IN-CHAIN guard (runs in the handler-wrapping interceptor's
  `:before`, so a handler-RETURN legacy root is captured by the interceptor
  machinery and surfaced as `:rf.error/handler-exception`). The symmetric
  FINAL-effects boundary check — covering a legacy root inserted by an
  `:after` interceptor AFTER this ran — lives in the router (rf2-u1kdvg) and
  emits in-band rather than throwing, so the drain is not aborted."
  [app-db event]
  (when (legacy-runtime-root? app-db)
    (throw (error/ex-info-from-data (legacy-runtime-root-ex-data event))))
  app-db)

(defn- commit-fx-effects
  "fx-kind commit: enforce the closed effect-map (M-8 + EP-0001) and assoc
  :db / :rf.db/runtime / :fx into the context. Bad-return / nil-return policy
  lives here too — `nil` is the documented legal no-op (rf2-k3bj); any
  non-map return emits :rf.error/effect-handler-bad-return with :no-recovery
  and the dispatch becomes a no-op.

  Two shape checks run before commit: `police-effect-map-shape!` rejects
  top-level keys outside `#{:db :rf.db/runtime :fx}` (M-8 + EP-0001), and
  `fx-value-ok?` rejects a non-sequential `:fx` value (rf2-24zly) — both emit
  :rf.error/effect-map-shape (:logged-and-skipped) and DROP the offending
  slot, so a malformed effect map never reaches `fx/do-fx` to throw a raw
  host exception after the :db commit.

  EP-0001 (rf2-bvwoi4): `:db` targets the app-db partition; `:rf.db/runtime`
  targets the runtime-db partition (reserved by convention — a non-framework
  handler emitting it fires `:rf.warning/app-handler-runtime-effect` via
  `police-runtime-effect-authority!`, but the effect is still committed). The
  `:rf.db/runtime` effect is assoc'd into the context here; the actual
  PARTITIONED commit (scoping `:db` to app-db, installing the runtime-db /
  full-frame write as one atomic frame-state transition) lands in rf2-adwcv6
  (bead 5)."
  [ctx event effects]
  (cond
    (nil? effects) ctx                       ;; documented legal no-op
    (not (map? effects))
    (do (trace/emit-error! :rf.error/effect-handler-bad-return
                           {:event-id      (when (vector? event) (first event))
                            :event         event
                            :returned      effects
                            :returned-type (type effects)
                            :reason        "reg-event handler returned a non-map; expected {:db ... :fx [...]}."
                            :recovery      :no-recovery})
        ctx)
    :else
    (do
      (police-effect-map-shape! effects event)
      (police-runtime-effect-authority! ctx event effects)
      (cond-> ctx
        (contains? effects :db) (interceptor/assoc-effect :db (:db effects))
        ;; runtime-db partition effect (EP-0001 rf2-bvwoi4). Carried through the
        ;; context here; the partitioned/atomic commit path lands in rf2-adwcv6
        ;; (bead 5). Reserved-by-convention — the authority diagnostic above has
        ;; already fired for a non-framework writer; the effect is applied either way.
        (contains? effects :rf.db/runtime) (interceptor/assoc-effect :rf.db/runtime (:rf.db/runtime effects))
        (and (contains? effects :fx)
             (fx-value-ok? effects event)) (interceptor/assoc-effect :fx (:fx effects))))))

(def event-handler-interceptor-id
  "The single `:id` the framework stamps on the handler-wrapping interceptor
  (the terminal `:before` that invokes the user event handler). One id since
  EP-0018 collapsed the db/fx/ctx triple to one form — a captured
  `:rf/interceptor-error` whose `:id` is this is the EVENT HANDLER itself
  throwing (vs. a user interceptor). A stable framework-owned contract."
  :rf/event-handler)

(defn- wrap-event-handler
  "Wrap `handler-fn` into the ONE event-handler interceptor whose :before
  runs the handler (EP-0018 — the historical per-kind db/fx/ctx wrappers
  collapsed to this single shape).

  The :before:
    (a) honours :rf/skip-handler? (Spec 010 steps 1-2 recovery — schema
        validation has already emitted its failure trace);
    (b) pulls the event vector from the coeffects;
    (c) invokes the user handler with the coeffects map + the event vector
        (`(fn [coeffects event] effect-map)`);
    (d) commits the returned closed effects map via `commit-fx-effects`.

  The interceptor stamps `:rf/event-handler` and carries `:rf/default? true`
  (rf2-twt7m Change 3) so tools (Xray, Story, the Event lens) can filter the
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
        (let [event   (interceptor/get-coeffect ctx :event)
              new-ctx (commit-fx-effects ctx event
                                         (handler-fn (interceptor/get-coeffect ctx) event))]
          ;; EP-0001 (rf2-tfepxu, decision #8): a `:db` effect carrying the
          ;; retired `:rf/runtime` app-db root is a HARD ERROR — reject it at
          ;; the single post-commit chokepoint. No-op (cheap) when the legacy
          ;; key is absent.
          (reject-legacy-runtime-root! (interceptor/get-effect new-ctx :db) event)
          new-ctx)))))

(defn event-handler-meta
  "Build the registrar-shaped handler-meta map for an event handler from a
  raw `handler-fn`, WITHOUT registering it. Returns the same shape
  `register-event!` installs: `meta` merged with `:handler-fn` and the
  `:interceptors` vector carrying the `:rf/event-handler` wrapping
  interceptor at its tail.

  Per rf2-a2sn1 — the single source of truth for the handler-meta shape,
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

  EP-0001 (rf2-3939ig) — the GENERAL minting mechanism. A registration
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

  Reserved BY CONVENTION (Mike ruling #4), not a capability gate: the
  effect is applied either way; the flag only governs the dev diagnostic."
  [meta]
  (boolean (or (:rf/framework-authority? meta)
               (:rf/machine? meta))))

;; ---- :rf.cofx/requires parsing (EP-0017 §4 / Spec 001 §The declaration key) -
;;
;; `:rf.cofx/requires` is a standard registration-metadata key (Spec 001 middle
;; slot) on `reg-event` declaring the handler's consumed coeffect ids. The
;; runtime delivers EXACTLY the declared facts, flat, into the handler's
;; coeffects map (declared-only delivery — Spec 002 §Satisfaction). Since
;; EP-0018 collapsed to the one coeffects-in form, `:rf.cofx/requires` is
;; uniformly available to every event (the EP-0017 hole — a db handler that
;; structurally could not take delivery — is closed). The parsing / shape
;; validation / duplicate-id check lives in `re-frame.cofx/parse-requires`; the
;; parsed entries are stored on the registration under
;; `:rf.cofx/requires-parsed` for the satisfaction step
;; (`assemble-initial-ctx`) and the raw value is retained under
;; `:rf.cofx/requires` so `handler-meta` surfaces it exactly as authored
;; (Spec 009 §9).

;; ---- registration ---------------------------------------------------------

;; ---- bare-interceptor detection (rf2-3ut12) -------------------------------
;;
;; The `reg-event` interceptor chain lives under metadata `:interceptors`
;; and MUST be a VECTOR (per Spec 001 §Allowed forms of the middle slot).
;; A BARE interceptor —
;; `(reg-event id mw/some-interceptor handler)` rather than
;; `(reg-event id {:interceptors [mw/some-interceptor]} handler)` — used to be SILENTLY
;; dropped: an interceptor built by `->interceptor` / `->interceptor*` is a
;; *map* (`{:id … :before … :after …}`), so `normalise-args`' two-arg branch
;; read it as the metadata-map, the chain never reached the registrar, and
;; the interceptor never ran — no error, no warning (field-confirmed via the
;; rf8 migration). This is the same silent-drop class as
;; `:rf.warning/runtime-state-dropped` (p806o), the fail-closed work (gro94),
;; and the M-8 silent no-op (cxo1h).
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

  Loud-fails on a BARE interceptor (rf2-3ut12): an interceptor map handed
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
  (Spec 009 §`:rf.handler/source`, Xray Spec 021 §11.2 B.7 stretch,
  rf2-xgfuy). User-supplied `:rf.handler/source` overrides the auto-
  captured value (mirrors `source-coords/merge-coords` semantics — so
  tooling that synthesises registrations from another source can stamp
  the original form-source). Returns `m` unchanged when no source is
  pending (programmatic / REPL registrations that bypass the macro
  path).

  Per rf2-xgfuy §Production elision: the whole body is gated on
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

  RETAIN-vs-STRIP decision (one line, per the bead): the raw `:interceptors`
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
      ;; next dispatch (EP-0022, rf2-0adhqs.2).
      (validate-refs-registered! meta-interceptors)
      [(dissoc meta :interceptors) meta-interceptors])))

(defn- register-event!
  "Registration body for `reg-event` — the one public event form (EP-0018).

  Steps:
    1. parse the variadic tail into [metadata handler];
    2. resolve the interceptor chain from metadata `:interceptors` via
       `resolve-interceptors` (rf2-bpmszk): validates the map value and strips
       the raw key from the stored meta;
    3. wrap the user handler into the `:rf/event-handler` interceptor via
       `wrap-event-handler`;
    4. register under `:event` with `:handler-fn` retained for tooling
       introspection and `:rf.handler/source` carrying the macro-captured
       form-source string when present (Spec 009, rf2-xgfuy);
    5. return the event id. Path-D schema-first privacy has no
       user-facing redaction interceptor to police at registration time.

  Returns the event id."
  [reg-fn-name id args]
  (let [[raw-meta handler-fn] (normalise-args reg-fn-name args)
        [meta interceptors] (resolve-interceptors reg-fn-name id raw-meta)
        wrapped (wrap-event-handler handler-fn)]
    ;; Per Spec 010 §Production builds + rf2-iftj4: reject the
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
    ;; EP-0018: `:rf.cofx/requires` is uniformly available (the former
    ;; db-handler exception is gone — the one form is coeffects-in).
    ;; Per Spec 015 §1. Event handlers: VALIDATE any declared `:sensitive` /
    ;; `:large` marks fail-loud BEFORE the registrar write (rf2-ehexnw); the
    ;; marks themselves are DERIVED from the registrar meta at `marks-for` read
    ;; time, no imperative stash. Late-bound — the hook is unbound when the
    ;; marks artefact is absent (which it never is in the canonical build, but
    ;; the indirection keeps `events` decoupled from `marks`). Runs for MACHINE
    ;; registrations too: a machine's author marks ride its `:event` reg meta
    ;; (via `reg-machine` opts), and its `:data-schema` marks flow through the
    ;; separate schema-marks table — `marks-for :event <id>` unions both at read
    ;; time (rf2-qpibk0), order-independent with no stash to clobber.
    (when-let [validate! (late-bind/get-fn :marks/validate-marks!)]
      (validate! :event meta))
    (let [requires-parsed (cofx/parse-requires id (:rf.cofx/requires meta))]
      (registrar/register! :event id
        (cond-> (assoc (-> meta source-coords/merge-coords merge-form-source)
                       :handler-fn   handler-fn
                       :interceptors (-> [] (into interceptors) (conj wrapped)))
          (seq requires-parsed)
          (assoc :rf.cofx/requires-parsed requires-parsed))))
    id))

(defn normalize-relowered-meta
  "Normalize a PROJECTED event's registration metadata back into the
  AUTHORED metadata shape `reg-event` accepts, so a projected app-value event
  descriptor can be re-lowered through the one `reg-event` form (EP-0018,
  rf2-untip9).

  An app value projected off the live registrar (`av/app-value`) carries each
  event's EFFECTIVE registrar metadata under `:metadata` — including the slots
  `register-event!` GENERATES rather than the author supplies:

    * `:interceptors` — the EFFECTIVE chain `reg-event` assembled: the author's
      interceptor REFERENCES at the head, the inline `:rf/event-handler`
      framework wrapper (`:rf/default? true`) appended at the tail. Re-feeding
      that effective chain to `reg-event` makes `validate-meta-interceptors!`
      read the inline wrapper as an AUTHORED inline interceptor and reject it
      `:rf.error/inline-interceptor-removed` (chains are reference-only since
      EP-0022). The author's chain is the references with the framework wrapper
      removed — the documented recovery `(remove :rf/default? interceptors)`
      (`resolve-interceptors`).
    * `:rf.cofx/requires-parsed` — the normalised cofx-requirement vector
      `register-event!` derives from the authored `:rf.cofx/requires`. `reg-event`
      regenerates it, so a stale projected copy is dropped (the author's
      `:rf.cofx/requires` survives and is re-parsed).

  Stripping the regenerated slots (and recovering the author's reference chain)
  yields a metadata map structurally identical to what the author originally
  passed `reg-event`, so re-lowering re-validates the references, re-parses the
  cofx requirements, and re-appends a FRESH framework wrapper — a faithful
  round-trip. A CONSTRUCTED descriptor (from `module`) never carries these
  generated slots, so this is a no-op on it: an authored `:interceptors` chain
  is references-only (no `:rf/default?` entry to strip) and carries no
  `:rf.cofx/requires-parsed`. INTERNAL."
  [meta]
  (if-not (map? meta)
    meta
    (cond-> (dissoc meta :rf.cofx/requires-parsed)
      ;; Recover the author's reference chain from the effective chain by
      ;; dropping the framework-default wrapper(s). Done ONLY when the chain
      ;; actually carries a `:rf/default?` entry, so an authored
      ;; references-only chain is left byte-identical (and an empty residual
      ;; chain is removed rather than passed as `[]`).
      (some :rf/default? (:interceptors meta))
      (as-> m (let [user-refs (filterv (complement :rf/default?) (:interceptors meta))]
                (if (seq user-refs)
                  (assoc m :interceptors user-refs)
                  (dissoc m :interceptors)))))))

(defn reg-event
  "Register a `(fn [coeffects event-vec] effect-map)` event handler under
  `id` — the ONE public event-registration form (EP-0018, ruled
  2026-06-14). Coeffects in, a closed effects map out.

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
  to every event (the EP-0017 hole closed by EP-0018). `inject-cofx` is
  removed (EP-0017). The historical positional interceptor vector middle
  slot is removed; put the chain under metadata `:interceptors`.

  Returns `id`. Returning `nil` from the handler is a documented no-op.

  Full-context work that a `(fn [context] context)` handler once expressed
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
  conversion). Mirrors the `inject-cofx` removed-stub shape: surface on the
  always-on error-emit listener (production-survivable) AND the dev trace bus,
  then throw the ex-info carrying the same `:rf.error/id`."
  [error-kw reg-fn-name where id replacement fix]
  (let [reason (str "`" reg-fn-name "` is REMOVED in EP-0018 (no alias). "
                    "Use `" replacement "` instead — " fix)]
    (when-let [dispatch-on-error! (late-bind/get-fn-cached :error-emit/dispatch-on-error)]
      (dispatch-on-error! error-kw nil id nil nil 0 (interop/now-ms)))
    (trace/emit-error! error-kw
                       (cond-> {:recovery :no-recovery :reason reason}
                         (some? id) (assoc :id id)))
    (error/throw-error!
      error-kw
      where
      reason
      {:recovery :no-recovery
       :extra    (cond-> {} (some? id) (assoc :id id))})))

;; ---- data-driven removed event-registration names (rf2-ne2uk8) ------------
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
  single audit surface for the removed event registrars (rf2-ne2uk8). A row is
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

;; ---- EP-0023 inline-registration lowering (rf2-ffc6s0) --------------------
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

  `meta` is the inline entry's metadata map. The only AUTHORED meta slots this
  lowering acts on are the cofx-requirements: `:rf.cofx/requires` is parsed via
  `cofx/parse-requires` into `:rf.cofx/requires-parsed`, exactly as
  `register-event!` does (EP-0017 §4/§5). This is load-bearing: the
  satisfaction step (`router/assemble-initial-ctx`) reads the TOP-LEVEL
  `:rf.cofx/requires-parsed` slot to deliver the declared coeffects, so an
  inline image-loaded event MUST carry it or its declared facts are silently
  dropped (rf2-khi7xr — a fail-open drop, violating EP-0017 §5 uniform
  declared-only delivery and the EP-0023 §Image Fragments \"both paths lower to
  the same runtime descriptor shape\" contract). A malformed / duplicate
  declaration fails loud at lowering (`:rf.error/cofx-request-invalid` /
  `:rf.error/cofx-name-collision`), mirroring registration.

  The other event-meta slot affecting the runnable chain — author-declared
  `:interceptors` references — remains an advanced inline shape out of this
  slice's scope (the descriptor already carries the inline `:metadata` for
  introspection). `impl` is the raw handler fn.

  Returns ONLY the runnable slots so image-assembly merges them onto the
  descriptor, preserving `:impl` + provenance for replacement-winner
  coordinates and dedupe."
  [meta impl]
  (let [requires-parsed (cofx/parse-requires :rf/image-inline-event
                                             (:rf.cofx/requires meta))]
    (cond-> (event-handler-meta impl)
      (seq requires-parsed) (assoc :rf.cofx/requires-parsed requires-parsed))))

(late-bind/set-fn! :image/lower-inline-event lower-inline-event)
