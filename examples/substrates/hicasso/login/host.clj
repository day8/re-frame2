(ns hicasso.login.host
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
  `hicasso.login.policy/render-state-policy`: the same Var the server
  bundle derives its entry allowlists from, so one list is read by both
  halves and a host that reaches past it is refused by the sidecar rather
  than served.

  ## Running it

  See the example README. This namespace loads and runs on a plain Clojure
  classpath — the shared `login.model` is `.cljc`, so the JVM holds the
  application's state the same way the browser does — and it is driven,
  over a real socket against the real sidecar launcher, by
  `re-frame.ssr.ring.login-host-crossing-test`
  (`implementation/ssr-ring/test/`, tagged `:crossing`)."
  (:require [re-frame.ssr.ring :as ssr-ring]
            [re-frame.ssr.ring.node :as node]
            ;; The one render-state list, shared with `server.cljs`.
            [hicasso.login.policy :as policy]
            ;; The application, on the JVM: every `auth.login` schema, fx,
            ;; machine, event and sub. Requiring it is what makes
            ;; `:initial-events` below name something that exists.
            [login.model :as model]))

(def build-id
  "The bundle this host was deployed against.

  It must be the string the server bundle publishes — `hicasso.login.server/
  build-id`, a `goog-define` a release stamps. The check runs in BOTH
  directions and neither is optional: the sidecar refuses a request whose
  `buildId` is not its own — its build-identity refusal, whose code belongs
  to the sidecar's vocabulary and is spelled there rather than here — and
  the adapter refuses an ANSWER whose `x-rf-ssr-build` is not this one
  (`:rf.error/ssr-node-build-skew`). Two artefacts from different builds
  cannot quietly serve one page between them."
  (or (System/getenv "LOGIN_HICASSO_BUILD_ID") "login-hicasso-dev"))

(def endpoint
  "Where the sidecar is listening. The adapter's default is the launcher's
  default bind, and it accepts any absolute http(s) URL — a non-loopback
  sidecar is not refused. Render state can carry server-only values, so a
  remote one is the operator's network and transport to secure."
  (or (System/getenv "LOGIN_HICASSO_SSR_NODE") node/default-endpoint))

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
  (ssr-ring/ssr-handler
    {:initial-events (:initial-events model/frame-config)
     ;; The demo HTTP stub, remapped for this frame exactly as the browser
     ;; mount remaps it. No request is issued during a server render, but
     ;; the frame is configured the same either way.
     :fx-overrides   (:fx-overrides model/frame-config)
     ;; What the BROWSER may see: the form slice, and nothing else.
     :payload        [:auth]
     ;; No `:root-view` — only the default JVM-local renderer reads one.
     :renderer       (node/renderer
                       {:endpoint     endpoint
                        :entry        policy/root-entry
                        :build-id     build-id
                        ;; The one list. `server.cljs` publishes the same
                        ;; keys as the entry's per-partition allowlists.
                        :render-state policy/render-state-policy
                        :timeout-ms   1000})
     ;; The client bundle, and the element it adopts. `hicasso.login.core/run`
     ;; reads `__rf_payload`, finds one, and HYDRATES rather than mounting.
     :app-element-id "app"
     :script-src     "/js/main.js"}))

(def handler
  "The Ring handler this deployment serves. Hand it to any Ring adapter."
  (make-handler {:endpoint endpoint :build-id build-id}))
