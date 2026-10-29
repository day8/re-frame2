(ns re-frame.sub-watched-reaction-cljs-test
  "The watched-reaction seam on CLJS (`re-frame.subs/acquire-watched-reaction!`
  / `release-watched-reaction!`): a shared cached reaction carries one
  removable set of disposal callbacks, so a released watcher's callback is
  gone and the eviction runs each live one once. The JVM twin is in
  `re-frame.sub-cache-test`; this one runs the CLJS reader branches."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.subs :as rf.subs]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each (rf.test-support/make-reset-runtime-fixture
                      {:adapter rf.substrate.plain-atom/adapter}))

(deftest released-watchers-leave-only-the-live-callbacks
  (rf/reg-event ::init (fn [_ _] {:db {:a 1}}))
  (rf/reg-sub ::a (fn [db _] (:a db)))
  (rf/dispatch-sync [::init])
  (let [fired (atom [])
        held  #(swap! fired conj :held)
        other #(swap! fired conj :other)]
    (rf.subs/acquire-watched-reaction! :rf/default [::a] held)
    (rf.subs/acquire-watched-reaction! :rf/default [::a] other)
    (dotimes [i 50]
      (let [f #(swap! fired conj i)
            r (rf.subs/acquire-watched-reaction! :rf/default [::a] f)]
        (rf.subs/release-watched-reaction! :rf/default [::a] r f)))
    (rf/clear-sub-cache! :rf/default)
    (is (= [:held :other] @fired))))

(deftest a-throwing-callback-does-not-stop-the-others-and-is-rethrown
  (rf/reg-event ::init (fn [_ _] {:db {:a 1}}))
  (rf/reg-sub ::a (fn [db _] (:a db)))
  (rf/dispatch-sync [::init])
  (let [fired (atom [])
        boom  (ex-info "boom" {})
        r     (rf.subs/acquire-watched-reaction! :rf/default [::a] #(throw boom))]
    (rf.subs/acquire-watched-reaction! :rf/default [::a] #(swap! fired conj :after))
    (is (identical? boom (try (rf.interop/dispose! r) nil (catch :default e e)))
        "the first failure is rethrown")
    (is (= [:after] @fired) "the later callback still ran")))
