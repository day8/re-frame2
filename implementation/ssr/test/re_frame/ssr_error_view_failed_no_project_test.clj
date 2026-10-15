(ns re-frame.ssr-error-view-failed-no-project-test
  "Recoverable error-view failures leave the response status intact on both error buses."
  (:require [clojure.test :refer [deftest is use-fixtures]]
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
    (doseq [[category compare-status] [[:rf.error/ssr-ring-error-view-failed =]
                                       [:rf.error/sub-exception not=]]]
      (let [f (server-frame)]
        (rf.trace/emit-error! category {:frame f :exception (ex-info "synthetic" {})
                                        :recovery :no-recovery})
        (is (compare-status 200 (:status (:response (rf.ssr/flush-response-result! f))))
            (str category))))))

(deftest always-on-path-error-view-failed-record-is-not-buffered-or-projected
  (let [f (server-frame)]
    (rf.ssr.error-listener/error-emit-projection-listener
      {:error :rf.error/ssr-ring-error-view-failed :frame f :time 0
       :exception (ex-info "synthetic error-view failure" {})})
    (is (= 200 (:status (:response (rf.ssr/flush-response-result! f)))))))
