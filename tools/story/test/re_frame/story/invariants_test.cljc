(ns re-frame.story.invariants-test
  "`re-frame.story.invariants` (spec/017 §Invariant sentinels): the pure
  core over hand-built `:rf/epoch-record` tapes, and the live
  `with-invariants` sentinel over a real frame on the JVM. Live reports
  are captured, so a deliberate violation does not fail this suite."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.story.invariants :as rf.story.invariants]
            #?(:clj [re-frame.story.invariants :refer [with-invariants]])
            #?(:clj [re-frame.frame :as rf.frame])
            #?(:clj [re-frame.registrar :as rf.registrar])
            #?(:clj [re-frame.epoch :as rf.epoch])
            #?(:clj [re-frame.substrate.plain-atom :as rf.substrate.plain-atom])
            ;; machine restore hooks must be on the classpath
            #?(:clj [re-frame.machines]))
  #?(:cljs (:require-macros [re-frame.story.invariants :refer [with-invariants]])))

#?(:clj (use-fixtures :each
          (fn [test-fn]
            (rf.registrar/clear-all!)
            (reset! rf.frame/frames {})
            (try (rf/init! rf.substrate.plain-atom/adapter)
                 (catch clojure.lang.ExceptionInfo _ nil))
            (require 're-frame.machines :reload)
            (rf.epoch/clear-epoch-listeners!)
            (rf.epoch/clear-history!)
            (rf.frame/ensure-default-frame!)
            (test-fn))))

(defn- epoch
  "A minimal `:rf/epoch-record`; `m` overrides any slot."
  [epoch-id m]
  (merge {:epoch-id     epoch-id
          :frame        :test/frame
          :outcome      :ok
          :db-before    {}
          :db-after     {}
          :trace-events []}
         m))

;; ---- coerce-invariant ------------------------------------------------------

(deftest coerce-explicit-map
  (let [f (fn [_] true)]
    (testing "an explicit map keeps its :id and resolves :check"
      (is (= {:id :my/inv :check f}
             (rf.story.invariants/coerce-invariant 3 {:id :my/inv :check f}))))
    (testing ":pred is accepted as an alias for :check"
      (is (= f (:check (rf.story.invariants/coerce-invariant 0 {:pred f}))))))
  (testing "a map without a check fn throws a structured error"
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                 (rf.story.invariants/coerce-invariant 0 {:id :x})))))

(deftest coerce-db-shorthand
  (testing "[:db path = expected] checks equality and carries :expected"
    (let [{:keys [check]} (rf.story.invariants/coerce-invariant 0 [:db [:n] = 5])]
      (is (:ok? (check (epoch 1 {:db-after {:n 5}}))))
      (is (= {:ok? false :path [:n] :expected 5 :actual 4}
             (check (epoch 1 {:db-after {:n 4}})))))))

(deftest coerce-bad-shape-throws
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
               (rf.story.invariants/coerce-invariant 0 :not-an-invariant))))

;; ---- check-epoch -----------------------------------------------------------

(deftest check-epoch-violation-carries-spine
  (let [c (rf.story.invariants/coerce-invariant 0 (fn [e] (pos? (get-in (:db-after e) [:n]))))]
    (is (= {:invariant :invariant-0 :epoch-id 7 :frame :test/frame
            :event :do/thing :trigger-event [:do/thing 42]}
           (rf.story.invariants/check-epoch c (epoch 7 {:event-id      :do/thing
                                                        :trigger-event [:do/thing 42]
                                                        :db-after      {:n -1}}))))))

(deftest check-epoch-isolates-predicate-exception
  (testing "a throwing predicate is caught and reported as a violation with :error"
    (let [c (rf.story.invariants/coerce-invariant 0 (fn [_] (throw (ex-info "boom" {}))))]
      (is (= {:invariant :invariant-0 :epoch-id 1 :frame :test/frame :error "boom"}
             (rf.story.invariants/check-epoch c (epoch 1 {})))))))

;; ---- first-bad-epoch -------------------------------------------------------

(deftest first-bad-epoch-nil-when-holds
  (let [tape [(epoch 1 {:db-after {:n 1}})
              (epoch 2 {:db-after {:n 2}})
              (epoch 3 {:db-after {:n 3}})]]
    (is (nil? (rf.story.invariants/first-bad-epoch tape (fn [e] (pos? (:n (:db-after e))))))))
  (testing "an empty (or nil) tape returns nil — no epoch can fail"
    (is (nil? (rf.story.invariants/first-bad-epoch [] (fn [_] false))))
    (is (nil? (rf.story.invariants/first-bad-epoch nil (fn [_] false))))))

(deftest first-bad-epoch-returns-first-failing
  (testing "returns the FIRST failing epoch, enriched with trigger + db-diff + traces"
    (let [tape [(epoch 1 {:db-after {:n 1}})
                (epoch 2 {:trigger-event [:break]
                          :db-before     {:n 1}
                          :db-after      {:n -5 :extra true}
                          :trace-events  [{:operation :rf.event/run-start}]})
                (epoch 3 {:db-after {:n -9}})]]
      (is (= {:invariant     :invariant-0
              :epoch-id      2
              :frame         :test/frame
              :trigger-event [:break]
              :db-diff       #{:n :extra}
              :trace-events  [{:operation :rf.event/run-start}]
              :epoch         (get tape 1)}
             (rf.story.invariants/first-bad-epoch tape (fn [e] (>= (:n (:db-after e)) 0))))))))

(deftest first-bad-epoch-failure-in-first-epoch
  (let [tape [(epoch 1 {:db-after {:n -1}})
              (epoch 2 {:db-after {:n -2}})]]
    (is (= 1 (:epoch-id (rf.story.invariants/first-bad-epoch tape (fn [e] (pos? (:n (:db-after e))))))))))

(deftest first-bad-epoch-accepts-db-shorthand
  (let [tape [(epoch 1 {:db-after {:cart {:items []}}})
              (epoch 2 {:db-after {:cart {:items :oops}}})]]
    (is (= {:epoch-id 2 :path [:cart :items] :actual :oops}
           (select-keys (rf.story.invariants/first-bad-epoch tape [:db [:cart :items] vector?])
                        [:epoch-id :path :actual])))))

;; ---- on-epoch! (report-once core) ------------------------------------------

(defn- with-captured-reports
  "Run `f`, returning every `clojure.test` / `cljs.test` report it emits
  instead of letting them reach the live reporter."
  [f]
  (let [reports (atom [])]
    #?(:clj
       (binding [clojure.test/report (fn [m] (swap! reports conj m))]
         (f))
       :cljs
       (with-redefs [cljs.test/report (fn [m] (swap! reports conj m))]
         (f)))
    @reports))

(deftest on-epoch-reports-once-per-frame-same-epoch-id
  (testing "two frames with the SAME epoch-id each report once (dedup-key is per-frame)"
    (let [coerced (rf.story.invariants/coerce-invariants [(fn [e] (pos? (:n (:db-after e))))])
          state   (atom {:seen #{} :violations []})
          ep-a    (epoch 1 {:frame :frame/a :db-after {:n -1}})
          ep-b    (epoch 1 {:frame :frame/b :db-after {:n -1}})
          reports (with-captured-reports
                    (fn []
                      (rf.story.invariants/on-epoch! state coerced ep-a)
                      (rf.story.invariants/on-epoch! state coerced ep-b)
                      (rf.story.invariants/on-epoch! state coerced ep-a)
                      (rf.story.invariants/on-epoch! state coerced ep-b)))]
      (is (= 2 (count (filter #(= :fail (:type %)) reports))))
      (is (= [:frame/a :frame/b] (mapv :frame (:violations @state)))))))

;; ---- with-invariants (live sentinel over a real frame, JVM) ----------------

#?(:clj
   (deftest with-invariants-passes-across-multiple-dispatches
     (testing "a holding invariant across multiple dispatches reports only passes"
       (rf/make-frame {:id :test/main})
       (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
       (rf/reg-event :inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))
       (let [reports (with-captured-reports
                       (fn []
                         (with-invariants [(fn [e] (>= (:n (:db-after e)) 0))
                                           [:db [:n] number?]]
                           (rf/dispatch-sync [:seed] {:frame :test/main})
                           (rf/dispatch-sync [:inc]  {:frame :test/main})
                           (rf/dispatch-sync [:inc]  {:frame :test/main}))))]
         (is (zero? (count (filter #(= :fail (:type %)) reports))))
         (is (= 2 (count (filter #(= :pass (:type %)) reports)))
             "one :pass per invariant that held across the run")))))

#?(:clj
   (deftest with-invariants-reports-once-per-failing-epoch
     (rf/make-frame {:id :test/main})
     (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
     (rf/reg-event :dec  (fn [{:keys [db]} _] {:db (update db :n dec)}))
     (let [reports (with-captured-reports
                     (fn []
                       (with-invariants [(fn [e] (>= (:n (:db-after e)) 0))]
                         (rf/dispatch-sync [:seed] {:frame :test/main})  ; n=0  holds
                         (rf/dispatch-sync [:dec]  {:frame :test/main})  ; n=-1 fails
                         (rf/dispatch-sync [:dec]  {:frame :test/main}))))] ; n=-2 fails
       (is (= 2 (count (filter #(= :fail (:type %)) reports)))))))

#?(:clj
   (deftest with-invariants-listener-unregistered-after-body
     (testing "the sentinel listener is removed on exit — later epochs are not observed"
       (rf/make-frame {:id :test/main})
       (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
       (rf/reg-event :dec  (fn [{:keys [db]} _] {:db (update db :n dec)}))
       (let [reports (with-captured-reports
                       (fn []
                         (with-invariants [(fn [e] (>= (:n (:db-after e)) 0))]
                           (rf/dispatch-sync [:seed] {:frame :test/main}))
                         (rf/dispatch-sync [:dec] {:frame :test/main})))]
         (is (zero? (count (filter #(= :fail (:type %)) reports))))))))

;; spec/017 promises the sentinel works with fresh and destroyed frames.
#?(:clj
   (deftest with-invariants-works-with-destroyed-frame
     (rf/make-frame {:id :test/main})
     (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
     (rf/reg-event :inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))
     (let [body-completed (atom false)]
       (with-captured-reports
         (fn []
           (with-invariants [(fn [e] (map? (:db-after e)))]
             (rf/dispatch-sync [:seed] {:frame :test/main})
             (rf/dispatch-sync [:inc]  {:frame :test/main})
             (rf/destroy-frame! :test/main)
             (reset! body-completed true))))
       (is (true? @body-completed)))))
