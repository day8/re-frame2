# EP: Parametric Subscription Inputs

Status: proposal

Type: Standards Track

Date: 2026-06-08

Created: 2026-06-08

Target Artifact: `day8/re-frame2-core`

Target API Surface:

- `reg-sub`
- `subscribe`
- `subscribe-once`
- `compute-sub`
- `sub-topology`
- subscription cache entries
- Xray subscription topology and live-sub inspection
- migration guidance from re-frame v1

Requires:

- [Spec 006 - Reactive Substrate](https://github.com/day8/re-frame2/blob/main/spec/006-ReactiveSubstrate.md)
- [Spec 008 - Testing](https://github.com/day8/re-frame2/blob/main/spec/008-Testing.md)
- [Spec 009 - Instrumentation](https://github.com/day8/re-frame2/blob/main/spec/009-Instrumentation.md)
- [Spec API](https://github.com/day8/re-frame2/blob/main/spec/API.md)
- [Conventions](https://github.com/day8/re-frame2/blob/main/spec/Conventions.md)

Benchmark References:

- re-frame v1 `reg-sub` signal functions
- re-frame v1 `:<-` subscription sugar
- re-frame2 `compute-sub`
- re-frame2 Xray subscription topology

## Abstract

This enhancement proposes restoring the useful part of re-frame v1's
two-function subscription registration form:

```clojure
(rf/reg-sub
  :some/id
  (fn input-fn [query-v]
    ...)
  (fn computation-fn [inputs query-v]
    ...))
```

re-frame v1 used the first function as a signal function. It was called with
the subscription query vector and returned the input signals for the
subscription node. v1 also provided `:<-` sugar for the common static case:

```clojure
(rf/reg-sub
  :some/id
  :<- [:some/input]
  (fn [input query-v]
    ...))
```

re-frame2 currently preserves the static `:<-` sugar but appears not to
support the general two-function form in the implementation. Some specs still
describe `signal-fn?`, so the design surface is internally inconsistent.

The proposed re-frame2 rule is:

> `reg-sub` supports parametric input functions, but they return subscription
> query descriptors, not live substrate reactions.

That keeps the expressive power that matters: a subscription can choose its
upstream subscriptions from its own `query-v`. The v2 change is in the first
function's return value:

```clojure
;; v1: return live signals
[(rf/subscribe [:x])
 (rf/subscribe [:y])]

;; v2: return subscription query descriptors
[[:x] :y]
```

A bare keyword in an input descriptor is shorthand for the zero-argument query
vector of the same id, so `:y` means `[:y]`. The shorthand only applies in
descriptor positions: `[[:x] :y]` is two inputs, while `[:x :y]` remains one
query vector whose id is `:x` and whose argument is `:y`. This avoids
reintroducing the v1 weakness where a signal function could be
substrate-specific, opaque to JVM tests, and hard for Xray or AI tools to
explain.

## Motivation

The common subscription forms are already well served:

```clojure
(rf/reg-sub :items
  (fn [db _]
    (:items db)))

(rf/reg-sub :visible-items
  :<- [:items]
  :<- [:filters/current]
  (fn [[items filter] _]
    (filter-items items filter)))
```

The missing case is a subscription whose upstream inputs depend on the query
vector:

```clojure
(rf/subscribe [:article/view :article/id-123])
```

That query may need inputs such as:

- `[:article/by-id :article/id-123]`
- `[:comments/for-article :article/id-123]`
- `[:permissions/current-user]`

With only static `:<-`, authors must choose between awkward workarounds:

- read too much from app-db in one large layer-1 sub;
- create many ad hoc subscriptions;
- put query selection logic into the computation function and subscribe to
  broad upstream values;
- avoid the subscription graph and move derivation into views;
- use `subscribe-once` or event handlers for reads that are really
  materialized-view concerns.

Those workarounds are contrary to the re-frame2 ethos:

- views are declarative reads over materialized views;
- subscriptions are pure derived data;
- event handlers are causal writes, not query planners;
- tooling should be able to show the signal graph;
- tests should be able to compute subscriptions without a browser substrate;
- AI agents should be able to inspect a registration and understand its
  dependency shape.

The capability also matters for resource queries, routing, Hasura/GraphQL
integration, multi-frame apps, and Xray. Many best-in-class SPA patterns need
parameterized read models: "the article for this route", "the cache entry for
this resource key", "the live result for this Hasura variable map", "the
validation state for this form field", and "the machine snapshot for this
actor id".

## Current State

The current implementation parser in `re-frame.subs` accepts:

```clojure
(rf/reg-sub :id handler-fn)

(rf/reg-sub :id
  :<- [:upstream]
  handler-fn)

(rf/reg-sub :id
  :<- [:a]
  :<- [:b]
  handler-fn)
```

It stores static inputs in `:input-signals`. The reactive path and
`compute-sub` recursively resolve those static query vectors.

The specs are broader than the implementation:

- `spec/API.md` advertises `(reg-sub id ?metadata signal-fn? computation-fn)`;
- `spec/008-Testing.md` describes `compute-sub` resolving either static `:<-`
  inputs or a function-shaped signal result;
- `spec/006-ReactiveSubstrate.md` mostly documents the static `:<-` topology.

So there are two separate questions:

1. Should re-frame2 support the general two-function input form?
2. If yes, should it preserve v1 reaction-returning signal functions, or define
   a cleaner v2 data-returning input function?

This EP recommends "yes" to the first question and "data-returning input
function" to the second.

## Goals

- Preserve `:<-` as the preferred static-input sugar.
- Add a general parametric input function form.
- Make the input function depend on `query-v`, not app-db.
- Make the input function return subscription query descriptors, not live
  reactions.
- Keep `compute-sub` pure and JVM-runnable.
- Keep Xray and pair tools able to inspect realized dependencies.
- Preserve value-equality recompute suppression.
- Preserve synchronous subscription disposal.
- Give migration users a simple mechanical rewrite from v1 signal functions.
- Keep invalid input loud under the no-silent-swallow rule.

## Non-Goals

- Do not restore `reg-sub-raw`.
- Do not restore re-frame alpha lifecycle policies.
- Do not let ordinary subscription input functions read app-db to decide
  topology.
- Do not make subscription fetching or side effects legitimate.
- Do not make function-shaped topology fully enumerable at registration time.
- Do not support substrate-private reactions as the canonical return value.

## Proposed API

### Layer-1 app-db reader

Unchanged:

```clojure
(rf/reg-sub
  :article/by-id
  (fn [db [_ article-id]]
    (get-in db [:articles/by-id article-id])))
```

The handler receives app-db and the full query vector.

### Static input sugar

Unchanged:

```clojure
(rf/reg-sub
  :article/title
  :<- [:article/current]
  (fn [article _]
    (:title article)))
```

Multiple static inputs remain:

```clojure
(rf/reg-sub
  :article/page
  :<- [:article/current]
  :<- [:comments/current]
  :<- [:permissions/current-user]
  (fn [[article comments permissions] _]
    {:article article
     :comments comments
     :can-edit? (:edit? permissions)}))
```

This is sugar for a static vector of upstream query vectors. It is the best
form when the upstream topology is independent of the caller's query args.

### Parametric input function

New supported form:

```clojure
(rf/reg-sub
  :article/page
  (fn [[_ article-id]]
    [[:article/by-id article-id]
     [:comments/for-article article-id]
     :permissions/current-user])
  (fn [[article comments permissions] [_ article-id]]
    {:id article-id
     :article article
     :comments comments
     :can-edit? (:edit? permissions)}))
```

The first function:

- is called `input-fn` in re-frame2 documentation and metadata;
- receives the full subscription query vector;
- is called when the subscription node is materialized for a concrete
  `query-v`;
- returns subscription query descriptors describing the upstream inputs;
- must be pure and deterministic over `query-v`;
- must not call `subscribe`, deref app-db, dispatch, mutate, or perform IO.

The second function:

- is called `computation-fn`;
- receives resolved input values as its first argument;
- receives the original outer `query-v` as its second argument;
- remains the only place where derived data is calculated.

### Input function return shapes

The input function may return one of these shapes:

```clojure
;; No upstream inputs.
nil

;; One zero-argument upstream query. The computation fn receives that value.
:permissions/current-user

;; One upstream query. The computation fn receives that input value.
[:article/by-id article-id]

;; Ordered upstream query descriptors. The computation fn receives a vector of
;; values. Bare keywords mean zero-argument queries in descriptor position.
[[:article/by-id article-id]
 [:comments/for-article article-id]
 :permissions/current-user]

;; Named upstream query descriptors. The computation fn receives a map of
;; values with the same keys.
{:article [:article/by-id article-id]
 :comments [:comments/for-article article-id]
 :viewer :permissions/current-user}
```

Examples:

```clojure
(rf/reg-sub
  :article/edit-model
  (fn [[_ article-id]]
    {:article [:article/by-id article-id]
     :viewer :permissions/current-user
     :form [:forms/by-id [:article article-id]]})
  (fn [{:keys [article viewer form]} [_ article-id]]
    {:id article-id
     :title (:title article)
     :body (:body article)
     :dirty? (:dirty? form)
     :can-save? (and (:dirty? form) (:edit? viewer))}))
```

This map form is not just convenience. It makes larger derived views readable
and removes positional coupling in subscriptions with more than two inputs.

## Relationship To re-frame v1

re-frame v1's general form allowed a signal function that returned live input
signals, often by calling `subscribe`:

```clojure
(rf/reg-sub
  :article/page
  (fn [[_ article-id]]
    [(rf/subscribe [:article/by-id article-id])
     (rf/subscribe [:comments/for-article article-id])
     (rf/subscribe [:permissions/current-user])])
  (fn [[article comments permissions] _]
    {:article article
     :comments comments
     :can-edit? (:edit? permissions)}))
```

The v2 form keeps the same two-function shape but changes the return contract:

```clojure
(rf/reg-sub
  :article/page
  (fn [[_ article-id]]
    [[:article/by-id article-id]
     [:comments/for-article article-id]
     :permissions/current-user])
  (fn [[article comments permissions] _]
    {:article article
     :comments comments
     :can-edit? (:edit? permissions)}))
```

That rewrite is small, but the semantics are much cleaner:

- input dependencies are data;
- dependencies are visible to tools;
- `compute-sub` can resolve them without a reactive runtime;
- ports to non-Reagent substrates do not need to accept arbitrary reaction
  objects;
- SSR and JVM tests can compute the same graph as CLJS views;
- invalid return shapes can be rejected with structured errors.

This is a deliberate compatibility tradeoff. re-frame2 should be familiar to
v1 users, but it should not preserve substrate-private escape hatches when a
data-first contract gives better correctness, tooling, and portability.

## Syntax Sugar

`:<-` remains the canonical sugar for static inputs:

```clojure
(rf/reg-sub
  :visible-items
  :<- [:items]
  :<- [:filters/current]
  (fn [[items filter] _]
    (filter-items items filter)))
```

It is equivalent in meaning to:

```clojure
(rf/reg-sub
  :visible-items
  (fn [_]
    [[:items]
     :filters/current])
  (fn [[items filter] _]
    (filter-items items filter)))
```

But authors should prefer `:<-` whenever the input list is static because it is
more readable, fully static, and easier for Xray to render before any concrete
subscription instance exists.

The input-function form is for query-vector-parametric topology:

```clojure
(rf/reg-sub
  :visible-items-for-project
  (fn [[_ project-id]]
    [[:project/items project-id]
     [:project/filter project-id]])
  (fn [[items filter] _]
    (filter-items items filter)))
```

A useful style rule:

> If the upstream query vectors can be written literally, use `:<-`. If they
> need values from the outer query vector, use an input function.

## Semantics

### Materialization

For a concrete `(subscribe [:article/page :a1])`:

1. Resolve `:article/page` in the registrar.
2. If the subscription has static `:<-` inputs, use those query vectors.
3. If the subscription has an `input-fn`, call it with `[:article/page :a1]`.
4. Normalize the returned input descriptor.
5. Subscribe to each upstream query vector in the same frame.
6. Build the derived container over the resolved upstream values.
7. Call the computation function with the projected input value and the outer
   query vector.
8. Cache the resulting node under the concrete outer query vector.

The `input-fn` is not part of the hot recompute path. It runs when a node is
created for a query vector. It reruns only when that node is discarded and later
materialized again, or when the registration is replaced and the cache entry is
rebuilt.

### Recompute

Once materialized, the subscription behaves like any other layer-2+ sub:

- upstream value changes trigger recompute;
- `=`-equal upstream values suppress body recompute;
- `=`-equal computed values suppress downstream propagation;
- disposal releases upstream subscriptions synchronously;
- hot reload invalidates affected cache entries.

The input function does not rerun merely because app-db changed. That is
intentional. A subscription node's dependency topology is fixed for the life of
that concrete query-vector cache entry.

### No app-db-dependent topology

An input function receives only `query-v`, not app-db.

App-db-dependent topology is tempting, but it creates a moving graph whose
edges can change when state changes. That makes cache invalidation, disposal,
static topology, `compute-sub`, Xray rendering, and AI explanation much harder.

If a derived value needs to branch on state, subscribe to the relevant inputs
and branch in the computation function:

```clojure
(rf/reg-sub
  :dashboard/current-panel
  :<- [:dashboard/mode]
  :<- [:dashboard/table-data]
  :<- [:dashboard/chart-data]
  (fn [[mode table chart] _]
    (case mode
      :table table
      :chart chart)))
```

That may subscribe to a little more than strictly necessary, but it keeps the
graph honest and inspectable. If the extra work is genuinely too expensive, the
feature probably needs a named state machine, a resource, or a future explicit
dynamic-topology API.

## Tooling And Observability

Static topology remains precise:

```clojure
(rf/sub-topology)
;; {:visible-items {:inputs [[:items] [:filters/current]]
;;                  ...}}
```

Parametric topology has two layers:

- the registrar can say "this sub has an input function";
- each live cache entry can say which concrete upstream query vectors were
  realized for its concrete outer query vector.

Suggested `sub-topology` shape:

```clojure
{:article/page
 {:input-kind :parametric
  :inputs :parametric
  :doc "Article page view model"}}
```

Suggested live sub-cache shape:

```clojure
{[:article/page :a1]
 {:sub-id :article/page
  :input-kind :parametric
  :realized-inputs [[:article/by-id :a1]
                    [:comments/for-article :a1]
                    [:permissions/current-user]]
  :value {...}}}
```

Xray should render:

- static `:<-` edges in the static topology panel;
- parametric nodes with a "parametric inputs" marker in static mode;
- realized input edges in live/cache mode;
- structured errors when input normalization fails.

This is better than v1 reaction-returning signal functions because the tool
does not have to reverse-engineer opaque reactions.

## Error Contract

Invalid input function behavior must signal loudly.

Errors:

- `:rf.error/reg-sub-bad-args` - registration shape is not one of the accepted
  forms.
- `:rf.error/sub-input-fn-exception` - input function throws while materializing
  a subscription.
- `:rf.error/sub-input-fn-bad-return` - input function returns a value that is
  not nil, a keyword, a query vector, a vector of query descriptors, or a map
  of query descriptors.
- `:rf.error/sub-input-fn-returned-reaction` - input function appears to return
  a substrate reaction or derefable input signal rather than subscription query
  descriptors.

Recovery should match existing subscription failure posture:

- materialize a nil-yielding reaction when recovery can continue;
- do not cache nil for unregistered sub ids;
- include the outer query vector and sub id in the trace tags;
- include the bad return class, not the bad return value, unless it passes
  normal wire elision.

Under the no-silent-swallow rule, a bad return shape cannot be silently treated
as no inputs.

## Implementation Sketch

### Registration metadata

Extend sub metadata from:

```clojure
{:handler-fn handler-fn
 :input-signals [query-v ...]}
```

to:

```clojure
{:handler-fn handler-fn
 :input-kind :db | :static | :parametric
 :input-signals [query-v ...]     ;; static only; [] for :db
 :input-fn input-fn}              ;; parametric only
```

`input-signals` can remain for compatibility with existing tooling, but
tooling should start reading `:input-kind` as the discriminant.

### Parser changes

`parse-reg-sub-args` should accept:

```clojure
(reg-sub :id handler-fn)
(reg-sub :id metadata handler-fn)
(reg-sub :id :<- query-v ... handler-fn)
(reg-sub :id metadata :<- query-v ... handler-fn)
(reg-sub :id input-fn computation-fn)
(reg-sub :id metadata input-fn computation-fn)
```

Do not accept mixed `:<-` plus input function in v1. It is expressive, but it
adds an avoidable arity matrix. If mixed static and parametric inputs are
needed, write them all in the input function.

### Input normalization

Add a pure normalizer:

```clojure
(normalize-sub-inputs returned)
;; -> {:shape :none | :single | :vector | :map
;;     :queries [query-v ...]
;;     :project (fn [values] projected-input)}
```

Validation rules:

- a bare keyword is a zero-argument query descriptor and normalizes to
  `[keyword]`;
- a query vector is a vector whose first element is a keyword;
- a vector of inputs is a vector whose elements are query descriptors;
- a map input has arbitrary keys but every value must be a query descriptor;
- the bare-keyword shorthand applies only inside descriptor positions:
  `[[:x] :y]` is two inputs; `[:x :y]` is one query vector;
- empty vector is valid and projects to `[]`;
- nil is valid and projects to nil;
- derefable/reaction-like return values are invalid.

### Reactive path

For `:parametric` subscriptions:

1. call `input-fn` with the outer query vector;
2. normalize the return value;
3. subscribe to each normalized query vector;
4. build the derived value with a computation wrapper that projects the
   resolved input values back into the requested shape;
5. store the realized inputs on the cache entry for disposal, trace, and Xray.

The existing single-input specialization should still apply after
normalization. A parametric input function returning one query vector should use
the same fixed-arity fast path as a one-input `:<-` subscription.

### `compute-sub`

`compute-sub` should use the same normalizer:

```clojure
compute-sub(query-v, db):
  reg = lookup(first query-v)

  case reg.input-kind:
    :db
      return reg.handler-fn(db, query-v)

    :static
      values = compute each reg.input-signals
      return reg.handler-fn(project-static(values), query-v)

    :parametric
      descriptor = reg.input-fn(query-v)
      norm = normalize-sub-inputs(descriptor)
      values = compute each norm.queries
      return reg.handler-fn(norm.project(values), query-v)
```

This keeps `compute-sub` as a faithful pure mirror of the runtime graph.

### `sub-topology`

`sub-topology` should become honest about non-enumerable static topology:

```clojure
{:sub/id
 {:input-kind :db | :static | :parametric
  :inputs [...] | :parametric
  :doc ...
  :ns ...
  :line ...
  :file ...}}
```

Live cache inspection can show realized parametric edges.

## Examples

### Route-parametric view model

```clojure
(rf/reg-sub
  :article/page
  (fn [[_ article-id]]
    {:article [:article/by-id article-id]
     :comments [:comments/for-article article-id]
     :viewer :viewer/current})
  (fn [{:keys [article comments viewer]} [_ article-id]]
    {:id article-id
     :title (:title article)
     :comments comments
     :can-edit? (contains? (:editable-articles viewer) article-id)}))
```

The view stays declarative:

```clojure
(let [{:keys [title comments can-edit?]}
      @(rf/subscribe [:article/page route-article-id])]
  ...)
```

### Resource projection

```clojure
(rf/reg-sub
  :resource/data
  (fn [[_ resource-id resource-key]]
    [:rf.resource/entry resource-id resource-key])
  (fn [entry _]
    (:data entry)))
```

This fits the Resource Queries EP without making resource subscriptions
perform fetches. The subscription remains passive; events and resource owners
cause resource lifecycle changes.

### Hasura row by id

```clojure
(rf/reg-sub
  :customer/view
  (fn [[_ customer-id]]
    {:customer [:hasura/customer-by-id customer-id]
     :orders [:hasura/orders-for-customer customer-id]
     :viewer :auth/viewer})
  (fn [{:keys [customer orders viewer]} _]
    {:customer customer
     :orders orders
     :can-refund? (:refund? viewer)}))
```

The input function expresses the graph. The Hasura resource layer owns HTTP,
GraphQL, websockets, stale policy, auth, and cache entries.

### Form field state

```clojure
(rf/reg-sub
  :form/field-model
  (fn [[_ form-id field-id]]
    {:value [:form/field-value form-id field-id]
     :errors [:form/field-errors form-id field-id]
     :touched? [:form/field-touched? form-id field-id]})
  (fn [{:keys [value errors touched?]} _]
    {:value value
     :errors errors
     :show-errors? (and touched? (seq errors))}))
```

This avoids a proliferation of one-off field subscriptions while keeping the
read model pure and inspectable.

## Alternatives

### A. Keep only static `:<-`

This is the current effective implementation.

Pros:

- smallest runtime;
- static topology is simple;
- no new registration shape.

Cons:

- spec/API already imply more;
- parametric read models become awkward;
- authors over-read broad app-db slices;
- views or events accumulate query planning logic;
- resource/routing/form/GraphQL use cases are noisier than they need to be;
- v1 users lose a familiar capability.

### B. Restore exact v1 signal functions

Accept signal functions that call `subscribe` and return reactions.

Pros:

- strongest source familiarity for v1 code;
- maximally flexible;
- aligns with old tutorials.

Cons:

- opaque to JVM `compute-sub`;
- hard for non-Reagent adapters;
- harder to render in Xray;
- harder to serialize, replay, or explain;
- permits side-effect-shaped behavior in subscription creation;
- reinforces a substrate-specific mental model.

This EP rejects B as the canonical re-frame2 design.

### C. Data-returning input functions

Accept the two-function form, but require the first function to return
subscription query descriptors.

Pros:

- restores the useful expressiveness;
- keeps the graph inspectable;
- keeps `compute-sub` pure;
- works across substrates;
- makes migration mechanical;
- composes with static `:<-` sugar;
- gives Xray concrete live edges.

Cons:

- not exact v1 source compatibility for signal functions that call
  `subscribe`;
- parametric topology is not fully enumerable at registration time;
- implementation needs a normalizer and more cache metadata.

This EP recommends C.

### D. New explicit `:inputs` option map

Use a new shape:

```clojure
(rf/reg-sub
  :article/page
  {:inputs (fn [[_ id]] [[:article/by-id id]])}
  (fn [article _] article))
```

Pros:

- unambiguous;
- avoids overloading the v1 two-function shape;
- room for future options.

Cons:

- less familiar to v1 users;
- conflicts with the existing metadata-map position;
- makes common parametric subs more ceremonial;
- still needs all the same runtime machinery.

This could be a future metadata extension, but it should not be the primary
surface.

## Compatibility And Migration

For v1 code:

```clojure
(rf/reg-sub
  :x
  (fn [[_ id]]
    (rf/subscribe [:y id]))
  (fn [y _]
    ...))
```

rewrite to:

```clojure
(rf/reg-sub
  :x
  (fn [[_ id]]
    [:y id])
  (fn [y _]
    ...))
```

For multiple inputs:

```clojure
(fn [[_ id]]
  [(rf/subscribe [:a id])
   (rf/subscribe [:b])])
```

rewrite to:

```clojure
(fn [[_ id]]
  [[:a id]
   :b])
```

`[:b]` is also valid and is the canonical fully-expanded form. The bare
keyword shorthand exists for zero-argument input queries because it makes the
new input function read like a compact dependency declaration rather than a
manual list of `subscribe` calls.

For static inputs, prefer `:<-`:

```clojure
(rf/reg-sub
  :x
  :<- [:a]
  :<- [:b]
  (fn [[a b] _]
    ...))
```

No compatibility shim should silently accept reaction returns. If the input
function returns a derefable/reaction-like value, re-frame2 should emit a
structured error explaining the data-returning replacement.

## Implementation Plan

1. Update `spec/API.md`, `spec/006-ReactiveSubstrate.md`, and
   `spec/008-Testing.md` to define data-returning input functions.
2. Extend `re-frame.subs/parse-reg-sub-args` with the two-function form.
3. Add input descriptor normalization and error ids.
4. Extend the reactive cache construction path for `:parametric` inputs.
5. Extend `compute-sub` to resolve `:parametric` inputs with the same
   normalizer.
6. Extend cache entries and trace tags with realized input query vectors.
7. Update `sub-topology` and Xray to distinguish static and parametric
   topology.
8. Add tests for static sugar, parametric single/vector/map inputs, invalid
   returns, thrown input functions, `compute-sub`, hot reload, disposal, and
   multi-frame behavior.
9. Update migration docs and skills with v1 rewrite examples.

## Test Plan

Core tests:

- `(reg-sub :x input-fn computation-fn)` registration succeeds.
- Input function receives the full outer query vector.
- Single query-vector return passes scalar input to computation.
- Vector return passes vector input values.
- Map return passes map input values.
- Static `:<-` behavior is unchanged.
- `compute-sub` matches `subscribe-once` for the same db and query.
- Invalid return shapes emit structured errors.
- Reaction/derefable returns are rejected.
- Input function throw emits `:rf.error/sub-input-fn-exception`.
- Hot reload invalidates existing parametric cache entries.
- Disposal releases realized upstream input subscriptions.
- Multi-frame parametric subscriptions resolve inputs in the same frame.

Tooling tests:

- `sub-topology` reports `:input-kind :parametric`.
- Live sub-cache inspection reports realized inputs.
- Xray renders parametric static nodes without pretending the full edge set is
  enumerable.

Migration tests:

- v1-like examples rewritten to data-returning input functions compute the same
  values.

## Open Decisions

1. Should map-shaped inputs be included in v1, or deferred behind vector-only
   support?

   Recommendation: include them. They are easy to specify, improve readability,
   and match the data-first direction of re-frame2.

2. Should the public docs call the first function `signal-fn` for v1
   familiarity or `input-fn` for v2 clarity?

   Recommendation: use `input-fn` in re-frame2 docs and metadata, with one
   migration note saying this is the v2 replacement for v1's signal function.

3. Should input functions receive app-db?

   Recommendation: no. Keep topology query-parametric, not state-parametric.

4. Should exact v1 reaction-returning signal functions be accepted with a
   warning?

   Recommendation: no. Pre-alpha is the time to reject the less portable shape
   and teach the data-returning replacement.

5. Should mixed `:<-` plus input function be accepted?

   Recommendation: no for v1. It adds parser and teaching complexity for little
   gain.

## Bead Plan

1. Spec reconciliation bead:
   update API, ReactiveSubstrate, Testing, Conventions, and migration docs so
   they consistently describe `:<-` and data-returning input functions.

2. Core implementation bead:
   parser, metadata, normalizer, reactive cache, compute-sub, errors, and tests.

3. Tooling bead:
   update `sub-topology`, sub-cache inspection, Xray rendering, and pair-tool
   egress shape for realized parametric inputs.

4. Docs and skills bead:
   update guide subscription chapter, v1 migration guide, setup/improver skills,
   and examples where parametric subscriptions make existing code clearer.

## Recommendation

Adopt data-returning parametric input functions.

Keep `:<-` as the static-input sugar. Add the general two-function form, but
make its first function return subscription query descriptors such as
`[[:x] :y]` instead of live signals. This captures the best part of re-frame
v1's subscription design while making it more compatible with re-frame2's
goals: substrate independence, pure testing, Xray visibility, AI-readable
topology, SSR friendliness, and a clean separation between declarative reads
and causal events.
