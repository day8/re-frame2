(ns re-frame.flows-direct-clear-settle-cljs-test
  "Spec 013 §The same boundary for a direct flow clear, on both hosts: when
  `(rf/clear :flow id)` returns, no remaining flow still publishes a value
  derived from the slot it removed. The settle re-evaluates only flows that
  have run since they were (re-)registered; the rest wait for the next drain.

  Every observation is taken with the clear as the only intervening call,
  because a stale dependent would self-heal on any later drain.
  `app-db-value` is a pure read, so observing cannot trigger a pass."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.flows :as rf.flows]
   [re-frame.test-support :as rf.test-support]
   #?(:clj  [re-frame.substrate.plain-atom :as substrate]
      :cljs [re-frame.adapter.reagent :as substrate])))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter substrate/adapter}))

(defn- db [] (rf/app-db-value :rf/default))

(defn- registered? [frame-id flow-id]
  (contains? (get (rf.flows/flows-snapshot) frame-id) flow-id))

(defn- seed-chain!
  "`:probe/a` [:x] -> [:a] and `:probe/b` [:a] -> [:b], which counts its
  derivations in `derives`; seeded to {:x 2 :a 2 :b 2}."
  [derives]
  (rf/reg-event :seed (fn [_ _] {:db {:x 2}}))
  (rf/reg-flow :probe/a {:inputs [[:x]] :output-path [:a]} identity)
  (rf/reg-flow :probe/b {:inputs [[:a]] :output-path [:b]} (fn [a] (swap! derives inc) a))
  (rf/dispatch-sync [:seed]))

(deftest direct-clear-settles-dependents-before-it-returns
  (let [derives (atom 0)]
    (seed-chain! derives)
    (is (= {:x 2 :a 2 :b 2} (db)))
    (rf/clear :flow :probe/a)
    (is (not (registered? :rf/default :probe/a)))
    ;; Only the cleared leaf is vacated; the dependent stays published,
    ;; re-derived once from A's absence.
    (is (= [{:x 2 :b nil} 2] [(db) @derives]))
    (rf/reg-event :noop (fn [_ _] {}))
    (rf/dispatch-sync [:noop])
    (is (= [{:x 2 :b nil} 2] [(db) @derives])
        "an unrelated drain finds nothing left to repair")))

(deftest direct-clear-no-op-paths-stay-silent
  (testing "an unknown flow id settles nothing and derives nothing"
    (let [derives (atom 0)]
      (seed-chain! derives)
      (rf/clear :flow :probe/no-such-flow)
      (is (= [{:x 2 :a 2 :b 2} 1] [(db) @derives]))))
  (testing "an absent frame is a silent no-op, not a throw"
    (is (= :probe/a (rf/clear :flow :probe/a {:frame :probe/never-registered})))))

(deftest direct-clear-malformed-opts-fail-closed-and-touch-nothing
  ;; `{:fram f}` must signal rather than fall back to the ambient frame, which
  ;; holds a flow of the same id: a tolerant destructure would clear that one.
  (rf/make-frame {:id :probe/named})
  (rf/reg-event :seed (fn [_ _] {:db {:x 2}}))
  (doseq [f [:rf/default :probe/named]]
    (rf/reg-flow :probe/a {:frame f :inputs [[:x]] :output-path [:a]} identity)
    (rf/dispatch-sync [:seed] {:frame f}))
  (let [state  (fn [] (mapv (fn [f] [(registered? f :probe/a) (rf/app-db-value f)])
                            [:rf/default :probe/named]))
        before (state)]
    (is (= [[true {:x 2 :a 2}] [true {:x 2 :a 2}]] before))
    (is (= {:rf.error/id :rf.error/registrar-clear-bad-request :reason :malformed-opts :kind :flow}
           (select-keys (ex-data (try (rf/clear :flow :probe/a {:fram :probe/named}) nil
                                      (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e e)))
                        [:rf.error/id :reason :kind])))
    (is (= before (state)) "neither frame's flow nor output was touched")
    (rf/clear :flow :probe/a {:frame :probe/named})
    (is (= [[true {:x 2 :a 2}] [false {:x 2}]] (state))
        "the exact {:frame f} form clears the frame it names, and only that frame")))

(deftest direct-clear-defers-a-never-run-flow-even-over-seeded-inputs
  ;; A flow registered since the last drain has never evaluated, so the clear
  ;; of another flow leaves it for that drain even with its inputs present.
  (let [calls (atom [])]
    (rf/reg-event :seed (fn [_ _] {:db {:user {:name "Ada"}}}))
    (rf/reg-event :noop (fn [_ _] {}))
    (rf/dispatch-sync [:seed])
    (rf/reg-flow :t/greeting {:inputs [[:user :name]] :output-path [:greeting]}
      (fn [n] (swap! calls conj n) (str "Hi " n)))
    (rf/reg-flow :t/legacy {:inputs [[:x]] :output-path [:legacy]} identity)
    (rf/clear :flow :t/legacy)
    (is (= [[] {:user {:name "Ada"}}] [@calls (db)]))
    (rf/dispatch-sync [:noop])
    (is (= "Hi Ada" (:greeting (db))) "the next drain evaluates it")))

(deftest direct-clear-leaves-a-cold-re-registration-for-the-next-drain
  ;; Spec 013 §Re-registration: a dependent re-registered since its last
  ;; evaluation keeps its value (stale but owned), as does anything derived
  ;; from it, until the next drain recomputes both.
  (rf/reg-event :seed (fn [_ _] {:db {:x 2}}))
  (rf/reg-event :noop (fn [_ _] {}))
  (rf/reg-flow :c/a {:inputs [[:x]] :output-path [:a]} identity)
  (rf/reg-flow :c/b {:inputs [[:a]] :output-path [:b]} (fn [a] (some-> a (* 10))))
  (rf/reg-flow :c/c {:inputs [[:b]] :output-path [:c]} (fn [b] (str "c:" b)))
  (rf/dispatch-sync [:seed])
  (is (= {:x 2 :a 2 :b 20 :c "c:20"} (db)))
  (rf/reg-flow :c/b {:inputs [[:a]] :output-path [:b]} (fn [a] (if a (* 100 a) :none)))
  (rf/clear :flow :c/a)
  (is (= {:x 2 :b 20 :c "c:20"} (db)))
  (rf/dispatch-sync [:noop])
  (is (= {:x 2 :b :none :c "c::none"} (db))))
