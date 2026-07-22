(ns re-frame.ui.fingerprint
  "The NAMED HOME of the compiled-view identity digests (S0 coverage-pass
  addendum, rf2-vxgfnd.2): `template-fingerprint` and `hook-signature-hash`.
  `render-fingerprint` and normalization `N` are NOT
  here — they stay owned by jvm-tree-and-conversion-contract.md / Spec 011.

  ## The algorithm (shared by all)

      digest  = FNV-1a 64-bit over the UTF-8 bytes of the canonical-EDN
                serialisation of the input form
      output  = <prefix> + 16 lowercase hex chars (zero-padded)

  FNV-1a 64 aligns with the repo's checked-in render-fingerprint choice
  (jvm-tree contract §Normalization: \"FNV-1a is today's checked-in
  choice\"). Each digest is version-prefixed so the algorithm can rotate
  without ambiguity:

      template-fingerprint  \"tf1-\"  input: the view's template AST
                            fingerprint projection (source-location
                            metadata stripped; forms as read)
      hook-signature-hash   \"hs1-\"  input (shape 2, S3):
                            [2 {:locals [...] :effects [...] :react [...]}]
                            — the ordered host-hook plan. `sub` sites are
                            deliberately EXCLUDED: dev's fixed full hook
                            skeleton makes adding your first sub a
                            same-signature edit (Spec 004 rewrite §Hot
                            reload). `:react` carries the frozen
                            re-frame.ui.react interop hooks as ordered
                            {:kind :order} entries (kind + source-order
                            position among ALL let-body host hooks, so a
                            local↔react reorder — both live in the top-region
                            let, interleaved in React hook order — changes the
                            signature). Adding, removing, or reordering any
                            wrapper changes the plan; editing a deps vector or
                            setup body does NOT. The S1→S3 shape bump (integer
                            1→2, prefix \"hs1-\" unchanged — the prefix versions
                            the ALGORITHM, the integer the INPUT shape)
                            re-serialises every hook signature: a deliberate
                            one-time global remount wave when S3 lands (pre-
                            alpha, no shim).
      config-fingerprint    \"cf1-\"  input: [frame-id config-source-map]
                            — a root form's static frame plan (S1c,
                            root-identity contract §6): the frame-root's
                            literal :id plus its config SOURCE FORMS
                            (:initial-events etc. as written, NOT their
                            runtime values — config expressions evaluate
                            at preflight; the fingerprint is what
                            build-time plan-conflict detection compares)

  ## Canonical serialization

  `canonical-string` is a TYPE-PRESERVING, INJECTIVE canonical string (not
  EDN). Every node is one self-delimiting token — a type-tag char, the
  char length of its body, a `:`, then the body — so a set / vector / list /
  map / record carry DISTINCT tags and a set never collides with a vector (or
  map) of the same flattened elements. A RECORD additionally carries its
  fully-qualified type name inside its token, so it collides neither with the
  plain map of the same entries nor with a record of a different type
  (rf2-59car). Sets, map keys, and record entries are order-normalized;
  vectors and lists stay order-sensitive; scalars carry their host-identical
  `pr-str`. Two `=`-equal plans (up to map-key / set authoring order) always
  digest identically (no spurious plan-conflict); a genuine config change —
  including a set↔vector↔list type flip, or a record type swap — always
  digests differently. Cross-host equality of the hex output is pinned by
  `re-frame.freehand.fingerprint-cljs-test`."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Canonical serialization
;; ---------------------------------------------------------------------------
;;
;; The digest input is a TYPE-PRESERVING, INJECTIVE canonical string — not
;; EDN. Every node is emitted as one self-delimiting token
;;
;;     <type-tag><char-length of body>":"<body>
;;
;; — a single type-tag char, the CHARACTER count of the body, a ":"
;; separator, then the body. The length turns each token into a netstring:
;; a parent collection's body is just its child tokens concatenated, and no
;; two distinct child sequences can share an encoding (the concatenation
;; parses back unambiguously).
;;
;; The TYPE TAG is what makes the encoding type-preserving. A set (`s`),
;; vector (`v`), list/seq (`l`), map (`m`), and record (`r`) carry DISTINCT
;; tags, so a set never collides with a vector — or a map — of the same
;; flattened elements. That was the pre-#5745 hazard: a set was flattened to a
;; bare vector of its elements' `pr-str` STRINGS, so the distinct configs
;; `#{:a}` and `[":a"]` shared one canonical form (`[":a"]`). That is a
;; GUARANTEED pre-hash collision — a real config change reads as an idempotent
;; no-op — closed here because `#{:a}` -> "s.." and `[":a"]` -> "v.." can never
;; coincide, and the inner keyword `:a` -> "t..:a" and string `":a"` ->
;; "t..\":a\"" differ too.
;;
;; RECORDS ARE MATCHED FIRST (rf2-59car), because a record also satisfies
;; `map?`. A leading `map?` branch discarded the record's TYPE, so `(->RecA 1)`,
;; `(->RecB 1)`, and `{:x 1}` — three pairwise-UNEQUAL values — all encoded as
;; the plain map `{:x 1}` and digested identically. That is the same class of
;; GUARANTEED pre-hash collision as the pre-#5745 set flattening, and it lands
;; on the same failure mode: `config-fingerprint` plan-conflict
;; detection reads a false digest MATCH as "nothing changed", so a genuine plan
;; conflict is silently treated as an idempotent no-op. The `r` token therefore
;; carries the record's fully-qualified type name as its own leading `t` token,
;; ahead of the entries, and the distinct `r` tag keeps a record clear of the
;; plain map of the same entries.
;;
;; ORDER handling: sets sort their child tokens (order-INSENSITIVE), map and
;; record entries sort by their canonical KEY token (authoring-order-
;; insensitive, #5745), and vectors/lists preserve producer order
;; (order-SENSITIVE). Records share the map's entry canonicalization exactly,
;; which matters because a record's EXTENSION entries (its `__extmap`) preserve
;; INSERTION order in the small-map representation: `(assoc (->RecA 1) :b 2 :c 3)`
;; and `(assoc (->RecA 1) :c 3 :b 2)` are `=`, and must digest identically.
;; Emitting entries as a length-delimited token stream — rather than reducing
;; them into an intermediate `sorted-map-by` — means two DISTINCT keys that
;; share a comparator rank can never overwrite one another; both entries survive
;; into the digest.
;;
;; SCALARS (`t`) carry their `pr-str`, which is host-identical for the
;; supported terminal types and already type-distinct: a keyword `:a`, the
;; string `":a"`, and the symbol `a` print differently, so they never collide.

(def ^:private canonical-version
  "Version marker prefixing every canonical string. It is hashed with the
  body, so a fingerprint computed under an older encoding is detectably
  distinct — never silently reinterpreted — when the encoding is revised.
  `cfp2` is the type-preserving, length-delimited writer (rf2-vxgfnd.78);
  the unversioned `pr-str`-flattening form (pre-#5745 / #5745) was v1."
  "cfp2:")

(declare -write)

(defn- token
  "A self-delimiting canonical token: the `tag` char, the CHARACTER length
  of `body`, a `:` separator, then `body`. The length makes the token a
  netstring, so concatenated child tokens parse back unambiguously and no
  two distinct child sequences share an encoding."
  [tag body]
  (str tag (count body) ":" body))

(defn- canonical-entries
  "The entry-token stream of an associative value: each entry's key and value
  tokens, with the entries SORTED by their canonical KEY token, concatenated.
  Shared by the map and the record branch of `-write`, so a record's entries —
  its declared fields AND its insertion-ordered `__extmap` extensions —
  canonicalize exactly like a plain map's. A token stream (not a sorted-map)
  means two distinct keys that share a comparator rank can never overwrite one
  another."
  [x]
  (->> x
       (map (fn [[k v]] [(-write k) (-write v)]))
       (sort-by first)
       (mapcat (fn [[k v]] [k v]))
       (apply str)))

(defn- record-type-name
  "The fully-qualified `my.ns/MyRec` name of a record's type, rendered
  IDENTICALLY on both hosts so tagging a record cannot break the cross-host
  equality of the hex output.

  On CLJS this is `(pr-str (type x))`: `defrecord` sets the type's
  `cljs$lang$ctorPrWriter` to write that name as a COMPILE-TIME literal, so it
  is stable, unique per record type, and survives `:advanced` renaming.
  `cljs.core/type->str` would NOT do — it reads `cljs$lang$ctorStr`, which
  `deftype` sets but `defrecord` does not, so it silently degrades to the
  constructor's JS source text — and neither would `(.-name (type x))`, which
  `:advanced` munges.

  On the JVM the record's class name carries the same information in host form
  (the MUNGED namespace, a `.`, then the verbatim record name), so demunging
  the namespace segment and rejoining it with `/` lands on the very same
  string."
  [x]
  #?(:clj  (let [^String n (.getName (class x))
                 i         (.lastIndexOf n ".")]
             (str (clojure.lang.Compiler/demunge (subs n 0 i))
                  "/"
                  (subs n (inc i))))
     :cljs (pr-str (type x))))

(defn- -write
  "Emit the injective, type-preserving canonical token for `x`. Collections
  carry a distinct type tag (`m`/`s`/`v`/`l`/`r`) so a map, set, vector, list,
  and record never collide; sets, map keys, and record entries are
  order-normalized while vectors and lists keep producer order; scalars (`t`)
  carry their host-identical, type-distinct `pr-str`."
  [x]
  (cond
    ;; Record — matched BEFORE `map?`, which a record ALSO satisfies
    ;; (rf2-59car). The body is the record's fully-qualified type name as a
    ;; leading `t` token, then its entries canonicalized exactly as a map's, so
    ;; two record types with the same entries — and a record vs the plain map of
    ;; those entries — always digest differently.
    (record? x) (token "r" (str (token "t" (record-type-name x))
                                (canonical-entries x)))
    ;; Map — sort ENTRIES by their canonical KEY token, then emit key and
    ;; value tokens in that order. A token stream (not a sorted-map) means
    ;; two distinct keys can never overwrite one another.
    (map? x)    (token "m" (canonical-entries x))
    ;; Set — order-INSENSITIVE: sort the child tokens. The `s` tag keeps a
    ;; set distinct from a vector/list of the same elements.
    (set? x)    (token "s" (apply str (sort (map -write x))))
    ;; Vector — order-SENSITIVE: preserve producer order.
    (vector? x) (token "v" (apply str (map -write x)))
    ;; List / seq — order-SENSITIVE; the `l` tag keeps it distinct from a
    ;; vector of the same elements.
    (seq? x)    (token "l" (apply str (map -write x)))
    ;; Scalar — `pr-str` is host-identical and type-distinct for the
    ;; supported terminals (keyword vs string vs symbol vs number vs nil).
    :else       (token "t" (pr-str x))))

(defn canonical-string
  "One deterministic, type-preserving canonical string per value, identical
  on both hosts (see the section comment above for the token grammar).
  Equal values — up to map-key / set / record-extension authoring order —
  produce the same string; distinct values, including a set vs a vector vs a
  list of the same elements, and a record vs another record type vs the plain
  map of the same entries, produce different strings."
  [x]
  (str canonical-version (-write x)))

;; ---------------------------------------------------------------------------
;; FNV-1a 64
;; ---------------------------------------------------------------------------

#?(:clj
   (defn- fnv1a-64 ^long [^bytes bs]
     (loop [h (unchecked-long -3750763034362895579)  ; 0xcbf29ce484222325
            i 0]
       (if (< i (alength bs))
         (recur (unchecked-multiply
                 (bit-xor h (bit-and 255 (long (aget bs i))))
                 (unchecked-long 1099511628211))
                (inc i))
         h)))
   :cljs
   (defn- fnv1a-64 [bs]
     (let [prime (js/BigInt "1099511628211")]
       (loop [h (js/BigInt "14695981039346656037")
              i 0]
         (if (< i (.-length bs))
           ;; cljs.core/bit-xor and * inline to the raw JS operators in
           ;; call position, which are BigInt-correct.
           (recur (js/BigInt.asUintN
                   64
                   (* (bit-xor h (js/BigInt (aget bs i))) prime))
                  (inc i))
           h)))))

(defn- hex64 [h]
  #?(:clj  (let [s (Long/toHexString (long h))]
             (str (subs "0000000000000000" (count s)) s))
     :cljs (let [s (.toString h 16)]
             (str (subs "0000000000000000" (count s)) s))))

(defn- utf8-bytes [^String s]
  #?(:clj  (.getBytes s "UTF-8")
     :cljs (.encode (js/TextEncoder.) s)))

(defn digest
  "prefix + FNV-1a-64 hex of the canonical string of `form`."
  [prefix form]
  (str prefix (hex64 (fnv1a-64 (utf8-bytes (canonical-string form))))))

;; ---------------------------------------------------------------------------
;; The three named digests
;; ---------------------------------------------------------------------------

(defn template-fingerprint
  "\"tf1-\" digest of the template-AST fingerprint projection."
  [ast-projection]
  (digest "tf1-" ast-projection))

(defn hook-signature-hash
  "\"hs1-\" digest of the ordered host-hook plan
  `{:locals [...] :effects [...] :react [...]}` (shape-version 2, S3; `sub`
  sites excluded by design). `:react` entries are `{:kind :order}` maps — the
  wrapper kind plus its source-order position among ALL top-region let-body
  host hooks (locals included), so a local↔react reorder changes the signature.
  The leading integer versions the INPUT SHAPE; the \"hs1-\" prefix (unchanged)
  versions the algorithm."
  [{:keys [locals effects react] :or {locals [] effects [] react []}}]
  (digest "hs1-" [2 {:locals  (vec locals)
                     :effects (vec effects)
                     :react   (vec react)}]))

(defn config-fingerprint
  "\"cf1-\" digest of a static frame plan (S1c root-identity contract §6):
  `[frame-id config-source-map]` — the frame-root's literal `:id` plus its
  config SOURCE forms (the props map minus `:id`, as written). Hashing the
  source keeps the fingerprint computable with no evaluation (config
  expressions are runtime values, evaluated at preflight); build-time
  plan-conflict detection compares exactly this digest."
  [frame-id config]
  (digest "cf1-" [frame-id (or config {})]))
