;;;; tests/eval_corpus_shape_test.clj — evals/evals.json is a REPOSITORY
;;;; wrapper, in neither of upstream's formats: task evaluation is an object
;;;; with no `should_trigger`; trigger evaluation is a TOP-LEVEL LIST of
;;;; `{query, should_trigger}` that run_eval.py indexes as `item["query"]`.
;;;; Feeding the wrapper to the trigger loop without conversion fails on format
;;;; before a single query is graded, so evals/README.md must say so and carry
;;;; the conversion, and the conversion must yield items the runner can read.
;;;; Id and name uniqueness is scripts/check_skill_eval_docs.py's A4 axis.
;;;;
;;;; Run: bb tests/eval_corpus_shape_test.clj   (from the skill root)

(ns eval-corpus-shape-test
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]))

(def ^:private skill-root (-> *file* io/file .getAbsoluteFile .getParentFile .getParentFile))

(def ^:private corpus
  (delay (json/parse-string (slurp (io/file skill-root "evals" "evals.json")) true)))

(def ^:private evals-readme (delay (slurp (io/file skill-root "evals" "README.md"))))

(defn- contains-any? [s needles] (boolean (some #(str/includes? s %) needles)))

(deftest corpus-is-not-already-the-trigger-input
  (let [parsed @corpus]
    (is (contains? parsed :evals)
        "evals.json must be the repository wrapper; if it is deliberately the native trigger list, retire this suite and the README's conversion together")
    (is (seq (:evals parsed)) "the corpus is empty")
    (is (not-any? #(contains? % :query) (:evals parsed))
        "a fixture carries `query`: a half-converted corpus silently drops entries in whichever runner reads it")))

(deftest docs-do-not-claim-the-corpus-is-upstreams-trigger-input
  (is (contains-any? @evals-readme ["repository convention" "REPOSITORY convention"])
      "evals/README.md must call the wrapper this repo's own convention")
  (let [conv (:convention @corpus)]
    (is (contains-any? conv ["REPOSITORY convention" "repository convention"])
        "evals.json's `convention` string must agree with the README")
    (is (contains-any? conv ["TOP-LEVEL LIST" "top-level list"])
        "and name the trigger format it converts to")))

(deftest readme-documents-the-conversion
  (let [body @evals-readme]
    (is (str/includes? body "query: .prompt") "§How to run must carry the prompt -> query mapping")
    (is (contains-any? body ["Task evaluation" "task evaluation"]) "distinguish upstream's task schema from its trigger format")
    (is (str/includes? body "should_trigger") "name the trigger label")))

(deftest documented-conversion-yields-the-trigger-input
  ;; The README's jq filter, expressed over the parsed corpus.
  (let [converted (mapv (fn [e] {:query (:prompt e) :should_trigger (:should_trigger e)}) (:evals @corpus))]
    (is (every? #(string? (:query %)) converted) "run_eval.py reads item[\"query\"] as a string")
    (is (every? #(boolean? (:should_trigger %)) converted) "and compares item[\"should_trigger\"] as a boolean")))

(let [{:keys [fail error]} (run-tests 'eval-corpus-shape-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
