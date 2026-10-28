(ns re-frame.migration.reg-event-codemod-integration-test
  "Evaluates migrated registrations against the real v2 runtime."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.migration.reg-event-codemod :as rf.migration.reg-event-codemod])
  (:import [java.io PushbackReader StringReader]))

(defn- reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf/init! rf.substrate.plain-atom/adapter)
  (test-fn))

(use-fixtures :each reset-runtime)

(defn- eval-source!
  "Evaluate every top-level form with the real re-frame.core alias."
  [s]
  (binding [*ns* (the-ns 're-frame.migration.reg-event-codemod-integration-test)]
    (with-open [r (PushbackReader. (StringReader. s))]
      (loop [last-val nil]
        (let [form (read {:eof ::eof} r)]
          (if (= ::eof form)
            last-val
            (recur (eval form))))))))

(defn- rf-error-id
  "Return the first :rf.error/id in the thrown cause chain, or nil."
  [f]
  (try
    (f)
    nil
    (catch Throwable t
      (loop [e t]
        (when e
          (or (:rf.error/id (ex-data e))
              (recur (.getCause e))))))))

(deftest rewritten-canonical-metadata-registers
  (let [src (str "(rf/reg-event-db :counter/inc\n"
                 "  {:interceptors [(rf/path :counter)]}\n"
                 "  (fn [db _] (update db :value inc)))")
        {:keys [source findings]} (rf.migration.reg-event-codemod/rewrite-string src)]
    (is (= [:rewrite nil true]
           [(:action (first findings))
            (rf-error-id #(eval-source! source))
            (some? (rf.registrar/lookup :event :counter/inc))]))))

(deftest rewritten-positional-vector-registers
  (let [src (str "(rf/reg-event-db :counter/dec\n"
                 "  [(rf/path :counter)]\n"
                 "  (fn [db _] (update db :value dec)))")
        {:keys [source findings]} (rf.migration.reg-event-codemod/rewrite-string src)]
    (is (= [:rewrite nil true]
           [(:action (first findings))
            (rf-error-id #(eval-source! source))
            (some? (rf.registrar/lookup :event :counter/dec))]))))

(deftest chain-resolved-through-a-def-registers
  (let [src (str "(def counter-chain [[:rf.interceptor/path [:counter]]])\n"
                 "(def counter-path [:rf.interceptor/path [:counter]])\n"
                 "(rf/reg-event-db :counter/inc {:interceptors counter-chain}\n"
                 "  (fn [db _] (update db :value inc)))\n"
                 "(rf/reg-event-db :counter/dec {:interceptors [counter-path]}\n"
                 "  (fn [db _] (update db :value dec)))\n")
        {:keys [source findings]} (rf.migration.reg-event-codemod/rewrite-string src)]
    (is (= [[:rewrite :rewrite] nil true true]
           [(mapv :action findings)
            (rf-error-id #(eval-source! source))
            (some? (rf.registrar/lookup :event :counter/inc))
            (some? (rf.registrar/lookup :event :counter/dec))]))))

(deftest old-preserved-inline-path-output-throws-path-removed
  (let [old (str "(rf/reg-event :counter/inc\n"
                 "  {:interceptors [(rf/path :counter)]}\n"
                 "  (fn [{:keys [db]} _] {:db (update db :value inc)}))")]
    (is (= :rf.error/path-removed (rf-error-id #(eval-source! old))))))

(deftest positional-middle-slot-throws-bad-middle-slot
  ;; Valid refs isolate rejection of the positional slot.
  (let [old (str "(rf/reg-event :counter/inc\n"
                 "  [[:rf.interceptor/path [:counter]]]\n"
                 "  (fn [{:keys [db]} _] {:db (update db :value inc)}))")]
    (is (= :rf.error/reg-event-bad-middle-slot
           (rf-error-id #(eval-source! old))))))

(deftest inline-interceptor-value-throws-inline-removed
  (let [old (str "(rf/reg-event :counter/inc\n"
                 "  {:interceptors [{:id :my/ic :before identity}]}\n"
                 "  (fn [{:keys [db]} _] {:db (update db :value inc)}))")]
    (is (= :rf.error/inline-interceptor-removed
           (rf-error-id #(eval-source! old))))))

(deftest unregistered-ref-throws-unregistered-interceptor
  (let [src (str "(rf/reg-event :counter/inc\n"
                 "  {:interceptors [:not/registered]}\n"
                 "  (fn [{:keys [db]} _] {:db db}))")]
    (is (= :rf.error/unregistered-interceptor
           (rf-error-id #(eval-source! src))))))
