(ns re-frame.machine-regions-placement-cljs-test
  "`reg-machine` refuses `:regions` on any node that is not `:type :parallel`,
  naming the missing `:type :parallel`: a flat or compound root with
  `:rf.error/machine-root-slot-not-supported`, before any other check walks the
  root's region bodies (a malformed body would throw a host exception there,
  one that differs per host), and a state with `:rf.error/machine-unknown-node-key`."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [clojure.string :as str]
   [re-frame.core :as rf]
   ;; Loads the machines facade so `rf/reg-machine` routes through its
   ;; late-bind hook.
   [re-frame.machines]
   [re-frame.machines.test-support :as rf.machines.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter})))

(defn- registration
  "Register `machine` under a fresh id. Returns `:registered`, the refusal's
  ex-data, or `{:host-throw <message>}` when the throw carries no
  `:rf.error/id`."
  [machine]
  (try (rf/reg-machine (keyword "regions-placement" (str (gensym))) machine) :registered
       (catch #?(:clj Throwable :cljs :default) t
         (let [data (ex-data t)]
           (if (:rf.error/id data)
             data
             {:host-throw #?(:clj (.getMessage ^Throwable t) :cljs (str t))})))))

(defn- names-type-parallel? [refusal]
  (str/includes? (str (:reason refusal)) ":type :parallel"))

(def ^:private regions-values
  "A well-formed map, a malformed region body (a host exception if walked),
  an empty map and nil."
  [{:r {:initial :x :states {:x {}}}} {:r 42} {} nil])

(deftest a-flat-or-compound-root-refuses-regions
  (doseq [r regions-values
          :let [refusal (registration {:initial :a :regions r :states {:a {}}})]]
    (is (= [:rf.error/machine-root-slot-not-supported [:regions] true]
           ((juxt :rf.error/id :offending-keys names-type-parallel?) refusal))
        (pr-str r refusal))))

(deftest a-state-that-is-not-parallel-refuses-regions
  (doseq [[position make] {:leaf         (fn [r] {:initial :s :states {:s {:regions r}}})
                           :region-state (fn [r] {:type :parallel
                                                  :regions {:q {:initial :s :states {:s {:regions r}}}}})}
          r               regions-values
          :let [refusal (registration (make r))]]
    (testing (str "the " position " state with :regions " (pr-str r))
      (is (= [:rf.error/machine-unknown-node-key :s [:regions] true]
             ((juxt :rf.error/id :state :offending-keys names-type-parallel?) refusal))
          (pr-str refusal)))))
