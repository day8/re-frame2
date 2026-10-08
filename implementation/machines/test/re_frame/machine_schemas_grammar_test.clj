(ns re-frame.machine-schemas-grammar-test
  "The machine-level `:schemas` map grammar.

  `:schemas` is a closed-sub-key map. `:data` is wired
  (validated at the `:where :machine-data` boundary — pinned by
  `machine-schema-test`), and `:output` validates completion payloads.
  `:events`, `:tags`, and `:meta` are declaration-only surfaces.
  `[:schemas :input]` is NOT accepted (state input is not adopted).
  Any other sub-key, `:input` included, fails with
  `:rf.error/machine-bad-schemas-key`; a non-map `:schemas` with
  `:rf.error/machine-bad-schemas`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]  ;; loaded for its late-bind hooks (`rf/reg-machine`)
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- registration-throws?
  "Register `machine` under `machine-id`; return the ExceptionInfo if it
  threw, else nil."
  [machine-id machine]
  (try (rf/reg-machine machine-id machine) nil
       (catch clojure.lang.ExceptionInfo e e)))

;; ---- (1) accepted categories register + round-trip ------------------------

(deftest accepted-schemas-categories-register
  (testing "a :schemas map of accepted categories registers and round-trips
            through the `:rf/machine` projection"
    (let [schemas {:data   [:map [:n :int]]
                   :events {:counter/inc [:map [:by :int]]}
                   :output [:map [:result :int]]
                   :tags   [:enum :busy :idle]
                   :meta   [:map [:rf/snapshot-version :int]]}]
      (rf/reg-machine :rf.machine-schemas/full
        {:initial :idle :schemas schemas :states {:idle {}}})
      (is (= schemas (get-in (rf/handler-meta {:source :store :kind :event :id :rf.machine-schemas/full})
                             [:rf/machine :schemas]))))))

;; ---- (2) unknown sub-key fails loud ---------------------------------------

(deftest unknown-schemas-sub-key-fails-loud
  (is (= {:rf.error/id :rf.error/machine-bad-schemas-key :schemas-key :bogus}
         (select-keys (ex-data (registration-throws? :rf.machine-schemas/unknown
                                 {:initial :idle
                                  :schemas {:data [:map] :bogus [:map]}
                                  :states  {:idle {}}}))
                      [:rf.error/id :schemas-key]))))

;; ---- (3) :input is rejected (state input not adopted) ---------------------

;; ---- (4) non-map :schemas fails loud --------------------------------------

(deftest non-map-schemas-fails-loud
  (is (= :rf.error/machine-bad-schemas
         (:rf.error/id (ex-data (registration-throws? :rf.machine-schemas/non-map
                                  {:initial :idle
                                   :schemas [:not :a :map]
                                   :states  {:idle {}}}))))))

;; ---- (5) absent :schemas registers cleanly --------------------------------
