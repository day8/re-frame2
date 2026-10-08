(ns re-frame.spawn-failure-routing-cljs-test
  "A child's FAILURE never reaches a SUCCESS route (the `:on-done` fold, the
  `:rf.machine.spawn/done` event), in either spawn form (Spec 005 §`:on-error`,
  §Child completion protocol). A `:spawn-all` failure reaches the parent only
  through `:on-any-failed` and the join's `:failed` set; a single `:spawn`
  failure always rides `[:rf.machine.spawn/error …]`, resolved through
  `:on-error`, else an explicit `:on`, else nowhere."
  (:require
   #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.machines]
   [re-frame.machines.test-support :as rf.machines.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter}))
  rf.machines.test-support/trace-capture-fixture)

(def ^:private state rf.machines.test-support/machine-state)
(def ^:private data  rf.machines.test-support/machine-data)
(def ^:private snapshot rf.machines.test-support/snapshot)

(def ^:private failure-payload {:status 503})

(defn- child-def
  "`:ok` ends on a plain `:final?` leaf whose output is `:the-value`; `:fail`
  ends on an `:error? true` leaf whose output is `failure-payload`; `:throw`
  raises from an action and stays alive."
  []
  {:initial :running
   :data    {:value :the-value :err failure-payload}
   :states  {:running {:on {:ok    :done
                            :fail  :failed
                            :throw {:action (fn [_] (throw (ex-info "child action threw" {:row :throw})))}}}
             :done    {:final? true :output-key :value}
             :failed  {:final? true :error? true :output-key :err}}})

(defn- recording-fold
  "An `:on-done` fold that records every result it is handed into `folds` and
  lands it in the parent's `:slot`."
  [folds]
  (fn [{:keys [data result]}]
    (swap! folds conj result)
    (assoc data :slot result)))

(def ^:private catch-error
  {:target :caught
   :action (fn [{d :data ev :event}] {:data (assoc d :caught (nth ev 2))})})

(defn- spawned
  [parent-id]
  (get-in (rf.machines.test-support/runtime-db)
          [:rf.runtime/machines :spawned parent-id [:working]]))

(defn- start-spawn-parent!
  "Register `child-id` and a single-`:spawn` parent whose `:working` state
  spawns it, start the parent, and return the child's address. `spawn-extra`
  merges into the `:spawn` map, `working-on` becomes `:working`'s `:on`, and
  `root-on` the machine root's `:on`."
  [parent-id child-id {:keys [spawn-extra working-on root-on]}]
  (rf/reg-machine child-id (child-def))
  (rf/reg-machine parent-id
    (cond-> {:initial :idle
             :data    {:slot nil}
             :states  {:idle    {:on {:start :working}}
                       :working (cond-> {:spawn (merge {:machine-id child-id} spawn-extra)}
                                  working-on (assoc :on working-on))
                       :next    {}
                       :errored {}
                       :caught  {}}}
      root-on (assoc :on root-on)))
  (rf/dispatch-sync [parent-id [:start]])
  (spawned parent-id))

(defn- start-join-parent!
  "Register `child-id` and a one-child `:spawn-all` parent whose child's
  `:on-done` is `fold`, start it, and return the child's address.
  `:on-any-failed` is declared only when `on-any-failed?`."
  [parent-id child-id fold on-any-failed?]
  (rf/reg-machine child-id (child-def))
  (rf/reg-machine parent-id
    {:initial :idle
     :data    {:slot nil}
     :states  {:idle    {:on {:start :working}}
               :working {:spawn-all (cond-> {:children        [{:id         :a
                                                                 :machine-id child-id
                                                                 :on-done    fold}]
                                             :on-all-complete [:all-ok]}
                                      on-any-failed? (assoc :on-any-failed [:any-failed]))
                         :on        {:all-ok     :next
                                     :any-failed {:target :errored
                                                  :action (fn [{d :data ev :event}]
                                                            {:data (assoc d :failure (nth ev 2))})}}}
               :next    {}
               :errored {}}})
  (rf/dispatch-sync [parent-id [:start]])
  (get-in (spawned parent-id) [:children :a]))

;; ---- :spawn-all — the per-child :on-done is success-only --------------------

(deftest j1-failed-join-child-reaches-on-any-failed-and-never-its-fold
  (let [folds (atom [])
        child (start-join-parent! ::j1 ::j1-child (recording-fold folds) true)]
    (rf/dispatch-sync [child [:fail]])
    (is (= [:errored failure-payload []]
           [(state ::j1) (:failure (data ::j1)) @folds]))))

(deftest j3-failed-join-child-without-on-any-failed-lands-in-the-failed-set-only
  (let [folds (atom [])
        child (start-join-parent! ::j3 ::j3-child (recording-fold folds) false)]
    (rf/dispatch-sync [child [:fail]])
    (is (= [:working #{:a} []]
           [(state ::j3) (:failed (spawned ::j3)) @folds]))))

;; ---- single :spawn — every failure rides the failure carrier ----------------

(deftest p1-failed-child-never-reaches-on-done
  (let [folds (atom [])
        child (start-spawn-parent! ::p1 ::p1-child {:spawn-extra {:on-done (recording-fold folds)}})]
    (rf/dispatch-sync [child [:fail]])
    (is (= [:working []] [(state ::p1) @folds]))))

(deftest p3-failed-child-reaches-an-explicit-error-handler-without-on-error
  (let [child (start-spawn-parent! ::p3 ::p3-child {:root-on {:rf.machine.spawn/error catch-error}})]
    (rf/dispatch-sync [child [:fail]])
    (is (= [:caught failure-payload] [(state ::p3) (:caught (data ::p3))]))))

(deftest p5-succeeded-child-folds-and-takes-the-explicit-done-transition
  (let [folds (atom [])
        child (start-spawn-parent! ::p5 ::p5-child {:spawn-extra {:on-done (recording-fold folds)}
                                                    :working-on  {:rf.machine.spawn/done :next}})]
    (rf/dispatch-sync [child [:ok]])
    (is (= [:next [:the-value] :the-value]
           [(state ::p5) @folds (:slot (data ::p5))]))))

;; The `:on-error` route itself is machines-on-error-cljs-test's.

(deftest p7-throwing-child-reaches-an-explicit-error-handler-without-on-error
  (let [child (start-spawn-parent! ::p7 ::p7-child {:root-on {:rf.machine.spawn/error catch-error}})]
    (rf/dispatch-sync [child [:throw]])
    (is (= [:caught :rf.error/machine-action-exception]
           [(state ::p7) (:rf.error/id (:caught (data ::p7)))]))))

(deftest p8-throwing-child-with-no-hooks-stays-alive
  (let [child (start-spawn-parent! ::p8 ::p8-child {})]
    (rf/dispatch-sync [child [:throw]])
    (is (= [:working true []]
           [(state ::p8) (some? (snapshot child))
            (rf.machines.test-support/events-of :rf.machine/done)]))))
