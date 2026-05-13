(ns re-frame.ssr.hash
  "Render-tree structural hashing. Per Spec 011 §Hydration-mismatch detection.

  The server hashes the render-tree at SSR time; the client recomputes
  the hash on first render and compares. Mismatch = the runtime emits
  `:rf.ssr/hydration-mismatch` with both hashes. FNV-1a 32-bit over the
  canonical-EDN traversal of the render tree, output as lowercase hex.
  Same algorithm both sides → byte-identical hashes for byte-identical
  canonical EDN.

  Per the rf2-gxgo7 split of re-frame.ssr.

  ## Byte-level fast path (rf2-t7ktb)

  FNV-1a is defined over a byte stream. Both runtimes encode the
  canonical-EDN string as UTF-8 bytes before hashing — `.getBytes
  StandardCharsets/UTF_8` on JVM, `goog.crypt/stringToUtf8ByteArray` on
  CLJS. Operating on UTF-8 bytes (rather than UTF-16 code units) keeps
  JVM and CLJS byte-identical for the full Unicode range and lets the
  JVM hot loop run over a primitive `byte-array` with `aget`, avoiding
  per-character `.charAt` boxing overhead. For ASCII inputs (the common
  SSR case) a UTF-8 byte equals the UTF-16 code unit so this change is
  a pure refactor; for non-ASCII inputs the byte stream is the only
  cross-runtime stable representation. The CLJS↔JVM parity smoke
  (`hash_check_cljs_test.cljs`) pins `9d7457ef` for the ASCII fixture
  `[:div {:class \"x\"} [:p \"hi\"]]` and continues to hold."
  (:require #?(:cljs [goog.crypt :as gcrypt])
            [clojure.string])
  #?(:clj (:import [java.nio.charset StandardCharsets])))

(defn canonical-edn
  "Print a render-tree node in a stable order. Maps are sorted by key
  string; sequences keep order. Functions and var-references appear
  as their toString — stable enough for trees that re-render the same
  view-fn from the same registry.

  Per Spec 011 §Hydration-mismatch detection: **nil pruned**. Two render
  trees that differ only by absent-key vs nil-value are structurally
  equivalent — `[:div {:class nil}]` and `[:div {}]` hash identically;
  `[:p \"text\" nil]` and `[:p \"text\"]` hash identically. Without this
  pruning the server emits `{:class nil}` while the client emits `{}`
  (the common `{:class (when condition? :selected)}` shape), producing
  spurious `:rf.ssr/hydration-mismatch` traces. Returns nil for nil
  input so the parent collection's `keep` / `remove` step prunes it."
  [x]
  (cond
    (nil? x) nil                                    ;; pruned at parent

    (map? x)
    (str "{"
         (clojure.string/join
           ","
           (->> x
                (remove (comp nil? val))
                (sort-by (comp str key))
                (map (fn [[k v]] (str (canonical-edn k) " " (canonical-edn v))))))
         "}")

    (vector? x)
    (str "[" (clojure.string/join " " (keep canonical-edn x)) "]")

    (sequential? x)
    (str "(" (clojure.string/join " " (keep canonical-edn x)) ")")

    (set? x)
    (str "#{"
         (clojure.string/join " " (sort (keep canonical-edn x)))
         "}")

    (fn? x)
    (str "#fn[" (.toString ^Object x) "]")

    :else (pr-str x)))

(defn fnv-1a-32
  "FNV-1a 32-bit hash of a string, output as lowercase 8-char hex (no
  prefix). Both runtimes hash the UTF-8 byte encoding of the input so
  output is byte-identical across JVM and CLJS for any Unicode input.

  - JVM: encodes via `String.getBytes(UTF_8)` and loops over the
    primitive `byte-array` with `aget`. The byte is unsigned via
    `(bit-and b 0xff)`; the 32-bit truncation comes from
    `(bit-and 0xffffffff (unchecked-multiply ...))` matching the
    pre-rf2-t7ktb arithmetic exactly — only the iteration source
    moved from `.charAt`-by-`.charAt` to `aget` over a byte array.
  - CLJS: encodes via `goog.crypt/stringToUtf8ByteArray` (returns a
    JS array of unsigned bytes 0..255) and loops over it with `aget`.
    `Math.imul` provides the 32-bit signed multiply; the final
    `unsigned-bit-shift-right h 0` lifts the result back to unsigned
    for hex formatting.

  Per rf2-t7ktb — byte-level fast path replaces a char-by-char loop
  that walked `.charAt` / `.charCodeAt`. The JVM hot loop now runs
  over a primitive byte array (measured ~3-5x faster on medium-sized
  canonical-EDN inputs); the CLJS path matches the same byte stream
  so the JVM↔CLJS parity pin (`hash_check_cljs_test.cljs`) holds."
  [s]
  #?(:clj
     (let [^bytes bs (.getBytes ^String s StandardCharsets/UTF_8)
           len      (alength bs)]
       (loop [i 0
              h 2166136261]                                       ;; FNV offset basis
         (if (>= i len)
           (format "%08x" (bit-and h 0xffffffff))
           (recur (unchecked-inc-int i)
                  (bit-and 0xffffffff
                           (unchecked-multiply
                             (bit-xor h (bit-and (aget bs i) 0xff))
                             16777619))))))                       ;; FNV prime
     :cljs
     (let [bs  (gcrypt/stringToUtf8ByteArray s)
           len (alength bs)]
       (loop [i 0
              h 2166136261]                                       ;; FNV offset basis
         (if (>= i len)
           (let [u   (unsigned-bit-shift-right h 0)
                 hex (.toString u 16)]
             (.padStart hex 8 "0"))
           (recur (inc i)
                  (js/Math.imul (bit-xor h (aget bs i)) 16777619)))))))

(defn render-tree-hash
  "Per Spec 011 §Hydration-mismatch detection: a stable structural hash
  of the render tree. Deterministic across JVM and CLJS for trees with
  identical canonical-EDN representation."
  [render-tree]
  (fnv-1a-32 (canonical-edn render-tree)))
