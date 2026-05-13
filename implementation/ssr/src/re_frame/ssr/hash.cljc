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
  "FNV-1a 32-bit hash of a string. Returns the lowercase-hex form, no
  prefix. Stable on JVM and CLJS — uses unchecked 32-bit multiply on
  both sides (CLJS via Math.imul, JVM via long-multiply-then-mask).
  Output bytes are byte-identical for byte-identical input strings."
  [s]
  (let [offset 2166136261              ;; FNV offset basis
        prime  16777619]               ;; FNV prime
    (loop [i 0
           h offset]
      (if (>= i (count s))
        ;; Convert h to unsigned 32-bit and emit lowercase 8-char hex.
        ;; JS's bitwise ops are 32-bit SIGNED; the `>>> 0` idiom forces
        ;; unsigned. JVM bit-and-with-0xffffffff suffices.
        #?(:clj  (format "%08x" (bit-and h 0xffffffff))
           :cljs (let [u (unsigned-bit-shift-right h 0)
                       hex (.toString u 16)]
                   (.padStart hex 8 "0")))
        (let [c (#?(:clj int :cljs .charCodeAt) (.charAt s i) #?(:cljs 0))
              x (bit-xor h c)
              ;; Guaranteed 32-bit multiply.
              p #?(:clj  (bit-and 0xffffffff
                                  (unchecked-multiply x prime))
                   :cljs (js/Math.imul x prime))]
          (recur (inc i) p))))))

(defn render-tree-hash
  "Per Spec 011 §Hydration-mismatch detection: a stable structural hash
  of the render tree. Deterministic across JVM and CLJS for trees with
  identical canonical-EDN representation."
  [render-tree]
  (fnv-1a-32 (canonical-edn render-tree)))
