(ns re-frame2-pair-mcp.tools.reserved-frame-guard
  "Server-side backstop: refuse a WHOLESALE (unsliced) read of a reserved
  `:rf/*` TOOL frame.

  ## Why a server guard, when the skill already steers

  The `re-frame2-pair` skill steers the agent to orient first and
  never wholesale-read a tool frame — a single read of the `:rf/xray`
  frame's entire working set (epoch ring + trace buffer + panel state)
  can blow well past 100K tokens. The skill STEERS; this guard ENFORCES
  — a stray `path: []` of `:rf/xray` can no longer blow the context
  window regardless of agent judgment. Skill steers, MCP guards.

  ## What is refused

  A WHOLESALE read of a RESERVED `:rf/*` tool frame is refused (NOT
  silently truncated). \"Wholesale\" means the agent asked for the full,
  unsliced frame state:

    - `snapshot` with `path: []` (explicit root) or `mode: :full` and no
      narrowing path, while a reserved tool frame is in scope
      (`frames :all`, or `frames` naming a reserved frame like
      `[:rf/xray]`).
    - `get-path` with `frame` naming a reserved tool frame and
      `path: []` (root).

  ## What is UNAFFECTED (the negative guards)

    - Non-reserved (user) frames at root path — a wholesale read of an
      app frame is fine; the snapshot pipeline's `:summary` default and
      the wire cap already bound it.
    - Any SLICED read of a `:rf/*` frame — a specific `path` (e.g.
      `[:rf.xray/epochs 0]`) is exactly the targeted-drill the agent is
      steered toward, so it is always allowed.
    - The DEFAULT snapshot scope (`frames :app`) — reserved tool frames
      are already excluded from it, so a default-scope read
      never reaches the guard.

  ## The reserved-frame predicate

  `reserved-tool-frame?` mirrors the runtime's predicate of the same name
  (`re-frame2-pair.runtime/reserved-tool-frame?`): the rule
  is the reserved-namespace CONVENTION (spec/Conventions.md §Reserved
  namespaces — framework-owned ids live under the single `:rf/*` root),
  NOT a hardcoded `:rf/xray` literal, so the guard holds for every `:rf/*`
  tool frame any project mounts (Xray's `:rf/xray`, an SSR slot, …). The
  SOLE carve-out is `:rf/default`, which Conventions.md names \"the
  universal default frame id\" — it shares the `:rf/*` root but IS the
  canonical APP frame, so it is never treated as a tool frame.

  This is the server-side mirror: the snapshot tool already filters the
  LIVE registry through the runtime predicate app-side, but
  the guard must refuse BEFORE the eval round-trip — a refusal, not a
  transform — so it re-states the same rule over the keyword the agent
  named. The rule is one line; the two copies can't drift because they
  are the same single convention."
  (:require [re-frame2-pair-mcp.tools.wire :as wire]))

(defn reserved-tool-frame?
  "True when `frame-id` names a framework-reserved `:rf/*` TOOL frame.

  Server-side mirror of `re-frame2-pair.runtime/reserved-tool-frame?`
  — the reserved-namespace convention (namespace = \"rf\"),
  minus the `:rf/default` carve-out (the canonical app frame). A
  non-keyword / un-namespaced id (a user's `:stories`, `:sandbox`) is an
  app frame and returns false."
  [frame-id]
  (and (keyword? frame-id)
       (= "rf" (namespace frame-id))
       (not= :rf/default frame-id)))

(defn- root-path?
  "True for an explicit root path `[]` — the wholesale `get-in db []`
  that returns the entire db. (A nil path is \"no path arg\", not root.)"
  [p]
  (and (vector? p) (empty? p)))

(defn- refusal
  "Build the `{:ok? false ...}` refusal envelope for a wholesale read of a
  reserved tool frame. Echoes the skill's wording (ops.md):
  names the frame, explains the cost, and redirects to `orient` (the
  bounded orientation op) plus a targeted slice. `frame-desc` is the human
  string for the scope being refused (a frame id like `:rf/xray`, or
  `all frames (frames \"all\")` for the sweep-everything case);
  `example-frame` is a concrete reserved frame id to embed in the
  get-path drill example."
  [frame-desc example-frame]
  {:ok?    false
   :reason :wholesale-read-of-reserved-frame
   :frame  frame-desc
   ;; Echo the ops.md wording: orient is the first read; snapshot/get-path
   ;; are drill-ins; `path: []` on a tool frame is never the move — the
   ;; reserved :rf/xray frame is Xray's entire working set (epoch ring +
   ;; trace buffer + panel state) and has overflowed past 100K tokens.
   :hint   (str "Refusing a wholesale (unsliced) read of the reserved :rf/* TOOL frame "
                frame-desc ". This is a devtool surface (e.g. Xray's :rf/xray is its "
                "entire working set — epoch ring + trace buffer + panel state) and a "
                "full read has overflowed past 100K tokens — it would blow the context "
                "window. orient is your first read (mcp__re-frame2-pair__orient {}); "
                "then drill into a known sub-tree with a TARGETED slice — "
                "get-path {frame \"" example-frame "\" path \"[:rf.xray/...]\"}, "
                "snapshot {path \"[...]\"}, or read-sub. "
                "Never path \"[]\" a tool frame.")})

(defn snapshot-refusal
  "Refusal result for a wholesale `snapshot` read that targets reserved
  tool frame(s) — or `nil` when the read is allowed.

  Refuses when BOTH hold:
    1. The read is WHOLESALE — `path` is `[]` (explicit root), or `path`
       is nil AND the global `mode` is `:full` (full unsliced slice). A
       nil path with the default `:summary` mode yields a tiny marker, so
       it is NOT wholesale and is allowed.
    2. A reserved tool frame is IN SCOPE — `frames` is `:all` (sweeps in
       every reserved frame), or `frames` is an explicit vector naming at
       least one reserved frame (e.g. `[:rf/xray]`).

  The DEFAULT scope `:app` already excludes reserved tool frames
  so it never refuses. A sliced read (non-`[]` `path`) is
  the targeted drill the agent is steered toward and is always allowed.

  `frames` is the parsed frames arg (`:app` / `:all` / a keyword vector);
  `path` is the parsed path arg (`nil` / `[]` / a key vector); `mode` is
  the parsed global mode (`:summary` / `:full`)."
  [frames path mode]
  (let [wholesale? (or (root-path? path)
                       (and (nil? path) (= :full mode)))]
    (when wholesale?
      (cond
        (= :all frames)
        (wire/err-text (refusal "all frames (frames \"all\")" ":rf/xray"))

        (and (sequential? frames) (some reserved-tool-frame? frames))
        (let [reserved (filterv reserved-tool-frame? frames)
              one      (first reserved)]
          (wire/err-text
            (refusal (pr-str (if (= 1 (count reserved)) one reserved))
                     (pr-str one))))

        :else nil))))

(defn get-path-refusal
  "Refusal result for a wholesale `get-path` read that targets a reserved
  tool frame — or `nil` when the read is allowed.

  Refuses when the resolved `frame` names a reserved tool frame AND a
  ROOT path `[]` is requested — either the singular `path` is `[]`, or
  the batch `paths` contains a `[]` element (`get-in db []` returns the
  whole db). `get-path` requires a path (a nil path is its own
  `:missing-path` usage error), and any non-empty path is a targeted
  slice — exactly the drill the agent is steered toward — so only the
  explicit root `[]` of a reserved frame is refused.

  `frame` is the resolved frame keyword (may be nil when the agent let it
  default to the operating frame — in that case the server can't know the
  resolved frame here, so this client-side guard does not fire on the
  omitted-`:frame` path). That omitted-`:frame` case is NOT an escape
  hatch: `set-operating-frame` refuses to PIN a reserved `:rf/*` tool
  frame, so the runtime's `current-frame` resolver
  can never return a reserved frame at tier 2 — an omitted-`:frame` read
  resolves to an APP frame (or refuses with `:ambiguous-frame`), never a
  tool frame. So the only way to address a reserved frame is the explicit
  `:frame` arg, which this guard sees and refuses at root path. `path` is
  the parsed singular path; `paths` is the parsed batch vector-of-paths
  (`nil` when not a batch)."
  [frame path paths]
  (when (and (reserved-tool-frame? frame)
             (or (root-path? path)
                 (and (sequential? paths) (some root-path? paths))))
    (wire/err-text (refusal (pr-str frame) (pr-str frame)))))
