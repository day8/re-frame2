(ns re-frame.events
  "Event-handler registration. Per Spec 002 §Event handlers and Spec 001
  §Registry model.

  Three kinds of event handlers:
    reg-event-db   — pure (db, event) → new-db
    reg-event-fx   — pure (cofx, event) → effects-map ({:db ... :fx [...]})
    reg-event-ctx  — full-context handler returns context (advanced)

  All three register under registry kind :event with an :event/kind sub-tag
  recording which form was used. The runtime treats all three uniformly
  during drain — the difference is only in the wrapping shape.

  Per Spec 015 §1. Event handlers — the registration meta-map accepts
  optional `:sensitive [paths]` and `:large [paths]` keys that index
  into the dispatched event vector's arg-map (the second element).
  The marks are stashed in the per-(kind, id) marks table via
  `re-frame.marks/register-marks!` (called through the late-bind
  hook to keep events decoupled from the optional marks artefact)."
  (:require [re-frame.interop :as interop]
            [re-frame.registrar :as registrar]
            [re-frame.interceptor :as interceptor]
            [re-frame.late-bind :as late-bind]
            [re-frame.cofx :as cofx]
            [re-frame.source-coords :as source-coords]
            [re-frame.trace :as trace]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- metadata-map `:interceptors` — the superset middle slot (rf2-bpmszk) -
;;
;; The `reg-event-*` metadata-map carries a RESERVED `:interceptors` key,
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
  "True when `x` is shaped like an interceptor — a map carrying any of the
  interceptor keys (`:id` / `:before` / `:after`). The bar matches `:before` /
  `:after` detection used elsewhere (`bare-interceptor-map?`) while also
  admitting an `:id`-only map (a named no-op interceptor). A non-map entry
  (keyword, string, number, …) is the unambiguous malformed tell."
  [x]
  (and (map? x)
       (or (contains? x :id)
           (contains? x :before)
           (contains? x :after))))

(defn- throw-bad-interceptors-value!
  "Raise `:rf.error/reg-event-bad-interceptors` (ex-info) for a malformed
  metadata-map `:interceptors` value — a non-vector, or a vector carrying a
  non-interceptor entry. Loud-fail at registration per Conventions §No silent
  swallow: a malformed chain cannot be honoured and must not be silently
  dropped or coerced."
  [reg-fn-name id value reason]
  (throw (ex-info
           ":rf.error/reg-event-bad-interceptors"
           {:rf.error/id :rf.error/reg-event-bad-interceptors
            :where       'rf/reg-event-db
            :reg-fn      reg-fn-name
            :id          id
            :recovery    :fix-registration
            :reason      reason
            :got         value
            :expected    "a vector of interceptor maps (e.g. [(path :a) some-interceptor])"})))

(defn- validate-meta-interceptors!
  "Validate the metadata-map `:interceptors` value at registration. The value
  MUST be a vector, and every entry MUST be an interceptor map (per
  `interceptor-entry?`). Raises `:rf.error/reg-event-bad-interceptors` on a
  malformed value. A no-op (returns `value`) for a well-shaped vector."
  [reg-fn-name id value]
  (cond
    (not (vector? value))
    (throw-bad-interceptors-value!
      reg-fn-name id value
      (str reg-fn-name " for `" id "` carried a non-vector `:interceptors` value in "
           "its metadata-map; `:interceptors` must be a vector of interceptor "
           "maps (e.g. `{:interceptors [(path :a) some-interceptor]}`)."))

    (not (every? interceptor-entry? value))
    (throw-bad-interceptors-value!
      reg-fn-name id value
      (str reg-fn-name " for `" id "` carried a `:interceptors` vector with a "
           "non-interceptor entry; every entry must be an interceptor map "
           "(carrying `:id` / `:before` / `:after`). Build interceptors with "
           "`rf/->interceptor` or the framework helpers (e.g. `(path :a)`)."))

    :else value))

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

(defn- attaches-validate-at-boundary-interceptor?
  "Truthy when the effective user interceptor chain contains the
  `:rf.schema/at-boundary` interceptor. Detects by `:id` so the check
  stays cycle-free against `re-frame.spec`."
  [interceptors]
  (and (sequential? interceptors)
       (some (fn [icpt]
               (and (map? icpt)
                    (= :rf.schema/at-boundary (:id icpt))))
             interceptors)))

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
    (throw (ex-info ":rf.error/at-boundary-missing-schema"
                    {:rf.error/id :rf.error/at-boundary-missing-schema
                     :where    'rf/reg-event-db
                     :reg-fn   reg-fn-name
                     :id       id
                     :reason
                     (str reg-fn-name " for `" id "` attaches the "
                          "`:rf.schema/at-boundary` interceptor but the "
                          "registration carries no `:schema` metadata. "
                          "The boundary interceptor cannot validate "
                          "without a schema and is structurally "
                          "meaningless without one. Either attach a "
                          "`:schema` to the metadata-map "
                          "(recommended) or remove the boundary "
                          "interceptor from metadata `:interceptors`.")
                     :recovery :no-recovery})))
  nil)

;; ---- effect-map shape policing (Spec migration M-8) -----------------------
;;
;; Per migration/from-re-frame-v1/README.md §M-8 and Spec-Schemas.md §:rf/effect-map,
;; the effect-map a reg-event-fx handler returns is a CLOSED shape: only :db,
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
  "The closed set of top-level effect-map keys a `reg-event-fx` handler may
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
;; value for a `reg-event-fx` HANDLER RETURN — at the moment of the fx-kind
;; `:commit`, i.e. inside the handler-wrapping interceptor's `:before`. That
;; is BEFORE the `:after` interceptor pass runs (`execute-chain` runs every
;; `:before` then every `:after` — see `re-frame.interceptor/execute-chain`).
;;
;; So the per-handler-return checks DO NOT cover effects that arrive at the
;; commit boundary by any OTHER route:
;;   - a `reg-event-ctx` handler returns a context directly (the `:ctx`
;;     `:commit` is `(or new-ctx ctx)` — no effect-map validation);
;;   - a framework / user `:after` interceptor mutates `[:effects …]` after
;;     the handler-return checks have already run (docs/guide §09 documents
;;     `:after` interceptors adding/modifying `:effects`/`:fx`).
;;
;; The router consumes the FINAL `(:effects final-ctx)` after the whole chain
;; (every `:before` AND every `:after`) has run. `police-final-effects!` is
;; the single authoritative shape gate applied THERE, so the closed
;; effect-map + whole-`:fx` contract holds uniformly regardless of how an
;; effect reached the final context (reg-event-fx, reg-event-ctx, framework
;; interceptors, or user `:after`). It returns the CLEANED effects map —
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
  `commit-fx-effects` per-handler-return checks — a `reg-event-ctx` return
  or an `:after`-interceptor mutation (rf2-u1kdvg). `nil` / a non-map
  effects value (no effects produced) passes through untouched.

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

;; ---- handler-as-interceptor wrappers --------------------------------------
;;
;; The three reg-event-* forms share a single :before shape:
;;   1. honour :rf/skip-handler? (Spec 010 §Validation order, rf2-7leq/jwm4),
;;   2. invoke the user handler with the kind-appropriate inputs,
;;   3. project the return into the context.
;;
;; The differences are purely data: which inputs to read, which interceptor
;; :id to stamp, and how the return commits back. The shared shape lives in
;; `wrap-event-handler` below; per-kind specs live in `kind-spec` as a small
;; dispatch table. This collapses the historical db/fx/ctx triple into one
;; well-named primitive — adding a new event kind becomes a one-row edit.

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
     :where         'rf/reg-event-db
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
    (throw (ex-info ":rf.error/legacy-runtime-root"
                    (legacy-runtime-root-ex-data event))))
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
                            :reason        "reg-event-fx handler returned a non-map; expected {:db ... :fx [...]}."
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

(def ^:private kind-spec
  "Per-kind hooks for `wrap-event-handler`. Each entry carries:
    :interceptor-id  the stamped :rf/* id (observable in traces)
    :invoke          (fn [handler-fn ctx event]) → handler return value
    :commit          (fn [ctx event return]) → new ctx
  The shared `:before` body composes invoke → commit around the
  :rf/skip-handler? short-circuit (Spec 010 steps 1-2 recovery).

  Notes per kind:
    :db   — handler is (fn [db event]) → new-db; commits via assoc-effect :db.
    :fx   — handler is (fn [cofx event]) → effect-map; commits via
            `commit-fx-effects` which enforces the closed :db/:fx shape
            (M-8) and polices bad returns (rf2-k3bj).
    :ctx  — handler is (fn [context]) → context; commits the return value
            directly, defaulting to the inbound ctx on nil return."
  {:db  {:interceptor-id :rf/db-handler
         :invoke         (fn [handler-fn ctx event]
                           (handler-fn (interceptor/get-coeffect ctx :db) event))
         :commit         (fn [ctx _event new-db]
                           (interceptor/assoc-effect ctx :db new-db))}
   :fx  {:interceptor-id :rf/fx-handler
         :invoke         (fn [handler-fn ctx event]
                           (handler-fn (interceptor/get-coeffect ctx) event))
         :commit         commit-fx-effects}
   :ctx {:interceptor-id :rf/ctx-handler
         :invoke         (fn [handler-fn ctx _event]
                           (handler-fn ctx))
         :commit         (fn [ctx _event new-ctx]
                           (or new-ctx ctx))}})

(defn- wrap-event-handler
  "Wrap `handler-fn` into an interceptor whose :before runs the handler.

  The body is uniform across event kinds:
    (a) honour :rf/skip-handler? (Spec 010 steps 1-2 recovery — schema
        validation has already emitted its failure trace);
    (b) pull the event vector from the coeffects (used by every kind's
        invoke + commit);
    (c) invoke the user handler with kind-appropriate inputs;
    (d) commit the return into the context.

  See `kind-spec` for the per-kind :invoke / :commit pair.

  Per rf2-twt7m Change 3: the produced interceptor carries
  `:rf/default? true` so tools (Xray, Story, the Event lens
  redesign rf2-zh2qc) can filter out the framework's auto-wrappers
  without a hardcoded allowlist of `:rf/db-handler` /
  `:rf/fx-handler` / `:rf/ctx-handler` interceptor ids. Self-
  describing: the meta lives on the interceptor map itself."
  [kind handler-fn]
  (let [{:keys [interceptor-id invoke commit]} (get kind-spec kind)]
    (interceptor/->interceptor*
      :id         interceptor-id
      :rf/default? true
      :before
      (fn [ctx]
        (if (:rf/skip-handler? ctx)
          ctx
          (let [event   (interceptor/get-coeffect ctx :event)
                new-ctx (commit ctx event (invoke handler-fn ctx event))]
            ;; EP-0001 (rf2-tfepxu, decision #8): a `:db` effect carrying the
            ;; retired `:rf/runtime` app-db root is a HARD ERROR — reject it
            ;; at the single post-commit chokepoint covering all three event
            ;; kinds. No-op (cheap) when the legacy key is absent.
            (reject-legacy-runtime-root! (interceptor/get-effect new-ctx :db) event)
            new-ctx))))))

(defn event-handler-meta
  "Build the registrar-shaped handler-meta map for an `:fx`-kind event
  handler from a raw `handler-fn`, WITHOUT registering it. Returns the
  same shape `register-event!` installs: `meta` merged with
  `:event/kind :fx`, `:handler-fn`, and the `:interceptors` vector
  carrying the handler-wrapping interceptor at its tail.

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
          :event/kind   :fx
          :handler-fn   handler-fn
          :interceptors (-> [] (into interceptors) (conj (wrap-event-handler :fx handler-fn))))))

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
  stamps the key (the routing façade stamps it on its `reg-event-fx`
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
;; slot) on `reg-event-fx` and `reg-event-ctx` declaring the handler's consumed
;; coeffect ids. The runtime delivers EXACTLY the declared facts, flat, into the
;; handler's coeffects map (declared-only delivery — Spec 002 §Satisfaction).
;;
;; On `reg-event-db` it is a REGISTRATION-TIME ERROR
;; (`:rf.error/cofx-request-invalid`): a db handler receives only the db and
;; cannot take delivery. Needing the world is what graduates a handler to the
;; fx form. The parsing / shape validation / duplicate-id check lives in
;; `re-frame.cofx/parse-requires`; the parsed entries are stored on the
;; registration under `:rf.cofx/requires-parsed` for the satisfaction step
;; (`assemble-initial-ctx`) and the raw value is retained under
;; `:rf.cofx/requires` so `handler-meta` surfaces it exactly as authored
;; (Spec 009 §9).

(defn- reject-db-handler-requires!
  "Raise `:rf.error/cofx-request-invalid` when a `reg-event-db` registration
  carries `:rf.cofx/requires`: a db handler is `(fn [db event] new-db)` and
  cannot take coeffect delivery. Per Spec 001 §The declaration key + EP-0017
  §4. A no-op for any other kind, or when the key is absent."
  [kind id meta]
  (when (and (= kind :db) (contains? meta :rf.cofx/requires))
    (trace/emit-error! :rf.error/cofx-request-invalid
                       {:failing-id id
                        :received   (:rf.cofx/requires meta)
                        :reason     (str "`reg-event-db` for `" id "` carried "
                                         "`:rf.cofx/requires`, but a db handler "
                                         "receives only the db and cannot take "
                                         "coeffect delivery.")
                        :recovery   :no-recovery})
    (throw (ex-info ":rf.error/cofx-request-invalid"
                    {:rf.error/id :rf.error/cofx-request-invalid
                     :failing-id  id
                     :received    (:rf.cofx/requires meta)
                     :where       'rf/reg-event-db
                     :recovery    :no-recovery
                     :reason
                     (str "`reg-event-db` for `" id "` declared "
                          "`:rf.cofx/requires`. A db handler `(fn [db event] "
                          "new-db)` cannot take coeffect delivery — a handler "
                          "that needs world facts graduates to `reg-event-fx`. "
                          "Move the declaration to a `reg-event-fx` "
                          "registration.")}))))

;; ---- registration ---------------------------------------------------------

;; ---- bare-interceptor detection (rf2-3ut12) -------------------------------
;;
;; The `reg-event-*` interceptor chain lives under metadata `:interceptors`
;; and MUST be a VECTOR (per Spec 001 §Allowed forms of the middle slot).
;; A BARE interceptor —
;; `(reg-event-db id mw/some-interceptor handler)` rather than
;; `(reg-event-db id {:interceptors [mw/some-interceptor]} handler)` — used to be SILENTLY
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
  interceptor handed to `reg-event-*` where a metadata map was required.
  Loud-fail at registration per Conventions §No silent swallow — the
  interceptor would otherwise be silently dropped and never run."
  [reg-fn-name slot offending args]
  (throw (ex-info
           ":rf.error/reg-event-bare-interceptor"
           {:rf.error/id :rf.error/reg-event-bare-interceptor
            :where       'rf/reg-event-db
            :reg-fn      reg-fn-name
            :slot        slot
            :recovery    :fix-registration
            :reason
            (str reg-fn-name " received a BARE interceptor in the " (name slot)
                 " slot; the interceptor chain belongs in metadata "
                 "`:interceptors` and MUST be a vector. A bare interceptor map "
                 "would be silently dropped (never run). Wrap it: `("
                 reg-fn-name " id {:interceptors [the-interceptor]} handler)` "
                 "— not `(" reg-fn-name " id the-interceptor handler)`.")
            :got         offending
            :expected    "metadata-map with :interceptors (e.g. {:interceptors [(path :a)]})"
            :args        args})))

(defn- normalise-args
  "Accept the two documented shapes for the variadic tail of reg-event-*:
    (handler)          — bare handler
    (metadata handler) — metadata-map, optionally carrying `:interceptors`
  Per Spec 001 §Allowed forms of the middle slot. Returns
  `[metadata handler]`.

  Loud-fails on a BARE interceptor (rf2-3ut12): an interceptor map handed
  where a metadata-map was expected is rejected with
  `:rf.error/reg-event-bare-interceptor` rather than silently dropped — the
  two-arg branch catches `(reg-event-* id bare-icpt handler)` (a map with
  `:before` / `:after`)."
  [reg-fn-name args]
  (case (count args)
    1 [{} (first args)]
    2 (let [[middle handler] args]
        (cond
          (bare-interceptor-map? middle)
          (throw-bare-interceptor! reg-fn-name :middle middle args)
          (map? middle)    [middle handler]
          :else            (throw (ex-info
                                    ":rf.error/reg-event-bad-middle-slot"
                                    {:rf.error/id :rf.error/reg-event-bad-middle-slot
                                     :where       'rf/reg-event-db
                                     :recovery    :fix-registration
                                     :reason      "the middle slot of a reg-event-* call must be a metadata-map (e.g. {:doc \"...\" :interceptors [(path :a)]}); the positional interceptor vector is retired"
                                     :args        args
                                     :got         middle
                                     :expected    "metadata-map (e.g. {:doc \"...\" :interceptors [(path :a)]})"}))))
    (throw (ex-info
             ":rf.error/reg-event-bad-arity"
             {:rf.error/id :rf.error/reg-event-bad-arity
              :where       'rf/reg-event-db
              :recovery    :fix-registration
              :reason      "reg-event-* expects (id handler) or (id metadata handler); put interceptor chains in metadata :interceptors"
              :args        args
              :count       (count args)}))))

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
      [(dissoc meta :interceptors) meta-interceptors])))

(defn- register-event!
  "Common registration body for the three reg-event-* forms.

  Steps (uniform across :db / :fx / :ctx kinds):
    1. parse the variadic tail into [metadata handler];
    2. resolve the interceptor chain from metadata `:interceptors` via
       `resolve-interceptors` (rf2-bpmszk): validates the map value and strips
       the raw key from the stored meta;
    3. wrap the user handler into the kind-appropriate interceptor via
       `wrap-event-handler` (see `kind-spec`);
    4. register under `:event` with `:event/kind` recording which form was
       used, `:handler-fn` retained for tooling introspection, and
       `:rf.handler/source` carrying the macro-captured form-source
       string when present (Spec 009, rf2-xgfuy);
    5. return the event id. Path-D schema-first privacy has no
       user-facing redaction interceptor to police at registration time.

  Returns the event id."
  [kind reg-fn-name id args]
  (let [[raw-meta handler-fn] (normalise-args reg-fn-name args)
        [meta interceptors] (resolve-interceptors reg-fn-name id raw-meta)
        wrapped (wrap-event-handler kind handler-fn)]
    ;; Per Spec 010 §Production builds + rf2-iftj4: reject the
    ;; registration when `:rf.schema/at-boundary` is attached but no
    ;; `:schema` is declared on the metadata-map. The boundary
    ;; interceptor is structurally meaningless without a schema, so
    ;; surface the misconfiguration at the moment of registration
    ;; (always — both dev and prod) rather than waiting for the first
    ;; dispatch in production.
    (reject-at-boundary-without-schema! reg-fn-name id meta interceptors)
    ;; EP-0017 §4: parse + validate `:rf.cofx/requires`. On `reg-event-db` the
    ;; key is a registration-time error (a db handler cannot take delivery);
    ;; otherwise parse into the normalised entry vector the satisfaction step
    ;; consumes (`cofx/deliver-declared-cofx`, via `assemble-initial-ctx`),
    ;; raising `:rf.error/cofx-request-invalid` / `:rf.error/cofx-name-collision`
    ;; on a malformed / duplicate declaration.
    (reject-db-handler-requires! kind id meta)
    (let [requires-parsed (cofx/parse-requires id (:rf.cofx/requires meta))]
      (registrar/register! :event id
        (cond-> (assoc (-> meta source-coords/merge-coords merge-form-source)
                       :event/kind   kind
                       :handler-fn   handler-fn
                       :interceptors (-> [] (into interceptors) (conj wrapped)))
          (seq requires-parsed)
          (assoc :rf.cofx/requires-parsed requires-parsed))))
    ;; Per Spec 015 §1. Event handlers: stash any declared `:sensitive`
    ;; / `:large` paths in the marks table so emit-time projection can
    ;; resolve them. Late-bound — the hook is unbound when the marks
    ;; artefact is absent (which it never is in the canonical build,
    ;; but the indirection keeps `events` decoupled from `marks`).
    ;;
    ;; Per rf2-qpibk0: SKIP the clearing call for a MACHINE registration
    ;; (`:rf/machine?` in meta). `reg-machine` re-registers the machine as an
    ;; event handler with bare meta (no mark keys), and `register-marks!`'s
    ;; full-replace semantics would CLEAR any manually-registered machine
    ;; marks (`register-marks! :event machine-id {...}`) — the order-dependent
    ;; clobber the bead closes. A machine declares its `:sensitive?` /
    ;; `:large?` in its `:data-schema` (bridged into the separate schema-marks
    ;; table by `reg-machine`, unioned at read time), never in the reg meta, so
    ;; there is nothing to stash here for a machine — and skipping the call lets
    ;; a manual machine `register-marks!` survive `reg-machine` regardless of
    ;; order (it unions with the schema marks at `marks-for` read time).
    (when-not (:rf/machine? meta)
      (when-let [register! (late-bind/get-fn :marks/register-marks!)]
        (register! :event id meta)))
    id))

(defn reg-event-db
  "Register a `(fn [db event-vec] new-db)` event handler under `id`.

  The handler is **pure** — it receives the current `app-db` value and
  the event vector that triggered the dispatch, and returns the next
  `app-db` value. The runtime atomically swaps the frame's `app-db` to
  the returned value before any `:fx` are walked. For side-effecting
  handlers reach for `reg-event-fx`; for full-context manipulation reach
  for `reg-event-ctx`.

  Shapes (the optional middle slot is the **superset** metadata map —
  it carries reflection metadata (`:doc`, `:schema`, …) **and** a
  reserved `:interceptors` chain in one map):

      (reg-event-db :id                            (fn [db ev] new-db))
      (reg-event-db :id {:doc \"...\" :schema ...} (fn [db ev] new-db))
      (reg-event-db :id {:doc \"...\" :interceptors [(path :counter)]}
                                                    (fn [slice ev] new-slice))

  The historical positional interceptor vector middle slot is removed.
  Put the chain under metadata `:interceptors`.

  Returns `id`.

  Example:

      (rf/reg-event-db :counter/inc
        (fn [db _]
          (update db :n inc)))

      (rf/dispatch [:counter/inc])

  See also: `reg-event-fx` (effect-map handlers), `reg-event-ctx`
  (advanced — context manipulation), `dispatch`, `dispatch-sync`."
  [id & args]
  (register-event! :db "reg-event-db" id args))

(defn reg-event-fx
  "Register a `(fn [cofx event-vec] effect-map)` event handler under `id`.

  The handler is **pure** — it receives a coeffect map (carrying `:db`,
  `:event`, plus exactly the facts it declared via `:rf.cofx/requires`,
  delivered flat) and the event vector, and returns an effect-map. The
  runtime walks the effects in order:

  1. `:db`  — atomic swap to the frame's `app-db` (Spec 002 §`:fx`
     ordering, rule 1).
  2. `:fx`  — vector of `[fx-id args]` pairs, processed in source order
     by the registered fx handlers (see `reg-fx`).

  The effect-map is a **closed shape** — `#{:db :rf.db/runtime :fx}` at
  the top level (per migration M-8 + EP-0001). App handlers return only
  `:db` and `:fx`; `:rf.db/runtime` (the runtime-db partition) is
  reserved by convention for framework / runtime-extension authority and
  is not part of an ordinary handler's vocabulary. Legacy v1 top-level
  keys (`:dispatch`, `:dispatch-later`, `:http`, ...) wrap as `:fx`
  entries — `{:fx [[:dispatch event] ...]}`.

  Shapes (the optional middle slot is the **superset** metadata map —
  it carries reflection metadata **and** a reserved `:interceptors`
  chain in one map):

      (reg-event-fx :id                              (fn [cofx ev] {...}))
      (reg-event-fx :id {:doc \"...\"}                 (fn [cofx ev] {...}))
      (reg-event-fx :id {:rf.cofx/requires [:rf/time-ms]}
                                                     (fn [cofx ev] {...}))
      (reg-event-fx :id {:doc \"...\" :interceptors [(path :a)]}
                                                     (fn [cofx ev] {...}))

  Coeffects are declared via `:rf.cofx/requires` on the metadata map (the
  value arrives FLAT in the cofx map under its id); `inject-cofx` is removed
  (EP-0017). The historical positional interceptor vector middle slot is
  removed; put the chain under metadata `:interceptors`.

  Returns `id`. Returning `nil` from the handler is a documented no-op.

  Example:

      (rf/reg-event-fx :user/save
        (fn [{:keys [db]} [_ user]]
          {:db (assoc db :user/pending? true)
           :fx [[:dispatch [:analytics/track :user-save]]
                [:rf.http/managed {:method :post
                                   :url    \"/api/users\"
                                   :body   user
                                   :on-success [:user/saved]
                                   :on-failure [:user/save-failed]}]]}))

  See also: `reg-event-db` (pure db-only handlers), `reg-event-ctx`
  (advanced — context manipulation), `reg-fx` (register a custom fx),
  `reg-cofx` (register a coeffect supplier; declare consumption via
  `:rf.cofx/requires`)."
  [id & args]
  (register-event! :fx "reg-event-fx" id args))

(defn reg-event-ctx
  "Register a `(fn [context] context)` full-context event handler under
  `id`. **Advanced** — most handlers want `reg-event-db` or
  `reg-event-fx` instead.

  Use this when the handler needs to manipulate the interceptor context
  directly: read or assoc multiple coeffects, build effects keyed off
  pre-existing context state, short-circuit downstream interceptors, or
  perform context-level work that the `{:db ... :fx [...]}` shape can't
  express.

  Returns `id`. Returning `nil` from the handler leaves the inbound
  context unchanged (documented no-op).

  Shapes (the optional middle slot is the **superset** metadata map —
  it carries reflection metadata **and** a reserved `:interceptors`
  chain in one map):

      (reg-event-ctx :id                  (fn [ctx] new-ctx))
      (reg-event-ctx :id {:doc \"...\"}     (fn [ctx] new-ctx))
      (reg-event-ctx :id {:rf.cofx/requires [:rf/time-ms]}
                                          (fn [ctx] new-ctx))
      (reg-event-ctx :id {:doc \"...\" :interceptors [icpt1 icpt2]}
                                          (fn [ctx] new-ctx))

  Coeffects are declared via `:rf.cofx/requires` on the metadata map, the
  same as `reg-event-fx` (EP-0017 §4): each declared value arrives FLAT in
  the context's `:coeffects` map under its id, and the whole recordable
  envelope is reachable as `:rf.cofx` through the context (EP-0017 §5).
  `inject-cofx` is removed.

  The historical positional interceptor vector middle slot is removed;
  put the chain under metadata `:interceptors`.

  See also: `reg-event-db`, `reg-event-fx`, `reg-cofx` (register a
  coeffect supplier), `->interceptor`, `assoc-coeffect`, `assoc-effect`."
  [id & args]
  (register-event! :ctx "reg-event-ctx" id args))

(defn clear-event
  "Unregister an event handler. Zero-arity clears every registered
  event handler in the registrar; one-arity clears the named one.

  Hot-reload tools and test fixtures call this between rebuilds to
  drop stale handlers; production code rarely needs it. Returns nil.

  See also: `reg-event-db`, `reg-event-fx`, `reg-event-ctx`."
  ([] (registrar/clear-kind! :event))
  ([id] (registrar/unregister! :event id)))
