(ns re-frame.ssr.request
  "Per-request request slot + the `:rf.server/request` cofx + the
  per-request frame-teardown hook. Per Spec 011 §Server-only `reg-cofx`
  for request context and §Per-request frame teardown contract (rf2-fcj33).

  The `:rf.server/request` cofx surfaces the active HTTP request map to
  event handlers via `(rf/inject-cofx :rf.server/request)`. Use cases:
  reading the URL inside `:rf/server-init`, pulling a session cookie in
  `:auth/check-session`, branching on `:request-method`, etc.

  The mechanism is a per-frame slot — NOT a single dynamic var — so two
  simultaneous per-request frames (the common SSR shape under concurrent
  load) carry independent request data without leaking into each other.
  Host adapters (rf2-ny6v7's Ring adapter; future Pedestal / raw-HTTP /
  edge-runtime adapters) populate the slot via `set-request!` before
  kicking off the drain and clear it via `clear-request!` after the
  response is built (typically inside `frame.cljc`'s `destroy-frame!`
  teardown — but adapters that re-use a long-lived frame can clear
  inline).

  Storage shape: `defonce` side-channel atom keyed by frame-id. This
  mirrors `pending-error-traces` rather than living in app-db because
  the request map is HOST-CONTROLLED INPUT (the host's wire-shape data —
  Ring request map, Pedestal context, etc.); the cofx surfaces it into
  the handler's `:coeffects` map, but it has no place in the
  application's serialisable app-db. Storing it outside app-db keeps it
  out of the hydration payload (`:rf/app-db` ships to the client) —
  server-side request data must never leak into the client's bootstrap
  state.

  The cofx is `:platforms #{:server}` so client-side dispatches that
  reference it silently no-op via `:rf.cofx/skipped-on-platform` (the
  standard cofx-gating contract per Spec 011 §634-642).

  The `reg-cofx` registration for `:rf.server/request` lives in the
  `re-frame.ssr` façade so a `(require 're-frame.ssr :reload)` after
  `(registrar/clear-all!)` re-installs it. This namespace exports the
  handler fn only.

  Per the rf2-gxgo7 split of re-frame.ssr."
  (:require [re-frame.late-bind :as late-bind]
            [re-frame.ssr.error-listener :as error-listener]
            [re-frame.ssr.response :as response]
            [re-frame.trace :as trace]))

(defonce
  ^{:doc "Per-frame storage for the active HTTP request. Keys are
  frame-ids; values are the host-supplied request map (Ring shape, or
  whatever the host adapter normalises to). Side-channel — not in
  app-db so the request never rides the hydration payload to the
  client. Host adapters populate via `set-request!` before drain and
  clear via `clear-request!` after response materialisation."}
  request-slots
  (atom {}))

(defn set-request!
  "Populate the per-frame request slot. Called by an SSR host adapter
  (rf2-ny6v7 ships the Ring adapter; future Pedestal / raw-HTTP / edge-
  runtime adapters follow the same contract) once per request, before
  kicking off the drain.

  The shape of `request` is host-defined: the Ring adapter passes the
  Ring request map (`:request-method`, `:uri`, `:headers`, `:cookies`,
  `:body`, `:query-string`, `:server-name`, `:scheme`, etc.); other
  adapters may pass their native context shape. The cofx surfaces
  whatever the adapter stored — the runtime never inspects the request.

  Returns `frame-id`."
  [frame-id request]
  (swap! request-slots assoc frame-id request)
  frame-id)

(defn get-request
  "Read the active request for `frame-id`. Returns nil when no host
  adapter has populated the slot (e.g. JVM tests that drive the drain
  directly without a host wrapper, or a client-side dispatch that
  injected the cofx — in that case the `:platforms` gate fires the
  `:rf.cofx/skipped-on-platform` trace before this fn is called).

  Public read surface — host adapters and tools may inspect the active
  request via this fn."
  [frame-id]
  (get @request-slots frame-id))

(defn clear-request!
  "Clear the per-frame request slot. Host adapters call this after
  building the wire response (typically as part of per-request frame
  teardown). Safe to call when no slot is populated.

  Returns `frame-id`."
  [frame-id]
  (swap! request-slots dissoc frame-id)
  frame-id)

;; ---- per-request frame teardown (rf2-fcj33) -------------------------------
;;
;; Per Spec 011 §Per-request frame teardown contract. The SSR runtime owns
;; three side-channel `defonce` atoms keyed by frame-id —
;; `pending-error-traces` (per-frame buffer of captured error trace events,
;; in `re-frame.ssr.error-listener`), `request-slots` (per-frame HTTP-request
;; map, here), and `response-slots` (per-frame HTTP response accumulator,
;; in `re-frame.ssr.response` — rf2-jbcmt moved this off `app-db`). All three
;; live outside app-db (see the rationale comments above each defonce) and
;; so are NOT cleared by the frame's app-db / sub-cache teardown in
;; `frame/destroy-frame!`.
;;
;; This fn is the cleanup hook. Wired into `frame/destroy-frame!` via the
;; `:ssr/on-frame-destroyed` late-bind key — `core` calls it from its
;; ordered teardown step list when the SSR artefact is on the classpath;
;; the hook resolves to nil and the destroy proceeds without it when the
;; SSR artefact is absent. Idempotent: tolerates a frame-id with no slot
;; in any of the three atoms.

(defn on-frame-destroyed!
  "Per Spec 011 §Per-request frame teardown contract (rf2-fcj33). Drop
  the per-frame entries in `pending-error-traces`, `request-slots`, and
  `response-slots` for `frame-id`. Called from `frame/destroy-frame!`
  via the `:ssr/on-frame-destroyed` late-bind hook. Idempotent — a
  second call against the same frame-id sees the atoms already cleared
  and does nothing.

  Per rf2-jbcmt the `response-slots` side-channel was added to plug the
  `:rf/response` hydration-payload leak / per-fx full-app-db swap; this
  hook releases that slot symmetrically with the request slot.

  Per rf2-4dra9 (Spec 011 §Head/meta contract), also invokes any
  registered `:ssr/head-on-frame-destroyed` hook so `re-frame.ssr.head`
  can release its per-frame head-snapshot bookkeeping. Hook lookup is
  late-bound so the call is a no-op when the head ns is absent.

  Head-cleanup throws are caught + surfaced on the trace bus rather
  than silently swallowed — mirrors the trace-on-catch pattern shipped
  in `ssr-ring/lifecycle/destroy-frame-quietly!` (audit rf2-cegm7 CQ-2 /
  rf2-j54ee). Vanishing destroy-time exceptions is exactly what the
  R6 cluster was fixing; keep the symmetry."
  [frame-id]
  (error-listener/clear-pending-error-traces! frame-id)
  (swap! request-slots dissoc frame-id)
  (response/clear-response! frame-id)
  (when-let [head-cleanup! (late-bind/get-fn :ssr/head-on-frame-destroyed)]
    (try (head-cleanup! frame-id)
         (catch #?(:clj Throwable :cljs :default) t
           (trace/emit! :warning :rf.ssr.head/cleanup-failed
                        {:frame    frame-id
                         :hook     :ssr/head-on-frame-destroyed
                         :reason   (or #?(:clj  (.getMessage ^Throwable t)
                                          :cljs (.-message t))
                                       (str t))
                         :ex-class #?(:clj  (.getName (class t))
                                      :cljs (.-name (type t)))
                         :recovery :warned-and-skipped})
           nil)))
  nil)

(defn request-cofx
  "Handler fn for `:rf.server/request`. 1-arity reads from the
  per-frame slot; 2-arity accepts an explicit value override (tests
  and conformance harnesses that drive the drain without a host
  adapter)."
  ([ctx]
   (let [frame-id (get-in ctx [:coeffects :frame])
         request  (get-request frame-id)]
     (assoc-in ctx [:coeffects :rf.server/request] request)))
  ([ctx request]
   (assoc-in ctx [:coeffects :rf.server/request] request)))
