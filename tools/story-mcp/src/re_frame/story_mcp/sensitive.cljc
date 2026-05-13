(ns re-frame.story-mcp.sensitive
  "Spec/009 §Privacy default-suppress filter for `:sensitive?` trace
  events (rf2-zq0n1, follows rf2-a32kd).

  ## Why this ns exists in story-mcp

  Spec 009 mandates that framework-published forwarders — Sentry /
  Honeybadger, pair2 server, Story-MCP, Causa-MCP — MUST default-drop
  trace events whose registration declared `:sensitive? true`. The
  runtime stamps the flag at the top level of every emitted trace
  event inside such a registration's handler scope; the forwarder's
  job is to gate egress on it before any data crosses the trust
  boundary into the agent surface.

  Story-MCP's current tool surface (`run-variant`, `preview-variant`,
  `read-failures`, ...) returns *assertion records* and the *post-run
  app-db*, not raw `:rf/trace-event` items. The literal default-drop
  filter has nothing to filter in v1: the trace stream is not part of
  any return shape. Still, this ns ships now so:

  - When a future story-mcp tool surfaces raw traces (the natural
    extension is a `play-traces` op that returns the trace events
    emitted during the play phase, mirroring pair2-mcp's `subscribe
    :trace`), the filter is already in place and the wiring is one
    `update` call.
  - The opt-in arg name (`:include-sensitive?`) is fixed
    cross-server. An agent that knows the slot on pair2-mcp gets the
    same slot on story-mcp the moment story-mcp grows a trace surface.
  - The spec/009 MUST is honoured at the artefact level — a
    code-review of story-mcp can see the filter machinery rather
    than relying on a forward reference to pair2-mcp.

  ## API

  - `sensitive-event?` — predicate. True iff the map carries
    `:sensitive? true` at the top level.
  - `strip-sensitive` — `[events include?] -> [kept dropped-count]`.
    The default-suppress filter; pass `include? true` to disable.
  - `include-sensitive?` — read the per-call opt-in arg off a tool
    arg map. Default false.

  ## Worked example (future trace surface)

  ```clojure
  (defn- tool-play-traces [args]
    (let [[vid err] (required-arg args :variant-id)
          incl?     (sensitive/include-sensitive? args)]
      (if err err
        (let [raw          (run-and-collect-traces vid)
              [kept dropped] (sensitive/strip-sensitive raw incl?)
              payload      (cond-> {:variant-id vid :traces kept}
                             (pos? dropped) (assoc :dropped-sensitive dropped))]
          (text-result (pr-edn payload) payload)))))
  ```"
  (:require [clojure.string :as str]))

(defn sensitive-event?
  "Does this event carry the top-level `:sensitive? true` stamp? The
  filter is conservative — only the literal `true` value drops; any
  other value (including a possible string-coercion via an ill-behaved
  transport) passes through. The `:rf/trace-event` schema types
  `:sensitive?` as a boolean (per spec/009 + spec/Spec-Schemas)."
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

(defn- truthy-arg?
  "Coerce an MCP arg value into a boolean. JSON booleans arrive as
  `true` / `false`; the truthy-string forms (`\"true\"`, `\"1\"`, ...)
  are accepted for parity with the `set-allow-writes!` boot-config
  surface in `re-frame.story-mcp.config`."
  [v]
  (cond
    (boolean? v) v
    (string? v)  (contains? #{"true" "1" "yes" "y" "on"}
                            (str/lower-case (str/trim v)))
    :else        (boolean v)))

(defn include-sensitive?
  "True iff the caller opted in to forwarding `:sensitive? true` events
  for this call. Default off. Reads the `:include-sensitive?` arg
  (keyword key — story-mcp tools receive keyword-keyed arg maps from
  `re-frame.story-mcp.server`'s dispatcher)."
  [args]
  (truthy-arg? (get args :include-sensitive?)))
