(ns re-frame.ssr-error-view-failed-no-project-test
  "`:rf.error/ssr-ring-error-view-failed` is a recoverable degradation: when a
  caller's `:error-view` throws, the host falls back to the locked default
  template and the status already projected for the original failure stands.
  Both buffering listeners skip the category, so it is never buffered to
  re-project later — a custom projector's 4xx cannot be flipped to a 5xx by a
  post-error re-flush. The dev-bus tests sit in `debug-enabled?` arms because
  `trace/emit-error!` emits nothing under `-Dre-frame.debug=false`; the
  always-on test runs in both postures."
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

(deftest dev-path-error-view-failed-trace-is-not-buffered-or-projected
  (when rf.interop/debug-enabled?
    (let [f (server-frame)]
      (rf.trace/emit-error! :rf.error/ssr-ring-error-view-failed
                            {:frame     f
                             :exception "synthetic error-view failure"
                             :ex-class  "clojure.lang.ExceptionInfo"
                             :recovery  :fell-back-to-default-error-template})
      (is (= 200 (:status (:response (rf.ssr/flush-response-result! f))))))))

(deftest dev-path-genuine-drain-time-error-still-projects-non-200
  (testing "the skip is targeted, not a listener that drops everything"
    (when rf.interop/debug-enabled?
      (let [f (server-frame)]
        (rf.trace/emit-error! :rf.error/sub-exception
                              {:frame f :exception (ex-info "sub boom" {}) :recovery :no-recovery})
        (is (not= 200 (:status (:response (rf.ssr/flush-response-result! f)))))))))

(deftest always-on-path-error-view-failed-record-is-not-buffered-or-projected
  (let [f (server-frame)]
    (rf.ssr.error-listener/error-emit-projection-listener
      {:error :rf.error/ssr-ring-error-view-failed :frame f :time 0
       :exception (ex-info "synthetic error-view failure" {})})
    (is (= 200 (:status (:response (rf.ssr/flush-response-result! f)))))))
