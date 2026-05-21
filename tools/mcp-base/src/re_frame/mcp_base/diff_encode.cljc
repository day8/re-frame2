(ns re-frame.mcp-base.diff-encode
  "Path-keyed structural diff for epoch records (rf2-1wdzp) projected
  into path-headed cluster sections (rf2-qeous).

  ## What this does

  Each `:rf/epoch-record` carries `:db-before` and `:db-after` —
  near-identical full app-db snapshots. `pr-str` doesn't preserve
  structural sharing, so on the wire the pair is roughly 2× app-db
  per epoch; a 50-epoch default `:epochs` slice ⇒ up to 100× app-db.

  The transform replaces `:db-after` with a path-headed cluster
  projection of a path-keyed structural diff against `:db-before`:

      {:db-before <full>
       :db-after  {:rf.mcp/diff-from :db-before
                   :sections [{:section-path [:cart :items]
                               :section-kind :modified
                               :patches      [[<path> :assoc <new-value>]
                                              [<path> :dissoc]]}
                              {:section-path [:checkout :state]
                               :section-kind :modified
                               :patches      [...]}]}}

  Each section heads N patches with a breadcrumb path + a kind
  summary (`:added` / `:removed` / `:modified`). A patch inside a
  section is a 2- or 3-element vector — `[path :assoc v]` for new /
  changed leaves, `[path :dissoc]` for keys that disappeared. The
  decoder flattens sections back to a patch list and applies each
  patch in order via `assoc-in` / `update-in` to reconstruct
  `:db-after`.

  ## Why sections-per-cluster (rf2-qeous)

  Agent queries like 'what did this cascade do?' want scoped
  cluster summaries — the path breadcrumb signals 'these N changes
  belong together'. The flat patch list (the predecessor shape)
  forced agents to re-cluster mentally. The sections projection
  mirrors Causa's panel `sections-per-cluster` decomposition
  (rf2-gfxmk Phase 1 of rf2-abts7) — same path-headed clusters; only
  the per-section body shape differs (patches here vs annotated
  subtree there).

  The patch list is preserved per-section as the round-trip
  primitive: concatenating every section's `:patches` reproduces
  the flattened patch list, which `apply-patches` replays
  losslessly. See `section_grouping.cljc/sections->patches` for the
  inverse projection.

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
  `:rf.mcp/summary` (rf2-tygdv), and `:rf.mcp/dedup-table` (rf2-lwgg8
  mechanism 5). Agents recognise the family once.

  ## Patch grammar pinned via Malli (rf2-rgg7d)

  The patch tuple grammar is pinned as a Malli schema (`patch-schema`).
  Each `collect-patches` emission is validated at the encoder boundary
  inside `diff-encode-db-after` so a future encoder change that drifts
  the tuple shape trips the dev-build gate before it reaches a consumer.

  The schema is published as a public def for downstream Malli-based
  decoders and for the cross-MCP wire-vocab conformance test
  (`tools/mcp-conformance/wire-vocab`). Both today re-state the grammar
  in their own source; pinning it here gives them a canonical home to
  refer to once they switch to consume the schema directly.

  Validation is soft-pass when Malli is not resolvable on the runtime
  classpath: mcp-base does not pull Malli into its own deps (consumers
  bring their own — re-frame2-pair-mcp/story-mcp both have Malli on the
  classpath via the implementation deps or the story artefact). On
  CLJS the validation branch sits behind a `goog-define`'d
  `validate-patches?` flag so a production build with
  `:closure-defines {re-frame.mcp-base.diff-encode/validate-patches?
  false}` (typically alongside `goog.DEBUG false`) elides the branch
  via Closure DCE. JVM consumers run validation unconditionally — JVM
  paths are dev/server-side, the cost is invisible against the
  surrounding tree-walk."
  (:require [re-frame.mcp-base.section-grouping :as sg]
            [re-frame.mcp-base.vocab :as vocab]))

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

  Published as a public def so downstream decoders (the cross-MCP
  wire-vocab conformance test) can consume the canonical schema
  rather than re-stating the grammar."
  [:or
   [:tuple [:vector :any] [:= :assoc] :any]
   [:tuple [:vector :any] [:= :dissoc]]])

(def patches-schema
  "Malli schema for the full patch vector — a sequential of
  `patch-schema`. Convenience alias used at the encoder boundary so
  validation walks the whole emission once rather than once per
  tuple."
  [:sequential patch-schema])

(def section-schema
  "Malli schema for one section in the path-headed cluster projection
  (rf2-qeous).

  Shape:

      {:section-path [<key>...]            ; the cluster breadcrumb
       :section-kind :added|:removed|:modified
       :patches      [<patch>...]}         ; subset of the flat patch list

  Published as a public def so downstream decoders (the cross-MCP
  wire-vocab conformance test) can consume the canonical schema
  rather than re-stating the grammar."
  [:map
   [:section-path [:vector :any]]
   [:section-kind [:enum :added :removed :modified]]
   [:patches      patches-schema]])

(def sections-schema
  "Malli schema for the full sections vector — sequential of
  `section-schema`. Convenience alias used at the encoder boundary
  so validation walks the whole emission once."
  [:sequential section-schema])

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
           "JVM-side: validation always on. JVM consumers (story-mcp)
           run on dev / server hosts where the tree-walk cost is
           invisible against the surrounding diff. The compile-time
           elision only meaningfully applies under CLJS `:advanced`."
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
        (throw (ex-info ":rf.error/bad-diff-patches"
                        {:rf.error/id    :rf.error/bad-diff-patches
                         :where          'mcp-base/diff-encode-db-after
                         :recovery       :no-recovery
                         :reason         "diff-encode patch grammar violated"
                         :schema         patches-schema
                         :patches        patches})))))
  nil)

(defn- validate-sections!
  "Validate `sections` against `sections-schema` and throw ex-info on
  mismatch. Soft-pass when Malli is absent, mirrors
  `validate-patches!`. Called by `diff-encode-db-after` at the wire
  boundary after the section-grouping pass (rf2-qeous)."
  [sections]
  (when validate-patches?
    (when-let [validate (resolve-malli-validate)]
      (when-not (validate sections-schema sections)
        (throw (ex-info ":rf.error/bad-diff-sections"
                        {:rf.error/id   :rf.error/bad-diff-sections
                         :where         'mcp-base/diff-encode-db-after
                         :recovery      :no-recovery
                         :reason        "diff-encode section grammar violated"
                         :schema        sections-schema
                         :sections      sections})))))
  nil)

(declare collect-patches-into)

(defn- collect-map-patches-into
  "Like `collect-map-patches`, but threads an explicit accumulator
  `acc` rather than building a fresh vector and `into`-ing it back at
  every recursion site. Two-pass: walks `b` once to emit `:assoc`
  patches for added / changed keys (recursing on map/map pairs), then
  walks `a` once to emit `:dissoc` patches for keys absent from `b`.

  Threading the accumulator (rf2-c2k9k / F12) avoids the intermediate
  patches vector each recursion site previously allocated for `into
  acc` — on deep app-db diffs (5-10 nesting levels typical) every
  recursion now `conj`s directly into the parent accumulator rather
  than allocating a fresh vector and copying it back. Big-O is
  unchanged; the saving is allocation pressure, mostly visible on the
  hot `watch-epochs` / `trace-window` slice."
  [acc a b path]
  (let [after-assocs
        (reduce-kv
          (fn [acc k bv]
            (let [av (get a k ::absent)
                  p  (conj path k)]
              (cond
                ;; Key added.
                (= av ::absent)
                (conj acc [p :assoc bv])
                ;; Unchanged — skip.
                (= av bv)
                acc
                ;; Both maps: recurse, threading acc.
                (and (map? av) (map? bv))
                (collect-patches-into acc av bv p)
                ;; Otherwise: leaf replacement.
                :else
                (conj acc [p :assoc bv]))))
          acc
          b)]
    (reduce-kv
      (fn [acc k _av]
        (if (contains? b k)
          acc
          (conj acc [(conj path k) :dissoc])))
      after-assocs
      a)))

(defn- collect-patches-into
  "Internal accumulator-threading entry point for the recursion. The
  public `collect-patches` calls this with an empty seed vector; the
  internal `collect-map-patches-into` calls this for each
  map-into-map descent, sharing the parent's accumulator rather than
  allocating a fresh sub-vector."
  [acc a b path]
  (cond
    (= a b) acc
    (and (map? a) (map? b)) (collect-map-patches-into acc a b path)
    :else (conj acc [path :assoc b])))

(defn collect-patches
  "Patch-list factory. Two maps recurse via `collect-map-patches-into`;
  any other shape change is a single root-level `:assoc` replacement.
  Exposed for tests and for advanced consumers that want to diff
  arbitrary structures (not just `:db-after` vs `:db-before`)."
  [a b path]
  (collect-patches-into [] a b path))

(defn apply-patches
  "Apply a vector of patches to `base`, returning the reconstructed
  value. Patches are `[path :assoc v]` or `[path :dissoc]`. Root-path
  patches (path `[]`) replace `base` outright (for `:assoc`) or are a
  no-op (for `:dissoc`, by convention).

  ## Decoder-boundary validation (rf2-8e61v)

  `apply-patches` is the wire-decoder entry point: a malformed patch
  reaching this fn is a contract violation by an upstream encoder (a
  drifted re-frame2 mcp-base, a third-party decoder rolling its own
  shape, a transport corruption). Mirror `diff-encode-db-after`'s
  encoder-boundary gate: validate `patches` against `patches-schema`
  and throw `:rf.error/bad-diff-patches` ex-info on mismatch.

  The encoder side already pinned the grammar; this is the symmetric
  decode-side gate so the cross-MCP wire convention surfaces the
  drift rather than silently no-op'ing on the malformed tuple (the
  previous behaviour fell through the `cond` to `:else acc` and
  dropped corrupted patches without a peep).

  Soft-pass behaviour mirrors the encoder: when Malli is not
  resolvable on the runtime classpath, validation is skipped. The
  CLJS `validate-patches?` `goog-define` toggle elides both gates
  together in `:advanced` builds."
  [base patches]
  (validate-patches! patches)
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
  "Replace an epoch's `:db-after` with a path-headed cluster
  projection of a path-keyed structural diff against its own
  `:db-before` (rf2-qeous). Returns the epoch with `:db-after`
  shaped as
  `{:rf.mcp/diff-from :db-before :sections [{...}{...}]}`.

  Each section bundles the patches inside one path-headed cluster —
  the agent reads `:section-path` + `:section-kind` for cluster
  intent and drills into `:patches` for leaf detail. See
  `re-frame.mcp-base.section-grouping` for the cluster algorithm.

  When `:db-before` is missing (older epoch from a runtime that pruned
  it, or a synthetic record), the function leaves the epoch unchanged
  — there's nothing to diff against and silently shipping a half-shape
  would corrupt the agent's view."
  [epoch]
  (if-not (and (map? epoch)
               (contains? epoch :db-before)
               (contains? epoch :db-after))
    epoch
    (let [patches  (collect-patches (:db-before epoch) (:db-after epoch) [])
          _        (validate-patches! patches)
          sections (sg/group-patches-into-sections patches)
          _        (validate-sections! sections)]
      (assoc epoch :db-after
             {vocab/diff-from-key :db-before
              :sections           sections}))))

(defn decode-db-after
  "Reverse `diff-encode-db-after`. Given an epoch whose `:db-after` is
  a `{:rf.mcp/diff-from :db-before :sections [...]}` marker,
  reconstruct the full `:db-after` from the epoch's `:db-before` and
  the per-section patch lists (flattened in section order).

  Idempotent on already-full epochs (the marker check returns the
  input unchanged when `:db-after` isn't a diff). Provided for
  agent-host round-trip parity and for the unit tests.

  ## Decoder-boundary section validation (rf2-j6oay)

  Mirrors the encoder's `validate-sections!` gate. `sections->patches`
  is a permissive `mapcat :patches` — a section with malformed
  `:section-path` / `:section-kind` slots, or extra/missing slots,
  would slip through to `apply-patches` whose own gate only validates
  the flattened `:patches` list. Validating `sections` here gives
  encoder/decoder symmetry per the rf2-8e61v argument: the cross-MCP
  wire convention surfaces drift on the receiving side too, rather
  than silently passing the cosmetic `:section-kind` / `:section-path`
  slots through to an agent-host UI that paints them as truth.

  Soft-pass + `goog-define`-elidable by construction — reuses the
  same `validate-sections!` helper as the encoder."
  [epoch]
  (let [da (when (map? epoch) (:db-after epoch))]
    (if-not (and (map? da)
                 (= :db-before (get da vocab/diff-from-key)))
      epoch
      (let [sections  (:sections da)
            _         (validate-sections! (or sections []))
            patches   (sg/sections->patches (or sections []))
            db-before (:db-before epoch)
            rebuilt   (apply-patches db-before patches)]
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
