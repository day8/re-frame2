;;;; tests/eval_corpus_shape_test.clj — the eval corpus is a REPOSITORY
;;;; wrapper, and evals/README.md documents the conversion to Anthropic's
;;;; separate trigger-evaluation input (rf2-fzbj.42 F1).
;;;;
;;;; The defect this pins: evals.json's `convention` string and the README
;;;; both claimed the corpus was Anthropic's skill-creator trigger fixture,
;;;; and §How to run named the description-optimisation loop as the run path
;;;; with no conversion. Upstream has TWO formats and this file is neither:
;;;;
;;;;   * task evaluation  — an OBJECT {skill_name, evals:[{id, prompt,
;;;;     expected_output, expectations, …}]}; no `should_trigger` at all;
;;;;   * trigger evaluation — a TOP-LEVEL LIST of {query, should_trigger};
;;;;     run_eval.py iterates the loaded document and reads item["query"].
;;;;
;;;; So a maintainer following the old prose hit `TypeError: string indices
;;;; must be integers` on the whole file, or `KeyError: 'query'` after merely
;;;; unwrapping `evals` — a format failure before a single query was graded.
;;;;
;;;; This suite is a DATA-SHAPE pin, not a scorer: it runs no model, starts no
;;;; upstream executor, and adds no dependency. It asserts (1) the premise —
;;;; the corpus really is in neither upstream shape, so the conversion is
;;;; load-bearing rather than decorative; (2) the README documents that
;;;; wrapper honestly and carries the conversion; and (3) applying the
;;;; documented mapping to the real corpus yields exactly one
;;;; query/should_trigger item per fixture with strings and labels preserved.
;;;;
;;;; Run locally:  bb tests/eval_corpus_shape_test.clj   (from the skill root)
;;;; Exit:         0 = pass, non-zero = fail.
;;;;
;;;; CI: gated by the `skills-structural` job in .github/workflows/test.yml,
;;;; which loops `skills/re-frame2-pair-retro/tests/*_test.clj`.
;;;;
;;;; NOT published — package.json `files` ships neither `tests/` nor `evals/`.

(ns eval-corpus-shape-test
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing run-tests]]))

;; ---------------------------------------------------------------------------
;; Sources
;; ---------------------------------------------------------------------------

(def ^:private skill-root
  (-> *file*
      (io/file)
      (.getAbsoluteFile)
      (.getParentFile)   ;; tests/
      (.getParentFile))) ;; skills/re-frame2-pair-retro/

(def ^:private corpus
  (delay (json/parse-string (slurp (io/file skill-root "evals" "evals.json")) true)))

(def ^:private evals-readme
  (delay (slurp (io/file skill-root "evals" "README.md"))))

(defn- contains-any?
  [s needles]
  (boolean (some #(str/includes? s %) needles)))

;; The conversion evals/README.md documents, expressed over the parsed corpus.
;; Kept deliberately literal — it is the jq filter, not a generalisation of it.
(defn- to-trigger-input
  [parsed]
  (mapv (fn [e] {:query (:prompt e) :should_trigger (:should_trigger e)})
        (:evals parsed)))

;; ---------------------------------------------------------------------------
;; The premise — the corpus is in NEITHER upstream shape
;; ---------------------------------------------------------------------------

(deftest corpus-is-not-already-the-trigger-input
  (let [parsed @corpus]
    (testing "the top level is an object, so the trigger runner's `for item in eval_set` cannot work"
      (is (map? parsed)
          (str "evals.json is now a top-level list. If the corpus was deliberately "
               "moved to Anthropic's native trigger shape, retire this suite and the "
               "conversion in evals/README.md together — do not leave both standing."))
      (is (contains? parsed :evals)
          "the repository wrapper no longer carries an `evals` key."))
    (testing "entries carry `prompt`, not the `query` the trigger runner indexes"
      (is (seq (:evals parsed)) "the corpus is empty.")
      (is (every? #(contains? % :prompt) (:evals parsed))
          "some fixture lost its `prompt` field.")
      (is (not-any? #(contains? % :query) (:evals parsed))
          (str "a fixture carries `query`. The corpus is half-converted — pick one "
               "shape; a mixed corpus silently drops entries in whichever runner reads it.")))
    (testing "entries carry `should_trigger`, which upstream's TASK schema does not define"
      (is (every? #(contains? % :should_trigger) (:evals parsed))
          "a fixture lost its `should_trigger` label."))))

;; ---------------------------------------------------------------------------
;; The documentation tells the truth about the wrapper
;; ---------------------------------------------------------------------------

(deftest docs-do-not-claim-the-corpus-is-upstreams-trigger-input
  (testing "evals/README.md calls the wrapper a repository convention"
    (let [body @evals-readme]
      (is (contains-any? body ["repository convention" "REPOSITORY convention"])
          (str "evals/README.md no longer says the wrapper is this repo's own "
               "convention. Claiming upstream provenance for it is the rf2-fzbj.42 "
               "defect: a maintainer feeds evals.json to the description-optimisation "
               "loop and hits a format error before anything is graded."))
      (is (not (str/includes? body "The fixtures follow Anthropic's `skill-creator` convention"))
          "the superseded false-provenance sentence is back.")))
  (testing "evals.json's own `convention` string agrees with the README"
    (let [conv (:convention @corpus)]
      (is (string? conv) "evals.json lost its `convention` string.")
      (is (contains-any? conv ["REPOSITORY convention" "repository convention"])
          (str "evals.json's `convention` still advertises an upstream schema. It is "
               "read by whoever opens the file rather than the README, so both must "
               "carry the correction or the two disagree again."))
      (is (contains-any? conv ["TOP-LEVEL LIST" "top-level list"])
          "the `convention` string no longer names the trigger format it must be converted to."))))

(deftest readme-documents-the-conversion
  (let [body @evals-readme]
    (testing "§How to run carries the prompt→query mapping"
      (is (str/includes? body "query: .prompt")
          (str "evals/README.md no longer documents the conversion. Naming the "
               "description-optimisation loop as the run path WITHOUT it is exactly "
               "the finding: the loop reads a top-level list of query/should_trigger "
               "objects and this corpus is neither.")))
    (testing "it distinguishes the two upstream formats"
      (is (contains-any? body ["Task evaluation" "task evaluation"])
          "the README no longer distinguishes upstream's task schema from its trigger format.")
      (is (str/includes? body "should_trigger")
          "the README no longer names the trigger label."))
    (testing "the upstream citations are pinned to a verifiable revision"
      (is (str/includes? body "3d59511518591fa82e6cfcf0438d68dd5dad3e76")
          (str "the upstream links are no longer pinned to the reviewed revision. "
               "A `blob/main` link cannot be re-checked later against what was read.")))))

;; ---------------------------------------------------------------------------
;; The documented conversion actually produces the trigger input
;; ---------------------------------------------------------------------------

(deftest documented-conversion-yields-the-trigger-input
  (let [parsed    @corpus
        fixtures  (:evals parsed)
        converted (to-trigger-input parsed)]
    (testing "one item per fixture, and nothing else"
      (is (= (count fixtures) (count converted)))
      (is (vector? converted) "the conversion must produce a top-level list.")
      (is (= #{:query :should_trigger} (into #{} (mapcat keys) converted))
          (str "the converted items carry keys beyond query/should_trigger. Local "
               "bookkeeping (id, name, rationale) is not part of the trigger input.")))
    (testing "strings and labels survive verbatim"
      (is (= (mapv :prompt fixtures) (mapv :query converted))
          "a prompt was altered by the conversion.")
      (is (= (mapv :should_trigger fixtures) (mapv :should_trigger converted))
          "a should_trigger label was altered by the conversion."))
    (testing "every converted item satisfies the runner's two accesses"
      (is (every? #(string? (:query %)) converted)
          "run_eval.py reads item[\"query\"] as a string.")
      (is (every? #(boolean? (:should_trigger %)) converted)
          "run_eval.py compares item[\"should_trigger\"] as a boolean."))
    (testing "ids and names stay unique in the source (they key the per-run directories)"
      (is (= (count fixtures) (count (set (map :id fixtures)))))
      (is (= (count fixtures) (count (set (map :name fixtures))))))))

;; ---------------------------------------------------------------------------
;; Run
;; ---------------------------------------------------------------------------

(let [{:keys [fail error]} (run-tests 'eval-corpus-shape-test)]
  (System/exit (if (and (zero? fail) (zero? error)) 0 1)))
