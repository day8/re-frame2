(ns re-frame.story-mcp.tools.args
  "Story-MCP-specific argument readers + bounded-allowlist coercions.

  The cross-MCP keyword / boolean / int parsers live in
  `re-frame.mcp-base.args` — one canonical implementation across MCPs.
  This ns adds the story-MCP-specific shapes:

  - `include-sensitive?` — the operator+caller dual-gate on the
    `:include-sensitive` opt-in escape hatch.
  - `required-arg` — the missing-required-arg shape that returns an
    error-envelope rather than throwing.
  - `with-variant` / `with-variant-id` — the four-line variant-id
    prelude shared by ten handlers, routing the agent-supplied id
    through the bounded variant-registry allowlist before coercing to
    a keyword.
  - `with-story-id` — the story-side peer of `with-variant-id`,
    resolving `:story-id` through the bounded story-registry allowlist
    (shared by `get-story` / `get-docs-markdown`).
  - `read-run-opts` — the `:substrate` / `:active-modes` /
    `:cell-overrides` reader for `run-variant` / `preview-variant` /
    `snapshot-identity`, with each kw-typed slot routed through
    `safe-keyword` against its live bounded set.
  - `require-collection` / `require-map` / `run-opts-shape-error` —
    reject a SCALAR sent for a collection-typed arg (`:tags`,
    `:active-modes`) or a non-map sent for `:cell-overrides` with a
    clean `isError` result, BEFORE any downstream reader gets a chance
    to silently walk a scalar as a sequence (a string iterates
    character-by-character) or drop it.
  - `run-opts-semantic-error` — the SEMANTIC peer of those shape
    guards: a correctly-shaped run option carrying an identifier the
    server does not know (`:active-modes` id, `:cell-overrides` KEY)
    is refused rather than dropped, so a typo cannot execute a
    different scenario than the one the agent asked for (rf2-sw1d).
    `substrate-arg-error` is the same rule on the third run-option
    slot.

  ## Bounded-allowlist keyword resolution

  A naive `(keyword raw-agent-string)` INTERNS on the JVM — a caller
  streaming unique random strings as `:variant-id`s would slowly grow
  the JVM's never-shrinking keyword table. The mitigation is to resolve
  every agent-supplied keyword id against a bounded set BEFORE coercing
  it to a keyword, using `rf.mcp-base.args/safe-keyword` (which routes through
  `find-keyword` on the JVM — no intern outside the allowlist).

  For READ-side handlers (every tool in dev / docs / testing) the
  bounded set IS the live registry's id set; an id outside the registry
  can't possibly resolve to a registered handler anyway, so the strict
  gate is equivalent to 'reject what the lookup would have missed
  anyway'. The fns below take the registry-key fn as data so each
  helper site doesn't repeat the bind.

  The WRITE-side handlers (`register-variant`) are the documented
  exception: by design they extend the registry with a fresh keyword.
  Those callers intern via `rf.mcp-base.args/fresh-keyword` directly, bounded by
  the operator-gated `--allow-writes` flag (the registry itself is the
  bounded set; the operator chose to open it)."
  (:require [clojure.string :as str]
            [re-frame.mcp-base.args :as rf.mcp-base.args]
            [re-frame.story :as rf.story]
            [re-frame.story-mcp.config :as rf.story-mcp.config]
            [re-frame.story-mcp.tools.cljs-resolve :as rf.story-mcp.tools.cljs-resolve]
            [re-frame.story-mcp.tools.result :as rf.story-mcp.tools.result]))

(defn include-sensitive?
  "True iff the caller opted in to forwarding `:sensitive? true` records
  / app-db slots AND the operator has opened the server-side gate.
  Default off. Reads the `:include-sensitive` arg via the cross-MCP
  `rf.mcp-base.args/parse-boolean` parser (so string-form booleans `\"true\"` /
  `\"yes\"` / `\"1\"` are accepted alongside the JSON `true`).

  Predicate FUNCTION name retains the `?` per the Clojure idiom; the
  wire/data KEY is `:include-sensitive` (no `?`) because Anthropic's
  Messages API rejects `?` in tool input-schema property keys
  (`^[a-zA-Z0-9_.-]{1,64}$`). The `?` belongs on the predicate, not on
  the data key.

  ## Boot-time gate

  The operator-only gate `rf.story-mcp.config/sensitive-reads-allowed?` (set by
  `--allow-sensitive-reads`) is the outer check: when it is `false`,
  this fn always returns `false`,
  the per-call `:include-sensitive` arg is silently ignored, and
  declared-sensitive `:app-db` slots / assertion records remain
  redacted regardless of what the caller asked for. The MCP caller
  surface (`tools/list`) likewise omits `:include-sensitive` from
  the descriptor schemas when the gate is closed (see `schemas/
  with-include-sensitive`)."
  [arguments]
  (and (rf.story-mcp.config/sensitive-reads-allowed?)
       (rf.mcp-base.args/parse-boolean (get arguments :include-sensitive) false)))

(defn required-arg
  "Read a required argument. Returns `[value nil]` on success, or
  `[nil error-result]` on miss."
  [arguments k]
  (let [v (get arguments k)]
    (if (or (nil? v) (and (string? v) (str/blank? v)))
      [nil (rf.story-mcp.tools.result/error-result (str "Missing required argument: " (name k)))]
      [v nil])))

(defn- variant-id-set
  "Snapshot of registered variant ids — the bounded allowlist used by
  `with-variant` / `with-variant-id` / `read-run-opts`. Captured per
  call so a registration that lands between two read tools is reflected
  on the second read (the registry is logically a mutable atom; we
  re-snapshot rather than cache)."
  []
  (rf.story/ids :variant))

(defn- cell-override-key-set
  "Bounded allowlist for `:cell-overrides` KEYS: the keys of the
  variant's EFFECTIVE-args map under the caller's `active-modes`
  (`rf.story/resolve-args` with `:active-modes`, no cell-overrides).
  Cell-overrides exist to override an arg already present in the cell's
  effective args, so the legitimate key universe is exactly the keys the
  render will carry — a finite, registry-derived set. That universe is
  the variant's declared args UNION every arg key the active modes
  contribute, because Story's precedence merges mode args BEFORE
  cell-local overrides (`re-frame.story.args` §precedence: `… < mode-args
  < variant-args < cell-overrides`). Building the allowlist from the
  variant alone would drop a caller override for an arg introduced ONLY
  by an active mode, so the render would show the mode value instead of
  the caller override. Used by `read-run-opts` to route each
  caller-supplied override key through `rf.mcp-base.args/safe-keyword` (no JVM intern
  outside the set). Returns `#{}` for an unresolvable variant
  with no active modes — every override key then rejects, which is the
  honest answer (nothing to override)."
  [vk active-modes]
  (set (keys (rf.story/resolve-args vk {:active-modes active-modes}))))

(defn- safe-cell-overrides
  "Coerce a caller-supplied `:cell-overrides` map (string-keyed off the
  JSON wire via the no-intern ingress, or keyword-keyed from a
  direct-invoke caller) into a keyword-keyed override map, routing
  every KEY through `rf.mcp-base.args/safe-keyword` against `allowed` — the
  variant's declared arg-key set. A key outside the set is DROPPED (no
  intern), so an attacker cannot mint fresh keywords by streaming unique
  override keys. Values pass through verbatim — they are data, not
  identifiers. Non-map input yields `nil` (no overrides).

  The drop is DEFENCE IN DEPTH, not the boundary contract. This
  docstring used to add 'a typo'd override simply doesn't apply', which
  described the behaviour accurately but was the wrong answer at an
  agent boundary: the three handlers now refuse an unknown override key
  up front via `run-opts-semantic-error` (rf2-sw1d), so a typo returns
  `isError` naming the key rather than a successful run for a reduced
  tuple. By the time this coercer sees a map from a handler, every key
  has already resolved; the drop survives only so a direct-invoke caller
  that skips the guard still cannot intern."
  [overrides allowed]
  (when (map? overrides)
    (persistent!
     (reduce-kv
      (fn [acc k v]
        (if-let [kw (rf.mcp-base.args/safe-keyword k allowed)]
          (assoc! acc kw v)
          acc))
      (transient {})
      overrides))))

(defn require-collection
  "Guard a collection-shaped MCP arg against a caller-supplied SCALAR.
  MCP JSON arrays wire-decode to Clojure vectors; a malformed client
  that sends a bare scalar (a JSON string / number / boolean) for an
  array-typed slot would otherwise be silently walked as a sequence by
  `into` / `keep` / `seq` downstream — a string in particular iterates
  CHARACTER BY CHARACTER (`(seq \"abc\")` => `(\\a \\b \\c)`), producing
  a wrong-but-successful result instead of a clean rejection.

  Returns nil when `k` is absent from `arguments` or its value is
  already a collection (`coll?` — vector, list, or set all pass);
  otherwise an `isError` result naming the offending arg with a stable
  `:rf.error/scalar-for-collection-arg` id.

  Shared by every array-typed MCP arg (`list-stories`'s `:tags`,
  `run-opts-shape-error`'s `:active-modes`) so a scalar is rejected
  identically everywhere."
  [arguments k]
  (let [v (get arguments k)]
    (when (and (some? v) (not (coll? v)))
      (rf.story-mcp.tools.result/error-result
        (str ":" (name k) " must be an array; got a scalar "
             (some-> v class .getName) " (" (pr-str v) ").")
        {:rf.error :rf.error/scalar-for-collection-arg
         :arg      (keyword k)}))))

(defn require-map
  "Guard a map-shaped MCP arg (`:cell-overrides`) against a
  caller-supplied non-map — a scalar OR a sequential/set collection.
  Same rationale as `require-collection`: without this gate a non-map
  `:cell-overrides` is silently DROPPED by `safe-cell-overrides`'s
  `(when (map? overrides) ...)` guard (no overrides applied, no error
  surfaced) rather than rejected.

  Returns nil when `k` is absent from `arguments` or its value is
  already a map; otherwise an `isError` result naming the offending
  arg with a stable `:rf.error/non-map-arg` id."
  [arguments k]
  (let [v (get arguments k)]
    (when (and (some? v) (not (map? v)))
      (rf.story-mcp.tools.result/error-result
        (str ":" (name k) " must be an object; got "
             (some-> v class .getName) " (" (pr-str v) ").")
        {:rf.error :rf.error/non-map-arg
         :arg      (keyword k)}))))

(defn run-opts-shape-error
  "Validate the run-opts-bearing slots (`:active-modes` array-shaped,
  `:cell-overrides` map-shaped) BEFORE `read-run-opts` reads them.
  Shared by every `read-run-opts` call site (`preview-variant`,
  `run-variant`, `snapshot-identity`) so a scalar sent for either slot
  is rejected identically everywhere: without this gate, a scalar
  `:active-modes` string is walked character-by-character by
  `read-run-opts`'s `into`/`keep` (each character probed against the
  mode allowlist and silently dropped, producing a confusing empty
  `:active-modes []` instead of an error), and a scalar
  `:cell-overrides` is silently dropped by `safe-cell-overrides`.

  Returns nil when both slots are well-shaped (or absent); otherwise
  the first offending arg's `isError` result. Callers short-circuit on
  a non-nil return the same way they already short-circuit on
  `with-variant` / `required-arg` errors."
  [arguments]
  (or (require-collection arguments :active-modes)
      (require-map arguments :cell-overrides)))

(defn substrate-arg-error
  "Validate an EXPLICITLY-supplied `:substrate` for `tool-name` BEFORE its
  handler reads run-opts (`run-variant`, `preview-variant`,
  `snapshot-identity`). Returns nil when `:substrate` is absent (the
  legitimate default-substrate path) or resolves against a REACHED
  substrate registry; otherwise an `isError` result. Never returns a
  coerced value — it only rejects; `read-run-opts` still performs the
  no-intern `safe-keyword` coercion for the honoured path.

  Two rejection modes (rf2-3fc89f.21 — never silently drop the request to
  `:substrate nil`):

    - substrate capability UNREACHABLE (the JVM stdio host has no browser
      bridge to the CLJS `register-substrate!` registry) and a
      `:substrate` was supplied ⇒ the requested render substrate cannot
      be honoured here, so return the shared capability-unavailable error
      (a run under a silently-dropped substrate would be invalid
      substrate-specific evidence).
    - capability REACHED but the requested id is not in the bounded
      registry ⇒ an unknown-substrate error naming the registered set.
      The probe is `safe-keyword` against the registry set, so a hostile
      name still interns no JVM keyword.

  Absent `:substrate` never trips either branch — the default substrate
  is a legitimate no-override path independent of provider reachability."
  [arguments tool-name]
  (let [raw (:substrate arguments)]
    (when (some? raw)
      (if-not (rf.story-mcp.tools.cljs-resolve/substrate-provider-available?)
        (rf.story-mcp.tools.result/capability-unavailable-result
          {:tool       tool-name
           :capability "substrate-registry"
           :detail     (str "An explicit :substrate " (pr-str raw) " was requested, "
                            "but no substrate registry is reachable from this host — "
                            "the request cannot be honoured and is NOT silently "
                            "dropped to nil.")})
        (when-not (rf.mcp-base.args/safe-keyword raw (rf.story-mcp.tools.cljs-resolve/registered-substrates-set))
          (rf.story-mcp.tools.result/error-result
            (str "Unknown substrate: " (pr-str raw) ". Registered substrates: "
                 (pr-str (vec (rf.story-mcp.tools.cljs-resolve/registered-substrates))) ".")
            {:rf.error   :rf.error/story-mcp-unknown-substrate
             :substrate  (str raw)
             :registered (vec (rf.story-mcp.tools.cljs-resolve/registered-substrates))}))))))

(defn- raw-id-str
  "Render a caller-supplied identifier for a diagnostic WITHOUT interning
  it. A string is echoed exactly as the agent wrote it (so the agent can
  match the rejected token against its own call); anything else (a number
  / boolean / nil sent for a keyword-typed slot) is rendered in read
  syntax so a type confusion is visible rather than stringified into a
  look-alike. Never calls `keyword`."
  [v]
  (if (string? v) v (pr-str v)))

(defn- unresolved-ids
  "The subset of `raws` that does NOT resolve against the bounded set
  `allowed`, as raw display strings (via `raw-id-str` — no intern).
  The probe is `rf.mcp-base.args/safe-keyword`, the SAME resolver the
  coercer uses, so the guard and `read-run-opts` can never disagree about
  what counts as known. Order is the caller's, so the diagnostic lists
  the offending ids in the order they were supplied."
  [raws allowed]
  (into [] (comp (remove #(some? (rf.mcp-base.args/safe-keyword % allowed)))
                 (map raw-id-str))
        raws))

(defn run-opts-semantic-error
  "Reject an UNKNOWN identifier in the semantic run-option slots
  (`:active-modes` ids, `:cell-overrides` KEYS) for the resolved variant
  `vk`, instead of executing a different scenario than the one asked for.

  Shape validation is NOT this function's job — call
  `run-opts-shape-error` first (and `substrate-arg-error` for the third
  run-option slot) and short-circuit on a non-nil result. Like
  `read-run-opts`, this assumes `:active-modes` (when present) is already
  a collection and `:cell-overrides` (when present) already a map.

  Returns nil when every supplied identifier resolves (or both slots are
  absent — an ABSENT option legitimately defaults); otherwise an
  `isError` result naming the offending raw ids and the accepted set.

  ## Why this refuses rather than drops (rf2-sw1d)

  `read-run-opts` resolves both slots through the bounded-allowlist
  `safe-keyword` gate and DROPS what misses, which is the correct
  no-intern posture but the wrong ANSWER at an agent boundary: the drop
  is silent, so a typo'd or stale mode / override key yields a
  `:status :pass`, a share URL, or a visual-regression `:content-hash`
  for a DIFFERENT tuple than the caller requested. Both slots are
  semantically material — `spec/API.md` records `:cell-overrides` as
  identity-bearing, and `snapshot-identity`'s own descriptor says a
  mode / override change produces a different hash — so a silently
  different run is false evidence at precisely the automation boundary
  Story-MCP exists to make trustworthy. A rejected call costs the agent
  one round trip; a silently different run costs it the whole chain of
  inference built on the result.

  This is the same rule the boundary already applies on its other
  identifier axes — unknown top-level argument keys
  (`:rf.story-mcp/unknown-arguments`), an unknown `:variant-id` /
  `:story-id`, an unknown `:substrate` (`substrate-arg-error`) and an
  unknown decorator `:kind` are all refused, each naming the accepted
  set — extended to the two nested slots that still dropped. It does NOT
  change Story's own runtime resolution, which stays deliberately
  tolerant so the UI shell and play runner can ignore a stale persisted
  mode (`re-frame.story.args/mode-args`, whose contract explicitly
  delegates the diagnosis here: *\"tools surface the mismatch as a
  validation warning\"*). Passive hydration may discard stale state; an
  explicit agent command must not certify another tuple.

  ## Ordering — modes first, atomically

  Modes are validated FIRST and as a whole: a mixed known+unknown list
  rejects rather than running the known subset, because a partial mode
  set is exactly the 'different scenario' this guards against. Only once
  every mode resolves is the cell-override allowlist derived, because
  that allowlist is the variant's EFFECTIVE arg keys UNDER those modes
  (`cell-override-key-set`) — Story merges mode args before cell-local
  overrides, so an arg introduced only by an active mode is a legitimate
  override target. Deriving it from an unvalidated mode list would
  reject a good override key on the strength of a bad mode id.

  ## Accepted sets are DERIVED, never a second copy

  Both sets are read live at error time from the same sources the
  coercer uses — `rf.story/list-modes` for modes,
  `cell-override-key-set` for override keys — so the diagnostic cannot
  drift from the allowlist actually enforced. Nothing here hard-codes a
  roster.

  ## No-intern invariant preserved

  Unknown ids are reported as RAW strings via `raw-id-str`. The
  membership probe is `safe-keyword`, which resolves through
  `find-keyword` on the JVM, so a hostile caller streaming unique
  identifiers still interns nothing — the security posture that
  introduced the drop is kept; only the success is withdrawn."
  [arguments vk]
  (let [raw-modes     (:active-modes arguments)
        modes?        (some? raw-modes)
        mode-set      (rf.story/list-modes)
        unknown-modes (when modes? (unresolved-ids raw-modes mode-set))]
    (if (seq unknown-modes)
      (rf.story-mcp.tools.result/error-result
        (str "Unknown :active-modes id(s): " (pr-str unknown-modes)
             ". Registered modes: " (pr-str (mapv str (sort mode-set)))
             ". (Call list-modes for the current set. A present-but-unknown mode is"
             " REFUSED, not ignored — running the known subset would execute a"
             " different scenario than the one requested. Omit :active-modes"
             " entirely to render under no mode.)")
        {:rf.error     :rf.error/story-mcp-unknown-active-mode
         :active-modes unknown-modes
         :registered   (mapv str (sort mode-set))})
      ;; Every supplied mode resolved — derive the override allowlist
      ;; UNDER those modes, then apply the same rule to its keys.
      (let [active-modes  (when modes?
                            (into [] (keep #(rf.mcp-base.args/safe-keyword % mode-set)) raw-modes))
            raw-overrides (:cell-overrides arguments)
            allowed       (when (map? raw-overrides) (cell-override-key-set vk active-modes))
            unknown-keys  (when (map? raw-overrides) (unresolved-ids (keys raw-overrides) allowed))]
        (when (seq unknown-keys)
          (rf.story-mcp.tools.result/error-result
            (str "Unknown :cell-overrides key(s): " (pr-str unknown-keys)
                 " for variant " (pr-str vk)
                 (when (seq active-modes) (str " under active modes " (pr-str (mapv str active-modes))))
                 ". Overridable arg keys here: " (pr-str (mapv str (sort allowed)))
                 ". (A cell override may only override an arg already present in the"
                 " cell's effective args — read them from preview-variant's"
                 " :effective-args. A present-but-unknown key is REFUSED, not dropped:"
                 " running or hashing without it would answer for a different tuple.)")
            {:rf.error       :rf.error/story-mcp-unknown-cell-override-key
             :cell-overrides unknown-keys
             :variant-id     (str vk)
             :active-modes   (mapv str (or active-modes []))
             :allowed        (mapv str (sort allowed))}))))))

(defn read-run-opts
  "Build the `re-frame.story/run-variant` opts map from the standard MCP
  arg slots (`:substrate`, `:active-modes`, `:cell-overrides`) for the
  resolved variant `vk`. Each slot lands only when present, so the
  resulting map is the minimal shape `run-variant` / `snapshot-identity`
  / `variant-share-url` expect.

  VALIDATION is NOT this function's job — it is a pure coercer. Call
  `run-opts-shape-error` (shape), `substrate-arg-error` and
  `run-opts-semantic-error` (unknown identifiers, rf2-sw1d) first and
  short-circuit on a non-nil result. This function assumes
  `:active-modes` (when present) is already a collection and
  `:cell-overrides` (when present) is already a map; fed a scalar,
  `:active-modes` silently degrades (a string iterates
  character-by-character) rather than erroring. Every handler runs the
  full guard chain, so in production every id reaching here already
  resolved — the drops below are the retained no-intern floor for a
  direct-invoke caller, not the boundary's answer to a typo.

  Shared by the `preview-variant`, `run-variant`, and
  `snapshot-identity` handlers — the same set `run-opts-shape-error`
  gates — each advertising the same `:substrate` / `:active-modes` /
  `:cell-overrides` tuple in its descriptor. (`preview-variant` also
  threads the resulting opts into `rf.story/variant-share-url`; that is a
  Story call inside the handler, not a fourth tool.)
  `:cell-overrides` is identity-bearing for
  `snapshot-identity` — it perturbs the snapshot `:content-hash` via the
  resolved `:effective-args`. The absent-slot-not-present rule keeps the
  helper general. All call sites resolve `vk` via `with-variant` /
  `with-variant-id` before calling this, so the variant is always known
  here.

  `:substrate` and each entry in `:active-modes` are coerced through
  `rf.mcp-base.args/safe-keyword` against the live registry's bounded set — an
  unrecognised id surfaces as `nil` (which `run-variant`'s opts contract
  tolerates as 'no override') rather than as a freshly-interned keyword.
  The substrate registry is CLJS-only, so on a JVM-standalone deploy
  `rf.story-mcp.tools.cljs-resolve/registered-substrates-set` normalises the absent provider
  to `#{}` — never `nil`. That empty allowlist is not this function's
  refusal boundary: an EXPLICIT `:substrate` against an unreachable
  provider is rejected upstream by `substrate-arg-error`, which gates on
  `rf.story-mcp.tools.cljs-resolve/substrate-provider-available?`. The modes set is the
  registered-mode id set.

  `:cell-overrides` arrives string-keyed off the JSON wire (the
  no-intern ingress leaves nested arg keys as strings). Its KEYS are
  routed through `rf.mcp-base.args/safe-keyword` against the variant's EFFECTIVE
  arg-key set under the active modes (`cell-override-key-set`) — a
  bounded, registry-derived allowlist — so an override key outside that
  set is dropped rather than interned. This is the read-side counterpart
  to the operator-gated write-body keywordisation in `tools.write`.

  The active modes are coerced FIRST so the cell-override allowlist is
  derived from the EFFECTIVE args for those modes (`rf.story/resolve-args`
  with `:active-modes`). Story's precedence merges mode args before
  cell-local overrides, so an arg key introduced only by an active mode
  is a legitimate override target, and deriving the allowlist from the
  effective args keeps that override applicable."
  [vk arguments]
  (let [substrate-set (rf.story-mcp.tools.cljs-resolve/registered-substrates-set)
        mode-set      (rf.story/list-modes)
        active-modes  (when (some? (:active-modes arguments))
                        (into []
                              (keep #(rf.mcp-base.args/safe-keyword % mode-set))
                              (:active-modes arguments)))]
    (cond-> {}
      (some? (:substrate arguments))
      (assoc :substrate (rf.mcp-base.args/safe-keyword (:substrate arguments) substrate-set))

      (some? (:active-modes arguments))
      (assoc :active-modes active-modes)

      (some? (:cell-overrides arguments))
      (assoc :cell-overrides (safe-cell-overrides (:cell-overrides arguments)
                                                  (cell-override-key-set vk active-modes))))))

;; ---------------------------------------------------------------------------
;; Shared lifecycle timeout
;;
;; `run-variant` and `preview-variant` both block on the SAME
;; `rf.story/run-variant` lifecycle via `async/deref-blocking`. The blocking
;; ceiling is a single-threaded-stdio protection: the MCP server's
;; request loop is single-threaded, so an unbounded blocking deref on one
;; tool parks every unrelated call. Both tools share the same ceiling
;; through one helper so they cannot drift by copy-paste — both advertise
;; the same tunable, capped `:timeout-ms`. One helper, one ceiling.
;; ---------------------------------------------------------------------------

(def ^:const max-timeout-ms
  "Hard ceiling on `:timeout-ms` for the lifecycle tools (`run-variant`,
  `preview-variant`). The MCP server's request loop is single-threaded —
  a lifecycle call with an unbounded `:timeout-ms` parks the loop and
  starves unrelated tool calls. 30 s matches the `:rf.http/timeout-ms`
  baseline for the project's outbound HTTP fx, so an agent that learns
  one ceiling sees the same one everywhere.
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
  "Resolve the caller-supplied `:timeout-ms` arg into the END-TO-END
  lifecycle deadline for a lifecycle tool. Reads the slot via the
  cross-MCP `rf.mcp-base.args/parse-positive-int` parser (string-form ints accepted),
  defaults to `default-timeout-ms` when absent/unparseable, and clamps
  DOWN to `max-timeout-ms` so one slow request can't park the
  single-threaded stdio loop. Shared by `tool-run-variant` and
  `tool-preview-variant` so the two lifecycle tools can never drift in
  their blocking policy.

  The deadline this resolves is enforced by `tools.lifecycle/
  run-variant-blocking` over the SYNCHRONOUS Story work (a JVM
  `[:wait]` is an inline `Thread/sleep`), not just the post-return
  dereference — see that fn's docstring (rf2-j538f7.31)."
  [arguments]
  (min max-timeout-ms
       (rf.mcp-base.args/parse-positive-int (:timeout-ms arguments) default-timeout-ms)))

(defn with-variant
  "Resolve `:variant-id` from `arguments` (required), resolve it against
  the registered-variants set (no JVM intern outside the allowlist), and
  probe `rf.story/variant->edn` for the body. When both
  succeed, returns `(f vk body)`. Otherwise short-circuits with an
  error result:

    - Missing/blank `:variant-id` ⇒ `Missing required argument: …`
      (the shape `required-arg` emits).
    - Unregistered variant ⇒ `Variant not found: <vid>` (the raw
      caller-supplied string is echoed; we don't have a keyword form
      because the safe-keyword gate refused to intern one).

  Crystallises the four-line prelude shared by six tool handlers
  (`preview-variant`, `get-variant`, `explain-variant`, `variant->edn`,
  `run-variant`, `snapshot-identity`). Tools that
  tolerate a registered-but-never-run variant (`read-a11y-violations`,
  `read-failures`, `unregister-variant`) reach for `with-variant-id`
  instead."
  [arguments f]
  (let [[vid err] (required-arg arguments :variant-id)]
    (if err
      err
      (if-let [vk (rf.mcp-base.args/safe-keyword vid (variant-id-set))]
        (let [body (rf.story/variant->edn vk)]
          (if (nil? body)
            (rf.story-mcp.tools.result/error-result (str "Variant not found: " (pr-str vk)))
            (f vk body)))
        (rf.story-mcp.tools.result/error-result (str "Variant not found: " (pr-str vid)))))))

(defn with-story-id
  "Resolve `:story-id` from `arguments` (required), resolve it against
  the registered-stories set (no JVM intern outside the allowlist), and
  call `(f sk)` when it resolves. Otherwise short-
  circuits with an error result:

    - Missing/blank `:story-id` ⇒ `Missing required argument: …`
      (the shape `required-arg` emits).
    - Unregistered story ⇒ `Story not found: <sid>` (the raw caller-
      supplied string is echoed; the safe-keyword gate refused to
      intern a keyword form).

  The story-side peer of `with-variant-id`. Crystallises the prelude
  shared by the two docs handlers that resolve a story id directly
  (`get-story`, `get-docs-markdown`) — both probe
  `(rf.mcp-base.args/safe-keyword sid (rf.story/ids :story))` and emit the same
  `Story not found` error on a miss."
  [arguments f]
  (let [[sid err] (required-arg arguments :story-id)]
    (if err
      err
      (if-let [sk (rf.mcp-base.args/safe-keyword sid (rf.story/ids :story))]
        (f sk)
        (rf.story-mcp.tools.result/error-result (str "Story not found: " (pr-str sid)))))))

(defn with-variant-id
  "Resolve `:variant-id` from `arguments` (required) against the
  registered-variants set (no JVM intern outside the allowlist).
  Returns `(f vk)` when the id resolves; otherwise an error result.

  Used by tools whose reads tolerate a REGISTERED-but-never-RUN variant
  (`read-a11y-violations`, `read-failures`, `unregister-variant`): the id
  must still be registered (safe-keyword resolves it against the live
  variant set), but it may carry no recorded run/body yet, and the
  handler answers vacuously (e.g. `read-failures` returns an empty
  accumulator). It does NOT tolerate a genuinely UNREGISTERED id — the
  no-intern bound can't resolve one, so a never-registered id returns the
  same `Variant not found` error result `with-variant` emits, the honest
  answer since there's nothing to read."
  [arguments f]
  (let [[vid err] (required-arg arguments :variant-id)]
    (if err
      err
      (if-let [vk (rf.mcp-base.args/safe-keyword vid (variant-id-set))]
        (f vk)
        (rf.story-mcp.tools.result/error-result (str "Variant not found: " (pr-str vid)))))))
