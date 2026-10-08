(ns re-frame.spawn-all-init-incarnation-fence-cljs-test
  "`spawn-all-init-fx` fences its live-join and reject-sentinel writes to the
  exact frame incarnation: a preflight callback that destroys owner frame A and
  publishes same-id B leaves B untouched. The fx is driven directly under A's
  bound event owner, and the destroyer runs on the callback's own stack."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.error-emit :as rf.error-emit]
   [re-frame.frame :as rf.frame]
   [re-frame.late-bind :as rf.late-bind]
   [re-frame.machines]
   [re-frame.machines.lifecycle-fx.spawn :as rf.machines.lifecycle-fx.spawn]
   [re-frame.machines.paths :as rf.machines.paths]
   [re-frame.machines.spawn-order :as rf.machines.spawn-order]
   [re-frame.machines.test-support :as rf.machines.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

;; The always-on error-listener registry is a defonce atom: clear it per test.
(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    {:adapter #?(:clj  rf.substrate.plain-atom/adapter
                 :cljs rf.adapter.reagent/adapter)
     :init-fn (fn [] (rf.error-emit/clear-error-listeners!))})
  rf.machines.test-support/trace-capture-fixture)

;; `run-init` installs the `:schemas/validate-with-registered-fn` hook itself,
;; keyed on this marker, so the strict child's spawn-time gate runs the destroyer.
(def ^:private child-schema ::child-schema)

(def ^:private strict-spawn
  {:child-id :c :machine-id :fence/strict :spawned-id :fence/strict#1})

(def ^:private ok+missing-spawns
  ;; :fence/missing is never registered.
  [{:child-id :ok      :machine-id :fence/ok      :spawned-id :fence/ok#1}
   {:child-id :missing :machine-id :fence/missing :spawned-id :fence/missing#1}])

(defn- reg-children! []
  (rf/reg-machine :fence/strict {:initial :running :data {} :schemas {:data child-schema}
                                 :states  {:running {}}})
  (rf/reg-machine :fence/ok {:initial :running :data {} :states {:running {}}}))

(defn- init-args
  "The `[:rf.machine/spawn-all-init args]` payload the transition reducer builds
  for `:par/a`'s invoke `[:forking]` over `children`."
  [children]
  {:rf/parent-id :par/a
   :rf/invoke-id [:forking]
   :join-state   {:children  (into {} (map (juxt :child-id :spawned-id)) children)
                  :done      #{}
                  :failed    #{}
                  :resolved? false
                  :spec      {:join :all :on-all-complete [:all/done]}
                  :invoke-id [:forking]}
   :child-args   (mapv (fn [{:keys [machine-id spawned-id child-id]}]
                         {:machine-id            machine-id
                          :id-prefix             machine-id
                          :rf/spawned-id         spawned-id
                          :rf/spawn-all-child-id child-id})
                       children)})

(defn- run-init
  "Make frame A and call `spawn-all-init-fx` directly under A's event owner.
  `trigger` is `{:kind :validator :verdict <:true|:false|:throw>}` (the schema
  hook) or `{:kind :reject-listener}` (an `:errors` listener on the
  unregistered-type record); when `destroy?`, it first destroys A and publishes
  same-id B. Returns what the frame id holds afterwards."
  [frame-a children {:keys [trigger destroy?]}]
  (rf.machines.spawn-order/reset-all!)
  (rf.machines.test-support/reset-captured!)
  (rf/make-frame {:id frame-a})
  (let [token-a       (rf.frame/frame-incarnation-token frame-a)
        fired?        (atom false)
        b-birth       (atom nil)
        orig-validate (rf.late-bind/get-fn :schemas/validate-with-registered-fn)
        destroy+B!    (fn []
                        (when (and destroy? (compare-and-set! fired? false true))
                          (rf.frame/destroy-frame! frame-a)
                          (rf/make-frame {:id frame-a})
                          (reset! b-birth (rf.machines.test-support/runtime-db frame-a))))]
    (rf.error-emit/register-error-listener! ::destroyer
      (fn [r]
        (when (and (= :reject-listener (:kind trigger))
                   (= :rf.error/machine-spawn-unregistered-type (:error r)))
          (destroy+B!))))
    (try
      (when (= :validator (:kind trigger))
        (rf.late-bind/set-fn! :schemas/validate-with-registered-fn
          (fn [schema _data]
            (when (= schema child-schema)
              (destroy+B!)
              (case (:verdict trigger)
                :throw (throw (ex-info "validator boom" {}))
                :false false
                true)))))
      (rf.frame/call-with-event-owner-token frame-a token-a
        #(rf.machines.lifecycle-fx.spawn/spawn-all-init-fx {:frame frame-a} (init-args children)))
      {:b-birth   @b-birth
       :b-runtime (rf.machines.test-support/runtime-db frame-a)
       :join-slot (get-in (rf.machines.test-support/runtime-db frame-a)
                          (rf.machines.paths/spawned-path :par/a [:forking]))
       :started   (rf.machines.test-support/events-of :rf.machine.spawn-all/started)}
      (finally
        (rf.error-emit/unregister-error-listener! ::destroyer)
        (rf.late-bind/set-fn! :schemas/validate-with-registered-fn orig-validate)))))

(deftest owner-loss-in-a-preflight-callback-leaves-the-successor-untouched
  (reg-children!)
  (doseq [[frame-a trigger children]
          [[:fence/conform {:kind :validator :verdict :true}  [strict-spawn]]
           [:fence/fail    {:kind :validator :verdict :false} [strict-spawn]]
           [:fence/throw   {:kind :validator :verdict :throw} [strict-spawn]]
           [:fence/reject  {:kind :reject-listener}           ok+missing-spawns]]]
    (testing (str frame-a)
      (let [{:keys [b-birth b-runtime started]}
            (run-init frame-a children {:trigger trigger :destroy? true})]
        ;; b-birth stays nil unless the destroyer ran, while A's own write lands.
        (is (= b-birth b-runtime) "B's runtime-db is still its birth value")
        (is (empty? started) "no :rf.machine.spawn-all/started fired")))))

(deftest live-owner-accept-seeds-a-live-join
  (reg-children!)
  (let [{:keys [join-slot started]}
        (run-init :fence/live-accept [strict-spawn] {:trigger {:kind :validator :verdict :true}})]
    (is (= {:c :fence/strict#1} (:children join-slot)))
    (is (= 1 (count started)))))

(deftest live-owner-reject-seeds-the-sentinel
  (reg-children!)
  (let [{:keys [join-slot started]} (run-init :fence/live-reject ok+missing-spawns {})]
    (is (= {:rf/spawn-all-rejected? true} join-slot))
    (is (empty? started) "a rejected invoke fires no started trace")))
