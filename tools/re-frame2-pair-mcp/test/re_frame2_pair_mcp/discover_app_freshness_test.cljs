(ns re-frame2-pair-mcp.discover-app-freshness-test
  "discover-app attaches the freshness/liveness token to every `:ok? true`
  payload, and promotes a stale-build verdict to a top-level `:warning`
  unless another warning already holds the slot."
  (:require [cljs.test :refer-macros [deftest is async]]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.discover-app :as discover-app]
            [re-frame2-pair-mcp.test-utils :as tu]))

(defn- fresh-conn []
  (let [conn (nrepl/make-conn 0 "127.0.0.1")]
    (swap! conn assoc :probed-builds #{:examples/step-deck} :resolved-build-id nil)
    conn))

(def ^:private health-with-browser-half
  {:ok?                        true
   :debug-enabled?             true
   :coord-annotation-enabled?  true
   :frames                     [:rf/default]
   :ambiguous-frame?           false
   :runtime-instance-id        "uuid-live"
   :runtime-loaded-at          1000
   :read-at                    1500})

(defn- discover!
  "discover-app against `health`, with the JVM half stubbed to `jvm-half`;
  resolves to the payload's EDN."
  [health jvm-half]
  (-> (tu/with-stubbed-freshness! jvm-half
        (fn []
          (tu/with-stubbed-eval! health
            (fn [] (discover-app/discover-app (fresh-conn)
                                              (tu/args->js {:build "examples/step-deck"}))))))
      (.then tu/extract-edn)))

(deftest discover-app-carries-fresh-token
  ;; Last flush (500) before the runtime loaded (1000).
  (async done
    (-> (discover! health-with-browser-half
                   {:compile-cycle 4 :build-flushed-at 500 :runtime-count 1 :heartbeat-age-ms 100})
        (.then
          (fn [edn]
            (is (= {:runtime-instance-id "uuid-live" :compile-cycle 4 :liveness :fresh}
                   (select-keys (:freshness edn) [:runtime-instance-id :compile-cycle :liveness])))
            (is (not (contains? edn :warning)) "a fresh discover-app carries no warning")
            (done))))))

(deftest discover-app-flags-stale-build-at-top-level
  ;; Last flush (9000) after the runtime loaded (1000).
  (async done
    (-> (discover! health-with-browser-half
                   {:compile-cycle 11 :build-flushed-at 9000 :runtime-count 1 :heartbeat-age-ms 100})
        (.then
          (fn [edn]
            (is (= {:liveness :stale-build :warning :stale-build}
                   {:liveness (:liveness (:freshness edn)) :warning (:warning edn)}))
            (done))))))

(deftest discover-app-ambiguous-frame-keeps-its-warning-and-token
  ;; A stale build on the ambiguous-frame branch: the token still rides, and
  ;; the ambiguous-frame warning is not overwritten by :stale-build.
  (async done
    (-> (discover! (assoc health-with-browser-half
                          :frames [:rf/default :feature/sandbox]
                          :ambiguous-frame? true)
                   {:compile-cycle 2 :build-flushed-at 9000 :runtime-count 1 :heartbeat-age-ms 50})
        (.then
          (fn [edn]
            (is (= {:liveness :stale-build :warning :ambiguous-frame}
                   {:liveness (:liveness (:freshness edn)) :warning (:warning edn)}))
            (done))))))
