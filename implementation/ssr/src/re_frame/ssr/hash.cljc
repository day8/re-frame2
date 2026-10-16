(ns re-frame.ssr.hash
  "The hydration-payload install ledger's content digest, and the numeric
  print form the HTML emitter shares with it.

  ## `data-hash` — the install ledger's digest

  The multi-root install ledger (`re-frame.ssr.install`) asks \"is the
  payload arriving under this id the SAME DATA as the one already
  installed?\". `data-hash` answers it with a digest of the payload as
  DATA: nil preserved everywhere (`{:x nil}` is not `{}`, `[nil 7]` is not
  `[7]`, `#{nil 1}` is not `#{1}`), map insertion order irrelevant, set
  order irrelevant, sequence position significant. A canonicalisation
  that dropped nil would make each of those pairs collide
  DETERMINISTICALLY, so a second root carrying a genuinely different
  server slice would be waved through as `:already-installed` and hydrate
  index-based content against the wrong data, silently, where the ledger
  exists to fail loud.

  ## Cross-runtime agreement

  `fnv-1a-32` (the bytes → hex step) and `canonical-number` (the
  cross-runtime numeric print form) make the digest byte-identical on the
  JVM and in CLJS for the same logical value, which is what lets two roots
  reading one `__rf_payload` script agree on either host.
  `canonical-number` is also the HTML emitter's number form
  (`re-frame.ssr.emit`), so a number renders the same text on both hosts."
  (:require [clojure.string]))

;; Reflection warnings guard the primitive JVM hash loop against boxing.
;; The JVM-side `fnv-1a-32` uses `.getBytes` + an `aget` loop with
;; primitive math; a reflection warning here would flag accidental
;; boxing introduced by future refactors. CLJS has no reflection
;; concept — the directive is JVM-only.
#?(:clj (set! *warn-on-reflection* true))

(defn canonical-number
  "Cross-runtime-stable print form of a number — shared by the data digest
  (`canonical-data-edn`'s numeric leaf) and the HTML emitter
  (`re-frame.ssr.emit`'s number branch).

  ClojureScript has only IEEE doubles: it unifies a whole-valued double to
  an integer (`1.0` IS the JS number 1, printing `\"1\"`) and has no ratio
  type. The JVM prints `\"1.0\"` for that same `Double` and `\"1/2\"` for a
  `Ratio`, so a server render carrying either would paint different text
  from the client's render of the same view — a text divergence React
  hydration recovers from by re-rendering. Canonicalise to the CLJS form:

    - a whole-valued double/float within IEEE exact-integer range prints as
      its integer form (no trailing `.0`), matching CLJS;
    - a `Ratio` prints as its IEEE-double form (mirroring a CLJS view that computed the same value
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
  [number]
  #?(:clj
     (cond
       (ratio? number)
       (canonical-number (double number))

       (and (or (instance? Double number) (instance? Float number))
            (let [d (double number)]
              (and (not (Double/isNaN d))
                   (not (Double/isInfinite d))
                   (== d (Math/rint d))                       ;; whole-valued
                   (<= -9.007199254740992E15 d 9.007199254740992E15))))
       (str (long number))

       :else (pr-str number))
     :cljs
     (pr-str number)))

;; FNV-1a runs over a UTF-8 byte stream on both sides.
;; Why UTF-8 bytes, not UTF-16 code units?
;;
;;   - JVM uses primitive `byte[]` + `aget` — no boxing per step, ~3-5×
;;     faster than a `.charAt`-per-step loop on medium inputs.
;;   - CLJS uses `TextEncoder` → `Uint8Array` and a `Math.imul` per-byte
;;     loop — same per-byte semantics as JVM.
;;
;; Byte-identity: UTF-8 is byte-deterministic, so a UTF-8 byte sequence
;; computed by `String.getBytes(UTF_8)` on JVM is identical to the one
;; produced by `TextEncoder('utf-8').encode(canonical-string)` in JavaScript. The hash
;; output is therefore byte-identical across runtimes for any input the
;; canonical-EDN serializer can produce.
;;
;; ASCII subset: for ASCII codepoints (0..127), UTF-8 emits a single byte
;; whose numeric value equals the UTF-16 code unit. A
;; `.charCodeAt` / `.charAt`-int path therefore produces the same hash as
;; this byte-level path for ASCII input — the JVM↔CLJS parity pin in
;; `hash_check_cljs_test.cljs` is ASCII.

(defn fnv-1a-32
  "FNV-1a 32-bit hash of a string over its UTF-8 byte sequence. Returns
  the lowercase 8-char hex form, no prefix.

  Stable on JVM and CLJS — both sides hash UTF-8 bytes with an unchecked
  32-bit multiply (CLJS via `Math.imul`, JVM via long-multiply-then-mask).
  Output hex string is byte-identical across runtimes for byte-identical
  input strings."
  [canonical-string]
  #?(:clj
     (let [bytes ^bytes (.getBytes ^String canonical-string java.nio.charset.StandardCharsets/UTF_8)
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
     (let [bytes (.encode (js/TextEncoder.) canonical-string) ;; Uint8Array
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

(defn canonical-data-edn
  "Canonical EDN of a DATA value — nil PRESERVED. -> a string.

  The rules:

    - **nil is content.** `nil` prints as `\"nil\"` wherever it appears —
      as a map value, a sequence element, a set member or the whole value.
      `{:x nil}` and `{}` therefore differ, as do `[nil 7]` / `[7]` and
      `#{nil 1}` / `#{1}`. There is no sentinel and so no sentinel
      collision: `nil` is an EDN literal and `pr-str` already distinguishes
      it from the STRING `\"nil\"` (which prints with its quotes).

    - **Map insertion order is not content**, so entries are emitted sorted
      by the canonical form of their KEY — a total order, where `str`
      alone would collide a keyword `:a` with the string `\":a\"`, whose
      canonical forms differ.

    - **Set order is not content**, so members are emitted sorted by their
      own canonical form.

    - **Sequence position IS content**, so vectors and seqs keep their
      order and are distinguished from each other (`[…]` vs `(…)`).

    - **Numbers go through `canonical-number`**, so a JVM `1.0` and a CLJS
      `1` — the SAME IEEE double, printed differently by the two hosts —
      agree. That unification is not a nil-style collision: CLJS cannot
      represent the two apart at all, so preserving the JVM's distinction
      would break the cross-host agreement the ledger depends on.

    - **Everything else is `pr-str`**, which is total over EDN. A payload
      is data — the deserialised `__rf_payload` — so a non-data value in
      one (a raw fn, a foreign JS object) is a caller error; it hashes by
      its host print form, which makes two such payloads DISAGREE and fail
      loud as a conflict rather than alias silently. That is the safe
      direction for a ledger."
  [value]
  (cond
    (map? value)
    (str "{"
         (->> value
              (sort-by (comp canonical-data-edn key))
              (map (fn [[map-key entry-value]]
                     (str (canonical-data-edn map-key) " "
                          (canonical-data-edn entry-value))))
              (clojure.string/join ","))
         "}")

    (vector? value)
    (str "[" (clojure.string/join " " (map canonical-data-edn value)) "]")

    (set? value)
    (str "#{" (clojure.string/join " " (sort (map canonical-data-edn value))) "}")

    (sequential? value)
    (str "(" (clojure.string/join " " (map canonical-data-edn value)) ")")

    (number? value)
    (canonical-number value)

    :else
    (pr-str value)))

(defn data-hash
  "A stable structural hash of a DATA value — nil preserved. Lowercase
  hex, FNV-1a 32-bit over `canonical-data-edn`, byte-identical on JVM and
  CLJS for the same logical value.

  The install ledger's content digest (`re-frame.ssr.install/payload-content-digest`)."
  [value]
  (fnv-1a-32 (canonical-data-edn value)))
