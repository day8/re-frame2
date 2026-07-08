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
;; Single-pass mutually-recursive walker (`canonical-edn-into`): each
;; canonical fragment appends into a per-call `StringBuilder` on JVM /
;; mutable JS string accumulator on CLJS; the accumulated string is then
;; byte-hashed once. The output (the parity-pinned canonical literal at
;; every fixture in `re-frame.ssr.hash-parity-fixtures`) is byte-
;; identical to the prior nested-`(str ...)` shape that materialised the
;; whole form before hashing — only the allocation profile changes.
;;
;; `canonical-edn` (string-returning) is retained as the public surface
;; for tests + the JVM↔CLJS parity fixtures that pin the canonical-EDN
;; literal directly; it delegates to the same builder-driven walker.

(declare canonical-edn-into)

(defn canonical-number
  "Cross-runtime-stable print form of a number — shared by the render-tree
  hash (`canonical-edn-into`'s numeric leaf) and the HTML emitter
  (`re-frame.ssr.emit`'s number branch) so the hash and the emitted markup
  stay byte-consistent (rf2-0ypnnk).

  ClojureScript has only IEEE doubles: it unifies a whole-valued double to
  an integer (`1.0` IS the JS number 1, printing `\"1\"`) and has no ratio
  type. The JVM prints `\"1.0\"` for that same `Double` and `\"1/2\"` for a
  `Ratio`, so a render tree carrying either hashed AND rendered divergently
  across runtimes — a spurious hydration mismatch that CRASHES under
  `:ssr {:on-mismatch :hard-error}`. Canonicalise to the CLJS form:

    - a whole-valued double/float within IEEE exact-integer range prints as
      its integer form (no trailing `.0`), matching CLJS;
    - a `Ratio` prints as its IEEE-double form (mirroring the head emitter's
      rf2-8jl26 ratio coercion, and a CLJS view that computed the same value
      by division);
    - every other number keeps its default `pr-str` form — integers and
      common decimals already agree across runtimes, and `pr-str`/`str`
      coincide for numbers (no quoting), so the emitter reuses this for HTML.

  Residual: a whole-valued double of magnitude ≥ 2^53 (the IEEE exact-
  integer limit) or one that prints in scientific notation keeps the JVM
  default — the JVM/JS shortest-round-trip float algorithms diverge there
  and no cheap normalisation closes it. Such values are vanishingly rare in
  SSR view output; the common price/progress `9.0`/`0.0` case is fully
  canonical."
  [x]
  #?(:clj
     (cond
       (ratio? x)
       (canonical-number (double x))

       (and (or (instance? Double x) (instance? Float x))
            (let [d (double x)]
              (and (not (Double/isNaN d))
                   (not (Double/isInfinite d))
                   (== d (Math/rint d))                       ;; whole-valued
                   (<= -9.007199254740992E15 d 9.007199254740992E15))))
       (str (long x))

       :else (pr-str x))
     :cljs
     (pr-str x)))

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

(defn- key-canonical
  "The canonical-EDN string of a map KEY, used as the map's sort key
  (rf2-mff1ht). `(comp str key)` is NOT a total order — a keyword `:a`
  and a string `\":a\"` both `str` to `\":a\"`, so `sort-by` falls back
  to source iteration/insertion order and logically-equal maps can
  canonicalise to different strings (and hash differently). The canonical
  EDN of a key distinguishes those shapes (`:a` vs the quoted `\":a\"`)
  while leaving keyword-only ordering byte-identical to the old `str`
  order (a keyword's canonical form equals its `str` form), so the
  parity-pinned fixtures stay green. A nil key (not pruned — only nil
  VALUES are) canonicalises to the empty string, mirroring the prior
  `(str nil)` => `\"\"` sort position."
  [k]
  (or (member-canonical k) ""))

(defn- append-map!
  "Maps: emit `{` + sorted key-value pairs (`key value`, comma-joined) + `}`.
  Entries sorted by the canonical-EDN form of the key (rf2-mff1ht) — a
  total, cross-runtime-stable order, unlike `(comp str key)` which
  collides string-form-equal keys of different types. Nil-valued entries
  pruned per Spec 011 §Hydration-mismatch detection."
  [sb m]
  (let [entries (->> m
                     (remove (comp nil? val))
                     (sort-by (comp key-canonical key)))]
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
    ;; A raw fn head — `[my-component props]`, the idiomatic Reagent/UIx/
    ;; Helix SSR shape where `my-component` is the deref'd defn VALUE (a raw
    ;; fn, not a Var). `(.toString fn)` is NOT cross-runtime stable: JVM =
    ;; class-name + identity-hashcode, CLJS = the JS source — never equal.
    ;; The server hashes the raw render tree and the client re-hashes the
    ;; SAME raw tree, so a fn `.toString` in the canonical EDN made byte-
    ;; identical HTML hash differently → a spurious
    ;; `:rf.ssr/hydration-mismatch` that CRASHES under
    ;; `:ssr {:on-mismatch :hard-error}` (rf2-jsa2ml). No cross-runtime-
    ;; stable fn identity exists, so drop it: every raw fn head serialises
    ;; to one fixed token. The fn's props (the rest of the child vector)
    ;; still hash, and a Var head stays identity-stable — a Var is NOT `fn?`
    ;; on the JVM, so `[#'ns/view …]` falls to `:else` → `(pr-str …)` →
    ;; `#'ns/view`, identical on both runtimes.
    (do #?(:clj  (.append ^StringBuilder sb "#fn[]")
           :cljs (.push sb "#fn[]"))
        sb)

    (number? x)
    ;; Canonicalise the numeric print form so the JVM `1.0`/`1/2` and the
    ;; CLJS `1`/`0.5` agree (rf2-0ypnnk) — see `canonical-number`. The
    ;; emitter (`re-frame.ssr.emit`) applies the SAME normalisation to the
    ;; HTML so the structural hash and the rendered markup stay byte-
    ;; consistent for the same logical tree.
    (do #?(:clj  (.append ^StringBuilder sb ^String (canonical-number x))
           :cljs (.push sb (canonical-number x)))
        sb)

    :else
    (do #?(:clj  (.append ^StringBuilder sb ^String (pr-str x))
           :cljs (.push sb (pr-str x)))
        sb)))

(defn canonical-edn
  "Print a render-tree node in a stable order. Maps are sorted by the
  canonical-EDN form of their keys (a total order — rf2-mff1ht);
  sequences keep order. A raw fn head serialises to one fixed identity-
  free token (`#fn[]`) — its `.toString` is not cross-runtime stable
  (rf2-jsa2ml); a Var reference keeps its `#'ns/name` print form (stable
  both runtimes). Numeric leaves are canonicalised via `canonical-number`
  so whole-valued doubles / ratios agree cross-runtime (rf2-0ypnnk).

  Per Spec 011 §Hydration-mismatch detection: **nil pruned**. Two render
  trees that differ only by absent-key vs nil-value are structurally
  equivalent — `[:div {:class nil}]` and `[:div {}]` hash identically;
  `[:p \"text\" nil]` and `[:p \"text\"]` hash identically. Without this
  pruning the server emits `{:class nil}` while the client emits `{}`
  (the common `{:class (when condition? :selected)}` shape), producing
  spurious `:rf.ssr/hydration-mismatch` traces. Returns nil for nil
  input so the parent collection's `keep` / `remove` step prunes it.

  Delegates to `canonical-edn-into` with a fresh accumulator (rf2-8otin) —
  the public surface is unchanged but the production hash path skips this
  allocation entirely (see `render-tree-hash`)."
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
  accumulator and byte-hashes the result once. The streaming walker
  emits byte-identical output to the prior tree-shaped-intermediates
  shape; the parity-pinned literals at `re-frame.ssr.hash-parity-fixtures`
  are the cross-runtime contract."
  [render-tree]
  #?(:clj
     (let [sb (StringBuilder.)]
       (canonical-edn-into sb render-tree)
       (fnv-1a-32 (.toString sb)))
     :cljs
     (let [sb (array)]
       (canonical-edn-into sb render-tree)
       (fnv-1a-32 (.join sb "")))))
