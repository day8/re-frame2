# Pattern — Stale Detection

> **Type:** Pattern
> The cross-cutting epoch idiom re-frame2 uses to silently ignore async results from a superseded state. Convention-and-architecture; not its own Spec.

> **Code samples are in ClojureScript** (the CLJS reference). The pattern itself is host-agnostic.

> **Composition note.** Stale-detection composes with [Pattern-AsyncEffect](Pattern-AsyncEffect.md) for in-flight async work superseded by state changes — when the dispatcher of an async-effect interaction may have moved on by the time the reply arrives, carry an epoch on the dispatched reply event and suppress on mismatch. Same idiom, different host substrate. See also [Pattern-WebSocket](Pattern-WebSocket.md), which uses this pattern for connection-epoch staleness.

## Role

A **named pattern**, not a Spec. This doc *names* a recurring idiom — capture an epoch, carry it through, check on receipt, suppress on mismatch — so per-feature Specs that use it cross-reference one canonical description rather than re-deriving the rationale.

The runtime contract for *each instance* of the pattern is owned by the per-feature Spec; this doc owns only the shared idiom and the trace-event family naming convention.

### Glossary

- **Async work** — any operation whose result arrives back as a dispatched event (HTTP fetch, scheduled timer firing, spawned task reply, websocket message that mutates state).
- **State container** — a named, identifiable holder of state inside `app-db`: a state-machine instance, the current route, a remote-data slice, a websocket connection, etc. Every container that initiates async work owns its own epoch counter.
- **Epoch** — a value (typically an `int` counter, but a unique token works equally well) attached to a state container. The epoch advances on each *life event* of the container — e.g. a state transition, a route change, a connection reset, the start of a new async cycle. An epoch comparison is well-defined within a single container; epochs from different containers are not compared.
- **Carry** — the dispatched async-result event includes the epoch as a payload field (or as a cofx-injected value).
- **Commit** vs **suppress** — when the async result is processed, the handler compares carried epoch to current. *Commit* applies normal state changes; *suppress* drops the event (after emitting a trace event for visibility) and leaves state unchanged.

## The pattern, stated formally

When async work is initiated against an identifiable state container:

1. The runtime captures the **current epoch** of that container — a counter or unique token. The epoch advances on every life event of the container (state transition, route change, frame creation, etc.).
2. The async dispatch carries the captured epoch as a payload field (or in cofx).
3. When the async result arrives, the runtime compares the carried epoch against the **current** epoch.
4. **Match → commit.** State has not been superseded; the result is current; apply normally.
5. **Mismatch → suppress.** State has been superseded; the result is stale; ignore (and emit a structured `<feature>/stale-<reason>` trace event for debugging).

## Why this pattern emerges from re-frame2's architecture

Three architectural properties of re-frame2 compose into the epoch idiom; any host that preserves these three properties gets the pattern for free.

### 1. Async events flow through the runtime — not around it

Every async result that affects state arrives back as a *named, dispatched event* (`:on-success`, `::after-elapsed`, `:request/completed`, etc.). It does not mutate state directly; it crosses the dispatch boundary like any other event. This is what gives us a place to attach the epoch — the dispatched event payload.

### 2. Snapshot commits are atomic to externally-observable state

The frame's `app-db` transitions atomically per drained event. There is a clear "now" in `app-db` that async results can compare against. The carried epoch and the current epoch are both unambiguous values; comparison is deterministic.

### 3. State containers have identifiable instances

`[:rf/machines <id>]`, `[:route]`, frame ids, spawned actor ids, `:after` timers — every state container in re-frame2 is named and identifiable. The epoch does not need to be global; it lives on the container that initiated the async work. Each container's epoch advances on its own life events.

Combined: every state container that initiates async work increments its epoch on each life event; carries the epoch in the dispatched async-result event; checks on receipt against the current snapshot's epoch; suppresses on mismatch.

## Ownership — who owns the epoch counter

The state container that **initiates** the async work owns the epoch counter. There is no global epoch; there is no framework-level registry of epochs. Each container maintains its own counter on its own state, in `app-db` at whatever path the container occupies (or as a typed field on the container in static hosts).

Concretely:

- A state-machine instance owns the epoch for its `:after` timers.
- The current route owns the epoch for in-flight loads scoped to that route.
- A websocket connection owns its connection-epoch for messages received over it.
- A debounced-input container (see worked example below) owns the epoch for its in-flight lookups.

The owner is also responsible for advancing the epoch — typically in the same handler that performs the life event (state transition handler, route-change handler, connection reset handler, etc.). The async dispatch must read and capture the current epoch *after* any advance triggered by the dispatch itself, so it captures the value the **reply** will be checked against.

**Container destruction is host-dependent.** When a state container goes away entirely (machine destroyed, route unmounted, websocket torn down), whether and how the epoch resets is up to the host. Some hosts may fully delete the container's slice from `app-db`; others may keep a tombstone slice with the counter intact. The pattern does not mandate a specific behaviour. If a destroyed-then-recreated container starts fresh from `0`, that is fine; if it continues from where it left off, that is also fine — both are correct because comparison is only meaningful within a single live container.

## Trace-event naming ownership

This pattern owns the **family-level naming convention** for stale-detection trace events: every stale-detection trace event uses the shape `:<feature>/stale-<reason>`. That shape is a normative rule of this pattern.

This pattern does **not** own the specific event names within the family. Each feature that uses the pattern coins its own `:<feature>/stale-<reason>` trace event(s) appropriate to its substrate (timer expiry, navigation result arrival, websocket message receipt, etc.) and registers them in its per-feature spec. The per-feature spec is the source of truth for the exact strings; this pattern is the source of truth for the shape.

## Cancellation as optimisation, not correctness

Stale-detection eliminates the need for a cancellation primitive in the framework contract. Cancellation is hard:

- Not always supported by the host (`AbortController` is fairly recent; not all fetches support it; many runtimes have no equivalent).
- Always best-effort — the work may still complete somewhere; you just don't see the result.
- Different effect implementations support different cancellation surfaces.

Stale-detection sidesteps all of this. The work *does* complete; the result *does* arrive; the runtime *does* process the dispatch. The result just gets silently discarded at the commit-validation stage. That's correct behaviour with no cancellation infrastructure required.

Hosts that *do* support cancellation (`AbortController`, Erlang actor monitor refs, ScheduledFuture cancel) MAY layer it on top as an optimisation — abort to save bandwidth/CPU. But correctness does not depend on it.

> **Why re-frame2 does NOT introduce a cancellation primitive.** Adding `:cancel-dispatch-later` (or equivalent) would force every async-shaped feature to grow its own cancellation surface and would push host-specific concerns (which fetch APIs, which timer APIs, which supervision protocols) into the pattern contract. The epoch is portable across all of them.

## When the pattern is needed (and when it isn't)

The epoch is needed when **state-instance identity matters for handling**. Specifically, when:

- The receiving state IS in a state that handles the event (so re-frame's standard "unhandled event" fallback doesn't catch it), AND
- The author wants to ignore it because of state-instance churn (we left and came back, or this is a stale instance).

**State-machine delayed transitions need it.** A state declares "after N ms in this state, fire event X". If the user exits the state and re-enters it before the timer fires, the original timer eventually fires and dispatches event X — but the receiving state IS in a state that handles X (it just re-entered), so the event would be processed against a fresh state instance. The carried epoch lets the handler suppress the stale firing. (The state-machine spec uses the term `:after` for these delayed transitions; see [005 §Epoch-based stale detection](005-StateMachines.md#epoch-based-stale-detection).)

**Navigation-scoped async results need it.** A route initiates a load, the user navigates to a different route, and the new route happens to handle the same reply event id. Without the epoch, the prior load's result would be applied to the new route. The epoch is keyed to the route at dispatch time and checked against the current route on arrival. (The routing spec uses the term `nav-token` for this route-scoped epoch; see [012 §Navigation tokens](012-Routing.md#navigation-tokens--stale-result-suppression).)

Spawned-actor replies often **don't** need it — the reply event id is usually request-specific (`:auth/succeeded` for auth, `:cart/loaded` for cart). When the parent transitions to a state that doesn't handle the event, re-frame's standard "unhandled event" fallback catches it.

But if a parent re-enters the same state (issues a new request after transitioning back), the new request's reply could collide with the previous request's reply — same event id, different request instance. In that case, the epoch is needed.

**Default rule for per-feature spec authors:** if the feature involves the same dispatch event id being potentially fired twice for *different* state instances, use the epoch. Document the choice explicitly.

## Trace event naming convention

All stale-detection trace events follow the same shape, so tools see the family at a glance:

```
:<feature>/stale-<reason>
```

| Trace event | Feature | Reason |
|---|---|---|
| `:rf.machine.timer/stale-after` | State-machine `:after` timer | Epoch mismatch on timer expiry (state was exited then re-entered, or never re-entered) |
| `:route.nav-token/stale-suppressed` | Routing async result | Carried nav-token does not match current route's nav-token |

Tags carry the carried epoch, the current epoch, and any per-feature context (`:state`, `:route-id`, `:event-id`).

Consumers can subscribe to all stale-detection events with a simple regex on `:operation` — `:.*/stale-.*` — to surface "a thing should have happened but didn't because the state moved on" symptoms across substrates.

## Worked example — applying the pattern to a new async feature

Suppose a future re-frame2 lib adds a `:debounced-input` substrate: each keystroke spawns an async lookup; only the latest lookup's result should commit.

The substrate gains a per-input epoch, e.g. stored on the input state container. Each spawned lookup captures the epoch at spawn time. The lookup's `:on-success` carries it back. The receiving handler's first action: compare carried vs current; mismatch → emit `:input.debounce/stale-result`; suppress.

That's the entire correctness story. No cancellation, no `AbortController`, no special debounce primitive — just three architectural properties composing into the epoch idiom.

## Cross-references

- [005 §Delayed `:after` transitions §Epoch-based stale detection](005-StateMachines.md#epoch-based-stale-detection) — first instance of the pattern.
- [012 §Navigation tokens — stale-result suppression](012-Routing.md#navigation-tokens--stale-result-suppression) — second instance, same idiom.
- [Spec-Schemas.md §`:rf/trace-event`](Spec-Schemas.md#rftrace-event) — the trace-op vocabulary, where the `*/stale-*` naming is documented.
- [Principles.md §Regularity over cleverness](Principles.md#regularity-over-cleverness) — the meta-principle that justifies naming a recurring shape rather than re-deriving it per feature.
