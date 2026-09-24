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
       whose mirror is the reducer's `{<child-id> <spawned-id>}` map;
    4. a single `:spawn` whose child fails its spawn-time `[:schemas :data]`
       validation.

  Each rejection is paired with its accepted control, whose mirror still names
  the installed child. The schema rejection also pins the cleanup's
  exact-incarnation fence: a validator that destroys the owning frame and
  publishes a same-id successor leaves the successor's mirror alone."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.machines]
            [re-frame.machines.lifecycle-fx.spawn :as rf.machines.lifecycle-fx.spawn]
            [re-frame.machines.test-support :as rf.machines.test-support]
            ;; The schemas artefact runs the `[:schemas :data]` validator the
            ;; spawn-time gate routes through; the `.malli` adapter publishes
            ;; Malli's validate/explain into the late-bind table.
            [re-frame.schemas]
            [re-frame.schemas.malli]
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

;; ---------------------------------------------------------------------------
;; (4) Spawn-time [:schemas :data] rejection.
;; ---------------------------------------------------------------------------

(def ^:private StrictData
  "`:n` must be a positive int: the child's spawn-time `[:schemas :data]` gate."
  [:map [:n pos-int?]])

(defn- reg-strict-child!
  "A child whose registered `:data` conforms, so the spawn's `:data` override
  is what the gate judges."
  [id]
  (rf/reg-machine id {:initial :running
                      :data    {:n 1}
                      :schemas {:data StrictData}
                      :states  {:running {}}}))

(defn- spawn-schema-failures
  "The captured `:phase :spawn` schema-validation failures."
  []
  (filterv #(= :spawn (get-in % [:tags :phase]))
           (rf.machines.test-support/events-of :rf.error/schema-validation-failure)))

(deftest schema-reject-clears-the-mirror
  (testing "a :spawn whose :data fails the child's [:schemas :data] schema is
            rejected, and the parent's :rf/spawned mirror names no child for
            that invoke"
    (reg-strict-child! :srm/strict)
    (reg-parent! :srm/ps {:machine-id :srm/strict
                          :id-prefix  :srm/rejected
                          :data       {:n 0}})
    (rf/dispatch-sync [:srm/ps [:start]])
    (is (= :busy (rf.machines.test-support/machine-state :srm/ps))
        "the parent still enters :busy — only the child spawn is rejected")
    (is (= 1 (count (spawn-schema-failures)))
        "the rejection reports its one schema diagnostic")
    (is (nil? (snapshot :srm/rejected#1))
        "no child snapshot installed")
    (is (nil? (registry-slot :srm/ps [:busy]))
        "the registry slot names no child")
    (is (not (contains? (:rf/spawned (machine-data :srm/ps)) [:busy]))
        "and neither does the mirror")
    (is (not (contains? (machine-data :srm/ps) :rf/spawned))
        "the emptied :rf/spawned map is pruned")))

(deftest schema-conforming-control-keeps-the-mirror
  (testing "control: the same spawn with conforming :data installs, and the
            mirror names it"
    (reg-strict-child! :srm/strict-ok)
    (reg-parent! :srm/ps-ok {:machine-id :srm/strict-ok
                             :id-prefix  :srm/accepted
                             :data       {:n 2}})
    (rf/dispatch-sync [:srm/ps-ok [:start]])
    (is (empty? (spawn-schema-failures))
        "a conforming spawn reports no schema diagnostic")
    (is (some? (snapshot :srm/accepted#1))
        "the child is live")
    (is (= :srm/accepted#1 (registry-slot :srm/ps-ok [:busy])))
    (is (= :srm/accepted#1 (get-in (machine-data :srm/ps-ok) [:rf/spawned [:busy]]))
        "the mirror names the installed child, beside the registry slot")))

(deftest schema-reject-after-owner-loss-leaves-the-successor-mirror-alone
  (testing "a failing validator that destroys the owning frame and publishes a
            same-id successor: the successor's mirror entry for the same
            address survives, because the cleanup is bound to the owner's exact
            incarnation"
    (reg-strict-child! :srm/strict-lost)
    (let [frame-a  :srm/lost-frame
          parent   :srm/lost-parent
          address  :srm/lost#1
          seed     (fn [rt]
                     (assoc-in rt [:rf.runtime/machines :snapshots parent]
                               {:state :busy
                                :data  {:rf/spawned {[:busy] address}}}))
          fired?   (atom false)
          b-seeded (atom nil)
          orig     (rf.late-bind/get-fn :schemas/validate-with-registered-fn)]
      (rf/make-frame {:id frame-a})
      (rf.frame/swap-runtime-db! frame-a seed)
      (try
        ;; The validator is application code: on its first call it destroys
        ;; the owning incarnation A, publishes same-id B carrying the same
        ;; mirror entry, and fails the data.
        (rf.late-bind/set-fn! :schemas/validate-with-registered-fn
          (fn [_schema _data]
            (if (compare-and-set! fired? false true)
              (do (rf.frame/destroy-frame! frame-a)
                  (rf/make-frame {:id frame-a})
                  (rf.frame/swap-runtime-db! frame-a seed)
                  (reset! b-seeded (rf.machines.test-support/runtime-db frame-a))
                  false)
              true)))
        ;; Driven directly under A's event-owner token: B is published on the
        ;; validator's own stack, which a live `dispatch-sync` drain forbids.
        (rf.frame/call-with-event-owner-token frame-a
          (rf.frame/frame-incarnation-token frame-a)
          (fn []
            (rf.machines.lifecycle-fx.spawn/spawn-fx
              {:frame frame-a}
              {:machine-id    :srm/strict-lost
               :data          {:n 0}
               :rf/parent-id  parent
               :rf/invoke-id  [:busy]
               :rf/spawned-id address})))
        (is (true? @fired?)
            "the validator ran and replaced the owning incarnation")
        (is (= address (get-in @b-seeded [:rf.runtime/machines :snapshots parent
                                          :data :rf/spawned [:busy]]))
            "B was seeded with a mirror entry naming the rejected address")
        (is (= @b-seeded (rf.machines.test-support/runtime-db frame-a))
            "B's runtime-db, mirror entry included, is untouched")
        (finally
          (rf.late-bind/set-fn! :schemas/validate-with-registered-fn orig)
          (rf.frame/destroy-frame! frame-a))))))
