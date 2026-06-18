(ns re-frame2-pair-mcp.sticky-build-invoke-test
  "End-to-end one-step sticky-build pin (rf2-jkwu4).

  THE NORTH STAR: after a SINGLE `discover-app`, an agent should be able
  to call EVERY other pair tool with NO `build` arg and have it just
  work — even with several shadow builds running. The lower-level pieces
  are pinned elsewhere (`build_id_cache_test` for the `:resolved-build-id`
  cache + `arg-build` precedence; `port_to_build_test` for the `:port`
  resolver writing the cache). This suite closes the loop the live repro
  exercised: drive the REAL `discover-app` then a REAL no-`build` tool
  call THROUGH `tools/invoke` (the single MCP egress) on the SAME conn,
  and assert the second call's per-tool body resolves the build
  `discover-app` stuck — NOT the `:app` env default.

  The live repro (2026-06-03, 5 builds running):

    discover-app {port 8033} -> resolves :examples/machine-epochs, OK
    orient {}                -> FAILED :build-not-running :app  (the bug)
    read-dom {selector ...}  -> FAILED :build-not-running :app  (the bug)

  These tests prove the build STICKS across the `invoke` boundary so the
  follow-up no-`build` calls target the resolved build."
  (:require [cljs.test :refer-macros [deftest is async]]
            [applied-science.js-interop :as j]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools :as tools]
            [re-frame2-pair-mcp.tools.discover-app :as discover-app]
            [re-frame2-pair-mcp.tools.get-path :as get-path]
            [re-frame2-pair-mcp.tools.probe :as probe]
            [re-frame2-pair-mcp.tools.wire :as wire]
            [re-frame2-pair-mcp.test-utils :as tu]))

(def ^:private healthy-health
  {:ok?                        true
   :debug-enabled?             true
   :coord-annotation-enabled?  true
   :frames                     [:rf/default]
   :ambiguous-frame?           false})

(defn- fresh-conn
  "A conn-atom fresh out of connect! — caches cleared, plus a fake live
  socket so the freshness JVM-half probe doesn't try a real TCP connect."
  []
  (let [conn (nrepl/make-conn 0 "127.0.0.1")]
    (swap! conn assoc :probed-builds #{} :resolved-build-id nil
           :build-alias {} :socket #js {} :closed? false)
    conn))

(defn- captured-build-from-second-call
  "Drive `discover-app` with `discover-args`, then a no-`build` `get-path`
  through `tools/invoke` on the SAME conn. Returns a Promise of the
  build-id the second call's body resolved via `wire/arg-build`. The
  build that should stick is `running-build` (the one running in the
  workspace). All round-trips are stubbed so the test is hermetic:

    - `probe/running-builds`      → [running-build] (canonicalize-build-step
                                     maps the sticky default to itself).
    - `probe/resolve-build-by-port` → running-build (the :port resolver).
    - `nrepl/cljs-eval-value`     → healthy-health (discover-app's probe +
                                     health read; the freshness JVM-half
                                     uses jvm-eval, stubbed to nil below).
    - `nrepl/jvm-eval`            → blank (freshness degrades to :unknown,
                                     harmless here)."
  [discover-args running-build]
  (let [conn          (fresh-conn)
        captured      (atom :NOT-CALLED)
        orig-running  probe/running-builds
        orig-port     probe/resolve-build-by-port
        orig-eval     nrepl/cljs-eval-value
        orig-jvm      nrepl/jvm-eval
        orig-get-path get-path/get-path-tool
        eval-stub     (fn ([_c _b _f] (js/Promise.resolve healthy-health))
                        ([_c _b _f _o] (js/Promise.resolve healthy-health)))
        jvm-stub      (fn [& _] (js/Promise.resolve {:value ""}))]
    ;; Pre-probe so runtime-preloaded? short-circuits without a round-trip.
    (swap! conn update :probed-builds conj running-build)
    (set! probe/running-builds (fn [_] (js/Promise.resolve [running-build])))
    (set! probe/resolve-build-by-port (fn [_c _p] (js/Promise.resolve running-build)))
    (set! nrepl/cljs-eval-value eval-stub)
    (set! nrepl/jvm-eval jvm-stub)
    ;; The probe of the second call: capture what build the no-`build`
    ;; get-path resolved. (get-path's real body reads wire/arg-build conn
    ;; raw-args — we read it the same way the body would.)
    (set! get-path/get-path-tool
          (fn [c args]
            (reset! captured (wire/arg-build c args))
            (js/Promise.resolve
              #js {:content #js [#js {:type "text" :text "{:ok? true}"}]})))
    (-> (discover-app/discover-app conn discover-args)
        (.then (fn [_disc]
                 ;; second tool call: NO build arg, through the invoke egress.
                 (tools/invoke conn "get-path" (tu/args->js {:path "[:k]"}) nil)))
        (.then (fn [_] @captured))
        (.finally (fn []
                    (set! probe/running-builds orig-running)
                    (set! probe/resolve-build-by-port orig-port)
                    (tu/restore-eval! eval-stub orig-eval)
                    (tu/restore-jvm-eval! jvm-stub orig-jvm)
                    (set! get-path/get-path-tool orig-get-path))))))

;; ---------------------------------------------------------------------------
;; Acceptance #1 — multi-build, :port. discover-app {port 8033} then a
;; no-build call lands on the resolved build, NOT :app.
;; ---------------------------------------------------------------------------

(deftest port-discover-sticks-build-through-invoke
  (async done
    (-> (captured-build-from-second-call
          (tu/args->js {:port 8033}) :examples/machine-epochs)
        (.then
          (fn [resolved]
            (is (= :examples/machine-epochs resolved)
                "post-discover-app{port}, a no-build tool call THROUGH invoke targets the resolved build")
            (is (not= :app resolved)
                "it must NOT fall back to the :app env default (the live-repro bug)")
            (done))))))

;; ---------------------------------------------------------------------------
;; Acceptance #2 — multi-build, explicit. discover-app {build ...} then a
;; no-build call lands on the resolved build, NOT :app.
;; ---------------------------------------------------------------------------

(deftest explicit-discover-sticks-build-through-invoke
  (async done
    (-> (captured-build-from-second-call
          (tu/args->js {:build "examples/machine-epochs"}) :examples/machine-epochs)
        (.then
          (fn [resolved]
            (is (= :examples/machine-epochs resolved)
                "post-discover-app{build}, a no-build tool call THROUGH invoke targets the resolved build")
            (is (not= :app resolved))
            (done))))))

;; ---------------------------------------------------------------------------
;; A later EXPLICIT :build overrides AND updates the sticky default
;; (rf2-lbm21), so the NEXT no-build call inherits the override.
;; ---------------------------------------------------------------------------

(deftest later-explicit-build-overrides-and-restickies
  (async done
    (let [conn          (fresh-conn)
          orig-eval     nrepl/cljs-eval-value
          orig-jvm      nrepl/jvm-eval
          orig-running  probe/running-builds
          orig-port     probe/resolve-build-by-port
          orig-get-path get-path/get-path-tool
          captured      (atom nil)
          eval-stub     (fn ([_c _b _f] (js/Promise.resolve healthy-health))
                          ([_c _b _f _o] (js/Promise.resolve healthy-health)))
          jvm-stub      (fn [& _] (js/Promise.resolve {:value ""}))]
      (swap! conn update :probed-builds conj :examples/machine-epochs)
      (set! probe/resolve-build-by-port (fn [_c _p] (js/Promise.resolve :examples/machine-epochs)))
      (set! probe/running-builds
            (fn [_] (js/Promise.resolve [:examples/machine-epochs :examples/standard-epochs])))
      (set! nrepl/cljs-eval-value eval-stub)
      (set! nrepl/jvm-eval jvm-stub)
      (set! get-path/get-path-tool
            (fn [c args]
              (reset! captured (wire/arg-build c args))
              (js/Promise.resolve
                #js {:content #js [#js {:type "text" :text "{:ok? true}"}]})))
      (-> (discover-app/discover-app conn (tu/args->js {:port 8033}))
          (.then (fn [_]
                   (is (= :examples/machine-epochs (:resolved-build-id @conn))
                       "discover-app{port} stuck machine-epochs")
                   ;; A later EXPLICIT build override on a get-path call.
                   (tools/invoke conn "get-path"
                                 (tu/args->js {:path "[:k]" :build "examples/standard-epochs"})
                                 nil)))
          (.then (fn [_]
                   (is (= :examples/standard-epochs @captured)
                       "the explicit :build wins on that call")
                   (is (= :examples/standard-epochs (:resolved-build-id @conn))
                       "and stick-build! updates the sticky default to the override")
                   ;; The NEXT no-build call inherits the new sticky default.
                   (tools/invoke conn "get-path" (tu/args->js {:path "[:k]"}) nil)))
          (.then (fn [_]
                   (is (= :examples/standard-epochs @captured)
                       "subsequent no-build call inherits the updated sticky default")))
          (.finally (fn []
                      (tu/restore-eval! eval-stub orig-eval)
                      (tu/restore-jvm-eval! jvm-stub orig-jvm)
                      (set! probe/running-builds orig-running)
                      (set! probe/resolve-build-by-port orig-port)
                      (set! get-path/get-path-tool orig-get-path)))
          (.then (fn [_] (done)))))))
