(ns re-frame.test-support-test
  "Coverage for the public test-flavoured helpers landed under rf2-0l3s
  (resolves rf2-hkr5):

    - dispatch-sequence
    - assert-state

  The fixture machinery (snapshot-registrar / with-fresh-registrar /
  reset-runtime-fixture) is exercised transitively by the rest of the
  test suite — these tests pin the helper *signatures* and the
  per-helper contract in Spec 008 §Built-in test-runner namespace."
  (:require [clojure.test :refer [deftest is testing use-fixtures
                                  do-report report]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.flows :as flows]
            [re-frame.schemas :as schemas]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]
            [re-frame.test-support :as ts]))

;; ---- fixtures -------------------------------------------------------------

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (reset! schemas/schemas-by-frame {})
  (trace/clear-trace-cbs!)
  (rf/init! plain-atom/adapter)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr :reload)
  (require 're-frame.machines :reload)
  (test-fn))

(use-fixtures :each reset-runtime)

;; ---- helpers --------------------------------------------------------------

(defn- record-reports
  "Run `body-fn` with `clojure.test/report` rebound to record into the
  returned atom. The recorded value is the seq of `:type` keys in the
  order they fired so tests can assert pass/fail outcomes from
  helpers that emit through `do-report`."
  [body-fn]
  (let [recorded (atom [])]
    (with-redefs [report (fn [m] (swap! recorded conj (:type m)))]
      (body-fn))
    @recorded))

(defn- register-counter-handlers! []
  (rf/reg-event-db :counter/init (fn [_ _] {:n 0}))
  (rf/reg-event-db :counter/inc  (fn [db _] (update db :n inc)))
  (rf/reg-event-db :counter/dec  (fn [db _] (update db :n dec)))
  (rf/reg-event-db :counter/add
    (fn [db [_ amt]] (update db :n + amt))))

;; ---- dispatch-sequence ----------------------------------------------------

(deftest dispatch-sequence-runs-each-event-in-order
  (testing "events fire in order; final app-db reflects the cumulative result"
    (register-counter-handlers!)
    (rf/dispatch-sync [:counter/init])
    (let [final (ts/dispatch-sequence
                  [[:counter/inc]
                   [:counter/inc]
                   [:counter/inc]
                   [:counter/dec]])]
      (is (= 2 (:n final))
          "three incs and one dec leave :n at 2")
      (is (= final (rf/get-frame-db :rf/default))
          "return value matches the live app-db value"))))

(deftest dispatch-sequence-after-each-captures-intermediate-states
  (testing ":after-each fires once per event with (db, event)"
    (register-counter-handlers!)
    (rf/dispatch-sync [:counter/init])
    (let [seen (atom [])]
      (ts/dispatch-sequence
        [[:counter/inc] [:counter/inc] [:counter/dec]]
        {:after-each (fn [db ev] (swap! seen conj [(:n db) ev]))})
      (is (= [[1 [:counter/inc]]
              [2 [:counter/inc]]
              [1 [:counter/dec]]]
             @seen)
          ":after-each observed each step's committed state"))))

(deftest dispatch-sequence-frame-opt
  (testing ":frame opt routes dispatches to a non-default frame"
    ;; Register handlers first so :on-create can resolve them at
    ;; reg-frame time (Spec 002 §Frame lifecycle).
    (register-counter-handlers!)
    (rf/dispatch-sync [:counter/init])
    (rf/reg-frame :test-support/seq-frame {:on-create [:counter/init]})
    (let [final (ts/dispatch-sequence
                  [[:counter/inc] [:counter/add 5]]
                  {:frame :test-support/seq-frame})]
      (is (= 6 (:n final)))
      (is (= 6 (:n (rf/get-frame-db :test-support/seq-frame))))
      (is (= {:n 0} (rf/get-frame-db :rf/default))
          ":rf/default is unaffected"))))

;; ---- assert-state ---------------------------------------------------------

(deftest assert-state-path-form-pass
  (register-counter-handlers!)
  (rf/dispatch-sync [:counter/init])
  (rf/dispatch-sync [:counter/add 7])
  (let [outcomes (record-reports
                   (fn [] (ts/assert-state [:n] 7)))]
    (is (= [:pass] outcomes)
        "matching path form fires a clojure.test :pass")))

(deftest assert-state-path-form-fail
  (register-counter-handlers!)
  (rf/dispatch-sync [:counter/init])
  (let [outcomes (record-reports
                   (fn [] (ts/assert-state [:n] 99)))]
    (is (= [:fail] outcomes)
        "mismatching path form fires a clojure.test :fail")))

(deftest assert-state-full-db-form
  (register-counter-handlers!)
  (rf/dispatch-sync [:counter/init])
  (let [pass-outcomes (record-reports
                        (fn [] (ts/assert-state {:n 0})))
        fail-outcomes (record-reports
                        (fn [] (ts/assert-state {:n 42})))]
    (is (= [:pass] pass-outcomes))
    (is (= [:fail] fail-outcomes))))

(deftest assert-state-frame-opt
  (testing ":frame opt selects which frame's app-db is asserted against"
    (register-counter-handlers!)
    (rf/dispatch-sync [:counter/init])
    (rf/reg-frame :test-support/assert-frame {:on-create [:counter/init]})
    (rf/dispatch-sync [:counter/add 3] {:frame :test-support/assert-frame})
    (let [outcomes (record-reports
                     (fn []
                       (ts/assert-state [:n] 3 {:frame :test-support/assert-frame})
                       (ts/assert-state [:n] 0 {:frame :rf/default})))]
      (is (= [:pass :pass] outcomes)
          ":rf/default and the named frame each carry their own state"))))

