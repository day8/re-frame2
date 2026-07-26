# D012 — Declared reads and evidence levels

Status: **Ruled**
Ruling: **There is no `:reads` declaration in v1; every evidence projection
states scope, basis, completeness, and loss.**

Horizon: **Upcoming**

## Decision

The decision is whether Freehand should add an author-facing declaration of subscription
reads, and define how tools distinguish static proof, author declaration, runtime
observation, opaque host behavior, and capped evidence.

These are related but separable decisions. The evidence vocabulary is needed by
the initial tool surface. A new `:reads` authoring form can wait until the core
inline `v/sub` model and compiled grammar are working.

The settled absorption ruling does not answer this question. The absorbed
ViewCell and compiler already provide dynamic captures and finite compiled sites;
`re-frame.ui`’s deletion neither requires nor forbids a declarative read layer.

## The problem

The paved Freehand form makes a read at the point of use:

```clojure
(v/defview todo-row [{:keys [id]}]
  (let [todo     (v/sub [:todo/by-id id])
        editing? (v/sub [:todo.ui/editing? id])]
    [:li {:class {:editing editing?}}
     (:title todo)]))
```

This is ergonomic, supports conditional and chained queries with ordinary
Clojure, and lets the selected commit record exactly what the view read. In an
interpreted view, however, the runtime cannot know every possible read before
executing all paths. Its evidence is exact for a committed render but not a
static upper bound for the program.

The Fable design proposes an optional declared form:

```clojure
(v/defview todo-row
  {:props [:map [:id :any]]
   :reads {:todo     [:todo/by-id ?id]
           :editing? [:todo.ui/editing? ?id]}}
  [{:keys [id todo editing?]}]
  [:li {:class {:editing editing?}}
   (:title todo)])
```

This turns query templates into descriptor data and injects their resolved
values into the view’s inputs. It can provide a static manifest in interpreted
mode and make compilation straightforward. It also introduces a placeholder
language, aliasing rules, conditional-read questions, error behavior, and a
second way to obtain subscription values.

The decision is not whether reads should be inspectable. They must be. It is
whether static declaration earns enough leverage to join the authoring language,
and how honestly every kind of evidence is labeled meanwhile.

## Constraints already settled

- Keep `v/sub`; Freehand does not become a whole-app-state renderer or require
  prop-drilling of the database.
- A `v/sub` in an active boundary returns a stabilized value and records its
  exact resolved target in that candidate render.
- The selected commit publishes dependencies, event sites, and tree evidence
  atomically. Abandoned renders publish nothing.
- Compiled reads are finite lexical sites and can have a statically complete
  possible-site manifest.
- Interpreted reads may follow arbitrary same-thread Clojure control flow.
- A declared host is intentionally opaque about the registered React
  implementation's private instances and hooks, while still exposing public
  identity/config/intents.
- Tooling must state evidence loss rather than silently truncate it.

## Evidence has both scope and basis

A single ranking such as “proven > declared > observed” is misleading. A runtime
capture can be complete for one committed generation while incomplete as a claim
about all possible executions. Conversely, a static manifest can completely list
possible sites without saying which were active in a particular commit.

Every evidence projection should therefore state at least:

```clojure
{:scope     :possible-sites       ; or a committed generation / named CI corpus
 :basis     :static-proof         ; :declaration, :observation, :opaque
 :complete? true                  ; only relative to the stated scope
 :loss      nil}                  ; or {:reason :cap :dropped 17}
```

Representative records:

```clojure
;; Compiled possible-site manifest
{:scope :possible-sites :basis :static-proof :complete? true :loss nil}

;; Exact reads selected by one committed render
{:scope {:committed-generation 812}
 :basis :observation :complete? true :loss nil}

;; Union observed in a CI corpus: useful, never a program proof
{:scope {:corpus :component-gallery :runs 240}
 :basis :observation :complete? false :loss nil}

;; Intentionally opaque React wrapper
{:scope :private-host-work :basis :opaque :complete? false :loss nil}

;; A cap was exceeded; exact loss is visible
{:scope {:committed-generation 812}
 :basis :observation :complete? false
 :loss {:reason :cap :dropped 17}}
```

An author declaration may use `:basis :declaration`. It earns
`:complete? true` for possible reads only if the runtime/analyzer enforces that
no undeclared read can occur. An unenforced annotation is a claim, not proof.

## Options for authoring reads

### Option A — Inline `v/sub` only in v1

Do not add `:reads`. Interpreted views expose committed observations; compiled
views additionally expose statically proved possible sites. Props-only views are
reported explicitly.

Consequences:

- Page-one authoring stays `v/defview`, `v/sub`, Hiccup, and event vectors.
- Conditional, chained, and dynamically parameterized reads remain ordinary
  Clojure rather than a query-template DSL.
- Component libraries that need a static manifest can compile their leaves,
  consistent with the current library placement recommendation.
- Uncompiled views cannot provide a static upper bound on dependencies. CI can
  publish an observed union, honestly scoped to its corpus.
- The future declaration design remains unconstrained by an early placeholder
  syntax.

### Option B — Optional `:reads` in v1

Allow either inline reads or a descriptor map of named query templates. Resolve
declared queries before invoking the body and inject their values under named
bindings/props.

Consequences:

- Interpreted views can have static dependency manifests without compilation.
- Structural tests can supply resolved read values directly, and catalogs can
  explain data needs before mounting.
- For compiled views, declarations can become the site table itself.
- Freehand must define placeholder resolution (`?id`), namespace/collision
  rules, query ordering, dependent queries, conditional reads, missing frames,
  overrides, equality, diagnostics, and whether inline `v/sub` may be mixed in.
- Injecting resolved values into the same props map blurs caller input and
  substrate input unless the descriptor/tree/tool surfaces preserve the
  distinction.
- Library leaves already recommended for compilation receive limited additional
  static benefit.

### Option C — Require declared reads and remove inline `v/sub`

Make every view a pure `(resolved-inputs, props) -> tree` function and prohibit
ambient reads.

Consequences:

- All dependency identity becomes data and every view has a static query contract.
- Tooling, SSR preflight, and pure unit invocation become straightforward.
- Conditional/chained reads require a growing declaration DSL or multiple view
  boundaries.
- The common re-frame idiom of local, granular derived subscriptions becomes
  more ceremonial. Page-level whole-model queries or prop plumbing are likely to
  spread.
- It discards a central ergonomic reason for a re-frame-native substrate and
  effectively recreates a subscription-injection system under another syntax.

Both designs reject this as the paved path.

### Option D — External view-model declarations only

Keep views inline-read or props-only, but let an application separately register
a named view-model query that a parent passes as a value.

Consequences:

- Existing re-frame subscriptions already provide most of this capability.
- It avoids a second read mechanism in `v/defview`.
- It does not provide per-view static dependency evidence unless the connection
  is declared somewhere, and risks duplicating the subscription registry.

This is an application architecture technique, not necessarily substrate API.

## Recommendation

Adopt **Option A for `:re-frame.freehand/v1`**, while defining the evidence
scope/basis/loss vocabulary immediately.

In v1 this means there is no `:reads` descriptor key, including for libraries;
“optional” would still create a second read language that tools and both emitters
must understand.

Do not add `:reads` until implementation and pilots demonstrate an uncompiled
surface that materially benefits from static dependency declarations. Library
controls already compile by default once checked, so their compiled manifests
provide the strongest evidence without a second authoring path. Application
pages benefit most from ordinary Clojure control flow and should not pay for a
placeholder/query-template DSL merely to improve a manifest.

Make the future trigger concrete. Reconsider optional declared reads if one or
more of these appears in the pilots:

- SSR/root preflight needs dependency knowledge that compiled manifests and
  root plans cannot provide;
- a sizable interpreted component library needs static read contracts for docs
  or AI without compiling its leaves;
- observed CI unions routinely miss production dependency paths and the missing
  static knowledge causes real faults;
- pure Story/catalog invocation is materially impaired by subscription overrides;
- the same small query-template shape recurs without needing conditional or
  chained reads.

If the feature is later admitted, require declarations to be enforced and forbid
silent mixing with undeclared inline reads. Otherwise label them
`:basis :declaration, :complete? false` and do not use them for elision or closure
claims.

The smallest future experiment should be an enforced empty-read declaration for a
props-only boundary, with any `v/sub` rejected. It can test the value of static
read contracts without first inventing placeholder interpolation.

## 2026-07-26 amendment — test-render subscription overrides

This is the evidence half of the accepted
[product-completion setpoint](../product-completion-setpoint.md)'s **ER-08**,
which sends the implementation contract to Spec 008 and the evidence amendment
here.

Mike accepted one development/testing substitution seam under
`re-frame.freehand.test`. It preserves Story's useful ability to show an error,
loading, empty, or other difficult view state without replaying the full event
history. It does not introduce `:reads`, application state, or a production
`v/*` API.

The contract is deliberately narrow:

1. An override key is the entire subscription query vector, compared by ordinary
   equality. There are no prefixes, patterns, predicates, or fallback matching.
2. A matching `v/sub` inside the explicit test/Story render bracket reads the
   pinned value. A miss takes the ordinary subscription path.
3. The override map never mutates app-db, registration state, or the subscription
   cache and never satisfies a subscription assertion. A derivation test still
   tests the real subscription.
4. The seam and its React carriage are development/test-only and production
   elided. Installation and cleanup are bracket-owned and exact.
5. Story records `:sub-overrides` as a lower-fidelity rendering rung. Freehand
   evidence uses the existing `:basis :declaration` and a test-render scope, with
   explicit loss such as `{:reason :subscription-resolution-substituted}`; it
   adds no member to the basis roster above and does not claim that real
   subscription logic ran.

Spec 008 owns the mounted/structural test contract when the implementation slice
lands. The Story provider, `re-frame.freehand.test` facade, Spec 008 text, mounted
proof, and programmer guide move together. Until then, the shipped guide remains
honest about the currently absent Freehand seam.

## Tool contract to settle now

The initial evidence schema should distinguish:

| Evidence | Scope | Basis | Completeness claim |
|---|---|---|---|
| compiled manifest | possible sites in one grammar/version | static proof | complete for compiler-visible sites |
| committed reads/events/tree | one selected generation | observation | complete for that generation unless capped |
| interpreted CI union | one named corpus and run set | observation | not complete for all executions |
| future declared reads | declared query contract | declaration | complete only when enforced |
| host wrapper internals | private host work | opaque | deliberately unavailable |
| any capped projection | its original scope | original basis plus loss | incomplete, with dropped count/reason |

This vocabulary should be shared by `view-manifest`, `mounted-views`,
`view-dependencies`, `view-event-sites`, and `explain-render`; tools should not
invent mode-specific meanings.

## Dependencies and what this unlocks

The evidence vocabulary depends on the selected-commit bundle and compiled
manifest shape. A future `:reads` form would also depend on D002’s descriptor,
D011’s declaration option/schema policy, frame resolution, and subscription
override semantics.

This decision unlocks:

- honest common-mode tool projections;
- CI observed-manifest artifacts without false closure claims;
- production-safe evidence caps;
- a clean v1 authoring surface;
- explicit criteria for revisiting declared reads rather than adding it on
  aesthetic grounds.

## Sources

- [codex-design.md — “Replicant and the no-`sub` idea”](../codex-design.md#replicant-and-the-no-sub-idea)
  argues for inline `sub` and a props-only-first gradient.
- [codex-design.md — “Debugging”](../codex-design.md#debugging) requires
  completeness and loss markers across both modes.
- [fable-design.md §2.5 — “The declared-reads profile”](../fable-design.md#25-the-data-orientation-doctrine)
  presents the optional declaration and its benefits/costs.
- [fable-design.md §3.5 — evidence instrument](../fable-design.md#35-placement-where-each-tier-applies)
  distinguishes observed, declared, and proven manifests.
- [fable-design.md §8 Q6](../fable-design.md#8-for-the-operator) records the
  unresolved default.
