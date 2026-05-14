# Cross-MCP tool-naming convention

Source: rf2-mzf1r.

The re-frame2 MCP triplet — `tools/pair2-mcp/`, `tools/story-mcp/`,
and `tools/causa-mcp/` (spec-only today) — exposes ~40 tools today,
trending towards ~50 once causa-mcp's implementation lands. An agent
host with two or three servers attached at once sees the union as
one surface. **The verb a tool uses is the first signal the agent
parses**; verb drift across siblings makes that signal lossy
(snapshot in pair2 ≠ snapshot-identity in story; `read-` in story ≠
`get-` in causa) and pushes the agent towards trial-and-error rather
than pattern-match.

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

### Pair2-mcp (11 tools)

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

### Story-mcp (17 tools)

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
| `list-assertions` | `list-` | Conformant. |
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
