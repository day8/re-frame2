(ns re-frame.machine-schemas-grammar-test
  "The machine-level `:schemas` map grammar (EP-0029 A3, the clean-break
  successor to the retired EP-0005 `:data-schema` key).

  `:schemas` is a closed-sub-key map. `:data` is the live, wired category
  (validated at the `:where :machine-data` boundary — pinned by
  `machine-schema-test`). The remaining A3 categories (`:events` / `:output` /
  `:tags` / `:meta`) are accepted as DECLARATION-ONLY surfaces — their values
  stay abstract and carry no wired behaviour this wave (the
  `[:schemas :output]` → completion-payload binding is a separate wave).
  `[:schemas :input]` is NOT accepted (state input is not adopted). Any other
  sub-key — or a non-map `:schemas` — fails loud at registration.

  Contract under test:

   1. **Accepted categories.** A `:schemas` map carrying only members of the
      closed set (`:data` / `:events` / `:output` / `:tags` / `:meta`)
      registers cleanly and round-trips through `(machine-meta id)`.
   2. **Unknown sub-key fails loud.** A `:schemas` map carrying an unknown
      sub-key raises `:rf.error/machine-bad-schemas-key`.
   3. **`:input` rejected.** `[:schemas :input]` raises
      `:rf.error/machine-bad-schemas-key` (state input is not adopted, B1).
   4. **Non-map `:schemas` fails loud.** A non-map `:schemas` value raises
      `:rf.error/machine-bad-schemas`.
   5. **Absent `:schemas` unaffected.** A machine with no `:schemas` key
      registers cleanly."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines :as machines]
            [re-frame.machines.test-support :as mtest]
            [re-frame.substrate.plain-atom :as plain-atom]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(defn- registration-throws?
  "Register `machine` under `machine-id`; return the ExceptionInfo if it
  threw, else nil."
  [machine-id machine]
  (try (rf/reg-machine machine-id machine) nil
       (catch clojure.lang.ExceptionInfo e e)))

;; ---- (1) accepted categories register + round-trip ------------------------

(deftest accepted-schemas-categories-register
  (testing "a :schemas map of accepted categories registers cleanly and
            round-trips through machine-meta"
    (let [schemas {:data   [:map [:n :int]]
                   :events {:counter/inc [:map [:by :int]]}
                   :output [:map [:result :int]]
                   :tags   [:enum :busy :idle]
                   :meta   [:map [:rf/snapshot-version :int]]}]
      (rf/reg-machine :rf.machine-schemas/full
        {:initial :idle :schemas schemas :states {:idle {}}})
      (let [meta (machines/machine-meta :rf.machine-schemas/full)]
        (is (some? meta) "registration completed")
        (is (= schemas (:schemas meta))
            "the whole :schemas map round-trips through machine-meta")
        (is (= [:map [:n :int]] (get-in meta [:schemas :data]))
            "[:schemas :data] is the wired data-context schema home")))))

;; ---- (2) unknown sub-key fails loud ---------------------------------------

(deftest unknown-schemas-sub-key-fails-loud
  (testing "an unknown :schemas sub-key raises :rf.error/machine-bad-schemas-key"
    (let [ex (registration-throws? :rf.machine-schemas/unknown
               {:initial :idle
                :schemas {:data [:map] :bogus [:map]}
                :states  {:idle {}}})]
      (is (some? ex) "an unknown :schemas sub-key SHOULD throw at registration")
      (is (= :rf.error/machine-bad-schemas-key (:rf.error/id (ex-data ex)))
          "error category names the bad-schemas-key contract")
      (is (= :bogus (:schemas-key (ex-data ex)))
          "ex-data carries the offending sub-key"))))

;; ---- (3) :input is rejected (state input not adopted) ---------------------

(deftest input-schemas-sub-key-rejected
  (testing "[:schemas :input] raises :rf.error/machine-bad-schemas-key — state
            input (B1) is not adopted, so declaring it must fail loud"
    (let [ex (registration-throws? :rf.machine-schemas/input
               {:initial :idle
                :schemas {:input [:map]}
                :states  {:idle {}}})]
      (is (some? ex) "[:schemas :input] SHOULD throw at registration")
      (is (= :rf.error/machine-bad-schemas-key (:rf.error/id (ex-data ex))))
      (is (= :input (:schemas-key (ex-data ex)))))))

;; ---- (4) non-map :schemas fails loud --------------------------------------

(deftest non-map-schemas-fails-loud
  (testing "a non-map :schemas value raises :rf.error/machine-bad-schemas"
    (let [ex (registration-throws? :rf.machine-schemas/non-map
               {:initial :idle
                :schemas [:not :a :map]
                :states  {:idle {}}})]
      (is (some? ex) "a non-map :schemas SHOULD throw at registration")
      (is (= :rf.error/machine-bad-schemas (:rf.error/id (ex-data ex)))
          "error category names the bad-schemas contract"))))

;; ---- (5) absent :schemas is unaffected ------------------------------------

(deftest absent-schemas-registers-cleanly
  (testing "a machine with no :schemas key registers cleanly"
    (is (= :rf.machine-schemas/none
           (rf/reg-machine :rf.machine-schemas/none
             {:initial :idle :data {:n 0} :states {:idle {}}}))
        "a schema-less machine registers and returns its id")))
