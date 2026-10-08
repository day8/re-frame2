(ns re-frame.machine-spawn-unregistered-type-cljs-test
  "A spawn whose `:machine-id` names no registered machine TYPE (and carries no
  inline `:definition`) is rejected fail-closed: nothing installs, and exactly
  one always-on `:rf.error/machine-spawn-unregistered-type` per offending child
  fans out carrying structural context only — never the spawn args. A
  `:spawn-all` with such a child rejects the whole invoke rather than hanging
  its `:all` join.

  Runs on both hosts: the always-on `:errors` fan-out and the dev trace are
  host wiring that could regress on one runtime alone."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [clojure.string :as str]
   [re-frame.core :as rf]
   [re-frame.error-emit :as rf.error-emit]
   [re-frame.machines]
   [re-frame.machines.test-support :as rf.machines.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

;; The always-on error-listener registry is a `defonce` atom: clear it per test.
(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    {:adapter #?(:clj  rf.substrate.plain-atom/adapter
                 :cljs rf.adapter.reagent/adapter)
     :init-fn (fn [] (rf.error-emit/clear-error-listeners!))})
  rf.machines.test-support/trace-capture-fixture)

(def ^:private frame-db rf.machines.test-support/runtime-db)

(defn- with-error-records
  "Run `thunk` under an always-on error listener; return the
  unregistered-type records it saw, oldest first."
  [thunk]
  (let [seen (atom [])]
    (rf.error-emit/register-error-listener! ::recorder (fn [r] (swap! seen conj r)))
    (try
      (thunk)
      (filterv #(= :rf.error/machine-spawn-unregistered-type (:error %)) @seen)
      (finally (rf.error-emit/unregister-error-listener! ::recorder)))))

(defn- reject-traces []
  (rf.machines.test-support/events-of :rf.error/machine-spawn-unregistered-type))

(deftest single-spawn-of-unregistered-type-installs-nothing
  (let [secret "super-secret-token"]
    ;; :ghost/worker is never registered.
    (rf/reg-machine :sup/ghost
      {:initial :idle
       :states  {:idle    {:on {:start :working}}
                 :working {:spawn {:machine-id :ghost/worker
                                   :data       {:auth secret}
                                   :start      [:go {:token secret}]}}}})
    (let [records (with-error-records #(rf/dispatch-sync [:sup/ghost [:start]]))]
      (is (= [:working nil]
             [(rf.machines.test-support/machine-state :sup/ghost)
              (get-in (frame-db) [:rf.runtime/machines :spawned :sup/ghost])])
          "the parent still transitions; the rejected child gets no spawn slot")
      (is (empty? (rf.machines.test-support/events-of :rf.machine.spawn/spawned))
          "the spawn cascade never ran")
      (testing "the always-on record and the dev trace carry structural context only"
        (is (= [{:error      :rf.error/machine-spawn-unregistered-type
                 :machine-id :ghost/worker
                 :frame      :rf/default
                 :recovery   :no-recovery
                 :reason     true
                 :time       true}]
               (mapv #(-> % (update :reason string?) (update :time number?)) records)))
        (is (= [:ghost/worker] (mapv #(get-in % [:tags :machine-id]) (reject-traces))))
        (is (not (str/includes? (pr-str [records (reject-traces)]) secret))
            "no :start / :data value rides either surface")))))

(deftest spawn-all-emits-exactly-one-reject-per-offending-child
  ;; :card/missing-a and :card/missing-b are never registered.
  (rf/reg-machine :card/ok {:initial :running :data {} :states {:running {}}})
  (rf/reg-machine :sup/card
    {:initial :idle
     :states  {:idle    {:on {:start :forking}}
               :forking {:spawn-all {:children        [{:id :a  :machine-id :card/missing-a}
                                                       {:id :ok :machine-id :card/ok}
                                                       {:id :b  :machine-id :card/missing-b}]
                                     :join            :all
                                     :on-all-complete [:all/done]}
                         :on        {:all/done :ready :back :idle}}
               :ready   {}}})
  (let [records (with-error-records #(rf/dispatch-sync [:sup/card [:start]]))
        slot    #(get-in (frame-db) [:rf.runtime/machines :spawned :sup/card [:forking]])]
    ;; The invoke-level preflight is the sole emitter: a child-local re-check
    ;; would double every record.
    (is (= [:card/missing-a :card/missing-b] (sort (map :machine-id records)))
        "one always-on record per offending child — never two, never the sibling")
    (is (= [:card/missing-a :card/missing-b] (sort (map #(get-in % [:tags :machine-id]) (reject-traces))))
        "and one dev trace per offending child")
    (is (= [{:rf/spawn-all-rejected? true} :forking nil]
           [(slot)
            (rf.machines.test-support/machine-state :sup/card)
            (rf.machines.test-support/snapshot :card/ok#1)])
        "a childless reject sentinel stands in for the join, and the registered sibling is suppressed")
    (is (empty? (rf.machines.test-support/events-of :rf.machine.spawn/spawned))
        "no child of the rejected invoke reaches the spawn cascade")
    (rf/dispatch-sync [:sup/card [:back]])
    (is (nil? (slot)) "parent exit clears the reject sentinel")))
