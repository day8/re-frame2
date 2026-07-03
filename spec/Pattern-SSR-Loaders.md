# Pattern — SSR Loaders (RETIRED)

> **Type:** Pattern (retired)
> **Status: RETIRED — do not implement.** This pattern was authored on two false premises and describes a render barrier that does not exist. It is kept as a stub so the historical cross-references resolve and so the retirement rationale is recorded where an implementor would look. For the shape that actually works, see [§What to use instead](#what-to-use-instead).

## Why this pattern is retired

The pattern taught a "fan-out-then-render" SSR loader: a boot-like state machine whose `:loading` state fans out N parallel HTTP fetches via `:spawn-all`, joins on all-complete, writes results into `app-db`, and lets the drain settle before `render-to-string`. A phase-level `:after {30000 :timed-out}` deadline was mandated as the SSR render budget's outer envelope.

**The pattern cannot work, because the render barrier it relied on was never installed.** Two premises the original design rested on are both false:

1. **"The JVM transport blocks the drain thread."** It does not. The managed-HTTP JVM transport is `java.net.http.HttpClient.sendAsync` ([014-HTTPRequests](014-HTTPRequests.md)) — the fetch is dispatched off-thread and the reply lands as a later event. The drain reaches fixed point with the machine's children still in flight and renders the `:loading` skeleton, not the loaded page.
2. **"The `:after` deadline bounds the fan-out under SSR."** It does not. `:after` **no-ops under SSR** ([005-StateMachines §SSR mode](005-StateMachines.md#ssr-mode), [011-SSR §`:after` is no-op under SSR](011-SSR.md#after-is-no-op-under-ssr)): the entry action skips timer scheduling and the synthetic timer-elapsed event is never queued. The mandated server deadline is a dead remedy — it never fires. A machine that leans on it to bound async work under SSR simply hangs at `:loading` until the request-scoped frame is destroyed.

So the only real SSR render barrier is the one the runtime actually installs — **`drain-blocking-resources!`, which drains resources and resources only** ([016-Resources §SSR and hydration](016-Resources.md#ssr-and-hydration)). Machines have no equivalent drain-until-quiescent barrier under SSR, and adding one was considered and rejected (the generalise-the-barrier option; see the ruling in the retirement bead). The consequence is a firm rule:

> **Machines are synchronous-only under SSR.** Async machine work — a `:spawn` / `:spawn-all` whose children exist to drive async loads (`:rf.http/managed`, websocket protocols, polling) — is **outside the SSR allowed subset**. It is a programmer error, not a supported shape. The synchronous machine substrate (plain transitions, `:always`, `:spawn` of synchronous co-effects, hierarchical entry/exit) runs identically on both platforms; async invoke work does not. See [Cross-Spec-Interactions §4 — Machines under SSR](Cross-Spec-Interactions.md#4-machines-under-ssr-allowed-subset) and [005 §SSR mode](005-StateMachines.md#ssr-mode).

The `spawn-all-under-ssr-ring` conformance fixture ([conformance/fixtures/spawn-all-under-ssr-ring.edn](conformance/fixtures/spawn-all-under-ssr-ring.edn)) pins this: a `:spawn-all` loader driven under `:platform :server` has its server deadline skipped (`:rf.machine.timer/skipped-on-server`) and its snapshot stuck at `:loading` — the fan-out never settles, exactly the out-of-subset symptom.

## What to use instead

**For SSR data-loading that must block the render, use resources, not a machine fan-out.** Route-owned blocking resources ([016-Resources §SSR and hydration](016-Resources.md#ssr-and-hydration)) are the shape the runtime's SSR render barrier (`drain-blocking-resources!`) actually understands:

- The server route handler resolves the route, computes route resources, and enqueues blocking resource ensures.
- `drain-blocking-resources!` pumps the event loop until the current nav-token's blocking resources settle, bounded by a **real** wall-clock render deadline; on timeout it settles each still-unsettled blocking entry to a structured first-load failure (`{:kind :rf.http/timeout :reason :ssr-blocking-timeout}` — [016 §SSR and hydration](016-Resources.md#ssr-and-hydration)) so the render sees a structured `:error` rather than a hung `:loading` skeleton.
- The settled resource projection rides the hydration payload; the client hydrates without a duplicate immediate fetch for fresh entries.

Resources give the parallel fan-out (N blocking ensures drain together), the per-entry decode / freshness / classification, the deadline that actually fires, and hydration — all of it inside the one barrier the SSR host adapter drives.

**Machines still have a place on the server** — the *synchronous* subset. A machine that resolves a route, sets an auth slice from a request cofx, or drives a synchronous boot sequence runs identically server-side. Just don't ask it to await network I/O during the render; that is the resources' job.

## Cross-references

- [016-Resources.md §SSR and hydration](016-Resources.md#ssr-and-hydration) — the resources-only render barrier (`drain-blocking-resources!`) that replaces this pattern.
- [Cross-Spec-Interactions.md §4 — Machines under SSR (allowed-subset)](Cross-Spec-Interactions.md#4-machines-under-ssr-allowed-subset) — the machines-are-synchronous-only-under-SSR rule and its conformance fixture.
- [005-StateMachines.md §SSR mode](005-StateMachines.md#ssr-mode) — `:after` no-ops under SSR; spawn of async-invoke children is out-of-subset.
- [011-SSR.md §`:after` is no-op under SSR](011-SSR.md#after-is-no-op-under-ssr) — the SSR-side statement of the `:after` carve-out.
- [Pattern-FormAction.md](Pattern-FormAction.md) — the POST-path sibling; its GET-path pointer to this pattern now resolves to resources-backed loading (above).
