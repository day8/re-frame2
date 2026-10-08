(ns re-frame.machine-noop-signal-test
  "A declined event is signalled once, by the benign unhandled no-op, never also by
  a no-change `:rf.machine/transition`; only a machine's first start signals its
  birth, by `:rf.machine/started`."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            ;; Installs the late-bind hooks `rf/reg-machine` resolves through.
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- record-traces! [body-fn]
  (rf.machines.test-support/with-trace-capture seen
    (body-fn)
    @seen))

(defn- ops [evs op] (filterv #(= op (:operation %)) evs))

(defn- reg-tl! []
  (rf/reg-machine :rf2-coozg/tl {:initial :red :states {:red {}}}))

(deftest declined-events-emit-only-the-no-op-signal
  (reg-tl!)
  (rf/reg-machine :rf2-coozg/par
    {:type    :parallel
     :regions {:a {:initial :a0 :states {:a0 {}}}
               :b {:initial :b0 :states {:b0 {}}}}})
  ;; For the parallel machine every region declines, so it signals once in aggregate.
  (doseq [machine-id [:rf2-coozg/tl :rf2-coozg/par]]
    (rf/dispatch-sync [machine-id [:rf.machine/start]])
    (let [evs (record-traces! #(rf/dispatch-sync [machine-id [:no-such-event]]))]
      (is (= [1 0] (mapv #(count (ops evs %)) [:rf.machine.event/unhandled-no-op :rf.machine/transition]))
          (str machine-id)))))

(deftest only-the-first-start-signals-birth
  ;; A start is a pure init-kick, so it never emits a `:rf.machine/transition`.
  (reg-tl!)
  (let [start-signals (fn []
                        (let [evs (record-traces! #(rf/dispatch-sync [:rf2-coozg/tl [:rf.machine/start]]))]
                          [(mapv (comp (juxt :machine-id :state :cause) :tags) (ops evs :rf.machine/started))
                           (ops evs :rf.machine/transition)]))]
    (is (= [[[:rf2-coozg/tl :red :explicit]] []] (start-signals)) "the first start")
    (is (= [[] []] (start-signals)) "a redundant start on the live machine")))
