(ns re-frame.ssr.ring.lifecycle
  "Per-request frame lifecycle and shared handler-construction contracts.

  Both Ring handlers use these helpers for required-option, payload-policy,
  trusted-shell, and error-fallback decisions. Request data is exposed through
  the frame-scoped `:rf.server/request` coeffect rather than being appended to
  initial event vectors. Per Spec 011 §Request storage and §Head/meta."
  (:require [re-frame.core :as rf]
            [re-frame.error :as error]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.ssr.payload-policy :as payload-policy]
            [re-frame.ssr.ring.trust :as trust]
            [re-frame.trace :as trace]))

(set! *warn-on-reflection* true)

;; ---- always-on error emission ---------------------------------------------
;;
;; The optional late-bind hook keeps production error shipping independent of
;; the development-gated trace surface.

(defn emit-always-on-error!
  "Fan a pre-built always-on SSR error record through the
  error-emit registry via the `:error-emit/dispatch-error-record` late-bind
  hook. `record` is the union shape `{:error <kw> :frame <id-or-nil> :time
  <ms> + flat category keys}`. No-op when the producer hasn't loaded.
  Returns nil."
  [record]
  (when-let [dispatch-error-record! (late-bind/get-fn :error-emit/dispatch-error-record)]
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
  `:on-error`. Shared by `ssr-handler` AND `stream-handler` so the
  topology-leak contract below lives in exactly one place.

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
      (trace/emit-error! :rf.error/ssr-ring-on-error-failed
                         {:exception (.getMessage on-error-t)
                          :ex-class  (.getName (class on-error-t))
                          :recovery  :fell-back-to-default-on-error})
      (default-on-error request t))))

(defn destroy-frame-quietly!
  "Best-effort frame teardown. Exceptions during destroy must not mask
  a real handler error; swallow + emit a `:warning` trace is preferred
  over propagation.

  Surfaces any destroy-time throw on the trace bus rather than
  silently swallowing it — keeps the error visible to dev tooling
  without escalating to a user-visible 500 (the handler-side error has
  already been materialised by the time this fn runs)."
  [frame-id]
  (try
    (rf/destroy-frame! frame-id)
    (catch Throwable t
      (trace/emit! :warning :rf.ssr/destroy-frame-failed
                   {:frame    frame-id
                    :reason   (or (.getMessage t) (.getName (class t)))
                    :ex-class (.getName (class t))
                    :recovery :warned-and-skipped})
      nil)))

(defn resolve-root-view
  "Resolve the caller's `:root-view` opt to a hiccup vector. Accepts
  either a hiccup vector directly OR a 0-arity fn that returns hiccup.
  Resolve once per request so a non-idempotent function cannot make rendered
  HTML and payload hashes describe different trees. Streaming re-resolves only
  after at least one continuation, when post-drain state needs a fresh hash."
  [root-view]
  (cond
    (vector? root-view) root-view
    (fn?     root-view) (root-view)
    :else
    (error/throw-error!
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
     :body-attrs {…} or nil                       ;; stamped on <body>
     :head-model {…} or nil}                      ;; raw reconstructible model

  The two attribute bags ride alongside the rendered fragment because
  `head-model->html` deliberately drops them (Spec 011 §Default flow
  step 4: `:html-attrs` populate `<html>`; `:body-attrs` populate
  `<body>` — the host shell stamps them, not the head emitter).

  `:head-model` is the raw `rf/active-head` value used by the separately
  client-reconstructible head hash; emitted HTML is not a stable shared input.

  Exceptions during resolution degrade gracefully — empty fragment,
  no attrs — so a buggy head fn can't take down the request. The trace
  surface and always-on emitter carry `:rf.error/ssr-head-resolution-failed`.
  This category is deliberately non-projecting: losing metadata degrades to an
  empty head while the usable body remains a 200."
  [frame-id]
  (try
    (let [model (rf/active-head frame-id)]
      {:head-html  (rf/head-model->html model)
       :html-attrs (:html-attrs model)
       :body-attrs (:body-attrs model)
       :head-model model})
    (catch Throwable t
      (trace/emit-error! :rf.error/ssr-head-resolution-failed
                         {:frame     frame-id
                          :exception t
                          :recovery  :no-recovery})
      ;; Always-on but non-projecting: observability must not turn a degraded
      ;; empty head into an error response.
      (emit-always-on-error!
        {:error     :rf.error/ssr-head-resolution-failed
         :frame     frame-id
         :time      (interop/now-ms)
         :exception t
         :recovery  :no-recovery})
      {:head-html "" :html-attrs nil :body-attrs nil :head-model nil})))

(defn render-document-hash
  "The canonical structural hash for the SSR **body** render tree — the
  wire hash carried as the root element's `data-rf-render-hash` marker and
  the payload's `:rf/render-hash` (Spec 011 §Hydration-mismatch detection).

  This channel is body-only because the client hashes the body tree returned
  by its render function. Head divergence uses the separate, reconstructible
  `:rf/head-hash` channel."
  [body-hiccup]
  (rf/render-tree-hash body-hiccup))

(defn render-head-hash
  "The canonical structural hash for the CLIENT-RECONSTRUCTIBLE head
  model — a SEPARATE channel from the body's `render-document-hash`
  and carried as the `<head>` element's
  `data-rf-head-hash` wire marker and the payload's optional
  `:rf/head-hash` key.

  `head-model` is the RAW model `resolve-head` attaches under
  `:head-model` — the map `rf/active-head` (or a registered `reg-head` fn)
  returns (`{:title :meta :link :script :json-ld :html-attrs
  :body-attrs}`), NOT the rendered `:head-html` string. Hashing the model
  (not emitted HTML) is what makes the channel client-reconstructible at
  all: the client calls the SAME `(rf/active-head frame-id)` against the
  just-hydrated app-db + the route slice carried in `:rf/runtime-db`
  (Spec 011 §Default flow step 5) and hashes the resulting model identically.

  Returns nil when `head-model` is nil — the explicit-`:head`-STRING
  request shape (the caller-supplied-string branch, and the
  degraded-empty-head fallback on a throwing head fn). In both cases the
  server knows the head is NOT client-reconstructible (an explicit string
  has no model to recompute; a degraded head's model was never produced),
  so the channel is OMITTED rather than shipping a hash the client could
  never match."
  [head-model]
  (when head-model
    (rf/render-tree-hash head-model)))

(defn resolve-initial-events!
  "Resolve the caller's `:initial-events` opt to the setup vector
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
        (error/throw-error!
          :rf.error/invalid-initial-events
          'rf.ssr/ssr-handler
          (str ":initial-events fn must return an :initial-events vector; the "
               "(fn [request] ...) form derives the setup vector from the Ring "
               "request and its result must be a vector.")
          {:recovery :return-an-initial-events-vector-from-the-fn
           :extra    {:returned derived}})))

    :else
    (error/throw-error!
      :rf.error/invalid-initial-events
      'rf.ssr/ssr-handler
      (str ":initial-events must be a vector OR a (fn [request] initial-events-vector); "
           "pass one of those as :initial-events.")
      {:recovery :supply-a-vector-or-a-fn-of-the-request
       :extra    {:received initial-events}})))

(defn validate-required-opts!
  "Throw a structured `:rf.error/ssr-ring-missing-*` ex-info when a
  caller omits a required handler opt (`:initial-events` / `:root-view`).

  Both handlers fail at construction rather than on the first request or,
  worse, inside an already-committed writer. Returns `opts` unchanged."
  [{:keys [initial-events root-view] :as opts}]
  (when-not initial-events
    (error/throw-error!
      :rf.error/ssr-ring-missing-initial-events
      'rf.ssr/ssr-handler
      "ssr-handler requires :initial-events (a vector of events); supply :initial-events in the handler opts."
      {:recovery :supply-the-initial-events-opt}))
  (when-not root-view
    (error/throw-error!
      :rf.error/ssr-ring-missing-root-view
      'rf.ssr/ssr-handler
      (str "ssr-handler requires :root-view (a hiccup vector or 0-arity fn); "
           "supply :root-view in the handler opts.")
      {:recovery :supply-the-root-view-opt}))
  opts)

(defn validate-construction-opts!
  "Run the full fail-closed-at-boot validation triple shared by
  `re-frame.ssr.ring/ssr-handler` and
  `re-frame.ssr.ring.streaming/stream-handler`. A misconfigured handler
  refuses to construct rather than failing per request. The checks are:

    1. required-opt presence (`:initial-events` / `:root-view`) via
       `validate-required-opts!`.
    2. hydration-payload policy (the single `:payload` opt — vector
       allowlist or whole-app-db keyword) via
       `payload-policy/validate-policy-opts!` — throws
       `:rf.error/ssr-missing-payload-policy` (or
       `:rf.error/ssr-unknown-payload-policy` on an unknown policy).
    3. trusted-shell-hook shape (`:head` / `:body-end` / `:script-src` /
       `:app-element-id` are strings or nil) via
       `trust/validate-trusted-shell-opts!` — both shells route these
       into the HTML envelope (`:head` / `:body-end` as raw content
       hooks, `:script-src` / `:app-element-id` as escaped attribute
       hooks), so a structural mistake (map / vector / symbol / number)
       surfaces here as `:rf.error/ssr-trusted-shell-opt-invalid` at
       boot rather than as a `ClassCastException` deep in the rendering
       path.

  Returns `opts` unchanged on success — composes into a `let` /
  threading position cleanly."
  [opts]
  (validate-required-opts! opts)
  (payload-policy/validate-policy-opts! opts)
  (trust/validate-trusted-shell-opts! opts))

(defn validate-streaming-opts!
  "Reject opts the streaming handler CANNOT honour, at handler-
  construction time (boot) rather than silently ignoring them per-request.
  Run by `re-frame.ssr.ring.streaming/stream-handler` AFTER the shared
  `validate-construction-opts!` triple.

  `:html-shell`: the non-streaming `ssr-handler` builds its
  response by calling a ONE-PIECE `:html-shell` fn `(body-html payload-edn
  opts) → string` — it has the full body + payload in hand before the
  envelope is composed, so a custom shell can wrap them arbitrarily. The
  streaming handler CANNOT use that contract: it flushes the envelope as
  TWO chunks straddling N continuation chunks — the prefix
  (`default-streaming-prefix`) on first byte, the suffix
  (`default-streaming-suffix`) after the continuations + final payload
  have drained (Spec 011 §Streaming SSR — the wire shape pins
  prefix → shell → continuations → payload → suffix). A one-piece
  `:html-shell` callback can never run after streaming has started, so the
  streaming path ALWAYS writes the split default envelope and a passed
  `:html-shell` is silently dropped.

  Silently accepting it would discard security and document configuration.
  `stream-handler` therefore refuses to construct when `:html-shell` is
  present (any non-nil value), pointing the caller at the streaming
  envelope surface (the split `default-streaming-prefix` /
  `default-streaming-suffix` plus the `:head` / `:body-end` /
  `:script-src` / `:app-element-id` trusted-shell hooks, which the
  streaming envelope DOES honour). A nil `:html-shell` passes (no
  override requested).

  Returns `opts` unchanged on success."
  [opts]
  (when (some? (:html-shell opts))
    (error/throw-error!
      :rf.error/ssr-streaming-unsupported-opt
      'rf.ssr/stream-handler
      (str "stream-handler does not support :html-shell — the "
           "streaming envelope is flushed as a SPLIT prefix/suffix "
           "straddling the continuation chunks (Spec 011 §Streaming "
           "SSR), so a one-piece (body-html payload-edn opts) → string "
           ":html-shell fn cannot be applied after streaming starts. "
           "Use the streaming envelope surface instead: the :head, "
           ":body-end, :script-src, and :app-element-id trusted-shell "
           "hooks (honoured by default-streaming-prefix / "
           "default-streaming-suffix), or build a non-streaming "
           "ssr-handler when a custom one-piece shell is required.")
      {:recovery :drop-html-shell-or-use-non-streaming-handler
       :extra    {:opt-key :html-shell
                  :got     (:html-shell opts)}}))
  opts)

(defn resolve-on-error
  "Resolve the effective `:on-error` Ring-fn from `raw-opts`. Shared by
  `ssr-handler` AND `stream-handler` so the precedence lives in one
  place. Precedence:

    1. caller-supplied `:on-error` — full Ring-fn override; used verbatim.
    2. absent                      — host-locked `default-on-error`
                                     (\"Internal error\" plaintext).

  A caller wanting a branded transport-failure body writes an explicit
  leak-safe `:on-error` Ring fn that returns a fixed response and
  ignores the throwable — the `default-on-error` shape, caller-owned."
  [{:keys [on-error]}]
  (or on-error default-on-error))
