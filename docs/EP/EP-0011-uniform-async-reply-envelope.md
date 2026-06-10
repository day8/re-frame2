# EP-0011: Uniform Async Reply Envelope

Status: proposal
Type: standards-track

> Drafted from the first-principles synthesis. This EP proposes one normalized
> completion shape for managed asynchronous effects, with existing callback
> vocabularies lowered onto it.
>
> Normative home after acceptance: the Managed-Effects spec,
> `spec/016-Resources.md`, and the specs for machines, routing, HTTP, and any
> effect family that produces asynchronous causal replies.

## Abstract

re-frame2 uses events as continuations for effects. That is the right shape for
a deterministic event fold, but each async effect family currently names its
continuation differently: HTTP success/failure callbacks, resource replies,
timers, route-settle events, and machine-delivered events all repeat the same
idea.

This EP defines a **uniform async reply envelope**. A managed async effect
declares where to send its reply, and the runtime completes that reply with a
standard outcome map containing status, value/error, correlation, cancellation
or staleness information, and world inputs such as completion time.

Existing `:on-success`/`:on-failure` style APIs can remain as compatibility
sugar that lowers into the uniform envelope.

## Motivation

Async work has cross-cutting behavior:

- dedupe;
- cancellation;
- stale reply suppression;
- tracing;
- work-ledger correlation;
- frame teardown;
- route egress;
- retry;
- SSR preload/hydration;
- error promotion.

If each async effect family encodes continuation differently, the runtime must
reimplement those concerns per family. A single reply shape makes them one
subsystem concern.

The work ledger proposed around resource queries is the natural substrate: a
ledger row is a reified continuation. This EP generalizes that idea beyond
resources without forcing every public API to look identical immediately.

## Goals

- Define one reply envelope for managed async completions.
- Keep events as the continuation mechanism.
- Allow existing effect APIs to lower onto the envelope.
- Make work-ledger correlation, stale suppression, and cancellation uniform.
- Carry causal world inputs from EP-0010 on the reply.

## Non-Goals

- This EP does not introduce promises, callbacks, or monads into the app-facing
  event model.
- This EP does not require removing `:on-success` and `:on-failure` from public
  HTTP APIs.
- This EP does not define the complete work-ledger schema. It defines the reply
  surface that ledger-backed work uses.
- This EP does not require all one-shot synchronous effects to use the envelope.

## Relationships

- `spec/016-Resources.md` needs a work-ledger and resource reply format. This
  EP defines the reusable continuation part.
- EP-0010 causal world inputs says completion time must be on the reply if it
  affects durable state.
- EP-0008 production observability channels use reply status and error
  classification to decide which failures survive production.
- Machines and routing may continue to expose domain-specific source forms, but
  their async completions should lower to the same internal reply model.

## Specification

### Reply Target

A managed async effect SHOULD have one normalized reply target. The spelling is
an open issue; this draft uses `:rf/reply-to`.

```clojure
{:url "/api/articles/42"
 :method :get
 :rf/reply-to [:article/load-replied {:id 42}]}
```

The reply target is an event vector prefix. On completion, the runtime dispatches
that event with a reply map added in the effect-family's specified position.

Example dispatched event:

```clojure
[:article/load-replied
 {:id 42
  :rf/reply {:status :ok
             :value {:title "A title"}
             :work/id [:http :article/by-id 42]
             :completed-at-ms 1781078400456
             :stale? false}}]
```

### Reply Map

The reply map SHOULD contain:

```clojure
{:status          :ok                 ;; or :error, :cancelled, :stale
 :value           value               ;; present for successful replies
 :error           error               ;; present for failed replies
 :work/id         work-id             ;; correlation with a work-ledger row
 :started-at-ms   started-at-ms       ;; optional, causal input if durable
 :completed-at-ms completed-at-ms     ;; optional, causal input if durable
 :attempt         attempt-number      ;; optional
 :stale?          boolean             ;; optional, for ignored replies
 :aborted?        boolean             ;; optional
 :meta            effect-family-meta} ;; optional, data only
```

Exact keys may be refined during proposal review, but the shape is one map with
one status field and data-only correlation.

### Lowering Existing HTTP Callbacks

Existing source forms remain valid:

```clojure
{:http-xhrio {:uri "/api/articles/42"
              :method :get
              :on-success [:article/load-ok 42]
              :on-failure [:article/load-failed 42]}}
```

The runtime may lower this to the uniform representation:

```clojure
{:rf.http/request
 {:uri "/api/articles/42"
  :method :get
  :rf/reply-to [:rf.http/compat-reply
                {:on-success [:article/load-ok 42]
                 :on-failure [:article/load-failed 42]}]}}
```

The compatibility reply handler then dispatches the legacy event shape:

```clojure
[:article/load-ok 42 response]
[:article/load-failed 42 error]
```

The lowering is an implementation strategy, not a requirement for application
authors to use the compatibility event.

### Resource Query Example

A resource fetch can be expressed directly in reply form:

```clojure
{:rf.resource/fetch
 {:resource/id :article/by-id
  :params {:id 42}
  :cache-key [:article/by-id {:id 42}]
  :rf/reply-to [:rf.resource/replied
                {:resource/id :article/by-id
                 :params {:id 42}}]}}
```

The resource reducer applies the reply map to the cache entry and ledger row:

```clojure
[:rf.resource/replied
 {:resource/id :article/by-id
  :params {:id 42}
  :rf/reply {:status :ok
             :value article
             :work/id [:resource :article/by-id {:id 42}]
             :completed-at-ms 1781078400456}}]
```

### Timer Example

Timer effects also complete as replies:

```clojure
{:rf.timer/after
 {:ms 250
  :rf/reply-to [:search/debounce-fired {:query-id query-id}]}}
```

Completion:

```clojure
[:search/debounce-fired
 {:query-id query-id
  :rf/reply {:status :ok
             :value nil
             :work/id [:timer query-id]
             :completed-at-ms 1781078400300}}]
```

### Functor-Like Mapping

The runtime SHOULD support pure transformation of reply targets. This can be as
simple as wrapping the target event vector or as explicit as a helper that maps
reply payloads.

The conformance law is:

```text
complete(map-reply(f, work), result) == map-event(f, complete(work, result))
```

In plain terms: mapping the continuation before completion and mapping the event
after completion should produce equivalent causal events.

## Rationale

The proposal keeps the strongest part of re-frame: async work reports back as
events, not as hidden callbacks mutating state. The improvement is to avoid
inventing a new continuation slot for every effect family.

Uniform replies also make production behavior easier to audit. A stale reply, a
cancelled reply, and a failed reply become statuses in one data shape rather
than effect-family-specific branches.

## Backwards Compatibility

This EP is designed for compatibility. Existing public effect maps can lower to
the reply envelope. Libraries may expose both ergonomic source forms and the
normalized lower-level form during migration.

No existing application should be required to rewrite `:on-success` and
`:on-failure` handlers as part of the first implementation.

## Bead Plan / Reference Implementation

1. Specify the canonical reply map keys and the canonical reply-target key.
2. Add internal helpers for creating, completing, and tracing replies.
3. Lower one existing managed async effect family to the envelope behind its
   current public API.
4. Integrate the envelope with work-ledger rows for resource queries.
5. Add stale-suppression and cancellation tests that run through the shared
   reply path.
6. Document compatibility lowering for legacy callback APIs.

## Open Issues

- Is the target key `:rf/reply-to`, `:reply-to`, or effect-family-specific with
  normalized internal lowering?
- Does the reply map belong as the last event argument, under `:rf/reply` in an
  options map, or in a fixed envelope event?
- Which statuses are canonical: `:ok`, `:error`, `:cancelled`, `:stale`, and
  are `:timeout` or `:aborted` statuses or error categories?
- Does every reply require a work id, or only ledger-managed replies?

## Recommendation

Adopt directionally. The reply envelope is a small abstraction that removes
duplicated async control flow and gives the resource work ledger a framework-wide
meaning.
