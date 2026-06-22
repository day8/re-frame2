(ns re-frame2-pair-mcp.tools.elision
  "Size-elision wire markers.

  One of the wire-protocol mechanisms — alongside `:rf.mcp/summary`,
  `:rf.mcp/overflow`, `:rf.mcp/diff-from`, `:rf.mcp/dedup-table`, and
  `:rf.mcp/cache-hit`. After diff-encoding
  collapses each `:db-after`, and dedup pools repeated
  subtrees, a single large slot — say a 100KB uploaded PDF base64 on
  `[:user :uploaded-pdf]` — still rides the wire verbatim. The
  framework's size-elision walker (`rf/elide-wire-value`)
  substitutes such slots with a `{:rf.size/large-elided {...}}` marker
  carrying a fetch handle (`[:rf.elision/at <path>]`). Agents drill
  back into the slot via `get-path` using the handle's path.

  ## Where in the pipeline

  Elision runs FIRST — server-side inside the eval form, where the
  frame's `[:rf.runtime/elision]` runtime-db registry is reachable. The MCP server gets
  back data that already carries `:rf.size/large-elided` markers in
  place of declared / over-threshold slots. The downstream pipeline
  (path-slicing → diff-encode → dedup → wire-cap) operates on the
  post-elision payload — cap measures post-elision bytes, so a single
  declared-large slot can't blow the cap on its own.

  ## Where it fires

  - `snapshot` tool: each frame's `:app-db` AND `:sub-cache` slices are
    run through the walker before slice-app-db-in-snapshot sees them.
    The `:sub-cache` arm pins the Tool-Pair contract that direct reads
    of `(rf/sub-cache frame-id)` MUST route through `elide-wire-value`.
  - `get-path` tool: the value at the requested path is run through
    the walker before pr-str.
  - `list-subscriptions :include-values` tool: each sub-cache entry's
    `:value` is run through the walker server-side, mirroring
    `snapshot`'s `:sub-cache` slice — the two read the same reactive
    cache source, so both MUST redact alike off-box.

  The pull-mode epoch tools (`trace-window` / `watch-epochs`) egress
  whole `:rf/epoch-record`s, not bare app-db slices — they route
  through `re-frame.core/projected-record` (the framework's single
  normative off-box-egress emission site for epoch records; see
  `re-frame2-pair-mcp.tools.epoch-egress`), NOT this per-slot walker.

  ## `:elision` MCP arg

  Boolean opt-out. Default `true`. The arg is parsed by the shared
  `re-frame2-pair-mcp.tools.args/parse-bool-arg` table.

  ## `:include-sensitive` MCP arg

  The same `:include-sensitive` flag that gates trace / epoch
  forwarding (spec/009 §Privacy) also gates whether the walker treats
  declared-sensitive slots as pass-through (`:rf.size/include-sensitive?
  true`) or substitutes them with the `:rf/redacted` sentinel
  (`:rf.size/include-sensitive? false`, the default). Off-box default
  per Tool-Pair §`Direct-read privacy posture for sub-cache and
  get-path`: sensitive slots are dropped unless the caller opts in
  explicitly. The MCP wire-key has no trailing `?` (Anthropic's
  tool-input-schema regex rejects `?`); the
  walker-option keyword `:rf.size/include-sensitive?` is a namespaced
  framework key (not on the wire) and retains the predicate `?`.

  ## Named `:rf.egress/*` profiles (EP-0015 §10)

  The direct-read surfaces (`snapshot` / `get-path` / `subscribe` /
  `read-sub` / `list-subscriptions` / `record` / `watch-until`) are an
  off-box **tool wire** — they hand live frame state to an LLM/MCP
  client. Per EP-0015 §10 the *named* boundary is the choice an egress
  surface makes, not a hand-rolled combination of `:rf.size/*` booleans.
  This MCP server's wire is the graduating consumer of
  `:rf.egress/off-box-tool`; the trusted-local `--allow-sensitive-reads`
  opt-in is the graduating consumer of `:rf.egress/local-raw`.

  The profile is resolved through the cross-MCP single source of truth —
  `re-frame.mcp-base.egress/profile-size-opts` — a pure-data mirror of the
  framework's `re-frame.projection/profile-size-opts` table that the
  mcp-conformance wire-vocab gate pins byte-identical to the framework
  enum (so the server never re-derives the mapping AND never pulls the
  framework runtime graph into the node bundle). `:rf.egress/off-box-tool`
  resolves to `{:rf.size/include-sensitive? false :rf.size/include-large?
  false :rf.size/include-digests? true}` (sensitive redacts, large elides,
  and the marker carries the structural digest a tool needs to reason
  about shape); `:rf.egress/local-raw` resolves to both inclusions true
  (the operator's deliberate raw read). The `:elision` MCP arg composes ON
  TOP as the EP-0015 §10 explicit override (a caller that turns elision off
  overlays `:rf.size/include-large? true`, keeping large content even
  under off-box-tool's floor)."
  (:require [re-frame.mcp-base.vocab :as base-vocab]
            [re-frame.mcp-base.egress :as base-egress]))

;; ---------------------------------------------------------------------------
;; Named `:rf.egress/*` profile adoption (EP-0015 §10).
;;
;; The MCP posture (the `--allow-sensitive-reads` gate + the per-call
;; `:include-sensitive` opt-in, already collapsed to a single boolean
;; `include-sensitive?` at each tool call site) selects the named
;; boundary profile; the framework resolves it to the `:rf.size/*`
;; floor. This server is the off-box-tool / local-raw graduating
;; consumer — it expresses "which boundary is this", not "which booleans".
;; ---------------------------------------------------------------------------

(defn walk-required?
  "Decide whether an AI-facing read surface MUST run the per-slot egress
  walker (`re-frame.core/elide-wire-value`) over an app-db-rooted value
  before it crosses the off-box MCP wire (EP-0015).

  The walker is the ONLY thing on the direct-read surfaces that redacts a
  frame-declared-sensitive slot to `:rf/redacted`. The walker decision is
  gated on BOTH the large-slot toggle AND the sensitive opt-in — never on
  `elision?` alone. That keeps EP-0015's two-key sensitive opt-in intact
  (`spec/015-Data-Classification.md`, `spec/Tool-Pair.md`): the large
  toggle never doubles as a sensitive bypass, so a caller under the
  trusted-local `--allow-sensitive-reads` gate that passes `:elision
  false` WITHOUT the per-call `:include-sensitive true` opt-in still
  cannot pull raw sensitive values off-box.

  Fail-CLOSED invariant: the walker runs UNLESS the caller has explicitly
  opted into BOTH raw axes — large content (`:elision false`, so
  `include-large?` is `false`) AND sensitive content (`:include-sensitive
  true`, so `include-sensitive?` is `true`). Only that deliberate
  full-raw opt-in (the `:rf.egress/local-raw` boundary) skips the walker;
  every other combination — including a bare `:elision false` — still
  walks, where `elision-opts-edn` overlays `:rf.size/include-large? true`
  (large passes) while keeping `:rf.size/include-sensitive? false`
  (sensitive redacts). When in doubt, redact.

  `include-large?` and `include-sensitive?` are the SAME walker-aligned
  booleans `elision-opts-edn` consumes (`include-large? = (not elision?)`):

  | include-large? | include-sensitive? | walk? | meaning                           |
  |----------------|--------------------|-------|-----------------------------------|
  | false          | false              | YES   | off-box default — both redact     |
  | true           | false              | YES   | `:elision false`, sensitive redacts |
  | false          | true               | YES   | sensitive passes, large elides    |
  | true           | true               | NO    | deliberate full-raw local opt-in  |

  Gate OFF forces `include-large? false` + `include-sensitive? false`, so
  this always returns `true` there — the published-build floor."
  [include-large? include-sensitive?]
  (not (and include-large? include-sensitive?)))

(defn posture->profile
  "Resolve the off-box egress POSTURE of a direct-read tool surface to a
  named `:rf.egress/*` profile (EP-0015 §10).

  `include-sensitive?` is the already-gated, already-opted-in boolean each
  tool computes (`(and (raw-state-allowed?) per-call-include-sensitive)`):

  - `false` (the published-build default, or a forgetful caller under the
    gate) ⇒ `:rf.egress/off-box-tool` — the MCP/AI tool wire. Sensitive
    redacts, large elides, structural digests on.
  - `true`  (the trusted-local operator's deliberate raw read) ⇒
    `:rf.egress/local-raw` — sensitive AND large pass through.

  Revealing sensitive data is the operator's `local-raw` act and is
  trace-visible (Spec 015 §Cross-tool visibility grain) — the
  `--allow-sensitive-reads` launch log + the per-call opt-in are the
  audit record of the reveal."
  [include-sensitive?]
  (if include-sensitive?
    :rf.egress/local-raw
    :rf.egress/off-box-tool))

(defn elision-opts-edn
  "Render the elision opts map as an EDN string for inlining into a
  CLJS eval form sent over nREPL.

  Resolution (EP-0015 §10): the egress POSTURE
  (`include-sensitive?`) names a `:rf.egress/*` profile via
  `posture->profile`; the framework's `re-frame.projection/profile-size-opts`
  resolves that profile to its `:rf.size/*` floor (the single source of
  truth — this server never re-derives the table). The `:elision` MCP arg
  composes on top as the explicit override: when `include-large?` is true
  it overlays `:rf.size/include-large? true` (a caller turning elision off
  keeps large content even under the off-box-tool floor).

  Knobs (both follow the walker-opt polarity so the helper
  is symmetric across the two `:rf.size/*` opts):

  - `include-large?`      — when true, `:rf.size/include-large?` is
                            `true` so the walker passes large slots
                            through unmodified; when false (the
                            default for elision-enabled call sites),
                            `false` so the walker substitutes the
                            `:rf.size/large-elided` marker.
  - `include-sensitive?`  — when true, the surface is the trusted-local
                            `:rf.egress/local-raw` boundary (declared-
                            sensitive slots pass through unmodified); when
                            false (the default), the off-box
                            `:rf.egress/off-box-tool` boundary (the walker
                            substitutes the `:rf/redacted` sentinel).

  Polarity note. The MCP arg `elision` is the *operator-facing* on/off
  switch (true = apply the walker = emit markers). The walker opt
  `:rf.size/include-large?` is the *walker-facing* pass-through switch
  (true = no marker). The two are inverse views of the same Boolean.
  The helper treats both opts uniformly via the profile floor + the
  explicit `include-large?` overlay; call sites compute `(not elision?)`
  once and pass `include-large?` in directly.

  Both knobs default off-box-safe per the Tool-Pair §Direct-read
  privacy posture contract — large slots elide, sensitive slots
  redact, unless the caller opts in explicitly.

  Single-arity form applies the off-box-safe default (`include-sensitive?`
  false ⇒ `:rf.egress/off-box-tool`) so call-sites that don't reveal
  sensitive data needn't spell it out."
  ([include-large?]
   (elision-opts-edn include-large? false))
  ([include-large? include-sensitive?]
   (let [profile (posture->profile include-sensitive?)
         floor   (base-egress/profile-size-opts profile)]
     ;; The `:elision` arg is the EP-0015 §10 explicit override: a caller
     ;; turning elision OFF overlays `:rf.size/include-large? true` on the
     ;; profile floor (the override wins). Sensitive inclusion stays the
     ;; profile's (local-raw true / off-box-tool false) — the gate already
     ;; decided it.
     (pr-str (cond-> floor
               include-large?
               (assoc base-vocab/include-large-opt true))))))

(defn elide-sub-value-src
  "CLJS source for a fn that walks ONE sub-cache entry's `:value` slot
  through `re-frame.core/elide-wire-value`.

  Returns a source string for an anonymous fn `(fn [entry] ...)` that
  runs `entry`'s `:value` (the subscription's current deref) through the
  walker, leaving `:query-v` / `:ref-count` untouched. A subscription
  whose value derives from a declared-sensitive app-db slot redacts to
  `:rf/redacted`; a declared-large value elides to
  `:rf.size/large-elided` — parity with `snapshot`'s `:sub-cache` slice,
  which reads the SAME reactive cache source (so both redact alike).

  `frame-edn` is the source for the `:frame` opt (a quoted keyword or a
  runtime `current-frame` call) so the walker resolves the right
  `[:rf.runtime/elision]` runtime-db registry; `elision-opts` is the rendered
  `elision-opts-edn` map threading the `--allow-sensitive-reads` gate
  through `:rf.size/include-sensitive?`.

  rf2-mtzv5m — the entry's `:query-v` is threaded into the walker as the
  `:query-v` opt so a framework route read sub (`:rf/route` /
  `:rf.route/query` / `:rf.route/params`) re-seeds the walk at its
  `[:rf.runtime/routing :current …]` storage position (via the routing-owned
  seed table `elide-wire-value` consults), redacting a `:sensitive` route
  query / param that would otherwise ride RAW (the route classification is
  re-rooted ABSOLUTE in the registry; the bare slice the sub returns would
  never match a whole-value-rooted walk)."
  [frame-edn elision-opts]
  (str "(fn [entry]"
       "  (if (contains? entry :value)"
       "    (let [opts (merge {:frame " frame-edn " :query-v (:query-v entry)} " elision-opts ")]"
       "      (update entry :value (fn [v] (re-frame.core/elide-wire-value v opts))))"
       "    entry))"))
