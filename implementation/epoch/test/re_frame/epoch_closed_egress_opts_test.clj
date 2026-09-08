(ns re-frame.epoch-closed-egress-opts-test
  "rf2-kuky.6 / rf2-bv1p — the ONE egress door reads ONE vocabulary against
  an `:rf/epoch-record`, and its opts map is CLOSED.

  ## The defect this pins

  The retired `projected-record` door read the two SHARED inclusion axes
  only in their UNQUALIFIED spelling, while every other egress door — the
  walker, `project-egress`, the `:rf/project-egress-opts` schema, Conventions
  — spelled them QUALIFIED. So a caller who had learnt the vocabulary from any
  of those and passed the qualified key there had it SILENTLY DROPPED, and the
  record egressed under the fail-closed floor while the call read as an
  opt-in. (The qualified spelling was `:rf.size/*` then and is `:rf.egress/*`
  since rf2-kuky.93; the defect is about the SPLIT, not about either name.)

  That is a fail-closed direction (over-redaction, not a leak), which is
  precisely why it survived: nothing looked wrong at the sink. Its mirror
  image is what makes it worth a gate — pair-MCP's eval builder chose the
  bare spelling BECAUSE it was the only one that worked there, so the two
  spellings were each load-bearing on a different surface.

  ## Why this suite still exists after rf2-bv1p retired that door

  `re-frame.egress-closed-opts-test` pins the door's closed set generically.
  What is UNIQUE here is the `:rf/epoch-record` arm: rf2-bv1p carried the
  three epoch-only axes ACROSS from the retired door onto
  `project-egress-opt-keys` rather than letting the retirement delete a
  capability, so this suite is what proves they arrived and are graded.

  ## The three epoch-local knobs are qualified now (rf2-kuky.93)

  `:rf.egress/include-fx-args?` / `:rf.egress/include-runtime-db?` /
  `:rf.egress/include-event-args?` were spelled BARE until rf2-kuky.93. They
  are not app-db axes at all — fx args, the runtime-db partition and event
  args are different keyspaces — so `:rf.size/*` would have been absurd on
  them, and bare was the only honest spelling while the vocabulary was named
  for the AXIS. Stage 4 of the rf2-kuky.9 option-A ruling settled it by naming
  the vocabulary for the BOUNDARY instead: `:rf.egress/*` is true of all six
  axes, so the closed twelve-key map takes ONE namespace and one Malli schema.
  `epoch-local-knobs-are-qualified` below is the test the earlier revision
  asked a later sweep to change on purpose."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.core :as rf]
            [re-frame.epoch]
            [re-frame.projection :as rf.projection]))

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
  "A minimal epoch record. It carries the `:kind` DISCRIMINATOR because that
  is what makes `project-egress` dispatch it to the epoch ARM rather than
  walk it as a kindless tree (rf2-kuky.92) — a record without it would
  exercise the wrong path and the epoch-only axes would be inert.

  Its CONTENT does not matter here: the opts guard is graded BEFORE the
  record is inspected, which is itself part of the contract — a malformed
  opts map is malformed against any input."
  {:kind      :rf/epoch-record
   :frame     :app/main
   :epoch-id  1
   :db-before {:auth {:password "s3cret"}}
   :db-after  {:auth {:password "s3cret"}}})

(deftest project-egress-rejects-the-unqualified-shared-axes
  (testing "rf2-kuky.6 — the bare spellings this door used to be the ONLY
            reader of are now a loud error, so the two vocabularies cannot
            both look right at once."
    (doseq [k [:include-sensitive? :include-large?]]
      (let [d (bad-opts-ex-data #(rf/project-egress a-record {k true}))]
        (is (some? d) (str k " throws :rf.error/bad-egress-opts"))
        (is (= [k] (:unknown-keys d)) (str k " is NAMED in ex-data"))
        (is (= 'rf/project-egress (:where d))
            ":where names the door the caller called")
        (is (contains? (set (:accepted d)) :rf.egress/include-sensitive?)
            "and the accepted set teaches the spelling that works")))))

(deftest project-egress-rejects-an-arbitrary-unknown-key
  (testing "rf2-kuky.6 — a closed SET, not a denylist of the spellings that
            bit us."
    (let [d (bad-opts-ex-data
              #(rf/project-egress a-record {:totally-made-up true}))]
      (is (some? d))
      (is (= [:totally-made-up] (:unknown-keys d)))))
  (testing "rf2-bv1p — and the CONVERSE control, because the retired epoch
            door REFUSED these four and the one door ACCEPTS them. A test
            that still expected a refusal would go green only for as long
            as the vocabulary stayed too narrow."
    (doseq [k [:frame :path :rf.egress/include-digests? :rf.egress/threshold-bytes]]
      (is (nil? (bad-opts-ex-data
                  #(rf/project-egress a-record {k nil})))
          (str k " IS project-egress vocabulary")))))

(deftest project-egress-accepts-every-member-of-its-own-set
  (testing "rf2-kuky.6 — the CONTROL. A guard that rejected everything would
            satisfy every assertion above, so exercise the accepted set:
            each key, alone, gets through."
    (doseq [k rf.projection/project-egress-opt-keys]
      (let [v (if (= k :rf.egress/profile) :rf.egress/off-box-tool true)]
        (is (nil? (bad-opts-ex-data
                    #(rf/project-egress a-record {k v})))
            (str k " is accepted")))))
  (testing "nil and empty opts are not 'unknown keys'"
    (is (nil? (bad-opts-ex-data #(rf/project-egress a-record))))
    (is (nil? (bad-opts-ex-data #(rf/project-egress a-record nil))))
    (is (nil? (bad-opts-ex-data #(rf/project-egress a-record {}))))))

(deftest epoch-local-knobs-are-qualified
  (testing "rf2-kuky.93 — the three epoch-local knobs are `:rf.egress/*` like
            every other axis on this door. They are still a different
            KEYSPACE from the app-db axes; what changed is that the namespace
            names the BOUNDARY rather than the axis, so one namespace is true
            of all six and the closed map takes one Malli schema."
    (doseq [k [:rf.egress/include-fx-args? :rf.egress/include-runtime-db?
               :rf.egress/include-event-args?]]
      (is (contains? rf.projection/project-egress-opt-keys k)
          (str k " is door vocabulary"))
      (is (nil? (bad-opts-ex-data #(rf/project-egress a-record {k true})))
          (str k " passes the door's guard"))))
  (testing "rf2-kuky.93 — and BOTH retired spellings are refused rather than
            aliased. The BARE one is the pre-rename spelling of these three;
            the `:rf.size/*` one is what a reader who generalised from the
            app-db axes would have guessed. Neither is silently dropped, so a
            stale caller throws instead of losing its opt-in."
    (doseq [k [:rf.egress/include-fx-args? :rf.egress/include-runtime-db?
               :rf.egress/include-event-args?]]
      (doseq [retired [(keyword (name k)) (keyword "rf.size" (name k))]]
        (is (some? (bad-opts-ex-data
                     #(rf/project-egress a-record {retired true})))
            (str "the retired " retired " is NOT silently accepted"))))))

(deftest the-guard-runs-before-the-input-is-inspected
  (testing "rf2-kuky.6 — a malformed opts map is malformed against ANY
            input, so the guard is graded first — otherwise the same bad
            call would throw or return a value depending on what it was
            pointed at."
    (is (some? (bad-opts-ex-data
                 #(rf/project-egress "not-a-record" {:include-sensitive? true})))))
  (testing "rf2-bv1p — and this is where the one door DIFFERS from the
            retired one, in the FAIL-CLOSED direction. `projected-record`
            returned `nil` for non-map input; `project-egress` has no
            non-map short-circuit at all — a kindless input is a VALUE and
            is WALKED. With no live frame and no sensitive opt-out the
            walker redacts it wholesale rather than handing it back."
    (is (= :rf/redacted (rf/project-egress "not-a-record"))
        "bare 1-arity redacts an unresolvable frameless value")
    (is (= :rf/redacted (rf/project-egress nil))
        "nil is a value too, and redacts rather than returning nil")
    (is (= "not-a-record"
           (rf/project-egress "not-a-record" {:rf.egress/include-sensitive? true}))
        "the explicit opt-out is the one deliberate way to ship it raw")))
