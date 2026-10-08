(ns re-frame.ssr-render-failed-test
  "`project-render-exception!` (Spec 011 §View-time exceptions). The
  projected public-error is always-on; the `:rf.error/ssr-render-failed`
  trace rides `trace/emit-error!`, which emits nothing under
  `-Dre-frame.debug=false`, so its assertion sits in a `debug-enabled?` arm."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]))

(use-fixtures :each rf.ssr.test-fixture/reset-runtime)

(deftest project-render-exception-emits-ssr-render-failed-trace
  (let [traces (atom [])
        f      (rf.frame/make-anon-frame-record!
                 {:platform :server
                  :ssr      {:public-error-id   :rf.ssr/default-error-projector
                             :dev-error-detail? false}})
        t      (ex-info "synthetic render-time failure" {})]
    (rf/register-listener! :trace ::srf
                           (fn [ev]
                             (when (= :rf.error/ssr-render-failed (:operation ev))
                               (swap! traces conj ev))))
    (try
      (is (= 500 (:status (rf.ssr/project-render-exception! f t))))
      (when rf.interop/debug-enabled?
        (is (= [{:op-type  :error
                 :recovery :projected-to-public-error
                 :tags     {:frame             f
                            :exception         t
                            :exception-message "synthetic render-time failure"
                            :ex-class          "clojure.lang.ExceptionInfo"}}]
               (map #(-> (select-keys % [:op-type :recovery])
                         (assoc :tags (select-keys (:tags %) [:frame :exception
                                                              :exception-message
                                                              :ex-class])))
                    @traces))
            "exactly one trace, with the catalogued tags (Spec 009 §Error event catalogue)"))
      (finally
        (rf/unregister-listener! :trace ::srf)))))

(deftest project-render-exception-noop-for-non-server-frame
  (is (nil? (rf.ssr/project-render-exception! (rf.frame/make-anon-frame-record! {})
                                              (ex-info "should not fire" {})))))

(deftest project-render-exception-rethrows-under-on-view-exception-throw
  (let [f (rf.frame/make-anon-frame-record!
            {:platform :server
             :ssr      {:public-error-id   :rf.ssr/default-error-projector
                        :on-view-exception :throw}})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"eager dev failure"
                          (rf.ssr/project-render-exception! f (ex-info "eager dev failure" {}))))))
