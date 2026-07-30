# The reagent-slim non-reactive arm — diagnosis (rf2-z3vlz)

**Verdict: the bench arm's composition. Not the adapter, not the bundle.**

HD-008's donor-gate instrument reported that in the `:advanced` bench bundle
compiling stock `reagent`, `reagent2` (reagent-slim) and `uix` together, a
`reg-view` tree mounted through `reagent2.dom.client` rendered correctly at
mount and then never re-rendered on a write — 78 of 78 writes failed the DOM
read-back, while the stock-Reagent arm in the identical harness passed 78 of
78. [rf2-z3vlz](../decisions.md) named three candidates and established none:

- **(a)** a reagent-slim **adapter defect** — shipped, first-class, users
  affected;
- **(b)** a **mixed-bundle artefact** — stock `reagent` and `reagent2`
  coexisting in one `:advanced` bundle;
- **(c)** a defect in the **bench arm's composition**.

It is (c), and the failing ingredient is one microtask.

`lane/verified-write!` writes, **yields one microtask**, and only then calls the
arm's drain. reagent-slim's render scheduler is **microtask-based**
(`reagent2.impl.batching`: *"a microtask-based scheduler … no
requestAnimationFrame fallback is required"*), so by the time the drain runs,
reagent-slim has already emptied its own component queue and issued its
`forceUpdate` **outside** any `flushSync` boundary. React holds that work at the
default lane — which is precisely the hazard
`reagent2.dom.client/flush-render!` documents (*"a bare `forceUpdate` issued
from outside React's batching context is subject to automatic batching: React
SCHEDULES the re-render rather than committing it"*) — and the `flush-render!`
that follows finds an empty queue and commits nothing. Stock Reagent survives
the identical harness because its component queue is **`requestAnimationFrame`**
-scheduled and is therefore still full one microtask later: its drain finds the
work and commits it inside the boundary.

Two corrections to the bead's statement of the symptom fall out of this:

1. **The arm is not non-reactive; its commit is LATE.** All 12 of 12 writes
   reach the DOM at the `:macrotask` rung. `NEVER` and `LATE` are different
   findings and the escalation ladder below tells them apart.
2. **re-frame is not on the causal path at all.** The positive control — a
   plain reagent-slim component reading a plain `reagent2.core/atom`, no frame,
   no subscription, no adapter hook — reproduces the failure exactly, in every
   bundle.

## What was run

| | |
|---|---|
| Producing commit | `f998a74c4aeb44ddc4dc2830dff31cb7181d3dfb` |
| Runtime | **chromium 147.0.7727.15** (playwright, headless), `:advanced`, `goog.DEBUG false` |
| Host | Windows 11, 24 logical cores, 32 GB |
| Taken | 2026-07-31 00:36 AUSEST |
| Instrument | `implementation/freehand/test/re_frame/bench/hicasso/z3vlz_{probe,slim_substrate,reagent_substrate}.cljs` |
| Entries | `z3vlz_{slim_only,slim_reagent,slim_uix,mixed}.cljs` |
| Build id | `:hicasso-bench` via `--config-merge` — **no new build id**, `implementation/shadow-cljs.edn` untouched |

```bash
cd C:/path/to/re-frame2
node implementation/freehand/test/re_frame/bench/hicasso/z3vlz_run.cjs

# one rung at a time
Z3VLZ_ONLY=slim-only Z3VLZ_PORT=8171 \
  node implementation/freehand/test/re_frame/bench/hicasso/z3vlz_run.cjs
```

Exit codes: `0` every declared bundle matched and every page ran; `1` a build
failed, a bundle did not match its declared composition, a page threw, or the
probe recorded a fatal. **The arm-order guard is NOT ENGAGED** — this driver
publishes no timed figure and no arm-to-arm ratio, so there is nothing for it to
adjudicate. The only numbers here are write and DOM read-back counts.

## The design: bundle composition is the variable, the probe is the constant

One probe namespace holds everything that does not change — the subscription,
the event, the `reg-view` boundaries, the frame provider, both write mechanisms
the bead reproduced with, the drain escalation ladder and the DOM read-back
accounting. Four entry namespaces differ **only** in what they `:require`, one
added namespace per rung. Each bundle's composition is then checked against its
own **source map** before its page is driven, so *"no stock reagent compiled in"*
is verified rather than asserted:

| bundle | compiled sources | stock `reagent` | `reagent2` | `uix` |
|---|---:|---|---|---|
| `slim-only` | 96 | **no** | yes | no |
| `slim+reagent` | 108 | yes | yes | no |
| `slim+uix` | 102 | **no** | yes | yes |
| `mixed` (the bead's bundle) | 114 | yes | yes | yes |

## The result

Every cell is `N unverified of M` — all 8 cells read back out of the DOM inside
each write's own window, `M` = 12 writes per order, alternating
`frame/replace-app-db!` and `dispatch-sync` of a registered event.

### The arm under test — `reg-view` boundaries reading `rf/subscribe`

| page | mount correct | write **inside** the drain | write, **drain immediately** | write, **yield**, then drain |
|---|---|---|---|---|
| `slim-only` / install reagent-slim | yes | **0 of 12** | **0 of 12** | **12 of 12** |
| `slim+reagent` / install reagent-slim | yes | **0 of 12** | **0 of 12** | **12 of 12** |
| `slim+uix` / install reagent-slim | yes | **0 of 12** | **0 of 12** | **12 of 12** |
| `mixed` / install reagent-slim | yes | **0 of 12** | **0 of 12** | **12 of 12** |
| `slim+reagent` / install **stock reagent** | yes | **0 of 12** | **0 of 12** | **0 of 12** |
| `mixed` / install **stock reagent** | yes | **0 of 12** | **0 of 12** | **0 of 12** |

### The positive control — plain component, plain `reagent2.core/atom`, no re-frame

| page | write **inside** the drain | write, **drain immediately** | write, **yield**, then drain |
|---|---|---|---|
| every reagent-slim page (4 of them) | **0 of 12** | **0 of 12** | **12 of 12** |
| every stock-Reagent page (2 of them) | **0 of 12** | **0 of 12** | **0 of 12** |

Read the two tables together and the three candidates resolve themselves.

- **Not (a).** The single-substrate reagent-slim bundle — 96 compiled sources,
  stock Reagent verifiably absent — re-renders on **every** write under the
  adapter's own documented drain contract: `0 unverified of 12`, all at the
  `:sync` rung, under both write mechanisms. There is no adapter defect to fix.
- **Not (b).** Bundle composition changes nothing. Four bundles spanning 96 to
  114 compiled sources, with and without stock Reagent, with and without UIx,
  return byte-identical verdicts.
- **Not a late-binding collision either.** The positive control has no re-frame
  on its path — no frame, no `subscribe`, no `:adapter/*` hook — and fails in
  exactly the same pattern. Whatever this is, `re-frame.views` cannot be
  reaching it.
- **(c), and precisely located.** The only variable that moves the result is
  the microtask between the write and the drain, and it moves the result only
  for the substrate whose scheduler is a microtask.

The `db-followed-all? true` slot is recorded on every write: `app-db` took the
new value in all 36 writes per arm, in every bundle, so the failure is on the
**view leg** and never on the event leg.

## The escalation ladder, and why the answer is LATE rather than NEVER

Each failed read-back is retried through progressively more generous drains:
the arm's own drain again (`:redrain`), then a macrotask (`:macrotask`), then
two animation frames (`:raf2`). Under `yield-then-drain` on reagent-slim, all
12 writes land at `:macrotask` — never at `:sync`, never at `:redrain`, and
never lost. A second synchronous `flush-render!` does **not** recover the
commit, which is the load-bearing detail: once reagent-slim has issued its
`forceUpdate` outside a boundary, no synchronous drain gets it back. Only
letting React's own scheduler run does.

## The repair, and where it belongs

The repair is the ARM, not the adapter and not the guard. A reagent-slim write
row must not put a microtask between the write and the drain. Either shape
works and both read `0 unverified of 12`:

```clojure
;; the adapter's documented production contract — the write inside `f`
(rdc2/flush-render! (fn [] (write!) (ratom2/flush!)))

;; or: no yield between the two
(write!)
(rdc2/flush-render! (fn [] (ratom2/flush!)))
```

`lane/verified-write!`'s microtask is deliberate and is load-bearing for the
React-hook arms, whose notification really is queued — its docstring says so,
and `:gap-ms` prices it per arm. It is simply not neutral: for an arm whose
substrate schedules on the same microtask queue the harness is yielding to, the
yield hands the commit to React and the drain arrives a turn too late. A shared
instrument that yields once for everybody cannot serve both scheduler families,
and the fix therefore belongs in the arm's composition (or in an arm-level
opt-out), not in the shared yield.

**This is a measurement finding, not a shipped-code finding.** No consumer is
affected: an application does not write and then yield before flushing — it
dispatches, and the adapter's own path commits. The behaviour is already
covered by the shipped reagent-slim adapter smoke
(`implementation/adapters/reagent-slim/testbed/smoke.cjs`), which mounts a
`reg-view` tree under `rf/frame-provider` through `reagent2.dom.client`, clicks,
and asserts the counter text changes — in a slim-only bundle. That smoke was
green throughout, and it was right.

## What this page does NOT claim

No timing. Nothing here is comparable with a bar row, and the driver publishes
no ratio precisely so that it cannot be quoted as one. The HD-008 slim write
figures the instrument suppressed — reading 0.16–0.50× the floor while changing
nothing — remain unpublished and unpublishable: they were taken through the
composition this page identifies as broken, so they price a commit that had not
happened. They must be re-taken through a corrected arm, not rescued.
