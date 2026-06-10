# EP-0014: Derivation And Process Algebra

Status: proposal
Type: standards-track

> Drafted from the first-principles synthesis. This EP proposes one vocabulary
> for subscriptions, runtime subscriptions, flows, resources, and selected
> machine/process state.
>
> Normative home after acceptance: subscription, flows, resource, machines, and
> runtime-model specs.

## Abstract

Subscriptions, runtime subscriptions, flows, resources, and machine selectors
are currently exposed as separate mechanisms. They should remain available as
ergonomic source forms, but they are all points in one design space:

- declared inputs;
- a derivation function or process transition;
- an output fact;
- a storage class;
- an evaluation policy;
- a lifecycle/owner.

This EP defines a shared **derivation/process algebra**. The goal is not a new
public API on day one. The goal is a common model that makes the system easier
to explain, inspect, test, and extend.

## Motivation

A large SPA repeatedly asks "where does this fact come from?" Today the answer
may cross several vocabularies:

- `reg-sub` derives ephemeral values for views;
- `reg-runtime-sub` reads runtime-db;
- `reg-flow` materializes derived app-db state;
- resources fetch and cache server-state by params/scope;
- machines maintain process snapshots and expose selectors;
- route matches drive loaders and state transitions.

Those are real differences, but they do not need unrelated mental models. A
common vocabulary lets docs, Xray, tests, and AI agents show one graph of facts
and processes rather than several partial diagrams.

## Goals

- Define a shared vocabulary for derived facts and stateful processes.
- Preserve existing source forms such as `reg-sub` and `reg-flow`.
- Make storage and evaluation policy explicit.
- Provide a natural home for resource queries and machine selectors.
- Leave room for optional delta contracts without making them the default.

## Non-Goals

- This EP does not replace the subscription API.
- This EP does not require every app to author derivation maps directly.
- This EP does not make flows obsolete.
- This EP does not require a differential-dataflow engine.
- This EP does not collapse machines into simple subscriptions.

## Relationships

- `spec/016-Resources.md` resource queries become one kind of
  derivation/process with runtime-owned storage and async reply integration.
- EP-0004 subscription inputs fit directly into the declared input vocabulary.
- EP-0005 machines remain the formal state-machine/process form.
- EP-0011 async replies are the command/reply path for remote or timed
  processes.
- EP-0012 paths and canonical identity provide the input/output addressing
  rules.
- EP-0013 app values are the natural place to store derivation declarations.

## Specification

### Core Vocabulary

A **fact** is a named value that can be read by views, handlers, tools, or other
derivations.

A **derivation** computes a fact from declared inputs.

A **process** is a derivation with state, lifecycle, and commands over time.
Machines are the most formal process kind.

Every derivation/process declaration SHOULD be describable by these dimensions:

```clojure
{:id         :cart/total
 :kind       :derivation       ;; or :process, :resource, :machine-selector
 :inputs     [...]
 :output     ...
 :storage    ...
 :evaluation ...
 :lifecycle  ...
 :derive     ...}
```

### Inputs

Inputs are data descriptions of dependencies:

```clojure
[[:db [:cart :items]]
 [:db [:pricing :discounts]]
 [:runtime [:rf.runtime/routes :current]]
 [:param :article/id]
 [:resource :article/by-id {:id [:param :article/id]}]
 [:machine :upload/main :state]]
```

Input functions from EP-0004 remain a source form that compiles into declared
inputs at runtime.

### Storage Classes

The storage class states where the output lives:

```clojure
:ephemeral       ;; cached by reactive substrate; not durable frame-state
:app-db          ;; materialized under app-db
:runtime-db      ;; durable framework-owned runtime state
:host-transient  ;; host handles/caches, not replayable durable state
:remote          ;; server-owned fact represented locally by resource state
```

Not every storage class is public writable by app code. EP-0006 governs
runtime-db and host-transient authority.

### Evaluation Policies

Evaluation policy states when the derivation runs:

```clojure
:on-demand       ;; subscription-style
:after-event     ;; flow-style materialization
:on-reply        ;; resource or async completion
:on-route        ;; route activation/egress
:scheduled       ;; timer/process driven
:manual          ;; explicit invalidation or refresh
```

### Subscription Example

Existing source:

```clojure
(rf/reg-sub
  :cart/total
  :<- [:cart/items]
  :<- [:pricing/discounts]
  (fn [[items discounts] _]
    (sum-cart items discounts)))
```

Algebra view:

```clojure
{:id :cart/total
 :kind :derivation
 :inputs [[:sub :cart/items]
          [:sub :pricing/discounts]]
 :storage :ephemeral
 :evaluation :on-demand
 :derive sum-cart}
```

### Flow Example

Existing source:

```clojure
(rf/reg-flow
  {:id :cart/materialized-total
   :inputs {:items [:cart :items]
            :discounts [:pricing :discounts]}
   :path [:cart :total]
   :output (fn [{:keys [items discounts]}]
             (sum-cart items discounts))})
```

Algebra view:

```clojure
{:id :cart/materialized-total
 :kind :derivation
 :inputs [[:db [:cart :items]]
          [:db [:pricing :discounts]]]
 :output [:db [:cart :total]]
 :storage :app-db
 :evaluation :after-event
 :derive sum-cart}
```

### Resource Example

```clojure
{:id :article/by-id
 :kind :resource
 :inputs [[:param :id]
          [:scope :tenant-id]]
 :output [:resource-cache :article/by-id]
 :storage :runtime-db
 :evaluation :on-demand
 :lifecycle :owner
 :commands [{:effect :http/get
             :rf/reply-to [:rf.resource/replied
                           {:resource/id :article/by-id}]}]}
```

### Machine Selector Example

```clojure
{:id :upload/progress
 :kind :machine-selector
 :inputs [[:machine :upload/main :data]]
 :storage :ephemeral
 :evaluation :on-demand
 :derive (fn [snapshot] (get-in snapshot [:data :progress]))}
```

A machine process itself has state and commands:

```clojure
{:id :upload/main
 :kind :process
 :storage :runtime-db
 :evaluation :on-reply
 :lifecycle :frame
 :states [:idle :uploading :failed :done]
 :commands {:uploading [{:effect :http/post
                         :rf/reply-to [:upload/replied]}]}}
```

### Optional Delta Contract

A derivation MAY provide a delta step as an optimization:

```clojure
{:id :large-grid/visible-rows
 :derive derive-visible-rows
 :step-delta step-visible-rows-delta}
```

The required law is:

```text
derive(apply-delta(input, delta)) =
apply-delta(derive(input), step-delta(output, delta))
```

The exact delta representation is deferred. Whole-value derivation remains the
default and must remain correct without a delta step.

### Graph Inspection

The runtime SHOULD be able to expose a graph view of derivations and processes:

```clojure
{:facts #{:cart/items :pricing/discounts :cart/total}
 :edges #{[:cart/items :cart/total]
          [:pricing/discounts :cart/total]}
 :storage {:cart/total :ephemeral}}
```

This is useful for Xray, docs, tests, and static analysis. The first
implementation may derive the graph from existing registrations rather than
requiring new source forms.

## Rationale

The proposal reduces conceptual duplication without flattening useful
differences. A subscription and a resource are not the same runtime mechanism,
but they can share the same inspection vocabulary: inputs, output, storage,
evaluation, lifecycle.

This is also the right place to keep advanced performance ideas. Delta
contracts can be valuable for large collections, but only as law-checked
optimization. The ordinary app author should still write whole-value functions.

## Backwards Compatibility

Existing APIs remain source forms. `reg-sub`, `reg-runtime-sub`, `reg-flow`,
resource declarations, and machine registrations can be described by the
algebra without changing call sites.

The first accepted version should be documentation and internal metadata before
any public API rewrite.

## Bead Plan / Reference Implementation

1. Add a spec section defining fact, derivation, process, input, storage,
   evaluation, and lifecycle vocabulary.
2. Teach the registrar metadata for subscriptions and flows to expose this
   vocabulary internally.
3. Add a graph-inspection helper for a small subset of registrations.
4. Map Spec 016 resource declarations into the same vocabulary.
5. Add examples to docs showing the source form and algebra view side by side.
6. Defer delta execution until a real performance bead needs it.

## Open Issues

- Which storage classes are normative now, and which are explanatory only?
- Does graph inspection become public API or Xray/internal API first?
- How much of a process declaration overlaps with the machine spec?
- Should route matches be facts in this algebra, or remain a routing-specific
  input kind?

## Recommendation

Adopt directionally as a unifying standards-track proposal. It should initially
be a spec and metadata model, not a replacement API. That gives the project the
shared language needed for resources, machines, flows, and future app values.
