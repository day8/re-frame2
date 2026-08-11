# Callback identity and retirement — the verdict

**Verdict: NOT ADMITTED. Fresh-per-render is sufficient, and is recorded as sufficient.** A narrow stable-event primitive is not added.

This record discharges the standing rule in [specification §4.1](specification.md#41-events):

> Generated intent callbacks may be fresh per render. A narrow stable-event primitive is admitted only if a realistic retaining host demonstrates a material problem and the solution is safe across abandonment, frame reincarnation, and teardown.

Owned by `rf2-hic-029`. The evidence is `implementation/hicasso/test/re_frame/hicasso/retaining_host_callbacks_dom_cljs_test.cljs`, a browser-tier witness built for this question and for nothing else. The risk register's row is [Callback identity and retirement](lanes/adversarial-risks.md#phase-1-kernel-risks).

## What the rule asks, and what answers each clause

The rule is a conjunction, and its two halves need different instruments.

*A material problem* is a COUNT. A fresh closure per render costs a native DOM element nothing — React assigns the new listener and moves on. It costs a **memoized foreign child** its bail-out, because `React.memo` compares with `Object.is` per prop and two closures minted in two renders are never the same object. So the retaining host is the only place on a page where callback identity is a price rather than a detail.

*Safe across abandonment, reincarnation and teardown* is a ROUTING claim, and it is asked of a callback the vendor kept.

## The mechanism, read from source before anything was measured

`impl.intent`'s `intent-handler`, `key-map-handler` and `event-callback` each allocate a fresh closure at LOWERING time, and lowering happens per render. Every event-position carrier therefore churns identity per render — the intent vector and the key-map exactly as much as `h/fn`. The churn is the lowering's, not the author's.

Two paths already preserve identity, and both say so in their own docstrings: `lower-declared-prop` hands an unmarked function through untouched at every contract and hands a marked `h/fn` through by identity at `:handler`; and `codec/host-prop-value` crosses functions by identity *"so `React.memo` and every downstream bail-out that compares handler identity keep working"*.

**Hicasso's own boundaries never had this problem.** `codec/boundary-props=` compares with CLJS `=`, and an intent vector or key-map is data, so a boundary child bails on value equality. The question exists only at a foreign `React.memo` edge.

## The measurement

Nine carriers, one memoized vendor, one parent that re-renders five times on a piece of app-db the vendor's props do not read. The vendor's mount is one run, so **1 is a bail-out and 6 is a memo defeated by identity**. There is no third answer.

| Carrier | Vendor body runs | Dispatches? |
|---|---|---|
| `:absent` — no callback (control) | 1 | — |
| `:plain-fn` — hoisted unmarked fn | 1 | no |
| `:handler` — hoisted `h/fn` at a `:handler` declaration | 1 | no |
| `:native-stable` — `n/use-frame` + `react/useCallback` | **1** | **yes** |
| `:intent-vector` | 6 | yes |
| `:hfn-inline` | 6 | yes |
| `:hfn-hoisted` | 6 | yes |
| `:key-map` | 6 | yes |
| `:native-churn` — the same island without `useCallback` | 6 | yes |

The ordinary path pays nothing for the safety: one tick runs **1** boundary body, with or without a retaining host on the page. The incarnation pin lives in a memo row acquired once per incarnation, not in work done per render.

**`:hfn-hoisted` reading 6 is the load-bearing row.** Hoisting the author's own function out of the render does not hoist what the vendor sees, because the `:event` wrapper — the thing that closes over the frame-locked dispatch — is re-minted every render regardless. So there is no author-side workaround by hoisting, and the question cannot be dismissed as an authoring mistake.

**`:native-stable` reading 1 while still dispatching is the row that decides the verdict.**

## Safety, and why the existing answer is already the safe one

The retained-callback rows are asserted against a callback the vendor stashed at its mount render:

- **retention** — five renders later, the mount render's closure still dispatches into its own incarnation, with no refusal. Staleness across renders is not staleness across incarnations, and only the second is a fault.
- **teardown** — after the root is unmounted and the frame retired, it is refused with exactly one always-on `:rf.error/frame-destroyed`, carrying the captured frame id and the refused event's head. The loudness is the point: silence would be indistinguishable from a handler that quietly worked.
- **reincarnation** — against a successor seated under the same public id, the successor's app-db is untouched and the drop is again loud. A vendor mounted under the successor gets a working callback, so the silence is a refusal rather than a runtime in which nothing dispatches.
- **the sabotage control** — an `rf/capture-frame` taken while no frame is live pins nothing, stays address-directed, and DOES write the successor. The refusals above are therefore capable of failing, and pass because of the pin.

`n/use-frame` inherits all of this by construction, and its docstring already states the property: the ops map is the runtime's own memo row, identical across renders under one frame, and **replaced when that incarnation is superseded**. A `useCallback` keyed on it is stable exactly as long as it is safe to be, and no longer. As that docstring puts it, *"a memo keyed on the frame KEYWORD would pass every stability test and fail exactly this one"*.

## Why NOT ADMITTED

The rule admits a primitive only if a material problem has a safe solution. The problem is real — five carriers defeat a vendor's memo, and hoisting does not help. But the safe solution **already exists on the shipped surface**, is identity-stable, dispatches, and is incarnation-pinned: `n/use-frame` plus React's own `useCallback`, inside the tier whose whole premise is that past the fence React's hooks are the vocabulary. A new primitive would be a second mechanism for a problem the native tier already solves, which is the shape [§3.4 Capability pays rent](specification.md#34-capability-pays-rent) exists to refuse.

Frequency agrees. Across every application-shaped Hicasso source in the repository — the four witness applications and the HMR testbed — the number of callbacks handed to a memoized foreign child is **zero**. The witness applications declare no `defhost` at all, and the testbed's two declare no `:callbacks`.

So the interpreted edge keeps fresh-per-render, which is what makes an intent a value and keeps the position table honest; and the one edge where identity can cost something has an answer that is already written, already tested, and already safe across all three axes the rule names.

## What would reopen this

A witness application that genuinely needs a dispatching callback on a memoized foreign child **and** cannot use the native tier there. That combination has not appeared. If it does, the row to re-run is the grid above, and the bar is unchanged: a localized host-edge primitive, safe across abandonment, reincarnation and teardown, or nothing.

## Provenance and known limits

Every figure above was captured from a browser-lane run of the witness. Three limits are stated rather than left to be discovered:

- **A vendor body run is not a millisecond.** It is the event `React.memo` exists to prevent, counted where it happens; what one costs depends on the vendor, which is a fact about somebody else's code.
- **The grid's arms need per-arm runtime isolation.** Tearing an arm down without it contaminates later arms, and the churn rows then read 1 — the bail-out answer — for a reason that has nothing to do with the carrier. This was observed, and it is why the witness tears each arm down through the fixture door.
- **The witness does not currently land green in the browser lane**, for a reason outside itself: a suite sorting at that namespace position perturbs a neighbouring suite whose async cleanup outlives its own `done` (`rf2-d3tc`). The numbers above are unaffected — they are this suite's own assertions — but the lane cannot be green until that bead is fixed.
