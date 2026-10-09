(ns re-frame.flows-mid-walk-write-settle-cljs-test
  "Spec 013 §Sequencing — a `:fx` walk that writes frame state settles the
  frame's flows.

  The flow pass runs before the `:fx` walk, so the machine lifecycle effects
  (`:rf.machine/update-snapshot`, `:rf.machine/destroy`, `:rf.machine/spawn`)
  write runtime-db after it. When the walk leaves the frame's state container
  non-`identical?` and the frame holds a flow, the walk enqueues one
  head-inserted settle; otherwise it enqueues none.

  Lives in core's test tree because only core's `:test` classpath carries both
  the machines and the flows artefacts."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.event-emit :as rf.event-emit]
   ;; Loaded for their late-bind hooks: without them no flow registers, and
   ;; `reg-machine` and the lifecycle effects do not resolve.
   [re-frame.flows]
   [re-frame.machines]
   [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
   [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter}))

(defn- snapshot
  [actor-id]
  (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
          [:rf.runtime/machines :snapshots actor-id]))

(defn- db [] (rf/app-db-value :rf/default))

(defn- call-counting-runs
  "Run `(f runs)` with `runs` recording the id of every processed event. It
  listens on the always-on event-emit stream because traces are compiled out
  under the production gate, and registers inside the test because the reset
  fixture clears listeners."
  [f]
  (let [runs (atom [])]
    (rf.event-emit/register-event-listener!
      ::run-recorder
      (fn [record] (swap! runs conj (:event-id record))))
    (try
      (f runs)
      (finally
        (rf.event-emit/unregister-event-listener! ::run-recorder)))))

(defn- settles [runs] (count (filter #{:rf/settle-flows} @runs)))

(defn- reg-machine-and-flows!
  "A two-state machine `:pm/m` and two flows over its snapshot — `:state` to
  `[:mstate]` and `[:data :note]` to `[:mnote]` — both established."
  []
  (rf/reg-machine :pm/m
    {:initial :idle
     :data    {:note "orig"}
     :states  {:idle {:on {:go :busy}}
               :busy {:on {:go :idle}}}})
  (rf/reg-flow :pf/state
    {:inputs      [[:rf.db/runtime :rf.runtime/machines :snapshots :pm/m :state]]
     :output-path [:mstate]}
    identity)
  (rf/reg-flow :pf/note
    {:inputs      [[:rf.db/runtime :rf.runtime/machines :snapshots :pm/m :data :note]]
     :output-path [:mnote]}
    identity)
  (rf/dispatch-sync [:pm/m [:go]])
  (rf/dispatch-sync [:pm/m [:go]]))

(deftest a-write-on-a-frame-with-flows-settles-exactly-once
  (testing "one walk with two snapshot writes leaves both flows fresh after the
            one dispatch, through exactly one settle"
    (reg-machine-and-flows!)
    (rf/reg-event :p/two-patches
      (fn [_ _]
        {:fx [[:rf.machine/update-snapshot {:rf/machine-id :pm/m :rf/patch {:state :busy}}]
              [:rf.machine/update-snapshot {:rf/machine-id :pm/m :rf/patch {:data {:note "x"}}}]]}))
    (call-counting-runs
      (fn [runs]
        (rf/dispatch-sync [:p/two-patches])
        (is (= {:mstate :busy :mnote "x"} (select-keys (db) [:mstate :mnote])))
        (is (= [:p/two-patches :rf/settle-flows] @runs))))))

(deftest a-continuation-queued-before-spawn-sees-the-newborn
  (testing "a `:dispatch` placed before `:rf.machine/spawn` reads the newborn's
            flow: the spawn's bootstrap dispatch is FIFO behind it, so only the
            head-inserted settle can refresh the flow first"
    (rf/reg-machine :m/child {:initial :born :states {:born {}}})
    (rf/reg-flow :pf/child
      {:inputs      [[:rf.db/runtime :rf.runtime/machines :snapshots :c/one :state]]
       :output-path [:cstate]}
      identity)
    (rf/reg-event :p/record-child
      (fn [{:keys [db]} _] {:db (assoc db :child-seen (:cstate db))}))
    (rf/reg-event :p/spawn
      (fn [_ _]
        {:fx [[:dispatch [:p/record-child]]
              [:rf.machine/spawn {:machine-id :m/child :fixed-actor-id :c/one}]]}))
    (rf/dispatch-sync [:p/spawn])
    (is (= :born (:child-seen (db))))))

(deftest walks-that-write-no-frame-state-enqueue-no-settle
  (testing "a `{:fx [[:dispatch ...]]}` walk on a frame with flows"
    (reg-machine-and-flows!)
    (rf/reg-event :p/noop (fn [_ _] {}))
    (rf/reg-event :p/dispatch-only (fn [_ _] {:fx [[:dispatch [:p/noop]]]}))
    (call-counting-runs
      (fn [runs]
        (rf/dispatch-sync [:p/dispatch-only])
        (is (= [:p/dispatch-only :p/noop] @runs)))))

  (testing "a machine transition whose :entry returns :fx and arms an :after timer"
    (rf/reg-machine :pm/timed
      {:initial :idle
       :states  {:idle {:on {:go :busy}}
                 :busy {:entry (fn [_] {:fx [[:dispatch [:p/noop]]]})
                        :after {60000 :idle}
                        :on    {:go :idle}}}})
    (rf/reg-flow :pf/timed
      {:inputs      [[:rf.db/runtime :rf.runtime/machines :snapshots :pm/timed :state]]
       :output-path [:tstate]}
      identity)
    (rf/dispatch-sync [:pm/timed [:go]])
    (rf/dispatch-sync [:pm/timed [:go]])
    (call-counting-runs
      (fn [runs]
        (rf/dispatch-sync [:pm/timed [:go]])
        (is (= :busy (:tstate (db))) "fresh after the transition")
        (is (pos? (count @runs)) "control — the recorder saw the transition")
        (is (zero? (settles runs)))))))

(deftest a-frame-with-no-flows-pays-nothing
  (testing "update-snapshot on a frame holding no flows enqueues no settle"
    (rf/reg-machine :pm/m
      {:initial :idle :states {:idle {:on {:go :busy}} :busy {:on {:go :idle}}}})
    (rf/reg-event :p/patch
      (fn [_ [_ patch]]
        {:fx [[:rf.machine/update-snapshot {:rf/machine-id :pm/m :rf/patch patch}]]}))
    (rf/dispatch-sync [:pm/m [:go]])
    (call-counting-runs
      (fn [runs]
        (rf/dispatch-sync [:p/patch {:state :idle}])
        (is (= :idle (:state (snapshot :pm/m))) "control — the patch landed")
        (is (= [:p/patch] @runs))))))
