# tools/

This directory houses CLJS dev / inspection tools that consume re-frame2's
instrumentation API. Each tool ships independently, on its own release
cadence, and is intentionally kept out of the runtime path that
production consumers depend on. Most ship as their own Maven artefact;
the publication model and its deliberate exceptions (git-coord, npm,
source-path-only) are detailed under [Per-tool layout](#per-tool-layout).

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
  artefacts where its job demands it — for example, a machine visualiser will pull
  `implementation/machines/`). The dependency flows tool → implementation.
- The reverse is forbidden. Nothing under `implementation/` may `:require`
  anything under `tools/`. Adding such a dep would haul tooling weight
  (DOM-heavy UI, monaco, story metadata, MCP server bits) into every
  consumer's production bundle.
- The contract is enforced structurally: `tools/` is a separate
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

Most tools publish to Clojars under `day8/re-frame2-<tool>` (the
Node-side MCP servers publish to npm instead — `@day8/re-frame2-pair-mcp`).
Two tools are deliberate exceptions to the Clojars publish model:

- `tools/template/` distributes by git-coord, not Clojars
  (rf2-dolpf §2.5) — the published artefact is a tagged commit on the
  template repo, invoked via `clojure -Tnew create`. See the `template`
  entry under "Shipped" below.
- `tools/testbed-support/` is not a published jar at all — it
  has no Clojars/publish coord (its local `deps.edn` exists only for the
  JVM endpoint tests, not as a packaging surface) and is consumed only as
  an extra source path wired into the testbed builds. See its entry under
  "Shipped" below.

A top-level `tools/deps.edn` (rf2-nuuk3) is the tool tier's CLASSPATH
coordinator: it declares each tool as a `:local/root` dep so a REPL
started from `tools/` sees the whole tier on one classpath. That is all
it is — it carries no test alias, and there is no `tools/shadow-cljs.edn`.
Both retired (rf2-6r9j.139 / rf2-6r9j.140): each was a second,
hand-written inventory standing beside the per-tool configuration CI
actually runs, each had drifted from it, and neither had a gate that
would notice.

- **JVM tests.** The tool tier's JVM command is
  `scripts/test-jvm-tools.sh`, run from the repo root. Its roster is the
  maintained one — `scripts/check_jvm_lane_rosters.py` holds it against
  the required CI jobs — and it covers `xray`, `machines-viz`, `story`,
  `story-mcp`, `mcp-base`, `testbed-support`,
  `mcp-conformance/wire-vocab` and `template`. A single artefact stays
  `cd tools/<tool> && clojure -M:test`, which is what the per-tool CI
  gates run. Xray's JS-only `.cljs` specs (the DOM + Reagent shell) are
  not JVM-loadable at all and run under the consolidated
  `implementation/shadow-cljs.edn` `:node-test` build instead; its
  `.clj`/`.cljc` slice — including the EP-0011 uniform reply-envelope
  tooling-projection coverage at
  `xray/test/.../panels/reply_envelope_cljs_test.cljc` — runs on the JVM
  (rf2-f2tkbt).
- **CLJS builds.** Every tool with a CLJS surface owns a
  `shadow-cljs.edn` beside its `deps.edn`, and those are the configs the
  npm scripts and CI jobs invoke: `re-frame2-pair-mcp/` (`server`,
  `server-test`, `descriptor-gen`), `mcp-base/` (`cljs-test`) and
  `machines-viz/` (`machines-viz-node-test`). `xray` has no shadow build
  of its own — its CLJS tests run via `implementation/shadow-cljs.edn` —
  and `template`'s `resources/` tree is placeholder-bearing and not valid
  ClojureScript, so it is kept off every CLJS classpath (see
  `tools/deps.edn` → "Why template is NOT in the base `:deps` map").

## Shipped

The tools below have substantial implementations on disk and are
actively developed against. Maturity varies (the alpha framework is
itself pre-1.0); the common factor is that the artefact exists, is
wired into the build, and consumers can use it today.

- `tools/xray/` — `day8/re-frame2-xray`. Xray, the in-app
  devtools panel for re-frame2 — structural successor to
  re-frame-10x (renamed per `tools/xray/spec/DESIGN-RATIONALE.md`
  Lock #1; the standalone 10x port is now redirected into Xray per
  rf2-jt6t / #556). Preloaded into dev builds via `:preloads`, which is
  dev build configuration — that build placement, not a gate inside
  Xray, is what keeps it out of a release build. The manual
  `init!` / mount path carries no `goog.DEBUG` gate, so a host
  installing Xray from app code keeps the `:require` and the calls in a
  dev-only namespace; see
  [`tools/xray/README.md`](./xray/README.md) §Bundle isolation.
  Panel inventory: event-detail, causality graph, time-travel
  scrubber, slice-centric app-db, machine inspector, schema-violation
  timeline, hydration debugger, issues ribbon, AI co-pilot rail. See
  [`tools/xray/spec/000-Vision.md`](./xray/spec/000-Vision.md).

- `tools/machines-viz/` — `day8/re-frame2-machines-viz`. The
  substrate-agnostic `MachineChart` state-chart component (xyflow +
  elkjs in-page renderer: nested compound states, parallel regions,
  `:spawn-all` join + cancellation-cascade overlays, `:after` countdown
  rings, a UIx adapter) plus a read-only share-URL viewer page
  (`public/viewer.html` + `viewer.cljs`, built by the
  `machines-viz-viewer` Shadow build and hosted by the consumer — nothing
  in this repository deploys it, rf2-8m344). Also ships the pure-data
  Mermaid `stateDiagram-v2` emitter (relocated out of the runtime
  `machines` artefact per rf2-sqhqu so the engine stays pure), SCXML
  import/export round-trip, an AI-generate-a-machine seam, and PNG / SVG
  / Mermaid / share-URL exporters. Embedded by Xray's Machine Inspector
  panel; bundle-isolated like the rest of `tools/`. See
  [`tools/machines-viz/README.md`](./machines-viz/README.md).

- `tools/testbed-support/` — a small dev-only support library
  (2 namespaces: `re-frame.testbed.story-host` and the
  security-sensitive `re-frame.testbed.open-in-editor-server`) the Xray /
  Story browser testbeds share.
  `story-host/mount-with-hash-routing!` owns the
  live-app ↔ Story-shell hash-toggle host harness the showcase testbeds
  share. `open-in-editor-server/handler` is the JVM-side
  `POST /__rf-open-in-editor` endpoint every testbed `:dev-http` entry
  runs; it resolves a classpath-relative source coordinate against the
  live JVM source paths at request time, so "open in editor" needs no
  checkout path configured anywhere. It is
  not a published jar — no Clojars/publish coord; its local `deps.edn`
  exists for JVM tests. It's wired into
  the testbed builds as an extra source path in
  `implementation/shadow-cljs.edn`. Bundle-isolated (nothing under
  `implementation/` `:require`s it). See
  [`tools/testbed-support/README.md`](./testbed-support/README.md) for usage and
  [`its spec`](./testbed-support/spec/README.md) for the host/editor contracts.

- `tools/mcp-base/` — `day8/re-frame2-mcp-base`. Shared primitives
  for the MCP servers (`re-frame2-pair-mcp`, `story-mcp`): 13
  namespaces — `vocab` (wire-vocabulary constants `:rf.mcp/*`,
  `:rf.size/*`, JSON-RPC error codes), `sensitive` (spec/009 §Privacy
  default-suppress filter), `elision` (`:rf.size/large-elided`
  wire-boundary walker), `args` (MCP argument coercion helpers),
  `diff-encode` (path-keyed structural diff, rf2-1wdzp),
  `section-grouping` (patch-list → path-headed cluster sections,
  rf2-qeous), `overflow` (overflow-marker payload shape, rf2-rvyzy),
  `cap` (wire-boundary token-budget cap pipeline, rf2-eyelu), `cursor`
  (shared cursor-pagination machinery, rf2-ee38b.19), `envelope`
  (indicator-field `with-indicators` splice, rf2-ee38b.19),
  `descriptor-manifest` (tool-descriptor manifest generator +
  drift-check, rf2-sofwv), `egress` (EP-0015 profile-adoption egress
  filter, rf2-qus09h), and `dedup` (cross-MCP dedup-envelope, lifted
  rf2-ttspi7). Pure `.cljc` with zero runtime deps beyond
  `org.clojure/clojure`. Per rf2-vw4sq. See
  [`tools/mcp-base/spec/README.md`](./mcp-base/spec/README.md).

- `tools/mcp-conformance/` — End-to-end MCP-client conformance
  harness for the re-frame2 MCP servers (`re-frame2-pair-mcp`, `story-mcp`).
  Pure Node test fixtures: drives each server through the official
  `@modelcontextprotocol/sdk` `Client` so SDK-strict schema
  regressions surface before a real consumer attaches. Also hosts
  the cross-MCP wire-vocabulary conformance fixtures
  (`wire-vocab/`), the cross-MCP tool-naming convention
  (`NAMING.md`), and the cross-MCP token-budget posture
  (`TOKEN-BUDGETS.md`). Per rf2-cum40 / rf2-j2z7o / rf2-mzf1r /
  rf2-ll0yq. Spec posture: documented exemption from the per-tool
  `spec/` convention — the conformance contracts live on the
  servers being verified, not on the harness; the harness's
  normative contract is its test corpus + the 3 top-level docs
  (`NAMING.md`, `TOKEN-BUDGETS.md`, `wire-vocab/README.md`) plus
  the README. Bundle-isolated by construction (no CLJS sources,
  Node-side only). See
  [`tools/mcp-conformance/README.md`](./mcp-conformance/README.md).

- `tools/re-frame2-pair-mcp/` — `@day8/re-frame2-pair-mcp`. A Node-based
  stdio JSON-RPC MCP server (compiled from ClojureScript via
  shadow-cljs) that pair-programs with a live re-frame2 app over a
  persistent nREPL socket. The one implementation of every pair
  operation — it replaced (and the project has since removed) the
  earlier bash-shim → babashka → nREPL transport.
  It provides runtime discovery, addressed state/view reads, pull-based
  observation, event execution and gated writes. The
  [owning catalogue](./re-frame2-pair-mcp/spec/003-Tool-Catalogue.md)
  documents the current operations and their authority gates; its executable
  registry owns the ordered wire inventory. Published to npm as
  `@day8/re-frame2-pair-mcp`. See
  [`tools/re-frame2-pair-mcp/README.md`](./re-frame2-pair-mcp/README.md).

- `tools/story/` — `day8/re-frame2-story`. A Storybook-class
  component playground for re-frame2, implementing
  [`spec/007-Stories.md`](../spec/007-Stories.md). Each variant runs
  in its own frame (`spec/002`), is EDN-shaped data (not a function),
  ships with schema-derived controls (`spec/010`),
  assertion-vocabulary play sequences, and a content-hashed snapshot
  identity for visual-regression keying. Embeds Xray's epoch panel
  as a registered story panel. See
  [`tools/story/README.md`](./story/README.md).

- `tools/story-mcp/` — `day8/re-frame2-story-mcp`. JVM-side stdio
  JSON-RPC MCP server that exposes Story's read (and gated write)
  surface as MCP tools: inspect stories, preview/run variants, read evidence,
  and author registrations behind `--allow-writes`. The
  [owning registry contract](./story-mcp/spec/002-Tool-Registry.md)
  documents the current catalogue without a second tool-name/count inventory
  here. Lands as Stage 7 of the Story epic (`rf2-tgci`).
  See [`tools/story-mcp/README.md`](./story-mcp/README.md).

- `tools/template/` — `day8/re-frame2-template`. The front-door
  scaffolding tool for new re-frame2 apps (rf2-lrtc; rf2-dolpf). A
  [deps-new](https://github.com/seancorfield/deps-new) template; users
  invoke it via `clojure -Tnew create :template
  io.github.day8/re-frame2-template :name acme/my-app` and receive a
  working CLJS app wired against the alpha `day8/re-frame2-*` coords.
  That `io.github.*` invocation is the post-split target; the current checkout
  uses the [local-development invocation](./template/spec/API.md#local-development-invocation),
  including the documented pre-publication coordinate setup before watch.
  Two substrate variants (Reagent / UIx) selectable via the top-level
  `:substrate :uix` k/v. Distribution is git-coord, not Clojars
  (rf2-dolpf §2.5).

  Note: `tools/template/` is build-time only; the template jar is
  never on a consumer's runtime classpath, so the bundle-isolation
  contract holds trivially. It is the one tool in this directory whose
  job is generation rather than runtime observation.

## Per-tool `spec/` folder convention (rf2-bfax)

Every tool ships a local `spec/` folder, complete enough that the tool
could almost be one-shotted from it. Same posture the project-level
[`spec/`](../spec/) has to the framework: the spec/ folder is the
normative contract; `src/` is its downstream consequence.

Why each tool needs its own:

- Design decisions are preserved in committed form. Decisions
  iterated across multiple sessions (locked options, dropped
  alternatives, the reasoning trail) survive in the repo rather than
  in `findings/` (which is gitignored and local-only).
- Audit findings are preserved. Research that informed the design
  (for example Storybook surveys and XState parity audits) gets committed into
  `tools/<tool>/spec/findings/` so it isn't lost when the local
  `findings/` directory is cleaned up.
- One-shot-able. A future contributor (human or AI) can read the
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

The convention does not confuse with the project-level `spec/`.
That folder is the framework's normative contract. The tool-level
`spec/` is the tool's normative contract — bounded scope, downstream
of the framework's spec.

### Tool-shared contracts indexed back to `spec/Ownership.md`

Where a contract surface is shared across the tool tier — typical
example: the cross-MCP wire vocabulary, privacy filter, and token-
budget cap pipeline shared by `re-frame2-pair-mcp` and `story-mcp` — its
canonical home stays with the tool artefact
(`tools/mcp-base/spec/`) rather than being lifted into
the project-level `spec/`. This is the [`spec/README.md` §Canonical
homes outside `/spec`](../spec/README.md#canonical-homes-outside-spec)
rule (rf2-0hs5t.3 (a)), and the surface is indexed back to the
framework via a row in [`spec/Ownership.md`](../spec/Ownership.md).

Two rules apply:

1. One canonical home. The tool's `spec/` is the single source
   of truth for the shared contract. Other tools cite it; they do
   not redefine it. Drift detection is the same as for in-tree
   surfaces — a second normative definition is a corpus bug.
2. Indexed from `spec/Ownership.md`. The contract surface gets
   a row in the framework's ownership matrix with the canonical
   home cell pointing at the tool's spec path. This keeps the
   "where does X live?" question single-sourced even when the
   answer is "downstream of `/spec`."

The rule applies to genuinely-shared tool contracts. Single-tool
contracts (the `tools/xray/spec/...` panel inventory, the
`tools/story/spec/...` Story format) stay with their tool and are
not indexed in the framework's `spec/Ownership.md` — they are not
framework-level surfaces.

## In design / planned

Entries below are in design — the spec is being shaped, but no
runtime implementation has landed on disk yet. They will graduate to
"Shipped" once their `src/` tree gains substance; empty scaffolding is
not created up-front.

- `tools/machines-viz-mcp/` — `day8/re-frame2-machines-viz-mcp`.
  A likely separate MCP agent surface for the shipped
  [`tools/machines-viz/`](#shipped) chart tool. Spec-only — the
  separation is being shaped, but no `machines-viz-mcp/` directory has
  landed on disk yet. (Note: the chart component itself, the Mermaid /
  SCXML emitters, and the read-only viewer all ship today in
  `tools/machines-viz/`, listed under "Shipped" above; this entry is the
  planned MCP surface only.)

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
