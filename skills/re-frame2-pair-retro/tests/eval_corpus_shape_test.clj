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

(deftest docs-do-not-claim-the-corpus-is-upstreams-trigger-input
  (let [conv (:convention @corpus)]
    (is (= [true true true]
           [(contains-any? @evals-readme ["repository convention" "REPOSITORY convention"])
            (contains-any? conv ["REPOSITORY convention" "repository convention"])
            (contains-any? conv ["TOP-LEVEL LIST" "top-level list"])]))))

(deftest readme-documents-the-conversion
  (is (= [true true true]
         [(str/includes? @evals-readme "query: .prompt")
          (contains-any? @evals-readme ["Task evaluation" "task evaluation"])
          (str/includes? @evals-readme "should_trigger")])))

;; The README's jq filter must yield usable input for the trigger runner.
(deftest documented-conversion-yields-the-trigger-input
  (let [converted (mapv (fn [e] {:query (:prompt e) :should_trigger (:should_trigger e)})
                        (:evals @corpus))]
    (is (seq converted))
    (is (= [true true]
           [(every? #(string? (:query %)) converted)
            (every? #(boolean? (:should_trigger %)) converted)]))))

(let [{:keys [fail error]} (run-tests 'eval-corpus-shape-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
