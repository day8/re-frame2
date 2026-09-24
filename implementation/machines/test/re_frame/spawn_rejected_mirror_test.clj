(ns re-frame.spawn-rejected-mirror-test
  "A rejected spawn leaves no `[:data :rf/spawned <invoke-id>]` entry on its
  parent.

  The transition reducer binds the parent's `:rf/spawned` mirror when it
  allocates the child's address, before the spawn fx decides. The fx can then
  refuse the spawn, and the mirror must not outlive that refusal: an action that
  reads the mirror to destroy its child would otherwise aim
  `[:rf.machine/destroy …]` at an actor that was never born, or at another
  parent's live child. The install writes the mirror beside the registry slot on
  success; each rejection clears it, so the two move together on both outcomes.

  Pinned rejections:

    1. a single `:spawn` of an UNREGISTERED machine type;
    2. a single `:spawn` whose GENERATED `<type>#<n>` address a live actor
       already holds (`:rf.error/machine-spawn-all-duplicate-id`);
    3. a `:spawn-all` invoke rejected as a whole (an unregistered child type),
       whose mirror is the reducer's `{<child-id> <spawned-id>}` map.

  Each rejection is paired with its accepted control, whose mirror still names
  the installed child."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter})
  rf.machines.test-support/trace-capture-fixture)

(def ^:private machine-data rf.machines.test-support/machine-data)
(def ^:private snapshot rf.machines.test-support/snapshot)

(defn- registry-slot
  "The runtime registry slot the mirror mirrors."
  [parent-id invoke-id]
  (get-in (rf.machines.test-support/runtime-db)
          [:rf.runtime/machines :spawned parent-id invoke-id]))

(defn- reg-child!
  [id]
  (rf/reg-machine id {:initial :running :states {:running {}}}))

(defn- reg-parent!
  "A parent whose `:busy` state spawns `spawn-spec` on entry."
  [id spawn-spec]
  (rf/reg-machine id
    {:initial :idle
     :states  {:idle {:on {:start :busy}}
               :busy {:spawn spawn-spec
                      :on    {:stop :idle}}}}))

;; ---------------------------------------------------------------------------
;; (1) Unregistered machine type.
;; ---------------------------------------------------------------------------

(deftest unregistered-type-reject-clears-the-mirror
  (testing "a :spawn of an unregistered type is rejected, and the parent's
            :rf/spawned mirror names no child for that invoke"
    ;; :srm/ghost is never registered.
    (reg-parent! :srm/p1 {:machine-id :srm/ghost})
    (rf/dispatch-sync [:srm/p1 [:start]])
    (is (= :busy (rf.machines.test-support/machine-state :srm/p1))
        "the parent still enters :busy — only the child spawn is rejected")
    (is (= 1 (count (rf.machines.test-support/events-of
                      :rf.error/machine-spawn-unregistered-type)))
        "the rejection itself is reported")
    (is (nil? (registry-slot :srm/p1 [:busy]))
        "the registry slot names no child")
    (is (not (contains? (:rf/spawned (machine-data :srm/p1)) [:busy]))
        "and neither does the mirror")
    (is (not (contains? (machine-data :srm/p1) :rf/spawned))
        "the emptied :rf/spawned map is pruned, as a teardown prunes it")))

(deftest registered-type-control-keeps-the-mirror
  (testing "control: a registered type installs, and the mirror names it"
    (reg-child! :srm/kid)
    (reg-parent! :srm/p2 {:machine-id :srm/kid})
    (rf/dispatch-sync [:srm/p2 [:start]])
    (is (= :srm/kid#1 (registry-slot :srm/p2 [:busy])))
    (is (= :srm/kid#1 (get-in (machine-data :srm/p2) [:rf/spawned [:busy]]))
        "the mirror names the installed child, beside the registry slot")))

;; ---------------------------------------------------------------------------
;; (2) Generated-address collision.
;; ---------------------------------------------------------------------------

(deftest generated-address-collision-reject-clears-the-mirror
  (testing "two parent TYPES each mint :srm/worker#1; the second spawn is
            rejected, and its parent's mirror does not name the first
            parent's live child"
    (reg-child! :srm/worker)
    (reg-parent! :srm/pa {:machine-id :srm/worker})
    (reg-parent! :srm/pb {:machine-id :srm/worker})
    (rf/dispatch-sync [:srm/pa [:start]])
    (rf/dispatch-sync [:srm/pb [:start]])
    (is (= 1 (count (rf.machines.test-support/events-of
                      :rf.error/machine-spawn-all-duplicate-id)))
        "the collision is reported")
    (is (= :srm/pa (get-in (machine-data :srm/worker#1) [:rf/parent-id]))
        "the live child belongs to the first parent")
    (is (= :srm/worker#1 (get-in (machine-data :srm/pa) [:rf/spawned [:busy]]))
        "control: the owning parent's mirror still names its child")
    (is (nil? (registry-slot :srm/pb [:busy]))
        "the rejected parent's registry slot names no child")
    (is (not (contains? (:rf/spawned (machine-data :srm/pb)) [:busy]))
        "and neither does its mirror")))

;; ---------------------------------------------------------------------------
;; (3) A :spawn-all invoke rejected as a whole.
;; ---------------------------------------------------------------------------

(deftest spawn-all-reject-clears-the-mirror
  (testing "a :spawn-all with an unregistered child type is rejected
            atomically, and the parent's mirror carries no children map for
            that invoke"
    (reg-child! :srm/real)
    ;; :srm/phantom is never registered.
    (rf/reg-machine :srm/fan
      {:initial :idle
       :states  {:idle {:on {:start :busy}}
                 :busy {:spawn-all {:children        [{:id :a :machine-id :srm/real}
                                                      {:id :b :machine-id :srm/phantom}]
                                    :on-all-complete [:done]}
                        :on        {:done :idle}}}})
    (rf/dispatch-sync [:srm/fan [:start]])
    (is (true? (:rf/spawn-all-rejected? (registry-slot :srm/fan [:busy])))
        "the join slot holds the reject sentinel")
    (is (nil? (snapshot :srm/real#1))
        "no sibling installed")
    (is (not (contains? (:rf/spawned (machine-data :srm/fan)) [:busy]))
        "the mirror carries no children map for the rejected invoke")))

(deftest spawn-all-accept-control-keeps-the-mirror
  (testing "control: an all-registered :spawn-all writes the children map"
    (reg-child! :srm/one)
    (rf/reg-machine :srm/fan-ok
      {:initial :idle
       :states  {:idle {:on {:start :busy}}
                 :busy {:spawn-all {:children        [{:id :a :machine-id :srm/one}]
                                    :on-all-complete [:done]}
                        :on        {:done :idle}}}})
    (rf/dispatch-sync [:srm/fan-ok [:start]])
    (is (= {:a :srm/one#1} (get-in (machine-data :srm/fan-ok) [:rf/spawned [:busy]])))))
