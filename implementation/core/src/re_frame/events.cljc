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
;; `:spec`, `:tags`, ...) OR an interceptor vector. The interceptor chain
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
  `:interceptors` key. The metadata-map is for reflection (`:doc`, `:spec`,
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

;; ---- at-boundary registration-time validation (rf2-iftj4) -----------------
;;
;; The `:rf.schema/at-boundary` interceptor (per Spec 010 §Production builds)
;; is structurally meaningless without a `:schema` to validate against. If a
;; developer attaches it to a handler that carries no `:schema` metadata,
;; pre-rf2-iftj4 the runtime emitted `:rf.warning/boundary-without-spec` on
;; the FIRST dispatch in a production build only — dev builds were silent,
;; and the misconfiguration only surfaced in prod. Per rf2-ycqtv finding #8
;; (Mike-pick option (b)), the registrar now hard-rejects the registration
;; with `:rf.error/at-boundary-missing-schema` so the developer learns
;; immediately, regardless of dev/prod gate.
;;
;; Detection is by interceptor `:id` (`:rf.schema/at-boundary`), not by var
;; equality — keeps `events` decoupled from `re-frame.spec` (which depends
;; transitively on this ns via core re-exports).

(defn- attaches-at-boundary?
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
  (when (and (attaches-at-boundary? interceptors)
             (not (and (map? meta) (contains? meta :schema))))
    (throw (ex-info ":rf.error/at-boundary-missing-schema"
                    {:error    :rf.error/at-boundary-missing-schema
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
;; the effect-map a reg-event-fx handler returns is a CLOSED shape: only :db
;; and :fx live at the top level. Legacy v1 top-level keys (:dispatch,
;; :dispatch-later, :dispatch-n, :http, etc.) must move into :fx as
;; [[fx-id args] ...] entries.
;;
;; The runtime polices this contract: any non-:db / non-:fx top-level key
;; emits a structured :rf.error/effect-map-shape trace per Spec 009 §Error
;; contract, with :recovery :logged-and-skipped. The offending key is dropped
;; (NOT merged silently nor routed through the fx machinery).

(defn- police-effect-map-shape!
  "Emit :rf.error/effect-map-shape for each non-:db / non-:fx top-level key
  in `effects`. Per Spec migration M-8 the effect-map is closed at the top
  level. Returns the list of offending keys (which the caller drops).

  Hot-path short-circuit (rf2-4ymm0 EV4): the well-shaped case is the
  overwhelming majority — handlers return `{}`, `{:db ...}`, `{:fx ...}`,
  or `{:db ... :fx ...}`. Allocating an `offending` vector per dispatch
  for the every-key-walks-the-closed-set check is wasted work. Pre-check
  via `every?` (no allocation), and fall through to the doseq/vec build
  only when at least one key is offending."
  [effects event]
  (if (every? #{:db :fx} (keys effects))
    nil
    (let [event-id (when (vector? event) (first event))
          offending (->> (keys effects)
                         (remove #{:db :fx})
                         (vec))]
      (doseq [k offending]
        (let [v      (get effects k)
              reason (str "Effect-map for `" event-id "` returned top-level key `" k
                          "`; only `:db` and `:fx` are allowed at the top level.")]
          (trace/emit-error! :rf.error/effect-map-shape
                             {:failing-id    event-id
                              :event-id      event-id
                              :event         event
                              :offending-key k
                              :value         v
                              :reason        reason
                              :recovery      :logged-and-skipped})))
      offending)))

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

(defn- commit-fx-effects
  "fx-kind commit: enforce the closed effect-map (M-8) and assoc :db / :fx
  into the context. Bad-return / nil-return policy lives here too — `nil`
  is the documented legal no-op (rf2-k3bj); any non-map return emits
  :rf.error/effect-handler-bad-return with :no-recovery and the dispatch
  becomes a no-op."
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
      (cond-> ctx
        (contains? effects :db) (interceptor/assoc-effect :db (:db effects))
        (contains? effects :fx) (interceptor/assoc-effect :fx (:fx effects))))))

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
  `:rf/default? true` so tools (Causa, Story, the Event lens
  redesign rf2-zh2qc) can filter out the framework's auto-wrappers
  without a hardcoded allowlist of `:rf/db-handler` /
  `:rf/fx-handler` / `:rf/ctx-handler` interceptor ids. Self-
  describing: the meta lives on the interceptor map itself."
  [kind handler-fn]
  (let [{:keys [interceptor-id invoke commit]} (get kind-spec kind)]
    (interceptor/->interceptor
      :id         interceptor-id
      :rf/default? true
      :before
      (fn [ctx]
        (if (:rf/skip-handler? ctx)
          ctx
          (let [event (interceptor/get-coeffect ctx :event)]
            (commit ctx event (invoke handler-fn ctx event))))))))

;; ---- registration ---------------------------------------------------------

(defn- normalise-args
  "Accept the three documented shapes for the variadic tail of reg-event-*:
    (handler)                          — bare handler
    (metadata-or-interceptors handler) — middle slot is one or the other
    (metadata interceptors handler)    — explicit pair
  Per Spec 001 §Allowed forms of the middle slot. Returns
  `[metadata interceptors handler]`."
  [args]
  (case (count args)
    1 [{} [] (first args)]
    2 (let [[middle handler] args]
        (cond
          (map? middle)    [middle [] handler]
          (vector? middle) [{} middle handler]
          :else            (throw (ex-info
                                    "reg-event-*: middle slot must be a metadata-map or an interceptor-vector"
                                    {:args     args
                                     :got      middle
                                     :expected "metadata-map (e.g. {:doc \"...\"}) OR interceptor-vector (e.g. [(path :a)])"}))))
    3 (let [[meta interceptors handler] args]
        [meta (or interceptors []) handler])
    (throw (ex-info
             "reg-event-* arity error — expected (id handler), (id metadata handler), or (id metadata interceptors handler)"
             {:args args :count (count args)}))))

(defn- merge-form-source
  "Merge `*pending-form-source*` into `m` under `:rf.handler/source`
  (Spec 009 §`:rf.handler/source`, Causa Spec 021 §11.2 B.7 stretch,
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
  (let [[meta interceptors handler-fn] (normalise-args args)
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
    (when-let [register! (late-bind/get-fn :marks/register-marks!)]
      (register! :event id meta))
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

      (reg-event-db :id                          (fn [db ev] new-db))
      (reg-event-db :id {:doc \"...\" :spec ...}   (fn [db ev] new-db))
      (reg-event-db :id [(path :counter)]        (fn [slice ev] new-slice))
      (reg-event-db :id {:doc \"...\"} [icpt]      (fn [db ev] new-db))

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

  The effect-map is a **closed shape**: only `:db` and `:fx` are
  permitted at the top level (per migration M-8). Legacy v1 top-level
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
