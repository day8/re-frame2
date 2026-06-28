# Path and canonical identity

re-frame2 has **one path algebra** and **one canonical-identity rule**, stated once, with laws every consumer inherits. Flow inputs/outputs, route params, resource cache keys, schema paths, redaction-mark paths, and work ids all sit on the same foundation — they do not each define their own path or identity semantics.

You rarely call this surface directly as an application author. You meet it through the consumers (you write a flow `:path`, a resource scope, a route `:params`), and you need to know the shared rules those declarations obey so a path or an identity behaves the way you expect.

> **Mental-model anchor.** A `:rf/path` is what you already use everywhere in re-frame2 — a plain vector like `[:cart :items 42 :qty]` passed to `get-in` / `assoc-in`. This leaf names the algebra *behind* those vectors and pins the few places it deviates from raw `get-in`/`assoc-in` (the root path `[]`, and missing-vs-present-`nil`). It does **not** introduce a new optics API you must adopt; plain vector paths stay valid and idiomatic.

## When to load

Load alongside the primary leaf when the task touches:

- A **flow** whose `:path` / `:inputs` overlap another flow's — "why is this a registration error?" (`references/fundamentals/flows.md`).
- A **resource** cache key / scope — "why does key order not matter?", "what is a valid param value?" (`patterns/resources.md`).
- A **route** param round-trip — "why is my query order changing?", "why did this param fail to match?" (`references/tooling/routing.md`).
- Any "what counts as a valid path segment / identity value?" question, or a host value (a `Date`, a function, an object) showing up where an identity is expected.

## Path shape and the segment domain

A **concrete `:rf/path`** is a vector of EDN segments focusing a value inside ordinary Clojure/EDN data:

```clojure
[]                    ;; root path — focuses the entire value
[:user]
[:cart :items 42 :qty]
```

- **The root path `[]` focuses the whole value.** This is the one place the algebra deviates from raw `assoc-in`: a conforming `put` at `[]` *replaces* the root, whereas `(assoc-in m [] x)` would associate under the key `nil`. Treat `[]` as "the entire partition / value", not "no path".
- **Canonical form is a vector.** APIs may accept any sequential collection for migration ergonomics, but every stored declaration normalizes to a vector.
- **Valid segments are portable EDN identity values**: keywords, strings, symbols, integers (in the safe-integer range below), booleans, UUIDs, instants, and `nil`. Functions, atoms, promises, DOM nodes, AbortControllers, and other host handles are **not** valid segments and are rejected at the boundary that accepts the path.
- A subsystem **may narrow** the segment domain for its surface — but only as a *stated policy* over the shared definition, never a private re-definition. Flows narrow **inputs and outputs differently**:
  - A flow's **`:inputs`** path inherits the *full* shared domain (keyword / string / symbol / boolean / integer / UUID / instant / **`nil`**); the only added policy is **non-empty** (a `[]` input would read the whole partition).
  - A flow's **output `:path`** narrows further — non-empty **and** nil-free: it **excludes `nil` segments** (a nil-keyed output is almost certainly a bug) and cannot be the `[]` root (a root output overlaps every path, clobbering the partition / making the flow a prerequisite of every other).

  SSR allowlists are another narrowing (single-segment). Every narrowing is *additive policy* applied after the shared `segment?` check, not a re-enumeration of which values are segments.

### Path operations are internal-first

The reference helpers — conceptually `get` / `lookup` / `put` / `over` / `compose` / `prefix?` / `overlap?` / `instantiate` — are **internal** today. There is no `re-frame.core` facade export. The *semantics* are normative now; the public names appear only once two or more consumers need them through the internal namespace. As an application author you use paths through the consumers (flows, resources, routes), not a public optics API. The takeaway: **path semantics are uniform**, because every subsystem uses the same shared relation rather than a private ad-hoc one.

### Missing versus present `nil`

`nil` is a valid EDN value, and an absent key is a *different fact* from a key present with value `nil`:

```clojure
(lookup {} [:page])          ;; => {:present? false}
(lookup {:page nil} [:page]) ;; => {:present? true :value nil}
```

Canonical identity preserves this distinction — `{}` and `{:page nil}` are **not** the same identity. A surface may *intentionally* elide `nil` before computing identity (routing's query printing omits a `nil` query key), but that is a per-surface policy you opt into, never the default identity rule. Resource params, for instance, get no such elision for free — a present `nil` param is part of the cache key.

## Path templates and `[:rf.path/param …]`

A **path template** is a declaration-time path with named variables. The **canonical stored shape** of a variable is the explicit data form `[:rf.path/param <name>]` (under the reserved `:rf.path/*` namespace):

```clojure
{:id      :invoice/customer-email
 :rf/path [:billing :invoices :by-id [:rf.path/param :invoice-id] :customer :email]
 :params  [:map [:invoice-id :uuid]]}
```

- The `'?name` quote-symbol spelling is **declaration-boundary sugar** only — it normalizes to `[:rf.path/param <name>]`. `'?name` never appears in any stored or serialized shape (one fact, one identity), and never shows up in a trace or Xray row.
- Instantiation is pure; an **unbound parameter fails closed** (no silent `nil` segment).
- A concrete runtime path that literally contains a symbol like `?invoice-id` is just a symbol segment — it is only substituted when processed as a template declaration.

## Path prefix and overlap

```clojure
(prefix? [:cart] [:cart :items 42])         ;; => true
(overlap? [:cart :items] [:cart :items 42]) ;; => true  (parent/child)
(overlap? [:cart :items 42] [:cart :items]) ;; => true  (symmetric)
(overlap? [:cart :items 42] [:cart :items 43]) ;; => false (siblings)
(overlap? [] [:anything])                   ;; => true  (root overlaps everything)
```

`overlap?` is true **exactly when either path is a prefix of the other**, and it is symmetric. Two paths that share a prefix but then branch (`[:x :y]` vs `[:x :z]`) do **not** overlap — they write disjoint leaves under a shared parent.

This is the relation flows use: a flow's output path `[]` is invalid (it would claim the whole partition), and two flow outputs in one frame that overlap are a **registration error**, not a silent last-write-wins. See `references/fundamentals/flows.md` for the flow-specific rules.

## Canonical EDN identity (`CEDN-1`)

**Canonical identity is a pure function over portable EDN.** It backs every equality-sensitive runtime identity: resource params and scopes, scoped resource keys, work ids, route params (after route coercion), and named path declarations. The contract: **equal facts produce the same identity across CLJ and CLJS hosts, and unsupported values fail closed.**

Canonical identity is **not** stringification. `str`, `pr-str` over an unordered map, `JSON.stringify`, and object identity are not valid identity contracts — they differ by host, leak insertion order, or depend on references.

### The `CEDN-1` domain (what is a valid identity value)

`nil`, booleans, strings, keywords, symbols; portable integers in the ECMAScript safe-integer range `[-9007199254740991, 9007199254740991]`; UUIDs and instants; and vectors, lists, maps, and sets whose nested values are themselves canonical EDN.

Key order does not affect identity — a map and the same map with keys in another order canonicalize equal:

```clojure
(= (canonical {:page 1 :tag "cljs"})
   (canonical {:tag "cljs" :page 1}))   ;; => true
```

Vectors and lists preserve element order and stay distinct; sets are unordered. Prefer **vectors** for public identity tuples (the shape resource keys, owner tokens, causes, and work ids already use).

> **CEDN-1 byte trap — host `=` over normalized EDN is not the identity.** The normative rule: *equality-sensitive comparison must be equivalent to comparing `CEDN-1` bytes* (Conventions §Canonical byte encoding). `CEDN-1` tags **kind** — vector `v[…]`, list `l(…)`, integer `i:…`, keyword `k:…`. Host `=` does **not** always agree: in CLJS `(= [1 2] '(1 2))` is `true`, yet a vector and a list are **distinct identities** under `CEDN-1`. So a lookup table keyed by host `=`/`hash` over a *normalized EDN value* can collide two facts the canonical rule keeps apart (or miss a hit on `=`-but-not-`hash`-equal types). **Do not** roll your own identity from `(= normalized-a normalized-b)` or a host hash-map keyed on raw EDN — compare as `CEDN-1` bytes (the canonical string/byte projection) or store through a wrapper whose equality *is* the canonical rule. Sorting map keys is necessary but **not sufficient** — kind-collapse still bites. Resources, work ids, and route params all route through the canonical comparison, not bare host `=`.

### Fail closed on host values

The canonicalizer **rejects by default** anything outside the domain — functions; atoms / refs / volatiles / promises / futures; DOM nodes, React elements, AbortControllers, request handles, timers; arbitrary host objects or class instances; floating-point values, ratios, decimals, `NaN`, infinities; and mutable by-reference objects. An out-of-domain value buried *anywhere* in a structure fails the **whole** identity closed (error id `:rf.error/non-edn-identity`) — never a silent host-comparison fallback.

The fix is to **encode host values into portable EDN at the boundary** before they reach an identity. Coerce a host `Date` to an EDN `#inst` instant in your resource params / route coercion; after that it is an instant *fact*, not a host object. A subsystem may choose a *smaller* input domain (resources reject date-like host values in params, demanding the coerced instant) — that is a stated narrowing, not a fork.

### Identity vs digest

The **canonical EDN value *is* the identity** everywhere — storage, work ledger, traces, replay records. A **digest** is an *optional, versioned, always-recomputable projection* for size-constrained surfaces (wire budgets, dedupe tables; the `:rf.size/include-digests?` flag is the precedent). A digest is **never** an independent identity fact, never required for correctness, and never the authoritative stored key. If you see a digest, treat it as a derived view of the real identity, not the identity itself.

## Scoped resource keys (the shape resources use)

A resource instance's identity is a **scoped resource key** — `[canonical-scope resource-id canonical-params]` — where scope and params run through the shared canonical rule, so map insertion order cannot change the key:

```clojure
(= (canonical [:rf.scope/session {:user-id "u-42" :tenant-id "acme"}])
   (canonical [:rf.scope/session {:tenant-id "acme" :user-id "u-42"}]))  ;; => true
```

Work ids build on the same identity (`[:rf.work/resource scoped-resource-key generation]`) — one fact, one canonical id. See `patterns/resources.md` for the resource-side authoring rules.

## Routes are prisms

A registered route is a lawful partial round trip (a *prism*) between URLs and route data: `match-url(route-url(…))` returns canonical route data. The route-emission rules that follow from canonical identity:

- **Deterministic query order, both directions** — `route-url` emits query keys in canonical order; a host map's iteration order never leaks into the URL string, and the same query map written two ways produces byte-identical URLs. `match-url` is symmetric: it returns the parsed `:query` map in the *same* canonical key order for an arbitrary inbound URL, so `?b=2&a=1` and `?a=1&b=2` yield one identical `:query` identity (a stable `:rf.route/query` sub value / no-op-detection key / SSR-hydration parity, independent of the inbound link's key spelling). Both prism legs share one canonical order.
- **`nil` query elision by policy** — a query param whose value is `nil` is elided before the URL is printed (so it is absent after `match-url`). `false`, `0`, and `""` are present values and round-trip.
- **Fail-closed out-of-domain params** — required path params reject `nil` / absent / empty-string. Route schemas may coerce URL strings into EDN (ints, UUIDs, enums, booleans); canonical identity applies *after* coercion. A param that cannot be represented as canonical EDN after coercion fails the match/print closed — it never invents an identity via `str` or object identity.

See `references/tooling/routing.md` for the route authoring surface.

## Cross-references

- `references/fundamentals/flows.md` — flow `:path` / `:inputs` overlap rules (the registration-error contract).
- `patterns/resources.md` — scoped resource keys, the fail-closed param/scope domain.
- `references/tooling/routing.md` — canonical route emission and fail-closed params.
- `SKILL-REDIRECT.md` → *Conventions* — the normative `:rf/path` algebra, canonical-identity rule, `CEDN-1` domain and byte encoding, path laws, and overlap relation.
- `SKILL-REDIRECT.md` → *Spec schemas* — the path / path-template / scoped-resource-key schemas.
