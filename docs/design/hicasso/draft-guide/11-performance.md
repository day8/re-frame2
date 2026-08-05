# Performance

> **Draft.** No `implementation/hicasso/` package yet. Names marked **[unfrozen]** may change. Mechanisms are proven under `implementation/freehand/test/re_frame/bench/hicasso/`; product spellings and some call shapes are still settling.

Most Hicasso screens do not need a performance strategy. Write ordinary Hiccup —
`defview`, `sub` where you use the value, intent vectors — and ship.

> **For about 98% of view code, performance is not an issue.** Do not pre-optimise.
> Do not invent a second architecture "just in case."

Hicasso is Hiccup-first and interpreted. The walk is not free, but it is rarely
what hurts you. Measured against Reagent, the Hiccup walk itself is about
**parity (~0.96×)**. On the worst mounts we have seen, roughly **~70% of the
extra cost was read shape** — many fine per-row subscriptions instead of a few
coarse ones — not time spent turning vectors into elements. Screens that use
coarser, more natural reads land nearer **~1.2×** where the pathological case
sat nearer **~1.5×**. So when something *is* slow, you fix **where and how you
read** (and who re-renders) long before you abandon Hiccup.

**The other ~2%** is a measured island: a hot cell, a third-party React widget,
an SDK that wants a DOM node. You can step **below Hiccup** for that island —
closer to React, into a foreign component — without rewriting the app. There is
no dual mode that "turns the interpreter off." There is a ladder.

> **Ninety-eight percent stays Hiccup. For the two percent: find the island, then
> fix that island — do not rewrite the product.**

The 98/2 split is how you should write, not a lab certificate. Stay on the happy
path until a profile names something specific.

## Find the island first

You do not need Hicasso-private tooling.

- **React DevTools Profiler** — every Hicasso boundary is a real React function
  component. See which components commit and how often.
- **Xray** (or any Spec 009 consumer) — which **subscriptions** recomputed for
  the event you care about. Often the problem is a high, coarse read, not a slow
  walk.

Name the boundary, the sub, or the event **before** changing architecture.
"The app is slow" is still the 98% problem wearing a costume.

## What is not an escape

| Wish | Reality |
|---|---|
| "Compile this view off the interpreter" | **No.** No dual mode, no second compiler product |
| "Bare JS component in head position" | **Refused** — use `defhost` or `[:>]`, not silent auto-host |
| "I'll switch this page to Reagent/UIx" | Only as **another root**, not a spelling inside one `defview` |
| "Hooks everywhere so we're ready for the 2%" | That *is* rewriting the product. The 2% is an island |

No profile yet? You are still in the 98%. Stay on tier-1 Hiccup
([Views and reads](02-views-and-reads.md)).

## The ladder

### 1. Still Hiccup — fix reads and re-renders

Same language, less cost. This step comes first **because that is where the cost
usually lives** — not interpretation.

**Boundaries only where re-render granularity matters.**

```clojure
[todo-row {:key id :id id}]   ;; boundary — own re-render, own sub edge set
(row-chrome {:id id})         ;; plain call — inlined; free at runtime
```

A **vector** head is a boundary: React identity, its own `sub` reads, default
value-equality bail-out. A **plain call** splices into the parent and donates
reads upward. Markup that always re-renders with its parent should be a helper,
not a `defview`.

**Push reads down.** A `sub` high in the tree invalidates that boundary and
everything the value is threaded through. The expensive mounts we measured spent
most of their deficit on **too many fine per-instance reads**. Read at the point
of use ([Views and reads](02-views-and-reads.md)).

**Stable keys and the default bail-out.** Every list of boundaries needs a
stable `:key` in the props map (a Hicasso-minted missing-key warning is planned;
until then you still get React's). If a child's props still `=` after a parent
re-render, the body does not run. That is what makes a page-wide write cheap for
unchanged cards — when keys and props are stable, **hundreds of bodies can skip**.
What defeats the bail-out: threading a changing value as a prop instead of
reading in the child; a fresh function or JS object every parent render;
unstable keys that force remounts.

**Keep high-rate motion out of app-db.** Drag and scroll are host mechanics until
you commit ([Ephemeral state](07-ephemeral-state.md)).

### Big lists and broad commits (honest)

One event that touches state **many** row boundaries care about — a big table or
feed — is the shape where cost **can** bite. Outside measurements have shown
about **1.4–1.6×** on broad bulk for candidate arms; that axis is **not fully
priced on our own instrument yet**. Big-list bulk is a known risk, not a solved
win.

Work it in this order:

1. Keys + bail-out (above).
2. Better-placed / coarser reads.
3. A virtualized list via `defhost` when the list is really a React virtualizer.
4. Another substrate only if that surface is React-first by design.

Do not jump to hooks on a 300-row table before keys, bail-out, and read shape.

### 2. Host-edge React (one island)

A `defview` body is a real React function component. Hooks and refs are fine for
**mechanics**: measure, SDK handles, animation clocks that are not app state.
Semantic state still belongs in app-db.

**Do not use ambient `rf/dispatch` or ambient `rf/capture-frame` from a
timeout and hope.** In a Hicasso body those ambient forms are **not a contract**
— they may work, throw, or pick the wrong frame depending on adapter, renderer,
and timing. Intent vectors already carry the frame. For hand-written async (SDK
callbacks, value-first foreign handlers), prefer the **event layer** (`:fx` on
the ctx, `:dispatch-later`). When you must close over a frame at the edge, the
intended spelling is **`h/frame`** **[unfrozen]** composed with the platform
carry: `(rf/capture-frame (h/frame))`. The shape is fixed; the product spelling
may still be landing — check before you depend on it.

```clojure
;; .cljs host-edge namespace — not the whole app.
(ns app.hosts.measure-box
  (:require [re-frame.core :as rf]
            [re-frame.hicasso :as h :refer [defview]]
            ["react" :as react]))

(defview measure-box [{:keys [children]}]
  (let [frame (h/frame)   ;; [unfrozen] — may still be landing; not ambient rf/*
        ref   (react/useRef nil)]
    (react/useLayoutEffect
      (fn []
        (when-let [node (.-current ref)]
          ;; Later, off the render: (rf/capture-frame frame) then work.
          js/undefined))
      #js [])
    (into [:div {:ref ref}] children)))
```

| You gain | You give up |
|---|---|
| Full React toolkit for that island | Headless / data-tree tests for that body ([Testing](08-testing.md)) |
| Layout / SDK lifecycle control | Hook rules and StrictMode are yours |
| Rest of the app stays Hicasso | That node is no longer pure data markup |

Keep the island small. Callback refs for attach/teardown: [Interop](05-interop.md)
Advanced.

### 3. Foreign React (`defhost` / `[:>]`)

When the lower level is **someone else's** component (date picker, chart,
virtualized list):

- **`defhost`** — declare once; policies at the declaration ([Interop](05-interop.md)).
- **`[:> Component …]`** — secondary raw escape; may lag `defhost` in the arm.

```clojure
(h/defhost chart Chart
  {:ssr :client-only})

(defview dashboard [_]
  [:div
   [chart {:series (sub [:metrics/series])}]])
```

An existing React element is a legal child (pass-through). Keep JS requires in
`.cljs` host namespaces.

### 4. Another view layer

A whole screen in UIx or Reagent is **another adapter root**, not a Hicasso mode.
Multi-frame isolation: [Getting started](01-getting-started.md).

## Order of operations (only for the 2%)

1. **Name the island** — Profiler and Xray ([above](#find-the-island-first)).
2. **Still Hiccup** — keys, bail-out, fewer boundaries, reads at point of use.
   Most "hot" cases die here.
3. **Host-edge island** — one widget; async via event `:fx` or
   `(rf/capture-frame (h/frame))`, never ambient hope.
4. **`defhost`** — third-party / virtualized React.
5. **Different substrate** — only if that is the product direction.

## What you keep

| Level | Data intents | Ambient `sub` | Headless-friendly | Lever |
|---|---|---|---|---|
| Tier-1 Hiccup | Yes | Yes | Intents today; full render later | Boundaries, reads, keys, bail-out |
| Host-edge hooks | Partial | Yes in that body | **No** for that body | React |
| `defhost` | Opaque at the crossing | Via props / parents | Foreign region out | Leave work in the library |
| Other adapter | That root's rules | That root's rules | That root's rules | Full switch |

## Troubleshooting

| Symptom | What went wrong | Fix |
|---|---|---|
| One cell change re-renders everything | Read too high, or one giant boundary | Push `sub` down; split boundaries |
| Page-wide write re-runs every card | Props not `=` or keys unstable | Stable `:key`; read in the child; intent data not fresh fns |
| Big table feels ~1.5× | Broad commit fan-out — known hard shape | Keys + bail-out + read shape; then virtualized `defhost` |
| Bare `rf/dispatch` from a timeout "sometimes works" | Ambient `rf/*` is not a contract in Hicasso bodies | Event `:fx`, or `(rf/capture-frame (h/frame))` when available |
| Hooks in every cell "for speed" | Second architecture | One island; app-db for semantic state |
| Structural test dies after a "perf fix" | Hooks or JS in a `.cljc` body | Quarantine host code in `.cljs` |
| `[DatePicker …]` bare head | Not legal | `defhost` (or `[:>]` when available) |
| SDK mounts twice / leaks | Ref without paired teardown | Attach + cleanup as one ref (React 19) — [Interop](05-interop.md) |
| Still slow after dropping to React | Wrong layer | Profile again — often reads or event volume |

## When not to go below Hiccup

- Still the **98%** — no profile, only anxiety about the interpreter.
- The real issue is **event volume** (e.g. controlling every keystroke on a dense
  grid) — [Controlled inputs](04-controlled-inputs.md).
- You are about to reimplement the app in hooks "for the 2%." That is a
  substrate change, not an escape hatch.

## Not settled yet

| Question | Status |
|---|---|
| Compile / dual-mode path | **Not planned** — one interpreted Hiccup product |
| `[:>]` availability | Ruled; may lag `defhost` — [Interop](05-interop.md) |
| `h/frame` shipping | Shape fixed; spelling **[unfrozen]**; may still be landing |
| Big-list bulk on our own instrument | Outside numbers show risk; full own pricing still open |
| Perf-island scaffold / macro | **None** — plain React + `defview` |
