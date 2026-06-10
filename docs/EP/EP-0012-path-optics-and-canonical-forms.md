# EP-0012: Path Optics And Canonical Forms

Status: proposal
Type: standards-track

> Drafted from the first-principles synthesis. This EP proposes one lightweight
> path and canonical-identity vocabulary for app-db paths, runtime paths,
> flow dependencies, schema/redaction paths, resource identity, work ids, and
> route round trips.
>
> Normative home after acceptance: `spec/Conventions.md`, plus references from
> routing, schemas, flows, resources, and runtime-subsystem specs.

## Abstract

re-frame2 already depends on path-like and canonical-identity concepts across
many surfaces: `get-in` paths, flow inputs and outputs, schema paths, projection
policies, redaction paths, route params, resource cache keys, and work-ledger
ids. Those surfaces repeat rules for root paths, nil versus missing, map key
ordering, path overlap, and route round trips.

This EP defines a small shared vocabulary:

- `:rf/path` for data paths treated as lightweight optics;
- canonical EDN identity for params, scopes, work ids, and cache keys;
- route patterns as prisms with parse/print round-trip laws.

The proposal is intentionally modest. It does not require a full optics library.
It names the laws and helpers the implementation already needs.

## Motivation

Anonymous vectors are productive, but large SPAs eventually need stronger
refactoring handles. The same app-db path may appear in event handlers,
subscriptions, flows, schema declarations, privacy marks, tests, and docs.
Different subsystems should not disagree about whether `[]` means root, whether
`nil` is the same as absence, or whether two paths overlap.

Resource identity adds the same problem for params and scopes. If two maps
describe the same request, the runtime must derive the same cache key and work
id. Ad hoc stringification is not a sufficient contract.

## Goals

- Define one path vocabulary for data focus and path composition.
- Define canonical EDN identity rules for maps, params, scopes, and work ids.
- State route parse/print round-trip laws.
- Provide a place for property tests that all path consumers inherit.
- Improve AI/tooling readability by making paths named, owned, and canonical.

## Non-Goals

- This EP does not introduce a heavy optics dependency.
- This EP does not require app authors to stop using plain vectors.
- This EP does not redesign routing.
- This EP does not make every app-db path globally registered on day one.

## Relationships

- `spec/016-Resources.md` requires stable resource identity and work-ledger keys.
- EP-0006 runtime-subsystem projection policies depend on paths.
- EP-0007 one-name-per-fact is extended here to one canonical identity per path
  or params fact.
- EP-0010 and EP-0011 use canonical work ids and reply identities.
- EP-0014 derivation declarations use paths for inputs and materialized
  outputs.

## Specification

### `:rf/path`

A re-frame2 path is a vector of path segments. The empty vector `[]` focuses the
root value.

```clojure
[:billing :invoices :by-id invoice-id :customer :email]
[]
```

Path segments are compared by ClojureScript equality. Keywords, strings,
numbers, and symbols are allowed as plain segments. Specs may later define
parameter markers for path templates, but concrete runtime paths are ordinary
vectors.

### Path Operations

The implementation SHOULD define one small internal namespace with operations
equivalent to:

```clojure
(rf.path/focus value path)      ;; get the value at path
(rf.path/put value path x)      ;; set the value at path
(rf.path/over value path f)     ;; update the value at path
(rf.path/compose p q)           ;; append paths
(rf.path/prefix? p q)           ;; p is a prefix of q
(rf.path/overlap? p q)          ;; p and q can affect overlapping values
```

Public exposure is an open issue. The normative requirement is shared semantics,
not the exact namespace.

### Path Laws

For concrete paths, the helpers SHOULD satisfy:

```text
focus(put(s, p, x), p) = x
put(s, p, focus(s, p)) = s
compose(p, []) = p
compose([], p) = p
compose(compose(p, q), r) = compose(p, compose(q, r))
```

The second law is subject to the existing Clojure map behavior around absent
intermediate maps. If the implementation creates intermediate maps, that
behavior must be stated once and tested once.

### Named Path Declarations

Specs and feature modules MAY name important paths:

```clojure
{:id      :invoice/customer-email
 :rf/path [:billing :invoices :by-id '?invoice-id :customer :email]
 :owner   :billing/invoices
 :schema  :email
 :privacy :sensitive}
```

Named paths are optional in ordinary application code but useful for schema,
privacy, generated tests, refactors, and derivation graphs.

### Canonical EDN Identity

Canonical identity is a deterministic normalization of EDN values used as
resource params, scopes, work ids, route params, and cache keys.

The canonical form MUST:

- preserve the distinction between absent keys and keys with `nil` values;
- order map entries deterministically;
- preserve vector order;
- treat lists and vectors according to a stated rule rather than host
  stringification;
- reject or explicitly encode values that are not portable EDN identities, such
  as functions, atoms, DOM nodes, or JS objects.

Example:

```clojure
(canonical {:page 1 :filter {:tag "cljs" :archived? false}})
;; equivalent wherever map insertion order differed
```

This canonical form is the basis for equality-sensitive runtime ids:

```clojure
[:resource :article/list (canonical {:tag "cljs" :page 1})]
[:work :http (canonical {:method :get :url "/api/articles"})]
```

### Routing Prism Law

A route pattern is a partial isomorphism between URLs and route data. For every
route id and valid params value:

```text
match-url(route-url(route-id, params)) = {:id route-id :params (canonical params)}
```

The inverse only holds for URLs in the route's domain because URL parsing is
partial and may include redirects, defaults, or normalization.

Example:

```clojure
(route-url :article/show {:id 42 :tab "comments"})
;; => "/articles/42?tab=comments"

(match-url "/articles/42?tab=comments")
;; => {:id :article/show
;;     :params {:id 42 :tab "comments"}}
```

### Path Overlap Example

Flow outputs and user writes can use the shared path semantics:

```clojure
(rf.path/overlap? [:cart :items] [:cart :items 42 :qty])
;; => true

(rf.path/overlap? [:cart :items] [:profile :name])
;; => false
```

This allows runtime checks to reject or warn on conflicting materialized
derivations without each subsystem implementing overlap differently.

## Rationale

This EP turns repeated local conventions into one small algebra. The point is
not to make users think about optics; the point is to make the implementation,
docs, and tools stop redefining path behavior in several places.

The route prism law gives routing the same benefit: encoding and decoding drift
becomes a property-test failure rather than a user-discovered bug.

## Backwards Compatibility

Plain vector paths remain valid. Existing APIs can adopt the shared helpers
internally before exposing any new source form. Named path declarations are
additive.

Canonicalization may reveal existing ambiguous cache-key or work-id behavior.
Those fixes should be handled as targeted migration beads.

## Bead Plan / Reference Implementation

1. Add a small path helper namespace and property tests for the path laws.
2. Define canonical EDN identity in `spec/Conventions.md`.
3. Update routing tests to assert the prism round-trip law.
4. Replace ad hoc path-overlap checks in flows and projection policies with the
   shared helper.
5. Update resource/work-ledger identity docs to cite canonical EDN identity.

## Open Issues

- Should path helpers be public API or internal support for specs and tooling?
- What exact canonical ordering is used for heterogeneous map keys?
- Are path templates part of this EP or deferred until feature-module manifests
  need them?
- How strict should canonicalization be for non-EDN JS values?

## Recommendation

Adopt after review. This is a small standards-track EP because it changes a
cross-cutting contract, but the implementation is intentionally modest: one
path vocabulary, one canonical identity rule, and property tests.
