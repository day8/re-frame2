(ns re-frame.handler-source-egress-strip-cljs-test
  "Handler source-as-data lives on the registry and never in a frame's state, so
  the frame-state egress snapshot carries none of it (Conventions §Reserved
  registration metadata, 002 §The two-partition frame contract)."
  (:require
   #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
   [re-frame.core :as rf]
   ;; Installs the hooks `reg-machine` resolves through.
   [re-frame.machines]
   [re-frame.machines.paths :as rf.machines.paths]
   [re-frame.machines.test-support :as rf.machines.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter})))

(def ^:private source-as-data-keys
  #{:rf.handler/source :source-code :source-coords})

(defn- source-keys-anywhere
  "The `source-as-data-keys` found as a map key at any depth of `v`."
  [v]
  (letfn [(walk [acc x]
            (cond
              (map? x)  (reduce-kv
                          (fn [a k vv]
                            (walk (if (contains? source-as-data-keys k)
                                    (conj a k)
                                    a)
                                  vv))
                          acc x)
              (coll? x) (reduce walk acc x)
              :else     acc))]
    (walk #{} v)))

(deftest frame-state-snapshot-carries-no-source-as-data
  ;; The spec is a literal at the call site, so `reg-machine` co-locates its
  ;; source; the registry then holds source the snapshot must not.
  (rf/reg-event :tlf4if/seed (fn [_ [_ n]] {:db {:seeded n}}))
  (rf/reg-machine :tlf4if/probed
    {:initial :idle
     :data    {:n 0}
     :guards  {:ready? (fn [_] true)}
     :actions {:bump   (fn [{data :data}] {:data (update data :n inc)})}
     :states  {:idle    {:on {:go {:target :running :guard :ready? :action :bump}}}
               :running {}}})
  (rf/dispatch-sync [:tlf4if/seed 7])
  (rf/dispatch-sync [:tlf4if/probed [:rf.machine/start]])
  (rf/dispatch-sync [:tlf4if/probed [:go]])
  (let [source (fn [kind id] (:rf.handler/source (rf/handler-meta {:source :store :kind kind :id id})))
        state  (rf/frame-state-value :rf/default)]
    (is (every? string? [(source :event :tlf4if/seed)
                         (source :machine-guard [:tlf4if/probed :ready?])
                         (source :machine-action [:tlf4if/probed :bump])])
        "precondition: the registry carries the source")
    (is (= [7 :running]
           [(get-in state [:rf.db/app :seeded])
            (:state (get-in state (into [:rf.db/runtime] (rf.machines.paths/snapshot-path :tlf4if/probed))))])
        "precondition: the snapshot carries the seeded app-db and the live machine")
    (is (= #{} (source-keys-anywhere state)))))
