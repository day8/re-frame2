(ns re-frame.mcp-base.sensitive
  "Spec/009 §Privacy default-suppress filter for `:sensitive?` trace
  events.

  ## What spec/009 mandates

  Framework-published forwarders — Sentry / Honeybadger, pair2 server,
  Story-MCP, Causa-MCP — MUST default-drop trace events whose
  registration declared `:sensitive? true`. The runtime stamps the
  flag at the top level of every emitted trace event inside such a
  registration's handler scope; the forwarder's job is to gate egress
  on it before any data crosses the trust boundary into the agent
  surface.

  ## Cross-server convention

  The opt-in arg name (`:include-sensitive?`) is the fixed, cross-
  server vocabulary an agent learns once. Every MCP tool that surfaces
  trace-like data MUST accept this arg, default it to false, and feed
  it to `strip-sensitive` (and any analogous walker that recurses
  through snapshot slices).

  ## Why this ns is zero-dep

  Pair2-mcp is a CLJS Node bundle (no `re-frame.trace` on its
  classpath); story-mcp / causa-mcp are JVM-side and DO have the
  framework primitive available. The predicate itself
  (`(and (map? ev) (true? (:sensitive? ev)))`) is conservative and
  identical to `re-frame.trace/sensitive?`; per the spec the runtime
  always stamps the literal boolean. Consumers that want to bind to
  the framework primitive (story-mcp does, for code-review locality)
  alias the surface in their own ns and delegate through here.")

(defn sensitive-event?
  "Does this event carry the top-level `:sensitive? true` stamp?

  Conservative: only the literal `true` value drops; any other value
  (including a possible string-coercion via an ill-behaved transport)
  passes through. The `:rf/trace-event` schema types `:sensitive?` as
  a boolean (Spec 009 + Spec-Schemas); any other shape is a contract
  violation we surface rather than silently treat as sensitive."
  [ev]
  (and (map? ev)
       (true? (:sensitive? ev))))

(defn strip-sensitive
  "Remove `:sensitive? true` events from `events` unless the caller has
  opted in. Returns `[kept dropped-count]`. Cheap on the common path
  (no sensitive events ⇒ identical-vector return + zero drop count).

  This is the load-bearing default-suppress filter per spec/009
  §Privacy. Apply it to any vector of trace-event-shaped maps before
  the result crosses the MCP boundary into the agent surface."
  [events include?]
  (cond
    include?         [events 0]
    (empty? events)  [events 0]
    :else
    (let [kept (filterv (complement sensitive-event?) events)
          n    (- (count events) (count kept))]
      [kept n])))

(defn scrub-snapshot
  "Walk a per-frame snapshot map and apply `strip-sensitive` to every
  `:traces` / `:epochs` slice. Returns `[scrubbed-snapshot
  total-dropped]`. Non-map slices and non-snapshot inputs pass
  through unchanged.

  Per rf2-zq0n1 / rf2-3cted, sensitive trace events are stripped from
  `:traces` and `:epochs` but `:app-db`, `:sub-cache`, `:machines`
  are LEFT ALONE — app-db payload redaction is `with-redacted`'s job
  at write-time, not the forwarder's job at read-time. Re-asserted
  by the snapshot-scrubber tests in every MCP that emits per-frame
  snapshots."
  [snapshot include?]
  (if (or include? (not (map? snapshot)))
    [snapshot 0]
    (let [dropped (volatile! 0)
          scrub-slice
          (fn [items]
            (let [[kept n] (strip-sensitive (vec items) false)]
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
      [scrubbed @dropped])))
