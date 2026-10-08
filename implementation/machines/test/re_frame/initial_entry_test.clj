(ns re-frame.initial-entry-test
  "A spawned actor's initial-state `:entry` fires as it is spawned, and a throw
  in the bootstrap cascade commits nothing (Spec 005 §Errors)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter})
  rf.machines.test-support/trace-capture-fixture)

(deftest spawned-initial-entry-fires-on-spawn
  (let [calls (atom [])]
    (rf/reg-fx :rf2-0z73/record (fn [_ arg] (swap! calls conj arg)))
    (rf/reg-machine :rf2-0z73/child
      {:initial :requesting
       :actions {:fire-request (fn [_] {:fx [[:rf2-0z73/record :child-entry-fired]]})}
       :states  {:requesting {:entry :fire-request}}})
    (rf/reg-machine :rf2-0z73/parent
      {:initial :idle
       :states  {:idle    {:on {:start :working}}
                 :working {:spawn {:machine-id :rf2-0z73/child}}}})
    (rf/dispatch-sync [:rf2-0z73/parent [:start]])
    (is (= [:child-entry-fired] @calls)
        "the spawned child's initial :entry fired exactly once, during the spawn")))

(deftest initial-entry-throw-halts-bootstrap-atomically
  (rf/reg-machine :rf2-dd3b/throws
    {:initial :a
     :actions {:throws (fn [_] (throw (ex-info "boom in :entry" {})))}
     :states  {:a {:entry :throws}}})
  (rf/dispatch-sync [:rf2-dd3b/throws [:noop]])
  (is (nil? (rf.machines.test-support/snapshot :rf2-dd3b/throws))
      "the bootstrap snapshot is not committed")
  (is (= [[:error :no-recovery :rf2-dd3b/throws [:rf.machine/start] true]]
         (mapv (fn [{:keys [op-type recovery tags]}]
                 [op-type recovery (:actor-id tags) (:event tags) (some? (:exception tags))])
               (rf.machines.test-support/events-of :rf.error/machine-action-exception)))
      "exactly one error, naming the actor and its synthetic creation marker"))

(deftest initial-entry-throw-in-compound-cascade
  (rf/reg-machine :rf2-dd3b/compound
    {:initial :outer
     :actions {:outer-ok  (fn [{data :data}] {:data (assoc data :outer-ran? true)})
               :inner-bad (fn [_] (throw (ex-info "inner boom" {})))}
     :states  {:outer {:entry   :outer-ok
                       :initial :inner
                       :states  {:inner {:entry :inner-bad}}}}})
  (rf/dispatch-sync [:rf2-dd3b/compound [:noop]])
  (is (nil? (rf.machines.test-support/snapshot :rf2-dd3b/compound))
      "the outer :entry's :data write does not commit when the inner :entry throws")
  (is (seq (rf.machines.test-support/events-of :rf.error/machine-action-exception))))
