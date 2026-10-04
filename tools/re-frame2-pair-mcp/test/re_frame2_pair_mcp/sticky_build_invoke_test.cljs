(ns re-frame2-pair-mcp.sticky-build-invoke-test
  "End-to-end one-step sticky-build pin.

  THE NORTH STAR: after a SINGLE `discover-app`, an agent should be able
  to call EVERY other pair tool with NO `build` arg and have it just
  work — even with several shadow builds running. The lower-level pieces
  are pinned elsewhere (`build_id_cache_test` for the `:resolved-build-id`
  cache + `arg-build` precedence; `port_to_build_test` for the `:port`
  resolver writing the cache). This suite closes the loop: drive the
  REAL `discover-app` then a REAL no-`build` tool call THROUGH
  `tools/invoke` (the single MCP egress) on the SAME conn, and assert
  the second call's per-tool body resolves the build `discover-app`
  stuck — NOT the `:app` env default.

  The scenario guarded, with several builds running:

    discover-app {port 8033} -> resolves :examples/machine-epochs
    orient {}                -> targets the resolved build, not :app
    read-dom {selector ...}  -> targets the resolved build, not :app

  The plain discover-then-call case is pinned by `build_id_cache_test`'s
  `port-discover-no-pre-probe-sticks-through-invoke`; this suite pins
  what rides on top of it — a later explicit `:build` re-sticking the
  default, and a typed suffix sticking as the canonical id."
  (:require [cljs.test :refer-macros [deftest is async]]
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


;; ---------------------------------------------------------------------------
;; A later EXPLICIT :build overrides AND updates the sticky default,
;; so the NEXT no-build call inherits the override.
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

;; ---------------------------------------------------------------------------
;; A typed SUFFIX :build sticks as the CANONICAL running id, so a later
;; no-build call inherits the full id rather than the suffix.
;; ---------------------------------------------------------------------------

(deftest suffix-build-sticks-as-the-canonical-id
  (async done
    (let [conn          (fresh-conn)
          orig-eval     nrepl/cljs-eval-value
          orig-jvm      nrepl/jvm-eval
          orig-running  probe/running-builds
          orig-get-path get-path/get-path-tool
          eval-stub     (fn ([_c _b _f] (js/Promise.resolve healthy-health))
                          ([_c _b _f _o] (js/Promise.resolve healthy-health)))
          jvm-stub      (fn [& _] (js/Promise.resolve {:value ""}))]
      (swap! conn update :probed-builds conj :examples/machine-epochs)
      (set! probe/running-builds
            (fn [_] (js/Promise.resolve [:examples/machine-epochs :examples/standard-epochs])))
      (set! nrepl/cljs-eval-value eval-stub)
      (set! nrepl/jvm-eval jvm-stub)
      (set! get-path/get-path-tool
            (fn [_c _args]
              (js/Promise.resolve
                #js {:content #js [#js {:type "text" :text "{:ok? true}"}]})))
      (-> (tools/invoke conn "get-path"
                        (tu/args->js {:path "[:k]" :build "machine-epochs"})
                        nil)
          (.then (fn [_]
                   (is (= :examples/machine-epochs (:resolved-build-id @conn))
                       "the sticky default holds the canonical id, not the typed suffix")))
          (.finally (fn []
                      (tu/restore-eval! eval-stub orig-eval)
                      (tu/restore-jvm-eval! jvm-stub orig-jvm)
                      (set! probe/running-builds orig-running)
                      (set! get-path/get-path-tool orig-get-path)))
          (.then (fn [_] (done)))))))
