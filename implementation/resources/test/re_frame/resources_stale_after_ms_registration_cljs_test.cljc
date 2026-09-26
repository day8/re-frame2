(ns re-frame.resources-stale-after-ms-registration-cljs-test
  "Registration-time validation of a resource's `:stale-after-ms` (Spec 016
  §Freshness clock contract).

  Absent means the entry never goes stale on a timer, and a non-negative
  number of milliseconds is the policy — `0` meaning stale the instant it
  loads. Every other value is refused by `reg-resource` with
  `:rf.error/resource-bad-spec`, whose ex-data names the key and the value,
  the same check and error shape `:gc-after-ms` gets. A value that got past
  registration would reach `:stale-at` arithmetic at the first load settle."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.registrar :as rf.registrar]
            [re-frame.resources.registry :as rf.resources.registry]))

(defn- spec-with
  "A minimal valid resource METADATA map, with `extra` merged in."
  [extra]
  (merge {:doc           "test resource"
          :scope         :rf.scope/global
          :params-schema [:map [:slug :string]]}
         extra))

(def ^:private request-fn
  (fn [_params _ctx] {:request {:method :get :url "/api/x"}}))

;; FN form, which `clojure.test` and `cljs.test` both accept.
(use-fixtures :each
  (fn [test-fn]
    (rf.registrar/clear-kind! :resource)
    (try
      (test-fn)
      (finally
        (rf.registrar/clear-kind! :resource)))))

(deftest a-non-negative-stale-after-ms-registers-unchanged
  (doseq [[label extra expected] [["absent"   {}                      nil]
                                  ["zero"     {:stale-after-ms 0}     0]
                                  ["positive" {:stale-after-ms 60000} 60000]]]
    (is (= :test/stale (rf.resources.registry/reg-resource
                         :test/stale (spec-with extra) request-fn))
        (str label ": registers"))
    (is (= expected (:stale-after-ms (rf.resources.registry/resource-meta :test/stale)))
        (str label ": stored as declared"))))

(deftest any-other-stale-after-ms-is-a-bad-spec
  (doseq [bad [nil -1 "60000" :never]]
    (let [thrown (try (rf.resources.registry/reg-resource
                        :test/stale-bad (spec-with {:stale-after-ms bad}) request-fn)
                      nil
                      (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e e))
          data   (ex-data thrown)]
      (is (= :rf.error/resource-bad-spec (:rf.error/id data))
          (str (pr-str bad) " is refused with :rf.error/resource-bad-spec"))
      (is (and (= :test/stale-bad (:resource-id data))
               (contains? data :stale-after-ms)
               (= bad (:stale-after-ms data)))
          (str (pr-str bad) ": the ex-data names the key and the value"))
      (is (nil? (rf.resources.registry/resource-meta :test/stale-bad))
          (str (pr-str bad) ": nothing was registered")))))
