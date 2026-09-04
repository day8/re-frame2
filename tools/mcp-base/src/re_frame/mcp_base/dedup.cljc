(ns re-frame.mcp-base.dedup
  "Structural dedup at the wire boundary — the cross-MCP codec, owned
  here and shipped in the base.

  ## Why dedup at the wire boundary

  Persistent data structures share subtrees in memory; `pr-str` flattens
  the sharing, so a payload with N references to the same subtree
  serialises that subtree N times. This namespace walks a persistent
  data structure, hash-identifies repeated subtrees, and rewrites the
  structure as a flat cache map keyed by `de-dupe.cache/cache-N`
  namespaced symbols. `expand` reconstructs it exactly.

  Both MCP servers ship duplicate-rich payloads:
  re-frame2-pair-mcp's `:epochs` slice (`:db-before` + path-keyed
  `:db-after` diff), subscribe progress `:events`; story-mcp's
  `run-variant` results (`:app-db` + `:snapshot` + the evidence
  slots) and assertion vectors. The transform is the same
  data transform on both hosts, so it lives here once.

  ## Provenance — vendored from `day8/de-dupe` v0.3.0 (rf2-2ii52)

  The codec below was an external runtime dependency,
  `day8/de-dupe {:git/url \"https://github.com/day8/de-dupe.git\"
  :git/tag \"v0.3.0\"}`, until it was ABSORBED into this artefact. The
  reason was packaging, not preference: `clein pom` can only express an
  `:mvn/version` coordinate, so it dropped the git coordinate SILENTLY
  and both `day8/re-frame2-mcp-base` and `day8/re-frame2-story-mcp`
  would have published a pom missing a runtime dependency. The library
  is not on Clojars and cannot be put there under the `day8` group
  (Clojars refuses NEW projects in unverified non-reverse-domain
  groups), so there was no version to rewrite to. Absorbing a 271-line
  single-namespace codec with one production call site was the small
  move; a new standalone release surface was the large one.

  The upstream licence text and the list of changes made while
  absorbing sit immediately above the vendored section below, per its
  MIT terms.

  ## Why equality, not identity

  Values reaching the wire boundary are equality-shared, not identity-
  shared: re-frame2-pair-mcp reconstructs CLJS values from EDN over
  bencode (no identity sharing survives the transport); story-mcp
  synthesises assertion records and rendered hiccup fresh per call.
  Equality is what makes the cross-record share-pooling actually fire on
  the wire boundary, so `de-dupe-eq` is the only encoder here — the
  upstream identity-based variant was dropped rather than carried as a
  branch nothing takes.

  ## Wire shape

  A deduped payload is wrapped in a top-level marker:
  `{:rf.mcp/dedup-table <cache-map>}` (`rf.mcp-base.vocab/dedup-table-key`). Agents
  reconstruct by calling `expand` on the cache-map value — a cross-MCP
  key by construction, so an agent that learned the slot on one server
  sees the same slot key on the other.

  The cache-element namespace stays `de-dupe.cache` after the absorb.
  It is a WIRE constant, not an implementation detail: the Node
  conformance decoder pins `de-dupe.cache/cache-0`
  (`tools/mcp-conformance/lib/dedup-envelope.cjs`), the wire-vocab
  schemas validate against it, and Spec 009 / Tool-Pair document it.
  Renaming it would be a wire break bought for nothing.

  ## Reference grammar — why a payload symbol is not a reference

  Occupying a namespace is not the same as owning it: a re-frame app may
  legitimately hold `de-dupe.cache/whatever` in app-db, and a decoder
  that reads EVERY symbol in the namespace as a slot reference turns
  that value into nil, into somebody else's subtree, or into a thrown
  error (rf2-kjv05). So references are spelled, not merely namespaced.
  In VALUE position a token `de-dupe.cache/<name>` is

  - a REFERENCE to slot N, when `<name>` is exactly `cache-<digits>`;
  - an ESCAPED LITERAL of `de-dupe.cache/<rest>`, when `<name>` is
    `!<rest>`;
  - ordinary payload data otherwise.

  The encoder escapes — one leading `!` — every payload token that would
  otherwise read as one of the first two forms, so its own output is
  unambiguous by construction. Escaping is reversible under repetition
  (a payload `de-dupe.cache/!cache-1` rides out as `…/!!cache-1`), which
  is what makes the round-trip exact rather than merely usually-exact.

  A SYMBOL, a KEYWORD and a same-spelled STRING are all escaped, because
  JSON erases the distinctions between them: Cheshire renders the symbol
  `de-dupe.cache/cache-1`, the keyword `:de-dupe.cache/cache-1` and the
  string \"de-dupe.cache/cache-1\" as ONE JSON string. Those three are
  exactly the scalars a JSON encoder flattens to a bare namespaced
  string, which is why `token-name` tests `ident?` and `string?` and
  nothing else. Escaping only some of them leaves the JSON projection
  inexact even where the Clojure round-trip holds — that was the keyword
  gap the first cut at rf2-kjv05 left behind, and it corrupted VALUE and
  map-KEY positions alike, since a keyword is the ordinary spelling of
  both in re-frame app-db data. Cache KEYS are unaffected — they are the
  allocator's own `cache-N` symbols and never carry an escape.

  What the grammar does NOT restore is the TYPE across JSON: a payload
  symbol, keyword and string in this namespace all arrive at a Node
  consumer as the same string, exactly as they would anywhere else in
  the payload. The guarantee is that the JSON PROJECTION survives the
  codec unchanged — dedup then expand is the identity on it — not that
  JSON grew a type system.

  A missing reference stays a LOUD failure: `cache-<digits>` is a
  reference whether or not the table holds that slot, so a truncated or
  hand-mangled table is rejected by the Node decoder rather than
  silently re-read as data.

  ## Idempotence on no-dedup-opportunity

  A payload with no repeated subtrees deduplicates to a one-entry cache
  (the wire shape is very slightly larger than the input). The encoder
  skips wrapping in two cases so no-repeat payloads stay raw:

  - Empty / scalar values short-circuit BEFORE `de-dupe-eq` via
    `empty-payload?`.
  - A NON-EMPTY collection with no repeated subtrees runs `de-dupe-eq`
    but produces a one-entry root-only cache
    (`{de-dupe.cache/cache-0 <original>}`, no `cache-N` substitutions);
    `no-substitutions?` detects that and returns the original value
    unchanged. Without this, ordinary no-repeat payloads would grow by
    the wrapper + `cache-0` slot on every response.

  ## What does NOT live here — the wrapper-aware test helper

  `expand` takes a raw cache map, which is exactly what an agent-side
  Clojure consumer holds after reading `:rf.mcp/dedup-table` off the
  wire. The wrapper-aware, idempotent-on-already-expanded convenience
  (`dedup-expand`) is a TEST affordance — neither MCP server inverts the
  transform at runtime — so each consumer keeps its own in its test
  corpus, signalled \"test-only\" by location:

  - re-frame2-pair-mcp keeps it in `re-frame2-pair-mcp.test-utils`
    (its CLJS test corpus's shared test-helper ns).
  - story-mcp keeps it in `re-frame.story-mcp.test-support`
    (its JVM test corpus's shared test-helper ns)."
  (:require [clojure.string :as str]
            [re-frame.mcp-base.vocab :as rf.mcp-base.vocab])
  #?(:clj (:import [java.util HashMap])))

;; ---------------------------------------------------------------------------
;; VENDORED CODEC — day8/de-dupe v0.3.0, absorbed under rf2-2ii52
;; ---------------------------------------------------------------------------
;;
;; The MIT License (MIT)
;;
;; Copyright (c) 2015-2026 Michael Thompson
;;
;; Permission is hereby granted, free of charge, to any person obtaining a copy
;; of this software and associated documentation files (the "Software"), to deal
;; in the Software without restriction, including without limitation the rights
;; to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
;; copies of the Software, and to permit persons to whom the Software is
;; furnished to do so, subject to the following conditions:
;;
;; The above copyright notice and this permission notice shall be included in
;; all copies or substantial portions of the Software.
;;
;; THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
;; IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
;; FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
;; AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
;; LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
;; OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
;; THE SOFTWARE.
;;
;; CHANGES MADE WHILE ABSORBING (the wire shape is unchanged by all of them):
;;
;;   1. The compression-id counter was a NAMESPACE-GLOBAL atom that every
;;      `create-cache-internal` call `reset!` to 1. That is a real defect, not
;;      a stylistic one: two concurrent JVM encodes can interleave one call's
;;      reset with another's allocation and hand out the SAME `cache-N` id
;;      twice inside one cache, corrupting the payload. It is now a call-local
;;      `volatile!` threaded through the walk, so concurrent encodes cannot
;;      see each other at all. The memo maps inside the encoder and `expand`
;;      moved from `atom` to `volatile!` for the same reason: they were always
;;      call-local, and saying so in the type removes the question.
;;   2. The identity-based `de-dupe` entry point was dropped. Nothing on the
;;      wire boundary is identity-shared (see the namespace docstring), so the
;;      `hash-fn` / `equivalent?` parameters that existed ONLY to switch
;;      between the two variants were collapsed to `hash` / `=`.
;;   3. Unreached surface was dropped rather than carried: `map-from-seq`,
;;      `contains-compressed-elements?`, `partition-decompressed-elements`,
;;      `contains-only-keys?`, and the dead `::cache`-metadata branch of
;;      `is-cache-element?` (nothing ever attaches that key).
;;   4. Everything not on the public surface — the walkers, the bucket store,
;;      the counting pass — is now `^:private`. The public codec is
;;      `cache-element-ns`, `make-cache-element`, `de-dupe-eq` and `expand`.
;;   5. Upstream classified a value as a reference on its NAMESPACE alone, so
;;      an ordinary payload symbol in `de-dupe.cache` was aliased to a cache
;;      slot and `expand` stopped being the exact inverse it promised
;;      (rf2-kjv05). References are now spelled `cache-<digits>` and colliding
;;      payload tokens are escaped by the encoder — for symbols, KEYWORDS and
;;      strings alike, which is the set JSON flattens onto one spelling; see
;;      the namespace docstring's §Reference grammar. This IS a wire change,
;;      taken as one pre-alpha cut across the codec, the spec and the Node
;;      decoder; the cache-element namespace, the `cache-N` key spelling and
;;      the `:rf.mcp/dedup-table` envelope are untouched.
;;
;; ---------------------------------------------------------------------------

(def cache-element-ns
  "Namespace of every cache-element symbol on the wire:
  `de-dupe.cache/cache-N`. A WIRE constant — see the namespace
  docstring's §Wire shape for why the absorb kept the name."
  "de-dupe.cache")

(defn make-cache-element
  "The cache-element symbol naming cache slot `id` —
  `de-dupe.cache/cache-<id>`. Slot 0 is always the root."
  [id]
  (symbol cache-element-ns (str "cache-" id)))

;; ---- Reference grammar (rf2-kjv05) -----------------------------------------
;;
;; See the namespace docstring's §Reference grammar for the WHY. In value
;; position `de-dupe.cache/cache-<digits>` is a reference, `de-dupe.cache/!…`
;; is an escaped literal, and everything else in the namespace is data. A
;; TOKEN, throughout this section, is a symbol, a keyword or a string spelled
;; that way — the three scalars JSON flattens onto one string.

(def ^:private cache-element-prefix
  "`de-dupe.cache/` — the same wire namespace as `cache-element-ns`, in
  the flat prefix form JSON leaves behind once it has erased the
  symbol/string distinction. Derived from `cache-element-ns` so the two
  spellings cannot drift apart."
  (str cache-element-ns "/"))

(def ^:private escape-marker
  "The single character prefixed to a payload token that would otherwise
  read as a reference (or as an escape)."
  "!")

(defn ^:private slot-name?
  "True when `nm` is a name the id allocator emits — `cache-<digits>`."
  [nm]
  (boolean (re-matches #"cache-\d+" nm)))

(defn ^:private escaped-name?
  "True when `nm` carries the escape marker."
  [nm]
  (str/starts-with? nm escape-marker))

(defn ^:private token-name
  "For a SYMBOL, KEYWORD or STRING spelled `de-dupe.cache/<name>`, that
  `<name>`; nil for every other value. All three count, because JSON
  collapses them onto one spelling — see the namespace docstring's
  §Reference grammar. `ident?` rather than `symbol?` is the whole of the
  keyword arm: the three types below are exactly the scalars a JSON
  encoder renders as a bare namespaced string."
  [x]
  (cond
    (ident? x)  (when (= cache-element-ns (namespace x)) (name x))
    (string? x) (when (str/starts-with? x cache-element-prefix)
                  (subs x (count cache-element-prefix)))
    :else       nil))

(defn ^:private retoken
  "A token of the same TYPE as `x` (symbol in, symbol out; keyword in,
  keyword out; string in, string out) spelled `de-dupe.cache/<nm>`. Type
  preservation is what keeps the Clojure round-trip exact while the JSON
  one flattens."
  [x nm]
  (cond
    (symbol? x)  (symbol cache-element-ns nm)
    (keyword? x) (keyword cache-element-ns nm)
    :else        (str cache-element-prefix nm)))

(defn ^:private cache-element?
  "True when `x` is a cache-element symbol — a reference to another slot
  in the same cache — rather than an ordinary value. The allocator's
  exact `cache-<digits>` name is required, not merely the namespace: an
  ordinary payload symbol such as `de-dupe.cache/not-a-ref` shares the
  namespace and is DATA. Payload tokens that WOULD spell a reference are
  escaped on the way in (`escape-token`), so the encoder never emits an
  ambiguous one.

  SYMBOL, deliberately, where `escape-token` takes any ident or string:
  `make-cache-element` only ever emits symbols, so on the Clojure side a
  keyword or string is payload however it is spelled. It is escaped
  anyway because JSON will erase it onto the symbol's own spelling
  downstream — the escape is the JSON projection's protection, not this
  predicate's."
  [x]
  (and (symbol? x)
       (= cache-element-ns (namespace x))
       (slot-name? (name x))))

(defn ^:private escape-token
  "One `!` in front of any payload token that would otherwise read as a
  reference or as an escape; every other value is returned unchanged.
  Reversible under repetition, so a payload token already spelled
  `de-dupe.cache/!cache-1` rides out as `…/!!cache-1` and comes back."
  [x]
  (if-let [nm (token-name x)]
    (if (or (slot-name? nm) (escaped-name? nm))
      (retoken x (str escape-marker nm))
      x)
    x))

(defn ^:private unescape-token
  "The exact inverse of `escape-token` — strip ONE escape marker."
  [x]
  (if-let [nm (token-name x)]
    (if (escaped-name? nm)
      (retoken x (subs nm (count escape-marker)))
      x)
    x))

;; ---- Mutable hash-bucket store (platform-specific) -------------------------
;;
;; The algorithm is platform-agnostic; the only platform-specific bit is the
;; mutable hash→bucket store used during compression. On CLJS that is a
;; `js/Map`, on the JVM a `java.util.HashMap`. The shape of the method calls
;; (`.get`, `.set` / `.put`) is the only thing that varies, and it is isolated
;; to these three helpers.

(defn ^:private new-bucket-store
  "An empty mutable hash→bucket store."
  []
  #?(:cljs (js/Map.)
     :clj  (HashMap.)))

(defn ^:private bucket-get
  "The bucket associated with `h` in `store`, or nil."
  [store h]
  #?(:cljs (.get store h)
     :clj  (.get ^java.util.Map store h)))

(defn ^:private bucket-set!
  "Associate `h` → `bucket` in the mutable `store`. Returns the store."
  [store h bucket]
  #?(:cljs (.set store h bucket)
     :clj  (.put ^java.util.Map store h bucket))
  store)

;; ---- Structure-preserving walk ---------------------------------------------

(defn ^:private map-entry?*
  [form]
  #?(:cljs (satisfies? IMapEntry form)
     :clj  (instance? clojure.lang.IMapEntry form)))

(defn ^:private record?*
  [form]
  #?(:cljs (satisfies? IRecord form)
     :clj  (instance? clojure.lang.IRecord form)))

(defn ^:private side-walk
  "Like `clojure.walk/walk`, but `outer` receives BOTH the original form
  and the rebuilt one — which is what lets the encoder read the
  `:cache-id` metadata `inner` attached before the rebuild dropped it.
  Recognises every Clojure collection; consumes seqs as with `doall`."
  [inner outer form]
  (cond
    (list? form)       (outer form (apply list (doall (map inner form))))
    (map-entry?* form) (outer form (vec (doall (map inner form))))
    (seq? form)        (outer form (doall (map inner form)))
    (record?* form)    (outer form (reduce (fn [r x] (conj r (inner x))) form form))
    (coll? form)       (outer form (into (empty form) (doall (map inner form))))
    :else              (outer form form)))

(defn ^:private side-prewalk
  [inner outer form]
  (side-walk (partial side-prewalk inner outer) outer (inner form)))

(defn ^:private cacheable?
  "True for the forms worth pooling. Scalars are cheaper inline than as a
  reference, and a 2-element vector is excluded because that is how a map
  entry rebuilds — pooling those would rewrite map structure."
  [element]
  (and (not (or (and (vector? element)
                     (= 2 (count element)))
                (number? element)
                (keyword? element)
                (string? element)))
       (or (list? element)
           (seq? element)
           (coll? element))))

;; ---- Pass 1: count candidates ----------------------------------------------
;;
;; Only subtrees seen MORE THAN ONCE are worth a cache slot, so the encoder
;; counts before it substitutes.

(defn ^:private count-cacheable-element!
  [store element]
  (let [h      (hash element)
        bucket (or (bucket-get store h) [])]
    (if-let [entry (some (fn [entry]
                           (when (= (:element entry) element) entry))
                         bucket)]
      (bucket-set! store h (mapv (fn [bucket-entry]
                                   (if (identical? bucket-entry entry)
                                     (update bucket-entry :count inc)
                                     bucket-entry))
                                 bucket))
      (bucket-set! store h (conj bucket {:element element :count 1})))))

(defn ^:private count-cacheable-elements
  [form]
  (let [store (new-bucket-store)]
    (side-prewalk (fn [element]
                    (when (and (not (identical? element form))
                               (cacheable? element))
                      (count-cacheable-element! store element))
                    element)
                  (fn [_org-element element] element)
                  form)
    store))

(defn ^:private repeated-cacheable?
  [counts element]
  (let [h      (hash element)
        bucket (or (bucket-get counts h) [])]
    (boolean
      (some (fn [{candidate :element :keys [count]}]
              (and (< 1 count)
                   (= element candidate)))
            bucket))))

;; ---- Pass 2: substitute -----------------------------------------------------

(defn ^:private next-cache-id!
  "Allocate the next cache-element id from the CALL-LOCAL `counter`."
  [counter]
  (let [cache-id (make-cache-element @counter)]
    (vswap! counter inc)
    cache-id))

(defn ^:private check-in-cache
  "First sighting of `element`: record it and return the element tagged
  with its freshly allocated `:cache-id` (the rebuild reads that tag off
  the original). Later sightings: return the cache-element symbol, which
  IS the substitution."
  [element store counter]
  (let [h      (hash element)
        bucket (or (bucket-get store h) [])]
    (if-let [cache-id (some (fn [[cached-element cache-id]]
                              (when (= cached-element element) cache-id))
                            bucket)]
      cache-id
      (let [cache-id (next-cache-id! counter)]
        (bucket-set! store h (conj bucket [element cache-id]))
        (with-meta element {:cache-id cache-id})))))

(defn ^:private escape-literals
  "`escape-token` applied throughout `form`.

  Runs BEFORE the counting pass, not folded into it: the counter hashes
  subtrees top-down, so escaping later would leave the two passes
  hashing different values for any subtree holding a colliding literal,
  and the pooling would quietly stop firing for exactly those subtrees."
  [form]
  (side-prewalk escape-token (fn [_org-element element] element) form))

(defn de-dupe-eq
  "Compress `form` into a flat cache map keyed by `de-dupe.cache/cache-N`
  symbols, pooling subtrees by EQUALITY. Slot `cache-0` always holds the
  root; every further slot is a subtree that occurred more than once and
  has been replaced, at each occurrence, by its cache-element symbol.
  `expand` is the exact inverse.

  Payload tokens that would themselves read as references are escaped
  first (`escape-literals`), so the cache the encoder emits is
  unambiguous by construction — see the namespace docstring's §Reference
  grammar.

  A form with no repeated subtrees yields a one-entry root-only cache —
  see `no-substitutions?`, which is how `dedup-value` avoids growing a
  payload it cannot shrink."
  [form]
  (let [form             (escape-literals form)
        counter          (volatile! 1)
        compressed-cache (volatile! {})
        candidate-counts (count-cacheable-elements form)
        values-store     (new-bucket-store)
        process-element  (fn [element]
                           ;; The root itself is slot 0, never a substitution;
                           ;; map entries and scalars are not cacheable; and a
                           ;; subtree seen once is cheaper inline.
                           (if (or (identical? element form)
                                   (not (cacheable? element))
                                   (not (repeated-cacheable? candidate-counts element)))
                             element
                             (check-in-cache element values-store counter)))
        outer-fn         (fn [org-element element]
                           (if (and (cacheable? org-element)
                                    (not (identical? org-element form)))
                             (if-let [id (:cache-id (meta org-element))]
                               (do (vswap! compressed-cache assoc id element)
                                   id)
                               element)
                             element))
        cache-0          (side-prewalk process-element outer-fn form)]
    (vswap! compressed-cache assoc (make-cache-element 0) cache-0)
    @compressed-cache))

;; ---- The inverse ------------------------------------------------------------

(defn ^:private decompress-cache
  "Every slot in `cache`, expanded. Memoised per call so a subtree shared
  by many slots is rebuilt once and stays shared in memory."
  [cache]
  (let [expanded (volatile! {})]
    (letfn [(expand-value [value]
              (cond
                (cache-element? value) (expand-entry value)
                (list? value)          (apply list (map expand-value value))
                (map-entry?* value)    (vec (map expand-value value))
                (seq? value)           (doall (map expand-value value))
                (record?* value)       (reduce (fn [r x] (conj r (expand-value x))) value value)
                (coll? value)          (into (empty value) (map expand-value value))
                ;; Scalars — and the one scalar shape that is not simply
                ;; itself: an escaped literal, which sheds one marker
                ;; here (§Reference grammar).
                :else                  (unescape-token value)))
            (expand-entry [cache-id]
              (if (contains? @expanded cache-id)
                (get @expanded cache-id)
                (let [expanded-value (expand-value (get cache cache-id))]
                  (vswap! expanded assoc cache-id expanded-value)
                  expanded-value)))]
      (into {} (for [cache-id (keys cache)]
                 [cache-id (expand-entry cache-id)])))))

(defn expand
  "Reconstruct the original structure from a `de-dupe-eq` cache map.
  This is what an agent-side Clojure consumer calls on the value it
  reads out of the `:rf.mcp/dedup-table` slot."
  [cache]
  (get (decompress-cache cache) (make-cache-element 0)))

;; ---------------------------------------------------------------------------
;; END VENDORED CODEC. Below is the wire-boundary policy — re-frame2's own.
;; ---------------------------------------------------------------------------

(defn empty-payload?
  "True for values where dedup yields no win — nil, empty collections,
  scalars. Skipping the wrap avoids the trivial cache-of-one shape
  bloating the wire for empty / single-record responses."
  [v]
  (or (nil? v)
      (and (coll? v) (empty? v))
      (not (coll? v))))

(def ^:private root-cache-key
  "The slot `de-dupe-eq` always emits for the whole structure
  (`de-dupe.cache/cache-0`). Every substituted subtree adds a further
  `cache-N` entry, so a cache carrying ONLY this key made no
  substitutions."
  (make-cache-element 0))

(defn no-substitutions?
  "True when a `de-dupe-eq` cache made NO substitutions — it holds
  exactly the one root entry (`cache-0`) and nothing else, because the
  payload had no repeated subtrees. A non-empty collection with no
  repeats deduplicates to exactly this one-entry root-only cache, whose
  wrapped wire shape is strictly LARGER than the raw input; `dedup-value`
  detects it and returns the original value so the documented
  no-repeat-payloads-stay-raw contract holds for ordinary (not just
  empty / scalar) payloads too.

  Checked on the cache SHAPE (single entry keyed by `cache-0`), not by
  re-walking the value: any repeated subtree would have added a second
  `cache-N` entry, so `(= 1 (count cache))` already implies root-only —
  the explicit key check also verifies the root slot the codec emits."
  [cache]
  (and (map? cache)
       (= 1 (count cache))
       (contains? cache root-cache-key)))

(defn dedup-value
  "Apply structural dedup to `v` and wrap the result in the cross-MCP
  marker (`rf.mcp-base.vocab/dedup-table-key`). Returns `v` unchanged when
  `enabled?` is false, when `v` is empty / scalar (no dedup opportunity),
  or when the `de-dupe-eq` cache made no substitutions (a non-empty
  collection with no repeated subtrees — `no-substitutions?`)."
  [v enabled?]
  (if (or (not enabled?) (empty-payload? v))
    v
    (let [cache (de-dupe-eq v)]
      (if (no-substitutions? cache)
        v
        {rf.mcp-base.vocab/dedup-table-key cache}))))
