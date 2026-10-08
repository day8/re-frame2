(ns re-frame.source-coord-cljs-test
  "CLJS calls to `rf/dispatch-sync` and `rf/subscribe` reach the call-site
  macros through re-frame.core's self-`:require-macros`, not the value
  aliases, which do not stamp. The macros expand on the JVM side for both
  targets, so `source_coord_jvm_test.cljc` pins the stamp's shape and
  placement; this pins the CLJS wiring."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.trace.tooling :as rf.trace.tooling]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each (rf.test-support/make-reset-runtime-fixture
                      {:adapter rf.substrate.plain-atom/adapter}))

(deftest cljs-dispatch-and-subscribe-macros-stamp-call-site
  (let [seen (atom [])]
    (rf.trace.tooling/register-listener! ::rec #(swap! seen conj %))
    (try
      (rf/dispatch-sync [:rf2-ts1a/missing])
      (rf/subscribe [:rf2-ts1a/missing-sub])
      (finally (rf.trace.tooling/unregister-listener! ::rec)))
    (doseq [op [:rf.error/no-such-handler :rf.error/no-such-sub]]
      (let [cs (:rf.trace/call-site (first (filter #(= op (:operation %)) @seen)))]
        (is (and (symbol? (:ns cs)) (integer? (:line cs))) (str op))))))
