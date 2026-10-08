(ns re-frame.fixed-actor-id-addressing-test
  "`:fixed-actor-id` is the one stable-name mechanism — the address IS the id
  (Spec 005 §Spec-spec keys). A re-entered fixed child is a new incarnation at
  the same address, and a spawn onto a LIVE fixed address runs the occupant
  through the ordinary destroy path before installing the replacement."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter})
  rf.machines.test-support/trace-capture-fixture)

(def ^:private snapshot rf.machines.test-support/snapshot)
(def ^:private data-of rf.machines.test-support/machine-data)

(defn- markable
  "`[:mark v]` stores v at `[:data :tag]`; the `:exit` bumps `exits`."
  [exits]
  {:initial :running
   :data    {}
   :actions {:mark (fn [{d :data ev :event}] {:data (assoc d :tag (second ev))})}
   :states  {:running {:exit (fn [_] (swap! exits inc) {})
                       :on   {:mark {:action :mark}}}}})

(defn- reg-install!
  "`[event-id payload?]` hand-emits a spawn of `machine-id` at `addr`, with
  `{:payload payload}` as its `:data` when a payload is given."
  [event-id machine-id addr]
  (rf/reg-event event-id
    (fn [_ [_ payload]]
      {:fx [[:rf.machine/spawn (cond-> {:machine-id machine-id :fixed-actor-id addr}
                                 payload (assoc :data {:payload payload}))]]})))

(defn- destroyed-ids []
  (mapv #(-> % :tags :actor-id) (rf.machines.test-support/events-of :rf.machine/destroyed)))

(deftest re-entered-fixed-actor-id-is-a-new-incarnation-at-the-same-address
  (rf/reg-machine :fai/child (markable (atom 0)))
  (rf/reg-machine :fai/parent
    {:initial :idle
     :states  {:idle    {:on {:go :working}}
               :working {:spawn {:machine-id :fai/child :fixed-actor-id :fai/pinned}
                         :on    {:back :idle}}}})
  (rf/dispatch-sync [:fai/parent [:go]])
  (rf/dispatch-sync [:fai/pinned [:mark :first]])
  (is (= :first (:tag (data-of :fai/pinned))) "the child lives at the address itself")
  (rf/dispatch-sync [:fai/parent [:back]])
  (is (nil? (snapshot :fai/pinned)) "the exit cascade destroyed it")
  (rf/dispatch-sync [:fai/parent [:go]])
  (is (nil? (:tag (data-of :fai/pinned))) "re-entry installed a FRESH incarnation at the same address")
  (rf/dispatch-sync [:fai/pinned [:mark :second]])
  (is (= :second (:tag (data-of :fai/pinned))) "a dispatch reaches the current incarnation"))

(deftest spawning-onto-an-occupied-fixed-address-destroys-the-occupant-then-installs
  (let [exits (atom 0)]
    (rf/reg-machine :fai/occupant (markable exits))
    (reg-install! :fai/install :fai/occupant :fai/slot)
    (rf/dispatch-sync [:fai/install :first])
    (rf/dispatch-sync [:fai/slot [:mark :first-tag]])
    (rf.machines.test-support/reset-captured!)
    (rf/dispatch-sync [:fai/install :second])
    (is (= 1 @exits) "the occupant's :exit ran exactly once — not overwritten, not destroyed twice")
    (is (= {:payload :second} (select-keys (data-of :fai/slot) [:payload :tag]))
        "the address carries a FRESH snapshot; the occupant's :tag did not survive")
    (is (= [:rf.machine/destroyed :rf.machine.spawn/spawned]
           (into [] (comp (map :operation) (filter #{:rf.machine/destroyed :rf.machine.spawn/spawned}))
                 (rf.machines.test-support/captured-events)))
        "the occupant's destroyed is observed BEFORE the replacement's spawned")
    (rf/dispatch-sync [:fai/slot [:mark :second-tag]])
    (is (= :second-tag (:tag (data-of :fai/slot))) "a dispatch reaches the replacement")))

(deftest a-rejected-incoming-spawn-leaves-the-occupant-intact
  (let [exits (atom 0)]
    (rf/reg-machine :fai/holder (markable exits))
    (reg-install! :fai/hold :fai/holder :fai/held)
    ;; :fai/never-registered is never registered, so the spawn is refused.
    (reg-install! :fai/bad-hold :fai/never-registered :fai/held)
    (rf/dispatch-sync [:fai/hold :original])
    (rf/dispatch-sync [:fai/bad-hold])
    (is (= [:original 0] [(:payload (data-of :fai/held)) @exits])
        "the refused spawn neither touched the occupant's snapshot nor ran its :exit")))

(deftest replacing-an-occupant-reaps-its-tracked-child-and-respawns-a-new-incarnation
  (rf/reg-machine :fai/kid (markable (atom 0)))
  (rf/reg-machine :fai/breeder
    {:initial :running
     :data    {}
     :states  {:running {:spawn {:machine-id :fai/kid :fixed-actor-id :fai/kid-addr}}}})
  (reg-install! :fai/install-breeder :fai/breeder :fai/breeder-slot)
  (rf/dispatch-sync [:fai/install-breeder])
  (rf/dispatch-sync [:fai/kid-addr [:mark :old]])
  (rf.machines.test-support/reset-captured!)
  (rf/dispatch-sync [:fai/install-breeder])
  (is (= [:fai/kid-addr :fai/breeder-slot] (destroyed-ids))
      "the tracked child ends once, inside its parent's teardown — ahead of the parent's own destroyed")
  (is (nil? (:tag (data-of :fai/kid-addr)))
      "the replacement's child at the same fixed address is a NEW incarnation"))

(deftest replacing-a-join-child-retains-its-cancellation-facts
  (rf/reg-machine :fai/jchild {:initial :running :data {} :states {:running {}}})
  (rf/reg-machine :fai/jparent
    {:initial :idle
     :data    {}
     :states  {:idle    {:on {:go :working}}
               :working {:spawn-all {:children        [{:id             :only
                                                        :machine-id     :fai/jchild
                                                        :fixed-actor-id :fai/joined}]
                                     :join            :all
                                     :on-all-complete [:all-done]}
                         :on        {:all-done :done}}
               :done    {}}})
  (reg-install! :fai/take-the-address :fai/jchild :fai/joined)
  (rf/dispatch-sync [:fai/jparent [:go]])
  (rf.machines.test-support/reset-captured!)
  (rf/dispatch-sync [:fai/take-the-address])
  ;; Both cancellation gates test for exactly :explicit.
  (is (= {:reason :explicit :rf.reply/cancelled? true :rf.reply/status :cancelled}
         (-> (rf.machines.test-support/events-of :rf.machine/destroyed)
             first
             :tags
             (select-keys [:reason :rf.reply/cancelled? :rf.reply/status])))))

;; A registered machine TYPE is a definition, never an occupant, so an address
;; equal to the type's own registration key needs both directions pinned.

(deftest first-spawn-at-an-address-equal-to-its-machine-type-is-not-a-replacement
  (rf/reg-machine :fai/selfnamed (markable (atom 0)))
  (reg-install! :fai/install-selfnamed :fai/selfnamed :fai/selfnamed)
  (rf/dispatch-sync [:fai/install-selfnamed :spawned])
  (rf/dispatch-sync [:fai/selfnamed [:mark :hit]])
  (is (empty? (destroyed-ids)) "the registered-but-unspawned TYPE was not torn down as an occupant")
  (is (= {:payload :spawned :tag :hit} (select-keys (data-of :fai/selfnamed) [:payload :tag]))
      "the spawned actor installed at its type's name and resolves through the type"))

(deftest replacing-a-live-actor-at-its-machine-types-own-name-keeps-the-definition
  (let [exits (atom 0)]
    (rf/reg-machine :fai/selfnamed2 (markable exits))
    (reg-install! :fai/install-selfnamed2 :fai/selfnamed2 :fai/selfnamed2)
    (reg-install! :fai/install-sibling2 :fai/selfnamed2 :fai/sibling2)
    (rf/dispatch-sync [:fai/install-selfnamed2 :first])
    (rf/dispatch-sync [:fai/install-sibling2])
    (rf/dispatch-sync [:fai/selfnamed2 [:mark :old]])
    (rf/dispatch-sync [:fai/install-selfnamed2 :second])
    (is (= [1 {:payload :second}] [@exits (select-keys (data-of :fai/selfnamed2) [:payload :tag])])
        "the occupant's teardown ran once and a FRESH actor replaced it")
    (rf/dispatch-sync [:fai/selfnamed2 [:mark :new]])
    (rf/dispatch-sync [:fai/sibling2 [:mark :sib]])
    (is (= [:new :sib] [(:tag (data-of :fai/selfnamed2)) (:tag (data-of :fai/sibling2))])
        "the shared definition survived: the replacement and a sibling of the type both resolve")))
