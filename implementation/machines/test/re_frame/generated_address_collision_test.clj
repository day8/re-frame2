(ns re-frame.generated-address-collision-test
  "A spawn whose GENERATED `<type>#<n>` address is already held by a LIVE actor
  is rejected fail-closed with `:rf.error/machine-spawn-all-duplicate-id`, and
  the occupant survives intact (Spec 005 §Declarative `:spawn`). The
  declarative counter lives in the spawning parent's snapshot while the
  address space is frame-wide, so a respawned parent, or a second instance of
  one parent type, re-mints a live address; nobody named that address, so
  there is no replacement request to honour."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter})
  rf.machines.test-support/trace-capture-fixture)

(def ^:private snapshot rf.machines.test-support/snapshot)
(def ^:private machine-data rf.machines.test-support/machine-data)

(defn- reject-events []
  (rf.machines.test-support/events-of :rf.error/machine-spawn-all-duplicate-id))

(defn- reg-child!
  "A child whose `[:mark v]` stores v, so a surviving occupant can be told apart
  from a replacement that overwrote it."
  [id]
  (rf/reg-machine id
    {:initial :running
     :data    {:mark :none}
     :actions {:mark (fn [{d :data ev :event}] {:data (assoc d :mark (second ev))})}
     :states  {:running {:on {:mark {:action :mark}}}}}))

(defn- reg-hire!
  "`[ev-id addr]` spawns `parent-type` at the fixed address `addr`."
  [ev-id parent-type]
  (rf/reg-event ev-id
    (fn [_ [_ addr]] {:fx [[:rf.machine/spawn {:machine-id     parent-type
                                               :fixed-actor-id addr}]]})))

(deftest a-respawned-parent-is-refused-its-live-orphans-generated-address
  ;; A hand-emitted child is tracked by no slot, so it outlives its parent's
  ;; destroy; the respawned parent's fresh counter then re-mints its address.
  (reg-child! :gac/child)
  (rf/reg-machine :gac/parent
    {:initial :idle
     :states  {:idle    {:on {:go    :working
                              :adopt {:action (fn [_]
                                                {:fx [[:rf.machine/spawn {:machine-id :gac/child}]]})}}}
               :working {:spawn {:machine-id :gac/child}}}})
  (reg-hire! :gac/hire :gac/parent)
  (rf/reg-event :gac/fire (fn [_ _] {:fx [[:rf.machine/destroy :gac/p]]}))
  (rf/dispatch-sync [:gac/hire :gac/p])
  (rf/dispatch-sync [:gac/p [:adopt]])
  (rf/dispatch-sync [:gac/child#1 [:mark :FIRST]])
  (rf/dispatch-sync [:gac/fire])
  (rf/dispatch-sync [:gac/hire :gac/p])
  (is (= {} (:rf/spawn-counter (snapshot :gac/p)))
      "the new incarnation's counter is seeded fresh")
  (rf.machines.test-support/reset-captured!)
  (rf/dispatch-sync [:gac/p [:go]])
  (is (= {:rejects [{:failing-id :gac/child#1 :machine-id :gac/child :parent-id :gac/p}]
          :spawned []
          :mark    :FIRST
          :next    nil}
         {:rejects (mapv #(select-keys (:tags %) [:failing-id :machine-id :parent-id])
                         (reject-events))
          :spawned (rf.machines.test-support/events-of :rf.machine.spawn/spawned)
          :mark    (:mark (machine-data :gac/child#1))
          :next    (snapshot :gac/child#2)})
      "one reject naming the address, no spawned trace, the occupant untouched,
       and no re-allocation to a fresh address"))

(deftest two-instances-of-one-parent-type-escape-only-through-a-prefixed-hand-emitted-spawn
  ;; Both instances' declarative spawns mint <child>#1 from their own counters.
  ;; The hand-emitted allocator's frame-wide counter is a separate stream that
  ;; declarative spawns do not advance and that does not skip an occupant, so
  ;; only a hand-emitted spawn under its own :id-prefix escapes.
  (reg-child! :gac2/child)
  (rf/reg-machine :gac2/parent
    {:initial :idle
     :actions {:bare     (fn [_] {:fx [[:rf.machine/spawn {:machine-id :gac2/child}]]})
               :prefixed (fn [_] {:fx [[:rf.machine/spawn {:machine-id :gac2/child
                                                           :id-prefix  :gac2/manual}]]})}
     :states  {:idle    {:on {:go :working}}
               :working {:spawn {:machine-id :gac2/child :data {:secret "hunter2"}}
                         :on    {:bare     {:action :bare}
                                 :prefixed {:action :prefixed}}}}})
  (reg-hire! :gac2/hire :gac2/parent)
  (rf/dispatch-sync [:gac2/hire :gac2/a])
  (rf/dispatch-sync [:gac2/hire :gac2/b])
  (rf/dispatch-sync [:gac2/a [:go]])
  (rf/dispatch-sync [:gac2/child#1 [:mark :FROM-A]])
  (rf.machines.test-support/reset-captured!)
  (rf/dispatch-sync [:gac2/b [:go]])
  (let [raw (first (reject-events))]
    (is (= :no-recovery (:recovery raw)))
    (is (not (str/includes? (pr-str raw) "hunter2"))
        "the reject carries structural context only, never the spawn :data"))
  (rf/dispatch-sync [:gac2/b [:bare]])
  (rf/dispatch-sync [:gac2/b [:bare]])
  (rf/dispatch-sync [:gac2/a [:prefixed]])
  (rf/dispatch-sync [:gac2/b [:prefixed]])
  (is (= {:refused  [:gac2/child#1 :gac2/child#1 :gac2/child#1]
          :child#2  nil
          :prefixed [true true]
          :mark     :FROM-A}
         {:refused  (mapv (comp :failing-id :tags) (reject-events))
          :child#2  (snapshot :gac2/child#2)
          :prefixed (mapv (comp some? snapshot) [:gac2/manual#1 :gac2/manual#2])
          :mark     (:mark (machine-data :gac2/child#1))})
      "B's declarative spawn and both bare retries are refused at the same
       address, the prefixed spawns get #1 and #2, and A's child is untouched"))
