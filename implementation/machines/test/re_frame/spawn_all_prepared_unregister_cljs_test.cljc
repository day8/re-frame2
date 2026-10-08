(ns re-frame.spawn-all-prepared-unregister-cljs-test
  "An admitted `:spawn-all` child installs from its invoke's prepared result even
  when the registrar diverges from it mid-drain: validated once, live through its
  pinned prepared definition, while an undisturbed sibling keeps the
  hot-reloadable TYPE keyword.

  The mid-drain mutator is the child's own `[:schemas :data]` validator, which runs
  after the preflight retained the definition and before the install; trace
  listeners deliver post-drain, so they cannot reach that window."
  (:require
   #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.machines]
   [re-frame.machines.test-support :as rf.machines.test-support]
   [re-frame.registrar :as rf.registrar]
   [re-frame.schemas]
   [re-frame.schemas.malli]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter}))
  rf.machines.test-support/trace-capture-fixture)

;; The synthetic bootstrap moves :idle -> :ready, so a child past :idle resolved
;; a handler; an installed-but-unresolvable snapshot stays at :idle.
(def ^:private booting-child
  {:initial :idle
   :data    {}
   :states  {:idle    {:on {:rf.machine.spawn/spawned :ready}}
             :ready   {:on {:go :working}}
             :working {}}})

(def ^:private hot-reloaded-child
  (assoc booting-child :states {:idle         {:on {:rf.machine.spawn/spawned :ready}}
                                :ready        {:on {:go :hot-reloaded}}
                                :hot-reloaded {}}))

(defn- parent-over [children]
  {:initial :idle
   :states  {:idle    {:on {:start :forking}}
             :forking {:spawn-all {:children        children
                                   :join            :all
                                   :on-all-complete [:all/done]}
                       :on        {:all/done :ready}}
             :ready   {}}})

(defn- once [f]
  (let [fired (atom false)]
    #(when (compare-and-set! fired false true) (f))))

(deftest unregister-between-preflight-and-install-still-installs-the-admitted-child
  (let [pre-install (atom 0)
        unregister! (once #(rf.registrar/unregister! :event :sa/b))]
    (rf/reg-machine :sa/a booting-child)
    (rf/reg-machine :sa/b (assoc booting-child :schemas
                                 {:data [:fn (fn [_]
                                               ;; a call before :sa/b#1 exists is a pre-install validation
                                               (when (nil? (rf.machines.test-support/snapshot :sa/b#1))
                                                 (swap! pre-install inc))
                                               (unregister!)
                                               true)]}))
    (rf/reg-machine :sup/unreg (parent-over [{:id :a :machine-id :sa/a}
                                             {:id :b :machine-id :sa/b}]))
    (rf/dispatch-sync [:sup/unreg [:start]])
    (is (= 1 @pre-install) "validated once by install time: the install consumed the preflight's result")
    (is (= {:children {:a :sa/a#1 :b :sa/b#1}}
           (select-keys (get-in (rf.machines.test-support/runtime-db)
                                [:rf.runtime/machines :spawned :sup/unreg [:forking]])
                        [:children :rf/prepared]))
        "a live join naming both children, its prepared scratch consumed")
    (rf/reg-machine :sa/a hot-reloaded-child)
    (rf/dispatch-sync [:sa/a#1 [:go]])
    (rf/dispatch-sync [:sa/b#1 [:go]])
    (is (= [:hot-reloaded :working] (mapv rf.machines.test-support/machine-state [:sa/a#1 :sa/b#1]))
        "both are live: the keyword child follows a hot reload, the pinned child runs its prepared definition")))

(deftest reregister-to-unrelated-v2-mid-drain-keeps-one-coherent-authority
  ;; v2 names none of v1's states, so a child resolved through v2 would never
  ;; reach v1's :ready.
  (let [reregister! (once #(rf/reg-machine :sa/swap
                             {:initial :v2-initial
                              :data    {}
                              :states  {:v2-initial {:on {:rf.machine.spawn/spawned :v2-boot}}
                                        :v2-boot    {}}}))]
    (rf/reg-machine :sa/swap (assoc booting-child :schemas {:data [:fn (fn [_] (reregister!) true)]}))
    (rf/reg-machine :sup/swap (parent-over [{:id :c :machine-id :sa/swap}]))
    (rf/dispatch-sync [:sup/swap [:start]])
    (is (= :ready (rf.machines.test-support/machine-state :sa/swap#1)))))
