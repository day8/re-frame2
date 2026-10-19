(ns re-frame.final-singleton-rest-test
  "At whole-machine finality the runtime reaps only SPAWNED actors. A
  singleton rests in its final state: it stays readable, its completion fires
  exactly once, and an event to it is a no-op unless its definition declares
  a way out."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.schemas]
            [re-frame.schemas.malli]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter})
  rf.machines.test-support/trace-capture-fixture)

(def ^:private snapshot rf.machines.test-support/snapshot)
(def ^:private events-of rf.machines.test-support/events-of)

(defn- reg-finisher!
  "A singleton that finishes on `:finish` with `:v` as its output, and
  optionally a root `:on` way out of its final state."
  ([id] (reg-finisher! id nil))
  ([id root-on]
   (rf/reg-machine id
     (cond-> {:initial :idle
              :data    {:v 7}
              :states  {:idle {:on {:finish :done}}
                        :done {:final? true :output-key :v}}}
       root-on (assoc :on root-on)))))

(deftest a-final-singleton-stays-readable
  (reg-finisher! :fs/one)
  (rf/dispatch-sync [:fs/one [:finish]])
  (is (= {:state :done :data {:v 7}} (snapshot :fs/one))
      "the final snapshot stays at [:rf.runtime/machines :snapshots :fs/one]")
  (is (= {:state :done :data {:v 7}}
         (select-keys @(rf/subscribe [:rf/machine :fs/one]) [:state :data]))
      "and the :rf/machine sub reads it"))

(deftest completion-fires-exactly-once
  (reg-finisher! :fs/once)
  (rf/dispatch-sync [:fs/once [:finish]])
  (rf/dispatch-sync [:fs/once [:poke]])
  (rf/dispatch-sync [:fs/once [:finish]])
  (is (= [[:fs/once 7]]
         (mapv (juxt (comp :actor-id :tags) (comp :output :tags))
               (events-of :rf.machine/done)))
      "one :rf.machine/done, on the transition into finality")
  (is (empty? (events-of :rf.machine/destroyed)) "a singleton is not reaped"))

(deftest a-final-singleton-ignores-events-with-no-way-out
  (reg-finisher! :fs/stuck)
  (rf/dispatch-sync [:fs/stuck [:finish]])
  (rf/dispatch-sync [:fs/stuck [:finish]])
  (is (= {:state :done :data {:v 7}} (snapshot :fs/stuck))
      "no rebirth from :initial and no change: the event is a no-op"))

(deftest a-declared-way-out-still-leaves-the-final-state
  (reg-finisher! :fs/again {:reset :idle})
  (rf/dispatch-sync [:fs/again [:finish]])
  (rf/dispatch-sync [:fs/again [:reset]])
  (is (= :idle (:state (snapshot :fs/again))))
  (rf/dispatch-sync [:fs/again [:finish]])
  (is (= 2 (count (events-of :rf.machine/done)))
      "each entry into finality is one completion"))

(deftest a-spawned-actor-is-still-reaped-at-finality
  (rf/reg-machine :fs/kid
    {:initial :w
     :states  {:w    {:on {:finish :done}}
               :done {:final? true}}})
  (rf/reg-machine :fs/parent
    {:initial :idle
     :states  {:idle {:on {:go :busy}}
               :busy {:spawn {:machine-id :fs/kid}}}})
  (rf/dispatch-sync [:fs/parent [:go]])
  (rf/dispatch-sync [:fs/kid#1 [:finish]])
  (is (nil? (snapshot :fs/kid#1)) "a spawned actor is torn down at finality")
  (is (= 1 (count (events-of :rf.machine/done)))))

;; ---- born final: birth is an entry into finality ---------------------------

(defn- reg-born-final!
  "A singleton whose initial configuration is already whole-machine final,
  with `v` as its completion output. `:flat` is a lone `:final?` initial leaf;
  `:parallel` is two regions that each start in a `:final?` leaf, with no root
  `:on-done`. `schemas` is merged in as the definition's `:schemas`."
  [id shape v schemas]
  (rf/reg-machine id
    (cond-> (case shape
              :flat     {:initial :done
                         :data    {:v v}
                         :states  {:done {:final? true :output-key :v}}}
              :parallel {:type    :parallel
                         :data    {:v v}
                         :regions {:a {:initial :done
                                       :states  {:done {:final? true :output-key :v}}}
                                   :b {:initial :done
                                       :states  {:done {:final? true}}}}})
      schemas (assoc :schemas schemas))))

(defn- completions []
  (mapv (juxt (comp :actor-id :tags) (comp :output :tags))
        (events-of :rf.machine/done)))

(deftest a-singleton-born-final-completes-once
  (doseq [[shape id] [[:flat :fs/born-flat] [:parallel :fs/born-parallel]]]
    (testing (str shape " singleton, born on its first event")
      (rf.machines.test-support/reset-captured!)
      (reg-born-final! id shape 3 nil)
      (rf/dispatch-sync [id [:poke]])
      (is (= [[id 3]] (completions)) "birth into finality is one completion")
      (rf/dispatch-sync [id [:poke]])
      (is (= [[id 3]] (completions)) "a later unhandled event adds none")
      (is (= {:v 3} (:data (snapshot id))) "the singleton rests, readable")
      (is (empty? (events-of :rf.machine/destroyed)) "and is not reaped"))))

(deftest an-eager-start-into-finality-completes-once-and-rests
  (doseq [[shape id] [[:flat :fs/kick-flat] [:parallel :fs/kick-parallel]]]
    (testing (str shape " singleton, born by [:rf.machine/start]")
      (rf.machines.test-support/reset-captured!)
      (reg-born-final! id shape 4 nil)
      (rf/dispatch-sync [id [:rf.machine/start]])
      (is (= [[id 4]] (completions)) "an eager birth into finality is one completion")
      (rf/dispatch-sync [id [:poke]])
      (rf/dispatch-sync [id [:rf.machine/start]])
      (is (= [[id 4]] (completions)) "neither a later event nor a redundant start adds one")
      (is (= {:v 4} (:data (snapshot id))) "the singleton rests, readable")
      (is (empty? (events-of :rf.machine/destroyed)) "and is not reaped"))))

(deftest birth-into-finality-validates-the-completion-output
  (doseq [[shape id] [[:flat :fs/bad-flat] [:parallel :fs/bad-parallel]]]
    (testing (str shape " singleton whose completion output violates [:schemas :output]")
      (rf.machines.test-support/reset-captured!)
      (reg-born-final! id shape "nope" {:output [:int]})
      (rf/dispatch-sync [id [:poke]])
      (is (= [[id :completion "nope"]]
             (->> (events-of :rf.error/schema-validation-failure)
                  (filter #(= :machine-output (-> % :tags :where)))
                  (mapv (comp (juxt :machine-id :phase :value) :tags))))
          "the birth completion is validated as a transition's is")
      (is (= [[id "nope"]] (completions)) "and still flows"))))
