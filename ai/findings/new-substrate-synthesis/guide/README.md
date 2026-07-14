# The re-frame2 UI Guide

re-frame2 UI is the view layer for re-frame2: views are hiccup, handlers are event
vectors, and the compiler ships code with no interpreter, no hooks ceremony, and no
manual memoization. This guide teaches the library as a user. Design rationale lives
one directory up, and the dataflow half of the story — events, effects, app-db — is
the core guide's (`docs/core/`).

| Chapter | You'll learn |
|---|---|
| [01 — Getting started](01-getting-started.md) | Install, mount, first interactive view, first headless test |
| [02 — Views](02-views.md) | `defview`, templates, styling, props, children, lists, presence (exit animations), custom elements |
| [03 — State](03-state.md) | `sub` (including conditional reads), `local`, `effect`, `lease` |
| [04 — Events](04-events.md) | Event vectors, placeholders, `ui/event`, forms & controlled inputs, the callback decision table |
| [05 — Frames](05-frames.md) | `frame-root`, `frame-provider`, multi-frame pages, holds |
| [06 — Debugging](06-debugging.md) | Render causes, the interaction surface, Xray navigation, pairing with an AI |
| [07 — Performance](07-performance.md) | What you never do, the little you do |
| [08 — Server rendering](08-ssr.md) | JVM rendering, roots and frames, root identity, the host tier, static output |
| [09 — Testing](09-testing.md) | Headless view tests, selectors, `flush!`, Story |
| [10 — A worked app](10-worked-app.md) | The counter grown into a dashboard: state shape, tiles, narrow subs, a live tile, tests |
| [11 — How it works](11-how-it-works.md) | The mechanism: the compiler, the build digest, the reactive core, two emitters, the dev/prod split |

**Stage honesty.** The library ships in stages (S1–S7). Everything unmarked on these
pages is shipped on main today. That covers the Stage-1 compiler slice (compiled
templates, props, roots and mounting, `ui/raw` / `ui/html` / `ui/spread`, the Tier-1
test core) and the Stage-2 reactive core that has since landed: `sub`-driven repaints,
Tier-1 `sub` reads, `frame-root`'s runtime ENSURE preflight, the `ui/adapter` you hand
`rf/init!`, the `(frame)` ops map, and the mounted Tier-3 test surface (`with-root`,
`query`, `flush!`). A
compact marker like *(lands S3 — committed handlers)* flags a surface whose contract is
final but whose implementation lands in a later stage; the spelling and semantics shown
are the ruled contract either way. The *(lands S2)* marker survives on just one
straggler — `lease`, whose view-level semantics confirm at S3. Wave-2 names
(`ui/element`, `ui/view`,
`ui/portal`, `re-frame.ui.data/render`) are not v1 and only ever appear with that
qualifier.

**Reading the examples.** Code on these pages assumes
`(:require [re-frame.core :as rf] [re-frame.ui :as ui :refer [defview sub]])`, with the
other body forms (`local`, `effect`, `lease`, `frame`, `presence-phase`) referred bare
the same way — one convention, every chapter.

**Coming from Reagent?** Your hiccup transfers whole. Reads are `(sub [:q])` — a value,
nothing to deref. Handlers are usually vectors, not closures. One component form, no
ratoms: app state lives in app-db, keystroke ephemera in `local`.

**Coming from React / UIx / Helix?** Components, memo, context, and external stores are
all here — the compiler writes them. You will not write a hook, a deps array, a
`useCallback`, or a `memo` wrapper again. (And unlike hooks, `sub` may sit in a branch.)
Where your reflexes reach for a hook, this is the map:

| Looking for | Here |
|---|---|
| `useState` | `local` ([03](03-state.md)) — or app-db, the moment anything else cares |
| `useEffect`, DOM work | `effect` ([03](03-state.md)) |
| `useEffect`, data fetching | events + resources; views declare `lease` ([03](03-state.md)) |
| `useCallback` / `memo` / deps arrays | gone — every view memoizes on value-equal props ([07](07-performance.md)) |
| Context | props, subs, and frame scoping ([05](05-frames.md)); React context itself only at foreign boundaries ([02](02-views.md)) |
| Suspense / loading states | resource status values you branch on ([03](03-state.md)) |
| Error boundaries | `ui/error-boundary` ([02](02-views.md)) *(lands S3)* |
| Refs | `(ui/raw-fn set-node)` + `effect` ([03](03-state.md)) |
| Portals | wave-2 (`ui/portal`) — not v1 |

---

**About these docs.** The examples are written to run as CI fixtures, and coverage
grows with the stages: guide 09's Tier-1 core — the verbatim render, the
intent-through-attrs respelling, the dispatch → sub → re-render loop, a seeded-state
render, and the sub-override door — already runs as fixtures in the library's JVM
suite, and the remaining chapters are lifted as their stages land, per the
fixture-pipeline draft one directory up (`drafts/guide-fixture-pipeline.md`). A fence
that is deliberately schematic — an elided fragment, or a wave-2 surface — says so in
a `;; guide:no-fixture` comment on the fence itself. The bar does not move with the
coverage: if a guide example needs internals explained, that's an API bug, not a
documentation problem.
