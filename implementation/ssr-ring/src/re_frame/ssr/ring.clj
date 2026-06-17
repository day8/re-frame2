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
  enforced at the storage layer rather than via `:payload` allowlist
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
  `lifecycle` (frame teardown, root-view / head resolution, the
  construction-time validation triple `validate-construction-opts!`, the
  `:on-error` precedence `resolve-on-error`, and `default-on-error`),
  `pipeline` (the 4-step request pipeline + accumulator → Ring-map
  materialiser), `trust` (trusted-shell-hook contract), `streaming`
  (chunked-HTTP counterpart). `handler-defaults` lives here so the
  handler constructor reads top-down as one concept; the construction
  validation + on-error resolution it composes are shared verbatim with
  `stream-handler`, so they live in `lifecycle`.

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

  Streaming counterpart: `stream-handler` (re-exported below from
  `re-frame.ssr.ring.streaming`) is the chunked-HTTP sibling of
  `ssr-handler` — it flushes a shell on first byte and streams
  `:rf/suspense-boundary` subtrees as their data resolves (Spec 011
  §Streaming SSR; rf2-ojakd / rf2-olb64). `ssr-handler` itself is
  single-shot: it mirrors `render-to-string`.

  Out of scope (deferred / other beads):

    - Async Ring handler (3-arity) — synchronous-only in v1;
      extension is additive."
  (:require [re-frame.error :as error]
            [re-frame.frame :as frame]
            [re-frame.ssr :as ssr]
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
;; The façade re-exposes sub-namespace fns at `re-frame.ssr.ring/<name>`
;; so consumers see the same surface they did pre-split (rf2-pjsrc /
;; rf2-zkca8.1). A plain `(def alias other-ns/fn)` copies the VALUE but
;; NOT the var metadata, so REPL `doc`, editor hover, completion, and the
;; JVM-introspected api-manifest all saw EMPTY docs + nil arglists at the
;; very namespace users are told to require (rf2-l1qgjw issue 1). The
;; `import-fn` macro below re-points the alias AND copies the source var's
;; authored `:doc` / `:arglists` onto the façade var, so the canonical
;; function contract surfaces at the façade boundary. `:added` /
;; `:deprecated` ride along when present (forward-compatible); `:line` /
;; `:file` are deliberately NOT copied (they point at the implementation
;; ns, which is the correct source-navigation target — the façade docs
;; name the sub-namespace for deeper detail).

(defmacro ^:private import-fn
  "Re-export `src-sym` (a fully-qualified var symbol) at this namespace
  under the same name, preserving its `:doc` + `:arglists` (and `:added`
  / `:deprecated` when present). Unlike a bare `(def alias src)` — which
  drops all metadata — the façade var carries the source var's authored
  contract so REPL/editor/api-manifest tooling sees real docs + arglists
  at `re-frame.ssr.ring/<name>` (rf2-l1qgjw).

  The metadata is copied via `alter-meta!` AFTER the `def` (rather than
  attached to the def symbol literal) so the `:arglists` value — a list
  of param vectors — is treated as DATA, not evaluated as a form."
  [src-sym]
  (when-not (resolve src-sym)
    ;; Internal compile-time author error: a typo'd source symbol in an
    ;; `import-fn` re-export above. Routed through the canonical builder so
    ;; even this build-time exception carries `:rf.error/id` + `:reason`
    ;; (rf2-vvixub). It never reaches a runtime/app channel.
    (error/throw-error!
      :rf.error/ssr-ring-import-fn-unresolved
      'rf.ssr.ring/import-fn
      (str "import-fn cannot resolve the source var " src-sym
           "; check the fully-qualified symbol and that its namespace is "
           "required at the top of re-frame.ssr.ring.")
      {:recovery :correct-the-import-fn-source-symbol
       :extra    {:sym src-sym}}))
  (let [nm (symbol (name src-sym))]
    `(do
       (def ~nm ~src-sym)
       (alter-meta! (var ~nm) merge
                    (select-keys (meta (var ~src-sym))
                                 [:doc :arglists :added :deprecated]))
       (var ~nm))))

(import-fn cookie/cookie->set-cookie-header)
(import-fn shell/default-html-shell)

;; Streaming SSR surface (rf2-ojakd / rf2-olb64 (a)) — chunked-HTTP
;; counterpart of `ssr-handler`. Per Spec 011 §Streaming SSR.
(import-fn streaming/stream-handler)
(import-fn streaming/default-streaming-prefix)
(import-fn streaming/default-streaming-suffix)

;; ---- handler defaults + re-exported construction helpers ------------------
;;
;; Co-located with the handler constructor so the file reads top-down as
;; one concept. `default-on-error` is re-exported from `lifecycle`
;; (shared with `stream-handler` so the rf2-kzvwq topology-leak contract
;; lives in one place). The construction-time validation triple
;; (`validate-construction-opts!`) and the `:on-error` precedence
;; (`resolve-on-error`) also live in `lifecycle` — shared verbatim with
;; `stream-handler` so the fail-closed-at-boot + on-error contracts are
;; single-sourced.

;; `default-on-error` is a DATA var (a 2-arity fn VALUE held in a `def`,
;; not a `defn`), so it carries no `:arglists`. Re-export it copying the
;; authored `:doc` so REPL `doc` works at the façade, but it is correctly
;; excluded from the fn-arglists api-manifest check (data var, like
;; `handler-defaults`).
(def default-on-error lifecycle/default-on-error)
(alter-meta! #'default-on-error assoc
             :doc (-> #'lifecycle/default-on-error meta :doc))

(def handler-defaults
  "Default `ssr-handler` opts merged under caller-supplied opts at
  construction time (`:emit-hash?`, `:html-shell`, `:content-type`).
  A DATA var, not a fn — exposed so callers can read or extend the
  baseline. `:on-error` is deliberately NOT here (it is resolved
  separately via `lifecycle/resolve-on-error` so the defaults stay
  orthogonal to on-error precedence)."
  {:emit-hash?   true
   :html-shell   shell/default-html-shell
   :content-type "text/html; charset=utf-8"})

;; ---- ssr-handler ----------------------------------------------------------

(defn ssr-handler
  "Return a Ring-shaped (synchronous) handler that renders one
  re-frame2 SSR request per call.

  Required opts:

    :on-create   — the event dispatched at frame creation, in EITHER form:
                     • an event VECTOR (e.g. `[:rf/server-init]`), OR
                     • a `(fn [request] event-vector)` deriving the event
                       from the Ring request (rf2-kzns7l). The fn is called
                       once per request, before the drain; its result must
                       be an event vector. Use it to fold a request-derived
                       fact into the boot event's PAYLOAD — the replay-safe
                       recordable boundary, e.g.
                       `(fn [req] [:auth/server-init {:user (extract-user req)}])`
                       (Spec 011 §Request storage substrate, durable
                       request-derived-fact pattern).
                   For NON-durable request reads inside a handler, declare
                   `:rf.cofx/requires [:rf.server/request]` on the
                   registration (EP-0017 — `inject-cofx` is removed) — Spec
                   011 §Request storage substrate (rf2-afxhv) names that cofx
                   as the canonical ambient read surface.
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
    :payload        — Hydration-payload policy — REQUIRED, fail-closed
                      (rf2-gtgf9; single-opt consolidation rf2-pffil).
                      One opt, two shapes:
                        • a non-empty VECTOR of top-level app-db keys —
                          an allowlist (recommended). Only the listed
                          keys ship in the payload's `:rf/app-db`; every
                          other key is dropped, including keys added
                          later as the app evolves.
                        • the KEYWORD `:rf.ssr.payload/whole-app-db` —
                          opts into shipping the whole `app-db`. Use only
                          when the app's `app-db` is structurally safe to
                          expose end-to-end.
                      Absence throws `:rf.error/ssr-missing-payload-policy`
                      at handler-construction time; an unrecognised
                      keyword throws `:rf.error/ssr-unknown-payload-policy`.
                      The single opt removes the prior surface's
                      precedence/silent-ignore ambiguity — one value holds
                      exactly one policy; the allowlist-vs-whole choice is
                      the value's shape, not a contest between two opts.
    :html-shell     — (body-html payload-edn opts) → string. Defaults
                      to `default-html-shell`. Replace to inject custom
                      <head>, scripts, JSON-LD, etc.
    :content-type   — Content-Type header for HTML responses. Default
                      \"text/html; charset=utf-8\" (matches the SSR
                      runtime's default in the response accumulator).

  ---- :on-error vs :error-view — the error-handling division (rf2-s2ndr) ----

  TWO error opts handle TWO different failures. They are NOT alternatives;
  a robust deployment usually wires BOTH. Pick by asking \"which failure
  am I handling?\":

  | Question                          | :error-view             | :on-error                        |
  |-----------------------------------|-------------------------|----------------------------------|
  | WHICH failure?                    | An exception INSIDE the | A transport / Ring-layer failure |
  |                                   | re-frame drain or the   | the projector CANNOT see —       |
  |                                   | render walk — something | per-request frame setup throw,   |
  |                                   | the error PROJECTOR     | a render-time CLJ exception,     |
  |                                   | catches.                | a header/cookie materialise      |
  |                                   |                         | throw, a thrown `:on-create`.    |
  | WHAT does it produce?             | The PROJECTED ERROR     | A raw Ring response map          |
  |                                   | PAGE body (hiccup) — a  | `{:status … :headers … :body …}` |
  |                                   | view keyword or         | returned verbatim to the server. |
  |                                   | `(public-error)→hiccup` |                                  |
  |                                   | fn, rendered through    |                                  |
  |                                   | the SSR emitter.        |                                  |
  | WHAT is its INPUT?                | The PUBLIC-ERROR map    | The raw `(request throwable)` —  |
  |                                   | (sanitised by the       | the UNSANITISED throwable. The   |
  |                                   | projector — safe to     | locked default NEVER reads it    |
  |                                   | render).                | (rf2-kzvwq `.getMessage` leak).  |
  | WHICH HTTP path?                  | Normal response with    | Last-resort net OUTSIDE the      |
  |                                   | the projector's status  | normal pipeline (the projector   |
  |                                   | (typically 500) + the   | never ran / can't run).          |
  |                                   | rendered page.          |                                  |
  | DEFAULT when omitted?             | Minimal default error   | Minimal locked 500 (\"Internal   |
  |                                   | template.               | error\", topology-leak-safe).    |

  Mnemonic: `:error-view` is the PROJECTED page (drain-time, sanitised,
  hiccup); `:on-error` is the TRANSPORT net (Ring-layer, raw throwable,
  outside the projector). A buggy `:error-view` falls back to the default
  error template; a buggy `:on-error` falls back to `default-on-error`
  (`safe-on-error`, rf2-ljjh0) — NEITHER bypasses the error boundary.

    :on-error       — (request throwable) → ring-response. The TRANSPORT/
                      Ring-layer error net (see table above). Called when
                      the per-request frame setup OR a Ring-layer /
                      transport failure the projector can't see throws.
                      Defaults to a minimal 500 response. NOTE: normal
                      projected render/drain errors do NOT reach this
                      hook — they flow through the error projector and
                      render `:error-view` / the default error template
                      (see below). `:on-error` is the last-resort
                      transport-failure net. A caller wanting a branded
                      transport-failure body writes an explicit leak-safe
                      `:on-error` fn that returns a fixed response and
                      ignores the throwable (the `default-on-error`
                      shape, caller-owned). Use the `:error-view` opt
                      below for caller-registered hiccup-rendered error
                      pages on the projected (drain-time) path.
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
      (ssr-ring/ssr-handler {:on-create  [:rf/server-init]
                             :root-view  [:app/root]
                             ;; REQUIRED, fail-closed (rf2-gtgf9). A vector
                             ;; is an allowlist of top-level app-db keys to
                             ;; ship; `:rf.ssr.payload/whole-app-db` opts into
                             ;; the whole db. Omit it and construction throws
                             ;; `:rf.error/ssr-missing-payload-policy`.
                             :payload    [:articles :session-user]
                             :html-shell ssr-ring-app/shell}))
    (jetty/run-jetty handler {:port 3000 :join? false})"
  [raw-opts]
  (lifecycle/validate-construction-opts! raw-opts)
  ;; Merge defaults once at construction time so the pipeline helpers
  ;; (`setup-request-frame!`, `build-full-response`) can destructure
  ;; without re-stating the `:or` map. Caller-supplied values win.
  ;;
  ;; `:on-error` resolution goes through `lifecycle/resolve-on-error`
  ;; (shared with `stream-handler`): the caller's `:on-error` fn or the
  ;; host-locked `default-on-error`. Resolution happens AFTER merge so
  ;; handler-defaults can stay orthogonal to on-error (no `:on-error`
  ;; slot in the defaults map).
  (let [opts        (-> (merge handler-defaults raw-opts)
                        (assoc :on-error (lifecycle/resolve-on-error raw-opts)))
        {:keys [on-error]} opts]
    (fn ring-handler [request]
      (let [{:keys [frame-id short-circuit]}
            (pipeline/setup-request-frame! opts request)]
        (if short-circuit
          short-circuit
          (try
            ;; rf2-nu5w48 / EP-0013 §Realm Conformance: bind the request
            ;; frame's OWN realm around the accumulator read + the redirect
            ;; short-circuit so the per-frame response side channel is
            ;; addressed by the `(realm, frame)` slot (`frame/frame-address`),
            ;; not the default realm's bare-id slot. `ssr/get-response` reads
            ;; the realm's accumulated status/headers/cookies/redirect, and the
            ;; redirect materialiser ships THAT realm's response. The non-error
            ;; render branch (`build-full-response*`) re-establishes the realm
            ;; binding around its own render walk (rf2-bzw8gd); the error path
            ;; binds it inside `project-render-throw->ring-response`. Both
            ;; no-op for a default-realm frame (byte-identical single-realm
            ;; path). The redirect branch does no view resolution, so it only
            ;; needs `call-with-realm` (the side-channel addressing dimension);
            ;; the registrar is bound by the branches that resolve views.
            (frame/call-with-realm (frame/frame-realm frame-id)
              (fn []
                (let [resp (ssr/get-response frame-id)]
                  (if (some? (:redirect resp))
                    ;; Redirect — short-circuit per Spec 011 §Redirect precedence.
                    (pipeline/ssr-response->ring-response resp nil)
                    (pipeline/build-full-response frame-id resp opts)))))
            (catch Throwable t
              ;; rf2-ljjh0 — `safe-on-error` contains a throwing
              ;; caller `:on-error`: a bug in the caller's transport-
              ;; failure handler falls back to the locked
              ;; `default-on-error` rather than escaping as a raw 500.
              (lifecycle/safe-on-error on-error request t))
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

    ;; `ssr-middleware` is CURRIED: `(ssr-middleware opts)` returns a
    ;; Ring middleware `(handler) → wrapped-handler`. Apply it to the
    ;; fallback handler, then compose normally. `:payload` is REQUIRED
    ;; (fail-closed, rf2-gtgf9) — the same allowlist-or-whole-db policy
    ;; `ssr-handler` enforces.
    (def app
      (-> default-handler
          ((ssr-ring/ssr-middleware
             {:on-create [:rf/server-init]
              :root-view [:app/root]
              :payload   [:articles :session-user]
              :match?    (fn [req] (= :get (:request-method req)))}))
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
