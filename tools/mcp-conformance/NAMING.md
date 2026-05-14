# Cross-MCP tool-naming convention

Source: rf2-mzf1r.

The re-frame2 MCP triplet — `tools/pair2-mcp/`, `tools/story-mcp/`,
and `tools/causa-mcp/` (spec-only today) — exposes a deliberately
bounded surface, catalogued per-server at
[`tools/pair2-mcp/spec/003-Tool-Catalogue.md`](../pair2-mcp/spec/003-Tool-Catalogue.md),
[`tools/story-mcp/spec/002-Tool-Registry.md`](../story-mcp/spec/002-Tool-Registry.md),
and (when its impl lands)
[`tools/causa-mcp/spec/`](../causa-mcp/spec/). Per the
§"Single source of truth for tool counts" rule below, every count
sits in one place: the catalogue. An agent host with two or three
servers attached at once sees the union as one surface.
**The verb a tool uses is the first signal the agent parses**; verb
drift across siblings makes that signal lossy (snapshot in pair2 ≠
snapshot-identity in story; `read-` in story ≠ `get-` in causa) and
pushes the agent towards trial-and-error rather than pattern-match.

This doc locks the verb vocabulary the triplet picks from. New tools
land against an existing verb; novel verbs require a Lock entry in
the relevant server's `DESIGN-RATIONALE.md` and a return-trip here
to extend the table. The conformance harness in
[`wire-vocab/`](./wire-vocab/) covers the wire payloads; this doc
covers the catalogue surface.

## The verb table

| Verb shape | Semantics | Examples | Notes |
|---|---|---|---|
| **`get-<thing>`** | Single-entity read by id / key / addressed path. Returns ONE record or value. | `get-app-db`, `get-path`, `get-story`, `get-variant`, `get-trace-buffer`, `get-epoch-history`, `get-machine-state`, `get-machine-list`, `get-issues`, `get-handlers`, `get-source-coord`, `get-story-instructions` | The most common verb. The agent supplies an id / path / filter; the server returns the addressed slice. `get-` is a pure read — no side-effects, no recompute. `get-trace-buffer` and `get-epoch-history` are `get-` rather than `list-` because they return slices addressed by filter, not full enumerations. |
| **`list-<things>`** | Collection enumeration — no id, returns vector / set. | `list-stories`, `list-substrates`, `list-tags`, `list-modes`, `list-assertions` | Closed-set or registrar-derived enumeration. The returned shape is a vector of records (or ids); no filter axis collapses it to one entity. Pair2-mcp doesn't ship any `list-*` today (its catalogue is small enough that the per-frame snapshot covers the discovery workflow); causa-mcp uses `get-machine-list` rather than `list-machines` because the call carries a frame filter — borderline, but `get-` won when the canonical pin was chosen. New tools that match the pure-enumeration shape use `list-`. |
| **`read-<thing>`** | Diagnostic re-read of last-computed state. Same shape as `get-`, but reserved for the **no-recompute** read path against a previously-executed artefact. | `read-failures` | Distinguished from `get-` so the agent recognises "the run already happened; this is a cheap reflection" — not "fetch the live state". The difference matters when the value is expensive to recompute (story-mcp's `read-failures` reads the variant's accumulated `:rf.story/assertions` rather than re-running the play sequence). |
| **`discover-<surface>`** | Session-bootstrap health probe. Returns `{:ok? ... :debug-enabled? ... :frames [...] :build-id ...}` or a structured `:reason` keyword. | `discover-app` | Run first every session. Universal across pair2-mcp and (planned) causa-mcp; story-mcp doesn't ship `discover-app` because its runtime model is JVM-side (no nREPL handshake). |
| **`dispatch`** | Fire a re-frame2 event. Bare verb, **universal across pair2-mcp and causa-mcp**. Always tagged with `:origin :<server-name>` on the trace bus. | `dispatch` | Story-mcp does NOT ship `dispatch` — its mutations go through `register-variant` / `unregister-variant` instead. The bare verb is reserved for the framework's primary mutation primitive. |
| **`eval-cljs`** | The escape hatch — evaluate an arbitrary CLJS form in the connected runtime. Bare verb, **universal across pair2-mcp and (planned) causa-mcp**. Any side-effect the form triggers inherits the server's `:origin` tag. | `eval-cljs` | Story-mcp does NOT ship `eval-cljs` because its server runs JVM-side and there's no browser runtime to eval into. When `causa-mcp` impl lands it ships the same shape (per its DESIGN-RATIONALE Lock #5: closed-set catalogue, deliberate escape valve). |
| **`restore-<thing>`** | Time-travel state restore. Mirrors a user-confirmed in-panel affordance. | `restore-epoch` | Mutating; tagged `:origin :<server-name>`. Causa-mcp only today. |
| **`reset-<thing>`** | Replace state, bypassing the normal cascade. Mirrors a "try anyway" affordance. | `reset-frame-db` | Mutating; tagged `:origin :<server-name>`. Causa-mcp only today. |
| **`register-<thing>` / `unregister-<thing>`** | Registry add / remove, symmetric pair. Both gated behind the server's write-allow flag where applicable. | `register-variant`, `unregister-variant` | Story-mcp only today. If a future tool surfaces "register a handler at the framework level via MCP" it adopts this verb. |
| **`run-<thing>`** | Execute a definition and report **pass / fail** results. Implies a play sequence, an assertion vocabulary, or some explicit success criterion. | `run-variant`, `run-a11y` | Distinguished from `preview-` (no pass/fail) and `dispatch` (one event, not a sequence). Story-mcp only today. |
| **`preview-<thing>`** | Execute and report **rendered / resolved state**, but no pass/fail. The "show me what this would look like" call. | `preview-variant` | The symmetric pair of `run-` for the same registry. Story-mcp only today. |
| **`record-as-<thing>`** | Capture user / agent activity for a bounded duration; emit as an artefact (variant snippet, etc.). | `record-as-variant` | The bridge between live dispatches and the persisted registry. Story-mcp only today. |
| **`subscribe` / `unsubscribe`** | Streaming pair. Bare verbs, **universal across pair2-mcp and (planned) causa-mcp**. `subscribe` returns one `notifications/progress` per matching batch; `unsubscribe` closes out-of-band. | `subscribe`, `unsubscribe` | Topic vocabulary is per-server (pair2 ships `:trace` / `:epoch` / `:fx` / `:error`; causa-mcp ships the same set when impl lands). Story-mcp does not ship streaming. |
| **`tail-<thing>`** | Wait for an external state change to land. Polls until a probe condition flips or `wait-ms` expires. | `tail-build` | Today only `tail-build` exists (await hot-reload). Future variants (`tail-test`, `tail-deploy`) take the same shape: integer wait, probe form, structured timeout. |
| **Mega-op bare verbs** | Reserved for derived projections / multi-registry reads that don't fit `get-<thing>`. Bare names, no prefix. | `snapshot`, `trace-window`, `watch-epochs` | These are pair2-mcp's coarse-grained reads that span multiple registry kinds (`snapshot` covers app-db + sub-cache + machines + epochs + traces in one round-trip; `trace-window` and `watch-epochs` page over the trace bus). Adding a new bare verb requires a Lock entry in the relevant server's `DESIGN-RATIONALE.md`. |

## What's NOT a locked verb

These prefixes are **deliberately rejected** for new tools — call them
out in PR review and pick from the table above instead.

- **`fetch-`, `query-`, `find-`, `lookup-`** — verb-soup synonyms for
  `get-`. Pick `get-`.
- **`update-`, `set-`** — implies a generic mutation surface. Pick a
  named verb (`dispatch`, `register-`, `restore-`, `reset-`) so the
  agent sees the registry / surface, not the verb tense.
- **`enumerate-`, `all-<things>`, `<things>-list`** — verb-soup
  synonyms for `list-`. Pick `list-`.
- **`call-`, `invoke-`, `run-fn`** — `eval-cljs` is the catalogued
  escape hatch; bare-name dispatch is the catalogued event-fire. Don't
  bypass them.
- **`stream-`, `observe-`, `tail-trace`** — `subscribe` / `unsubscribe`
  is the catalogued streaming pair. `tail-` is reserved for state-
  change-await semantics (one-shot, returns when condition trips).

## Server alignment today (no renames required)

The triplet is **largely aligned** with this convention today. The
table below is the audit; deviations are flagged but **none warrant
renames in this bead's scope** — the existing names predate this
canonical pin and renaming would cascade through every agent skill,
every README example, and every CI fixture. The convention is the lock
for **new tools landing under causa-mcp's impl** and for **any future
extension** to pair2-mcp / story-mcp.

### Pair2-mcp (14 tools)

| Tool | Verb shape | Notes |
|---|---|---|
| `discover-app` | `discover-` | Conformant. |
| `eval-cljs` | bare (universal) | Conformant. |
| `dispatch` | bare (universal) | Conformant. |
| `tail-build` | `tail-` | Conformant. |
| `snapshot` | bare (mega-op) | Conformant — multi-slice projection. |
| `trace-window` | bare (mega-op) | Conformant — paginated projection over trace bus. |
| `watch-epochs` | bare (mega-op) | Conformant — paginated projection. (Borderline: arguably a `list-` candidate, but the cursor / filter shape leans mega-op.) |
| `get-path` | `get-` | Conformant. |
| `subscribe` / `unsubscribe` | bare (universal pair) | Conformant. |
| `subscription-info` | bare-noun read | **Non-conformant** — bare-noun read of a streaming-subscription's status; see causa-mcp's `list-subscriptions` for the conformant cross-server pair. The current name predates the cross-MCP convention (rf2-zjz9q landed it before the rf2-3we2k Lock #12 picked the conformant verb). A future rename to `list-subscriptions` aligns the triplet; today the divergence is acknowledged. |
| `handler-meta` | bare-noun read | Audit pending — added by rf2-cibp8 (PR #1107). Bare-noun read of a registered handler's registration-metadata map. Conformance against the bare-noun-exception clause needs a separate audit pass. |
| `registry-list` | `<noun>-list` suffix | Audit pending — added by rf2-pctf8 (PR #1107). The `<things>-list` suffix shape is **flagged as rejected** in §"What's NOT a locked verb" above (a `list-<things>` prefix is the catalogued shape). The current name mirrors the runtime's `(rf/registry-list kind)` accessor; whether to grandfather the suffix shape or rename to `list-handlers` is a separate decision. |
| `get-pair2-instructions` | `get-` | Conformant — single-record read of the agent-onboarding instructions blob (rf2-fnpqg). Mirrors story-mcp's `get-story-instructions`. |

### Story-mcp (19 tools)

| Tool | Verb shape | Notes |
|---|---|---|
| `get-story-instructions` | `get-` | Conformant — single-record read of the instruction blob. |
| `preview-variant` | `preview-` | Conformant. |
| `list-substrates` | `list-` | Conformant. |
| `list-stories` | `list-` | Conformant. |
| `get-story` | `get-` | Conformant. |
| `get-variant` | `get-` | Conformant. |
| `list-tags` | `list-` | Conformant. |
| `list-modes` | `list-` | Conformant. |
| `list-decorators` | `list-` | Conformant — read-only `(rf/registrations :decorator)` enumeration (rf2-mqp1u). |
| `list-assertions` | `list-` | Conformant. |
| `get-docs-markdown` | `get-` | Conformant — single-record read of a story's GFM-projected documentation (rf2-i0kyy). Sibling shape to `get-story`. |
| `variant->edn` | bare (Clojure idiom) | **Deviation** — `->edn` is a Clojure-idiomatic projection name (mirrors `into`, `seq->vec` etc.). The convention catalogues this as an accepted exception: when the operation is a **canonical-form serialiser** of a known artefact, `<thing>->edn` is preferable to `get-<thing>-edn` because the arrow signals the projection direction. Story-mcp ships exactly one of these; if a sibling appears (`variant->json`, etc.) it follows the same shape. |
| `run-variant` | `run-` | Conformant. |
| `snapshot-identity` | bare (Clojure idiom) | **Deviation** — bare-noun read of a content-hash. The convention catalogues this as an accepted bare-noun exception when the return value is a single primitive (a hash, a count, a digest) and the call is read-only. An alternative future shape `get-variant-identity` would also be conformant; the current name is grandfathered. |
| `run-a11y` | `run-` | Conformant. |
| `read-failures` | `read-` | Conformant — diagnostic re-read of last-computed state. |
| `register-variant` | `register-` | Conformant. |
| `unregister-variant` | `unregister-` | Conformant. |
| `record-as-variant` | `record-as-` | Conformant. |

### Causa-mcp (planned, 18 tools per `tools/causa-mcp/spec/`)

| Tool | Verb shape | Notes |
|---|---|---|
| `discover-app` | `discover-` | Conformant. |
| `eval-cljs` | bare (universal) | Conformant. |
| `dispatch` | bare (universal) | Conformant. |
| `tail-build` | `tail-` | Conformant. |
| `subscribe` / `unsubscribe` | bare (universal pair) | Conformant. |
| `list-subscriptions` | `list-` | Conformant — enumeration of active streaming subscriptions; the cross-server-symmetric counterpart to pair2-mcp's (non-conformant) `subscription-info`. Per [`tools/causa-mcp/spec/DESIGN-RATIONALE.md` Lock #12](../causa-mcp/spec/DESIGN-RATIONALE.md) (rf2-3we2k, 2026-05-14) — the eighteenth tool, picked under the conformant verb when pair2-mcp's drift was surfaced by audit rf2-m9yoi. |
| `get-trace-buffer` | `get-` | Conformant — filter-addressed slice read. |
| `get-epoch-history` | `get-` | Conformant — filter-addressed slice read. |
| `get-app-db` | `get-` | Conformant. |
| `get-app-db-diff` | `get-` | Conformant. |
| `get-machine-state` | `get-` | Conformant. |
| `get-machine-list` | `get-` | **Borderline** — a pure enumeration would be `list-machines`; the current name uses `get-` because the call carries a frame filter. Either shape is conformant; the current name is the spec'd pick and stays. |
| `get-issues` | `get-` | Conformant. |
| `get-handlers` | `get-` | Conformant. |
| `get-source-coord` | `get-` | Conformant. |
| `restore-epoch` | `restore-` | Conformant. |
| `reset-frame-db` | `reset-` | Conformant. |

## Single source of truth for tool counts

Each per-server spec carries its catalogue count in prose ("the 19 tools",
"the fourteen tools"). Past drift episodes (story-mcp shipped both "16 tools"
and "17 tools" across five docs after `list-subscriptions` was added; the
audit that surfaced it had to grep across `tools/story-mcp/` to find every
mention) trace to one root cause: **the count is repeated, not extracted**.
Every doc that recites the number ages independently when a tool lands or
gets retired.

The convention for per-server tool-catalogue docs:

- **One canonical count site per server.** Pin the integer in exactly one
  place — the catalogue file's `# <Server> — Tool Registry` heading or its
  introductory paragraph (`tools/pair2-mcp/spec/003-Tool-Catalogue.md`,
  `tools/story-mcp/spec/002-Tool-Registry.md`, `tools/causa-mcp/spec/`
  forthcoming). Every other doc that needs to cite the count **links to
  the catalogue** rather than repeating the integer:
  ```md
  See [`002-Tool-Registry.md`](002-Tool-Registry.md) for the full tool list.
  ```
  rather than
  ```md
  See [`002-Tool-Registry.md`](002-Tool-Registry.md) — the 17 tools.
  ```
- **PR-review rule.** A PR that adds or removes a tool MUST update the
  canonical count site in the catalogue heading. Any other doc that still
  reads "the N tools" after the catalogue update is a stale citation — fix
  it or convert it to a count-free link. The cross-MCP audit walks the
  triplet once per release looking for the pattern; until that audit is
  automated, the discipline lives here.
- **`Server alignment today` (above)** carries explicit counts because the
  audit table is the canonical comparison — those counts WILL drift unless
  this NAMING.md is itself updated when the catalogues are. The table's
  counts are pinned **to this doc's last audit pass**, not asserted as
  always-current; the catalogue is always the live source.

This is the same single-source-of-truth principle that governs the verb
table above — the table is the lock, the per-server catalogues are the
projections.

## Error-vocabulary alignment

The `:reason` keyword on `{:ok? false ...}` returns follows the same
cross-server discipline. Three reserved namespaces:

- **`:rf.error/*`** — framework runtime errors (handler exceptions,
  schema-validation failures, dispatch-cycle limits). Owned by the
  framework, surfaced verbatim by every server.
- **`:rf.epoch/*`** — epoch-machinery errors (`:rf.epoch/cursor-stale`,
  `:rf.epoch/id-aged-out`). Owned by `spec/Tool-Pair.md`, surfaced
  verbatim by every server that ships epoch slices.
- **`:<server-name>.error/*`** — server-specific failures
  (`:rf.story-mcp.error/write-gate-closed`,
  `:rf.pair2-mcp.error/runtime-not-preloaded`,
  `:rf.causa-mcp.error/...`). Owned by the server.

Additional cross-server reserved keywords cover the wire-vocabulary
markers (`:rf.mcp/overflow`, `:rf.mcp/summary`, `:rf.mcp/dedup-table`,
`:rf.mcp/diff-from`, `:rf.mcp/cache-hit`, `:rf.size/large-elided`,
`:rf.elision/at`) — see [`wire-vocab/README.md`](./wire-vocab/README.md)
for the shape contract and the conformance gate.

## How to extend this table

A new tool that **doesn't fit** an existing verb shape needs:

1. A Lock entry in the relevant server's `spec/DESIGN-RATIONALE.md`
   recording the verb pick (question / options / pick / why / date).
2. A row in the table above with one-line semantics + an example.
3. A row in the "What's NOT a locked verb" list IF the new verb shape
   subsumes a previously-tempting prefix.
4. A test fixture in `wire-vocab/` if the new tool emits any of the
   cross-server wire markers.

Don't extend silently — agents learn the verb table by example, and
silent additions to the table erode the "one signal, one meaning"
property the table exists to enforce.

## See also

- [`wire-vocab/README.md`](./wire-vocab/README.md) — cross-MCP
  wire-vocabulary conformance (the `:rf.mcp/*` namespace).
- [`tools/pair2-mcp/spec/003-Tool-Catalogue.md`](../pair2-mcp/spec/003-Tool-Catalogue.md)
  — pair2-mcp's tool catalogue (verbs annotated in
  `spec/Principles.md`).
- [`tools/story-mcp/spec/002-Tool-Registry.md`](../story-mcp/spec/002-Tool-Registry.md)
  — story-mcp's tool catalogue.
- [`tools/causa/spec/010-MCP-Server.md`](../causa/spec/010-MCP-Server.md)
  §"Tool catalogue" — causa-mcp's catalogue prose (until
  `tools/causa-mcp/spec/003-Tool-Catalogue.md` lands).
- [`spec/Conventions.md`](../../spec/Conventions.md) §"Reserved
  namespaces (framework-owned)" — the `:rf.*` keyword discipline this
  doc inherits.
