(ns re-frame.http-middleware
  "Per-frame request- AND response-side interceptor chain for `:rf.http/managed`.

  Extracted from `re-frame.http-managed` per rf2-3i9b. Per rf2-6y3q
  (Spec 014 §Middleware): each frame has an ordered chain of HTTP
  interceptors. Each interceptor is an interceptor-map carrying an
  optional `:before` (request-side transform, fired in registration
  order before `:rf.http/managed` issues the request) and an optional
  `:after` (response-side transform, fired in REVERSE registration order
  on the response BEFORE `:on-success` / `:on-failure` are dispatched).

  Per rf2-uheqq (Mike decision 2026-05-28, rf2-omwua option b + shape
  (iii)): the public surface is `(reg-http-interceptor id interceptor-map)`
  — a single map carrying `:before`, `:after`, `:frame`, and any
  `:rf/registration-metadata` keys. Pre-alpha clean break: the prior
  positional `(reg-http-interceptor id opts? before)` shape is retired
  outright. The reshape aligns with the event-interceptor
  `{:id :before :after}` mental model the rest of the framework already
  uses (Spec 002).

  ## ctx contract

  Each `:before` receives a ctx map with the documented shape:

    {:request <request-map>      ;; the :request map from the args
     :args    <full-args-map>    ;; the full :rf.http/managed args
     :frame   <frame-id>         ;; resolved frame id
     :event   <origin-event>}    ;; originating event vector or nil

  A `:before` returns the (possibly-modified) ctx. The runtime threads
  the chain in registration order; the final `:request` is what the
  transport ships.

  Each `:after` receives `(fn [ctx response] response')` where:

    - `ctx`      — the SAME ctx the `:before` chain produced for THIS
                   request. Carrying the request ctx forward enables
                   request-correlated response handling: response-time
                   telemetry (wall-clock delta between a `:before`'s
                   start mark and the `:after`'s read), per-request
                   header parsing, auth-token refresh keyed off the
                   originating event, …
    - `response` — `{:kind :success :value <decoded>}` or
                   `{:kind :failure :failure <failure-map>}`. The shape
                   matches the reply-payload `build-reply-event`
                   appends to `:on-success` / `:on-failure`.

  Returns the (possibly-transformed) response map; the runtime threads
  the next `:after` over the return value and finally substitutes the
  fully-threaded response into the reply-payload before
  `:on-success` / `:on-failure` fire.

  ## Chain ordering

  - `:before` chain — registration order. A registered before B: request
    flows A.before → B.before → transport.
  - `:after`  chain — REVERSE registration order. A registered before B:
    response flows transport → B.after → A.after → reply dispatch.

  This mirrors the event-interceptor onion (Spec 002): the outermost
  registration wraps the innermost on the request side and again on the
  response side. Interceptors with no `:after` are transparent in the
  response chain (skipped, not nil-substituted).

  ## Storage

  Per-frame in a `defonce` atom keyed `frame-id → [interceptor ...]`,
  mirroring the per-frame flow registry pattern (see
  `re-frame.flows.registry`'s private `flows` atom + the
  `flows-snapshot` accessor). Frame-scoped: an interceptor registered
  against frame A does not fire for a request dispatched from frame B."
  (:require [re-frame.http-privacy :as privacy]
            [re-frame.interop      :as interop]
            [re-frame.source-coords :as source-coords]
            [re-frame.trace        :as trace]))

(defonce
  ^{:doc "frame-id → vector of `:rf/http-interceptor-meta` slots — each a map
  carrying `:id`, optional `:before`, optional `:after`, `:frame`, and the
  captured registration-metadata (`:doc`, `:schema`, `:tags`, `:sensitive?`,
  flat source-coord keys `:ns`/`:line`/`:column`/`:file`). Per-frame so
  each frame's HTTP middleware chain is isolated. Order is
  registration-order; clearing an id and re-registering re-appends to the
  end."}
  interceptors
  (atom {}))

(defn- valid-args?
  "rf2-uheqq — shape (iii) validates id (positional keyword) and the
  interceptor-map: must be a map, must carry at least one of
  `:before` / `:after` (each, if present, must be a fn), and
  if `:frame` is present it must be a keyword."
  [id interceptor-map]
  (and (keyword? id)
       (map? interceptor-map)
       (or (nil? (:before interceptor-map))
           (fn?  (:before interceptor-map)))
       (or (nil? (:after  interceptor-map))
           (fn?  (:after  interceptor-map)))
       ;; at least one of :before / :after must be supplied — registering
       ;; a no-op interceptor is meaningless and almost certainly a typo
       (or (some? (:before interceptor-map))
           (some? (:after  interceptor-map)))
       (or (nil? (:frame interceptor-map))
           (keyword? (:frame interceptor-map)))))

(defn reg-http-interceptor
  "Register an HTTP interceptor on a frame's `:rf.http/managed` middleware
  chain. Per Spec 014 §Middleware + rf2-uheqq.

  Signature: `(reg-http-interceptor id interceptor-map)` — `id` is a
  keyword; `interceptor-map` carries:

    - `:before` (optional) — `(fn [ctx] ctx')`, request-side transform
    - `:after`  (optional) — `(fn [ctx response] response')`,
                              response-side transform
    - `:frame`  (optional) — frame id, default `:rf/default`
    - `:doc` / `:tags` / `:schema` / `:sensitive?` — standard
      `:rf/registration-metadata` (per `:rf/http-interceptor-meta`)

  At least one of `:before` / `:after` MUST be supplied — a no-op
  interceptor is rejected.

  Example:
    (reg-http-interceptor :auth
      {:doc \"Stamp Bearer token.\"
       :before (fn [ctx] ...)
       :after  (fn [ctx response] ...)})

  The `:before` chain runs in REGISTRATION ORDER before the request
  fires. The `:after` chain runs in REVERSE REGISTRATION ORDER after
  the response is built, BEFORE `:on-success` / `:on-failure` are
  dispatched. `:after` sees the SAME ctx the `:before` chain produced
  (enables request-correlated telemetry).

  `ctx` carries `:request` (the request map), `:args` (the full
  `:rf.http/managed` args), `:frame` (the frame-id), and `:event` (the
  originating event vector). `:before` returns a (possibly-modified)
  ctx. `:after` receives the ctx unchanged plus the response map
  (`{:kind :success :value v}` or `{:kind :failure :failure f}`) and
  returns the (possibly-transformed) response.

  Source-coords (`:ns` / `:line` / `:column` / `:file`) are auto-captured
  at the `rf/reg-http-interceptor` call site by the JVM-emitted macro in
  `re-frame.core` (per Spec 001 §Source-coordinate capture). The stored
  slot conforms to `:rf/http-interceptor-meta` (Spec-Schemas).

  Re-registering an id replaces the slot in place (keeping registration
  order). Order is preserved across replace; first registration wins
  for position.

  After `clear-http-interceptor` removes a slot, a subsequent
  `reg-http-interceptor` of the same id is a fresh registration and
  appends to the end of the chain — the prior position is forgotten on
  clear (per Spec 014 §Chain order and frame scope, rf2-kg5nw).

  Throws `:rf.error/http-bad-interceptor` if any arg shape is invalid.

  Returns the registered `id`."
  [id interceptor-map]
  (when-not (valid-args? id interceptor-map)
    (throw (ex-info ":rf.error/http-bad-interceptor"
                    {:where    'rf/reg-http-interceptor
                     :recovery :no-recovery
                     :received {:id id :interceptor-map interceptor-map}
                     :reason   "expected (reg-http-interceptor id interceptor-map): id keyword; interceptor-map a map carrying at least one of :before / :after (each a fn), optional :frame keyword, optional :rf/registration-metadata"})))
  (let [frame-id  (or (:frame interceptor-map) :rf/default)
        before    (:before interceptor-map)
        after     (:after  interceptor-map)
        user-meta (dissoc interceptor-map :frame :before :after)
        slot      (cond-> (source-coords/merge-coords user-meta)
                    true   (assoc :id    id
                                  :frame frame-id)
                    before (assoc :before before)
                    after  (assoc :after  after))]
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

;; rf2-jkake.9 — the `:before` (`run-interceptor-chain!`) and `:after`
;; (`run-after-chain!`) chains share one walk shape: reduce over the
;; per-frame chain, skip interceptors lacking the relevant slot, run the
;; slot fn, reject a non-map return with `:rf.error/http-interceptor-bad-
;; return`, and wrap any throw as `:rf.error/http-interceptor-failed`
;; (routed through the privacy composer + re-thrown). `run-chain*`
;; factors that body out; the two public fns are thin wrappers that vary
;; only on: the slot key (`:before` / `:after`), how the slot fn is
;; invoked (`(slot acc)` vs `(slot fixed-ctx acc)`), the chain order
;; (registration / reverse), the `:where` symbol + optional `:phase`, the
;; URL source (the threaded `acc` for `:before`; the fixed middleware-ctx
;; for `:after`), and the bad-return sentence. The load-bearing comments
;; on each wrapper preserve the per-path rationale.
(defn- run-chain*
  [{:keys [chain frame-id slot-key invoke sensitive? where phase url-of slot-noun]} init]
  (reduce
    (fn [acc interceptor]
      (let [{:keys [id]} interceptor
            slot          (get interceptor slot-key)]
        (if slot
          (try
            (let [out (invoke slot acc)]
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
                                 :reason      (str "interceptor " id " " slot-noun)
                                 :id          id
                                 :returned    out}))))
            (catch #?(:clj Throwable :cljs :default) t
              (let [data (ex-info ":rf.error/http-interceptor-failed"
                                  (cond-> {:where    where
                                           :recovery :no-recovery
                                           :frame    frame-id
                                           :interceptor-id id
                                           :url      (url-of acc)
                                           ;; Prefer the inner throw's :reason
                                           ;; (a human sentence naming the
                                           ;; offending interceptor) over the
                                           ;; raw message — canonical throws
                                           ;; stringify the discriminator kw as
                                           ;; their message.
                                           :cause    (or (:reason (ex-data t))
                                                         #?(:clj  (.getMessage ^Throwable t)
                                                            :cljs (.-message t)))}
                                    phase (assoc :phase phase)))]
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
          acc)))
    init
    chain))

(defn run-interceptor-chain!
  "Walk the registration-order interceptor chain for `frame-id`, threading
  `ctx` through each `:before`. Returns the final ctx, or throws
  `:rf.error/http-interceptor-failed` if any `:before` throws.

  Interceptors without a `:before` slot are transparent in the request
  chain (acc passes through unchanged).

  `ctx` carries a top-level `:sensitive?` flag (resolved by
  `managed-handler` from per-call args + handler-registration metadata)
  so the failure-path trace event redacts the request URL via the
  query-param denylist before it reaches the trace surface. Without
  this gate, an `Authorization`-token-bearing query string (e.g.
  `?access_token=…`) leaked into traces whenever an interceptor
  threw — rf2-1jcpm (round-2 security audit finding 1)."
  [frame-id ctx]
  (run-chain*
    {:chain      (get @interceptors frame-id)
     :frame-id   frame-id
     :slot-key   :before
     ;; `:before` reads the URL from the threaded accumulator — the
     ;; evolving ctx, whose `:request` an earlier `:before` may have
     ;; rewritten — so the trace carries the URL as the failing
     ;; interceptor actually saw it.
     :invoke     (fn [before acc] (before acc))
     :url-of     #(get-in % [:request :url])
     :sensitive? (true? (:sensitive? ctx))
     :where      'rf.http/run-interceptor-chain!
     :slot-noun  ":before did not return a ctx map"}
    ctx))

(defn run-after-chain!
  "Per rf2-uheqq + Spec 014 §Middleware. Walk the per-frame interceptor
  chain for `frame-id` in REVERSE registration order, threading
  `response` through each `:after`. Returns the (possibly-transformed)
  response map.

  Each `:after` receives `(fn [ctx response] response')` — `ctx` is the
  middleware-ctx the `:before` chain produced for THIS request (carried
  forward by the transport so the `:after` sees the exact same shape
  the `:before` ended with). `response` is `{:kind :success :value v}`
  or `{:kind :failure :failure f}`.

  Interceptors without an `:after` slot are transparent in the response
  chain (acc passes through unchanged). Throws by `:after` propagate
  to the caller via the same `:rf.error/http-interceptor-failed` shape
  the `:before` path uses, so a misbehaving response-side interceptor
  surfaces on the same trace event."
  [frame-id middleware-ctx response]
  (run-chain*
    {;; Reverse order — mirror of the event-interceptor onion (Spec 002).
     :chain      (reverse (get @interceptors frame-id))
     :frame-id   frame-id
     :slot-key   :after
     ;; `:after` always sees the fixed middleware-ctx the `:before` chain
     ;; produced (the response, not the ctx, is what threads through the
     ;; reduce), so the URL is read from that ctx rather than the acc.
     :invoke     (fn [after acc] (after middleware-ctx acc))
     :url-of     (fn [_acc] (get-in middleware-ctx [:request :url]))
     :sensitive? (true? (:sensitive? middleware-ctx))
     :where      'rf.http/run-after-chain!
     :phase      :after
     :slot-noun  ":after did not return a response map"}
    response))
