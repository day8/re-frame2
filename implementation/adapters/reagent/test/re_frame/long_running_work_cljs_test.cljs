(ns re-frame.long-running-work-cljs-test
  "Integration test: drives the long-running-work example (rf2-o9fg)
   through the parent coordinator + child workers. Each helper spins a
   fresh frame via `make-frame`, walks the :work/flow parent through a
   flow (spawn cascade, happy-path join, mid-flight cancel, parent
   unmount, reset round-trip), and asserts the resulting
   [:rf.db/runtime :rf.runtime/machines :snapshots :work/flow] snapshot + the
   runtime-owned [:rf.db/runtime :rf.runtime/machines :spawned :work/flow [:working]]
   join-state slot.

   The fixture fns live HERE (the adapter test tree), not under
   examples/patterns/long_running_work/ — the example source stays
   test-free per the locked test-free-examples policy (rf2-8cevm). The ns
   requires the example's `long-running-work.worker` source directly (its
   ns-load reg-machine calls install :work/flow + :work/processor + the
   related subs), then exercises it. We don't need long-running-work.core
   (the entry point) since the integration tests bypass mount and React
   entirely; the views ns is exercised by the adapter browser smoke.
   (rf2-cd2zo folded the former `long-running-work.worker-test` fixture ns
   in here and retired the example test/ dir.)

   Per rf2-am9d this ns uses snapshot/restore via re-frame.test-support
   so the contract is uniform across CLJS fixtures — the snapshot
   captures the example's ns-load registrations (the :work/flow
   parent + :work/processor child machines and the views' framework
   subs), and the restore on the way out leaves them intact for any
   subsequent test ns."
  (:require [cljs.test :refer-macros [deftest testing use-fixtures is]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]
            [re-frame.views]
            [long-running-work.worker])
  (:require-macros [re-frame.core :refer [with-new-frame]]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    ;; EP-0002 (rf2-9o48ih): each test spins its OWN top-level frame via
    ;; `make-frame`; opt out of the ambient `:rf/default` scope so the new
    ;; frame's `:initial-events` drain synchronously (top-level boot) rather than
    ;; being treated as a mid-cascade child-frame creation.
    {:adapter       reagent-adapter/adapter
     :ambient-frame nil}))

;; ============================================================================
;; HELPERS
;; ============================================================================

(defn- snapshot
  "Read the parent's machine snapshot from a frame's app-db."
  [frame]
  (get-in (rf/frame-state-value frame) [:rf.db/runtime :rf.runtime/machines :snapshots :work/flow]))

(defn- join-state
  "Read the runtime-owned join-state slot at
   [:rf.db/runtime :rf.runtime/machines :spawned :work/flow [:working]]. Returns nil after the
   cascade has cleared it."
  [frame]
  (get-in (rf/frame-state-value frame) [:rf.db/runtime :rf.runtime/machines :spawned :work/flow [:working]]))

(defn- new-frame
  "Spin up a fresh test frame. We dispatch :work/flow [:reset] via
   :initial-events rather than going through the :app/initialise fanout so the
   test ns doesn't transitively depend on long-running-work.core (which
   pulls in Reagent's DOM-only namespaces and a defonce root-creation).
   Both reach the same end-state: parent machine at :idle with cleared
   :data."
  []
  (frame/make-anon-frame-record! {:initial-events [[:work/flow [:reset]]]}))

;; ============================================================================
;; (1) SPAWN CASCADE — :start spawns 3 children
;; ============================================================================

(defn- test-spawn-cascade []
  (with-new-frame [f (new-frame)]
    ;; After :app/initialise, the parent is :idle with progress all-zero.
    (let [snap (snapshot f)]
      (is (= :idle (:state snap)))
      (is (= {:s1 0 :s2 0 :s3 0} (-> snap :data :progress)))
      (is (nil? (join-state f))))                            ;; not yet allocated

    ;; :start transitions :idle → :working; the runtime emits
    ;; :rf.machine/spawn-all-init + 3 :rf.machine/spawn fxs. The
    ;; init fx seeds the join-state map at
    ;; [:rf.db/runtime :rf.runtime/machines :spawned :work/flow [:working]] with :children mapping
    ;; each user-supplied id (:s1/:s2/:s3) to the gensym'd
    ;; spawned-id (:work/processor#N).
    (rf/dispatch-sync [:work/flow [:start]] {:frame f})
    (let [snap (snapshot f)
          js   (join-state f)]
      (is (= :working (:state snap)))
      ;; The runtime allocated a join-state slot at
      ;; [:rf.db/runtime :rf.runtime/machines :spawned :work/flow [:working]] keyed by user id.
      (is (map? js))
      (is (= #{:s1 :s2 :s3} (set (keys (:children js)))))
      (is (false? (:resolved? js)))
      (is (empty?  (:done js)))
      (is (empty?  (:failed js))))))

;; ============================================================================
;; (2) HAPPY-PATH JOIN COMPLETION — synthesised :on-child-done events
;; ============================================================================

(defn- test-happy-path-join []
  (with-new-frame [f (new-frame)]
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
      ;; from :working, the destroy fx clears [:rf.db/runtime :rf.runtime/machines :spawned
      ;; :work/flow [:working]].
      (is (nil? (join-state f))))))

;; ============================================================================
;; (3) MID-FLIGHT CANCELLATION CASCADE — :cancel tears every child down
;; ============================================================================

(defn- test-cancel-cascade []
  (with-new-frame [f (new-frame)]
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
      (is (= 90  (rf/compute-sub [:work/items-done]   (rf/frame-state-value f))))
      (is (= 300 (rf/compute-sub [:work/total-items] (rf/frame-state-value f)))))

    ;; User clicks Cancel. The parent transitions :working →
    ;; :cancelled; the :spawn-all desugared :exit fires one
    ;; :rf.machine/destroy fx with :rf/spawn-all true; the
    ;; destroy fx handler iterates the join-state's :children map
    ;; and tears each surviving child down, then clears the
    ;; [:rf.db/runtime :rf.runtime/machines :spawned :work/flow [:working]] slot.
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
;; test exercises that contract by dispatching the same event. This
;; test pins the machine-side invariant; the React-side wiring is
;; covered by the view code itself (`r/with-let` cleanup is a
;; Reagent idiom, not a re-frame2 contract).

(defn- test-parent-unmount-cascade []
  (with-new-frame [f (new-frame)]
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

(defn- test-reset-after-cancel []
  (with-new-frame [f (new-frame)]
    (rf/dispatch-sync [:work/flow [:start]]               {:frame f})
    (rf/dispatch-sync [:work/flow [:progress :s1 42 100]] {:frame f})
    (rf/dispatch-sync [:work/flow [:cancel]]              {:frame f})

    (rf/dispatch-sync [:work/flow [:reset]] {:frame f})
    (let [snap (snapshot f)]
      (is (= :idle (:state snap)))
      (is (= {:s1 0 :s2 0 :s3 0} (-> snap :data :progress)))
      (is (nil? (-> snap :data :outcome))))))

(deftest long-running-work-spawn-cascade
  (testing ":start spawns 3 children via :spawn-all and seeds the join-state"
    (test-spawn-cascade)))

(deftest long-running-work-happy-path-join
  (testing "synthesised :on-child-done events resolve the :all join and stamp :complete"
    (test-happy-path-join)))

(deftest long-running-work-cancel-cascade
  (testing "mid-flight :cancel tears down every surviving child via the :spawn-all exit"
    (test-cancel-cascade)))

(deftest long-running-work-parent-unmount-cascade
  (testing "view-unmount path: same :cancel dispatch from r/with-let cleanup"
    (test-parent-unmount-cascade)))

(deftest long-running-work-reset-round-trip
  (testing ":cancelled → :reset returns the parent to :idle with cleared :progress"
    (test-reset-after-cancel)))
