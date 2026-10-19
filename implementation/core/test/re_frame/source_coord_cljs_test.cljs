(ns re-frame.source-coord-cljs-test
  "A CLJS call to `rf/dispatch-sync` reaches the call-site macro through
  re-frame.core's self-`:require-macros`, not the value alias, which does not
  stamp. `rf/subscribe` is a plain fn on both hosts, so a call-position read
  stamps nothing. The macros expand on the JVM side for both targets, so
  `source_coord_jvm_test.cljc` pins the stamp's shape and placement; this pins
  the CLJS wiring."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.trace.tooling :as rf.trace.tooling]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each (rf.test-support/make-reset-runtime-fixture
                      {:adapter rf.substrate.plain-atom/adapter}))

(deftest cljs-dispatch-sync-stamps-call-site-and-subscribe-does-not
  (let [seen (atom [])]
    (rf.trace.tooling/register-listener! ::rec #(swap! seen conj %))
    (try
      (rf/dispatch-sync [:rf2-ts1a/missing])
      (rf/subscribe [:rf2-ts1a/missing-sub])
      (finally (rf.trace.tooling/unregister-listener! ::rec)))
    (let [first-of (fn [op] (first (filter #(= op (:operation %)) @seen)))
          cs       (:rf.trace/call-site (first-of :rf.error/no-such-handler))
          sub-miss (first-of :rf.error/no-such-sub)]
      (is (and (symbol? (:ns cs)) (integer? (:line cs))) "dispatch-sync stamps")
      (is (some? sub-miss))
      (is (not (contains? sub-miss :rf.trace/call-site)) "subscribe is a plain fn"))))
