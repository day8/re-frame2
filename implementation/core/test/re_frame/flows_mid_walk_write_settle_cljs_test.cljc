(ns re-frame.flows-mid-walk-write-settle-cljs-test
  "Spec 013 §Sequencing — a `:fx` walk that WRITES frame state settles the
  frame's flows (rf2-3x7nj.9.7).

  A flow may read machine state (`[:rf.db/runtime :rf.runtime/machines
  :snapshots <id> ...]` is Spec 013's own example input). The flow pass is the
  router's outermost `:after`, so it runs BEFORE the `:fx` walk. The machine
  lifecycle effects `:rf.machine/update-snapshot`, `:rf.machine/destroy` and
  `:rf.machine/spawn` write runtime-db DURING that walk — after the pass that
  would have acted on it. Before the fix nothing asked for a settle, so a flow
  over the snapshot kept publishing the pre-write value until some later,
  unrelated event happened to drain the frame. Worse, a continuation the same
  handler queued read the stale flow and could persist that wrong decision.

  The fix is generic rather than per-writer: when the walk leaves the frame's
  state container non-`identical?` to its value at walk start, and the frame
  holds at least one flow, the walk requests the SAME one head-inserted settle
  the reserved flow effects already request. A per-writer list was rejected
  because it was wrong on day one — both reviews of this defect missed spawn,
  whose bootstrap dispatch is FIFO and so runs BEHIND a continuation queued
  ahead of it.

  The CONTROLS below matter as much as the regressions: a walk that writes
  nothing, a machine transition (which commits through the pending runtime-db
  effect the flow pass already reads), a frame with NO flows and a dry run
  must all enqueue ZERO settles. Without the flows guard, 50 `update-snapshot`
  dispatches on a flow-free frame became 100 events.

  Lives in core's test tree because core's `:test` classpath carries both the
  machines and the flows artefacts; neither artefact's own `:test` alias
  carries the other. Named `*_cljs_test.cljc` so both the JVM runner and the
  shadow-cljs `:node-test` build discover it — the walk is shared `.cljc`."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.event-emit :as rf.event-emit]
   ;; Loading these publishes their late-bind hooks. Without `re-frame.flows`
   ;; no flow is ever registered and every assertion below reads the stale
   ;; value for the wrong reason; without `re-frame.machines` `reg-machine`
   ;; and the lifecycle effects do not resolve.
   [re-frame.flows]
   [re-frame.fx :as rf.fx]
   [re-frame.machines]
   [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
   [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter}))

;; ---- helpers ---------------------------------------------------------------

(defn- snapshot
  [actor-id]
  (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
          [:rf.runtime/machines :snapshots actor-id]))

(defn- db [] (rf/app-db-value :rf/default))

(defn- call-counting-runs
  "Run `(f runs)` with a listener recording the event id of every processed
  event, in order.

  The listener is on the ALWAYS-ON event-emit substrate (one record per
  processed event), not on the dev trace stream: the core artefact also runs
  under the production gate (`-Dre-frame.debug=false`), where traces are
  compiled out and a trace-based counter would read zero for every
  zero-settle control below.

  Installed INSIDE the test body, never at namespace load: the reset fixture
  clears listeners, so a load-time listener counts nothing and every
  zero-settle control below would pass against a dead instrument. Each caller
  therefore also asserts the total run count it expects — the positive
  control that the recorder is live."
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
  `[:mstate]` and `[:data :note]` to `[:mnote]`."
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
  ;; An ordinary transition materialises the actor's snapshot, and its own
  ;; flow pass reads it, so both flows are established before any test acts.
  (rf/dispatch-sync [:pm/m [:go]])
  (rf/dispatch-sync [:pm/m [:go]]))

(defn- patch-event! []
  (rf/reg-event :p/patch
    (fn [_ [_ patch]]
      {:fx [[:rf.machine/update-snapshot {:rf/machine-id :pm/m :rf/patch patch}]]})))

;; ---------------------------------------------------------------------------
;; Regressions — RED before the fix, GREEN after
;; ---------------------------------------------------------------------------

(deftest update-snapshot-settles-a-flow-over-the-snapshot
  (testing "a flow over an actor's snapshot reflects `:rf.machine/update-snapshot`
            after ONE dispatch — no unrelated follow-up drain"
    (reg-machine-and-flows!)
    (patch-event!)
    (is (= :idle (:state (snapshot :pm/m))) "precondition — the actor is :idle")
    (is (= {:mstate :idle :mnote "orig"} (select-keys (db) [:mstate :mnote]))
        "precondition — both flows are established over the snapshot")

    (rf/dispatch-sync [:p/patch {:state :busy}])
    (is (= :busy (:state (snapshot :pm/m))) "the patch landed on the snapshot")
    ;; THE DEFECT, `:state` leg. Red before the fix: `:idle`.
    (is (= :busy (:mstate (db)))
        "the flow over the snapshot's :state is fresh after the ONE dispatch")

    (rf/dispatch-sync [:p/patch {:data {:note "patched"}}])
    (is (= "patched" (get-in (snapshot :pm/m) [:data :note])))
    ;; THE DEFECT, `:data` leg. Red before the fix: "orig".
    (is (= "patched" (:mnote (db)))
        "the flow over the snapshot's :data is fresh after the ONE dispatch")))

(deftest destroy-settles-a-flow-over-the-snapshot
  (testing "after ONE `[:rf.machine/destroy <id>]` the flow over the destroyed
            actor's snapshot reads nil"
    (reg-machine-and-flows!)
    (rf/reg-event :p/destroy (fn [_ _] {:fx [[:rf.machine/destroy :pm/m]]}))
    (is (= :idle (:mstate (db))) "precondition")

    (rf/dispatch-sync [:p/destroy])
    (is (nil? (snapshot :pm/m)) "the actor's snapshot is gone")
    ;; Red before the fix: `:idle` / "orig" survive the actor.
    (is (nil? (:mstate (db))) "the :state flow reads the absence")
    (is (nil? (:mnote (db))) "the :data flow reads the absence")))

(deftest a-continuation-after-update-snapshot-reads-the-new-value
  (testing "a `:dispatch` queued by the SAME handler after the patch reads the
            settled flow, not the stale one — assert what it RECORDED, because
            the final flow value is right either way once the queue drains"
    (reg-machine-and-flows!)
    (rf/reg-event :p/record
      (fn [{:keys [db]} _] {:db (assoc db :recorded (:mstate db))}))
    (rf/reg-event :p/patch-then-record
      (fn [_ _]
        {:fx [[:rf.machine/update-snapshot {:rf/machine-id :pm/m
                                            :rf/patch      {:state :busy}}]
              [:dispatch [:p/record]]]}))

    (rf/dispatch-sync [:p/patch-then-record])
    ;; Red before the fix: `:idle` — the continuation ran before any drain had
    ;; re-read the snapshot, and persisted that wrong decision.
    (is (= :busy (:recorded (db)))
        "the continuation recorded the post-patch flow value")))

(deftest a-continuation-queued-before-spawn-sees-the-newborn
  (testing "a `:dispatch` placed BEFORE `:rf.machine/spawn` in one fx vector reads
            the newborn's flow — the spawn's bootstrap dispatch is FIFO, so it
            runs behind that continuation and cannot refresh the flow for it"
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
    (is (= :born (:state (snapshot :c/one))) "precondition — the child spawned")
    ;; Red before the fix: nil — the continuation ran before the bootstrap.
    (is (= :born (:child-seen (db)))
        "the continuation queued ahead of the spawn saw the newborn's state")))

;; ---------------------------------------------------------------------------
;; Controls — green before and after
;; ---------------------------------------------------------------------------

(deftest an-ordinary-transition-is-already-fresh
  (testing "a machine transition commits through the pending runtime-db effect
            the flow pass reads, so it is fresh after one dispatch and needs
            no settle"
    (reg-machine-and-flows!)
    (call-counting-runs
      (fn [runs]
        (rf/dispatch-sync [:pm/m [:go]])
        (is (= :busy (:mstate (db))) "fresh after the one transition")
        (is (= 1 (count @runs)) "control — the recorder saw the one event")
        (is (zero? (settles runs)) "and no settle was enqueued")))))

(deftest walks-that-write-no-frame-state-enqueue-no-settle
  (testing "a walk whose fx write no frame state enqueues ZERO settles on a
            frame WITH flows"
    (reg-machine-and-flows!)
    (rf/reg-event :p/noop (fn [_ _] {}))
    (rf/reg-event :p/dispatch-only (fn [_ _] {:fx [[:dispatch [:p/noop]]]}))
    (call-counting-runs
      (fn [runs]
        (dotimes [_ 5] (rf/dispatch-sync [:p/dispatch-only]))
        (is (= 10 (count @runs))
            "control — 5 dispatches, each with its one continuation")
        (is (zero? (settles runs)) "a {:fx [[:dispatch ...]]} walk never settles"))))

  (testing "a machine transition whose :entry returns :fx and arms an :after
            timer enqueues ZERO settles"
    (rf/reg-event :p/noop (fn [_ _] {}))
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
        (is (zero? (settles runs))
            "the :entry fx walk and the :after arming wrote no frame state")))))

(deftest a-frame-with-no-flows-pays-nothing
  (testing "update-snapshot on a frame holding NO flows enqueues ZERO settles —
            the guard that keeps 50 dispatches at 50 events rather than 100"
    (rf/reg-machine :pm/m
      {:initial :idle :states {:idle {:on {:go :busy}} :busy {:on {:go :idle}}}})
    (patch-event!)
    (rf/dispatch-sync [:pm/m [:go]])
    (call-counting-runs
      (fn [runs]
        (dotimes [i 50]
          (rf/dispatch-sync [:p/patch {:state (if (even? i) :idle :busy)}]))
        (is (= :busy (:state (snapshot :pm/m))) "control — the patches landed")
        (is (= 50 (count @runs)) "50 dispatches ran exactly 50 events")
        (is (zero? (settles runs)) "no settle on a flow-free frame")))))

(deftest a-dry-run-enqueues-no-settle
  (testing "a dry run records its fx and executes none, so the walk writes
            nothing and cannot request a settle"
    (reg-machine-and-flows!)
    (patch-event!)
    (call-counting-runs
      (fn [runs]
        (let [sink (atom [])]
          (binding [rf.fx/*effect-sink* sink]
            (rf/dispatch-sync [:p/patch {:state :busy}]))
          (is (= 1 (count @sink)) "control — the sink recorded the one fx")
          (is (= :idle (:state (snapshot :pm/m))) "the fx did not execute")
          (is (zero? (settles runs)) "and no settle was enqueued"))))))

(deftest a-write-on-a-frame-with-flows-settles-exactly-once
  (testing "a writing walk on a frame WITH flows enqueues exactly one settle,
            whatever the walk wrote — also the positive control for the settle
            counter the zero-settle controls above rely on"
    (reg-machine-and-flows!)
    (rf/reg-event :p/two-patches
      (fn [_ _]
        {:fx [[:rf.machine/update-snapshot {:rf/machine-id :pm/m :rf/patch {:state :busy}}]
              [:rf.machine/update-snapshot {:rf/machine-id :pm/m :rf/patch {:data {:note "x"}}}]]}))
    (call-counting-runs
      (fn [runs]
        (rf/dispatch-sync [:p/two-patches])
        (is (= {:mstate :busy :mnote "x"} (select-keys (db) [:mstate :mnote])))
        (is (= 1 (settles runs)) "one walk, two writes, one settle")))))
