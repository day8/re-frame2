(ns re-frame.recordable
  "Internal DATA-ONLY recordable-value predicate (EP-0017 erratum).

  ## Purpose — \"can this value be recorded and read back as ordinary data?\"

  A *recordable* coeffect value rides the durable causal record: it is folded
  into the epoch ledger, replayed verbatim, shipped in the SSR payload,
  exported, and re-read by Xray / pair tooling. So it MUST be ordinary EDN
  data — a value that round-trips through `pr-str` / `read-string` (or its
  host-portable equivalent) unchanged in meaning. A host handle accepted as
  recordable (a DOM node, a `Promise`, a function, an atom, a `Date`, any
  JS / Java object) breaks that contract SILENTLY: the failure surfaces far
  away, at replay / Xray / SSR / epoch-export time, not at the bad coeffect.
  This namespace is the cheap, dev-time guard that catches the author error
  at the source (EP-0017:386 — recordable values must be EDN).

  ## NOT the canonical-identity tool

  This is deliberately NOT `re-frame.identity/canonical`. That namespace is
  the CEDN-1 *equality* canonicaliser for cache keys / route params / work
  ids: it is heavier and stricter for a DIFFERENT purpose — it rejects
  floats, ratios, big decimals, and out-of-safe-range integers because they
  cannot be portable equality keys. None of those are problems for a
  RECORDED value: a float is perfectly good data to write down and read
  back. So this predicate ACCEPTS the full EDN data domain (any number,
  including floats / ratios) and rejects ONLY genuine non-data host handles.
  Mixing the two purposes would either wrongly reject valid recordable data
  or wrongly accept a host handle as an identity. They stay separate.

  ## Shape — allow-list, fail-safe

  `recordable-edn-value?` is a recursive ALLOW-LIST: it accepts the known EDN
  leaf kinds (nil / boolean / number / string / keyword / symbol / char /
  `#uuid` / `#inst`) and recurses into the EDN collection kinds (vector /
  list / seq / set / map). Anything outside that closed set — a function, an
  atom / `IDeref`, a `Promise`, a DOM node, a host / JS / Java object — falls
  through to REJECT by construction. Adding a new host type can never
  accidentally pass; only an explicit new accept-branch widens the domain.

  Pure namespace — no runtime state, no trace, no host I/O. `.cljc` so the
  JVM test sweep exercises the walker cross-host."
  #?(:clj (:import [java.util UUID Date])))

#?(:clj (set! *warn-on-reflection* true))

;; ---- leaf-kind discrimination (host-portable) -----------------------------

(defn- uuid-leaf?
  [x]
  #?(:clj  (instance? UUID x)
     :cljs (uuid? x)))

(defn- inst-leaf?
  "A `#inst` value that ROUND-TRIPS through `pr-str` / `read-string`: a
  `java.util.Date` on the JVM, a `js/Date` on CLJS. `#inst` is a tagged EDN
  literal, so such an instant IS recordable data even though it is a host
  object — the EDN reader's default `#inst` reader returns it unchanged in
  meaning.

  `java.time.Instant` is DELIBERATELY excluded on the JVM. Although Clojure's
  `pr-str` prints an `Instant` with the `#inst` tag, the EDN reader's default
  `#inst` reader produces a `java.util.Date`, NOT an `Instant` — and with no
  data-reader bound for the tag a bare `read-string` of the printed form throws
  `No reader function for tag inst`. So an `Instant` does NOT round-trip; treat
  it as a non-recordable host handle so the failure surfaces AT THE SOURCE
  coeffect (via `explain-non-recordable`) rather than far away at replay /
  Xray / SSR / epoch-export time. Authors who genuinely need to record an
  instant convert to `java.util.Date` (`Date/from`) at the boundary."
  [x]
  #?(:clj  (instance? Date x)
     :cljs (instance? js/Date x)))

(defn- recordable-leaf?
  "True iff `x` is a non-collection EDN leaf value that records and reads back
  as ordinary data. The CLOSED accept-set: nil, boolean, number (ANY number —
  integer / float / ratio / decimal; all are valid recorded data), string,
  keyword, symbol, char, `#uuid`, `#inst`. Everything else (function, atom,
  promise, DOM node, host object) is NOT a recordable leaf."
  [x]
  (or (nil? x)
      (boolean? x)
      (number? x)
      (string? x)
      (keyword? x)
      (symbol? x)
      (char? x)
      (uuid-leaf? x)
      (inst-leaf? x)))

;; ---- recursive walk -------------------------------------------------------

(declare first-non-recordable)

(defn- type-name
  "A safe printable type name for `x` — never the raw host object."
  [x]
  (cond
    (nil? x) "nil"
    :else    (str (type x))))

(defn- walk-elements
  "Walk indexed elements of a sequential collection, returning the first
  non-recordable descriptor (with the index prepended to its `:path`) or nil."
  [path xs]
  (loop [i 0, xs (seq xs)]
    (when xs
      (or (first-non-recordable (conj path i) (first xs))
          (recur (inc i) (next xs))))))

(defn- walk-set
  "Walk set elements. A set element has no index; the path segment is the
  element's own (recordable) value when it is a leaf, else a marker."
  [path s]
  (some (fn [el]
          (first-non-recordable
            (conj path (if (recordable-leaf? el) el :rf.recordable/set-element))
            el))
        s))

(defn- walk-map
  "Walk a map's keys and values. A non-recordable KEY is reported at the
  parent path with a `:rf.recordable/map-key` marker (a key is rarely a
  collection, but a composite key is still walked); a non-recordable VALUE is
  reported under its key (the key itself when it is a recordable leaf, else
  the marker)."
  [path m]
  (some (fn [[k v]]
          (or (first-non-recordable (conj path :rf.recordable/map-key) k)
              (first-non-recordable
                (conj path (if (recordable-leaf? k) k :rf.recordable/map-key))
                v)))
        m))

(defn- first-non-recordable
  "Return a descriptor `{:path <vec> :bad-type <string> :bad-value <x>}` for
  the FIRST non-recordable value reachable from `value` (depth-first), or nil
  when the whole structure is recordable EDN data. `path` is the accumulated
  path to `value` from the root.

  Records the bad value's PATH and host TYPE only — the caller decides whether
  a value-preview is safe to surface (never the raw host handle blindly)."
  [path value]
  (cond
    (recordable-leaf? value) nil

    (map? value)             (walk-map path value)
    (set? value)             (walk-set path value)
    ;; Vectors and any other sequential / seq collection (lists, lazy seqs).
    (or (vector? value)
        (sequential? value)
        (seq? value))        (walk-elements path value)

    ;; Anything else — function, atom / IDeref, Promise, DOM node, host /
    ;; JS / Java object — is not recordable data.
    :else                    {:path      path
                              :bad-type  (type-name value)
                              :bad-value value}))

(defn recordable-edn-value?
  "True iff `value` is recordable EDN data all the way down — i.e. it can be
  written to the causal record and read back as ordinary data. See the ns
  docstring for the accept / reject domain. Use `explain-non-recordable` to
  get the failing path + type for an error payload."
  [value]
  (nil? (first-non-recordable [] value)))

(defn explain-non-recordable
  "Return `{:path <vec> :bad-type <string> :bad-value <x>}` describing the
  first non-recordable value reachable from `value`, or nil when `value` is
  recordable EDN data. The `:bad-value` is the RAW value (a host handle when
  the failure is a host object) — callers MUST NOT put it verbatim into a
  trace / record; use `safe-preview` for the surfaced payload."
  [value]
  (first-non-recordable [] value))

(defn safe-preview
  "A printable, recordable-only preview of `value` for an error payload —
  NEVER the raw host object. Returns a short `pr-str` of `value` when `value`
  is itself recordable EDN data (so the preview round-trips), else nil (the
  caller surfaces `:bad-type` alone). Truncated to `max-len` chars."
  ([value] (safe-preview value 200))
  ([value max-len]
   (when (recordable-edn-value? value)
     (let [s (pr-str value)]
       (if (> (count s) max-len)
         (str (subs s 0 max-len) "…")
         s)))))
