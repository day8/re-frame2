# Below Hiccup

> **Draft.** No `implementation/hicasso/` package yet. Names marked **[unfrozen]** may change. Mechanisms are proven under `implementation/freehand/test/re_frame/bench/hicasso/`; product spellings and some call shapes are still settling.

> **For about 98% of view code, performance is not an issue.** Write ordinary
> Hiccup: `defview`, `sub` at the point of use, intents as data. Do not
> pre-optimise. Do not invent a second architecture "just in case."

Hicasso is **Hiccup-first and interpreted**. That is the product for almost
everything you ship. The interpreter is not free, but measured work says it is
**rarely the bill**: the Hiccup walk itself sits at about **parity with Reagent's
own walk (~0.96×)** on the programme's comparisons. On the worst-case mount
witnesses, about **~70% of the deficit was read shape** — many fine-grained
per-instance reads versus a handful of coarse ones — not "we spent time turning
vectors into elements." Coarsened, census-shaped screens land nearer **~1.2×**
where the pathological witness sat nearer **~1.5×**. So when you *do* optimise,
you start with **where and how you read**, not with abandoning Hiccup.

**For the other ~2%** — a measured hot path, a third-party React widget, an
imperative SDK — you *can* go lower. There is **no second mode** that turns
interpretation off for the whole app. There **are** deliberate steps down the
stack. This page is the map for that minority.

> **Ninety-eight percent stays Hiccup. For the two percent: measure, then isolate
> the escape — do not rewrite the product.**

The percentages are a **product assertion**, not a lab result: write as if they
were true until a profile proves a specific island is the exception. The
magnitudes above are the measured reason step 1 is "fix the reads" first.

## How to name the island

You do not need Hicasso-private tooling.

- **React DevTools Profiler** works as-is — every Hicasso boundary is a real
  React function component. See which components commit and how often.
- **Xray** (or any consumer of Spec 009) shows which **subscriptions** recomputed
  for the focused event — often the island is a high, coarse read, not a slow
  walk.

Name the boundary, the sub, or the event **before** changing architecture.
"The app is slow" is still the 98% problem in disguise.

## What "lower" is not

| Wish | Reality |
|---|---|
| "Compile this view off the interpreter" | **No.** No dual mode, no analyzer, no ViewCell product path |
| "Bare JS component in head position" | **Refused** — one door: `defhost` / `[:>]`, not silent auto-host |
| "Drop to Reagent/UIx for one page" | Only as **another root / adapter**, not a Hicasso mode |
| "I'll use hooks everywhere so we're ready for the 2%" | That *is* rewriting the product. The 2% is an island, not a lifestyle |

If you have not measured, you are still in the 98%. Stay on tier-1 Hiccup
([Views and reads](02-views-and-reads.md)). When a profile names an island, start
with [step 1](#1-still-hiccup--spend-less) before any host-edge React.

## The ladder (top → bottom)

### 1. Still Hiccup — spend less

Same language, less cost. This is the first lever and the one that preserves
testing and data trees. **It is first because that is where the measured cost
lives**, not by taste: read shape and re-render fan-out, not interpretation.

**Boundaries only where re-render granularity matters.**

```clojure
[todo-row {:key id :id id}]   ;; boundary — own re-render, own sub edge set
(row-chrome {:id id})         ;; plain call — inlined; free at runtime
```

A **vector** head is a boundary (React identity, its own `sub` reads, default
value-equality bail-out). A **plain call** splices hiccup into the parent and
donates reads upward. Helpers that always re-render with their parent should be
calls, not `defview`s.

**Push reads down — this is where the money measurably is.** A `sub` high in the
tree invalidates that boundary and everything the value is threaded through.
Worst-case mount witnesses spent most of their deficit on **too many fine
per-instance reads** versus a few coarse ones; coarsening the read shape is what
moved numbers. Read at the point of use
([Views and reads](02-views-and-reads.md)).

**Keys and the default bail-out.** Every boundary list needs a stable `:key` in
the props map (Hicasso-minted missing-key warnings are ruled; until they land you
still get React's own). The default **value-equality bail-out** is what makes a
parent re-render cheap: if a child's props still `=`, its body does not run. A
page-wide write that leaves card props equal measurably re-ran **0 of hundreds**
of card bodies when keys and props were stable. What **defeats** the bail-out:
threading a changing value down as a prop instead of reading it in the child;
fresh function / JS identity on every parent render; unstable keys that force
remounts.

**Do not invent a second state system for motion.** High-rate drag/scroll belongs
at a host edge or off app-db until commit
([Ephemeral state](07-ephemeral-state.md)).

### Bulk honesty (the 2% you will actually hit)

**Broad commits over hundreds of boundaries** — a big table or feed where one
event touches state that many rows care about — is the shape where the measured
record says cost **can be material**. Outside instruments have read about
**1.4–1.6×** on broad bulk for candidate arms; that axis is **not yet fully
priced on our own converged instrument**. State it: big-list bulk is a known
risk, not a polished win.

Levers, in order:

1. **Keys + default bail-out** (above) — so a write that does not change a row's
   props skips that body.
2. **Coarser / better-placed reads** — so fewer boundaries invalidate.
3. **Step 3** — a virtualized foreign list via `defhost` when the list itself is
   the product of a virtualizing React library.
4. **Step 4** — another substrate only if the product direction is React-first
   for that surface.

Do not skip to hooks for a 300-row table before keys, bail-out, and read shape
have been checked.

### 2. Real React inside a boundary (host edge)

A `defview` body **is** an honest React function component. Hooks and refs are
**legal** for host mechanics — geometry, measure-before-paint, SDK handles,
animation clocks that are not app state.

The rule is taught, not runtime-policed:

> Semantic application state → app-db.  
> Component mechanics → ordinary React at the edge.

**Ambient `rf/*` forms are non-contractual in Hicasso bodies.** They may work,
throw, or answer the wrong frame depending on adapter, renderer, and timing —
never rely on them. Intent vectors carry the frame for free. For **hand-written**
async (timeouts, SDK callbacks, value-first foreign handlers that need a plain
closure), the ruled door is **`h/frame`** **[unfrozen]** — read the current
frame id inside a render (or a runtime-armed render callback), then compose with
the platform carry: `(rf/capture-frame (h/frame))`. Honest tense: the shape is
**ruled**; product shipping of `h/frame` may still be landing. Prefer putting
async work in the **event layer** (`:fx` with frame on the ctx, `:dispatch-later`)
when you can; use `h/frame` at foreign edges when you cannot.

```clojure
;; .cljs host-edge namespace — not the whole app.
(ns app.hosts.measure-box
  (:require [re-frame.core :as rf]
            [re-frame.hicasso :as h :refer [defview]]
            ["react" :as react]))

(defview measure-box [{:keys [children]}]
  (let [;; Ruled door — frame id for later async; do NOT use bare rf/dispatch
        ;; or ambient rf/capture-frame from a timeout and hope.
        frame (h/frame)                           ;; [unfrozen]; may still be landing
        ref   (react/useRef nil)]
    (react/useLayoutEffect
      (fn []
        (when-let [node (.-current ref)]
          ;; Later, off the render: (rf/capture-frame frame) then work/dispatch.
          js/undefined))
      #js [])
    (into [:div {:ref ref}] children)))
```

**What you pay**

| You gain | You give up |
|---|---|
| Full React toolkit for that island | Headless / data-tree testing for that body ([Testing](08-testing.md)) |
| Fine control of layout/measure/SDK lifecycle | You own hook rules and StrictMode yourself |
| Rest of the app stays Hicasso | That node is no longer "pure data markup" |

Keep the island **small**. The win is isolation, not "half the app in hooks."

Callback **refs** (attach + teardown in one function) are the taught form for
imperative DOM ownership; details live under [Interop](05-interop.md) Advanced.

### 3. Foreign React components (`defhost` / `[:>]`)

When the lower level is **someone else's** React component (npm date picker,
chart, design-system primitive, **virtualized list**):

- **`defhost`** — declare once, use as a view head; props conversion and callback
  contracts at the declaration ([Interop](05-interop.md)).
- **`[:> Component …]`** — secondary raw escape for cases a static declaration
  cannot express; same conversion path, less identity for tools. Build status may
  lag the ruling — check the interop page.

```clojure
(h/defhost chart Chart
  {:ssr :client-only})   ;; or {:fallback […]}

(defview dashboard [_]
  [:div
   [chart {:series (sub [:metrics/series])}]])
```

An **existing React element** is a legal child anywhere (pass-through). Something
that already produced `createElement` output can sit under Hiccup without being
re-interpreted as tags.

**What you pay:** less structural `=` at that node, JS quarantined in `.cljs`,
SSR policy explicit (`:client-only` / fallback). Do not smuggle a raw JS require
into a `.cljc` view ns.

### 4. A different view layer entirely

If a whole screen wants UIx or Reagent authoring, that is **another adapter
root**, not a Hiccup escape hatch. Hicasso does not embed "UIx mode." Two frames
on one page is the multi-app pattern ([Getting started](01-getting-started.md));
mixing substrates is an architecture choice, not a spelling inside one
`defview`.

## Order of operations (the 2%)

You only enter this list after something is **named** (Profiler / Xray) or is
obviously a foreign/SDK boundary you never expected Hiccup to own.

1. **Name the island** — which boundary, which sub, which event, which widget
   ([How to name the island](#how-to-name-the-island)).
2. **Still Hiccup** — keys, bail-out, fewer boundaries, reads at point of use,
   no high-rate writes to app-db. Most "2%" cases die here and return to the 98%.
3. **Host-edge island** — one measured widget, hooks/refs; async via
   `(rf/capture-frame (h/frame))` or event-layer `:fx` — never ambient `rf/*`
   hope.
4. **`defhost`** — third-party React (including virtualized lists), not your
   markup.
5. **Different substrate** — only if the product direction is React-first for that
   surface.

## What you keep / what you lose

| Level | Data tree / `=` intents | `sub` ambient | Headless-friendly | Perf lever |
|---|---|---|---|---|
| Tier-1 Hiccup | Yes | Yes | Yes (when headless lands; intents today) | Boundaries + read placement + keys/bail-out |
| Host-edge hooks in a view | Partial (surrounding tree yes) | Yes in that body | **No** for that body | React tools |
| `defhost` / foreign | Crossing is opaque JS | Via props / parent reads | Foreign region out | Leave heavy work in the library |
| Other adapter root | That root's rules | That root's rules | That root's rules | Full switch |

## Troubleshooting

| Symptom | What went wrong | Fix |
|---|---|---|
| Everything re-renders when one cell changes | Read too high, or everything is one boundary | Push `sub` down; split boundaries |
| Page-wide write re-runs every card body | Props not `=` or keys unstable | Stable `:key`; stop threading changing values; use intent data not fresh fns |
| Bulk table feels 1.5× | Broad commit fan-out — a known hard shape | Keys + bail-out + read shape first; then virtualized `defhost` list |
| Bare `rf/dispatch` / `rf/capture-frame` from a timeout "sometimes works" | Ambient `rf/*` is non-contractual in Hicasso bodies | Event-layer `:fx`, or `(rf/capture-frame (h/frame))` once `h/frame` ships |
| "I'll just use hooks everywhere for speed" | Second architecture | Isolate one island; keep app-db for semantic state |
| Headless / structural test dies after a "perf fix" | Hooks or foreign JS entered a `.cljc` body | Quarantine host code in `.cljs`; keep semantic half pure |
| Bare `[DatePicker …]` or raw JS head | Not legal | `defhost` (or `[:>]` once available) |
| SDK mounts twice / leaks | Ref attach without paired teardown | One attach, one cleanup (React 19 return-from-ref) — [Interop](05-interop.md) |
| Still slow after dropping to React | Wrong layer | Measure again — often read shape or event volume, not Hiccup |

## When not to go lower

- You are still in the **98%** — no profile, only a feeling, or a fear of
  interpretation.
- The fix is really **event volume** (e.g. controlling every keystroke on a 100-cell
  grid) — see [Controlled inputs](04-controlled-inputs.md) when-not.
- You are about to reimplement the app in hooks "for the 2%" — that is a
  substrate change, not a Hicasso escape.

## Not settled yet

| Question | Status |
|---|---|
| Whether product ever grows a compile / dual-mode path | **Not planned for v0** — single interpreted Hiccup product |
| `[:>]` availability | Ruled; may lag `defhost` in the experimental arm — [Interop](05-interop.md) |
| `h/frame` product shipping | **Ruled** (`(h/frame)` + `(rf/capture-frame (h/frame))`); implementation may still be landing — spelling **[unfrozen]** |
| Bulk broad-commit magnitudes on the converged instrument | **Not fully priced** — outside instruments already show material cost |
| Official "perf island" macro or scaffold | **None** — host edge is plain React + `defview` |
| How aggressively the guide should show host-edge examples | Open — this page stays the map; depth stays on interop / ephemeral |
