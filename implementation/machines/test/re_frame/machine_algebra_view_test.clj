(ns re-frame.machine-algebra-view-test
  "`re-frame.machines.tooling` lowers each registered machine, and each live
  snapshot, to a `:process` algebra node, and names the machines a selector sub
  reads (spec/Derivations.md §Machines expose algebra views)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.paths :as rf.machines.paths]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.machines.tooling :as rf.machines.tooling]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private fixed-classifications
  {:kind          :process
   :refinement    :machine-process
   :storage       :runtime-db
   :lifecycle     :machine-instance
   :materialized? true})

(def upload-machine
  {:initial :idle
   :data    {:progress 0}
   :actions {:start-upload    (fn [_] {})
             :record-progress (fn [_] {})}
   :states  {:idle      {:on {:upload/start :uploading}}
             :uploading {:entry :start-upload
                         :on    {:upload/progress  {:action :record-progress}
                                 :upload/succeeded :done
                                 :upload/failed    :failed}}
             :failed    {}
             :done      {}}})

(def ^:private upload-node
  "The node `upload-machine` lowers to under `:upload/main`, `:source` aside."
  (merge fixed-classifications
         {:id          :upload/main
          :source-form {:kind :reg-machine :id :upload/main}
          :inputs      [[:event :upload/failed] [:event :upload/progress]
                        [:event :upload/start] [:event :upload/succeeded]]
          :evaluation  #{:on-transition}
          :output      [:runtime (rf.machines.paths/snapshot-path :upload/main)]
          :owner       [:machine :upload/main]
          :spawns?     false}))

(defn- static-node [machine-id]
  (get (rf.machines.tooling/machine-algebra-view) machine-id))

(deftest machines-facade-exposes-no-algebra-view
  ;; Derivations.md: the algebra views ship no public accessor.
  (is (every? nil? (map #(ns-resolve 're-frame.machines %)
                        '[machine-algebra-view machine-instance-algebra-view]))))

(deftest machine-exposes-its-full-process-node
  (rf/reg-machine :upload/main upload-machine)
  (is (= upload-node (dissoc (static-node :upload/main) :source))))

(deftest declared-event-inputs-are-walked-from-the-whole-state-tree
  (doseq [[label machine-id spec expected]
          [["reserved framework events and the :* wildcard are not inputs"
            :guarded/main
            {:initial :a
             :data    {}
             :states  {:a {:on {:go           :b
                                :*            :a
                                :rf.machine/x :b}}
                       :b {}}}
            [[:event :go]]]
           ["leaf + parent :on keys are collected across the hierarchy, sorted"
            :auth/flow
            {:initial :authenticated
             :data    {}
             :states  {:authenticated
                       {:initial :dashboard
                        :on      {:logout :loggedout}
                        :states  {:dashboard {:on {:open-settings :settings}}
                                  :settings  {:on {:close :dashboard}}}}
                       :loggedout {}}}
            [[:event :close] [:event :logout] [:event :open-settings]]]
           [":regions state-maps are walked like :states"
            :par/main
            {:initial :running
             :data    {}
             :states  {:running
                       {:type    :parallel
                        :regions {:left  {:initial :l0 :states {:l0 {:on {:left/go :l1}} :l1 {}}}
                                  :right {:initial :r0 :states {:r0 {:on {:right/go :r1}} :r1 {}}}}}}}
            [[:event :left/go] [:event :right/go]]]]]
    (rf/reg-machine machine-id spec)
    (is (= expected (:inputs (static-node machine-id))) label)))

(deftest evaluation-policy-follows-the-declared-timers-and-spawns
  (rf/reg-machine :worker/child {:initial :running :data {} :states {:running {}}})
  (doseq [[label machine-id spec expected]
          [["an :after delayed transition adds :scheduled"
            :timed/main
            {:initial :waiting
             :data    {}
             :states  {:waiting   {:after {1000 :timed-out}}
                       :timed-out {}}}
            [#{:on-transition :scheduled} false]]
           ["a :spawn-bearing state adds :on-reply and sets :spawns?"
            :parent/main
            {:initial :idle
             :data    {}
             :states  {:idle    {:on {:go :working}}
                       :working {:spawn {:machine-id :worker/child}}}}
            [#{:on-transition :on-reply} true]]]]
    (rf/reg-machine machine-id spec)
    (is (= expected ((juxt :evaluation :spawns?) (static-node machine-id))) label)))

(deftest source-coords-surface-in-the-node
  ;; Registered by symbol, so these are the `reg-machine` call site's coords.
  (rf/reg-machine :upload/main upload-machine)
  (let [{:keys [ns line]} (:source (static-node :upload/main))]
    (is (= 're-frame.machine-algebra-view-test ns))
    (is (integer? line))))

(deftest data-schema-and-doc-pass-through
  (rf/reg-machine :doced/main
    {:initial :a
     :doc     "a documented machine"
     :data    {:n 0}
     :schemas {:data [:map [:n :int]]}
     :states  {:a {}}})
  (is (= {:schema [:map [:n :int]] :doc "a documented machine"}
         (select-keys (static-node :doced/main) [:schema :doc]))))

(defn- seed-snapshot!
  "Install `snap` as `machine-id`'s runtime-db snapshot."
  [machine-id snap]
  (let [seed-id (keyword "test" (str "seed-" (name machine-id)))]
    (rf/reg-event seed-id
      (fn [{rt :rf.db/runtime} _]
        {:rf.db/runtime (assoc-in (or rt {}) (rf.machines.paths/snapshot-path machine-id) snap)}))
    (rf/dispatch-sync [seed-id])))

(deftest live-instance-view-projects-each-materialized-snapshot
  ;; A spawned actor's snapshot names its type; :worker/child itself is never
  ;; instantiated, so it has no live node. The snapshot value is not inlined.
  (rf/reg-machine :upload/main upload-machine)
  (rf/reg-machine :worker/child {:initial :running :data {} :states {:running {:on {:tick :running}}}})
  (seed-snapshot! :upload/main {:state :uploading :data {:progress 42}})
  (seed-snapshot! :worker#1 {:state :running :data {} :rf/machine-type :worker/child})
  (is (= {:upload/main (assoc upload-node :state :uploading :spawned? false)
          :worker#1    (merge fixed-classifications
                              {:id          :worker#1
                               :source-form {:kind :reg-machine :id :worker/child}
                               :inputs      [[:event :tick]]
                               :evaluation  #{:on-transition}
                               :output      [:runtime (rf.machines.paths/snapshot-path :worker#1)]
                               :owner       [:machine :worker#1]
                               :spawns?     false
                               :state       :running
                               :spawned?    true})}
         (update-vals (rf.machines.tooling/machine-instance-algebra-view :rf/default)
                      #(dissoc % :source)))))

(deftest machine-selector-targets-extractor
  (rf/reg-machine :upload/main upload-machine)
  (rf/reg-sub :upload/progress
    {:inputs [[:rf/machine :upload/main]]}
    (fn [[snapshot] _] (get-in snapshot [:data :progress] 0)))
  (rf/reg-sub :upload/has-tag
    {:inputs [[:rf.machine/has-tag? :upload/main :busy]]}
    (fn [[has?] _] has?))
  (rf/reg-sub :combined
    {:inputs [[:rf/machine :upload/main] [:rf/machine :download/main]]}
    (fn [[u d] _] [u d]))
  (rf/reg-sub :plain/sub (fn [db _] (:x db)))
  (let [subs [:upload/progress :upload/has-tag :combined :plain/sub :nope/missing]]
    (is (= [#{:upload/main} #{:upload/main} #{:upload/main :download/main} #{} #{}]
           (map rf.machines.tooling/machine-selector-targets subs)))
    (is (= [true true true false false]
           (map rf.machines.tooling/machine-selector? subs)))))
