(ns re-frame.ssr.ring
  "Ring/Pedestal host adapter for re-frame2 SSR. Per Spec 011 §HTTP
  response contract and the rf2-ny6v7 host-adapter brief.

  Spec 011 §357–363 names the host adapter as the layer responsible for
  materialising the runtime's `:response` accumulator into the wire
  format its server framework expects. The SSR runtime never writes to
  a network socket directly; it owns the request lifecycle (frame
  create → drain → response read → frame destroy) and a structured
  response shape; the host adapter wires that shape to a real HTTP
  server.

  Privacy note (rf2-jbcmt): the response accumulator is **side-channel
  storage** — Spec 011 §Response storage substrate locks it in a
  framework-private atom keyed by frame-id, NOT in `app-db`. Consequence
  for this adapter: the hydration payload built from `app-db` cannot
  carry server-only response data (Set-Cookie auth tokens, internal
  `X-*` headers, redirect targets) by accident — the boundary is
  enforced at the storage layer rather than via `:payload-keys`
  filtering on every host endpoint. `build-payload` (in
  `re-frame.ssr.ring.payload`) ships the app-db slice exactly because
  the accumulator is structurally outside app-db.

  This namespace ships that adapter for Ring (https://github.com/ring-clojure).
  Ring is the canonical Clojure HTTP-server abstraction; the
  request/response maps documented at the Ring Concepts wiki page
  (`:status`, `:headers`, `:body` on responses; `:uri`,
  `:request-method`, `:headers`, etc. on requests) are the wire shape
  this adapter consumes and produces. Pedestal, HttpKit, Reitit-ring,
  and Jetty all accept Ring-shaped handlers, so a single adapter covers
  the bulk of the Clojure HTTP ecosystem.

  ---- file split (rf2-pjsrc, rf2-zkca8.1) ----

  Pre-split this namespace was 747 LoC carrying nine independent
  concerns enumerated by its own `;; ----` banners. After the rf2-pjsrc
  split it became a thin façade over eight flat sub-namespaces; the
  rf2-zkca8.1 recombine pass merged the two single-consumer-trivia
  leaves back here / into `pipeline`. Current sub-namespaces:

    - `re-frame.ssr.ring.cookie`           — RFC 6265 Set-Cookie wire
                                             serialisation.
    - `re-frame.ssr.ring.headers`          — pair-vec → Ring header-map
                                             collapse, multi-valued
                                             headers, content-type
                                             default.
    - `re-frame.ssr.ring.shell`            — `default-html-shell`.
    - `re-frame.ssr.ring.payload`          — `build-payload`,
                                             `resolve-version`.
    - `re-frame.ssr.ring.lifecycle`        — frame teardown, root-view /
                                             head resolution, on-create
                                             enrichment.
    - `re-frame.ssr.ring.pipeline`         — `setup-request-frame!` +
                                             `build-full-response` +
                                             `ssr-response->ring-response`
                                             (the 4-step request pipeline
                                             and its accumulator →
                                             Ring-map materialiser).

  `default-on-error` + `handler-defaults` + `validate-handler-opts!`
  live here (the pre-rf2-zkca8.1 `handler-defaults` sub-ns was 45 L
  and had `ring` as its only consumer; folding them in keeps the
  handler-constructor reading top-down as one concept).

  This façade re-exposes the public surface (`ssr-handler`,
  `ssr-middleware`, `cookie->set-cookie-header`, `default-html-shell`)
  and wires the sub-namespaces into the request lifecycle.

  Public surface:

    (ssr-handler opts)                     → ring-handler-fn
    (ssr-middleware opts)                  → ((handler) → wrapped-handler)
    (cookie->set-cookie-header cookie-map) → string
    (default-html-shell body-html payload-edn opts) → string

  Per-request flow inside `ssr-handler` (the four pipeline steps live
  in `re-frame.ssr.ring.pipeline`):

    1. `setup-request-frame!` — populate the per-frame request slot
       via `ssr/set-request!` BEFORE registering the frame so the
       synchronous `:on-create` drain can resolve `:rf.server/request`
       (Spec 011 §Request storage substrate).
    2. `ssr/get-response` — read the resolved response accumulator
       (flushes any pending error projection per Spec 011 §Server
       error projection).
    3. Branch on `:redirect` — emit a Ring response with status +
       Location header only, no body, no payload (Spec 011 §Redirect
       precedence). Otherwise `build-full-response` renders the
       `:root-view`, builds the hydration payload, wraps in the
       `:html-shell` envelope, and materialises structured cookies to
       Set-Cookie headers per RFC 6265.
    4. `destroy-frame!` in `finally` — the `:ssr/on-frame-destroyed`
       hook clears the per-frame request slot in the same step
       (rf2-fcj33).

  Out of scope (deferred / other beads):

    - Streaming SSR (rf2-olb64) — `render-to-string` is single-shot;
      this adapter mirrors that.
    - Async Ring handler (3-arity) — synchronous-only in v1;
      extension is additive."
  (:require [re-frame.ssr :as ssr]
            [re-frame.ssr.ring.cookie :as cookie]
            [re-frame.ssr.ring.lifecycle :as lifecycle]
            [re-frame.ssr.ring.pipeline :as pipeline]
            [re-frame.ssr.ring.shell :as shell]
            ;; rf2-ojakd / rf2-olb64 (a) — streaming SSR adapter. Loaded
            ;; eagerly so `stream-handler` resolves at the façade. The
            ;; streaming surface is the chunked-HTTP counterpart of
            ;; `ssr-handler`; non-streaming consumers don't pay any
            ;; per-request cost (the writer thread is only spawned on
            ;; a `stream-handler` call site).
            [re-frame.ssr.ring.streaming :as streaming]))

(set! *warn-on-reflection* true)

;; ---- public-surface re-exports --------------------------------------------
;;
;; `def`s expose the sub-namespace fns at `re-frame.ssr.ring/<name>` so
;; consumers see the same surface they did pre-split.

(def cookie->set-cookie-header cookie/cookie->set-cookie-header)
(def default-html-shell        shell/default-html-shell)

;; Streaming SSR surface (rf2-ojakd / rf2-olb64 (a)) — chunked-HTTP
;; counterpart of `ssr-handler`. Per Spec 011 §Streaming SSR.
(def stream-handler            streaming/stream-handler)
(def default-streaming-prefix  streaming/default-streaming-prefix)
(def default-streaming-suffix  streaming/default-streaming-suffix)

;; ---- handler defaults + caller-opt validation -----------------------------
;;
;; Pre-rf2-zkca8.1 these lived in `re-frame.ssr.ring.handler-defaults`
;; (45 L, one consumer — this ns). Recombined here so the handler
;; constructor reads top-down as one concept.

(defn default-on-error
  "Minimal 500 response used when the caller doesn't supply `:on-error`.
  The SSR runtime's error projector handles trace-emitted errors
  during drain; this hook covers exceptions the projector can't see
  (Ring-layer throws, render-time CLJ exceptions).

  rf2-kzvwq / security audit 2026-05-14 §P2.1 — the body MUST NOT leak
  the throwable's message. `.getMessage` carries internal topology that
  has no business reaching the wire: JDBC URLs (host, port, database
  name), file paths under deploy roots, partial SQL fragments, server-
  internal class names. Pre-fix, an attacker who could trigger any
  unhandled JVM exception would see e.g.
  `\"SSR error: Connection refused: jdbc:postgresql://internal-db.svc:5432/auth\"`
  on the public wire — direct topology disclosure.

  We now emit a fixed generic body matching the projector's
  `fallback-public-error` shape. Apps that want dev-mode detail
  override via `:on-error` (the recommended pattern is
  `(if dev? log-and-detail log-only-quietly)`)."
  [_request ^Throwable _t]
  {:status  500
   :headers {"Content-Type" "text/plain; charset=utf-8"}
   :body    "Internal error"})

(def handler-defaults
  {:emit-hash?   true
   :html-shell   shell/default-html-shell
   :content-type "text/html; charset=utf-8"
   :on-error     default-on-error})

(defn- validate-handler-opts!
  "Throw a structured `:rf.error/ssr-ring-missing-*` ex-info when a
  caller omits a required `ssr-handler` opt. Extracted from the
  handler body per audit rf2-asmj1 R3 / cluster rf2-sljs1 so the body
  of `ssr-handler` reads as the lifecycle wiring rather than a
  validation-then-wire two-step."
  [{:keys [on-create root-view]}]
  (when-not on-create
    (throw (ex-info ":rf.error/ssr-ring-missing-on-create"
                    {:reason "ssr-handler requires :on-create (an event vector)"})))
  (when-not root-view
    (throw (ex-info ":rf.error/ssr-ring-missing-root-view"
                    {:reason "ssr-handler requires :root-view (a hiccup vector or 0-arity fn)"}))))

;; ---- ssr-handler ----------------------------------------------------------

(defn ssr-handler
  "Return a Ring-shaped (synchronous) handler that renders one
  re-frame2 SSR request per call.

  Required opts:

    :on-create   — the event vector dispatched at frame creation. Read
                   the Ring request map from handlers via
                   `(rf/inject-cofx :rf.server/request)` — Spec 011 §Request
                   storage substrate (rf2-afxhv) names the cofx as the
                   canonical read surface.
    :root-view   — either a hiccup vector (e.g. `[:app/root]`) OR a
                   0-arity fn returning hiccup. Rendered against the
                   per-request frame after the drain settles.

  Optional opts:

    :fx-overrides   — per-frame `:fx-overrides` map, passed through
                      verbatim to `(rf/make-frame ...)`. Useful for
                      stubbing `:rf.http/managed` during tests.
    :ssr            — per-frame `:ssr` config map (e.g.
                      `{:dev-error-detail? true
                        :public-error-id   :myapp/projector}`).
    :emit-hash?     — embed `data-rf-render-hash` on the root element
                      (default true).
    :version        — hydration payload's `:rf/version` (default 1).
    :schema-digest  — hydration payload's `:rf/schema-digest`, when
                      the app participates in the digest check.
    :payload-keys   — coll of top-level app-db keys to ship in the
                      payload's `:rf/app-db`. Default: ship the whole
                      app-db. Use to slice large per-request state
                      down to what the client genuinely needs.
    :html-shell     — (body-html payload-edn opts) → string. Defaults
                      to `default-html-shell`. Replace to inject custom
                      <head>, scripts, JSON-LD, etc.
    :content-type   — Content-Type header for HTML responses. Default
                      \"text/html; charset=utf-8\" (matches the SSR
                      runtime's default in the response accumulator).
    :on-error       — (request throwable) → ring-response. Called when
                      the per-request frame setup OR render throws.
                      Defaults to a minimal 500 response. The SSR
                      runtime's error projector handles trace-emitted
                      errors during drain; this hook covers the
                      exceptions the projector can't see (Ring-layer
                      throws, render-time CLJ exceptions).

  Returns:

    (fn handler [ring-request] ring-response)

  Per-request lifecycle (see ns docstring for full detail):

    (ssr/set-request! frame-id request)            ;; before drain
      → reg-frame                   (drains :on-create synchronously;
                                     the `:rf.server/request` cofx
                                     reads from the populated slot)
        → read get-response          (flushes error projections)
        → branch on :redirect
        → render-to-string + payload
        → materialise to Ring map
      → finally: destroy-frame!     (the `:ssr/on-frame-destroyed`
                                     hook clears the request slot)

  Example:

    (require '[ring.adapter.jetty :as jetty]
             '[re-frame.core :as rf]
             '[re-frame.ssr.ring :as ssr-ring])

    (rf/init! (requiring-resolve 'ssr-ring-app/ssr-adapter))
    (def handler
      (ssr-ring/ssr-handler {:on-create [:rf/server-init]
                             :root-view [:app/root]
                             :html-shell ssr-ring-app/shell}))
    (jetty/run-jetty handler {:port 3000 :join? false})"
  [raw-opts]
  (validate-handler-opts! raw-opts)
  ;; Merge defaults once at construction time so the pipeline helpers
  ;; (`setup-request-frame!`, `build-full-response`) can destructure
  ;; without re-stating the `:or` map. Caller-supplied values win.
  (let [opts        (merge handler-defaults raw-opts)
        {:keys [on-error]} opts]
    (fn ring-handler [request]
      (let [{:keys [frame-id short-circuit]}
            (pipeline/setup-request-frame! opts request)]
        (if short-circuit
          short-circuit
          (try
            (let [resp (ssr/get-response frame-id)]
              (if (some? (:redirect resp))
                ;; Redirect — short-circuit per Spec 011 §Redirect precedence.
                (pipeline/ssr-response->ring-response resp nil)
                (pipeline/build-full-response frame-id resp opts)))
            (catch Throwable t
              (on-error request t))
            (finally
              ;; `destroy-frame!` invokes `:ssr/on-frame-destroyed`
              ;; (rf2-fcj33), which clears the per-frame request slot.
              (lifecycle/destroy-frame-quietly! frame-id))))))))

;; ---- ssr-middleware -------------------------------------------------------

(defn ssr-middleware
  "Return Ring middleware that delegates to `ssr-handler` for the
  requests its `:match?` predicate accepts, and to the wrapped handler
  otherwise.

  Useful when SSR is one of several handlers in a Ring stack — e.g.
  static-asset middleware in front, JSON-API routes alongside.

  Opts are `ssr-handler`'s opts plus:

    :match?  — (request) → boolean. When truthy, SSR renders. When
               falsy, the call falls through to the wrapped handler.
               Default: matches every GET request.

  Example:

    (def app
      (-> default-handler
          (ssr-ring/ssr-middleware
            {:on-create [:rf/server-init]
             :root-view [:app/root]
             :match? (fn [req] (= :get (:request-method req)))})
          wrap-static-assets))"
  [{:keys [match?] :as opts}]
  (let [match? (or match? (fn default-match? [req]
                            (= :get (:request-method req))))
        ssr   (ssr-handler (dissoc opts :match?))]
    (fn middleware [handler]
      (fn wrapped [request]
        (if (match? request)
          (ssr request)
          (handler request))))))
