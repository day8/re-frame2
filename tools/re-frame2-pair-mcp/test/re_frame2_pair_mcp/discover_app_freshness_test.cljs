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
   :runtime-loaded-at          1000})

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

;; Several tabs on one build: every op reaches shadow-cljs's default runtime,
;; the tab that answered the health read, so discover-app names that tab.

(def ^:private answering-tab
  (assoc health-with-browser-half
         :runtime-instance-id "uuid-answering"
         :runtime-loaded-at   1700000000000))

(deftest discover-app-flags-several-runtimes-and-names-the-answering-tab
  (async done
    (-> (discover! answering-tab
                   {:compile-cycle 3 :build-flushed-at 900 :runtime-count 2 :heartbeat-age-ms 50})
        (.then
          (fn [edn]
            (is (= {:liveness :fresh :runtime-count 2 :warning :several-runtimes}
                   {:liveness      (:liveness (:freshness edn))
                    :runtime-count (:runtime-count (:freshness edn))
                    :warning       (:warning edn)}))
            (is (re-find #"^2 runtimes \(browser tabs\) are connected to build :examples/step-deck"
                         (str (:note edn))))
            (is (re-find #"runtime-instance-id is \"uuid-answering\" \(loaded at 2023-11-14T22:13:20.000Z\)"
                         (str (:note edn))))
            (done))))))

(deftest discover-app-one-runtime-carries-no-several-runtimes-flag
  (async done
    (-> (discover! answering-tab
                   {:compile-cycle 3 :build-flushed-at 900 :runtime-count 1 :heartbeat-age-ms 50})
        (.then
          (fn [edn]
            (is (= {:liveness :fresh :warning nil :note nil}
                   {:liveness (:liveness (:freshness edn))
                    :warning  (:warning edn)
                    :note     (:note edn)}))
            (done))))))

(deftest discover-app-stale-build-keeps-the-warning-slot-over-several-runtimes
  ;; A stale answering tab beside a second one: :stale-build keeps the slot,
  ;; and the note still names the tab every op reaches.
  (async done
    (-> (discover! answering-tab
                   {:compile-cycle 3 :build-flushed-at 1700000009000 :runtime-count 3 :heartbeat-age-ms 50})
        (.then
          (fn [edn]
            (is (= {:liveness :stale-build :warning :stale-build}
                   {:liveness (:liveness (:freshness edn)) :warning (:warning edn)}))
            (is (re-find #"^3 runtimes " (str (:note edn))))
            (done))))))
