(ns re-frame2-pair-mcp.tools.elision
  "Size-elision wire markers.

  One of the wire-protocol mechanisms — alongside `:rf.mcp/summary`,
  `:rf.mcp/overflow`, `:rf.mcp/diff-from`, `:rf.mcp/dedup-table`, and
  `:rf.mcp/cache-hit`. After diff-encoding
  collapses each `:db-after`, and dedup pools repeated
  subtrees, a single large slot — say a 100KB uploaded PDF base64 on
  `[:user :uploaded-pdf]` — still rides the wire verbatim. The
  framework's egress door (`rf/project-egress`)
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
    run through the door before slice-app-db-in-snapshot sees them.
    The `:sub-cache` arm pins the Tool-Pair contract that direct reads
    of `(rf/sub-cache frame-id)` MUST route through `project-egress`.
  - `get-path` tool: the value at the requested path is run through
    the door before pr-str.
  - `list-subscriptions :include-values` tool: each sub-cache entry's
    `:value` is run through the door server-side, mirroring
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

  The direct-read surfaces (`snapshot` / `get-path` /
  `read-sub` / `list-subscriptions` / `record` / `watch-until`) are an
  off-box **tool wire** — they hand live frame state to an LLM/MCP
  client. Per EP-0015 §10 the *named* boundary is the choice an egress
  surface makes, not a hand-rolled combination of `:rf.size/*` booleans.
  This MCP server's wire is the graduating consumer of
  `:rf.egress/off-box-tool`; the trusted-local `--allow-sensitive-reads`
  opt-in is the graduating consumer of `:rf.egress/local-raw`.

  The server NAMES the profile; the app-side door resolves it
  (rf2-kuky.88). Every rendered eval form calls
  `re-frame.core/project-egress` with an `:rf.egress/profile` chosen by
  the shared cross-MCP posture mapping
  `re-frame.mcp-base.egress/mcp-tool-profile`, and `project-egress`
  resolves that NAME to the framework's own §10 `:rf.size/*` floor inside
  the app runtime, where the framework graph is already loaded. That is
  what keeps the resolution table out of the Node bundle WITHOUT a second
  copy of it: the server ships a keyword, not a policy.
  `:rf.egress/off-box-tool` redacts sensitive, elides large, and carries
  the structural digest a tool needs to reason about shape;
  `:rf.egress/local-raw` opts both inclusions back in (the operator's
  deliberate raw read). The `:elision` MCP arg composes ON TOP as the
  EP-0015 §10 explicit override (a caller that turns elision off overlays
  `:rf.size/include-large? true`, keeping large content even under
  off-box-tool's floor)."
  (:require [re-frame.mcp-base.vocab :as rf.mcp-base.vocab]
            [re-frame.mcp-base.egress :as rf.mcp-base.egress]))

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

(defn egress-opts-edn
  "Render the direct-read egress opts as an EDN string for inlining into a
  CLJS eval form sent over nREPL.

  ## Named-egress profile, resolved app-side (rf2-kuky.88)

  The egress POSTURE (`include-sensitive?`) names an `:rf.egress/*`
  profile via the SHARED cross-MCP mapping
  `re-frame.mcp-base.egress/mcp-tool-profile` (called here only AFTER this
  server's `--allow-sensitive-reads` gate + per-call opt-in). The NAME is
  what rides the wire; `re-frame.core/project-egress` resolves it to the
  framework's own §10 `:rf.size/*` floor inside the app runtime. This
  server neither carries nor consults a resolution table — that mirror was
  deleted with this bead, because a server that names a boundary has no
  reason to resolve one, and the mirror was a second place the §10 table
  could drift.

  The `:elision` MCP arg composes on top as the EP-0015 §10 explicit
  override: when `include-large?` is true it overlays
  `:rf.size/include-large? true`, so a caller turning elision off keeps
  large content even under the off-box-tool floor. The override wins over
  the profile (projection.cljc §`resolve-elision-opts`).

  Knobs:

  - `include-large?`      — when true, overlay `:rf.size/include-large?
                            true` so the walk passes large slots through
                            unmodified; when false (the default for
                            elision-enabled call sites) the profile floor
                            stands and the `:rf.size/large-elided` marker
                            is substituted.
  - `include-sensitive?`  — when true, the surface is the trusted-local
                            `:rf.egress/local-raw` boundary (declared-
                            sensitive slots pass through unmodified); when
                            false (the default), the off-box
                            `:rf.egress/off-box-tool` boundary (the
                            `:rf/redacted` sentinel is substituted).

  Polarity note. The MCP arg `elision` is the *operator-facing* on/off
  switch (true = redact/elide = emit markers). The walker opt
  `:rf.size/include-large?` is the *walker-facing* pass-through switch
  (true = no marker). The two are inverse views of the same Boolean; call
  sites compute `(not elision?)` once and pass `include-large?` in
  directly.

  Both knobs default off-box-safe per the Tool-Pair §Direct-read privacy
  posture contract — large slots elide, sensitive slots redact, unless the
  caller opts in explicitly.

  Single-arity form applies the off-box-safe default (`include-sensitive?`
  false ⇒ `:rf.egress/off-box-tool`) so call-sites that don't reveal
  sensitive data needn't spell it out."
  ([include-large?]
   (egress-opts-edn include-large? false))
  ([include-large? include-sensitive?]
   (pr-str (cond-> {:rf.egress/profile
                    (rf.mcp-base.egress/mcp-tool-profile include-sensitive?)}
             include-large?
             (assoc rf.mcp-base.vocab/include-large-opt true)))))

(defn project-sub-value-src
  "CLJS source for a fn that projects ONE sub-cache entry's `:value` slot
  through `re-frame.core/project-egress`.

  Returns a source string for an anonymous fn `(fn [entry] ...)` that
  runs `entry`'s `:value` (the subscription's current deref) through the
  egress door, leaving `:query-v` / `:ref-count` untouched. A subscription
  whose value derives from a declared-sensitive app-db slot redacts to
  `:rf/redacted`; a declared-large value elides to
  `:rf.size/large-elided` — parity with `snapshot`'s `:sub-cache` slice,
  which reads the SAME reactive cache source (so both redact alike).

  `frame-edn` is the source for the `:frame` opt (a quoted keyword or a
  runtime `current-frame` call) so the door resolves the right
  `[:rf.runtime/elision]` runtime-db registry; `egress-opts` is the
  rendered `egress-opts-edn` map naming the `:rf.egress/*` profile the
  `--allow-sensitive-reads` gate resolved to.

  rf2-mtzv5m — the entry's `:query-v` is threaded into the projection as
  the `:query-v` opt so a framework route read sub (`:rf/route` /
  `:rf.route/query` / `:rf.route/params`) re-seeds the walk at its
  `[:rf.runtime/routing :current …]` storage position (via the
  routing-owned seed table the walk consults), redacting a `:sensitive`
  route query / param that would otherwise ride RAW (the route
  classification is re-rooted ABSOLUTE in the registry; the bare slice the
  sub returns would never match a whole-value-rooted walk)."
  [frame-edn egress-opts]
  (str "(fn [entry]"
       "  (if (contains? entry :value)"
       "    (let [opts (merge {:frame " frame-edn " :query-v (:query-v entry)} " egress-opts ")]"
       "      (update entry :value (fn [v] (re-frame.core/project-egress v opts))))"
       "    entry))"))
