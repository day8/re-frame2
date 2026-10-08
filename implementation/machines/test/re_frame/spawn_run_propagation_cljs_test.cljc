(ns re-frame.spawn-run-propagation-cljs-test
  "Run propagation crosses the machine SPAWN edge (Spec 002 §Run propagation):
  per-call `:fx-overrides` and the `:origin` / `:trace-id` lineage reach every
  fx a spawned child fires, the child's first event keeps `:source
  :machine-spawn` and its FIFO place (it is not machine-internal), and a
  per-frame `:fx-overrides` reaches the children too."
  (:require
   #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.late-bind :as rf.late-bind]
   [re-frame.machines]
   [re-frame.machines.test-support :as rf.machines.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter})))

(def ^:private stub-overrides {:sp/probe :sp/probe.stub})

(defn- register-probe!
  "Register the probe fx and its stub; return the atom both record into. The
  stub also records the dispatch envelope it ran under."
  []
  (let [fired (atom [])]
    (rf/reg-fx :sp/probe
      (fn [_ args] (swap! fired conj (assoc args :via :real))))
    (rf/reg-fx :sp/probe.stub
      (fn [m args]
        (let [env (:envelope m)]
          (swap! fired conj (assoc args
                                   :via      :stub
                                   :source   (:source env)
                                   :origin   (:origin env)
                                   :trace-id (:trace-id env))))))
    fired))

(defn- register-machines!
  "A child that fires the probe from its initial `:entry` and from a `:begin`
  action, and a parent that spawns it with `:start [:begin]`."
  []
  (rf/reg-machine :sp/child
    {:initial :running
     :states  {:running {:entry (fn [_] {:fx [[:sp/probe {:at :entry}]]})
                         :on    {:begin {:action (fn [_] {:fx [[:sp/probe {:at :begin}]]})}}}}})
  (rf/reg-machine :sp/spawner
    {:initial :idle
     :states  {:idle     {:on {:go :spawning}}
               :spawning {:spawn {:machine-id :sp/child :start [:begin]}}}}))

(deftest per-call-overrides-and-lineage-reach-spawned-children
  (let [fired (register-probe!)
        sink  (atom [])
        real  (rf.late-bind/get-fn :router/dispatch!)]
    (register-machines!)
    (try
      (rf.late-bind/set-fn! :router/dispatch!
                            (fn [event opts]
                              (swap! sink conj opts)
                              (real event opts)))
      (rf/dispatch-sync [:sp/spawner [:go]]
                        {:fx-overrides stub-overrides
                         :origin       :sp/tool
                         :trace-id     "sp-trace"})
      (finally
        (rf.late-bind/set-fn! :router/dispatch! real)))
    (is (= {:stub 2} (frequencies (map :via @fired)))
        "every probe the child fired hit the per-call stub")
    (is (every? #(= {:source :machine-spawn :origin :sp/tool :trace-id "sp-trace"}
                    (select-keys % [:source :origin :trace-id]))
                @fired))
    (is (= [nil] (map :rf.machine/internal? (filter #(= :machine-spawn (:source %)) @sink)))
        "the one spawn dispatch is not machine-internal, so the newborn's first event stays FIFO")))

(deftest per-frame-overrides-still-reach-spawned-children
  (let [fired (register-probe!)]
    (register-machines!)
    (rf/make-frame {:id :sp/frame :fx-overrides stub-overrides})
    (rf/dispatch-sync [:sp/spawner [:go]] {:frame :sp/frame})
    (is (= {:stub 2} (frequencies (map :via @fired))))))
