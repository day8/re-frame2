# Performance

> **Draft.** No `implementation/hicasso/` package yet. Names marked **[unfrozen]** may change. Mechanisms are proven under `implementation/freehand/test/re_frame/bench/hicasso/`; product spellings and some call shapes are still settling.

**98% of the time, Hiccup is completely fine.**

Write ordinary `defview`, `sub` at the point of use, intents as data. Ship it.
Do not pre-optimise. Do not invent a second architecture "just in case."

**About 2% of the view code is on the hot path** — a cell that re-renders
constantly, a large table under a broad write, a foreign React widget, an SDK
that wants a DOM node. When a profile names that island, **do this** (top →
bottom; stop as soon as the island is fixed):

1. **Still Hiccup** — keys, bail-out, fewer boundaries, reads at point of use  
2. **Host-edge React** — one measured island, hooks/refs for mechanics only  
3. **`defhost` / `[:>]`** — someone else's React component  
4. **Another root** — a whole screen on Reagent/UIx by product choice, not a
   Hicasso mode  

There is no dual mode that turns interpretation off for the app. The 2% is an
island, not a lifestyle.

---

## Why Hiccup is usually enough

The interpreter is not free, but it is rarely the bill. Against Reagent, the
Hiccup walk itself is about **parity (~0.96×)**. On the worst mounts we have
seen, roughly **~70% of the extra cost was read shape** — many fine per-row
subscriptions instead of a few coarse ones — not time turning vectors into
elements. Coarser, more natural reads land nearer **~1.2×** where the bad case
sat nearer **~1.5×**.

So when something *is* slow, you fix **where and how you read** (and who
re-renders) long before you abandon Hiccup. The 98/2 split is how you should
write until a profile names a specific exception — not a lab certificate that
every screen has been timed.

---

## The default path (the 98%)

Stay here. Full mechanics live in [Views and reads](02-views-and-reads.md); the
performance rules of thumb are:

**1. Boundaries only where re-render granularity matters.**

```clojure
[todo-row {:key id :id id}]   ;; boundary — own re-render, own sub edge set
(row-chrome {:id id})         ;; plain call — inlined; no boundary of its own
```

A **view in head position** mints a boundary — React identity, its own `sub`
reads, its own bail-out
([Views and reads](02-views-and-reads.md#boundaries-and-inlining)). Native tags,
fragments and `defhost` heads sit in vector position too and mint none of that.
A **plain call** still runs and still builds markup; what it does not buy is
re-render granularity, and its hiccup splices into the parent while its reads
donate upward. Markup that always rides its parent should be a helper, not a
`defview`.

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

## The hot path (the 2%) — do this

You only enter this section after something is **named** (Profiler / Xray) or is
obviously a foreign/SDK boundary you never expected Hiccup to own.

### Name the island first

You do not need Hicasso-private tooling.

| Tool | What it shows |
|---|---|
| **React DevTools Profiler** | Which components commit (every Hicasso boundary is a real React FC) |
| **Xray** (or Spec 009) | Which **subscriptions** recomputed for the event you care about |

Name the boundary, the sub, or the event **before** changing architecture.
"The app is slow" is still the 98% problem wearing a costume.

### Step 1 — Still Hiccup

Re-check the default path on the named island: fewer boundaries, reads at point
of use, stable keys, no high-rate `:db` writes. Most hot-path cases die here and
return to the 98%. That step is first because **that is where the cost usually
lives**, not interpretation.

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

### Step 2 — Host-edge React (one island)

A `defview` body is a real React function component. Hooks and refs are fine for
**mechanics**: measure, SDK handles, animation clocks that are not app state.
Semantic state stays in app-db.

**Async work belongs in the event layer, which already has the frame.** An fx
handler receives the frame id in its context and `:dispatch-later` says the
delay as data, so a `setTimeout` in a view that dispatches is mis-layered before
it is anything else. Move the work and the carrying question goes with it.

Intent vectors need none of this — they already carry their boundary's frame.
What survives the move is the **foreign edge**: a dispatching closure handed to
a caller you do not control — an SDK attached through a ref, a `defhost` callback
slot, a library that calls back with a value. There the frame is knowable during
the render and nowhere else, so read it there with **`h/frame`** **[unfrozen]**
and compose it with the platform carry: `(rf/capture-frame (h/frame))`.

Do not reach for an ambient `rf/dispatch` or a bare `rf/capture-frame` instead.
Ambient frame lookup is exactly what Hicasso's stricter body discipline
withdraws: inside a body those forms **refuse**, under every adapter, naming the
collector they went around — deterministically, not "sometimes"
([Events as data](03-events-as-data.md#callbacks-carry-their-frame)).

```clojure
;; .cljs host-edge namespace — not the whole app.
(ns app.hosts.measure-box
  (:require [re-frame.core :as rf]
            [re-frame.hicasso :as h :refer [defview]]
            ["react" :as react]))

(defview measure-box [{:keys [children]}]
  (let [frame (h/frame)   ;; [unfrozen] name; not an ambient rf/* lookup
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

### Step 3 — Foreign React (`defhost` / `[:>]`)

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

### Step 4 — Another view layer

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
| Default Hiccup (98%) | Yes | Yes | Intents today; full render later | Boundaries, reads, keys, bail-out |
| Host-edge hooks | Partial | Yes in that body | **No** for that body | React |
| `defhost` | Opaque at the crossing | Via props / parents | Foreign region out | Leave work in the library |
| Other adapter | That root's rules | That root's rules | That root's rules | Full switch |

## Troubleshooting

| Symptom | What went wrong | Fix |
|---|---|---|
| One cell change re-renders everything | Read too high, or one giant boundary | Push `sub` down; split boundaries |
| Page-wide write re-runs every card | Props not `=` or keys unstable | Stable `:key`; read in the child; intent data not fresh fns |
| Big table feels ~1.5× | Broad commit fan-out — known hard shape | Keys + bail-out + read shape; then virtualized `defhost` |
| Bare `rf/dispatch` from a timeout | An ambient `rf/*` in a body refuses, naming the collector — it never "sometimes works" | Own the async work through `:fx`; only a closure crossing to foreign code carries `(rf/capture-frame (h/frame))` |
| Hooks in every cell "for speed" | Second architecture | One island; app-db for semantic state |
| Structural test dies after a "perf fix" | Hooks or JS in a `.cljc` body | Quarantine host code in `.cljs` |
| Bare `[DatePicker …]` head | Not legal | `defhost` (or `[:>]` when available) |
| SDK mounts twice / leaks | Ref without paired teardown | Attach + cleanup as one ref (React 19) — [Interop](05-interop.md) |
| Still slow after dropping to React | Wrong layer | Profile again — often reads or event volume |

## When not to leave the 98%

- No profile — only a feeling, or a fear of the interpreter.
- The real issue is **event volume** ([Controlled inputs](04-controlled-inputs.md)).
- You are about to reimplement the app in hooks "for the 2%." That is a
  substrate change, not an escape hatch.

## Not settled yet

| Question | Status |
|---|---|
| Compile / dual-mode path | **Not planned** — one interpreted Hiccup product |
| `[:>]` availability | Ruled; may lag `defhost` — [Interop](05-interop.md) |
| `h/frame` spelling | Behaviour settled and proven in the arm; the product name is **[unfrozen]** |
| Big-list bulk on our side | Outside numbers show risk; full own pricing still open |
| Perf-island macro / scaffold | **None** — plain React + `defview` |
