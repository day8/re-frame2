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
            [re-frame.interceptor :as interceptor]))

;; ---- handler-as-interceptor wrappers --------------------------------------
;; Each reg-event-* form wraps the user's handler fn into an interceptor
;; whose :before slot runs the handler and stores its result on the context.

(defn- db-handler->interceptor
  "Wrap a (fn [db event]) → new-db handler."
  [handler-fn]
  (interceptor/->interceptor
    :id :rf/db-handler
    :before
    (fn [ctx]
      (let [db    (interceptor/get-coeffect ctx :db)
            event (interceptor/get-coeffect ctx :event)
            new-db (handler-fn db event)]
        (interceptor/assoc-effect ctx :db new-db)))))

(defn- fx-handler->interceptor
  "Wrap a (fn [cofx event]) → effects-map handler."
  [handler-fn]
  (interceptor/->interceptor
    :id :rf/fx-handler
    :before
    (fn [ctx]
      (let [cofx    (interceptor/get-coeffect ctx)
            event   (interceptor/get-coeffect ctx :event)
            effects (handler-fn cofx event)]
        (cond-> ctx
          (contains? effects :db) (interceptor/assoc-effect :db (:db effects))
          (contains? effects :fx) (interceptor/assoc-effect :fx (:fx effects))
          ;; Allow other top-level effect-map keys (open shape per Spec 002).
          true (update :effects merge (dissoc effects :db :fx)))))))

(defn- ctx-handler->interceptor
  "Wrap a (fn [context]) → context handler. Advanced; few apps need it."
  [handler-fn]
  (interceptor/->interceptor
    :id :rf/ctx-handler
    :before
    (fn [ctx] (or (handler-fn ctx) ctx))))

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
