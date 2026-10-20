(ns re-frame.machine-cofx-lint-test
  "The dev-only consumer-attachment lints: ambient-durable (a machine declaring
  an ambient cofx) and consume-undeclared (a named entry's source reading a
  registered cofx the machine did not declare)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.cofx-attach :as rf.machines.cofx-attach]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter})
  rf.machines.test-support/trace-capture-fixture)

(def ^:private AMBIENT :rf.warning/machine-cofx-ambient-durable)
(def ^:private CONSUME :rf.warning/machine-cofx-consume-undeclared)

(defn- lint!
  "Drive the lint as the registration home does: parse, then lint."
  [machine-id machine]
  (rf.machines.cofx-attach/lint-machine! machine-id (rf.machines.cofx-attach/parse-machine-requires machine)))

(defn- warns [op] (rf.machines.test-support/events-of op))

(defn- guard-reading [declared source-code]
  {:rf.cofx/requires declared
   :initial :idle
   :guards  {:g {:fn (fn [_] true) :source-code source-code}}
   :states  {:idle {:on {:go {:target :done :guard :g}}} :done {}}})

(deftest ambient-durable-fires-only-for-an-ambient-declaration
  (rf/reg-cofx :lint/ambient {:recordable? false} (fn [] :A))
  (rf/reg-cofx :lint/recordable {:recordable? true} (fn [] :R))
  (rf/reg-machine :lint/ambient-machine
    {:rf.cofx/requires [:lint/ambient :lint/recordable]
     :initial :idle
     :data    {}
     :states  {:idle {:on {:go :done}} :done {}}})
  (is (= [{:op-type  :warning
           :recovery :warned-and-proceeded
           :tags     {:machine-id :lint/ambient-machine
                      :rf.cofx/id :lint/ambient}}]
         (mapv #(select-keys % [:op-type :recovery :tags]) (warns AMBIENT)))))

(deftest consume-undeclared-tolerates-unreadable-string-source
  (rf/reg-cofx :lint/undeclared5 {:recordable? true} (fn [] :U))
  ;; the safe EDN reader rejects `#=`: the source is left unscanned, never thrown on
  (lint! :lint/consume-unreadable
         (guard-reading [:rf/time-ms] "(fn [] #=(str :lint/undeclared5"))
  (is (empty? (warns CONSUME))))

(deftest consume-undeclared-flags-only-an-undeclared-registered-cofx-read
  ;; The macro stamps :source-code as a pr-str STRING the scan reads back. Of
  ;; the three qualified reads only the undeclared registered cofx is flagged:
  ;; :some.domain/plain-key is not a registered cofx.
  (rf/reg-cofx :lint/declared   {:recordable? true} (fn [] :D))
  (rf/reg-cofx :lint/undeclared {:recordable? true} (fn [] :U))
  (rf/reg-machine :lint/consume
    {:rf.cofx/requires [:lint/declared]
     :initial :idle
     :guards  {:g (fn [{cofx :rf.cofx data :data}]
                    [(:lint/declared cofx) (:lint/undeclared cofx) (:some.domain/plain-key data)])}
     :states  {:idle {:on {:go {:target :done :guard :g}}}
               :done {}}})
  (is (= [{:machine-id :lint/consume :slot :guards :entry-id :g :rf.cofx/id :lint/undeclared}]
         (mapv :tags (warns CONSUME)))))
