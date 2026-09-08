(ns re-frame.fixed-actor-id-addressing-test
  "The address IS the id (rf2-kuky.15 ruled A, delivered by rf2-kuky.70).

  With the `:system-id` family deleted, `:fixed-actor-id` is the ONE
  stable-name mechanism. This suite pins the three claims Spec 005 now makes
  about it, so nobody re-adds a per-frame name registry to \"restore parity\":

    1. **A re-entered `:fixed-actor-id` child is a NEW INCARNATION at the
       SAME address.** The spawn reuses the id verbatim and
       `rf.machines.reply/actor-generation` is 1 for it; an ordinary
       application dispatch to that address reaches whichever incarnation
       currently owns it.

    2. **The imperative recipe.** An action that hand-emits
       `[:rf.machine/spawn …]` and must keep the child's id chooses a fresh
       explicit keyword address (from an event value or a recorded coeffect),
       stores it in ordinary `:data`, and passes it as `:fixed-actor-id`. It
       then dispatches to that address and destroys it explicitly. There is NO
       second spawn-result channel.

    3. **Occupied-fixed-address reuse is CHARACTERISED, not guaranteed.**
       `spawn-all-address-collisions` is a WITHIN-BATCH guard; the ordinary
       `spawn-fx*` path installs at a supplied fixed address with no general
       occupied-address rejection. The test below records what the runtime
       actually does today rather than asserting a guarantee the code does not
       make."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.reply :as rf.machines.reply]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private snapshot rf.machines.test-support/snapshot)

;; ---------------------------------------------------------------------------
;; (1) A re-entered :fixed-actor-id child is a new incarnation at one address
;; ---------------------------------------------------------------------------

(deftest re-entered-fixed-actor-id-is-a-new-incarnation-at-the-same-address
  (testing "rf2-kuky.70 — leaving and re-entering a :fixed-actor-id-bearing
            state destroys the child and spawns a fresh one at the SAME
            address; the id is reused verbatim (no #n suffix) and
            actor-generation is 1 for both incarnations"
    (rf/reg-machine :fai/child
      {:initial :running
       :data    {}
       :actions {:mark (fn [{d :data ev :event}] {:data (assoc d :tag (second ev))})}
       :states  {:running {:on {:mark {:action :mark}}}}})
    (rf/reg-machine :fai/parent
      {:initial :idle
       :states  {:idle    {:on {:go :working}}
                 :working {:spawn {:machine-id     :fai/child
                                   :fixed-actor-id :fai/pinned}
                           :on    {:back :idle}}}})
    ;; First incarnation.
    (rf/dispatch-sync [:fai/parent [:go]])
    (is (some? (snapshot :fai/pinned))
        "the child installed at the explicit address, not at a #n-suffixed id")
    (is (nil? (snapshot :fai/child#1))
        "no allocated <type>#<n> id was minted alongside the fixed address")
    (rf/dispatch-sync [:fai/pinned [:mark :first]])
    (is (= :first (:tag (:data (snapshot :fai/pinned))))
        "an ordinary dispatch to the address reaches the live incarnation")

    ;; Exit destroys it.
    (rf/dispatch-sync [:fai/parent [:back]])
    (is (nil? (snapshot :fai/pinned))
        "the exit cascade destroyed the child at that address")

    ;; Re-entry: a NEW incarnation at the SAME address.
    (rf/dispatch-sync [:fai/parent [:go]])
    (is (some? (snapshot :fai/pinned))
        "re-entry spawned a fresh incarnation at the SAME address")
    (is (nil? (:tag (:data (snapshot :fai/pinned))))
        "the new incarnation carries fresh :data — it is not the old actor")
    (rf/dispatch-sync [:fai/pinned [:mark :second]])
    (is (= :second (:tag (:data (snapshot :fai/pinned))))
        "an ordinary dispatch to :fai/pinned reaches whichever incarnation currently owns it")

    (is (= 1 (rf.machines.reply/actor-generation :fai/pinned))
        "actor-generation is 1 for a fixed address — the id carries no #n counter;
         incarnations are told apart by the :work-generation stamp, not the id")))

;; ---------------------------------------------------------------------------
;; (2) The imperative-spawn recipe: choose the address, hold it, destroy it
;; ---------------------------------------------------------------------------

(deftest hand-emitted-spawn-keeps-its-child-by-choosing-the-address
  (testing "rf2-kuky.70 Target 3 — an action that hand-emits
            [:rf.machine/spawn …] derives a FRESH explicit keyword address from
            an event value, stores it in ordinary :data, passes it as
            :fixed-actor-id, dispatches to it, and destroys it explicitly. No
            second spawn-result channel is involved."
    (rf/reg-machine :fai/worker
      {:initial :running
       :data    {}
       :actions {:note (fn [{d :data ev :event}] {:data (assoc d :note (second ev))})}
       :states  {:running {:on {:note {:action :note}}}}})
    (rf/reg-machine :fai/boss
      {:initial :idle
       :data    {}
       :states
       {:idle
        {:on {;; Hand-emitted spawn: the boss PICKS the address from the event.
              :hire   {:action (fn [{d :data [_ job] :event}]
                                 (let [addr (keyword "fai.job" (name job))]
                                   {:data (assoc d :worker addr)
                                    :fx   [[:rf.machine/spawn
                                            {:machine-id     :fai/worker
                                             :fixed-actor-id addr
                                             :data           {:job job}}]]}))}
              ;; Later: address the child off the boss's own :data.
              :poke   {:action (fn [{d :data}]
                                 {:fx [[:dispatch [(:worker d) [:note :poked]]]]})}
              ;; And tear it down explicitly. NOTE an action's `:data` is
              ;; MERGED onto the snapshot (XState `assign` parity), so a key
              ;; is retired by writing nil rather than by `dissoc`.
              :fire   {:action (fn [{d :data}]
                                 {:data {:worker nil}
                                  :fx   [[:rf.machine/destroy (:worker d)]]})}}}}})
    (rf/dispatch-sync [:fai/boss [:hire :alpha]])
    (is (= :fai.job/alpha (:worker (:data (snapshot :fai/boss))))
        "the boss recorded the address it chose in ordinary :data")
    (is (= {:job :alpha} (select-keys (:data (snapshot :fai.job/alpha)) [:job]))
        "the child installed at exactly the chosen address, carrying its spawn :data")

    (rf/dispatch-sync [:fai/boss [:poke]])
    (is (= :poked (:note (:data (snapshot :fai.job/alpha))))
        "the boss addressed the child by the id it held — plain :dispatch, one verb")

    (rf/dispatch-sync [:fai/boss [:fire]])
    (is (nil? (snapshot :fai.job/alpha))
        "an explicit [:rf.machine/destroy <addr>] tore the child down")
    (is (nil? (:worker (:data (snapshot :fai/boss))))
        "the boss dropped the address it no longer owns")))

(deftest a-fresh-address-lets-a-new-worker-run-beside-a-lingering-one
  (testing "rf2-kuky.70 Target 3 — an app that needs a fresh actor while an
            older one lingers allocates a DIFFERENT explicit address; the two
            coexist, and the old one is destroyed explicitly"
    (rf/reg-machine :fai/worker2
      {:initial :running :data {} :states {:running {}}})
    (rf/reg-event :fai/hire
      (fn [_ [_ job]]
        {:fx [[:rf.machine/spawn {:machine-id     :fai/worker2
                                  :fixed-actor-id (keyword "fai.w" (name job))
                                  :data           {:job job}}]]}))
    (rf/dispatch-sync [:fai/hire :one])
    (rf/dispatch-sync [:fai/hire :two])
    (is (some? (snapshot :fai.w/one)) "the first worker is live at its own address")
    (is (some? (snapshot :fai.w/two)) "the second worker is live beside it")

    (rf/reg-event :fai/fire (fn [_ [_ addr]] {:fx [[:rf.machine/destroy addr]]}))
    (rf/dispatch-sync [:fai/fire :fai.w/one])
    (is (nil? (snapshot :fai.w/one)) "the older worker was destroyed explicitly")
    (is (some? (snapshot :fai.w/two)) "the newer worker is untouched")))

;; ---------------------------------------------------------------------------
;; (3) Occupied-fixed-address reuse — CHARACTERISATION, not a guarantee
;; ---------------------------------------------------------------------------

(deftest spawning-onto-an-occupied-fixed-address-replaces-the-occupant
  (testing "rf2-kuky.70 Target 4 — CHARACTERISATION of current behaviour, NOT a
            contract this bead widens. `spawn-all-address-collisions` is a
            WITHIN-BATCH guard (pinned separately); the ORDINARY spawn path has
            no general occupied-address rejection, so a second spawn at a LIVE
            fixed address REPLACES the occupant's snapshot in place. The
            address stays the one name; the earlier incarnation is gone."
    (rf/reg-machine :fai/occupant
      {:initial :running
       :data    {}
       :states  {:running {}}})
    (rf/reg-event :fai/install
      (fn [_ [_ payload]]
        {:fx [[:rf.machine/spawn {:machine-id     :fai/occupant
                                  :fixed-actor-id :fai/slot
                                  :data           {:payload payload}}]]}))
    (rf/dispatch-sync [:fai/install :first])
    (is (= :first (:payload (:data (snapshot :fai/slot))))
        "precondition: the address is OCCUPIED by the first incarnation")

    (rf/dispatch-sync [:fai/install :second])
    (is (= :second (:payload (:data (snapshot :fai/slot))))
        "the second spawn REPLACED the live occupant at the same address —
         no occupied-address rejection fires on the ordinary spawn path")
    (is (nil? (snapshot :fai/occupant#1))
        "no sidelined copy of the first incarnation survives under an allocated id")))
