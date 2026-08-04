(ns re-frame.ssr.ring
  "Ring host adapter for re-frame2 SSR, per Spec 011.

  Each synchronous request receives an isolated server frame. The adapter
  stores the Ring request before the initial-event drain, reads the resolved
  response accumulator, renders or redirects, materialises Ring headers and
  cookies, and tears the frame down. Response metadata lives outside app-db,
  so it cannot enter the hydration payload through app-db projection.

  This façade exposes the non-streaming handler and middleware, the streaming
  handler, the default shell, and cookie serialization."
  (:require [re-frame.error :as error]
            [re-frame.ssr :as ssr]
            [re-frame.ssr.ring.cookie :as cookie]
            [re-frame.ssr.ring.lifecycle :as lifecycle]
            [re-frame.ssr.ring.pipeline :as pipeline]
            [re-frame.ssr.ring.shell :as shell]
            ;; Loaded eagerly for the façade; only stream-handler requests
            ;; create writer threads.
            [re-frame.ssr.ring.streaming :as streaming]))

(set! *warn-on-reflection* true)

;; ---- public-surface re-exports --------------------------------------------
;;
;; Bare defs lose source var metadata. Preserve public docs and arglists while
;; leaving source-navigation metadata at the implementation var.

(defmacro ^:private import-fn
  "Re-export `src-sym` (a fully-qualified var symbol) at this namespace
  under the same name, preserving its `:doc` + `:arglists` (and `:added`
  / `:deprecated` when present). Unlike a bare `(def alias src)` — which
  drops all metadata — the façade var carries the source var's authored
  contract for REPL, editor, and API-manifest tooling.

  The metadata is copied via `alter-meta!` AFTER the `def` (rather than
  attached to the def symbol literal) so the `:arglists` value — a list
  of param vectors — is treated as DATA, not evaluated as a form."
  [src-sym]
  (when-not (resolve src-sym)
    ;; Author error at namespace load; retain the canonical structured shape.
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

;; Chunked-HTTP counterpart of `ssr-handler`.
(import-fn streaming/stream-handler)
(import-fn streaming/default-streaming-prefix)
(import-fn streaming/default-streaming-suffix)

;; ---- handler defaults + re-exported construction helpers ------------------
;;
;; Construction validation and error fallback live in lifecycle because both
;; handlers use the same boot boundary.

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
  construction time (`:emit-hash?`, `:html-shell`). A DATA var, not a
  fn — exposed so callers can read or extend the baseline. `:on-error`
  is deliberately NOT here (it is resolved separately via
  `lifecycle/resolve-on-error` so the defaults stay orthogonal to
  on-error precedence).

  `:content-type` carries no default: the opt is a genuine
  override that force-replaces the response Content-Type when supplied.
  A default of `text/html; charset=utf-8` here would force-replace an
  app's own `:rf.server/set-header \"content-type\"` on every request; an
  absent (nil) opt instead leaves the runtime's default-seeded
  `text/html; charset=utf-8` (Spec 011 §Status defaults) — or the app's
  explicit Content-Type — in control. So the on-the-wire default is
  unchanged."
  {:emit-hash? true
   :html-shell shell/default-html-shell})

;; ---- ssr-handler ----------------------------------------------------------

(defn ssr-handler
  "Return a Ring-shaped (synchronous) handler that renders one
  re-frame2 SSR request per call.

  Required opts:

    :initial-events — ordered event vector, or `(fn [request] events)` called
                      once before the drain. Put durable request-derived facts
                      in an event payload; use the `:rf.server/request` coeffect
                      for ambient request reads.
    :root-view      — hiccup vector or 0-arity fn returning hiccup, rendered
                      (head must be a CALLABLE — the Var reg-view defs, or
                      (rf/view :id); a keyword head is an HTML element, never
                      a view — rf2-j81hs)
                      against the settled request frame.
                      Two spellings emit byte-identical HTML but differ on
                      the hydration hash channel (rf2-q1b96):

                        (fn [] ((rf/view :app/root)))  ;; resolves → hashed
                        [(rf/view :app/root)]          ;; a reference → no hash

                      A root that stays a callable-headed vector is the
                      UNRESOLVED root form; hashing it yields one constant
                      for every application, so the handler emits no
                      `data-rf-render-hash` and no payload `:rf/render-hash`
                      for it. That is the shape an ADOPTION-TIER root
                      (compiled `re-frame.ui`, native UIx, Freehand) can only
                      ever be, and Spec 011 §Hydration-mismatch detection
                      requires it to carry none. A hiccup-tier host that
                      wants the channel passes the resolving form — the only
                      one symmetric with the documented client
                      `:render-tree-fn #((rf/view :app/root))`.
    :payload        — non-empty allowlist of top-level app-db keys, or the
                      explicit `:rf.ssr.payload/whole-app-db` opt-in. Missing
                      and unknown policies fail at handler construction.

  Optional opts:

    :fx-overrides   — per-frame `:fx-overrides` map, passed through
                      verbatim to `(rf/make-frame ...)`. Useful for
                      stubbing `:rf.http/managed` during tests.
    :ssr            — per-frame `:ssr` config map (e.g.
                      `{:dev-error-detail? true
                        :public-error-id   :myapp/projector}`).
    :emit-hash?     — emit hydration hash markers (default true). Gates the
                      WIRE markers only; the payload's hash keys are driven
                      by whether a hash exists at all (see `:root-view`).
    :client-frame-id — stable frame id stamped as the payload's WIRE
                      `:rf/frame-id`, when the deployment fixes one both
                      server and client agree on ahead of time. Default
                      nil — the per-request server frame is anonymous, so
                      the payload OMITS `:rf/frame-id` (the documented
                      no-conflict shape: the client's explicit `:frame`
                      target to `hydrate!` stands, Spec 011 §The hydration
                      payload). NEVER pass a per-request gensym here — the
                      client's hydrate guard rejects a present-and-
                      different id as `:rf.error/hydration-frame-id-
                      mismatch`.
    :version        — hydration payload's `:rf/version` (default 1).
    :schema-digest  — hydration payload's `:rf/schema-digest`, when
                      the app participates in the digest check.
    :html-shell     — (body-html payload-edn opts) → string. Defaults
                      to `default-html-shell`. Replace to inject custom
                      <head>, scripts, JSON-LD, etc.
    :content-type   — successful-body Content-Type override. Omit it to retain
                      the runtime or app header. Projected HTML errors do not
                      inherit this override.

    :error-view     — registered-view keyword or `(fn [public-error] hiccup)`
                      rendering the projected error page for a projected 5xx
                      (a drain-time OR render-time SERVER fault) and for an
                      unrenderable root/shell throw. Receives ONLY the
                      sanitised `:rf/public-error` map (never the request,
                      throwable, frame, or trace). A projected 4xx (routing
                      miss / bad client input) does NOT call it — the app
                      renders its own not-found / bad-request UI + hydration
                      payload. A failing error view (a throw, OR a reactive
                      sub that recovered to nil inside it) falls back ONCE to
                      the host default template, without re-projecting.
    :on-error       — `(fn [request throwable] ring-response)` for setup,
                      materialisation, and transport failures outside the
                      projector. Its default and fallback expose no throwable
                      detail.

  Trusted shell opts are structurally checked at construction. `:head` and
  `:body-end` are raw content hooks and must not contain untrusted input;
  `:script-src` and `:app-element-id` are escaped attribute values.

    :head           — raw HTML inside
                      `<head>...</head>`. Default: route-resolved head
                      fragment.
    :body-end       — raw HTML before
                      `</body>` — the escape hatch for analytics /
                      third-party scripts. Default: nil (omitted).
    :script-src     — escaped client-side
                      bootstrap script URL written `escape-attr`'d into
                      `<script src=\"...\">`. Default: \"/main.js\".
    :app-element-id — escaped id of the
                      `<div>` wrapping the rendered body, written
                      `escape-attr`'d into `<div id=\"...\">`. Default:
                      \"app\". The client-side hydrator reads this
                      element by id.

  Returns:

    (fn handler [ring-request] ring-response)

  Per-request lifecycle (see ns docstring for full detail):

    (ssr/set-request! frame-id request)            ;; before drain
      → make-frame                  (drains :initial-events synchronously;
                                     the `:rf.server/request` cofx
                                     reads from the populated slot)
        → flush-response-result!     (flushes error projections once;
                                     returns {:response :public-error})
        → classify: :redirect | projected-5xx error arm |
                     projected-4xx/none → render-to-string + payload
        → materialise to Ring map
      → finally: destroy-frame!     (the `:ssr/on-frame-destroyed`
                                     hook clears the request slot)

  Example:

    (require '[ring.adapter.jetty :as jetty]
             '[re-frame.core :as rf]
             '[re-frame.ssr.ring :as ssr-ring])

    (rf/init! (requiring-resolve 'ssr-ring-app/ssr-adapter))
    (def handler
      (ssr-ring/ssr-handler {:initial-events [[:rf/server-init]]
                             :root-view      [(rf/view :app/root)]
                             ;; A vector allowlists top-level app-db keys;
                             ;; `:rf.ssr.payload/whole-app-db` opts into
                             ;; the whole db. Omit it and construction throws
                             ;; `:rf.error/ssr-missing-payload-policy`.
                             :payload        [:articles :session-user]
                             :html-shell     ssr-ring-app/shell}))
    (jetty/run-jetty handler {:port 3000 :join? false})"
  [raw-opts]
  (lifecycle/validate-construction-opts! raw-opts)
  ;; Merge defaults once at construction time so the pipeline helpers
  ;; (`setup-request-frame!`, `build-full-response`) can destructure
  ;; without re-stating the `:or` map. Caller-supplied values win.
  ;;
  ;; Resolve `:on-error` separately so handler-defaults stays orthogonal to
  ;; the caller-or-locked-default precedence.
  (let [opts        (-> (merge handler-defaults raw-opts)
                        (assoc :on-error (lifecycle/resolve-on-error raw-opts)))
        {:keys [on-error]} opts]
    (fn ring-handler [request]
      (let [{:keys [frame-id frame short-circuit]}
            (pipeline/setup-request-frame! opts request)]
        (if short-circuit
          short-circuit
          (try
            ;; `ssr/flush-response-result!` reads the per-frame accumulated
            ;; status/headers/cookies/redirect AND the projected `:public-error`
            ;; (one drain), so the handler classifies the drain-time outcome
            ;; without re-inferring projection from `(:status resp)`.
            (let [{:keys [response public-error]} (ssr/flush-response-result! frame-id)]
              (cond
                ;; Redirect precedence FIRST (Spec 011 §Redirect precedence) —
                ;; a pending projection is ignored while a redirect stands.
                (some? (:redirect response))
                (pipeline/ssr-response->ring-response response nil)

                ;; A projected 5xx discovered during the drain (a handler/fx
                ;; exception, a custom 5xx projection) — the app-db is in an
                ;; arbitrary partial state, so DISCARD the root body + payload
                ;; and ship the projected-error arm (Spec 011 §Drain-time error
                ;; classification, rf2-oytx7j).
                (pipeline/projected-5xx? public-error)
                (pipeline/materialise-projected-error frame-id response public-error opts)

                ;; A projected 4xx (routing miss / bad client input) or no
                ;; projection keeps the app's OWN body: the root view renders
                ;; (the app's not-found / bad-request UI) and the client
                ;; hydrates into a working SPA. A post-render recovered-to-nil
                ;; 5xx is caught inside `build-full-response`.
                :else
                (pipeline/build-full-response frame-id response opts)))
            (catch Throwable t
              ;; A failing caller hook falls back to the locked response.
              (lifecycle/safe-on-error on-error request t))
            (finally
              ;; Frame teardown also clears its per-request side channels.
              ;; Destroy the VALUE (incarnation-EXACT, rf2-moftbs); the keyword
              ;; `frame-id` names the frame on any failure trace.
              (lifecycle/destroy-frame-quietly! frame frame-id))))))))

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

    ;; `ssr-middleware` is curried: `(ssr-middleware opts)` returns a
    ;; Ring middleware `(handler) → wrapped-handler`. Apply it to the
    ;; fallback handler, then compose normally. `:payload` is REQUIRED
    ;; (fail-closed) — the same allowlist-or-whole-db policy
    ;; `ssr-handler` enforces.
    (def app
      (-> default-handler
          ((ssr-ring/ssr-middleware
             {:initial-events [[:rf/server-init]]
              :root-view      [(rf/view :app/root)]
              :payload        [:articles :session-user]
              :match?         (fn [req] (= :get (:request-method req)))}))
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
