(ns re-frame.mcp-base.diff-encode
  "Path-keyed structural diff for epoch records (rf2-1wdzp).

  ## What this does

  Each `:rf/epoch-record` carries `:db-before` and `:db-after` —
  near-identical full app-db snapshots. `pr-str` doesn't preserve
  structural sharing, so on the wire the pair is roughly 2× app-db
  per epoch; a 50-epoch default `:epochs` slice ⇒ up to 100× app-db.

  The transform replaces `:db-after` with a path-keyed structural diff
  against `:db-before`:

      {:db-before <full>
       :db-after  {:rf.mcp/diff-from :db-before
                   :patches [[<path> :assoc <new-value>]
                             [<path> :dissoc]]}}

  A patch is a 2- or 3-element vector — `[path :assoc v]` for new /
  changed leaves, `[path :dissoc]` for keys that disappeared. The
  decoder applies each patch in order via `assoc-in` / `update-in` to
  reconstruct `:db-after`.

  ## Self-contained records

  The diff is intra-record (each epoch's `:db-after` is encoded against
  the SAME record's `:db-before`); records remain self-contained and
  decodable without reference to siblings. The slice can be reordered,
  paginated, or filtered without breaking decode.

  ## Why not `clojure.data/diff`

  Its parallel-vector sparse form for vector diffs (with `nil`
  placeholders meaning \"common at this position\") loses information
  once you only carry one half plus the original — you can't tell
  `nil` (the leaf value `nil`) apart from `nil` (the no-change
  sentinel). Path-keyed patches are unambiguous for any value the
  runtime can produce.

  ## Cross-MCP vocabulary

  The `:rf.mcp/diff-from` marker key follows the same `:rf.mcp/*`
  namespace convention as `:rf.mcp/overflow` (rf2-rvyzy),
  `:rf.mcp/summary` (rf2-tygdv), and causa-mcp's `:rf.mcp/dedup-table`
  (rf2-lwgg8 mechanism 5). Agents recognise the family once."
  (:require [re-frame.mcp-base.vocab :as vocab]))

(declare collect-patches)

(defn- collect-map-patches
  "Generate patches that transform map `a` into map `b` at `path`.
  Recurses into sub-maps; vectors and scalars are treated as leaves
  (replaced wholesale via `:assoc` rather than re-diffed element-wise
  — element-wise vector diff doesn't shrink the wire for the typical
  app-db where vector values are short)."
  [a b path]
  (let [ks (into #{} (concat (keys a) (keys b)))]
    (reduce
      (fn [acc k]
        (let [av (get a k ::absent)
              bv (get b k ::absent)
              p  (conj path k)]
          (cond
            ;; Key removed.
            (= bv ::absent)
            (conj acc [p :dissoc])
            ;; Key added.
            (= av ::absent)
            (conj acc [p :assoc bv])
            ;; Unchanged — skip.
            (= av bv)
            acc
            ;; Both maps: recurse.
            (and (map? av) (map? bv))
            (into acc (collect-patches av bv p))
            ;; Otherwise: leaf replacement.
            :else
            (conj acc [p :assoc bv]))))
      []
      ks)))

(defn collect-patches
  "Patch-list factory. Two maps recurse via `collect-map-patches`; any
  other shape change is a single root-level `:assoc` replacement.
  Exposed for tests and for advanced consumers that want to diff
  arbitrary structures (not just `:db-after` vs `:db-before`)."
  [a b path]
  (cond
    (= a b) []
    (and (map? a) (map? b)) (collect-map-patches a b path)
    :else [[path :assoc b]]))

(defn apply-patches
  "Apply a vector of patches to `base`, returning the reconstructed
  value. Patches are `[path :assoc v]` or `[path :dissoc]`. Root-path
  patches (path `[]`) replace `base` outright (for `:assoc`) or are a
  no-op (for `:dissoc`, by convention)."
  [base patches]
  (reduce
    (fn [acc patch]
      (let [[path op v] patch]
        (cond
          (empty? path)
          (if (= op :assoc) v acc)
          (= op :assoc)
          (assoc-in acc path v)
          (= op :dissoc)
          (let [parent-path (vec (butlast path))
                k           (last path)]
            (if (empty? parent-path)
              (dissoc acc k)
              (update-in acc parent-path dissoc k)))
          :else acc)))
    base
    patches))

(defn diff-encode-db-after
  "Replace an epoch's `:db-after` with a path-keyed structural diff
  against its own `:db-before`. Returns the epoch with `:db-after`
  shaped as `{:rf.mcp/diff-from :db-before :patches [...]}`.

  When `:db-before` is missing (older epoch from a runtime that pruned
  it, or a synthetic record), the function leaves the epoch unchanged
  — there's nothing to diff against and silently shipping a half-shape
  would corrupt the agent's view."
  [epoch]
  (if-not (and (map? epoch)
               (contains? epoch :db-before)
               (contains? epoch :db-after))
    epoch
    (let [patches (collect-patches (:db-before epoch) (:db-after epoch) [])]
      (assoc epoch :db-after
             {vocab/diff-from-key :db-before
              :patches            patches}))))

(defn decode-db-after
  "Reverse `diff-encode-db-after`. Given an epoch whose `:db-after` is
  a `{:rf.mcp/diff-from :db-before :patches [...]}` marker, reconstruct
  the full `:db-after` from the epoch's `:db-before` and the patch list.
  Idempotent on already-full epochs (the marker check returns the input
  unchanged when `:db-after` isn't a diff). Provided for agent-host
  round-trip parity and for the unit tests."
  [epoch]
  (let [da (when (map? epoch) (:db-after epoch))]
    (if-not (and (map? da)
                 (= :db-before (get da vocab/diff-from-key)))
      epoch
      (let [patches   (:patches da)
            db-before (:db-before epoch)
            rebuilt   (apply-patches db-before (or patches []))]
        (assoc epoch :db-after rebuilt)))))

(defn diff-encode-epochs
  "Apply `diff-encode-db-after` to every epoch in `epochs` unless `mode`
  is `:full` (in which case the vector passes through unchanged). Each
  record is encoded against ITS OWN `:db-before` — no cross-record
  dependency; the slice can be reordered, paginated, or filtered
  without breaking decode.

  `mode` is one of:
    :diff — default. Each `:db-after` becomes a structural diff.
    :full — pass through (legacy behaviour, opt-in)."
  [epochs mode]
  (if (= mode :full)
    epochs
    (mapv diff-encode-db-after epochs)))
