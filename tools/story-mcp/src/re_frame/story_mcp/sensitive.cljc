(ns re-frame.story-mcp.sensitive
  "Spec/009 §Privacy default-suppress filter for `:sensitive?` trace
  events (rf2-zq0n1, follows rf2-a32kd).

  ## Cross-MCP factoring (rf2-vw4sq)

  Most of the behaviour now lives in `re-frame.mcp-base.sensitive`,
  the shared CLJC primitive consumed by every MCP server in the
  re-frame2 triplet. This namespace keeps a local alias so:

  - `sensitive-event?` binds to `re-frame.trace/sensitive?` (the
    framework primitive, per rf2-sqxjn — every consumer of
    `:sensitive?` composes against ONE check rather than
    reimplementing the five-token shape). The base's predicate is
    the same boolean check; the JVM-side alias preserves code-review
    locality with the framework dep.
  - The MCP-side helpers (`strip-sensitive`, `include-sensitive?`)
    surface the canonical shape and the arg name an agent learns
    once.

  ## What spec/009 mandates

  Framework-published forwarders — Sentry / Honeybadger, pair2 server,
  Story-MCP, Causa-MCP — MUST default-drop trace events whose
  registration declared `:sensitive? true`. The runtime stamps the
  flag at the top level of every emitted trace event inside such a
  registration's handler scope; the forwarder's job is to gate
  egress on it before any data crosses the trust boundary into the
  agent surface.

  ## Why this ns exists in story-mcp

  Story-MCP's current tool surface (`run-variant`, `preview-variant`,
  `read-failures`, ...) returns *assertion records* and the *post-run
  app-db*, not raw `:rf/trace-event` items. The literal default-drop
  filter has nothing to filter in v1: the trace stream is not part of
  any return shape. Still, this ns ships now so a future trace
  surface (the natural extension is a `play-traces` op that returns
  the trace events emitted during the play phase, mirroring
  pair2-mcp's `subscribe :trace`) inherits the filter and the arg-name
  contract automatically."
  (:require [clojure.string :as str]
            [re-frame.mcp-base.sensitive :as base]
            [re-frame.trace :as trace]))

(defn sensitive-event?
  "Does this event carry the top-level `:sensitive? true` stamp? Thin
  alias over the framework-published `re-frame.trace/sensitive?`
  predicate (re-exported as `re-frame.core/sensitive?`) — per rf2-sqxjn,
  every consumer of `:sensitive?` (Causa, Story, story-mcp, pair2-mcp,
  causa-mcp) composes against ONE framework primitive rather than
  reimplementing the five-token check."
  [ev]
  (trace/sensitive? ev))

(defn strip-sensitive
  "Remove `:sensitive? true` events from `events` unless the caller has
  opted in. Returns `[kept dropped-count]`.

  Delegates to `re-frame.mcp-base.sensitive/strip-sensitive` (the
  cross-MCP shared primitive per rf2-vw4sq). Local definition retained
  for backwards compatibility — story-mcp consumers that already
  binding-named this fn get the same shape."
  [events include?]
  (base/strip-sensitive events include?))

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
