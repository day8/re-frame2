(ns re-frame.events
  "Event-handler registration. Per Spec 002 §Event handlers and Spec 001
  §Registry model.

  Three kinds of event handlers:
    reg-event-db   — pure (db, event) → new-db
    reg-event-fx   — pure (cofx, event) → effects-map ({:db ... :fx [...]})
    reg-event-ctx  — full-context handler returns context (advanced)

  All three register under registry kind :event with an :event/kind sub-tag
  recording which form was used. The runtime treats all three uniformly
  during drain — the difference is only in the wrapping shape."
  (:require [re-frame.registrar :as registrar]
            [re-frame.interceptor :as interceptor]
            [re-frame.privacy :as privacy]
            [re-frame.source-coords :as source-coords]
            [re-frame.trace :as trace]))

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

;; ---- effect-map shape policing (Spec migration M-8) -----------------------
;;
;; Per spec/MIGRATION.md §M-8 and Spec-Schemas.md §:rf/effect-map,
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
  level. Returns the list of offending keys (which the caller drops)."
  [effects event]
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
    offending))

;; ---- handler-as-interceptor wrappers --------------------------------------
;; Each reg-event-* form wraps the user's handler fn into an interceptor
;; whose :before slot runs the handler and stores its result on the context.

(defn- db-handler->interceptor
  "Wrap a (fn [db event]) → new-db handler.

  Per Spec 010 §Validation order steps 1-2 (rf2-7leq, rf2-jwm4) — if
  any pre-handler validation set :rf/skip-handler? on the context,
  short-circuit and run no body. The schema-validation-failure trace
  has already fired."
  [handler-fn]
  (interceptor/->interceptor
    :id :rf/db-handler
    :before
    (fn [ctx]
      (if (:rf/skip-handler? ctx)
        ctx
        (let [db    (interceptor/get-coeffect ctx :db)
              event (interceptor/get-coeffect ctx :event)
              new-db (handler-fn db event)]
          (interceptor/assoc-effect ctx :db new-db))))))

(defn- fx-handler->interceptor
  "Wrap a (fn [cofx event]) → effects-map handler. See db-handler->interceptor
  for the :rf/skip-handler? short-circuit (Spec 010 step 1/2 recovery).

  Per Spec migration M-8 the effect map is closed: only :db and :fx live at
  the top level. Any other top-level key is policed via
  police-effect-map-shape! — a :rf.error/effect-map-shape trace is emitted
  per offending key (Spec 009 §Error contract, :recovery
  :logged-and-skipped) and the key is dropped.

  Per rf2-k3bj: a reg-event-fx handler is contracted to return a map (or
  nil — the documented no-op). Any other return type (vector, number,
  string, ...) emits :rf.error/effect-handler-bad-return; the runtime
  cannot guess intent and treats the dispatch as a no-op (`:recovery
  :no-recovery`)."
  [handler-fn]
  (interceptor/->interceptor
    :id :rf/fx-handler
    :before
    (fn [ctx]
      (if (:rf/skip-handler? ctx)
        ctx
        (let [cofx    (interceptor/get-coeffect ctx)
              event   (interceptor/get-coeffect ctx :event)
              effects (handler-fn cofx event)]
          (cond
            (nil? effects) ctx  ;; documented legal no-op
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
                (contains? effects :db)
                (interceptor/assoc-effect :db (:db effects))

                (contains? effects :fx)
                (interceptor/assoc-effect :fx (:fx effects))))))))))

(defn- ctx-handler->interceptor
  "Wrap a (fn [context]) → context handler. Advanced; few apps need it."
  [handler-fn]
  (interceptor/->interceptor
    :id :rf/ctx-handler
    :before
    (fn [ctx]
      (if (:rf/skip-handler? ctx)
        ctx
        (or (handler-fn ctx) ctx)))))

;; ---- registration ---------------------------------------------------------

(defn- normalise-args
  "Accept either (id handler) or (id metadata-or-interceptors handler).
  Per Spec 001 §Allowed forms of the middle slot: the middle slot may be
  metadata (map) or an interceptors vector."
  [args]
  (case (count args)
    1 [{} [] (first args)]
    2 (let [[middle handler] args]
        (cond
          (map? middle)        [middle [] handler]
          (vector? middle)     [{} middle handler]
          :else                (throw (ex-info
                                        "reg-event-*: middle slot must be a metadata-map or an interceptor-vector"
                                        {:args     args
                                         :got      middle
                                         :expected "metadata-map (e.g. {:doc \"...\"}) OR interceptor-vector (e.g. [(path :a)])"}))))
    3 (let [[meta interceptors handler] args]
        [meta (or interceptors []) handler])
    (throw (ex-info
             "reg-event-* arity error — expected (id handler), (id metadata handler), (id interceptors handler), or (id metadata interceptors handler)"
             {:args args :count (count args)}))))

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
  (let [[meta interceptors handler-fn] (normalise-args args)
        _           (warn-interceptors-in-meta! "reg-event-db" id meta)
        wrapped     (db-handler->interceptor handler-fn)
        all-chain   (concat interceptors [wrapped])]
    (registrar/register! :event id
      (assoc (source-coords/merge-coords meta)
             :event/kind   :db
             :handler-fn   handler-fn
             :interceptors (vec all-chain)))
    ;; Per rf2-isdwf / Spec 009 §Privacy: emit
    ;; :rf.warning/sensitive-without-redaction once per (kind, id) pair
    ;; when :sensitive? true but no with-redacted in the chain. Called
    ;; AFTER register! so listeners see the registration trace first.
    (privacy/warn-sensitive-without-redaction! :event id meta interceptors)
    id))

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
  (let [[meta interceptors handler-fn] (normalise-args args)
        _           (warn-interceptors-in-meta! "reg-event-fx" id meta)
        wrapped     (fx-handler->interceptor handler-fn)
        all-chain   (concat interceptors [wrapped])]
    (registrar/register! :event id
      (assoc (source-coords/merge-coords meta)
             :event/kind   :fx
             :handler-fn   handler-fn
             :interceptors (vec all-chain)))
    (privacy/warn-sensitive-without-redaction! :event id meta interceptors)
    id))

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
  (let [[meta interceptors handler-fn] (normalise-args args)
        _           (warn-interceptors-in-meta! "reg-event-ctx" id meta)
        wrapped     (ctx-handler->interceptor handler-fn)
        all-chain   (concat interceptors [wrapped])]
    (registrar/register! :event id
      (assoc (source-coords/merge-coords meta)
             :event/kind   :ctx
             :handler-fn   handler-fn
             :interceptors (vec all-chain)))
    (privacy/warn-sensitive-without-redaction! :event id meta interceptors)
    id))

(defn clear-event
  "Unregister an event handler. Zero-arity clears every registered
  event handler in the registrar; one-arity clears the named one.

  Hot-reload tools and test fixtures call this between rebuilds to
  drop stale handlers; production code rarely needs it. Returns nil.

  See also: `reg-event-db`, `reg-event-fx`, `reg-event-ctx`."
  ([] (registrar/clear-kind! :event))
  ([id] (registrar/unregister! :event id)))
