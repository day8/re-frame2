(ns re-frame.substrate-source-test
  "The `:dispatch-later` fx stamps `:source :fx-dispatch-later` on the deferred
  dispatch's envelope (Spec 002 §`:source`, Spec-Schemas
  §`:rf/dispatch-envelope`), so the Epoch panel labels the precise trigger
  rather than an aggregate. The `:dispatch` fx's `:fx-dispatch` stamp is pinned
  by `re-frame.cascade-envelope-propagation-test`; the machines artefact pins
  `:after-timer`, `:machine-spawn` and `:always` in its own tests.

  The stamp is an envelope property, so an always-on `:test/probe` fx reads
  `(:envelope m)` in the parent and in the deferred child, in both postures,
  and also delivers the completion signal (a trace-delivered one would never
  arrive under `-Dre-frame.debug=false`). Only the claim the trace owns, that
  `:source` is hoisted to the dispatched event's top level (Spec 009 §Core
  fields), sits in the dev arm."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas :as rf.schemas]
            [re-frame.flows :as rf.flows]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(defn reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.flows/reset-flows!)
  (rf.schemas/clear-schemas-by-frame!)
  (rf.trace.tooling/clear-listeners!)
  (rf/init! rf.substrate.plain-atom/adapter)
  (require 're-frame.routing :reload)
  ;; `init!` does not synthesise `:rf/default` and framework operations need a
  ;; carried frame (EP-0002), so register it and pin it as the scope.
  (rf/make-frame {:id :rf/default})
  (rf/with-frame :rf/default
    (test-fn)))

(use-fixtures :each reset-runtime)

(deftest dispatch-later-fx-stamps-source-fx-dispatch-later
  (let [seen      (atom [])
        envelopes (atom {})
        done      (promise)]
    (rf/register-listener! :trace ::rec (fn [ev] (swap! seen conj ev)))
    (try
      (rf/reg-fx :test/probe
        (fn [m [level]]
          (swap! envelopes assoc level (:envelope m))
          (when (= :child level) (deliver done :seen))))
      (rf/reg-event :test/parent
        (fn [_ _]
          {:fx [[:test/probe [:parent]]
                [:dispatch-later {:ms 1 :event [:test/child]}]]}))
      (rf/reg-event :test/child (fn [_ _] {:fx [[:test/probe [:child]]]}))
      (rf/dispatch-sync [:test/parent] {:source :ui})
      (is (= [:seen :ui :fx-dispatch-later]
             [(deref done 2000 :timeout)
              (:source (:parent @envelopes))
              (:source (:child @envelopes))]))
      (when rf.interop/debug-enabled?
        (is (= {[:test/parent] :ui [:test/child] :fx-dispatch-later}
               (-> (into {} (keep #(when (= :rf.event/dispatched (:operation %))
                                     [(get-in % [:tags :rf.event/v]) (:source %)]))
                         @seen)
                   (select-keys [[:test/parent] [:test/child]])))))
      (finally (rf/unregister-listener! :trace ::rec)))))
