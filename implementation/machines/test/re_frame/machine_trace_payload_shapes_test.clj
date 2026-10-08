(ns re-frame.machine-trace-payload-shapes-test
  "Payload shapes the Xray epoch panel reads off machine traces: the
  `:rf.machine/action-ran` `:phase`, a throwing guard's
  `:rf.machine/guard-evaluated` (which aborts the macrostep, Spec 005
  §`:rf.machine/guard-evaluated`), and `:rf.machine.timer/cancelled` with its
  `:reason`, paired to the `:scheduled` arm it closes."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- record-traces! [body-fn]
  (rf.machines.test-support/with-trace-capture seen
    (body-fn)
    @seen))

(defn- ops [evs op]
  (filterv #(= op (:operation %)) evs))

(deftest action-ran-phase-marks-always-and-after-actions
  (rf/reg-machine :ps/phases
    {:initial :a
     :actions {:hop (fn [_] nil) :tap (fn [_] nil) :timeout (fn [_] nil)}
     :states  {:a {:on {:go :b}}
               :b {:always [{:target :c :action :hop}]}
               :c {:entry :tap
                   :after {1000 {:target :d :action :timeout}}}
               :d {}}})
  (let [evs (record-traces!
              (fn []
                (rf/dispatch-sync [:ps/phases [:go]])
                (rf/dispatch-sync [:ps/phases [:rf.machine.timer/after-elapsed 1000 1 [:c]]])))]
    (is (= [[:hop :always] [:tap :entry] [:timeout :after-action]]
           (mapv (comp (juxt :action-id :phase) :tags) (ops evs :rf.machine/action-ran))))))

(deftest guard-evaluated-threw-surfaces-error-and-aborts-selection
  (rf/reg-machine :ps/guard-throws
    {:initial :idle
     :guards  {:boom (fn [_] (throw (ex-info "guard boom" {})))
               :ok   (fn [_] true)}
     :states  {:idle {:on {:go [{:guard :boom :target :A}
                                {:guard :ok   :target :B}]}}
               :A    {}
               :B    {}}})
  ;; Boot first: a throw on the macrostep that lazily boots rolls the boot back too.
  (rf/dispatch-sync [:ps/guard-throws [:rf.machine/start]])
  (let [evs (record-traces! #(rf/dispatch-sync [:ps/guard-throws [:go]]))]
    (is (= [[:boom :threw true]]
           (mapv (fn [{t :tags}] [(:guard-id t) (:outcome t) (instance? Throwable (:exception t))])
                 (ops evs :rf.machine/guard-evaluated)))
        "only the throwing guard ran: selection aborted instead of falling through to :ok")
    (is (= 1 (count (ops evs :rf.error/machine-action-exception))))
    (is (= :idle (rf.machines.test-support/machine-state :ps/guard-throws))
        "the macrostep rolled back atomically")))

(deftest cancelled-on-exit-carries-its-reason-and-mirrors-scheduled
  (rf/reg-machine :ps/mirror
    {:initial :loading
     :states  {:loading {:after {30000 :timeout}
                         :on    {:cancel :idle}}
               :idle    {}
               :timeout {}}})
  (let [evs     (record-traces!
                  (fn []
                    (rf/dispatch-sync [:ps/mirror [:rf.machine/start]])
                    (rf/dispatch-sync [:ps/mirror [:cancel]])))
        pairing (-> (ops evs :rf.machine.timer/scheduled)
                    first
                    :tags
                    (select-keys [:actor-id :state :epoch :frame]))]
    (is (= {:actor-id :ps/mirror :state :loading :epoch 1 :frame :rf/default} pairing))
    (is (= [(assoc pairing :reason :on-exit)]
           (mapv #(select-keys (:tags %) [:actor-id :state :epoch :frame :reason])
                 (ops evs :rf.machine.timer/cancelled)))
        "one cancellation, pairable with its arm by (actor, state, epoch)")))

(deftest cancelled-on-destroy-emits-reason-on-destroy
  (rf/reg-machine :ps/destroy-target
    {:initial :armed
     :states  {:armed {:after {60000 :done}}
               :done  {}}})
  (rf/reg-machine :ps/destroyer
    {:initial :ready
     :states  {:ready {:on {:fire {:action (fn [_]
                                             {:fx [[:rf.machine/destroy :ps/destroy-target]]})}}}}})
  (let [evs (record-traces!
              (fn []
                (rf/dispatch-sync [:ps/destroy-target [:rf.machine/start]])
                (rf/dispatch-sync [:ps/destroyer [:rf.machine/start]])
                (rf/dispatch-sync [:ps/destroyer [:fire]])))]
    (is (some #{:on-destroy} (map #(-> % :tags :reason) (ops evs :rf.machine.timer/cancelled))))))
