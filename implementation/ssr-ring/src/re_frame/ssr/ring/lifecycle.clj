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
    - `resolve-on-error`            — the `:on-error` / locked-default
                                      precedence

  Both lived twice (once in the `ring` façade, once inlined in
  `streaming`) before — colocating them here, alongside the
  `validate-required-opts!` / `default-on-error` pieces they already
  compose, keeps the boot contract single-sourced.
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
            [re-frame.error :as error]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.ssr.payload-policy :as payload-policy]
            [re-frame.ssr.ring.trust :as trust]
            [re-frame.trace :as trace]))

(set! *warn-on-reflection* true)

;; ---- always-on error-emit helper (EP-0008 rf2-hhutya) ---------------------
;;
;; The promoted SSR error categories ride the always-on error-emit axis
;; (surface #4) ALONGSIDE the dev-gated `trace/emit-error!`. The Ring host
;; adapter ships above core's require graph, so it reaches
;; `error-emit/dispatch-error-record!` through the published
;; `:error-emit/dispatch-error-record` late-bind hook. UNGATED — fires on a
;; `-Dre-frame.debug=false` JVM SSR host so off-box shippers see the
;; structured record the dev trace surface would have elided.

(defn emit-always-on-error!
  "Fan a PRE-BUILT always-on SSR error record out through the corpus-wide
  error-emit registry via the `:error-emit/dispatch-error-record` late-bind
  hook. `record` is the union shape `{:error <kw> :frame <id-or-nil> :time
  <ms> + flat category keys}`. No-op when the producer hasn't loaded.
  Returns nil."
  [record]
  (when-let [dispatch-error-record! (late-bind/get-fn :error-emit/dispatch-error-record)]
    (dispatch-error-record! record))
  nil)

(def ^:const default-on-error-body
  "The literal body emitted by `default-on-error`. Pinned to the
  rf2-kzvwq topology-leak-safe shape: generic plaintext, no throwable
  detail, no internal class names."
  "Internal error")

(def ^:const default-on-error-content-type
  "The Content-Type emitted by `default-on-error` — plaintext UTF-8,
  matching the locked default body shape (rf2-kzvwq §P2.1)."
  "text/plain; charset=utf-8")

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
  projector's `fallback-public-error` shape. The fn ignores the
  throwable so the topology-leak surface stays closed. Apps that want a
  branded transport-failure body — plaintext or HTML — supply an
  explicit leak-safe `:on-error` Ring fn that returns a fixed response
  and ignores the throwable."
  (let [response {:status  500
                  :headers {"Content-Type" default-on-error-content-type}
                  :body    default-on-error-body}]
    (fn default-on-error-fn [_request _t] response)))

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

  `on-error` is the ALREADY-RESOLVED fn (precedence applied by
  `resolve-on-error` at construction time): the caller's `:on-error` or
  `default-on-error`. When `on-error` IS `default-on-error` it cannot
  throw — the guard is then a free no-op — so this is cheap on the
  common path."
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
      ;; EP-0008 (rf2-hhutya): ALSO ride the always-on axis. This EXECUTES
      ;; the resolved Spec 011 §`resolve-head` emits …-failed Option-B
      ;; ruling ("the always-on error-emit substrate carries the trace to
      ;; user observability stacks") that the impl had drifted from (it
      ;; emitted only via the dev-gated trace bus). NON-PROJECTING: a head
      ;; fn that throws degrades to an empty `<head>` and ships a 200 — the
      ;; always-on `error-emit-projection-listener` skips this category
      ;; (`non-projection-eligible-error?`, rf2-lia3i), so promotion ships
      ;; the off-box record WITHOUT flipping the degraded-200 wire outcome.
      (emit-always-on-error!
        {:error     :rf.error/ssr-head-resolution-failed
         :frame     frame-id
         :time      (interop/now-ms)
         :exception t
         :recovery  :no-recovery})
      {:head-html "" :html-attrs nil :body-attrs nil})))

(defn render-document-hash
  "The canonical structural hash for the FULL SSR document state — body
  render tree PLUS the resolved head fragment (`:head-html`) and the
  `<html>`/`<body>` attribute bags (`:html-attrs` / `:body-attrs`).

  Per Spec 011 §Head/meta contract and §Mismatch detection — head: in v1
  the head rides the UNIFIED `:rf/render-hash` channel; the server-rendered
  HTML carries a single structural hash covering both head and body, so the
  bundled v1 hydration-mismatch detector cannot tell a head-only divergence
  from a body-only one but DOES detect either. Hashing only the body (the
  prior shape) silently accepted a head-only mismatch — a contract drift
  against §624-626/§648-650 (rf2-9fw2de).

  `head-bag` is the map returned by `resolve-head` (or the explicit-`:head`
  shape `{:head-html <string> :html-attrs nil :body-attrs nil}`). We wrap
  the body tree and the head fragment in a self-describing canonical vector
  and hand it to `render-tree-hash`:

    [:rf/ssr-document <body-hiccup> {:head-html  <string-or-nil>
                                     :html-attrs <map-or-nil>
                                     :body-attrs <map-or-nil>}]

  `render-tree-hash`'s canonical-EDN walk sorts map keys and prunes nil
  values (`hash.cljc`), so the wrapper is deterministic and byte-identical
  across JVM/CLJS — the same cross-runtime contract the body-only hash
  honoured. The `:rf/ssr-document` tag keeps the head channel structurally
  distinct from any body subtree that might happen to be a 3-element vector,
  so a body-only change and a head-only change can never collide."
  [body-hiccup {:keys [head-html html-attrs body-attrs]}]
  (rf/render-tree-hash
    [:rf/ssr-document body-hiccup
     {:head-html  head-html
      :html-attrs html-attrs
      :body-attrs body-attrs}]))

(defn resolve-on-create!
  "Resolve the caller's `:on-create` opt to the event vector dispatched at
  per-request frame creation, given the live Ring `request`. Two accepted
  forms (rf2-kzns7l):

    1. an event VECTOR — passed through verbatim (the original contract).
    2. a `(fn [request] event-vector)` — a 1-arity fn DERIVING the event
       vector from the Ring request. Called EXACTLY ONCE here, before
       `reg-frame`, with the request the host has already populated into
       the per-request slot. The result MUST itself be an event vector,
       validated the same way.

  The fn form is the replay-safe way to fold a request-derived fact into
  the boot event's PAYLOAD — the recordable causal boundary (Spec 011
  §Request storage substrate, the durable request-derived-fact pattern):

      :on-create (fn [req] [:auth/server-init {:user (extract-user req)}])

  This is purely ADDITIVE. It is NOT a revival of the removed
  `on-create-with-request` positional-conj helper (audit rf2-cegm7 A2 /
  rf2-j54ee): that silently appended the WHOLE request to the caller's
  vector (`[:rf/server-init]` → `[:rf/server-init {ring-request}]`),
  putting the request in the wrong arg slot. Here the caller OWNS the
  shape of the resulting event vector — only the extracted, sanitised
  fact rides it. The ambient `:rf.server/request` cofx remains the
  canonical surface for NON-durable request reads and is unaffected.

  `:on-create` is required (per `validate-required-opts!`), which a fn
  satisfies as a truthy value — so the form check happens here, inside
  the per-request setup try/catch. A value that is neither a vector nor a
  1-arity fn (or a fn whose result is not a vector) is a programmer
  error: surface it as `:rf.error/invalid-on-create` rather than letting
  `reg-frame` produce an obscure failure downstream."
  [on-create request]
  (cond
    (vector? on-create)
    on-create

    (fn? on-create)
    (let [derived (on-create request)]
      (if (vector? derived)
        derived
        (error/throw-error!
          :rf.error/invalid-on-create
          'rf.ssr/ssr-handler
          (str ":on-create fn must return an event vector; the (fn [request] ...) "
               "form derives the on-create event from the Ring request and its "
               "result must be a vector.")
          {:recovery :return-an-event-vector-from-the-on-create-fn
           :extra    {:returned derived}})))

    :else
    (error/throw-error!
      :rf.error/invalid-on-create
      'rf.ssr/ssr-handler
      (str ":on-create must be an event vector OR a (fn [request] event-vector); "
           "pass one of those as :on-create.")
      {:recovery :supply-an-event-vector-or-a-fn-of-the-request
       :extra    {:received on-create}})))

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
    (error/throw-error!
      :rf.error/ssr-ring-missing-on-create
      'rf.ssr/ssr-handler
      "ssr-handler requires :on-create (an event vector); supply :on-create in the handler opts."
      {:recovery :supply-the-on-create-opt}))
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
       `trust/validate-trusted-shell-opts!` — both shells route these
       into the HTML envelope (`:head` / `:body-end` as raw content
       hooks, `:script-src` / `:app-element-id` as escaped attribute
       hooks), so a structural mistake (map / vector / symbol / number)
       surfaces here as `:rf.error/ssr-trusted-shell-opt-invalid` at
       boot rather than as a `ClassCastException` deep in the rendering
       path (rf2-o6ndb).

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

  `:html-shell` (rf2-oq4m5). The non-streaming `ssr-handler` builds its
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

  Accepting it silently is a fail-OPEN API-contract gap: a custom shell
  commonly carries CSP nonces, asset URLs, analytics/script policy, root
  markup, or host-specific document structure — a production app switching
  from `ssr-handler` to `stream-handler` would lose all of it with no
  construction error, warning, or test signal. We fail CLOSED at boot
  instead: `stream-handler` refuses to construct when `:html-shell` is
  present (any non-nil value), pointing the caller at the streaming
  envelope surface (the split `default-streaming-prefix` /
  `default-streaming-suffix` plus the `:head` / `:body-end` /
  `:script-src` / `:app-element-id` trusted-shell hooks, which the
  streaming envelope DOES honour). A nil `:html-shell` passes (no
  override requested) so the shared `handler-defaults` map — which does
  NOT carry `:html-shell` for the streaming path — and an explicit
  `{:html-shell nil}` both construct cleanly.

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
