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
            [re-frame.source-coords :as source-coords]
            [re-frame.trace :as trace]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- metadata-map mis-use detection (rf2-bbea) ----------------------------
;;
;; `reg-event-*`'s middle slot may be a metadata-map (open shape — `:doc`,
;; `:schema`, `:tags`, ...) OR an interceptor vector. The interceptor chain
;; lives in the *positional* slot only, NOT inside the metadata-map. Putting
;; `:interceptors` inside the metadata-map silently drops the chain — which
;; surfaced via rf2-w3vn (Circle Drawer): `{:interceptors [undoable]}` placed
;; in the metadata-map disabled undo silently until the typo was spotted.
;;
;; Path 1 from rf2-bbea: warn at registration when `:interceptors` appears
;; inside the metadata-map. The runtime emits a structured trace event
;; (`:rf.warning/interceptors-in-metadata-map`, per Conventions §Reserved
;; namespaces — `:rf.warning/*`) that hot-reload tools and 10x can surface.

(defn- warn-interceptors-in-meta!
  "Emit `:rf.warning/interceptors-in-metadata-map` when `meta` carries the
  `:interceptors` key. The metadata-map is for reflection (`:doc`, `:schema`,
  `:tags`, `:platforms`, ...) and the interceptor chain belongs in the
  positional slot. Returns nil."
  [reg-fn-name id meta]
  (when (and (map? meta) (contains? meta :interceptors))
    (trace/emit! :warning :rf.warning/interceptors-in-metadata-map
                 {:reg-fn      reg-fn-name
                  :id          id
                  :offending-keys [:interceptors]
                  :reason
                  (str reg-fn-name " for `" id "` received `:interceptors` "
                       "inside the metadata-map; `:interceptors` is a "
                       "positional slot, not metadata. The interceptors are "
                       "being silently ignored. Move them out of the metadata "
                       "map and into the third positional argument: "
                       "`(" reg-fn-name " " id " [icpt1 icpt2] (fn ...))` "
                       "or `(" reg-fn-name " " id " {:doc \"...\"} "
                       "[icpt1 icpt2] (fn ...))`.")
                  :recovery    :ignored}))
  nil)

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
  "Truthy when `interceptors` (the positional vector) contains the
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
  positional `interceptors` vector includes `:rf.schema/at-boundary`
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
                          "interceptor from the positional vector.")
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

  Framework-minted handlers (today: machine handlers — `assemble-initial-ctx`
  stamps `:rf/framework-authority? true` from `:rf/machine?`) write
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

(defn reject-legacy-runtime-root!
  "Throw `:rf.error/legacy-runtime-root` (ex-info) when `app-db` carries the
  retired `:rf/runtime` root key at its top level. Per Conventions §The
  legacy `:rf/runtime` root + decision #8: a HARD ERROR in the final form —
  framework runtime state lives in the runtime-db partition now, never under
  an app-db `:rf/runtime` root. A no-op (returns `app-db`) for any value that
  does not carry the legacy key, so it is cheap on the hot path.

  Always-on (NOT dev-gated): a legacy-shaped write is a structural contract
  violation that must surface in every build, not a dev-only advisory."
  [app-db event]
  (when (and (map? app-db) (contains? app-db legacy-runtime-root-key))
    (let [event-id (when (vector? event) (first event))]
      (throw (ex-info
               ":rf.error/legacy-runtime-root"
               {:rf.error/id :rf.error/legacy-runtime-root
                :where       'rf/reg-event-db
                :event-id    event-id
                :event       event
                :recovery    :no-recovery
                :reason
                (str "Event `" event-id "` returned a `:db` value carrying the "
                     "retired `:rf/runtime` app-db root. Framework runtime state "
                     "now lives in the runtime-db partition (the reserved "
                     "`:rf.db/runtime` effect, addressed by `:rf.runtime/*` "
                     "children) — NOT under an app-db `:rf/runtime` root, which "
                     "is retired. Move framework/runtime writes to the "
                     "`:rf.db/runtime` effect; keep application data under `:db`.")
                :offending-key legacy-runtime-root-key}))))
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
  positional interceptor prefix (empty for machines)."
  ([handler-fn] (event-handler-meta {} [] handler-fn))
  ([meta interceptors handler-fn]
   (assoc meta
          :event/kind   :fx
          :handler-fn   handler-fn
          :interceptors (-> [] (into interceptors) (conj (wrap-event-handler :fx handler-fn))))))

;; ---- registration ---------------------------------------------------------

;; ---- bare-interceptor detection (rf2-3ut12) -------------------------------
;;
;; The `reg-event-*` interceptor chain is POSITIONAL and MUST be a VECTOR
;; (per Spec 001 §Allowed forms of the middle slot). A BARE interceptor —
;; `(reg-event-db id mw/some-interceptor handler)` rather than
;; `(reg-event-db id [mw/some-interceptor] handler)` — used to be SILENTLY
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
;; `bare → [bare]`: that would be magic; the caller wraps it.

(defn- bare-interceptor-map?
  "True when `x` is a map carrying interceptor fn keys (`:before` / `:after`)
  — i.e. a bare interceptor passed where the metadata-map / interceptors
  slot was expected. A legitimate registration metadata-map (`:doc`,
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
  interceptor handed to `reg-event-*` where a `[vector]` was required.
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
                 " slot; the interceptor chain is positional and MUST be a "
                 "vector. A bare interceptor map would be silently dropped "
                 "(never run). Wrap it: `(" reg-fn-name " id [the-interceptor] "
                 "handler)` — not `(" reg-fn-name " id the-interceptor handler)`.")
            :got         offending
            :expected    "interceptor-vector (e.g. [(path :a)])"
            :args        args})))

(defn- normalise-args
  "Accept the three documented shapes for the variadic tail of reg-event-*:
    (handler)                          — bare handler
    (metadata-or-interceptors handler) — middle slot is one or the other
    (metadata interceptors handler)    — explicit pair
  Per Spec 001 §Allowed forms of the middle slot. Returns
  `[metadata interceptors handler]`.

  Loud-fails on a BARE interceptor (rf2-3ut12): an interceptor map handed
  where a metadata-map or `[vector]` was expected is rejected with
  `:rf.error/reg-event-bare-interceptor` rather than silently dropped — the
  two-arg branch catches `(reg-event-* id bare-icpt handler)` (a map with
  `:before` / `:after`), and the three-arg branch catches a non-vector
  positional interceptors slot."
  [reg-fn-name args]
  (case (count args)
    1 [{} [] (first args)]
    2 (let [[middle handler] args]
        (cond
          (bare-interceptor-map? middle)
          (throw-bare-interceptor! reg-fn-name :middle middle args)
          (map? middle)    [middle [] handler]
          (vector? middle) [{} middle handler]
          :else            (throw (ex-info
                                    ":rf.error/reg-event-bad-middle-slot"
                                    {:rf.error/id :rf.error/reg-event-bad-middle-slot
                                     :where       'rf/reg-event-db
                                     :recovery    :fix-registration
                                     :reason      "the middle slot of a reg-event-* call must be a metadata-map (e.g. {:doc \"...\"}) or an interceptor-vector (e.g. [(path :a)])"
                                     :args        args
                                     :got         middle
                                     :expected    "metadata-map (e.g. {:doc \"...\"}) OR interceptor-vector (e.g. [(path :a)])"}))))
    3 (let [[meta interceptors handler] args]
        (when (and (some? interceptors) (not (vector? interceptors)))
          ;; The positional interceptors slot must be a vector. A bare
          ;; interceptor map (or any other non-vector) here would be
          ;; `(into [] …)`'d into MapEntries / corruption — reject it loud.
          (throw-bare-interceptor! reg-fn-name :interceptors interceptors args))
        [meta (or interceptors []) handler])
    (throw (ex-info
             ":rf.error/reg-event-bad-arity"
             {:rf.error/id :rf.error/reg-event-bad-arity
              :where       'rf/reg-event-db
              :recovery    :fix-registration
              :reason      "reg-event-* expects (id handler), (id metadata handler), or (id metadata interceptors handler)"
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

(defn- register-event!
  "Common registration body for the three reg-event-* forms.

  Steps (uniform across :db / :fx / :ctx kinds):
    1. parse the variadic middle slot into [metadata interceptors handler];
    2. warn-if-misplaced — `:interceptors` inside the metadata-map silently
       drops the chain (rf2-bbea, rf2-w3vn);
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
  (let [[meta interceptors handler-fn] (normalise-args reg-fn-name args)
        wrapped (wrap-event-handler kind handler-fn)]
    (warn-interceptors-in-meta! reg-fn-name id meta)
    ;; Per Spec 010 §Production builds + rf2-iftj4: reject the
    ;; registration when `:rf.schema/at-boundary` is attached but no
    ;; `:schema` is declared on the metadata-map. The boundary
    ;; interceptor is structurally meaningless without a schema, so
    ;; surface the misconfiguration at the moment of registration
    ;; (always — both dev and prod) rather than waiting for the first
    ;; dispatch in production.
    (reject-at-boundary-without-schema! reg-fn-name id meta interceptors)
    (registrar/register! :event id
      (assoc (-> meta source-coords/merge-coords merge-form-source)
             :event/kind   kind
             :handler-fn   handler-fn
             :interceptors (-> [] (into interceptors) (conj wrapped))))
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

  Shapes (the middle slot is optional and may be metadata OR
  interceptor-vector, NOT both — per Conventions §`:interceptors` is
  positional, not metadata):

      (reg-event-db :id                            (fn [db ev] new-db))
      (reg-event-db :id {:doc \"...\" :schema ...} (fn [db ev] new-db))
      (reg-event-db :id [(path :counter)]          (fn [slice ev] new-slice))
      (reg-event-db :id {:doc \"...\"} [icpt]        (fn [db ev] new-db))

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
  `:event`, plus any cofx injected via `inject-cofx`) and the event
  vector, and returns an effect-map. The runtime walks the effects in
  order:

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

  Shapes (the middle slot is optional and may be metadata OR
  interceptor-vector, NOT both):

      (reg-event-fx :id                       (fn [cofx ev] {...}))
      (reg-event-fx :id {:doc \"...\"}          (fn [cofx ev] {...}))
      (reg-event-fx :id [(inject-cofx :now)]  (fn [cofx ev] {...}))
      (reg-event-fx :id {:doc \"...\"} [icpt]   (fn [cofx ev] {...}))

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
  `inject-cofx` (consume a registered cofx)."
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

  Shapes (the middle slot is optional and may be metadata OR
  interceptor-vector, NOT both):

      (reg-event-ctx :id                  (fn [ctx] new-ctx))
      (reg-event-ctx :id {:doc \"...\"}     (fn [ctx] new-ctx))
      (reg-event-ctx :id [icpt1 icpt2]    (fn [ctx] new-ctx))

  See also: `reg-event-db`, `reg-event-fx`, `->interceptor`,
  `assoc-coeffect`, `assoc-effect`."
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
