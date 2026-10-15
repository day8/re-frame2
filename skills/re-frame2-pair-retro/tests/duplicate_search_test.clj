(ns duplicate-search-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]))

(def ^:private skill-md
  (delay (slurp (io/file (-> *file* io/file .getAbsoluteFile .getParentFile .getParentFile)
                        "SKILL.md"))))

;; A closed issue can already own the friction.
(deftest prescribed-query-is-all-state-and-repo-narrow
  (let [cmds (map second (re-seq #"`(gh issue list [^`]+)`" @skill-md))]
    (is (seq cmds) "SKILL.md must prescribe a duplicate query")
    (doseq [cmd cmds]
      (is (= [true "all"]
             [(str/includes? cmd "--repo day8/re-frame2")
              (or (second (re-find #"--state[= ](\S+)" cmd)) "open")])
          cmd))))

(deftest failed-query-reports-not-checked
  (is (re-find #"(?i)duplicate status was not checked" @skill-md)))

(deftest grant-stays-read-only
  (is (= [true true false]
         (mapv #(str/includes? @skill-md %)
               ["Bash(gh issue list *)" "Bash(gh issue view *)" "gh issue create *"]))))

(let [{:keys [fail error]} (run-tests 'duplicate-search-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
