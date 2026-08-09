# Coverage proof suites

[`../synth-codex.md`](../specification.md#7-complete-use-case-coverage) owns capability placement, phase deliverables, and release policy. This file owns the concrete proof suites: the finite witnesses and artifacts that demonstrate coverage. It does not maintain a second roadmap or paraphrase phase outcomes.

## Canonical suites

| Suite | Scope | Green evidence |
|---|---|---|
| Package and facade | Standalone artifact, public namespaces, root lifecycle, HMR, production build, one facade and optional reachability | Clean consumer builds without benchmark-tree imports; dependency and sentinel tests prove production erasure |
| Governance and instruments | Proposed product budgets, K1 proposal, red boundary shell, K3 scoreboards, reference hardware, estimands, controls and refusal rules | Product budgets are ratified by the named decider; K1 is ratified or rejected at its sitting; shell meets its byte-exact line or has its own prospective disposition; K3 denominators remain separate; sabotaged instruments refuse |
| Reactive kernel | Commit-owned dynamic reads, real abandonment, multiple roots, same-id reincarnation, HMR, Activity/Suspense, substrate and exact cleanup | Mutation/sabotage suite reports zero stale reads, tears, cross-frame operations or residue across the exercised population |
| Control and browser | Text/textarea, checkbox/radio/select, file, number/date/range, contenteditable, composition, revision, normalization, caret/selection, autofill and reset/FormData | Chromium, Firefox and WebKit DOM/browser suite with same-turn echo, published mechanics and deliberate failure controls |
| Ordinary application | Routes, keyed lists, article edit, async mutation, errors, reset, pagination, accessibility, code splitting and multiple frames | Todo and RealWorld-class flows use only public surfaces and contain no artificial boundary introduced for the harness |
| Testing and Xray | L0–L4 facade, restricted semantic assertions, mounted DOM, browser truth, evidence schema, causal projection, privacy/loss and erasure | Supported test namespace and one mutation-proved causal trace; opaque/unknown/loss states remain honest; production sentinels absent |
| Host and native interop | ReactNode positions, providers, compound components, render props, refs, portals, outward embedding, virtualizer and imperative SDK | Every row of the [canonical native-tier checklist](hot-path-architecture.md#canonical-native-tier-acceptance-checklist) plus host ownership/cleanup witnesses is green |
| SSR and hydration | Every public surface inventory id, its Render or Client-only policy, and the common hydration/refusal states | Every id points to a green row in the [canonical SSR/hydration matrix](react-compatibility-notes.md#public-surface-ssrhydration-matrix) |
| Bulk and economics | Sparse, broad replacement, reorder and controlled edit at 100, 300 and 1,000 items; retained shell/read heap and teardown | Qualified topology-tournament results meet ratified user-visible/comparative budgets and scaling shape; the boundary shell meets its operative line or named disposition; teardown is zero |
| Optional products | Resource-demand verdict, forms, overlays, presence, routing/resource recipes, migration and caller-gated Node service | Each product has a named consumer, deciding witness, kill condition and zero unused reachability; Node additionally proves request isolation, termination and caller latency |
| Adoption and migration | Install, development, diagnostics, production build, upgrade, donor-tool disposition and real pilot use | Two independent pilot applications ship substantial screens without bespoke support; no primary tool/product path depends on an experimental donor surface |

Warm-allocation evidence is deliberately absent from the release suites until its instrument qualifies. No allocation claim ships in the meantime; retained heap, shell, teardown, bulk, and user-visible budgets remain mandatory.

## Concrete witness fixtures

### Ordinary application

- Todo and RealWorld-class flows with dynamic reads, event data, routing, mutation state, errors and controlled editing.
- A four-field editor and 100-cell grid with published per-keystroke state-write, recomputation, boundary-run, commit and visible-echo mechanics.
- A typeahead resource witness covering debounce, supersession, refresh-with-data, cancellation and abandoned render.

### React and host ownership

- A compound library with provider, named ReactNode slots and a render prop lowered only through the explicit conversion.
- A blessed virtualizer preserving keyboard access, focus and selection.
- An imperative editor/map/chart with balanced acquire/release under StrictMode, remount, retry and thrown render.

### Browser and platform

- Chromium, Firefox and WebKit control/IME behavior; structural accessibility assertions plus browser focus and axe checks.
- Lazy load, fallback, error, retry and HMR through the Hicasso boundary-ABI bridge.
- The complete public-surface SSR/hydration inventory, including deliberate mismatch and overlapping-root witnesses.

### Bulk and economic

- Fine, coarse, chunked/windowed and native-virtualized topology arms over the same data, DOM/intent behavior and operation script.
- Sparse update, broad replacement, reorder and controlled edit at every registered size, with instrument eligibility decided before product data publishes.
- Read-free shell, retained reads and teardown measured on the named substrate segments; no per-read average hides the shell.

### Adoption and migration

- One migration-shadow witness compares canonical DOM and intent behavior before any rewriting codemod is trusted.
- Two real pilot applications exercise installation, hot reload, diagnostics, production build and upgrade without framework-author intervention.

## Boundary facts

- Hicasso mints React functions; it has no late-bound view-id registry. Code splitting uses a small `React.lazy` adapter for the private boundary props ABI, declared outside render and composed with Suspense/error handling.
- Read-free does not imply hydration-free. Zero-hydration islands require a separate island/root architecture.
- A full server or JVM renderer is a second implementation. The semantic assertion harness remains intentionally restricted and React server bytes remain authoritative.
- Progressive Suspense SSR requires React streaming APIs. A `renderToString` proof supports only a non-streaming first product.
- Hicasso-specific User Timing does not replace adapter-neutral lifecycle/read/source evidence.
- Native semantics and rent are governed only by the [native-boundary design law](design-laws.md#native-boundary); this proof lane applies its canonical checklist without restating it.

## Explicit refusals

No compiled Hiccup mode, automatic hot promotion, deep conversion inside arbitrary host data, unmarked JavaScript heads, automatic fetching/Suspense from reads, fake Hooks runtime, generic local state/effect/ref DSL, UIx clone, RSC/Flight ownership, or core design system.
