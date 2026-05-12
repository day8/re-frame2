(ns re-frame.http-middleware
  "Per-frame request-side interceptor chain for `:rf.http/managed`.

  Extracted from `re-frame.http-managed` per rf2-3i9b. Per rf2-6y3q
  (Spec 014 §Middleware): each frame has an ordered chain of
  request-side interceptors that fire before `:rf.http/managed` issues a
  request. The shape matches the event-handler interceptor idiom — each
  interceptor is a map `{:id <kw> :before (fn [ctx] ctx')}` — so authors
  reuse what they already know.

  The `ctx` map carried through the chain has the documented shape:

    {:request <request-map>      ;; the :request map from the args
     :args    <full-args-map>    ;; the full :rf.http/managed args
     :frame   <frame-id>         ;; resolved frame id
     :event   <origin-event>}    ;; originating event vector or nil

  A `:before` fn returns the (possibly-modified) ctx. The runtime
  threads the chain in registration order; the final `:request` is what
  the transport ships. After-style interceptors (response transforms)
  are out of scope for v1; the slot `:after` is reserved for future
  extension.

  Storage is per-frame in a `defonce` atom keyed `frame-id → [interceptor ...]`,
  mirroring the `re-frame.flows/flows` pattern. Frame-scoped: an
  interceptor registered against frame A does not fire for a request
  dispatched from frame B."
  (:require [re-frame.interop :as interop]
            [re-frame.trace   :as trace]))

(defonce
  ^{:doc "frame-id → vector of {:id :before} interceptor maps. Per-frame
  so each frame's HTTP middleware chain is isolated. Order is
  registration-order; clearing an id and re-registering re-appends to
  the end."}
  interceptors
  (atom {}))

(defn- valid-interceptor?
  [m]
  (and (map? m)
       (keyword? (:id m))
       (fn? (:before m))))

(defn reg-http-interceptor
  "Register a request-side HTTP interceptor on a frame's `:rf.http/managed`
  middleware chain. Per Spec 014 §Middleware.

  The interceptor map shape is:

    {:frame  <frame-id>            ;; default :rf/default
     :id     <keyword>              ;; required, addressable for clear
     :before (fn [ctx] ctx')}       ;; required, request-side transform

  `ctx` carries `:request` (the request map), `:args` (the full
  `:rf.http/managed` args), `:frame` (the frame-id), and `:event` (the
  originating event vector). The fn returns a (possibly-modified) ctx.

  Re-registering an id replaces the slot in place (keeping registration
  order). Order is preserved across replace; first registration wins
  for position.

  Throws `:rf.error/http-bad-interceptor` if the shape is invalid."
  [{:keys [frame id before] :as interceptor}]
  (when-not (valid-interceptor? interceptor)
    (throw (ex-info ":rf.error/http-bad-interceptor"
                    {:where    'reg-http-interceptor
                     :recovery :no-recovery
                     :received interceptor
                     :reason   "interceptor must be a map with :id (keyword) and :before (fn)"})))
  (let [frame-id (or frame :rf/default)
        slot     (cond-> {:id id}
                   before (assoc :before before))]
    (swap! interceptors update frame-id
           (fn [chain]
             (let [chain (or chain [])
                   idx   (->> chain
                              (keep-indexed (fn [i v] (when (= (:id v) id) i)))
                              first)]
               (if idx
                 (assoc chain idx slot)
                 (conj chain slot)))))
    (when interop/debug-enabled?
      (trace/emit! :info :rf.http.interceptor/registered
                   {:frame frame-id
                    :id    id}))
    id))

(defn clear-http-interceptor
  "Unregister an HTTP interceptor by id from a frame's chain.

  Single-arity: clear by id on `:rf/default`.
  Two-arity: clear by id on the named frame.
  No-arg form not supported — explicit ids only."
  ([id] (clear-http-interceptor :rf/default id))
  ([frame id]
   (let [frame-id (or frame :rf/default)
         existed? (some? (some (fn [v] (when (= (:id v) id) v))
                               (get @interceptors frame-id)))]
     (swap! interceptors update frame-id
            (fn [chain]
              (vec (remove (fn [v] (= (:id v) id)) chain))))
     (when (and existed? interop/debug-enabled?)
       (trace/emit! :info :rf.http.interceptor/cleared
                    {:frame frame-id
                     :id    id}))
     id)))

(defn clear-all-http-interceptors!
  "Test-time helper: drop the per-frame interceptor registry."
  []
  (reset! interceptors {})
  nil)

(defn run-interceptor-chain!
  "Walk the registration-order interceptor chain for `frame-id`, threading
  `ctx` through each `:before`. Returns the final ctx, or throws
  `:rf.error/http-interceptor-failed` if any `:before` throws."
  [frame-id ctx]
  (let [chain (get @interceptors frame-id)]
    (reduce
      (fn [acc {:keys [id before]}]
        (if before
          (try
            (let [out (before acc)]
              (if (map? out)
                out
                (throw (ex-info "interceptor :before did not return a ctx map"
                                {:returned out}))))
            (catch #?(:clj Throwable :cljs :default) t
              (let [data (ex-info ":rf.error/http-interceptor-failed"
                                  {:where    'run-http-interceptor-chain!
                                   :recovery :no-recovery
                                   :frame    frame-id
                                   :interceptor-id id
                                   :url      (get-in acc [:request :url])
                                   :cause    #?(:clj  (.getMessage ^Throwable t)
                                                :cljs (.-message t))})]
                (when interop/debug-enabled?
                  (trace/emit-error! :rf.error/http-interceptor-failed
                                     (ex-data data)))
                (throw data))))
          acc))
      ctx
      chain)))
