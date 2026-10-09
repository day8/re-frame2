(ns re-frame.concurrency-stress-test
  "JVM stress coverage for two races the deterministic suite cannot reach:
  `rf/dispatch` from one frame's handler landing on a second frame whose drain
  runs on the executor thread, and an event handler re-registered from another
  thread while events stream through it. Every event must run exactly once.
  CLJS is single-threaded; these races cannot manifest there."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.schemas :as rf.schemas]
            [re-frame.flows :as rf.flows]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(defn- reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.flows/reset-flows!)
  (rf.schemas/clear-schemas-by-frame!)
  (rf/init! rf.substrate.plain-atom/adapter)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr :reload)
  (require 're-frame.machines :reload)
  (test-fn))

(use-fixtures :each reset-runtime)

(def ^:private stress-iters
  (or (some-> (System/getenv "RF2_35RGJ_STRESS_ITERS") Long/parseLong)
      5000))

(deftest ^:stress cross-frame-dispatch-under-executor-jitter-stress
  ;; Each iteration waits for B to settle before the next, so an envelope
  ;; stranded behind B's drain release shows as a timeout rather than being
  ;; swept up by a later dispatch.
  (testing (str "cross-frame dispatch never drops or duplicates "
                "envelopes across " stress-iters " iterations")
    (rf/make-frame {:id :rgj.exec/a})
    (rf/make-frame {:id :rgj.exec/b :drain-depth 10000})
    (let [failures    (atom [])
          ;; one indirection, so the handler is registered once rather than
          ;; racing leftover envelopes each iteration
          current-cnt (atom (atom 0))
          fanout      4]
      (rf/reg-event :b/leaf
        {:frame :rgj.exec/b}
        (fn [{:keys [db]} _]
          (swap! @current-cnt inc)
          {:db (update db :n (fnil inc 0))}))
      (rf/reg-event :a/cross-fire
        {:frame :rgj.exec/a}
        (fn [_ _]
          (dotimes [_ fanout]
            (rf/dispatch [:b/leaf] {:frame :rgj.exec/b}))
          {}))
      (dotimes [i stress-iters]
        (let [cnt (atom 0)]
          (reset! current-cnt cnt)
          (rf/dispatch-sync [:a/cross-fire] {:frame :rgj.exec/a})
          (let [deadline (+ (System/currentTimeMillis) 5000)]
            (loop []
              (cond
                (= fanout @cnt) :done
                (> (System/currentTimeMillis) deadline)
                (swap! failures conj {:iter i :reason :timeout :seen @cnt :want fanout})
                :else (do (Thread/yield) (recur)))))
          (let [delta @cnt]
            (when (not= fanout delta)
              (swap! failures conj {:iter i :delta delta :want fanout})))))
      (is (zero? (count @failures))
          (str "Expected zero cross-frame dispatch failures across "
               stress-iters " iterations; got " (count @failures)
               (when (pos? (count @failures))
                 (str ". First few: " (pr-str (vec (take 5 @failures))))))))))

(deftest ^:stress hot-reload-race-during-drain-stress
  ;; Spec 001 §Hot-reload semantics rule 1 under contention. v1 and v2 have the
  ;; same effect, so the final count is exact whichever body ran each event.
  (testing (str "sustained dispatch + concurrent re-registration — "
                "every event runs exactly once across " stress-iters " events")
    (rf/make-frame {:id :rgj.reload/main :drain-depth 100000})
    (let [v1 (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))})
          v2 (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))})]
      (rf/reg-event :rgj.reload/tick {:frame :rgj.reload/main} v1)
      (let [stop          (atom false)
            reload-thread (Thread.
                            ^Runnable
                            (fn []
                              (let [toggle (atom false)]
                                (while (not @stop)
                                  (rf/reg-event :rgj.reload/tick
                                                {:frame :rgj.reload/main}
                                                (if (swap! toggle not) v2 v1))
                                  (Thread/yield)))))]
        (.start reload-thread)
        (try
          (dotimes [_ stress-iters]
            (rf/dispatch-sync [:rgj.reload/tick] {:frame :rgj.reload/main}))
          (finally
            (reset! stop true)
            (.join reload-thread 5000)))
        (let [n (:n (rf/app-db-value :rgj.reload/main))]
          (is (= stress-iters n)
              (str "Expected " stress-iters " events processed under "
                   "hot-reload churn; got " n)))))))
