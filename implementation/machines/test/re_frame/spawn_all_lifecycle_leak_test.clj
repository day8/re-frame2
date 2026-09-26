(ns re-frame.spawn-all-lifecycle-leak-test
  "A `:spawn-all` lifecycle leak shape the runtime closes. (The MIXED
  registered/unregistered orphan shape — the whole invoke rejected so no
  registered sibling installs — is pinned in
  `machine_spawn_unregistered_type_test`.)

  COMPLETED children at join resolution. A completed child that stayed a
      LIVE actor would rely on the parent's resolution transition EXITING the
      `:spawn-all` state to be torn down by the exit cascade, so an
      INTERNAL/self resolution handler (or a parent with no `:on` for the
      resolution event) would leak all N completed children.

      Completion is finality, so a child that folds into a join destroys
      itself at its own completion with `:reason :rf.machine/finished`
      (Spec 005 §Final states, D4). The leak is structurally unreachable, and
      only SURVIVORS remain for the join to cancel at resolution. The test
      below pins the OUTCOME — no child snapshot survives an internal/self
      resolution — because that is the invariant a future change could break.

  JVM plain-atom, mirroring `spawn_all_test`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; Loading `re-frame.machines` registers the late-bind hooks
            ;; (`:machines/reg-machine`, …) the tests below exercise — keep the
            ;; require even though the ns is reached only via `rf/...` facades.
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private frame-db rf.machines.test-support/runtime-db)
(def ^:private snapshot rf.machines.test-support/snapshot)

(defn- mk-child
  "A child that on :go / :fail reaches a `:final?` terminal — completion IS
  finality, so the child carries no parent vocabulary and destroys itself the
  moment it completes."
  []
  {:initial :running
   :data    {:id nil}
   :actions {:record-id (fn [{data :data ev :event}]
                          {:data (assoc data :id (second ev))})}
   :states  {:running {:on {:set-id {:action :record-id}
                            :go     {:target :done}
                            :fail   {:target :failed}}}
             :done   {:final? true :output-key :id}
             :failed {:final? true :error? true :output-key :id}}})

;; ===========================================================================
;; internal/self join-resolution destroys COMPLETED children
;; ===========================================================================

(deftest internal-self-join-resolution-destroys-completed-children
  (testing "when the join resolves but the resolution transition
            does NOT exit the :spawn-all state (no :on for the resolution
            event), NO child snapshot survives — the decisive child destroyed
            itself at its own finality, the survivor was cancelled at
            resolution, and neither leaks"
    (let [child  (mk-child)
          parent {:initial :idle
                  :states
                  {:idle {:on {:start :racing}}
                   ;; :racing declares NO :on for :race/won → the parent STAYS
                   ;; in :racing when the :any join resolves (the internal/self
                   ;; resolution shape — the resolution transition does not exit
                   ;; the :spawn-all state, so the exit cascade never runs).
                   :racing
                   {:spawn-all
                    {:children         [{:id :a :machine-id :qb/child-ba :start [:set-id :a]}
                                       {:id :b :machine-id :qb/child-bb :start [:set-id :b]}]
                     :join             :any
                     :on-some-complete [:race/won]}}}}]
      (rf/reg-machine :qb/child-ba child)
      (rf/reg-machine :qb/child-bb child)
      (rf/reg-machine :qb/parent-b parent)
      (rf/dispatch-sync [:qb/parent-b [:start]])
      (let [ids  (:children (get-in (frame-db) [:rf.runtime/machines :spawned :qb/parent-b [:racing]]))
            a-id (:a ids)
            b-id (:b ids)]
        (is (some? (snapshot a-id)) "child :a live before resolution")
        (is (some? (snapshot b-id)) "child :b live before resolution")
        ;; :a completes → the :any join resolves. Parent stays in :racing.
        (rf/dispatch-sync [a-id [:go]])
        (let [j (get-in (frame-db) [:rf.runtime/machines :spawned :qb/parent-b [:racing]])]
          (is (true? (:resolved? j)) ":any resolved on :a's completion")
          (is (= #{:a} (:done j)) "record holds the decisive child at resolution"))
        (is (= :racing (:state (snapshot :qb/parent-b)))
            "the parent STAYED in :racing — the resolution transition did NOT exit the :spawn-all state")
        (is (nil? (snapshot b-id))
            "survivor :b was destroyed at resolution (unconditional sibling cancellation)")
        (is (nil? (snapshot a-id))
            "decisive child :a is gone too — it destroyed ITSELF at its :final? state (:reason :rf.machine/finished), before the parent ever folded the completion. No exit cascade was needed, so no leak.")))))
