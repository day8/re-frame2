# EP-0004: Parametric Subscription Inputs

Status: final
Type: standards-track

> This EP restores a disciplined, signal-free version of re-frame v1's
> two-function `reg-sub` form: a pure `input-fn` from the outer query vector to a
> vector of input query vectors, resolved in the same frame as the outer
> subscription. Its normative home is
> [`spec/006-ReactiveSubstrate.md` §Subscription input producers](../../spec/006-ReactiveSubstrate.md#subscription-input-producers--app-db-reader-static-parametric-input-fn),
> which governs; this document is the design record behind that contract.
>
> **`final` — no open errata; shipped clean.** The contract graduated into the
> reactive-substrate spec and is governed there.

## Abstract

This proposal restores a disciplined version of re-frame v1's two-function
`reg-sub` form:

```clojure
(rf/reg-sub
  :id
  (fn input-fn [query-v]
    [[:x] [:y]])
  (fn computation-fn [[x y] query-v]
    ...))
```

The first function is no longer a v1 signal function. It is a re-frame2
`input-fn`: a pure function from the outer subscription query vector to a
vector of input query vectors. The runtime resolves those input query vectors
in the same frame as the outer subscription.

This keeps the useful v1 capability, query-parametric subscription inputs,
without carrying forward v1's reaction-returning signal functions:

```clojure
;; v1: return live signals
[(rf/subscribe [:x])
 (rf/subscribe [:y])]

;; v2: return query vectors
[[:x] [:y]]
```

The grammar is intentionally narrow:

> An `input-fn` MUST return a vector, and every element of that vector MUST be a
> query vector.

No bare keyword shorthand is accepted. No map return is accepted. No scalar
single-query return is accepted. Single-input parametric subscriptions still
return a vector of query vectors:

```clojure
(fn [[_ id]]
  [[:item/by-id id]])
```

This removes the v1 shape ambiguity entirely. `[:x :y]` is never interpreted as
an `input-fn` return. It is only valid as an element inside the returned vector:
`[[:x :y]]`.

## Motivation

The current implementation and specification disagree:

- `implementation/core/src/re_frame/subs.cljc` stores only static
  `:input-signals`; it has no general signal/input function path.
- `spec/API.md` advertises `(reg-sub id ?metadata signal-fn? computation-fn)`.
- `spec/008-Testing.md` describes a `compute-sub` branch for a function-shaped
  signal source.

That inconsistency should not be resolved by accepting v1 reaction-returning
signal functions. re-frame2 already commits to `compute-sub` as pure and
JVM-runnable: it takes an app-db value, resolves subscriptions as data, and
does not depend on Reagent reactions. A v1 signal function that returns live
reactions is incompatible with that contract.

So the query-vector-returning design is not merely cleaner. It is the only
design that fits the existing re-frame2 testing and substrate model.

Static `:<-` chains cover fixed dependency lists:

```clojure
(rf/reg-sub
  :visible-items
  :<- [:items]
  :<- [:filter]
  (fn [[items filter] _]
    (filter-items items filter)))
```

They do not cover dependencies selected from the outer query vector:

```clojure
(rf/subscribe [:article/page article-id])
```

That subscription may need `[:article/by-id article-id]`,
`[:comments/for-article article-id]`, and `[:viewer/current]`. Without
parametric inputs, authors over-read app-db, duplicate subscriptions, or move
query planning into views. All three make the signal graph less useful to
programmers, Xray, tests, and AI agents.

## Goals

- Restore the query-parametric `reg-sub` capability.
- Preserve `:<-` as the preferred syntax for static inputs.
- Require input functions to return vectors of query vectors, not live
  reactions.
- Keep `compute-sub` pure and JVM-runnable.
- Keep subscription dependencies inspectable by Xray and pair tools.
- Keep dependency topology fixed for each concrete cached query vector.
- Make v1 migration explicit, including the break for v1 map-returning signal
  functions.
- Avoid ambiguous return shapes.

## Non-Goals

- Do not restore `reg-sub-raw`.
- Do not restore exact v1 signal functions.
- Do not support bare keyword shorthand.
- Do not support map-returning `input-fn`s in the initial design.
- Do not allow ordinary input functions to choose dependencies from app-db.
- Do not make subscriptions perform fetching or other effects.
- Do not make every possible parametric edge enumerable at registration time.

## Relationships

This is a Reactive Substrate proposal that other view-model EPs build on:

- **Resolves before resource/route/Hasura subscription view-models.** This EP
  should be resolved before the [Resource Queries EP](EP-0003-resource-queries.md), the
  route-model helpers, or Hasura helpers lean on parameterized subscription view
  models, because it determines whether those helpers use static `:<-`,
  vector-of-query-vectors input functions, or broader app-db reads. Downstream
  view-model surfaces should not commit a subscription-input shape until this EP
  is resolved.
- **Composes with explicit frame target resolution.** Input query vectors are
  frame-agnostic data; the runtime resolves them in the frame of the outer
  subscription. See the [Explicit Frame Target Resolution
  EP](EP-0002-frame-target-resolution.md) and the
  [§Frame resolution](#frame-resolution) section below.

## Specification

### Accepted forms

`reg-sub` supports three input-production modes.

| Mode | Form | Meaning |
|---|---|---|
| App-db reader | `(reg-sub id computation-fn)` | No upstream subscriptions. The computation fn receives app-db and query-v. |
| Static inputs | `(reg-sub id :<- q1 :<- q2 computation-fn)` | Inputs are literal query vectors known at registration. |
| Parametric inputs | `(reg-sub id input-fn computation-fn)` | Inputs are computed from the outer query-v when a concrete cache entry is materialized. |

The unifying model is:

> A subscription has an input query-vector producer. Layer-1 has no producer,
> `:<-` is the literal producer, and `input-fn` is the query-parametric
> producer.

### Input grammar

An `input-fn` MUST return a vector of query vectors.

```clojure
query-vector := vector whose first element is a keyword
input-return := [query-vector*]
```

Examples:

```clojure
;; Multiple inputs.
[[:article/by-id article-id]
 [:comments/for-article article-id]
 [:viewer/current]]

;; Single input.
[[:article/by-id article-id]]

;; No inputs, unusual but valid.
[]
```

Rejected:

```clojure
:viewer/current                         ;; bare keyword
[:article/by-id article-id]             ;; scalar query vector
[[:article/by-id article-id] :viewer]   ;; mixed vector + bare keyword
{:article [:article/by-id article-id]}  ;; map return
```

The scalar query-vector rejection is deliberate. `[:x :y]` is ambiguous at this
boundary: it could be one query vector with argument `:y`, or it could be an
author intending two inputs. The only accepted single-query spelling is
`[[:x :y]]`.

### Parametric input function

The input function:

- receives the full outer `query-v`;
- is called when the concrete subscription node is materialized;
- returns a vector of query vectors;
- must be pure and deterministic over `query-v`;
- must not call `subscribe`, deref app-db, dispatch, mutate, or perform IO.

The computation function:

- receives a vector of resolved input values in the same order;
- receives the original outer `query-v`;
- computes the derived subscription value.

Example:

```clojure
(rf/reg-sub
  :article/page
  (fn [[_ article-id]]
    [[:article/by-id article-id]
     [:comments/for-article article-id]
     [:viewer/current]])
  (fn [[article comments viewer] [_ article-id]]
    {:id article-id
     :article article
     :comments comments
     :can-edit? (:edit? viewer)}))
```

### Static `:<-` sugar

`:<-` remains the best form when inputs are literal:

```clojure
(rf/reg-sub
  :visible-items
  :<- [:items]
  :<- [:filter]
  (fn [[items filter] _]
    (filter-items items filter)))
```

It is equivalent to a constant input producer:

```clojure
(rf/reg-sub
  :visible-items
  (fn [_] [[:items] [:filter]])
  (fn [[items filter] _]
    (filter-items items filter)))
```

That equivalence is about producing input query vectors. The existing static
`:<-` handler delivery convention is preserved: a single static `:<-` input is
delivered as the bare value, while multiple static `:<-` inputs are delivered as
a vector. Parametric `input-fn` subscriptions are different: their computation
function always receives a vector of resolved input values, even when the
`input-fn` returns exactly one query vector.

Use `:<-` for static inputs. Use `input-fn` only when the upstream query
vectors need values from the outer query vector.

### No app-db-dependent topology

This proposal revised the documented signal-function shape. Earlier
`spec/008-Testing.md` pseudocode included `(signal-fn db query-v)`; the
executed v2 contract is `(input-fn query-v)`.

The objection to `db` is not purity. A `db` value is pure and JVM-computable.
The problem is reactive-cache stability. A state-dependent edge set means a
subscription cache entry's upstream edges can change when app-db changes. That
breaks the fixed-topology-per-cache-entry invariant used by disposal, hot
reload, live topology display, and Xray explanation.

When the parameter lives in app-db, thread it through the query vector at the
call site:

```clojure
(let [article-id @(rf/subscribe [:current-route/article-id])
      page       @(rf/subscribe [:article/page article-id])]
  ...)
```

The graph is still dynamic at the view boundary, where React already handles
subscription lifecycle. Each concrete `[:article/page article-id]` cache entry
has stable dependencies for its lifetime.

### Frame resolution

Input query vectors are frame-agnostic data. The runtime resolves them in the
same frame as the outer subscription. The input function does not need a frame
argument and must not search for one.

This composes with the Explicit Frame Target Resolution EP: frame identity is
carried by the outer subscription operation, and every realized input inherits
that frame.

## Runtime Semantics

For `(subscribe [:article/page :a1])`:

1. Resolve `:article/page` in the registrar.
2. Produce input query vectors:
   - no query vectors for a layer-1 app-db reader;
   - literal query vectors for `:<-`;
   - `(input-fn [:article/page :a1])` for the parametric form.
3. Validate that the result is a vector of query vectors.
4. Subscribe to each input query vector in the same frame.
5. Call the computation function with the resolved input values and the outer
   query-v. For this parametric form, the resolved input values are delivered
   as a vector in producer order.
6. Cache the derived node under the concrete outer query-v.

The input function is not on the hot recompute path. It runs when a cache entry
is materialized, and again only if that cache entry is disposed and later
recreated or the registration is replaced.

Once materialized, the node follows ordinary layer-2+ semantics:

- upstream value changes trigger recompute;
- `=`-equal upstream values suppress body recompute;
- `=`-equal computed values suppress downstream propagation;
- disposal releases realized upstream subscriptions synchronously;
- hot reload invalidates affected cache entries.

## Error Contract

Invalid input behavior signals loudly.

| Error id | Condition |
|---|---|
| `:rf.error/reg-sub-bad-args` | Registration shape is not accepted. |
| `:rf.error/sub-input-fn-exception` | Input function throws while materializing a node. |
| `:rf.error/sub-input-fn-bad-return` | Input function returns a scalar, map, bare keyword, reaction, derefable, malformed query vector, or other invalid shape. |

Recovery follows existing subscription failure posture: emit a structured error,
materialize a nil-yielding reaction when safe, include the outer query vector
and sub id, and do not silently treat a bad input return as no inputs.

## Tooling

Static topology remains precise for `:<-`:

```clojure
{:visible-items
 {:input-kind :static
  :inputs [[:items] [:filter]]}}
```

Parametric topology is two-level:

```clojure
;; Registrar/static view
{:article/page
 {:input-kind :parametric
  :inputs :parametric}}

;; Live cache entry
{[:article/page :a1]
 {:sub-id :article/page
  :input-kind :parametric
  :realized-inputs [[:article/by-id :a1]
                    [:comments/for-article :a1]
                    [:viewer/current]]}}
```

Xray should render static edges from `:<-` in static topology views and render
realized parametric edges in live/cache views. It should not pretend that every
possible parametric edge is enumerable before concrete query vectors exist.

## Backwards Compatibility

This is intentionally breaking compared with re-frame v1.

v1 signal functions could:

- call `subscribe`;
- return a single live signal;
- return a vector of live signals;
- return a map of live signals;
- in some usages receive extra arguments beyond the outer query vector.

v2 input functions:

- must not call `subscribe`;
- must return a vector of query vectors;
- receive only the outer query vector;
- cannot return maps or bare keywords;
- cannot choose dependency topology from app-db.

The static `:<-` form remains preserved. The layer-1 `(fn [db query-v] ...)`
form remains preserved.

## Migration

### Vector-returning signal functions

v1:

```clojure
(rf/reg-sub
  :item/detail
  (fn [[_ id]]
    [(rf/subscribe [:item/by-id id])
     (rf/subscribe [:selection/current])])
  (fn [[item selected] [_ id]]
    (assoc item :selected? (= selected id))))
```

v2:

```clojure
(rf/reg-sub
  :item/detail
  (fn [[_ id]]
    [[:item/by-id id]
     [:selection/current]])
  (fn [[item selected] [_ id]]
    (assoc item :selected? (= selected id))))
```

### Map-returning signal functions

v1:

```clojure
(rf/reg-sub
  :item/detail
  (fn [[_ id]]
    {:item (rf/subscribe [:item/by-id id])
     :selected (rf/subscribe [:selection/current])})
  (fn [{:keys [item selected]} [_ id]]
    (assoc item :selected? (= selected id))))
```

v2 requires choosing an explicit input order and changing the computation
function to vector destructuring:

```clojure
(rf/reg-sub
  :item/detail
  (fn [[_ id]]
    [[:item/by-id id]
     [:selection/current]])
  (fn [[item selected] [_ id]]
    (assoc item :selected? (= selected id))))
```

Do not rely on source map iteration order when rewriting. Pick and preserve an
explicit order at the call site.

### Single input

v1:

```clojure
(rf/reg-sub
  :item/title
  (fn [[_ id]]
    (rf/subscribe [:item/by-id id]))
  (fn [item _]
    (:title item)))
```

v2:

```clojure
(rf/reg-sub
  :item/title
  (fn [[_ id]]
    [[:item/by-id id]])
  (fn [[item] _]
    (:title item)))
```

## Reference Implementation

1. Extend `parse-reg-sub-args` to recognize the two-function form.
2. Store sub metadata with an input-kind discriminator:

   ```clojure
   {:handler-fn handler-fn
    :input-kind :db | :static | :parametric
    :input-signals [...]
    :input-fn input-fn}
   ```

3. Add a pure input normalizer:

   ```clojure
   (normalize-sub-inputs input-return)
   ;; => {:queries [query-v ...]}
   ```

4. Use the normalizer in both the reactive cache path and `compute-sub`.
5. Store realized input query vectors on cache entries for disposal, trace, and
   Xray.
6. Update `sub-topology` to report `:input-kind :parametric` without pretending
   the edge set is statically enumerable.

## Test Plan

- Static `:<-` behavior remains unchanged.
- Parametric input function receives the full outer query vector.
- Parametric vector-of-query-vectors input returns resolve to vectors of input
  values, including the single-input case.
- Scalar returns such as `[:x :y]`, `:x`, maps, reactions, and derefables are
  rejected.
- `compute-sub` and `subscribe-once` agree for parametric subscriptions.
- Hot reload invalidates existing parametric cache entries.
- Disposal releases realized upstream inputs.
- Multi-frame subscriptions resolve every realized input in the outer frame.
- Xray/live sub-cache inspection reports realized parametric inputs.
- Migration examples for v1 vector, map, and single-signal returns compute the
  same values after rewrite.

## Rejected Ideas

### Exact v1 signal functions

Rejected because they return live substrate reactions. That is incompatible
with pure JVM `compute-sub`, non-Reagent substrates, SSR, and tool inspection.

### App-db-dependent input functions

Rejected because they create state-dependent edge sets inside a cache entry.
That breaks the stable-topology invariant used by disposal, hot reload, and
Xray. Thread state-derived parameters through the outer query vector instead.

### Bare keyword shorthand

Rejected because it adds a second spelling for zero-argument queries and makes
the descriptor grammar less visually obvious. The explicit spelling is `[:y]`,
and inside an input return it is `[[:y]]`.

### Scalar single-input return

Rejected because it reintroduces the `[:x :y]` ambiguity. The two-character
cost of `[[:x :y]]` buys a clear boundary between "one query with an arg" and
"the collection of input query vectors."

### Map-returning input functions

Rejected for the initial design because they introduce a second input grammar
and force additional migration choices around map key order and named
destructuring. They can be reconsidered later as an explicit extension, but the
v1 core should ship one unambiguous shape.

## Open Issues

1. Should a future extension add named inputs, or should named input grouping
   stay in user code above the subscription layer?

2. Should `sub-topology` expose the input function source coordinate or only
   the enclosing `reg-sub` source coordinate?

3. Should malformed input returns include a redacted preview in development
   traces, or only class/shape metadata?

## Bead Plan

1. Spec reconciliation:
   update API, ReactiveSubstrate, Testing, Conventions, and migration docs to
   define vector-of-query-vectors input functions and remove the old
   `(signal-fn db query-v)` branch.

2. Core implementation:
   parser, metadata, input normalizer, reactive cache path, `compute-sub`,
   error ids, and tests.

3. Tooling:
   `sub-topology`, sub-cache inspection, Xray rendering, and pair-tool egress
   for realized parametric inputs.

4. Migration skill:
   teach v1 signal-function rewrites, including vector-returning,
   map-returning, and single-signal-returning signal functions.

## Recommendation

Adopt vector-of-query-vectors parametric input functions.

This restores the part of v1 that scales: query-parametric subscription
composition. It rejects the parts that conflict with re-frame2's architecture:
reaction-returning signal functions, state-dependent hidden topology, and
ambiguous descriptor grammar. The result is a static, inspectable graph per
concrete query vector, with enough expressiveness for route, resource, form,
Hasura, and machine view models.

## Resolved Decisions

The three §Open Issues are all settled — none remains an open question. Mike's
2026-06-08 finalization accepted the implementation-shaped answers below: two
were answered **de facto by the shipped implementation**, conservatively, and
one is **deferred by design** per [§Rejected Ideas](#rejected-ideas). This
section is the binding record (mirroring the EP-0001 / EP-0002 Resolved
Decisions pattern); the §Open Issues phrasing above is read as the question each
ruling answers.

1. **Issue 3 (malformed input-fn return preview) — fail-closed off-box.**
   Decided **by the implementation**, conservatively. The raw offending value
   (`:returned`) rides **only** the dev-only trace path — `subs.cljc`
   `emit-sub-input-fn-error!` emits it through `trace/emit-error!`, which DCEs
   under `:advanced` + `goog.DEBUG=false`. The **always-on**,
   production-survivable error surface (the `:error-emit/dispatch-on-error`
   listener, axis 1) carries the **error-id** (`:rf.error/sub-input-fn-bad-return`
   / `:rf.error/sub-input-fn-exception`), the **sub-id**, the **outer query-v**,
   and the **frame** — but **NOT** the offending value. So the answer to "redacted
   preview vs class/shape metadata in production" is **neither raw nor a preview
   off-box**: the raw value never egresses in production; only a development trace
   reader (which already has full app context) ever sees it. This is the ruled
   posture — **fail-closed off-box**. A bad input return is still never silently
   treated as no inputs: the structured error fires loudly on both axes and the
   node recovers to a nil-yielding reaction.

2. **Issue 2 (input-fn source coordinate) — enclosing `reg-sub` coordinate only,
   as shipped.** `sub-topology` (in `re_frame/subs/tooling.cljc`) exposes the
   **enclosing `reg-sub` source coordinate** — `:ns` / `:line` / `:file`,
   auto-captured by the `reg-sub` macro per Spec 001 §Source-coordinate capture —
   **not** a separate `input-fn` source coordinate. The static view reports the
   `:input-kind :parametric` sentinel (the realized edge set is per-concrete-query-v
   runtime state, not statically enumerable); the realized parametric edges live in
   `sub-cache-snapshot`'s `:realized-inputs` slot. The two-level topology shape the
   §Tooling section sketches is the one shipped.

3. **Issue 1 (named / map-returning inputs) — deferred by design, not open.**
   Map-returning input functions remain **out of scope for the v1 core**, exactly
   as [§Rejected Ideas → Map-returning input functions](#rejected-ideas) records:
   they introduce a second input grammar and force migration choices around map key
   order and named destructuring, so the core ships **one unambiguous shape**
   (vector of query vectors). They are **reconsiderable later as an explicit future
   extension** — that is a deliberate deferral, not an unanswered question. Named
   input grouping stays in user code above the subscription layer until such an
   extension is proposed on its own merits.
