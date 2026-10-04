(ns re-frame.parallel-test
  "Per Spec 005 §Parallel regions (the Nine States pattern).

  The broadcast semantics — the initial region map, events broadcast across
  regions with undeclined regions staying put, shared `:data` flowing through
  region actions in declaration order, tag union across regions, compound
  regions and per-region `:always` — are pinned by the
  spec/conformance/fixtures/parallel-*.edn fixtures that
  `machines_conformance_test` runs, together with the SCXML parallel tests and
  the region suites.

  Covered here:
    - Print/read round-trip: parallel snapshots survive pr-str ↔
      read-string with shape intact.
    - Registration-time rejection of bad shape (`:type :parallel`
      without `:regions`, nested parallel, malformed region body).
    - The synthetic per-region machine is memoised per machine."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines :as rf.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; snapshot lookup via the shared machines test-support
;; — no hardcoded `[:rf.runtime/machines :snapshots …]` path.
(def ^:private snapshot rf.machines.test-support/snapshot)

;; ---- 1. snapshot print/read round-trip ---------------------------------

(deftest parallel-snapshot-print-read-roundtrip
  (testing "parallel-region snapshot survives pr-str ↔ read-string with shape intact"
    (let [m {:type    :parallel
             :data    {:items [:a :b]}
             :regions {:data {:initial :loaded
                              :states  {:loaded {:tags #{:data/loaded}}}}
                       :form {:initial :neutral
                              :states  {:neutral {:tags #{:form/neutral}}}}}}]
      (rf/reg-machine :par/print m)
      (rf/dispatch-sync [:par/print [:no-match]])
      (let [snap         (snapshot :par/print)
            serialised   (pr-str snap)
            deserialised (edn/read-string serialised)]
        (is (= snap deserialised)
            "round-trip pr-str → read-string yields = value")
        (is (= {:data :loaded :form :neutral} (:state deserialised))
            ":state map round-trips as a keyword-keyed map")
        (is (= #{:data/loaded :form/neutral} (:tags deserialised))
            ":tags survives the round-trip")))))

;; ---- 2. registration-time validation ----------------------------------

(deftest parallel-registration-time-validation
  (testing ":type :parallel without :regions is rejected at registration"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #":rf.error/machine-parallel-bad-shape"
          (rf.machines/make-machine-handler {:type :parallel}))
        ":type :parallel requires :regions"))

  (testing ":type :parallel with :initial / :states is rejected at registration"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #":rf.error/machine-parallel-bad-shape"
          (rf.machines/make-machine-handler {:type :parallel
                                            :initial :foo
                                            :regions {:r {:initial :s :states {:s {}}}}}))
        ":type :parallel is mutually exclusive with :initial / :states at the root"))

  (testing "region missing :initial is rejected at registration"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #":rf.error/machine-parallel-bad-shape"
          (rf.machines/make-machine-handler {:type :parallel
                                            :regions {:r {:states {:s {}}}}}))
        "each region body must declare :initial"))

  (testing "nested :type :parallel is rejected at registration"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #":rf.error/machine-parallel-nested-not-supported"
          (rf.machines/make-machine-handler
            {:type    :parallel
             :regions {:outer {:type    :parallel
                               :regions {:inner {:initial :s :states {:s {}}}}}}}))
        "a region cannot itself declare :type :parallel"))

  (testing "nested :type :parallel deeper in a region's state-tree is rejected"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #":rf.error/machine-parallel-nested-not-supported"
          (rf.machines/make-machine-handler
            {:type    :parallel
             :regions {:r {:initial :compound
                           :states  {:compound {:type    :parallel
                                                :regions {:in {:initial :s
                                                               :states  {:s {}}}}}}}}})))))

;; ---- 3. region-machine memoization ---------------------------------------

(deftest region-machine-result-is-memoised-per-machine
  (testing "region-machine returns identical-equal results across repeat calls for the same parent-machine"
    (let [m {:type    :parallel
             :data    {}
             :regions {:a {:initial :one :states {:one {}}}
                       :b {:initial :two :states {:two {}}}}}]
      (rf/reg-machine :par/cache m)
      (let [cached  (:rf/machine (rf/handler-meta {:source :store :kind :event :id :par/cache}))
            first-a (re-frame.machines.parallel/region-machine cached :a)
            again-a (re-frame.machines.parallel/region-machine cached :a)
            first-b (re-frame.machines.parallel/region-machine cached :b)]
        (is (identical? first-a again-a)
            "second region-machine call returns the cached spec object")
        (is (not (identical? first-a first-b))
            "different regions yield different objects")
        (is (= :a (:rf/region first-a)))
        (is (= :b (:rf/region first-b)))))))
