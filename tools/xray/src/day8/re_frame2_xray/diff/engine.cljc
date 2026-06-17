(ns day8.re-frame2-xray.diff.engine
  "Editscript-backed diff projection engine (rf2-n2jig).

  ## Purpose

  Replace the home-grown classifier (since deleted from
  `tools/xray/src/day8/re_frame2_xray/views/edn_inspector.cljs`)
  (`diff-op`, `diff-op-container`, `children-changed?`, etc.). Per
  pair-debug 2026-05-27 the project locks Editscript in as the canonical
  diff engine for Xray — pre-alpha posture, no transitional double-engine
  state, no shim. The findings doc
  (`ai/findings/diff-mode-3-key-and-triangle-grammar-2026-05-27.md` §9)
  carries the full rationale.

  ## Output shape

  `(project before after)` returns a pure-data map:

      {:path-ops      {[path] {:op <kw> ...}}     ; leaf-resolution ops
       :container-ops {[path] {:op <kw> :change-count <n>}} ; container ops
       :flat-rows     [{:path :op :before :after} ...]      ; pure-diff lens
       :vector-removals {[parent-path] [{:before-index :before-value} ...]}
                                                            ; R6 vector deletes
       :wholly-changed-roots #{[path] ...}                  ; R5 reclassification
       :shift-suffix  {[path] (was N)}                      ; R6 :same-shifted
      }

  Where each per-leaf `:op` is one of:

      :added         — new key/index/element in after
      :removed       — key/index/element existed in before, gone now
      :modified      — both sides present, scalar changed (R1)
      :same          — both sides identical
      :same-shifted  — same value but moved positionally (R6 vectors)

  Container ops carry one extra op:

      :children      — descendant(s) changed, but the container itself
                       is structurally intact (same kind, same set of
                       keys/indices)

  And per R7 a container whose kind changed (map→vec, scalar→map, …)
  reclassifies as `:modified` at the container path with `:before` carrying
  the old value.

  ## Wholly-changed reclassification (R5, revised per Mike's pair-debug
  answer Q2)

  When EVERY descendant of a container is `:added` (or `:removed`), the
  container reclassifies to `:added` (or `:removed`) and is recorded in
  `:wholly-changed-roots`. Per-descendant glyphs + 2px stripes are
  suppressed by the renderer; per-descendant row WASHES are retained
  (so an operator scrolled into the middle of a 20-leaf added shard
  still sees the green wash and knows they're inside a new subtree).

  ## Vector shift detection (R6)

  Editscript's A* algorithm uses Myers underpinnings under the hood, so
  an insert at position 1 in `[:a :b :c :d]` → `[:a :NEW :b :c :d]` is
  expressed as a single `[[1] :+ :NEW]` edit — the elements at positions
  2..4 are NOT marked as modified, they are intrinsically the same. We
  walk the edit script per-vector-path, compute the inferred old index
  of every after-side index that doesn't sit at a `:+`/`:r` op, and
  emit a `:same-shifted` op with a `:was-index` suffix when the new
  index differs from the old.

  ## Sentinel-aware modified (R8)

  When a one-sided `:rf/redacted` sentinel appears in a `:modified`
  pair, we tag the op with `:rf.xray.diff/redaction-side` so the
  renderer can curate the suffix (`← was redacted` / `← now redacted`)
  without leaking the sentinel marker text. Two-sided redaction
  classifies as `:same` (per R8's v1 scope).

  ## Pure / JVM-runnable

  Pure data → data, no IO, no `rf/*` reads. `.cljc` so the JVM unit-test
  target picks it up.

  ## Cost

  Editscript's A* is O(|a||b|) in the worst case (per the library's
  README); the per-epoch one-diff cadence is well within Xray's dev-tool
  budget (millisecond range for typical app-dbs). For pathologically
  large structures the calling code can short-circuit via
  `identical?` BEFORE invoking this engine."
  (:require [clojure.set :as set]
            [editscript.core :as es]
            [editscript.edit :as ee]))

;; =========================================================================
;; sentinels
;; =========================================================================

(def missing-sentinel
  "Marker for a `before` / `after` slot that does not exist in its side
  of the diff. Distinct from `nil` (a real CLJS value)."
  :day8.re-frame2-xray.diff.engine/missing)

(def redacted-sentinel
  "Recognise the framework's `:rf/redacted` sentinel — used for R8's
  curated suffix branch. Keeping the predicate scoped to a single
  constant means a future contract evolution (e.g. namespaced sentinel
  map shape) updates here only."
  :rf/redacted)

(defn- redacted? [v] (= v redacted-sentinel))

;; =========================================================================
;; container kind classification
;; =========================================================================

(defn- container-kind
  "Classify `v` into a kind keyword for type-change detection (R7).
  Returns `:map / :vector / :list / :set / :scalar`. The exact
  distinction between vector and list matters for R6 (positional vs
  walking compare) but not for R7's reclassification — a list→vector
  flip is still a `:modified` at the parent."
  [v]
  (cond
    (map? v)        :map
    (set? v)        :set
    (vector? v)     :vector
    (list? v)       :list
    (sequential? v) :seq
    :else           :scalar))

(defn- container? [v]
  (not= :scalar (container-kind v)))

;; =========================================================================
;; safe value accessor along an Editscript path
;; =========================================================================
;;
;; The path components Editscript uses for sequentials are 0-based indices,
;; for maps are the actual keys, and for sets are the elements themselves.
;; `get-in`-with-defaults handles all three because vectors / maps / sets
;; all satisfy `clojure.core/get` for their respective addressing modes.

(defn- value-at
  "Walk `data` along `path`, returning `missing-sentinel` if any segment
  is absent. Pure; safe for arbitrary nested shapes."
  [data path]
  (reduce (fn [acc seg]
            (cond
              (= acc missing-sentinel)
              missing-sentinel

              (and (map? acc) (contains? acc seg))
              (get acc seg)

              (and (or (vector? acc) (sequential? acc))
                   (integer? seg))
              (if (and (>= seg 0) (< seg (count acc)))
                (nth (vec acc) seg)
                missing-sentinel)

              (set? acc)
              (if (contains? acc seg) seg missing-sentinel)

              :else missing-sentinel))
          data path))

;; =========================================================================
;; raw Editscript edit-script walker
;; =========================================================================

(defn- expand-set-replacement
  "Expand an Editscript whole-set `:r` (replace) at a SET-valued path into
  the per-member membership delta: members only-in-before ⇒ `:-`, members
  only-in-after ⇒ `:+`, members in BOTH ⇒ no edit (unchanged). Members
  match BY VALUE (sets are unordered), mirroring Editscript's own set-edit
  path scheme (the member itself is the trailing path segment).

  Editscript's A* emits per-member `:+` / `:-` edits for a SINGLE-member
  swap (`#{:a} → #{:b}` ⇒ `[[:a] :-] [[:b] :+]`, fixed member-level by
  rf2-l0us2) but crosses its cost threshold and falls back to a WHOLE-SET
  `:r` once MULTIPLE members change simultaneously
  (`#{:a :b :c} → #{:a :d :e}` ⇒ `[[] :r #{:a :d :e}]`) — the empty↔populated
  edge (`#{} → #{:a}`) hits the same `:r`. Left alone the `:r` classifies as
  a single `:modified` at the set's path and every per-member path returns
  `:same` from `op-at`, which the renderer paints as a whole-set
  removal/add ('sea of red') instead of member-level `-removed +added`
  (rf2-4vp8c, residual of rf2-l0us2 which fixed only the single-member
  case). Synthesizing the membership delta restores member-level chrome
  REGARDLESS of how many members changed; this subsumes the empty↔populated
  set expansion (the in-both set is empty there, so it degenerates to
  all-`:+` or all-`:-`).

  Pure."
  [path before-set after-set]
  (into (mapv (fn [el] [(conj (vec path) el) :-])
              (set/difference before-set after-set))
        (mapv (fn [el] [(conj (vec path) el) :+ el])
              (set/difference after-set before-set))))

(defn- expand-collection-replacement
  "Expand a single Editscript edit that replaces a collection wholesale
  (whole-value `:r`) into per-member `:+` / `:-` edits, so downstream
  classification sees member-level granularity instead of one whole-value
  `:modified`.

  ## Empty↔populated maps (rf2-9d4j8 / rf2-5j7ch)

  Editscript's A* emits a single `[[path] :r new-value]` edit for the
  `{} → {populated}` (and `{populated} → {}`) cases — the whole collection
  replacement is one step in the edit script. Downstream classification
  then tags `path` as `:modified` and leaves every PER-KEY path returning
  `:same` from `op-at`, which the renderer reads as 'no per-key change' —
  wrong for an absent→present transition (rf2-9d4j8; related precedent
  rf2-5j7ch which patched only the `:flat-rows` lens).

  ## Sets — empty↔populated AND multi-member swaps (rf2-l0us2 / rf2-4vp8c)

  For a SINGLE-member set swap Editscript already emits member-level
  `:+` / `:-` edits (`#{:a} → #{:b}` ⇒ `[[:a] :-] [[:b] :+]`), so it needs
  no expansion. But Editscript falls back to a whole-value `:r` for sets in
  two cases: when ONE side is EMPTY (`#{} → #{:a}` ⇒ `[[] :r #{:a}]`,
  rf2-l0us2) AND when MULTIPLE members change simultaneously
  (`#{:a :b :c} → #{:a :d :e}` ⇒ `[[] :r #{:a :d :e}]`, rf2-4vp8c — its
  A* cost threshold). Both classify as a single `:modified` at the set's
  path with every per-member path `:same` ('sea of red'). We catch ALL
  set `:r` here and delegate to `expand-set-replacement`, which synthesizes
  the membership delta regardless of member count — the empty↔populated
  edge is just the degenerate case where the in-both intersection is empty.

  ## Vectors / lists — empty↔populated edge (rf2-yucxn)

  Editscript emits a whole-value `:r` for the vector/list empty edge too
  (`[1] → []` ⇒ `[[] :r []]`; `{:a [1]} → {:a []}` ⇒ `[[:a] :r []]`; and
  symmetrically for `[] → [1]`). Left alone this classifies as a single
  `:modified` at the sequential's path — a whole-key `~` modify — which
  reads inconsistently with the set/map empty edges (which expand to
  member-level `:removed` / `:added` with the key intact). A vector/list
  going empty is an ELEMENT REMOVAL (and going populated-from-empty an
  ELEMENT ADD), not a wholesale value mutation. We expand the empty edge to
  per-index `:-` / `:+` edits so it renders member-level, matching set/map.
  Only the EMPTY edge is expanded: a populated↔populated vector swap is
  handled by Editscript's per-index `:+` / `:-` / `:r` edits already (it does
  NOT collapse to a whole-value `:r`), so there is no `:r` to intercept
  there. Per-index `:-` edits then flow through `project`'s
  `:vector-removals` channel exactly like ordinary tail deletions.

  This expansion runs over the raw edit script BEFORE classification so
  every downstream artefact (`:path-ops`, `:container-ops`,
  `:flat-rows`, `:wholly-changed-roots`, `:vector-removals`) sees
  member-level granularity.

  Rule:
    - SET↔SET `:r` (both sides sets) → membership-delta expansion, any
      member count (`expand-set-replacement`).
    - MAP `:r` where ONE side is the EMPTY map → per-key `:+` / `:-`.
      (Multi-key populated↔populated MAP swaps are NOT a known Editscript
      `:r` pathology — A* emits per-key edits for maps — so this branch
      stays scoped to the empty edge.)
    - VECTOR/LIST/SEQ `:r` where ONE side is the EMPTY sequential → per-
      index `:-` (going empty) / `:+` (filling from empty). Scoped to the
      empty edge — populated↔populated sequentials never collapse to a
      whole-value `:r` (rf2-yucxn).
    - Type changes (nil↔map, scalar↔set, map↔vector, set↔map, vector↔map)
      are LEFT ALONE so R7's `:rf.xray.diff/type-change?` branch still
      fires (both sides must be the SAME sequential kind to expand).

  Pure; non-matching edits pass through unchanged."
  [edit before after]
  (let [[path op _value] edit]
    (if (not= op :r)
      [edit]
      (let [before-at (value-at before path)
            after-at  (value-at after path)]
        (cond
          ;; SET↔SET replace — synthesize the membership delta for ANY
          ;; member count (single swap, multi-member swap, full no-overlap
          ;; replacement, or the empty↔populated edge). rf2-4vp8c.
          (and (set? before-at) (set? after-at))
          (expand-set-replacement path before-at after-at)

          ;; {} → populated map: per-key :+
          (and (map? before-at) (empty? before-at)
               (map? after-at)  (seq after-at))
          (mapv (fn [[k v]] [(conj (vec path) k) :+ v]) after-at)

          ;; populated map → {}: per-key :- (Editscript :- omits value)
          (and (map? before-at) (seq before-at)
               (map? after-at)  (empty? after-at))
          (mapv (fn [[k _v]] [(conj (vec path) k) :-]) before-at)

          ;; rf2-yucxn — sequential (vector / list / seq) empty edge. BOTH
          ;; sides must be sequentials of the same family (neither a set nor
          ;; a map) so a vector↔map type flip stays an R7 `:modified`.
          ;; `[] → [populated]`: per-index :+ at the AFTER indices.
          ;; `[populated] → []`: per-index :- at the BEFORE indices (each
          ;; flows through `project`'s `:vector-removals` channel).
          ;; Indices descend for the removal so the renderer surfaces them
          ;; in before order; `resolve-vector-removals` recovers the true
          ;; before-index regardless.
          (let [seq? (fn [v] (and (sequential? v) (not (map? v)) (not (set? v))))]
            (and (seq? before-at) (seq? after-at)
                 (or (empty? before-at) (empty? after-at))))
          (let [bvec (vec before-at)
                avec (vec after-at)]
            (cond
              (and (empty? bvec) (seq avec))
              (mapv (fn [i] [(conj (vec path) i) :+ (nth avec i)])
                    (range (count avec)))

              (and (seq bvec) (empty? avec))
              (mapv (fn [i] [(conj (vec path) i) :-])
                    (range (count bvec)))

              ;; both empty (shouldn't reach — `:r` implies a value change)
              :else [edit]))

          :else
          [edit])))))

(defn- raw-edits
  "Return Editscript A* edits for `(before, after)` as a vector of
  3-tuples `[path op value?]`, with whole-value collection replacements
  pre-expanded into per-member `:+` / `:-` edits: empty↔populated maps
  (rf2-9d4j8), empty↔populated sets (rf2-l0us2), and multi-member set
  swaps (rf2-4vp8c). Pure."
  [before after]
  (let [raw (try
              (ee/get-edits (es/diff before after {:algo :a-star}))
              (catch #?(:clj Exception :cljs js/Error) _e
                ;; Editscript can throw on certain pathological inputs
                ;; (e.g. comparing maps with unreadable keys); fall
                ;; back to a conservative whole-value replacement so
                ;; the renderer doesn't crash. The fallback edit
                ;; reproduces the operator-visible signal ("everything
                ;; changed") without false sub-tree precision.
                [[[] :r after]]))]
    (into [] (mapcat (fn [edit] (expand-collection-replacement edit before after))) raw)))

;; =========================================================================
;; per-path op classification (leaves)
;; =========================================================================

(defn- leaf-op
  "Classify a (before, after) leaf pair, accounting for the R8 redaction
  branch. Returns a map `{:op kw :before x :after y ...}`."
  [before after]
  (cond
    (and (= before missing-sentinel) (= after missing-sentinel))
    {:op :same :before before :after after}

    (= before missing-sentinel)
    {:op :added :after after}

    (= after missing-sentinel)
    {:op :removed :before before}

    (= before after)
    {:op :same :before before :after after}

    ;; R8 — one-sided redaction. Curated suffix branches off this tag.
    (and (redacted? before) (not (redacted? after)))
    {:op :modified :before before :after after
     :rf.xray.diff/redaction-side :before}

    (and (not (redacted? before)) (redacted? after))
    {:op :modified :before before :after after
     :rf.xray.diff/redaction-side :after}

    ;; R7 — container kind change is `:modified`, not `:children`.
    (or (and (container? before) (not (container? after)))
        (and (not (container? before)) (container? after))
        (and (container? before) (container? after)
             (not= (container-kind before) (container-kind after))))
    {:op :modified :before before :after after
     :rf.xray.diff/type-change? true}

    :else
    {:op :modified :before before :after after}))

;; =========================================================================
;; vector shift detection (R6)
;; =========================================================================
;;
;; For each vector path that received `:+` / `:-` edits, walk the after-
;; side indices and compute the inferred "old index" of each that the
;; A* edit script left untouched. The rule:
;;
;;   - Walk after-indices 0..N-1.
;;   - For each, count how many `:+` operations precede or sit at this
;;     after-index (these inserted new elements; the after-index of the
;;     unchanged element shifts down by that count).
;;   - Count how many `:-` operations precede the inferred old index
;;     (these removed before-elements; the before-index of the unchanged
;;     element shifts up by that count).
;;   - When (after-index ≠ before-index) → `:same-shifted`.
;;
;; This is a per-vector linear walk, O(N + edits-per-vector). Pure.

(defn- replay-vector-deletes
  "Replay Editscript's `:-` edits against a live `survivors` list of
  ORIGINAL before-indices and return `{:removed :survivors}`.

  Editscript applies `:-` edits SEQUENTIALLY against a progressively-
  shrinking sequence: each `:-` at edit-index `i` removes the element
  CURRENTLY occupying index `i`, AFTER all prior `:-` at this parent have
  already been applied. A contiguous tail deletion therefore repeats the
  SAME edit-index (`[1 2 3] → [1]` ⇒ `[[1] :-] [[1] :-]`), and a scattered
  deletion uses post-shift indices (`[:a :b :c :d] → [:a :c]` ⇒ `[[1] :-]
  [[2] :-]`). These are NOT stable before-indices.

  `survivors` starts as `(range before-len)`. For each delete edit-index
  `i` in Editscript order we record `survivors[i]` into `:removed` (in
  removal order) then splice it out, so what remains in `:survivors` are
  the before-indices of all surviving elements in after-order. An out-of-
  range edit-index (should not happen for well-formed Editscript output)
  is skipped rather than crashing the projection. Pure.

  Used by both `shift-suffixes-for-vector` (`:survivors`) and
  `resolve-vector-removals` (`:removed`)."
  [before-len delete-idxs]
  (loop [survivors  (vec (range before-len))
         [i & more] delete-idxs
         removed    []]
    (if (nil? i)
      {:removed removed :survivors survivors}
      (if (and (>= i 0) (< i (count survivors)))
        (recur (into (subvec survivors 0 i)
                     (subvec survivors (inc i)))
               more
               (conj removed (nth survivors i)))
        ;; Defensive: a malformed out-of-range edit-index is skipped
        ;; rather than crashing the projection.
        (recur survivors more removed)))))

(defn- shift-suffixes-for-vector
  "Given an after-vector's length + the per-position edits Editscript
  emitted at this vector's path, return a map
  `{after-index was-before-index}` for every after-element whose
  identity moved positionally (op `:same-shifted`).

  The logic REPLAYS the edit script against a live `survivors` list of
  ORIGINAL before-indices — the same technique `resolve-vector-removals`
  (rf2-yucxn) uses for the removals channel. This is mandatory because
  Editscript emits `:-` edits at POST-shift indices applied SEQUENTIALLY
  against a shrinking vector — they are NOT stable before-indices. The
  pre-fix `delete-idxs ≤ cursor` fixed-point (rf2-1njv97) mis-read those
  post-shift indices as before-indices and over-counted deletes for any
  scattered/multi-element removal (`[:a :b :c :d] → [:a :c]` reported the
  surviving `:c` as `(was 3)` instead of `(was 2)`; the single-delete case
  worked only by coincidence).

  Replay procedure:
    1. `survivors` starts as `(range before-len)` — every before-index.
    2. Apply each `:-` in Editscript order: edit-index `i` removes the
       element CURRENTLY at `survivors[i]` (post-shift), shrinking the list.
       What remains are the before-indices of all surviving elements, in
       after-order.
    3. Splice each `:+` (keyed by AFTER-index, ascending) into the survivor
       list as a `nil` insert-marker, so the list aligns 1:1 with the
       after-vector by index.
  The resulting `slots` vector maps `after-index → original before-index`
  (or `nil` for an inserted slot). Positions with edits (`:+` / `:r`) are
  skipped from the output because they classify under :added / :modified,
  not :same-shifted; an after-index whose before-index equals it (no move)
  is likewise omitted."
  [before-len edits-at-this-vector]
  (let [;; `:+` edits are keyed by their AFTER-index; `:-` edits carry
        ;; POST-shift edit-indices in Editscript order (NOT before-indices);
        ;; `:r` edits sit at a shared, surviving index.
        delete-edit-idxs (->> edits-at-this-vector
                              (filter (fn [edit] (= :- (second edit))))
                              (mapv (fn [edit] (peek (first edit)))))
        insert-idxs      (->> edits-at-this-vector
                              (filter (fn [edit] (= :+ (second edit))))
                              (map (fn [edit] (peek (first edit))))
                              (sort)
                              vec)
        replace-idxs     (->> edits-at-this-vector
                              (filter (fn [edit] (= :r (second edit))))
                              (map (fn [edit] (peek (first edit))))
                              (into #{}))
        ;; Step 1+2 — replay the `:-` deletes against the survivor list of
        ;; original before-indices, removing the post-shift slot each time.
        survivors-after-deletes
        (:survivors (replay-vector-deletes before-len delete-edit-idxs))
        ;; Step 3 — splice insert-markers (nil) at each `:+` after-index so
        ;; the slot vector aligns position-for-position with the after-vector.
        slots
        (reduce (fn [acc after-idx]
                  (let [after-idx (min after-idx (count acc))]
                    (into (conj (subvec acc 0 after-idx) nil)
                          (subvec acc after-idx))))
                survivors-after-deletes
                insert-idxs)
        insert-set (set insert-idxs)]
    ;; Emit `{after-index before-index}` only for surviving (non-edit) slots
    ;; whose position actually moved.
    (reduce-kv
      (fn [acc after-idx before-idx]
        (if (or (nil? before-idx)                  ; inserted slot → :added
                (contains? insert-set after-idx)
                (contains? replace-idxs after-idx) ; replaced slot → :modified
                (= after-idx before-idx))          ; unmoved → no suffix
          acc
          (assoc acc after-idx before-idx)))
      {}
      (vec slots))))

(defn- group-edits-by-vector-parent
  "Group all edits by their parent path WHEN the parent is a vector.
  Returns `{[parent-path] [edits...]}`. Map-key edits are filtered out
  (their parents are maps; R6 doesn't apply)."
  [edits after]
  (reduce
    (fn [acc [path _op _val :as edit]]
      (if (empty? path)
        acc
        (let [parent-path (vec (butlast path))
              parent-val  (value-at after parent-path)
              leaf-key    (peek path)]
          (if (and (or (vector? parent-val)
                       (sequential? parent-val))
                   (integer? leaf-key))
            (update acc parent-path (fnil conj []) edit)
            acc))))
    {}
    edits))

;; =========================================================================
;; main projection
;; =========================================================================

(defn- expand-leaf-paths
  "When an edit at `path` operates on a container value (e.g. `[[:flash] :+
  {:level :ok :text \"hi\"}]`), expand it into per-leaf paths so the
  renderer's path-keyed lookup finds an op at every descendant slot. The
  expansion is recursive over maps / vectors / sets. Pure.

  rf2-bufw2 — an EMPTY container (`[]`, `{}`, `#{}`, `'()`) is itself a
  terminal leaf the operator navigates to and sees in the tree: it has
  no descendant slots to recurse into, so the recursive branches below
  would emit NOTHING and the slot would fall through `op-at` to `:same`
  — the only path inside a wholly-`:+`/`:-` subtree that would lie to
  the operator (a green-`:added` cascade with two muted `:same` empty
  slots). The renderer treats empty containers as leaf rows (see
  `render-node`'s `empty?` branch); the engine must classify them as
  leaves too. Emit the empty container as a single leaf op so the
  absent↔empty-collection transition surfaces honestly. The check is
  `container?` + `empty?` (NOT type-specific) because the equivalence
  covers all four collection kinds, and ordering it before the
  recursive branches keeps the per-kind walks for the non-empty case
  untouched."
  [path op value]
  (cond
    (and (container? value) (empty? value))
    [{:path path :op op :value value}]

    (map? value)
    (mapcat (fn [[k cv]] (expand-leaf-paths (conj (vec path) k) op cv)) value)

    (set? value)
    (mapcat (fn [el] (expand-leaf-paths (conj (vec path) el) op el)) value)

    (or (vector? value) (sequential? value))
    (mapcat (fn [[i cv]] (expand-leaf-paths (conj (vec path) i) op cv))
            (map-indexed vector value))

    :else
    [{:path path :op op :value value}]))

(defn- container-ancestors
  "Return every prefix of `path` (including `[]` the root, EXCLUDING
  `path` itself) as a vector of paths from shallowest to deepest. Used
  to mark every ancestor of a change as `:children`. The root `[]` is
  included so the top-level container picks up the `:children`
  classification + a change count whenever any descendant differs."
  [path]
  (->> (range 0 (count path))
       (mapv (fn [i] (vec (take i path))))))

(defn- mark-wholly-changed
  "Per R5 (revised): walk down from root, when EVERY descendant slot
  carries `:added`, the container reclassifies to `:added` and gets
  added to `:wholly-changed-roots`. Same rule mirrored for `:removed`.

  Implementation: traverse the AFTER-side containers (for `:added`) +
  the BEFORE-side containers (for `:removed`). For each container,
  check whether every descendant leaf-path under it carries a matching
  op in `path-ops`. If so, mark this container as the wholly-changed
  root, and ELIDE deeper roots — only the shallowest wholly-changed
  ancestor counts.

  The root path `[]` is explicitly excluded from wholly-changed
  promotion (rf2-9d4j8). Empty→populated cold-boot epochs would
  otherwise see ALL per-key chrome (glyph + stripe) suppressed under a
  single root-level reclassification, which contradicts the operator's
  read on a cold-boot diff: each top-level key is a discrete addition
  and wants its own gutter chrome. Nested containers can still qualify
  as wholly-changed (e.g. `{} → {:user {:id 7}}` marks `[:user]`).

  ## Sets are member-keyed — a swap is NOT wholly-changed (rf2-l0us2)

  Editscript keys SET members by VALUE, not by a positional/key slot
  shared across both sides (`#{:a} → #{:b}` emits `[[:a] :-] [[:b] :+]`).
  So a set whose membership SWAPPED has every BEFORE-member at a
  `:removed` leaf and every AFTER-member at an `:added` leaf — two
  DISJOINT path-sets. The before-side uniformity walk then sees the set
  as 'all members removed' and the after-side walk sees it as 'all
  members added', and BOTH falsely promote the set container to wholly-
  changed. The renderer paints that as a struck-through whole key (the
  'sea of red' from the bead repro: `:tags #{:door/locked}` →
  `#{:door/closed}` rendered as `:tags` removed, not `-:door/locked
  +:door/closed` with `:tags` intact).

  Concretely: a set is wholly-changed ONLY when the OPPOSITE side is
  empty or absent — a genuine `#{} → #{…}` cold-boot or `#{…} → #{}`
  clear, where there is no surviving member to anchor a member-level
  diff. When the opposite side still holds members, the membership delta
  IS the diff and the per-member `:added` / `:removed` chrome (which the
  renderer's `children-of-pair` set union already emits) must show with
  the key intact.

  Implementation: `collect-leaves` (the walker that feeds the uniformity
  test) takes the OPPOSITE side too. When it reaches a SET, it collects
  the UNION of both sides' members. A member-swapped set then contributes
  BOTH a `:removed` leaf (the gone member) AND an `:added` leaf (the new
  member), so the uniformity test fails AT THE SET and at every ANCESTOR
  of it — no false promotion anywhere up the tree (an ancestor MAP whose
  only changed descendant is a swapped set was the deeper trap a set-only
  gate missed). When the opposite set is empty/absent the union equals
  the present side's members (all one op), so cold-boot / clear sets still
  promote correctly. Maps and vectors are unchanged: their slots are keyed
  by a shared key/index, so the one-sided walk was always correct for
  them — the union is taken only for sets."
  [before after path-ops]
  (let [collect-leaves
        ;; `opposite` is the value at the SAME path on the other side of
        ;; the diff (`missing-sentinel` when absent). Only sets consult it
        ;; (member-keyed → disjoint paths across sides); maps/vectors walk
        ;; `data` alone exactly as before.
        (fn collect-leaves [data opposite path]
          (cond
            ;; rf2-bufw2 — an empty container is a terminal leaf (it has
            ;; no descendant slots), exactly as `expand-leaf-paths`
            ;; treats it. The two walkers MUST agree on what a leaf is:
            ;; if `collect-leaves` skipped an empty-collection slot, a
            ;; container whose only changed descendants are real `:added`
            ;; leaves alongside an UNCHANGED (`:same`) empty-collection
            ;; sibling would be falsely promoted to wholly-changed
            ;; `:added` (the empty slot being invisible to the
            ;; uniformity check). Emitting it as a leaf lets
            ;; `check-uniform` see its `path-ops` op (`:same` when
            ;; unchanged, `:added`/`:removed` when inside a genuinely
            ;; wholly-changed subtree) and decide correctly.
            (and (container? data) (empty? data))
            [path]

            (map? data)
            (mapcat (fn [[k cv]]
                      (collect-leaves cv
                                      (if (map? opposite)
                                        (get opposite k missing-sentinel)
                                        missing-sentinel)
                                      (conj (vec path) k)))
                    data)

            ;; rf2-l0us2 — sets are member-keyed, so a swap puts each
            ;; side's members at DISJOINT paths. Collect the UNION of both
            ;; sides' members so a swapped set contributes both a removed
            ;; and an added leaf, breaking the false uniformity at the set
            ;; AND every ancestor.
            (set? data)
            (let [opposite-set (when (set? opposite) opposite)
                  members      (into data (or opposite-set #{}))]
              (mapcat (fn [el]
                        (collect-leaves el missing-sentinel
                                        (conj (vec path) el)))
                      members))

            (or (vector? data) (sequential? data))
            (let [opp-vec (when (or (vector? opposite) (sequential? opposite))
                            (vec opposite))]
              (mapcat (fn [[i cv]]
                        (collect-leaves cv
                                        (if (and opp-vec (< i (count opp-vec)))
                                          (nth opp-vec i)
                                          missing-sentinel)
                                        (conj (vec path) i)))
                      (map-indexed vector data)))

            :else
            [path]))
        check-uniform
        (fn [data opposite root-path target-op]
          (let [leaves (collect-leaves data opposite root-path)]
            (and (seq leaves)
                 (every? (fn [lp]
                           (= target-op (:op (get path-ops lp))))
                         leaves))))
        ;; `opposite-root` is the OTHER side's whole value — the after-
        ;; side walk (`:added`) threads `before`, the before-side walk
        ;; (`:removed`) threads `after` — so `check-uniform` can resolve
        ;; the counterpart at any path via `value-at`.
        walk-containers
        (fn walk-containers [data path target-op opposite-root acc]
          (let [opposite (value-at opposite-root path)]
            (cond
              (not (container? data))
              acc

              ;; Root path `[]` never qualifies as a wholly-changed root —
              ;; recurse into children instead so nested containers can
              ;; still be marked (rf2-9d4j8).
              (and (= [] path) (check-uniform data opposite path target-op))
              (cond
                (map? data)
                (reduce-kv (fn [acc k cv]
                             (walk-containers cv (conj (vec path) k)
                                              target-op opposite-root acc))
                           acc data)

                (or (vector? data) (sequential? data))
                (reduce (fn [acc [i cv]]
                          (walk-containers cv (conj (vec path) i)
                                           target-op opposite-root acc))
                        acc (map-indexed vector data))

                :else acc)

              (check-uniform data opposite path target-op)
              (conj acc path)

              (map? data)
              (reduce-kv (fn [acc k cv]
                           (walk-containers cv (conj (vec path) k)
                                            target-op opposite-root acc))
                         acc data)

              (or (vector? data) (sequential? data))
              (reduce (fn [acc [i cv]]
                        (walk-containers cv (conj (vec path) i)
                                         target-op opposite-root acc))
                      acc (map-indexed vector data))

              :else acc)))
        added-roots
        (when (container? after)
          (walk-containers after [] :added before #{}))
        removed-roots
        (when (container? before)
          (walk-containers before [] :removed after #{}))
        all-roots (into (or added-roots #{}) (or removed-roots #{}))
        ;; Elide deeper roots — when a path is wholly-changed AND its
        ;; ancestor is also wholly-changed, drop the deeper one (the
        ;; parent's reclassification subsumes it).
        minimal
        (reduce (fn [acc root]
                  (if (some (fn [other]
                              (and (not= other root)
                                   (= other (vec (take (count other) root)))
                                   (< (count other) (count root))))
                            all-roots)
                    acc
                    (conj acc root)))
                #{}
                all-roots)]
    minimal))

(defn- container-paths-from-leaves
  "Given the set of leaf paths in path-ops, derive every ancestor
  container path that carries a `:children` op. Returns a map
  `{[path] {:op :children :change-count N}}` where N counts the number
  of changed (non-`:same`) descendants directly + indirectly under this
  path. Excludes the empty root path unless explicitly changed."
  [path-ops]
  (let [non-same-leaves
        (->> path-ops
             (remove (fn [[_p {:keys [op]}]] (= op :same)))
             (map first))]
    (reduce
      (fn [acc leaf-path]
        (reduce
          (fn [acc' anc]
            (update acc' anc
                    (fn [cur]
                      (if cur
                        (update cur :change-count (fnil inc 0))
                        {:op :children :change-count 1}))))
          acc
          (container-ancestors leaf-path)))
      {}
      non-same-leaves)))

(defn- compare-path
  "Total-order comparator for two path vectors that may carry MIXED
  segment types (rf2-n83r8).

  Clojure's default vector comparator compares element-wise using each
  element's natural `compare`, which throws `ClassCastException` the
  moment two paths share a prefix and diverge into segments of
  different types at the same index (e.g. `[:flow :phases 2]` vs
  `[:flow :phases :foo]`). The flat-rows pipeline currently never
  produces such a pair — type-change reclassification (R7) emits a
  single `:modified` row at the container path and short-circuits
  per-leaf descent — but the latent fragility is real: any future
  evolution that surfaces mixed-type siblings under a shared prefix
  would crash the sort at consumer time, well downstream of the
  Editscript `try/catch` at `raw-edits`.

  Strategy (bead-suggested option 2, Mike-confirmed): depth (path
  length) first, then per-segment lexicographic comparison of each
  segment's `pr-str`. Properties:

    - Total — any two segments compare via their string serialisation,
      which is defined for every Clojure value.
    - Stable across types — `[:a 10]` < `[:a 2]` lexicographically
      (`\"10\"` < `\"2\"`); this is a known property of string-based
      ordering of numerics. Acceptable for a dev-tool diff lens; the
      operator reads the path text directly so ordering matches the
      text-display order.
    - Pure-data, no allocation beyond the two `pr-str` strings per
      comparison; per-sort overhead is O(n log n × depth) string
      compares, well within the per-epoch diff budget.

  Used by both `:flat-rows` sort sites in `project` to guarantee they
  never CCE regardless of segment-type mix."
  [a b]
  (let [la (count a)
        lb (count b)]
    (if (not= la lb)
      (compare la lb)
      (loop [i 0]
        (if (>= i la)
          0
          (let [c (compare (pr-str (nth a i)) (pr-str (nth b i)))]
            (if (zero? c)
              (recur (inc i))
              c)))))))

(defn- flat-rows-from-path-ops
  "Build the legacy flat-diff lens — one row per non-`:same` leaf op.
  Each row is a map `{:path :op :before :after}`. Used by the `:diff`
  mode (pure-diff) renderer."
  [path-ops]
  (->> path-ops
       (remove (fn [[_p {:keys [op]}]] (= op :same)))
       (remove (fn [[_p {:keys [op]}]] (= op :same-shifted)))
       (mapv (fn [[path {:keys [op before after]
                          :rf.xray.diff/keys [redaction-side type-change?]}]]
               (cond-> {:path  path
                        :op    op
                        :before before
                        :after  after}
                 redaction-side
                 (assoc :rf.xray.diff/redaction-side redaction-side)
                 type-change?
                 (assoc :rf.xray.diff/type-change? true))))
       (sort-by :path compare-path)
       vec))

(defn- resolve-vector-removals
  "rf2-yucxn — recover the true `{:before-index :before-value}` for every
  `:-` edit Editscript emits at a single vector/list parent.

  Editscript applies `:-` edits SEQUENTIALLY against a progressively-
  shrinking sequence: each `:-` at edit-index `i` removes the element
  CURRENTLY occupying index `i`, AFTER all prior `:-` at this parent have
  already been applied. A contiguous tail deletion therefore repeats the
  SAME edit-index (`[1 2 3] → [1]` ⇒ `[[1] :-] [[1] :-]`), and a scattered
  deletion uses post-shift indices (`[:a :b :c :d] → [:a :c]` ⇒ `[[1] :-]
  [[2] :-]`). Resolving `(value-at before [parent i])` independently per
  edit (the pre-fix approach) read the WRONG element for every edit after
  the first — reporting one before-value repeatedly and dropping the
  genuinely-removed tail elements.

  We replay the edits against a live mutable index map: `survivors` is the
  vector of ORIGINAL before-indices still present; each `:-` at edit-index
  `i` removes (and records) `survivors[i]`, then drops it from `survivors`.
  The recorded original index resolves the before-value via `(nth bvec _)`.

  `edits` are the raw `:-` edits at THIS parent in Editscript order; `bvec`
  is the parent's before-side sequence as a vector. Returns a vector of
  `{:before-index :before-value}` in before-index order. Pure."
  [edits bvec]
  (let [edit-idxs (mapv (fn [e] (peek (first e))) edits)
        recovered (:removed (replay-vector-deletes (count bvec) edit-idxs))]
    (->> recovered
         sort
         (mapv (fn [orig] {:before-index orig
                           :before-value (nth bvec orig)})))))

(defn project
  "Compute the diff projection for `(before, after)`. Returns a map per
  the namespace docstring. Pure."
  [before after]
  (if (identical? before after)
    {:path-ops             {}
     :container-ops        {}
     :flat-rows            []
     :wholly-changed-roots #{}
     :shift-suffix         {}}
    (let [raw     (raw-edits before after)
          ;; A `:-` edit at a vector parent removes a before-element by
          ;; before-index. That index identity does NOT correspond to a
          ;; stable after-side path — the surviving elements shift up.
          ;; Recording a path-op at the after-side `[parent-path
          ;; before-idx]` would collide with the shifted element now
          ;; occupying that slot. We therefore route vector deletions
          ;; into a separate `:vector-removals` channel and leave the
          ;; after-path leaf classifications to the shift walker.
          vector-parent?
          (fn [path]
            (when (seq path)
              (let [parent (value-at before (vec (butlast path)))]
                (or (vector? parent)
                    (and (sequential? parent) (not (map? parent)) (not (set? parent)))))))
          ;; Split raw edits into vector-`:-` deletions (per parent) and
          ;; everything else. The deletions are grouped by parent path IN
          ;; EDIT ORDER so `resolve-vector-removals` can replay them against
          ;; the live before-sequence (rf2-yucxn — a single post-shift
          ;; before-index resolution per edit was wrong for multi-element
          ;; / scattered deletions).
          [vec-del-edits other-edits]
          (reduce
            (fn [[dels oth] [path op _value :as edit]]
              (if (and (= op :-) (vector-parent? path))
                [(update dels (vec (butlast path)) (fnil conj []) edit) oth]
                [dels (conj oth edit)]))
            [{} []]
            raw)
          vector-removals
          (reduce-kv
            (fn [acc parent-path edits]
              (let [bvec (vec (value-at before parent-path))]
                (assoc acc parent-path
                       (resolve-vector-removals edits bvec))))
            {}
            vec-del-edits)
          ;; Step 1 — expand each non-vector-deletion raw edit into
          ;; per-leaf ops at every path the operator can navigate to in
          ;; the AFTER tree.
          per-leaf
          (mapcat
            (fn [[path op value]]
              (case op
                :+ (expand-leaf-paths path :added value)
                :- (let [removed-val (value-at before path)]
                     (if (container? removed-val)
                       (expand-leaf-paths path :removed removed-val)
                       [{:path path :op :removed :value removed-val}]))
                :r [{:path path :op :modified
                     :before (value-at before path)
                     :after  value}]
                :s [{:path path :op :modified
                     :before (value-at before path)
                     :after  (value-at after path)}]
                []))
            other-edits)
          ;; Step 2 — re-classify modifieds via the leaf-op rules so R7
          ;; type-change + R8 redaction branches surface in the op map.
          path-ops
          (reduce
            (fn [acc {:keys [path op value before-val after-val]
                      :as entry}]
              (cond
                (= op :added)
                (assoc acc path {:op :added :after (or value (value-at after path))})

                (= op :removed)
                (assoc acc path {:op :removed :before (or value (value-at before path))})

                (= op :modified)
                (let [b (value-at before path)
                      a (value-at after path)
                      classified (leaf-op b a)]
                  (assoc acc path classified))

                :else
                acc))
            {}
            per-leaf)
          ;; Step 3 — R6 vector shift suffixes. For each vector-typed
          ;; parent that received +/- edits, compute the (after-index →
          ;; before-index) map and emit `:same-shifted` ops for any
          ;; after-index whose identity moved.
          edits-by-vec-parent
          (group-edits-by-vector-parent raw after)
          shift-suffix
          (reduce
            (fn [acc [parent-path edits]]
              (let [;; The shift replay reconstructs survivor before-indices
                    ;; from the BEFORE-side length (rf2-1njv97); the after
                    ;; length is recoverable from the slot vector itself.
                    before-val (value-at before parent-path)
                    before-len (cond
                                 (vector? before-val)     (count before-val)
                                 (sequential? before-val) (count before-val)
                                 :else 0)
                    shifts (shift-suffixes-for-vector before-len edits)]
                (reduce-kv
                  (fn [acc' after-idx before-idx]
                    (assoc acc' (conj (vec parent-path) after-idx) before-idx))
                  acc
                  shifts)))
            {}
            edits-by-vec-parent)
          ;; Inject :same-shifted ops where applicable. We DO NOT clobber
          ;; an existing :added/:modified op — only annotate idle (so far
          ;; absent from path-ops, meaning identical-value) slots whose
          ;; position moved.
          path-ops-with-shifts
          (reduce-kv
            (fn [acc leaf-path before-idx]
              (if (contains? acc leaf-path)
                acc
                (let [v (value-at after leaf-path)]
                  (assoc acc leaf-path {:op :same-shifted
                                        :before v
                                        :after v
                                        :rf.xray.diff/was-index before-idx}))))
            path-ops
            shift-suffix)
          ;; Step 4 — wholly-changed reclassification (R5).
          wholly-changed
          (mark-wholly-changed before after path-ops-with-shifts)
          ;; Step 5 — container-ancestor ops (:children) derived from the
          ;; final path-ops map.
          container-ops
          (container-paths-from-leaves path-ops-with-shifts)
          ;; Step 6 — overlay wholly-changed roots, swapping their
          ;; `:children` op to `:added` / `:removed`.
          container-ops'
          (reduce
            (fn [acc root]
              (let [op (or (:op (get path-ops-with-shifts root))
                           ;; Wholly-changed roots whose path didn't
                           ;; appear in path-ops as a leaf — read the
                           ;; op from a representative descendant.
                           (some-> path-ops-with-shifts
                                   (->> (filter (fn [[p _]]
                                                  (= root (vec (take (count root) p)))))
                                        first
                                        second
                                        :op)))]
                (assoc acc root
                       (assoc (get acc root {:change-count 1})
                              :op op
                              :rf.xray.diff/wholly-changed? true))))
            container-ops
            wholly-changed)
          ;; Step 7 — flat-rows for the pure-diff lens. Combines the
          ;; path-op rows with the vector-removal rows (which the
          ;; AFTER-path classifier can't surface because the removed
          ;; element has no stable after-path).
          flat-rows (into (flat-rows-from-path-ops path-ops-with-shifts)
                          (mapcat
                            (fn [[parent-path removals]]
                              (map (fn [{:keys [before-index before-value]}]
                                     {:path  (conj (vec parent-path) before-index)
                                      :op    :removed
                                      :before before-value
                                      :rf.xray.diff/vector-removal? true})
                                   removals))
                            vector-removals))]
      {:path-ops             path-ops-with-shifts
       :container-ops        container-ops'
       ;; rf2-n83r8 — `compare-path` is mixed-type safe; see its
       ;; docstring for the rationale and the latent-fragility caveat.
       :flat-rows            (vec (sort-by :path compare-path flat-rows))
       :vector-removals      vector-removals
       :wholly-changed-roots wholly-changed
       :shift-suffix         shift-suffix})))

;; =========================================================================
;; renderer helpers — read the projection
;; =========================================================================

(defn op-at
  "Lookup the op tag at `path` in `projection`. Returns one of `:added /
  :removed / :modified / :same / :same-shifted / :children` (the last
  for container paths whose subtree differs). Returns `:same` for paths
  with no entry."
  [projection path]
  (let [path (vec path)]
    (or (:op (get-in projection [:path-ops path]))
        (:op (get-in projection [:container-ops path]))
        :same)))

(defn entry-at
  "Lookup the full op map at `path`. Returns `nil` for `:same` slots."
  [projection path]
  (let [path (vec path)]
    (or (get-in projection [:path-ops path])
        (get-in projection [:container-ops path]))))

(defn change-count-at
  "Lookup the descendant-change count at the container `path`. Returns
  `0` for paths with no entry. Drives R3-revised's `[N∆]` chip."
  [projection path]
  (or (:change-count (get-in projection [:container-ops (vec path)]))
      0))

;; =========================================================================
;; rf2-5j7ch / rf2-9d4j8 — empty↔populated map replacements are now
;; expanded inside `project` (via `expand-empty-map-replacement` at the
;; raw-edits stage). The previous post-processor `expand-empty-root-
;; replacement` operated on `:flat-rows` only and left `:path-ops` /
;; `:container-ops` carrying a single `[]`-anchored `:modified` op,
;; which made `op-at` return `:same` for every per-key path in the
;; FULL+DIFF lens (rf2-9d4j8). Pre-alpha clean swap: the post-processor
;; is removed and the engine produces per-key rows at every lens.

(defn wholly-changed-ancestor
  "Return the shallowest wholly-changed-root that is an ancestor of
  `path` (or equal to `path`), or nil. Drives R5's
  suppress-descendant-glyphs-and-stripes rule."
  [projection path]
  (let [path (vec path)]
    (->> (:wholly-changed-roots projection)
         (filter (fn [root]
                   (= root (vec (take (count root) path)))))
         (sort-by count)
         first)))

(defn shifted-was-index
  "When `path` carries a `:same-shifted` op, return the original
  before-index for the R6 `(was N)` suffix. Returns nil otherwise."
  [projection path]
  (:rf.xray.diff/was-index (get-in projection [:path-ops (vec path)])))

(defn type-change?
  "True when the op at `path` is `:modified` due to a container kind flip
  (R7). Drives the `← was <type> with N keys` suffix branch."
  [projection path]
  (boolean (:rf.xray.diff/type-change? (get-in projection [:path-ops (vec path)]))))

(defn redaction-side
  "When the op at `path` is `:modified` and one side is the `:rf/redacted`
  sentinel, return `:before` or `:after` indicating which side carries
  the sentinel. Drives R8's curated suffix branch. Returns nil otherwise."
  [projection path]
  (:rf.xray.diff/redaction-side (get-in projection [:path-ops (vec path)])))

(defn vector-removals-at
  "Return the off-path `:-` removals recorded for the vector/list/seq at
  `path` — a vector of `{:before-index :before-value}` maps in
  ascending before-index order — or `nil` when no element was removed
  there.

  A vector/list removal has no stable AFTER-side path (the survivors
  shift up to fill the gap), so the engine routes it into the off-path
  `:vector-removals` channel keyed by the PARENT path instead of emitting
  a `:path-ops` leaf op. The renderer's sequential body walk consumes
  this channel to splice each genuinely-removed element back into the
  rendered list at its true before-index, struck-through, rather than
  index-aligning the raw before/after vectors (rf2-vu42n — index
  alignment mis-attributes the strike to a surviving-shifted member and
  drops the actually-removed one for scattered / mid-vector removals)."
  [projection path]
  (get-in projection [:vector-removals (vec path)]))
