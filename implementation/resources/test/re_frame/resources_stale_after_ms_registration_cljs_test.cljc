(ns re-frame.resources-stale-after-ms-registration-cljs-test
  "Registration-time validation of a resource's `:stale-after-ms` (Spec 016
  §Freshness clock contract): absent or a non-negative number of milliseconds
  registers; anything else is refused with `:rf.error/resource-bad-spec`
  before it can reach `:stale-at` arithmetic."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.registrar :as rf.registrar]
            [re-frame.resources.registry :as rf.resources.registry]))

(defn- spec-with [extra]
  (merge {:doc           "test resource"
          :scope         :rf.scope/global
          :params-schema [:map [:slug :string]]}
         extra))

(def ^:private request-fn
  (fn [_params _ctx] {:request {:method :get :url "/api/x"}}))

(use-fixtures :each
  (fn [test-fn]
    (rf.registrar/clear-kind! :resource)
    (try
      (test-fn)
      (finally
        (rf.registrar/clear-kind! :resource)))))

(deftest a-non-negative-stale-after-ms-registers-unchanged
  (doseq [[extra expected] [[{} nil]
                            [{:stale-after-ms 0} 0]
                            [{:stale-after-ms 60000} 60000]]]
    (rf.resources.registry/reg-resource :test/stale (spec-with extra) request-fn)
    (is (= expected (:stale-after-ms (rf.resources.registry/resource-meta :test/stale)))
        (pr-str extra))))

(deftest any-other-stale-after-ms-is-a-bad-spec
  (doseq [bad [nil -1 "60000"]]
    (let [thrown (try (rf.resources.registry/reg-resource
                        :test/stale-bad (spec-with {:stale-after-ms bad}) request-fn)
                      nil
                      (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e e))]
      (is (= {:rf.error/id :rf.error/resource-bad-spec :resource-id :test/stale-bad :stale-after-ms bad}
             (select-keys (ex-data thrown) [:rf.error/id :resource-id :stale-after-ms]))
          (pr-str bad))
      (is (nil? (rf.resources.registry/resource-meta :test/stale-bad))))))
