(ns re-frame.mcp-base.diff-encode
  "Path-keyed structural diff for epoch records projected
  into path-headed cluster sections.

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

  ## Why sections-per-cluster

  Agent queries like 'what did this cascade do?' want scoped
  cluster summaries — the path breadcrumb signals 'these N changes
  belong together', so an agent reads cluster intent without
  re-clustering a flat patch list mentally. The sections projection
  mirrors Xray's panel `sections-per-cluster` decomposition — same
  path-headed clusters; only the per-section body shape differs
  (patches here vs annotated subtree there).

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
  runtime can produce: a changed vector element rides under a numeric
  INDEX path (`[[:items 0 :qty] :assoc 2]`), which carries the position
  explicitly and never relies on a positional `nil` sentinel.

  ## Vectors: same-length structural diff, length changes whole-leaf

  Maps recurse key-by-key; SAME-LENGTH vectors recurse element-by-element
  under numeric-INDEX paths. A single item update inside a large
  vector/list app-db value (a table row, a card, a queue/log entry) then
  yields an actionable item-level patch (`[[:items 0 :qty] :assoc 2]`)
  rather than a whole-vector replacement — preserving the token-budget
  premise for the dominant in-place-update app-db shapes. Integer index
  keys ride through the existing `assoc-in` replay and the path-generic
  section-grouping unchanged; no decoder change is required.

  A vector LENGTH change (an insert / delete) is emitted as a single
  whole-vector `[path :assoc <new-vector>]` replacement. Element-level
  vector insert/delete semantics are deliberately NOT designed: the
  grammar's numeric `:assoc` reaches an index via `assoc-in` (which only
  overwrites-in-place or grows by one at the tail — it has no shift
  primitive) and `[<index-path> :dissoc]` is a documented no-op against a
  vector parent (`dissoc-in` dissocs only into a MAP parent).
  Same-length diffing is lossless for in-place updates; a length delta
  falls back to the unambiguous whole-vector replacement rather than ship
  a half-designed splice encoding. Non-vector sequentials (lists, lazy
  seqs) are also whole-leaf replaced — only `vector?` collections diff
  structurally, so the index-path replay always targets a vector.

  ## Cross-MCP vocabulary

  The `:rf.mcp/diff-from` marker key follows the same `:rf.mcp/*`
  namespace convention as `:rf.mcp/overflow`,
  `:rf.mcp/summary`, and `:rf.mcp/dedup-table`. Agents recognise the
  family once.

  ## Patch grammar pinned via Malli

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
  "Malli schema for one section in the path-headed cluster projection.

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
  and `re-frame.http.encoding`'s resolve-pattern — keep mcp-base
  Malli-free at its own dep boundary; downstream consumers provide
  Malli on the runtime classpath."
  []
  #?(:clj  (try (requiring-resolve 'malli.core/validate)
                (catch Throwable _ nil))
     :cljs (try (resolve 'malli.core/validate)
                (catch :default _ nil))))

(defn- patch-shape
  "Value-free shape summary of one (malformed) patch tuple, for an
  egress-safe diagnostic. A patch is `[<path> <op> <value?>]`; the
  `<value>` at position 2 is the app-db leaf the egress policy expects
  to stay projected, so it is DELIBERATELY excluded. The path is a
  vector of structural keys (the breadcrumb the encoder walked), the op
  is a grammar keyword, and the arity disambiguates assoc/dissoc — all
  value-free. A non-vector patch reports only its type."
  [patch]
  (if (vector? patch)
    {:path  (when (vector? (nth patch 0 nil)) (nth patch 0 nil))
     :op    (let [op (nth patch 1 nil)] (when (keyword? op) op))
     :arity (count patch)}
    {:type (some-> patch type pr-str)}))

(defn- section-shape
  "Value-free shape summary of one (malformed) section map. A section is
  `{:section-path [...] :section-kind k :patches [...]}`; the nested
  `:patches` can carry app-db leaf values, so it is excluded — only the
  structural breadcrumb, the kind keyword, and a value-free patch count
  are reported. A non-map section reports only its type."
  [section]
  (if (map? section)
    {:section-path (let [p (:section-path section)] (when (vector? p) p))
     :section-kind (let [k (:section-kind section)] (when (keyword? k) k))
     :patch-count  (let [ps (:patches section)] (when (counted? ps) (count ps)))}
    {:type (some-> section type pr-str)}))

(defn- first-bad
  "Walk `data` (the offending sequential batch) and return
  `[bad-index element-summary]` for the FIRST element that fails
  `element-schema`, using `summarize` to render a value-free shape of
  that element. Returns `nil` when `data` is not a sequential batch or
  when no individual element is at fault (the batch shape itself —
  e.g. a non-sequential value — is the violation; the count/shape slots
  already convey that). `summarize` never receives or emits the raw leaf
  value, so the result is egress-safe."
  [validate element-schema data summarize]
  (when (sequential? data)
    (loop [i 0 xs (seq data)]
      (when xs
        (if (validate element-schema (first xs))
          (recur (inc i) (next xs))
          [i (summarize (first xs))])))))

(defn- validate-against!
  "Shared soft-pass Malli gate behind `validate-patches!` /
  `validate-sections!`. Validates `data` against `schema` and throws
  `:rf.error/<error-id>` ex-info on mismatch; no-op when Malli is not
  resolvable on the runtime classpath (soft-pass — mirrors
  `re-frame.schemas.malli`'s default validator) or when
  `validate-patches?` is elided.

  `where` is the calling-site symbol threaded through to the ex-info
  `:where` slot so it reflects the ACTUAL wire boundary that tripped
  rather than a hardcoded one side.

  ## Egress-safe diagnostics (EP-0015)

  The diff-encode wire carries epoch `:db-before` / `:db-after`
  payloads, so the VALUE side of an `:assoc` patch (and any map a
  section's `:patches` carries) can be an app-db leaf the egress policy
  expects to stay projected / redacted. The thrown diagnostic must NOT
  smuggle that value back out — neither under a `:patches` / `:sections`
  ex-data slot, nor in the exception message, nor in any value reachable
  via `pr-str`. So the offending `data` is NEVER stored raw. Instead the
  diagnostic carries value-FREE shape: the error id, boundary `:where`,
  `:recovery`, `:reason`, the static grammar `:schema` (the encoder's
  own definition — no app-db content), a `:count` of the batch, and —
  when the batch is sequential — the first failing element's
  `:bad-index` and a value-free `:shape` (path / op / kind / arity /
  type, never the leaf value). This mirrors `assoc-in-safe`, which
  reports path + parent TYPE instead of the parent VALUE."
  [schema element-schema data where error-id reason summarize]
  (when validate-patches?
    (when-let [validate (resolve-malli-validate)]
      (when-not (validate schema data)
        (let [[bad-index shape] (first-bad validate element-schema data summarize)]
          (throw (ex-info (str error-id)
                          (cond-> {:rf.error/id error-id
                                   :where       where
                                   :recovery    :no-recovery
                                   :reason      reason
                                   :schema      schema
                                   :count       (when (counted? data) (count data))}
                            bad-index (assoc :bad-index bad-index :shape shape))))))))
  nil)

(defn- validate-patches!
  "Validate `patches` against `patches-schema` and throw ex-info on
  mismatch. No-op when Malli is not resolvable on the runtime
  classpath (soft-pass — mirrors `re-frame.schemas.malli`'s default
  validator).

  `where` is the calling-site symbol threaded in by the caller so the
  ex-info `:where` reflects the ACTUAL wire boundary that tripped:
  `'mcp-base/diff-encode-db-after` from the encoder,
  `'mcp-base/apply-patches` from the decoder. Both boundaries call
  this; hardcoding one side misattributes a decode-side throw to the
  encoder and misleads operator triage about which side of the wire
  drifted. The thrown diagnostic is value-free (EP-0015): it reports the
  first bad patch's path / op / arity, never the patch VALUE."
  [patches where]
  (validate-against! patches-schema patch-schema patches where
                     :rf.error/bad-diff-patches
                     "diff-encode patch grammar violated"
                     patch-shape))

(defn- validate-sections!
  "Validate `sections` against `sections-schema` and throw ex-info on
  mismatch. Soft-pass when Malli is absent, mirrors
  `validate-patches!`.

  `where` is the calling-site symbol threaded in by the caller so the
  ex-info `:where` reflects the ACTUAL wire boundary that tripped:
  `'mcp-base/diff-encode-db-after` from the encoder
  (after the section-grouping pass), `'mcp-base/decode-db-after`
  from the decoder. Both boundaries call this; hardcoding one side
  misattributes a decode-side throw to the encoder. The thrown
  diagnostic is value-free (EP-0015): it reports the first bad section's
  breadcrumb / kind / patch-count, never the nested patch VALUEs."
  [sections where]
  (validate-against! sections-schema section-schema sections where
                     :rf.error/bad-diff-sections
                     "diff-encode section grammar violated"
                     section-shape))

(declare collect-patches-into)

(defn- collect-map-patches-into
  "Like `collect-map-patches`, but threads an explicit accumulator
  `acc` rather than building a fresh vector and `into`-ing it back at
  every recursion site. Two-pass: walks `b` once to emit `:assoc`
  patches for added / changed keys (recursing on map/map pairs), then
  walks `a` once to emit `:dissoc` patches for keys absent from `b`.

  Threading the accumulator avoids an intermediate patches vector at
  each recursion site — on deep app-db diffs (5-10 nesting levels
  typical) every recursion `conj`s directly into the parent
  accumulator rather than allocating a fresh vector and copying it
  back. Big-O is the same as a copy-back approach; the saving is
  allocation pressure, mostly visible on the hot `watch-epochs` /
  `trace-window` slice."
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
                ;; A changed EXISTING key: delegate to `collect-patches-into`
                ;; so the dispatch is decided in ONE place — two maps recurse
                ;; key-by-key, two same-length vectors recurse element-wise,
                ;; anything else (incl. an unchanged value,
                ;; which short-circuits to a no-op) is a single leaf
                ;; `[p :assoc bv]` replacement.
                :else
                (collect-patches-into acc av bv p))))
          acc
          b)]
    (reduce-kv
      (fn [acc k _av]
        (if (contains? b k)
          acc
          (conj acc [(conj path k) :dissoc])))
      after-assocs
      a)))

(defn- collect-vector-patches-into
  "Structurally diff two SAME-LENGTH vectors element-by-element, threading
  the accumulator. Each element pair recurses through
  `collect-patches-into` under the numeric-INDEX path `(conj path i)`, so
  a single changed element inside a long vector yields an index-headed
  `[[:items 0 :qty] :assoc 2]` patch instead of replacing the whole
  vector — the actionable item-level patch the token-budget premise needs
  for the common table / card / queue / log app-db shapes.

  ## Same-length only (root + length changes stay whole-leaf)

  Only EQUAL-LENGTH vectors are structurally diffed here. A length change
  (insert / delete) is emitted by the caller as a single whole-vector
  `[path :assoc b]` replacement. Element-level vector insert/delete
  semantics are deliberately NOT designed yet: the patch grammar's
  numeric `:assoc` reaches an index via `assoc-in` (which both grows a
  vector by one at the tail and overwrites in place) but has no shift
  primitive, and `[<index-path> :dissoc]` is a documented no-op against a
  vector parent (`dissoc-in` dissocs only into a MAP parent).
  Same-length diffing covers the dominant in-place-update case losslessly;
  a length delta falls back to the unambiguous whole-vector replacement
  rather than risk a half-designed shift/splice encoding.

  Integer index keys ride through the existing replay
  (`assoc-in` accepts integer vector indices) and section-grouping
  (path-generic) unchanged — no decoder change is needed."
  [acc a b path]
  (let [n (count a)]
    (loop [i   0
           acc acc]
      (if (< i n)
        (let [av (nth a i)
              bv (nth b i)]
          (recur (inc i)
                 ;; Skip the conj of `path`+i and the recursion call for an
                 ;; unchanged element — the common case for tables/queues
                 ;; where most rows hold steady across an epoch.
                 ;; `collect-patches-into` would also no-op via its own
                 ;; `(= a b)` arm; the inline guard avoids the index-conj
                 ;; + call on the hot slice.
                 (if (= av bv)
                   acc
                   (collect-patches-into acc av bv (conj path i)))))
        acc))))

(defn- collect-patches-into
  "Internal accumulator-threading entry point for the recursion. The
  public `collect-patches` calls this with an empty seed vector; the
  internal `collect-map-patches-into` / `collect-vector-patches-into`
  call this for each map-into-map / same-length-vector descent, sharing
  the parent's accumulator rather than allocating a fresh sub-vector.

  Recursion arms:
    - two maps                       → `collect-map-patches-into`
    - two SAME-LENGTH vectors        → `collect-vector-patches-into`
      (element-wise, numeric-index paths)
    - any other change (incl. a length-changed or non-vector sequential)
      → a single whole-leaf `[path :assoc b]` replacement."
  [acc a b path]
  (cond
    (= a b) acc
    (and (map? a) (map? b)) (collect-map-patches-into acc a b path)
    (and (vector? a) (vector? b) (= (count a) (count b)))
    (collect-vector-patches-into acc a b path)
    :else (conj acc [path :assoc b])))

(defn collect-patches
  "Patch-list factory. Two maps recurse via `collect-map-patches-into`;
  two same-length vectors recurse element-wise via
  `collect-vector-patches-into` (numeric-index paths); any
  other shape change (a length-changed vector, a non-vector sequential,
  a scalar↔collection flip, the root) is a single whole-leaf `:assoc`
  replacement. Exposed for tests and for advanced consumers that want to
  diff arbitrary structures (not just `:db-after` vs `:db-before`)."
  [a b path]
  (collect-patches-into [] a b path))

(defn- dissoc-in
  "Remove the key at `path` (a non-empty path vector) from `m`, a
  no-op when the path's parent is absent or is not a map.

  The spec contract for `[<path> :dissoc]` is 'remove the key at
  `path`; a no-op if it does not exist'. The naive
  `(update-in m parent dissoc k)` violates this two ways when the
  parent path doesn't resolve to a map:

    - a MISSING parent manufactures nil branches —
      `(update-in {} [:missing] dissoc :leaf)` ⇒ `{:missing nil}`,
      `(update-in {:a {}} [:a :b] dissoc :c)` ⇒ `{:a {:b nil}}` —
      corrupting the reconstructed `:db-after` into a shape neither
      encoder side emitted;
    - a SCALAR parent throws a host `ClassCastException`
      (`(update-in {:a 1} [:a] dissoc :b)`), surfacing a raw host
      exception at the wire decoder boundary.

  `apply-patches`'s own grammar gate pins the patch SHAPE, but cannot
  prove a patch's parent path exists in THIS `base` — a malformed /
  corrupt / third-party diff replayed against a mismatched base is
  exactly the case this guards. We honour the spec no-op: dissoc only
  when the parent resolves to a map; otherwise return `m` unchanged.

  `path` is always non-empty here (the root `[]` `:dissoc` is handled
  by convention as a no-op in `apply-patches*` before this is called)."
  [m path]
  (let [parent-path (vec (butlast path))
        k           (last path)]
    (if (empty? parent-path)
      (if (map? m) (dissoc m k) m)
      (let [parent (get-in m parent-path)]
        (if (map? parent)
          (assoc-in m parent-path (dissoc parent k))
          m)))))

(defn- assoc-in-safe
  "Set the value at `path` (a non-empty path vector) in `m` to `v`,
  raising a structured `:rf.error/bad-diff-replay` ex-info — never a
  raw host exception — when an intermediate parent is present but
  non-associative.

  The spec contract for `[<path> :assoc v]` is 'set the value at
  `path`, creating the slot if it didn't exist'. The naive `assoc-in`
  honours the create-if-absent half — a MISSING / `nil` intermediate
  parent auto-vivifies a map (`(assoc-in {} [:a :b] 2)` ⇒
  `{:a {:b 2}}`), which is the intended grammar behaviour and is
  preserved here. But when an intermediate parent is PRESENT and
  non-associative, `assoc-in` throws a raw host exception with nil
  `ex-data`:

    - a SCALAR intermediate throws `ClassCastException`
      (`(assoc-in {:a 1} [:a :b] 2)` — `1` can't be `assoc`ed into);
    - a VECTOR intermediate reached by a non-integer key throws
      `IllegalArgumentException` (`(assoc-in {:a [1 2]} [:a :b] 9)`).

  This is the `:assoc` peer of `dissoc-in`'s missing-/scalar-parent
  guard: `apply-patches`'s grammar gate pins the patch
  SHAPE but cannot prove a patch's parent path is associative in THIS
  `base`. A malformed / corrupt / third-party diff replayed against a
  mismatched base is exactly the case this guards — including via
  `decode-db-after`, whose non-validating `apply-patches*` replay
  routes here.

  Policy (acceptance): a non-associative intermediate
  parent is a base/patch MISMATCH, not a no-op. Unlike `:dissoc`
  (where 'remove a key from a non-map' is naturally a no-op), silently
  dropping an `:assoc` would discard the requested change (data loss),
  and clobbering the scalar with a manufactured map would corrupt the
  base into a shape neither encoder side emitted (data corruption).
  Neither silent outcome is safe, so we surface the drift as a
  structured failure rather than guess. The ex-info names the ACTUAL
  decode-side boundary via `where` (`'mcp-base/apply-patches`
  from the public validating decoder, `'mcp-base/decode-db-after` from
  the section decoder), carries `:recovery :no-recovery`, and reports
  the offending `:patch-path` plus the `:at` prefix where traversal hit
  the non-associative node — without `pr-str`ing the value (the base
  may carry sensitive payload; we report shape, not content)."
  [m path v where]
  ;; Walk every key in `path`; before each key is applied, verify the
  ;; node it lands on is associable-for-that-key the way `assoc-in`
  ;; requires — including the LEAF key's direct parent (the final
  ;; descent `assoc-in` performs). A MISSING / `nil` node is fine
  ;; (auto-vivified to a map). A PRESENT non-associative node is the
  ;; mismatch we surface.
  (loop [node    m
         segs    (seq path)
         crumbed []]
    (if (empty? segs)
      (assoc-in m path v)
      (let [seg  (first segs)
            ;; A map takes any key; a vector takes only an in-bounds
            ;; (or tail) integer index. `nil`/absent auto-vivifies.
            ok?  (or (nil? node)
                     (map? node)
                     (and (vector? node) (integer? seg)
                          (<= 0 seg (count node))))]
        (if ok?
          (recur (get node seg) (rest segs) (conj crumbed seg))
          (throw (ex-info ":rf.error/bad-diff-replay"
                          {:rf.error/id :rf.error/bad-diff-replay
                           :where       where
                           :recovery    :no-recovery
                           :reason      "diff :assoc replay hit a non-associative intermediate parent — patch path does not exist in this base"
                           :patch-path  (vec path)
                           :at          crumbed
                           :parent-type (some-> node type pr-str)})))))))

(defn- apply-patches*
  "Replay an already-validated patch vector against `base`, returning
  the reconstructed value. Patches are `[path :assoc v]` or
  `[path :dissoc]`. Root-path patches (path `[]`) replace `base`
  outright (for `:assoc`) or are a no-op (for `:dissoc`, by
  convention). Nested `:dissoc` routes through `dissoc-in`, whose
  missing-/scalar-parent no-op honours the spec contract (no
  nil-branch manufacture, no host `ClassCastException`).
  Nested `:assoc` routes through `assoc-in-safe`, the `:assoc` peer
  guard: a missing parent auto-vivifies (intended
  create-if-absent grammar), but a PRESENT non-associative intermediate
  parent raises a structured `:rf.error/bad-diff-replay` ex-info rather
  than leak the raw host `ClassCastException` `assoc-in` would throw.

  This is the non-validating core: callers that have ALREADY validated
  the patch grammar (e.g. `decode-db-after`, whose `validate-sections!`
  gate walks each section's nested `:patches` against `patches-schema`
  via `section-schema`) replay through here directly rather than
  re-validating the flattened list a second time on the JVM decode hot
  path. The public `apply-patches` validates first, then
  delegates here.

  `where` is the boundary symbol threaded into a
  `:rf.error/bad-diff-replay` ex-info if a nested `:assoc` lands on a
  non-associative intermediate parent, so the error attributes the
  ACTUAL decode-side boundary that replayed the mismatched patch
  — `'mcp-base/apply-patches` from the public decoder,
  `'mcp-base/decode-db-after` from the section decoder. The 2-arity
  defaults to the public-decoder boundary."
  ([base patches] (apply-patches* base patches 'mcp-base/apply-patches))
  ([base patches where]
   (reduce
     (fn [acc patch]
       (let [[path op v] patch]
         (cond
           (empty? path)
           (if (= op :assoc) v acc)
           (= op :assoc)
           (assoc-in-safe acc path v where)
           (= op :dissoc)
           (dissoc-in acc path)
           :else acc)))
     base
     patches)))

(defn apply-patches
  "Apply a vector of patches to `base`, returning the reconstructed
  value. Patches are `[path :assoc v]` or `[path :dissoc]`. Root-path
  patches (path `[]`) replace `base` outright (for `:assoc`) or are a
  no-op (for `:dissoc`, by convention).

  ## Decoder-boundary validation

  `apply-patches` is the wire-decoder entry point: a malformed patch
  reaching this fn is a contract violation by an upstream encoder (a
  drifted re-frame2 mcp-base, a third-party decoder rolling its own
  shape, a transport corruption). Mirror `diff-encode-db-after`'s
  encoder-boundary gate: validate `patches` against `patches-schema`
  and throw `:rf.error/bad-diff-patches` ex-info on mismatch. The
  ex-info names `'mcp-base/apply-patches` as the decode-side boundary.

  The encoder side pins the grammar; this is the symmetric
  decode-side gate so the cross-MCP wire convention surfaces the
  drift rather than silently no-op'ing on the malformed tuple.
  Without the gate, a malformed tuple would fall through the `cond` to
  `:else acc` and drop corrupted patches without a peep.

  Soft-pass behaviour mirrors the encoder: when Malli is not
  resolvable on the runtime classpath, validation is skipped. The
  CLJS `validate-patches?` `goog-define` toggle elides both gates
  together in `:advanced` builds.

  Note: `decode-db-after` does NOT route through this fn — it validates
  `:sections` (whose nested `:patches` are walked against the same
  `patches-schema`) and replays via the non-validating `apply-patches*`
  to avoid a redundant second Malli walk of every patch tuple on the
  JVM decode hot path."
  [base patches]
  (validate-patches! patches 'mcp-base/apply-patches)
  (apply-patches* base patches))

(defn diff-encode-db-after
  "Replace an epoch's `:db-after` with a path-headed cluster
  projection of a path-keyed structural diff against its own
  `:db-before`. Returns the epoch with `:db-after`
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
          _        (validate-patches! patches 'mcp-base/diff-encode-db-after)
          ;; Thread `:db-before` so section classification can prove a
          ;; container is genuinely NEW before claiming `:added` — patch
          ;; shape alone cannot (`:assoc` covers both insert and
          ;; change). Without this an existing parent whose direct
          ;; child changed would be mislabelled `:added` to the agent.
          sections (sg/group-patches-into-sections
                     patches {:db-before (:db-before epoch)})
          _        (validate-sections! sections 'mcp-base/diff-encode-db-after)]
      (assoc epoch :db-after
             {vocab/diff-from-key :db-before
              :sections           sections}))))

(defn- validate-diff-marker-body!
  "Enforce the CLOSED marker-body contract on a `:db-after` map that
  carries `vocab/diff-from-key`. A structural check — pure
  Clojure, no Malli — so it fires on BOTH hosts regardless of Malli
  presence or the `validate-patches?` `goog-define` (mirroring
  `assoc-in-safe`'s replay guard, not the Malli `validate-sections!`
  grammar gate).

  The wire contract (mcp-conformance `DiffFromBody`, a CLOSED map) is:
  a diff marker is EXACTLY `{:rf.mcp/diff-from :db-before, :sections [...]}` —
  two top-level keys, the marker value restricted to `:db-before`.
  Presence of the marker key signals INTENT to be a diff marker, so the
  body must satisfy the closed two-key contract. The guard closes two
  boundary cases the conformance contract forbids (both otherwise
  silent):

    - an EXTRA sibling key — `{:rf.mcp/diff-from :db-before :sections []
      :sneaky :key}` — a `:sections`-only gate would ignore the stray
      key entirely (a mixed-envelope `:db-after` the encoder never
      emits);
    - an UNSUPPORTED marker value — `{:rf.mcp/diff-from :db-later ...}` —
      treating it as 'not a diff' would pass it THROUGH unchanged, so a
      corrupt / third-party marker would survive the decoder unflagged
      rather than surfacing a decoder-boundary contract error.

  On either case the decode boundary surfaces a structured
  `:rf.error/bad-diff-marker` ex-info naming the actual decode-side
  `where`, `:recovery :no-recovery`, and value-free diagnostics only
  (the offending key set / marker-value type — never the app-db
  payload, EP-0015)."
  [db-after where]
  (let [ks       (set (keys db-after))
        expected #{vocab/diff-from-key :sections}
        marker   (get db-after vocab/diff-from-key)]
    (when (or (not= ks expected)
              (not= :db-before marker))
      (throw (ex-info ":rf.error/bad-diff-marker"
                      {:rf.error/id   :rf.error/bad-diff-marker
                       :where         where
                       :recovery      :no-recovery
                       :reason        (str "diff marker body violated the closed "
                                           "{:rf.mcp/diff-from :db-before :sections [...]} "
                                           "contract — extra/missing top-level key or "
                                           "unsupported :rf.mcp/diff-from value")
                       :expected-keys expected
                       :actual-keys   ks
                       ;; value-free: the marker VALUE may be wire drift, so
                       ;; report whether it is the supported keyword, never
                       ;; an arbitrary smuggled value.
                       :marker-ok?    (= :db-before marker)})))))

(defn decode-db-after
  "Reverse `diff-encode-db-after`. Given an epoch whose `:db-after` is
  a `{:rf.mcp/diff-from :db-before :sections [...]}` marker,
  reconstruct the full `:db-after` from the epoch's `:db-before` and
  the per-section patch lists (flattened in section order).

  Idempotent on already-full epochs (the marker check returns the
  input unchanged when `:db-after` isn't a diff). Provided for
  agent-host round-trip parity and for the unit tests.

  ## Decoder-boundary section validation

  Mirrors the encoder's `validate-sections!` gate. `sections->patches`
  is a permissive `mapcat :patches` — a section with malformed
  `:section-path` / `:section-kind` slots, or extra/missing slots,
  would slip through to a patch replay whose grammar gate only covers
  the flattened `:patches` list. Validating `sections` here gives
  encoder/decoder symmetry: the cross-MCP
  wire convention surfaces drift on the receiving side too, rather
  than silently passing the cosmetic `:section-kind` / `:section-path`
  slots through to an agent-host UI that paints them as truth. The
  ex-info names `'mcp-base/decode-db-after` as the decode-side boundary.

  The gate validates the RAW `:sections` slot — it does NOT default a
  missing / `nil` / `false` slot to `[]`. A diff marker
  always carries an explicit `:sections` vector; coercing an absent or
  falsey slot to an empty vector before the gate would let a malformed
  `{:rf.mcp/diff-from :db-before}` marker decode to a no-op (an empty
  seq satisfies `[:sequential section-schema]`), silently ERASING the
  epoch's real `:db-after` change in diagnostics — the exact silent
  pass-through this boundary posture forbids. `sections-schema`
  rejects `nil` / `false` (neither is sequential), so wire drift
  trips `:rf.error/bad-diff-sections` when Malli is present. An
  explicit `:sections []` — a genuine no-change diff — still validates
  and replays to `:db-before` unchanged.

  Soft-pass + `goog-define`-elidable by construction — reuses the
  same `validate-sections!` helper as the encoder.

  ## Single validation pass

  `section-schema` nests `patches-schema`, so the `validate-sections!`
  gate above already Malli-walks every section's `:patches` against the
  same grammar the public `apply-patches` would re-check. To avoid
  validating each patch tuple twice on the JVM decode hot path
  (`watch-epochs` / `trace-window` slices, where Malli is always
  present), the replay routes through the non-validating
  `apply-patches*` rather than the public `apply-patches`."
  [epoch]
  (let [db-after (when (map? epoch) (:db-after epoch))]
    ;; A map is a diff marker iff it carries `diff-from-key`.
    ;; Detect on the KEY's PRESENCE (intent), not on the value being exactly
    ;; `:db-before`: a marker whose value is unsupported (or which carries an
    ;; extra sibling key) is corrupt wire drift the decoder MUST flag, not a
    ;; full epoch it should pass through. A `:db-after` with NO marker key is
    ;; an already-full epoch and decodes to itself.
    (if-not (and (map? db-after)
                 (contains? db-after vocab/diff-from-key))
      epoch
      ;; The map intends to be a diff marker: enforce the CLOSED two-key body
      ;; contract + the supported `:db-before` marker value FIRST,
      ;; so an extra sibling key or an unsupported marker value surfaces a
      ;; structured `:rf.error/bad-diff-marker` rather than slipping through.
      (do
        (validate-diff-marker-body! db-after 'mcp-base/decode-db-after)
        ;; Validate the RAW `:sections` value — do NOT default a
        ;; missing / nil / false slot to `[]` before the gate.
        ;; A diff marker carries an EXPLICIT `:sections` vector; coercing
        ;; an absent or falsey slot to an empty vector would slip a malformed
        ;; `{:rf.mcp/diff-from :db-before}` marker past `validate-sections!`
        ;; (an empty seq satisfies `[:sequential section-schema]`) and
        ;; replay it as a no-op, silently ERASING the epoch's real
        ;; `:db-after` change in diagnostics. `sections-schema` rejects
        ;; `nil` / `false` (neither is sequential) so the decoder-boundary
        ;; gate trips `:rf.error/bad-diff-sections` on wire drift, while
        ;; an explicit `:sections []` (a genuine no-change diff) still
        ;; validates and replays to `:db-before` unchanged.
        (let [sections  (:sections db-after)
              _         (validate-sections! sections 'mcp-base/decode-db-after)
              patches   (sg/sections->patches sections)
              db-before (:db-before epoch)
              rebuilt   (apply-patches* db-before patches 'mcp-base/decode-db-after)]
          (assoc epoch :db-after rebuilt))))))

(defn diff-encode-epochs
  "Apply `diff-encode-db-after` to every epoch in `epochs` unless `mode`
  is `:full` (in which case the vector passes through unchanged). Each
  record is encoded against ITS OWN `:db-before` — no cross-record
  dependency; the slice can be reordered, paginated, or filtered
  without breaking decode.

  `mode` is one of:
    :diff — default. Each `:db-after` becomes a structural diff.
    :full — pass through unchanged (opt-in)."
  [epochs mode]
  (if (= mode :full)
    epochs
    (mapv diff-encode-db-after epochs)))
