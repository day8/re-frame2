# tools/

This directory houses **CLJS dev / inspection tools** that consume re-frame2's
instrumentation API. Each tool ships as its own Maven artefact, on its own
release cadence, and is intentionally kept out of the runtime path that
production consumers depend on.

`tools/` is a sibling of `implementation/`, not part of it. The split is
deliberate — see the bundle-isolation contract below.

## How `tools/` differs from `implementation/`

| | `implementation/` | `tools/` |
|---|---|---|
| Who `:require`s it? | Consumers' production apps | Dev / story / agent surfaces |
| What contract does it implement? | The Pattern Specification (`spec/`) | Downstream consumer of the spec's instrumentation API (Spec 009, Tool-Pair) |
| Bundle exposure? | Shipped to end users | Must not reach a production build |
| Release cadence? | Lockstep through `1.0` (rf2-w05l) | Per-tool, independent |
| Owns spec surface? | Yes — `implementation/core/` is the canonical reference | No — tools *consume* the surface |

`implementation/core/` is the runtime; everything in `tools/` is a downstream
observer of that runtime.

## The bundle-isolation contract

Tools must not be reachable from a consumer's production build.

- A tool may `:require` from `implementation/core/` (and from the per-feature
  artefacts where its job demands it — e.g. a machine visualiser will pull
  `implementation/machines/`). The dependency flows **tool → implementation**.
- The reverse is forbidden. Nothing under `implementation/` may `:require`
  anything under `tools/`. Adding such a dep would haul tooling weight
  (DOM-heavy UI, monaco, story metadata, MCP server bits) into every
  consumer's production bundle.
- The contract is enforced **structurally**: `tools/` is a separate
  classpath root, not on `implementation/`'s `deps.edn` or
  `shadow-cljs.edn`. Bundle isolation is "the wrong artefact is absent
  from the classpath" — same mechanism the substrate adapters use.

Today this convention exists as a directory split plus disciplined
`deps.edn` hygiene. The physical separation makes the contract obvious
to humans, agents, and tree-shakers alike.

## Per-tool layout

Each tool gets its own subdirectory, structured like the per-adapter jars
under `implementation/adapters/`:

```
tools/
├── <tool>/
│   ├── deps.edn              ; declares day8/re-frame2-<tool>
│   ├── src/...               ; tool source
│   ├── test/...              ; tool tests
│   └── spec/...              ; the tool's normative spec (see below)
└── ...
```

Each `deps.edn` carries a `:local/root` dep on `../../implementation/core`
(plus whichever per-feature artefacts the tool legitimately consumes).
Each tool publishes to Clojars under `day8/re-frame2-<tool>`.

A top-level `tools/deps.edn` and `tools/shadow-cljs.edn` (rf2-nuuk3) act
as build coordinators across the tool tier, matching the pattern in
`implementation/`. `tools/deps.edn` declares each tool as a `:local/root`
dep and exposes a `:test` alias that aggregates every JVM-runnable
tool's `test/` tree; running `clojure -M:test` from `tools/` exercises
the aggregated suite. `tools/shadow-cljs.edn` mirrors the per-tool CLJS
builds (today: `re-frame2-pair-mcp/server` and `re-frame2-pair-mcp/server-test`) so
`shadow-cljs compile <build>` works from `tools/` as well as from each
tool's directory. Per-tool invocations remain valid — the coordinators
compose the per-tool builds, they do not replace them. CLJS-only tools
(`causa`) and JVM-only tools whose sources contain placeholder-bearing
template files (`template`) are excluded from the shadow-cljs surface
for technical reasons documented in the coordinator's comment block.

## Shipped

The tools below have substantial implementations on disk and are
actively developed against. Maturity varies (the alpha framework is
itself pre-1.0); the common factor is that the artefact exists, is
wired into the build, and consumers can use it today.

- **`tools/causa/`** — `day8/re-frame2-causa`. **Causa**, the in-app
  devtools panel for re-frame2 — structural successor to
  re-frame-10x (renamed per `tools/causa/spec/DESIGN-RATIONALE.md`
  Lock #1; the standalone 10x port is now redirected into Causa per
  rf2-jt6t / #556). Preloaded into dev builds via `:preloads`;
  production builds elide it through the universal
  `interop/debug-enabled?` gate (zero bytes shipped to consumers).
  Panel inventory: event-detail, causality graph, time-travel
  scrubber, slice-centric app-db, machine inspector, schema-violation
  timeline, hydration debugger, issues ribbon, AI co-pilot rail. See
  [`tools/causa/spec/000-Vision.md`](./causa/spec/000-Vision.md).

- **`tools/mcp-base/`** — `day8/re-frame2-mcp-base`. Shared primitives
  for the MCP servers (`re-frame2-pair-mcp`, `story-mcp`): seven
  namespaces — `vocab` (wire-vocabulary constants `:rf.mcp/*`,
  `:rf.size/*`, JSON-RPC error codes), `sensitive` (spec/009 §Privacy
  default-suppress filter), `elision` (`:rf.size/large-elided`
  wire-boundary walker), `args` (MCP argument coercion helpers),
  `diff-encode` (path-keyed structural diff, rf2-1wdzp), `overflow`
  (overflow-marker payload shape, rf2-rvyzy), and `cap` (wire-boundary
  token-budget cap pipeline, rf2-eyelu). Pure `.cljc` with zero
  runtime deps beyond `org.clojure/clojure`. Per rf2-vw4sq. See
  [`tools/mcp-base/spec/README.md`](./mcp-base/spec/README.md).

- **`tools/mcp-conformance/`** — End-to-end MCP-client conformance
  harness for the re-frame2 MCP servers (`re-frame2-pair-mcp`, `story-mcp`).
  Pure Node test fixtures: drives each server through the official
  `@modelcontextprotocol/sdk` `Client` so SDK-strict schema
  regressions surface before a real consumer attaches. Also hosts
  the cross-MCP wire-vocabulary conformance fixtures
  (`wire-vocab/`), the cross-MCP tool-naming convention
  (`NAMING.md`), and the cross-MCP token-budget posture
  (`TOKEN-BUDGETS.md`). Per rf2-cum40 / rf2-j2z7o / rf2-mzf1r /
  rf2-ll0yq. **Spec posture: documented exemption from the per-tool
  `spec/` convention** — the conformance contracts live on the
  servers being verified, not on the harness; the harness's
  normative contract IS its test corpus + the three top-level docs
  (`NAMING.md`, `TOKEN-BUDGETS.md`, `wire-vocab/README.md`) plus
  the README. Bundle-isolated by construction (no CLJS sources,
  Node-side only). See
  [`tools/mcp-conformance/README.md`](./mcp-conformance/README.md).

- **`tools/re-frame2-pair-mcp/`** — `@day8/re-frame2-pair-mcp`. A Node-based
  stdio JSON-RPC **MCP server** (compiled from ClojureScript via
  shadow-cljs) that pair-programs with a live re-frame2 app over a
  persistent nREPL socket. Structural successor to the bash-shim →
  babashka → nREPL chain under `skills/re-frame2-pair/scripts/`.
  Fourteen tools (`discover-app`, `eval-cljs`, `dispatch`,
  `trace-window`, `watch-epochs`, `tail-build`, `snapshot`,
  `get-path`, the streaming triad `subscribe` / `unsubscribe` /
  `list-subscriptions`, the registrar-introspection pair
  `handler-meta` / `list-handlers`, and `get-re-frame2-pair-instructions`);
  per-op latency drops from ~700ms to ~5–50ms. Published to npm as
  `@day8/re-frame2-pair-mcp`. See
  [`tools/re-frame2-pair-mcp/README.md`](./re-frame2-pair-mcp/README.md).

- **`tools/story/`** — `day8/re-frame2-story`. A Storybook-class
  component playground for re-frame2, implementing
  [`spec/007-Stories.md`](../spec/007-Stories.md). Each variant runs
  in its own frame (`spec/002`), is EDN-shaped data (not a function),
  ships with schema-derived controls (`spec/010`),
  assertion-vocabulary play sequences, and a content-hashed snapshot
  identity for visual-regression keying. Embeds Causa's epoch panel
  as a registered story panel. See
  [`tools/story/README.md`](./story/README.md).

- **`tools/story-mcp/`** — `day8/re-frame2-story-mcp`. JVM-side stdio
  JSON-RPC **MCP server** that exposes Story's read (and gated write)
  surface as MCP tools. Nineteen tools across four categories — Dev
  (`get-story-instructions`, `preview-variant`, `list-substrates`),
  Docs (`list-stories`, `get-story`, `get-variant`, `list-tags`,
  `list-modes`, `list-decorators`, `list-assertions`, `variant->edn`,
  `get-docs-markdown`), Testing (`run-variant`, `snapshot-identity`,
  `run-a11y`, `read-failures`), and Write (`register-variant`,
  `unregister-variant`, `record-as-variant`, gated on
  `--allow-writes`). Lands as Stage 7 of the Story epic (`rf2-tgci`).
  See [`tools/story-mcp/README.md`](./story-mcp/README.md).

- **`tools/template/`** — `day8/re-frame2-template`. The front-door
  scaffolding tool for new re-frame2 apps (rf2-lrtc; rf2-dolpf). A
  [deps-new](https://github.com/seancorfield/deps-new) template; users
  invoke it via `clojure -Tnew create :template
  io.github.day8/re-frame2-template :name acme/my-app` and receive a
  working CLJS app wired against the alpha `day8/re-frame2-*` coords.
  Three substrate variants (Reagent / UIx / Helix) selectable via the
  top-level `:substrate :uix|:helix` k/v. Distribution is git-coord,
  not Clojars (rf2-dolpf §2.5).

  Note: `tools/template/` is **build-time only**; the template jar is
  never on a consumer's runtime classpath, so the bundle-isolation
  contract holds trivially. It is the one tool in this directory whose
  job is generation rather than runtime observation.

## Per-tool `spec/` folder convention (rf2-bfax)

Every tool ships a local `spec/` folder, complete enough that the tool
could *almost* be one-shotted from it. Same posture the project-level
[`spec/`](../spec/) has to the framework: the spec/ folder is the
normative contract; `src/` is its downstream consequence.

Why each tool needs its own:

- **Design decisions are preserved in committed form.** Decisions
  iterated across multiple sessions (locked options, dropped
  alternatives, the reasoning trail) survive in the repo rather than
  in `findings/` (which is gitignored and local-only).
- **Audit findings are preserved.** Research that informed the design
  (Storybook surveys, XState parity audits, etc.) gets committed into
  `tools/<tool>/spec/findings/` so it isn't lost when the local
  `findings/` directory is cleaned up.
- **One-shot-able.** A future contributor (human or AI) can read the
  spec folder and rebuild the tool with high fidelity.

Typical structure:

```
tools/<tool>/spec/
├── 000-Vision.md             ; goals, hard constraints, non-goals
├── 001-<area>.md             ; per-capability normative docs
├── 002-<area>.md
├── ...
├── Principles.md             ; the tool's design principles
├── API.md                    ; consolidated public API surface
├── DESIGN-RATIONALE.md       ; WHY each major call was made
└── findings/                 ; committed audit / research content
    ├── <research-doc>.md
    └── ...
```

The shape mirrors the project-level [`spec/`](../spec/) — `000-Vision`
+ numbered capability docs + `Principles` + `API` + (here) an explicit
`DESIGN-RATIONALE` and committed `findings/`. Add `MIGRATION.md` and
`Spec-Schemas.md` per-tool if the tool warrants them.

The convention does **not** confuse with the project-level `spec/`.
That folder is the framework's normative contract. The tool-level
`spec/` is the tool's normative contract — bounded scope, downstream
of the framework's spec.

### Tool-shared contracts indexed back to `spec/Ownership.md`

Where a contract surface is *shared across the tool tier* — typical
example: the cross-MCP wire vocabulary, privacy filter, and token-
budget cap pipeline shared by `re-frame2-pair-mcp` and `story-mcp` — its
**canonical home stays with the tool artefact**
(`tools/mcp-base/spec/`) rather than being lifted into
the project-level `spec/`. This is the [`spec/README.md` §Canonical
homes outside `/spec`](../spec/README.md#canonical-homes-outside-spec)
rule (rf2-0hs5t.3 (a)), and the surface is indexed back to the
framework via a row in [`spec/Ownership.md`](../spec/Ownership.md).

Two rules apply:

1. **One canonical home.** The tool's `spec/` is the single source
   of truth for the shared contract. Other tools cite it; they do
   not redefine it. Drift detection is the same as for in-tree
   surfaces — a second normative definition is a corpus bug.
2. **Indexed from `spec/Ownership.md`.** The contract surface gets
   a row in the framework's ownership matrix with the canonical
   home cell pointing at the tool's spec path. This keeps the
   "where does X live?" question single-sourced even when the
   answer is "downstream of `/spec`."

The rule applies to genuinely-shared tool contracts. Single-tool
contracts (the `tools/causa/spec/...` panel inventory, the
`tools/story/spec/...` Story format) stay with their tool and are
not indexed in the framework's `spec/Ownership.md` — they are not
*framework-level* surfaces.

## In design / planned

Entries below are **in design** — the spec is being shaped, but no
runtime implementation has landed on disk yet. They will graduate to
"Shipped" once their `src/` tree gains substance; empty scaffolding is
not created up-front.

- **`tools/machines-viz-mcp/`** — `day8/re-frame2-machines-viz-mcp`.
  A likely separate MCP surface for machine viz. Confirmed separation
  pending the first cut. (The chart-component role originally scoped
  to `tools/machines-viz/` was superseded by Causa's Machine Inspector
  panel per PR #1400/#1402/#1407; the pure Mermaid emitter relocated
  to `implementation/machines/src/re_frame/machines/mermaid.cljc` per
  rf2-yamkm.)

## Distinction from `skills/`

`skills/` and `tools/` look superficially similar — both sit at the top level,
both are downstream of the spec — but they are different artefacts entirely:

| | `skills/` | `tools/` |
|---|---|---|
| Artefact kind | Markdown agent definitions (Claude Code skills) | CLJS jars |
| Consumption model | Loaded by an AI agent at invocation time | `:require`d by a dev build |
| Build pipeline | npm + Claude Code Plugin packaging | Clojars publish via the multi-artefact release pipeline |
| Runtime substrate | The agent (Claude Code) | A CLJS runtime + the host's frame(s) |

They are kept separate intentionally — different lifecycles, different
consumption models, different publication channels.

## See also

- [Spec 009 — Instrumentation](../spec/009-Instrumentation.md) — the API surface tools consume.
- [Spec Tool-Pair](../spec/Tool-Pair.md) — runtime contract for pair-shaped AI tools (re-frame-pair and equivalents).
- [Spec 007 — Stories](../spec/007-Stories.md) — the contract `tools/story/` will implement.
- [`implementation/adapters/README.md`](../implementation/adapters/README.md) — the per-jar layout pattern this directory mirrors.
- [`skills/README.md`](../skills/README.md) — for the markdown-agent flavour of downstream artefacts.
