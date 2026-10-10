(ns re-frame.ssr-head-resolution-no-project-test
  "`:rf.error/ssr-head-resolution-failed` is a recoverable degradation: a
  throwing `:head` fn degrades to an empty `<head>` and the request still
  answers 200 (Spec 011 §1070). Both buffering listeners skip the category,
  so the 200 holds whenever the host settles the response. The dev-bus tests
  sit in `debug-enabled?` arms because `trace/emit-error!` emits nothing
  under `-Dre-frame.debug=false`; the always-on test runs in both postures."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.error-listener :as rf.ssr.error-listener]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]
            [re-frame.trace :as rf.trace]))

(use-fixtures :each rf.ssr.test-fixture/reset-runtime)

(defn- server-frame []
  ;; The default projector maps any projected `:rf.error/*` off 200, so a
  ;; 200 means the trace was neither buffered nor projected.
  (rf.frame/make-anon-frame-record!
    {:platform :server
     :ssr      {:public-error-id :rf.ssr/default-error-projector :dev-error-detail? false}}))

(deftest dev-path-head-failure-trace-is-not-buffered-or-projected
  (when rf.interop/debug-enabled?
    (let [f (server-frame)]
      (rf.trace/emit-error! :rf.error/ssr-head-resolution-failed
                            {:frame f :exception (ex-info "head boom" {}) :recovery :no-recovery})
      (is (= 200 (:status (:response (rf.ssr/flush-response-result! f))))))))

(deftest dev-path-control-non-head-error-still-projects
  (testing "the skip is targeted, not a listener that drops everything"
    (when rf.interop/debug-enabled?
      (let [f (server-frame)]
        (rf.trace/emit-error! :rf.error/schema-validation-failure
                              {:frame f :exception (ex-info "schema boom" {}) :recovery :no-recovery})
        (is (not= 200 (:status (:response (rf.ssr/flush-response-result! f)))))))))

(deftest always-on-path-head-failure-record-is-not-buffered-or-projected
  (let [f (server-frame)]
    (rf.ssr.error-listener/error-emit-projection-listener
      {:error :rf.error/ssr-head-resolution-failed :frame f :time 0
       :exception (ex-info "head boom" {})})
    (is (= 200 (:status (:response (rf.ssr/flush-response-result! f)))))))
