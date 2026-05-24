# `tools/xray/testbeds/step_deck/`

Single-frame **step-deck** testbed (rf2-6qgbs.2) — a clean,
step-by-step surface for exercising re-frame2 features one frame at a
time, paired with a **Xray** instance on the right. The single-frame
counterpart to the two-frame isolation testbed (rf2-6qgbs.1): both
mount the **same** shared `testdeck/` modules; this one mounts the
panel **once**.

## The shape

```
┌──────────────────────────────────┬──────────────────┐
│  Step-deck (one frame)           │                  │
│  ┌────────────────────────────┐  │   Xray          │
│  │ [Counter][Machine][Routing]│  │   (inline)       │
│  │ [Async & errors]           │  │                  │
│  ├────────────────────────────┤  │   one frame —    │
│  │ ACTION   (the controls)    │  │   no frame       │
│  │   ↓                        │  │   picker needed; │
│  │ CHECK    (live observable) │  │   every lens     │
│  └────────────────────────────┘  │   reads :step-deck│
└──────────────────────────────────┴──────────────────┘
```

One `frame-provider`, one frame id (`:step-deck`). The exercise is
straightforward: pick a tab, **act** on its controls, read the
**check** strip below, and corroborate it against the matching Xray
lens on the right. No diverging second frame to track.

## Action → check cards

The step-deck wraps the shared panel in an **ACTION → CHECK** framing:

- **ACTION** — the panel's own controls (the buttons / inputs the
  `testdeck.*` modules already register). Do this.
- **CHECK** — a one-line live readout of the active tab's canonical
  observable, read from the **same** testdeck subs the panel reads,
  annotated with the Xray lens that corroborates it. Observe this.

The CHECK strip is **not** a second copy of the tab logic — it
subscribes to the same per-frame subs (`:counter/value`, `:ws/state`,
`:testdeck/active-tab`, `:testdeck.async/status`, …) and states, in
one line, what changed and where to look in Xray.

## Shared testdeck modules — reused, not rebuilt

The app code path is the **shared testdeck** (`testdeck.*`, under
`tools/xray/testbeds/testdeck/`). This testbed reuses them verbatim
and supplies only the single-frame harness + the action→check framing:

| Namespace          | Owns                                                                       |
|--------------------|----------------------------------------------------------------------------|
| `testdeck.routes`  | Route table + tab inventory. Tabs ARE routes; nav is `:rf.route/navigate`. |
| `testdeck.counter` | Counter: L2 sub create/destroy, changeable-arg `show-greater-than-N` view, `now` cofx, clean handler-exception path. |
| `testdeck.machine` | Interactive websocket connection state machine (connect / disconnect / send) with nested states + guards + actions. |
| `testdeck.async`   | Async fx (~600ms slow request) + error surfacing.                          |
| `testdeck.panel`   | The reusable tabbed app shell + per-frame `:on-create` boot event.         |

This testbed (`step-deck.core`) supplies only the single-frame harness:
it mounts `testdeck.panel/panel` **once**, in one URL-bound
frame-provider, inside an action→check card, plus the Xray host.

## Single, URL-bound frame

The step-deck registers exactly **one** frame and lets it own the
browser URL (the default — `:url-bound? true`). Because there is one
routed frame, tab navigation (`:rf.route/navigate`, fired by the
panel's tab bar) syncs the address bar and the back button with no
two-frame URL race. Contrast the two-frame testbed, which registers
both frames **non-url-bound** per `spec/012-Routing.md` §Multi-frame
routing. Open `/machine` directly and the frame lands on the Machine
tab.

## Test surface, not tutorial

No deliberate bugs, no teaching layers, no anti-pattern demos
(`feedback_testbeds_are_test_surfaces`). The slow async fetch
(`testdeck.async`, ~600ms) and the clean handler-exception path
(`testdeck.counter`, `:counter/throw`) are FEATURES being exercised —
they light up Xray's Issues lens legitimately. Regression coverage
lives in the substrate contract tests + the Xray feature-matrix gate;
this testbed is test-free (rf2-8cevm).

## What to try in Xray

Open the page; Xray auto-mounts inline on the right. Every lens reads
the single `:step-deck` frame — no frame picker needed.

1. **Counter, step by step.** On the Counter tab, click `+` three
   times. The CHECK strip shows `:counter/value` move and
   `:counter/greater-than? 5` flip once it exceeds 5; Xray's App-db
   and Views lenses corroborate.

2. **L2 sub create/destroy.** Toggle "show parity" off then on.
   Xray's Views lens shows the `:counter/parity` L2 node disappear and
   reappear; the CHECK strip's `:counter/show-parity?` flips.

3. **Machine you drive.** On the Machine tab, click Connect.
   `:ws/connection` walks `:connecting → :authenticating → :connected`
   in the Machines lens; the CHECK strip's `:ws/state` follows. Send a
   message, then Disconnect.

4. **Tabs are routes.** Switch tabs and watch the address bar change
   (this frame owns the URL). The CHECK strip and Xray's Routing lens
   both report `:testdeck/active-tab`.

5. **Issues, legitimately.** Click Fetch on the Async & errors tab
   (~600ms slow effect → one Issue). Or click "throw" on the Counter
   tab → a clean `:rf.error/handler-exception` Issue.

## Files

- `core.cljs` — the single-frame harness + mount + action→check
  framing. Mounts the shared `testdeck.panel` once in a URL-bound
  frame-provider.
- `index.html` — minimal static host with the standard
  `[data-rf-xray-host]` aside so the Xray preload auto-mounts inline.

## Running

From `implementation/`:

```bash
npx shadow-cljs watch :examples/step-deck
```

Served at http://localhost:8031 via the top-level `:dev-http` map.

## Build target

`:examples/step-deck` (defined in
[`implementation/shadow-cljs.edn`](../../../../implementation/shadow-cljs.edn)).
The Xray preload (`day8.re-frame2-xray.preload`) is wired in
`:devtools/:preloads` — every dev build auto-mounts Xray inline on
load.

## Cross-references

- [`spec/006-ReactiveSubstrate.md`](../../../../spec/006-ReactiveSubstrate.md)
  §The cache is held inside the frame container — the per-frame sub
  cache this single frame holds.
- [`spec/002-Frames.md`](../../../../spec/002-Frames.md) — frame
  lifecycle, `reg-frame`, `:on-create`, frame-provider context.
- [`spec/005-StateMachines.md`](../../../../spec/005-StateMachines.md)
  — the machine substrate the `:ws/connection` machine exercises.
- [`spec/012-Routing.md`](../../../../spec/012-Routing.md) §Multi-frame
  routing — the `:url-bound?` ownership rule (this testbed is the
  single-url-owner case).
- [`spec/009-Instrumentation.md`](../../../../spec/009-Instrumentation.md)
  — the slow-effect Issue surface the async fx exercises.
- [`two_frame_isolation/`](../two_frame_isolation/) — the two-frame
  sibling that mounts the same `testdeck.*` modules in two frames.
