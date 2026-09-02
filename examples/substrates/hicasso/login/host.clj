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
  *what does the RENDER need?* They differ in both directions, and this
  handler is where that is declared. The render-state policy is
  `hicasso.login.server/render-state-policy` — the SAME map the bundle
  derives its entry allowlists from, so one list is read by both halves and
  a host that reaches past it is refused by the sidecar rather than served.

  ## Running it

  See the example README. There is one thing this file cannot do on its
  own, and it is worth knowing before you try: a JVM host has to hold the
  application's state, so the shared `login.model` — the owner of every
  `auth.login` schema, fx, machine, event and sub — has to be loadable from
  Clojure. It is `examples/core/login/model.cljs` today, ClojureScript
  only, and the single line that keeps it there is a `localStorage` write
  in the demo session fx. Making it `.cljc` is a separate change to a file
  three example arms share, so it is not made here; until it is, this
  namespace is the WIRING rather than a running server, and the crossing it
  describes is exercised end to end by
  `re-frame.hicasso.login-server-crossing-ssr-dom-cljs-test`."
  (:require [re-frame.ssr.ring :as ssr-ring]
            [re-frame.ssr.ring.node :as node]
            ;; The application, on the JVM. See §Running it above.
            #_[login.model]))

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

(def handler
  "The Ring handler. One `ssr-handler`, one `:renderer`.

  `:render-state` and `:payload` are the two policies; `:initial-events`
  seeds the form slice before the first render exactly as the browser boot
  does, so the two halves render the same page from the same events."
  (ssr-ring/ssr-handler
    {:initial-events [[:auth.login/initialise-form]]
     ;; What the BROWSER may see: the form slice, and nothing else.
     :payload        [:auth]
     ;; What the RENDER may see. Read from the bundle's own map when it is
     ;; on this classpath; spelled here otherwise, and the sidecar refuses
     ;; the difference rather than serving it.
     :render-state   {:app-db     [:auth :auth.login/server-notice]
                      :runtime-db [:rf.runtime/machines]}
     ;; No `:root-view` — only the default JVM-local renderer reads one.
     :renderer       (node/renderer
                       {:endpoint     endpoint
                        :entry        "hicasso.login/root"
                        :build-id     build-id
                        ;; The same two policies the seam projects under.
                        :render-state {:app-db     [:auth :auth.login/server-notice]
                                       :runtime-db [:rf.runtime/machines]}
                        :timeout-ms   1000})
     ;; The client bundle, and the element it adopts. `hicasso.login.core/run`
     ;; reads `__rf_payload`, finds one, and HYDRATES rather than mounting.
     :app-element-id "app"
     :script-src     "/js/main.js"}))
