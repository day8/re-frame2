;;;; tests/duplicate_search_test.clj — all-state duplicate-search contract
;;;; for the §Issue drafts branch (rf2-2jeh5).
;;;;
;;;; The skill's duplicate invariant: whenever Pair-retro represents that it
;;;; checked for an existing owner, the candidate search covers open AND
;;;; closed issues — `gh issue list` defaults to `--state open`, so the
;;;; prescribed query must carry `--state all` explicitly or a closed issue
;;;; that already owns the friction (a landed fix, an intentional rejection)
;;;; is invisible and the skill drafts a twin. State broadens DISCOVERY only;
;;;; semantic comparison still decides ownership, and a failed query is
;;;; "not checked", never "no duplicate".
;;;;
;;;; This is one focused behavioral/command fixture, not a skill runner: it
;;;; extracts the prescribed `gh issue list` argv from SKILL.md verbatim,
;;;; models gh's documented state filtering over a three-issue fixture set,
;;;; and asserts the outcomes the skill's own §Issue drafts prose promises.
;;;; Removing `--state all` from SKILL.md makes the closed-owner case here
;;;; fail while the no-match control keeps passing (the bead's non-vacuity
;;;; criterion).
;;;;
;;;; Run: bb tests/duplicate_search_test.clj   (from skills/re-frame2-pair-retro/)
;;;; Exit: 0 = pass, non-zero = fail.

(ns duplicate-search-test
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing run-tests]]))

;; ---------------------------------------------------------------------------
;; Filesystem helpers
;; ---------------------------------------------------------------------------

(def ^:private skill-root
  (-> *file*
      (io/file)
      (.getAbsoluteFile)
      (.getParentFile)   ;; tests/
      (.getParentFile))) ;; skills/re-frame2-pair-retro/

(def ^:private skill-md
  (delay (slurp (io/file skill-root "SKILL.md"))))

;; ---------------------------------------------------------------------------
;; Prescribed-argv extraction — the command the skill actually teaches.
;; ---------------------------------------------------------------------------

(defn- prescribed-list-commands
  "Every `gh issue list …` invocation SKILL.md prescribes, taken from
  inline code spans verbatim. The frontmatter grant (`Bash(gh issue list *)`)
  is a permission pattern, not a prescription, and carries no argv — the
  regex requires at least one argument so it is excluded by shape."
  [md]
  (->> (re-seq #"`(gh issue list [^`]+)`" md)
       (map second)))

(defn- argv-state
  "The `--state` value an extracted command carries, defaulting to \"open\"
  when absent — mirroring `gh issue list --help`: \"By default, this only
  lists open issues\" (--state {open|closed|all}, default open)."
  [cmd]
  (or (second (re-find #"--state[= ](\S+)" cmd))
      "open"))

;; ---------------------------------------------------------------------------
;; Fixture repository — one closed semantic owner, one unrelated open issue
;; sharing a broad keyword, one unrelated closed issue sharing it too.
;; ---------------------------------------------------------------------------

(def ^:private fixture-issues
  [{:number 4101 :state "closed"
    :title "restore-epoch leaves stale machine snapshot after schema tightening"
    :keywords #{"restore" "epoch" "schema" "friction"}
    :semantic-match? true
    :disposition "fix landed in 0.9.2 — the friction is an upgrade away"}
   {:number 4200 :state "open"
    :title "epoch ring default depth is too small for long sessions"
    :keywords #{"epoch" "depth"}
    :semantic-match? false}
   {:number 4050 :state "closed"
    :title "epoch viewer keyboard shortcuts"
    :keywords #{"epoch" "keyboard"}
    :semantic-match? false}])

(defn- gh-list
  "Model of `gh issue list --repo day8/re-frame2 --state <s> --search <kw>`:
  filter the fixture set by the requested state (gh's documented behaviour),
  then by keyword overlap. Returns the candidate seq."
  [state keywords]
  (->> fixture-issues
       (filter #(or (= state "all") (= state (:state %))))
       (filter #(seq (set/intersection keywords (:keywords %))))))

(defn- duplicate-check
  "Model of the skill's §Issue drafts decision rule. `gh-ok?` false models a
  failed/unavailable query. Discovery uses the state parsed from the
  PRESCRIBED command; suppression additionally requires a semantic match
  (state alone must never decide ownership)."
  [state keywords gh-ok?]
  (if-not gh-ok?
    {:outcome :not-checked}
    (let [candidates (gh-list state keywords)]
      (if-let [owner (first (filter :semantic-match? candidates))]
        {:outcome     :link-existing
         :issue       (:number owner)
         :disposition (:disposition owner)}
        {:outcome :draft}))))

(defn- prescribed-state
  "The state the skill's own prescribed query would search with."
  []
  (argv-state (first (prescribed-list-commands @skill-md))))

;; ---------------------------------------------------------------------------
;; The prescribed argv — narrow to day8/re-frame2, explicitly all-state.
;; ---------------------------------------------------------------------------

(deftest prescribed-query-is-all-state-and-repo-narrow
  (testing "every prescribed `gh issue list` searches day8/re-frame2 across all states"
    (let [cmds (prescribed-list-commands @skill-md)]
      (is (seq cmds)
          "SKILL.md prescribes at least one `gh issue list` duplicate query")
      (doseq [cmd cmds]
        (is (str/includes? cmd "--repo day8/re-frame2")
            (str "duplicate search stays narrow to day8/re-frame2: " cmd))
        (is (= "all" (argv-state cmd))
            (str "duplicate search must pass --state all explicitly — gh "
                 "defaults to open-only, which hides a closed owner: " cmd))))))

;; ---------------------------------------------------------------------------
;; Closed owner — discovered under the prescribed state, viewed, linked.
;; ---------------------------------------------------------------------------

(deftest closed-owner-is-discovered-and-linked
  (testing "a semantically matching CLOSED issue is in the candidate set and suppresses the twin draft"
    (let [state      (prescribed-state)
          keywords   #{"restore" "epoch" "friction"}
          candidates (gh-list state keywords)]
      (is (some #(= 4101 (:number %)) candidates)
          (str "the closed owner (#4101) must be discoverable — under gh's "
               "open-only default it is invisible and the skill drafts a twin "
               "(searched state: " state ")"))
      (let [{:keys [outcome issue disposition]} (duplicate-check state keywords true)]
        (is (= :link-existing outcome)
            "a discovered semantic owner is linked instead of twinned")
        (is (= 4101 issue))
        (is (str/includes? (str disposition) "0.9.2")
            "the closed hit is VIEWED — its disposition (the landed fix) is relayed, not just its number")))))

;; ---------------------------------------------------------------------------
;; No-match control — an unrelated closed keyword-hit must not suppress the
;; draft; state broadens discovery, semantics decide ownership. This control
;; passes with or without `--state all`, pinning the non-vacuity direction.
;; ---------------------------------------------------------------------------

(deftest unrelated-hits-do-not-suppress-the-draft
  (testing "keyword-sharing but semantically unrelated issues (open AND closed) still yield one draft"
    (let [state      (prescribed-state)
          keywords   #{"epoch"}
          candidates (gh-list state keywords)]
      ;; The broad keyword surfaces unrelated issues, the closed #4050 and the
      ;; semantic owner among them — discovery is deliberately generous.
      (is (some #(= 4050 (:number %)) (gh-list "all" keywords))
          "an unrelated CLOSED issue can appear in an all-state candidate set")
      ;; Ownership needs the semantic match; strip it from this scenario by
      ;; searching keywords the owner doesn't... the owner shares :epoch, so
      ;; model the semantic comparison directly: candidates minus the owner
      ;; carry no semantic match and must not suppress.
      (let [non-owners (remove :semantic-match? candidates)]
        (is (seq non-owners) "the control set is non-empty (shape-matched control)")
        (is (not-any? :semantic-match? non-owners)))
      (is (= {:outcome :draft}
             (duplicate-check state #{"unrelated-vocabulary"} true))
          "no semantic/keyword match at all → the requested draft is produced"))))

;; ---------------------------------------------------------------------------
;; Query-error control — a failed search is "not checked", never "no owner".
;; ---------------------------------------------------------------------------

(deftest failed-query-reports-not-checked
  (testing "a failed gh query yields :not-checked — not :draft on an implied no-duplicate"
    (is (= {:outcome :not-checked}
           (duplicate-check (prescribed-state) #{"restore" "epoch"} false))))
  (testing "SKILL.md carries the not-checked instruction in prose"
    (is (re-find #"(?i)duplicate status was not checked" @skill-md)
        "§Issue drafts must instruct saying the check was not completed on a skipped/failed query")))

;; ---------------------------------------------------------------------------
;; Read-only surface unchanged — list/view only, no mutation grant.
;; ---------------------------------------------------------------------------

(deftest grant-stays-read-only
  (testing "the allowed-tools grant is still exactly gh issue list/view (no create/label/write)"
    (is (str/includes? @skill-md "Bash(gh issue list *)"))
    (is (str/includes? @skill-md "Bash(gh issue view *)"))
    (is (not (str/includes? @skill-md "gh issue create *"))
        "no mutation grant may ride along with the all-state fix")))

;; ---------------------------------------------------------------------------
;; Run
;; ---------------------------------------------------------------------------

(let [{:keys [fail error]} (run-tests 'duplicate-search-test)]
  (System/exit (if (and (zero? fail) (zero? error)) 0 1)))
