(ns re-frame.resources-clear-artefact-missing-cljs-test
  "`(rf/clear kind id)` for the resources trio routes through the
  optional-capability wrappers in `re-frame.core-resources`, so without the
  resources artefact it raises `:rf.error/resources-artefact-missing` rather
  than a silent no-op. The artefact is on the classpath here, so its absence
  is simulated by setting the hook to nil and restoring it in `finally`."
  (:require
   #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.late-bind :as rf.late-bind]
   [re-frame.resources]
   [re-frame.test-support :as rf.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter})))

(defn- clear-raised
  "Call `(rf/clear kind id)` with `hook-key` unset; return the thrown ex-data."
  [hook-key kind id]
  (let [original (rf.late-bind/get-fn hook-key)]
    (try
      (rf.late-bind/set-fn! hook-key nil)
      (rf/clear kind id)
      nil
      (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
        (ex-data e))
      (finally
        (rf.late-bind/set-fn! hook-key original)))))

(deftest clear-resource-raises-when-resources-artefact-missing
  ;; Each kind reaches its own hook, so each is checked.
  (doseq [[hook kind id] [[:resources/clear-resource :resource :probe/feed]
                          [:resources/clear-mutation :mutation :probe/save]
                          [:resources/clear-resource-scope :resource-scope :probe/tenant]]]
    (is (= :rf.error/resources-artefact-missing (:rf.error/id (clear-raised hook kind id)))
        (str kind))))
