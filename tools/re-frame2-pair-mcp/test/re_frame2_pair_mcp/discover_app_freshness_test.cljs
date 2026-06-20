(ns re-frame2-pair-mcp.discover-app-freshness-test
  "discover-app attaches the freshness/liveness token.

  The token rides on every `:ok? true` discover-app payload so an agent
  sees 'this runtime is fresh / stale-build / disconnected' on the very
  first call — the signal that heads off a stale-build false-alarm detour
  up front. These tests pin:

    1. a healthy discover-app carries `:freshness` with `:liveness
       :fresh`;
    2. a stale-BUILD verdict surfaces both in the token AND as a
       top-level `:warning :stale-build`;
    3. the warning branches (ambiguous-frame) still carry the token and
       keep their own warning."
  (:require [cljs.test :refer-macros [deftest is async]]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.discover-app :as discover-app]
            [re-frame2-pair-mcp.test-utils :as tu]))

(defn- fresh-conn []
  (let [conn (nrepl/make-conn 0 "127.0.0.1")]
    (swap! conn assoc :probed-builds #{} :resolved-build-id nil)
    conn))

(defn- prime! [conn build-id]
  (swap! conn update :probed-builds (fnil conj #{}) build-id))

(def ^:private health-with-browser-half
  "A `(runtime/health)` payload carrying the browser-half freshness
  fields the runtime preload stamps."
  {:ok?                        true
   :debug-enabled?             true
   :coord-annotation-enabled?  true
   :frames                     [:rf/default]
   :ambiguous-frame?           false
   :runtime-instance-id        "uuid-live"
   :runtime-loaded-at          1000
   :read-at                    1500})

(deftest discover-app-carries-fresh-token
  (async done
    (let [conn (fresh-conn)
          _    (prime! conn :examples/step-deck)
          args (tu/args->js {:build "examples/step-deck"})]
      ;; JVM half: last flush (500) BEFORE the runtime loaded (1000) ⇒ fresh.
      (-> (tu/with-stubbed-freshness! {:compile-cycle 4
                                       :build-flushed-at 500
                                       :runtime-count 1
                                       :heartbeat-age-ms 100}
            (fn []
              (tu/with-stubbed-eval! health-with-browser-half
                (fn [] (discover-app/discover-app conn args)))))
          (.then
            (fn [result]
              (let [edn (tu/extract-edn result)
                    tok (:freshness edn)]
                (is (true? (:ok? edn)))
                (is (map? tok) "discover-app attaches a :freshness token")
                (is (= "uuid-live" (:runtime-instance-id tok)))
                (is (= 4 (:compile-cycle tok)) "monotonic compile-cycle surfaced")
                (is (= :fresh (:liveness tok)) "fresh: load newer than flush")
                (is (not (contains? edn :warning))
                    "a fresh discover-app carries no warning"))
              (done)))))))

(deftest discover-app-flags-stale-build-at-top-level
  (async done
    (let [conn (fresh-conn)
          _    (prime! conn :examples/step-deck)
          args (tu/args->js {:build "examples/step-deck"})]
      ;; JVM half: last flush (9000) AFTER the runtime loaded (1000) ⇒ stale.
      (-> (tu/with-stubbed-freshness! {:compile-cycle 11
                                       :build-flushed-at 9000
                                       :runtime-count 1
                                       :heartbeat-age-ms 100}
            (fn []
              (tu/with-stubbed-eval! health-with-browser-half
                (fn [] (discover-app/discover-app conn args)))))
          (.then
            (fn [result]
              (let [edn (tu/extract-edn result)
                    tok (:freshness edn)]
                (is (= :stale-build (:liveness tok))
                    "stale-build verdict in the token")
                (is (= :stale-build (:warning edn))
                    "stale-build promoted to a top-level warning so the alarm is loud")
                (is (re-find #"STALE BUILD" (:hint tok))
                    "token hint names the alarm"))
              (done)))))))

(deftest discover-app-degrades-to-unknown-without-jvm-half
  ;; A socketless conn (no JVM to read) → :liveness :unknown, browser
  ;; half intact, no crash.
  (async done
    (let [conn (fresh-conn)
          _    (prime! conn :examples/step-deck)
          args (tu/args->js {:build "examples/step-deck"})]
      (-> (tu/with-stubbed-eval! health-with-browser-half
            (fn [] (discover-app/discover-app conn args)))
          (.then
            (fn [result]
              (let [edn (tu/extract-edn result)
                    tok (:freshness edn)]
                (is (true? (:ok? edn)) "still a healthy discover-app")
                (is (= :unknown (:liveness tok))
                    "no live socket ⇒ :liveness :unknown (graceful degrade)")
                (is (= "uuid-live" (:runtime-instance-id tok))
                    "browser half is still reported"))
              (done)))))))

(deftest discover-app-ambiguous-frame-keeps-its-warning-and-token
  (async done
    (let [conn (fresh-conn)
          _    (prime! conn :examples/step-deck)
          args (tu/args->js {:build "examples/step-deck"})
          ambiguous (assoc health-with-browser-half
                           :frames [:rf/default :feature/sandbox]
                           :ambiguous-frame? true)]
      (-> (tu/with-stubbed-freshness! {:compile-cycle 2
                                       :build-flushed-at 500
                                       :runtime-count 1
                                       :heartbeat-age-ms 50}
            (fn []
              (tu/with-stubbed-eval! ambiguous
                (fn [] (discover-app/discover-app conn args)))))
          (.then
            (fn [result]
              (let [edn (tu/extract-edn result)]
                (is (= :ambiguous-frame (:warning edn))
                    "ambiguous-frame warning is preserved")
                (is (map? (:freshness edn))
                    "freshness token rides the warning branch too")
                (is (= :fresh (:liveness (:freshness edn)))))
              (done)))))))
