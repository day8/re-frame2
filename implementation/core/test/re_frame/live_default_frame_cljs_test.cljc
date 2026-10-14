(ns re-frame.live-default-frame-cljs-test
  "A frame made without `:images`, on both hosts: a dispatched event or an
  `:fx` entry whose id two namespaces register is reported on the always-on
  axis and does not run, while every other id keeps working (Spec 002 §Image
  resolution and composition). The JVM-only `re-frame.live-default-frame-test`
  covers the rest of the contract; this one holds each host's catch of the
  ambiguity throw.

  Each rival registration names its namespace through `:ns` metadata, which is
  the provenance a `reg-*` written in that namespace records."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(deftest an-ambiguous-event-or-fx-is-reported-and-does-not-run
  (let [seen (atom [])
        runs (atom [])]
    (rf.error-emit/register-error-listener! ::recorder #(swap! seen conj %))
    (try
      (rf/reg-event :probe/ev {:ns 'probe.a} (fn [_ _] (swap! runs conj :ev-a) {}))
      (rf/reg-event :probe/ev {:ns 'probe.b} (fn [_ _] (swap! runs conj :ev-b) {}))
      (rf/reg-fx :probe/fx {:ns 'probe.a} (fn [_ _] (swap! runs conj :fx-a)))
      (rf/reg-fx :probe/fx {:ns 'probe.b} (fn [_ _] (swap! runs conj :fx-b)))
      (rf/reg-fx :probe/ok (fn [_ _] (swap! runs conj :ok)))
      (rf/reg-event :probe/fires (fn [_ _] {:fx [[:probe/fx] [:probe/ok]]}))
      (rf/make-frame {:id :probe/frame})
      (rf/dispatch-sync [:probe/ev] {:frame :probe/frame})
      (rf/dispatch-sync [:probe/fires] {:frame :probe/frame})
      (is (= [:ok] @runs)
          "neither ambiguous id ran, and the walk continued past the ambiguous fx")
      (is (= [[:event :probe/ev] [:fx :probe/fx]]
             (mapv :registration
                   (filter #(= :rf.error/ambiguous-registration (:error %)) @seen))))
      (finally
        (rf.error-emit/unregister-error-listener! ::recorder)))))
