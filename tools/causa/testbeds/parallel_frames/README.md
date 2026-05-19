# `tools/causa/testbeds/parallel-frames/`

Parallel-Frames testbed (rf2-m00rw) — **THE** canonical multi-frame
isolation demo for Causa. One app, mounted in **two** frames on **one**
page (`:above` and `:below`), with zero cross-frame coupling. Replaces
the shop testbed (rf2-yhxk3) as the canonical multi-frame exemplar; see
*Why not shop?* below.

## The shape

```
┌─────────────────────────────────────────────────────────────────┐
│  Parallel Frames demo                                           │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │  ABOVE frame  (:above)                                    │  │
│  │  Counter | Clock | Title (HTTP via :title/flow machine)   │  │
│  └───────────────────────────────────────────────────────────┘  │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │  BELOW frame  (:below)                                    │  │
│  │  Counter | Clock | Title (HTTP via :title/flow machine)   │  │
│  └───────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

Two `frame-provider` subtrees, each rooted on a separate frame id.
Each is a **fully isolated reactive context**: its own `app-db`, its
own router queue, its own sub-cache. Handlers and subs are registered
once globally; the `reg-view`-injected `dispatch` / `subscribe` resolve
via React context to the surrounding `frame-provider`'s frame id, so
the same view source produces two independent reactive contexts.

The exercise IS observing the two frames diverge as the user interacts
with each independently. There is no deliberate bug, no teaching layer,
no anti-pattern demonstration — just clean feature exercise across two
isolated frames.

## What each frame exercises

| Feature       | Wiring                                                                                  | Causa lens that lights up                                |
|---------------|-----------------------------------------------------------------------------------------|----------------------------------------------------------|
| **Counter**   | `+ / −` buttons. Events: `::counter-inc`, `::counter-dec`. Sub: `::counter`.            | Event list; App-db diff (`:counter` slot)                |
| **Clock**     | Per-frame **Tick** button — each click dispatches `::clock-tick` once against the surrounding frame. Sub: `::clock-ticks`. (rf2-gxgmt: the auto-tick chain was retired — spine pollution outweighed teaching value.) | Event list (one row per click); App-db diff (`:clock :ticks` slot) |
| **Title HTTP**| Refresh / Force-error buttons. Drives `:title/flow` machine through `:idle → :loading → :loaded / :error`. Mock fx resolves ~600ms after dispatch. | Machines lens (state chart + transitions); Trace lens (HTTP-shaped rows); Issues lens (slow effect, ~600ms) |

## Frame isolation — the load-bearing rule

Per `spec/006-ReactiveSubstrate.md` §The cache is held inside the frame
container: **subs are per-frame**. A sub registered globally runs
against whichever frame's `app-db` the dispatch envelope targets; it
**must not** reach into another frame's `app-db`. The same rule binds
this testbed: every sub here reads from the current frame's `app-db`
only. There is no cross-frame projection helper, no "route data home"
pattern, no shared root state.

This is the load-bearing architectural rule the testbed exists to
exhibit. See the saved Mike-feedback memories:

- `feedback_frames_are_isolated_contexts.md`
- `feedback_testbeds_are_test_surfaces.md`

## Issues source — the slow fetch is intentional

The mock HTTP fx (`::mock-fetch` in `core.cljs`) is deliberately slow
(~600ms — see `HTTP-MOCK-DELAY-MS`). This is **not a bug**. The delay
is calibrated to exceed Causa's slow-effect threshold so the testbed's
Issues panel surfaces every fetch as a legitimate `slow effect` Issue.

A consumer that wants the Issues panel "live" without slow waits can
toggle the constant down to ~50ms; the Issue surface is intended to
demonstrate Causa's affordance, not block normal interaction.

## What to try in Causa

Open Causa (Ctrl+Shift+C) on the page. The frame picker in the L1
ribbon switches every L2 (Events) and L4 (App-db, Views, Machines,
HTTP, Issues, Trace) panel between observing `:above` and `:below`.

1. **Frame divergence on the counters.** Click `+` three times on
   `:above`. Switch the Causa frame picker to `:below`. The Events
   list is empty for `:below`; the App-db diff shows no `:counter`
   movement.

2. **Independent clocks.** Click **Tick** in `:above` a few times,
   then click **Tick** in `:below` once. Watch the App-db diff under
   `:above` vs `:below`: only the frame whose Tick button you clicked
   sees its `:clock :ticks` advance. The same `::clock-tick` handler
   is registered once globally and resolves against whichever frame
   the dispatch envelope targets via the frame-provider context —
   proving on-demand per-frame isolation without continuous spine
   noise. (rf2-gxgmt — the previous auto-tick chain was retired.)

3. **Per-frame machine state.** Click Refresh on `:below`. Open the
   Machines tab — `:title/flow` reads `:loading`. Switch frame picker
   to `:above`. The same `:title/flow` machine reads `:idle` (because
   it's a different frame's `[:rf/machines :title/flow]` slot).

4. **HTTP correlation per frame.** While `:below` is still in
   `:loading`, observe Causa's Trace tab — one in-flight HTTP-shaped
   row, scoped to `:below`. Switch to `:above` — no in-flight rows.

5. **Issues per request.** Each fetch (~600ms) trips Causa's
   slow-effect Issue surface. The Issues panel scopes per frame; one
   row per fetch per frame.

6. **Force-error per frame.** Click Force-error on `:above`. The
   machine drives through `:idle → :loading → :error` — a legitimate
   failure cascade, not a deliberate bug. `:below`'s machine state
   (likely `:loaded` from step 3) is unaffected.

## Why not `shop/`?

The shop testbed (rf2-yhxk3) routes data across frames (e.g. it
places `:checkout/snapshot` on cart-frame's `app-db` so a sub-chain
can resolve under one frame), uses a deliberately-wrong sub for a
"fix this bug" tutorial moment, and includes "LAYER 5" teaching-layer
comments. Per Mike's saved-memory rules, this is the anti-pattern: a
testbed is a TEST surface, not a tutorial surface, and frames are
isolated contexts that must not exchange data through their app-dbs.

Parallel-Frames is the clean exemplar. A follow-on bead tracks the
shop testbed's deletion now that this clean exemplar lands.

## Files

- `core.cljs` — the app + the two-frame mount. Single namespace,
  ~350 LoC.
- `index.html` — minimal static host with the standard
  `[data-rf-causa-host]` aside so the Causa preload auto-mounts inline.
- `spec.cjs` — Playwright smoke that asserts both frames mount, the
  counters are isolated, the per-frame Tick buttons advance only
  their own `:clock :ticks` slot, Refresh on `:below` doesn't move
  `:above`, and Force-error on `:above` doesn't disturb `:below`'s
  `:loaded` state.

## Running

From `implementation/`:

```bash
npx shadow-cljs watch :examples/parallel-frames
```

Or, via the examples orchestrator:

```bash
cd implementation
EXAMPLES_FILTER=parallel-frames node ../examples/scripts/serve-and-run-examples-tests.cjs
```

## Build target

`:examples/parallel-frames` (defined in
[`implementation/shadow-cljs.edn`](../../../../implementation/shadow-cljs.edn)).
The Causa preload (`day8.re-frame2-causa.preload`) is wired in
`:devtools/:preloads` — every dev build auto-mounts Causa inline on
load.

## Cross-references

- [`spec/006-ReactiveSubstrate.md`](../../../../spec/006-ReactiveSubstrate.md)
  §The cache is held inside the frame container — the normative
  statement of per-frame isolation. This testbed is the canonical
  demo.
- [`spec/002-Frames.md`](../../../../spec/002-Frames.md) — frame
  lifecycle, `reg-frame`, `:on-create`, frame-provider context.
- [`spec/005-StateMachines.md`](../../../../spec/005-StateMachines.md)
  — the machine substrate the `:title/flow` exercises.
- [`spec/009-Instrumentation.md`](../../../../spec/009-Instrumentation.md)
  — the slow-effect Issue surface the mock fx exercises.
