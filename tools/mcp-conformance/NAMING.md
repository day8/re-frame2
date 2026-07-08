# Cross-MCP tool-naming convention

Source: rf2-mzf1r.

The re-frame2 MCP pair — `tools/re-frame2-pair-mcp/` and
`tools/story-mcp/` — exposes a deliberately bounded surface, catalogued
per-server at
[`tools/re-frame2-pair-mcp/spec/003-Tool-Catalogue.md`](../re-frame2-pair-mcp/spec/003-Tool-Catalogue.md)
and
[`tools/story-mcp/spec/002-Tool-Registry.md`](../story-mcp/spec/002-Tool-Registry.md).
Per the §"Single source of truth for tool counts" rule below, every count
sits in one place: the catalogue. An agent host with both servers
attached at once sees the union as one surface.
**The verb a tool uses is the first signal the agent parses**; verb
drift across siblings makes that signal lossy (snapshot in re-frame2-pair ≠
snapshot-identity in story) and pushes the agent towards trial-and-error
rather than pattern-match.

(Historical: a third server `xray-mcp` was envisaged; it was dropped
per rf2-hvl1g — AI agent access to Xray state already flows via
`re-frame2-pair-mcp` against the framework-published Xray runtime API,
so a dedicated xray-mcp is unnecessary.)

This doc locks the verb vocabulary the pair picks from. New tools
land against an existing verb; novel verbs require a Lock entry in
the relevant server's `DESIGN-RATIONALE.md` and a return-trip here
to extend the table. The conformance harness in
[`wire-vocab/`](./wire-vocab/) covers the wire payloads; this doc
covers the catalogue surface.

## The verb table

| Verb shape | Semantics | Examples | Notes |
|---|---|---|---|
| **`get-<thing>`** | Single-entity read by id / key / addressed path. Returns ONE record or value. | `get-app-db`, `get-path`, `get-story`, `get-variant`, `get-trace-buffer`, `get-epoch-history`, `get-machine-state`, `get-machine-list`, `get-issues`, `get-handlers`, `get-source-coord`, `get-story-instructions` | The most common verb. The agent supplies an id / path / filter; the server returns the addressed slice. `get-` is a pure read — no side-effects, no recompute. `get-trace-buffer` and `get-epoch-history` are `get-` rather than `list-` because they return slices addressed by filter, not full enumerations. |
| **`list-<things>`** | Collection enumeration — no id, returns vector / set. | `list-stories`, `list-substrates`, `list-tags`, `list-modes`, `list-assertions` | Closed-set or registrar-derived enumeration. The returned shape is a vector of records (or ids); no filter axis collapses it to one entity. re-frame2-pair-mcp doesn't ship any `list-*` today (its catalogue is small enough that the per-frame snapshot covers the discovery workflow). New tools that match the pure-enumeration shape use `list-`. |
| **`read-<thing>`** | Diagnostic re-read of last-computed state. Same shape as `get-`, but reserved for the **no-recompute** read path against a previously-executed artefact. | `read-failures`, `read-a11y-violations` | Distinguished from `get-` so the agent recognises "the run already happened; this is a cheap reflection" — not "fetch the live state". The difference matters when the value is expensive to recompute (story-mcp's `read-failures` reads the variant's accumulated `:rf.story/assertions` rather than re-running the play sequence; `read-a11y-violations` reads the in-browser a11y panel's accumulated axe-core violations rather than executing axe — the name says read, not run, precisely because calling it neither runs a fresh check nor proves the variant accessible). |
| **`discover-<surface>`** | Session-bootstrap health probe. Returns `{:ok? ... :debug-enabled? ... :frames [...] :build-id ...}` or a structured `:reason` keyword. | `discover-app` | Run first every session. Used by re-frame2-pair-mcp; story-mcp doesn't ship `discover-app` because its runtime model is JVM-side (no nREPL handshake). |
| **`dispatch`** | Fire a re-frame2 event. Bare verb, used by re-frame2-pair-mcp. Always tagged with `:origin :<server-name>` on the trace bus. | `dispatch` | Story-mcp does NOT ship `dispatch` — its mutations go through `register-variant` / `unregister-variant` instead. The bare verb is reserved for the framework's primary mutation primitive. |
| **`eval-cljs`** | The escape hatch — evaluate an arbitrary CLJS form in the connected runtime. Bare verb, used by re-frame2-pair-mcp. Any side-effect the form triggers inherits the server's `:origin` tag. | `eval-cljs` | Story-mcp does NOT ship `eval-cljs` because its server runs JVM-side and there's no browser runtime to eval into. |
| **`restore-<thing>`** | Time-travel state restore. Mirrors a user-confirmed in-panel affordance. | `restore-epoch` | Mutating; tagged `:origin :<server-name>`. Shipped by re-frame2-pair-mcp behind `--allow-writes` (rf2-ee38b.18). |
| **`replace-<thing>`** | Replace a named state partition with a caller-supplied value, bypassing the normal cascade. Mirrors a "try anyway" / state-injection affordance. | `replace-app-db` | Mutating; tagged `:origin :<server-name>`. `replace-app-db` (re-frame2-pair-mcp) is gated behind `--allow-writes` (rf2-ee38b.18); it wraps the framework's `replace-frame-state!` Tool-Pair write primitive as an app-only partial map (`{:rf.db/app v}`) — the ONE frame-state write surface (a db-shaped key never silently replaces runtime-db). |
| **`reset-<thing>`** | Reset a named state / session partition to its empty / default value, bypassing the normal cascade. | `reset-operating-frame` | Mutating where it touches app-db; tagged `:origin :<server-name>`. `reset-operating-frame` (rf2-zomfq) clears the SESSION operating-frame pin — not an app-db write, so ungated. (The former `reset-frame-db` state-injection tool was renamed to `replace-app-db` under EP-0001.) |
| **`set-<thing>`** | Pin / declare ONE named **session setting** — not a generic mutation surface. Narrowly catalogued for the operating-frame contract only. | `set-operating-frame` | Added by rf2-zomfq (re-frame2-pair). The Tool-Pair §Tool-surface obligations contract mandates a set/reset/get trio for the operating frame and states the stable names are "typically `set-operating-frame` / `reset-operating-frame` / `get-operating-frame`"; the spec-mandated name wins for cross-server mental-model transfer. This is the deliberate carve-out from the `set-` rejection in §"What's NOT a locked verb" below — admissible ONLY because it pins a single named session setting (the operating frame), not arbitrary state. A future generic `set-<arbitrary>` is still rejected. Lock entry: re-frame2-pair-mcp `DESIGN-RATIONALE.md` §Subsequent evolution (rf2-zomfq). |
| **`register-<thing>` / `unregister-<thing>`** | Registry add / remove, symmetric pair. Both gated behind the server's write-allow flag where applicable. | `register-variant`, `unregister-variant` | Story-mcp only today. If a future tool surfaces "register a handler at the framework level via MCP" it adopts this verb. |
| **`run-<thing>`** | Execute a definition and report **pass / fail** results. Implies a play sequence, an assertion vocabulary, or some explicit success criterion. | `run-variant` | Distinguished from `preview-` (no pass/fail) and `dispatch` (one event, not a sequence). Story-mcp only today. `run-` is reserved for tools that actually execute a runner — the a11y violation surface is a `read-` (no-recompute) tool, `read-a11y-violations`, because it only reflects the in-browser panel's already-accumulated state and does NOT execute axe. |
| **`preview-<thing>`** | Execute and report **rendered / resolved state**, but no pass/fail. The "show me what this would look like" call. | `preview-variant` | The symmetric pair of `run-` for the same registry. Story-mcp only today. |
| **`explain-<thing>`** | Pure read of the **derivation / resolution reasoning** for a definition — "why did this resolve the way it did". No run, no live state. Mirrors a human "explain" panel. | `explain-variant` | Distinguished from `get-` (which returns the stored body) and `preview-` (which executes): `explain-` returns the plan compiler's source/merge/lowering reasoning. Story-mcp's `explain-variant` mirrors the human Explain panel over `re-frame.story/explain` (rf2-ba86n.17). Story-mcp only today. |
| **`describe-<thing>`** | Read the **composed / derived behaviour** an addressed runtime context runs PLUS where each piece resolved from — "what behaviour does THIS context run, and which source won each resolution". A pure read; no run, no mutation. | `describe-image` | Added by rf2-srobm0 (re-frame2-pair). Distinguished from `get-` (returns ONE stored record/value), `read-` (no-recompute reflection of an already-executed artefact), and `list-` (flat enumeration): `describe-` reports a per-context COMPOSITION — the selected universe a frame actually runs, with optional per-registration provenance. `describe-image` is the EP-0023 Use-Case 7 read over a frame's running image generation (images / kinds / capability requires / per-kind counts / optional per-registration coordinate). Lock #11 in re-frame2-pair-mcp `DESIGN-RATIONALE.md`. |
| **`record-as-<thing>`** | Capture user / agent activity for a bounded duration; emit as an artefact (variant snippet, etc.). | `record-as-variant` | The bridge between live dispatches and the persisted registry. Story-mcp only today. |
| **`subscribe` / `unsubscribe`** | Streaming pair. Bare verbs, used by re-frame2-pair-mcp. `subscribe` returns one `notifications/progress` per matching batch; `unsubscribe` closes out-of-band. | `subscribe`, `unsubscribe` | Topic vocabulary is per-server (re-frame2-pair ships `:trace` / `:epoch` / `:fx` / `:error`). Story-mcp does not ship streaming. |
| **`tail-<thing>`** | Wait for an external state change to land. Polls until a probe condition flips or `wait-ms` expires. | `tail-build` | Today only `tail-build` exists (await hot-reload). Future variants (`tail-test`, `tail-deploy`) take the same shape: integer wait, probe form, structured timeout. |
| **`watch-<thing>`** | Block until a predicate over a live signal holds, or time out. Distinct from `tail-` (which awaits an EXTERNAL state change via a probe-value delta) — `watch-` blocks on a PREDICATE over an in-runtime signal-set (app-db / sub / DOM / focus). | `watch-until` | Added by rf2-zo4b9 (re-frame2-pair). `watch-epochs` predates the prefix and stays on the bare-verb list (its semantics are pull-mode pagination, not blocking-until). The server-side poll cadence mirrors `tail-build`. |
| **Mega-op bare verbs** | Reserved for derived projections / multi-registry reads that don't fit `get-<thing>`. Bare names, no prefix. | `snapshot`, `trace-window`, `watch-epochs`, `record`, `orient` | These are re-frame2-pair-mcp's coarse-grained reads / recorders that span multiple registry kinds (`snapshot` covers app-db + sub-cache + machines + epochs + traces in one round-trip; `trace-window` and `watch-epochs` page over the trace bus; `record` (rf2-zo4b9) installs a read-only change-log observer over a heterogeneous signal-set — app-db / sub / DOM / focus; `orient` (rf2-3bu3d.8) is the first-contact app-shape summary — liveness + frames + per-frame app-db top-keys + registry counts/ids + machines in one round-trip, composing the existing introspection surfaces). Adding a new bare verb requires a Lock entry in the relevant server's `DESIGN-RATIONALE.md`. NOTE the contrast with the `record-as-<thing>` PREFIX (story-mcp's `record-as-variant`, capture-as-artefact): bare `record` is the live recorder, `record-as-` persists a captured artefact. |

## What's NOT a locked verb

These prefixes are **deliberately rejected** for new tools — call them
out in PR review and pick from the table above instead.

- **`fetch-`, `query-`, `find-`, `lookup-`** — verb-soup synonyms for
  `get-`. Pick `get-`.
- **`update-`** — implies a generic mutation surface. Pick a
  named verb (`dispatch`, `register-`, `restore-`, `reset-`) so the
  agent sees the registry / surface, not the verb tense.
- **`set-`** — generally rejected for the same generic-mutation-surface
  reason: pick a named verb. The **sole catalogued exception** is
  `set-operating-frame` (rf2-zomfq), admitted because it pins ONE named
  session setting the Tool-Pair contract mandates under that exact name
  (§The verb table `set-<thing>` row). A generic `set-<arbitrary-slot>`
  remains rejected.
- **`enumerate-`, `all-<things>`, `<things>-list`** — verb-soup
  synonyms for `list-`. Pick `list-`.
- **`call-`, `invoke-`, `run-fn`** — `eval-cljs` is the catalogued
  escape hatch; bare-name dispatch is the catalogued event-fire. Don't
  bypass them.
- **`stream-`, `observe-`, `tail-trace`** — `subscribe` / `unsubscribe`
  is the catalogued streaming pair. `tail-` is reserved for external-
  state-change-await semantics (one-shot, returns when a probe-value
  delta trips); `watch-` (rf2-zo4b9) is reserved for blocking on a
  PREDICATE over an in-runtime signal-set. Continuous change-logging of
  a signal-set is `record` (rf2-zo4b9), not `observe-`.

## Server alignment today

The pair is **fully aligned** with this convention today
(post-rf2-4y595). The table below is the audit; the table reflects
the post-rename state — pair-mcp's two former deviations
(`subscription-info`, `registry-list`) were renamed to
`list-subscriptions` / `list-handlers` per rf2-4y595 (rf2-h1izl
follow-on C5). The convention is the lock for **any future extension**
to re-frame2-pair-mcp / story-mcp.

### re-frame2-pair-mcp

(Live tool count: see
[`tools/re-frame2-pair-mcp/spec/003-Tool-Catalogue.md`](../re-frame2-pair-mcp/spec/003-Tool-Catalogue.md)
— the canonical count site per §"Single source of truth for tool
counts" below.)

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
| `list-subscriptions` | `list-` | Conformant — renamed from `subscription-info` per rf2-4y595 (NAMING.md follow-on). No back-compat shim; the old name hard-errors with `:unknown-tool`. |
| `handler-meta` | bare-noun read | Conformant exception — bare-noun read of a single-record metadata map. Accepted under the bare-noun-exception clause when the return value is a structured metadata blob the agent reads as one record (same shape as story-mcp's `snapshot-identity`). Added by rf2-cibp8. |
| `list-handlers` | `list-` | Conformant — renamed from `registry-list` per rf2-4y595 (NAMING.md follow-on). The `<noun>-list` suffix was flagged as rejected in §"What's NOT a locked verb" above; `list-<things>` prefix is the catalogued shape. The runtime's `(rf/registry-list kind)` accessor keeps its name (separate naming surface). Added by rf2-pctf8. |
| `get-re-frame2-pair-instructions` | `get-` | Conformant — single-record read of the agent-onboarding instructions blob (rf2-fnpqg). Mirrors story-mcp's `get-story-instructions`. |
| `restore-epoch` | `restore-` | Conformant — time-travel state restore. Gated behind `--allow-writes` (default OFF). Added by rf2-ee38b.18. |
| `replace-app-db` | `replace-` | Conformant — state injection, bypassing the normal cascade. Gated behind `--allow-writes` (default OFF). Added by rf2-ee38b.18; renamed from `reset-frame-db` under EP-0001 (rf2-tfepxu / rf2-0acvdb); tracks the framework's `replace-frame-state!` write primitive as an app-only partial map (rf2-t3lftq — API-shrink #3 consolidated the former `replace-app-db!` into this). |
| `read-dom` | `read-` | Conformant — diagnostic re-read of rendered DOM content (no recompute; the render already happened). Added by rf2-nfjil. |
| `record` | bare (mega-op) | Conformant — bare-verb signal recorder spanning app-db / sub / DOM / focus. Lock entry in `DESIGN-RATIONALE.md`. Distinct from the `record-as-` prefix (capture-as-artefact). Added by rf2-zo4b9. |
| `read-recording` | `read-` | Conformant — diagnostic re-read of a recording's change-log (paired with `record`). Added by rf2-zo4b9. |
| `watch-until` | `watch-` | Conformant — blocks until a predicate over a signal holds. New `watch-` prefix; Lock entry in `DESIGN-RATIONALE.md`. Added by rf2-zo4b9. |
| `set-operating-frame` | `set-` (carve-out) | Conformant — pins the session operating frame (the tier-2 escape from `:ambiguous-frame`). The one catalogued `set-` exception; the Tool-Pair contract mandates this stable name. Lock entry in `DESIGN-RATIONALE.md`. Added by rf2-zomfq. |
| `reset-operating-frame` | `reset-` | Conformant — clears the session operating-frame pin. Ungated (session-state, not app-db). Added by rf2-zomfq. |
| `get-operating-frame` | `get-` | Conformant — single-record read of the `{:frames :selected :operating}` operating-frame triple (Tool-Pair §Tool-surface obligations). Added by rf2-zomfq. |
| `read-sub` | `read-` | Conformant — diagnostic re-read of a single subscription's current value via the validated read path (no recompute beyond the reactive cache resolve). Added by rf2-3bu3d.7. |
| `orient` | bare (mega-op) | Conformant — first-contact app-shape summary spanning liveness + frames + per-frame app-db top-keys + registry counts/ids + machines in one round-trip. Lock #9 in `DESIGN-RATIONALE.md`. Added by rf2-3bu3d.8. |
| `describe-image` | `describe-` | Conformant — new `describe-<thing>` prefix. Per-frame read of the composed image generation a frame runs (images / kinds / capability requires / per-kind counts / optional per-registration provenance). Distinct from `get-` / `read-` / `list-` — see §The verb table. Lock #11 in `DESIGN-RATIONALE.md`. Added by rf2-srobm0. |

### Story-mcp

(Live tool count: see
[`tools/story-mcp/spec/002-Tool-Registry.md`](../story-mcp/spec/002-Tool-Registry.md)
— the canonical count site per §"Single source of truth for tool
counts" below.)

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
| `list-decorators` | `list-` | Conformant — read-only `(story/registrations :decorator)` enumeration (rf2-mqp1u). |
| `list-assertions` | `list-` | Conformant. |
| `get-docs-markdown` | `get-` | Conformant — single-record read of a story's GFM-projected documentation (rf2-i0kyy). Sibling shape to `get-story`. |
| `variant->edn` | bare (Clojure idiom) | **Deviation** — `->edn` is a Clojure-idiomatic projection name (mirrors `into`, `seq->vec` etc.). The convention catalogues this as an accepted exception: when the operation is a **canonical-form serialiser** of a known artefact, `<thing>->edn` is preferable to `get-<thing>-edn` because the arrow signals the projection direction. Story-mcp ships exactly one of these; if a sibling appears (`variant->json`, etc.) it follows the same shape. |
| `run-variant` | `run-` | Conformant. |
| `snapshot-identity` | bare (Clojure idiom) | **Deviation** — bare-noun read of a content-hash. The convention catalogues this as an accepted bare-noun exception when the return value is a single primitive (a hash, a count, a digest) and the call is read-only. An alternative future shape `get-variant-identity` would also be conformant; the current name is grandfathered. |
| `read-failures` | `read-` | Conformant — diagnostic re-read of last-computed state. |
| `read-a11y-violations` | `read-` | Conformant — diagnostic re-read of the in-browser a11y panel's accumulated axe-core violations; does NOT execute axe (the `read-` no-recompute path, sibling of `read-failures`). |
| `register-variant` | `register-` | Conformant. |
| `unregister-variant` | `unregister-` | Conformant. |
| `record-as-variant` | `record-as-` | Conformant. |

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
  introductory paragraph (`tools/re-frame2-pair-mcp/spec/003-Tool-Catalogue.md`,
  `tools/story-mcp/spec/002-Tool-Registry.md`). Every other doc that
  needs to cite the count **links to the catalogue** rather than
  repeating the integer:
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
  it or convert it to a count-free link. The cross-MCP audit walks both
  servers once per release looking for the pattern; until that audit is
  automated, the discipline lives here.
- **`Server alignment today` (above) carries no integer counts in its
  headings.** Per-server subsections link to the catalogue for the live
  count rather than pinning an integer that would silently age past the
  next add/remove. The audit table itself enumerates the verb-conformance
  judgement for each tool by name — a tool added after this doc's last
  audit pass simply isn't in the table yet, which is the correct signal
  ("re-audit needed") rather than a contradicted count.

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
  `:rf.re-frame2-pair-mcp.error/runtime-not-preloaded`). Owned by the
  server.

Additional cross-server reserved keywords cover the wire-vocabulary
markers (`:rf.mcp/overflow`, `:rf.mcp/summary`, `:rf.mcp/dedup-table`,
`:rf.mcp/diff-from`, `:rf.mcp/cache-hit`, `:rf.size/large-elided`,
`:rf.elision/at`) — see [`wire-vocab/README.md`](./wire-vocab/README.md)
for the shape contract and the conformance gate.

### JSON-RPC error codes

Underneath the `:reason` keyword layer above sits the **JSON-RPC 2.0
numeric error-code** layer — the codes the SDK / framework return
when a request is malformed at the protocol level rather than failing
in tool-result space. Per JSON-RPC §5.1 and MCP's reuse, the same
numeric codes apply across both servers; the canonical home for every
code constant is
[`tools/mcp-base/src/re_frame/mcp_base/vocab.cljc`](../mcp-base/src/re_frame/mcp_base/vocab.cljc)
(see the `code-*` defs).

The conformance contract pins:

- **`-32601 MethodNotFound`** — the canonical code for an unknown
  JSON-RPC method. Pinned by `mcp-base/vocab.cljc/code-method-not-found`.
  Every server in the pair MUST yield this code for an unrecognised
  method name; the conformance harness asserts the exact value (see
  `tools/mcp-conformance/test/_runner.cjs/assertJsonRpcErrorCodes`).
- **`-32602 InvalidParams` / `-32603 InternalError`** — both are
  conformant today for a malformed `tools/call` request (e.g. one
  missing the `:name` slot). JSON-RPC §5.1's plain reading says
  `-32602`; the npm MCP SDK in practice wraps the underlying zod
  parse failure as `-32603`. Both are pinned by `mcp-base/vocab.cljc`
  (`code-invalid-params` / `code-internal-error`). The conformance
  gate accepts the **union** — either code passes
  `assertJsonRpcErrorCodes` — so an SDK tightening that flips the
  emit from one to the other does not break the contract. A future
  bead may collapse to a single code if upstream converges; until
  then the union is the locked surface.

Note the relationship to MCP tool-result errors: tool-execution
failures (a `dispatch` that throws, a `run-variant` that asserts) do
NOT use these JSON-RPC codes. They surface via the MCP tool-result
shape (`isError: true` plus the `:reason` keyword vocabulary above)
so the agent host can present the failure to the LLM without
aborting the conversation. JSON-RPC codes are reserved for
protocol-level failures the SDK detects before (or instead of)
dispatching a tool.

## Operator-opt-in CLI flag vocabulary

Boot-time CLI flags that gate authority surfaces share a canonical
name across both servers. Same operator semantic ⇒
same flag spelling. Sourced from rf2-2x3ql: story-mcp originally
shipped `--allow-sensitive-reads` (rf2-uaymx / rf2-g9fje); pair-mcp
shipped `--allow-raw-state` for the same concept. Both servers now
expose the gate as `--allow-sensitive-reads` — the operator-facing
name avoids implementation leak ("raw-state" was specific to
pair-mcp's data shape) and reads at the operator semantic level.

| Flag                      | Meaning                                                                                                                                                        | Servers that ship it             | Rationale anchor |
|---------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------|------------------|
| `--no-eval`               | Opt OUT of the arbitrary-CLJS-form evaluator tool (`eval-cljs`). Default is eval-cljs ENABLED (rf2-a0z0h; inverts the prior rf2-cxx5s default-OFF posture — eval is the REPL primitive of a pair-debug session, and the gate did not add a protection separable from `--allow-writes` because eval can express the same writes). With this flag, `eval-cljs` calls return `{:ok? false :reason :rf.error/eval-cljs-disabled}` without touching nREPL. | re-frame2-pair-mcp               | rf2-a0z0h (current); rf2-zyoj2 / rf2-cxx5s (prior default-OFF posture) |
| `--allow-sensitive-reads` | Honour caller-supplied `:include-sensitive true` (and pair-mcp's `:elision false`) on direct-read tools. Default OFF; sensitive slots return `:rf/redacted` and large slots elide regardless of the per-call arg when the flag is absent. | re-frame2-pair-mcp, story-mcp    | rf2-2x3ql (alignment), rf2-c2dtu (pair-mcp impl), rf2-uaymx / rf2-g9fje (story-mcp impl) |
| `--allow-writes`          | Enable the server's state-mutating write surface. story-mcp: the registry write tools (`register-variant` / `unregister-variant`). pair-mcp: the state-mutation tools (`restore-epoch` / `replace-app-db`). Default OFF; gated calls return `isError: true` — story-mcp surfaces `structuredContent {:gated true :tool "<name>"}`, pair-mcp surfaces `{:ok? false :reason :rf.error/writes-disabled :tool "<name>"}` — when the flag is absent. Note: this gate protects the named-write audit trail; it does NOT defend against eval-driven writes (the eval surface can express the same writes when enabled), so it composes with `--no-eval` for a true read-only posture. | re-frame2-pair-mcp, story-mcp    | story-mcp IMPL-SPEC §7.3 (003-Write-Surface-Gating.md); pair-mcp rf2-ee38b.18 (writes.cljs) |

### Rules

- **Same semantic ⇒ same flag name across servers.** A new operator
  opt-in that already has a counterpart on a sibling server reuses the
  counterpart's CLI flag spelling.
- **Operator-facing semantic, not implementation leak.** Flag names
  describe what the operator is opting into (`--allow-sensitive-reads`)
  rather than the impl detail (`--allow-raw-state`).
- **Hard rename, no aliases.** Per re-frame2's pre-alpha posture, when a
  flag is realigned both spellings are NOT accepted. The legacy
  spelling stops being recognised at the parser; tests pin the
  rejection so a regression can't reintroduce ambiguity. (See
  `tools/re-frame2-pair-mcp/test/re_frame2_pair_mcp/raw_state_test.cljs`
  `parse-launch-flags-old-name-rejected`.)
- **Internal Clojure identifiers may keep legacy names.** The CLI flag
  is the operator-facing surface; per-server impl-side identifiers
  (predicate / namespace / keyword names) are separate refactor
  surfaces that don't require cross-server lockstep. pair-mcp retains
  `raw-state-allowed?` / `:allow-raw-state?` internally even though the
  CLI flag aligned on `--allow-sensitive-reads`.

### Wire-level conformance (rf2-ee38b.20)

The flag table's default-OFF posture and the hard-rename rule are pinned
end-to-end (over the real MCP wire) by the conformance harness:

- **story-mcp `--allow-writes`** — `test/end-to-end-flag-gates.cjs`
  boots story-mcp without / with / with-a-legacy spelling and asserts the
  gate stays closed by default (`structuredContent.gated`), opens only on
  the canonical spelling, and is NOT re-opened by an unrecognised flag
  (the no-alias rule). Runs on CI in the `mcp-conformance-story` job.
- **re-frame2-pair-mcp `--no-eval`** — `test/live-re-frame2-pair-subscribe.cjs`
  (which boots a non-degraded server WITH `--no-eval`) asserts the
  `:rf.error/eval-cljs-disabled` envelope crosses the wire. Post-rf2-a0z0h
  the eval gate flipped to default-ON, so the disabled envelope is now
  reachable only via the explicit opt-out. This is the one boot
  configuration where pair-mcp's eval gate is observable: in degraded
  mode (no nREPL) every tool short-circuits to `:nrepl-port-not-found`
  before the gate runs, so the wire check needs a live runtime (the
  hermetic orchestrator provides one on CI). The pair-mcp parser
  rename-rejection (legacy `--allow-raw-state` ⇒ gate stays closed; legacy
  `--allow-eval` ⇒ silent no-op, gate stays at default-ON) is pinned
  unit-side by `raw_state_test.cljs`
  (`parse-launch-flags-old-name-rejected` + `parse-launch-flags-legacy-allow-eval-is-noop`).

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
- [`tools/re-frame2-pair-mcp/spec/003-Tool-Catalogue.md`](../re-frame2-pair-mcp/spec/003-Tool-Catalogue.md)
  — re-frame2-pair-mcp's tool catalogue (verbs annotated in
  `spec/Principles.md`).
- [`tools/story-mcp/spec/002-Tool-Registry.md`](../story-mcp/spec/002-Tool-Registry.md)
  — story-mcp's tool catalogue.
- [`spec/Conventions.md`](../../spec/Conventions.md) §"Reserved
  namespaces (framework-owned)" — the `:rf.*` keyword discipline this
  doc inherits.
