(ns re-frame.spawn-on-done-transition-cljs-test
  "`:spawn :on-done` is a fn (the `:data` fold) or an `:on`-shaped transition
  the parent takes when the child completes, resolved at the spawning state's
  level with the completion carrier on `:event`. Registration refuses any other
  value, and checks a transition-shaped one like every transition slot."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.machines :as rf.machines]
   [re-frame.machines.cofx-attach :as rf.machines.cofx-attach]
   [re-frame.machines.test-support :as rf.machines.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter}))
  rf.machines.test-support/trace-capture-fixture)

(def ^:private snapshot rf.machines.test-support/snapshot)

(defn- child-of [parent-id invoke-id]
  (get-in (rf.machines.test-support/runtime-db)
          [:rf.runtime/machines :spawned parent-id invoke-id]))

(defn- refusal
  "Register `definition` and return the refusal's ex-data, or nil when it registers."
  [machine-id definition]
  (try (rf/reg-machine machine-id definition)
       nil
       (catch #?(:clj Exception :cljs :default) e
         (or (ex-data e) {:rf.error/id ::no-ex-data}))))

(defn- reg-child!
  "`:ok <v>` finishes on a plain `:final?` leaf whose result is `v`."
  [child-id]
  (rf/reg-machine child-id
    {:initial :running
     :data    {}
     :states  {:running {:on {:ok {:target :done
                                   :action (fn [{data :data ev :event}]
                                             {:data (assoc data :token (second ev))})}}}
               :done    {:final? true :output-key :token}}}))

(defn- store-result [{data :data ev :event}]
  {:data (assoc data :token (:result (nth ev 2)))})

(defn- login-parent
  "A parent that spawns `child-id` from `:authenticating` with `on-done`;
  `authenticating` merges onto the spawning state, `states` adds states."
  ([child-id on-done] (login-parent child-id on-done {} {}))
  ([child-id on-done authenticating states]
   {:initial :idle
    :data    {:token nil}
    :actions {:store store-result}
    :states  (merge {:idle           {:on {:login :authenticating}}
                     :authenticating (merge {:spawn {:machine-id child-id
                                                     :on-done    on-done}}
                                            authenticating)
                     :authenticated  {}}
                    states)}))

(defn- run-login!
  "Register `parent`, enter `:authenticating`, finish the child with `result`;
  return the parent's snapshot."
  [parent-id child-id parent result]
  (reg-child! child-id)
  (rf/reg-machine parent-id parent)
  (rf/dispatch-sync [parent-id [:login]])
  (rf/dispatch-sync [(child-of parent-id [:authenticating]) [:ok result]])
  (snapshot parent-id))

(defn- result-is [v]
  (fn [{ev :event}] (= v (:result (nth ev 2)))))

;; ---- the transition forms ---------------------------------------------------

(deftest transition-map-on-done-advances-the-parent
  (testing "the parent moves to the target and the action reads the child's result off the carrier"
    (let [snap (run-login! :sodt-map/login :sodt-map/auth
                           (login-parent :sodt-map/auth {:target :authenticated :action :store})
                           "T")]
      (is (= [:authenticated "T"] [(:state snap) (get-in snap [:data :token])])))))

(deftest vector-path-on-done-targets-an-absolute-path
  (testing "a vector :on-done is an absolute path, and is never invoked as a :data fold"
    (let [snap (run-login! :sodt-vec/login :sodt-vec/auth
                           (login-parent :sodt-vec/auth [:signed-in :dashboard] {}
                                         {:signed-in {:initial :home
                                                      :states  {:home {} :dashboard {}}}})
                           "T")]
      (is (= [[:signed-in :dashboard] []]
             [(:state snap) (rf.machines.test-support/events-of :rf.error/machine-action-exception)])))))

(deftest guarded-candidates-take-the-first-passing-one
  (let [on-done [{:guard (result-is "admin") :target :admin :action :store}
                 {:target :authenticated}]
        run     (fn [parent-id child-id result]
                  (:state (run-login! parent-id child-id
                                      (login-parent child-id on-done {} {:admin {}})
                                      result)))]
    (is (= [:admin :authenticated]
           [(run :sodt-g1/login :sodt-g1/auth "admin")
            (run :sodt-g2/login :sodt-g2/auth "guest")]))))

(deftest all-guards-failing-falls-through-to-an-explicit-arm
  (is (= :fallback
         (:state (run-login! :sodt-ft/login :sodt-ft/auth
                             (login-parent :sodt-ft/auth
                                           {:guard (result-is "admin") :target :authenticated}
                                           {:on {:rf.machine.spawn/done :fallback}}
                                           {:fallback {}})
                             "guest")))))

;; ---- registration ----------------------------------------------------------

(deftest malformed-on-done-is-refused-at-registration
  (doseq [v ["oops" nil []]]
    (is (= [:rf.error/machine-bad-on-done-clause :authenticating]
           ((juxt :rf.error/id :state) (refusal :sodt-bad/login (login-parent :sodt-bad/auth v))))
        (pr-str v))))

(deftest transition-on-done-is-checked-like-every-transition-slot
  (doseq [[on-done error-id] [[{:target :nowhere} :rf.error/machine-unresolved-target]
                              [{:target :authenticated :guard :no-such-guard}
                               :rf.error/machine-unresolved-guard]]]
    (is (= error-id (:rf.error/id (refusal :sodt-chk/login (login-parent :sodt-chk/auth on-done))))
        (pr-str on-done))))

;; ---- parallel regions ------------------------------------------------------

(deftest region-on-done-transition-is-region-scoped
  (testing "a region's :spawn :on-done moves only that region, and its guard reads the in-region invoke-id"
    (reg-child! :sodt-r/auth)
    (rf/reg-machine :sodt-r/parent
      {:type    :parallel
       :data    {}
       :regions {:loader {:initial :working
                          :states  {:working {:spawn {:machine-id :sodt-r/auth
                                                      :on-done    {:guard  (fn [{ev :event}]
                                                                             (= [:working] (nth ev 1)))
                                                                   :target :ready}}}
                                    :ready   {}}}
                 :other  {:initial :idle
                          :states  {:idle {}}}}})
    (rf/dispatch-sync [:sodt-r/parent [:rf.machine.spawn/spawned]])
    (rf/dispatch-sync [(child-of :sodt-r/parent [:loader :working]) [:ok "T"]])
    (is (= {:loader :ready :other :idle} (:state (snapshot :sodt-r/parent))))))

(deftest foreign-region-declines-the-spawn-on-done-arm
  (testing "a carrier addressed to region :a never fires region :b's :spawn :on-done at the same in-region path"
    ;; Registration refuses two regions spawning at one in-region path, so only
    ;; the pure transition reaches the resolver's region decline.
    (let [region (fn [target]
                   {:initial :loading
                    :states  {:loading {:spawn {:machine-id :sodt/any :on-done target}}
                              :a-done  {}
                              :b-done  {}}})]
      (is (= {:a :a-done :b :loading}
             (get-in (rf.machines/machine-transition
                       {:type    :parallel
                        :data    {}
                        :regions {:a (region :a-done)
                                  :b (region :b-done)}}
                       {:state {:a :loading :b :loading} :data {}}
                       [:rf.machine.spawn/done [:a :loading] {:result "T" :error? false} 1])
                     [:snapshot :state]))))))

;; ---- recorded facts --------------------------------------------------------

(def ^:private rolled-six
  {:rf.cofx/requires [:test/roll8]
   :fn (fn [{cofx :rf.cofx}] (= 6 (:test/roll8 cofx)))})

(deftest spawn-on-done-guard-fact-is-ensured
  (rf/reg-cofx :test/roll8 {:recordable? true} (fn [] 6))
  (let [m {:initial :working :guards {:g rolled-six}
           :states  {:working {:spawn {:machine-id :x/child
                                       :on-done {:target :loaded :guard :g}}}
                     :loaded  {}}}]
    (is (contains? (set (map :id (rf.machines.cofx-attach/ensure-set-for
                                   (rf.machines.cofx-attach/index-ensure-sets m)
                                   {:state :working :data {}}
                                   [:rf.machine.spawn/done [:working] {:result 1 :error? false}])))
                   :test/roll8))))
