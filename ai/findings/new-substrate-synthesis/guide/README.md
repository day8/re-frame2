# The re-frame2 UI guide

re-frame2 UI is the view layer for re-frame2. You write **hiccup** and **event
vectors**. The compiler turns them into efficient React at build time — no hiccup
interpreter in the bundle, no hooks ceremony, no manual memoisation.

This guide teaches the library as a **user**. The dataflow half (events, effects,
app-db) lives in the [core guide](../../../../docs/core/introduction.md). Design
rationale for the substrate lives one directory up in the synthesis suite.

---

## What you will learn

By the end of the main track you can:

1. Mount a working counter and drive it from the UI or a REPL
2. Structure views with `defview`, props, and keyed lists
3. Read shared state with `sub`, hold ephemera with `local`, keep resources alive
   with `lease`
4. Express intent as data handlers, including controlled forms
5. Scope work in frames (one app, or several isolated worlds on one page)
6. Grow that counter into a small dashboard with tests
7. Fetch server data end-to-end without fetching inside views

Then optional chapters cover testing, debugging, performance, SSR, mechanism, migration
maps, and the closed grammar's hard limits.

---

## How to read this guide

**Read in order through chapter 07.** Each chapter assumes the ones before it.
Chapters 08–14 are depth: open them when you need them. Chapter 14 is the
"compile error dictionary" — reach for it when a build failure surprises you.

| # | Chapter | Job |
|---|---------|-----|
| 1 | [Getting started](01-getting-started.md) | Install, mount a counter, feel the loop |
| 2 | [Views](02-views.md) | `defview`, templates, props, interop basics |
| 3 | [State](03-state.md) | The four inputs: `sub`, props, `local`, `lease` |
| 4 | [Events](04-events.md) | Handlers as data, forms, when to escape |
| 5 | [Frames](05-frames.md) | Isolation, multi-frame pages, `(frame)` |
| 6 | [A worked app](06-worked-app.md) | Counter → ops dashboard (synthesis) |
| 7 | [Talking to servers](07-servers.md) | Resources, transport fx, status in the view |
| 8 | [Testing](08-testing.md) | Headless first, mounted when the DOM is the point |
| 9 | [Debugging](09-debugging.md) | Causes, Xray, Pair, loud failures |
| 10 | [Performance](10-performance.md) | What you never write; the little you do |
| 11 | [Server rendering](11-ssr.md) | Same views on the JVM; roots and hydration |
| 12 | [How it works](12-how-it-works.md) | Optional mechanism: hiccup→AST→emit, ViewCell, dual hosts |
| 13 | [From other worlds](13-from-other-worlds.md) | React / UIx / Reagent translation |
| 14 | [What the compiler forbids](14-compile-time-limits.md) | Closed grammar: walls, fixes, escapes |

**Coming from Reagent?** Hiccup mostly transfers. The big habit change is **events as
vectors** and **`(sub …)` without deref** — read [04](04-events.md) carefully, then
[13](13-from-other-worlds.md). Do not equate re-frame2's `reg-view` with this library's
compiler: `reg-view` registers and injects frame locals; `defview` lowers templates —
[13 §From Reagent](13-from-other-worlds.md) spells out the difference.

**Coming from React / UIx?** You will not write hooks, deps arrays, or
`memo` wrappers in ordinary views. Start at [01](01-getting-started.md), then use
[13](13-from-other-worlds.md) as a map whenever a reflex reaches for a hook.

---

## Conventions on these pages

Code assumes:

```clojure
(:require [re-frame.core :as rf]
          [re-frame.ui :as ui :refer [defview sub]])
```

Other body forms (`local`, `effect`, `lease`, `frame`, `presence-phase`) are referred
the same way when a chapter needs them — one convention, every chapter.

**Stage honesty.** The library ships in stages. Unmarked examples describe behaviour
shipped on main. Surfaces that land later carry a short marker such as *(lands S3)*.
Wave-2 names (`ui/element`, `ui/view`, `ui/portal`, `re-frame.ui.data/render`) are not
v1 and only appear with that qualifier.

**True snippets.** Fences are written to run as guide fixtures where the pipeline covers
them. A fence that is schematic or wave-2 says so with a `;; guide:no-fixture` comment
on the fence itself. Guide 08's enrolled fixture covers the Tier-1 deftest, intent
projection, dispatch-to-sub loop, seeded-state render, and sub-override door. Its
remaining fences and every other chapter are prospective pipeline enrolment.

---

## Core guide cross-links

| Topic | Core chapter |
|---|---|
| app-db, the event pipeline | [introduction](../../../../docs/core/introduction.md), [app-db](../../../../docs/core/app-db.md) |
| `reg-sub`, derivation graphs | [subscriptions](../../../../docs/core/subscriptions.md) |
| `reg-event`, fx | [effects](../../../../docs/core/effects.md) |
| Handler/sub unit tests | [testing](../../../../docs/core/testing/index.md) |
