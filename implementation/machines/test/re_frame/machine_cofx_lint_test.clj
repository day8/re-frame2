(ns re-frame.machine-cofx-lint-test
  "The dev-only consumer-attachment lints: ambient-durable (an action declaring
  an ambient cofx) and consume-undeclared (a named entry's source reading a
  registered cofx it did not declare)."
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
  "Drive the lint as the registration home does: index, then lint."
  [machine-id machine]
  (rf.machines.cofx-attach/lint-machine! machine-id (rf.machines.cofx-attach/index-ensure-sets machine)))

(defn- warns [op] (rf.machines.test-support/events-of op))

(defn- fold [cofx-id]
  {:rf.cofx/requires [cofx-id] :fn (fn [{:keys [data]}] {:data data})})

(defn- guard-reading [declared source-code]
  {:initial :idle
   :guards  {:g {:rf.cofx/requires declared :fn (fn [_] true) :source-code source-code}}
   :states  {:idle {:on {:go {:target :done :guard :g}}} :done {}}})

(deftest ambient-durable-fires-only-for-an-action-declaring-an-ambient-cofx
  (rf/reg-cofx :lint/ambient {:doc "ambient (default grade)"} (fn [] :A))
  (rf/reg-cofx :lint/recordable {:recordable? true} (fn [] :R))
  ;; let-bound, so the macro stamps no :source-code; a guard is a pure read
  ;; and a recordable fact is replay-safe to fold, so neither warns.
  (let [m {:initial :idle
           :data    {}
           :guards  {:ok? (fold :lint/ambient)}
           :actions {:fold-it (fold :lint/ambient) :fold-recordable (fold :lint/recordable)}
           :states  {:idle {:on {:go {:target :done :guard :ok? :action :fold-it}}}
                     :done {:entry :fold-recordable}}}]
    (rf/reg-machine :lint/ambient-machine m)
    (is (= [{:op-type  :warning
             :recovery :warned-and-proceeded
             :tags     {:machine-id :lint/ambient-machine
                        :slot       :actions
                        :entry-id   :fold-it
                        :rf.cofx/id :lint/ambient}}]
           (mapv #(select-keys % [:op-type :recovery :tags]) (warns AMBIENT))))))

(deftest consume-undeclared-flags-only-an-undeclared-registered-cofx-read
  (rf/reg-cofx :lint/declared   {:recordable? true} (fn [] :D))
  (rf/reg-cofx :lint/undeclared {:recordable? true} (fn [] :U))
  ;; :some.domain/plain-key is not a registered cofx, so it is not flagged
  (lint! :lint/consume-form
         (guard-reading [:lint/declared]
                        '(fn [{cofx :rf.cofx :keys [data]}]
                           [(:lint/undeclared cofx) (:some.domain/plain-key data)])))
  (is (= [{:machine-id :lint/consume-form :slot :guards :entry-id :g :rf.cofx/id :lint/undeclared}]
         (mapv :tags (warns CONSUME)))))

(deftest consume-undeclared-tolerates-unreadable-string-source
  (rf/reg-cofx :lint/undeclared5 {:recordable? true} (fn [] :U))
  ;; the safe EDN reader rejects `#=`: the source is left unscanned, never thrown on
  (lint! :lint/consume-unreadable
         (guard-reading [:lint/declared5] "(fn [] #=(str :lint/undeclared5"))
  (is (empty? (warns CONSUME))))

(deftest consume-undeclared-fires-end-to-end-through-reg-machine
  ;; the macro stamps :source-code as a pr-str STRING the scan reads back
  (rf/reg-cofx :lint/e2e-declared   {:recordable? true} (fn [] :D))
  (rf/reg-cofx :lint/e2e-undeclared {:recordable? true} (fn [] :U))
  (rf/reg-machine :lint/e2e-consume
    {:initial :idle
     :guards  {:g {:rf.cofx/requires [:lint/e2e-declared]
                   :fn (fn [{cofx :rf.cofx}] (some? (:lint/e2e-undeclared cofx)))}}
     :states  {:idle {:on {:go {:target :done :guard :g}}}
               :done {}}})
  (is (= #{:lint/e2e-undeclared} (set (map #(:rf.cofx/id (:tags %)) (warns CONSUME))))))
