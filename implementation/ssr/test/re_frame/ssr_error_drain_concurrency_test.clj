(ns re-frame.ssr-error-drain-concurrency-test
  "`consume-pending-traces!` pulls and clears a frame's pending error traces
  in ONE atomic transition. A deref-then-dissoc drain would drop a trace
  appended between the two steps — and with it a fail-closed status. JVM-only:
  the race needs real parallelism."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.ssr.error-listener :as rf.ssr.error-listener]))

(def ^:private consume! #'rf.ssr.error-listener/consume-pending-traces!)

(defn- buffer! [frame-id trace]
  (swap! rf.ssr.error-listener/pending-error-traces
         update frame-id (fnil conj []) trace))

(deftest consume-pending-traces-loses-no-trace-under-concurrent-append
  (let [frame-id   :rf.test/drain-race
        appends    2000
        drained    (atom [])
        start-gate (java.util.concurrent.CountDownLatch. 1)
        appender   (Thread. (fn []
                              (.await start-gate)
                              (dotimes [i appends]
                                (buffer! frame-id {:op-type :error :seq i}))))
        drainer    (Thread. (fn []
                              (.await start-gate)
                              (dotimes [_ (* 4 appends)]
                                (swap! drained into (consume! frame-id)))))]
    (swap! rf.ssr.error-listener/pending-error-traces dissoc frame-id)
    (.start appender)
    (.start drainer)
    (.countDown start-gate)
    (.join appender)
    (.join drainer)
    (is (= (range appends)
           (sort (map :seq (into @drained (consume! frame-id)))))
        "every appended trace surfaces exactly once: no loss, no duplication")))
