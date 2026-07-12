(ns re-frame.ui.fingerprint
  "The NAMED HOME of the compiled-view identity digests (S0 coverage-pass
  addendum, rf2-vxgfnd.2): `template-fingerprint`, `hook-signature-hash`,
  and `build-digest`. `render-fingerprint` and normalization `N` are NOT
  here — they stay owned by jvm-tree-and-conversion-contract.md / Spec 011.

  ## The algorithm (shared by all three)

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
      hook-signature-hash   \"hs1-\"  input: [1 {:locals [...] :effects [...]}]
                            — the ordered host-hook plan. `sub` sites are
                            deliberately EXCLUDED: dev's fixed full hook
                            skeleton makes adding your first sub a
                            same-signature edit (Spec 004 rewrite §Hot
                            reload). At S1 both vectors are always empty
                            (no reactivity in this slice); the input shape
                            is the frozen contract.
      build-digest          \"bd1-\"  input: the SORTED vector of
                            [view-id template-fingerprint hook-signature]
                            triples of every view in the build (sorted by
                            view-id so the digest is compile-order
                            independent)
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
  map carry DISTINCT tags and a set never collides with a vector (or map) of
  the same flattened elements. Sets and map keys are order-normalized;
  vectors and lists stay order-sensitive; scalars carry their host-identical
  `pr-str`. Two `=`-equal plans (up to map-key / set authoring order) always
  digest identically (no spurious plan-conflict); a genuine config change —
  including a set↔vector↔list type flip — always digests differently.
  Cross-host equality of the hex output is pinned by
  `re-frame.ui.fingerprint-cljs-test`."
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
;; vector (`v`), list/seq (`l`), and map (`m`) carry DISTINCT tags, so a set
;; never collides with a vector — or a map — of the same flattened elements.
;; That was the pre-#5745 hazard: a set was flattened to a bare vector of its
;; elements' `pr-str` STRINGS, so the distinct configs `#{:a}` and `[":a"]`
;; shared one canonical form (`[":a"]`). That is a GUARANTEED pre-hash
;; collision — a real config change reads as an idempotent no-op — closed here
;; because `#{:a}` -> "s.." and `[":a"]` -> "v.." can never coincide, and the
;; inner keyword `:a` -> "t..:a" and string `":a"` -> "t..\":a\"" differ too.
;;
;; ORDER handling: sets sort their child tokens (order-INSENSITIVE), map
;; entries sort by their canonical KEY token (authoring-order-insensitive,
;; #5745), and vectors/lists preserve producer order (order-SENSITIVE).
;; Emitting map entries as a length-delimited token stream — rather than
;; reducing them into an intermediate `sorted-map-by` — means two DISTINCT
;; keys that share a comparator rank can never overwrite one another; both
;; entries survive into the digest.
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

(defn- -write
  "Emit the injective, type-preserving canonical token for `x`. Collections
  carry a distinct type tag (`m`/`s`/`v`/`l`) so a map, set, vector, and
  list never collide; sets and map keys are order-normalized while vectors
  and lists keep producer order; scalars (`t`) carry their host-identical,
  type-distinct `pr-str`."
  [x]
  (cond
    ;; Map — sort ENTRIES by their canonical KEY token, then emit key and
    ;; value tokens in that order. A token stream (not a sorted-map) means
    ;; two distinct keys can never overwrite one another.
    (map? x)    (token "m" (->> x
                                (map (fn [[k v]] [(-write k) (-write v)]))
                                (sort-by first)
                                (mapcat (fn [[k v]] [k v]))
                                (apply str)))
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
  Equal values — up to map-key / set authoring order — produce the same
  string; distinct values, including a set vs a vector vs a list of the same
  elements, produce different strings."
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
  `{:locals [...] :effects [...]}` (version-1 input shape; `sub` sites
  excluded by design)."
  [{:keys [locals effects] :or {locals [] effects []}}]
  (digest "hs1-" [1 {:locals (vec locals) :effects (vec effects)}]))

(defn build-digest
  "\"bd1-\" digest over `[[view-id template-fingerprint hook-signature] ...]`
  triples, sorted by view-id (compile-order independent)."
  [triples]
  (digest "bd1-" (vec (sort-by (comp pr-str first) triples))))

(defn config-fingerprint
  "\"cf1-\" digest of a static frame plan (S1c root-identity contract §6):
  `[frame-id config-source-map]` — the frame-root's literal `:id` plus its
  config SOURCE forms (the props map minus `:id`, as written). Hashing the
  source keeps the fingerprint computable with no evaluation (config
  expressions are runtime values, evaluated at preflight); build-time
  plan-conflict detection compares exactly this digest."
  [frame-id config]
  (digest "cf1-" [frame-id (or config {})]))
