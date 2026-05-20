(ns re-frame.router-transducer-test
  "Unit coverage for the v1.1 transducer-router Phase-1 scaffold
  (rf2-cl8me). Non-normative — these tests pin the scaffold's pure-fn
  surface so the design at spec/Design-TransducerRouter.md is exercisable.

  The scaffold does not touch the live runtime; these tests construct
  ad-hoc envelopes + frames and walk them through the transducer +
  reducing functions directly. No registrar / no router / no substrate."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.router-transducer :as rt]))

;; ---- test-only helpers ----------------------------------------------------

(defn- envelope
  "Construct a minimal dispatch envelope. The frame here is a plain map
  carrying `:handlers` + `:db`; real wiring would point at the live
  frame record."
  [event handlers db & {:as extras}]
  (merge {:event event
          :frame {:handlers handlers
                  :db       db}}
         extras))

(defn- transduce-one
  "Drive one envelope through (factory frame) + rf. Returns the final acc."
  [envelope rf]
  (let [tdx (rt/frame-transducer-factory (:frame envelope))]
    (transduce tdx rf [envelope])))

;; ---- transducer / step-result coverage -----------------------------------

(deftest step-result-shape
  (testing "happy path produces a fully-tagged step-result"
    (let [handlers {:inc (fn [db _] {:db (update db :n inc)})}
          e        (envelope [:inc] handlers {:n 0})
          steps    (into [] (rt/frame-transducer-factory (:frame e)) [e])]
      (is (= 1 (count steps)))
      (let [step (first steps)]
        (is (= :ok (:rf/step step)))
        (is (= {:n 0} (:db-before step)))
        (is (= {:n 1} (:db-after step)))
        (is (= {:db {:n 1}} (:effects step)))
        (is (= [] (:fx step)))))))

(deftest no-handler-step-tag
  (testing "missing handler short-circuits with :rf/step :no-handler"
    (let [e     (envelope [:missing] {} {:n 0})
          steps (into [] (rt/frame-transducer-factory (:frame e)) [e])
          step  (first steps)]
      (is (= :no-handler (:rf/step step)))
      (is (= {:n 0} (:db-after step)) "db preserved on no-handler"))))

(deftest handler-throw-tags-error
  (testing "handler exception is caught and surfaced as :handler-throw"
    (let [handlers {:boom (fn [_ _] (throw (ex-info "boom" {})))}
          e        (envelope [:boom] handlers {:n 0})
          steps    (into [] (rt/frame-transducer-factory (:frame e)) [e])
          step     (first steps)]
      (is (= :handler-throw (:rf/step step)))
      (is (= {:n 0} (:db-after step)) "db rolled back on throw")
      (is (some? (:error step)))
      (is (= :rf.error/handler-throw (get-in step [:error :operation]))))))

(deftest validate-event-short-circuits
  (testing "failed :validate-event fence short-circuits before run"
    (let [seen (atom false)
          handlers {:x (fn [_ _] (reset! seen true) {:db {:n 1}})}
          e (envelope [:x] handlers {:n 0}
                      :validate-event (fn [ev] (= ev [:y]))) ;; fails
          steps (into [] (rt/frame-transducer-factory (:frame e)) [e])
          step  (first steps)]
      (is (= :validation (:rf/step step)))
      (is (false? @seen) "handler never invoked when validation fails")
      (is (= {:n 0} (:db-after step))))))

(deftest fx-staging
  (testing "non-:db effects are normalised into a flat :fx seq"
    (let [handlers {:multi (fn [_ _]
                             {:db {:n 1}
                              :fx [[:dispatch [:next]]]
                              :http {:url "/x"}})}
          e        (envelope [:multi] handlers {:n 0})
          steps    (into [] (rt/frame-transducer-factory (:frame e)) [e])
          step     (first steps)]
      (is (= :ok (:rf/step step)))
      (is (= {:n 1} (:db-after step)))
      (is (= [[:dispatch [:next]] [:http {:url "/x"}]] (:fx step))
          "extras after :fx in source order"))))

;; ---- reducing-function coverage ------------------------------------------

(deftest sync-rf-commits-eagerly
  (testing "sync-rf folds db-after onto :db and walks fx inline"
    (let [handlers {:inc (fn [db _] {:db (update db :n inc)
                                     :fx [[:log :did-inc]]})}
          rf (rt/sync-rf)
          acc (transduce-one (envelope [:inc] handlers {:n 0}) rf)]
      (is (= {:n 1} (:db acc)))
      (is (= [[:log :did-inc]] (:fx-applied acc)))
      (is (= 1 (count (:steps acc))))
      (is (= [] (:errors acc))))))

(deftest sync-rf-folds-multiple-envelopes
  (testing "sync-rf applied across multiple envelopes accumulates db"
    (let [handlers {:inc (fn [db _] {:db (update db :n inc)})}
          frame    {:handlers handlers :db {:n 0}}
          ;; Each envelope sees the SAME :db (the frame snapshot) because
          ;; the scaffold does not thread acc->frame; real wiring would
          ;; rebind the frame's :db slot via the substrate container.
          envs     [(envelope [:inc] handlers {:n 0})
                    (envelope [:inc] handlers {:n 1})
                    (envelope [:inc] handlers {:n 2})]
          tdx      (rt/frame-transducer-factory frame)
          acc      (transduce tdx (rt/sync-rf) envs)]
      (is (= {:n 3} (:db acc)) "last db-after wins")
      (is (= 3 (count (:steps acc)))))))

(deftest queued-rf-routes-dispatches-to-queue
  (testing "queued-rf segregates :dispatch fx from other fx"
    (let [handlers {:start (fn [_ _]
                             {:db {:n 1}
                              :fx [[:dispatch [:next]]
                                   [:log :started]
                                   [:dispatch [:also-next]]]})}
          rf  (rt/queued-rf)
          acc (transduce-one (envelope [:start] handlers {:n 0}) rf)]
      (is (= {:n 1} (:db acc)))
      (is (= [[:log :started]] (:fx-applied acc))
          ":dispatch entries do NOT land in :fx-applied")
      (is (= [[:next] [:also-next]] (vec (:queue acc)))
          ":dispatch entries land on :queue in source order"))))

(deftest batch-rf-defers-fx-until-completing
  (testing "batch-rf accumulates fx but applies them only on completing arity"
    (let [handlers {:a (fn [_ _] {:db {:n 1} :fx [[:log :a]]})
                    :b (fn [_ _] {:db {:n 2} :fx [[:log :b]]})}
          frame    {:handlers handlers :db {:n 0}}
          envs     [(envelope [:a] handlers {:n 0})
                    (envelope [:b] handlers {:n 1})]
          tdx      (rt/frame-transducer-factory frame)
          ;; transduce calls the completing arity automatically
          acc      (transduce tdx (rt/batch-rf) envs)]
      (is (= {:n 2} (:db acc)) "last db wins")
      (is (true? (:committed? acc)) "completing arity stamps :committed?")
      (is (= [[:log :a] [:log :b]] (:fx-applied acc))
          "both fx applied at completing")
      (is (= [] (:staged-fx acc)) "staged buffer cleared after commit"))))

(deftest batch-rf-skips-fx-without-completing
  (testing "fx are NOT applied when only the step arity runs (no completing)"
    (let [handlers {:a (fn [_ _] {:db {:n 1} :fx [[:log :a]]})}
          rf       (rt/batch-rf)
          init     (rf)
          step1    (first (into []
                                (rt/frame-transducer-factory
                                  {:handlers handlers :db {:n 0}})
                                [(envelope [:a] handlers {:n 0})]))
          acc      (rf init step1)]
      (is (= {:n 1} (:db acc)))
      (is (= [[:log :a]] (:staged-fx acc)) "fx staged, not applied")
      (is (= [] (:fx-applied acc)) "no fx applied yet")
      (is (false? (:committed? acc))))))

;; ---- driver coverage ------------------------------------------------------

(deftest manual-driver-tick-one-at-a-time
  (testing "manual driver pumps one envelope per tick!"
    (let [handlers {:inc (fn [db _] {:db (update db :n inc)})}
          frame    {:handlers handlers :db {:n 0}}
          {:keys [push! tick!]} (rt/manual-driver)]
      (push! (envelope [:inc] handlers {:n 0}))
      (push! (envelope [:inc] handlers {:n 0}))
      (let [acc1 (tick! frame (rt/sync-rf))]
        (is (= {:n 1} (:db acc1))))
      (let [acc2 (tick! frame (rt/sync-rf))]
        (is (= {:n 1} (:db acc2)) "second tick processes second envelope")
        (is (= 2 (count (:steps acc2)))
            "steps accumulate across ticks")))))

(deftest manual-driver-drain-to-fixed-point
  (testing "drain! processes everything until queue empties"
    (let [handlers {:inc (fn [db _] {:db (update db :n inc)})}
          frame    {:handlers handlers :db {:n 0}}
          {:keys [push! drain!]} (rt/manual-driver)]
      (dotimes [_ 5] (push! (envelope [:inc] handlers {:n 0})))
      (let [acc (drain! frame (rt/sync-rf))]
        (is (= 5 (count (:steps acc))))
        (is (= {:n 1} (:db acc))
            "frame-db is constant across envelopes in the scaffold")))))
