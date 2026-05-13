(ns re-frame.ssr.hash
  "Render-tree structural hashing. Per Spec 011 §Hydration-mismatch detection.

  The server hashes the render-tree at SSR time; the client recomputes
  the hash on first render and compares. Mismatch = the runtime emits
  `:rf.ssr/hydration-mismatch` with both hashes. FNV-1a 32-bit over the
  canonical-EDN traversal of the render tree, output as lowercase hex.
  Same algorithm both sides → byte-identical hashes for byte-identical
  canonical EDN.

  Per the rf2-gxgo7 split of re-frame.ssr."
  (:require [clojure.string]))

;; Audit rf2-asmj1 P6 / cluster rf2-sljs1 — reflection-warning gate.
;; The JVM-side `fnv-1a-32` uses `.getBytes` + an `aget` loop with
;; primitive math; a reflection warning here would flag accidental
;; boxing introduced by future refactors. CLJS has no reflection
;; concept — the directive is JVM-only.
#?(:clj (set! *warn-on-reflection* true))

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

;; Per rf2-t7ktb: FNV-1a runs over a UTF-8 byte stream on BOTH sides.
;; Why UTF-8 bytes, not UTF-16 code units?
;;
;;   - JVM uses primitive `byte[]` + `aget` — no boxing per step, ~3-5×
;;     faster than the previous `.charAt`-per-step loop on medium trees.
;;   - CLJS uses `TextEncoder` → `Uint8Array` and a `Math.imul` per-byte
;;     loop — same per-byte semantics as JVM.
;;
;; Byte-identity: UTF-8 is byte-deterministic, so a UTF-8 byte sequence
;; computed by `String.getBytes(UTF_8)` on JVM is identical to the one
;; produced by `TextEncoder('utf-8').encode(s)` in JavaScript. The hash
;; output is therefore byte-identical across runtimes for any input the
;; canonical-EDN serializer can produce.
;;
;; ASCII subset: for ASCII codepoints (0..127), UTF-8 emits a single byte
;; whose numeric value equals the UTF-16 code unit. The previous
;; `.charCodeAt` / `.charAt`-int path therefore produced the same hash as
;; this byte-level path for ASCII input — the JVM↔CLJS parity pin in
;; `hash_check_cljs_test.cljs` (`9d7457ef` for `[:div {:class "x"} [:p "hi"]]`)
;; is ASCII and survives unchanged.

(defn fnv-1a-32
  "FNV-1a 32-bit hash of a string over its UTF-8 byte sequence. Returns
  the lowercase 8-char hex form, no prefix.

  Stable on JVM and CLJS — both sides hash UTF-8 bytes with an unchecked
  32-bit multiply (CLJS via `Math.imul`, JVM via long-multiply-then-mask).
  Output hex string is byte-identical across runtimes for byte-identical
  input strings."
  [s]
  #?(:clj
     (let [bytes ^bytes (.getBytes ^String s java.nio.charset.StandardCharsets/UTF_8)
           len   (alength bytes)]
       (loop [i (int 0)
              h (long 2166136261)]                       ;; FNV offset basis
         (if (>= i len)
           (format "%08x" (bit-and h 0xffffffff))
           (recur (unchecked-inc-int i)
                  (bit-and 0xffffffff
                           (unchecked-multiply
                             (bit-xor h (bit-and (aget bytes i) 0xff))
                             16777619))))))             ;; FNV prime
     :cljs
     (let [bytes (.encode (js/TextEncoder.) s)           ;; Uint8Array
           len   (.-length bytes)]
       (loop [i 0
              h 2166136261]                              ;; FNV offset basis
         (if (>= i len)
           (let [u (unsigned-bit-shift-right h 0)
                 hex (.toString u 16)]
             (.padStart hex 8 "0"))
           (recur (inc i)
                  (js/Math.imul (bit-xor h (aget bytes i))
                                16777619)))))))         ;; FNV prime

(defn render-tree-hash
  "Per Spec 011 §Hydration-mismatch detection: a stable structural hash
  of the render tree. Deterministic across JVM and CLJS for trees with
  identical canonical-EDN representation."
  [render-tree]
  (fnv-1a-32 (canonical-edn render-tree)))
