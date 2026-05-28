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

  This namespace is a thin façade over flat sub-namespaces, one concern
  each (see the `:require` list): `cookie` (RFC 6265 Set-Cookie),
  `headers` (pair-vec → Ring header-map collapse + content-type
  default), `shell` (`default-html-shell` + shared envelope helpers),
  `payload` (`build-payload` — the non-streaming wrapper over the
  shared `re-frame.ssr.payload-policy` version-resolution + assembly),
  `lifecycle` (frame
  teardown, root-view / head resolution, required-opt validation,
  `default-on-error`), `pipeline` (the 4-step request pipeline +
  accumulator → Ring-map materialiser), `trust` (trusted-shell-hook
  contract), `streaming` (chunked-HTTP counterpart). `handler-defaults`
  + `validate-handler-opts!` live here so the handler constructor reads
  top-down as one concept.

  This façade re-exposes the public surface (`ssr-handler`,
  `ssr-middleware`, `stream-handler`, `cookie->set-cookie-header`,
  `default-html-shell`) and wires the sub-namespaces into the request
  lifecycle.

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
            [re-frame.ssr.payload-policy :as payload-policy]
            [re-frame.ssr.ring.cookie :as cookie]
            [re-frame.ssr.ring.lifecycle :as lifecycle]
            [re-frame.ssr.ring.pipeline :as pipeline]
            [re-frame.ssr.ring.shell :as shell]
            [re-frame.ssr.ring.trust :as trust]
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
;; constructor reads top-down as one concept. `default-on-error` is
;; re-exported from `lifecycle` (shared with `stream-handler` so the
;; rf2-kzvwq topology-leak contract lives in one place).

(def default-on-error lifecycle/default-on-error)
(def make-default-on-error lifecycle/make-default-on-error)

(def handler-defaults
  {:emit-hash?   true
   :html-shell   shell/default-html-shell
   :content-type "text/html; charset=utf-8"})

(defn- resolve-on-error
  "Resolve the effective `:on-error` from `raw-opts`. Precedence:

    1. caller-supplied `:on-error`     — full Ring-fn override; used verbatim.
    2. caller-supplied `:on-error-fallback` — `{:body … :content-type …}`
       template; built into a default-shaped fn via
       `make-default-on-error`. The fn ignores the throwable (rf2-kzvwq
       no-leak contract preserved).
    3. neither                          — host-locked `default-on-error`
       (`\"Internal error\"` plaintext).

  Per rf2-c1tac — splits the templatable-default case from the full-
  override case so callers can swap the body string without writing a
  Ring fn AND without inheriting the `.getMessage` topology-leak risk."
  [{:keys [on-error on-error-fallback]}]
  (cond
    on-error          on-error
    on-error-fallback (make-default-on-error on-error-fallback)
    :else             default-on-error))

;; ---- trusted-shell-hook contract (rf2-o6ndb) ------------------------------
;;
;; The four trusted-shell-hook opts (`:head`, `:body-end`, `:script-src`,
;; `:app-element-id`) are TRUSTED STRINGS — injected RAW into the
;; rendered HTML envelope, no escaping, no validation, no sandbox.
;; Naming, structural validation, and the structured-alternative
;; recommendation for untrusted-customization use cases live in
;; `re-frame.ssr.ring.trust` (sibling to `re-frame.ssr.payload-policy`).
;; See Spec 011 §Trusted shell hook contract for the full surface.

(defn- validate-handler-opts!
  "Throw a structured `:rf.error/ssr-ring-missing-*` ex-info when a
  caller omits a required `ssr-handler` opt. Extracted from the
  handler body per audit rf2-asmj1 R3 / cluster rf2-sljs1 so the body
  of `ssr-handler` reads as the lifecycle wiring rather than a
  validation-then-wire two-step.

  Per rf2-gtgf9 the hydration-payload policy is also validated here so
  misconfigured deployments fail at handler-construction time (boot)
  rather than at first request — the canonical fail-closed pattern.
  Delegates to `re-frame.ssr.payload-policy/validate-policy-opts!`,
  which throws `:rf.error/ssr-missing-payload-policy` (or
  `:rf.error/ssr-unknown-payload-policy` on a typo'd
  `:payload-policy`).

  Per rf2-o6ndb the four trusted-shell-hook opts (`:head`, `:body-end`,
  `:script-src`, `:app-element-id`) are structural-shape-checked — they
  are TRUSTED STRINGS injected RAW into the rendered HTML envelope, so
  a structural error (map / vector / symbol) surfaces here as
  `:rf.error/ssr-trusted-shell-opt-invalid`. The framework names the
  trust boundary; the content trust itself remains the caller's per
  Spec 011 §Trusted shell hook contract.

  The required-opt presence checks (`:on-create` / `:root-view`) and
  the policy / trusted-shell checks are shared with `stream-handler`
  (`lifecycle/validate-required-opts!` + `payload-policy` + `trust`) so
  both handlers fail closed at the same boundary."
  [opts]
  (lifecycle/validate-required-opts! opts)
  (payload-policy/validate-policy-opts! opts)
  (trust/validate-trusted-shell-opts! opts))

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
    :payload-keys   — Allowlist (recommended): a non-empty sequential
                      coll of top-level app-db keys to ship in the
                      payload's `:rf/app-db`. Other keys are dropped,
                      including any keys added later as the app evolves.
                      The recommended primary mechanism per the
                      explicit fail-closed policy contract (rf2-gtgf9).
    :payload-policy — Explicit policy keyword. The only currently-
                      recognised value is
                      `:rf.ssr.payload/whole-app-db`, which opts into
                      shipping the whole `app-db`. Use only when the
                      app's `app-db` is structurally safe to expose
                      end-to-end. Mutually exclusive with
                      `:payload-keys` (allowlist wins when both are
                      passed; that's not a contradiction since the
                      allowlist is a more-restrictive policy choice).
                      One of `:payload-keys` or `:payload-policy`
                      MUST be passed; absence of both throws
                      `:rf.error/ssr-missing-payload-policy` at
                      handler-construction time.
    :html-shell     — (body-html payload-edn opts) → string. Defaults
                      to `default-html-shell`. Replace to inject custom
                      <head>, scripts, JSON-LD, etc.
    :content-type   — Content-Type header for HTML responses. Default
                      \"text/html; charset=utf-8\" (matches the SSR
                      runtime's default in the response accumulator).
    :on-error       — (request throwable) → ring-response. Called when
                      the per-request frame setup OR a Ring-layer /
                      transport failure the projector can't see throws.
                      Defaults to a minimal 500 response. NOTE: normal
                      projected render/drain errors do NOT reach this
                      hook — they flow through the error projector and
                      render `:error-view` / the default error template
                      (see below). `:on-error` is the last-resort
                      transport-failure net.
    :on-error-fallback — (map) `{:body \"…\" :content-type \"…\"}` —
                      templating shortcut (rf2-c1tac) for callers who
                      want to swap the locked default body string
                      (\"Internal error\") for a branded plaintext-or-
                      HTML page WITHOUT writing a full `:on-error` fn.
                      The resulting fn ignores the throwable, so the
                      rf2-kzvwq `.getMessage` topology-leak surface
                      stays closed. Ignored when `:on-error` is also
                      supplied (the explicit fn wins). The caller-
                      supplied `:body` is the caller's trust boundary
                      — emitted RAW like the four trusted shell-hook
                      opts. Use the `:error-view` opt below for caller-
                      registered hiccup-rendered error pages on the
                      projected (drain-time) path.
    :error-view     — (optional) the projected-error page body (Spec 011
                      §Server error projection step 5). Either a
                      registered-view keyword (resolved as
                      `[error-view public-error]` — the view receives
                      the public-error map as its single prop) OR a
                      1-arity fn `(public-error) → hiccup`. Rendered
                      through the standard SSR emitter so the public-
                      error map flows through position-appropriate
                      escaping and the app's own styling. When absent,
                      the host emits a minimal default error template.
                      A buggy `:error-view` falls back to the default
                      template (the error boundary cannot be bypassed
                      by a bug in the caller's error page).

  Trusted shell-hook opts (per Spec 011 §Trusted shell hook contract,
  rf2-o6ndb) — four optional strings the default shell injects RAW
  into the rendered HTML envelope. The framework names them as
  TRUSTED-STRING surfaces; the trust call itself is the caller's.
  Structural-shape-checked at handler-construction time (non-string
  non-nil values throw `:rf.error/ssr-trusted-shell-opt-invalid`); the
  content is NOT escaped. Wiring any of these from untrusted input
  (CMS field, tenant-admin form, query-string parameter) accepts an
  arbitrary-script-injection XSS vector. Use the structured
  alternatives (`reg-head` for head fragments, `reg-view*` +
  `:rf.server/*` fx for body content) when the content originates
  upstream of the trust boundary.

    :head           — (string) verbatim HTML inside `<head>...</head>`.
                      Default: route-resolved head fragment.
    :body-end       — (string) verbatim HTML before `</body>` — the
                      escape hatch for analytics / third-party scripts.
                      Default: nil (omitted).
    :script-src     — (string) the client-side bootstrap script URL
                      written into `<script src=\"...\">`. Default:
                      \"/main.js\".
    :app-element-id — (string) the id of the `<div>` wrapping the
                      rendered body. Default: \"app\". The client-side
                      hydrator reads this element by id.

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
  ;;
  ;; rf2-c1tac — `:on-error` resolution moved through `resolve-on-error`:
  ;; the templatable `:on-error-fallback {:body … :content-type …}` opt
  ;; produces a default-shaped fn without forcing the caller to write a
  ;; Ring fn. Resolution happens AFTER merge so handler-defaults can stay
  ;; orthogonal to on-error (no `:on-error` slot in the defaults map).
  (let [opts        (-> (merge handler-defaults raw-opts)
                        (assoc :on-error (resolve-on-error raw-opts)))
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
