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

**Current state of the corpus (2026-05-09).** First wave of cross-Spec fixtures landed under bead rf2-bhhu — interactions 5, 6, 7, and 19 are now `Pinned`. The second wave (rf2-msd4) closed the conformance runner's `reg-machine` / `:throw` op gap and pinned interactions 11, 12, and 17. One entry (interaction 21) is `Locked` — a documentation-only family-level rule, owned by Spec 004, not fixture-trackable. The remaining 13 stay `Provisional`; their fixture filenames remain *targets for future authoring* until the corresponding runner / runtime gaps close (rendering capability per [rf2-j9yf](#); adapter-lifecycle hooks; tool-pair time-travel; hot-reload-mid-cascade hooks; etc.). Pinning these is tracked under follow-up beads filed by rf2-bhhu. When a fixture lands, the entry's status flips to **`Pinned`** and the filename becomes a live link.

## Frames × Machines

### 1. Frame disposal with active machine instances

- **Specs:** [002-Frames §Destroy](002-Frames.md#destroy), [005-StateMachines §Hierarchical compound states §Entry/exit cascading](005-StateMachines.md#entryexit-cascading-along-the-lca).
- **Scenario:** `(rf/destroy-frame :auth)` is called while the frame holds active machine instances mid-flight.
- **Behaviour:** Each active machine runs its `:exit` cascade from leaf to root in **reverse-creation order** (the most recently spawned instance disposes first). Pending `:after` timers are cancelled — staleness via the epoch idiom (per [005 §Epoch-based stale detection](005-StateMachines.md#epoch-based-stale-detection)) means timers that fire after destroy land against an unmatching epoch and no-op. Outbound `:fx` from those `:exit` actions runs through `do-fx`. After every machine has settled, the sub-cache disposes (per [006 §Subscription cache — Lifetime contract](006-ReactiveSubstrate.md#lifetime-contract--frame-disposal)). The substrate adapter releases frame-scoped resources. `:rf.frame/destroyed` traces; `:rf.machine/disposed` traces fire per instance.
- **Reason:** Run-to-completion (per [002 §Run-to-completion](002-Frames.md#run-to-completion-dispatch-drain-semantics)) extends to disposal — letting `:exit` cascades complete preserves the invariant that every state has its symmetric exit. Reverse-creation order matches the actor-disposal convention.
- **Status:** `Provisional` — fixture pending: `frame-destroy-with-machines.edn`.

### 2. Sub-cache hit inside a machine microstep

- **Specs:** [005-StateMachines §Drain semantics §Level 3](005-StateMachines.md#level-3--within-a-single-machine-event), [006-ReactiveSubstrate §Subscription cache](006-ReactiveSubstrate.md#subscription-cache--contract-and-operational-semantics).
- **Scenario:** A machine action's body reads a subscription via `(rf/subscribe-value [...])` to make a routing decision.
- **Behaviour:** Cache lookup succeeds and returns the value computed against the most recently committed `app-db` (which is the `app-db` *before* the current Level-3 cascade started, since the machine commits one snapshot at the end). Subs do not see the in-flight `:data` of the current cascade. Sub-cache invalidation fires once after the cascade's final commit, not after each microstep.
- **Reason:** External observers see one macrostep per machine event (per [005 §Drain semantics §Level 3](005-StateMachines.md#level-3--within-a-single-machine-event)). Subs are external observers. Letting subs observe in-flight data would expose the partial-snapshot view the macrostep contract specifically avoids.
- **Status:** `Provisional` — fixture pending: `machine-microstep-subscribe.edn`.

### 3. Machine spawn at boot before substrate adapter ready

- **Specs:** [005-StateMachines §Spawning](005-StateMachines.md#spawning--dynamic-actors), [006-ReactiveSubstrate §Adapter selection](006-ReactiveSubstrate.md#adapter-selection-at-boot).
- **Scenario:** A `(rf/reg-frame :app {:on-create [:boot]})` fires `:boot` which spawns a machine — but boot order means the substrate adapter has not been installed yet.
- **Behaviour:** `:on-create` events are queued on the frame's router but the drain does not start until the adapter is installed. Once `(rf/install-adapter! ...)` completes, the queue drains. Spawned machines therefore always run against an installed adapter.
- **Reason:** A machine action that calls `(rf/subscribe-value ...)` must reach a working sub-cache, which requires the adapter. Deferring drain until adapter-ready is the simplest invariant.
- **Status:** `Provisional` — fixture pending: `boot-order-adapter-ready.edn`.

## Machines × SSR

### 4. Machines under SSR (allowed-subset)

- **Specs:** [005-StateMachines §SSR mode](005-StateMachines.md#ssr-mode), [011-SSR §`:after` is no-op under SSR](011-SSR.md#after-is-no-op-under-ssr).
- **Scenario:** A request-scoped frame on the server hosts machines that drive the SSR boot sequence (auth probe, profile fetch, route resolution).
- **Behaviour:** Machines run normally on the server with one carve-out: `:after` is a **no-op** under `:ssr-server`. The entry action skips timer scheduling; the synthetic timer-elapsed event is never queued; the request frame is destroyed before any timer could fire anyway. `:always` microsteps run normally; `:invoke` runs normally provided the invoked work is synchronous co-effects. `:invoke` of a long-running async machine that depends on a real timer to settle is a programmer error and traces `:rf.error/ssr-async-invoke-without-deadline`.
- **Reason:** Server-side `setTimeout` either leaks (timer outlives the request) or is artificial (the SSR render has no time to wait). The carve-out is the only one machines need; everything else is host-agnostic and runs identically on both platforms.
- **Status:** `Provisional` — fixture pending: `after-no-op-under-ssr.edn`.

### 5. Hydration with machine snapshots

- **Specs:** [005-StateMachines §Where snapshots live](005-StateMachines.md#where-snapshots-live), [011-SSR §Hydration payload](011-SSR.md).
- **Scenario:** The server renders a page that ran machines to completion of their SSR-eligible drain (see Interaction 4); the client hydrates and continues from the server's settled state.
- **Behaviour:** Machine snapshots live at `[:rf/machines <id>]` inside `app-db` per [Conventions §Reserved app-db keys](Conventions.md#reserved-app-db-keys). The hydration payload is `app-db` itself; machines deserialise as data. After hydration, the client mounts the same machine handlers (registered identically); subsequent dispatches resolve to the (now client-side) handler. `:after` timers that the server skipped now schedule on the client per the entry action's normal behaviour.
- **Reason:** Machine state inheriting [Goal 3 — Frame state revertibility](000-Vision.md#frame-state-revertibility) for free is the same property that makes hydration trivial — one EDN payload, no separate machine-state channel.
- **Status:** `Pinned` — [`conformance/fixtures/cross-spec-ssr-hydrate-with-machines.edn`](conformance/fixtures/cross-spec-ssr-hydrate-with-machines.edn).

## Routing × SSR

### 6. Routing in SSR

- **Specs:** [012-Routing](012-Routing.md), [011-SSR](011-SSR.md).
- **Scenario:** A server-side render handles a request for `/users/42`; the route matches a registered route handler that produces the initial state.
- **Behaviour:** The route is bound from the request URL at frame creation; `(rf/sub-value [:rf/route])` returns the resolved route map. The route handler runs to populate `app-db`. Navigation effects (`:rf.nav/push-url`, `:rf.nav/replace-url`) are registered with `:platforms #{:client}` and so are **no-ops on the server** — the generic platform-gate path in `do-fx` emits `:rf.fx/skipped-on-platform` (with `:fx-id` carrying the specific nav fx, `:platform :server`, `:registered-platforms #{:client}`); no nav-specific trace exists. The request frame is request-scoped and there is no browser to navigate. The hydration payload includes the resolved `:rf/route` slice; the client mounts at the same route without re-resolving.
- **Reason:** Routing-as-state means the route is just an `app-db` slice. SSR populates it; the client hydrates it. Navigation effects are device-side concerns that don't survive to the server.
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
- **Scenario:** `destroy-frame` is called while the substrate adapter is mid-render (a React render pass for the CLJS reference, or equivalent in another host).
- **Behaviour:** The current render pass completes against the snapshot it began with — render is single-tick, observably atomic from the substrate's perspective. After the render commits, the next reactive update is the disposal: sub-cache disposes, the substrate releases the frame-scoped subtree (in CLJS-Reagent: unmount), the lifecycle listeners fire. No render mid-disposal observes a partial state.
- **Reason:** Run-to-completion at the render boundary. React's commit cycle (and equivalents) is uninterruptible; cooperating with that cycle keeps the contract simple.
- **Status:** `Provisional` — fixture pending: `frame-destroy-during-render.edn`.

### 9. Reactive substrate without React-context

- **Specs:** [006-ReactiveSubstrate §register-context-provider](006-ReactiveSubstrate.md#register-context-provider-frame-keyword--component), [002-Frames §View ergonomics](002-Frames.md#view-ergonomics-the-hard-part).
- **Scenario:** A host substrate (Solid, plain-atom on the JVM, or a hand-rolled minimal adapter) does not implement `register-context-provider`.
- **Behaviour:** The core falls back to **explicit-frame-as-argument**: views thread the frame keyword through their props, and `subscribe` / `dispatch` resolve the frame from the argument. The CLJS reference's React-context tier of `read-frame-from-context` is skipped; the dynamic-binding tier (`*current-frame*`) and the default tier (`:rf/default`) are the resolution chain. `:rf.warning/no-context-provider-once` traces on first use of a non-default frame in this configuration, pointing at `with-frame` or explicit threading.
- **Reason:** Context is an *ergonomic optimisation* over explicit-frame addressing (per [002 §View ergonomics](002-Frames.md#view-ergonomics-the-hard-part)), not a pattern-level commitment. Hosts without a context concept fall back to the addressing mechanism that was always available.
- **Status:** `Provisional` — fixture pending: `headless-explicit-frame.edn`.

### 10. Plain Reagent fn under a non-default frame

- **Specs:** [002-Frames §Reading the frame from React context](002-Frames.md#reading-the-frame-from-react-context-cljs-implementation-detail), [006 §Frame-provider via React context](006-ReactiveSubstrate.md#frame-provider-via-react-context).
- **Scenario:** A plain Reagent component (not registered via `reg-view`, so without the `^{:context-type frame-context}` metadata) is rendered inside a non-default `frame-provider` and calls `(rf/subscribe ...)`.
- **Behaviour:** The plain fn cannot read the React context (it lacks `contextType`); the resolution chain falls through to `*current-frame*` (unset) and lands on `:rf/default`. The subscription targets the wrong frame. The runtime emits `:rf.warning/plain-fn-under-non-default-frame-once` (once per `(fn, enclosing-frame)` pair, not per render) per [Conventions §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned), pointing the user at `reg-view` or `with-frame`.
- **Reason:** Plain Reagent fns under default frames are a common, working pattern (the warning would be noise). Plain fns under non-default frames almost always indicate a hidden bug; the once-per-pair warning surfaces the issue without spamming.
- **Status:** `Provisional` — fixture pending: `plain-fn-non-default-frame-warning.edn`.

## Machines × Errors

### 11. Machine action throws

- **Specs:** [005-StateMachines §Actions](005-StateMachines.md#actions), [009-Instrumentation §Error contract](009-Instrumentation.md#error-contract).
- **Scenario:** A machine action's fn throws an exception during a transition's action group.
- **Behaviour:** The action group's exception is caught by the machine handler; the in-flight cascade halts. The snapshot is **not committed** — the pre-action `app-db` slice at `[:rf/machines <id>]` remains. `:rf.error/machine-action-exception` traces with `:tags` carrying `:machine-id`, `:action-id`, `:state-path`, `:transition`, `:event`, `:exception`, `:exception-message`, and `:reason`; the generic `:rf.error/handler-exception` does **not** also fire (the machine layer catches the throw before it can bubble out as a handler exception). Any `:fx` already accumulated from earlier slots in the same Level-2 cascade is **dropped** (the snapshot did not commit, so the dependent effects should not fire). The `:always` microstep does **not** fire on the failed cascade. The frame's `:on-error` projector (per [009 §Error-handler policy](009-Instrumentation.md#error-handler-policy-on-error-per-frame)) runs the user-defined projector if registered.
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
- **Scenario:** A globally-registered `:machine-action :auth/login-attempt` is re-registered (figwheel save) while a machine instance is mid-transition with that action mid-flight.
- **Behaviour:** The in-flight action invocation completes against the resolved (old) action fn. The next microstep — including any `:always` microstep that follows in the same Level-3 cascade — resolves the new action fn. Active instances are *not* re-spawned; the machine definition itself is unchanged.
- **Reason:** Run-to-completion of in-flight work is the hot-reload contract guarantee 1 (per [001 §The hot-reload contract](001-Registration.md#the-hot-reload-contract)). Picking up new actions on next microstep keeps the dev-loop tight without disrupting active flows.
- **Status:** `Provisional` — fixture pending: `hot-reload-machine-action.edn`.

## Drain loop × Substrate

### 14. Re-entrant dispatch from inside a render

- **Specs:** [002-Frames §Run-to-completion §Render boundaries](002-Frames.md#render-boundaries), [006-ReactiveSubstrate §Subscription cache](006-ReactiveSubstrate.md#subscription-cache--contract-and-operational-semantics).
- **Scenario:** A view's render fn calls `(rf/dispatch [:something])` (perhaps inside a `:ref` callback that fires synchronously in render).
- **Behaviour:** The dispatched event lands on the router queue and is processed in the **next** drain cycle, after the render commits. The current drain (which produced the `app-db` value the render is reading) has already settled — run-to-completion. `dispatch-sync` from inside any handler raises `:rf.error/dispatch-sync-in-handler` (per [002 §dispatch-sync](002-Frames.md#dispatch-sync)).
- **Reason:** Re-entrant synchronous dispatch from render would cause render to observe a state that exists only mid-cascade — exactly the partial-state view run-to-completion was designed to prevent.
- **Status:** `Provisional` — fixture pending: `dispatch-from-render.edn`.

## Machines × Tooling

### 15. Re-spawning a machine instance via Tool-Pair

- **Specs:** [005-StateMachines §Spawning](005-StateMachines.md#spawning--dynamic-actors), [Tool-Pair §Time-travel](Tool-Pair.md).
- **Scenario:** A pair-tool's "rewind to epoch N" reverts the frame's value via `replace-container!`; the prior `app-db` had a machine snapshot at `[:rf/machines :auth.session/abc]` that no longer exists.
- **Behaviour:** The revert lands `app-db` back to its prior value, including the machine snapshot. The machine's *handler* is still in the registrar (handlers don't revert with state). The next event dispatched to the machine resolves the handler, reads the (now-restored) snapshot, and processes normally. Pending `:after` timers from before the rewind have either fired (against now-stale epochs, no-ops) or been GC'd.
- **Reason:** Goal 3 (revertibility) plus Conventions' `:rf/machines` reserved key plus epoch-based `:after` staleness give time-travel for free for the machine substrate. No special revert path.
- **Status:** `Provisional` — fixture pending: `time-travel-revert.edn`.

## Errors × SSR

### 16. Error projection on the server

- **Specs:** [009-Instrumentation §Server error projection](009-Instrumentation.md), [011-SSR](011-SSR.md).
- **Scenario:** A handler on the server throws during request processing.
- **Behaviour:** The exception is caught by the drain loop; `:rf.error/handler-exception` traces; the user's per-frame `:on-error` projector fires if registered. The projector returns a sanitised error shape suitable for the public response (no stack traces, no PII). The HTTP response is built from the projected error per the request-frame's response-status fx.
- **Reason:** Server errors must not leak internal state to the public boundary; the projector is the named sanitisation seam.
- **Status:** `Provisional` — fixture pending: `server-error-projection.edn`.

### 17. Machine error inside SSR

- **Specs:** [005 §Actions](005-StateMachines.md#actions), [011-SSR](011-SSR.md).
- **Scenario:** A machine running on the server has an action that throws.
- **Behaviour:** Per Interaction 11, the machine snapshot does not commit; the error projector runs (Interaction 16). The HTTP response is the projected-error response. The request-scoped frame is destroyed at the end of the request as usual; pending `:after` timers (none, per Interaction 4) need no special cleanup.
- **Reason:** Compose: Interaction 11 (machine all-or-nothing) plus Interaction 16 (server error projection). No new behaviour at the boundary.
- **Status:** `Pinned` — [`conformance/fixtures/cross-spec-ssr-machine-error.edn`](conformance/fixtures/cross-spec-ssr-machine-error.edn).

## Subscriptions × Hot-reload

### 18. Re-registering a sub mid-cascade

- **Specs:** [001-Registration §Hot-reload semantics](001-Registration.md#hot-reload-semantics), [006-ReactiveSubstrate §Subscription cache](006-ReactiveSubstrate.md#subscription-cache--contract-and-operational-semantics).
- **Scenario:** A figwheel save delivers a sub re-registration via host async event handler while a drain cycle is in flight.
- **Behaviour:** The cache slot for that sub is disposed when the re-registration arrives. Already-computed values bound to the in-flight event's effect map remain bound (the values are now-disconnected from the cache). The next dequeue, and any subsequent subscribe, builds against the new sub body.
- **Reason:** Disposing the cache slot eagerly is correct; values already taken out of the cache (e.g., into a closure) are caller-managed. Hot-reload is non-destructive to in-flight work, but the cache itself is allowed to update mid-cycle because it's dev-time only.
- **Status:** `Provisional` — fixture pending: `hot-reload-sub-mid-cascade.edn`.

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
- **Scenario:** A program calls `(rf/install-adapter! ...)` a second time without an intervening `(rf/dispose-adapter!)`.
- **Behaviour:** The second call raises `:rf.error/adapter-already-installed` and does not change the installed adapter. To swap, dispose first, then install.
- **Reason:** Mid-process adapter swap would leave an unknown set of cached reactions, mounted views, and frame containers wired to the old adapter — the inconsistency is unrecoverable. The dispose-then-install path forces a known clean state.
- **Status:** `Provisional` — fixture pending: `adapter-already-installed.edn`.

## Registration family

### 21. Family asymmetry — only `reg-view` has a macro tier

- **Specs:** [001-Registration](001-Registration.md) (the registration family); [Spec 004 §reg-view](004-Views.md#reg-view-is-the-multi-frame-contract); [Conventions §`*`-suffix naming](Conventions.md#-suffix-naming-for-fn-versions-of-macros).
- **Scenario:** A reader looks at the public API and notices that `reg-view` ships as a **macro** with a `reg-view*` plain-fn partner, while every other `reg-*` (`reg-event-db`, `reg-event-fx`, `reg-event-ctx`, `reg-sub`, `reg-fx`, `reg-cofx`, `reg-frame`, `reg-flow`, `reg-route`, `reg-app-schema`, `reg-machine`, `reg-error-projector`) is a plain fn with no `*` partner. Why is the family asymmetric?
- **Behaviour:** `reg-view` is the only `reg-*` macro because views need a Var binding — Reagent calls them by symbol from hiccup heads (`[counter "label"]`). The macro defs the symbol, registers the view, and auto-injects `dispatch` / `subscribe` lexically into the body. None of the other registrations are *invoked by name* from user-facing data; they are dispatched (events) or looked up by id (subs, fx, cofx, frames, routes, schemas, machines) at runtime. They have no need for an auto-defed Var, no need for compile-time auto-id derivation, and no body-shape compile-time check to enforce — so they stay plain fns.
- **The `*` convention applies only where a macro sweetens an underlying fn.** Adding `reg-event-db*` / `reg-sub*` / etc. would be pure aliases for `reg-event-db` / `reg-sub` themselves — no macro tier exists, no fn partner is necessary. The convention is reserved for the sweetened-vs-unsweetened case, per `let` / `let*`.
- **Render trees use Vars; runtime lookups use ids.** Keyword vectors at render time are HTML elements, never views. The runtime does not intercept the keyword case — Reagent's hiccup semantics are preserved unmodified. Render-tree calls go through Var-references (`[my-view args]`); registry lookups go through `(rf/view id)`. See [Spec 004 §Calling a registered view](004-Views.md#calling-a-registered-view).
- **Reason:** The family looks asymmetric because the underlying need is asymmetric. Views participate in the render-tree by Var reference (Reagent's idiomatic hiccup head); other registrations participate by id (the registry holds the data). The macro tier exists where the Var binding is part of the contract; the plain fn tier is sufficient where the registry id is.
- **Status:** `Locked` — Spec 004 owns the `reg-view` macro shape; this entry documents the family-level asymmetry so implementors and readers don't expect a `*` partner for every registration.

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
