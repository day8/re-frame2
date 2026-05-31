(ns re-frame.story.fingerprint
  "The single canonical projection + fingerprinting primitive for Story.

  Per tools/story/spec/017-Testing-Story.md §Canonicalization this is the
  ONE implementation that determinism, semantic-diff, snapshot-identity,
  `:plan-hash` / `:run-hash`, future golden-slice comparison, and the
  inline-plan-to-registered-variant metamorphic relation all consume.

  It deliberately does NOT live in `re-frame.story.canonical` (that ns
  installs the canonical *vocabulary* — tags + the lifecycle machine).
  This ns is the fingerprinting path, and it folds the former
  `re-frame.story.identity` `canonical-form` / `content-hash` /
  `snapshot-tuple` hashing into one place so there are no near-duplicate
  canonicalisers to drift apart. `re-frame.story.identity` now delegates
  its projection + hash to this ns (see that ns for the snapshot-identity
  migration path).

  ## The public surface

  - `canonicalize` — the ONE public helper. Given any Story value (a
    run-result, an epoch slice, a normalized plan, or a snapshot tuple)
    it returns the host-portable canonical projection: volatile fields
    stripped, `:rf.story/*` accumulator keys dropped from app-db, total
    per-slot ordering imposed, and key spellings reconciled.
  - `content-hash` — stable 8-char hex hash of an exact value's ordered
    canonical form (NO volatile strip). This is the low primitive the
    shipping snapshot-identity tuple hashes; preserving the no-strip
    semantics keeps existing visual-regression baselines byte-identical
    across the fold.
  - `canonical-hash` — stable 8-char hex hash of the `canonicalize`d
    projection (volatile strip applied). This is the determinism /
    semantic-diff / run-equivalence hash.
  - `plan-hash` — `canonical-hash` over the enumerated plan-input slice.
  - `run-hash` — `canonical-hash` over the canonical epoch/run slice.

  Every downstream call site MUST route through these; it MUST NOT
  reimplement projection or hashing locally.

  ## What `canonicalize` strips / normalizes

  Per spec §Canonicalization the primitive MUST:

  - strip `:rf.story/*` accumulator keys from any app-db it projects;
  - project away the volatile record fields
    `{:elapsed-ms :dispatch-id :source :source-coord :runner :variant/id
      :plan-hash :run-hash}` (and the shipping `:variant-id` spelling,
    reconciled to `:variant/id` first; the authoritative set
    `volatile-fields` also carries the per-run epoch / trace stamps —
    `:epoch-id :trace-id :committed-at :schema-digest`);
  - impose a total per-slot ordering — effects keep emission order,
    sub-runs are topo-then-id, epochs are dispatch order, trace events
    keep emission order;
  - enumerate the `:plan-hash` input fields;
  - compute `:run-hash` over the canonical epoch slice.

  ## Hash function

  The hash is the same portable hash the former identity ns used: a
  stable string serialisation (deterministic key order; sets/vectors
  written in stable order; each collection wrapped under a reserved
  structural type-tag so the four collection kinds are distinguishable —
  rf2-lvrqa; functions folded to the `opaque-fn` sentinel so a hashed slot
  carrying a fn is deterministic across processes — rf2-4gwja) hashed with
  `hash` (JVM `clojure.lang.Util/hasheq`, CLJS `cljs.core/hash`), rendered
  as an 8-char lowercase hex string. It is 32-bit and per-artefact, not
  cryptographic; callers that need collision-resistance against an
  external service dedupe by `[id content-hash]`. The sha-256 path is a
  later extension.

  The canonical-form keyword `canonical-version`
  (`:rf/snapshot-canonical-v2`) is the first slot of the hashed structure,
  so a canonical-form revision bumps the version and old baselines are
  detectably stale rather than silently mis-compared.")

;; ===========================================================================
;; CANONICAL VERSION TAG
;; ===========================================================================

(def canonical-version
  "The canonical-form version tag — the first slot of every hashed
  structure, so a canonical-form revision bumps it and old baselines are
  detectably stale rather than silently mis-compared.

  Bumped `:rf/snapshot-canonical-v1` → `:rf/snapshot-canonical-v2`
  (rf2-lvrqa) for the soundness fix that type-tags the canonical form: maps
  / sets / vectors / seqs are now wrapped under reserved structural tags
  (`map-tag` / `set-tag` / `vec-tag` / `seq-tag`) plus functions fold to the
  `opaque-fn` sentinel (rf2-4gwja). Both change the byte shape of the
  canonical form, so EVERY hash this primitive emits — `content-hash`
  (snapshot identity), `canonical-hash`, `plan-hash`, `run-hash` — changes
  value. Pre-alpha: re-stamping baselines is cheap, and there are NO
  in-repo stored hash fixtures (every consumer asserts hash STABILITY /
  SENSITIVITY relationally, never a pinned hex literal — verified
  rf2-lvrqa), so the bump invalidates only EXTERNAL visual-regression
  baselines, which re-stamp on their next capture. The v1 → v2 bump is
  exactly the signal that drives that external re-stamp."
  :rf/snapshot-canonical-v2)

;; ===========================================================================
;; VOLATILE FIELDS
;; ===========================================================================

(def volatile-fields
  "The volatile record fields `canonicalize` projects away before
  hashing — per spec §Canonicalization. Two equivalent runs that differ
  only in these fields MUST hash equal.

  `:variant/id`, `:plan-hash`, and `:run-hash` are listed because they
  are identity / derived-hash slots, not behaviour: a canonical projection
  must not change just because the plan id or a derived hash string rode
  along inside the slice. (`:run-hash` is the spec volatile set's
  symmetric companion to `:plan-hash` — a run-result carries its own
  `:run-hash`, which must not feed a re-canonicalization of that result.
  The hashes still key the artifact at the call site; they simply do not
  feed the canonical value recursively.)

  ### Per-run stamp fields (rf2-5x1wt.8 — the determinism strip)

  `:epoch-id`, `:dispatch-id`, `:trace-id`, `:committed-at`, and
  `:schema-digest` are the per-RUN bookkeeping stamps the framework writes
  into a freshly-captured epoch tape and its projected evidence rows. The
  epoch counter, the dispatch-id counter, and the trace-event `:id`
  counter are PROCESS-GLOBAL monotonic atoms (never reset per frame), and
  `:committed-at` is wall-clock — so two semantically-equal runs replayed
  into FRESH frames (`re-frame.story.artifact/replay-run-artifact`, spec
  §Run artifact and replay) stamp DIFFERENT values for each. The `.7`
  worker deliberately left this strip to the determinism gate
  (rf2-5x1wt.8): a replay into a fresh frame stamps different epoch /
  dispatch / trace ids, and THIS strip — together with the structural
  `:id` / `:time` / `:frame` strip in `strip-run-stamps` below — is what
  normalizes them so two semantically-equal runs canonicalize `=` and hash
  equal.

  Each of these keys is reserved or framework-specific (an app-db value is
  vanishingly unlikely to key on `:epoch-id` / `:trace-id` /
  `:committed-at` / `:schema-digest`), so the blunt recursive strip in
  `project` is safe for them. The genuinely-common keys a trace event /
  epoch record also carries (`:id`, `:time`, `:frame`) are NOT in this set
  — stripping them globally would erase semantic app-db data; they are
  stripped STRUCTURALLY (only on their carrier maps) by `strip-run-stamps`.

  This strip applies on the `canonicalize` / `canonical-hash`
  (= determinism / diff / `:run-hash`) path ONLY — the strip-free
  `content-hash` (snapshot identity) is untouched, so snapshot-identity
  baselines stay byte-stable (§Snapshot-identity migration path)."
  #{:elapsed-ms :dispatch-id :source :source-coord :runner
    :variant/id :plan-hash :run-hash
    :epoch-id :trace-id :committed-at :schema-digest})

(def ^:private variant-id-spellings
  "The variant-id key spellings reconciled at this boundary. The shipping
  `re-frame.story.identity` snapshot tuple wrote `:variant-id`; the
  normalized plan + run-result (spec §Artifacts / §Run result) write
  `:variant/id`. `canonicalize` rewrites the legacy spelling to the
  normalized one so a snapshot tuple and a plan slice describing the same
  variant project identically."
  [:variant-id :variant/id])

;; ===========================================================================
;; PROJECTION — strip + reconcile, recursively
;; ===========================================================================

(defn- story-accumulator-key?
  "True for `:rf.story/*` accumulator keys stripped from any projected
  app-db. Namespaced keyword whose namespace is exactly `rf.story`."
  [k]
  (and (keyword? k) (= "rf.story" (namespace k))))

(defn- reconcile-variant-id
  "Reconcile the `:variant-id` (shipping snapshot) spelling to the
  normalized-plan `:variant/id` spelling on a map, preferring an existing
  `:variant/id` if both are present (the normalized plan is the source of
  truth). Idempotent."
  [m]
  (let [[legacy canonical] variant-id-spellings]
    (if (and (map? m) (contains? m legacy))
      (let [v (get m legacy)]
        (-> m
            (dissoc legacy)
            (cond-> (not (contains? m canonical)) (assoc canonical v))))
      m)))

(defn project
  "Recursively strip volatile + accumulator keys and reconcile key
  spellings across a Story value. This is the deterministic *content*
  projection that precedes ordering + serialisation; it is applied
  uniformly to maps, vectors, sets, and seqs.

  Rules, applied to every map encountered at any depth:

  - reconcile `:variant-id` → `:variant/id`;
  - drop every key in `volatile-fields`;
  - drop every `:rf.story/*` accumulator key.

  This single rule set is why a run-result, an epoch beat, and a snapshot
  tuple all canonicalise consistently — the projection does not need to
  know which shape it was handed."
  [x]
  (cond
    (map? x)
    (let [m (reconcile-variant-id x)]
      (persistent!
        (reduce-kv
          (fn [acc k v]
            (if (or (contains? volatile-fields k)
                    (story-accumulator-key? k))
              acc
              (assoc! acc k (project v))))
          (transient {})
          m)))

    (set? x)        (into #{} (map project) x)
    (vector? x)     (mapv project x)
    (sequential? x) (mapv project x)
    :else           x))

;; ===========================================================================
;; STRUCTURAL RUN-STAMP STRIP  (rf2-5x1wt.8 — the determinism layer)
;; ===========================================================================
;;
;; `:id`, `:time`, and `:frame` are per-RUN stamps on a trace event / epoch
;; record — but they are ALSO common semantic app-db keys (`{:user/id …
;; :id 42}`, a `:frame` reference, a `:time` value). The blunt recursive
;; `project` strip cannot touch them without erasing app data, so they are
;; stripped STRUCTURALLY: only when they ride their carrier map.
;;
;; - a TRACE EVENT carries `:operation` + `:op-type` (Spec 009 §Trace event
;;   shape); its per-run stamps are `:id` (process-global counter), `:time`
;;   (wall-clock), and a handful of volatile slots that ride its `:tags`
;;   sub-map — `:frame` (the fresh replay frame id), `:rf.trace/dispatch-id`
;;   / `:rf.trace/trace-id` (per-run id counters), and `:rf.event/elapsed-ms`
;;   (per-run timing). The SEMANTIC tags (`:rf.trace/event-id`, the event
;;   payload `:rf.event/v`, a changed `:rf.event/db`) are LEFT intact, so a
;;   real behavioural difference in the trace still perturbs the hash.
;; - an EPOCH RECORD carries `:epoch-id` + (`:outcome` | `:db-after` |
;;   `:trace-events`) (Spec-Schemas §`:rf/epoch-record`); its per-run frame
;;   stamp is `:frame` (a fresh replay allocates a new `:rf.test.replay/*`
;;   id). `:epoch-id` / `:committed-at` / `:schema-digest` are already
;;   stripped by the recursive `project` (reserved keys), so only `:frame`
;;   needs the structural treatment here.
;;
;; The strip is recursive so it reaches trace events nested inside an epoch
;; record's `:trace-events` and epoch records nested inside a run-result's
;; `:epoch-tape`. It runs BEFORE `project`, so a record's reserved stamps
;; are dropped by `project` and its common-key stamps by this pass.

(def ^:private volatile-trace-tag-keys
  "The per-run stamp keys that ride a trace event's `:tags` sub-map
  (Spec 009 §Trace event shape). A fresh-frame replay stamps a new
  `:frame` id and per-run id / timing counters here; the SEMANTIC tags
  (`:rf.trace/event-id`, `:rf.event/v` payload, `:rf.event/db` value) are
  NOT in this set, so a real behavioural difference still perturbs the
  canonical value.

  ## Per-run handler-timing stamps (the determinism strip)

  `:rf.event/elapsed-ms` (event run), `:rf.fx/elapsed-ms` (fx handler), and
  `:rf.cofx/elapsed-ms` (cofx handler) are the dev-only wall-clock durations
  the framework stamps onto a `:rf.event/run-end` / `:rf.fx/handled` /
  `:rf.cofx/run` trace event's `:tags` (Spec 009; emitted under
  `interop/debug-enabled?`, DCE'd in production). They are pure per-RUN
  timing jitter — two semantically-equal runs replayed into FRESH frames
  measure DIFFERENT handler durations (e.g. 4ms vs 0ms under JIT / scheduling
  noise), so they MUST be stripped or the determinism gate
  (`re-frame.story.determinism/assert-deterministic`) false-drifts to
  `:non-deterministic` whenever a replayed program runs a TIMED fx / cofx.
  `:rf.event/elapsed-ms` was already stripped; `:rf.fx/elapsed-ms` and
  `:rf.cofx/elapsed-ms` are its symmetric companions — all three are
  handler wall-clock, none is behaviour."
  #{:frame :rf.trace/dispatch-id :rf.trace/trace-id
    :rf.event/elapsed-ms :rf.fx/elapsed-ms :rf.cofx/elapsed-ms})

(defn- trace-event?
  "True iff `m` is a trace event (Spec 009 §Trace event shape): a map
  carrying both `:operation` and `:op-type`. Pure data → data."
  [m]
  (and (map? m) (contains? m :operation) (contains? m :op-type)))

(defn- strip-trace-tags
  "Drop the per-run stamp keys (`volatile-trace-tag-keys`) from a trace
  event's `:tags` sub-map, leaving the semantic tags. No-op when `:tags`
  is absent. Pure data → data."
  [trace-event]
  (if (map? (:tags trace-event))
    (update trace-event :tags #(apply dissoc % volatile-trace-tag-keys))
    trace-event))

(defn- epoch-record?
  "True iff `m` is an `:rf/epoch-record` (Spec-Schemas §`:rf/epoch-record`):
  a map carrying `:epoch-id` plus at least one of the load-bearing record
  slots (`:outcome` / `:db-after` / `:trace-events`). The extra slot guards
  against a bare evidence row that merely back-references an `:epoch-id`
  (those carry no `:frame`, so the guard only affects whether `:frame` is
  read as a per-run stamp). Pure data → data."
  [m]
  (and (map? m)
       (contains? m :epoch-id)
       (or (contains? m :outcome)
           (contains? m :db-after)
           (contains? m :trace-events))))

(defn strip-run-stamps
  "Strip the per-run stamps that ride a trace event (`:id`, `:time`) or an
  epoch record (`:frame`) — the common-key stamps `project` cannot strip
  globally without erasing app-db data (rf2-5x1wt.8). Recursive across
  maps, vectors, sets, and seqs, so it reaches trace events nested in an
  epoch record's `:trace-events` and epoch records nested in a run-result's
  `:epoch-tape`. Pure data → data; idempotent.

  This is the structural companion to the reserved-key recursive strip in
  `project`: between the two, two semantically-equal runs replayed into
  fresh frames lose every per-run stamp and canonicalize `=`."
  [x]
  (cond
    (map? x)
    (let [m (cond-> x
              (trace-event? x) (-> (dissoc :id :time) strip-trace-tags)
              (epoch-record? x) (dissoc :frame))]
      (persistent!
        (reduce-kv (fn [acc k v] (assoc! acc k (strip-run-stamps v)))
                   (transient {})
                   m)))

    (set? x)        (into #{} (map strip-run-stamps) x)
    (vector? x)     (mapv strip-run-stamps x)
    (sequential? x) (mapv strip-run-stamps x)
    :else           x))

;; ===========================================================================
;; CANONICAL FORM — stable ordering + host-portable serialisation
;; ===========================================================================
;;
;; Folded from the former `re-frame.story.identity` canonical-form path
;; (rf2-ee38b.3) and hardened for soundness (rf2-lvrqa + rf2-4gwja):
;;
;; - STRUCTURAL TYPE TAGS (rf2-lvrqa). Each collection is wrapped in a
;;   `[<type-tag> [<canon-elems> …]]` vector keyed by a reserved sentinel
;;   keyword, so the four collection types are mutually distinguishable
;;   AFTER `pr-str`. The former code flattened a map `{:a 1}` to the bare
;;   vector `[:a 1]` and a set `#{}` to `[]`, so `{}` / `#{}` / `[]` and
;;   `{:a 1}` / `[:a 1]` collapsed to byte-identical canonical forms and
;;   hashed equal — a soundness hole every downstream consumer (determinism
;;   gate, semantic diff, golden, snapshot identity) inherited. Tagging
;;   closes it: a map<->vector or set<->vector flip now perturbs the hash.
;; - OPAQUE FN SENTINEL (rf2-4gwja). A function value is canonicalised to
;;   the stable `:rf/opaque-fn` sentinel rather than passing through the
;;   Object/default branch, where `pr-str` would embed the fn's per-process
;;   object identity (`#object[…0x4a2f…]`) and make any hashed slice
;;   carrying a raw fn NON-DETERMINISTIC across processes. The sentinel is
;;   the deliberate trade-off (see `-canon` for fns): two plans/runs that
;;   differ ONLY in fn identity hash EQUAL — determinism is the requirement,
;;   so fn identity is intentionally NOT discriminated.
;;
;; Maps sort entries by the canonicalised key's `pr-str`; sets sort
;; elements by `pr-str`; vectors/seqs keep producer order and recurse;
;; scalars pass through. `pr-str` over canonical scalars is host-identical
;; across JVM + CLJS, so the ordering — and the tags — are stable across
;; hosts.

(def map-tag
  "Reserved structural type-tag prefixing a map's canonical form
  (rf2-lvrqa). A map `{:a 1}` canonicalises to `[:rf/map [:a 1]]`, never
  the bare `[:a 1]`, so it cannot collide with a literal vector of the same
  flattened shape."
  :rf/map)

(def set-tag
  "Reserved structural type-tag prefixing a set's canonical form
  (rf2-lvrqa). A set `#{:a}` canonicalises to `[:rf/set [:a]]`, never the
  bare `[:a]`, so it cannot collide with a vector or a one-entry map."
  :rf/set)

(def vec-tag
  "Reserved structural type-tag prefixing a vector's canonical form
  (rf2-lvrqa). A vector `[:a]` canonicalises to `[:rf/vec [:a]]`, so it is
  distinguishable from a list/seq of the same elements and from a tagged
  map/set."
  :rf/vec)

(def seq-tag
  "Reserved structural type-tag prefixing a seq/list's canonical form
  (rf2-lvrqa). A list `(:a)` canonicalises to `[:rf/seq [:a]]`, so seq vs
  vector is a distinguishable structural difference."
  :rf/seq)

(def opaque-fn
  "Stable opaque sentinel a function value canonicalises to (rf2-4gwja).
  Replaces the per-process object-identity `pr-str` of a raw fn so any
  hashed slice carrying a fn (a `:fx-overrides` / `:interceptors` plan slot,
  an app-db closure-as-value, an effect `:args` callback) hashes
  DETERMINISTICALLY across processes. The deliberate trade-off: two values
  differing ONLY in fn identity hash EQUAL — determinism, not fn
  discrimination, is the contract."
  :rf/opaque-fn)

(defprotocol Canonicalise
  "Render a value into a canonical form: stable key order in maps, stable
  element order in sets, structural type tags distinguishing the four
  collection kinds (rf2-lvrqa), function values folded to a stable opaque
  sentinel (rf2-4gwja), terminal types (strings, keywords, numbers,
  booleans, nil) unchanged. Returns a value that round-trips through
  `pr-str` deterministically across hosts and processes."
  (-canon [x]))

(defn- canon-map-entries
  "Map canon: sort by the canonicalised key (via `pr-str` of the
  canon-key), flatten into a `[k v k v ...]` vector, then wrap under the
  reserved `map-tag` so a map is never byte-identical to a vector / set of
  the same flattened shape (rf2-lvrqa). Symmetric across JVM + CLJS because
  `pr-str` over canonical scalars is host-identical."
  [m]
  (let [entries (->> m
                     (map (fn [[k v]] [(-canon k) (-canon v)]))
                     (sort-by (fn [[k _]] (pr-str k))))]
    [map-tag (into [] (mapcat identity) entries)]))

(defn- canon-set
  "Set canon: sort canonicalised elements by their `pr-str` into a stable
  vector, then wrap under the reserved `set-tag` so a set is never
  byte-identical to a vector / map (rf2-lvrqa)."
  [s]
  [set-tag (vec (sort-by pr-str (map -canon s)))])

(defn- canon-vector
  "Vector canon: recurse over elements (producer order preserved — it is
  semantic) and wrap under the reserved `vec-tag` (rf2-lvrqa)."
  [v]
  [vec-tag (mapv -canon v)])

(defn- canon-seq
  "Seq/list canon: realise + recurse (producer order preserved) and wrap
  under the reserved `seq-tag`, so a seq is distinguishable from a vector
  (rf2-lvrqa)."
  [s]
  [seq-tag (mapv -canon s)])

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

  ;; FUNCTION → stable opaque sentinel (rf2-4gwja). `clojure.lang.Fn` is the
  ;; marker interface fns / closures implement but keywords, symbols, maps,
  ;; vectors, and sets do NOT (they are IFn but not Fn), so this extension
  ;; catches only genuine functions and does not shadow the collection
  ;; branches. CLJS `function` is the native JS fn type (keywords / colls are
  ;; not `function`), the symmetric host case.
  #?(:clj  clojure.lang.Fn  :cljs function)
  (-canon [_] opaque-fn)

  #?(:clj  clojure.lang.IPersistentMap  :cljs IMap)
  (-canon [x] (canon-map-entries x))

  #?(:clj  clojure.lang.IPersistentVector :cljs PersistentVector)
  (-canon [x] (canon-vector x))

  #?(:clj  clojure.lang.IPersistentList :cljs List)
  (-canon [x] (canon-seq x))

  #?(:clj  clojure.lang.IPersistentSet  :cljs PersistentHashSet)
  (-canon [x] (canon-set x))

  #?(:clj  Object             :cljs default)
  (-canon [x]
    ;; Fallback for ISeq / LazySeq / Cons / etc — realise into a tagged seq
    ;; with canonical recursion. A raw fn reaching here (an `IFn` host type
    ;; the `Fn` / `function` branch above did not match) is mapped to the
    ;; opaque sentinel BEFORE the collection branches, so it can never fall
    ;; through to an object-identity `pr-str` (rf2-4gwja). `pr-str` over the
    ;; result is deterministic across hosts AND processes.
    (cond
      (fn? x)         opaque-fn
      (map? x)        (canon-map-entries x)
      (set? x)        (canon-set x)
      (vector? x)     (canon-vector x)
      (sequential? x) (canon-seq x)
      :else           x)))

(defn canonical-form
  "Return the host-portable canonical-form representation of `x` (stable
  key order in maps, stable element order in sets, recursion through
  vectors/seqs, scalars unchanged). The result round-trips through
  `pr-str` deterministically across hosts.

  This is the ordering + serialisation layer ONLY; it does NOT strip
  volatile / accumulator keys. `canonicalize` composes `project` (strip +
  reconcile) with `canonical-form` (order) — call `canonicalize` unless
  you specifically want the raw ordering of an already-projected value."
  [x]
  (-canon x))

;; ===========================================================================
;; CANONICALIZE — the one public projection
;; ===========================================================================

(defn canonicalize
  "The single canonical projection. Given any Story value `x` (a
  run-result, an epoch/run slice, a normalized plan, or a snapshot
  tuple), return the host-portable canonical form with volatile fields
  stripped, `:rf.story/*` accumulator keys dropped, key spellings
  reconciled (`:variant-id` → `:variant/id`), and a total per-slot
  ordering imposed (maps key-sorted; sets element-sorted; vectors/seqs —
  effects, sub-runs, epochs, trace events — keep their authored order,
  which the producer already emits deterministically: effects in emission
  order, epochs in dispatch order).

  This is THE primitive: determinism, semantic-diff, snapshot-identity,
  `:plan-hash`, `:run-hash`, golden slices, and the
  inline-plan-to-registered-variant metamorphic relation all consume it.
  Equivalent values canonicalize `=`; a semantic difference (app-db,
  effect, assertion, …) perturbs the canonical value.

  Projection composes two strips before ordering: the recursive reserved-key
  strip (`project` — volatile + `:rf.story/*` accumulator keys) and the
  structural per-run-stamp strip (`strip-run-stamps` — `:id` / `:time` /
  `:frame` only on their trace-event / epoch-record carriers, rf2-5x1wt.8).
  Together they erase every per-run stamp a fresh-frame replay writes, so
  two semantically-equal runs canonicalize `=` (the determinism gate's
  `test/assert-deterministic` is exactly this equality over N replays)."
  [x]
  (canonical-form (project (strip-run-stamps x))))

;; ===========================================================================
;; CONTENT HASH
;; ===========================================================================

(defn- hex8
  "Render the 32-bit hash of `s` as an 8-char lowercase hex string,
  identically on JVM + CLJS."
  [s]
  (let [h #?(:clj  (bit-and 0xffffffff (hash s))
             :cljs (unsigned-bit-shift-right (hash s) 0))]
    #?(:clj  (format "%08x" h)
       :cljs (let [hs  (.toString h 16)
                   pad (- 8 (.-length hs))]
               (if (pos? pad)
                 (str (apply str (repeat pad "0")) hs)
                 hs)))))

(defn hash-canonical
  "Hash an ALREADY-canonical value (rf2-lvrqa). Prepends `canonical-version`
  as the first hashed slot and renders the `[version canonical-value]` pair
  to an 8-char-hex hash via a SINGLE `pr-str` — it does NOT re-run
  `canonical-form` over its input.

  This is the load-bearing primitive `content-hash` and `canonical-hash`
  share. The former code re-applied `canonical-form` to the already-canonical
  value before hashing; that was harmless only while `canonical-form` was
  idempotent, but the rf2-lvrqa type-tags make it NON-idempotent (a second
  pass would re-wrap `[:rf/map …]` as `[:rf/vec [:rf/map …]]`), so the
  double-canon is removed. A consequence the determinism gate + golden rely
  on: a caller holding an already-`canonicalize`d value `c` gets
  `(hash-canonical c)` == `(canonical-hash <the raw value>)` == `run-hash`,
  with no second canonicalization pass and no idempotence assumption."
  [canonical-value]
  (hex8 (pr-str [canonical-version canonical-value])))

(defn content-hash
  "Stable 8-char-hex content hash of the *exact* value `x` — ordering
  imposed, but the volatile-field strip is NOT applied.

  The canonical form is keyed by `canonical-version`
  (`:rf/snapshot-canonical-v2`) as the first hashed slot, so a
  canonical-form revision bumps the version and old baselines are
  detectably stale. Map key order does not affect the hash; a semantic
  difference — including a map<->vector / set<->vector type flip
  (rf2-lvrqa) — does.

  This is the low primitive the shipping `re-frame.story.identity`
  snapshot tuple hashes. The rf2-lvrqa canonical-version bump
  (`:rf/snapshot-canonical-v2`) re-stamps the snapshot content-hash, so
  external visual-regression baselines re-capture on their next run (there
  are no in-repo stored hash fixtures). Determinism / run-equivalence
  callers want the strip — use `canonical-hash` (or `plan-hash` /
  `run-hash`) there."
  [x]
  (hash-canonical (canonical-form x)))

(defn canonical-hash
  "Stable 8-char-hex hash of the `canonicalize`d projection of `x` —
  volatile fields stripped, `:rf.story/*` accumulator keys dropped,
  key spellings reconciled, ordering imposed.

  This is the determinism / semantic-diff / run-equivalence hash: two
  equivalent values that differ only in volatile fields hash equal; a
  semantic difference perturbs it. `plan-hash` and `run-hash` are this
  primitive applied to enumerated slices — there is no second hash
  implementation. `(canonical-hash x)` == `(hash-canonical (canonicalize
  x))`, the equivalence the determinism gate + golden reuse to hash a canon
  they already hold without a second canonicalization."
  [x]
  (hash-canonical (canonicalize x)))

;; ===========================================================================
;; PLAN HASH — enumerated plan inputs
;; ===========================================================================

(def plan-hash-input-keys
  "The enumerated normalized-plan fields that feed `plan-hash` (spec
  §Artifacts — required normalized plan shape). The hash is taken over
  exactly these slots so two plans with the same testable/renderable
  content hash equal regardless of derived `:evidence`, attached
  `:explain` debug data, the `:source-chain`, or the carried
  `:plan-hash` / `:variant/id` identity slots (those are stripped by
  `canonicalize` as volatile).

  `:world` carries frame config, args, setup, db-seed, render overrides,
  network stubs, fx/interceptor overrides, decorators, and platforms;
  `:script` is behaviour under test; `:expect` is the judgement; and
  `:required-runner` / `:tags` are targeting + classification. `:story/id`
  is kept so two variants under different stories with otherwise identical
  bodies do not collide."
  [:story/id :world :script :expect :required-runner :tags])

(defn plan-hash
  "Compute the `:plan-hash` over the enumerated plan-input slice of a
  normalized `plan` (see `plan-hash-input-keys`). Routes through the same
  `canonical-hash` primitive as `run-hash` — there is no second hash
  implementation.

  Accepts either spelling of the plan map; `canonicalize` reconciles
  `:variant-id` → `:variant/id` and strips it (and any rider `:plan-hash`)
  before hashing, so the plan-hash is a pure function of the plan's
  testable/renderable content."
  [plan]
  (canonical-hash (select-keys plan plan-hash-input-keys)))

;; ===========================================================================
;; RUN HASH — canonical epoch/run slice
;; ===========================================================================

(def run-hash-input-keys
  "The run-result slots that feed `:run-hash` (spec §Run result). The
  hash is taken over the canonical epoch/run slice — the evidence that a
  semantic difference must perturb — and excludes the API-stable but
  non-evidential slots (`:elapsed-ms`, `:runner`, `:plan-hash`,
  `:variant/id`, the derived `:narrative` / `:trace-summary` projections),
  which `canonicalize` strips or which are pure re-projections of the
  retained slots.

  `:status` plus the final `:app-db`, the `:epoch-tape`, the
  `:assertions` / `:checks` verdicts, the projected `:effects` /
  `:schema-violations` / `:warnings`, and the resolved `:sub-overrides` /
  `:fidelity` are the behavioural surface: change any one and the run-hash
  changes."
  [:status :app-db :epoch-tape :assertions :checks
   :effects :schema-violations :warnings :sub-overrides :fidelity])

(defn run-hash
  "Compute the `:run-hash` over the canonical epoch/run slice of a
  run-`result` (see `run-hash-input-keys`). Routes through the same
  `canonical-hash` primitive as `plan-hash`.

  Two equivalent runs that differ only in volatile fields (wall-clock
  elapsed, generated dispatch ids, runner kind, …) hash equal because
  `canonicalize` projects those away recursively, including inside the
  `:epoch-tape` beats."
  [result]
  (canonical-hash (select-keys result run-hash-input-keys)))
