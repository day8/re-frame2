(ns re-frame.story.result
  "The ONE unified run-result — the single shape every Story runner,
  Test mode, CI, `clojure.test`, and MCP consume
  (`tools/story/spec/017-Testing-Story.md` §Run result +
  §Unified run result).

  ## One result, one source of truth

  Spec/017 §Run result: *all runners MUST return the same shape*, and the
  evidential slots are *projections from one retained epoch tape*, not a
  second capture path. Every runner — `story/run`, the per-step runner,
  artifact replay — speaks this ONE result language, so a verdict and the
  assertions slot can never disagree (the \"false GREEN\" class).

  The shape is derived — wherever the tape carries the evidence — from
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
    accumulator; `:consumed-selectors` carries the agreement-floor's
    exactly-consumed schema-violation selector set (the single source of
    truth Test mode reads, never re-derives); `:app-db` / `:run-hash` /
    `:plan-hash` / `:runner` / `:required-runner` / `:fidelity` /
    `:elapsed-ms` round out the shape.
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
  tape is clean is `:pass` (the /spec/007-Stories.md §Story-as-test duality — a variant
  with no assertions is vacuously green).

  ## Pure / JVM-testable

  Every fn here is pure data → data: assertion records + projected evidence
  + plan slots in, the unified result out. It requires NOTHING from
  `re-frame.core` / the DOM, so the whole assembly runs under
  `clojure -M:test`. The runner reads the tape + the accumulator and hands
  the vectors here; the assembly never touches the runtime."
  (:require [malli.core                   :as m]
            [re-frame.story.assertions    :as assertions]
            [re-frame.story.play.evidence :as evidence]
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

;; ===========================================================================
;; THE SCHEMA-BACKED CONTRACT  (API governance freeze)
;; ===========================================================================
;;
;; The unified run-result is a FROZEN, schema-backed public contract: the ONE
;; result language spoken IDENTICALLY across `story/run`, `story/is`,
;; `story/render-variant`, the Story UI Test mode, story-mcp `run-variant` /
;; `read-failures`, and generated run artifacts. A result object moves
;; CLJS test → Story UI → MCP with NO semantic translation — these Malli
;; schemas are the executable statement of that contract (spec/017 §Run
;; result). They are plain Malli forms (`metosin/malli`), survive `:advanced`
;; (plain data), and run on both JVM + CLJS.
;;
;; The verdict is `:status` (an `Status` enum) — there is NO `:passing?`
;; boolean and NO lifecycle-as-verdict: a boolean could not express the
;; distinct `:cannot-run` THIRD outcome, and a
;; lifecycle state (`:ready` / `:error`) is the frame's mount state, not the
;; run's judgement. The accessors are `result-status` / `result-passed?`
;; (re-exported from `re-frame.story`); a green/red bit is DERIVED from
;; `:status`, never stored.
;;
;; The map schemas are deliberately OPEN (`{:closed false}`): the load-bearing
;; contract slots (`:status` + the judgement / evidence slots) are pinned, but
;; a runner / MCP / artifact layer MAY carry additional provenance slots
;; (`:frame`, `:decorators`, `:rendered-hiccup`, `:snapshot`, …) without
;; breaking the contract. What is frozen is the SHARED vocabulary every
;; surface reads, not an exhaustive key list.

(def Status
  "The frozen verdict enum (spec/017 §Run result). The ONE field every
  run / assertion / check carries — `:pass` | `:fail` | `:cannot-run` |
  `:error`. A single verdict enum rather than a `:passing?` boolean,
  which could not express the `:cannot-run` THIRD status."
  (into [:enum] statuses))

(def AssertionRecord
  "Malli schema for ONE evaluated assertion record (spec/017 §Run result —
  Assertion record). `:status` is the frozen verdict; `:passed?` is a
  diagnostic-mirror boolean ONLY (the verdict is
  `:status`). Open map — the `.18` atom fields and source-coords ride
  along."
  [:map {:closed false}
   [:assertion   {:optional true} :keyword]
   [:status      Status]
   [:passed?     {:optional true} [:maybe :boolean]]
   [:payload     {:optional true} [:sequential :any]]
   [:expected    {:optional true} :any]
   [:actual      {:optional true} :any]
   [:reason      {:optional true} [:maybe [:or :keyword :string]]]
   [:cannot-run? {:optional true} :boolean]
   [:source      {:optional true} :any]
   [:elapsed-ms  {:optional true} [:maybe number?]]])

(def CheckRecord
  "Malli schema for ONE check record (spec/017 §Run result — Check record)
  — a named check id grouping the assertion records its assertions
  produced. `:status` aggregates the group via the ONE aggregation rule."
  [:map {:closed false}
   [:check      :keyword]
   [:status     Status]
   [:assertions [:vector AssertionRecord]]])

(def RunResult
  "Malli schema for the ONE unified run-result (spec/017 §Run result) — the
  FROZEN public contract every Story runner, Test mode, CI, `clojure.test`
  / `cljs.test`, and story-mcp consume IDENTICALLY. Open map: the verdict
  + judgement + agreement-floor slots are pinned; the evidential `.4`
  projections and the identity / timing / provenance slots are optional
  (a host without the epoch artefact omits the evidential ones; a bare
  re-read like `read-failures` omits the tape-floor slots).

  The frozen, always-present slots:

  - `:status`             — the verdict (`Status`); the headline every
                            surface reads.
  - `:assertions`         — the unified assertion records.
  - `:checks`             — the named check-record groups.
  - `:consumed-selectors` — the agreement-floor's exactly-consumed
                            schema-violation selector set (the single
                            source of truth Test mode reads, never
                            re-derives). Always present (`#{}` when empty)."
  [:map {:closed false}
   [:status             Status]
   [:assertions         [:vector AssertionRecord]]
   [:checks             [:vector CheckRecord]]
   [:consumed-selectors [:set :any]]
   ;; identity / timing (the API-stable provenance slots) — optional
   [:variant/id      {:optional true} :keyword]
   [:plan-hash       {:optional true} [:maybe :string]]
   [:run-hash        {:optional true} [:maybe :string]]
   [:runner          {:optional true} :any]
   [:required-runner {:optional true} [:maybe [:set :any]]]
   [:fidelity        {:optional true} [:maybe [:set :any]]]
   [:elapsed-ms      {:optional true} [:maybe number?]]
   [:app-db          {:optional true} :any]
   ;; evidential `.4` projections — optional (a host without the epoch
   ;; artefact projects empty / omits these)
   [:epoch-tape        {:optional true} [:sequential :any]]
   [:narrative         {:optional true} [:sequential :any]]
   [:schema-violations {:optional true} [:sequential :any]]
   [:warnings          {:optional true} [:sequential :any]]
   [:effects           {:optional true} [:sequential :any]]
   [:sub-runs          {:optional true} [:sequential :any]]
   [:renders           {:optional true} [:sequential :any]]
   [:reactive-counts   {:optional true} :map]
   ;; the run-level :cannot-run refusals, present iff the runner could not
   ;; attempt some expectation
   [:cannot-run {:optional true} [:sequential :any]]])

(defn valid-run-result?
  "True iff `result` conforms to the frozen `RunResult` contract.
  Pure data → data — the executable check Test mode / CI /
  MCP / the test corpus run to prove a result speaks the ONE language."
  [result]
  (m/validate RunResult result))

(defn explain-run-result
  "Malli explanation of why `result` does NOT conform to the frozen
  `RunResult` contract, or nil when it conforms. Pure data → data — the
  diagnostic companion to `valid-run-result?`."
  [result]
  (m/explain RunResult result))

(defn record-status
  "Derive the `:status` for ONE assertion record from its outcome fields.
  Pure data → data. An explicit `:status` already on the record wins (the
  record was minted by a status-aware path); otherwise:

  - `:cannot-run?` true   → `:cannot-run` (the runner could not prove it);
  - `:exception` / `:error` truthy → `:error`;
  - `:passed?` true       → `:pass`;
  - `:passed?` false      → `:fail`;
  - `:passed?` nil        → `:pass` (a non-assertion / vacuous record does
                            not fail the run — the /spec/007-Stories.md §Story-as-test duality)."
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
  aggregation reads ONE field, and renames the accumulator's
  `:source-coord` → `:source` (the spec/017 slot name) — the unified
  record emits ONLY `:source`. A record that already carries a `:status`
  is left as-is.

  This is the §B5.5 'adapt the existing assertion accumulator ONLY for
  assertion outcomes that are not already in the tape' boundary: the
  assertion verdict is the one slot the tape does NOT carry, so it is the
  one slot this ns adapts rather than projects."
  [raw]
  (let [st (record-status raw)]
    (cond-> (assoc raw :status st)
      (and (contains? raw :source-coord)
           (not (contains? raw :source))) (assoc :source (:source-coord raw))
      (contains? raw :source-coord)       (dissoc :source-coord))))

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
;; SCHEMA-ERROR EXACT CONSUMPTION  (spec/017 §Schema rule)
;; ===========================================================================
;;
;; The §Schema-rule invariant: any emitted
;; `:rf.error/schema-validation-failure` MUST fail the run UNLESS exactly
;; consumed by an expected `:rf.assert/schema-error`, even if recovery /
;; rollback leaves the final app-db acceptable. There is NO
;; `:rf.assert/no-schema-errors` knob — a schema-clean run is the FLOOR
;; (`evidence/tape-shows-failure?`), and these expectations REFINE it.
;;
;; Matching is an EXACT MULTISET pairing (spec/017 §Schema rule steps 1-4):
;;
;;   1. The projected violations (`evidence/schema-violations`, the SINGLE
;;      source — no second accumulator) are keyed by their `:selector`.
;;   2. Each declared `[:rf.assert/schema-error spec]` consumes EXACTLY ONE
;;      matching violation; N declared of a selector consume N. A bare
;;      `[:rf.assert/schema-error]` (the `[:any]` wildcard) consumes any one
;;      still-unconsumed violation.
;;   3. A declared expectation that matches NO (remaining) violation FAILS
;;      (a failed assertion record — the expected violation never happened).
;;   4. Any violation left UNCONSUMED fails the run (the agreement floor,
;;      since its selector is NOT in `:consumed-selectors`).
;;
;; The walk pairs concrete (non-wildcard) expectations FIRST, then wildcards
;; against whatever remains — so a wildcard never starves a concrete
;; expectation of its specific match. This keeps the multiset semantics
;; deterministic regardless of declaration order.

(defn- pair-expectations
  "Pair `expectations` (the projected `:rf.assert/schema-error`
  expectation records, `assertions/schema-error-expectation`) against
  `violations` (the projected `evidence/schema-violations`) by exact
  multiset selector match. Pure data → data.

  Returns `{:matched [{:expectation … :violation …} …]
            :unmatched [expectation …]      ; expected, no violation
            :unconsumed [violation …]}`     ; emitted, not expected

  Concrete (non-`[:any]`) expectations consume a same-selector violation
  first; `[:any]` wildcards then consume any remaining violation. A
  consumed violation is removed from the pool so N expectations of a
  selector consume exactly N violations (multiset, not set)."
  [expectations violations]
  (let [wildcard? (fn [e] (= [:any] (:selector e)))
        concrete  (remove wildcard? expectations)
        wildcards (filter wildcard? expectations)
        ;; pool of still-unconsumed violations, in tape order
        step      (fn [{:keys [pool matched unmatched]} exp pred]
                    (let [[before [hit & after]] (split-with (complement pred) pool)]
                      (if hit
                        {:pool      (vec (concat before after))
                         :matched   (conj matched {:expectation exp :violation hit})
                         :unmatched unmatched}
                        {:pool      pool
                         :matched   matched
                         :unmatched (conj unmatched exp)})))
        ;; concrete: match by identical selector
        after-conc (reduce (fn [acc exp]
                             (step acc exp #(= (:selector exp) (:selector %))))
                           {:pool (vec violations) :matched [] :unmatched []}
                           concrete)
        ;; wildcards: match any remaining violation
        after-wild (reduce (fn [acc exp] (step acc exp (constantly true)))
                           after-conc
                           wildcards)]
    {:matched    (:matched after-wild)
     :unmatched  (:unmatched after-wild)
     :unconsumed (:pool after-wild)}))

(defn schema-error-record
  "Mint the unified assertion record for ONE declared
  `:rf.assert/schema-error` expectation (spec/017 §Schema rule, §Run
  result — Assertion record). Pure data → data.

  `matched-violation` is the violation the expectation consumed, or nil
  when it matched none. A matched expectation is `:pass` (the expected
  violation happened and was exactly consumed); an unmatched expectation is
  `:fail` (the expected violation never happened — a MISSING expected
  schema violation fails the run, §Schema rule step 3). The record carries
  the declared spec as `:expected` and the matched violation's selector as
  `:actual` so a failing expectation reads diagnostically."
  [{:keys [atom spec selector] :as _expectation} matched-violation]
  (let [passed? (some? matched-violation)]
    {:assertion assertions/id-schema-error
     :payload   (vec (rest atom))
     :passed?   passed?
     :status    (if passed? :pass :fail)
     :expected  spec
     :actual    (when matched-violation (:selector matched-violation))
     :reason    (if passed?
                  (str "expected schema violation " (pr-str selector)
                       " was emitted and exactly consumed")
                  (str "expected schema violation " (pr-str selector)
                       " but no matching :rf.error/schema-validation-failure "
                       "was emitted during the run"))}))

(defn match-schema-expectations
  "EXACT-consumption match of declared `:rf.assert/schema-error`
  expectations against the tape's projected schema violations (spec/017
  §Schema rule). Pure data → data — the boundary the
  `run-result` assembly consumes.

  `schema-expectations` is the vector of declared `:rf.assert/schema-error`
  ATOMS (`[:rf.assert/schema-error spec]`); `epoch-tape` is the retained
  `:rf/epoch-record` vector (the SINGLE violation source — projected via
  `evidence/schema-violations`, never a second accumulator). Returns:

      {:records             [assertion-record …]  ; one per declared expectation
       :consumed-selectors  #{selector …}         ; selectors EXACTLY consumed
       :unmatched           [expectation …]       ; expected, never emitted
       :unconsumed          [violation …]}         ; emitted, not expected

  `:consumed-selectors` is the set the agreement floor
  (`evidence/tape-shows-failure?`) subtracts so an EXACTLY-consumed
  violation does not trip the floor. A violation left unconsumed keeps its
  selector OUT of the set, so the floor still fails the run on it (§Schema
  rule step 4). A `:fail` record for each unmatched expectation fails the
  run on a MISSING expected violation (step 3).

  The 2-arity projects the violations from the raw `epoch-tape`; the
  `[schema-expectations violations :as :projected]` 3-arity takes the
  ALREADY-PROJECTED `evidence/schema-violations` vector (the `run-result`
  assembly's `evidence-slots`) so the assembly does not walk the tape for
  the same violations a second time — one tape, one projection."
  ([schema-expectations epoch-tape]
   (match-schema-expectations schema-expectations
                              (evidence/schema-violations epoch-tape)
                              :projected))
  ([schema-expectations violations _projected]
   (let [exps        (mapv assertions/schema-error-expectation
                           (or schema-expectations []))
         {:keys [matched unmatched unconsumed]} (pair-expectations exps violations)
         records     (into (mapv (fn [{:keys [expectation violation]}]
                                   (schema-error-record expectation violation))
                                 matched)
                           (mapv (fn [exp] (schema-error-record exp nil))
                                 unmatched))]
     {:records            records
      :consumed-selectors (into #{} (map (comp :selector :violation)) matched)
      :unmatched          unmatched
      :unconsumed         unconsumed})))

;; ===========================================================================
;; CAUSAL / CASCADE EXPECTATIONS  (spec/017 §Causal and cascade
;; assertions)
;; ===========================================================================
;;
;; `:rf.assert/caused` and `:rf.assert/no-cascade-rerender` PROJECT a
;; cause→effect relationship from the SAME reactive evidence the tape already
;; carries — the `:rf.sub/run` / `:rf.view/rendered` rows stamped with the
;; dispatching cascade's `:cause-event-id` (Spec 009), surfaced in the
;; `evidence/reactive-counts` `:by-cause` projection. They are
;; tape-evaluated like `:rf.assert/schema-error`: NOT dispatched into the
;; frame, NOT a parallel accumulator — the tape is the source of truth
;; (spec/017 §Risks — "evidence projections drift").
;;
;; The measured COUNT for a causal expectation is read off `:reactive-counts`:
;;
;;   surface [:any]      → the cause's total (sub-recomputes + view-renders)
;;                         from `(:by-cause rc)`;
;;   surface [:sub sid]  → the cause's sub-recomputes whose sub-id is `sid`;
;;   surface [:view vid] → the cause's view-renders whose view-id is `vid`.
;;
;; A `[:sub …]` / `[:view …]` surface needs per-(cause × surface) counts that
;; `:by-cause` (cause-keyed totals) does not carry, so the matcher re-projects
;; them from the already-projected `:sub-runs` / `:renders` rows
;; (`evidence/sub-runs` / `renders`) — the SAME rows `:by-cause` counts,
;; filtered to the named cause AND surface. No new evidence stream.

(defn- causal-count
  "The measured effect COUNT for a causal `expectation` against the run's
  projected `evidence` (the `evidence/project-evidence` output). Pure data →
  data. Reads ONLY the already-projected reactive rows — `:reactive-counts`
  for the `[:any]` cause total, the `:sub-runs` / `:renders` rows for a named
  `:sub` / `:view` surface. Returns 0 when the cause produced no matching
  effect."
  [{:keys [event surface] :as _expectation} evidence]
  (let [rc (:reactive-counts evidence)]
    (case (first surface)
      :any  (let [{:keys [sub-recomputes view-renders]
                   :or   {sub-recomputes 0 view-renders 0}}
                  (get (:by-cause rc) event)]
              (+ sub-recomputes view-renders))
      :sub  (let [sid (second surface)]
              (count (filter (fn [r] (and (= event (:cause-event-id r))
                                          (= sid (:sub-id r))))
                             (:sub-runs evidence))))
      :view (let [vid (second surface)]
              (count (filter (fn [r] (and (= event (:cause-event-id r))
                                          (= vid (or (:view-id r)
                                                     (when (vector? (:render-key r))
                                                       (first (:render-key r)))))))
                             (:renders evidence))))
      0)))

(defn- causal-in-bounds?
  "True iff `n` satisfies the expectation's `[:min :max]` count bounds (an
  absent `:max` is unbounded above). Pure data → data."
  [{:keys [min max] :as _expectation} n]
  (and (>= n (or min 0))
       (or (nil? max) (<= n max))))

(defn causal-record
  "Mint the unified assertion record for ONE causal expectation (spec/017
  §Causal and cascade assertions, §Run result — Assertion record). Pure
  data → data.

  `n` is the measured effect count (`causal-count`). `reactive?` is whether
  the run carried ANY reactive evidence (a `:reactive-counts` slot). The
  verdict, in precedence:

  - a degenerate atom that names no `:event` → `:fail` (it asserts nothing
    measurable);
  - NO reactive evidence → `:cannot-run` — the run never exercised the
    reactive substrate, so the cause→effect relationship could not be
    measured (fail closed; NEVER a silent pass against an empty projection,
    §Causal and cascade assertions);
  - else the expectation passes iff `n` is within the `[:min :max]` bounds.

  The record carries the declared spec as `:expected` and the measured count
  + bounds as `:actual` so a failing / refused expectation reads
  diagnostically."
  [{:keys [atom id spec event surface min max] :as expectation} n reactive?]
  (let [degenerate? (nil? event)
        in-bounds?  (causal-in-bounds? expectation n)
        status      (cond
                      degenerate?            :fail
                      (not reactive?)        :cannot-run
                      in-bounds?             :pass
                      :else                  :fail)]
    {:assertion   id
     :payload     (vec (rest atom))
     :passed?     (= :pass status)
     :status      status
     :cannot-run? (= :cannot-run status)
     :expected    spec
     :actual      {:event event :surface surface :count n :min min :max max}
     :reason      (cond
                    degenerate?
                    (str (name id) " names no :event — a causal assertion MUST "
                         "name the cause event-id it projects")
                    (not reactive?)
                    (str (name id) " requires reactive evidence, but the run "
                         "carried no :rf.sub/run / :rf.view/rendered rows — "
                         ":cannot-run (run under the :cljs-reactive runner)")
                    in-bounds?
                    (str "event " (pr-str event) " caused " n " "
                         (pr-str surface) " effect(s), within bounds "
                         "[" min " " (or max "∞") "]")
                    :else
                    (str "event " (pr-str event) " caused " n " "
                         (pr-str surface) " effect(s), outside expected bounds "
                         "[" min " " (or max "∞") "]"))}))

(defn match-causal-expectations
  "Evaluate declared causal / cascade expectations against the run's
  projected reactive `evidence` (spec/017 §Causal and cascade
  assertions). Pure data → data — the boundary the `run-result` assembly
  consumes.

  `causal-atoms` is the vector of declared `[:rf.assert/caused …]` /
  `[:rf.assert/no-cascade-rerender …]` atoms; `evidence` is the
  `evidence/project-evidence` output (the SINGLE reactive source — the
  `:reactive-counts` / `:sub-runs` / `:renders` projections, never a second
  accumulator). Returns `{:records [assertion-record …]}`, one per declared
  expectation.

  Note these are PURE expectations, NOT agreement-floor signals: an
  over-render is a `:fail` of a `:rf.assert/no-cascade-rerender` declaration,
  not a tape-floor failure on its own (`evidence/tape-shows-failure?` does not
  read reactive counts). A run with NO reactive rows (no `:reactive-counts`
  slot) resolves each causal expectation to `:cannot-run` — fail closed, never
  a silent pass against an empty projection (this is the matcher's own
  fail-closed guard; it mirrors the post-run `:reactive-counts` evidence-slot
  check (`requirements/validate-evidence`) so the verdict is correct even
  where that check is not separately invoked)."
  [causal-atoms evidence]
  (let [reactive? (contains? evidence :reactive-counts)]
    {:records (mapv (fn [a]
                      (let [exp (assertions/causal-expectation a)]
                        (causal-record exp (causal-count exp evidence) reactive?)))
                    (or causal-atoms []))}))

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
  - `:schema-expectations` — the vector of declared `:rf.assert/schema-error`
                          atoms (`[:rf.assert/schema-error spec]`, from the
                          plan's `[:expect :assertions]` + in-script
                          `[:assert …]` checkpoints). EXACT-matched against
                          the tape's projected violations (§Schema rule):
                          each consumes one matching violation, an
                          unmatched expectation yields a `:fail` record
                          (a MISSING expected violation fails the run), and
                          exactly-consumed selectors are excused from the
                          agreement floor. The minted schema-error records
                          are appended to `:assertions`.
  - `:causal-expectations` — the vector of declared `:rf.assert/caused` /
                          `:rf.assert/no-cascade-rerender` atoms (from the
                          plan's `[:expect :assertions]` + checks + in-script
                          `[:assert …]` checkpoints). Tape-evaluated against
                          the projected `:reactive-counts` `:by-cause` /
                          `:sub-runs` / `:renders` projection (§Causal and
                          cascade assertions) — NOT dispatched,
                          NOT a parallel accumulator. The minted records are
                          appended to `:assertions`. These are PURE
                          expectations, not floor signals (an over-render is a
                          `:fail` of the declaration, not a tape-floor
                          failure); a run with no reactive rows is caught
                          upstream by the `:reactive-counts` fail-closed
                          evidence-slot check (`:cannot-run`).
  - `:consumed-selectors` — additional schema-violation selectors to excuse
                          from the agreement floor (§Schema rule), unioned
                          with whatever `:schema-expectations` consumed. A
                          pure escape hatch for callers that pre-compute the
                          match; defaults to `#{}` (the strict floor). The
                          UNION (the input set plus the schema-expectation
                          consumption) is surfaced verbatim on the result as
                          `:consumed-selectors` — the single source of truth
                          for \"which violations were exactly consumed\", so
                          Test mode reads it rather than re-deriving the set.
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
           schema-expectations causal-expectations unmet app-db attribution]
    :as   parts}]
  (let [tape           (vec (or epoch-tape []))
        ;; `:attribution` (the runner / replay-recorded
        ;; per-dispatch-step settle boundaries) lights up EXACT narrative
        ;; attribution. Absent → EVEN fallback, unchanged. The stamp rides
        ;; only the narrative projection; the `:epoch-tape` slot stays raw
        ;; and the stamp is a `:rf.story/*` key the determinism projection
        ;; strips, so the run-hash is unaffected.
        evidence-slots (evidence/project-evidence
                         tape {:script script :attribution attribution})
        ;; The tape is projected ONCE (`project-evidence` above); the
        ;; schema-violation + effect vectors it already produced are threaded
        ;; into the schema match and the agreement floor below rather than
        ;; re-projected from the raw tape. This reinforces the ns's stated
        ;; "one tape, one projection, single source of truth" invariant — the
        ;; floor and the schema matcher read the SAME projected evidence the
        ;; result's slots carry, never a second derivation.
        violations     (:schema-violations evidence-slots)
        ;; EXACT schema-error consumption (§Schema rule): pair
        ;; each declared `:rf.assert/schema-error` against the tape's
        ;; projected violations. The minted records (a `:pass` for each
        ;; matched expectation, a `:fail` for each unmatched — a MISSING
        ;; expected violation FAILS the run) join the assertion records; the
        ;; exactly-consumed selectors excuse those violations from the floor,
        ;; while an UNCONSUMED violation keeps its selector OUT of the set so
        ;; the floor still fails the run on it.
        schema-match   (match-schema-expectations schema-expectations violations :projected)
        ;; Causal / cascade expectations (§Causal and cascade
        ;; assertions): project each declared `:rf.assert/caused` /
        ;; `:rf.assert/no-cascade-rerender` against the SAME reactive
        ;; evidence the tape already carries (`:reactive-counts` `:by-cause`,
        ;; `:sub-runs`, `:renders`). Pure expectations, NOT floor signals —
        ;; the minted records join `:assertions` and the verdict reads them
        ;; through `aggregate-status`. A run with no reactive rows is caught
        ;; upstream by the `:reactive-counts` fail-closed evidence-slot check.
        causal-match   (match-causal-expectations causal-expectations evidence-slots)
        records        (-> (assertion-records assertions)
                           (into (:records schema-match))
                           (into (:records causal-match)))
        checks         (check-records check->atoms records)
        consumed       (into (or consumed-selectors #{})
                             (:consumed-selectors schema-match))
        unmet          (vec (or unmet []))
        base-status    (requirements/aggregate-status records unmet)
        ;; Agreement floor over the SAME projected evidence — the violations
        ;; + effects `project-evidence` already derived, not a re-walk of the
        ;; raw tape (the tape is still needed for the per-epoch `:outcome`
        ;; signal). One tape, one projection.
        ;;
        ;; The schema-violation floor signal is the matcher's MULTISET
        ;; `:unconsumed` (the EXACT pairing: N expectations of a selector
        ;; consume exactly N same-selector violations) — NOT a set-subtraction
        ;; over `consumed-selectors`. A set collapses duplicate selectors, so
        ;; M<N expectations of a selector with N violations would falsely
        ;; excuse ALL N — a partially-consumed schema violation reading green
        ;; (false-green). Threading the matcher's already-correct
        ;; `:unconsumed` (pinned by result_test, pair-expectations) reuses the
        ;; verified pairing rather than re-deriving, so the floor trips on the
        ;; (N−M) genuinely-unconsumed violations.
        ;;
        ;; The caller-supplied `consumed-selectors` escape hatch still excuses
        ;; its selectors from the floor: those are pre-computed excuses that
        ;; never went through the matcher, so they are subtracted (set-keyed,
        ;; as documented) from the matcher's multiset `:unconsumed`. The UNION
        ;; surfaced as the result's `:consumed-selectors` is unchanged.
        unconsumed     (cond->> (:unconsumed schema-match)
                         (seq consumed-selectors)
                         (remove #(contains? consumed-selectors (:selector %))))
        tape-red?      (evidence/evidence-shows-failure?
                         tape unconsumed (:effects evidence-slots) nil :unconsumed)
        ;; The agreement floor escalates ONLY a would-be pass — a real
        ;; :fail / :error / :cannot-run already outranks it (the floor never
        ;; downgrades a higher verdict).
        status         (if (and (= :pass base-status) tape-red?)
                         :fail
                         base-status)
        identity-slots (select-keys parts [:variant/id :plan-hash :run-hash
                                            :runner :required-runner :fidelity
                                            :elapsed-ms])]
    (cond-> (merge {:status             status
                    :assertions         records
                    :checks             checks
                    ;; The agreement-floor consumed-selector set is SURFACED on
                    ;; the result (it is already computed above for the floor)
                    ;; rather than discarded — `:consumed-selectors` is the
                    ;; single source of truth for "which schema violations were
                    ;; exactly consumed", so downstream consumers (Test mode's
                    ;; `schema-rows`) READ it instead of re-deriving the set
                    ;; from each `:rf.assert/schema-error :pass` record's
                    ;; `:actual`. Always present (an empty `#{}` when nothing
                    ;; was consumed) so the read needs no presence guard.
                    :consumed-selectors consumed
                    :app-db             app-db}
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
  failure so the test surfaces it.

  A `:cannot-run` verdict driven by a RUN-LEVEL refusal (`:cannot-run` on
  the result — a `requirement-refusal` the runner could not even attempt,
  e.g. `:rf.assert/visual-snapshot` under `:headless`) never lands in
  `:assertions` — it is a run-level slot, not a per-assertion record — so
  gating the run-level report on `(empty? assertions)` let a run with
  >= 1 PASSING assertion alongside the refusal fall through every branch
  and read false-GREEN (rf2-l3lyal: the refusal never reached
  `clojure.test`). The gate is instead 'no per-assertion report already
  conveys the refusal' — `(not-any? #(= :fail (:type %)) per-assertion)` —
  so a mixed run (an unrelated passing assertion + a run-level refusal)
  still gets the run-level `:cannot-run` report."
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

          (and (= :cannot-run status)
               (not-any? #(= :fail (:type %)) per-assertion))
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
