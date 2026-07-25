# Limits and escapes

Freehand is opinionated on purpose. The walls below are not accidental missing
features. They keep the paved path small enough that tests, tools, compilation,
and AI authoring can rely on it.

When you hit a wall, prefer the **named recovery** over inventing a second state
model or a silent fallback. If an error does not point you at a recovery, that is
a product defect worth fixing — not a reason to paper over the design.

This page is a map of walls and recoveries.

## Authoring walls

| You cannot | Why | Do this instead |
|---|---|---|
| Vector-call a bare `defn` | boundaries are declared descriptors | `v/defview` + `[view props]`, or call the helper with parens |
| Direct-call a `v/defview` | public vars are not `IFn` | `[view props]` |
| Use `v/sub` outside a declared render | render-only observation | `rf/subscribe-once` |
| Call `v/sub` off the render thread | capture is same-thread only | realize on the render thread or extract a child view |
| Put multi-intent vectors in handlers | one event per user action | one semantic event + effects |
| Use `local` / neutral hooks in views | one reactive system | re-frame facts, controllers, or host wrappers |
| Derive writable state from React keys / `v/self` | occurrence ≠ controller identity | explicit `:control` addresses |
| Bare fn on a foreign callback prop | unknown phase/identity | `v/event` / `v/handler` / `v/render-fn` / `v/raw-fn` |
| Bare React component as an ordinary head | foreign boundary must be named | `host/component` or UIx wrapper |
| Neutral portal primitive | not in v1 | top-layer intrinsics, behavior, or React wrapper |
| Automatic promotion | honesty | `v/check` then `{:compiled true}` |
| `:children` key inside the props map | reserved trailing channel only | trailing child forms → `:children` |
| Opaque reactive fn as a “render prop” | hides `sub` / identity | `v/render-fn`/`v/slot` if pure; else declared child view |
| `:parts` that rewrite structure or strip roles | attrs-only; owned a11y stays owned | composition spreads + a11y ownership rules |
| Dynamic map on controlled input without `spread-safe` | forfeits door proof | `v/spread-safe` (not bare merge / `v/spread`) |
| Put `:on-before-input` on the controlled door | precedes DOM mutation; not door-eligible | ordinary listener; door is `:on-input` / `:on-change` only |
| Invent public APIs the design did not name (`v/frame-provider`, mount seed kwargs, …) | aspirational drift | stick to spine contracts; mark TBD helpers as unpublished |

## Compiled-mode walls

| You cannot | Recovery |
|---|---|
| Hide `sub` or markup in opaque helpers | extract a child, pass values, or stay interpreted |
| Dynamic tag heads | finite branches or vary attributes |
| Markup-returning `map` as structure | keyed `for` of declared views |
| Unkeyed dynamic lists | real `:key`s |
| Inline interpreter fallback in compiled space | `[v/markup {:value …}]` as a declared interpreted child |

Run `v/check` before promotion. Findings are the work order.

## Performance walls

Compilation is not a cure-all. If cost is:

- **over-broad subscriptions** → narrow the graph
- **collection size** → window
- **foreign widget** → qualified leaf / behavior; do not invent another Hiccup compiler
- **interpretation of a hot template** → compile that boundary after measurement

## When Freehand is the wrong tool

- You need a mature Reagent/re-com ecosystem path *today* without riding pre-alpha
  Freehand implementation.
- The entire surface is a React app with little re-frame dataflow — UIx may be
  simpler.
- You need a pure whole-state renderer with no subscriptions — Replicant is an
  architectural alternative, not a Freehand mode.

Other **view layers** (Reagent, UIx) remain first-class. Freehand is the
re-frame-native peer, not a mandate that erases the others.

## Deliberate non-goals (full map)

These are product exclusions from the design spine, not temporary TODOs. Prefer
the named recovery column.

| Non-goal | Recovery / reality |
|---|---|
| Second compiler, `v/interp`, or hidden interpreted fallback inside compiled markup | stay interpreted, extract child, or `[v/markup {:value …}]` |
| Permanent `re-frame.ui` sibling product | absorb donor; delete at gate |
| Preserve every donor API because code was useful | Freehand owns the public contract |
| Third “unprofiled” mode for local state / hooks / refs / effects | host boundary or UIx wrapper |
| Automatic promotion or a “hot 5%” rule | measure → `v/check` → `{:compiled true}` |
| Callable declared views or a region DSL | `[view props]` / trailing children / compound views |
| `:reads` query-template language in v1 | inline `(v/sub …)` only |
| `v/self` or renderer-derived writable addresses | explicit `:control` / domain ids |
| Generic component-local storage verbs or widget event namespaces | library events; no `:rf.field/*` in core v1 |
| Controller / reducer DSL in Freehand | ordinary `reg-event` / `reg-sub` |
| Render/unmount ownership of domain resources | routes, resources, machines |
| Neutral portals or arbitrary lifecycle callbacks | top-layer, behavior, or React wrapper |
| Queued host command bus / service locator | explicit command targets on live connections |
| Payload-map arity on general re-frame dispatch | Freehand materializes projections at the event site only |
| React callback-protocol guessing | `v/event` / `v/handler` / `v/render-fn` / `v/raw-fn` |
| Late tree transforms as a portable theme system | CSS tokens + `data-part` + bounded `:parts` attrs |
| Automatic app-db / event-history capture in error reports | opt-in observer; safe summary only by default |
| Replicant-style aliases without library demand | declared views + plain helpers |
| Structural click simulation / second behavior-test language | assert event vectors; mounted tier for real DOM |
| Serializing DOM nodes, React elements, callbacks, host instances | host markers in structural trees; opaque interiors |
| Claiming React and JVM emitters are one implementation | separate emitters; shared semantic ABI |

## Relationship to `re-frame.ui`

`re-frame.ui` is in **donor mode**: its useful machinery (analyzer, emitters,
ViewCell, presence, tests) is absorbed as Freehand's compiled implementation.
Freehand does not preserve `re-frame.ui` as a sibling public product. Donor
deletion is a **gate** (conformance green, pilots pass, consumers migrate), not a
calendar date. Do not design new features against the donor namespace.
