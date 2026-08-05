# Performance

> **Draft.** No `implementation/hicasso/` package yet. Names marked **[unfrozen]** may change. Mechanisms are proven under `implementation/freehand/test/re_frame/bench/hicasso/`; product spellings and some call shapes are still settling.

Most Hicasso screens do not need a performance strategy. Write ordinary Hiccup —
`defview`, `sub` where you use the value, intent vectors — and ship.

> **For about 98% of view code, performance is not an issue.** Do not pre-optimise.
> Do not invent a second architecture "just in case."

The interpreter is not free, but it is rarely what hurts you. Against Reagent,
the Hiccup walk itself is about **parity (~0.96×)**. On the worst mounts we have
seen, roughly **~70% of the extra cost was read shape** — many fine per-row
subscriptions instead of a few coarse ones — not time turning vectors into
elements. Coarser, more natural reads land nearer **~1.2×** where the bad case
sat nearer **~1.5×**. So when something *is* slow, you fix **where and how you
read** (and who re-renders) long before you abandon Hiccup.

**The other ~2%** is a measured island: a hot cell, a third-party React widget,
an SDK that wants a DOM node.

So the job of this page is simple: **how to stay in the 98%**, and **what to do
for the ~2%** when a profile names a real island.

> **Ninety-eight percent stays Hiccup. For the two percent: fix that island —
> do not rewrite the product.**

The 98/2 split is how you should write, not a lab certificate. There is no dual
mode that turns interpretation off. There is a ladder **below Hiccup** for the
minority of cases that need it.

---

## The default path

Stay here until a profile says otherwise. Full mechanics live in
[Views and reads](02-views-and-reads.md); the performance rules of thumb are:

**1. Boundaries only where re-render granularity matters.**

```clojure
[todo-row {:key id :id id}]   ;; boundary — own re-render, own sub edge set
(row-chrome {:id id})         ;; plain call — inlined; free at runtime
```

A **vector** head is a boundary. A **plain call** splices into the parent and
donates reads upward. Markup that always rides its parent should be a helper,
not a `defview`.

**2. Read at the point of use.** A `sub` high in the tree invalidates that
boundary and everything the value is threaded through. Expensive mounts we
measured spent most of their deficit on **too many fine per-instance reads**.

**3. Stable keys and the default bail-out.** Every list of boundaries needs a
stable `:key` in the props map. If a child's props still `=` after a parent
re-render, the body does not run — a page-wide write can leave **hundreds of
unchanged cards** unrun. What defeats that: threading a changing value as a prop
instead of reading in the child; a fresh function or JS object every parent
render; unstable keys that force remounts.

**4. Keep high-rate motion out of app-db.** Drag and scroll are host mechanics
until you commit ([Ephemeral state](07-ephemeral-state.md)). Dense controlled
grids are often an **event-volume** problem, not a Hiccup one
([Controlled inputs](04-controlled-inputs.md)).

If you only do this, you are done for most apps.

---

## When something feels slow

### Name the island first

You do not need Hicasso-private tooling.

| Tool | What it shows |
|---|---|
| **React DevTools Profiler** | Which components commit (every Hicasso boundary is a real React FC) |
| **Xray** (or Spec 009) | Which **subscriptions** recomputed for the event you care about |

Name the boundary, the sub, or the event **before** changing architecture.
"The app is slow" is still the 98% problem wearing a costume.

### Then climb only as far as you must

Work **top to bottom**. Most hot cases die on step 1 and return to the default path.

#### Step 1 — Still Hiccup

Re-check the default path on the named island: fewer boundaries, reads at point
of use, stable keys, no high-rate `:db` writes. That step is first because
**that is where the cost usually lives**, not interpretation.

**Big lists (honest).** One event that many row boundaries care about — a large
table or feed — is the shape where cost **can** bite. Outside measurements have
shown about **1.4–1.6×** on broad bulk; we have not finished pricing that shape
on our side. Treat big-list bulk as a known risk.

Order for a big table:

1. Keys + bail-out  
2. Better-placed / coarser reads  
3. Virtualized list via `defhost` (step 3) if the list is a React virtualizer  
4. Another substrate (step 4) only if that surface is React-first by design  

Do not jump to hooks on a 300-row table before keys, bail-out, and read shape.

#### Step 2 — Host-edge React (one island)

A `defview` body is a real React function component. Hooks and refs are fine for
**mechanics**: measure, SDK handles, animation clocks that are not app state.
Semantic state stays in app-db.

**Do not bare `rf/dispatch` or ambient `rf/capture-frame` from a timeout and
hope.** In a Hicasso body those ambient forms are **not a contract** — they may
work, throw, or pick the wrong frame depending on adapter, renderer, and timing.
Intent vectors already carry the frame. Prefer the **event layer** (`:fx`,
`:dispatch-later`). When you must close over a frame at the edge, the intended
spelling is **`h/frame`** **[unfrozen]** with the platform carry:
`(rf/capture-frame (h/frame))`. Shape is fixed; product shipping may still be
landing.

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
          ;; Later, off the render: (rf/capture-frame frame) then work —
          ;; not bare rf/dispatch.
          js/undefined))
      #js [])
    (into [:div {:ref ref}] children)))
```

| You gain | You give up |
|---|---|
| Full React toolkit for that island | Headless tests for that body ([Testing](08-testing.md)) |
| Layout / SDK lifecycle control | Hook rules and StrictMode are yours |
| Rest of the app stays Hicasso | That node is no longer pure data markup |

Keep the island **small**. Callback refs: [Interop](05-interop.md) Advanced.

#### Step 3 — Foreign React (`defhost` / `[:>]`)

Someone else's component — date picker, chart, **virtualized list**:

- **`defhost`** — declare once ([Interop](05-interop.md))
- **`[:> …]`** — secondary raw escape; may lag `defhost`

```clojure
(h/defhost chart Chart
  {:ssr :client-only})

(defview dashboard [_]
  [:div
   [chart {:series (sub [:metrics/series])}]])
```

Existing React elements are legal children (pass-through). Keep JS requires in
`.cljs` host namespaces.

#### Step 4 — Another view layer

A whole screen in UIx or Reagent is **another adapter root**, not a Hicasso
mode. Multi-frame: [Getting started](01-getting-started.md).

### What is not on the ladder

| Wish | Reality |
|---|---|
| Compile off the interpreter | **No** dual mode |
| Bare JS head `[DatePicker …]` | **Refused** — `defhost` / `[:>]` |
| "Hooks everywhere for the 2%" | That rewrites the product |
| Switch one page to Reagent inside `defview` | Only as another root |

---

## What you keep at each level

| Level | Data intents | Ambient `sub` | Headless-friendly | Lever |
|---|---|---|---|---|
| Default Hiccup | Yes | Yes | Intents today; full render later | Boundaries, reads, keys, bail-out |
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
| Bare `[DatePicker …]` head | Not legal | `defhost` (or `[:>]` when available) |
| SDK mounts twice / leaks | Ref without paired teardown | Attach + cleanup as one ref (React 19) — [Interop](05-interop.md) |
| Still slow after dropping to React | Wrong layer | Profile again — often reads or event volume |

## When not to go below Hiccup

- Still the **98%** — no profile, only fear of the interpreter.
- The real issue is **event volume** ([Controlled inputs](04-controlled-inputs.md)).
- You are about to reimplement the app in hooks "for the 2%." That is a
  substrate change, not an escape hatch.

## Not settled yet

| Question | Status |
|---|---|
| Compile / dual-mode path | **Not planned** — one interpreted Hiccup product |
| `[:>]` availability | Ruled; may lag `defhost` — [Interop](05-interop.md) |
| `h/frame` shipping | Shape fixed; spelling **[unfrozen]**; may still be landing |
| Big-list bulk on our side | Outside numbers show risk; full own pricing still open |
| Perf-island macro / scaffold | **None** — plain React + `defview` |
