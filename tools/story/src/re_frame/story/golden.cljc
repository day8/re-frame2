(ns re-frame.story.golden
  "Golden slices — curated canonicalized run/epoch regression artifacts
  (NewTestStory rf2-5x1wt.32, spec/017-Testing-Story.md §Golden slices).
  The deferred P1.5 surface, unblocked now that canonicalization has been
  proven by the determinism gate (rf2-5x1wt.8) + the semantic diff
  (rf2-5x1wt.9).

  ## What a golden slice is

  A golden slice is a CURATED regression baseline: the
  `re-frame.story.fingerprint/canonicalize`d projection of a run's
  BEHAVIOURAL surface, captured once and compared against on every later
  run. It answers ONE question — does this run still behave the way the
  curated baseline says it should?

      capture: golden  = canonicalize(behavioural-slice(run))
      compare: match?  = (= golden  canonicalize(behavioural-slice(new-run)))

  It is NOT a Story variant (no curation lineage, no navigation slot) and
  NOT a run artifact (it carries no replayable `:event-program`). It is the
  THIRD artifact below Story — a frozen canonical expectation. It does NOT
  add a slot to the run-result schema (`re-frame.story.result`); it freezes
  the canonicalized SLICE of an existing result.

  ## Why canonicalize, not store the raw run

  A raw run carries per-RUN noise a fresh-frame replay restamps every time:
  the process-global epoch / dispatch / trace-id counters, the
  `:rf.test.replay/*` frame id, the wall-clock `:committed-at` / `:time` /
  `:elapsed-ms`. Storing a raw run as a golden would make EVERY rerun a
  false mismatch. So the golden is the `canonicalize`d slice — the same
  primitive the determinism gate compares N replays through and the
  semantic diff projects before comparing — so a golden mismatch means a
  SEMANTIC difference (app-db, effect, assertion verdict, schema failure,
  trace spine), never volatile drift. This is exactly why the bead was
  deferred until `.3`/`.8`'s `canonicalize` was proven: the golden reuses
  that one strip path rather than inventing a second one.

  ## The behavioural slice

  The slice frozen into a golden is `fingerprint/run-hash-input-keys` — the
  behavioural surface (`:status`, final `:app-db`, the `:epoch-tape`, the
  `:assertions` / `:checks` verdicts, the projected `:effects` /
  `:schema-violations` / `:warnings`, and the resolved `:sub-overrides` /
  `:fidelity`). This is the SAME slice `run-hash` hashes and the
  determinism gate (`re-frame.story.determinism/compare-runs`) and the
  semantic diff (`re-frame.story.diff/diff-runs`'s `:same?` judgement)
  compare — so a golden match, a determinism `:deterministic`, and a diff
  `{:same? true}` are the one judgement under three names. The pure
  provenance a run also carries (`:frame` replay id, `:run-artifact`
  back-link, `:replay-steps`) is excluded for exactly the reason `run-hash`
  excludes it: it legitimately differs per run.

  ## Capture / compare contract

  - `capture-golden` — freeze a run-result (or a `:rf.test/run-artifact`,
    or a normalized plan, via `->run-result`) into a `:rf.test/golden`
    slice. Pure when handed a run-result; impure (replays into a fresh
    frame) when handed an artifact / plan.
  - `golden-match?` — true iff a new run canonicalizes `=` to the golden's
    frozen slice. The fast path checks the cheap `:run-hash` first, then
    confirms with canonical equality (a hash collision can never report a
    false GREEN — equality is the authority).
  - `compare-golden` — the READABLE report. On match `{:match? true …}`; on
    mismatch it DELEGATES to `re-frame.story.diff/diff-runs` (NOT a
    reinvented diff) to localise WHERE the run parted from the golden, so a
    one-key app-db drift reads as a one-entry `:app-db` facet.

  ## Pure / JVM-testable

  The slice + canonical capture + the match / report logic
  (`behavioural-slice`, `slice-canonical`, `make-golden`, `golden?`,
  `golden-match?`, `compare-golden`) are pure data → data: a run-result in,
  a golden / verdict out — so they run under `clojure -M:test` with no
  runtime. The only impurity is `->run-result`, which (for an artifact /
  plan input) replays into a fresh frame via `.7`'s `replay-run-artifact`.

  This ns is bundle-isolated tooling; it `:require`s ONLY the pure
  fingerprint / diff modules + the artifact replay seam (which itself uses
  the late-bound `rf/epoch-history` facade), so it introduces NO hard
  `:require` of a test-only dep into the production Story path."
  (:require [re-frame.story.artifact    :as artifact]
            [re-frame.story.diff        :as diff]
            [re-frame.story.fingerprint :as fingerprint]))

;; ===========================================================================
;; THE :rf.test/golden SLICE
;; ===========================================================================

(def golden-kind
  "The `:golden/kind` tag every golden slice carries (spec/017 §Golden
  slices). A distinct value so a consumer tells a golden slice apart from a
  run artifact (`:rf.test/run-artifact`), a normalized plan, or a curated
  variant body."
  :rf.test/golden)

(defn behavioural-slice
  "The behavioural surface of a `run`-result — `select-keys` over
  `fingerprint/run-hash-input-keys`. Pure data → data.

  This is the SAME slice `run-hash` hashes and the determinism gate +
  semantic diff compare, so a golden freezes exactly the surface those two
  judge. The pure provenance a run also carries (`:frame` replay id,
  `:run-artifact` back-link, `:replay-steps`) is excluded — it legitimately
  differs per run and would make a golden brittle."
  [run]
  (select-keys run fingerprint/run-hash-input-keys))

(defn slice-canonical
  "The `canonicalize`d behavioural slice of a `run` — the frozen value a
  golden stores and `golden-match?` compares against. Pure data → data.

  `canonicalize` strips the per-run stamps (epoch / dispatch / trace ids,
  frame id, timestamps — recursively, including inside the `:epoch-tape`
  beats) and imposes a total ordering, so two semantically-equal runs yield
  the SAME canonical slice. This is THE equality the golden contract rests
  on."
  [run]
  (fingerprint/canonicalize (behavioural-slice run)))

(defn make-golden
  "Construct a `:rf.test/golden` slice from a `run`-result. Pure data →
  data — no runtime, JVM-runnable.

  The slice freezes the `canonicalize`d behavioural surface (`:canonical`)
  plus the cheap `:run-hash` discriminator and the `:run-hash-input-keys`
  the slice was taken over (so a future slice-key change is detectable, not
  silent).

  `opts` (optional):

  - `:meta` — curation provenance (`:variant/id` / `:doc` / `:created-at` /
    `:source`) stamped under `:golden/meta`. It is NEVER part of the
    compared `:canonical`, so re-curating a golden's doc never perturbs the
    regression baseline.
  - `:keep-run-result` — when truthy, retain the source `run`-result's
    behavioural slice under `:run-result`. The lossless `:canonical` form
    flattens maps into `[k v …]` vectors, so it cannot drive the readable
    `compare-golden` diff (which reads named slots — `:app-db`, `:effects`,
    …); retaining the slice lets a mismatch DELEGATE to
    `re-frame.story.diff/diff-runs` for a localised, readable report. It is
    the behavioural SLICE (not the whole result), so it carries no extra
    provenance and is itself canonicalize-comparable.

  Shape:

      {:golden/kind :rf.test/golden
       :canonical   <canonicalize(behavioural-slice(run))>
       :run-hash    <run-hash(run)>            ; cheap pre-check
       :slice-keys  [k …]                      ; the frozen surface
       :golden/meta {…}                        ; optional curation provenance
       :run-result  {…}}                       ; optional, for the readable diff"
  ([run] (make-golden run nil))
  ([run {:keys [meta keep-run-result] :as _opts}]
   (cond-> {:golden/kind golden-kind
            :canonical   (slice-canonical run)
            :run-hash    (fingerprint/run-hash run)
            :slice-keys  fingerprint/run-hash-input-keys}
     (seq meta)      (assoc :golden/meta meta)
     keep-run-result (assoc :run-result (behavioural-slice run)))))

(defn golden?
  "True iff `x` is a `:rf.test/golden` slice — the `:golden/kind` tag plus a
  frozen `:canonical` value. Pure data → data."
  [x]
  (boolean
    (and (map? x)
         (= golden-kind (:golden/kind x))
         (contains? x :canonical))))

;; ===========================================================================
;; CAPTURE  (run-result → golden; artifact / plan → fresh-frame replay)
;; ===========================================================================

(defn- ->run-result
  "Coerce a capture/compare `target` into a run-result. A run-result
  (carrying a `:status`) is used directly — the PURE path. A
  `:rf.test/run-artifact` (or a normalized plan, via the determinism gate's
  `->artifact` fold inside `replay-run-artifact`'s caller) is replayed into
  a FRESH frame via `re-frame.story.artifact/replay-run-artifact` (the
  IMPURE path), threading `opts` (`:frame` / `:hooks` / `:frame-config`).

  Replaying the artifact means a golden captured from an artifact freezes
  exactly the FRESH-frame run the determinism gate + semantic diff would
  produce — so capture-from-artifact and capture-from-result agree."
  [target opts]
  (if (artifact/run-artifact? target)
    (artifact/replay-run-artifact target opts)
    target))

(defn capture-golden
  "Capture a `:rf.test/golden` slice from `target` (spec/017 §Golden
  slices). The curated-baseline capture path.

  `target` is one of:

  - a run-result (the shared §Run result shape, carrying `:status`) — used
    directly, the PURE path (`clojure -M:test` with no runtime);
  - a `:rf.test/run-artifact` — REPLAYED into a fresh frame via
    `replay-run-artifact` to obtain a run-result first (the impure path);
    so a golden frozen from an artifact captures the fresh-frame run.

  `opts` (all optional):

  - `:meta`            — curation provenance (`:variant/id` / `:doc` /
                         `:created-at` / `:source`) stamped under
                         `:golden/meta`; never part of the compared
                         `:canonical`.
  - `:keep-run-result` — retain the captured run's behavioural slice under
                         `:run-result` so a later `compare-golden` mismatch
                         can produce a readable, delegated diff
                         (see `make-golden`).
  - `:frame` / `:hooks` / `:frame-config` — threaded to
                         `replay-run-artifact` for the artifact path.

  Returns the `:rf.test/golden` slice (see `make-golden`)."
  ([target] (capture-golden target nil))
  ([target {:keys [meta keep-run-result] :as opts}]
   (make-golden (->run-result target (dissoc opts :meta :keep-run-result))
                {:meta meta :keep-run-result keep-run-result})))

;; ===========================================================================
;; COMPARE  (canonical equality + the readable, delegated report)
;; ===========================================================================

(defn golden-match?
  "True iff `run` matches the `golden` slice — the run's canonicalized
  behavioural slice is `=` to the golden's frozen `:canonical` (spec/017
  §Golden slices). Pure when `run` is a run-result.

  Two-stage to avoid a false GREEN: the cheap `:run-hash` is checked first
  (mismatched hash ⇒ definitely different, short-circuit), then canonical
  equality is the AUTHORITY (a hash collision can never report a match
  because the canonical values still differ). The match is robust to
  per-run noise — frame ids, timestamps, epoch / dispatch / trace ids do
  NOT cause a false mismatch because `canonicalize` strips them on both
  sides — and sensitive to a real semantic difference (app-db, effect,
  assertion verdict, schema failure, trace spine), which perturbs the
  canonical value.

  `run` MAY be a run-result (pure) or a `:rf.test/run-artifact` (replayed
  into a fresh frame first — pass `opts` for `:frame` / `:hooks` /
  `:frame-config`)."
  ([golden run] (golden-match? golden run nil))
  ([golden run opts]
   (let [result (->run-result run opts)]
     (and (= (:run-hash golden) (fingerprint/run-hash result))
          (= (:canonical golden) (slice-canonical result))))))

(defn compare-golden
  "Compare a `run` against the `golden` slice and return a READABLE report
  (spec/017 §Golden slices). The curated-baseline compare path.

  On MATCH: `{:match? true :run-hash <hash>}`.

  On MISMATCH: `{:match? false :run-hash <new> :golden-run-hash <frozen>
  :diff <readable-diff>}` — the diff DELEGATES to
  `re-frame.story.diff/diff-runs` (the §Semantic-diff facet machinery, NOT
  a reinvented diff) so the report localises WHERE the run parted from the
  baseline: a one-key app-db drift reads as a one-entry `:app-db` facet, an
  effect-only change as an `:effects` facet, a status flip as `:status`,
  and so on (`:facets #{…}` names them up front).

  The diff is taken between the GOLDEN'S source run-result and the new run.
  The golden's `:canonical` form re-orders maps into sorted `[k v …]`
  vectors, which destroys the named-slot access (`:app-db`, `:effects`, …)
  the diff facets read — so the delegated diff needs the golden's source
  run-SLICE, kept under `:run-result` when the golden was captured with
  `:keep-run-result true` (or supplied here as `:golden-run-result`). Absent
  it, the report still states the mismatch FACT (both run-hashes) but marks
  `:diff :unavailable-no-run-result` — capture with `:keep-run-result true`
  when a readable mismatch diff is wanted.

  `opts` (all optional): `:golden-run-result` — the run-result the golden
  was captured from (for the delegated diff); `:frame` / `:hooks` /
  `:frame-config` — threaded to `replay-run-artifact` when `run` is an
  artifact."
  ([golden run] (compare-golden golden run nil))
  ([golden run {:keys [golden-run-result] :as opts}]
   (let [result    (->run-result run (dissoc opts :golden-run-result))
         match?    (and (= (:run-hash golden) (fingerprint/run-hash result))
                        (= (:canonical golden) (slice-canonical result)))
         base-run  (or golden-run-result (:run-result golden))]
     (if match?
       {:match? true :run-hash (fingerprint/run-hash result)}
       (cond-> {:match?          false
                :run-hash        (fingerprint/run-hash result)
                :golden-run-hash (:run-hash golden)}
         base-run       (assoc :diff (diff/diff-runs base-run result))
         (not base-run) (assoc :diff :unavailable-no-run-result))))))
