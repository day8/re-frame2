(ns re-frame.mcp-base.sensitive
  "Spec/009 §Privacy default-suppress filter for `:sensitive?` trace
  events.

  ## What spec/009 mandates

  Framework-published forwarders — Sentry / Honeybadger, re-frame2-pair server,
  Story-MCP — MUST default-drop trace events whose
  registration declared `:sensitive? true`. The runtime stamps the
  flag at the top level of every emitted trace event inside such a
  registration's handler scope; the forwarder's job is to gate egress
  on it before any data crosses the trust boundary into the agent
  surface.

  ## Cross-server convention

  The opt-in arg name (`:include-sensitive`) is the fixed, cross-
  server vocabulary an agent learns once. Every MCP tool that surfaces
  trace-like data MUST accept this arg, default it to false, and feed
  it to `strip-sensitive` (and any analogous walker that recurses
  through snapshot slices). The wire-key omits the trailing `?` per
  Anthropic's tool-input-schema regex `^[a-zA-Z0-9_.-]{1,64}$` —
  predicate-style `?` is rejected there. The predicate FUNCTION name
  `include-sensitive?` retains its `?` (the idiom belongs on the
  predicate, not on a data key whose wire form disallows it).
  Both servers ship the unqualified wire-key — story-mcp and
  re-frame2-pair-mcp alike — so the cross-server arg name is uniform.

  ## Why this ns is zero-dep

  re-frame2-pair-mcp is a CLJS Node bundle (no `re-frame.trace` on its
  classpath); story-mcp is JVM-side and DOES have the
  framework primitive available. The predicate body
  (`(and (map? ev) (sensitive-stamp? (:sensitive? ev)))`) is
  conservative and matches the spirit of `re-frame.privacy/sensitive?`.
  Per the spec the runtime always stamps the literal boolean; if a
  transport bug delivers any other truthy value (fail-closed
  posture) we drop the event AND log so the contract drift is
  visible.")

;; ---------------------------------------------------------------------------
;; Fail-closed predicate
;; ---------------------------------------------------------------------------
;;
;; Spec/009 declares `:sensitive?` as a boolean. The predicate is
;; fail-CLOSED: any truthy non-boolean stamp is treated as
;; "claim to be sensitive, but malformed" — we drop AND log so the
;; drift surfaces in operator output. Only an explicit `false` /
;; absent / nil stamp passes. Matching only the literal `true` would
;; fail OPEN on contract drift: an upstream serialisation bug that
;; coerces the boolean into a string would silently leak every
;; sensitive event past the wire-egress filter.

#?(:clj  (defonce ^:private ^java.util.concurrent.atomic.AtomicLong
           malformed-counter
           (java.util.concurrent.atomic.AtomicLong. 0))
   :cljs (defonce ^:private malformed-counter (atom 0)))

(defn malformed-count
  "Read the count of malformed `:sensitive?` stamps observed since
  process start (or since the last `reset-malformed-count!`). The
  fail-closed posture is silent on the wire — the counter
  is the operator-surface observability hook. Public so operator
  dashboards and tests can read the gate's activity.

  This is a faithful PER-EVENT metric: the egress walkers
  (`strip-sensitive` / `scrub-snapshot`) classify each event exactly
  once, so a single dropped malformed event bumps the counter exactly
  once. Direct `sensitive-event?` / `sensitive-stamp?` calls bump once
  per call.

  Returns a non-negative integer."
  []
  #?(:clj  (.get ^java.util.concurrent.atomic.AtomicLong malformed-counter)
     :cljs @malformed-counter))

(defn reset-malformed-count!
  "Reset the malformed-stamp counter to zero. The counter is a
  process-wide singleton; tests that exercise the fail-closed path
  call this to isolate their increments from other tests' increments.
  Returns the new value (always zero)."
  []
  #?(:clj  (do (.set ^java.util.concurrent.atomic.AtomicLong malformed-counter 0)
               0)
     :cljs (do (reset! malformed-counter 0)
               0)))

(defn- bump-malformed!
  "Increment the malformed-stamp counter. Internal."
  []
  #?(:clj  (.incrementAndGet ^java.util.concurrent.atomic.AtomicLong malformed-counter)
     :cljs (swap! malformed-counter inc)))

(defn- stamp-type-tag
  "A NON-sensitive, value-free class/type tag for a malformed stamp.
  Logged in place of the raw stamp so the contract-drift warning stays
  fail-CLOSED at the log boundary: an operator sees the
  shape of the drift (`String`, `Keyword`, …) and a fixed reason, but
  never the payload, which on a serialisation bug could carry a secret.
  Returns a short type-name string — never any of the stamp's data."
  [stamp]
  #?(:clj  (or (some-> stamp class .getSimpleName) "nil")
     :cljs (cond
             (string? stamp)  "string"
             (keyword? stamp) "keyword"
             (symbol? stamp)  "symbol"
             (number? stamp)  "number"
             (map? stamp)     "map"
             (vector? stamp)  "vector"
             (seq? stamp)     "seq"
             (set? stamp)     "set"
             (coll? stamp)    "coll"
             (nil? stamp)     "nil"
             :else            "value")))

(defn- log-malformed!
  "Surface a contract-drift warning when a non-boolean truthy
  `:sensitive?` stamp arrives. The runtime contract types this slot
  as a boolean; anything else is a serialisation bug worth fixing at
  the source. Stderr / `console.warn` keep the warning out of the MCP
  wire response.

  FAIL-CLOSED AT THE LOG BOUNDARY: logs are an egress
  boundary on this privacy surface. A malformed stamp is wire-adjacent,
  untrusted data; if a serialisation bug puts a secret-bearing
  string/map/vector in `:sensitive?`, echoing it with `pr-str` would
  leak the value to stderr / the JS console even though the event was
  dropped from MCP output. So we log ONLY a value-free type tag and a
  fixed reason — never the raw stamp (matching `:rf/redacted` posture).
  The `malformed-count` counter is the quantitative observability hook;
  the type tag is the qualitative one. Neither carries the payload."
  [stamp]
  (let [tag (stamp-type-tag stamp)]
    #?(:clj  (binding [*out* *err*]
               (println (str "[re-frame.mcp-base.sensitive] WARN: non-boolean truthy "
                             ":sensitive? stamp dropped (fail-closed) — "
                             "type=" tag " value=:rf/redacted")))
       :cljs (when (and (exists? js/console) js/console.warn)
               (js/console.warn
                 (str "[re-frame.mcp-base.sensitive] non-boolean truthy "
                      ":sensitive? stamp dropped (fail-closed) — "
                      "type=" tag " value=:rf/redacted"))))))

(defn sensitive-stamp?
  "Classify a sensitive-rollup slot value. Fail-closed posture:

    - boolean `true`           ⇒ drop (the documented spec/009 path).
    - boolean `false` / nil    ⇒ pass (non-sensitive).
    - any other truthy value   ⇒ drop AND log (malformed; contract
                                  drift surfaced rather than silently
                                  leaked, AND `malformed-count` bumped).

  Public so consumers that read a DIFFERENT rollup key than the
  trace-event `:sensitive?` stamp — e.g. re-frame2-pair-mcp's epoch-record
  rollup `:rf.epoch/sensitive?` — can route their stamp
  through the SAME fail-closed classifier rather than re-deriving a
  divergent `(true? ...)` check that fails OPEN on contract drift.
  `sensitive-event?` is the convenience wrapper that reads the
  trace-event `:sensitive?` slot off a map; this is the raw value
  classifier."
  [stamp]
  (cond
    (true? stamp)  true
    (false? stamp) false
    (nil? stamp)   false
    ;; Anything else that's truthy is a contract violation. Drop and
    ;; log so the drift is visible. Falsy non-nil sentinels (none in
    ;; the runtime today) would pass — but `false` and `nil` are the
    ;; only two falsy values in Clojure, so this is exhaustive.
    :else          (do (bump-malformed!)
                       (log-malformed! stamp)
                       true)))

(defn sensitive-event?
  "Does this event carry the top-level `:sensitive? true` stamp (or a
  fail-closed malformed-truthy variant)?

  Fail-closed: the literal `true` value drops (the
  spec/009 path), AND any other truthy non-boolean value drops too
  (with a stderr warning). The `:rf/trace-event` schema types
  `:sensitive?` as a boolean (Spec 009 + Spec-Schemas); a non-boolean
  stamp is a contract violation that we surface (warning + drop)
  rather than silently treat as non-sensitive. Only explicit `false`
  / absent / nil passes."
  [ev]
  (and (map? ev)
       (sensitive-stamp? (:sensitive? ev))))

(defn strip-sensitive
  "Remove `:sensitive? true` events from `events` unless the caller has
  opted in. Returns `[kept dropped-count]`. Cheap on the common path
  (no sensitive events ⇒ identical-vector return + zero drop count).

  This is the load-bearing default-suppress filter per spec/009
  §Privacy. Apply it to any vector of trace-event-shaped maps before
  the result crosses the MCP boundary into the agent surface.

  ## Fast-path + single-classification

  The docstring promises identical-vector return when no events drop.
  The implementation honours that with a SINGLE linear pass that
  classifies each event exactly once: it builds the kept vector while
  tracking whether anything dropped, and returns the original `events`
  (identity preserved, no `filterv` allocation) when nothing dropped.

  Why exactly once matters: `sensitive-event?` is
  SIDE-EFFECTING on the malformed-truthy path — it bumps
  `malformed-count` and logs a contract-drift warning. One pass ⇒ one
  classification ⇒ one bump + one warning per malformed event, so
  `malformed-count` is a faithful per-event metric for operator
  dashboards.

  Common-path cost: one linear pass, zero retained allocation, identity
  preserved on `events`. Drop-path cost: the same single pass plus the
  kept-vector it was already building."
  [events include?]
  (cond
    include?        [events 0]
    (empty? events) [events 0]
    :else
    ;; Single classifying pass: `sensitive-event?` runs exactly once per
    ;; event (so its malformed-path side effects fire exactly once),
    ;; while `dropped?` tracks whether to allocate. Identity-preserving
    ;; fast path: if nothing dropped, return the original `events`.
    (let [n-events (count events)
          kept     (reduce (fn [acc ev]
                             (if (sensitive-event? ev)
                               acc
                               (conj! acc ev)))
                           (transient [])
                           events)
          kept     (persistent! kept)
          n        (- n-events (count kept))]
      (if (zero? n)
        [events 0]
        [kept n]))))

(defn scrub-snapshot
  "Walk a per-frame snapshot map and apply a strip-fn to every
  `:traces` / `:epochs` slice. Returns `[scrubbed-snapshot
  total-dropped]`. Non-map slices and non-snapshot inputs pass
  through unchanged.

  ## What this is — and is NOT (EP-0015)

  This is a TRACE/EPOCH sensitivity FILTER only — NOT the complete
  snapshot privacy boundary. Sensitive trace events are stripped from
  the `:traces` and `:epochs` slices, but the value-carrying slices
  `:app-db`, `:sub-cache`, and `:machines` are LEFT ALONE: the walk is
  shallow by design and does NOT descend into
  arbitrary sub-trees or redact app-db values.

  Consequently the OUTPUT is NOT already-projected full-snapshot output.
  Per EP-0015 the public privacy model is registration-owned `:sensitive`
  / `:large` classification plus centralized `project-egress` (under a
  named `:rf.egress/*` profile) at the egress boundary — app-db payload
  redaction is owned by `project-egress`, not by any write-time
  interceptor. So the CALLER's egress pipeline must run `project-egress`
  over the non-trace slices with the frame's known policy BEFORE the
  snapshot crosses an MCP/tool boundary; do not treat `scrub-snapshot`
  output as sufficient and do not look for a write-time interceptor to
  have handled it. Re-asserted by the snapshot-scrubber tests in every
  MCP that emits per-frame snapshots.

  ## Strip-fn parameter

  Two-arity form `[snapshot include?]` uses the base
  `strip-sensitive` predicate (spec/009 §Privacy stamp check).
  Three-arity form `[snapshot include? strip-fn]` accepts a custom
  strip-fn matching the `[items include?] => [kept dropped]`
  contract — re-frame2-pair-mcp passes its union predicate that also catches
  the epoch-level `:sensitive?` rollup (`sensitive-event? OR
  sensitive-epoch?`). The default arity preserves the
  trace-event-only filter for story-mcp consumers.

  ## Slice-shape discipline + fail-closed

  A `:traces` / `:epochs` slice is a SEQUENTIAL batch of trace-event maps
  (a vector in the typical runtime emission, or a lazy seq when composed
  via `concat`/`map`/`filter`). The scrubber only normalises (`vec`) and
  runs `strip-fn` on a genuinely `sequential?` slice. A non-sequential
  slice (nil, a scalar, a string, or a single event MAP) is NOT a batch,
  so it passes through UNCHANGED — honouring the documented contract
  (`Non-map slices … pass through unchanged`). Guarding on `sequential?`
  matters: an unconditional `(vec …)` would mangle these shapes
  (`nil → []`, `\"oops\" → [\\o \\o …]`, a single event map → a vector of
  map-entries), silently losing the event shape, reporting zero drops,
  AND carrying any secret in that map straight past the filter.

  Fail-CLOSED exception: a single MAP slice that the caller-supplied
  `strip-fn` classifies as sensitive is DROPPED, not passed through —
  shipping it raw would leak the very secret the filter exists to
  suppress. It is replaced with the empty batch and counted as one drop.
  Classification routes through `strip-fn` (the SAME predicate the
  sequential branch uses), NOT the base `sensitive-event?` — so a
  union-signal sensitivity the caller's predicate catches (e.g.
  re-frame2-pair-mcp's `:rf.epoch/sensitive? true` epoch rollup, carrying
  no unqualified `:sensitive?` stamp) is honoured on the single-map shape
  too."
  ([snapshot include?]
   (scrub-snapshot snapshot include? strip-sensitive))
  ([snapshot include? strip-fn]
   (if (or include? (not (map? snapshot)))
     [snapshot 0]
     ;; Pure fold: thread BOTH the scrubbed map and the running drop
     ;; count through the `reduce-kv` accumulator `[frame-map count]`
     ;; rather than side-channelling the count through a mutable cell.
     ;; `scrub-frame` returns `[scrubbed-frame dropped]` for one frame;
     ;; the slice-scrub returns `[kept dropped]` straight from `strip-fn`,
     ;; so the count rides the value the whole way down — no `volatile!`.
     ;; Single walk over the snapshot, same cost as before.
     (let [scrub-slice
           (fn [frame-map slice-key acc]
             (if-not (contains? frame-map slice-key)
               [frame-map acc]
               (let [slice (get frame-map slice-key)]
                 (cond
                   ;; Genuine sequential trace-event batch (vector or lazy
                   ;; seq): normalise + scrub. `(vec …)` realises a lazy
                   ;; seq before `strip-fn` runs.
                   (sequential? slice)
                   (let [[kept n] (strip-fn (vec slice) false)]
                     [(assoc frame-map slice-key kept) (+ acc n)])

                   ;; Fail-CLOSED: a single map that is itself a sensitive
                   ;; event is NOT a batch and would leak its secret if
                   ;; shipped raw — drop it (empty batch) and count it.
                   ;; Classify via the SAME caller-supplied `strip-fn` the
                   ;; sequential branch uses — NOT the base
                   ;; `sensitive-event?`. Otherwise a single-map slice that
                   ;; is sensitive ONLY by a union signal the caller's
                   ;; predicate catches (e.g. re-frame2-pair-mcp's epoch
                   ;; rollup `:rf.epoch/sensitive? true`, with no unqualified
                   ;; `:sensitive?` stamp) would slip past this guard to the
                   ;; `:else` arm and ship RAW — leaking past the defensive
                   ;; single-map guard.
                   (map? slice)
                   (let [[_ n] (strip-fn [slice] false)]
                     (if (pos? n)
                       [(assoc frame-map slice-key []) (inc acc)]
                       [frame-map acc]))

                   ;; Any other non-sequential shape (nil, scalar, string)
                   ;; is not a batch — pass it through byte-identically per
                   ;; the helper contract.
                   :else
                   [frame-map acc]))))
           scrub-frame
           (fn [frame-map]
             (let [[fm1 d1] (scrub-slice frame-map :traces 0)]
               (scrub-slice fm1 :epochs d1)))
           [scrubbed dropped]
           (reduce-kv
             (fn [[m total] k v]
               (if (map? v)
                 (let [[scrubbed-frame n] (scrub-frame v)]
                   [(assoc m k scrubbed-frame) (+ total n)])
                 [(assoc m k v) total]))
             [{} 0]
             snapshot)]
       [scrubbed dropped]))))
