;;;; tests/duplicate_search_test.clj — the §Issue drafts duplicate search must
;;;; cover open AND closed issues. `gh issue list` defaults to `--state open`,
;;;; so without an explicit `--state all` a closed issue that already owns the
;;;; friction (a landed fix, an intentional rejection) is invisible and the
;;;; skill drafts a twin. The argv is extracted from SKILL.md verbatim and run
;;;; through a model of gh's documented state filter over a fixture set.
;;;;
;;;; Run: bb tests/duplicate_search_test.clj   (from skills/re-frame2-pair-retro/)

(ns duplicate-search-test
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]))

(def ^:private skill-md
  (let [f (io/file (-> *file* io/file .getAbsoluteFile .getParentFile .getParentFile) "SKILL.md")]
    (delay (slurp f))))

(defn- prescribed-list-commands
  "Every `gh issue list …` SKILL.md prescribes in an inline code span. The
   frontmatter grant `Bash(gh issue list *)` carries no argv, so the
   one-argument minimum excludes it."
  [md]
  (map second (re-seq #"`(gh issue list [^`]+)`" md)))

(defn- argv-state
  "The `--state` an extracted command carries; gh's default is open."
  [cmd]
  (or (second (re-find #"--state[= ](\S+)" cmd)) "open"))

(def ^:private fixture-issues
  ;; One closed semantic owner, and two unrelated issues sharing a keyword.
  [{:number 4101 :state "closed" :keywords #{"restore" "epoch" "schema" "friction"}}
   {:number 4200 :state "open"   :keywords #{"epoch" "depth"}}
   {:number 4050 :state "closed" :keywords #{"epoch" "keyboard"}}])

(defn- gh-list
  "Model of `gh issue list --state <s> --search <kw>`: filter by state, then keyword overlap."
  [state keywords]
  (->> fixture-issues
       (filter #(or (= state "all") (= state (:state %))))
       (filter #(seq (set/intersection keywords (:keywords %))))))

(deftest prescribed-query-is-all-state-and-repo-narrow
  (let [cmds (prescribed-list-commands @skill-md)]
    (is (seq cmds) "SKILL.md must prescribe a `gh issue list` duplicate query")
    (doseq [cmd cmds]
      (is (str/includes? cmd "--repo day8/re-frame2") (str "the search stays narrow to day8/re-frame2: " cmd))
      (is (= "all" (argv-state cmd)) (str "the search must pass --state all explicitly: " cmd)))))

(deftest closed-owner-is-discovered-under-the-prescribed-state
  (let [state (argv-state (first (prescribed-list-commands @skill-md)))]
    (is (some #(= 4101 (:number %)) (gh-list state #{"restore" "epoch" "friction"}))
        (str "the closed owner #4101 must be discoverable; under gh's open-only default it is invisible (searched: " state ")"))))

(deftest failed-query-reports-not-checked
  (is (re-find #"(?i)duplicate status was not checked" @skill-md)
      "§Issue drafts must say the check was not completed when the query fails, never imply no duplicate"))

(deftest grant-stays-read-only
  (is (str/includes? @skill-md "Bash(gh issue list *)"))
  (is (str/includes? @skill-md "Bash(gh issue view *)"))
  (is (not (str/includes? @skill-md "gh issue create *")) "no mutation grant may ride along with the search"))

(let [{:keys [fail error]} (run-tests 'duplicate-search-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
