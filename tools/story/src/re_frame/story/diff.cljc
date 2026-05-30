(ns re-frame.story.diff
  "Semantic diff over canonical run artifacts — `diff-run-artifacts`
  (NewTestStory rf2-5x1wt.9, spec/017-Testing-Story.md §Semantic diff). It
  answers ONE question: how do two runs differ in BEHAVIOUR, with the
  per-run noise (frame ids, timestamps, dispatch / epoch / trace ids)
  removed first?

  ## Canonicalize first, then diff

  A semantic diff that walked the raw run-results would drown in volatile
  noise: a fresh-frame replay (`re-frame.story.artifact/replay-run-artifact`,
  spec §Run artifact and replay) restarts the process-global epoch /
  dispatch / trace-id counters and allocates a new `:rf.test.replay/*` frame
  id, so two semantically-EQUAL runs differ in dozens of stamps. So both
  inputs are projected through `re-frame.story.fingerprint/canonicalize`
  (rf2-5x1wt.3, the determinism strip rf2-5x1wt.8) BEFORE any facet is
  compared. What survives the projection is exactly the behavioural surface
  the determinism gate compares — so a diff that finds NO facets is the same
  judgement `assert-deterministic` renders `:deterministic`, and a diff that
  finds facets explains a `:non-deterministic` divergence in readable terms.

  ## What the diff covers (spec §Semantic diff)

  Each facet is projected from the canonical run-result and compared
  independently, so a diff localises WHERE two runs parted. `facet-fns` is
  the authoritative ordered enumeration; it ships these ten:

  - `:status`            — the top-level run status, when it differs;
  - `:app-db`            — a readable key-path delta of the final app-db
                           (`:added` / `:removed` / `:changed` paths, each a
                           `{:path … :baseline … :current …}`-style entry);
  - `:assertions`        — terminal assertion verdicts that diverge, by id;
  - `:checks`            — mid-script `[:assert …]` checkpoint verdicts that
                           diverge;
  - `:effects`           — effects emitted by one run and not the other
                           (multiset delta — `:only-baseline` / `:only-current`);
  - `:schema-violations` — schema failures by surface selector
                           (`re-frame.story.play.evidence/violation-selector`);
  - `:warnings`          — warning records present in one run and not the other;
  - `:trace-ops`         — the trace `:operation` sequence (the causal op
                           spine), as an ordered alignment of the two;
  - `:sub-overrides`     — the `{query-vector data}` sub-override map delta;
  - `:fidelity`          — the `#{fidelity-token …}` set delta.

  The facet set is EXACTLY the canonical run-slice
  (`fingerprint/run-hash-input-keys`) the `:same?` judgement compares —
  nothing outside it. `:sub-runs` is deliberately NOT in that slice
  (over-recomputed evidence, not a determinism input), so it carries NO
  `diff-runs` facet; `diff-sub-runs` survives only as a standalone diagnostic
  fn (rf2-e6uod / rf2-5l0a5).

  ## A readable diff, not a data dump

  The result is SMALL by construction: `diff-run-artifacts` returns
  `{:same? true}` when the two runs are behaviourally identical, and
  otherwise a map carrying ONLY the facets that actually differ
  (`:facets #{…}` names them). A facet that matches contributes nothing, so
  a one-key app-db change yields a one-entry `:app-db` facet — not the whole
  database. This is the §Semantic-diff contract: surface the SEMANTIC
  difference, suppress the volatile noise.

  ## Inputs

  `diff-run-artifacts` accepts, for each side, either:

  - a `:rf.test/run-artifact` map — replayed via
    `re-frame.story.artifact/replay-run-artifact` to obtain a run-result
    (the IMPURE path; opts thread through);
  - a run-result map (the shared §Run-result shape) — used directly (the
    PURE path).

  The artifact path is the only impurity; everything below it — `diff-runs`
  and every facet fn — is pure data → data and runs under `clojure -M:test`
  with no runtime. Tests drive `diff-runs` with two hand-built run-results.

  ## Two-layer projection: strip noise, keep shape

  `canonicalize` re-orders every map into a sorted `[k v …]` vector, which
  destroys the named-slot access (`:app-db`, `:effects`, …) the facet fns
  need. So the assembler projects both run-results through the SAME volatile
  strip `canonicalize` applies — `re-frame.story.fingerprint/strip-run-stamps`
  then `project` (the structural per-run-stamp strip + the recursive
  volatile / `:rf.story/*` strip) — WITHOUT the final ordering pass. The
  result is a noise-free run-result whose map SHAPE is intact, so the facet
  fns read named slots while seeing none of the per-run noise. The top-level
  `:same?` judgement still uses full `canonicalize` (ordering imposed) for a
  robust order-insensitive equality.

  ## Pure / JVM-testable

  The ten facet diff fns wired into `facet-fns` — `diff-status`,
  `diff-app-db`, `diff-assertions`, `diff-checks`, `diff-effects`,
  `diff-schema-violations`, `diff-warnings`, `diff-trace-ops`,
  `diff-sub-overrides`, `diff-fidelity` — plus the coarse `diverging-slice-keys`
  fallback and the assembler (`diff-runs`) are pure data → data: two
  run-results in, a readable diff out. `diff-sub-runs` is pure too but is
  diagnostic-only — not wired into `diff-runs` (it is outside the `:same?`
  slice). `diff-run-artifacts` is the thin replay-then-`diff-runs` wrapper for
  the artifact inputs."
  (:require [clojure.set                  :as set]
            [re-frame.story.artifact      :as artifact]
            [re-frame.story.fingerprint   :as fingerprint]
            [re-frame.story.play.evidence :as evidence]))

;; ===========================================================================
;; APP-DB DELTA  (readable key-path diff)
;; ===========================================================================
;;
;; A raw `(not= db-a db-b)` is useless for a reader — it says THAT they
;; differ, never WHERE. This produces a readable path delta: the leaf paths
;; that were added, removed, or changed between baseline and current. The
;; assembler feeds these fns NOISE-STRIPPED app-dbs (`strip-noise` —
;; volatile / `:rf.story/*` accumulator keys gone), so a path that survives
;; here is a genuine semantic difference.

(defn- leaf-paths
  "Every leaf path through a nested map, paired with its value. A non-map
  value is a leaf at its own path; a NON-ROOT empty map is a leaf (its
  emptiness is semantic — `{:k {}}` vs `{:k {:a 1}}` differs at `[:k]`). Pure
  data → data; returns `{[path …] value}`.

  The ROOT is special-cased (rf2-bd6ei): an empty root map contributes NO
  leaf at all, so an app-db cleared to `{}` does not read as a spurious
  `{[] {}}` root leaf (which would surface as `:added`/`:removed` at the
  nonsensical `[]` path). A populated-vs-empty difference still diffs
  correctly — the populated side's leaves are `:removed`/`:added`, the empty
  side simply contributes nothing."
  [x]
  (letfn [(walk [prefix v acc]
            (cond
              ;; A non-empty map recurses into its entries.
              (and (map? v) (seq v))
              (reduce-kv (fn [a k vv] (walk (conj prefix k) vv a)) acc v)
              ;; An EMPTY map at the root is not a leaf — contribute nothing,
              ;; so a `{}` app-db yields no spurious `[]` root leaf. At a
              ;; non-root prefix the empty map IS a semantic leaf.
              (and (map? v) (empty? v) (empty? prefix))
              acc
              ;; A scalar, or a (non-root) empty map, is a leaf at its path.
              :else
              (assoc acc prefix v)))]
    (walk [] x {})))

(defn diff-app-db
  "Readable key-path delta between two app-db values. Pure data → data.

  Returns `nil` when the two are `=` (the canonical forms match), else:

      {:added   [{:path … :current  v} …]   ; in current, not baseline
       :removed [{:path … :baseline v} …]   ; in baseline, not current
       :changed [{:path … :baseline a :current b} …]}

  Paths are leaf paths through the nested map; a slot present on only one
  side is `:added` / `:removed`, a slot present on both with different
  values is `:changed`. Only the differing slots appear — a 100-key db with
  one changed key yields a one-entry `:changed`."
  [baseline current]
  (when (not= baseline current)
    (let [base-leaves (leaf-paths baseline)
          cur-leaves  (leaf-paths current)
          base-paths  (set (keys base-leaves))
          cur-paths   (set (keys cur-leaves))
          ;; `sort-by pr-str` is a total, type-safe order over heterogeneous
          ;; path vectors (a path may mix keyword / string / number keys, so
          ;; raw `sort` could throw comparing a keyword to a number).
          added       (sort-by pr-str (remove base-paths cur-paths))
          removed     (sort-by pr-str (remove cur-paths base-paths))
          changed     (sort-by pr-str
                               (filter (fn [p]
                                         (and (contains? base-leaves p)
                                              (not= (base-leaves p) (cur-leaves p))))
                                       cur-paths))]
      (cond-> {}
        (seq added)   (assoc :added
                             (mapv (fn [p] {:path p :current (cur-leaves p)}) added))
        (seq removed) (assoc :removed
                             (mapv (fn [p] {:path p :baseline (base-leaves p)}) removed))
        (seq changed) (assoc :changed
                             (mapv (fn [p] {:path     p
                                            :baseline (base-leaves p)
                                            :current  (cur-leaves p)})
                                   changed))))))

;; ===========================================================================
;; MULTISET DELTA  (effects / sub-runs — emission-ordered evidence rows)
;; ===========================================================================
;;
;; Effects and sub-runs are ordered vectors of rows. A semantic difference
;; is a row one run emitted and the other did not — a MULTISET delta (a row
;; emitted twice on one side and once on the other IS a difference). Both
;; sides are canonical, so equal rows compare `=`.

(defn- multiset
  "Frequency map of `coll` — `{element count}`. Pure data → data."
  [coll]
  (frequencies coll))

(defn- multiset-delta
  "The multiset delta between `baseline` and `current` row vectors. Pure
  data → data. Returns `nil` when the multisets match, else:

      {:only-baseline [row …]   ; counts higher in baseline (the surplus)
       :only-current  [row …]}  ; counts higher in current

  Surplus counts are expanded back to rows (a row with baseline-count 2 and
  current-count 1 contributes one row to `:only-baseline`), so the delta
  reads as the concrete rows that differ. Rows are returned in their
  baseline / current emission order (stable, deterministic)."
  [baseline current]
  (let [base-ms (multiset baseline)
        cur-ms  (multiset current)]
    (when (not= base-ms cur-ms)
      (let [surplus (fn [these others rows]
                      (into []
                            (mapcat (fn [row]
                                      (let [over (- (get these row 0)
                                                    (get others row 0))]
                                        (repeat (max 0 over) row))))
                            (distinct rows)))
            only-base (surplus base-ms cur-ms baseline)
            only-cur  (surplus cur-ms base-ms current)]
        (cond-> {}
          (seq only-base) (assoc :only-baseline only-base)
          (seq only-cur)  (assoc :only-current only-cur))))))

(defn diff-effects
  "Multiset delta over the two runs' projected `:effects` rows. Pure data →
  data; `nil` when the effect multisets match, else `{:only-baseline […]
  :only-current […]}` (the rows one run emitted and the other did not)."
  [baseline current]
  (multiset-delta (:effects baseline) (:effects current)))

(defn diff-sub-runs
  "DIAGNOSTIC-ONLY multiset delta over the two runs' projected `:sub-runs`
  rows — the subscription / view facts, when available. Pure data → data;
  `nil` when the sub-run multisets match (including when both runs carry no
  sub-runs), else `{:only-baseline […] :only-current […]}`.

  NOT part of the `:same?` judgement and NOT registered in `facet-fns`
  (rf2-e6uod / rf2-5l0a5). `:sub-runs` is deliberately excluded from
  `fingerprint/run-hash-input-keys` — sub-runs are over-recomputed evidence,
  not a determinism input — so a `:sub-runs`-only delta does NOT make
  `diff-runs` report `:same? false`, exactly as the determinism gate and the
  golden verdict treat it. This fn exists for callers that want to inspect the
  view-fact delta directly; it deliberately does not flow through `diff-runs`."
  [baseline current]
  (multiset-delta (:sub-runs baseline) (:sub-runs current)))

;; ===========================================================================
;; SCHEMA-VIOLATION DELTA  (by surface selector)
;; ===========================================================================
;;
;; A schema violation's identity is its surface SELECTOR (spec §Schema rule,
;; `re-frame.story.play.evidence/violation-selector`) — `[:event id]`,
;; `[:app-db registered-path path]`, etc. — not the whole record (whose
;; `:explain` / `:value` are diagnostic detail). The delta is the multiset
;; of selectors one run produced and the other did not, so a reader sees
;; "current introduced a schema failure at [:event :checkout/submit]".

(defn- violation-selectors
  "The surface selectors of a run-result's projected `:schema-violations`,
  in tape order. Reuses an existing `:selector` when the evidence boundary
  already attached it (it does — `schema-violation-record`), else recomputes
  it via `evidence/violation-selector`. Pure data → data."
  [run]
  (mapv (fn [v] (or (:selector v) (evidence/violation-selector v)))
        (:schema-violations run)))

(defn diff-schema-violations
  "Multiset delta over the two runs' schema-violation surface SELECTORS
  (spec §Schema rule). Pure data → data; `nil` when both runs produced the
  same multiset of violation surfaces, else `{:only-baseline [selector …]
  :only-current [selector …]}` — the surfaces one run failed validation on
  and the other did not. The selector (not the full record) is the identity,
  so a re-ordered-but-equal set of violations does NOT read as a diff while
  a genuinely-new failure surface does."
  [baseline current]
  (multiset-delta (violation-selectors baseline) (violation-selectors current)))

;; ===========================================================================
;; TRACE-OP SEQUENCE DELTA  (the causal op spine)
;; ===========================================================================
;;
;; The ordered sequence of trace `:operation`s across the tape is the causal
;; spine — the verbs the run executed, in order. A semantic difference is a
;; divergence in that ordered sequence. Both tapes are canonical (per-run
;; stamps stripped), so the operations align by position; the diff names the
;; first index where they part plus the two op sequences for context.

(defn- trace-ops
  "The ordered vector of trace `:operation`s across a run-result's
  `:epoch-tape`, in dispatch order (epoch order) then emission order within
  each epoch. Pure data → data. This is the causal op spine the diff
  compares; events with no `:operation` (a malformed row) contribute
  nothing."
  [run]
  (into []
        (comp (mapcat :trace-events)
              (keep :operation))
        (:epoch-tape run)))

(defn diff-trace-ops
  "Diff the two runs' trace `:operation` sequences — the causal op spine
  (spec §Semantic diff). Pure data → data; `nil` when the sequences are
  identical, else:

      {:baseline [op …]      ; baseline op sequence, in order
       :current  [op …]      ; current op sequence, in order
       :first-divergence i}  ; first index where they differ (nil if one is
                             ; a proper prefix of the other — only the length
                             ; differs)

  Keeping both ordered sequences (not just a set) means a re-ordering or a
  dropped op reads as a genuine difference — the op spine is causal, so its
  order is semantic."
  [baseline current]
  (let [a (trace-ops baseline)
        b (trace-ops current)]
    (when (not= a b)
      (let [n     (min (count a) (count b))
            diverge (first (filter #(not= (nth a %) (nth b %)) (range n)))]
        (cond-> {:baseline a :current b}
          (some? diverge) (assoc :first-divergence diverge))))))

;; ===========================================================================
;; STATUS DELTA
;; ===========================================================================

(defn diff-status
  "The top-level run `:status` delta. Pure data → data; `nil` when the two
  runs share a status, else `{:baseline s :current s}`. A `:pass` → `:fail`
  (or `:deterministic` → `:error`) flip is the headline difference a reader
  wants first."
  [baseline current]
  (let [a (:status baseline)
        b (:status current)]
    (when (not= a b)
      {:baseline a :current b})))

;; ===========================================================================
;; WARNINGS DELTA  (multiset of warning records)
;; ===========================================================================
;;
;; `:warnings` is the tape's projected `:op-type :warning` rows
;; (`re-frame.story.play.evidence/warnings`) — one record per emitted
;; warning, in tape order. A semantic difference is a warning one run raised
;; and the other did not, so the delta is the same MULTISET shape as effects
;; / sub-runs (a warning raised twice on one side and once on the other IS a
;; difference). Both sides are noise-stripped, so equal records compare `=`.

(defn diff-warnings
  "Multiset delta over the two runs' projected `:warnings` rows (spec/017
  §Semantic diff). Pure data → data; `nil` when the warning multisets match,
  else `{:only-baseline […] :only-current […]}` — the warning records one
  run raised and the other did not."
  [baseline current]
  (multiset-delta (:warnings baseline) (:warnings current)))

;; ===========================================================================
;; VERDICT DELTA  (assertions / checks — keyed by stable identity)
;; ===========================================================================
;;
;; Assertions and checks are ordered vectors of verdict-bearing records whose
;; IDENTITY is a stable selector (an assertion is `[:assertion :payload]`; a
;; check is its `:check` id), not the whole record (whose `:reason` /
;; `:expected` / `:actual` are diagnostic detail). The semantic difference is
;; a record one run produced and the other did not, OR a record whose VERDICT
;; (`:status`) flipped between the runs. So the delta is keyed by selector and
;; carries three buckets: `:added` / `:removed` selectors, and `:changed`
;; verdict flips. This mirrors the `diff-app-db` added/removed/changed shape
;; rather than inventing a new diff vocabulary.

(defn- verdict-delta
  "Verdict delta between two selector→status maps. Pure data → data. Returns
  `nil` when the maps are `=`, else a `cond->`-built map carrying ONLY the
  non-empty buckets:

      {:added   [{:selector … :current  status} …]   ; in current, not baseline
       :removed [{:selector … :baseline status} …]   ; in baseline, not current
       :changed [{:selector … :baseline a :current b} …]}  ; verdict flipped

  Selectors are ordered by `pr-str` (a total, type-safe order over
  heterogeneous selectors — a selector may mix keyword / vector / number, so
  raw `sort` could throw)."
  [baseline-by-sel current-by-sel]
  (when (not= baseline-by-sel current-by-sel)
    (let [base-sels (set (keys baseline-by-sel))
          cur-sels  (set (keys current-by-sel))
          added     (sort-by pr-str (remove base-sels cur-sels))
          removed   (sort-by pr-str (remove cur-sels base-sels))
          changed   (sort-by pr-str
                             (filter (fn [s]
                                       (and (contains? baseline-by-sel s)
                                            (not= (baseline-by-sel s)
                                                  (current-by-sel s))))
                                     cur-sels))]
      (cond-> {}
        (seq added)   (assoc :added
                             (mapv (fn [s] {:selector s
                                            :current  (current-by-sel s)}) added))
        (seq removed) (assoc :removed
                             (mapv (fn [s] {:selector s
                                            :baseline (baseline-by-sel s)}) removed))
        (seq changed) (assoc :changed
                             (mapv (fn [s] {:selector s
                                            :baseline (baseline-by-sel s)
                                            :current  (current-by-sel s)})
                                   changed))))))

(defn- assertions-by-selector
  "Map every assertion record in a run to its `:status`, keyed by the stable
  assertion selector `[:assertion :payload]`. Pure data → data. A duplicate
  selector keeps the LAST record's status (verdicts of equal-identity
  assertions agree by construction — the §Schema-rule multiset pairing mints
  one record per consumed violation)."
  [run]
  (into {}
        (map (fn [a] [[(:assertion a) (vec (:payload a))] (:status a)]))
        (:assertions run)))

(defn diff-assertions
  "Verdict delta over the two runs' `:assertions` records, keyed by the
  stable assertion selector `[:assertion :payload]` (spec/017 §Semantic diff).
  Pure data → data; `nil` when both runs evaluated the same assertions to the
  same verdicts, else the `verdict-delta` shape (`:added` / `:removed`
  selectors + `:changed` verdict flips). A `:pass` → `:fail` flip on one
  assertion reads as a one-entry `:changed`, not a wall of records."
  [baseline current]
  (verdict-delta (assertions-by-selector baseline)
                 (assertions-by-selector current)))

(defn- checks-by-id
  "Map every check record in a run to its `:status`, keyed by the `:check`
  id. Pure data → data."
  [run]
  (into {}
        (map (fn [c] [(:check c) (:status c)]))
        (:checks run)))

(defn diff-checks
  "Verdict delta over the two runs' `:checks` records, keyed by the `:check`
  id (spec/017 §Semantic diff). Pure data → data; `nil` when both runs ran
  the same checks to the same verdicts, else the `verdict-delta` shape
  (`:added` / `:removed` check ids + `:changed` verdict flips)."
  [baseline current]
  (verdict-delta (checks-by-id baseline) (checks-by-id current)))

;; ===========================================================================
;; SUB-OVERRIDES DELTA  (the resolved render-path override map)
;; ===========================================================================
;;
;; `:sub-overrides` is the resolved `{query-vector value}` map (spec/017
;; §View-state subscription overrides) — the third, lower-fidelity rung the
;; render path consults. A semantic difference is an override one run carried
;; and the other did not, or one whose pinned VALUE differs. The query vector
;; is the identity (each override key is an exact query vector), so the delta
;; is keyed by query vector with added / removed / changed buckets.

(defn diff-sub-overrides
  "Delta between two runs' resolved `:sub-overrides` maps (spec/017
  §View-state subscription overrides), keyed by the override's query vector.
  Pure data → data; `nil` when the override maps are `=`, else:

      {:added   [{:query … :current  v} …]   ; override only in current
       :removed [{:query … :baseline v} …]   ; override only in baseline
       :changed [{:query … :baseline a :current b} …]}  ; pinned value differs

  Query vectors are ordered by `pr-str` for a stable, type-safe order."
  [baseline current]
  (let [base (or (:sub-overrides baseline) {})
        cur  (or (:sub-overrides current) {})]
    (when (not= base cur)
      (let [base-qs (set (keys base))
            cur-qs  (set (keys cur))
            added   (sort-by pr-str (remove base-qs cur-qs))
            removed (sort-by pr-str (remove cur-qs base-qs))
            changed (sort-by pr-str
                             (filter (fn [q] (and (contains? base q)
                                                  (not= (base q) (cur q))))
                                     cur-qs))]
        (cond-> {}
          (seq added)   (assoc :added
                               (mapv (fn [q] {:query q :current (cur q)}) added))
          (seq removed) (assoc :removed
                               (mapv (fn [q] {:query q :baseline (base q)}) removed))
          (seq changed) (assoc :changed
                               (mapv (fn [q] {:query    q
                                              :baseline (base q)
                                              :current  (cur q)})
                                     changed)))))))

;; ===========================================================================
;; FIDELITY DELTA  (the fidelity-ladder rung set)
;; ===========================================================================
;;
;; `:fidelity` is a SET of the rungs a resolved plan rests on
;; (`#{:real-setup :db-seed :sub-overrides}`, spec/017 §View-state
;; subscription overrides — fidelity ladder). A semantic difference is a rung
;; one run rested on and the other did not, so the delta is a set delta
;; naming the rungs each side carried uniquely.

(defn diff-fidelity
  "Set delta over the two runs' `:fidelity` rung sets (spec/017 §View-state
  subscription overrides — fidelity ladder). Pure data → data; `nil` when the
  rung sets match, else `{:only-baseline #{rung …} :only-current #{rung …}}`
  — the fidelity rungs one run rested on and the other did not (e.g. a
  baseline that used `:real-setup` vs a current that fell back to
  `:sub-overrides`)."
  [baseline current]
  (let [base (set (:fidelity baseline))
        cur  (set (:fidelity current))]
    (when (not= base cur)
      (cond-> {}
        (seq (set/difference base cur))
        (assoc :only-baseline (set/difference base cur))
        (seq (set/difference cur base))
        (assoc :only-current (set/difference cur base))))))

;; ===========================================================================
;; THE ASSEMBLER  (pure — two run-results → readable diff)
;; ===========================================================================

(defn strip-noise
  "Strip the per-run volatile noise from a run-result WITHOUT imposing the
  canonical ordering. Pure data → data. This is `canonicalize` minus its
  final `canonical-form` (ordering) pass: it runs `strip-run-stamps` (the
  structural `:id` / `:time` / `:frame` strip on trace-event / epoch-record
  carriers) then `project` (the recursive volatile-field + `:rf.story/*`
  accumulator strip + `:variant-id` → `:variant/id` reconcile), but leaves
  every map a MAP so named-slot access (`:app-db`, `:effects`, …) still
  works. The facet fns read those slots, so they need the shape; this strip
  is why they see no per-run noise."
  [run]
  (fingerprint/project (fingerprint/strip-run-stamps run)))

(defn- diverging-slice-keys
  "The `fingerprint/run-hash-input-keys` slots whose CANONICAL projection
  differs between two run-results. Pure data → data. Each slot is
  canonicalized in isolation (the same projection `:same?` uses over the
  whole slice), so this names exactly which run-hash slot perturbed the
  hash. Returns an ordered (slice-order) vector of `{:slice-key …}` entries —
  empty only when the slice slots all match (which the `:same?` test already
  ruled out by the time this is consulted)."
  [baseline current]
  (into []
        (comp (filter (fn [k]
                        (not= (fingerprint/canonicalize (get baseline k))
                              (fingerprint/canonicalize (get current k)))))
              (map (fn [k] {:slice-key k})))
        fingerprint/run-hash-input-keys))

(def facet-fns
  "The ordered facet name → diff-fn map (spec §Semantic diff). Ordered so
  the headline facets (`:status`, `:app-db`, the `:assertions` / `:checks`
  verdicts) read first. Each fn takes the two NOISE-STRIPPED run-results
  (`strip-noise`) and returns the facet's readable delta or `nil` (no
  difference). A new facet is added here once and flows into `diff-runs` and
  `:facets` automatically.

  INVARIANT — facet-set == canonical slice keys. The facet names here are
  EXACTLY `re-frame.story.fingerprint/run-hash-input-keys`, the slice
  `:same?` is judged over (`:trace-ops` is the readable projection of the
  `:epoch-tape` slot's causal op spine, so it stands in for `:epoch-tape`).
  Two consequences both matter:

  - every slice slot is covered, so a diff the gate calls different always
    localises WHERE (any residual gap is caught by `diff-runs`' coarse
    slice-key fallback — the non-empty-`:facets` invariant);
  - NO facet sits OUTSIDE the slice. `:sub-runs` is deliberately excluded from
    the run-hash slice (over-recomputed evidence, not a determinism input —
    rf2-e6uod / rf2-5l0a5), so it is NOT registered here: a `:sub-runs`-only
    delta does not perturb the `:same?` slice, so a facet for it could never
    fire through `diff-runs` (it would be dead code that overstated coverage
    and disagreed with the determinism gate / golden verdict). `diff-sub-runs`
    survives as a standalone diagnostic fn (call it directly), NOT as part of
    the `:same?` judgement."
  (array-map
    :status            diff-status
    :app-db            (fn [b c] (diff-app-db (:app-db b) (:app-db c)))
    :assertions        diff-assertions
    :checks            diff-checks
    :effects           diff-effects
    :schema-violations diff-schema-violations
    :warnings          diff-warnings
    :trace-ops         diff-trace-ops
    :sub-overrides     diff-sub-overrides
    :fidelity          diff-fidelity))

(defn diff-runs
  "Diff two run-results — the PURE core (spec §Semantic diff). Pure data →
  data: two run-results in, a readable diff out.

  The `:same?` judgement is `re-frame.story.fingerprint/canonicalize`
  equality (the SAME judgement `assert-deterministic` renders) — so two runs
  the determinism gate calls equal diff to `{:same? true}`. When they differ,
  each facet is computed over the NOISE-STRIPPED run-results (`strip-noise` —
  the volatile / per-run-stamp / `:rf.story/*` strip, map shape preserved),
  so the volatile per-run noise (frame ids, timestamps, epoch / dispatch /
  trace ids) never reaches a facet delta — the diff shows SEMANTIC
  differences only.

  Returns `{:same? true}` when the two runs are behaviourally identical
  (every facet matched — the same judgement `assert-deterministic` calls
  `:deterministic`). Otherwise:

      {:same?  false
       :facets #{facet-name …}      ; which facets differ (the headline)
       <facet-name> <facet-delta>}  ; each differing facet's readable delta

  ONLY differing facets appear, so the diff stays small and readable. The
  `:facets` set names them up front so a consumer can branch without probing
  each slot.

  INVARIANT (rf2-rv9tt): a `:same? false` diff ALWAYS carries a non-empty
  `:facets`. The per-surface facets cover every `run-hash-input-keys` slot,
  but if some slice slot ever diverges with no specific facet firing, the
  coarse `:slice-keys` fallback names WHICH `run-hash-input-keys` slot
  perturbed the judgement (`[{:slice-key k} …]`) — never `{:same? false
  :facets #{}}`, which would be an undiagnosable diff.

  The `:same?` judgement is canonical equality of the run-SLICE
  (`fingerprint/run-hash-input-keys` — the behavioural surface, NOT the whole
  result), the EXACT slice + judgement `re-frame.story.determinism/compare-runs`
  uses. A run-result also carries pure provenance (`:frame` replay id,
  `:run-artifact` back-link, per-step `:replay-steps`) that legitimately
  differs per replay and is excluded from the slice for exactly this reason —
  so a diff agrees with the determinism gate on what counts as the same run."
  [baseline current]
  (if (= (fingerprint/canonicalize (select-keys baseline fingerprint/run-hash-input-keys))
         (fingerprint/canonicalize (select-keys current  fingerprint/run-hash-input-keys)))
    {:same? true}
    ;; The canonical forms differ. Feed each facet fn the NOISE-STRIPPED but
    ;; SHAPE-PRESERVED run-results (`strip-noise` — the same volatile / stamp
    ;; / accumulator strip `canonicalize` applies, WITHOUT the ordering pass
    ;; that flattens maps into `[k v …]` vectors). The facet fns read named
    ;; slots, so they need the map shape; they see none of the per-run noise,
    ;; so every delta they surface is a genuine semantic difference.
    (let [b      (strip-noise baseline)
          c      (strip-noise current)
          deltas (reduce-kv
                   (fn [acc facet f]
                     (if-let [d (f b c)]
                       (assoc acc facet d)
                       acc))
                   {}
                   facet-fns)
          ;; The non-empty-:facets invariant (rf2-rv9tt): the per-surface
          ;; facets cover every run-hash slice slot, but if NONE fired the
          ;; canonical forms still differ — so localise the divergence to the
          ;; specific `run-hash-input-keys` slot(s) rather than returning an
          ;; undiagnosable `{:same? false :facets #{}}`.
          deltas (if (seq deltas)
                   deltas
                   {:slice-keys (diverging-slice-keys b c)})]
      (assoc deltas
             :same?  false
             :facets (set (keys deltas))))))

;; ===========================================================================
;; diff-run-artifacts  (the public entry — replay artifacts, then diff)
;; ===========================================================================

(defn- ->run-result
  "Coerce a `diff-run-artifacts` side into a run-result. A run-result
  (carrying a `:status`) is used directly — the PURE path. A
  `:rf.test/run-artifact` is replayed into a FRESH frame via
  `re-frame.story.artifact/replay-run-artifact` (the IMPURE path), threading
  `opts` (`:frame` / `:hooks` / `:frame-config`). Replaying both sides means
  a diff over two artifacts compares two FRESH-frame runs — exactly the runs
  the determinism gate would compare — so artifact-vs-artifact and
  result-vs-result diffs agree."
  [side opts]
  (cond
    (artifact/run-artifact? side) (artifact/replay-run-artifact side opts)
    :else                         side))

(defn diff-run-artifacts
  "Readable semantic diff between two runs — the public entry point (spec
  §Semantic diff). `test/diff-run-artifacts`.

  `baseline` and `current` are each either a `:rf.test/run-artifact` (which
  is REPLAYED into a fresh frame to obtain a run-result — the impure path,
  the same replay the determinism gate drives) or an already-computed
  run-result (used directly — the pure path). `opts` (all optional) thread to
  `replay-run-artifact`: `:frame` / `:hooks` / `:frame-config`.

  Both runs are projected through `re-frame.story.fingerprint/canonicalize`
  to strip the per-run noise (frame ids, timestamps, epoch / dispatch /
  trace ids) BEFORE diffing, so the result shows the SEMANTIC differences —
  not volatile drift. The diff covers app-db deltas, effects, schema
  violations, the trace op spine, subscription / view facts, and the
  top-level status.

  Returns `{:same? true}` when the two runs are behaviourally identical,
  else `{:same? false :facets #{…} <facet> <delta> …}` carrying ONLY the
  facets that differ (see `diff-runs`). When both inputs are run-results,
  this is PURE — `clojure -M:test` exercises it with no runtime."
  ([baseline current] (diff-run-artifacts baseline current nil))
  ([baseline current opts]
   (diff-runs (->run-result baseline opts)
              (->run-result current opts))))
