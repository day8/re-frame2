(ns fresco.login.server
  "The login example's SERVER BUNDLE — the module `implementation/ssr-node`'s
  sidecar loads, and the Node half of the ssr-node crossing.

  This is the whole of what an application writes to render on Node: a build
  id and one `re-frame.fresco.server/node-module` form naming the views and
  the substrate. The framework owns the rest — reading the wire's state back
  (`re-frame.ssr.render-state/deserialize`), the render
  (`re-frame.fresco.server/render-body`), the state install
  (`re-frame.ssr.render-state/restore!`) and the wire domain.

      npx shadow-cljs compile :examples/login-fresco-server

  There is no sidecar to start by hand: the JVM host's `renderer`
  (`host.clj`) launches the sidecar on this bundle and supervises it.

  ## What crosses, and what does not

  The JVM host (`host.clj`) owns the request frame, the boot-event drain,
  the `<head>`, `__rf_payload`, the shell, the status and the response.
  **Node returns body markup and nothing else** — so there is no payload
  policy here, no head model, no cookies and no status. The service refuses
  a request carrying any of them, by name, with the reason attached.

  ## The entry table, and what decides the state

  `:entries` publishes the ids a JVM host may name — here one,
  `fresco.login.policy/root-entry`, read from the shared `.cljc` namespace
  so both halves spell it the same way. It names no state keys. Which keys
  reach the render is the JVM host's `:render-state` policy
  (`fresco.login.policy/render-state-policy`, which `host.clj` hands the
  Node renderer): the JVM projects the settled frame under it, and the
  sidecar checks only the shape of what arrives — top-level key text, EDN
  text per value, one byte ceiling. A host built against a different
  bundle is refused with the sidecar's build-identity refusal.

  ## The render module contract

  `{:protocol 1 :buildId <string> :entries [<entry id> …] :boot fn :render fn}`,
  which `node-module` builds, and `render` returns the body markup as a
  string — `render-body`'s answer — which is its ONE output channel. The
  service refuses a module that returns anything else."
  (:require [re-frame.core :as rf]
            [re-frame.fresco.server :as rf.fresco.server]
            [re-frame.fresco.substrate :as rf.fresco.substrate]
            ;; The views, the SSR coordinates and every `auth.login`
            ;; registration (the model rides in through this one require).
            [fresco.login.core :as views]
            ;; The entry id, shared with `host.clj`.
            [fresco.login.policy :as policy]
            [login.model :as model]))

;; ---------------------------------------------------------------------------
;; Build identity
;; ---------------------------------------------------------------------------

;; The identity of THIS bundle, published to the sidecar and checked against
;; the `:build-id` the JVM host was deployed with — in both directions (the
;; sidecar refuses a mismatched request, the adapter refuses a mismatched
;; answer). A `goog-define` so a release stamps the real thing:
;;
;;     shadow-cljs release examples/login-fresco-server \
;;       --config-merge '{:closure-defines {fresco.login.server/build-id "2026-09-02-a1b2c3"}}'
;;
;; The default is deliberately a DEV marker rather than a plausible version:
;; a host that never stamped one and a bundle that never stamped one agree by
;; accident, and a value that says so is the honest way to make that visible.
;; (`goog-define` takes a name and a default and no docstring, which is why
;; this one is written as a comment.)
(goog-define build-id "login-fresco-dev")

;; ---------------------------------------------------------------------------
;; The module
;; ---------------------------------------------------------------------------

(def module
  "`module.exports` for the sidecar. Named in `shadow-cljs.edn` as the
  `:examples/login-fresco-server` build's `:exports-var`.

  The entry id is NOT written here: `fresco.login.policy` owns it, in a
  `.cljc` namespace both this bundle and the JVM `host.clj` require. The
  render-state policy lives there too, and only the host reads it: the JVM
  projects, and this bundle renders what it is handed.

  `:boot` seats Fresco's substrate — the same one line the browser boot
  runs — and makes no frame, because a frame made at boot would be shared by
  every request in this isolate. `:frame-opts` is the app's own frame
  config; its `:initial-events` are NOT run, because the JVM already drained
  them and the projection is the settled result."
  (rf.fresco.server/node-module
    {:build-id          build-id
     :entries           [policy/root-entry]
     :boot              #(rf/init! rf.fresco.substrate/adapter)
     :hiccup            [views/root-view]
     :identifier-prefix views/identifier-prefix
     :frame-opts        model/frame-config}))
