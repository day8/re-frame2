(ns re-frame.ssr.ring.lifecycle
  "Per-request frame lifecycle and shared handler-construction contracts.

  Both Ring handlers use these helpers for required-option, payload-policy,
  trusted-shell, and error-fallback decisions. Request data is exposed through
  the frame-scoped `:rf.server/request` coeffect rather than being appended to
  initial event vectors. Per Spec 011 §Request storage and §Head/meta."
  (:require [re-frame.core :as rf]
            [re-frame.error :as rf.error]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.payload-policy :as rf.ssr.payload-policy]
            [re-frame.ssr.ring.trust :as rf.ssr.ring.trust]
            [re-frame.trace :as rf.trace]))

(set! *warn-on-reflection* true)

;; ---- always-on error emission ---------------------------------------------
;;
;; The optional late-bind hook keeps production error shipping independent of
;; the development-gated trace surface.

(defn emit-always-on-error!
  "Fan a pre-built always-on SSR error record through the
  error-emit registry via the `:error-emit/dispatch-error-record` late-bind
  hook. `record` is the union shape `{:rf.error/id <kw> :frame <id-or-nil> :time
  <ms> + flat category keys}`. No-op when the producer hasn't loaded.
  Returns nil."
  [record]
  (when-let [dispatch-error-record! (rf.late-bind/get-fn :error-emit/dispatch-error-record)]
    (dispatch-error-record! record))
  nil)

(def ^:const default-on-error-body
  "The generic body emitted by `default-on-error`; it exposes no throwable
  detail or internal class names."
  "Internal error")

(def ^:const default-on-error-content-type
  "The plaintext UTF-8 Content-Type emitted by `default-on-error`."
  "text/plain; charset=utf-8")

(def default-on-error
  "Minimal 500 response used when a handler caller doesn't supply
  `:on-error`, so the topology-leak contract below lives in exactly one
  place.

  The projector handles drain and render errors. This hook covers setup,
  materialisation, and transport exceptions outside it. The fixed body never
  reads the throwable; custom hooks must preserve that no-detail boundary."
  (let [response {:status  500
                  :headers {"Content-Type" default-on-error-content-type}
                  :body    default-on-error-body}]
    (fn default-on-error-fn [_request _t] response)))

(defn safe-on-error
  "Invoke the resolved `:on-error` Ring-fn under containment and return
  its Ring response. A failing caller hook is traced and replaced by the
  fixed, no-detail default so error handling cannot bypass the boundary."
  [on-error request t]
  (try
    (on-error request t)
    (catch Throwable on-error-t
      (rf.trace/emit-error! :rf.error/ssr-ring-on-error-failed
                         {:exception (.getMessage on-error-t)
                          :ex-class  (.getName (class on-error-t))
                          :recovery  :fell-back-to-default-on-error})
      (default-on-error request t))))

(defn destroy-frame-quietly!
  "Best-effort per-request frame teardown. Exceptions during destroy must not
  mask a real handler error; swallow + emit a `:warning` trace is preferred
  over propagation.

  `frame-target` is the DESTROY target: the frame VALUE `make-frame` returned
  (carrying the EXACT incarnation token, so teardown is incarnation-EXACT and
  never reaps a same-id successor a request drain reseated), or a
  frame-id keyword (address-directed — it destroys whichever incarnation
  currently holds the id). The per-request handlers always pass the VALUE so
  teardown is exact. The bare-id form is for callers that genuinely own the id's
  current incarnation (SSR test teardown). It is NOT for
  the setup-FAILURE path: core construction is the exact-token owner of a failed
  incarnation's rollback, so an address-directed reap there could destroy a
  same-id successor. The optional
  `diag-frame-id` names the frame on the failure trace so the `:frame` slot stays
  a clean keyword even when a value is the destroy target; it defaults to
  `frame-target` for the keyword call shape.

  Surfaces any destroy-time throw on the trace bus rather than
  silently swallowing it — keeps the error visible to dev tooling
  without escalating to a user-visible 500 (the handler-side error has
  already been materialised by the time this fn runs)."
  ([frame-target] (destroy-frame-quietly! frame-target frame-target))
  ([frame-target diag-frame-id]
   (try
     (rf/destroy-frame! frame-target)
     (catch Throwable t
       (rf.trace/emit! :warning :rf.ssr/destroy-frame-failed
                    {:frame    diag-frame-id
                     :reason   (or (.getMessage t) (.getName (class t)))
                     :ex-class (.getName (class t))
                     :recovery :warned-and-skipped})
       nil))))

(defn resolve-root-view
  "Resolve the caller's `:root-view` opt to a hiccup vector. Accepts
  either a hiccup vector directly OR a 0-arity fn that returns hiccup,
  resolved once per request.

  The hiccup vector's HEAD must be a callable — the Var `reg-view` defs,
  or `(rf/view :id)`. A keyword head is an HTML element on every host,
  never a view (Conventions §Render-tree shape vs runtime
  lookup), so `:root-view [:app/root]` renders an empty `<root>` element
  rather than the application. Prefer the 0-arity fn form when the opts
  map is built before the view is registered, since `(rf/view :id)`
  resolves where it is written. `[(rf/view :app/root)]` and
  `(fn [] ((rf/view :app/root)))` emit byte-identical HTML."
  [root-view]
  (cond
    (vector? root-view) root-view
    (fn?     root-view) (root-view)
    :else
    (rf.error/throw-error!
      :rf.error/invalid-root-view
      'rf.ssr/ssr-handler
      (str "root-view must be a hiccup vector or a 0-arity fn returning "
           "hiccup; pass one of those as :root-view.")
      {:recovery :supply-a-hiccup-vector-or-0-arity-fn
       :extra    {:received root-view}})))

(defn resolve-head
  "Resolve the active route's `:head` against `frame-id` (or the default
  head when the route doesn't declare one). Returns a map:

    {:head-html  \"<title>…</title><meta …>…\"   ;; inner-head fragment
     :html-attrs {…} or nil                       ;; stamped on <html>
     :body-attrs {…} or nil}                      ;; stamped on <body>

  The two attribute bags ride alongside the rendered fragment because
  `head-model->html` deliberately drops them (Spec 011 §Default flow
  step 4: `:html-attrs` populate `<html>`; `:body-attrs` populate
  `<body>` — the host shell stamps them, not the head emitter).

  Exceptions during resolution degrade gracefully — empty fragment,
  no attrs — so a buggy head fn can't take down the request. The trace
  surface and always-on emitter carry `:rf.error/ssr-head-resolution-failed`.
  This category is deliberately non-projecting: losing metadata degrades to an
  empty head while the usable body remains a 200."
  [frame-id]
  (try
    (let [model (rf.ssr/head-model frame-id)]
      {:head-html  (rf.ssr/head-model->html model)
       :html-attrs (:html-attrs model)
       :body-attrs (:body-attrs model)})
    (catch Throwable t
      (rf.trace/emit-error! :rf.error/ssr-head-resolution-failed
                         {:frame     frame-id
                          :exception t
                          :recovery  :no-recovery})
      ;; Always-on but non-projecting: observability must not turn a degraded
      ;; empty head into an error response.
      (emit-always-on-error!
        {:rf.error/id :rf.error/ssr-head-resolution-failed
         :frame     frame-id
         :time      (rf.interop/now-ms)
         :exception t
         :recovery  :no-recovery})
      {:head-html "" :html-attrs nil :body-attrs nil})))

(defn resolve-initial-events!
  "Resolve the `:initial-events` of the handler's `:frame` to the setup vector
  (an ordered vector of events) dispatched at per-request frame creation,
  given the live Ring `request`. Two accepted forms:

    1. an `:initial-events` VECTOR — passed through verbatim (lowered into
       the per-request `make-frame`'s `:initial-events`).
    2. a `(fn [request] -> initial-events-vector)` — a 1-arity fn DERIVING
       the setup vector from the Ring request. Called EXACTLY ONCE here,
       before `make-frame`, with the request the host has already populated
       into the per-request slot. The result MUST itself be an
       `:initial-events` vector, validated the same way.

  The fn form is the replay-safe way to fold a request-derived fact into
  a boot event's PAYLOAD — the recordable causal boundary (Spec 011
  §Request storage substrate, the durable request-derived-fact pattern):

      :initial-events (fn [req] [[:auth/server-init {:user (extract-user req)}]])

  The caller owns the derived event shape. Use the ambient
  `:rf.server/request` coeffect for non-durable request reads.

  Invalid values and function results surface as
  `:rf.error/invalid-initial-events` inside per-request setup."
  [initial-events request]
  (cond
    (vector? initial-events)
    initial-events

    (fn? initial-events)
    (let [derived (initial-events request)]
      (if (vector? derived)
        derived
        (rf.error/throw-error!
          :rf.error/invalid-initial-events
          'rf.ssr/ssr-handler
          (str ":initial-events fn must return an :initial-events vector; the "
               "(fn [request] ...) form derives the setup vector from the Ring "
               "request and its result must be a vector.")
          {:recovery :return-an-initial-events-vector-from-the-fn
           :extra    {:returned derived}})))

    :else
    (rf.error/throw-error!
      :rf.error/invalid-initial-events
      'rf.ssr/ssr-handler
      (str ":initial-events must be a vector OR a (fn [request] initial-events-vector); "
           "pass one of those as :initial-events inside :frame.")
      {:recovery :supply-a-vector-or-a-fn-of-the-request
       :extra    {:received initial-events}})))

(def ^:private frame-config-opts
  "Frame-config keys a handler takes inside `:frame`, refused at the
  handler's top level so a config split across the two places cannot be
  half-read."
  [:initial-events :fx-overrides :ssr :url-strategy])

(def ^:private handler-owned-frame-keys
  "Frame-config keys the handler sets on every request frame itself: `:id`
  is the fresh per-request id the request slot is keyed by, and `:platform`
  is `:server`. A `:frame` carrying either is refused rather than obeyed or
  silently overridden."
  [:id :platform])

(defn validate-frame-opt!
  "Throw `:rf.error/ssr-ring-frame-opt-invalid` unless the handler's frame
  config is one `:frame` map: no frame-config key at the handler's top level
  (`:recovery :move-the-frame-config-into-frame`), a `:frame` that is a map
  (`:supply-a-frame-config-map`), and neither `:id` nor `:platform` inside it
  (`:remove-the-handler-owned-key`). Returns `opts` unchanged."
  [{:keys [frame] :as opts}]
  (when-some [misplaced (seq (filter #(contains? opts %) frame-config-opts))]
    (rf.error/throw-error!
      :rf.error/ssr-ring-frame-opt-invalid
      'rf.ssr.ring/ssr-handler
      (str "ssr-handler takes the app's frame config as one :frame map; move "
           (pr-str (vec misplaced)) " inside :frame, e.g. "
           "{:frame {:initial-events [[:app/server-init]]}}.")
      {:recovery :move-the-frame-config-into-frame
       :extra    {:keys (vec misplaced)}}))
  (when-not (or (nil? frame) (map? frame))
    (rf.error/throw-error!
      :rf.error/ssr-ring-frame-opt-invalid
      'rf.ssr.ring/ssr-handler
      "ssr-handler's :frame is the app's frame config, a map; pass one as :frame."
      {:recovery :supply-a-frame-config-map
       :extra    {:received (rf.error/diag-value-summary frame)}}))
  (when-some [owned (seq (filter #(contains? frame %) handler-owned-frame-keys))]
    (rf.error/throw-error!
      :rf.error/ssr-ring-frame-opt-invalid
      'rf.ssr.ring/ssr-handler
      (str "ssr-handler owns the request frame's :id and :platform; remove "
           (pr-str (vec owned)) " from :frame.")
      {:recovery :remove-the-handler-owned-key
       :extra    {:keys (vec owned)}}))
  opts)

(defn validate-required-opts!
  "Throw a structured `:rf.error/ssr-ring-missing-*` ex-info when a
  caller omits a required handler opt. `:initial-events` inside `:frame` is
  always required; `:root-view` is required iff no `:renderer` is supplied —
  only the default JVM-local renderer reads it, so with a custom renderer
  it is optional and ignored.

  The handler fails at construction rather than on the first request.
  Returns `opts` unchanged."
  [{:keys [frame root-view renderer] :as opts}]
  (when-not (:initial-events frame)
    (rf.error/throw-error!
      :rf.error/ssr-ring-missing-initial-events
      'rf.ssr/ssr-handler
      (str "ssr-handler requires :initial-events inside :frame (a vector of events, "
           "or a (fn [request] events)); supply {:frame {:initial-events [...]}}.")
      {:recovery :supply-the-initial-events-opt}))
  (when-not (or root-view renderer)
    (rf.error/throw-error!
      :rf.error/ssr-ring-missing-root-view
      'rf.ssr/ssr-handler
      (str "ssr-handler requires :root-view (a hiccup vector or 0-arity fn) "
           "unless a custom :renderer is supplied; supply :root-view in the "
           "handler opts.")
      {:recovery :supply-the-root-view-opt}))
  opts)

(defn validate-construction-opts!
  "Run the full fail-closed-at-boot validation for
  `re-frame.ssr.ring/ssr-handler`. A misconfigured handler
  refuses to construct rather than failing per request. The checks are:

    0. the frame config is one `:frame` map, without the `:id` and
       `:platform` the handler owns, via `validate-frame-opt!`.
    1. required-opt presence (`:initial-events` inside `:frame`,
       `:root-view`) via `validate-required-opts!`.
    2. hydration-payload policy (the single `:payload` opt — an allowlist
       of keys and paths, or the whole-app-db keyword) via
       `payload-policy/validate-policy-opts!` — throws
       `:rf.error/ssr-missing-payload-policy` (or
       `:rf.error/ssr-unknown-payload-policy` on an unknown policy, or
       `:rf.error/ssr-malformed-payload-allowlist` on a malformed entry or
       a `:payload-include-sensitive` opt).
    3. trusted-shell-hook shape (`:head` / `:body-end` / `:script-src` /
       `:app-element-id` are strings or nil; `:script-src` may also be
       `false`) via
       `trust/validate-trusted-shell-opts!` — the shell routes these
       into the HTML envelope (`:head` / `:body-end` as raw content
       hooks, `:script-src` / `:app-element-id` as escaped attribute
       hooks), so a structural mistake (map / vector / symbol / number)
       surfaces here as `:rf.error/ssr-trusted-shell-opt-invalid` at
       boot rather than as a `ClassCastException` deep in the rendering
       path.

  Returns `opts` unchanged on success — composes into a `let` /
  threading position cleanly."
  [opts]
  (validate-frame-opt! opts)
  (validate-required-opts! opts)
  (rf.ssr.payload-policy/validate-policy-opts! opts)
  (rf.ssr.ring.trust/validate-trusted-shell-opts! opts))

(defn resolve-on-error
  "Resolve the effective `:on-error` Ring-fn from `raw-opts`, the one
  place the precedence lives. Precedence:

    1. caller-supplied `:on-error` — full Ring-fn override; used verbatim.
    2. absent                      — host-locked `default-on-error`
                                     (\"Internal error\" plaintext).

  A caller wanting a branded transport-failure body writes an explicit
  leak-safe `:on-error` Ring fn that returns a fixed response and
  ignores the throwable — the `default-on-error` shape, caller-owned."
  [{:keys [on-error]}]
  (or on-error default-on-error))
