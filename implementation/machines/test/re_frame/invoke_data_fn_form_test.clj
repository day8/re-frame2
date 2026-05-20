(ns re-frame.invoke-data-fn-form-test
  "Per rf2-h131 and Spec 005 §Declarative `:invoke` (sugar over spawn)
  §Spec-spec keys: `:data` admits a function form `(fn [snap ev] data)`
  so the spawned child's initial data can be derived from the parent's
  post-action snapshot + the triggering event.

  The four invariants under test:

   1. **Literal-map `:data` (regression).** A literal map is passed
      through verbatim — the back-compat path that pre-rf2-h131
      already worked.

   2. **fn-form `:data` is materialised.** When `:data` is a fn, the
      runtime invokes `(data snap event)` and passes the resulting
      map (NOT the fn itself) to the spawned child.

   3. **fn-form sees the post-action snapshot.** A transition's
      `:action` writes to `:data`; the fn-form sees those writes.
      Per Spec 005 line 1511.

   4. **fn-form throw routes to :rf.error/machine-action-exception.**
      Per Spec 005 §Errors line 1597 — same category as any
      user-supplied fn that throws during a machine action.

  Also covers `:invoke-all` symmetrically — each child's `:data` admits
  the same fn-form per Spec 005 §Spec-spec keys (line 1818)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.trace :as trace]))

(use-fixtures :each
  (test-support/reset-runtime-fixture-factory {:adapter plain-atom/adapter}))

(defn- snapshot
  [machine-id]
  (get-in (rf/get-frame-db :rf/default) [:rf/machines machine-id]))

(defn- frame-db []
  (rf/get-frame-db :rf/default))

;; ---- (1) literal-map :data — back-compat regression -----------------------

(deftest literal-map-data-passes-through
  (testing "literal-map `:data` arrives at the spawned child verbatim"
    (let [child  {:initial :running :data {} :states {:running {}}}
          parent {:initial :idle
                  :states
                  {:idle    {:on {:start :working}}
                   :working {:invoke {:machine-id :worker/proc
                                      :data       {:url "/api/foo" :method :get}}}}}]
      (rf/reg-machine :worker/proc child)
      (rf/reg-machine :sup/literal parent)
      (rf/dispatch-sync [:sup/literal [:start]])
      (let [child-data (:data (snapshot :worker/proc#1))]
        (is (= "/api/foo" (:url child-data))
            "the literal map's :url survived the spawn")
        (is (= :get (:method child-data))
            "the literal map's :method survived the spawn")))))

;; ---- (2) fn-form :data — materialised at spawn ----------------------------

(deftest fn-form-data-is-materialised
  (testing "fn-form `:data` is invoked; the spawned child receives the resulting map, NOT the fn"
    (let [child  {:initial :running :data {} :states {:running {}}}
          parent {:initial :idle
                  :data    {:endpoint "/api/login"}
                  :states
                  {:idle    {:on {:start :working}}
                   :working {:invoke {:machine-id :worker/proc
                                      :data       (fn [snap _]
                                                    {:url    (-> snap :data :endpoint)
                                                     :method :post})}}}}]
      (rf/reg-machine :worker/proc child)
      (rf/reg-machine :sup/fn-form parent)
      (rf/dispatch-sync [:sup/fn-form [:start]])
      (let [child-data (:data (snapshot :worker/proc#1))]
        (is (map? child-data)
            "the spawned child's :data is a literal map, not the fn")
        (is (= "/api/login" (:url child-data))
            "the fn-form derived :url from the parent's :data.:endpoint")
        (is (= :post (:method child-data))
            "the fn-form-derived :method survived the spawn")
        ;; Runtime stamps :rf/self-id etc. into :data per rf2-ijm7 — the
        ;; materialised map is the BASE the runtime then augments.
        (is (= :worker/proc#1 (:rf/self-id child-data))
            "the runtime stamped :rf/self-id over the materialised map")))))

;; ---- (3) fn-form sees post-action snapshot --------------------------------

(deftest fn-form-data-sees-post-action-snapshot
  (testing "fn-form `:data` sees `:data` writes the transition's `:action` made (post-action snapshot per Spec 005:1511)"
    (let [child  {:initial :running :data {} :states {:running {}}}
          parent {:initial :idle
                  :data    {:base-url "http://api.example.com"}
                  :actions {:assemble-endpoint
                            (fn [data _]
                              ;; The action writes :endpoint into :data;
                              ;; the :invoke :data fn must see it.
                              {:data (assoc data :endpoint
                                            (str (:base-url data) "/v1/me"))})}
                  :states
                  {:idle    {:on {:start {:target :working
                                          :action :assemble-endpoint}}}
                   :working {:invoke {:machine-id :worker/proc
                                      :data       (fn [snap _]
                                                    {:url (-> snap :data :endpoint)})}}}}]
      (rf/reg-machine :worker/proc child)
      (rf/reg-machine :sup/post-action parent)
      (rf/dispatch-sync [:sup/post-action [:start]])
      (is (= "http://api.example.com/v1/me"
             (:url (:data (snapshot :worker/proc#1))))
          "fn-form saw :data after :assemble-endpoint ran"))))

;; ---- (3b) fn-form sees the triggering event -------------------------------

(deftest fn-form-data-sees-triggering-event
  (testing "fn-form `:data` receives the inbound event vector as its second arg"
    (let [child  {:initial :running :data {} :states {:running {}}}
          parent {:initial :idle
                  :states
                  {:idle    {:on {:fetch :working}}
                   :working {:invoke {:machine-id :worker/proc
                                      :data       (fn [_ ev]
                                                    {:from-event ev})}}}}]
      (rf/reg-machine :worker/proc child)
      (rf/reg-machine :sup/event-form parent)
      (rf/dispatch-sync [:sup/event-form [:fetch :req-1]])
      (is (= [:fetch :req-1]
             (:from-event (:data (snapshot :worker/proc#1))))
          "fn-form's second arg was the triggering event"))))

;; ---- (4) fn-form throw routes to :rf.error/machine-action-exception ------

(deftest fn-form-data-throw-routes-to-machine-action-exception
  (testing "fn-form `:data` throw halts the cascade and emits :rf.error/machine-action-exception (Spec 005:1597)"
    (let [traces (atom [])
          child  {:initial :running :data {} :states {:running {}}}
          parent {:initial :idle
                  :states
                  {:idle    {:on {:start :working}}
                   :working {:invoke {:machine-id :worker/proc
                                      :data       (fn [_ _]
                                                    (throw (ex-info "boom" {:why :test})))}}}}]
      (rf/reg-machine :worker/proc child)
      (rf/reg-machine :sup/throwing parent)
      (try
        (trace/register-trace-listener! ::h131-error
                                  (fn [ev] (swap! traces conj ev)))
        (rf/dispatch-sync [:sup/throwing [:start]])
        ;; The cascade halted: no actor was spawned. Per Spec 005 §Errors,
        ;; the snapshot does NOT commit — the parent's lazy initial snapshot
        ;; is preserved (or stays absent if it was never materialised).
        (is (nil? (snapshot :worker/proc#1))
            "no spawned actor — the cascade halted before spawn-fx ran")
        (let [parent-snap (snapshot :sup/throwing)]
          (is (or (nil? parent-snap)
                  (= :idle (:state parent-snap)))
              "parent did not commit the transition (snapshot is either absent or still :idle)"))
        ;; The error trace fired with the canonical category. Per Spec 009,
        ;; error traces carry :op-type :error and the category as :operation.
        (is (some #(and (= :error (:op-type %))
                        (= :rf.error/machine-action-exception (:operation %)))
                  @traces)
            "an :rf.error/machine-action-exception trace was emitted")
        (finally (trace/unregister-trace-listener! ::h131-error))))))

;; ---- (5) :invoke-all child :data fn-form is materialised ------------------

(deftest invoke-all-child-data-fn-form-is-materialised
  (testing "each :invoke-all child's `:data` admits the same fn-form per Spec 005:1818"
    (let [child  {:initial :running :data {} :states {:running {}}}
          parent {:initial :idle
                  :data    {:base "/api"}
                  :states
                  {:idle      {:on {:fan-out :hydrating}}
                   :hydrating {:invoke-all
                               {:children
                                [{:id         :one
                                  :machine-id :hydra/leaf
                                  :data       (fn [snap _]
                                                {:url (str (-> snap :data :base) "/one")})}
                                 {:id         :two
                                  :machine-id :hydra/leaf
                                  :data       (fn [snap _]
                                                {:url (str (-> snap :data :base) "/two")})}]
                                :on-child-done   :child/done
                                :on-child-error  :child/error
                                :on-all-complete [:done]}}}}]
      (rf/reg-machine :hydra/leaf child)
      (rf/reg-machine :sup/all parent)
      (rf/dispatch-sync [:sup/all [:fan-out]])
      ;; Two children spawned, each with materialised :data.
      (is (= "/api/one"
             (:url (:data (snapshot :hydra/leaf#1))))
          "first child's fn-form derived :url from parent's :data")
      (is (= "/api/two"
             (:url (:data (snapshot :hydra/leaf#2))))
          "second child's fn-form derived :url from parent's :data"))))
