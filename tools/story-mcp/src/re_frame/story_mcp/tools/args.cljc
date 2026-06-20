(ns re-frame.story-mcp.tools.args
  "Story-MCP-specific argument readers + bounded-allowlist coercions
  (rf2-8yvyp, split out of the former `tools.helpers`).

  The cross-MCP keyword / boolean / int parsers live in
  `re-frame.mcp-base.args` — one canonical implementation per the
  cross-MCP factoring (rf2-vw4sq). This ns adds the story-MCP-specific
  shapes:

  - `include-sensitive?` — the operator+caller dual-gate on the
    `:include-sensitive` opt-in escape hatch.
  - `required-arg` — the missing-required-arg shape that returns an
    error-envelope rather than throwing.
  - `with-variant` / `with-variant-id` — the four-line variant-id
    prelude shared by ten handlers (see rf2-f0zxa), routing the agent-
    supplied id through the bounded variant-registry allowlist before
    coercing to a keyword (rf2-lqjbk).
  - `with-story-id` — the story-side peer of `with-variant-id`,
    resolving `:story-id` through the bounded story-registry allowlist
    (shared by `get-story` / `get-docs-markdown`).
  - `read-run-opts` — the `:substrate` / `:active-modes` /
    `:cell-overrides` reader for `run-variant` / `preview-variant` /
    `snapshot-identity`, with each kw-typed slot routed through
    `safe-keyword` against its live bounded set.

  ## Bounded-allowlist keyword resolution (rf2-lqjbk)

  A naive `(keyword raw-agent-string)` INTERNS on the JVM — a caller
  streaming unique random strings as `:variant-id`s would slowly grow
  the JVM's never-shrinking keyword table. The mitigation is to resolve
  every agent-supplied keyword id against a bounded set BEFORE coercing
  it to a keyword, using `args/safe-keyword` (which routes through
  `find-keyword` on the JVM — no intern outside the allowlist).

  For READ-side handlers (every tool in dev / docs / testing) the
  bounded set IS the live registry's id set; an id outside the registry
  can't possibly resolve to a registered handler anyway, so the strict
  gate is equivalent to 'reject what the lookup would have missed
  anyway'. The fns below take the registry-key fn as data so each
  helper site doesn't repeat the bind.

  The WRITE-side handlers (`register-variant`) are the documented
  exception: by design they extend the registry with a fresh keyword.
  Those callers intern via `args/fresh-keyword` directly, bounded by
  the operator-gated `--allow-writes` flag (the registry itself is the
  bounded set; the operator chose to open it)."
  (:require [clojure.string :as str]
            [re-frame.mcp-base.args :as args]
            [re-frame.story :as story]
            [re-frame.story-mcp.config :as config]
            [re-frame.story-mcp.tools.cljs-resolve :as cljs-resolve]
            [re-frame.story-mcp.tools.result :as result]))

(defn include-sensitive?
  "True iff the caller opted in to forwarding `:sensitive? true` records
  / app-db slots AND the operator has opened the server-side gate.
  Default off. Reads the `:include-sensitive` arg via the cross-MCP
  `args/parse-boolean` parser (so string-form booleans `\"true\"` /
  `\"yes\"` / `\"1\"` are accepted alongside the JSON `true`).

  Predicate FUNCTION name retains the `?` per the Clojure idiom; the
  wire/data KEY is `:include-sensitive` (no `?`) because Anthropic's
  Messages API rejects `?` in tool input-schema property keys
  (`^[a-zA-Z0-9_.-]{1,64}$`). The `?` belongs on the predicate, not on
  the data key.

  ## Boot-time gate (rf2-g9fje)

  The operator-only gate `config/sensitive-reads-allowed?` (set by
  `--allow-sensitive-reads`) is the outer check: when it is `false`,
  this fn always returns `false`,
  the per-call `:include-sensitive` arg is silently ignored, and
  declared-sensitive `:app-db` slots / assertion records remain
  redacted regardless of what the caller asked for. The MCP caller
  surface (`tools/list`) likewise omits `:include-sensitive` from
  the descriptor schemas when the gate is closed (see `schemas/
  with-include-sensitive`)."
  [arguments]
  (and (config/sensitive-reads-allowed?)
       (args/parse-boolean (get arguments :include-sensitive) false)))

(defn required-arg
  "Read a required argument. Returns `[value nil]` on success, or
  `[nil error-result]` on miss."
  [arguments k]
  (let [v (get arguments k)]
    (if (or (nil? v) (and (string? v) (str/blank? v)))
      [nil (result/error-result (str "Missing required argument: " (name k)))]
      [v nil])))

(defn- variant-id-set
  "Snapshot of registered variant ids — the bounded allowlist used by
  `with-variant` / `with-variant-id` / `read-run-opts`. Captured per
  call so a registration that lands between two read tools is reflected
  on the second read (the registry is logically a mutable atom; we
  re-snapshot rather than cache)."
  []
  (story/ids :variant))

(defn- cell-override-key-set
  "Bounded allowlist for `:cell-overrides` KEYS: the keys of the
  variant's EFFECTIVE-args map under the caller's `active-modes`
  (`story/resolve-args` with `:active-modes`, no cell-overrides).
  Cell-overrides exist to override an arg already present in the cell's
  effective args, so the legitimate key universe is exactly the keys the
  render will carry — a finite, registry-derived set. That universe is
  the variant's declared args UNION every arg key the active modes
  contribute, because Story's precedence merges mode args BEFORE
  cell-local overrides (`re-frame.story.args` §precedence: `… < mode-args
  < variant-args < cell-overrides`). Building the allowlist from the
  variant alone would drop a caller override for an arg introduced ONLY
  by an active mode (rf2-to3q7), so the render would show the mode value
  instead of the caller override. Used by `read-run-opts` to route each
  caller-supplied override key through `args/safe-keyword` (no JVM intern
  outside the set; rf2-3luf3). Returns `#{}` for an unresolvable variant
  with no active modes — every override key then rejects, which is the
  honest answer (nothing to override)."
  [vk active-modes]
  (set (keys (story/resolve-args vk {:active-modes active-modes}))))

(defn- safe-cell-overrides
  "Coerce a caller-supplied `:cell-overrides` map (string-keyed off the
  JSON wire after the rf2-3luf3 no-intern ingress, or keyword-keyed from
  a direct-invoke caller) into a keyword-keyed override map, routing
  every KEY through `args/safe-keyword` against `allowed` — the
  variant's declared arg-key set. A key outside the set is DROPPED (no
  intern), so an attacker cannot mint fresh keywords by streaming unique
  override keys, and a typo'd override simply doesn't apply rather than
  poisoning the keyword table. Values pass through verbatim — they are
  data, not identifiers. Non-map input yields `nil` (no overrides)."
  [overrides allowed]
  (when (map? overrides)
    (persistent!
     (reduce-kv
      (fn [acc k v]
        (if-let [kw (args/safe-keyword k allowed)]
          (assoc! acc kw v)
          acc))
      (transient {})
      overrides))))

(defn read-run-opts
  "Build the `re-frame.story/run-variant` opts map from the standard MCP
  arg slots (`:substrate`, `:active-modes`, `:cell-overrides`) for the
  resolved variant `vk`. Each slot lands only when present, so the
  resulting map is the minimal shape `run-variant` / `snapshot-identity`
  / `variant-share-url` expect.

  Shared by `tool-preview-variant`, `tool-run-variant`,
  `tool-snapshot-identity`, and `tool-variant-share-url` — all four
  advertise the same `:substrate` / `:active-modes` / `:cell-overrides`
  tuple in their descriptors (rf2-09rfpu Finding 2 closed the
  snapshot-identity gap: `:cell-overrides` is identity-bearing — it
  perturbs the snapshot `:content-hash` via the resolved
  `:effective-args`). The absent-slot-not-present rule keeps the helper
  general. All call sites resolve `vk` via `with-variant` /
  `with-variant-id` before calling this, so the variant is always known
  here.

  rf2-lqjbk: `:substrate` and each entry in `:active-modes` are
  coerced through `args/safe-keyword` against the live registry's
  bounded set — an unrecognised id surfaces as `nil` (which
  `run-variant`'s opts contract already tolerates as 'no override')
  rather than as a freshly-interned keyword. The substrates set is
  CLJS-only (the JVM-standalone deploy reads `nil`); the modes set
  is the registered-mode id set.

  rf2-3luf3: `:cell-overrides` arrives string-keyed off the JSON wire
  (the no-intern ingress no longer keywordises nested arg keys). Its
  KEYS are routed through `args/safe-keyword` against the variant's
  EFFECTIVE arg-key set under the active modes (`cell-override-key-set`)
  — a bounded, registry-derived allowlist — so an override key outside
  that set is dropped rather than interned. This is the read-side
  counterpart to the operator-gated write-body keywordisation in
  `tools.write`.

  rf2-to3q7: the active modes are coerced FIRST so the cell-override
  allowlist is derived from the EFFECTIVE args for those modes
  (`story/resolve-args` with `:active-modes`). Story's precedence merges
  mode args before cell-local overrides, so an arg key introduced only
  by an active mode is a legitimate override target; building the
  allowlist from the bare variant (as before) dropped that override and
  the render fell back to the mode value."
  [vk arguments]
  (let [substrate-set (cljs-resolve/registered-substrates-set)
        mode-set      (story/list-modes)
        active-modes  (when (some? (:active-modes arguments))
                        (into []
                              (keep #(args/safe-keyword % mode-set))
                              (:active-modes arguments)))]
    (cond-> {}
      (some? (:substrate arguments))
      (assoc :substrate (args/safe-keyword (:substrate arguments) substrate-set))

      (some? (:active-modes arguments))
      (assoc :active-modes active-modes)

      (some? (:cell-overrides arguments))
      (assoc :cell-overrides (safe-cell-overrides (:cell-overrides arguments)
                                                  (cell-override-key-set vk active-modes))))))

;; ---------------------------------------------------------------------------
;; Shared lifecycle timeout (rf2-ovmc5e)
;;
;; `run-variant` and `preview-variant` both block on the SAME
;; `story/run-variant` lifecycle via `async/deref-blocking`. The blocking
;; ceiling is a single-threaded-stdio protection (rf2-g9fje): the MCP
;; server's request loop is single-threaded, so an unbounded blocking
;; deref on one tool parks every unrelated call. Both tools MUST share the
;; same ceiling so they cannot drift by copy-paste — `preview-variant`
;; previously hard-coded a hidden 5 s constant while `run-variant`
;; advertised a tunable, capped `:timeout-ms`. One helper, one ceiling.
;; ---------------------------------------------------------------------------

(def ^:const max-timeout-ms
  "Hard ceiling on `:timeout-ms` for the lifecycle tools (`run-variant`,
  `preview-variant`). The MCP server's request loop is single-threaded —
  a lifecycle call with an unbounded `:timeout-ms` parks the loop and
  starves unrelated tool calls. 30 s matches the `:rf.http/timeout-ms`
  baseline rf2-it1cd pinned for the project's outbound HTTP fx, so an
  agent that learns one ceiling sees the same one everywhere.
  Caller-supplied values above this clamp DOWN to the ceiling rather than
  reject — a legitimate slow variant should still run, just capped."
  30000)

(def ^:const default-timeout-ms
  "Default `:timeout-ms` for the lifecycle tools when the caller omits the
  slot. 10 s — well under the 30 s ceiling, enough for the vast majority
  of variants. Kept as a separate const from `max-timeout-ms` so the
  descriptor schema can advertise both without re-spelling the literal."
  10000)

(defn resolve-timeout-ms
  "Resolve the caller-supplied `:timeout-ms` arg into the JVM blocking
  deref ceiling for a lifecycle tool. Reads the slot via the cross-MCP
  `args/parse-positive-int` parser (string-form ints accepted), defaults
  to `default-timeout-ms` when absent/unparseable, and clamps DOWN to
  `max-timeout-ms` so one slow request can't park the single-threaded
  stdio loop. Shared by `tool-run-variant` and `tool-preview-variant` so
  the two lifecycle tools can never drift in their blocking policy."
  [arguments]
  (min max-timeout-ms
       (args/parse-positive-int (:timeout-ms arguments) default-timeout-ms)))

(defn with-variant
  "Resolve `:variant-id` from `arguments` (required), resolve it against
  the registered-variants set (no JVM intern outside the allowlist —
  rf2-lqjbk), and probe `story/variant->edn` for the body. When both
  succeed, returns `(f vk body)`. Otherwise short-circuits with an
  error result:

    - Missing/blank `:variant-id` ⇒ `Missing required argument: …`
      (the shape `required-arg` emits).
    - Unregistered variant ⇒ `Variant not found: <vid>` (the raw
      caller-supplied string is echoed; we don't have a keyword form
      because the safe-keyword gate refused to intern one).

  Crystallises the four-line prelude shared by seven tool handlers
  (`preview-variant`, `get-variant`, `explain-variant`, `variant->edn`,
  `run-variant`, `snapshot-identity`, `record-as-variant`). Tools that
  tolerate unregistered variants (`read-a11y-violations`, `read-failures`,
  `unregister-variant`) reach for `with-variant-id` instead."
  [arguments f]
  (let [[vid err] (required-arg arguments :variant-id)]
    (if err
      err
      (if-let [vk (args/safe-keyword vid (variant-id-set))]
        (let [body (story/variant->edn vk)]
          (if (nil? body)
            (result/error-result (str "Variant not found: " (pr-str vk)))
            (f vk body)))
        (result/error-result (str "Variant not found: " (pr-str vid)))))))

(defn with-story-id
  "Resolve `:story-id` from `arguments` (required), resolve it against
  the registered-stories set (rf2-lqjbk — no JVM intern outside the
  allowlist), and call `(f sk)` when it resolves. Otherwise short-
  circuits with an error result:

    - Missing/blank `:story-id` ⇒ `Missing required argument: …`
      (the shape `required-arg` emits).
    - Unregistered story ⇒ `Story not found: <sid>` (the raw caller-
      supplied string is echoed; the safe-keyword gate refused to
      intern a keyword form).

  The story-side peer of `with-variant-id`. Crystallises the prelude
  shared by the two docs handlers that resolve a story id directly
  (`get-story`, `get-docs-markdown`) — both probe
  `(args/safe-keyword sid (story/ids :story))` and emit the same
  `Story not found` error on a miss."
  [arguments f]
  (let [[sid err] (required-arg arguments :story-id)]
    (if err
      err
      (if-let [sk (args/safe-keyword sid (story/ids :story))]
        (f sk)
        (result/error-result (str "Story not found: " (pr-str sid)))))))

(defn with-variant-id
  "Resolve `:variant-id` from `arguments` (required) against the
  registered-variants set (rf2-lqjbk — no JVM intern outside the
  allowlist). Returns `(f vk)` when the id resolves; otherwise an
  error result.

  Used by tools whose reads tolerate an unregistered variant
  (`read-a11y-violations`, `read-failures`, `unregister-variant`) — but per the
  rf2-lqjbk bound, an UNREGISTERED variant id can't intern through
  this surface either. The behavioural change: instead of returning
  an empty result for a never-seen id, the tool returns the same
  `Variant not found` error result `with-variant` emits. That is the
  more honest answer — if the id was never registered, there's
  nothing to read."
  [arguments f]
  (let [[vid err] (required-arg arguments :variant-id)]
    (if err
      err
      (if-let [vk (args/safe-keyword vid (variant-id-set))]
        (f vk)
        (result/error-result (str "Variant not found: " (pr-str vid)))))))
