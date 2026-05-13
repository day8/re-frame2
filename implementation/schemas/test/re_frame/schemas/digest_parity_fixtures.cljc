(ns re-frame.schemas.digest-parity-fixtures
  "Shared fixtures for the JVM↔CLJS app-schemas-digest byte-identity
  parity tests (rf2-xssfv).

  Spec 010 §Digest algorithm pins the digest as cross-runtime
  byte-identical: a CLJS server and a CLJS client running the same
  schema set MUST produce the same `\"sha256:\" + 16-hex` string —
  byte-for-byte. The empty-set vector (`sha256:e3b0c44298fc1c14`) was
  pinned at rf2-0z1z; this namespace extends the corpus to multi-
  schema, nested, with-props, primitive, and metadata-stripped cases
  so port implementations (and future refactors of the digest
  pipeline) can self-check against a wider surface.

  Strategy mirrors `re-frame.source-coord-parity-test` /
  `re-frame.source-coord-parity-cljs-test` (rf2-1q9de) — both runtimes
  consume the SAME fixture map and pin the SAME expected literal. The
  literal IS the cross-host byte-comparison point; if either runtime's
  digest pipeline diverges from the canonical bytes, that runtime's
  test fails. The fixtures live in a .cljc namespace so the JVM and
  CLJS test files load the same `def`s.

  Wire-form review: every entry is `\"sha256:\" + 16 lowercase hex
  chars` per Spec 010 §Digest algorithm. The 16-hex prefix is the
  first 64 bits of the SHA-256 over the canonical concatenation
  (line-sorted `<path-string> <sha256-hex>\\n`)."
  (:require [re-frame.schemas.digest]))

;; ---- the parity test entry point ------------------------------------------
;;
;; `compute-digest` is private; both runtimes reach it via `#'`. The
;; helper indirection keeps the runtime-specific var-deref out of the
;; per-fixture assertions.

(def compute-digest-var
  "The private digest-from-map function under test. Both runtimes deref
  the same symbol; the value is byte-identical when fed byte-identical
  inputs."
  #'re-frame.schemas.digest/compute-digest)

(defn compute-digest
  "Run the private digest pipeline against a `path->schema` map.
  Returns the canonical `\"sha256:\" + 16-hex` wire form."
  [path->schema]
  (compute-digest-var path->schema))

;; ---- the canonical corpus -------------------------------------------------
;;
;; Each fixture is a `{:label, :input, :expected, :rationale}` map. The
;; input is fed directly to `compute-digest`; the expected string is
;; the canonical literal both runtimes MUST produce. The corpus covers:
;;
;;   * empty-set      — already pinned at rf2-0z1z; carried here for
;;                      single-source-of-truth.
;;   * single-prim    — one path, primitive keyword schema (`:int`).
;;   * single-vector  — one path, vector schema (`[:int]`).
;;   * multi-schema   — two paths, vector schemas.
;;   * nested-paths   — three paths into a nested app-db shape.
;;   * with-props     — Malli `[:map {:closed true ...} & children]`
;;                      shape — exercises the map-key sort inside the
;;                      canonical form.
;;   * primitive-bool — confirms a non-int primitive keyword schema
;;                      hashes to a distinct value.

(def empty-set
  {:label     "empty-set"
   :input     {}
   :expected  "sha256:e3b0c44298fc1c14"
   :rationale "SHA-256 of the empty string — the lines list collapses
              to an empty concatenation. Carried from rf2-0z1z."})

(def single-prim
  {:label     "single-prim"
   :input     {[:n] :int}
   :expected  "sha256:5d955f1275ab1ae7"
   :rationale "One path, keyword primitive schema. Smallest non-empty
              fixture — tests the single-line code path."})

(def single-vector
  {:label     "single-vector"
   :input     {[:user] [:map [:id :uuid]]}
   :expected  "sha256:7217a35f9e8d1b08"
   :rationale "One path, vector Malli schema. Pins the bare-`[:map ...]`
              canonical form."})

(def multi-schema
  {:label     "multi-schema"
   :input     {[:user]  [:map [:id :uuid]]
               [:todos] [:vector :string]}
   :expected  "sha256:d2ffa3677b377e60"
   :rationale "Two paths, vector schemas. Exercises the lexicographic
              line-sort (Spec 010 §Digest algorithm step 4)."})

(def nested-paths
  {:label     "nested-paths"
   :input     {[:app :settings :theme] [:enum :light :dark]
               [:app :user :name]      :string
               [:app :user :age]       [:int {:min 0 :max 150}]}
   :expected  "sha256:09ec6b6ec7e9e058"
   :rationale "Three paths into a nested app-db shape. Exercises long
              paths and a schema-props map (`{:min 0 :max 150}`) that
              the canonical-form's sort-by-pr-str map-key ordering
              must serialise stably."})

(def with-props
  {:label     "with-props"
   :input     {[:user] [:map {:closed true :title "User"} [:id :uuid] [:name :string]]}
   :expected  "sha256:414f163e837264ca"
   :rationale "Malli `[:map {props} & children]` shape. Exercises the
              inner-map sort over `{:closed true :title \"User\"}` —
              insertion-order independence is pinned by the
              `props-order-independent` test below."})

(def primitive-bool
  {:label     "primitive-bool"
   :input     {[:flag] :boolean}
   :expected  "sha256:9ceab11cd54a81ef"
   :rationale "Distinct primitive keyword (`:boolean`) — confirms the
              digest moves on a primitive-type change, locking the
              `not= digest` invariant for the keyword-primitive
              surface."})

(def all-fixtures
  "All canonical fixtures in label-order. Test files iterate this list
  so adding a fixture requires no edits to the per-runtime test code."
  [empty-set
   single-prim
   single-vector
   multi-schema
   nested-paths
   with-props
   primitive-bool])

;; ---- invariant fixtures (input pairs that MUST hash identically) ---------
;;
;; The structural invariants from Spec 010 §Digest algorithm — order
;; independence (path-level and props-level) and metadata stripping —
;; aren't pinned to a literal here; instead they're asserted as
;; equality between two distinct inputs. The expected hash is whatever
;; both inputs produce (it will be the same string by construction,
;; and both runtimes will compute the same string).

(def path-order-pair
  {:label "path-order-independent"
   :input-a {[:user]  [:map [:id :uuid]]
             [:todos] [:vector :string]}
   :input-b {[:todos] [:vector :string]
             [:user]  [:map [:id :uuid]]}
   :rationale "Spec 010 §Digest algorithm step 4 — the lines list is
               lexicographically sorted before final hashing, so the
               registration / insertion order of paths in the
               `path->schema` map is digest-irrelevant."})

(def props-order-pair
  {:label   "props-order-independent"
   :input-a {[:user] [:map (array-map :closed true :title "User") [:id :uuid]]}
   :input-b {[:user] [:map (array-map :title "User" :closed true) [:id :uuid]]}
   :rationale "Spec 010 §Digest algorithm step 1 — the canonical form
               sorts map-keys by `(compare (pr-str a) (pr-str b))`, so
               the insertion order of keys inside a schema-props map
               is digest-irrelevant."})

(def metadata-stripped-pair
  {:label   "metadata-stripped"
   :input-a {[:user] [:map [:id :uuid]]}
   :input-b {[:user] (with-meta [:map [:id :uuid]] {:doc "user-id"})}
   :rationale "Spec 010 §Digest algorithm step 1 — the canonical form
               binds `*print-meta* false`, so metadata attached to a
               schema value does NOT alter the digest."})

(def metadata-stripped-inner-pair
  {:label   "metadata-stripped-inner"
   :input-a {[:user] [:map {:closed true} [:id :uuid]]}
   :input-b {[:user] [:map (with-meta {:closed true} {:annot :x}) [:id :uuid]]}
   :rationale "Metadata stripping applies recursively — metadata on a
               nested props map is dropped too, not just metadata on
               the outermost schema."})

(def invariant-pairs
  "All equality-invariant fixture pairs. Each MUST produce identical
  digests across both inputs and across both runtimes."
  [path-order-pair
   props-order-pair
   metadata-stripped-pair
   metadata-stripped-inner-pair])
