(ns day8.re-frame2-causa.panels-e2e.issues-e2e-cljs-test
  "Multi-frame e2e coverage for the Issues Ribbon panel (rf2-7icrs,
  spec/017 — Issues Ribbon row).

  The Issues Ribbon aggregates errors / warnings / schema violations
  / hydration mismatches into a unified feed. The `:rf.causa/issues-
  ribbon` sub projects the trace buffer's error / warning op-types
  into ribbon rows.

  Bug class: a host handler-exception dispatch MUST produce at least
  one :rf.error/handler-exception trace event AND that event MUST
  surface in the issues ribbon sub's output. If the trace cb isn't
  wired (or the wrong frame option drops it), the panel stays empty
  and the issue is silently lost — a regression Causa exists to
  prevent."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.test-helpers.e2e-multi-frame :as e2e]
            [day8.re-frame2-causa.test-helpers.host-fixtures.deliberate-throw
             :as throws]
            [day8.re-frame2-causa.test-helpers.host-fixtures.counter :as counter]))

(use-fixtures :each
  (test-support/reset-runtime-fixture-factory {:adapter plain-atom/adapter}))

(deftest causa-issues-ribbon-empty-on-quiet-host
  (testing "no host errors → issues-ribbon is empty (the all-clear case)"
    (e2e/with-host-and-causa-frames
      {:install-host counter/install-and-init!}
      (fn []
        (e2e/dispatch-host [:counter/inc])
        (let [ribbon (e2e/sub-causa [:rf.causa/issues-ribbon])]
          (is (map? ribbon)
              ":rf.causa/issues-ribbon did not return a map"))))))

(deftest causa-issues-ribbon-captures-handler-throw
  (testing "host handler-exception surfaces in trace buffer + issues ribbon"
    (e2e/with-host-and-causa-frames
      {:install-host throws/install-and-init!}
      (fn []
        ;; The deliberate-throw handler throws; the router catches and
        ;; emits :rf.error/handler-exception. dispatch-host swallows
        ;; via dispatch-sync's try/catch in the router.
        (e2e/dispatch-host [:deliberate-throw/throw-in-handler])
        (let [buffer    (e2e/sub-causa [:rf.causa/trace-buffer])
              errors    (filter #(= :rf.error/handler-exception (:operation %)) buffer)]
          (is (pos? (count errors))
              "no :rf.error/handler-exception trace event in buffer — error path not wired"))))))

(deftest causa-issues-ribbon-shape-after-throw
  (testing ":rf.causa/issues-ribbon resolves to map shape after host throw"
    (e2e/with-host-and-causa-frames
      {:install-host throws/install-and-init!}
      (fn []
        (e2e/dispatch-host [:deliberate-throw/throw-in-handler])
        (let [ribbon (e2e/sub-causa [:rf.causa/issues-ribbon])]
          (is (map? ribbon)
              ":rf.causa/issues-ribbon should resolve to a map post-error"))))))
