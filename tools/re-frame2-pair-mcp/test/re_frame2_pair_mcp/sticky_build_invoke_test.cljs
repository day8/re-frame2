(ns re-frame2-pair-mcp.sticky-build-invoke-test
  "Sticky build through `tools/invoke` with several builds running: an
  explicit `:build` becomes the session default for later no-`:build`
  calls, stored as the canonical running id even when typed as a suffix.
  `build_id_cache_test`'s `port-discover-no-pre-probe-sticks-through-invoke`
  pins the default `discover-app` sticks."
  (:require [cljs.test :refer-macros [deftest is async]]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools :as tools]
            [re-frame2-pair-mcp.tools.get-path :as get-path]
            [re-frame2-pair-mcp.tools.probe :as probe]
            [re-frame2-pair-mcp.tools.wire :as wire]
            [re-frame2-pair-mcp.test-utils :as tu]))

(deftest later-explicit-build-overrides-and-restickies
  (async done
    (let [conn          (nrepl/make-conn 0 "127.0.0.1")
          orig-running  probe/running-builds
          orig-get-path get-path/get-path-tool
          seen          (atom [])
          get-path!     (fn [args]
                          (tools/invoke conn "get-path" (tu/args->js (assoc args :path "[:k]")) nil))]
      (set! probe/running-builds
            (fn [_] (js/Promise.resolve [:examples/machine-epochs :examples/standard-epochs])))
      (set! get-path/get-path-tool
            (fn [c args]
              (swap! seen conj (wire/arg-build c args))
              (js/Promise.resolve #js {:content #js [#js {:type "text" :text "{:ok? true}"}]})))
      (-> (get-path! {:build "machine-epochs"})
          (.then (fn [_]
                   (is (= :examples/machine-epochs (:resolved-build-id @conn))
                       "a typed suffix sticks as the canonical id")
                   (get-path! {:build "examples/standard-epochs"})))
          (.then (fn [_] (get-path! {})))
          (.then (fn [_]
                   (is (= [:examples/machine-epochs :examples/standard-epochs :examples/standard-epochs]
                          @seen)
                       "a later explicit :build wins its call and the next no-:build call inherits it")))
          (.finally (fn []
                      (set! probe/running-builds orig-running)
                      (set! get-path/get-path-tool orig-get-path)))
          (.then (fn [_] (done)))))))
