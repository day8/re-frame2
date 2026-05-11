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

A top-level `tools/deps.edn` and `tools/shadow-cljs.edn` may appear later
as build coordinators (matching the pattern in `implementation/`) once
there's more than one tool to coordinate. Not needed yet.

## Shipped

- **`tools/template/`** — `day8/clj-template.re-frame2`. The front-door
  scaffolding tool for new re-frame2 apps (rf2-lrtc). A
  [clj-new](https://github.com/seancorfield/clj-new) template; users
  invoke it via `clojure -X:project/new :template re-frame2 :name
  acme/my-app` and receive a working CLJS app wired against the alpha
  `day8/re-frame2-*` coords. Three substrate variants (Reagent / UIx /
  Helix) selectable via `:edn-args '[:substrate ...]'`.

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

## Future homes

Each entry below is **in design** — none implemented yet. They land here as
implementation work begins; empty scaffolding is not created up-front.

- **`tools/story/`** — `day8/re-frame2-story`. Storybook-flavoured component
  playground with frame-aware controls, machine-state visualisation, and
  time-travel scrubbing. Spec surface: [`spec/007-Stories.md`](../spec/007-Stories.md).

- **`tools/story-mcp/`** — `day8/re-frame2-story-mcp`. A separate MCP agent
  surface for story (per the rf2-m6tu §6.1 separation: human-facing tool and
  agent-facing surface ship as distinct jars so the MCP server can be loaded
  without dragging the entire story UI into the classpath).

- **`tools/machines-viz/`** — `day8/re-frame2-machines-viz`. XState-style
  state-chart visualisation for machines registered via `reg-machine`.
  Consumes the trace bus and per-frame machine snapshots.

- **`tools/machines-viz-mcp/`** — `day8/re-frame2-machines-viz-mcp`. Likely
  a separate MCP surface for machine viz, mirroring the story / story-mcp
  split. Confirmed separation pending the first cut.

- **`tools/10x/`** — `day8/re-frame2-10x`. re-frame-10x v2 — the interactive
  devtools panel for the runtime. This entry partially answers
  [`rf2-tijr`](../.beads/) (the repo-placement question): 10x v2 lives here.
  The vendoring-replaced-by-multi-frame-isolation work tracked by that bead
  remains open.

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
