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
            [re-frame.error-emit :as rf.error-emit]
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
  (is (nil? (get-in (snapshot :gac/p) [:rf/runtime :spawn-counter]))
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

(deftest a-spawn-all-is-refused-whole-when-another-parents-child-holds-its-generated-address
  ;; Two parent types each :spawn-all one child of one type, and both mint
  ;; <child>#1. An admitted child always installs, so the batch preflight is
  ;; where the second batch has to be refused: whole, on the always-on record,
  ;; with the first parent's live child and join left exactly as they were.
  (reg-child! :gac3/child)
  (doseq [p [:gac3/pa :gac3/pb]]
    (rf/reg-machine p
      {:initial :idle
       :states  {:idle    {:on {:go :working}}
                 :working {:spawn-all {:children        [{:id :c :machine-id :gac3/child}]
                                       :on-all-complete [:all-done]}}}}))
  (rf/dispatch-sync [:gac3/pa [:go]])
  (rf/dispatch-sync [:gac3/child#1 [:mark :FROM-A]])
  (rf.machines.test-support/reset-captured!)
  (let [seen (atom [])]
    (rf.error-emit/register-error-listener! ::recorder #(swap! seen conj %))
    (try (rf/dispatch-sync [:gac3/pb [:go]])
         (finally (rf.error-emit/unregister-error-listener! ::recorder)))
    (is (= {:records [{:rf.error/id :rf.error/machine-spawn-all-duplicate-id
                       :failing-id  :gac3/child#1
                       :machine-id  :gac3/child
                       :parent-id   :gac3/pb}]
            :traces  [:gac3/child#1]
            :spawned []
            :owner   :gac3/pa
            :mark    :FROM-A
            :b-join  nil}
           {:records (mapv #(select-keys % [:rf.error/id :failing-id :machine-id :parent-id])
                           (filterv #(= :rf.error/machine-spawn-all-duplicate-id (:rf.error/id %))
                                    @seen))
            :traces  (mapv (comp :failing-id :tags) (reject-events))
            :spawned (rf.machines.test-support/events-of :rf.machine.spawn/spawned)
            :owner   (get-in (snapshot :gac3/child#1) [:rf/runtime :join-child :parent-id])
            :mark    (:mark (machine-data :gac3/child#1))
            :b-join  (get-in (snapshot :gac3/pb) [:rf/runtime :spawned])})
        "one always-on record and one dev trace name the address; nothing spawns,
         A's child still belongs to A's join, and B's mirror names no child")
    (is (not-any? #(contains? % :reason) @seen)
        "the always-on record is structural; the prose rides the dev trace only")))
