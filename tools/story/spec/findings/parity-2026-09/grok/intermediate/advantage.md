# Phase D — Advantage thesis

Tried to disprove each candidate against Storybook 10.6's *practical* workflow (OSS + official addons + Chromatic where that is the job). Tiers: `BUILT` / `SPECIFIED` / `IMAGINED`.

## 1. Jobs comparators do poorly *because of substrate*

| Job | Why JS workshops struggle | Disproof attempt | Keep? |
|---|---|---|---|
| Many **application** states at once, actually isolated | One preview iframe, one React tree, one implicit store. Grids are screenshots or re-mounts. | Histoire/Portfolio already show many *component* states. **Keep only the application-frame half.** | Keep (narrowed) |
| The example **is** the test **is** the share **is** the agent target | CSF is a JS module; tests, Chromatic, and MCP each re-encode. | Storybook Test + portable stories + CSF Factories are closing this. MCP still reads a running Storybook, not an EDN value. **Keep as "one EDN plan," not as "they have no tests."** | Keep (narrowed) |
| Mock **any** effect | No shared `reg-fx` seam → MSW + module mocks + decorator parade. | `sb.mock` (v10) and MSW cover HTTP/modules well. They still do not stub an arbitrary app fx with three data lines. | Keep |
| Failure has an **epoch tape** | Component workshops see DOM + actions log, not event→fx→sub→view. | Interactions panel + Chromatic diffs are good at *what changed visually*. They cannot reconstruct a six-domino cascade. | Keep |
| Honest cheap states | `useArgs` / painted props look as real as a loaded fixture. | Storybook users *know* args aren't the server. They lack a computed fidelity chip. Small but real. | Keep |
| Machine/resource-shaped UI states | Those are not native Storybook concepts. | You *can* wrap XState/TanStack in a story. It is not the workshop's unit. | Keep as latent |

Dropped: "MCP exists" as an advantage — **disproved**. `@storybook/addon-mcp` 10.6 is real (preview). Our edge, if any, is the **data artefact** and fail-closed JVM tools, not the presence of MCP.

Dropped: "many states at once" vs *devcards/Portfolio* — they already did the grid. Our edge vs *Storybook* remains.

## 2. Productized vs latent

| Claim | Tier | Productized? |
|---|---|---|
| Variant is a frame; side-by-side does not share app-db | `BUILT` (exercised login workspace) | Yes |
| Variant body is EDN; same artefact for canvas/docs/test/share/MCP | `BUILT` (registry + `variant->edn` + tutorial) | Yes for humans; agent loop split across two skills |
| `force-fx-stub` | `BUILT` (exercised) | Yes |
| Schema → controls | `BUILT` (`controls.cljs`) | **Latent as a front door** — UNDERMARKETED |
| Xray embed / epoch evidence | `BUILT` (exercised) | Yes |
| Record-don't-throw + `:cannot-run` | `BUILT` (code + ch.5; no-adapter test) | Yes |
| Fidelity ladder (labels) | `BUILT` (chips on sidebar) | Yes — the *badge*. The *upgrade* is the next row. |
| `reg-mode` combinatorial toolbar | `BUILT` | Yes; not Chromatic Modes (no baselines) |
| Failure promotion / generated runs | `SPECIFIED`; **implementation drops `:expect`** (`artifact->variant-body`) | **Dishonest until fixed.** Probe: fail → promote → `:pass` with empty assertions. |
| Fidelity upgrade snippet | `SPECIFIED`; snippet unreadable + `:extends` keeps pins | **Dishonest until fixed.** Labels on the sidebar are `BUILT`. |
| Global decorators | `BUILT` | UNDERMARKETED |
| Story rollup docs | `BUILT` | Yes |
| Machine-derived variant set | `IMAGINED` (generate.cljc is seed/shrink, not a machine walk) | Latent |
| Replay a production epoch as a variant | `SPECIFIED`/`IMAGINED` (artifact replay exists; production-epoch→variant is not a one-command path) | Latent |
| CSS/focus/portal isolation without iframes | **Not a win.** Div canvas. `BUILT` as in-page. Honest limit. | Do not productize an iframe clone unless a job demands it |
| One skill drives discover→run→fix | **Not productized.** Spec wants one path; skills split author vs pair. | Latent / self-inflicted ceremony |

## 3. The small set (user language)

`018` §3's five things still compose more power than Storybook's addon list:

1. a **selected variant** (a named application state);
2. **explicit inputs** (args, modes, stubs);
3. a **script**;
4. **expectations**;
5. **evidence** from the run (including Xray).

Keep this set. The jobs it currently fails are **keeping a failure as a regression**, **upgrading a cheap state without inheriting the pin**, and **getting a Storybook user to the first variant without a boot map**. Not a sixth primitive.

Do not add "MCP" or "iframe" as a sixth thing.

## 4. Limits of data-shaped artefacts

- Real HTTP, clocks, websockets: must be stubbed or the variant is not replayable. `force-fx-stub` + `:loaders-teardown` are the honest answers. Do not build a time-machine for the network.
- Non-serialisable args / fn-valued overrides: share must say view-only (`018` T4). Do not invent a serializer for closures.
- CSS/portals: in-page canvas will leak. Document it. An iframe preview is a Storybook noun unless a customer job (design-system CSS isolation) forces it.

## 5. A year from now (scenes)

**Design-system maintainer.** Opens a workspace of nine canonical UI states × light/dark. Each cell is a real frame. Schema-derived controls are the first thing they touch, not the fifth chapter. They still send pixels to Argos/Chromatic using `snapshot-identity`. They never asked us to host Chromatic.

**App developer.** `clojure -T` or a published coordinate plus three lines, `#/stories`, first variant on screen. They do not learn `install-canonical-vocabulary!`. They do not see two `:component` spellings.

**Agent.** One MCP (or one skill) lists variants, registers one, runs it, reads every assertion, patches the EDN body. The bytes it writes are the bytes the human sees in the sidebar. Storybook MCP can preview+test; it still writes a TS module.

**Tester.** A failing cell opens Xray on the epoch. They promote the run to a variant **that still fails for the same reason**. (Today that last sentence is false.) They never paste a Playwright repro beside the story.

Each scene is structurally awkward in Storybook (iframe+store, JS module encodings, no epoch). None requires a new subsystem.

## 6. Stop copying

Confirm `018` §3.1:

- Addon sprawl — stop.
- Opaque global decorator chains — we now *have* globals; keep them data-addressable, not `preview.ts` mystery.
- Visual examples disconnected from app state — the fidelity ladder is the antidote; keep it visible.
- Separate encodings for stories/tests/fixtures/repros — do not add a CSF importer, a second test runner, or a second debugger.

Additionally: do not copy Storybook MCP's "must have the workshop HTTP server up" without noticing our JVM catalogue reads are a real win — and do not copy our skill split as if it were an advantage.

## Opportunities worth pursuing (3–5)

1. **Promotion keeps the failed expectation** (J8/J9). Copy `:expect`; do not change `:extends` reuse. Same class as (2): stop using `:extends` as a mutation.
2. **Fidelity upgrade compiles and drops the pin** (J17). Comment off the last map line; do not inherit the pin.
3. **Install page compiles** (J1). 1-arity mount, named npm closure, keyword `:component`.
4. **One schema on the login card** (J3). Makes the derived-controls win visible. Schemas stay optional.
5. **Projections agree** — Explain args, evidence-row link, auto-grid count, run hashes. Then isolation/VR-recipe docs. **Do not** skip pixel capture on unchanged input-hash.

Decline for now: machine-generated variant sets; epoch-from-production; iframe canvas; MDX; CSF importer; in-process MCP; pixel scrubber; fourth Evidence mode; a generalized inheritance-removal API.

A proposal that adds a concept without removing one is not better.
