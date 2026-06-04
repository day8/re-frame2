(ns re-frame.ssr.ring.lifecycle
  "Per-request frame lifecycle helpers for the Ring host adapter.

  Pure helpers used by the request pipeline:

    - `destroy-frame-quietly!` — best-effort frame teardown
    - `resolve-root-view`      — hiccup-vec OR 0-arity fn → hiccup
    - `resolve-head`           — active route's `:head` → map carrying the
                                 rendered fragment plus `:html-attrs` /
                                 `:body-attrs` bags for the host shell

  Plus the two construction-time contracts shared verbatim by BOTH
  `re-frame.ssr.ring/ssr-handler` AND
  `re-frame.ssr.ring.streaming/stream-handler`:

    - `validate-construction-opts!` — the fail-closed-at-boot triple
                                      (required opts + payload policy +
                                      trusted-shell shape)
    - `resolve-on-error`            — the rf2-c1tac `:on-error` /
                                      `:on-error-fallback` / locked-default
                                      precedence

  Both lived twice (once in the `ring` façade, once inlined in
  `streaming`) before — colocating them here, alongside the
  `validate-required-opts!` / `make-default-on-error` / `default-on-error`
  pieces they already compose, keeps the boot contract single-sourced.
  Same sibling-placement rationale as `validate-required-opts!`:
  `streaming` and the `ring` façade both already require `lifecycle`,
  so hosting the shared checks here avoids any circular-require between
  the streaming sub-namespace and the façade.

  Per Spec 011 §Request storage substrate + §Head/meta contract.

  Note (audit rf2-cegm7 A2 / rf2-j54ee): the prior `on-create-with-request`
  helper conj'd the Ring request map onto the caller's `:on-create`
  event vector as a fallback for handlers that pre-dated the
  `:rf.server/request` cofx. The conj path is gone — Spec 011 names the
  cofx as the canonical read surface; the positional-arg variant had a
  subtle pitfall (a 1-vector `[:rf/server-init]` silently became
  `[:rf/server-init {ring-request...}]` after the conj, so a handler that
  forgot to destructure ended up with the request riding the unused-arg
  slot). Pre-alpha: one canonical surface, no fallback."
  (:require [re-frame.core :as rf]
            [re-frame.ssr.payload-policy :as payload-policy]
            [re-frame.ssr.ring.trust :as trust]
            [re-frame.trace :as trace]))

(set! *warn-on-reflection* true)

(def ^:const default-on-error-body
  "The literal body emitted by `default-on-error` when no template is
  supplied. Pinned to the rf2-kzvwq topology-leak-safe shape: generic
  plaintext, no throwable detail, no internal class names. The
  templating surface (`make-default-on-error`) accepts a caller-
  supplied body that overrides this; the locked default itself stays
  constant."
  "Internal error")

(def ^:const default-on-error-content-type
  "The Content-Type emitted by `default-on-error` when no template
  is supplied — plaintext UTF-8, matching the locked default body
  shape (rf2-kzvwq §P2.1)."
  "text/plain; charset=utf-8")

(defn make-default-on-error
  "Construct a `(fn [request throwable] ring-response)` fallback shaped
  like `default-on-error` but with a caller-templated body. Returns the
  fixed 500 status and the configured Content-Type + body unchanged on
  every call. Per rf2-c1tac — callers that want a branded plaintext-
  or-HTML error page can supply `:body` (and optionally
  `:content-type`) at handler-construction time without writing a full
  `:on-error` fn AND without inheriting the `.getMessage` topology-
  leak risk: the constructor never touches the throwable.

  Opts:

    :body          — (string) the literal response body. Defaults to
                     `default-on-error-body` (\"Internal error\").
                     Caller-supplied strings are emitted VERBATIM; the
                     caller is responsible for trusting the source (a
                     CMS-driven body is the caller's XSS exposure to
                     accept, same trust boundary as the four trusted
                     shell-hook opts).
    :content-type  — (string) the Content-Type header. Defaults to
                     `default-on-error-content-type` (\"text/plain;
                     charset=utf-8\"). Pass \"text/html; charset=utf-8\"
                     when the supplied `:body` is HTML.

  rf2-kzvwq §P2.1 contract preserved: the returned fn ignores the
  throwable. The `.getMessage` topology-leak surface stays closed
  regardless of the supplied template."
  ([] (make-default-on-error nil))
  ([{:keys [body content-type]}]
   (let [body*         (or body default-on-error-body)
         content-type* (or content-type default-on-error-content-type)
         response      {:status  500
                        :headers {"Content-Type" content-type*}
                        :body    body*}]
     (fn default-on-error-fn [_request _t] response))))

(def default-on-error
  "Minimal 500 response used when a handler caller doesn't supply
  `:on-error`. Shared by `ssr-handler` AND `stream-handler` so the
  topology-leak contract below lives in exactly one place.

  The SSR runtime's error projector handles trace-emitted errors during
  drain; this hook covers exceptions the projector can't see (Ring-layer
  throws, render-time CLJ exceptions, writer-thread-pre-spawn throws).

  rf2-kzvwq / security audit 2026-05-14 §P2.1 — the body MUST NOT leak
  the throwable's message. `.getMessage` carries internal topology that
  has no business reaching the wire: JDBC URLs (host, port, database
  name), file paths under deploy roots, partial SQL fragments, server-
  internal class names. We emit a fixed generic body matching the
  projector's `fallback-public-error` shape. Apps that want dev-mode
  detail override via `:on-error`; apps that want a branded HTML body
  without the .getMessage exposure pass `:on-error-fallback {:body
  \"…\" :content-type \"text/html; …\"}` (rf2-c1tac — the templating
  surface that builds this exact shape via `make-default-on-error`)."
  (make-default-on-error))

(defn safe-on-error
  "Invoke the resolved `:on-error` Ring-fn under containment and return
  its Ring response. Shared by `ssr-handler`, `stream-handler`, AND
  `setup-request-frame!` so EVERY `:on-error` call site is protected.

  rf2-ljjh0 — `:on-error` is the handler's last-resort transport-failure
  net (Ring-layer / render-time / setup-failure throws the projector
  can't see). A CALLER-supplied `:on-error` that itself throws must NOT
  escape the handler: an uncaught throwable handed to the Ring server
  (Jetty / http-kit) surfaces as a raw container 500 with a stack
  trace, defeating the rf2-kzvwq topology-leak contract the boundary
  otherwise upholds (`default-on-error` emits a fixed generic body,
  never the throwable). When the caller's `:on-error` throws, we surface
  the secondary throw on the trace bus and fall back to the host-locked
  `default-on-error` — mirroring the `resolve-error-body` pattern (a
  buggy `:error-view` must not bypass the error boundary either). The
  boundary is not bypassable by a bug in the caller's transport-failure
  handler, exactly as it is not bypassable by a bug in the caller's
  `:error-view`.

  `on-error` is the ALREADY-RESOLVED fn (rf2-c1tac precedence applied by
  `resolve-on-error` at construction time): the caller's `:on-error`,
  the `:on-error-fallback`-templated default, or `default-on-error`.
  When `on-error` IS `default-on-error` (or the templated default) it
  cannot throw — the guard is then a free no-op — so this is cheap on
  the common path."
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
  Per rf2-6t36h this MUST run exactly once per request — the fn-form
  branch is not guaranteed to be idempotent (unsorted-map iteration,
  gensym'd keys, time-of-day props can all vary between calls) and any
  variance between two invocations produces a hash mismatch on the wire
  vs the payload, firing a spurious `:rf.ssr/hydration-mismatch` on a
  perfectly successful hydration. Resolve once, thread the result."
  [root-view]
  (cond
    (vector? root-view) root-view
    (fn?     root-view) (root-view)
    :else
    (throw (ex-info ":rf.error/invalid-root-view"
                    {:rf.error/id :rf.error/invalid-root-view
                     :where    'rf.ssr/ssr-handler
                     :reason   "root-view must be a hiccup vector or a 0-arity fn"
                     :received root-view
                     :recovery :no-recovery}))))

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
  surface carries the throw via `:rf.error/ssr-head-resolution-failed`
  (per Spec 009 §Error event catalogue) so production observability
  stacks see the failing head fn even though the wire response continues.

  Per Spec 011 §Head/meta contract (rf2-4dra9, rf2-h2ujj) and
  rf2-bof8i (trace-emit on caught throw, Mike decision Option B over
  silent fallback — the always-on error-emit substrate per rf2-vnjfg /
  rf2-bacs4 carries the trace independent of the trace ring buffer's
  dev-only gating).

  Degrade-gracefully is the deliberate counterpoint to the view/sub
  FAIL-CLOSED posture (rf2-vvwmi / rf2-7d30s, Spec 011 §744/§748-751):
  a view or reactive sub that throws mid-render projects a non-200
  (the page is unusable); a head fn that throws ships a 200 with an
  empty `<head>` (only the metadata is missing — the body still
  renders + hydrates). To make the degraded-200 outcome ENFORCED rather
  than incidental to `ssr-handler`'s `get-response`-before-`resolve-head`
  call ordering, `:rf.error/ssr-head-resolution-failed` is registered as a
  recoverable-degradation category that the projection listeners skip by
  design (`re-frame.ssr.error-listener/non-projection-eligible-errors`,
  rf2-lia3i): the trace ships for observability but is never buffered for
  status projection, so no call-ordering change can flip the 200."
  [frame-id]
  (try
    (let [model (rf/active-head frame-id)]
      {:head-html  (rf/head-model->html model)
       :html-attrs (:html-attrs model)
       :body-attrs (:body-attrs model)})
    (catch Throwable t
      (trace/emit-error! :rf.error/ssr-head-resolution-failed
                         {:frame     frame-id
                          :exception t
                          :recovery  :no-recovery})
      {:head-html "" :html-attrs nil :body-attrs nil})))

(defn validate-on-create!
  "Validate the caller's :on-create event vector and return it verbatim.
  `:on-create` is required (per `validate-required-opts!`), so a
  non-vector here is a programmer error — surface it as an ex-info
  rather than letting `reg-frame` produce an obscure failure
  downstream. Audit rf2-cegm7 A3 / rf2-j54ee."
  [on-create]
  (if (vector? on-create)
    on-create
    (throw (ex-info ":rf.error/invalid-on-create"
                    {:rf.error/id :rf.error/invalid-on-create
                     :where    'rf.ssr/ssr-handler
                     :reason   ":on-create must be a vector (event)"
                     :received on-create
                     :recovery :no-recovery}))))

(defn validate-required-opts!
  "Throw a structured `:rf.error/ssr-ring-missing-*` ex-info when a
  caller omits a required handler opt (`:on-create` / `:root-view`).

  Shared by `re-frame.ssr.ring/ssr-handler` AND
  `re-frame.ssr.ring.streaming/stream-handler` so both fail closed at
  handler-construction time (boot) rather than at first request — the
  canonical fail-closed pattern (rf2-gtgf9, extended here to the two
  required opts). A streaming handler built without `:on-create` would
  otherwise fail per-request inside `setup-request-frame!`; one built
  without `:root-view` would fail inside the writer thread, truncating
  the chunked response. Both must refuse to construct, exactly as the
  non-streaming handler does. Returns `opts` unchanged on success.

  Sibling-validator placement (not in the `ring` façade): `streaming`
  already requires `lifecycle` and the `ring` façade does too, so
  hosting the check here avoids the circular-require between the
  streaming sub-namespace and the façade — same rationale as
  `trust`/`payload-policy`."
  [{:keys [on-create root-view] :as opts}]
  (when-not on-create
    (throw (ex-info ":rf.error/ssr-ring-missing-on-create"
                    {:rf.error/id :rf.error/ssr-ring-missing-on-create
                     :where    'rf.ssr/ssr-handler
                     :reason   "ssr-handler requires :on-create (an event vector)"
                     :recovery :no-recovery})))
  (when-not root-view
    (throw (ex-info ":rf.error/ssr-ring-missing-root-view"
                    {:rf.error/id :rf.error/ssr-ring-missing-root-view
                     :where    'rf.ssr/ssr-handler
                     :reason   "ssr-handler requires :root-view (a hiccup vector or 0-arity fn)"
                     :recovery :no-recovery})))
  opts)

(defn validate-construction-opts!
  "Run the full fail-closed-at-boot validation triple shared by
  `re-frame.ssr.ring/ssr-handler` AND
  `re-frame.ssr.ring.streaming/stream-handler`. A misconfigured handler
  MUST refuse to construct rather than fail per-request — the canonical
  fail-closed pattern (rf2-gtgf9). The three checks:

    1. required-opt presence (`:on-create` / `:root-view`) via
       `validate-required-opts!` — a streaming handler built without
       `:on-create` would otherwise fail per-request inside
       `setup-request-frame!`; one without `:root-view` would fail inside
       the writer thread, truncating the chunked response (rf2-ee38b.11).
    2. hydration-payload policy (the single `:payload` opt — vector
       allowlist or whole-app-db keyword) via
       `payload-policy/validate-policy-opts!` — throws
       `:rf.error/ssr-missing-payload-policy` (or
       `:rf.error/ssr-unknown-payload-policy` on a typo'd policy) per
       rf2-gtgf9 / rf2-pffil.
    3. trusted-shell-hook shape (`:head` / `:body-end` / `:script-src` /
       `:app-element-id` are strings or nil) via
       `trust/validate-trusted-shell-opts!` — both shells inject these
       RAW into the HTML envelope, so a structural mistake (map / vector
       / symbol / number) surfaces here as
       `:rf.error/ssr-trusted-shell-opt-invalid` at boot rather than as a
       `ClassCastException` deep in the rendering path (rf2-o6ndb).

  Returns `opts` unchanged on success — composes into a `let` /
  threading position cleanly."
  [opts]
  (validate-required-opts! opts)
  (payload-policy/validate-policy-opts! opts)
  (trust/validate-trusted-shell-opts! opts))

(defn resolve-on-error
  "Resolve the effective `:on-error` Ring-fn from `raw-opts`. Shared by
  `ssr-handler` AND `stream-handler` so the rf2-c1tac precedence lives
  in one place. Precedence:

    1. caller-supplied `:on-error`      — full Ring-fn override; used verbatim.
    2. caller-supplied `:on-error-fallback` — `{:body … :content-type …}`
       template; built into a default-shaped fn via
       `make-default-on-error`. The fn ignores the throwable (rf2-kzvwq
       no-leak contract preserved).
    3. neither                          — host-locked `default-on-error`
       (\"Internal error\" plaintext).

  Per rf2-c1tac — splits the templatable-default case from the full-
  override case so callers can swap the body string without writing a
  Ring fn AND without inheriting the `.getMessage` topology-leak risk."
  [{:keys [on-error on-error-fallback]}]
  (cond
    on-error          on-error
    on-error-fallback (make-default-on-error on-error-fallback)
    :else             default-on-error))
