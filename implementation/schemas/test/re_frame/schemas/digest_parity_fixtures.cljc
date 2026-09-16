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
  (line-sorted `<path-key> <sha256-hex>\\n`).

  rf2-ujmc3u: the `<path-key>` is now the CEDN-1 `canonical-bytes` token
  stream of the path vector (`v[k::n]`), NOT `pr-str` of the vector
  (`[:n]`) — Conventions §Canonical EDN identity lists schema digest path
  keys among the canonical-identity surfaces, and `pr-str` is host-divergent
  for the non-keyword segments a concrete path may carry. The literals below
  were repinned when the path-key encoding moved to `canonical-bytes`; the
  empty-set literal (`sha256:e3b0c44298fc1c14`) is unchanged because the
  empty schema set emits no path-key line."
  (:require [clojure.string :as str]
            [re-frame.schemas.digest]
            [re-frame.schemas.validator]))

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
   :expected  "sha256:e7939756d704eaab"
   :rationale "One path, keyword primitive schema. Smallest non-empty
              fixture — tests the single-line code path."})

(def single-vector
  {:label     "single-vector"
   :input     {[:user] [:map [:id :uuid]]}
   :expected  "sha256:a29be3b0c0ab2dfc"
   :rationale "One path, vector Malli schema. Pins the bare-`[:map ...]`
              canonical form."})

(def multi-schema
  {:label     "multi-schema"
   :input     {[:user]  [:map [:id :uuid]]
               [:todos] [:vector :string]}
   :expected  "sha256:290741855e4a96e7"
   :rationale "Two paths, vector schemas. Exercises the lexicographic
              line-sort (Spec 010 §Digest algorithm step 4)."})

(def nested-paths
  {:label     "nested-paths"
   :input     {[:app :settings :theme] [:enum :light :dark]
               [:app :user :name]      :string
               [:app :user :age]       [:int {:min 0 :max 150}]}
   :expected  "sha256:65d46c3f0b855ab3"
   :rationale "Three paths into a nested app-db shape. Exercises long
              paths and a schema-props map (`{:min 0 :max 150}`) that
              the canonical-form's sort-by-pr-str map-key ordering
              must serialise stably."})

(def with-props
  {:label     "with-props"
   :input     {[:user] [:map {:closed true :title "User"} [:id :uuid] [:name :string]]}
   :expected  "sha256:52ac732d3b0b99ae"
   :rationale "Malli `[:map {props} & children]` shape. Exercises the
              inner-map sort over `{:closed true :title \"User\"}` —
              insertion-order independence is pinned by the
              `props-order-independent` test below."})

(def primitive-bool
  {:label     "primitive-bool"
   :input     {[:flag] :boolean}
   :expected  "sha256:21f4bbee07588695"
   :rationale "Distinct primitive keyword (`:boolean`) — confirms the
              digest moves on a primitive-type change, locking the
              `not= digest` invariant for the keyword-primitive
              surface."})

;; ---- host-divergent PRINTER cases (rf2-k0hqk) -----------------------------
;;
;; Two scalar kinds did not survive `pr-str` deterministically, and both
;; faked the SSR hydrate handshake's `:rf.ssr/schema-digest-mismatch`
;; warning ("Deploy drift … Hydrating anyway") against byte-identical
;; code:
;;
;;   * a FUNCTION — `[:map [:n pos-int?]]`, the idiom Spec 010's how-to
;;     recommends — printed on the JVM as `#object[clojure.core$pos_int_
;;     QMARK_ 0x3aefae67 "…@3aefae67"]`, and that address is
;;     `System/identityHashCode`, a fresh value in every process. The
;;     digest therefore moved on every server restart.
;;   * a WHOLE-NUMBER DOUBLE — `{:min 1.0}` — printed `1.0` on the JVM
;;     and `1` on CLJS, which has one numeric type and cannot tell the
;;     two apart.
;;
;; THE TWO ARE NOT THE SAME KIND OF DEFECT, and the fixtures below are
;; shaped by that difference rather than by symmetry.
;;
;; The double case is a genuine cross-runtime disagreement that the
;; printer fix REMOVES — after it the two hosts agree — so it earns a
;; shared pinned literal in `all-fixtures` beside every other cross-host
;; vector.
;;
;; The fn case cannot have one. The token is derived from the HOST's own
;; name for the function (`clojure.core$pos_int_QMARK_` on the JVM,
;; `cljs$core$pos_int_QMARK_` on CLJS, munged again to something
;; build-specific under `:advanced`), so the fix buys per-host PROCESS
;; STABILITY and not cross-runtime identity. A shared literal for it
;; would be a fixture that cannot pass on both hosts. What IS assertable
;; on both — and is what `fn-bearing-observations` reports — is that the
;; bytes carry a name-derived token rather than an address, are stable
;; across serialisations, and still tell two different predicates apart.
;; Spec 010 §Digest algorithm carries the same split as normative prose.

(def whole-number-double
  {:label     "whole-number-double"
   :input     {[:n] [:int {:min 1.0 :max 10.0}]}
   :expected  "sha256:256998dfe0d8dc71"
   :rationale "rf2-k0hqk — whole-number doubles in a schema props map.
              `pr-str` of `1.0` is \"1.0\" on the JVM and \"1\" on CLJS,
              so this ordinary Malli prop digested differently on the two
              hosts and faked Deploy drift. The canonical form emits the
              integer the value denotes — the only direction that can
              agree, since CLJS cannot spell a `.0` suffix for a value it
              does not distinguish from an integer — so this literal is
              reachable from both runtimes. The CLJS reader collapses the
              `1.0` source literal to 1 before the fixture is even built;
              that IS the divergence, not a weakness of the fixture, and
              the JVM-side precondition (`whole-number-double-min` read
              back through `float?`) is what keeps this input honest
              about the kind it carries."})

(defn whole-number-double-min
  "The `:min` prop of `whole-number-double`'s schema, read back OUT of
  the fixture rather than restated, so a precondition assertion cannot
  drift from the input it guards. The JVM test asserts this is really a
  floating-point value — without that, editing the fixture's `1.0` to `1`
  would leave a green test exercising nothing."
  []
  (-> (:input whole-number-double) (get [:n]) (nth 1) :min))

(def fn-bearing-schema
  "A schema carrying a bare predicate FUNCTION — the idiom Spec 010's
  how-to recommends, and the shape whose digest was not process-stable
  at all before rf2-k0hqk."
  [:map [:n pos-int?]])

(def fn-bearing-other-schema
  "The same shape under a DIFFERENT predicate. Pinning that these two
  digest differently is what stops the fn defect being 'fixed' by
  collapsing every function to one constant token: that would make the
  hosts agree, and would silently stop the digest detecting a predicate
  swap — which is the drift detection the surface exists for."
  [:map [:n neg-int?]])

(defn fn-bearing-carries-fn?
  "Precondition — true iff `fn-bearing-schema` still holds a FUNCTION
  where the fixture expects one. `fn?` rather than `ifn?` on purpose:
  CLJS keywords and collections are all `ifn?`, so `ifn?` would answer
  true for a fixture that had drifted to `[:map [:n :int]]` and had
  stopped exercising this defect entirely."
  []
  (fn? (nth (nth fn-bearing-schema 1) 1)))

(defn fn-bearing-observations
  "Printer bytes and derived properties for the fn-bearing fixtures.

  Every read clears the process-wide print memo first, so a cached
  string cannot answer for a fresh serialisation — without that,
  `:stable-across-reads?` would be pinning the memo rather than the
  canonicaliser. Leaves the memo empty.

  The booleans are computed here rather than in the two test files so
  both runtimes assert the SAME properties against the same bytes."
  []
  (let [read! (fn [schema]
                (re-frame.schemas.validator/clear-edn-print-cache!)
                (re-frame.schemas.validator/run-printer schema))
        bytes (read! fn-bearing-schema)
        again (read! fn-bearing-schema)
        other (read! fn-bearing-other-schema)]
    (re-frame.schemas.validator/clear-edn-print-cache!)
    {:bytes                     bytes
     :other-predicate-bytes     other
     ;; The token is name-derived, so no per-process address rides in it.
     :address-free?             (nil? (re-find #"0x[0-9a-f]+" bytes))
     ;; `#object[…]` is the pre-fix host print — its absence is the
     ;; legible statement that the canonicaliser, not `pr-str`, produced
     ;; these bytes.
     :object-print-free?        (not (str/includes? bytes "#object"))
     :carries-fn-token?         (str/includes? bytes "#fn")
     :stable-across-reads?      (= bytes again)
     :discriminates-predicates? (not= bytes other)}))

(def all-fixtures
  "All canonical fixtures in label-order. Test files iterate this list
  so adding a fixture requires no edits to the per-runtime test code."
  [empty-set
   single-prim
   single-vector
   multi-schema
   nested-paths
   with-props
   primitive-bool
   whole-number-double])

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

;; ---- ambient printer limits (rf2-gwye.14) ---------------------------------
;;
;; A REPL or tool that binds `*print-length*` / `*print-level*` must neither
;; reach the digest bytes nor leave a truncated serialisation in the
;; process-wide `default-edn-print` memo. The first two forms collapse to one
;; string under either bound; the third also sends collection-valued map keys
;; and set members through the canonicaliser's `pr-str` comparator, where a
;; bound makes distinct members compare equal and merge.

(def printer-limit-schemas
  [[:map [:a :int]]
   [:map [:a :string]]
   [:map {:doc-keys {[:k 1] :x [:k 2] :y} :doc-set #{[:m 1] [:m 2]}} [:a :int]]])

(def printer-limit-bindings
  "`[label *print-length* *print-level*]` — each bound alone, then both."
  [[:print-length 1 nil]
   [:print-level nil 1]
   [:both 1 1]])

(defn printer-limit-observations
  "For each of `printer-limit-bindings`, starting from an EMPTY print memo: the
  printer bytes and digests of `printer-limit-schemas` computed INSIDE the
  binding (`:inside`), then re-read after it WITHOUT clearing (`:after`). Both
  must equal the unbounded `:baseline`. Leaves the memo empty."
  []
  (let [observe  (fn []
                   {:bytes   (mapv re-frame.schemas.validator/run-printer printer-limit-schemas)
                    :digests (mapv #(compute-digest {[:s] %}) printer-limit-schemas)})
        _        (re-frame.schemas.validator/clear-edn-print-cache!)
        baseline (observe)
        results  (mapv (fn [[label length level]]
                         (re-frame.schemas.validator/clear-edn-print-cache!)
                         (let [inside (binding [*print-length* length
                                                *print-level*  level]
                                        (observe))]
                           {:label label :inside inside :after (observe)}))
                       printer-limit-bindings)]
    (re-frame.schemas.validator/clear-edn-print-cache!)
    {:baseline baseline :results results}))
