(ns re-frame.story.invariants-cljs-test
  "CLJS coverage for `re-frame.story.invariants` (rf2-5x1wt.5 +
  rf2-5x1wt.6, spec/017-Testing-Story.md §Invariant sentinels).

  The pure surface (`first-bad-epoch`, `coerce-invariant`, `check-epoch`,
  the report-once `on-epoch!` core) is exercised on the JVM by
  `re-frame.story.invariants-test`; this CLJS sibling pins the
  host-portable cases the `cljs-test$` node-test build discovers — the
  `:require-macros` self-reference compiles, the `with-invariants` macro
  expands to valid ClojureScript, and the live sentinel observes a real
  CLJS frame's epochs (`dispatch-sync` drains synchronously on node, and
  the epoch artefact is on the CLJS test classpath via shadow-cljs.edn)."
  (:require [cljs.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.epoch :as epoch]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.machines]
            [re-frame.story.invariants :as inv])
  (:require-macros [re-frame.story.invariants :refer [with-invariants]]))

(use-fixtures :each
  (fn [test-fn]
    (registrar/clear-all!)
    (reset! frame/frames {})
    (rf/init! plain-atom/adapter)
    (epoch/clear-epoch-listeners!)
    (epoch/clear-history!)
    (frame/ensure-default-frame!)
    (test-fn)))

(defn- epoch-rec
  [epoch-id m]
  (merge {:epoch-id epoch-id :frame :test/frame :outcome :ok
          :db-before {} :db-after {} :trace-events []}
         m))

;; ---- pure surface (host-portability) -------------------------------------

(deftest first-bad-epoch-cljs
  (testing "first-bad-epoch returns the first failing epoch on CLJS"
    (let [tape [(epoch-rec 1 {:db-after {:n 1}})
                (epoch-rec 2 {:db-after {:n -1}})]
          bad  (inv/first-bad-epoch tape (fn [e] (pos? (:n (:db-after e)))))]
      (is (= 2 (:epoch-id bad))))
    (is (nil? (inv/first-bad-epoch [] (fn [_] false))))))

(deftest check-epoch-isolates-throw-cljs
  (testing "a throwing predicate is caught on CLJS"
    (let [c (inv/coerce-invariant 0 (fn [_] (throw (ex-info "boom" {}))))
          v (inv/check-epoch c (epoch-rec 1 {}))]
      (is (some? v))
      (is (string? (:error v))))))

;; ---- on-epoch! report-once (CLJS report sink) ----------------------------

(defn- with-captured-reports
  [f]
  (let [reports (atom [])]
    (with-redefs [cljs.test/report (fn [m] (swap! reports conj m))]
      (f))
    @reports))

(deftest on-epoch-reports-once-cljs
  (testing "a violation reports once per (invariant, epoch) on CLJS"
    (let [coerced (inv/coerce-invariants [(fn [e] (pos? (:n (:db-after e))))])
          state   (atom {:seen #{} :violations []})
          ep      (epoch-rec 5 {:db-after {:n -3}})
          reports (with-captured-reports
                    (fn [] (inv/on-epoch! state coerced ep)
                           (inv/on-epoch! state coerced ep)))]
      (is (= 1 (count (filter #(= :fail (:type %)) reports)))))))

;; ---- live with-invariants over a real CLJS frame -------------------------

(deftest with-invariants-live-cljs
  (testing "the sentinel observes a real frame's epochs and reports once per failing epoch"
    (rf/reg-frame :test/main {})
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))
    (rf/reg-event-db :dec  (fn [db _] (update db :n dec)))
    (let [reports (with-captured-reports
                    (fn []
                      (with-invariants [(fn [e] (>= (:n (:db-after e)) 0))]
                        (rf/dispatch-sync [:seed] {:frame :test/main})
                        (rf/dispatch-sync [:dec]  {:frame :test/main})
                        (rf/dispatch-sync [:dec]  {:frame :test/main}))))]
      (is (= 2 (count (filter #(= :fail (:type %)) reports)))
          "two failing epochs → two failures"))))

(deftest with-invariants-pass-and-destroy-cljs
  (testing "a holding invariant reports passes; destroying the frame mid-run is tolerated"
    (rf/reg-frame :test/main {})
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))
    (rf/reg-event-db :inc  (fn [db _] (update db :n inc)))
    (let [done    (atom false)
          reports (with-captured-reports
                    (fn []
                      (with-invariants [(fn [e] (map? (:db-after e)))]
                        (rf/dispatch-sync [:seed] {:frame :test/main})
                        (rf/dispatch-sync [:inc]  {:frame :test/main})
                        (rf/destroy-frame! :test/main)
                        (reset! done true))))]
      (is (true? @done) "the body completed across the destroy")
      (is (zero? (count (filter #(= :fail (:type %)) reports)))
          "a holding invariant reports no failures")
      (is (pos? (count (filter #(= :pass (:type %)) reports)))
          "a green sentinel reports a pass"))))
