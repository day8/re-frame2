# Cross-Spec Interactions

> **Type:** Reference
> Edge cases at the boundaries between Specs. Each interaction names which Specs meet, the scenario, the decided behaviour, and the reason. Owned content lives in the Specs cited; this doc surfaces the interaction so an implementor doesn't have to re-derive it.

Each numbered Spec is locked, but the points where two Specs meet have edge cases that no single Spec naturally owns. An AI implementing the CLJS reference (per [Goal — AI-implementable from the spec alone](000-Vision.md#ai-implementable-from-the-spec-alone)) will hit these interactions and need a canonical answer; this doc collects them.

> **What this is not.** A redefinition of any Spec. Where the cited Spec already answers the interaction, this doc points at the answer. Where the answer requires composing two Specs, this doc states the composition and the reason. Drift rule: if a citation here disagrees with the owning Spec, the Spec wins; this doc is wrong.

## How to read this document

Each interaction is one numbered subsection with five fields:

- **Specs that meet** — typically two, occasionally three.
- **Scenario** — one sentence describing the situation that surfaces the interaction.
- **Behaviour** — the decided outcome.
- **Reason** — why the behaviour was chosen, often a constraint pulled forward from a goal.
- **Status** — `Pinned`, `Provisional`, or `Locked` (see legend immediately below).

Interactions are grouped by the Specs that meet, in roughly the order an implementor encounters them. The grouping is for navigation only; each interaction stands on its own.

### Status legend

| Marker | Meaning |
|---|---|
| **`Pinned`** | A working fixture in the conformance corpus enforces this rule. An implementation that fails the fixture fails conformance. |
| **`Provisional`** | The rule is documented as a decided behaviour, but no fixture exists yet. Implementations should follow it; deviation is not yet detectable through the corpus. Provisional → Pinned as fixtures land. |
| **`Locked`** | The entry documents a normatively-settled, family-level architectural rule already owned by the cited Spec; there is no run-time scenario for a fixture to capture. The interaction exists here only to surface the rule from the perspective of where Specs meet. Locked entries do not transition to `Pinned`. |

**Current state of the corpus (point-in-time snapshot, 2026-05-09; the live source of truth is the fixture set under [conformance/](conformance/README.md) and each entry's own Status line).** The first wave of cross-Spec fixtures pinned interactions 5, 6, 7, and 19. The second wave closed the conformance runner's `reg-machine` / `:throw` op gap and pinned interactions 11, 12, and 17. One entry (interaction 21) is `Locked` — a documentation-only family-level rule, owned by Spec 004, not fixture-trackable. The remaining entries stay `Provisional`; their fixture filenames remain *targets for future authoring* until the corresponding runner / runtime gaps close (rendering capability; adapter-lifecycle hooks; tool-pair time-travel; hot-reload-mid-cascade hooks; etc.). When a fixture lands, the entry's status flips to **`Pinned`** and the filename becomes a live link.

## Frames × Machines

### 1. Frame disposal with active machine instances

- **Specs:** [002-Frames §Destroy](002-Frames.md#destroy), [005-StateMachines §Hierarchical compound states §Entry/exit cascading](005-StateMachines.md#entryexit-cascading-along-the-lca).
- **Scenario:** `(rf/destroy-frame! :auth)` is called while the frame holds active machine instances mid-flight.
- **Behaviour:** Each active machine runs its `:exit` cascade from leaf to root in **reverse-creation order** (the most recently spawned instance disposes first). Pending `:after` timers are cancelled — staleness via the epoch idiom (per [005 §Epoch-based stale detection](005-StateMachines.md#epoch-based-stale-detection)) means timers that fire after destroy land against an unmatching epoch and no-op. Outbound `:fx` from those `:exit` actions runs through `do-fx`. After every machine has settled, the sub-cache disposes (per [006 §Subscription cache — Lifetime contract](006-ReactiveSubstrate.md#lifetime-contract--frame-disposal)). The substrate adapter releases frame-scoped resources. `:rf.frame/destroyed` traces; `:rf.machine/disposed` traces fire per instance.
- **Reason:** Run-to-completion (per [002 §Run-to-completion](002-Frames.md#run-to-completion-dispatch-drain-semantics)) extends to disposal — letting `:exit` cascades complete preserves the invariant that every state has its symmetric exit. Reverse-creation order matches the actor-disposal convention.
- **Status:** `Pinned` — [`conformance/fixtures/cross-spec-frame-destroy-with-machines.edn`](conformance/fixtures/cross-spec-frame-destroy-with-machines.edn).

### 2. Sub-cache hit inside a machine microstep

- **Specs:** [005-StateMachines §Drain semantics §Level 3](005-StateMachines.md#level-3--within-a-single-machine-event), [006-ReactiveSubstrate §Subscription cache](006-ReactiveSubstrate.md#subscription-cache--contract-and-operational-semantics).
- **Scenario:** A machine event triggers a multi-microstep cascade (an `:always` whose guard becomes true via the triggering action). A subscription reads the machine's externally-observable state slot and is queried **after the dispatch settles** — a post-drain external read.
- **Behaviour:** The sub returns the value computed against the **committed post-cascade snapshot** — the single snapshot the machine commits at the end of the Level-3 cascade — never an intermediate microstep value. Subs do not see the in-flight `:data` of the current cascade; sub-cache invalidation fires once after the cascade's final commit, not after each microstep.
- **Not supported — the in-callback read.** A machine `:guard` / `:action` / `:entry` / `:exit` MUST NOT itself call `(rf/subscribe-once …)` (nor read app-db any other ambient way) to make its decision. An in-callback ambient read is unrecorded, so replay can select a *different* transition than the original run — breaking 005's token-grain replay contract ([005 §Causal host facts](005-StateMachines.md#causal-host-facts--rfcofx-ep-0017)) and the pure-fn conformance mode. A machine callback receives external facts by **payload threading** or via a **declared recordable coeffect** on `:rf.cofx` — not by reaching into the sub-cache. This interaction pins the *external* read (a sub over the snapshot), which is the only supported shape.
- **Reason:** External observers see one macrostep per machine event (per [005 §Drain semantics §Level 3](005-StateMachines.md#level-3--within-a-single-machine-event)). Subs are external observers. Letting subs observe in-flight data would expose the partial-snapshot view the macrostep contract specifically avoids.
- **Status:** `Pinned` — [`conformance/fixtures/cross-spec-machine-microstep-subscribe.edn`](conformance/fixtures/cross-spec-machine-microstep-subscribe.edn).

### 3. Machine spawn at boot before substrate adapter ready

- **Specs:** [005-StateMachines §Spawning](005-StateMachines.md#spawning--dynamic-actors), [006-ReactiveSubstrate §Adapter selection](006-ReactiveSubstrate.md#adapter-selection-at-boot).
- **Scenario:** A `(rf/reg-frame :app {:initial-events [[:boot]]})` fires `:boot` which spawns a machine — but boot order means the substrate adapter has not been installed yet.
- **Behaviour:** `:initial-events` are queued on the frame's router but the drain does not start until the adapter is installed. Once `(rf/install-adapter! ...)` completes, the queue drains. Spawned machines therefore always run against an installed adapter.
- **Reason:** A machine action that calls `(rf/subscribe-once ...)` must reach a working sub-cache, which requires the adapter. Deferring drain until adapter-ready is the simplest invariant.
- **Status:** `Provisional` — fixture pending: `boot-order-adapter-ready.edn`.

## Machines × SSR

### 4. Machines under SSR (allowed-subset)

- **Specs:** [005-StateMachines §SSR mode](005-StateMachines.md#ssr-mode), [011-SSR §`:after` is no-op under SSR](011-SSR.md#after-is-no-op-under-ssr).
- **Scenario:** A request-scoped frame on the server hosts machines that drive the SSR boot sequence (auth probe, profile fetch, route resolution).
- **Behaviour:** The **synchronous** machine substrate runs identically on the server — plain transitions, `:always` microsteps, hierarchical entry/exit cascading, and `:spawn` of children that do only **synchronous** co-effects. Two things carve out under `:ssr-server`:
  1. **`:after` is a no-op.** The entry action skips timer scheduling; the synthetic timer-elapsed event is never queued; the request frame is destroyed before any timer could fire. The runtime emits `:rf.machine.timer/skipped-on-server` in place of `:rf.machine.timer/scheduled` ([005 §SSR mode](005-StateMachines.md#ssr-mode)).
  2. **Async machine work is outside the allowed subset — machines are synchronous-only under SSR.** A `:spawn` / `:spawn-all` whose children exist to drive *async* loads (`:rf.http/managed`, websocket protocols, polling) is a **programmer error** on the server, not a supported shape. There is **no server-side render barrier for machines**: the only SSR render barrier the runtime installs is `drain-blocking-resources!` ([016-Resources §SSR and hydration](016-Resources.md#ssr-and-hydration)), which drains **resources only**. The JVM managed-HTTP transport is `sendAsync` ([014](014-HTTPRequests.md)), so a machine's in-flight fetch does not settle the drain — the render sees the `:loading` skeleton — and the `:after` deadline that might have bounded it is itself a no-op (point 1). An async loader machine under SSR therefore hangs at `:loading` and out-of-subset. The supported SSR fan-out-and-load shape is route-owned blocking **resources** (the barrier `drain-blocking-resources!` understands), not a machine fan-out — see [Pattern-SSR-Loaders §What to use instead](Pattern-SSR-Loaders.md#what-to-use-instead).
- **Reason:** Server-side `setTimeout` either leaks (timer outlives the request) or is artificial (the SSR render has no time to wait), so `:after` must no-op — and once it does, nothing bounds an async machine fan-out on the server. Rather than generalise the resources render barrier to also drain machine-issued async work (a machine work-ledger + await-quiescence pump — considered and **rejected** as disproportionate), the substrate confines machines to the synchronous subset under SSR and directs blocking data-load to resources. The earlier framing (a mandated server `:after` deadline plus a to-be-emitted `:rf.error/ssr-async-invoke-without-deadline` detection) rested on the false premise that the JVM transport blocks the drain and that `:after` installs under SSR — both untrue — and is retired with [Pattern-SSR-Loaders](Pattern-SSR-Loaders.md).
- **Status:** `Pinned` — [`conformance/fixtures/cross-spec-machines-under-ssr.edn`](conformance/fixtures/cross-spec-machines-under-ssr.edn) pins the `:after` no-op; [`conformance/fixtures/spawn-all-under-ssr-ring.edn`](conformance/fixtures/spawn-all-under-ssr-ring.edn) pins the out-of-subset symptom — a `:spawn-all` loader under `:platform :server` has its server deadline skipped and its snapshot stuck at `:loading`, never reaching `:ready`. There is no `:rf.error/ssr-async-invoke-without-deadline` op-type: the retired remedy is gone, the out-of-subset property is asserted directly against the observable drain outcome instead.

### 5. Hydration with machine snapshots

- **Specs:** [005-StateMachines §Where snapshots live](005-StateMachines.md#where-snapshots-live), [011-SSR §Hydration payload](011-SSR.md).
- **Scenario:** The server renders a page that ran machines to completion of their SSR-eligible drain (see Interaction 4); the client hydrates and continues from the server's settled state.
- **Behaviour:** Machine snapshots live at `[:rf.runtime/machines :snapshots <id>]` inside the frame's **runtime-db** partition per [Conventions §Reserved runtime-db keys](Conventions.md#reserved-runtime-db-keys). The hydration payload is the frame-state (both partitions); machines deserialise as data. After hydration, the client mounts the same machine handlers (registered identically); subsequent dispatches resolve to the (now client-side) handler. `:after` timers that the server skipped now schedule on the client per the entry action's normal behaviour.
- **Reason:** Machine state inheriting [Goal 3 — Frame state revertibility](000-Vision.md#frame-state-revertibility) for free is the same property that makes hydration trivial — one EDN payload, no separate machine-state channel.
- **Status:** `Pinned` — [`conformance/fixtures/cross-spec-ssr-hydrate-with-machines.edn`](conformance/fixtures/cross-spec-ssr-hydrate-with-machines.edn).

## Routing × SSR

### 6. Routing in SSR

- **Specs:** [012-Routing](012-Routing.md), [011-SSR](011-SSR.md).
- **Scenario:** A server-side render handles a request for `/users/42`; the route matches a registered route handler that produces the initial state.
- **Behaviour:** The route is bound from the request URL at frame creation; `(rf/sub-value [:rf/route])` returns the resolved route map (the sub-id `:rf/route` reads the slice at `[:rf.runtime/routing :current]` in runtime-db). The route handler runs to populate `app-db`. Navigation effects (`:rf.nav/push-url`, `:rf.nav/replace-url`) are registered with `:platforms #{:client}` and so are **no-ops on the server** — the generic platform-gate path in `do-fx` emits `:rf.fx/skipped-on-platform` (with `:fx-id` carrying the specific nav fx, `:platform :server`, `:registered-platforms #{:client}`); no nav-specific trace exists. The request frame is request-scoped and there is no browser to navigate. The hydration payload includes the resolved route slice at `[:rf.runtime/routing :current]`; the client mounts at the same route without re-resolving.
- **Reason:** Routing-as-state means the route is just a runtime-db slice. SSR populates it; the client hydrates it. Navigation effects are device-side concerns that don't survive to the server.
- **Status:** `Pinned` — [`conformance/fixtures/cross-spec-routing-in-ssr.edn`](conformance/fixtures/cross-spec-routing-in-ssr.edn).

### 7. Route-not-found under SSR

- **Specs:** [012-Routing §Route-not-found](012-Routing.md), [011-SSR §Server error projection](011-SSR.md).
- **Scenario:** A request URL matches no registered route on the server.
- **Behaviour:** The standard route-not-found path runs (per [012](012-Routing.md)), populating `app-db` with the `:rf.route/not-found` marker; the not-found route's `:on-match` events fire just like any other route. The runtime emits `:rf.error/no-such-handler` (the routing match-failure trace) and the default error projector ([009 §Error contract](009-Instrumentation.md#error-contract)) maps it to a locked `{:status 404 :code :not-found ...}` public-error, stamping `:status 404` onto the per-request response accumulator. The HTTP response status conveys the response semantics; the trace surface carries the structured error.
- **Reason:** The projector firing IS the wire-level signal that produces the 404 — bypassing it would mean every host re-implements the not-found-→-404 mapping. Routing match-failure surfaces as an error category so projector policy is a single seam: hosts that want a different not-found shape (custom JSON, signed URL, etc.) override the projector once instead of forking the routing layer.
- **Status:** `Pinned` — [`conformance/fixtures/cross-spec-route-not-found-ssr-status.edn`](conformance/fixtures/cross-spec-route-not-found-ssr-status.edn).

## Frames × Reactive Substrate

### 8. Frame disposal during render

- **Specs:** [002-Frames §Destroy](002-Frames.md#destroy), [006-ReactiveSubstrate §Adapter disposal lifecycle](006-ReactiveSubstrate.md#adapter-disposal-lifecycle).
- **Scenario:** `destroy-frame!` is called while the substrate adapter is mid-render (a React render pass for the CLJS reference, or equivalent in another host).
- **Behaviour:** The current render pass completes against the snapshot it began with — render is single-tick, observably atomic from the substrate's perspective. After the render commits, the next reactive update is the disposal: sub-cache disposes, the substrate releases the frame-scoped subtree (in CLJS-Reagent: unmount), the lifecycle listeners fire. No render mid-disposal observes a partial state.
- **Reason:** Run-to-completion at the render boundary. React's commit cycle (and equivalents) is uninterruptible; cooperating with that cycle keeps the contract simple.
- **Status:** `Provisional` — fixture pending: `frame-destroy-during-render.edn`.

### 9. Reactive substrate without React-context

- **Specs:** [006-ReactiveSubstrate §register-context-provider](006-ReactiveSubstrate.md#register-context-provider-frame-keyword--component), [002-Frames §View ergonomics](002-Frames.md#view-ergonomics-the-hard-part).
- **Scenario:** A host substrate (Solid, plain-atom on the JVM, or a hand-rolled minimal adapter) does not implement `register-context-provider`.
- **Behaviour:** The core falls back to **explicit-frame-as-argument**: views thread the frame keyword through their props, and `subscribe` / `dispatch` resolve the frame from the argument. The CLJS reference's React-context tier of `read-frame-from-context` is skipped; the dynamic-binding tier (`*current-frame*`) is the only remaining scope tier — there is **no `:rf/default` tier**: the resolution chain never synthesises a default; absence is `:rf.error/no-frame-context`. The runtime is intended to trace `:rf.warning/no-context-provider-once` on first use of a frame in this configuration, pointing at `with-frame` or explicit threading.
- **Reason:** Context is an *ergonomic optimisation* over explicit-frame addressing (per [002 §View ergonomics](002-Frames.md#view-ergonomics-the-hard-part)), not a pattern-level commitment. Hosts without a context concept fall back to the addressing mechanism that was always available.
- **Status:** `Provisional` — fixture pending: `headless-explicit-frame.edn`. The `:rf.warning/no-context-provider-once` op-type is **named here as design intent, not yet emitted** by the CLJS reference (which always installs the React-context tier and so never reaches this code path); it lights up when the first non-React substrate adapter lands. Hosts without a context concept must still implement the resolution-chain fallback regardless of whether the warning is wired.

### 10. Plain Reagent fn under a `frame-provider`

- **Specs:** [002-Frames §Reading the frame from React context](002-Frames.md#reading-the-frame-from-react-context-cljs-implementation-detail), [004-Views §Plain Reagent fns](004-Views.md#plain-reagent-fns-staged-adoption-the-footgun-is-now-a-loud-error), [006 §Frame-provider via React context](006-ReactiveSubstrate.md#frame-provider-via-react-context).
- **Scenario:** A plain Reagent component (not registered via `reg-view`, so without the `^{:contextType frame-context}` wiring) is rendered inside a `frame-provider` and calls `(rf/subscribe ...)`.
- **Behaviour:** The plain fn cannot read the React context (it lacks `contextType`); the resolution chain falls through to `*current-frame*` (unset) and bottoms out at **nil** — there is **no `:rf/default` floor** to land on: the resolver never synthesises a frame from absence. The public frame-scoped op turns that nil into a hard `:rf.error/no-frame-context` via `require-current-frame!` — the operation **fails fast and loudly** rather than silently routing to a conventional default and reading the wrong frame's app-db. The error rides the always-on error axis ([009 §What IS available in production](009-Instrumentation.md#what-is-available-in-production)), so it surfaces in production where dev traces are elided.
- **Reason:** A plain fn that cannot read its surrounding frame has no honest answer for which frame to act on; the call site fails loud rather than silently routing to a default and reading the wrong frame's app-db. The safe shapes are `reg-view`, an explicit `with-frame` scope, or a captured `(rf/capture-frame)` ([004 §Affordance for plain fns](004-Views.md#affordance-for-plain-fns-rfcapture-frame)). There is no fall-through to warn about — the footgun is a loud `:rf.error/no-frame-context` error ([004 §the footgun is now a loud error](004-Views.md#the-footgun-is-now-rferrorno-frame-context)).
- **Status:** `Provisional` — fixture pending: `plain-fn-no-frame-context.edn`.

## Machines × Errors

### 11. Machine action throws

- **Specs:** [005-StateMachines §Actions](005-StateMachines.md#actions), [009-Instrumentation §Error contract](009-Instrumentation.md#error-contract).
- **Scenario:** A machine action's fn throws an exception during a transition's action group.
- **Behaviour:** The action group's exception is caught by the machine handler; the in-flight cascade halts. The snapshot is **not committed** — the pre-action runtime-db slice at `[:rf.runtime/machines :snapshots <id>]` remains. `:rf.error/machine-action-exception` traces with `:tags` carrying `:machine-id`, `:action-id`, `:state-path`, `:transition`, `:event`, `:exception`, `:exception-message`, and `:reason`; the generic `:rf.error/handler-exception` does **not** also fire (the machine layer catches the throw before it can bubble out as a handler exception). Any `:fx` already accumulated from earlier slots in the same Level-2 cascade is **dropped** (the snapshot did not commit, so the dependent effects should not fire). The `:always` microstep does **not** fire on the failed cascade. The error fans out through the always-on [`register-listener!` (`:errors` stream)](009-Instrumentation.md#what-is-available-in-production) surface for off-box observability.
- **Reason:** All-or-nothing transitions match the FSM mental model. A half-applied transition with side effects but no snapshot change would be the worst kind of inconsistency.
- **Status:** `Pinned` — [`conformance/fixtures/cross-spec-machine-action-throws.edn`](conformance/fixtures/cross-spec-machine-action-throws.edn).

### 12. Effect handler throws inside a machine action's `:fx`

- **Specs:** [005-StateMachines §Action effect map](005-StateMachines.md#action-effect-map--data-fx), [002-Frames §`:fx` ordering §Error during `:fx`](002-Frames.md#fx-ordering-and-atomicity-guarantees), [009 §Error contract](009-Instrumentation.md#error-contract).
- **Scenario:** A machine action returns `{:fx [[:http ...] [:dispatch ...]]}`; the snapshot commits successfully; `do-fx` invokes `:http` and the fx handler throws.
- **Behaviour:** The snapshot commit already happened (per [005 §Drain semantics §Level 3 step 5](005-StateMachines.md#level-3--within-a-single-machine-event)) and is preserved. The `:fx` walk **continues** to subsequent entries (per [002 §Error during `:fx`](002-Frames.md#fx-ordering-and-atomicity-guarantees)) — `:dispatch` runs even though `:http` threw. Two trace events fire: `:rf.error/fx-handler-exception` for `:http`, and `:rf.machine/transition` for the successful machine transition.
- **Reason:** `:fx` ordering means *order*, not *dependency*. The action committed; downstream fx that genuinely depend on `:http` succeeding should be lifted to a `:dispatch` chain that observes `:http`'s result via cofx. Halting on first error would conflate the two concerns.
- **Status:** `Pinned` — [`conformance/fixtures/cross-spec-machine-fx-handler-throws.edn`](conformance/fixtures/cross-spec-machine-fx-handler-throws.edn).

### 13. Hot-reload of a machine action while instance is running

- **Specs:** [005-StateMachines §Actions](005-StateMachines.md#actions), [001-Registration §Hot-reload semantics](001-Registration.md#hot-reload-semantics).
- **Scenario:** A machine's action body — `:auth/login-attempt` in the machine's `:actions` map — is edited and the namespace is re-evaluated (figwheel save) while a machine instance is mid-transition with that action mid-flight. Two sub-cases differ in where the action body lives:
  1. **Action body defined inline in the machine spec's `:actions` map.** The save re-runs `reg-machine`, which replaces the machine's `:event` slot atomically. Active instances continue running with the spec they captured at spawn time; the new body applies to **future spawns** only.
  2. **Action body defined as a Clojure var referenced from the machine's `:actions` map.** The save re-`def`s the var. Every call site (active instances included) resolves the var on its next microstep and picks up the new body.
- **Behaviour:** In both sub-cases, any in-flight action invocation completes against the resolved (old) fn — guarantee 1 of the hot-reload contract. Sub-case 1's instances finish their lifecycle against the captured spec; sub-case 2's instances see the new body on the next microstep through ordinary var resolution. Active instances are *not* re-spawned in either case.
- **Reason:** There is **no** `:machine-action` registry kind (per [001 §Registry model](001-Registration.md#registry-model--the-canonical-kind-keyword-set) and [005 §Globally-registered guards/actions vs machine-scoped (RESOLVED)](005-StateMachines.md#globally-registered-guardsactions-vs-machine-scoped-resolved)) — machine guards and actions are machine-scoped declarations, not registry entries. The two paths (inline body vs Clojure-var ref) give the developer the choice of "respawn-required" vs "live-pickup" semantics without the framework needing a separate registry for action bodies.
- **Status:** `Provisional` — fixture pending: `hot-reload-machine-action.edn`.

## Drain loop × Substrate

### 14. Re-entrant dispatch from inside a render

- **Specs:** [002-Frames §Run-to-completion §Render boundaries](002-Frames.md#render-boundaries), [006-ReactiveSubstrate §Subscription cache](006-ReactiveSubstrate.md#subscription-cache--contract-and-operational-semantics).
- **Scenario:** A view's render fn calls `(rf/dispatch [:something])` (perhaps inside a `:ref` callback that fires synchronously in render).
- **Behaviour:** The dispatched event lands on the router queue and is processed in the **next** drain cycle, after the render commits. The current drain (which produced the `app-db` value the render is reading) has already settled — run-to-completion. `dispatch-sync` from inside any handler raises `:rf.error/dispatch-sync-in-handler` (per [002 §dispatch-sync](002-Frames.md#dispatch-sync)).
- **Reason:** Re-entrant synchronous dispatch from render would cause render to observe a state that exists only mid-run — exactly the partial-state view run-to-completion was designed to prevent.
- **Status:** `Pinned` — [`conformance/fixtures/cross-spec-dispatch-sync-in-handler.edn`](conformance/fixtures/cross-spec-dispatch-sync-in-handler.edn).

## Machines × Tooling

### 15. Re-spawning a machine instance via Tool-Pair

- **Specs:** [005-StateMachines §Spawning](005-StateMachines.md#spawning--dynamic-actors), [Tool-Pair §Time-travel](Tool-Pair.md).
- **Scenario:** A pair-tool's "rewind to epoch N" reverts the frame's value via `replace-container!`; the prior runtime-db had a machine snapshot at `[:rf.runtime/machines :snapshots :auth.session/abc]` that no longer exists.
- **Behaviour:** The revert lands the frame-state (both partitions) back to its prior value, including the runtime-db machine snapshot. The machine's *handler* is still in the registrar (handlers don't revert with state). The next event dispatched to the machine resolves the handler, reads the (now-restored) snapshot, and processes normally. Pending `:after` timers from before the rewind have either fired (against now-stale epochs, no-ops) or been GC'd.
- **Reason:** Goal 3 (revertibility) plus Conventions' reserved `[:rf.runtime/machines]` runtime-db subtree plus epoch-based `:after` staleness give time-travel for free for the machine substrate. No special revert path.
- **Status:** `Provisional` — fixture pending: `time-travel-revert.edn`.

## Errors × SSR

### 16. Error projection on the server

- **Specs:** [009-Instrumentation §Server error projection](009-Instrumentation.md), [011-SSR](011-SSR.md).
- **Scenario:** A handler on the server throws during request processing.
- **Behaviour:** The exception is caught by the drain loop; `:rf.error/handler-exception` traces; the registered server error projector (`reg-error-projector`, per [011-SSR](011-SSR.md)) consumes the error and returns a sanitised error shape suitable for the public response (no stack traces, no PII). The HTTP response is built from the projected error per the request-frame's response-status fx.
- **Reason:** Server errors must not leak internal state to the public boundary; the projector is the named sanitisation seam.
- **Status:** `Pinned` — [`conformance/fixtures/cross-spec-server-error-projection.edn`](conformance/fixtures/cross-spec-server-error-projection.edn).

### 17. Machine error inside SSR

- **Specs:** [005 §Actions](005-StateMachines.md#actions), [011-SSR §Server error projection](011-SSR.md), [009-Instrumentation §Error contract](009-Instrumentation.md#error-contract).
- **Scenario:** A machine running on the server (request-scoped frame, SSR drain) has an action that throws during a transition's action group.
- **Behaviour:** The behaviour composes Interaction 11 (machine all-or-nothing) with Interaction 16 (server error projection) — but the composition is non-trivial enough to be worth pinning end-to-end:
  1. **The action throw is caught by the machine handler** (per Interaction 11). The in-flight cascade halts; the machine snapshot at `[:rf.runtime/machines :snapshots <id>]` (in runtime-db) is NOT committed — the pre-action snapshot remains. Any `:fx` accumulated from earlier slots in the same Level-2 cascade is dropped (the snapshot did not commit, so dependent effects MUST NOT fire). The `:always` microstep does NOT fire on the failed cascade.
  2. **`:rf.error/machine-action-exception` traces** with `:tags` carrying `:machine-id`, `:action-id`, `:state-path`, `:transition`, `:event`, `:exception`, `:exception-message`, `:reason` (per Interaction 11). The generic `:rf.error/handler-exception` does NOT also fire — the machine layer catches the throw before it bubbles out as a handler exception.
  3. **The registered server error projector runs** (per Interaction 16). The projector (`reg-error-projector`) consumes the `:rf.error/machine-action-exception` event and returns a sanitised `:rf/public-error` shape suitable for the public response — no stack traces, no PII, locked structure per [011 §Server error projection](011-SSR.md). The default projector ([009 §Error contract](009-Instrumentation.md#error-contract)) supplies the projection if the user did not register one.
  4. **The HTTP response is built from the projected error** via the request-frame's response-status fx accumulator (a framework-private side-channel atom keyed by frame-id, read via `get-response`) per [011-SSR](011-SSR.md). The wire-level status conveys the error semantics (typically `5xx` for unexpected throw, `4xx` for projector-mapped domain failures); the trace surface carries the structured error.
  5. **The request-scoped frame is destroyed** at the end of the request per [002 §Destroy](002-Frames.md#destroy). `:after` timers need no special cleanup — per Interaction 4 (Machines under SSR) they are no-op'd on the server, so no scheduled work outlives the request. Active machine instances run their `:exit` cascades per [Cross-Spec-Interactions §1](#1-frame-disposal-with-active-machine-instances) — including the throwing machine, which is still alive (the snapshot was not committed by the failed action, but the instance handler is registered and its current state is the pre-action state, so its `:exit` runs against the live container as usual).
- **Reason:** Composing Interactions 11, 16, and 1 keeps every per-spec contract intact at the boundary — machine all-or-nothing AND server error projection AND request-frame teardown each apply unchanged. Pinning the composition end-to-end here means an SSR implementor doesn't have to re-derive the order of the five steps; the trace and HTTP-response shape are observable contract.
- **Status:** `Pinned` — [`conformance/fixtures/cross-spec-ssr-machine-error.edn`](conformance/fixtures/cross-spec-ssr-machine-error.edn).

## Subscriptions × Hot-reload

### 18. Re-registering a sub mid-cascade

- **Specs:** [001-Registration §Hot-reload semantics](001-Registration.md#hot-reload-semantics), [006-ReactiveSubstrate §Subscription cache](006-ReactiveSubstrate.md#subscription-cache--contract-and-operational-semantics), [002-Frames §Run-to-completion](002-Frames.md#run-to-completion-dispatch-drain-semantics).
- **Scenario:** A figwheel / shadow-cljs hot-reload save delivers a `reg-sub` re-registration via the host's async event handler **while a drain cycle is in flight** — typically a render-triggered sub deref or a derived-sub recomputation is on the stack when the new ns-load reaches `register-handler`.
- **Behaviour:** The composition of the hot-reload contract and the drain's single-thread invariant pins the following per-step ordering:
  1. **The re-registration is queued, not applied synchronously.** Hot-reload delivery rides the host's async event queue (the figwheel WebSocket message → `js/setTimeout 0` callback). The drain itself is synchronous (per [002 §Single-drainer invariant](002-Frames.md#single-drainer-invariant-concurrent-hosts)), so the re-registration cannot interleave inside a microstep — it lands between events at the earliest, between dispatch-queue cycles at the earliest non-pathological case.
  2. **On apply, the sub's cache slot is disposed eagerly.** The cache entry at the sub's id is dropped; downstream cache slots that depended on it are invalidated through the existing sub-cache invalidation path (per [006 §Invalidation algorithm](006-ReactiveSubstrate.md#invalidation-algorithm)). Listeners attached to those slots are notified through ordinary reactive-substrate fan-out.
  3. **Values already computed and bound** to in-flight event-handler effect maps remain bound — they are ordinary CLJS values taken out of the cache before the disposal. The handler completes with whatever it captured; the runtime does NOT retroactively recompute the in-flight handler's `:db` or `:fx` returns.
  4. **The next subscribe builds against the new sub body.** Any subsequent `(rf/subscribe ...)` (or the substrate's deref of an existing reactive that re-runs because of step 2's invalidation) resolves the new registration. The hot-reload `:rf.registry/handler-replaced` trace fires per [001 §Hot-reload trace surface](001-Registration.md#hot-reload-trace-surface).
  5. **Render-tick after the apply.** The substrate's batched-render pass picks up step 2's invalidations on the next reactive update; the view re-renders against the new sub body's output. No render observes a half-state where some derived subs ran against the old body and others against the new — the invalidation is atomic from the substrate's perspective per [006 §Subscription cache invalidation](006-ReactiveSubstrate.md#subscription-cache--contract-and-operational-semantics).
- **Reason:** Disposing the cache slot eagerly is correct — values already taken out of the cache (e.g., into a closure or an effect-map field) are caller-managed and don't need retroactive update. Hot-reload is non-destructive to in-flight work (run-to-completion preserved), but the cache itself is allowed to update mid-cycle because it's dev-time-only state and the substrate batches the next render. A different design (defer re-registration until drain settles) would block hot-reload arbitrarily long under a long drain; a different design (mutate cache during a microstep) would violate the single-drainer invariant. The async-queue interleave is the only correct seam.
- **Status:** `Pinned` — [`conformance/fixtures/cross-spec-hot-reload-sub-mid-cascade.edn`](conformance/fixtures/cross-spec-hot-reload-sub-mid-cascade.edn).

## Stories × Testing

### 19. Story decorators that override fx

- **Specs:** [007-Stories](007-Stories.md), [008-Testing](008-Testing.md), [002-Frames §Per-frame and per-call overrides](002-Frames.md#per-frame-and-per-call-overrides).
- **Scenario:** A story registers a frame with `:fx-overrides {:http :http.canned-200}` and a portable-stories-as-test runs the same story under the test framework.
- **Behaviour:** The id-valued override resolves identically in both contexts: `:http.canned-200` is a registered fx, the canned fx runs in place of the real `:http`. No function-valued lambda is needed; the override is portable across the wire (per [002 §Per-frame and per-call overrides §pattern-level vs CLJS reference](002-Frames.md#per-frame-and-per-call-overrides)).
- **Reason:** Pattern-level overrides are id-valued precisely so they survive the story → test transition. Function-valued overrides are CLJS-only ergonomic sugar.
- **Status:** `Pinned` — [`conformance/fixtures/cross-spec-portable-story-fx-override.edn`](conformance/fixtures/cross-spec-portable-story-fx-override.edn).

## Boot × Substrate

### 20. Adapter swap mid-process is forbidden

- **Specs:** [006-ReactiveSubstrate §Single adapter per process](006-ReactiveSubstrate.md#single-adapter-per-process).
- **Scenario:** A program calls `(rf/install-adapter! ...)` a second time without an intervening `(rf/destroy-adapter!)`.
- **Behaviour:** The second call raises `:rf.error/adapter-already-installed` and does not change the installed adapter. To swap, destroy first, then install.
- **Reason:** Mid-process adapter swap would leave an unknown set of cached reactions, mounted views, and frame containers wired to the old adapter — the inconsistency is unrecoverable. The dispose-then-install path forces a known clean state.
- **Status:** `Provisional` — fixture pending: `adapter-already-installed.edn`.

## Registration family

### 21. Family asymmetry — only `reg-view` and `reg-interceptor` have a macro tier

- **Specs:** [001-Registration](001-Registration.md) (the registration family); [Spec 004 §reg-view](004-Views.md#reg-view-is-the-multi-frame-contract); [Conventions §`*`-suffix naming](Conventions.md#-suffix-naming-for-fn-versions-of-macros).
- **Scenario:** A reader looks at the public API and notices that `reg-view` ships as a **macro** with a `reg-view*` plain-fn partner — and so does `reg-interceptor` (`reg-interceptor*` partner) — while every other `reg-*` (`reg-event`, `reg-sub`, `reg-fx`, `reg-cofx`, `reg-frame`, `reg-flow`, `reg-route`, `reg-app-schema`, `reg-machine`, `reg-error-projector`) is a plain fn with no `*` partner. Why is the family asymmetric?
- **Behaviour:** Exactly two registrars carry a macro tier, each for a distinct compile-time reason. `reg-view` needs a Var binding — Reagent calls views by symbol from hiccup heads (`[counter "label"]`) — so its macro defs the symbol, registers the view, and auto-injects `dispatch` / `subscribe` lexically into the body. `reg-interceptor` needs **source-coord capture**: its macro records the authoring call site so registered interceptors are addressable in traces and tooling, and the `reg-interceptor*` plain fn is the HoF / programmatic partner for callers that build interceptors dynamically. None of the other registrations need either: they are dispatched (events) or looked up by id (subs, fx, cofx, frames, routes, schemas, machines) at runtime, with no auto-defed Var, no compile-time auto-id derivation, and no captured source coord — so they stay plain fns.
- **The `*` convention applies only where a macro sweetens an underlying fn.** Adding `reg-event*` / `reg-sub*` / etc. would be pure aliases for `reg-event` / `reg-sub` themselves — those registrars have no macro tier, so no fn partner is necessary. The convention is reserved for the sweetened-vs-unsweetened case (the two macro-backed registrars above), per `let` / `let*`.
- **Render trees use Vars; runtime lookups use ids.** Keyword vectors at render time are HTML elements, never views. The runtime does not intercept the keyword case — Reagent's hiccup semantics are preserved unmodified. Render-tree calls go through Var-references (`[my-view args]`); registry lookups go through `(rf/view id)`. See [Spec 004 §Calling a registered view](004-Views.md#calling-a-registered-view).
- **Reason:** The family looks asymmetric because the underlying need is asymmetric. The macro tier exists only where a compile-time concern is part of the contract — a Var binding for `reg-view` (Reagent's idiomatic hiccup head), authoring-site source-coord capture for `reg-interceptor` — and the plain fn tier is sufficient everywhere the registry id alone carries the surface. The asymmetry is real but bounded: two macro-backed registrars, not one, and not the whole family.
- **Status:** `Locked` — [Spec 004](004-Views.md) owns the `reg-view` macro shape and [001 §Interceptors](001-Registration.md#interceptors--reg-interceptor-the-interceptor-registrar) owns the `reg-interceptor` macro shape; this entry documents the family-level asymmetry so implementors and readers don't assume a `*` partner exists for every registration (only the two macro-backed registrars carry one).

## Routing × Machines

### 22. Route change with route-scoped machines — no frame destroy, `:exit` on the exiting machine

- **Specs:** [012-Routing §Navigation blocking — pending-nav protocol](012-Routing.md#navigation-blocking--pending-nav-protocol) / [§Frame-destroy teardown](012-Routing.md#frame-destroy-teardown), [005-StateMachines §Cooperative cancellation](005-StateMachines.md), [002-Frames §Durable vs transient](002-Frames.md).
- **Scenario:** A URL-bound frame is on `/editor`, whose view spawned a machine (or hosts a route-owned machine). The user navigates to `/article/42`. What tears down the editor's machine-shaped resources?
- **Behaviour:** **Navigating does NOT destroy the frame** — the frame survives every route change by design (a frame is torn down only by an explicit `destroy-frame!`; the route slice at `[:rf.runtime/routing :current]` is *rewritten*, not the frame). So the machine-frame-destroy cascade ([Interaction 1](#1-frame-disposal-with-active-machine-instances)) does **not** fire on a route change. Instead, route-scoped machine teardown rides the machine's own lifecycle: a machine `:spawn`-ed inside a state runs its `:exit` cascade when the parent state exits (per [005 §Cooperative cancellation](005-StateMachines.md)), and a view-mounted machine is destroyed when *its view unmounts* (the `:parent-unmount-cascade` reason on `:rf.machine.lifecycle/destroyed`) — which a route change *does* cause when the router swaps the editor view out. The route slice rewrite and the view swap are what release the resources, not a frame teardown. Route-owned `:resources` release their owner leases automatically on route change (the leaving route's `[:route prev-id prev-nav-token]` owner is dropped — [016 §Route integration](016-Resources.md)). A route's `:can-leave` guard can *gate* the transition (unsaved-form confirm) before any of this, but it does not itself run teardown.
- **Reason:** Routing-as-state means the route is a runtime-db slice, orthogonal to frame lifetime — coupling route change to frame destruction would throw away the whole app world on every navigation. Frames survive unmount ([002 §Durable vs transient](002-Frames.md), [docs frames concept](../docs/core/concepts/frames.md)); route-scoped teardown is the machine `:exit` / view-unmount cascade's job (triggers 1–4 and view-unmount in [005 §Cooperative cancellation](005-StateMachines.md)), which is exactly why an app does *not* wire abort calls into route-leave handlers. This corrects the mistaken "navigating away destroys the frame" reading: the *screen* is cleaned up (view unmount → machine `:exit`), the *frame* is not.
- **Status:** `Provisional` — the two owning contracts (frame survives navigation; machine `:exit` on state exit / view unmount) are each pinned in their specs; this entry pins their composition at the routing boundary. Fixture pending: `cross-spec-route-change-machine-exit.edn`.

## Cross-references

- [000-Vision §Goals](000-Vision.md#goals) — the goals these interactions exist to satisfy.
- [Runtime-Architecture](Runtime-Architecture.md) — where the components meet at the level of architecture; this doc is the per-edge-case detail.
- Each numbered Spec — owns the surface its interactions cite; this doc never overrides.
- [conformance/](conformance/README.md) — fixtures for the interactions above (existing and future).

## When to update this document

Add an interaction entry when:

1. An implementation of the CLJS reference (or another host) hits a question that no individual Spec answers cleanly.
2. A bug-fix or design-decision conversation establishes a new interaction rule that will be needed again later.
3. A conformance fixture is added that pins a cross-Spec behaviour — the fixture is the test, the entry here is the documentation.

Do **not** add an entry when:

- The behaviour is fully described in one Spec; cite the Spec inline at the call site instead.
- The interaction is purely host-specific (CLJS reference detail with no pattern-level implication) — those go in the cited Spec's CLJS-reference section.
- The interaction is theoretical without a real use-case; this doc is for things implementors actually encounter.
