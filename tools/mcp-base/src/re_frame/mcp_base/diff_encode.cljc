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
  (rf2-lwgg8 mechanism 5). Agents recognise the family once.

  ## Patch grammar pinned via Malli (rf2-rgg7d)

  The patch tuple grammar is pinned as a Malli schema (`patch-schema`).
  Each `collect-patches` emission is validated at the encoder boundary
  inside `diff-encode-db-after` so a future encoder change that drifts
  the tuple shape trips the dev-build gate before it reaches a consumer.

  The schema is published as a public def for downstream Malli-based
  decoders (causa-mcp's spec/004-Wire-Pipeline.md) and for the
  cross-MCP wire-vocab conformance test
  (`tools/mcp-conformance/wire-vocab`). Both today re-state the grammar
  in their own source; pinning it here gives them a canonical home to
  refer to once they switch to consume the schema directly.

  Validation is soft-pass when Malli is not resolvable on the runtime
  classpath: mcp-base does not pull Malli into its own deps (consumers
  bring their own — pair2-mcp/story-mcp/causa-mcp all have Malli on the
  classpath via the implementation deps or the story artefact). On
  CLJS the validation branch sits behind a `goog-define`'d
  `validate-patches?` flag so a production build with
  `:closure-defines {re-frame.mcp-base.diff-encode/validate-patches?
  false}` (typically alongside `goog.DEBUG false`) elides the branch
  via Closure DCE. JVM consumers run validation unconditionally — JVM
  paths are dev/server-side, the cost is invisible against the
  surrounding tree-walk."
  (:require [re-frame.mcp-base.vocab :as vocab]))

;; ---------------------------------------------------------------------------
;; Patch grammar — the Malli schema pinned at the encoder boundary.
;; ---------------------------------------------------------------------------

(def patch-schema
  "Malli schema for a single patch tuple emitted by `collect-patches`.

  Shape:

      [:or
       [:tuple [:vector :any] [:= :assoc] :any]
       [:tuple [:vector :any] [:= :dissoc]]]

  - `[<path> :assoc <new-value>]` — a leaf is new or changed.
  - `[<path> :dissoc]`            — a key disappeared.

  `<path>` is a vector of keys reaching from the root of the
  diffed value down to the leaf being assoc'd / dissoc'd; an empty
  vector denotes the root (root replacement / no-op dissoc).

  Published as a public def so downstream decoders (causa-mcp
  spec/004-Wire-Pipeline.md, the cross-MCP wire-vocab conformance
  test) can consume the canonical schema rather than re-stating the
  grammar."
  [:or
   [:tuple [:vector :any] [:= :assoc] :any]
   [:tuple [:vector :any] [:= :dissoc]]])

(def patches-schema
  "Malli schema for the full patch vector — a sequential of
  `patch-schema`. Convenience alias used at the encoder boundary so
  validation walks the whole emission once rather than once per
  tuple."
  [:sequential patch-schema])

;; ---------------------------------------------------------------------------
;; Validation gate — soft-pass when Malli is absent; goog-define-elidable
;; on CLJS prod builds.
;; ---------------------------------------------------------------------------

#?(:cljs (goog-define ^boolean validate-patches?
           ;; @define {boolean}
           ;; Defaults to `true` (dev / test builds). Production CLJS
           ;; builds set this to false via
           ;; `:closure-defines {re-frame.mcp-base.diff-encode/validate-patches?
           ;; false}` (typically alongside `goog.DEBUG false`); Closure
           ;; DCE then prunes the validation branch from
           ;; `diff-encode-db-after`.
           true)
   :clj  (def ^:const validate-patches?
           "JVM-side: validation always on. JVM consumers (story-mcp,
           causa-mcp) run on dev / server hosts where the tree-walk
           cost is invisible against the surrounding diff. The
           compile-time elision only meaningfully applies under CLJS
           `:advanced`."
           true))

(defn- resolve-malli-validate
  "Resolve `malli.core/validate` if Malli is on the classpath; return
  `nil` otherwise. Mirrors `re-frame.epoch/resolve-malli-validate`
  and `re-frame.http-encoding`'s resolve-pattern — keep mcp-base
  Malli-free at its own dep boundary; downstream consumers provide
  Malli on the runtime classpath."
  []
  #?(:clj  (try (requiring-resolve 'malli.core/validate)
                (catch Throwable _ nil))
     :cljs (try (resolve 'malli.core/validate)
                (catch :default _ nil))))

(defn- validate-patches!
  "Validate `patches` against `patches-schema` and throw ex-info on
  mismatch. No-op when Malli is not resolvable on the runtime
  classpath (soft-pass — mirrors `re-frame.schemas.malli`'s default
  validator). Called by `diff-encode-db-after` at the wire boundary."
  [patches]
  (when validate-patches?
    (when-let [validate (resolve-malli-validate)]
      (when-not (validate patches-schema patches)
        (throw (ex-info "diff-encode patch grammar violated"
                        {:rf.error/code  :rf.error/bad-diff-patches
                         :schema         patches-schema
                         :patches        patches})))))
  nil)

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
      (validate-patches! patches)
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
