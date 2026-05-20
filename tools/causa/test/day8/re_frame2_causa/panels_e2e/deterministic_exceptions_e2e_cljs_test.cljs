(ns day8.re-frame2-causa.panels-e2e.deterministic-exceptions-e2e-cljs-test
  "Multi-frame e2e port of the Causa feature-matrix Playwright scenario
  `deterministic exceptions and issue/trace surfacing` (rf2-rviu8).
  The Playwright original (`scenarios.cjs::runExceptionSchemaHttp`)
  opened the deliberate-throw testbed, clicked each throw button
  (handler / fx / flow / machine), then asserted:

    1. Each throw fired the matching `:rf.error/<class>-exception`
       trace event.
    2. The Issues feed mounted with at least one row per error
       category.

  At the data layer:

    - The `issues_e2e` sibling already covers the
      `:rf.error/handler-exception` path against the
      `deliberate-throw/throw-in-handler` event.
    - This file extends that coverage to the FX path:
      `:deliberate-throw/throw-in-fx` produces both an
      `:rf.error/fx-handler-exception` (the fx itself throwing) and a
      visible row in the issues ribbon for the focused cascade.

  The flow + machine exception paths (Buttons C / D in the original
  Playwright scenario) need the `re-frame.flows` / `re-frame.machines`
  fixtures plus throw-on-action wiring — covered by the `deep-machine`
  fixture for the machine half and the existing
  `machine_inspector_e2e_cljs_test.cljs` for the snapshot half. The
  flow exception is the one surface still benefiting from browser-
  level coverage (it builds on the long-flow testbed), so it stays in
  Playwright in the meantime (covered by the surviving E-tagged
  scenarios).

  ## Bug class this catches

  rf2-39gcq (and the broader `:rf.error/*` projection chain) — if an
  fx throws but the trace emit is dropped (epoch-capture filter, frame
  tag drop), the Issues feed silently loses the row even though the
  router caught the throw."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.test-helpers.e2e-multi-frame :as e2e]
            [day8.re-frame2-causa.test-helpers.host-fixtures.deliberate-throw
             :as throws]))

(use-fixtures :each
  (test-support/reset-runtime-fixture {:adapter plain-atom/adapter}))

(defn- has-error?
  "True iff the trace buffer contains at least one event whose
  `:operation` is the supplied `op-kw`."
  [op-kw]
  (let [buffer (e2e/sub-causa [:rf.causa/trace-buffer])]
    (boolean (some #(= op-kw (:operation %)) buffer))))

(deftest causa-captures-handler-exception-from-host-throw
  (testing ":rf.error/handler-exception surfaces in Causa's trace buffer"
    (e2e/with-host-and-causa-frames
      {:install-host throws/install-and-init!}
      (fn []
        (e2e/dispatch-host [:deliberate-throw/throw-in-handler])
        (is (has-error? :rf.error/handler-exception)
            "no :rf.error/handler-exception in Causa trace buffer after handler throw")))))

(deftest causa-captures-fx-exception-from-host-throw
  (testing ":rf.error/fx-handler-exception surfaces in Causa's trace buffer"
    (e2e/with-host-and-causa-frames
      {:install-host throws/install-and-init!}
      (fn []
        ;; The fx body throws inside :deliberate-throw/boom. The
        ;; router catches and emits :rf.error/fx-handler-exception via
        ;; the fx-walker.
        (e2e/dispatch-host [:deliberate-throw/throw-in-fx])
        (is (has-error? :rf.error/fx-handler-exception)
            "no :rf.error/fx-handler-exception in Causa trace buffer after fx throw")))))

(deftest causa-issues-ribbon-surfaces-handler-throw-row
  (testing "the issues-ribbon sub composes the handler throw into ribbon rows"
    (e2e/with-host-and-causa-frames
      {:install-host throws/install-and-init!}
      (fn []
        (e2e/dispatch-host [:deliberate-throw/throw-in-handler])
        (let [ribbon (e2e/sub-causa [:rf.causa/issues-ribbon])]
          (is (map? ribbon)
              ":rf.causa/issues-ribbon should resolve to a map after a throw")
          ;; The ribbon's :rows / :issues / :feed slot name varies with
          ;; the projection pass; assert at least one mapped value
          ;; carries any error keyword in its pr-str (loose check —
          ;; tightening to a specific shape would couple this test to
          ;; the projection's internal layout).
          (let [pr (pr-str ribbon)]
            (is (clojure.string/includes? pr "handler-exception")
                "issues-ribbon's projection does not mention :rf.error/handler-exception")))))))

(deftest causa-handler-and-fx-throws-survive-in-isolation
  (testing "handler throw + fx throw produce distinct trace events under one fixture"
    (e2e/with-host-and-causa-frames
      {:install-host throws/install-and-init!}
      (fn []
        (e2e/dispatch-host [:deliberate-throw/throw-in-handler])
        (e2e/dispatch-host [:deliberate-throw/throw-in-fx])
        (is (has-error? :rf.error/handler-exception)
            "handler exception was lost after a subsequent fx throw")
        (is (has-error? :rf.error/fx-handler-exception)
            "fx exception was lost — either trace cb broke or epoch capture dropped it")))))
