# EP-0010: Causal World Inputs

Status: proposal
Type: standards-track

> Drafted from the first-principles synthesis. This EP proposes a replay
> determinism rule: host facts that affect durable frame-state must enter as
> causal input data, not ambient reads during transition execution.
>
> Normative home after acceptance: `spec/002-Frames.md` and the runtime
> conventions/spec section that defines event envelopes and coeffects.

## Abstract

re-frame2 promises a value-oriented runtime: a frame transition should be a
deterministic computation of prior frame-state plus a causal input. That promise
is weakened when durable app-db or runtime-db values are derived from ambient
host calls such as `now-ms`, random number generation, or UUID generation during
event handling, commit, resource bookkeeping, or work-ledger updates.

This EP defines **causal world inputs**. Any host fact that can affect durable
frame-state MUST be carried on the event envelope or supplied as an explicit,
recordable coeffect. Ambient host reads remain allowed for diagnostics,
performance measurement, host scheduling, and host-transient state that is not
part of replayable frame-state.

## Motivation

The current architecture is close to a pure fold:

```text
next-frame-state = transition(previous-frame-state, event-envelope)
```

That model is valuable because it enables replay, restore, deterministic tests,
SSR/hydration reasoning, and causal trace inspection. But the model is only
literal if the transition does not secretly consult the outside world.

Resource queries and work ledgers make this more urgent. Fields such as
`:started-at`, `:loaded-at`, `:stale-at`, `:deadline-at`, and epoch timestamps
are durable facts. If they come from ambient clock reads, replaying the same
event log later can produce different frame-state.

## Goals

- State the world-input rule once, at the event-envelope/coeffect boundary.
- Preserve replay determinism for durable app-db and runtime-db writes.
- Allow existing diagnostic and host-transient clock reads where they do not
  affect replayable frame-state.
- Give resources, work-ledger rows, epochs, machines, and routing one shared
  timestamp source.
- Make the rule lintable and testable.

## Non-Goals

- This EP does not require persisting every diagnostic timestamp.
- This EP does not make the browser clock trustworthy or monotonic.
- This EP does not define a public time-travel debugger storage format.
- This EP does not forbid host timers; it only constrains durable facts derived
  from them.

## Relationships

- EP-0001 defines the coherent app-db/runtime-db frame-state product. This EP
  protects that product from ambient impurity.
- EP-0002 defines explicit frame targeting and event envelopes. This EP adds a
  required world-input rule to that causal boundary.
- `spec/016-Resources.md` needs durable freshness and deadline timestamps; this
  EP defines where those timestamps come from.
- EP-0008 production observability channels remain separate: diagnostic channel
  timestamps may be ambient because they are not replayed as state.

## Specification

### Definitions

A **world input** is a host fact not determined by prior frame-state and the
event id/payload alone. Examples include wall-clock time, monotonic time,
randomness, generated UUIDs, browser location reads, storage reads, and network
completion metadata.

A **durable write** is any write that changes frame-state: app-db,
runtime-db, epoch records, resource cache entries, work-ledger rows, durable
machine snapshots, or any future replayable partition.

A **causal token** is an event envelope, managed-effect reply envelope, restore
token, or other input object that enters the frame fold.

### The World-Input Rule

If a world input affects a durable write, it MUST enter the transition as data
on a causal token or as an explicit coeffect captured in that token's replayable
input record.

Ambient reads MAY be used for:

- diagnostic trace timestamps;
- performance durations;
- scheduling host timers;
- comparing whether a host-transient cache entry should be evicted;
- deciding whether to enqueue a future causal token, provided the durable
  result is recorded on that token when it enters the fold.

Ambient reads MUST NOT be used directly to compute durable frame-state.

### Event Envelope Time

The router SHOULD stamp every event envelope with an enqueue-time value when no
caller supplies one. The exact key is an open issue, but the value is exposed to
coeffects and internal subsystems as the event's causal time.

Example envelope shape:

```clojure
{:event/id      :article/load-succeeded
 :event/payload {:id 42 :body article}
 :rf/frame      :main
 :rf.event/time-ms 1781078400123}
```

Replay, restore, tests, SSR preload, and host integrations MAY supply the time
explicitly. The router MUST preserve an explicitly supplied envelope time.

### Correct And Incorrect Durable Timestamping

Incorrect: durable state reads the ambient clock inside the handler.

```clojure
(rf/reg-event-fx
  :article/load-succeeded
  (fn [{:keys [db]} [_ id article]]
    {:db (assoc-in db [:articles id]
                   {:body article
                    :loaded-at (interop/now-ms)})}))
```

Correct: durable state uses the envelope/coeffect time.

```clojure
(rf/reg-event-fx
  :article/load-succeeded
  (fn [{:keys [db rf/event-time-ms]} [_ id article]]
    {:db (assoc-in db [:articles id]
                   {:body article
                    :loaded-at rf/event-time-ms})}))
```

The exact coeffect key is intentionally left open. The required property is that
the value came from the causal token, not from a fresh ambient read.

### Randomness And Generated Identity

If a generated value becomes durable identity, it follows the same rule.

Incorrect:

```clojure
{:db (assoc-in db [:todos (random-uuid)] {:text text})}
```

Correct:

```clojure
;; The UUID is supplied by the event envelope, an explicit coeffect, or a prior
;; host command that reports it back as causal data.
{:db (assoc-in db [:todos todo-id] {:text text})}
```

### Managed Effects

Managed effects that complete asynchronously SHOULD put completion metadata on
the reply envelope. For example, a resource response should carry the
completion time that will become `:loaded-at`; the resource reducer should not
read the clock again while applying the reply.

```clojure
[:article/resource-reply
 {:id 42
  :rf/reply {:status :ok
             :value article
             :completed-at-ms 1781078400456
             :work/id [:resource :article/by-id 42]}}]
```

### Conformance

The implementation SHOULD provide a lint or grep-style guard that flags direct
ambient time/random/UUID calls in code paths that produce durable writes. The
guard is allowed to be conservative and may use explicit allowlists for
diagnostics, scheduling, and host-transient state.

The implementation SHOULD include replay tests that show durable timestamps are
preserved when an event log is replayed with the same envelope times.

## Rationale

This EP keeps the productive re-frame programming model intact while making the
runtime's determinism claim precise. The event envelope already exists; adding
world inputs to it is smaller and safer than teaching every subsystem its own
restore-time interpretation of ambient facts.

The rule also makes the two-channel observability doctrine cleaner. Causal data
is replayable and state-affecting. Diagnostic data is ambient and operational.
The implementation can use both, but it must not confuse them.

## Backwards Compatibility

This proposal is additive at the API level. Existing handlers that read the
clock will continue to run until the lint and migration plan decide how hard to
enforce the rule.

The first implementation should expose envelope time as an additional coeffect
while keeping existing coeffects stable.

## Bead Plan / Reference Implementation

1. Add envelope time stamping at enqueue and preserve caller-supplied envelope
   time.
2. Expose event time through the event coeffects used by handlers and internal
   reducers.
3. Audit durable writes in epochs, resources, work ledgers, machines, routing,
   and flows for ambient world reads.
4. Add a replay test that proves durable timestamps remain stable.
5. Add a conservative lint/grep guard with documented allowlists.

## Open Issues

- What is the canonical public key: `:rf.event/time-ms`,
  `:rf/event-time-ms`, or another spelling?
- Should the time value be wall-clock milliseconds, monotonic milliseconds, or
  a structured clock value?
- Should random/UUID coeffects be specified now or only covered by the general
  rule until a concrete use case needs more detail?

## Recommendation

Adopt in principle. This is a small rule with high leverage: it protects replay
determinism, simplifies resource freshness semantics, and gives future managed
effects one place to put world facts that become durable state.
