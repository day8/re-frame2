# Fresco user guide

Fresco is re-frame2's native view layer. Views are Hiccup data, subscription
reads are ordinary function calls, and event handlers can remain event vectors.
The runtime turns that data into React elements; app-db, events, subscriptions,
effects, frames, and the event pipeline are ordinary re-frame2, as the Core
guide teaches them. This guide covers what changes at the view layer.

## When Fresco fits

Use Fresco when the application is primarily a re-frame2 application and you
want its data-oriented model to continue through the view tree:

- markup remains inspectable Hiccup data
- a view reads subscriptions with `h/sub` where it needs them
- common event handlers remain event vectors rather than opaque closures
- a frame remains explicit across rendering, callbacks, testing, and tools

## When to use another corpus or adapter

Pure business logic and HTTP work with no Fresco view belong in the Core,
async, or resources guides.

Reagent and UIx remain supported view layers.

**Reagent** suits an existing application whose view layer already works and
where migration cost dominates. Fresco looks familiar because both use Hiccup,
but Fresco has no ratoms, reactions or Form-2 components.
[Migrating from Reagent](20-migration-from-reagent.md) covers the differences.
A Reagent application still using re-frame v1 event shapes should complete the
core migration before applying the Fresco migration.

**UIx** is usually better when React organises the view layer: hooks are
common, a React design system dominates the tree, and the team thinks in React
component lifecycles. Fresco can host foreign React components through
[`h/defhost`](09-interop.md), but it does not try to replace a React-first
authoring model, so a React-first product will usually be clearer with the UIx
adapter, using Fresco only where its data-first view model is useful.

## Costs and limits

Interpreting Hiccup has a runtime cost. Cold mount can be slower than a
hand-written UIx equivalent, and each Fresco view pays a small fixed cost for
tracking its reads. Measure before moving code: a React island is for the part
of a screen that profiling identifies, not the default authoring style.
[The escape ladder](escape-ladder.md) says what each step away from interpreted
Hiccup costs.

Fresco has no second reactive store inside the view layer. State that other
views, tests, tools, routing, or SSR must observe belongs in app-db. The few
cases for DOM-owned or local UI state are covered in
[Ephemeral state](11-ephemeral-state.md).

## Status

!!! info "Pre-alpha"

    Fresco is pre-alpha, and this guide is written against what ships. Every
    namespace and function it names is exported today, and a CI gate checks
    every Fresco function a code sample names against the source that
    defines it.
