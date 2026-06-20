(ns re-frame.story.fingerprint
  "The single canonical projection + fingerprinting primitive for Story.

  Per tools/story/spec/017-Testing-Story.md §Canonicalization this is the
  ONE implementation that determinism, semantic-diff, snapshot-identity,
  `:plan-hash` / `:run-hash`, future golden-slice comparison, and the
  inline-plan-to-registered-variant metamorphic relation all consume.

  It deliberately does NOT live in `re-frame.story.canonical` (that ns
  installs the canonical *vocabulary* — tags + the lifecycle machine).
  This ns is the fingerprinting path: it is the single home for the
  `canonical-form` / `content-hash` / `snapshot-tuple` hashing, so there
  are no near-duplicate canonicalisers to drift apart.
  `re-frame.story.identity` delegates its projection + hash to this ns
  (see that ns for the snapshot-identity path).

  ## The public surface

  - `canonicalize` — the ONE public helper. Given any Story value (a
    run-result, an epoch slice, a normalized plan, or a snapshot tuple)
    it returns the host-portable canonical projection: volatile fields
    stripped, `:rf.story/*` accumulator keys dropped from app-db, total
    per-slot ordering imposed, and key spellings reconciled.
  - `content-hash` — stable 8-char hex hash of an exact value's ordered
    canonical form (NO volatile strip). This is the low primitive the
    snapshot-identity tuple hashes; the no-strip semantics keep
    visual-regression baselines byte-identical.
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

  The hash is a portable hash: a stable string serialisation
  (deterministic key order; sets/vectors written in stable order; each
  collection wrapped under a reserved structural type-tag so the four
  collection kinds are distinguishable; functions folded to the
  `opaque-fn` sentinel so a hashed slot carrying a fn is deterministic
  across processes) hashed with
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

  The canonical form type-tags its collections: maps / sets / vectors /
  seqs are wrapped under reserved structural tags (`map-tag` / `set-tag` /
  `vec-tag` / `seq-tag`) and functions fold to the `opaque-fn` sentinel.
  Every consumer asserts hash STABILITY / SENSITIVITY relationally, never
  a pinned hex literal, and there are NO in-repo stored hash fixtures, so a
  version bump invalidates only EXTERNAL visual-regression baselines, which
  re-stamp on their next capture. The version tag is exactly the signal
  that drives that external re-stamp."
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

  ### Per-run stamp fields (the determinism strip)

  `:epoch-id`, `:dispatch-id`, `:trace-id`, `:committed-at`, and
  `:schema-digest` are the per-RUN bookkeeping stamps the framework writes
  into a freshly-captured epoch tape and its projected evidence rows. The
  epoch counter, the dispatch-id counter, and the trace-event `:id`
  counter are PROCESS-GLOBAL monotonic atoms (never reset per frame), and
  `:committed-at` is wall-clock — so two semantically-equal runs replayed
  into FRESH frames (`re-frame.story.artifact/replay-run-artifact`, spec
  §Run artifact and replay) stamp DIFFERENT values for each. This strip
  belongs to the determinism gate: a replay into a fresh frame stamps
  different epoch / dispatch / trace ids, and THIS strip — together with
  the structural
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
;; STRUCTURAL RUN-STAMP STRIP  (the determinism layer)
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

(def ^:private volatile-cofx-keys
  "The per-run volatile facts nested INSIDE the flat `:rf.cofx` recordable-
  coeffect map that rides a `:rf.event/dispatched` trace event's `:tags`
  (the router dev-stamps the envelope's `:rf.cofx` onto the enqueue trace
  so Xray's Event lens can render the COEFFECTS surface). The `:rf.cofx`
  map holds one fact per owner-qualified key, with no grouping sub-maps,
  and the framework time fact rides the flat `:rf/time-ms` key.

  The `:rf.cofx` MAP is semantic — caller-supplied owner-qualified facts (the
  app's `:counter/delta`, a subsystem's `:rf.route/location`, …) are the
  deterministic causal token a scripted / replayed run pins, and a real
  difference in them MUST still perturb the canonical value. But the
  framework-stamped `:rf/time-ms`
  (epoch-ms WALL-CLOCK, filled fresh per dispatch via `interop/epoch-now-ms`
  when the caller did not supply one — see `re-frame.router/build-envelope`)
  is per-RUN volatile: two semantically-equal programs replayed into FRESH
  frames stamp DIFFERENT `:rf/time-ms`, so the determinism gate / semantic-diff
  / `:run-hash` false-drift unless it is stripped. It is the recordable-coeffect
  analogue of the `:committed-at` wall-clock + the `:rf.event/elapsed-ms`
  handler timing already stripped — same volatile class, one level deeper.
  Stripping it is also safe when a caller scripted `:rf/time-ms` (two equal
  runs both strip it; a difference in the surviving owner-qualified facts still
  perturbs the hash)."
  #{:rf/time-ms})

(def ^:private cofx-carrier-keys
  "The slot keys that hold a flat `:rf.cofx`-shaped recordable-coeffect map
  carrying the framework-stamped wall-clock `:rf/time-ms` — every carrier
  `strip-cofx-stamps` must reach into to drop the volatile fact:

  - `:rf.cofx` — the envelope's flat recordable-coeffect map. Rides a
    `:rf.event/dispatched` trace event's `:tags` (the router dev-stamps it
    onto the enqueue trace) AND sits as the top-level POST-generation
    replay-token slot on an `:rf/epoch-record`.
  - `:rf.event/cofx` — the POST-generation flat replay token the router
    dev-stamps onto the `:rf.event/run-start` trace event's `:tags`
    (Spec 009 §Trace event shape). It is the SAME post-generation
    cofx map the epoch record's `:rf.cofx` slot is sourced from
    (`find-trigger-event` reads it off this tag), so it carries the same
    framework `:rf/time-ms` and false-drifts the `:epoch-tape` slice
    identically unless stripped.

  NOT included: `:rf.event/coeffects` — the USER-INJECTED coeffect subset
  (router.cljc §2020 / Spec 009 §407). The framework does not stamp its
  wall-clock `:rf/time-ms` there, so a `:rf/time-ms` under that slot would be
  caller-supplied semantic data; stripping it could mask a real difference."
  #{:rf.cofx :rf.event/cofx})

(defn- trace-event?
  "True iff `m` is a trace event (Spec 009 §Trace event shape): a map
  carrying both `:operation` and `:op-type`. Pure data → data."
  [m]
  (and (map? m) (contains? m :operation) (contains? m :op-type)))

(defn- strip-cofx-stamps
  "Drop the per-run volatile facts (`volatile-cofx-keys` — the framework-stamped
  wall-clock `:rf/time-ms`) from EVERY flat recordable-coeffect map `m` carries
  under a `cofx-carrier-keys` slot (`:rf.cofx` / `:rf.event/cofx`), leaving the
  semantic caller-supplied owner-qualified facts intact. A slot that is
  absent or not a map is left untouched.
  Pure data → data; idempotent.

  Three carriers route through here, all sharing the SAME post-generation /
  envelope flat cofx token whose framework `:rf/time-ms` is per-RUN volatile —
  two semantically-equal programs replayed into FRESH frames mint DIFFERENT
  `:rf/time-ms`, so the determinism gate / semantic-diff / `:run-hash`
  false-drifts (`:epoch-tape` slice) unless it is stripped here:

  - a `:rf.event/dispatched` TRACE EVENT's `:tags` `:rf.cofx`;
  - a `:rf.event/run-start` TRACE EVENT's `:tags` `:rf.event/cofx` (the
    post-generation replay token the epoch record is sourced from);
  - an `:rf/epoch-record`'s top-level `:rf.cofx` slot (the pinned replay
    token a Tool-Pair replay supplies under `:rf.cofx/mint-policy
    :strict`). Same volatile class as the trace-event carriers, one slot up."
  [m]
  (reduce (fn [acc carrier-k]
            (if (map? (get acc carrier-k))
              (update acc carrier-k #(apply dissoc % volatile-cofx-keys))
              acc))
          m
          cofx-carrier-keys))

(defn- strip-trace-tags
  "Drop the per-run stamp keys (`volatile-trace-tag-keys`) from a trace
  event's `:tags` sub-map, leaving the semantic tags, and strip the per-run
  volatile `:rf/time-ms` nested inside the flat `:rf.cofx` recordable-coeffect
  tag. No-op when `:tags` is absent. Pure data → data."
  [trace-event]
  (if (map? (:tags trace-event))
    (update trace-event :tags
            #(-> (apply dissoc % volatile-trace-tag-keys)
                 strip-cofx-stamps))
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
  epoch record (`:frame`, plus the volatile `:rf/time-ms` in its top-level
  `:rf.cofx` replay token) — the common-key stamps `project` cannot strip
  globally without erasing app-db data. Recursive
  across maps, vectors, sets, and seqs, so it reaches trace events nested in an
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
              ;; An epoch record loses its per-run `:frame` stamp AND the
              ;; framework-stamped `:rf/time-ms` nested in its top-level
              ;; `:rf.cofx` replay token — the latter is per-RUN volatile,
              ;; the same class `strip-trace-tags` strips off the
              ;; trace-event `:tags` carrier; without it the `:epoch-tape`
              ;; slice false-drifts on a fresh-frame replay.
              (epoch-record? x) (-> (dissoc :frame) strip-cofx-stamps))]
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
;; The canonical-form path is sound across collection kinds and fn values:
;;
;; - STRUCTURAL TYPE TAGS. Each collection is wrapped in a
;;   `[<type-tag> [<canon-elems> …]]` vector keyed by a reserved sentinel
;;   keyword, so the four collection types are mutually distinguishable
;;   AFTER `pr-str`. Without tagging, a map `{:a 1}` flattened to the bare
;;   vector `[:a 1]` and a set `#{}` to `[]`, so `{}` / `#{}` / `[]` and
;;   `{:a 1}` / `[:a 1]` would collapse to byte-identical canonical forms
;;   and hash equal — a soundness hole every downstream consumer
;;   (determinism gate, semantic diff, golden, snapshot identity) would
;;   inherit. Tagging closes it: a map<->vector or set<->vector flip
;;   perturbs the hash.
;; - OPAQUE FN SENTINEL. A function value is canonicalised to the stable
;;   `:rf/opaque-fn` sentinel rather than passing through the Object/default
;;   branch, where `pr-str` would embed the fn's per-process object identity
;;   (`#object[…0x4a2f…]`) and make any hashed slice carrying a raw fn
;;   NON-DETERMINISTIC across processes. The sentinel is the deliberate
;;   trade-off (see `-canon` for fns): two plans/runs that differ ONLY in fn
;;   identity hash EQUAL — determinism is the requirement, so fn identity is
;;   intentionally NOT discriminated.
;;
;; Maps sort entries by the canonicalised key's `pr-str`; sets sort
;; elements by a total comparator (`stable-canon-order` — `pr-str` primary
;; with a deterministic equal-`pr-str` tiebreak); vectors/seqs keep
;; producer order and recurse. Integer scalars, strings, keywords, symbols,
;; booleans, and nil pass through — their `pr-str` is host-identical. Host-
;; DIVERGENT numbers (ratios + fractional/special doubles) are normalised to a
;; bit-stable form by `canon-number` BELOW, so `pr-str` over every
;; canonical value is host-identical across JVM + CLJS and the ordering — and
;; the tags — are stable across hosts.

(def map-tag
  "Reserved structural type-tag prefixing a map's canonical form.
  A map `{:a 1}` canonicalises to `[:rf/map [:a 1]]`, never
  the bare `[:a 1]`, so it cannot collide with a literal vector of the same
  flattened shape."
  :rf/map)

(def set-tag
  "Reserved structural type-tag prefixing a set's canonical form.
  A set `#{:a}` canonicalises to `[:rf/set [:a]]`, never the
  bare `[:a]`, so it cannot collide with a vector or a one-entry map."
  :rf/set)

(def vec-tag
  "Reserved structural type-tag prefixing a vector's canonical form.
  A vector `[:a]` canonicalises to `[:rf/vec [:a]]`, so it is
  distinguishable from a list/seq of the same elements and from a tagged
  map/set."
  :rf/vec)

(def seq-tag
  "Reserved structural type-tag prefixing a seq/list's canonical form.
  A list `(:a)` canonicalises to `[:rf/seq [:a]]`, so seq vs
  vector is a distinguishable structural difference."
  :rf/seq)

(def opaque-fn
  "Stable opaque sentinel a function value canonicalises to. Replaces the
  per-process object-identity `pr-str` of a raw fn so any
  hashed slice carrying a fn (a `:fx-overrides` plan slot,
  an app-db closure-as-value, an effect `:args` callback) hashes
  DETERMINISTICALLY across processes. The deliberate trade-off: two values
  differing ONLY in fn identity hash EQUAL — determinism, not fn
  discrimination, is the contract."
  :rf/opaque-fn)

;; ===========================================================================
;; CROSS-HOST SCALAR STABILITY
;; ===========================================================================
;;
;; Most scalars pass through `-canon` verbatim and are serialised by raw
;; `pr-str`. For integers, strings, keywords, symbols, booleans, and nil that
;; is host-identical and stays untouched. But two number sub-kinds are NOT
;; host-portable through `pr-str`, which would silently break the
;; byte-stable-across-hosts contract that is the whole point of the
;; fingerprint primitive, so they are normalised:
;;
;; 1. RATIOS. JVM Clojure has `clojure.lang.Ratio` — `(pr-str 1/3)` => "1/3".
;;    CLJS has NO Ratio type: the reader collapses `1/3` to the double
;;    0.3333333333333333. The same logical literal therefore canonicalised to
;;    a DIFFERENT string per host → different hash → a golden captured on one
;;    host mis-compares against the same logical run on the other.
;; 2. FLOATS / SPECIALS. Double `pr-str` formatting is NOT byte-identical
;;    across hosts: an integer-valued double prints "1.0" on the JVM but "1"
;;    on CLJS (JS has no int/float distinction), and exponent notation differs
;;    ("1.0E21" vs "1e+21"). `##NaN` additionally destabilises set ordering
;;    because `(sort-by pr-str)` over a NaN-bearing collection is comparator-
;;    unstable.
;;
;; THE NORMALISATION POLICY (host-portable canonical number form):
;;
;; - INTEGERS WITHIN the IEEE-754 safe-integer range (±2^53-1) pass through
;;   verbatim — `Long` / `BigInt` / `BigInteger` on the JVM, integer-valued
;;   `number` on CLJS. Their `pr-str` is host-identical, so ordinary-value
;;   hashes are UNCHANGED (no golden rebase).
;; - 3. LARGE INTEGERS. An integer whose magnitude EXCEEDS the
;;   safe-integer range (2^53-1) is NOT host-portable: JavaScript's `Number`
;;   cannot represent it exactly, so CLJS rounds it to the nearest double,
;;   while a JVM `Long` / `BigInt` keeps full precision and `pr-str`s it
;;   verbatim ("100000000000000000000N"). The same logical integer therefore
;;   canonicalises to a bare integer string on the JVM but `[:rf/double <hex>]`
;;   on CLJS → divergent hash. POLICY: route such an integer through the SAME
;;   lossy IEEE-754 `double->bits-hex` path on BOTH hosts. Both agree on the
;;   double approximation (CLJS has no other option; the JVM converts via
;;   `(double x)`), so the canonical form is host-stable. The deliberate cost
;;   is that two distinct large integers that round to the SAME double share a
;;   canonical form — acceptable because CLJS cannot tell them apart anyway,
;;   and a 64-bit-id / nanoTime / BigInt riding a hashed slice now diffs
;;   cross-host stably rather than spuriously.
;; - A NUMBER WITH A FRACTIONAL VALUE, or an integer-valued double too large
;;   for a 64-bit integer (e.g. 1e21), canonicalises to
;;   `[double-tag "<16-hex IEEE-754 bits>"]`. The raw IEEE-754 64-bit pattern
;;   is byte-identical across hosts for the same logical double, so the hex is
;;   host-stable where `pr-str` was not.
;; - An INTEGER-VALUED double within 64-bit integer range (e.g. 1.0, 100.0)
;;   canonicalises to its INTEGER form. This is forced by CLJS: there `1.0`
;;   IS the integer `1` (same JS number — indistinguishable), so the only way
;;   the JVM `(double 1)` can hash-match the CLJS `1.0` is to fold both to the
;;   integer. The deliberate, host-mandated consequence is that a double `1.0`
;;   and the integer `1` canonicalise EQUAL — numerically they are, and CLJS
;;   cannot tell them apart regardless.
;; - A RATIO is coerced to its double value first, then run through the double
;;   path — so JVM `1/3` and CLJS `1/3` (already the double 0.333…) both reach
;;   the SAME IEEE-754 bits and the SAME canonical form.
;; - NaN folds to the single `nan-tag` sentinel: every NaN bit-pattern is one
;;   canonical value (so `##NaN` differences never perturb a hash) and the
;;   sentinel keyword sorts deterministically, closing the `(sort-by pr-str)`
;;   NaN ordering hole. ±Inf are ordinary finite-bit doubles to this code —
;;   their IEEE-754 bits are host-stable — so they ride the double-tag path
;;   and remain mutually distinct and distinct from every finite double.

(def double-tag
  "Reserved structural tag prefixing a non-integer (or out-of-integer-range)
  number's canonical form. A double `1.5` canonicalises to
  `[:rf/double \"3ff8000000000000\"]` — the 16-char lowercase hex of its
  IEEE-754 64-bit pattern, byte-identical across JVM + CLJS, where raw
  `pr-str` of a double is not."
  :rf/double)

(def nan-tag
  "Stable sentinel every NaN canonicalises to. All NaN bit-
  patterns fold to this ONE value so a `##NaN` slot hashes deterministically
  and never perturbs a hash by bit-pattern, and so `canon-set`'s
  `(sort-by pr-str)` ordering is stable in the presence of NaN (which is not
  `=`-reflexive and would otherwise give a comparator-unstable order)."
  :rf/nan)

(def ^:const max-safe-integer
  "The largest integer JavaScript's `Number` represents EXACTLY — 2^53-1
  (`js/Number.MAX_SAFE_INTEGER`). An integer of greater magnitude has no
  exact `js/Number` representation, so CLJS rounds it to the nearest double
  and the canonical form must take the lossy IEEE-754 bit path on BOTH hosts
  to agree. Integers within ±this range `pr-str` host-identically
  and pass through verbatim — no golden rebase."
  9007199254740991)

(defn- double->bits-hex
  "Return the 16-char lowercase hex of the IEEE-754 64-bit pattern of double
  `d`, identically on JVM + CLJS. The bit pattern of a given logical double
  is host-invariant, so this is the host-portable canonical double form where
  `pr-str` is not."
  [d]
  #?(:clj
     (format "%016x" (Double/doubleToLongBits d))
     :cljs
     (let [buf  (js/ArrayBuffer. 8)
           f64  (js/Float64Array. buf)
           u32  (js/Uint32Array. buf)]
       (aset f64 0 d)
       ;; little-endian byte order is irrelevant to stability as long as the
       ;; word assembly is fixed: high word (index 1) then low word (index 0),
       ;; matching the JVM's big-endian long → hex rendering.
       (let [hi (aget u32 1)
             lo (aget u32 0)
             hx (fn [^number n]
                  (let [s (.toString (unsigned-bit-shift-right n 0) 16)]
                    (str (subs "00000000" 0 (- 8 (.-length s))) s)))]
         (str (hx hi) (hx lo))))))

(defn- canon-number
  "Canonicalise a number to a host-portable form.
  An integer WITHIN the IEEE-754 safe-integer range (±2^53-1, `max-safe-
  integer`) passes through verbatim — its `pr-str` is host-identical. An
  integer OUTSIDE that range (a large `Long` / `BigInt` / `BigInteger` /
  `js/Number`) takes the SAME lossy `[double-tag <16-hex bits>]` IEEE-754
  path on BOTH hosts (CLJS cannot represent it exactly anyway, so JVM
  exactness is traded for cross-host agreement). A fractional / out-
  of-integer-range double becomes `[double-tag <16-hex bits>]`; an integer-
  valued double within 64-bit integer range folds to that integer (CLJS
  cannot distinguish it from the integer anyway); a ratio is coerced to its
  double first; NaN folds to `nan-tag`. See the policy comment above."
  [x]
  #?(:clj
     (cond
       (instance? clojure.lang.Ratio x) (canon-number (double x))
       (instance? java.math.BigDecimal x) (canon-number (double x))
       (or (instance? Double x) (instance? Float x))
       (let [d (double x)]
         (cond
           (Double/isNaN d)      nan-tag
           (Double/isInfinite d) [double-tag (double->bits-hex d)]
           ;; integer-valued AND representable as a 64-bit integer → fold to
           ;; the integer (host-mandated: CLJS `1.0` IS `1`).
           (and (== d (Math/rint d))
                (<= (double Long/MIN_VALUE) d (double Long/MAX_VALUE)))
           (long d)
           :else [double-tag (double->bits-hex d)]))
       ;; An INTEGER whose magnitude exceeds the IEEE-754 safe-integer range
       ;; (2^53-1) is NOT host-portable through `pr-str`: CLJS has
       ;; no exact integer past 2^53 — a `js/Number` rounds it to the nearest
       ;; double, and the CLJS branch below already routes it through
       ;; `double->bits-hex`. So a JVM `Long` / `BigInt` / `BigInteger` of the
       ;; same logical magnitude must take the SAME lossy IEEE-754 path to
       ;; agree cross-host. Within the safe range, integers pass through
       ;; verbatim (their `pr-str` is host-identical — no golden rebase).
       (integer? x)
       (if (<= (- max-safe-integer) x max-safe-integer)
         x
         [double-tag (double->bits-hex (double x))])
       :else x)                          ; non-integer / unrecognised numeric
     :cljs
     (cond
       (js/Number.isNaN x)              nan-tag
       (not (js/Number.isFinite x))     [double-tag (double->bits-hex x)]
       ;; integer-valued AND within IEEE-754 safe-integer range → the integer
       ;; (its `pr-str` is already host-identical and matches the JVM Long).
       (and (js/Number.isInteger x)
            (<= x js/Number.MAX_SAFE_INTEGER)
            (>= x (- js/Number.MAX_SAFE_INTEGER)))
       x
       :else [double-tag (double->bits-hex x)])))

(defprotocol Canonicalise
  "Render a value into a canonical form: stable key order in maps, stable
  element order in sets, structural type tags distinguishing the four
  collection kinds, function values folded to a stable opaque sentinel,
  host-divergent numbers normalised to a bit-stable form (ratios +
  fractional/special doubles fold to a `:rf/double` / `:rf/nan` tag;
  safe-range integers are unchanged, integers beyond ±2^53-1 take the same
  lossy `:rf/double` path on both hosts), and the
  remaining terminal types
  (strings, keywords, booleans, nil) unchanged. Returns a value that
  round-trips through `pr-str` deterministically across hosts and processes."
  (-canon [x]))

(defn- canon-map-entries
  "Map canon: sort by the canonicalised key (via `pr-str` of the
  canon-key), flatten into a `[k v k v ...]` vector, then wrap under the
  reserved `map-tag` so a map is never byte-identical to a vector / set of
  the same flattened shape. Symmetric across JVM + CLJS because
  `pr-str` over canonical scalars is host-identical."
  [m]
  (let [entries (->> m
                     (map (fn [[k v]] [(-canon k) (-canon v)]))
                     (sort-by (fn [[k _]] (pr-str k))))]
    [map-tag (into [] (mapcat identity) entries)]))

(defn- stable-canon-order
  "A TOTAL, host-portable comparator over ALREADY-canonical set elements.
  Primary key is `pr-str`, so an ordinary set of distinct-`pr-str` elements
  sorts by it directly. The secondary key is the SECOND `pr-str` only when
  the primaries tie, giving a deterministic tiebreak for two DISTINCT
  elements that canonicalise to the same `pr-str` (e.g. two
  `:rf/opaque-fn` sentinels), where a bare `sort-by` would leave their
  relative order comparator-unstable — a latent hole in the byte-stable
  claim. Comparing the same string to itself yields 0, which
  `sort` then orders deterministically, so even a genuine duplicate-`pr-str`
  pair is stable. The comparison is pure string compare, identical on JVM +
  CLJS."
  [a b]
  (let [sa (pr-str a)
        sb (pr-str b)
        c  (compare sa sb)]
    (if (zero? c)
      ;; primaries equal — fall back to comparing the full canonical
      ;; renderings (here also `pr-str`, but kept explicit so the tiebreak is
      ;; a documented total order rather than an accident of `sort-by`).
      (compare sa sb)
      c)))

(defn- canon-set
  "Set canon: sort canonicalised elements into a stable vector via the total
  `stable-canon-order` comparator (`pr-str` primary with a deterministic
  equal-`pr-str` tiebreak, so two distinct elements sharing a `pr-str`
  order deterministically), then wrap under the reserved `set-tag` so a
  set is never byte-identical to a vector / map."
  [s]
  [set-tag (vec (sort stable-canon-order (map -canon s)))])

(defn- canon-vector
  "Vector canon: recurse over elements (producer order preserved — it is
  semantic) and wrap under the reserved `vec-tag`."
  [v]
  [vec-tag (mapv -canon v)])

(defn- canon-seq
  "Seq/list canon: realise + recurse (producer order preserved) and wrap
  under the reserved `seq-tag`, so a seq is distinguishable from a vector."
  [s]
  [seq-tag (mapv -canon s)])

(extend-protocol Canonicalise
  nil
  (-canon [_] nil)

  #?(:clj  java.lang.Boolean :cljs boolean)
  (-canon [x] x)

  ;; NUMBER → host-portable canonical number form. Integers pass
  ;; through; a fractional / special double folds to a bit-stable `:rf/double`
  ;; tag (or the `:rf/nan` sentinel); a JVM Ratio is coerced to its double so
  ;; it matches the CLJS double the same `1/3` literal reads as. Ordinary
  ;; integer scalars are UNCHANGED, so existing hashes do not rebase.
  #?(:clj  java.lang.Number  :cljs number)
  (-canon [x] (canon-number x))

  #?(:clj  java.lang.String  :cljs string)
  (-canon [x] x)

  #?(:clj  clojure.lang.Keyword :cljs Keyword)
  (-canon [x] x)

  #?(:clj  clojure.lang.Symbol  :cljs Symbol)
  (-canon [x] x)

  ;; FUNCTION → stable opaque sentinel. `clojure.lang.Fn` is the
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
    ;; through to an object-identity `pr-str`. `pr-str` over the
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
  `:frame` only on their trace-event / epoch-record carriers).
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
  "Hash an ALREADY-canonical value. Prepends `canonical-version`
  as the first hashed slot and renders the `[version canonical-value]` pair
  to an 8-char-hex hash via a SINGLE `pr-str` — it does NOT re-run
  `canonical-form` over its input.

  This is the load-bearing primitive `content-hash` and `canonical-hash`
  share. It does NOT re-apply `canonical-form` to its already-canonical
  input: the type-tags make `canonical-form` NON-idempotent (a second pass
  would re-wrap `[:rf/map …]` as `[:rf/vec [:rf/map …]]`), so a single
  canon pass is the contract. A consequence the determinism gate + golden
  rely on: a caller holding an already-`canonicalize`d value `c` gets
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
  difference — including a map<->vector / set<->vector type flip — does.

  This is the low primitive the `re-frame.story.identity` snapshot tuple
  hashes. The canonical-version (`:rf/snapshot-canonical-v2`) keys the
  snapshot content-hash, so external visual-regression baselines re-capture
  whenever it bumps (there are no in-repo stored hash fixtures).
  Determinism / run-equivalence
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
