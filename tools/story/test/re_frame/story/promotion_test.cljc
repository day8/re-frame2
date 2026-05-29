(ns re-frame.story.promotion-test
  "Tests for the run-artifact → variant promotion bridge (rf2-5x1wt.25,
  spec/017-Testing-Story.md §Promotion — Promotion bridge; NewTestStory
  §C1).

  Two layers, both under `clojure -M:test` (JVM) + the node-runtime CLJS
  build:

  - PURE `materialize-variant-plan` (§C1 bullets 1, 2, 4): a run artifact
    becomes a readable normalized plan; the plan preserves the source
    artifact link; the program projects into setup/script per the policy.
    Side-effect-free — these tests assert it registers NOTHING.
  - The explicit `promote-run-artifact!` registration path (§C1 bullet 3):
    promotion does NOT auto-register without the explicit named call; the
    explicit call registers a variant carrying the source link.

  The variant-plan compiler is pure data → data and the registrar is a
  pure side-table, so every test runs on both targets with no host — a
  fresh side-table per test via the fixture, and an explicit `:lookup`
  for `:extends` resolution where needed."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.story :as story]
            [re-frame.story.artifact :as artifact]
            [re-frame.story.promotion :as promotion]
            [re-frame.story.registrar :as registrar]))

;; ---- fixtures -----------------------------------------------------------
;;
;; Standard `clojure.test` fixture FUNCTION (not the `{:before …}` map
;; form): a one-arg fn that resets the Story side-table to empty, runs
;; the test, and the promotion path repopulates only what it registers.
;; Matches the `artifact_test` fixture shape — the function form runs on
;; both the JVM `clojure -M:test` runner and the node CLJS build.

(defn reset-side-table! [t]
  (registrar/clear-all!)
  (t))

(use-fixtures :each reset-side-table!)

;; ---- helpers ------------------------------------------------------------

(defn- sample-artifact
  "A run artifact with a two-step dispatch program + a stubbed fx
  decision + provenance slots + bulky captured evidence (so the
  provenance-trim assertions have something to drop)."
  []
  (artifact/make-run-artifact
    {:event-program [[:dispatch [:counter/init 5]]
                     [:dispatch [:counter/inc]]]
     :seed          42
     :fx-decisions  {:http/get :http/stub}
     :created-at    "2026-05-30T00:00:00Z"
     :source        {:tool :recorder}
     ;; bulky captured evidence the promotion link MUST drop:
     :epoch-tape    [{:big :tape}]
     :trace         [{:big :trace}]
     :result        {:status :fail}}))

;; ===========================================================================
;; §C1 bullet 1 — a run artifact becomes a readable normalized plan
;; ===========================================================================

(deftest materialize-produces-readable-plan
  (testing "a run artifact materializes to the normalized four-bucket plan"
    (let [art  (sample-artifact)
          plan (promotion/materialize-variant-plan art)]
      (is (contains? plan :world))
      (is (contains? plan :script))
      (is (contains? plan :expect))
      (is (map? (:expect plan)))
      (is (= #{:client} (get-in plan [:world :platforms]))
          "the plan carries the compiler's normalized defaults")))

  (testing "the default policy projects the whole program into :script"
    (let [art  (sample-artifact)
          plan (promotion/materialize-variant-plan art)]
      (is (= [[:dispatch [:counter/init 5]]
              [:dispatch [:counter/inc]]]
             (:script plan)))
      (is (= [] (get-in plan [:world :setup]))
          "nothing is demoted to a silent precondition without a hint")))

  (testing "a :variant/id rides onto the materialized plan"
    (let [art  (sample-artifact)
          plan (promotion/materialize-variant-plan
                 art {:variant/id :story.counter/regression-042})]
      (is (= :story.counter/regression-042 (:variant/id plan))))))

;; ===========================================================================
;; §C1 bullet 4 — generated event program becomes script/setup per policy
;; ===========================================================================

(deftest program-projects-to-setup-and-script-per-policy
  (testing ":setup-count cuts preconditions off the front into [:world :setup]"
    (let [art  (sample-artifact)
          plan (promotion/materialize-variant-plan art {:setup-count 1})]
      (is (= [[:dispatch [:counter/init 5]]] (get-in plan [:world :setup]))
          "the first step is a precondition")
      (is (= [[:dispatch [:counter/inc]]] (:script plan))
          "the rest is behaviour-under-test")))

  (testing "an explicit :setup + :script partition is used verbatim"
    (let [art  (sample-artifact)
          plan (promotion/materialize-variant-plan
                 art {:setup  [[:dispatch [:seed/a]]]
                      :script [[:dispatch [:act/b]]]})]
      (is (= [[:dispatch [:seed/a]]] (get-in plan [:world :setup])))
      (is (= [[:dispatch [:act/b]]] (:script plan)))))

  (testing "partition-program clamps an oversized :setup-count"
    (let [art (sample-artifact)
          {:keys [setup script]} (promotion/partition-program art {:setup-count 99})]
      (is (= 2 (count setup)) "every step becomes a precondition")
      (is (= [] script))))

  (testing "a bare event list in :script lifts to a tagged [:dispatch …] program"
    (let [art  (sample-artifact)
          plan (promotion/materialize-variant-plan
                 art {:script [[:counter/reset]]})]
      (is (= [[:dispatch [:counter/reset]]] (:script plan))))))

;; ===========================================================================
;; §C1 bullet 2 — promotion preserves the source-artifact link
;; ===========================================================================

(deftest materialize-preserves-source-artifact-link
  (testing "the plan carries a :run-artifact back-link to the source"
    (let [art  (sample-artifact)
          plan (promotion/materialize-variant-plan art)
          link (:run-artifact plan)]
      (is (= :rf.test/run-artifact (:artifact/kind link)))
      (is (= 42 (:seed link)))
      (is (= {:http/get :http/stub} (:fx-decisions link)))
      (is (= {:tool :recorder} (:source link)))
      (is (= [[:dispatch [:counter/init 5]]
              [:dispatch [:counter/inc]]]
             (:event-program link))
          "the replayable program survives on the link")))

  (testing "the link is TRIMMED — bulky captured evidence is dropped"
    (let [link (:run-artifact (promotion/materialize-variant-plan (sample-artifact)))]
      (is (not (contains? link :epoch-tape)))
      (is (not (contains? link :trace)))
      (is (not (contains? link :result))
          "a registered variant is a curation surface, not an evidence dump")))

  (testing "the promoted variant body also carries the source link"
    (let [body (promotion/artifact->variant-body (sample-artifact))]
      (is (= :rf.test/run-artifact (get-in body [:run-artifact :artifact/kind]))))))

;; ===========================================================================
;; §C1 bullet 3 — promotion does NOT auto-register without the explicit call
;; ===========================================================================

(deftest materialize-registers-nothing
  (testing "materialize-variant-plan is pure — it registers NO variant"
    (let [art (sample-artifact)]
      (promotion/materialize-variant-plan art {:variant/id :story.counter/never})
      (is (not (registrar/registered? :variant :story.counter/never))
          "materialize must not touch the side-table")
      (is (empty? (registrar/registrations :variant))
          "the side-table stays empty after materialization"))))

(deftest promote-requires-explicit-variant-id
  (testing "promote-run-artifact! throws without an explicit :variant/id"
    (let [art (sample-artifact)]
      (is (thrown-with-msg?
            #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
            #"story-promote-no-id"
            (promotion/promote-run-artifact! art {})))
      (is (empty? (registrar/registrations :variant))
            "a no-id promotion registers nothing"))))

(deftest promote-registers-the-named-variant
  (testing "the explicit named call DOES register a curated variant"
    (let [art (sample-artifact)
          ret (promotion/promote-run-artifact!
                art {:variant/id :story.counter/regression-042})]
      (is (= :story.counter/regression-042 ret)
          "promote returns the registered variant id")
      (is (registrar/registered? :variant :story.counter/regression-042)
          "the variant is now in the side-table")))

  (testing "the registered body carries the source-artifact link + the program"
    (registrar/clear-all!)
    (let [art (sample-artifact)]
      (promotion/promote-run-artifact!
        art {:variant/id :story.counter/regression-042})
      (let [body (registrar/handler-meta :variant :story.counter/regression-042)]
        (is (= :rf.test/run-artifact (get-in body [:run-artifact :artifact/kind]))
            "provenance survives into the registered variant")
        ;; The registrar lowers the public `:script` bare step-vector to
        ;; the shipping `:play-script` slot (still a bare step vector).
        (is (= [[:dispatch [:counter/init 5]]
                [:dispatch [:counter/inc]]]
               (:play-script body))
            "the behaviour program is the registered play script"))))

  (testing "the facade re-exports route to the same bridge"
    (registrar/clear-all!)
    (let [art (sample-artifact)]
      (is (contains? (story/materialize-variant-plan art) :run-artifact))
      (is (= :story.counter/from-facade
             (story/promote-run-artifact!
               art {:variant/id :story.counter/from-facade})))
      (is (registrar/registered? :variant :story.counter/from-facade)))))
