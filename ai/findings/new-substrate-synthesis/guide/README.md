# The re-frame2 UI Guide

re-frame2 UI is the view layer for re-frame2: views are hiccup, handlers are event
vectors, and the compiler ships code with no interpreter, no hooks ceremony, and no
manual memoization. This guide teaches the library as a user. Design rationale lives
one directory up; the dataflow half — events, effects, app-db — is the
[core guide](../../../../docs/core/introduction.md)
(`docs/core/`).

## How to read this guide

Chapter **numbers are stable filenames**; the sidebar order follows them. The
**recommended learning order** groups differently:

- **The reactive loop** — getting started, then state and events (reads and writes),
  then views (structure), frames (scoping), and the worked app (synthesis). Views come
  after state and events so `defview` examples assume `(sub …)` and event vectors are
  already familiar.
- **Operate the app** — testing, debugging, performance.
- **Ship and understand** — server rendering, then how it works (optional; read when you
  want to trust the claims, not before you can use the library).

**Porting from Reagent?** Read events before state — data handlers are the biggest habit
change. **Adding SSR?** Frames before server rendering.

**Stage honesty.** The library ships in stages (S1–S7). Everything unmarked on these
pages is shipped on main today. That covers the Stage-1 compiler slice (compiled
templates, props, roots and mounting, `ui/raw` / `ui/html` / `ui/spread`, the Tier-1
test core) and the Stage-2 reactive core that has since landed: `sub`-driven repaints,
Tier-1 `sub` reads, `frame-root`'s runtime ENSURE preflight, the `ui/adapter` you hand
`rf/init!`, the `(frame)` ops map, and the mounted Tier-3 test surface (`with-root`,
`query`, `flush!`). A compact marker like *(lands S3 — committed handlers)* flags a
surface whose contract is final but whose implementation lands in a later stage; the
spelling and semantics shown are the ruled contract either way. `lease` is exported on
main today; view-level lease semantics confirm at S3. Wave-2 names (`ui/element`,
`ui/view`, `ui/portal`, `re-frame.ui.data/render`) are not v1 and only ever appear with
that qualifier.

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

**Core guide cross-links.** This guide owns the view layer; these core chapters own the
dataflow it plugs into:

| Topic | Core chapter |
|---|---|
| app-db, the event pipeline | [introduction](../../../../docs/core/introduction.md), [app-db](../../../../docs/core/app-db.md) |
| `reg-sub`, derivation graphs | [subscriptions](../../../../docs/core/subscriptions.md) |
| `reg-event`, fx | [effects](../../../../docs/core/effects.md) |
| Frame isolation (dataflow side) | [frames](../../../../docs/core/frames.md) |
| Handler/sub unit tests | [testing](../../../../docs/core/testing/index.md) |

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