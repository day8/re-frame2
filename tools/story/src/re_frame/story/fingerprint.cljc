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
      :plan-hash}` (and the shipping `:variant-id` spelling, reconciled to
    `:variant/id` first);
  - impose a total per-slot ordering — effects keep emission order,
    sub-runs are topo-then-id, epochs are dispatch order, trace events
    keep emission order;
  - enumerate the `:plan-hash` input fields;
  - compute `:run-hash` over the canonical epoch slice.

  ## Hash function

  The hash is the same portable hash the former identity ns used: a
  stable string serialisation (deterministic key order; sets/vectors
  written in stable order) hashed with `hash` (JVM
  `clojure.lang.Util/hasheq`, CLJS `cljs.core/hash`), rendered as an
  8-char lowercase hex string. It is 32-bit and per-artefact, not
  cryptographic; callers that need collision-resistance against an
  external service dedupe by `[id content-hash]`. The sha-256 path is a
  later extension.

  The canonical-form keyword `:rf/snapshot-canonical-v1` is the first
  slot of the hashed structure, so a future canonical-form revision can
  introduce `:rf/snapshot-canonical-v2` without breaking v1 baselines.")

;; ===========================================================================
;; CANONICAL VERSION TAG
;; ===========================================================================

(def canonical-version
  "The canonical-form version tag. Folded verbatim from the former
  `re-frame.story.identity` `:rf/snapshot-canonical-v1` so existing
  snapshot-identity baselines keep the same first hash slot — the
  migration is a pure relocation, not a version bump (see
  `re-frame.story.identity` for the migration path)."
  :rf/snapshot-canonical-v1)

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
  canonical value."
  #{:frame :rf.trace/dispatch-id :rf.trace/trace-id :rf.event/elapsed-ms})

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
;; Folded verbatim from the former `re-frame.story.identity`
;; canonical-form path (rf2-ee38b.3). Maps become `[k v k v ...]` vectors
;; sorted by the canonicalised key's `pr-str`; sets become element-sorted
;; vectors; vectors/seqs recurse; scalars pass through. `pr-str` over
;; canonical scalars is host-identical across JVM + CLJS, so the ordering
;; is stable across hosts.

(defprotocol Canonicalise
  "Render a value into a canonical form: stable key order in maps, stable
  element order in sets, terminal types (strings, keywords, numbers,
  booleans, nil) unchanged. Returns a value that round-trips through
  `pr-str` deterministically across hosts."
  (-canon [x]))

(defn- canon-map-entries
  "Map canon: sort by the canonicalised key (via `pr-str` of the
  canon-key) then flatten into a `[k v k v ...]` vector. Symmetric across
  JVM + CLJS because `pr-str` over canonical scalars is host-identical."
  [m]
  (let [entries (->> m
                     (map (fn [[k v]] [(-canon k) (-canon v)]))
                     (sort-by (fn [[k _]] (pr-str k))))]
    (into [] (mapcat identity) entries)))

(defn- canon-set
  "Set canon: sort canonicalised elements by their `pr-str` into a stable
  vector."
  [s]
  (vec (sort-by pr-str (map -canon s))))

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
  (-canon [x] (canon-map-entries x))

  #?(:clj  clojure.lang.IPersistentVector :cljs PersistentVector)
  (-canon [x] (mapv -canon x))

  #?(:clj  clojure.lang.IPersistentList :cljs List)
  (-canon [x] (mapv -canon x))

  #?(:clj  clojure.lang.IPersistentSet  :cljs PersistentHashSet)
  (-canon [x] (canon-set x))

  #?(:clj  Object             :cljs default)
  (-canon [x]
    ;; Fallback for ISeq / LazySeq / etc — realise into a vector with
    ;; canonical recursion. `pr-str` over the result is deterministic.
    (cond
      (sequential? x) (mapv -canon x)
      (set? x)        (canon-set x)
      (map? x)        (canon-map-entries x)
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

(defn content-hash
  "Stable 8-char-hex content hash of the *exact* value `x` — ordering
  imposed, but the volatile-field strip is NOT applied.

  The canonical form is keyed by `canonical-version`
  (`:rf/snapshot-canonical-v1`) as the first hashed slot, so a future
  canonical-form revision can bump the version without breaking
  baselines. Map key order does not affect the hash; a semantic
  difference does.

  This is the low primitive the shipping `re-frame.story.identity`
  snapshot tuple hashes. Keeping it strip-free means the snapshot
  content-hash is byte-identical across the rf2-5x1wt.3 fold (existing
  visual-regression baselines stay valid). Determinism / run-equivalence
  callers want the strip — use `canonical-hash` (or `plan-hash` /
  `run-hash`) there."
  [x]
  (-> [canonical-version (canonical-form x)]
      canonical-form
      pr-str
      hex8))

(defn canonical-hash
  "Stable 8-char-hex hash of the `canonicalize`d projection of `x` —
  volatile fields stripped, `:rf.story/*` accumulator keys dropped,
  key spellings reconciled, ordering imposed.

  This is the determinism / semantic-diff / run-equivalence hash: two
  equivalent values that differ only in volatile fields hash equal; a
  semantic difference perturbs it. `plan-hash` and `run-hash` are this
  primitive applied to enumerated slices — there is no second hash
  implementation."
  [x]
  (-> [canonical-version (canonicalize x)]
      canonical-form
      pr-str
      hex8))

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
