(ns re-frame.adapter.helix-write-after-destroy-cljs-test
  "Helix-tier integration coverage of the `:rf.warning/write-after-destroy`
  nil-container guard at `substrate-adapter/replace-container!`
  (rf2-ft2b reproducer).

  The guard itself lives in `re-frame.substrate.adapter` and is
  unit-tested at the JVM tier in
  `re-frame.frame-lifecycle-test/replace-container-no-ops-on-nil-container`
  / `drain-after-destroy-does-not-npe`. That coverage uses the
  plain-atom adapter (JVM-side). This file pins the same contract
  through the Helix-installed adapter — the substrate-agnostic guard
  must fire identically regardless of which adapter is installed.

  Parity sibling: `uix_write_after_destroy_cljs_test.cljs` (rf2-4tzyq).
  Per rf2-k2fs0.

  ns ends in `-cljs-test` so shadow-cljs `:node-test` picks it up."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; rf2-qwm0a: listener / buffer surface lives in re-frame.trace.tooling.
            [re-frame.trace.tooling :as trace-tooling]
            [re-frame.frame :as frame]
            [re-frame.adapter.helix :as helix-adapter]
            [re-frame.substrate.adapter :as substrate-adapter]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter helix-adapter/adapter}))

(deftest replace-container-no-ops-on-nil-container-helix
  (testing "rf2-k2fs0: replace-container! with nil container under the Helix
            adapter is a no-op + :rf.warning/write-after-destroy. The
            guard is substrate-agnostic (lives in
            substrate-adapter/replace-container!), so the trace fires
            with the Helix adapter installed exactly the same way it
            does under plain-atom (the JVM-tier reproducer in
            frame-lifecycle-test)."
    (let [recorded (atom [])]
      (trace-tooling/register-trace-listener! ::rec (fn [ev] (swap! recorded conj ev)))
      (is (nil? (substrate-adapter/replace-container! nil {:any :value}))
          "nil container is a documented no-op, not an exception")
      (let [warns (filterv (fn [ev]
                             (and (= :warning (:op-type ev))
                                  (= :rf.warning/write-after-destroy
                                     (:operation ev))))
                           @recorded)]
        (is (= 1 (count warns))
            "exactly one :rf.warning/write-after-destroy trace fired"))
      (trace-tooling/unregister-trace-listener! ::rec))))

(deftest write-after-destroy-emits-warning-helix
  (testing "rf2-k2fs0: a write through a destroyed frame's nil container
            under the Helix adapter emits :rf.warning/write-after-destroy.
            Mirrors `replace-container-after-frame-destroy-no-ops`
            (frame-lifecycle-test) but with the Helix adapter installed —
            the contract is substrate-agnostic."
    (let [recorded (atom [])]
      (trace-tooling/register-trace-listener! ::rec (fn [ev] (swap! recorded conj ev)))
      (rf/reg-frame :helix-rf2-k2fs0/race-frame
                    {:doc "rf2-k2fs0 Helix-side reproducer frame"})
      ;; Tear the frame down; subsequent frame/get-frame-db returns nil.
      (frame/destroy-frame! :helix-rf2-k2fs0/race-frame)
      (let [container (frame/get-frame-db :helix-rf2-k2fs0/race-frame)]
        (is (nil? container)
            "get-frame-db on a destroyed frame returns nil — the precondition for the rf2-ft2b NPE")
        ;; The shape of the call from router.cljc's :db commit path. Pre-fix:
        ;; NPE. Post-fix: no-op + warning trace.
        (is (nil? (substrate-adapter/replace-container! container {:would :have :npe'd true}))
            "writing through the nil container is a documented no-op"))
      (let [warns (filterv (fn [ev]
                             (and (= :warning (:op-type ev))
                                  (= :rf.warning/write-after-destroy
                                     (:operation ev))))
                           @recorded)]
        (is (pos? (count warns))
            ":rf.warning/write-after-destroy fired for the post-destroy write"))
      (trace-tooling/unregister-trace-listener! ::rec))))
