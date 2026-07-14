# The re-frame2 UI Guide

re-frame2 UI is the view layer for re-frame2: views are hiccup, handlers are event
vectors, and the compiler ships code with no interpreter, no hooks ceremony, and no
manual memoization. This guide teaches the library as a user. Design rationale lives
one directory up; the dataflow half — events, effects, app-db — is the
[core guide](../../../../docs/core/introduction.md)
(`docs/core/`).

## How to read this guide

Chapters are **numbered for stable filenames**, not reading order. Start with **Part I**
and follow the steps in sequence — each step assumes the ones before it.

### Part I — The reactive loop

Build reads, writes, view structure, frame scoping, then a full app.

| Step | Chapter | You'll learn |
|---|---|---|
| 1 | [01 — Getting started](01-getting-started.md) | Install, mount, the counter, your first headless test |
| 2 | [03 — State](03-state.md) | `(sub …)`, `local`, `effect`, `lease` — the four inputs |
| 3 | [04 — Events](04-events.md) | Event vectors, placeholders, forms, the callback table |
| 4 | [02 — Views](02-views.md) | `defview`, templates, styling, props, interop |
| 5 | [05 — Frames](05-frames.md) | `frame-root`, `frame-provider`, multi-frame pages |
| 6 | [10 — A worked app](10-worked-app.md) | Counter → dashboard: state shape, tiles, tests |

Chapter 01 shows the whole loop in one screen; chapters 03–05 unpack each face. Chapter
02 comes *after* state and events so `defview` examples land when `(sub …)` and
`{:on-click [:event …]}` are already familiar — not as new concepts on top of template
grammar.

### Part II — Operate the app

Verify, observe, and tune what Part I built.

| Step | Chapter | You'll learn |
|---|---|---|
| 7 | [09 — Testing](09-testing.md) | Tier-1 headless tests, selectors, mounted tests, Story |
| 8 | [06 — Debugging](06-debugging.md) | Render causes, Xray, pairing with an AI |
| 9 | [07 — Performance](07-performance.md) | What you never write, the little you do |

### Part III — Ship and understand

Deploy to the server; optional deep dive into the machinery.

| Step | Chapter | You'll learn |
|---|---|---|
| 10 | [08 — Server rendering](08-ssr.md) | JVM rendering, roots, identity, hydration |
| 11 | [11 — How it works](11-how-it-works.md) | Compiler, digest, reactive core, two emitters |

Read [11](11-how-it-works.md) when you want to *trust* the claims, not before you can
use the library. It is deliberately last.

### Short paths

| You are… | Read |
|---|---|
| New to re-frame2 UI | Part I, in order |
| Porting from Reagent | 01 → **04** → 03 → 02 → 05 → 10 (handlers first — biggest habit change) |
| Adding SSR to an existing app | 01 → 05 → 08 |
| Evaluating the architecture | Part I, then 11 |

### File index (by number)

| # | Chapter | Part |
|---|---|---|
| 01 | [Getting started](01-getting-started.md) | I |
| 02 | [Views](02-views.md) | I (step 4) |
| 03 | [State](03-state.md) | I (step 2) |
| 04 | [Events](04-events.md) | I (step 3) |
| 05 | [Frames](05-frames.md) | I (step 5) |
| 06 | [Debugging](06-debugging.md) | II |
| 07 | [Performance](07-performance.md) | II |
| 08 | [Server rendering](08-ssr.md) | III |
| 09 | [Testing](09-testing.md) | II |
| 10 | [A worked app](10-worked-app.md) | I (step 6) |
| 11 | [How it works](11-how-it-works.md) | III |

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