(ns re-frame.http-middleware
  "Per-frame request-side interceptor chain for `:rf.http/managed`.

  Extracted from `re-frame.http-managed` per rf2-3i9b. Per rf2-6y3q
  (Spec 014 §Middleware): each frame has an ordered chain of
  request-side interceptors that fire before `:rf.http/managed` issues a
  request. Per rf2-eyjbn the public surface is positional and aligns
  with the rest of the `reg-*` family —
  `(reg-http-interceptor id opts? before)` — id keyword, opts kwarg with
  `:frame` plus any `:rf/registration-metadata` (`:doc`, `:tags`,
  `:schema`, `:sensitive?`), and a positional `before` fn `(fn [ctx] ctx')`.

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
  (:require [re-frame.http-privacy :as privacy]
            [re-frame.interop      :as interop]
            [re-frame.source-coords :as source-coords]
            [re-frame.trace        :as trace]))

(defonce
  ^{:doc "frame-id → vector of `:rf/http-interceptor-meta` slots — each a map
  carrying `:id`, `:before`, and the captured registration-metadata
  (`:doc`, `:spec`, `:tags`, `:sensitive?`, flat source-coord keys
  `:ns`/`:line`/`:column`/`:file`). Per-frame so each frame's HTTP
  middleware chain is isolated. Order is registration-order; clearing an
  id and re-registering re-appends to the end."}
  interceptors
  (atom {}))

(defn- valid-args?
  "rf2-eyjbn — the reshaped signature validates id (positional keyword),
  before-fn (positional fn), and opts (map or nil; if present, `:frame`
  must be a keyword)."
  [id opts before]
  (and (keyword? id)
       (fn? before)
       (or (nil? opts) (map? opts))
       (or (nil? (:frame opts)) (keyword? (:frame opts)))))

(defn reg-http-interceptor
  "Register a request-side HTTP interceptor on a frame's `:rf.http/managed`
  middleware chain. Per Spec 014 §Middleware.

  Signature: `(reg-http-interceptor id opts? before)` — `id` is a
  keyword; `before` is `(fn [ctx] ctx')`; `opts` is an optional map
  carrying `:frame` (default `:rf/default`) plus any of the
  `:rf/registration-metadata` keys (`:doc` / `:tags` / `:schema` /
  `:sensitive?`). Per rf2-eyjbn the surface aligns with the rest of the
  `reg-*` family — positional id, opts-kwarg with `:frame`, positional
  handler — matching `reg-flow`'s precedent.

  Two-arity (no opts):
    (reg-http-interceptor :id (fn [ctx] ctx'))
  Three-arity:
    (reg-http-interceptor :id {:frame :rf/api :doc \"...\"} (fn [ctx] ctx'))

  `ctx` carries `:request` (the request map), `:args` (the full
  `:rf.http/managed` args), `:frame` (the frame-id), and `:event` (the
  originating event vector). The fn returns a (possibly-modified) ctx.

  Source-coords (`:ns` / `:line` / `:column` / `:file`) are auto-captured
  at the `rf/reg-http-interceptor` call site by the JVM-emitted macro in
  `re-frame.core` (per Spec 001 §Source-coordinate capture). The stored
  slot conforms to `:rf/http-interceptor-meta` (Spec-Schemas) — base
  `:rf/registration-metadata` plus the interceptor-specific `:id`,
  `:before`, and `:frame` keys.

  Re-registering an id replaces the slot in place (keeping registration
  order). Order is preserved across replace; first registration wins
  for position.

  After `clear-http-interceptor` removes a slot, a subsequent
  `reg-http-interceptor` of the same id is a fresh registration and
  appends to the end of the chain — the prior position is forgotten on
  clear (per Spec 014 §Chain order and frame scope, rf2-kg5nw).

  Throws `:rf.error/http-bad-interceptor` if any arg shape is invalid.

  Returns the registered `id`."
  ([id before] (reg-http-interceptor id nil before))
  ([id opts before]
   (when-not (valid-args? id opts before)
     (throw (ex-info ":rf.error/http-bad-interceptor"
                     {:where    'rf/reg-http-interceptor
                      :recovery :no-recovery
                      :received {:id id :opts opts :before before}
                      :reason   "expected (reg-http-interceptor id opts? before): id keyword, before fn, opts (when present) a map with optional :frame keyword"})))
   (let [frame-id  (or (:frame opts) :rf/default)
         user-meta (dissoc opts :frame)
         slot      (assoc (source-coords/merge-coords user-meta)
                          :id     id
                          :before before
                          :frame  frame-id)]
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
     id)))

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
  `:rf.error/http-interceptor-failed` if any `:before` throws.

  `ctx` carries a top-level `:sensitive?` flag (resolved by
  `managed-handler` from per-call args + handler-registration metadata)
  so the failure-path trace event redacts the request URL via the
  query-param denylist before it reaches the trace surface. Without
  this gate, an `Authorization`-token-bearing query string (e.g.
  `?access_token=…`) leaked into traces whenever an interceptor
  threw — rf2-1jcpm (round-2 security audit finding 1)."
  [frame-id ctx]
  (let [chain (get @interceptors frame-id)
        sensitive? (true? (:sensitive? ctx))]
    (reduce
      (fn [acc {:keys [id before]}]
        (if before
          (try
            (let [out (before acc)]
              (if (map? out)
                out
                ;; Canonical thrown-error shape (Spec 009): message is
                ;; the stringified discriminator kw; the descriptive
                ;; sentence (naming the offending interceptor id) rides
                ;; on :reason. The outer wrapper carries :interceptor-id
                ;; so a chain failure is locatable via ex-data; the :id
                ;; key here is kept for programmatic consumers.
                (throw (ex-info ":rf.error/http-interceptor-bad-return"
                                {:rf.error/id :rf.error/http-interceptor-bad-return
                                 :where       'rf/reg-http-interceptor
                                 :recovery    :no-recovery
                                 :reason      (str "interceptor " id " :before did not return a ctx map")
                                 :id          id
                                 :returned    out}))))
            (catch #?(:clj Throwable :cljs :default) t
              (let [data (ex-info ":rf.error/http-interceptor-failed"
                                  {:where    'run-http-interceptor-chain!
                                   :recovery :no-recovery
                                   :frame    frame-id
                                   :interceptor-id id
                                   :url      (get-in acc [:request :url])
                                   ;; Prefer the inner throw's :reason
                                   ;; (a human sentence naming the
                                   ;; offending interceptor) over the
                                   ;; raw message — canonical throws
                                   ;; stringify the discriminator kw as
                                   ;; their message.
                                   :cause    (or (:reason (ex-data t))
                                                 #?(:clj  (.getMessage ^Throwable t)
                                                    :cljs (.-message t)))})]
                (when interop/debug-enabled?
                  ;; rf2-1jcpm — route through the privacy composer so a
                  ;; denylisted query param (`?api_key=…`) is scrubbed
                  ;; and `:sensitive?` is stamped on the trace event when
                  ;; either the handler/per-call sensitivity OR the URL's
                  ;; query string carries a denylisted param name.
                  (trace/emit-error! :rf.error/http-interceptor-failed
                                     (privacy/prepare-emit-failure
                                       (ex-data data)
                                       sensitive?)))
                (throw data))))
          acc))
      ctx
      chain)))
