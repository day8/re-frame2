# Pattern — Stale Detection

The cross-cutting epoch idiom re-frame2 uses to silently ignore async results from a superseded state. Capture an epoch, carry it through, check on receipt, suppress on mismatch.

Stale-detection is the cross-cutting **correctness** idiom layered over **managed external effects** — `:rf.http/managed`, `:rf.ws/*`, state-machine `:invoke`, `:rf.server/*`, and `:rf.flow/*`. The umbrella's framework-owned reply addressing delivers the reply to the *originating* event id; this leaf names the epoch convention that decides whether the receiving state still wants it. See [`spec/Managed-Effects.md`](../../../spec/Managed-Effects.md) for the umbrella.

## When to load this leaf

Load when the task is:

- An async dispatch from a state container that may have moved on by reply time (a `:after` timer firing after the user left and re-entered the state; an HTTP load completing after the user navigated to a different route; a debounced lookup completing after the user typed another keystroke).
- The user says "ignore stale results", "don't apply old data", "what if the user navigates away mid-request", or "I keep seeing the previous load's data flicker in".
- Composing with Pattern-AsyncEffect, Pattern-LongRunningWork, Pattern-WebSocket, or any state machine that uses `:after`.

Do NOT load for:

- A reply whose event id is unique to its request (`:auth/succeeded`, `:cart/loaded`). Re-frame's standard "unhandled event" fallback already drops it cleanly when the receiving state has moved past handling it. The epoch is needed only when the *same event id* may be dispatched against *different state instances* of the same container.
- Cancellation as an optimisation (saving bandwidth or CPU). That's an `AbortController` concern, not stale-detection. The epoch handles correctness regardless.

## The shape

Five steps, owned by the state container that initiates the async work.

1. The container holds an epoch (a counter or a unique token) on its own slice of `app-db`.
2. The epoch advances on every life event of the container — state transition, route change, connection reset, the start of a new async cycle.
3. The async dispatch captures the *current* epoch and threads it through the reply event payload (or via cofx).
4. On receipt, the handler compares carried-epoch to current-epoch.
5. **Match → commit.** State has not been superseded; apply normally. **Mismatch → suppress.** Emit a structured `:<feature>/stale-<reason>` trace event for visibility, leave state unchanged.

## re-frame2 features this pattern uses

| Feature | Role here |
|---|---|
| Dispatched-event reply channel | The seam where the epoch is carried. The async result re-enters the runtime as a named event whose payload includes the captured epoch. |
| Atomic snapshot commits | `app-db` transitions atomically per drained event. The carried epoch and current epoch are both unambiguous values; comparison is deterministic. |
| Identifiable state containers | `[:rf/machines <id>]`, `[:route]`, frame ids, spawned actor ids — every container in re-frame2 is named, so the epoch lives on the container, not in a global registry. |
| State-machine `:after` (substrate-owned epoch) | The runtime already advances a per-state epoch on entry / re-entry and the after-fired event handler suppresses on mismatch automatically. Application code only sees `:rf.machine.timer/stale-after` trace events. |
| Routing nav-tokens (substrate-owned epoch) | The router advances a nav-token on each route change; route-scoped async loads carry it; the receiving handler suppresses on mismatch. |

For substrate-owned epochs (`:after` timers, nav-tokens), the application gets the pattern for free; no manual epoch threading needed. The application-level shape below is for *user-owned* epochs — fields the application advances itself.

## Canonical declaration — application-owned epoch

A search-as-you-type input where each keystroke spawns a lookup; only the latest lookup's result should commit.

```clojure
(rf/reg-event-fx :search/key-typed
  (fn handler-search-key-typed [{:keys [db]} [_ query]]
    (let [next-epoch (inc (get-in db [:search :epoch] 0))]
      {:db (-> db
               (assoc-in [:search :query] query)
               (assoc-in [:search :epoch] next-epoch)
               (assoc-in [:search :status] :loading))
       :fx [[:http {:method     :get
                    :url        (str "/api/search?q=" query)
                    :on-success [:search/results-received next-epoch]
                    :on-error   [:search/load-failed       next-epoch]}]]})))

(rf/reg-event-fx :search/results-received
  (fn handler-search-results-received [{:keys [db]} [_ carried-epoch results]]
    (let [current-epoch (get-in db [:search :epoch] 0)]
      (if (= carried-epoch current-epoch)
        {:db (-> db
                 (assoc-in [:search :status] :loaded)
                 (assoc-in [:search :results] results))}
        ;; Mismatch — supersede. Drop the result; emit a trace event.
        {:fx [[:rf/trace-event {:operation :search/stale-result
                                :tags      {:carried carried-epoch
                                            :current current-epoch}}]]}))))
```

Three load-bearing points:

- The `:key-typed` handler **advances** the epoch (writes `next-epoch` into `app-db`) and **captures** it into the reply event in the same drain. The reply will be compared against whatever the epoch is *at receive time*, which may be a later value if more keystrokes have arrived.
- The reply handler reads the *current* epoch fresh from `db`, not from any closure capture. The carried epoch is a literal in the event vector; the current epoch lives where the container does.
- On mismatch the handler emits a `:<feature>/stale-<reason>` trace event so tools (re-frame2-pair's epoch buffer, the trace timeline) see the family at a glance. The shape `:search/stale-result` follows the family-naming convention; the specific event name is owned by the feature.

## Trace-event naming

Every stale-detection trace event uses the family shape:

```
:<feature>/stale-<reason>
```

| Trace event | Feature | Reason |
|---|---|---|
| `:rf.machine.timer/stale-after` | State-machine `:after` timer | Epoch mismatch on timer expiry (state was exited then re-entered) |
| `:rf.route.nav-token/stale-suppressed` | Routing async result | Carried nav-token does not match current route's nav-token |
| `:search/stale-result` (your feature) | Per-feature | Application-owned epoch mismatch on reply |

Tools subscribe to `:.*/stale-.*` to surface "a thing should have happened but didn't because state moved on" symptoms across substrates.

## Variations

**Substrate-owned epoch — `:after` timers.** No application work required. The state machine substrate advances `[:rf/machines <id> :epoch]` on every entry / re-entry of the `:after`-bearing state and the substrate-emitted handler suppresses on mismatch. Application-level code sees only the trace event `:rf.machine.timer/stale-after`.

**Substrate-owned epoch — routing.** Same story for route-scoped async loads. The router threads a `nav-token` through the load event payload; the receiver compares against `[:route :nav-token]`. Application code follows the same shape as the search example above but uses the router-supplied token rather than a hand-rolled counter.

**Cofx-injected epoch.** When the dispatching site doesn't know which epoch to carry — e.g., generic logging middleware or a cross-cutting effect — register a cofx that reads the current epoch from the relevant container and injects it. The reply handler reads the carried value off the event payload as before. The dispatcher doesn't need to know about epochs at all.

**Cancellation as optimisation.** Hosts that *do* support cancellation (`AbortController`, ScheduledFuture cancel) MAY layer it on top — abort to save bandwidth/CPU. But correctness does not depend on it. The work *does* complete; the result *does* arrive; the runtime *does* process the dispatch. The result just gets silently discarded at the commit-validation stage. That's correct behaviour with no cancellation infrastructure required.

## Ownership

The state container that **initiates** the async work owns the counter. There is no global epoch; there is no framework-level registry of epochs.

- A state-machine instance owns the epoch for its `:after` timers (substrate-owned).
- The current route owns the epoch for in-flight loads scoped to that route (substrate-owned).
- A search input owns the epoch for its in-flight lookups (application-owned — example above).
- A websocket connection owns its connection-epoch for messages received over it (Pattern-WebSocket).

The owner is also responsible for *advancing* — typically in the same handler that performs the life event. Advance first, then capture; the reply will be compared against whatever value lives in the container at receive time.

## Anti-patterns

- **Comparing epochs across containers.** The counter is well-defined within a single container; comparing a `:search/:epoch` against a `:route/:nav-token` is meaningless. Each container owns its own.
- **Capturing the epoch in a closure rather than the event payload.** Events must serialise (for SSR hydration, Tool-Pair epoch replay, trace events). Put the carried epoch in the event vector.
- **Implementing a global cancellation registry.** The reason re-frame2 does NOT introduce a `:cancel-dispatch-later` primitive is that every async-shaped feature would grow its own cancellation surface. The epoch is portable across timers, HTTP, sockets, workers, and timers — no per-feature plumbing.
- **Not advancing the epoch on every life event.** If only some transitions bump it, a "later" reply may still match an "older" container instance because the counter didn't move. Advance on every event that ought to supersede in-flight work.
- **Skipping the trace event on mismatch.** Suppression must be observable, or "should have happened but didn't" becomes silent. Emit `:<feature>/stale-<reason>` whenever you drop.
- **Re-deriving the substrate's epoch.** If `:after` already handles staleness, do not also carry an app-level epoch for the same timer. The substrate already advances per entry / re-entry.

## Worked example

No standalone example app — every state machine using `:after` is an inline example (the substrate-owned variant), and the routing example in `examples/reagent/routing/` exercises the nav-token variant. The substrate's behaviour is verified by the conformance suite under `spec/conformance/fixtures/` (look for the `stale-after` and `nav-token` fixtures).

## Pointer to the spec

Full rationale — including the architectural properties that make the pattern work, the trace-event family-naming rule, the ownership table for every container type, and the worked example of applying the pattern to a hypothetical `:debounced-input` substrate — lives in *Pattern — Stale detection* (see `SKILL-REDIRECT.md` at the repo root). The first instance of the pattern (substrate-owned `:after` epoch) is in Spec 005 §Epoch-based stale detection; the second (routing nav-tokens) is in Spec 012 §Navigation tokens.

---

*Derived from Pattern-StaleDetection in the spec @ main `89bd9c3`. Substrate-owned variants are tested under `spec/conformance/fixtures/` (search `stale-after`, `nav-token`). Re-verify after epoch-suppression or trace-event family-naming changes.*
