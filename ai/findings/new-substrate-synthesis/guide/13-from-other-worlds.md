# 13 — From other worlds

A translation map for people arriving from React, UIx, Helix, or Reagent. Nothing
here is required if you started at [01](01-getting-started.md) with a blank mind —
use it when a habit from elsewhere misleads.

## From React / UIx / Helix

| Looking for | Here | Chapter |
|---|---|---|
| `useState` | `local` — or app-db the moment anything else cares | [03](03-state.md) |
| `useEffect` (DOM / third-party) | `effect` | [03](03-state.md) |
| `useEffect` (data fetching) | events + resources; views declare `lease` | [03](03-state.md), [07](07-servers.md) |
| `useCallback` / `memo` / deps arrays | gone — every view memoises on value-equal props | [10](10-performance.md) |
| Context | props, subs, and frame scoping; React context only at foreign boundaries | [05](05-frames.md), [02](02-views.md) |
| Suspense / loading UI | resource status values you branch on | [03](03-state.md), [07](07-servers.md) |
| Error boundaries | `ui/error-boundary` *(lands S3)* | [02](02-views.md) |
| Refs | `(ui/raw-fn set-node)` + `effect` | [03](03-state.md) |
| Portals | wave-2 (`ui/portal`) — not v1 | — |
| Components as the unit | `defview` — one form, always | [02](02-views.md) |
| `useSyncExternalStore` / external store | you do not write it; `sub` is the bridge | [12](12-how-it-works.md) |

**Rules of thumb that bite early**

- You will not write a hook, a deps array, a `useCallback`, or a `memo` wrapper in an
  ordinary view. The compiler writes the React that would have required them.
- Unlike hooks, `sub` may sit in a branch.
- Fetching is never "a hook that runs on mount". It is an event + resource plan, with
  the view holding a `lease` and reading status ([07](07-servers.md)).

## From Reagent

### Two independent steps

1. **Dataflow first** — move events, subs, and a `frame-root` mount to re-frame2 while
   keeping registered Reagent views as they are (stock Reagent remains a supported,
   frozen compatibility tier). You immediately gain Xray, epochs, Story, schemas,
   machines, and resources.
2. **Views per subtree** — migrate to `defview` on your schedule, with the migrator.
   Old and new trees co-mount at explicit boundaries.

Step 1 does **not** rewrite your views. Step 2 is this guide's programming model.

### Mechanical rewrites (the bulk)

| Reagent | Here |
|---|---|
| `(defn my-view [a b] [:div …])` (Form-1) | `(ui/defview my-view [{:keys [a b]}] [:div …])` — one props map; update call sites |
| `@(subscribe [:q])` | `(sub [:q])` — drop the deref |
| `#(dispatch [:ev x])` when body is exactly a dispatch | `{:on-click [:ev x]}` |
| `#(dispatch [:typed (-> % .-target .-value)])` | `[:typed :rf.ui/value]` |
| hiccup tags, `:div.cls#id`, `:style` maps, `:class` vectors | identical |
| `^{:key k}` / `:key` in meta | `{:key k}` in the props map |
| `r/adapt-react-class` / foreign class as head | foreign component as template head + decision table in [04](04-events.md) |
| `reagent.dom/render` | `ui/mount` + `frame-root` |

### Local transformations (judgment)

| Reagent | Here |
|---|---|
| Form-2 / `with-let` local state | `local` for ephemera; app-db the moment it is product state ([03](03-state.md)) |
| Lifecycle methods / `:component-did-mount` | domain events for domain visibility; `effect` for host sync ([04](04-events.md)) |
| Ratoms, cursors, `track` | the four inputs only ([03](03-state.md)) — no parallel reactive graph |
| Reaction/cursor cleverness across frames | redesign: frames are isolated ([05](05-frames.md)) |

### Cohabitation

- **Legacy Reagent subtree inside a `ui` tree** — via `ui/raw` at an explicit boundary.
- **Migrated `defview` inside a remaining Reagent shell** — via `ui/->react`
  *(lands S6 with the migration wave)*.

### What does not change

Events, subs, fx, machines, schemas, routes, resources — the dataflow layer is the
same re-frame2 you already have (or just moved to). Migration is a **view-tier**
rewrite.

## Where next

- Fresh start: [01](01-getting-started.md)
- Habit that hurts most from React: [04](04-events.md) (data handlers)
- Habit that hurts most from Reagent Form-2: [03](03-state.md) (`local` doctrine)
- Why memoisation can be correct: [12](12-how-it-works.md)
