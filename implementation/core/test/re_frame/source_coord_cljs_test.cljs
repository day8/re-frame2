(ns re-frame.source-coord-cljs-test
  "Per rf2-ts1a — CLJS-side smoke check for `:rf.trace/call-site` on
  `:rf.error/*` trace events. JVM-side coverage lives in
  `source_coord_test.cljc`; the macro-expansion path is identical
  across both targets (the `.cljc` macros run on the Clojure side of
  the compiler in either case), so this file primarily verifies that
  the CLJS bundle wires up the dynamic-var read end-to-end without a
  regression."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; rf2-qwm0a: listener / buffer surface lives in re-frame.trace.tooling.
            [re-frame.trace.tooling :as trace-tooling]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each (test-support/make-reset-runtime-fixture
                      {:adapter plain-atom/adapter}))

(defn- record-traces
  [body-fn]
  (let [seen (atom [])]
    (trace-tooling/register-trace-listener! ::rec (fn [ev] (swap! seen conj ev)))
    (try (body-fn)
         (finally (trace-tooling/unregister-trace-listener! ::rec)))
    @seen))

(defn- errors-of [evs op]
  (filterv #(and (= :error (:op-type %))
                 (= op     (:operation %)))
           evs))

(deftest cljs-dispatch-macro-stamps-call-site
  (testing "CLJS: dispatch-sync macro stamps :rf.trace/call-site at the top level"
    (let [evs (record-traces
               (fn []
                 (rf/dispatch-sync [:rf2-ts1a/missing])))
          [miss] (errors-of evs :rf.error/no-such-handler)]
      (is (some? miss))
      (let [cs (:rf.trace/call-site miss)]
        (is (some? cs) "call-site captured")
        (is (symbol? (:ns cs)))
        (is (integer? (:line cs)))
        (is (not (contains? (:tags miss) :rf.trace/call-site))
            ":rf.trace/call-site lives at top level, not under :tags")))))

(deftest cljs-dispatch-star-fn-omits-call-site
  (testing "CLJS: dispatch-sync* fn-form does NOT stamp"
    (let [evs (record-traces
               (fn []
                 (rf/dispatch-sync* [:rf2-ts1a/missing])))
          [miss] (errors-of evs :rf.error/no-such-handler)]
      (is (some? miss))
      (is (not (contains? miss :rf.trace/call-site))))))

(deftest cljs-subscribe-macro-stamps-call-site
  (testing "CLJS: subscribe macro stamps :rf.trace/call-site on :rf.error/no-such-sub"
    (let [evs (record-traces
               (fn []
                 (rf/subscribe [:rf2-ts1a/missing-sub])))
          [miss] (errors-of evs :rf.error/no-such-sub)]
      (is (some? miss))
      (is (some? (:rf.trace/call-site miss))))))

(deftest cljs-subscribe-star-fn-omits-call-site
  (testing "CLJS: subscribe* fn-form does NOT stamp"
    (let [evs (record-traces
               (fn []
                 (rf/subscribe* [:rf2-ts1a/missing-sub])))
          [miss] (errors-of evs :rf.error/no-such-sub)]
      (is (some? miss))
      (is (not (contains? miss :rf.trace/call-site))))))

(deftest cljs-inject-cofx-macro-stamps-call-site
  (testing "CLJS: inject-cofx macro stamps :rf.trace/call-site on :rf.error/no-such-cofx"
    (rf/reg-event-fx :rf2-ts1a/uses-missing-cofx-cljs
                     [(rf/inject-cofx :rf2-ts1a/no-such-cofx-cljs)]
                     (fn [_cofx _event] {}))
    (let [evs (record-traces
               (fn []
                 (rf/dispatch-sync [:rf2-ts1a/uses-missing-cofx-cljs])))
          [miss] (errors-of evs :rf.error/no-such-cofx)]
      (is (some? miss))
      (is (some? (:rf.trace/call-site miss))))))
