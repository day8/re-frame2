# `tools/xray/testbeds/two_frame_isolation/`

Two-frame isolation testbed (rf2-6qgbs.1) — **THE** canonical
multi-frame isolation surface for Xray. One app, mounted in **two**
frames on **one** page (`:above` and `:below`) on the left, with a
**Xray** instance on the right. Replaces the parallel-frames
prototype.

## The shape

```
┌──────────────────────────────────┬──────────────────┐
│  Two-frame isolation             │                  │
│  ┌────────────────────────────┐  │   Xray          │
│  │ ABOVE frame  (:above)      │  │   (inline)       │
│  │ [Counter][Machine][Routing]│  │                  │
│  │ [Async & errors]           │  │   frame picker   │
│  └────────────────────────────┘  │   switches every │
│  ┌────────────────────────────┐  │   L2 / L4 panel  │
│  │ BELOW frame  (:below)      │  │   between        │
│  │ [Counter][Machine][Routing]│  │   :above /       │
│  │ [Async & errors]           │  │   :below         │
│  └────────────────────────────┘  │                  │
└──────────────────────────────────┴──────────────────┘
```

Two `frame-provider` subtrees, each rooted on a separate frame id. Each
is a **fully isolated reactive context**: its own `app-db`, its own
router queue + route slot, its own sub-cache, its own machine snapshot.
Handlers and subs are registered once globally; the `reg-view`-injected
`dispatch` / `subscribe` resolve via React context to the surrounding
`frame-provider`'s frame id, so the same view source produces two
independent reactive contexts.

The exercise IS observing the two frames diverge as the user interacts
with each independently. No deliberate bug, no teaching layer, no
anti-pattern demonstration — just clean feature exercise across two
isolated frames.

## Shared testdeck modules

The app code path is the **shared testdeck** (`testdeck.*`, under
`tools/xray/testbeds/testdeck/`). The single-frame step-deck testbed
(rf2-6qgbs.2) mounts these SAME modules — they are deliberately
reusable:

| Namespace          | Owns                                                                       |
|--------------------|----------------------------------------------------------------------------|
| `testdeck.routes`  | Route table + tab inventory. Tabs ARE routes; nav is `:rf.route/navigate`. |
| `testdeck.counter` | Counter: L2 sub create/destroy, changeable-arg `show-greater-than-N` view, `now` cofx, clean handler-exception path. |
| `testdeck.machine` | Interactive websocket connection state machine (connect / disconnect / send) with nested states + guards + actions. |
| `testdeck.async`   | Async fx (~600ms slow request) + error surfacing.                          |
| `testdeck.panel`   | The reusable tabbed app shell + per-frame `:on-create` boot event.         |

This testbed (`two-frame-isolation.core`) supplies only the two-frame
harness: it mounts `testdeck.panel/panel` twice, in two non-URL-bound
frame-providers, plus the Xray host.

## Frame isolation — the load-bearing rule

Per `spec/006-ReactiveSubstrate.md` §The cache is held inside the frame
container: **subs are per-frame**. A sub registered globally runs
against whichever frame's `app-db` the dispatch envelope targets; it
**must not** reach into another frame's `app-db`. Every sub here reads
the current frame's `app-db` only. There is no cross-frame projection
helper, no "route data home" pattern, no shared root state. The
cross-frame anti-pattern is structurally impossible — the
`reg-view`-injected accessors only ever see the current frame.

See the saved Mike-feedback memories:

- `feedback_frames_are_isolated_contexts.md`
- `feedback_testbeds_are_test_surfaces.md`

## Tabs are routes (per frame)

Each tab is a registered route (`testdeck.routes`). Switching tabs is
`:rf.route/navigate`, dispatched through the clicked frame's injected
`dispatch` — so the ABOVE frame can sit on Counter while BELOW sits on
Machine. The active route id lives in each frame's own `:rf/route`
slot.

### URL ownership across two frames

A browser has one address bar but this page has two routed frames. Per
`spec/012-Routing.md` §Multi-frame routing only one frame may own the
URL. Both frames are registered **non-url-bound**
(`:url-bound? false`), so each frame's `:rf/route` slot updates on tab
nav while the browser-history push no-ops (no two-frame URL race). The
route SLOT — the isolation point — stays per-frame; only the shared
browser-URL sync is suppressed. (The single-frame step-deck, rf2-6qgbs.2,
uses the default URL-bound frame, so its tab nav syncs the address bar.)

## Issues source — the slow fetch is intentional

The async fx in `testdeck.async` (`SLOW-MS` ~600ms) is **not a bug**.
The delay is calibrated to exceed Xray's slow-effect threshold so the
Issues panel surfaces every fetch as a legitimate `slow effect` Issue.
The clean handler-exception path on the Counter tab (`:counter/throw`)
is likewise a FEATURE being exercised — the supported way to light up
the Issues lens — not a buggy demo.

## What to try in Xray

Open the page; Xray auto-mounts inline on the right. The frame picker
in the L1 ribbon switches every L2 (Events) and L4 (App-db, Views,
Machines, Routing, Issues, Trace) panel between observing `:above` and
`:below`.

1. **Frame divergence on the counters.** Click `+` three times on
   `:above`'s Counter tab. Switch the Xray frame picker to `:below`.
   The Events list is empty for `:below`; the App-db diff shows no
   `:counter` movement.

2. **Per-frame routing.** Navigate `:above` to the Machine tab and
   `:below` to the Routing tab. Each frame's `:rf/route` slot differs;
   the Xray routing lens shows each frame's active route independently.

3. **Per-frame machine state.** Click Connect on `:below`'s Machine
   tab. Open the Machines lens — `:ws/connection` walks `:connecting →
   :authenticating → :connected`. Switch the picker to `:above`: the
   same machine reads `:disconnected`.

4. **L2 sub create/destroy.** On the Counter tab, toggle "show parity"
   off then on. Xray's Views lens shows the `:counter/parity` L2 node
   disappear and reappear.

5. **Changeable-arg view.** Change the show-greater-than threshold. The
   `[:counter/greater-than? N]` dynamic sub backs a fresh cache entry
   per threshold.

6. **Issues per request, per frame.** Click Fetch on `:above`'s
   Async & errors tab (~600ms slow effect → one Issue, scoped to
   `:above`). Or click "throw" on the Counter tab → a clean
   `:rf.error/handler-exception` Issue, scoped to the frame it fired in.

## Files

- `core.cljs` — the two-frame harness + mount. Mounts the shared
  `testdeck.panel` twice in non-url-bound frame-providers.
- `index.html` — minimal static host with the standard
  `[data-rf-xray-host]` aside so the Xray preload auto-mounts inline.

This testbed is test-free (rf2-8cevm). Regression coverage lives in the
substrate contract tests (`npm run test:cljs`), the Xray
feature-matrix gate, and the multi-frame e2e tests under
`tools/xray/test/.../panels_e2e/`.

## Running

From `implementation/`:

```bash
npx shadow-cljs watch :examples/two-frame-isolation
```

Served at http://localhost:8030 via the top-level `:dev-http` map.

## Build target

`:examples/two-frame-isolation` (defined in
[`implementation/shadow-cljs.edn`](../../../../implementation/shadow-cljs.edn)).
The Xray preload (`day8.re-frame2-xray.preload`) is wired in
`:devtools/:preloads` — every dev build auto-mounts Xray inline on
load.

## Cross-references

- [`spec/006-ReactiveSubstrate.md`](../../../../spec/006-ReactiveSubstrate.md)
  §The cache is held inside the frame container — the normative
  statement of per-frame isolation. This testbed is the canonical demo.
- [`spec/002-Frames.md`](../../../../spec/002-Frames.md) — frame
  lifecycle, `reg-frame`, `:on-create`, frame-provider context.
- [`spec/005-StateMachines.md`](../../../../spec/005-StateMachines.md)
  — the machine substrate the `:ws/connection` machine exercises.
- [`spec/012-Routing.md`](../../../../spec/012-Routing.md) §Multi-frame
  routing — the `:url-bound?` ownership rule.
- [`spec/009-Instrumentation.md`](../../../../spec/009-Instrumentation.md)
  — the slow-effect Issue surface the async fx exercises.
