(ns fresco.login.host
  "The JVM half of the login example's SSR route — a Ring handler that
  renders this page's BODY on Node and everything else itself.

  Ten lines of wiring and a page of why. The wiring is the whole point:
  swapping a JVM-local render for a Node one is ONE construction opt, and
  nothing else about the handler moves.

  ## The file name

  `host.clj`, not `server.clj`, and that is a compiler constraint rather
  than a preference. ClojureScript implicitly requires `foo/bar.clj` as the
  MACRO namespace of `foo.bar` whenever a `foo/bar.cljs` exists, so a
  `server.clj` beside `server.cljs` would be loaded into every compile of
  the server bundle — as macros, on a classpath that has no Ring on it.

  ## What this owns, and what Node owns (rf2-8arzr shared contract S2)

  The JVM keeps the request frame, the boot-event drain, the blocking-
  resource settle, the `<head>`, `__rf_payload`, the shell, the status,
  headers, cookies, redirects, error projection and frame teardown. Node
  returns body markup and nothing else. `:root-view` is absent because it
  is read only by the default JVM-local renderer.

  ## The two policies, and why there are two

  `:payload` answers *what may the BROWSER see?*; `:render-state` answers
  *what does the RENDER need?* They differ in both directions, and they
  are also opts on two DIFFERENT constructors — `:payload` on
  `ssr-handler`, `:render-state` on the renderer, which is what does the
  projecting. The render-state list is
  `fresco.login.policy/render-state-policy`: the same Var the server
  bundle derives its entry allowlists from, so one list is read by both
  halves and a host that reaches past it is refused by the sidecar rather
  than served.

  ## Running it — `init!`, then `app`

  Two things a JVM host needs that neither the Node boot nor the browser
  boot can do for it, because each of those initialises its OWN process:

    (host/init!)                                     ;; installs the adapter
    (jetty/run-jetty host/app {:port 3000 :join? false})

  `init!` installs the SSR substrate adapter. Requiring `re-frame.ssr`
  PUBLISHES its adapter; only `rf/init!` INSTALLS one, and without an
  installed adapter the first request cannot create its frame — `ssr-handler`
  projects the failure to a bare HTTP 500 before the renderer is reached, so
  a perfectly healthy sidecar renders nothing (rf2-gwye.61).

  `app` is the whole application: `handler` for the page, and the compiled
  browser bundle's output tree for everything else. `handler` ALONE renders
  a document for every request it is given, the `<script>` URL in the page
  it just served included — so serving it bare answers `/main.js` with
  another login page and the page never becomes interactive (rf2-gwye.62).
  Routing is the host application's job, which is what `app` shows; the
  library's own `ssr-middleware` is the other way to arrange it.

  Composition uses `ring.util.response` / `ring.middleware.content-type`
  from `ring/ring-core`, which every Ring server adapter already brings.

  This namespace loads and runs on a plain Clojure classpath — the shared
  `login.model` is `.cljc`, so the JVM holds the application's state the
  same way the browser does — and it is driven, over a real socket against
  the real sidecar launcher, by `re-frame.ssr.ring.login-host-crossing-test`
  (`implementation/ssr-ring/test/`, tagged `:crossing`). See the example
  README for the build commands the paths below assume."
  (:require [re-frame.core :as rf]
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.ring :as rf.ssr.ring]
            [re-frame.ssr.ring.node :as rf.ssr.ring.node]
            [ring.middleware.content-type :as ring.content-type]
            [ring.util.response :as ring.response]
            ;; The one render-state list, shared with `server.cljs`.
            [fresco.login.policy :as policy]
            ;; The application, on the JVM: every `auth.login` schema, fx,
            ;; machine, event and sub. Requiring it is what makes
            ;; `:initial-events` below name something that exists.
            [login.model :as model]))

(def build-id
  "The bundle this host was deployed against.

  It must be the string the server bundle publishes — `fresco.login.server/
  build-id`, a `goog-define` a release stamps. The check runs in BOTH
  directions and neither is optional: the sidecar refuses a request whose
  `buildId` is not its own — its build-identity refusal, whose code belongs
  to the sidecar's vocabulary and is spelled there rather than here — and
  the adapter refuses an ANSWER whose `x-rf-ssr-build` is not this one
  (`:rf.error/ssr-node-build-skew`). Two artefacts from different builds
  cannot quietly serve one page between them."
  (or (System/getenv "LOGIN_FRESCO_BUILD_ID") "login-fresco-dev"))

(def endpoint
  "Where the sidecar is listening. The adapter's default is the launcher's
  default bind, and it accepts any absolute http(s) URL — a non-loopback
  sidecar is not refused. Render state can carry server-only values, so a
  remote one is the operator's network and transport to secure."
  (or (System/getenv "LOGIN_FRESCO_SSR_NODE") rf.ssr.ring.node/default-endpoint))

(def client-dir
  "Where the compiled BROWSER bundle landed — the `:output-dir` of
  `:examples/login-fresco` in `implementation/shadow-cljs.edn`, as seen from
  the directory the README's build commands are run in.

  That build's `:asset-path` is `\".\"`, which means its generated
  dependency URLs are resolved against the DOCUMENT's own directory. The
  page is served at `/`, so this whole tree is served at `/` too: the shell
  asks for `/main.js` and the runtime then asks for `/cljs-runtime/…`, and
  both land inside this directory. Move the page off `/` and the asset
  mapping has to move with it."
  (or (System/getenv "LOGIN_FRESCO_CLIENT_DIR") "out/examples/login-fresco"))

(defn init!
  "Boot this JVM: install the SSR substrate adapter.

  A re-frame2 process installs ONE adapter, and installing it is an
  application-lifecycle act — call this once, before the Ring server starts
  accepting requests. It is NOT something the library does on a caller's
  behalf at require time or per request: requiring `re-frame.ssr` publishes
  the adapter, `rf/init!` seats it, and Spec 006 keeps those two apart on
  purpose. Idempotent for this adapter, so a REPL that evaluates it twice is
  fine."
  []
  (rf/init! rf.ssr/adapter))

(defn make-handler
  "Build the Ring handler against ONE sidecar. `handler` below is this
  called with the deployment's own `endpoint` / `build-id`; a test calls it
  with the ephemeral ones a spawned sidecar reported.

  One `ssr-handler`, one `:renderer`.

  `:payload` is the browser's allowlist; `:render-state` — an opt on the
  RENDERER, since the renderer is what projects — is
  `policy/render-state-policy`. `:initial-events` and `:fx-overrides` come
  from the shared `model/frame-config`, so the server seeds and stubs the
  frame exactly as the browser boot does and the two halves render the
  same page from the same events."
  [{:keys [endpoint build-id]}]
  (rf.ssr.ring/ssr-handler
    {:initial-events (:initial-events model/frame-config)
     ;; The demo HTTP stub, remapped for this frame exactly as the browser
     ;; mount remaps it. No request is issued during a server render, but
     ;; the frame is configured the same either way.
     :fx-overrides   (:fx-overrides model/frame-config)
     ;; What the BROWSER may see: the form slice, and nothing else.
     :payload        [:auth]
     ;; No `:root-view` — only the default JVM-local renderer reads one.
     :renderer       (rf.ssr.ring.node/renderer
                       {:endpoint     endpoint
                        :entry        policy/root-entry
                        :build-id     build-id
                        ;; The one list. `server.cljs` publishes the same
                        ;; keys as the entry's per-partition allowlists.
                        :render-state policy/render-state-policy
                        :timeout-ms   1000})
     ;; The client bundle, and the element it adopts. `fresco.login.core/run`
     ;; reads `__rf_payload`, finds one, and HYDRATES rather than mounting.
     :app-element-id "app"
     ;; The URL `client-dir`'s bundle is mapped to by `make-app` below. It
     ;; is one fact in two places and they have to agree: a `:script-src`
     ;; the application does not route is a page that never hydrates.
     :script-src     "/main.js"}))

(def handler
  "The PAGE handler — `ssr-handler`, and nothing else. It renders a document
  for every request it is given, so it wants a route in front of it rather
  than a socket: serve `app` below, not this."
  (make-handler {:endpoint endpoint :build-id build-id}))

(defn- page-request?
  "The one route this example renders. Everything else is an asset of the
  compiled bundle — which is why an unknown path gets a 404 rather than a
  login page: a miss that renders looks like a working asset URL."
  [{:keys [request-method uri]}]
  (and (= :get request-method) (= "/" uri)))

(defn make-app
  "The whole Ring application: `page-handler` for the page, `client-dir`'s
  compiled browser bundle for everything else.

  Twelve lines, no router and no server framework — the minimum that makes
  the documented build/serve sequence produce an interactive page. A real
  deployment usually has a router and a static-asset middleware already, and
  would use `re-frame.ssr.ring/ssr-middleware` to slot the page in
  alongside them."
  [page-handler client-dir]
  (fn app [request]
    (if (page-request? request)
      (page-handler request)
      (if-let [asset (ring.response/file-response (:uri request)
                                                  {:root client-dir})]
        (ring.content-type/content-type-response asset request)
        (-> (ring.response/not-found "No such asset in this build.")
            (ring.response/content-type "text/plain; charset=utf-8"))))))

(def app
  "What this deployment serves, once `init!` has run. Hand THIS to any Ring
  adapter."
  (make-app handler client-dir))
