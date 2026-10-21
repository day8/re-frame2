(ns re-frame.flows-mid-walk-write-settle-cljs-test
  "Spec 013 §Sequencing — a `:fx` walk that writes frame state settles the
  frame's flows inside the same event.

  The flow pass runs before the `:fx` walk, so an effect that writes
  runtime-db — the machine lifecycle effects `:rf.machine/destroy` and
  `:rf.machine/spawn`, or any fx calling `re-frame.frame/swap-runtime-db!` —
  writes after it. When the walk leaves the frame's state container
  non-`identical?` and the frame runs a flow, the event runs the flow pass
  again over the post-walk state and installs its outputs before it ends: one
  event, one epoch, and every flow fresh before any queued continuation runs.
  The settle is keyed on the container, not on a list of writers, so the
  test-local `:p/write-snapshot` effect stands for every writer.

  Epochs and flow traces are dev instrumentation, so the assertions on them
  sit inside `debug-enabled?`; the event and app-db assertions beside them
  hold in every build.

  Lives in core's test tree because only core's `:test` classpath carries the
  machines and flows artefacts together."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.epoch :as rf.epoch]
   [re-frame.event-emit :as rf.event-emit]
   ;; Loaded for their late-bind hooks: without them no flow registers or
   ;; runs, and `reg-machine` and the lifecycle effects do not resolve.
   [re-frame.flows]
   [re-frame.frame :as rf.frame]
   [re-frame.interop :as rf.interop]
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

(defn- epochs [] (count (rf.epoch/epoch-history :rf/default)))

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

(defn- reg-write-snapshot-fx!
  "`[:p/write-snapshot [path value]]` writes `value` at `path` inside machine
  `:pm/m`'s snapshot, mid-walk, through `swap-runtime-db!`."
  []
  (rf/reg-fx :p/write-snapshot
    (fn [{frame-id :frame} [path value]]
      (rf.frame/swap-runtime-db! frame-id assoc-in
                                 (into [:rf.runtime/machines :snapshots :pm/m] path)
                                 value)
      nil)))

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
  (rf/make-frame {:id :rf/default :flows [:pf/state :pf/note]})
  (rf/dispatch-sync [:pm/m [:go]])
  (rf/dispatch-sync [:pm/m [:go]]))

(deftest a-write-on-a-frame-with-flows-settles-inside-its-event
  (testing "one walk with two snapshot writes leaves both flows fresh after the
            one dispatch, as one event and one epoch"
    (reg-machine-and-flows!)
    (reg-write-snapshot-fx!)
    (rf/reg-event :p/two-writes
      (fn [_ _]
        {:fx [[:p/write-snapshot [[:state] :busy]]
              [:p/write-snapshot [[:data :note] "x"]]]}))
    (call-counting-runs
      (fn [runs]
        (let [before (epochs)]
          (rf/dispatch-sync [:p/two-writes])
          (is (= {:mstate :busy :mnote "x"} (select-keys (db) [:mstate :mnote])))
          (is (= [:p/two-writes] @runs))
          (when rf.interop/debug-enabled?
            (is (= 1 (- (epochs) before)))))))))

(deftest a-continuation-queued-before-spawn-sees-the-newborn
  (testing "a `:dispatch` placed before `:rf.machine/spawn` reads the newborn's
            flow, and the spawn adds no event and no epoch of its own"
    (rf/reg-machine :m/child {:initial :born :states {:born {}}})
    (rf/reg-flow :pf/child
      {:inputs      [[:rf.db/runtime :rf.runtime/machines :snapshots :c/one :state]]
       :output-path [:cstate]}
      identity)
    (rf/make-frame {:id :rf/default :flows [:pf/child]})
    (rf/reg-event :p/record-child
      (fn [{:keys [db]} _] {:db (assoc db :child-seen (:cstate db))}))
    (rf/reg-event :p/spawn
      (fn [_ _]
        {:fx [[:dispatch [:p/record-child]]
              [:rf.machine/spawn {:machine-id :m/child :fixed-actor-id :c/one}]]}))
    (call-counting-runs
      (fn [runs]
        (let [before (epochs)]
          (rf/dispatch-sync [:p/spawn])
          (is (= :born (:child-seen (db))))
          ;; The spawn's bootstrap event is the machine's own dispatch.
          (is (= [:p/spawn :p/record-child :c/one] @runs))
          (when rf.interop/debug-enabled?
            (is (= :born (->> (rf.epoch/epoch-history :rf/default)
                              (filter #(= :p/spawn (:event-id %)))
                              first :db-after :cstate))
                "the spawning event's own epoch holds the fresh flow, so a restore to it is consistent")
            (is (= (count @runs) (- (epochs) before)) "one epoch per event")))))))

(deftest walks-that-write-no-frame-state-run-no-second-pass
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
    (rf/make-frame {:id :rf/default :flows [:pf/state :pf/note :pf/timed]})
    (rf/dispatch-sync [:pm/timed [:go]])
    (rf/dispatch-sync [:pm/timed [:go]])
    (let [visits (atom 0)]
      (rf/register-listener! :trace ::visits
        (fn [ev]
          (when (and (= :pf/timed (get-in ev [:tags :flow-id]))
                     (#{:rf.flow/computed :rf.flow/skip} (:operation ev)))
            (swap! visits inc))))
      (try
        (call-counting-runs
          (fn [runs]
            (rf/dispatch-sync [:pm/timed [:go]])
            (is (= :busy (:tstate (db))) "fresh after the transition")
            (is (pos? (count @runs)) "control — the recorder saw the transition")
            (when rf.interop/debug-enabled?
              (is (= (count @runs) @visits) "one flow pass per event"))))
        (finally
          (rf/unregister-listener! :trace ::visits))))))

(deftest a-frame-with-no-flows-pays-nothing
  (testing "a mid-walk write on a frame running no flows runs no flow pass"
    (rf/reg-machine :pm/m
      {:initial :idle :states {:idle {:on {:go :busy}} :busy {:on {:go :idle}}}})
    (reg-write-snapshot-fx!)
    (rf/reg-event :p/write
      (fn [_ [_ path value]]
        {:fx [[:p/write-snapshot [path value]]]}))
    (rf/dispatch-sync [:pm/m [:go]])
    (call-counting-runs
      (fn [runs]
        (rf/dispatch-sync [:p/write [:state] :idle])
        (is (= :idle (:state (snapshot :pm/m))) "control — the write landed")
        (is (= [:p/write] @runs))))))
