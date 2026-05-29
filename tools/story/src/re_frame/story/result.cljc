(ns re-frame.story.result
  "The ONE unified run-result — the single shape every Story runner,
  Test mode, CI, `clojure.test`, and MCP consume (NewTestStory
  rf2-5x1wt.19, `tools/story/spec/017-Testing-Story.md` §Run result +
  §Unified run result).

  ## One result, one source of truth

  Spec/017 §Run result: *all runners MUST return the same shape*, and the
  evidential slots are *projections from one retained epoch tape*, not a
  second capture path. Before this ns three result vocabularies coexisted:

  - `runtime.cljc`'s `record-result-map` (`:lifecycle`, a flat
    `:assertions` vector, no top-level `:status`);
  - `runner.cljc`'s per-step `run-state` machine (`:pass` / `:fail` /
    per-step `:results`, read directly by the toolbar chip + CI runner);
  - `artifact.cljc`'s `replay-result` (the FIRST unified shape — top-level
    `:status`, the `.4` tape projection, a `:run-artifact` back-link).

  The documented \"false GREEN\" was exactly the disagreement between
  run-state and the assertions slot. This ns folds the three onto ONE
  shape, derived — wherever the tape carries the evidence — from `.4`'s
  `re-frame.story.play.evidence/project-evidence` (the single tape
  projection; this ns does NOT add a parallel accumulator). The assertion
  outcomes that are NOT in the tape (the `:rf.assert/*` records the
  handlers wrote to `:rf.story/assertions`) are the ONLY non-tape input:
  this ns adapts that accumulator into the unified assertion records and
  computes the verdict.

  ## The three record shapes (spec/017 §Run result)

  - RUN-RESULT — the top-level shape. `:status` ∈
    `#{:pass :fail :cannot-run :error}` is the verdict; the evidential
    slots (`:epoch-tape` / `:schema-violations` / `:warnings` / `:effects`
    / `:sub-runs` / `:renders` / `:narrative`) are `.4` projections; the
    judgement slots (`:assertions` / `:checks`) are folded from the
    accumulator; `:app-db` / `:run-hash` / `:plan-hash` / `:runner` /
    `:required-runner` / `:fidelity` / `:elapsed-ms` round out the shape.
  - ASSERTION-RECORD — one evaluated assertion (`.18`'s atom shape, plus a
    `:status`). The `:status` is derived from `:passed?` / `:skipped?` /
    `:cannot-run?` so the run aggregation reads ONE field uniformly.
  - CHECK-RECORD — a named check id grouping the assertion records its
    assertions produced (spec/017 §Checks — a failed check shows BOTH the
    check id and the underlying records).

  ## Status — the aggregation rule, stated once

  `aggregate-status` (in `re-frame.story.requirements`, reused here) is the
  ONE rule: `:error` > `:fail` > `:cannot-run` > `:pass`, AND a run whose
  tape shows unconsumed failure evidence cannot read `:pass` (the agreement
  floor, `evidence/tape-shows-failure?`). A run with zero assertions whose
  tape is clean is `:pass` (the spec/007 §Story-as-test duality — a variant
  with no assertions is vacuously green).

  ## Pure / JVM-testable

  Every fn here is pure data → data: assertion records + projected evidence
  + plan slots in, the unified result out. It requires NOTHING from
  `re-frame.core` / the DOM, so the whole assembly runs under
  `clojure -M:test`. The runner reads the tape + the accumulator and hands
  the vectors here; the assembly never touches the runtime."
  (:require [re-frame.story.play.evidence :as evidence]
            [re-frame.story.requirements  :as requirements]))

;; ===========================================================================
;; STATUS — the four verdicts (spec/017 §Run result)
;; ===========================================================================

(def statuses
  "The four run / assertion / check verdicts (spec/017 §Run result):

  - `:pass`       — every expectation the runner could attempt held;
  - `:fail`       — at least one expectation failed (or the tape shows
                    unconsumed failure evidence);
  - `:cannot-run` — the ONLY unmet expectations were ones the runner could
                    not even attempt (the distinct THIRD status, §`:cannot-run`);
  - `:error`      — a handler / fx / step threw."
  #{:pass :fail :cannot-run :error})

(defn record-status
  "Derive the `:status` for ONE assertion record from its outcome fields.
  Pure data → data. An explicit `:status` already on the record wins (the
  record was minted by a status-aware path); otherwise:

  - `:cannot-run?` true   → `:cannot-run` (the runner could not prove it);
  - `:exception` / `:error` truthy → `:error`;
  - `:passed?` true       → `:pass`;
  - `:passed?` false      → `:fail`;
  - `:passed?` nil        → `:pass` (a non-assertion / vacuous record does
                            not fail the run — the spec/007 duality)."
  [{:keys [status passed? cannot-run? skipped? exception error] :as _record}]
  (cond
    (contains? statuses status) status
    cannot-run?                 :cannot-run
    skipped?                    :cannot-run   ; §`:cannot-run` generalizes the shipping :skipped?
    (or exception error)        :error
    (true? passed?)             :pass
    (false? passed?)            :fail
    :else                       :pass))

;; ===========================================================================
;; ASSERTION RECORD — the `.18` atom shape + a unified `:status`
;; ===========================================================================

(defn assertion-record
  "Normalize a raw assertion accumulator entry (the shape the
  `:rf.assert/*` handlers wrote to `:rf.story/assertions`, plus the
  runtime's exception projections) into the unified assertion record
  (spec/017 §Run result — Assertion record). Pure data → data.

  The accumulator entry already carries `:assertion` / `:payload` /
  `:passed?` / `:expected` / `:actual` / `:reason` / `:dispatch-id` /
  `:source-coord` / `:elapsed-ms` (from `re-frame.story.assertions`'
  `assertion-record`); this stamps the derived `:status` so the run
  aggregation reads ONE field, and renames `:source-coord` → `:source`
  (the spec/017 slot name) while keeping `:source-coord` for back-compat
  readers. A record that already carries a `:status` is left as-is.

  This is the §B5.5 'adapt the existing assertion accumulator ONLY for
  assertion outcomes that are not already in the tape' boundary: the
  assertion verdict is the one slot the tape does NOT carry, so it is the
  one slot this ns adapts rather than projects."
  [raw]
  (let [st (record-status raw)]
    (cond-> (assoc raw :status st)
      (and (contains? raw :source-coord)
           (not (contains? raw :source))) (assoc :source (:source-coord raw)))))

(defn assertion-records
  "Normalize a raw assertion accumulator vector into unified assertion
  records, in accumulator order. Pure data → data."
  [raw-assertions]
  (mapv assertion-record (or raw-assertions [])))

;; ===========================================================================
;; CHECK RECORD — group assertions under their originating check id
;; ===========================================================================
;;
;; A check is a named, reusable assertion pack (spec/017 §Checks). A failed
;; check result MUST show BOTH the check id AND the underlying assertion
;; records. The plan carries the expanded check→assertion-atoms mapping;
;; the run carries the evaluated records. `check-records` pairs them: each
;; check id groups the records its assertion atoms produced (matched by the
;; assertion atom's id + payload), and the check's `:status` aggregates its
;; group via the ONE aggregation rule.

(defn- atom-id
  "The `:rf.assert/*` id at the head of an assertion atom, or nil."
  [a]
  (when (and (vector? a) (pos? (count a)))
    (let [h (first a)] (when (keyword? h) h))))

(defn- atom-payload
  "The payload (args after the id) of an assertion atom."
  [a]
  (when (vector? a) (vec (rest a))))

(defn- record-matches-atom?
  "True iff assertion `record` was produced by assertion `atom-v` — same
  `:rf.assert/*` id AND same payload. Pure data → data. The payload match
  disambiguates two `:rf.assert/path-equals` against different paths within
  one check."
  [record atom-v]
  (and (= (:assertion record) (atom-id atom-v))
       (= (vec (:payload record)) (atom-payload atom-v))))

(defn check-record
  "Build ONE check record (spec/017 §Run result — Check record) for
  `check-id` over its `assertion-atoms` (the atoms the plan expanded the
  check into) against the run's evaluated `records`. Pure data → data.

  The group is every evaluated record matching one of the check's atoms
  (by id + payload); the check `:status` aggregates the group via the ONE
  aggregation rule (`requirements/aggregate-status`). An atom with no
  matching record (the assertion never ran) contributes nothing to the
  group — the run-level aggregation still sees the ungrouped records, so a
  check that did not run does not mask the run verdict.

  Returns `{:check check-id :status … :assertions [record …]}`."
  [check-id assertion-atoms records]
  (let [group (into []
                    (filter (fn [r]
                              (some #(record-matches-atom? r %) assertion-atoms)))
                    records)]
    {:check      check-id
     :status     (requirements/aggregate-status group nil)
     :assertions group}))

(defn check-records
  "Build the run's check records from the plan's `check->atoms`
  (`{check-id [assertion-atom …]}` — the expanded check assertions) and the
  run's evaluated assertion `records`. Pure data → data. Empty when the
  plan declares no checks. The checks are emitted in `check->atoms`
  iteration order; pass an ordered map (or a vector of `[id atoms]` pairs)
  to pin order."
  [check->atoms records]
  (into []
        (map (fn [[check-id atoms]] (check-record check-id atoms records)))
        (or check->atoms [])))

;; ===========================================================================
;; THE UNIFIED RUN RESULT  (spec/017 §Run result)
;; ===========================================================================

(defn run-result
  "Assemble the ONE unified run-result (spec/017 §Run result) from a run's
  evidence + accumulated assertions + plan slots. Pure data → data — the
  single boundary from a settled run to the API-stable result, so Story
  Test mode, CI, `clojure.test`, MCP, and the golden/diff tools cannot
  disagree about what happened.

  `parts` carries:

  - `:epoch-tape`       — the retained `:rf/epoch-record` vector (the
                          evidence source; `.4`'s `project-evidence` reads
                          it). May be empty for a host without the epoch
                          artefact — the evidential slots are then empty.
  - `:assertions`       — the RAW assertion accumulator entries (the
                          `:rf.story/assertions` slot). Normalized here into
                          unified assertion records — the ONE non-tape
                          input (§B5.5).
  - `:script`           — the coerced script-step vector, for the two-level
                          `:narrative` projection (`.4`).
  - `:check->atoms`     — `{check-id [assertion-atom …]}`, the plan's
                          expanded checks, grouped into `:checks`.
  - `:consumed-selectors` — the schema-violation selectors the run's
                          `:rf.assert/schema-error` expectations consumed
                          (excused from the agreement floor, §Schema rule).
                          Defaults to `#{}` (the strict floor).
  - `:unmet`            — the per-requirement `:cannot-run` refusals for
                          expectations the runner could not attempt
                          (`requirements/unmet-assertions` /
                          `unmet-steps`). Folded into the status + surfaced
                          on `:cannot-run`.
  - `:app-db`           — the final (post-run) app-db, redacted upstream.
  - `:variant/id` / `:plan-hash` / `:run-hash` / `:runner` /
    `:required-runner` / `:fidelity` / `:elapsed-ms` — passed through
    verbatim when present (the API-stable identity / timing slots).

  The verdict: `requirements/aggregate-status` over the assertion records +
  the `:unmet` refusals gives the assertion-side status; the agreement
  floor (`evidence/tape-shows-failure?`) escalates a `:pass` to `:fail`
  when the tape carries unconsumed failure evidence — so a run NEVER reads
  green while the tape is red, and the verdict is computed from the
  PROJECTED evidence, not a sibling accumulator."
  [{:keys [epoch-tape assertions script check->atoms consumed-selectors
           unmet app-db]
    :as   parts}]
  (let [tape           (vec (or epoch-tape []))
        evidence-slots (evidence/project-evidence tape {:script script})
        records        (assertion-records assertions)
        checks         (check-records check->atoms records)
        consumed       (or consumed-selectors #{})
        unmet          (vec (or unmet []))
        base-status    (requirements/aggregate-status records unmet)
        tape-red?      (evidence/tape-shows-failure? tape consumed)
        ;; The agreement floor escalates ONLY a would-be pass — a real
        ;; :fail / :error / :cannot-run already outranks it (the floor never
        ;; downgrades a higher verdict).
        status         (if (and (= :pass base-status) tape-red?)
                         :fail
                         base-status)
        identity-slots (select-keys parts [:variant/id :plan-hash :run-hash
                                            :runner :required-runner :fidelity
                                            :elapsed-ms])]
    (cond-> (merge {:status      status
                    :assertions  records
                    :checks      checks
                    :app-db      app-db}
                   evidence-slots
                   identity-slots)
      (seq unmet) (assoc :cannot-run unmet))))

;; ===========================================================================
;; clojure.test / cljs.test BRIDGE PROJECTION  (spec/017 §Unified run result)
;; ===========================================================================
;;
;; `story/is` reports a run-result to clojure.test / cljs.test at
;; per-assertion granularity. The PURE projection here turns a unified
;; result into the vector of test reports `story/is` emits (one per
;; assertion record, plus a run-level report for a tape-floor / cannot-run
;; verdict that no single assertion carries). The impure `is`-emission lives
;; in `re-frame.story` (it threads `clojure.test/do-report` / the cljs
;; reader-conditional); this ns keeps the projection JVM-testable.

(defn assertion->report
  "Project ONE unified assertion record into the clojure.test report map
  shape (`{:type :pass|:fail|:error :message … :expected … :actual …}`).
  Pure data → data. A `:cannot-run` assertion reports `:fail` with a
  refusal message (the runner could not prove it — that is a failed
  expectation for test purposes, never a silent pass)."
  [{:keys [assertion status payload expected actual reason] :as _record}]
  (let [base-msg (str assertion " " (pr-str (or payload [])))]
    (case status
      :pass        {:type :pass :message base-msg}
      :error       {:type :error :message (str base-msg " — " (or reason "errored"))
                    :actual (or actual reason)}
      :cannot-run  {:type :fail
                    :message (str base-msg " — :cannot-run ("
                                  (or reason "runner cannot prove this assertion") ")")
                    :expected expected :actual :rf.story/cannot-run}
      ;; :fail (and any unexpected status)
      {:type :fail
       :message (str base-msg (when reason (str " — " reason)))
       :expected expected :actual actual})))

(defn result->reports
  "Project a unified `result` into the ordered vector of clojure.test
  report maps `story/is` emits — one per assertion record (per-assertion
  granularity, spec/017 §Public execution API), and a trailing run-level
  report when the run verdict is NOT covered by an assertion (a
  tape-floor-driven `:fail`, or a `:cannot-run` / `:error` carried only by
  the run, not a single assertion). Pure data → data.

  A `:pass` run with assertions emits only the per-assertion passes; a
  zero-assertion `:pass` run emits ONE run-level pass so the test sees a
  positive signal (the vacuous-green duality). A run whose verdict is
  worse than any single assertion (the tape floor flipped a green
  assertion set to `:fail`) emits a run-level report carrying the floor
  failure so the test surfaces it."
  [{:keys [status assertions schema-violations cannot-run] :as _result}]
  (let [per-assertion (mapv assertion->report assertions)
        worst         (requirements/aggregate-status assertions cannot-run)
        ;; The run verdict exceeds the assertion-level verdict (the tape
        ;; floor escalated it, or a run-level cannot-run/error) → emit a
        ;; run-level report so the floor failure is not silently dropped.
        run-level
        (cond
          (and (= :fail status) (not= :fail worst))
          [{:type :fail
            :message (str "run :fail — epoch tape shows unconsumed failure evidence"
                          (when (seq schema-violations)
                            (str " (" (count schema-violations) " schema violation(s))")))
            :expected :rf.story/clean-tape
            :actual   (or (seq schema-violations) :rf.story/tape-failure)}]

          (and (= :cannot-run status) (empty? assertions))
          [{:type :fail
            :message "run :cannot-run — the runner could not attempt this plan"
            :expected :rf.story/runnable
            :actual   :rf.story/cannot-run}]

          (and (= :pass status) (empty? per-assertion))
          [{:type :pass
            :message "run :pass — no assertions (vacuously green)"}]

          :else [])]
    (into per-assertion run-level)))

(defn passed?
  "True iff a unified `result`'s `:status` is `:pass`. Pure data → data —
  the one-line predicate `story/is` / the cljs.test adapter pattern checks.
  A `:cannot-run` is NOT a pass (the runner proved nothing)."
  [result]
  (= :pass (:status result)))
