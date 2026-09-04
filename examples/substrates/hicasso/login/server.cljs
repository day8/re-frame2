(ns hicasso.login.server
  "The login example's SERVER BUNDLE — the module `implementation/ssr-node`'s
  sidecar loads, and the Node half of the ssr-node crossing.

  This is the whole of what an application writes to render on Node. It is
  short on purpose: the framework owns the render (`re-frame.hicasso.server/
  render-body`), the state install (`re-frame.ssr.render-state/restore!`) and
  the wire domain, so what is left here is an ENTRY TABLE and a boot.

      npx shadow-cljs compile :examples/login-hicasso-server
      node implementation/ssr-node/bin/serve.cjs \\
        --module implementation/out/examples/login-hicasso-server/server.js

  ## What crosses, and what does not

  The JVM host (`host.clj`) owns the request frame, the boot-event drain,
  the `<head>`, `__rf_payload`, the shell, the status and the response.
  **Node returns body markup and nothing else** — so there is no payload
  policy here, no head model, no cookies and no status. The service refuses
  a request carrying any of them, by name, with the reason attached.

  ## The two allowlists, and who owns them

  `entries` publishes, per entry, the top-level keys of EACH partition that
  entry may be handed. The lists belong to the ENTRY rather than to the
  caller, so a JVM host cannot widen its own allowance, and an entry that
  declares no list for a partition cannot be rendered at all — absence is a
  refusal, never a licence to read everything.

  They are derived from `hicasso.login.policy/render-state-policy` — the
  SAME Var `host.clj` hands the Node renderer as `:render-state`, in a
  `.cljc` namespace precisely so both halves can read it. ONE list, two
  readers, one file. The two processes are deployed separately, so nothing
  can MAKE them agree at run time — but nothing has to: a host that asks
  for a key this table does not name is refused with the sidecar's
  state-key-not-allowed refusal, and a host built against a different
  bundle with its build-identity one. Both codes are the sidecar's
  vocabulary, spelled in its protocol rather than restated here. Drift is
  loud on the first request, not silent on the thousandth — and a host
  built from this repository cannot drift in the first place, because
  there is one list to edit.

  ## The render module contract

  `{:protocol 1 :buildId <string> :entries {…} :boot fn :render fn}`, and
  `render` has ONE output channel — `emit`. It returns `js/undefined`
  rather than `nil`, and the difference is not pedantry: the service
  refuses a module that returns a VALUE, and CLJS `nil` compiles to `null`,
  which the contract calls \"a sentence someone wrote\" and refuses along
  with every other value."
  (:require [re-frame.core :as rf]
            [re-frame.hicasso.server :as rf.hicasso.server]
            [re-frame.hicasso.substrate :as rf.hicasso.substrate]
            [re-frame.ssr.render-state :as rf.ssr.render-state]
            ;; The views, the SSR coordinates and every `auth.login`
            ;; registration (the model rides in through this one require).
            [hicasso.login.core :as views]
            ;; The one render-state list, shared with `host.clj`.
            [hicasso.login.policy :as policy]
            [login.model :as model]))

;; ---------------------------------------------------------------------------
;; Build identity
;; ---------------------------------------------------------------------------

;; The identity of THIS bundle, published to the sidecar and checked against
;; the `:build-id` the JVM host was deployed with — in both directions (the
;; sidecar refuses a mismatched request, the adapter refuses a mismatched
;; answer). A `goog-define` so a release stamps the real thing:
;;
;;     shadow-cljs release examples/login-hicasso-server \
;;       --config-merge '{:closure-defines {hicasso.login.server/build-id "2026-09-02-a1b2c3"}}'
;;
;; The default is deliberately a DEV marker rather than a plausible version:
;; a host that never stamped one and a bundle that never stamped one agree by
;; accident, and a value that says so is the honest way to make that visible.
;; (`goog-define` takes a name and a default and no docstring, which is why
;; this one is written as a comment.)
(goog-define build-id "login-hicasso-dev")

;; ---------------------------------------------------------------------------
;; The render-state policy — the one list both halves read
;; ---------------------------------------------------------------------------
;;
;; It is NOT written here. `hicasso.login.policy/render-state-policy` owns it,
;; in a `.cljc` namespace both this bundle and the JVM `host.clj` require, and
;; that namespace's docstring is where the per-key reasoning lives.

(def root-entry
  "The entry identifier a JVM host names in its renderer opts — from the
  shared policy namespace, for the same reason the allowlists are."
  policy/root-entry)

(defn- allowlist
  "The EDN text of each key in one slot of `policy/render-state-policy` —
  the spelling the protocol's key grammar admits (`:foo`, `:foo/bar`), and
  the same spelling `rf.ssr.render-state/serialize` puts on the wire."
  [slot]
  (into-array (map pr-str (get policy/render-state-policy slot))))

;; ---------------------------------------------------------------------------
;; The module
;; ---------------------------------------------------------------------------

(defn- boot!
  "Once per isolate, before the first render: seat the substrate. Hicasso
  ships its own, so this is the same one line the browser boot runs.

  Nothing else. No frame is made here — a frame made at boot would be
  shared by every request in this isolate, which is the one thing a
  per-request renderer must not do."
  []
  (rf/init! rf.hicasso.substrate/adapter)
  js/undefined)

(defn- render!
  "Render one request's body. `call` is the frozen `{entry, state, runtime,
  args}` the service hands a module; `emit` is its only output channel.

  `state` and `runtime` arrive as key-text -> EDN-text, per partition,
  because the service does not decode application data. `deserialize`
  reads them back under the bundled SAFE EDN reader, and `render-body`
  installs both partitions into a fresh per-request frame in one write."
  [^js call emit]
  (let [partitions (rf.ssr.render-state/deserialize
                     {:rf/app-db     (js->clj (.-state call))
                      :rf/runtime-db (js->clj (.-runtime call))})]
    (emit (rf.hicasso.server/render-body
            {:hiccup            [views/root-view]
             :render-state      partitions
             :identifier-prefix views/identifier-prefix
             ;; The app's own frame config — `:fx-overrides` points
             ;; `:rf.http/managed` at the demo stub, exactly as it does in
             ;; the browser. Its `:initial-events` are NOT run:
             ;; `render-body` forces the setup vector empty, because the
             ;; JVM already drained them and the projection is the settled
             ;; result.
             :frame-opts        model/frame-config})))
  ;; `undefined`, not `nil` — see the namespace docstring.
  js/undefined)

(def module
  "`module.exports` for the sidecar. Named in `shadow-cljs.edn` as the
  `:examples/login-hicasso-server` build's `:exports-var`."
  #js {:protocol 1
       :buildId  build-id
       :entries  (js-obj root-entry
                         #js {:stateAllowlist   (allowlist :app-db)
                              :runtimeAllowlist (allowlist :runtime-db)})
       :boot     boot!
       :render   render!})
