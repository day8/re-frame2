(ns re-frame.spawn-all-lifecycle-leak-test
  "rf2-qb1j5z — two related `:spawn-all` lifecycle leaks.

  (a) MIXED registered/unregistered `:spawn-all`. When any child names an
      UNREGISTERED TYPE, `spawn-all-init-fx` rejects the join. Previously it
      seeded NO join-state and returned nil WITHOUT aborting the entry `:fx`
      vector, so the registered siblings' per-child `:rf.machine/spawn` fxs
      still ran and installed LIVE actors that no seeded join ever tore down
      — orphans leaking until frame-destroy. The fix seeds a reject sentinel
      so the registered siblings suppress themselves: the whole malformed
      invoke spawns NOTHING (the atomic-reject analogue of a single `:spawn`).

  (b) COMPLETED-children leak at join resolution. `build-resolution-fx`
      destroyed only SURVIVORS; completed children (whose `:on-child-done` /
      `:on-child-error` terminal states are NOT necessarily `:final?`, so they
      stay LIVE) relied on the parent's resolution transition EXITING the
      `:spawn-all` state to be torn down by the exit cascade. An INTERNAL/self
      resolution handler (or a parent with no `:on` for the resolution event)
      leaked all N completed children. The fix destroys every child at
      resolution, regardless of whether the transition exits the state.

  JVM plain-atom, mirroring `spawn_all_test`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; Loading `re-frame.machines` registers the late-bind hooks
            ;; (`:machines/reg-machine`, …) the tests below exercise — keep the
            ;; require even though the ns is reached only via `rf/...` facades.
            [re-frame.machines]
            [re-frame.machines.spawn-order :as rf.machines.spawn-order]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private frame-db rf.machines.test-support/runtime-db)
(def ^:private snapshot rf.machines.test-support/snapshot)

(defn- mk-child
  "A child that on :go / :fail transitions to a PLAIN (non-:final?) terminal
  state and dispatches its completion back to `parent-id` — so a 'completed'
  child stays a LIVE actor (the (b) leak's precondition)."
  [parent-id done-kw error-kw]
  {:initial :running
   :data    {:id nil}
   :actions {:dispatch-done  (fn [{data :data}]
                               {:fx [[:dispatch [parent-id [done-kw (:id data)]]]]})
             :dispatch-error (fn [{data :data}]
                               {:fx [[:dispatch [parent-id [error-kw (:id data)]]]]})
             :record-id      (fn [{data :data ev :event}]
                               {:data (assoc data :id (second ev))})}
   :states  {:running {:on {:set-id {:action :record-id}
                            :go     {:target :done   :action :dispatch-done}
                            :fail   {:target :failed :action :dispatch-error}}}
             :done   {}
             :failed {}}})

(defn- collect-traces
  "Run `body-fn` with a trace listener; return the collected envelopes."
  [body-fn]
  (let [traces (atom [])
        cb-key (gensym ::leak-cb)]
    (rf/register-listener! :trace cb-key (fn [ev] (swap! traces conj ev)))
    (try (body-fn) (finally (rf/unregister-listener! :trace cb-key)))
    @traces))

;; ===========================================================================
;; (a) mixed registered/unregistered :spawn-all — atomic reject, no orphan
;; ===========================================================================

(deftest mixed-registered-unregistered-spawn-all-rejects-whole-invoke-atomically
  (testing "rf2-qb1j5z(a): a :spawn-all naming an unregistered sibling TYPE
            spawns NOTHING — the registered sibling is suppressed atomically,
            leaving no orphan actor to leak"
    ;; Only :qb/registered is registered; :qb/never-registered is NOT.
    (rf/reg-machine :qb/registered (mk-child :qb/parent-a :done :err))
    (rf/reg-machine :qb/parent-a
      {:initial :idle
       :states
       {:idle {:on {:start :hydrating}}
        :hydrating
        {:spawn-all
         {:children        [{:id :reg   :machine-id :qb/registered       :start [:set-id :reg]}
                            {:id :unreg :machine-id :qb/never-registered :start [:set-id :unreg]}]
          :join            :all
          :on-child-done   :done
          :on-child-error  :err
          :on-all-complete [:hydrate/done]}
         :on {:hydrate/done :ready}}
        :ready {}}})
    (let [traces (collect-traces (fn [] (rf/dispatch-sync [:qb/parent-a [:start]])))
          errs   (->> traces
                      (filter #(= :rf.error/machine-spawn-unregistered-type (:operation %))))
          slot   (get-in (frame-db) [:rf.runtime/machines :spawned :qb/parent-a [:hydrating]])
          snaps  (get-in (frame-db) [:rf.runtime/machines :snapshots])]
      (is (seq errs)
          "the unregistered sibling TYPE was rejected (:rf.error/machine-spawn-unregistered-type)")
      (is (= {:rf/spawn-all-rejected? true} slot)
          "the join slot holds the reject sentinel (no live child-bearing join state — cannot deadlock)")
      (is (not (contains? slot :children))
          "the sentinel carries no :children, so the join interceptor treats it as no live join")
      (is (= #{:qb/parent-a} (set (keys snaps)))
          "NO orphan: the ONLY snapshot is the parent's — the registered sibling never installed")
      (is (= [] (rf.machines.spawn-order/frame-order :rf/default))
          "NO orphan: nothing recorded in spawn-order (the registered sibling was suppressed)")
      (is (= :hydrating (:state (snapshot :qb/parent-a)))
          "the parent rests on the (malformed) :spawn-all state — the config error to fix"))))

;; ===========================================================================
;; (b) internal/self join-resolution destroys COMPLETED children
;; ===========================================================================

(deftest internal-self-join-resolution-destroys-completed-children
  (testing "rf2-qb1j5z(b): when the join resolves but the resolution transition
            does NOT exit the :spawn-all state (no :on for the resolution
            event), the COMPLETED children are torn down at resolution, not
            leaked"
    (let [child  (mk-child :qb/parent-b :done :err)
          parent {:initial :idle
                  :states
                  {:idle {:on {:start :racing}}
                   ;; :racing declares NO :on for :race/won → the parent STAYS
                   ;; in :racing when the :any join resolves (the internal/self
                   ;; resolution shape — the resolution transition does not exit
                   ;; the :spawn-all state, so the exit-cascade teardown of
                   ;; completed children never runs).
                   :racing
                   {:spawn-all
                    {:children         [{:id :a :machine-id :qb/child-ba :start [:set-id :a]}
                                       {:id :b :machine-id :qb/child-bb :start [:set-id :b]}]
                     :join             :any
                     :on-child-done    :done
                     :on-child-error   :err
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
            "COMPLETED child :a was ALSO destroyed at resolution — no leak (the fix)")))))
