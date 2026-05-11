(ns re-frame.story.identity
  "Snapshot-identity content-hashing. Per IMPL-SPEC §5.6 + spec/007
  §Variant snapshot identity.

  Every variant has a stable **snapshot identity** — a content hash
  over the canonicalised `(variant × resolved-args × decorators ×
  loaders × substrate × modes)` tuple. Visual-regression services
  key against `[variant-id content-hash]` — when the body changes,
  the hash changes; when it doesn't, the hash is stable across hosts
  and runs.

  ## What's in the hash

  Per IMPL-SPEC §5.6 the hash includes:

  - Variant id
  - `:events`, `:play`, `:loaders` (in declared order; canonicalised)
  - Effective `:args` (post-merge with story + active modes)
  - Decorator id sequence + their ref-args
  - Tag set
  - Parent story `:component` id
  - Parent story `:decorators`
  - Active substrate (when computing per-substrate identity)
  - Active modes (when computing per-mode identity)

  ## Hash function

  IMPL-SPEC §5.6 specifies `sha-256` of a transit-serialised canonical
  form. Stage 3 implements a **portable** hash function: a stable
  string serialisation (deterministic key order; sets/vectors written
  with stable order) hashed with `hash` (JVM `clojure.lang.Util/hasheq`,
  CLJS `cljs.core/hash`).

  Trade-off vs sha-256: `hash` is 32-bit and faster, but only ~4-billion
  states. For visual-regression keying that's enough provided the
  caller dedupes by `[variant-id content-hash]` (the variant id is
  unique; the hash is per-variant per-cell). The sha-256 path is left
  as a Stage 6+ extension when an external service needs cryptographic
  collision-resistance.

  The canonical-form keyword `:rf/snapshot-canonical-v1` is included as
  the first slot of the hashed structure, so future canonical-form
  revisions can introduce `:rf/snapshot-canonical-v2` without breaking
  v1 baselines."
  (:require [re-frame.story.args      :as args]
            [re-frame.story.registrar :as registrar]))

;; ---- canonicalisation ----------------------------------------------------

(defprotocol Canonicalise
  "Render a value into a canonical form: stable key order in maps,
  stable element order in sets, terminal types (strings, keywords,
  numbers, booleans, nil) unchanged. Returns a value that round-trips
  through `pr-str` deterministically across hosts."
  (-canon [x]))

(extend-protocol Canonicalise
  nil
  (-canon [_] nil)

  #?(:clj  java.lang.Boolean :cljs boolean)
  (-canon [x] x)

  #?(:clj  java.lang.Number  :cljs number)
  (-canon [x] x)

  #?(:clj  java.lang.String  :cljs string)
  (-canon [x] x)

  #?(:clj  clojure.lang.Keyword :cljs Keyword)
  (-canon [x] x)

  #?(:clj  clojure.lang.Symbol  :cljs Symbol)
  (-canon [x] x)

  #?(:clj  clojure.lang.IPersistentMap  :cljs IMap)
  (-canon [x]
    ;; Map canon: sort by the canonicalised key (via pr-str of the
    ;; canon-key). The serialisation is symmetric — both JVM and CLJS
    ;; sort the same way because `pr-str` over canonical scalars is
    ;; identical across hosts.
    (let [entries (->> x
                       (map (fn [[k v]] [(-canon k) (-canon v)]))
                       (sort-by (fn [[k _]] (pr-str k))))]
      (into [] (mapcat identity) entries)))

  #?(:clj  clojure.lang.IPersistentVector :cljs PersistentVector)
  (-canon [x] (mapv -canon x))

  #?(:clj  clojure.lang.IPersistentList :cljs List)
  (-canon [x] (mapv -canon x))

  #?(:clj  clojure.lang.IPersistentSet  :cljs PersistentHashSet)
  (-canon [x]
    ;; Stable set order: sort canonicalised elements by their pr-str.
    (vec (sort-by pr-str (map -canon x))))

  #?(:clj  Object             :cljs default)
  (-canon [x]
    ;; Fallback for ISeq / LazySeq / etc — realise into a vector with
    ;; canonical recursion. `pr-str` over the result is deterministic.
    (cond
      (sequential? x) (mapv -canon x)
      (set? x)        (vec (sort-by pr-str (map -canon x)))
      (map? x)        (let [entries (->> x
                                         (map (fn [[k v]] [(-canon k) (-canon v)]))
                                         (sort-by (fn [[k _]] (pr-str k))))]
                        (into [] (mapcat identity) entries))
      :else           x)))

(defn canonical-form
  "Return a canonical-form representation of `x`. Maps are vectors of
  `[k v k v ...]` sorted by key; sets are vectors sorted by element;
  vectors recurse; scalars unchanged. The resulting tree round-trips
  through `pr-str` deterministically across hosts."
  [x]
  (-canon x))

;; ---- per-host hash --------------------------------------------------------

(defn content-hash
  "Stable string hash of `x` (post-canonicalisation). Returns a
  lowercase hex string of fixed width (8 chars — 32-bit `hash`).

  Per IMPL-SPEC §5.6 the canonical form is keyed by
  `:rf/snapshot-canonical-v1` so future canonical-form revisions can
  bump the version without breaking baselines."
  [x]
  (let [canon (canonical-form [:rf/snapshot-canonical-v1 x])
        s     (pr-str canon)
        h     (bit-and 0xffffffff (hash s))
        hex   #?(:clj  (format "%08x" h)
                 :cljs (let [s (.toString h 16)
                             pad (- 8 (.-length s))]
                         (if (pos? pad)
                           (str (apply str (repeat pad "0")) s)
                           s)))]
    hex))

;; ---- snapshot tuple -------------------------------------------------------

(defn- variant-body-slice
  "Return the slice of the variant body that contributes to the snapshot
  identity. Excludes runtime-environmental keys (`:source` coords) and
  Stage 4+ slots that don't yet exist (kept for forward compatibility)."
  [variant-id]
  (let [body (registrar/handler-meta :variant variant-id)]
    (when body
      (select-keys body
                   [:events :play :loaders :loaders-complete-when
                    :tags :args->events :platforms :substrates]))))

(defn- story-body-slice
  "Story-level slice that the variant inherits for identity purposes.
  Per IMPL-SPEC §5.6 the parent story's `:component` id and
  `:decorators` are part of the variant's identity."
  [variant-id]
  (let [story-id (args/parent-story-id variant-id)
        body     (when story-id (registrar/handler-meta :story story-id))]
    (when body
      (select-keys body [:component :decorators :tags]))))

(defn snapshot-tuple
  "Build the canonical tuple that feeds `content-hash` for a variant.
  Returns a map.

  Arguments:
  - `variant-id` — keyword variant id.
  - `opts` (optional) — `{:active-modes [...] :cell-overrides {...}
                          :substrate <keyword>}`.

  The tuple captures everything the visual-regression service treats
  as identity-determining. A change to ANY of these fields produces a
  fresh hash; otherwise the hash is stable across runs."
  ([variant-id] (snapshot-tuple variant-id nil))
  ([variant-id {:keys [active-modes cell-overrides substrate] :as _opts}]
   (let [variant      (variant-body-slice variant-id)
         story        (story-body-slice variant-id)
         effective    (args/resolve-args variant-id
                                         {:active-modes   active-modes
                                          :cell-overrides cell-overrides})]
     {:rf/snapshot-canonical :rf/snapshot-canonical-v1
      :variant-id            variant-id
      :variant               variant
      :story                 story
      :effective-args        effective
      :active-modes          (vec (or active-modes []))
      :substrate             substrate})))

(defn snapshot-identity
  "Public entry point per IMPL-SPEC §3.2 — return the snapshot-identity
  record for `(variant × active-modes × cell-overrides × substrate)`.

  Returns:

      {:variant-id   <variant-id>
       :active-modes [<mode-id> ...]
       :substrate    <substrate-id>
       :content-hash \"<8-char hex>\"}

  Stable across hosts (JVM and CLJS produce the same hex hash for the
  same canonical inputs). Visual-regression services key against
  `[variant-id content-hash]`."
  ([variant-id] (snapshot-identity variant-id nil))
  ([variant-id {:keys [active-modes substrate] :as opts}]
   (let [tuple (snapshot-tuple variant-id opts)
         hex   (content-hash tuple)]
     {:variant-id    variant-id
      :active-modes  (vec (or active-modes []))
      :substrate     substrate
      :content-hash  hex})))
