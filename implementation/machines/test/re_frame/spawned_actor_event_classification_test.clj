(ns re-frame.spawned-actor-event-classification-test
  "A spawned actor's events are classified by its machine TYPE's `reg-machine`
  opts, because the type's handler serves every instance: an event dispatched
  to an instance redacts at every trace that echoes it, exactly as the same
  event dispatched to the type's singleton does (Spec 005 §Privacy)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.privacy :as rf.privacy]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter})
  rf.machines.test-support/trace-capture-fixture)

(def ^:private pw "PW-SENTINEL-spawned-type-opts")
(def ^:private email "kid@example.test")

(def ^:private child
  {:initial   :idle
   :data      {:token "TOKEN-SENTINEL-spawned"}
   :sensitive [[:data :token]]
   :actions   {:save (fn [{:keys [data event]}]
                       {:data (assoc data :email (:email (second event)))})}
   :states    {:idle {:on {:login {:target :done :action :save}}}
               :done {}}})

(defn- register! []
  (rf/reg-machine :spawned-class/child {:sensitive [[1 :password]]} child)
  (rf/reg-machine :spawned-class/parent
    {:initial :idle
     :states  {:idle    {:on {:start :working}}
               :working {:spawn {:machine-id     :spawned-class/child
                                 :fixed-actor-id :spawned-class/kid}}}})
  (rf/reg-event :spawned-class/forward
    (fn [_ [_ actor]] {:fx [[:dispatch [actor [:login {:email email :password pw}]]]]})))

(defn- login-traces
  "Dispatch a login carrying the password sentinel to `actor` (directly, or
  forwarded through another handler's `:dispatch` fx) and return the traces it
  emits."
  [actor forward?]
  (rf.machines.test-support/reset-captured!)
  (rf/dispatch-sync (if forward?
                      [:spawned-class/forward actor]
                      [actor [:login {:email email :password pw}]]))
  (rf.machines.test-support/captured-events))

(def ^:private echoing-ops
  "Every trace operation that echoes the routed event."
  #{:rf.event/dispatched :rf.event/run-start :rf.machine/event-received
    :rf.machine/action-ran :rf.machine/transition :rf.event/frame-state-changed
    :rf.event/run-end})

(defn- assert-redacted [actor traces]
  (let [ops        (set (map :operation traces))
        transition (some #(when (= :rf.machine/transition (:operation %)) %) traces)]
    (is (every? ops echoing-ops) (str actor " emits every echoing trace"))
    (is (= [:login {:email email :password rf.privacy/redacted-sentinel}]
           (get-in transition [:tags :event]))
        (str actor ": the transition echo redacts the password and keeps the email"))
    (is (empty? (filter #(str/includes? (pr-str %) pw) traces))
        (str actor ": no trace carries the raw password"))))

(deftest spawned-instance-events-take-the-type-opts-classification
  (register!)
  (rf/dispatch-sync [:spawned-class/parent [:start]])
  (is (some? (get-in (rf.machines.test-support/snapshot :spawned-class/kid)
                     [:rf/runtime :machine-type]))
      "the kid is a spawned instance, not a singleton")
  (testing "an event dispatched to the instance"
    (assert-redacted :spawned-class/kid (login-traces :spawned-class/kid false)))
  (testing "the type's singleton redacts the same event (control)"
    (rf/dispatch-sync [:spawned-class/child [:rf.machine/start]])
    (assert-redacted :spawned-class/child (login-traces :spawned-class/child false))))

(deftest forwarded-event-to-an-instance-redacts-on-the-forwarders-traces
  (register!)
  (rf/dispatch-sync [:spawned-class/parent [:start]])
  (let [traces (login-traces :spawned-class/kid true)]
    (is (some #(= :rf.fx/handled (:operation %)) traces)
        "the forwarder's `:dispatch` fx is traced")
    (is (= :done (rf.machines.test-support/machine-state :spawned-class/kid))
        "the forwarded login reached the instance")
    (is (empty? (filter #(str/includes? (pr-str %) pw) traces))
        "neither the forwarder's fx traces nor the instance's carry the raw password")))

(deftest an-instance-event-redacts-on-the-always-on-error-record
  ;; The failure is an effect the action returns, because an action's own
  ;; throw yields a structural record that carries no `:event`.
  (let [sunk     (atom [])
        frame-id :spawned-class/observed]
    (rf/register-observability-sink! ::errors (fn [record] (swap! sunk conj record)))
    (try
      (rf/make-frame {:id frame-id :observability {:errors [{:sink ::errors}]}})
      (rf/reg-fx :spawned-class/fail (fn [_ _] (throw (ex-info "effect failed" {}))))
      (rf/reg-machine :spawned-class/failing {:sensitive [[1 :password]]}
        {:initial :idle
         :actions {:fail (fn [_] {:fx [[:spawned-class/fail nil]]})}
         :states  {:idle {:on {:login {:target :done :action :fail}}} :done {}}})
      (rf/reg-machine :spawned-class/failing-parent
        {:initial :idle
         :states  {:idle    {:on {:start :working}}
                   :working {:spawn {:machine-id     :spawned-class/failing
                                     :fixed-actor-id :spawned-class/failing-kid}}}})
      (rf/dispatch-sync [:spawned-class/failing-parent [:start]] {:frame frame-id})
      (let [record-event (fn [actor]
                           (reset! sunk [])
                           (rf/dispatch-sync [actor [:login {:email email :password pw}]]
                                             {:frame frame-id})
                           (some #(when (= :rf.error/fx-handler-exception (:rf.error/id %)) (:event %))
                                 @sunk))]
        (is (= [:spawned-class/failing-kid [:login {:email email :password rf.privacy/redacted-sentinel}]]
               (record-event :spawned-class/failing-kid))
            "the instance's :rf.observe/error record redacts the password and keeps the email")
        (rf/dispatch-sync [:spawned-class/failing [:rf.machine/start]] {:frame frame-id})
        (is (= [:spawned-class/failing [:login {:email email :password rf.privacy/redacted-sentinel}]]
               (record-event :spawned-class/failing))
            "control: the type's singleton redacts the same event on the same record"))
      (finally (rf/unregister-observability-sink! ::errors)))))
