(ns day8.re-frame2-xray.panels-e2e.deterministic-exceptions-e2e-cljs-test
  "Real handler and FX failures remain visible in Xray traces and issues."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.test-helpers.e2e-multi-frame :as e2e]
            [day8.re-frame2-xray.test-helpers.host-fixtures.deliberate-throw
             :as throws]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- has-error?
  "True iff the trace buffer contains at least one event whose
  `:operation` is the supplied `op-kw`."
  [op-kw]
  (let [buffer (e2e/sub-xray [:rf.xray/trace-buffer])]
    (boolean (some #(= op-kw (:operation %)) buffer))))

(deftest xray-issues-ribbon-surfaces-handler-throw-row
  (testing "the issues-ribbon sub composes the handler throw into ribbon rows"
    (e2e/with-host-and-xray-frames
      {:install-host throws/install-and-init!}
      (fn []
        (e2e/dispatch-host [:deliberate-throw/throw-in-handler])
        (let [ribbon (e2e/sub-xray [:rf.xray/issues-ribbon])]
          (is (map? ribbon)
              ":rf.xray/issues-ribbon should resolve to a map after a throw")
          ;; The ribbon's :rows / :issues / :feed slot name varies with
          ;; the projection pass; assert at least one mapped value
          ;; carries any error keyword in its pr-str (loose check —
          ;; tightening to a specific shape would couple this test to
          ;; the projection's internal layout).
          (let [pr (pr-str ribbon)]
            (is (clojure.string/includes? pr "handler-exception")
                "issues-ribbon's projection does not mention :rf.error/handler-exception")))))))

(deftest xray-handler-and-fx-throws-survive-in-isolation
  (testing "handler throw + fx throw produce distinct trace events under one fixture"
    (e2e/with-host-and-xray-frames
      {:install-host throws/install-and-init!}
      (fn []
        (e2e/dispatch-host [:deliberate-throw/throw-in-handler])
        (e2e/dispatch-host [:deliberate-throw/throw-in-fx])
        (is (has-error? :rf.error/handler-exception)
            "handler exception was lost after a subsequent fx throw")
        (is (has-error? :rf.error/fx-handler-exception)
            "fx exception was lost — either trace cb broke or epoch capture dropped it")))))
