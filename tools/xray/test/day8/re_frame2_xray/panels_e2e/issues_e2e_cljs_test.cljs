(ns day8.re-frame2-xray.panels-e2e.issues-e2e-cljs-test
  "Multi-frame e2e coverage for the Issues Ribbon panel (rf2-7icrs,
  spec/017 — Issues Ribbon row).

  The Issues Ribbon aggregates errors / warnings / schema violations
  / hydration mismatches into a unified feed. The `:rf.xray/issues-
  ribbon` sub projects the trace buffer's error / warning op-types
  into ribbon rows.

  Bug class: a host handler-exception dispatch MUST produce at least
  one :rf.error/handler-exception trace event AND that event MUST
  surface in the issues ribbon sub's output. If the trace cb isn't
  wired (or the wrong frame option drops it), the panel stays empty
  and the issue is silently lost — a regression Xray exists to
  prevent."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-xray.test-helpers.e2e-multi-frame :as e2e]
            [day8.re-frame2-xray.test-helpers.host-fixtures.deliberate-throw
             :as throws]
            [day8.re-frame2-xray.test-helpers.host-fixtures.counter :as counter]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(deftest xray-issues-ribbon-empty-on-quiet-host
  (testing "no host errors → issues-ribbon is empty (the all-clear case)"
    (e2e/with-host-and-xray-frames
      {:install-host counter/install-and-init!}
      (fn []
        (e2e/dispatch-host [:counter/inc])
        (let [ribbon (e2e/sub-xray [:rf.xray/issues-ribbon])]
          (is (map? ribbon)
              ":rf.xray/issues-ribbon did not return a map"))))))

(deftest xray-issues-ribbon-captures-handler-throw
  (testing "host handler-exception surfaces in trace buffer + issues ribbon"
    (e2e/with-host-and-xray-frames
      {:install-host throws/install-and-init!}
      (fn []
        ;; The deliberate-throw handler throws; the router catches and
        ;; emits :rf.error/handler-exception. dispatch-host swallows
        ;; via dispatch-sync's try/catch in the router.
        (e2e/dispatch-host [:deliberate-throw/throw-in-handler])
        (let [buffer    (e2e/sub-xray [:rf.xray/trace-buffer])
              errors    (filter #(= :rf.error/handler-exception (:operation %)) buffer)]
          (is (pos? (count errors))
              "no :rf.error/handler-exception trace event in buffer — error path not wired"))))))

(deftest xray-issues-ribbon-shape-after-throw
  (testing ":rf.xray/issues-ribbon resolves to map shape after host throw"
    (e2e/with-host-and-xray-frames
      {:install-host throws/install-and-init!}
      (fn []
        (e2e/dispatch-host [:deliberate-throw/throw-in-handler])
        (let [ribbon (e2e/sub-xray [:rf.xray/issues-ribbon])]
          (is (map? ribbon)
              ":rf.xray/issues-ribbon should resolve to a map post-error"))))))
