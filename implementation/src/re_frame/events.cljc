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
            [re-frame.trace :as trace]))

;; ---- effect-map shape policing (Spec migration M-8) -----------------------
;;
;; Per docs/specification/MIGRATION.md §M-8 and Spec-Schemas.md §:rf/effect-map,
;; the effect-map a reg-event-fx handler returns is a CLOSED shape: only :db
;; and :fx live at the top level. Legacy v1 top-level keys (:dispatch,
;; :dispatch-later, :dispatch-n, :http, etc.) must move into :fx as
;; [[fx-id args] ...] entries.
;;
;; The runtime polices this contract: any non-:db / non-:fx top-level key
;; emits a structured :rf.error/effect-map-shape trace per Spec 009 §Error
;; contract, with :recovery :logged-and-skipped. The offending key is dropped
;; (NOT merged silently nor routed through the fx machinery) — silently
;; routing a top-level :dispatch was the v1 behaviour M-8 explicitly removes.

(defn- legacy-key-suggestion
  "Return a one-clause hint mapping a known legacy top-level key to its M-8
  rewrite, or nil for an unrecognised key. Used to compose the :reason
  string per Spec 009 §Style rubric for :reason strings."
  [k]
  (case k
    :dispatch        "wrap as `:fx [[:dispatch event]]`"
    :dispatch-later  "wrap as `:fx [[:dispatch-later {...}]]`"
    :dispatch-n      "expand each event into `:fx [[:dispatch e1] [:dispatch e2] ...]`"
    :http            "wrap as `:fx [[:http {...}]]`"
    nil))

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
      (let [v          (get effects k)
            suggestion (legacy-key-suggestion k)
            reason     (str "Effect-map for `" event-id "` returned top-level key `" k
                            "`; only `:db` and `:fx` are allowed at the top level"
                            (when suggestion (str " — " suggestion))
                            ".")]
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
  :logged-and-skipped) and the key is dropped."
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
          (when (map? effects)
            (police-effect-map-shape! effects event))
          (cond-> ctx
            (and (map? effects) (contains? effects :db))
            (interceptor/assoc-effect :db (:db effects))

            (and (map? effects) (contains? effects :fx))
            (interceptor/assoc-effect :fx (:fx effects))))))))

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
          :else                (throw (ex-info "re-frame-2: bad reg-event-* args"
                                               {:args args}))))
    3 (let [[meta interceptors handler] args]
        [meta (or interceptors []) handler])
    (throw (ex-info "re-frame-2: reg-event-* arity error" {:args args}))))

(defn reg-event-db
  [id & args]
  (let [[meta interceptors handler-fn] (normalise-args args)
        wrapped     (db-handler->interceptor handler-fn)
        all-chain   (concat interceptors [wrapped])]
    (registrar/register! :event id
      (assoc meta
             :event/kind   :db
             :handler-fn   handler-fn
             :interceptors (vec all-chain)))
    id))

(defn reg-event-fx
  [id & args]
  (let [[meta interceptors handler-fn] (normalise-args args)
        wrapped     (fx-handler->interceptor handler-fn)
        all-chain   (concat interceptors [wrapped])]
    (registrar/register! :event id
      (assoc meta
             :event/kind   :fx
             :handler-fn   handler-fn
             :interceptors (vec all-chain)))
    id))

(defn reg-event-ctx
  [id & args]
  (let [[meta interceptors handler-fn] (normalise-args args)
        wrapped     (ctx-handler->interceptor handler-fn)
        all-chain   (concat interceptors [wrapped])]
    (registrar/register! :event id
      (assoc meta
             :event/kind   :ctx
             :handler-fn   handler-fn
             :interceptors (vec all-chain)))
    id))

(defn clear-event
  ([] (registrar/clear-kind! :event))
  ([id] (registrar/unregister! :event id)))
