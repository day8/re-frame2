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

  See `kind-spec` for the per-kind :invoke / :commit pair."
  [kind handler-fn]
  (let [{:keys [interceptor-id invoke commit]} (get kind-spec kind)]
    (interceptor/->interceptor
      :id interceptor-id
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

(defn- register-event!
  "Common registration body for the three reg-event-* forms.

  Steps (uniform across :db / :fx / :ctx kinds):
    1. parse the variadic middle slot into [metadata interceptors handler];
    2. warn-if-misplaced — `:interceptors` inside the metadata-map silently
       drops the chain (rf2-bbea, rf2-w3vn);
    3. wrap the user handler into the kind-appropriate interceptor via
       `wrap-event-handler` (see `kind-spec`);
    4. register under `:event` with `:event/kind` recording which form was
       used and `:handler-fn` retained for tooling introspection;
    5. return the event id. Path-D schema-first privacy has no
       user-facing redaction interceptor to police at registration time.

  Returns the event id."
  [kind reg-fn-name id args]
  (let [[meta interceptors handler-fn] (normalise-args args)
        wrapped (wrap-event-handler kind handler-fn)]
    (warn-interceptors-in-meta! reg-fn-name id meta)
    (registrar/register! :event id
      (assoc (source-coords/merge-coords meta)
             :event/kind   kind
             :handler-fn   handler-fn
             :interceptors (-> [] (into interceptors) (conj wrapped))))
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
