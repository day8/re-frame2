(ns re-frame.mcp-base.sensitive
  "Spec/009 §Privacy default-suppress filter for `:sensitive?` trace
  events.

  ## What spec/009 mandates

  Framework-published forwarders — Sentry / Honeybadger, re-frame2-pair server,
  Story-MCP, Causa-MCP — MUST default-drop trace events whose
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
  story-mcp ships the renamed wire-key (rf2-y710n); re-frame2-pair-mcp still
  carries `:include-sensitive?` pending rf2-ihq4d.

  ## Why this ns is zero-dep

  re-frame2-pair-mcp is a CLJS Node bundle (no `re-frame.trace` on its
  classpath); story-mcp / causa-mcp are JVM-side and DO have the
  framework primitive available. The predicate body
  (`(and (map? ev) (sensitive-stamp? (:sensitive? ev)))`) is
  conservative and matches the spirit of `re-frame.privacy/sensitive?`.
  Per the spec the runtime always stamps the literal boolean; if a
  transport bug delivers any other truthy value (rf2-ih7g4 fail-closed
  posture) we drop the event AND log so the contract drift is
  visible.")

;; ---------------------------------------------------------------------------
;; Fail-closed predicate (rf2-ih7g4)
;; ---------------------------------------------------------------------------
;;
;; Spec/009 declares `:sensitive?` as a boolean. The previous
;; implementation matched only the literal `true` — any other truthy
;; value (string `\"true\"`, keyword `:yes`, non-empty collection,
;; number) passed through as if the event were non-sensitive. That is
;; fail-OPEN on contract drift: an upstream serialisation bug that
;; coerces the boolean into a string silently leaks every sensitive
;; event past the wire-egress filter.
;;
;; The fix is fail-CLOSED: any truthy non-boolean stamp is treated as
;; "claim to be sensitive, but malformed" — we drop AND log so the
;; drift surfaces in operator output. Only an explicit `false` /
;; absent / nil stamp passes.

#?(:clj  (defonce ^:private ^java.util.concurrent.atomic.AtomicLong
           malformed-counter
           (java.util.concurrent.atomic.AtomicLong. 0))
   :cljs (defonce ^:private malformed-counter (atom 0)))

(defn malformed-count
  "Read the count of malformed `:sensitive?` stamps observed since
  process start (or since the last `reset-malformed-count!`). The
  fail-closed posture (rf2-ih7g4) is silent on the wire — the counter
  is the operator-surface observability hook. Public so operator
  dashboards and tests can read the gate's activity.

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

(defn- log-malformed!
  "Surface a contract-drift warning when a non-boolean truthy
  `:sensitive?` stamp arrives. The runtime contract types this slot
  as a boolean; anything else is a serialisation bug worth fixing at
  the source. Stderr keeps the warning out of the MCP wire response."
  [stamp]
  #?(:clj  (binding [*out* *err*]
             (println (str "[re-frame.mcp-base.sensitive] WARN: non-boolean truthy "
                           ":sensitive? stamp dropped (fail-closed) — "
                           "type=" (some-> stamp class .getName)
                           " value=" (pr-str stamp))))
     :cljs (when (and (exists? js/console) js/console.warn)
             (js/console.warn
               (str "[re-frame.mcp-base.sensitive] non-boolean truthy "
                    ":sensitive? stamp dropped (fail-closed) — "
                    "value=" (pr-str stamp))))))

(defn- sensitive-stamp?
  "Classify a `:sensitive?` slot value. Fail-closed posture (rf2-ih7g4):

    - boolean `true`           ⇒ drop (the documented spec/009 path).
    - boolean `false` / nil    ⇒ pass (non-sensitive event).
    - any other truthy value   ⇒ drop AND log (malformed; contract
                                  drift surfaced rather than silently
                                  leaked).

  The classification is internal — callers reach `sensitive-event?`."
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

  Fail-closed (rf2-ih7g4): the literal `true` value drops (the
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

  ## Fast-path (rf2-0q30r)

  The docstring promises identical-vector return when no events drop;
  the implementation honours that by scanning first with `some` and
  only allocating a `filterv` vector when at least one match exists.
  Common-path cost: one linear `some` scan, zero allocation, identity
  preserved on `events`. Drop-path cost: the same linear scan plus
  the historical `filterv`/`count`/`count` pass — one extra walk on
  the rare branch in exchange for zero extra cost on every common
  call."
  [events include?]
  (cond
    include?                       [events 0]
    (empty? events)                [events 0]
    (not (some sensitive-event? events)) [events 0]
    :else
    (let [kept (filterv (complement sensitive-event?) events)
          n    (- (count events) (count kept))]
      [kept n])))

(defn scrub-snapshot
  "Walk a per-frame snapshot map and apply a strip-fn to every
  `:traces` / `:epochs` slice. Returns `[scrubbed-snapshot
  total-dropped]`. Non-map slices and non-snapshot inputs pass
  through unchanged.

  Per rf2-zq0n1 / rf2-3cted, sensitive trace events are stripped from
  `:traces` and `:epochs` but `:app-db`, `:sub-cache`, `:machines`
  are LEFT ALONE — app-db payload redaction is `with-redacted`'s job
  at write-time, not the forwarder's job at read-time. Re-asserted
  by the snapshot-scrubber tests in every MCP that emits per-frame
  snapshots.

  ## Strip-fn parameter (rf2-zpmmr)

  Two-arity form `[snapshot include?]` uses the base
  `strip-sensitive` predicate (spec/009 §Privacy stamp check).
  Three-arity form `[snapshot include? strip-fn]` accepts a custom
  strip-fn matching the `[items include?] => [kept dropped]`
  contract — re-frame2-pair-mcp passes its union predicate that also catches
  the epoch-level `:sensitive?` rollup (`sensitive-event? OR
  sensitive-epoch?`). The default arity preserves the
  trace-event-only filter for story-mcp / causa-mcp consumers."
  ([snapshot include?]
   (scrub-snapshot snapshot include? strip-sensitive))
  ([snapshot include? strip-fn]
   (if (or include? (not (map? snapshot)))
     [snapshot 0]
     (let [dropped (volatile! 0)
           scrub-slice
           (fn [items]
             (let [[kept n] (strip-fn (vec items) false)]
               (vswap! dropped + n)
               kept))
           scrub-frame
           (fn [frame-map]
             (cond-> frame-map
               (contains? frame-map :traces) (update :traces scrub-slice)
               (contains? frame-map :epochs) (update :epochs scrub-slice)))
           scrubbed (reduce-kv (fn [m k v]
                                 (assoc m k (if (map? v) (scrub-frame v) v)))
                               {} snapshot)]
       [scrubbed @dropped]))))
