# D002 — View boundaries and call semantics

Status: **Ruled**
Ruling: **Every mounted boundary uses `v/defview` and vector-call syntax;
ordinary helpers are direct-called functions and are never vector heads.**

Horizon: **Immediate**

## Decision

The decision is which functions may create a Freehand view boundary, how a boundary is
called, and whether the same definition may also be called as an inline helper.

This decision must be made before the descriptor ABI, interpreter, compiler,
HMR registry, structural tree, and diagnostics are implemented. Those systems
all need an unambiguous answer to “is this value a mounted Freehand view, an
ordinary Clojure function, or a foreign React component?”

This is not a decision about how compilation is selected. That is settled:
Freehand is one substrate; a view is declared with `v/defview`; and its compiled
tier is selected on that declaration with `{:compiled true}`. The compiled
grammar is `:re-frame.freehand/v1`. `re-frame.ui` is an implementation donor,
not a second product whose call convention must be preserved.

## The problem

The two designs disagree on the interpreted authoring model.

The sharp model gives view and helper different declarations and different call
syntax:

```clojure
(v/defview cart-badge [_]
  [:span.badge (v/sub [:cart/count])])

(defn price-line [{:keys [label amount]}]
  [:div.price-line label ": " amount])

(v/defview header [_]
  [:header
   [cart-badge {}]                       ; mounted boundary
   (price-line {:label "Due" :amount 7})]) ; inline helper
```

Under this model:

- every vector-called internal view head resolves to a Freehand descriptor;
- a declared view is never directly callable;
- a plain function is never an internal vector head;
- a subscription in `price-line` belongs to `header`, because the helper does
  not create an ownership boundary.

The permissive model uses one ordinary function in two ways:

```clojure
(defn cart-badge [_]
  [:span.badge (v/sub [:cart/count])])

[cart-badge {}] ; mount a tracked boundary
(cart-badge {}) ; inline into the enclosing boundary
```

This makes render granularity a one-character, programmer-owned dial. It also
means that the substrate must classify a bare function differently according
to its syntactic position and recover identity, source, HMR, structural-host,
and compiled-crossing information without a declaration.

The distinction matters even if both examples render the same initial Hiccup.
A boundary owns subscriptions, event sites, memoization, errors, profiling,
connection lifetime, and evidence. An inline call owns none of those things;
its work belongs to the enclosing boundary. Changing brackets to parentheses
therefore changes runtime ownership, not merely spelling.

## Constraints already settled

- Promotion changes one definition site only. Call sites remain `[view props]`
  when a view moves between interpreted and compiled lowering.
- Compiled and interpreted parents must mount one another through the common
  descriptor ABI.
- Declared view identity is qualified and stable across HMR. Runtime generation
  and compiler signatures are internal facts.
- Props are one map; trailing children become reserved `:children`; `:key`
  selects occurrence identity and is stripped before delivery.
- The structural host must retain boundary identity rather than flattening every
  view into anonymous Hiccup.
- The absorption ruling does not preserve a bare-function or callable-view API
  merely because donor code happened to support one.

## Options

### Option A — Sharp declaration boundary

Require `v/defview` for every internal mounted boundary. Only its descriptor may
appear as an internal vector head. Use plain `defn` and parentheses for inline
helpers. Calling a `v/defview` var as a function is an authoring error.

Consequences:

- Descriptor resolution is uniform in interpreted code, compiled code, the JVM
  structural host, HMR, catalogs, tools, and AI-generated edits.
- The compiler can emit an interpreted-child boundary without inventing a
  second classification path for bare functions.
- Stable id, source, children policy, props schema, lowering, and recovery data
  exist for every boundary by construction.
- The ownership change from helper to view requires an explicit declaration,
  making a performance or lifecycle boundary visible in review.
- Every view pays one macro declaration. A programmer cannot use brackets and
  parentheses on the same definition as an instant granularity dial.
- Existing bare-function view code must be classified during migration: change
  it to `v/defview` when it owns a boundary, or leave it as a parenthesized
  helper when it intentionally inlines.

This option does not forbid small helpers or coarse rendering regions. It makes
their ownership honest in the source.

### Option B — Bare functions may be boundaries

Allow a plain `defn` to be vector-called as a tracked interpreted boundary and
parenthesis-called as an inline helper. Keep `v/defview` optional for metadata,
compiled lowering, or public/library views.

Consequences:

- Interpreted tutorial code is extremely light, and the bracket/parenthesis dial
  is convenient during performance tuning.
- A function can be reused inline without extracting a second helper.
- Bare boundaries need heuristic or runtime-derived ids and source information.
  Anonymous/HOF-produced functions have weak HMR and tool identity.
- The vector-head classifier must distinguish internal bare functions from
  qualified host components and other callable values. The JVM host needs a
  parallel answer.
- Promotion first requires converting the definition to `v/defview`; although
  call sites need not change, promotion is no longer literally one option edit.
- AI and human readers must infer whether any function is intended to own
  subscriptions and connection lifetime from every call site.
- A parenthesis call to a stateful or subscription-reading “component” silently
  shares the enclosing boundary. This is powerful but easy to do accidentally.

Warnings can reduce the identity and state hazards, but warnings do not remove
the second classification model.

### Option C — Declared views are dual-entry values

Have `v/defview` produce a value that is both a vector-head descriptor and a
directly callable function. Brackets mount; parentheses inline the view body.

Consequences:

- It preserves the one-character dial while giving every definition stable
  metadata.
- A public var now has two materially different semantics. Direct calls bypass
  its own subscription ownership, memoization, error boundary, HMR generation,
  and profiling identity.
- Compiled declarations need an interpreted direct-call body or must reject
  parentheses by lowering, breaking call-site invariance during promotion.
- Tooling and stack traces must explain why “the same view” sometimes has an
  occurrence and sometimes disappears into its caller.

This is the most magical option and creates exactly the tier-dependent surface
the one-substrate ruling is intended to remove.

## Recommendation

Choose **Option A: the sharp declaration boundary**.

The extra `v/defview` at an actual ownership boundary is small, local ceremony
that buys a single classification rule everywhere else. That is a favorable
trade for people, tools, and AI: square brackets always mean “mount this named
Freehand boundary,” while parentheses always mean “perform ordinary Clojure
work in the current boundary.” It also makes the compiled-to-interpreted emitter
crossing a descriptor operation rather than a heuristic function operation.

Do not add a region DSL to compensate for losing the one-character dial. A
plain helper already creates a deliberately coarse region. If an inline helper
later needs independent invalidation, change its declaration to `v/defview` and
its parenthesis call to a vector call. That edit appropriately exposes the new
runtime ownership.

The implementation should produce a didactic error for both common mistakes:

```text
Declared view app.cart/cart-badge was called as a function.
Use [cart-badge props] to mount it, or extract a plain defn helper to inline it.
```

```text
Plain function app.cart/cart-badge cannot be an internal vector head.
Declare it with v/defview, or call it with parentheses as an inline helper.
```

Foreign React components remain legal only through the qualified host boundary;
they are not an exception for arbitrary internal function heads.

## What the ruling must pin down

If Option A is accepted, the normative contract should state:

1. `v/defview` interns a descriptor value with private browser and structural
   entries; it does not expose its body as a public callable.
2. `[view props & children]` is the only internal boundary call.
3. `(helper args)` is ordinary Clojure execution in the current boundary.
4. A `v/sub` reached through a helper is recorded against the enclosing view.
5. Interpreted and compiled descriptors may be children in either direction.
6. The checker reports hidden markup or reads in helpers with recoveries that
   preserve this distinction: pass a value, expose finite structure, extract a
   declared child, or keep the parent interpreted.
7. The interpreter, compiled analyzer, and JVM structural host use one total
   vector-head classification order: a Freehand descriptor is an internal
   boundary; a keyword is a DOM/custom element; a declared host descriptor is a
   foreign boundary; anything else is an error naming those three legal forms.
8. Direct-calling a declared view and vector-calling a plain function remain
   typed production errors, with richer source guidance in development.

Clojure maps themselves implement `IFn`, so the documented descriptor map should
be the descriptor’s inspection/registry projection, not necessarily the literal
runtime value held by the public var. Prefer a small non-`IFn` descriptor type so
the direct-call error remains true at runtime as well as in analyzer diagnostics.

Absorption therefore changes the donor JVM emitter's callable view value into the
shared descriptor form; preserving the old callable output would reintroduce the
cross-host call mismatch this decision closes.

## Dependencies and what this unlocks

Depends on no other open UI decision. It consumes the settled one-substrate,
single-declaration ruling.

It unlocks:

- the public descriptor shape and vector-head resolver;
- compiled-to-interpreted child emission;
- HMR identity and generation fencing;
- structural boundary nodes and test queries;
- stable diagnostic wording and AI authoring rules;
- reliable per-boundary subscription, event, and performance evidence.

## Sources

- [codex-design.md — “Views and helpers”](../codex-design.md#views-and-helpers)
  and [“Descriptor ABI and cross-mode calls”](../codex-design.md#descriptor-abi-and-cross-mode-calls)
  specify the sharp convention.
- [fable-design.md §2.1 — “Dream code”](../fable-design.md#21-dream-code)
  demonstrates the bracket/parenthesis dial.
- [fable-design.md §7.1 — “Anonymous-view identity”](../fable-design.md#71-standing-wounds-and-tensions)
  prices bare-function identity, and [§8 Q2(a)](../fable-design.md#8-for-the-operator)
  records the disagreement.
- [`spec/004D-Freehand-Compiled-Grammar.md` — `ui/defview`](../../../../spec/004D-Freehand-Compiled-Grammar.md#uidefview--the-one-component-form)
  documents the donor compiler’s current declared-view form and props ABI.
