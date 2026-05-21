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

;; ---- canonical-EDN walker (rf2-8otin / rf2-atmvj) -------------------------
;;
;; Pre-rf2-8otin the walker materialised the whole canonical form as a
;; nested `(str ...)` of `clojure.string/join` results, then handed the
;; finished string to `fnv-1a-32` which walked the UTF-8 bytes a second
;; time. Two passes plus a tree-shaped tower of intermediate strings.
;;
;; The new shape is single-pass: a mutually-recursive walker
;; (`canonical-edn-into`) appends each canonical fragment into a
;; per-call `StringBuilder` on JVM / mutable JS string accumulator on
;; CLJS. The accumulated string is then byte-hashed once. The output
;; (the parity-pinned canonical literal at every fixture in
;; `re-frame.ssr.hash-parity-fixtures`) is byte-identical to the old
;; walker — only the allocation profile changes.
;;
;; `canonical-edn` (string-returning) is retained as the public surface
;; for tests + the JVM↔CLJS parity fixtures that pin the canonical-EDN
;; literal directly; it now delegates to the same builder-driven walker.

(declare canonical-edn-into)

(defn- append-children!
  "Append canonical-EDN of each non-nil child of `xs` into `sb`,
  separated by `sep`. Maps with nil values and sequential children
  that are nil are pruned at the parent per Spec 011 §Hydration-mismatch
  detection."
  [sb xs sep]
  (loop [xs    (seq xs)
         first? true]
    (when xs
      (let [x (first xs)]
        (if (nil? x)
          (recur (next xs) first?)
          (do
            (when-not first?
              #?(:clj (.append ^StringBuilder sb ^String sep)
                 :cljs (.push sb sep)))
            (canonical-edn-into sb x)
            (recur (next xs) false)))))))

(defn- member-canonical
  "Render one set/seq member's canonical form to a fresh accumulator.
  Used for the set branch where members must be lexicographically
  sorted by their canonical form before appending. Returns nil for
  nil so the caller can `keep`-prune."
  [x]
  (when-not (nil? x)
    #?(:clj
       (let [sb (StringBuilder.)]
         (canonical-edn-into sb x)
         (.toString sb))
       :cljs
       (let [sb (array)]
         (canonical-edn-into sb x)
         (.join sb "")))))

(defn- append-sorted-set!
  "Sets: emit `#{` + sorted-by-canonical-EDN members + `}`. Members can
  be heterogeneous, so we materialise per-member canonical forms,
  sort the strings, then append. The set branch is the only place
  intermediate strings remain — sets need a comparator, and string
  comparison is the spec-stable one across both runtimes."
  [sb s]
  (let [items (->> s (keep member-canonical) sort)]
    #?(:clj
       (do (.append ^StringBuilder sb "#{")
           (loop [items (seq items) first? true]
             (when items
               (when-not first? (.append ^StringBuilder sb \space))
               (.append ^StringBuilder sb ^String (first items))
               (recur (next items) false)))
           (.append ^StringBuilder sb \}))
       :cljs
       (do (.push sb "#{")
           (loop [items (seq items) first? true]
             (when items
               (when-not first? (.push sb " "))
               (.push sb (first items))
               (recur (next items) false)))
           (.push sb "}")))))

(defn- append-map!
  "Maps: emit `{` + sorted key-value pairs (`key value`, comma-joined) + `}`.
  Nil-valued entries pruned per Spec 011 §Hydration-mismatch detection."
  [sb m]
  (let [entries (->> m
                     (remove (comp nil? val))
                     (sort-by (comp str key)))]
    #?(:clj  (.append ^StringBuilder sb \{)
       :cljs (.push sb "{"))
    (loop [es (seq entries) first? true]
      (when es
        (when-not first?
          #?(:clj  (.append ^StringBuilder sb \,)
             :cljs (.push sb ",")))
        (let [[k v] (first es)]
          (canonical-edn-into sb k)
          #?(:clj  (.append ^StringBuilder sb \space)
             :cljs (.push sb " "))
          (canonical-edn-into sb v))
        (recur (next es) false)))
    #?(:clj  (.append ^StringBuilder sb \})
       :cljs (.push sb "}"))))

(defn canonical-edn-into
  "Streaming canonical-EDN walker (rf2-8otin). Appends the canonical
  serialisation of `x` into the accumulator `sb` — a `StringBuilder`
  on JVM, a JS array on CLJS. Returns `sb`. Nil scalars are pruned at
  the parent (this fn no-ops on nil; callers walking a collection use
  `append-children!` which skips nil children).

  Output is byte-identical to (the now-thin) `canonical-edn`; the
  parity-pinned literals at `re-frame.ssr.hash-parity-fixtures` are
  the cross-runtime contract."
  [sb x]
  (cond
    (nil? x) sb                                     ;; pruned at parent

    (map? x)
    (do (append-map! sb x) sb)

    (vector? x)
    (do #?(:clj  (.append ^StringBuilder sb \[)
           :cljs (.push sb "["))
        (append-children! sb x " ")
        #?(:clj  (.append ^StringBuilder sb \])
           :cljs (.push sb "]"))
        sb)

    (sequential? x)
    (do #?(:clj  (.append ^StringBuilder sb \()
           :cljs (.push sb "("))
        (append-children! sb x " ")
        #?(:clj  (.append ^StringBuilder sb \))
           :cljs (.push sb ")"))
        sb)

    (set? x)
    (do (append-sorted-set! sb x) sb)

    (fn? x)
    (do #?(:clj  (.append ^StringBuilder sb "#fn[")
           :cljs (.push sb "#fn["))
        #?(:clj  (.append ^StringBuilder sb ^String (.toString ^Object x))
           :cljs (.push sb (.toString x)))
        #?(:clj  (.append ^StringBuilder sb \])
           :cljs (.push sb "]"))
        sb)

    :else
    (do #?(:clj  (.append ^StringBuilder sb ^String (pr-str x))
           :cljs (.push sb (pr-str x)))
        sb)))

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
  input so the parent collection's `keep` / `remove` step prunes it.

  Post rf2-8otin this delegates to `canonical-edn-into` with a fresh
  accumulator — the public surface is unchanged but the production
  hash path skips this allocation entirely (see `render-tree-hash`)."
  [x]
  (when-not (nil? x)
    #?(:clj
       (let [sb (StringBuilder.)]
         (canonical-edn-into sb x)
         (.toString sb))
       :cljs
       (let [sb (array)]
         (canonical-edn-into sb x)
         (.join sb "")))))

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
  identical canonical-EDN representation.

  Single-pass (rf2-8otin) — feeds the canonical-EDN walker into one
  accumulator and byte-hashes the result once. Pre-rf2-8otin
  `canonical-edn` materialised a tree-shaped tower of intermediate
  strings (one per nesting level from the inner `(str ...)` /
  `clojure.string/join` calls) before `fnv-1a-32` walked the bytes;
  the streaming walker eliminates those intermediates while preserving
  byte-identical output (the parity-pinned literals at
  `re-frame.ssr.hash-parity-fixtures` are the cross-runtime contract)."
  [render-tree]
  #?(:clj
     (let [sb (StringBuilder.)]
       (canonical-edn-into sb render-tree)
       (fnv-1a-32 (.toString sb)))
     :cljs
     (let [sb (array)]
       (canonical-edn-into sb render-tree)
       (fnv-1a-32 (.join sb "")))))
