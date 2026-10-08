(ns day8.re-frame2-xray.panel-registry-cljs-test
  "Pure-data tests for the internal L4-tab registry, on both the JVM and
  the node lane. The registry is a process-wide atom, so every test starts
  and ends on a cleared registry and asserts only on what it registered."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [day8.re-frame2-xray.panel-registry :as reg]))

(use-fixtures :each
  (fn [test-fn]
    (reg/reset-for-test!)
    (test-fn)
    (reg/reset-for-test!)))

(defn- tab [mode id order]
  {:id id :label (str id) :mnem (subs (name id) 0 1)
   :modes #{mode} :order order :panel (fn [] nil)})

(deftest reg-l4-tab-keyed-by-mode-and-id
  ;; The same id under two modes is two panels (Dynamic Routing vs Static
  ;; Routes), keyed apart by mode.
  (let [dyn (tab :dynamic :routes 6)
        sta (tab :static :routes 2)]
    (reg/reg-l4-tab! dyn)
    (reg/reg-l4-tab! sta)
    (is (= dyn (reg/tab-by-id :dynamic :routes)))
    (is (= sta (reg/tab-by-id :static :routes)))))

(deftest reg-l4-tab-replaces-in-place
  ;; Hot reload re-runs every install!, so a re-registration must not stack.
  (let [v2 (assoc (tab :dynamic :epoch 0) :label "Epoch v2")]
    (reg/reg-l4-tab! (tab :dynamic :epoch 0))
    (reg/reg-l4-tab! v2)
    (is (= [v2] (reg/tab-entries)))))

(deftest reg-l4-tab-rejects-malformed-entries
  (let [throws? (fn [entry]
                  (try (reg/reg-l4-tab! entry) false
                       (catch #?(:clj Throwable :cljs :default) _ true)))]
    (is (throws? {:id :x :label "X" :modes #{:dynamic :static} :panel (fn [] nil)})
        "modes must be a SINGLE-element set: the key is [mode id]")
    (is (throws? {:id :x :label "X" :modes #{:dynamic} :panel :not-callable})
        "a keyword :panel is rejected here rather than throwing when the
         shell renders [(:panel tab)]")))

(deftest tabs-for-mode-partitions-by-mode
  (reg/reg-l4-tab! (tab :dynamic :epoch 0))
  (reg/reg-l4-tab! (tab :dynamic :appdb 1))
  (reg/reg-l4-tab! (tab :static :catalogue 0))
  (is (= [:epoch :appdb] (mapv :id (reg/tabs-for-mode :dynamic))))
  (is (= [:catalogue] (mapv :id (reg/tabs-for-mode :static))))
  ;; The id set drives the select-tab event's contains? guard.
  (is (= #{:epoch :appdb} (reg/tab-ids-for-mode :dynamic))))

(deftest tabs-for-mode-nil-order-trails
  (reg/reg-l4-tab! (tab :dynamic :ordered-0 0))
  (reg/reg-l4-tab! (dissoc (tab :dynamic :unordered nil) :order))
  (reg/reg-l4-tab! (tab :dynamic :ordered-1 1))
  (is (= [:ordered-0 :ordered-1 :unordered]
         (mapv :id (reg/tabs-for-mode :dynamic)))))

(deftest default-tab-for-mode-is-the-lowest-order-tab
  (reg/reg-l4-tab! (tab :dynamic :appdb 1))
  (reg/reg-l4-tab! (tab :dynamic :epoch 0))
  (is (= :epoch (reg/default-tab-for-mode :dynamic))))
