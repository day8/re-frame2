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
  independently, so a diff localises WHERE two runs parted:

  - `:app-db`            — a readable key-path delta of the final app-db
                           (`:added` / `:removed` / `:changed` paths, each a
                           `{:path … :baseline … :current …}`-style entry);
  - `:effects`           — effects emitted by one run and not the other
                           (multiset delta — `:only-baseline` / `:only-current`);
  - `:schema-violations` — schema failures by surface selector
                           (`re-frame.story.play.evidence/violation-selector`);
  - `:trace-ops`         — the trace `:operation` sequence (the causal op
                           spine), as an ordered alignment of the two;
  - `:sub-runs`          — subscription / view facts when available (the
                           per-epoch sub-run rows the evidence boundary
                           projects);
  - `:status`            — the top-level run status, when it differs.

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

  The facet diff fns (`diff-app-db`, `diff-effects`, `diff-schema-violations`,
  `diff-trace-ops`, `diff-sub-runs`) and the assembler (`diff-runs`) are pure
  data → data: two run-results in, a readable diff out. `diff-run-artifacts`
  is the thin replay-then-`diff-runs` wrapper for the artifact inputs."
  (:require [re-frame.story.artifact      :as artifact]
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
  value is a leaf at its own path; an EMPTY map is a leaf (its emptiness is
  semantic — `{:k {}}` vs `{:k {:a 1}}` differs at `[:k]`). Pure data →
  data; returns `{[path …] value}`."
  [x]
  (letfn [(walk [prefix v acc]
            (if (and (map? v) (seq v))
              (reduce-kv (fn [a k vv] (walk (conj prefix k) vv a)) acc v)
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
  "Multiset delta over the two runs' projected `:sub-runs` rows — the
  subscription / view facts, when available (spec §Semantic diff). Pure
  data → data; `nil` when the sub-run multisets match (including when both
  runs carry no sub-runs), else `{:only-baseline […] :only-current […]}`."
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

(def facet-fns
  "The ordered facet name → diff-fn map (spec §Semantic diff). Ordered so
  the headline facets (`:status`, `:app-db`) read first. Each fn takes the
  two NOISE-STRIPPED run-results (`strip-noise`) and returns the facet's
  readable delta or `nil` (no difference). A new facet is added here once and
  flows into `diff-runs` and `:facets` automatically."
  (array-map
    :status            diff-status
    :app-db            (fn [b c] (diff-app-db (:app-db b) (:app-db c)))
    :effects           diff-effects
    :schema-violations diff-schema-violations
    :trace-ops         diff-trace-ops
    :sub-runs          diff-sub-runs))

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
                   facet-fns)]
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
