(ns re-frame.epoch-closed-egress-opts-test
  "rf2-kuky.6 — the epoch `projected-record` door reads ONE vocabulary, and
  its opts map is CLOSED.

  ## The defect this pins

  `projected-record` read the two SHARED inclusion axes only in their
  UNQUALIFIED spelling. Every other egress door — the walker,
  `project-egress`, the `:rf/project-egress-opts` schema, Conventions
  §`:rf.size/*` — spells them `:rf.size/*`. So a caller who had learnt the
  vocabulary from any of those and passed `:rf.size/include-sensitive? true`
  here had it SILENTLY DROPPED, and the record egressed under the
  fail-closed floor while the call read as an opt-in.

  That is a fail-closed direction (over-redaction, not a leak), which is
  precisely why it survived: nothing looked wrong at the sink. Its mirror
  image is what makes it worth a gate — pair-MCP's eval builder chose the
  bare spelling BECAUSE it was the only one that worked here, so the two
  spellings were each load-bearing on a different surface.

  ## What is deliberately NOT renamed

  The three EPOCH-LOCAL knobs (`:include-fx-args?`, `:include-runtime-db?`,
  `:include-event-args?`) stay BARE. They are not app-db axes at all — fx
  args, the runtime-db partition and event args are different keyspaces —
  and whether they graduate to a qualified spelling belongs to the
  `:rf.size/*` → `:rf.egress/*` sweep, not here. `epoch-local-knobs-stay-
  bare` below pins that so a later sweep has to change a test on purpose."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.epoch :as rf.epoch]
            [re-frame.epoch.tool-pair :as rf.epoch.tool-pair]))

(defn- bad-opts-ex-data
  "Run `f`, expecting a `:rf.error/bad-egress-opts` throw; return its
  `ex-data`, or `nil` when nothing was thrown. Re-throws any OTHER
  `ExceptionInfo` so a wrong-error failure reports the wrong error rather
  than an unhelpful nil."
  [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo e
      (let [d (ex-data e)]
        (if (= :rf.error/bad-egress-opts (:rf.error/id d))
          d
          (throw e))))))

(def ^:private a-record
  "A minimal epoch-shaped record. Its content does not matter here: the
  guard is graded BEFORE the record is inspected, which is itself part of
  the contract — a malformed opts map is malformed against any input."
  {:frame     :app/main
   :epoch-id  1
   :db-before {:auth {:password "s3cret"}}
   :db-after  {:auth {:password "s3cret"}}})

(deftest projected-record-rejects-the-unqualified-shared-axes
  (testing "rf2-kuky.6 — the bare spellings this door used to be the ONLY
            reader of are now a loud error, so the two vocabularies cannot
            both look right at once."
    (doseq [k [:include-sensitive? :include-large?]]
      (let [d (bad-opts-ex-data #(rf.epoch/projected-record a-record {k true}))]
        (is (some? d) (str k " throws :rf.error/bad-egress-opts"))
        (is (= [k] (:unknown-keys d)) (str k " is NAMED in ex-data"))
        (is (= 'rf/projected-record (:where d))
            ":where names the door the caller called")
        (is (contains? (set (:accepted d)) :rf.size/include-sensitive?)
            "and the accepted set teaches the spelling that works")))))

(deftest projected-record-rejects-an-arbitrary-unknown-key
  (testing "rf2-kuky.6 — a closed SET, not a denylist of the spellings that
            bit us."
    (let [d (bad-opts-ex-data
              #(rf.epoch/projected-record a-record {:totally-made-up true}))]
      (is (some? d))
      (is (= [:totally-made-up] (:unknown-keys d)))))
  (testing "and keys this door does not own are rejected even though a
            SIBLING door accepts them — `:frame` / `:path` shape the walk,
            which the epoch door derives from the record itself."
    (doseq [k [:frame :path :rf.size/include-digests? :rf.size/threshold-bytes]]
      (is (some? (bad-opts-ex-data
                   #(rf.epoch/projected-record a-record {k nil})))
          (str k " is not a projected-record opt")))))

(deftest projected-record-accepts-every-member-of-its-own-set
  (testing "rf2-kuky.6 — the CONTROL. A guard that rejected everything would
            satisfy every assertion above, so exercise the accepted set:
            each key, alone, gets through."
    (doseq [k rf.epoch.tool-pair/projected-record-opt-keys]
      (let [v (if (= k :rf.egress/profile) :rf.egress/off-box-tool true)]
        (is (nil? (bad-opts-ex-data
                    #(rf.epoch/projected-record a-record {k v})))
            (str k " is accepted")))))
  (testing "nil and empty opts are not 'unknown keys'"
    (is (nil? (bad-opts-ex-data #(rf.epoch/projected-record a-record))))
    (is (nil? (bad-opts-ex-data #(rf.epoch/projected-record a-record nil))))
    (is (nil? (bad-opts-ex-data #(rf.epoch/projected-record a-record {}))))))

(deftest epoch-local-knobs-stay-bare
  (testing "rf2-kuky.6 — the three epoch-local knobs are DELIBERATELY
            unqualified: different keyspaces, not app-db axes. Their
            qualified forms are therefore NOT accepted here; a later
            `:rf.egress/*` sweep must change this test on purpose rather
            than find it already passing."
    (doseq [k [:include-fx-args? :include-runtime-db? :include-event-args?]]
      (is (contains? rf.epoch.tool-pair/projected-record-opt-keys k)
          (str k " stays bare"))
      (is (some? (bad-opts-ex-data
                   #(rf.epoch/projected-record
                      a-record
                      {(keyword "rf.size" (name k)) true})))
          (str "a qualified :rf.size/" (name k) " is NOT silently accepted")))))

(deftest the-guard-runs-before-the-non-map-short-circuit
  (testing "rf2-kuky.6 — `projected-record` returns nil for non-map input.
            A malformed opts map is malformed against ANY input, so the
            guard is graded first — otherwise the same bad call would throw
            or return nil depending on what it was pointed at."
    (is (some? (bad-opts-ex-data
                 #(rf.epoch/projected-record "not-a-record" {:include-sensitive? true}))))
    (is (nil? (rf.epoch/projected-record "not-a-record" {:rf.size/include-sensitive? true}))
        "with a WELL-FORMED opts map the non-map short-circuit still returns nil")))
