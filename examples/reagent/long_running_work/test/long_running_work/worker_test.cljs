(ns long-running-work.worker-test
  "Headless tests for the long-running-work example's parent
   coordinator + child workers.

   Three flows, all browserless via `make-frame` + `dispatch-sync`:

     1. **Spawn cascade.** :start transitions :idle → :working;
        the runtime emits :rf.machine/invoke-all-init + 3
        :rf.machine/spawn fxs; the join-state's :children map at
        [:rf/spawned :work/flow [:working]] is populated.

     2. **Happy-path join completion.** Synthesise three
        [:work/flow [:work/child-done :sN]] events; the runtime
        intercepts each, updates :done; on the third the join
        condition resolves and dispatches [:work/all-done] into
        the parent, which transitions :working → :complete and
        stamps :outcome :complete.

     3. **Mid-flight cancellation cascade.** :start, then a few
        `:progress` events to simulate partial work, then `:cancel`;
        the parent transitions :working → :cancelled; the
        :invoke-all exit cascade fires :rf.machine/destroy with
        :rf/invoke-all true; the join-state slot at
        [:rf/spawned :work/flow [:working]] is torn down.

     4. **Parent-unmount cascade.** Same path as (3) but the
        :cancel event arrives via the view's r/with-let cleanup
        rather than the user clicking Cancel. The headless test
        synthesises the same dispatch and asserts the same
        cascade. The view-level wiring is exercised by the
        Playwright smoke (long_running_work.spec.cjs)."
  (:require [cljs.test :refer-macros [is]]
            [re-frame.core :as rf]
            [long-running-work.worker])
  (:require-macros [re-frame.views-macros :refer [with-frame]]))

;; ============================================================================
;; HELPERS
;; ============================================================================

(defn- snapshot
  "Read the parent's machine snapshot from a frame's app-db."
  [frame]
  (get-in (rf/get-frame-db frame) [:rf/machines :work/flow]))

(defn- join-state
  "Read the runtime-owned join-state slot at
   [:rf/spawned :work/flow [:working]]. Returns nil after the
   cascade has cleared it."
  [frame]
  (get-in (rf/get-frame-db frame) [:rf/spawned :work/flow [:working]]))

(defn- new-frame
  "Spin up a fresh test frame. We dispatch :work/flow [:reset] inside the
   :on-create rather than going through the :app/initialise fanout so the
   test ns doesn't transitively depend on long-running-work.core (which
   pulls in Reagent's DOM-only namespaces and a defonce root-creation).
   Both reach the same end-state: parent machine at :idle with cleared
   :data."
  []
  (rf/make-frame {:on-create [:work/flow [:reset]]}))

;; ============================================================================
;; (1) SPAWN CASCADE — :start spawns 3 children
;; ============================================================================

(defn test-spawn-cascade []
  (with-frame [f (new-frame)]
    ;; After :app/initialise, the parent is :idle with progress all-zero.
    (let [snap (snapshot f)]
      (is (= :idle (:state snap)))
      (is (= {:s1 0 :s2 0 :s3 0} (-> snap :data :progress)))
      (is (nil? (join-state f))))                            ;; not yet allocated

    ;; :start transitions :idle → :working; the runtime emits
    ;; :rf.machine/invoke-all-init + 3 :rf.machine/spawn fxs. The
    ;; init fx seeds the join-state map at
    ;; [:rf/spawned :work/flow [:working]] with :children mapping
    ;; each user-supplied id (:s1/:s2/:s3) to the gensym'd
    ;; spawned-id (:work/processor#N).
    (rf/dispatch-sync [:work/flow [:start]] {:frame f})
    (let [snap (snapshot f)
          js   (join-state f)]
      (is (= :working (:state snap)))
      ;; The runtime allocated a join-state slot at
      ;; [:rf/spawned :work/flow [:working]] keyed by user id.
      (is (map? js))
      (is (= #{:s1 :s2 :s3} (set (keys (:children js)))))
      (is (false? (:resolved? js)))
      (is (empty?  (:done js)))
      (is (empty?  (:failed js))))))

;; ============================================================================
;; (2) HAPPY-PATH JOIN COMPLETION — synthesised :on-child-done events
;; ============================================================================

(defn test-happy-path-join []
  (with-frame [f (new-frame)]
    (rf/dispatch-sync [:work/flow [:start]] {:frame f})

    ;; The child machines would normally drive themselves to :done
    ;; via :after-yield loops and dispatch :work/child-done on entry
    ;; to their terminal state. dispatch-sync doesn't fire :after
    ;; timers, so for the headless test we synthesise the
    ;; child-done events directly. The runtime's intercept logic
    ;; (intercept-invoke-all-event in re-frame.machines) treats
    ;; them identically — the events arrive at the parent's
    ;; handler boundary either way.

    (rf/dispatch-sync [:work/flow [:work/child-done :s1]] {:frame f})
    ;; After the first :work/child-done, the join state's :done
    ;; carries :s1; the join condition (:all of 3) hasn't resolved
    ;; yet so :resolved? stays false and the parent is still :working.
    (let [snap (snapshot f)
          js   (join-state f)]
      (is (= :working   (:state snap)))
      (is (= #{:s1}     (:done js)))
      (is (false?       (:resolved? js))))

    (rf/dispatch-sync [:work/flow [:work/child-done :s2]] {:frame f})
    (is (= #{:s1 :s2} (:done (join-state f))))
    (is (= :working   (:state (snapshot f))))

    ;; Third child done — :all resolves. The runtime sets :resolved?
    ;; true, builds per-sibling cancel fx for survivors (none, since
    ;; this was the last child), and dispatches [:work/flow
    ;; [:work/all-done]]. The parent's :working :on table catches
    ;; :work/all-done → :complete (with :stamp-outcome action).
    (rf/dispatch-sync [:work/flow [:work/child-done :s3]] {:frame f})
    (let [snap (snapshot f)]
      (is (= :complete (:state snap)))
      (is (= :complete (-> snap :data :outcome)))
      ;; The cascade tore down the invoke-all slot: after the exit
      ;; from :working, the destroy fx clears [:rf/spawned
      ;; :work/flow [:working]].
      (is (nil? (join-state f))))))

;; ============================================================================
;; (3) MID-FLIGHT CANCELLATION CASCADE — :cancel tears every child down
;; ============================================================================

(defn test-cancel-cascade []
  (with-frame [f (new-frame)]
    (rf/dispatch-sync [:work/flow [:start]] {:frame f})

    ;; Simulate partial progress from each shard. The action's
    ;; internal-self transition updates :data :progress without
    ;; exit/entry.
    (rf/dispatch-sync [:work/flow [:progress :s1 30 100]] {:frame f})
    (rf/dispatch-sync [:work/flow [:progress :s2 50 100]] {:frame f})
    (rf/dispatch-sync [:work/flow [:progress :s3 10 100]] {:frame f})

    (let [snap (snapshot f)]
      (is (= :working (:state snap)))
      (is (= {:s1 30 :s2 50 :s3 10} (-> snap :data :progress)))
      ;; The aggregate-progress sub: (30+50+10)/(3*100) = 90/300.
      (is (= 90  (rf/compute-sub [:work/items-done]   (rf/get-frame-db f))))
      (is (= 300 (rf/compute-sub [:work/total-items] (rf/get-frame-db f)))))

    ;; User clicks Cancel. The parent transitions :working →
    ;; :cancelled; the :invoke-all desugared :exit fires one
    ;; :rf.machine/destroy fx with :rf/invoke-all true; the
    ;; destroy fx handler iterates the join-state's :children map
    ;; and tears each surviving child down, then clears the
    ;; [:rf/spawned :work/flow [:working]] slot.
    (rf/dispatch-sync [:work/flow [:cancel]] {:frame f})
    (let [snap (snapshot f)]
      (is (= :cancelled (:state snap)))
      (is (= :cancelled (-> snap :data :outcome)))
      ;; The destroy cascade cleared the join-state slot.
      (is (nil? (join-state f)))
      ;; Partial :progress is preserved on the parent's :data
      ;; (cancellation is cooperative; the parent decides what to
      ;; do with the partial result). The view shows where each
      ;; shard got to at the moment of cancel.
      (is (= {:s1 30 :s2 50 :s3 10}
             (-> snap :data :progress))))))

;; ============================================================================
;; (4) PARENT-UNMOUNT CASCADE — :cancel dispatched from the view cleanup
;; ============================================================================
;;
;; The view's r/with-let cleanup dispatches [:work/flow [:cancel]]
;; on component unmount. From the parent machine's perspective this
;; is identical to a user-driven Cancel button click — the headless
;; test exercises that contract by dispatching the same event.
;;
;; The Playwright spec (long_running_work.spec.cjs) exercises the
;; *actual* React unmount path against a live browser; this test
;; pins the machine-side invariant.

(defn test-parent-unmount-cascade []
  (with-frame [f (new-frame)]
    (rf/dispatch-sync [:work/flow [:start]] {:frame f})
    (is (= :working (:state (snapshot f))))

    ;; The work-bench component's with-let finally clause runs on
    ;; React unmount. The headless test bypasses React and dispatches
    ;; the same event the cleanup would dispatch.
    (rf/dispatch-sync [:work/flow [:cancel]] {:frame f})

    (let [snap (snapshot f)]
      (is (= :cancelled (:state snap)))
      (is (= :cancelled (-> snap :data :outcome)))
      (is (nil? (join-state f))))))

;; ============================================================================
;; (5) RESET ROUND-TRIP — :cancelled → :idle clears progress for re-run
;; ============================================================================

(defn test-reset-after-cancel []
  (with-frame [f (new-frame)]
    (rf/dispatch-sync [:work/flow [:start]]               {:frame f})
    (rf/dispatch-sync [:work/flow [:progress :s1 42 100]] {:frame f})
    (rf/dispatch-sync [:work/flow [:cancel]]              {:frame f})

    (rf/dispatch-sync [:work/flow [:reset]] {:frame f})
    (let [snap (snapshot f)]
      (is (= :idle (:state snap)))
      (is (= {:s1 0 :s2 0 :s3 0} (-> snap :data :progress)))
      (is (nil? (-> snap :data :outcome))))))
