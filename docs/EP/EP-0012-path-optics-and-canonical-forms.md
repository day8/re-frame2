# EP-0012: Path Optics And Canonical Forms

Status: final
Type: standards-track

> **Ruling recorded 2026-06-12 (Mike, in-session).** Graduated `accepted →
> final`. The implementation is complete and merged (the `:rf/path` algebra +
> CEDN-1 canonical identity foundation, the fail-closed hardening, and the
> tier-2 consumer sweeps across routing/resources/flows), the full wave-end
> review battery passed clean (correctness, testing-coverage, code-comments,
> completeness, and a final-corrective pass), and the normative rules live in
> their home — [`spec/Conventions.md`](../../spec/Conventions.md) (the path /
> canonical-identity rules), validated end-to-end by the `re-frame.path` /
> `re-frame.identity` reference implementation. This EP is now the **rationale
> record**; where it and the spec differ, the spec governs. The one
> post-graduation item the action epic tracked — cache-key normalisation — has
> **landed**: the CEDN-1 canonical byte key-id is normative in
> [`spec/Conventions.md`](../../spec/Conventions.md) (§Canonical EDN identity),
> so the epic's remaining work is done.

> This EP defines one small path and canonical-identity algebra for app-db
> paths, runtime-db paths, flow dependencies and outputs, schema and redaction
> paths, resource identity, work ids, and routing parse/print round trips.
>
> **Ruling recorded 2026-06-11 (Mike, in-session).**
> Accepted. All six open issues are dispositioned in
> [§Open Issues](#open-issues): internal-first surface with a concrete per-op
> graduation criterion; the `[:rf.path/param …]` data form as the canonical
> template shape; the registrar kind deferred (forward-compatible with
> EP-0016's `reg-resource-scope` grammar); route data-form patterns deferred;
> canonical EDN as **the** identity everywhere with digests as optional
> recomputable projections (the storage default inverted from the original
> recommendation); and Spec 013's flow segments **widened** to the shared
> domain with two explicit flow policies retained. **Semantics are normative
> immediately**; the in-flight EP-0010/0011/0015 wave slices cite this
> definition rather than restating fragments. Sequencing (Mike ruling): the
> Conventions foundation slice + internal helpers land early; all consumer
> sweeps and conformance slices are ordered **behind** the current EP wave
> epics.
>
> Normative home after acceptance: `spec/Conventions.md`, with cross-references
> from routing, schemas, flows, resources, runtime-subsystems, and any future
> derivation/process specification.
>
> **Guide impact:** none yet (EP-0009 graduation rule). The `rf.path/*` /
> `rf.identity/*` helpers are internal-first (Open Issue 1 disposition) and not a
> public author surface, so no human-facing guide change ships with this EP; a
> guide entry is added only if/when an op graduates to public API.

## Abstract

The principle: **one path algebra and one canonical-identity rule, stated once,
with laws that every consumer inherits.**

re-frame2 already relies on the same idea in many places: a stable way to name
"the value over there" and a stable way to decide "these two identity values
are the same." Today that idea is repeated as local folklore: `get-in` paths in
event handlers, schema paths, flow inputs and outputs, route params, resource
cache keys, redaction declarations, runtime-db projection policies, and work
ledger ids each carry their own partial rules.

This EP defines one shared vocabulary:

- `:rf/path`: plain vector paths treated as lightweight optics over ordinary
  Clojure data.
- Canonical EDN identity: deterministic normalization/encoding for params,
  scopes, path declarations, resource keys, route params, and work ids.
- Route patterns as prisms: registered route patterns define a lawful partial
  round trip between URL strings and route data.

The proposal does not add a heavy optics library or make application authors
learn optics terminology. It names the algebra the framework already needs,
states the laws once, and lets every subsystem reuse those laws instead of
redefining them.

## Problem Statement

Large SPAs accumulate many references to the same facts. A single app-db slot
may be read by a subscription, written by an event, validated by a schema,
redacted by trace tooling, materialized by a flow, mentioned in documentation,
and inspected by an AI tool. A single remote read may be identified by route
params, resource params, a scope, a cache key, a work id, an HTTP request id,
and an Xray row.

When each subsystem invents its own rules, small differences become correctness
bugs:

- Does `[]` mean the root value everywhere?
- Does a key with value `nil` mean the same thing as an absent key?
- Do two map spellings with different insertion order produce the same cache
  key?
- Are `[:cart]` and `[:cart :items]` overlapping paths?
- Are route query keys printed in deterministic order?
- Does `route-url` accept the same params that `match-url` returns?
- Are JS objects, functions, atoms, promises, DOM nodes, dates, and other host
  values valid cache identities?

These are not local questions. A resource key that disagrees with route params
or a redaction path that disagrees with schema paths is a framework-level bug.
re-frame2 needs one answer.

The divergence is already observable in the CLJS reference. Four registration
surfaces accept "a path" today with four different grammars and surface-local
failure behavior:

| Surface | Accepts | On a malformed path |
|---|---|---|
| `path` interceptor (`re-frame.std-interceptors`) | varargs segments, coerced with `vec` | nothing rejected |
| `reg-app-schema` (`re-frame.schemas.storage`) | any `sequential?`, except runtime-db roots | throws `:rf.error/app-schema-bad-path` or `:rf.error/app-schema-runtime-path` |
| marks `:sensitive` / `:large` (`re-frame.marks`) | vector of path vectors with scalar segments; `[]` marks the whole value | throws `:rf.error/bad-marks` |
| flow `:inputs` / `:path` (`re-frame.flows.registry`) | non-empty vector of keyword/string/integer/symbol/boolean segments | throws a stable flow-validation error |

Even `[]` already means three things: the flow `:path` validator rejects it
(an empty output path would overlap every flow), the marks whole-value
convention requires it (`[[]]` marks the whole output, Spec 015), and raw
`assoc-in` mishandles it (see the lens-law example below). Each behavior is
locally defensible; the point is that no shared definition exists for them to
be policies *of*.

## Motivation

The pre-alpha window should be used to make the fundamental contracts elegant
before compatibility pressure hardens accidental behavior. re-frame v1 made raw
vector paths productive, and that strength should remain. The missing layer is
not a new abstraction for every call site; it is a tiny shared algebra behind
the existing vectors.

From first principles:

- A data path is a lightweight lens: it focuses a value, can put a value back,
  can update a value in place, and composes with other paths.
- A route pattern is a lightweight prism: it partially parses URLs into route
  data and prints valid route data back into URLs.
- A cache key or work id is a canonical identity: two spellings of the same EDN
  fact must collapse to one identity, and two different facts must not collapse
  accidentally.

Naming these concepts pays off without exposing their theory as user-facing
burden. Users keep writing Clojure data. The implementation, specs, tests, and
tools gain one place to enforce the rules.

## Goals

- Define `:rf/path` as the common path vocabulary for app-db and runtime-db
  focus, schema paths, redaction paths, flow inputs/outputs, projection
  policies, and future feature-module declarations.
- Keep plain vector paths valid and mechanically migratable from re-frame v1
  style code.
- Define a small set of path operations: `get`, `put`, `over`, `compose`,
  `prefix?`, and `overlap?`.
- State path laws, including root path behavior and the distinction between
  missing and present `nil`.
- Define canonical EDN identity for params, scopes, resource cache keys, work
  ids, route params/query maps, path declarations, and tool-facing identity
  values.
- Specify deterministic map key ordering and deterministic handling of sets,
  vectors, and lists in canonical identity.
- Reject or explicitly encode non-EDN host values instead of relying on host
  stringification.
- State route parse/print prism laws and the normalization points where the
  inverse is intentionally partial.
- Provide concrete Clojure/ClojureScript examples and property-test targets.
- Keep public API exposure modest: internal helper semantics are mandatory;
  public helper names may be exposed only when stable.

## Non-Goals

- This EP does not replace `get-in`, `assoc-in`, or ordinary vector paths in
  application code.
- This EP does not require all app-db paths to be globally registered.
- This EP does not introduce a full optics dependency, profunctor API, or new
  category-theory vocabulary in the public teaching surface.
- This EP does not redesign the routing subsystem or replace the existing
  string route-pattern grammar.
- This EP does not make route data-form path patterns mandatory. A data-form
  route pattern can be added later as an alternate front end to the same prism
  laws.
- This EP does not define a third-party runtime-subsystem extension API. It
  only supplies shared path/identity semantics used by such subsystems.
- This EP does not guarantee that every EDN value is a good application-level
  identity. It defines how portable EDN identity is canonicalized; schemas and
  subsystem contracts still decide which values are valid for a given slot.

## Relationships

- **EP-0007 (One Name Per Fact).** This EP is EP-0007's mechanism-level
  sibling. EP-0007 rules that every fact has one canonical *name*; this EP
  rules that every path-shaped and identity-shaped fact has one canonical
  *form* — one path algebra per fact, not one per subsystem, and one identity
  per work record (the `:work/id`-vs-`:stale-key` near-duplicate in EP-0007's
  sweep item 3 is exactly the class the canonical-identity rule forbids).
- **EP-0006 (Runtime Subsystem Contract).** Clause-4 projection/elision
  policies and the SSR allowlist-by-subsystem-child projector consume paths;
  they cite this EP's path semantics instead of restating them.
- **EP-0003 (Resource Queries) / Spec 016.** Spec 016 specifies canonical
  params and scope canonicalization for the HTTP resource scope. This EP
  supplies the shared path/identity definition that later resource transports
  and non-resource subsystems should cite instead of restating.
- **EP-0011 (Uniform Async Reply Envelope).** Reply correlation and stale
  suppression key on work ids, which are canonical EDN identities under this
  EP.
- **EP-0013 (App Values) / EP-0014 (Derivation Algebra).** Named path
  declarations are the durable handles feature manifests and derivation
  declarations would carry; both consume this EP's vocabulary without
  requiring it to change.
- **Spec homes.** `spec/Conventions.md` is the normative home after
  acceptance; `spec/012-Routing.md` (prism laws), `spec/013-Flows.md`
  (overlap), `spec/010-Schemas.md` (schema paths and digest ordering),
  `spec/015-Data-Classification.md` (marks paths), `spec/011-SSR.md`
  (hydration allowlists), and `spec/Runtime-Subsystems.md` (projection
  catalogues) cross-reference it.

## Definitions

**Path**: A vector of EDN path segments. A path focuses a value inside an
ordinary Clojure/EDN value. The empty vector `[]` focuses the root value.

**Concrete path**: A path with no template variables. Runtime reads and writes
operate on concrete paths.

**Path template**: A declaration-time path that includes named variables, such
as `'?invoice-id`, and can be instantiated into a concrete path. Path templates
are metadata for tooling and feature declarations; they are not read by
`get-in` directly.

**Segment**: One element of a path vector. Concrete segments MUST be portable
EDN identity values usable as Clojure associative keys or vector indexes.
Keywords, strings, symbols, integers, booleans, UUIDs, instants, and `nil` are
valid when the host can represent them as EDN. Functions, atoms, promises, DOM
nodes, AbortControllers, opaque JS objects, and other host handles are not
valid segments.

This is the shared upper bound, not a requirement that every subsystem accept
every segment type. A spec may deliberately narrow the domain for its public
surface — for example, Spec 016 rejects date-like host values in resource
params, and resource scope maps inherit the same canonicalization rule as
params maps — as long as it records that narrowing as a policy over the shared
definition.

**Focus**: The value selected by a path.

**Missing**: A path is missing when lookup cannot prove there is a value at the
focus. Missing is different from present with value `nil`.

**Present `nil`**: A map entry or vector slot exists and its value is `nil`.
Plain `get-in` cannot distinguish this from missing; the shared path algebra
MUST provide or internally use a presence-aware lookup where the distinction
matters.

**Canonical EDN identity**: A deterministic normalized value plus a versioned
byte encoding derived from portable EDN such that equal facts produce identical
identity and unsupported host values fail closed. This EP names the first
encoding `CEDN-1`.

**Route data**: The route id plus path params, query params, and optional
fragment represented as EDN:

```clojure
{:route-id :route/article
 :params   {:slug "welcome"}
 :query    {:tab "comments" :page 2}
 :fragment "discussion"}
```

**Route prism**: The partial isomorphism defined by a registered route pattern:
`route-url` prints valid route data into a URL, and `match-url` parses URLs in
the route's domain back into route data.

**Scoped resource key**: The resource cache identity
`[cache-scope resource-id canonical-params]`, where scope and params are
canonical EDN values.

**Work id**: A canonical EDN identity for a unit of asynchronous work, such as
`[:rf.work/resource scoped-resource-key generation]`.

## Proposed Solution

Adopt a shared path and canonical-identity contract in `spec/Conventions.md`.
Then update the subsystems that already use these concepts to cite the shared
contract instead of repeating their own local definitions.

The implementation should introduce a small internal namespace, conceptually:

```clojure
(rf.path/get value path)
(rf.path/lookup value path)
(rf.path/put value path x)
(rf.path/over value path f)
(rf.path/compose p q)
(rf.path/prefix? p q)
(rf.path/overlap? p q)

(rf.identity/canonical value)
(rf.identity/canonical-bytes value)
```

The exact namespace and public exposure are a reference-implementation choice
until this EP is accepted and implementation experience validates the names.
The semantics are standards-track. All path consumers MUST agree with them,
even if some helpers remain internal.

Existing public surfaces keep accepting raw vectors:

```clojure
(rf/reg-app-schema [:cart :items] CartItems)

(rf/reg-flow
  {:id     :cart/total
   :inputs [[:cart :items] [:pricing :discounts]]
   :path   [:cart :total]
   :output compute-total})
```

Feature authors may optionally name important paths:

```clojure
(def invoice-customer-email
  {:id      :invoice/customer-email
   :rf/path [:billing :invoices :by-id '?invoice-id :customer :email]
   :params  [:map [:invoice-id :uuid]]
   :owner   :billing/invoices
   :schema  :app/email
   :privacy #{:sensitive}})
```

Named paths make refactoring, schema extraction, redaction, flow validation,
and tool graphs easier, but they are not required for ordinary code.

## Specification

### Path Shape

A concrete `:rf/path` is a vector:

```clojure
[]
[:user]
[:cart :items 42 :qty]
[:rf.runtime/routing :current :params :slug]
```

The empty vector `[]` is the root path. It focuses the entire value:

```clojure
(rf.path/get {:a 1} [])
;; => {:a 1}

(rf.path/put {:a 1} [] {:b 2})
;; => {:b 2}
```

Concrete runtime paths MUST NOT contain functions, atoms, promises, DOM nodes,
AbortControllers, opaque JS objects, mutable host references, or values that
cannot be represented as portable EDN identity. Such values are rejected at the
boundary that accepts the path.

The primary path container is a vector. APIs MAY accept any sequential
collection for migration ergonomics, but the canonical path form is a vector
and all stored declarations MUST normalize to a vector.

### Partition-Relative Paths

This EP defines path semantics, not partition ownership. Existing subsystem
rules still decide what a path is relative to:

- `reg-app-schema` paths are app-db paths.
- Flow output `:path` values are app-db paths.
- Flow input paths are app-db paths unless their first segment is
  `:rf.db/runtime`, in which case that marker selects the runtime-db partition
  and is stripped before lookup.
- Runtime-subsystem catalogue paths are paths inside runtime-db.
- Redaction declarations derived from app-db schemas are app-db-relative paths
  stored as runtime bookkeeping.
- Marks paths declared on events, subs, flows, and cofx are relative to the
  declaring surface's value — the event arg-map, the sub or flow output, the
  injected cofx value — per Spec 015, with `[]` (the `[[]]` form) marking the
  whole value.
- Machine-body paths are `:data`-relative: per Spec 005 §Path conventions in
  machine bodies, user code never names snapshot-absolute `[:data ...]` paths;
  a body-level path operates on the destructured `:data` value.
- The SSR hydration `:payload` allowlist (Spec 011) is a vector of top-level
  app-db keys — single-segment app-db paths; the runtime-db side ships per
  the Runtime-Subsystems clause-4 allowlist-by-subsystem-child.

The shared path algebra applies after the owning spec has selected the root
value. Owning specs MAY also restrict which paths are valid for their slot
(flows reject the empty output path; SSR allowlists are single-segment), but a
restriction is a stated policy over the shared definition, never a private
re-definition of what a path means.

### Path Operations

The path algebra contains these logical operations:

```clojure
(rf.path/get value path)
;; Return the focused value, or nil when missing.

(rf.path/get value path not-found)
;; Return not-found when missing.

(rf.path/lookup value path)
;; Return {:present? true :value v} or {:present? false}.

(rf.path/put value path x)
;; Return value with x installed at path.

(rf.path/over value path f)
;; Return value with f applied to the current focused value.

(rf.path/compose p q)
;; Append two paths.

(rf.path/prefix? p q)
;; True when p is a prefix of q.

(rf.path/overlap? p q)
;; True when p and q may affect the same focused value.
```

Examples:

```clojure
(def db {:cart {:items {42 {:qty 2}}}})

(rf.path/get db [:cart :items 42 :qty])
;; => 2

(rf.path/put db [:cart :items 42 :qty] 3)
;; => {:cart {:items {42 {:qty 3}}}}

(rf.path/over db [:cart :items 42 :qty] inc)
;; => {:cart {:items {42 {:qty 3}}}}

(rf.path/compose [:cart :items] [42 :qty])
;; => [:cart :items 42 :qty]
```

`over` on a missing path calls `f` with `nil`, matching `update-in` unless a
surface explicitly provides a not-found-aware operation. A subsystem that needs
to distinguish missing from present `nil` MUST use `lookup` before deciding
what to do.

### Path Laws

For concrete paths and EDN values, conforming path helpers MUST satisfy these
laws, modulo the stated intermediate-container policy:

```text
lookup(put(s, p, x), p) = {:present? true, :value x}

if lookup(s, p) = {:present? true, :value x}
then put(s, p, x) = s

put(put(s, p, x), p, y) = put(s, p, y)

compose(p, []) = p
compose([], p) = p
compose(compose(p, q), r) = compose(p, compose(q, r))

get(s, compose(p, q), nf) = get(get(s, p), q, nf)
when lookup(s, p) is present and the focused value supports q.

over(s, p, identity) = s
when lookup(s, p) is present.

over(s, p, f) = put(s, p, f(get(s, p)))
for the public nil-on-missing get semantics.
```

The root path laws are:

```text
get(s, [], nf) = s
lookup(s, []) = {:present? true, :value s}
put(s, [], x) = x
over(s, [], f) = f(s)
overlap?([], p) = true
```

Intermediate container creation MUST be stated once. The CLJS reference should
follow Clojure's `assoc-in`/`update-in` map-creation behavior for missing map
paths: missing intermediate values are treated as maps. If a port supports
vector indexes in `put`, it MUST define out-of-range behavior explicitly and
test it; it MUST NOT silently diverge from the shared path laws.

### Missing Versus Present Nil

`nil` is a valid EDN value. An absent key and a key present with value `nil` are
different facts:

```clojure
(def a {})
(def b {:page nil})

(rf.path/lookup a [:page])
;; => {:present? false}

(rf.path/lookup b [:page])
;; => {:present? true :value nil}
```

Canonical identity MUST preserve this distinction:

```clojure
(= (rf.identity/canonical {})
   (rf.identity/canonical {:page nil}))
;; => false
```

Surfaces may intentionally elide `nil` before canonicalization, but that is a
surface-specific policy, not the canonical identity rule. Routing's query
printing policy, for example, treats `{:page nil}` as "omit the query key" for
URL construction; resource params do not get that elision for free. A resource
param key with `nil` value is a present value unless the resource schema or
caller explicitly removes it.

### Path Prefix And Overlap

For concrete paths:

```clojure
(rf.path/prefix? [:cart] [:cart :items 42])
;; => true

(rf.path/prefix? [:cart :items 42] [:cart])
;; => false

(rf.path/overlap? [:cart :items] [:cart :items 42 :qty])
;; => true

(rf.path/overlap? [:cart :items 42 :qty] [:cart :items 42])
;; => true

(rf.path/overlap? [:cart :items 42] [:cart :items 43])
;; => false

(rf.path/overlap? [] [:anything])
;; => true
```

`overlap?` is true exactly when either path is a prefix of the other. This is
the rule flows already need for dependency and output-collision checks. Flow
implementations MUST use the shared rule:

- Flow B depends on flow A when A's output path overlaps one of B's input
  paths.
- Two flow output paths in the same frame are invalid when they overlap.

Path templates need a separate `may-overlap?` relation because variables can
stand for many concrete values. That relation is useful for tooling and named
path declarations but is not required for concrete runtime flow sorting.

### Named Path Declarations

Named path declarations are data maps. This EP reserves `:rf/path` as the path
slot:

```clojure
{:id      :profile/display-name
 :rf/path [:profile :display-name]
 :owner   :profile
 :schema  :app/display-name
 :doc     "The name shown in app chrome."}
```

Path templates MAY use symbols beginning with `?` as variable markers:

```clojure
{:id      :invoice/customer-email
 :rf/path [:billing :invoices :by-id '?invoice-id :customer :email]
 :params  [:map [:invoice-id :uuid]]
 :owner   :billing/invoices
 :schema  :app/email
 :privacy #{:sensitive}}
```

Instantiation is a pure operation:

```clojure
(rf.path/instantiate
  invoice-customer-email
  {:invoice-id #uuid "11111111-1111-1111-1111-111111111111"})
;; => [:billing :invoices :by-id
;;     #uuid "11111111-1111-1111-1111-111111111111"
;;     :customer :email]
```

The template marker syntax is declaration-only. A concrete runtime path that
literally contains the symbol `?invoice-id` is just a symbol segment; it is not
implicitly substituted unless it is processed as a template declaration.

Named paths are optional for application authors, but specs and tooling SHOULD
prefer them when a path is important enough to carry ownership, schema,
privacy, projection, or derivation metadata.

### Canonical EDN Identity

Canonical identity is a pure function over portable EDN values. It is used for
all equality-sensitive runtime identities:

- resource params and scopes;
- scoped resource keys;
- work ids;
- route path/query params after route-specific parsing and normalization;
- named path declarations and path templates;
- schema digest path keys;
- any future derivation/process identity.

The function may return a normalized value, canonical bytes, or a digest over
canonical bytes. The contract is the same: equal facts produce the same
identity across CLJ/CLJS hosts, and unsupported values fail closed.

The `CEDN-1` canonical EDN domain is:

- `nil`, booleans, strings, keywords, symbols;
- portable integers in the ECMAScript safe-integer range
  `[-9007199254740991, 9007199254740991]`;
- UUIDs and instants when represented as EDN values or explicit tagged data;
- vectors, lists, maps, and sets whose nested values are canonical EDN values.

As with path segments, `CEDN-1` is the shared canonicalization domain. A
subsystem can choose a smaller input domain for public safety or portability;
that restriction must be explicit and is not a fork of the canonical encoding.

The canonicalizer MUST reject by default:

- functions;
- atoms, refs, volatile cells, promises, futures;
- DOM nodes, React elements, AbortControllers, request handles, timers;
- arbitrary JS objects or host class instances;
- floating point values, ratios, arbitrary precision decimals, NaN, and
  infinities unless a future spec explicitly encodes the numeric class;
- mutable objects whose identity is by reference rather than value.

APIs that need to carry such values MUST encode them explicitly into portable
EDN before they reach an identity boundary:

```clojure
;; Host object rejected:
(rf.identity/canonical #js {:tenant "acme"})
;; => throws :rf.error/non-edn-identity

;; Explicit EDN encoding accepted:
(rf.identity/canonical {:tenant "acme"})
;; => canonical EDN identity

;; Explicit tagged/value encoding for an instant-like fact:
(rf.identity/canonical {:at #inst "2026-06-10T00:00:00.000-00:00"})
```

Raw host dates are rejected for the same reason as other host objects. A route,
resource, or adapter boundary MAY explicitly coerce an accepted host date into
an EDN instant before canonicalization; after that coercion the value is an
instant fact, not a host object fact.

### Canonical Byte Encoding (`CEDN-1`)

`CEDN-1` is the reference byte encoding for canonical identity. It is an
internal comparison and digest format, not a display format and not a URL
format. Implementations MAY store a normalized EDN projection or a digest over
these bytes, but equality-sensitive comparisons MUST be equivalent to comparing
the `CEDN-1` bytes.

`CEDN-1` encodes a UTF-8 token stream with a type tag before every value:

| EDN value | Canonical token |
|---|---|
| `nil` | `n` |
| Boolean | `b:0` or `b:1` |
| String | `s:` plus a canonical EDN string literal over Unicode scalar values |
| Keyword | `k:` plus the canonical EDN keyword token, without auto-resolved `::` shorthand |
| Symbol | `y:` plus the canonical EDN symbol token |
| Portable integer | `i:` plus base-10 digits inside the safe-integer range, no leading `+`, no leading zero except `0` |
| UUID | `u:` plus lower-case RFC 4122 text |
| Instant | `t:` plus RFC 3339 UTC text with millisecond precision |
| Vector | `v[` elements in order `]` |
| List | `l(` elements in order `)` |
| Set | `q#{` elements sorted by their `CEDN-1` bytes `}` |
| Map | `m{` key/value pairs sorted by key `CEDN-1` bytes `}` |

Composite encodings separate adjacent element tokens, and each map key token
from its value token, with a single ASCII space. String, keyword, and symbol
encoders MUST reject names that cannot be round-tripped through portable EDN
readers on both CLJ and CLJS. Instant encoding normalizes equivalent instants
to UTC before printing; timezone text from the source literal is not identity.

The type tag is part of the bytes. That keeps distinct EDN values distinct:
the string `"42"`, the integer `42`, the keyword `:42`, the vector `[1 2]`,
and the list `(1 2)` cannot collide. Heterogeneous map keys are therefore
allowed within the supported domain: the sort key is the complete key byte
sequence, including the type tag. If a value is outside the supported domain,
the whole identity fails closed rather than falling back to host comparison.

### Map Key Canonicalization

Map entries MUST be ordered deterministically by the canonical encoding of
their keys, not by insertion order, hash-map iteration order, locale, or host
object identity. The reference rule is direct: compute each key's `CEDN-1`
bytes, sort lexicographically by those bytes, and then encode entries in that
order.

Examples:

```clojure
(= (rf.identity/canonical {:page 1 :tag "cljs"})
   (rf.identity/canonical {:tag "cljs" :page 1}))
;; => true

(= (rf.identity/canonical {:filter {:archived? false :tag "cljs"}})
   (rf.identity/canonical {:filter {:tag "cljs" :archived? false}}))
;; => true
```

Heterogeneous keys inside the `CEDN-1` domain are legal because their complete
type-tagged key bytes define the total order. If a key value falls outside that
domain, the map identity MUST fail closed rather than fall back to host
iteration or comparison.

Duplicate canonical keys are invalid. A reader or adapter that can produce a
map with duplicate keys after canonicalization MUST reject it before it becomes
a cache key, route identity, or work id.

### Sequence And Set Canonicalization

Vectors preserve vector kind and element order:

```clojure
(rf.identity/canonical [:a :b])
;; order is [:a :b], not sorted
```

Lists preserve list kind and element order. Lists and vectors are distinct EDN
facts and MUST NOT be silently collapsed unless a surface explicitly coerces
one to the other before canonicalization.

Sets are unordered EDN values. Canonical encoding MUST sort set elements by
their canonical element encoding. Sets remain distinct from vectors/lists.

Subsystems should prefer vectors for public identity tuples because vectors
are idiomatic, order-preserving, and already used for event vectors, resource
keys, owner tokens, causes, and work ids.

### Resource Identity

A scoped resource key is:

```clojure
[canonical-scope resource-id canonical-params]
```

where both scope and params use the shared canonical EDN identity rule:

```clojure
(def scope-a [:rf.scope/session {:user-id "u-42" :tenant-id "acme"}])
(def scope-b [:rf.scope/session {:tenant-id "acme" :user-id "u-42"}])

(def params-a {:slug "welcome" :include-comments? true})
(def params-b {:include-comments? true :slug "welcome"})

(= (rf.identity/canonical scope-a)
   (rf.identity/canonical scope-b))
;; => true

(= (rf.identity/canonical params-a)
   (rf.identity/canonical params-b))
;; => true

(def resource-key
  [(rf.identity/canonical scope-a)
   :article/by-slug
   (rf.identity/canonical params-a)])

resource-key
;; => [[:rf.scope/session {:tenant-id "acme" :user-id "u-42"}]
;;     :article/by-slug
;;     {:include-comments? true :slug "welcome"}]
```

The exact printed order of maps in REPL output is not the contract. The
canonical equality/bytes are the contract. Implementations may store the
normalized EDN value or a digest over canonical bytes, but Xray and debugging
tools SHOULD retain a human-readable EDN projection.

Work ids build on the same identity:

```clojure
(def work-id [:rf.work/resource resource-key 4])

(rf.identity/canonical work-id)
;; stable stale-suppression identity
```

One work record MUST have one canonical id. A subsystem MUST NOT carry a second
near-duplicate stale-suppression key that denotes the same facts under a
different head keyword unless a spec explicitly justifies the second identity.
This is EP-0007's one-name-per-fact rule applied to identity values; the
`:work/id`-versus-`:stale-key` near-duplicate (EP-0007 sweep item 3) is the
motivating instance.

### Route Prism Laws

For a registered route, `route-url` and `match-url` form a prism between valid
route data and URL strings in the route's domain.

For every route id and every valid path params, query params, and fragment:

```text
match-url(route-url(route-id, path-params, query-params, fragment))
=
{:route-id route-id
 :params   canonical-route-path-params
 :query    canonical-route-query-params-after-routing-policy
 :fragment canonical-fragment
 :validation-failed? false}
```

The route policy part matters:

- Path params are required. `nil`, absent, and empty string are rejected for
  required path segments because they cannot round-trip as path components.
- Query params with `nil` values are elided by `route-url` before the URL is
  printed. That means they are absent after `match-url`.
- Query params with `false`, `0`, and `""` are present values and must
  round-trip.
- Query defaults are applied by `match-url` according to the route's
  `:query-defaults`.
- Route schemas may coerce URL strings into EDN values such as integers,
  UUIDs, enums, or booleans. Canonical identity applies after that coercion.
- Fragments are percent-encoded by `route-url`, decoded by `match-url`, and
  normalized to `nil` when absent.

The inverse law holds only for canonical URLs in the route's domain:

```text
route-url(match-url(url)) = normalize-url(url)
```

It does not hold for every possible URL string. URL parsing is partial and
normalizing. The runtime may remove trailing slashes, decode percent escapes,
apply query defaults, reorder query keys, reject malformed encodings, route
unknown URLs to `:rf.route/not-found`, and apply redirect/guard behavior at the
event layer. Those are not prism-law violations; they are the reason the
inverse is restricted to the canonical emitted URL domain.

Route query printing MUST be deterministic. Query keys emitted by `route-url`
MUST be ordered by canonical EDN key order after route-specific coercion and
nil elision. A hash-map's iteration order MUST NOT leak into URL strings.

Example:

```clojure
(rf/reg-route :route/article
  {:path   "/articles/:slug"
   :params [:map [:slug :string]]
   :query  [:map
            [:tab {:optional true} :string]
            [:page {:optional true} :int]]})

(rf/route-url
  :route/article
  {:slug "welcome"}
  {:page 2 :tab "comments"}
  "discussion")
;; => "/articles/welcome?page=2&tab=comments#discussion"

(rf/match-url "/articles/welcome?page=2&tab=comments#discussion")
;; => {:route-id :route/article
;;     :params {:slug "welcome"}
;;     :query {:page 2 :tab "comments"}
;;     :fragment "discussion"
;;     :validation-failed? false}
```

The same route with query params in a different map insertion order prints the
same URL:

```clojure
(= (rf/route-url :route/article {:slug "welcome"}
                 {:tab "comments" :page 2})
   (rf/route-url :route/article {:slug "welcome"}
                 {:page 2 :tab "comments"}))
;; => true
```

### Route Params As Canonical Identity

Route path params and query params are identity-bearing data. Route handlers,
route-owned resources, SSR preload, and Xray route graphs should all see the
same canonical params:

```clojure
(let [route (rf/match-url "/articles/welcome?page=2&tab=comments")]
  {:resource :article/by-slug
   :params   (select-keys (:params route) [:slug])
   :scope    [:rf.scope/session {:tenant-id "acme" :user-id "u-42"}]})
;; route resource planning consumes canonical route data
```

If a route param value cannot be represented as canonical EDN after schema
coercion, route matching or URL printing MUST fail closed at the relevant
boundary. It MUST NOT use host `str`, JS object stringification, or object
identity to invent a cache or route identity.

### Internal And Public Helper Surface

The shared semantics are mandatory. Public API exposure is deliberately
smaller:

- The reference implementation MUST have one internal path helper module used
  by flows, schemas, routing, resources, runtime projection/elision, and tests.
- The reference implementation MUST have one internal canonical identity module
  used by resources, routing, work ledger, schema digest, and tests.
- A public helper surface SHOULD be exposed only after names and edge behavior
  are stable. If exposed, it SHOULD use simple names equivalent to
  `rf.path/get`, `rf.path/put`, `rf.path/over`, `rf.path/compose`,
  `rf.path/prefix?`, `rf.path/overlap?`, and `rf.identity/canonical`.
- Public helpers MUST obey the same laws as internal helpers; there is no
  "tool-only" path semantics.
- Subsystems MUST NOT keep private ad hoc overlap, canonicalization, or route
  round-trip logic once the shared helpers exist.

## Examples

### One Logical Path, Four Surfaces Today

The same logical fact — "the invoices slice of app-db" — is named by a schema
registration, a flow input, and a path interceptor, while a marks declaration
focuses inside the values it produces. Four surfaces, four subtly different
path semantics:

```clojure
;; 1. Schema registration: any sequential? accepted except runtime-db
;;    roots; malformed shapes throw :rf.error/app-schema-bad-path.
(rf/reg-app-schema [:billing :invoices] Invoices)

;; 2. Marks declaration (output-relative): a vector of path vectors
;;    with scalar segments; malformed entries throw :rf.error/bad-marks.
(rf/reg-sub :billing/invoice
  {:sensitive [[:customer :email]]}
  (fn [db [_ id]] (get-in db [:billing :invoices :by-id id])))

;; 3. Flow output: non-empty vector of scalar keys; [] rejected,
;;    overlap checked against sibling flows with a private prefix test.
(rf/reg-flow
  {:id     :billing/invoice-count
   :inputs [[:billing :invoices]]
   :path   [:billing :invoice-count]
   :output (fn [{:keys [invoices]}] (count invoices))})

;; 4. Path interceptor: varargs segments, not a vector.
(rf/reg-event-db :invoice/clear
  {:interceptors [(path :billing :invoices)]}
  (fn [_ _] {}))
```

Under this EP the four surfaces keep their local path shapes, but all four mean
the same thing by "path": each registration boundary normalizes to a canonical
`:rf/path` vector, malformed paths fail loudly through the shared path error
vocabulary instead of surface-local error families, and the flow overlap check
is the shared `overlap?` relation rather than a private one. Root selection
stays with the owning surface (app-db for the schema/flow/interceptor, the
sub's output for the marks declaration) per §Partition-Relative Paths.

### Raw Vector Paths

Existing re-frame style remains valid:

```clojure
(defn rename-customer [db invoice-id email]
  (assoc-in db
            [:billing :invoices :by-id invoice-id :customer :email]
            email))

(defn customer-email [db invoice-id]
  (get-in db [:billing :invoices :by-id invoice-id :customer :email]))
```

The same code expressed through the path helpers:

```clojure
(defn rename-customer [db invoice-id email]
  (rf.path/put db
               [:billing :invoices :by-id invoice-id :customer :email]
               email))

(defn customer-email [db invoice-id]
  (rf.path/get db
               [:billing :invoices :by-id invoice-id :customer :email]))
```

### Named Path Declaration

```clojure
(def customer-email-path
  {:id      :invoice/customer-email
   :rf/path [:billing :invoices :by-id '?invoice-id :customer :email]
   :params  [:map [:invoice-id :uuid]]
   :owner   :billing/invoices
   :schema  :app/email
   :privacy #{:sensitive}})

(defn customer-email [db invoice-id]
  (rf.path/get db
               (rf.path/instantiate customer-email-path
                 {:invoice-id invoice-id})))
```

The declaration can feed schema registration and privacy marks:

```clojure
(rf/reg-app-schema
  (rf.path/instantiate customer-email-path {:invoice-id invoice-id})
  :app/email)

;; A future feature manifest can carry the declaration once and let
;; schema/redaction/tooling consumers derive their own indexes from it.
```

### Get, Put, Over, Compose

```clojure
(def base {:cart {:items {42 {:qty 1 :price 10}}}})

(def item-path (rf.path/compose [:cart :items] [42]))
(def qty-path  (rf.path/compose item-path [:qty]))

(rf.path/get base qty-path)
;; => 1

(rf.path/put base qty-path 2)
;; => {:cart {:items {42 {:qty 2 :price 10}}}}

(rf.path/over base qty-path #(+ % 4))
;; => {:cart {:items {42 {:qty 5 :price 10}}}}
```

The law is about the resulting value. Implementations may add convenience
arities, but the unary function form is sufficient.

### What The Path Laws Catch

The laws are not decorative; obvious implementations violate them. Clojure's
raw `assoc-in` fails the root-path law — with an empty path it assoc's under
the key `nil` instead of replacing the root:

```clojure
(assoc-in {:a 1} [] {:b 2})
;; => {:a 1, nil {:b 2}}     ; not {:b 2}

(get-in (assoc-in {:a 1} [] {:b 2}) [])
;; => {:a 1, nil {:b 2}}     ; put-get violated at p = []
```

A `rf.path/put` that simply delegates to `assoc-in` is therefore
non-conforming, and the generative law test
`lookup(put(s, p, x), p) = {:present? true, :value x}` finds it as soon as
the path generator emits `[]`. The required behavior is
`put(s, [], x) = x`.

A second realistic violation: an implementation that "optimizes" by scrubbing
`nil` writes (`dissoc` instead of storing `nil`) breaks the same law at
`x = nil`:

```clojure
;; non-conforming put that drops nil writes:
(lookup (put {:page 1} [:page] nil) [:page])
;; => {:present? false}      ; law requires {:present? true :value nil}
```

That is exactly the missing-versus-present-`nil` ambiguity from the problem
statement, caught structurally instead of in a debugging session.

### Path Overlap Checks

```clojure
(rf.path/overlap? [:cart :items] [:cart :items 42 :qty])
;; => true

(rf.path/overlap? [:cart :items 42 :qty] [:cart :items 42])
;; => true

(rf.path/overlap? [:cart :items 42] [:cart :items 43])
;; => false

(rf.path/overlap? [:cart :items] [:profile :display-name])
;; => false

(rf.path/overlap? [] [:profile :display-name])
;; => true
```

Flow registration can use the same helper:

```clojure
(defn output-collision? [existing-flow new-flow]
  (rf.path/overlap? (:path existing-flow) (:path new-flow)))
```

### Canonical Params, Scopes, And Work Ids

```clojure
(def params-1 {:slug "welcome" :page 1})
(def params-2 {:page 1 :slug "welcome"})

(= (rf.identity/canonical params-1)
   (rf.identity/canonical params-2))
;; => true

(def scope-1 [:rf.scope/session {:tenant-id "acme" :user-id "u-42"}])
(def scope-2 [:rf.scope/session {:user-id "u-42" :tenant-id "acme"}])

(= (rf.identity/canonical scope-1)
   (rf.identity/canonical scope-2))
;; => true

(def resource-key
  [(rf.identity/canonical scope-1)
   :article/list
   (rf.identity/canonical params-1)])

(def work-id [:rf.work/resource resource-key 7])

(rf.identity/canonical work-id)
;; stable across map insertion order and host map iteration
```

Present `nil` remains distinct:

```clojure
(= (rf.identity/canonical {:page nil})
   (rf.identity/canonical {}))
;; => false
```

Non-EDN values are rejected:

```clojure
(rf.identity/canonical {:node js/document.body})
;; => throws :rf.error/non-edn-identity
```

### Route URL Parse/Print Round Trip

```clojure
(rf/reg-route :route/search
  {:path  "/search"
   :query [:map
           [:q :string]
           [:page {:optional true} :int]
           [:archived? {:optional true} :boolean]]})

(def url
  (rf/route-url :route/search
                {}
                {:archived? false
                 :q "clojure data"
                 :page 2}))

url
;; => "/search?archived%3F=false&page=2&q=clojure%20data"

(rf/match-url url)
;; => {:route-id :route/search
;;     :params {}
;;     :query {:archived? false :page 2 :q "clojure data"}
;;     :fragment nil
;;     :validation-failed? false}
```

The exact query-key encoding is owned by routing, but the ordering is
deterministic and the parse result is canonical route data.

### Route-Owned Resource Cache Key

```clojure
(rf/reg-route :route/article
  {:path "/articles/:slug"
   :params [:map [:slug :string]]
   :resources
   [{:resource  :article/by-slug
     :params    (fn [route] (select-keys (:params route) [:slug]))
     :scope     (fn [_route ctx]
                  [:rf.scope/session
                   {:tenant-id (:tenant-id ctx)
                    :user-id   (:user-id ctx)}])
     :blocking? true}]})

(let [route  (rf/match-url "/articles/welcome")
      scope  [:rf.scope/session {:user-id "u-42" :tenant-id "acme"}]
      params (select-keys (:params route) [:slug])]
  [(rf.identity/canonical scope)
   :article/by-slug
   (rf.identity/canonical params)])
;; => canonical scoped resource key
```

The route, resource cache, work ledger, SSR preload, and Xray row all point at
the same canonical fact.

## Rationale

### Plain Vectors Are Still The Right User Primitive

Vector paths are idiomatic Clojure, mechanically migratable from re-frame v1,
easy to print, easy to compare, and already present across the codebase. A
heavier public optics API would make simple code worse. The right design is to
keep vectors and give them one precise meaning.

### Lightweight Optics Give The Implementation Laws

The framework already depends on lens-like behavior. `get-in`, `assoc-in`, the
`path` interceptor, flow materialization, schema validation, and redaction all
focus part of a value and reason about updates. Stating path laws makes bugs
testable. It also gives tools and AI agents a durable contract: a path is not
just a vector-shaped hint; it is a lawful focus.

### Canonical Identity Is Not Stringification

`str`, `pr-str` over unordered host maps, JSON.stringify over JS objects, and
object identity are not valid framework identity contracts. They differ by
host, leak insertion order, or depend on object references. Resource caches and
work ledgers need an identity that survives SSR, hydration, replay, Xray
inspection, and multi-host conformance.

Canonical EDN is the smallest Clojure-native answer. It preserves ordinary data
as data, rejects host handles at identity boundaries, and gives non-Clojure
ports a concrete target.

### Routes Are Prisms, Not Just String Helpers

Routing is often where identity drift becomes visible. The app prints a URL,
the browser or server parses it later, and route-owned resource preloading
depends on the result. Treating route patterns as prisms makes the round-trip a
law instead of an aspiration. Encoding, query ordering, defaults, nil policy,
and schema coercion become property-test inputs.

### One Algebra Beats Repeated Subsystem Folklore

Flows, schemas, routing, resources, runtime projection, redaction, and future
feature manifests all need the same operations. Local implementations invite
nearly-identical edge behavior that diverges under pressure. A shared algebra
is simpler than many small explanations.

This is EP-0007 carried from the vocabulary level to the mechanism level.
EP-0007 forbids a second spelling for one fact; this EP forbids a second
semantics for one mechanism. Both make the defect class a named violation
instead of a per-review judgment call.

## Alternatives Considered

### Leave Paths As Informal Vectors

This preserves maximum short-term familiarity, but it leaves every subsystem to
rediscover root-path behavior, missing-vs-nil behavior, overlap, and canonical
ordering. The current specs already show the repetition. Large-SPA correctness
requires one answer.

### Introduce A Full Optics Library

Rejected for the core contract. A full optics library is more power than the
framework needs and would leak unnecessary terminology into the user API. The
implementation needs a tiny lens-like subset: get, put, over, compose, prefix,
and overlap. Ports can implement that directly.

### Make Named Paths Mandatory

Rejected. Mandatory global path registration would harm migration, small apps,
and exploratory development. Named paths are valuable when a path is important
enough to carry ownership, schema, privacy, or feature metadata. Raw vectors
remain valid.

### Use JSON Canonicalization

Rejected as the primary contract. JSON cannot represent keywords, symbols,
sets, UUIDs, instants, or EDN lists without additional tagging, and it blurs
important Clojure distinctions. A port may encode canonical EDN into JSON-like
bytes internally, but the semantic contract is canonical EDN identity.

### Let Each Subsystem Define Its Own Canonicalization

Rejected. Resources, routing, schemas, and work ids cross-reference each other.
If they use different canonicalization rules, the framework can produce two
identities for one fact or one identity for two facts. The whole point of this
EP is to prevent that.

### Coerce Lists To Vectors

Rejected as a canonical identity default. Lists and vectors are distinct EDN
values. A surface that wants vectors may coerce before canonicalization, but
canonical identity should not silently change EDN types.

### Preserve Map Insertion Order In URLs And Cache Keys

Rejected. Insertion order is not identity. Query strings and cache keys must be
deterministic for the same logical data regardless of how a map was built.

## Backwards Compatibility and Migration

Plain vector paths remain valid. This is the main compatibility property:

```clojure
[:user :name]
[:cart :items item-id :qty]
[]
```

Existing re-frame v1 and re-frame2 code using `get-in`, `assoc-in`, the `path`
interceptor, `reg-app-schema` paths, and flow paths can migrate mechanically:

- Leave ordinary vector paths in place.
- Normalize sequential path inputs to vectors at registration boundaries.
- Replace private path-overlap checks with the shared helper.
- Add named path declarations only where ownership, schema, privacy,
  projection, or feature-manifest metadata needs a durable handle.
- Convert ad hoc cache-key stringification to canonical EDN identity.
- Reject or explicitly encode host values that were previously smuggled into
  params, scopes, or work ids.

Some behavior changes are intentional:

- Maps with different insertion order now produce the same identity.
- A present `nil` key and an absent key are distinct unless a surface
  explicitly elides `nil` before canonicalization.
- Route query string output may become more deterministic and therefore differ
  in key order from older tests.
- Non-EDN params/scopes that previously happened to stringify will fail closed.

These are acceptable pre-alpha cleanups. v1 compatibility is desirable where
it preserves correct design; it must not freeze accidental identity behavior.

Migration tooling should classify issues:

- **Mechanical**: convert sequential paths to vectors, replace private overlap
  predicates, update route URL tests for deterministic query order.
- **Review required**: params or scopes containing non-EDN host values; cache
  keys built from strings; code that relies on missing and present `nil` being
  indistinguishable.
- **Optional improvement**: introduce named path declarations for feature-owned
  state slices and sensitive/large schema paths.

## Reference Implementation / Bead Plan

1. Add an internal path helper namespace with `get`, `lookup`, `put`, `over`,
   `compose`, `prefix?`, and `overlap?`.
2. Add an internal canonical identity namespace with canonical EDN normalization
   and canonical byte/string encoding.
3. Document `:rf/path` and canonical EDN identity in `spec/Conventions.md`.
4. Update `spec/012-Routing.md` to cite the route prism laws, deterministic
   query key ordering, and canonical params identity.
5. Update `spec/013-Flows.md` to cite shared `overlap?` and `prefix?` for
   dependency and output-collision checks.
6. Update `spec/010-Schemas.md` and `spec/015-Data-Classification.md` to cite
   `:rf/path` for app-db schema paths, marks declarations, and derived
   redaction declarations — including mapping the marks surface's
   marks-specific path validation onto the shared path error vocabulary.
7. Update `spec/016-Resources.md` to replace local canonicalization prose with
   the shared canonical EDN identity rule.
8. Update `spec/Runtime-Subsystems.md` to cite shared path semantics for
   subtree catalogues, projection allowlists, and redaction paths.
9. Add property tests and cross-host fixtures for path laws, canonical identity,
   route prism laws, and resource/work-id identity.
10. Add migration documentation showing how v1 vector paths remain valid and
    where optional named declarations help.

## Validation / Conformance

Conforming implementations SHOULD validate this EP with property tests and
small cross-host fixtures.

Path conformance:

- Generate EDN trees and concrete paths.
- Check root path laws.
- Check get/put/put-put/over/compose laws.
- Check missing versus present `nil` via `lookup`.
- Check `prefix?` and `overlap?` are deterministic, symmetric where
  appropriate, and treat `[]` as overlapping every path.
- Check flow output collisions use the same `overlap?` relation.

Canonical identity conformance:

- Generate maps with permuted insertion order and assert equal canonical
  identity.
- Generate nested maps, sets, vectors, and lists and assert deterministic
  canonical bytes.
- Assert vectors/lists/sets remain distinct where EDN distinguishes them.
- Assert present `nil` and absent keys are distinct.
- Assert unsupported host values fail closed with structured errors.
- Pin fixtures for heterogeneous map keys that the canonical encoder supports.

Route prism conformance:

- For each registered route, generate valid path params/query params from the
  route schemas and assert `match-url(route-url(...))` returns canonical route
  data.
- Assert `false`, `0`, and `""` query values round-trip.
- Assert `nil`, absent, and empty-string path params fail on print.
- Assert query `nil` elision is reflected in the parsed route data.
- Assert query key ordering is deterministic.
- Assert percent encoding/decoding is symmetric for path params, query values,
  and fragments.
- Assert the parse-print inverse only for URLs in the canonical emitted domain;
  non-canonical inputs may normalize.

Resource/work identity conformance:

- Assert resource keys are invariant under scope and params map insertion
  order.
- Assert different scopes with the same params do not collide.
- Assert work ids embed the canonical scoped resource key and generation.
- Assert stale suppression keys on the canonical work id, not a second
  near-duplicate identity.

Public/internal conformance:

- If helpers are public, public helper tests MUST be the same law tests used by
  internal consumers.
- If helpers remain internal, subsystem tests MUST still prove they share the
  same behavior.

## Open Issues

**All six issues were ruled 2026-06-11 (Mike, in-session).**
Original recommendations are kept verbatim as the record of what was ruled;
dispositions and riders are inline.

1. Should `rf.path/*` and `rf.identity/*` be public v1 API, or internal support
   with only the semantics documented publicly?
   **Recommendation:** internal-first. The semantics are normative immediately;
   the public names graduate only after the flows/schemas/routing/resources
   consumers have proven them, per §Internal And Public Helper Surface.
   **Disposition: as recommended, with the graduation gate made concrete:** an
   op graduates to public API when **two or more consumers** use it through the
   internal namespace without requiring shape changes. The consumer list now
   includes EP-0015's frame-config path maps and EP-0016's map-form targets
   alongside flows/schemas/routing/resources. The facade-export classification
   rule applies to each name at its graduation.
2. Should path templates reserve only `'?name` symbols, or should they use an
   explicit data form such as `[:rf.path/param :invoice-id]` to avoid any chance
   of confusing a literal symbol segment with a template variable?
   **Recommendation:** make the explicit data form the canonical stored shape
   and treat `'?name` as declaration-boundary sugar normalized into it. That
   removes the literal-symbol ambiguity this EP itself has to caveat.
   **Disposition: as recommended, with two riders:** the data form is what
   CEDN-1 encodes and what traces/Xray display — `'?name` never appears in any
   stored or serialized shape (one fact, one identity); and EP-0015's
   frame-config path maps accept **concrete paths only** (no templates), a
   stated narrowing per §Partition-Relative Paths.
3. Should named path declarations live in a registrar kind, a future feature
   manifest, or both?
   **Recommendation:** defer the registrar kind. Reserve the declaration shape
   now; let EP-0013/EP-0014 decide the home when a consumer needs runtime
   lookup. A registrar kind minted before its consumer is speculative surface.
   **Disposition: as recommended, with forward-compatibility pinned:** EP-0016's
   `reg-resource-scope` (named, registered, declared-inputs resolver) is the
   first live instance of the named-declaration pattern — the reserved
   declaration shape must stay compatible with its `{:inputs … :resolve …}`
   grammar so a later generalization is a relocation, not a redesign. The home
   is decided by **whichever of EP-0013/EP-0014 is accepted first, or a
   dedicated ruling if neither** — no dependency on unaccepted proposals.
4. Should route data-form path patterns graduate with this EP or remain a later
   additive front end to the same route prism laws?
   **Recommendation:** remain later, per Non-Goals. The prism laws are
   front-end-agnostic by construction.
   **Disposition: as recommended.** Rider: when the data front end does come,
   it MUST normalize into issue 2's canonical template shape — a route pattern
   is a path template over segments, and a second template grammar would be the
   per-subsystem redefinition this EP exists to prevent. No route API redesign
   rides this EP.
5. Should canonical identity expose stable human-readable strings, digests, or
   both? Debugging favors readable EDN; storage and lookup may favor bytes or
   digests.
   **Recommendation:** both, with roles fixed: the normalized EDN value is the
   tool/debugging projection (Xray rows stay readable), the digest is the
   storage/lookup key, and the digest is always derived from the normalized
   value — one fact, one identity, two encodings.
   **Disposition: both — but with the storage default INVERTED from the
   recommendation.** Canonical EDN is **the** identity everywhere — storage,
   work ledger, traces, epoch/replay records — exactly as shipped in final
   Spec 016 and now load-bearing as EP-0010 causal replay material. Digests
   are an **optional, versioned, always-recomputable projection** for
   size-constrained surfaces (wire budgets, dedupe tables — the existing
   `:rf.size/include-digests?` flag is the precedent, and the MB-scale wire
   investigation is the live consumer). A digest is never an independent
   identity fact, never required for correctness, and never the authoritative
   stored key in v1 (Runtime-Subsystems derived rule 2: one authoritative home
   per fact; mirrors are recomputable projections).
6. The flow path validator today restricts segments to
   keyword/string/integer/symbol/boolean — narrower than this EP's segment
   domain (no UUID, instant, or `nil` segments), even though UUID-keyed entity
   paths are a natural concrete shape.
   **Recommendation:** keep subsystem narrowing legal but explicit: Spec 013
   either widens flow segments to the shared domain or records its restriction
   as a stated policy over the shared definition (per §Partition-Relative
   Paths), so the divergence is a documented decision rather than residue.
   **Disposition: the direction is ruled — WIDEN.** Spec 013 widens flow
   segments to the shared domain (UUIDs and instants admitted; UUID-keyed
   entity paths are the natural materialization target, and no design reason
   for the restriction was ever produced — residue is not laundered into
   "documented decision" without a decision). Two explicit flow policies are
   retained as stated narrowings with rationale: **`nil` segments are excluded
   for flow outputs** (a nil-keyed output is almost certainly a bug), and **a
   flow output path cannot be `[]`** (the root path overlaps everything — a
   root output would clobber the entire partition).

## Recommendation

Adopt this EP as a standards-track proposal. It is small in implementation
surface but high leverage: one path vocabulary, one canonical EDN identity
rule, and one route prism law replace repeated subsystem-specific conventions.

The design keeps re-frame's practical vector-path ergonomics, remains
mechanically migratable from v1 codebases, and gives large SPAs the stronger
contract they need: paths and identities are ordinary values with laws.
