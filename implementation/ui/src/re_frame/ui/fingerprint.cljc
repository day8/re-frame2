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

  ## Canonical EDN

  `canonical-edn` prints with maps recursively sorted by `pr-str` of key
  and sets sorted likewise — one value, one string, on both hosts.
  Cross-host equality of the hex output is pinned by
  `re-frame.ui.fingerprint-cljs-test`."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Canonical EDN
;; ---------------------------------------------------------------------------

(defn- canonicalize [x]
  (cond
    (map? x)  (into (sorted-map-by (fn [a b] (compare (pr-str a) (pr-str b))))
                    (map (fn [[k v]] [k (canonicalize v)]))
                    x)
    (set? x)  (vec (sort (map pr-str x)))          ; sets as sorted printed vec
    (vector? x) (mapv canonicalize x)
    (seq? x)  (apply list (map canonicalize x))
    :else x))

(defn canonical-edn
  "One deterministic EDN string per value, both hosts."
  [x]
  (pr-str (canonicalize x)))

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
  "prefix + FNV-1a-64 hex of the canonical EDN of `form`."
  [prefix form]
  (str prefix (hex64 (fnv1a-64 (utf8-bytes (canonical-edn form))))))

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
