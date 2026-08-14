# Hicasso user guide

Hicasso is re-frame2's native view layer. Views are Hiccup data, subscription
reads are ordinary function calls, and event handlers can remain event vectors.
The runtime turns that data into React elements; app-db, events, subscriptions,
frames, and the event pipeline remain ordinary re-frame2.

This guide explains the Hicasso view model, controlled inputs, forms, routing,
resources, React interop, native components, local UI state, motion/presence,
overlays, SSR, testing, diagnostics, performance, migration, code splitting,
and accessibility. Numbered pages run from
[`00-installation`](00-installation.md) through
[`22-accessibility`](22-accessibility.md), and the sidebar carries that order —
each chapter leans only on what came before it.

## Prerequisites

You should already understand the re-frame2 basics: events, app-db,
subscriptions, effects, and frames. The Core guide owns those concepts. This
corpus explains what changes at the view layer and how that layer behaves at
its boundaries.

## When Hicasso fits

Use Hicasso when you want re-frame2's data-oriented model to continue through
the view tree:

- markup remains inspectable Hiccup data
- a view reads subscriptions with `h/sub` where it needs them
- common event handlers remain event vectors rather than opaque closures
- a frame remains explicit across rendering, callbacks, testing, and tools

## When to use another corpus or adapter

Pure business logic and HTTP work with no Hicasso view belong in the Core,
async, or resources guides.

A Reagent application still using re-frame v1 event shapes should complete the
core migration before applying the Hicasso migration. A React-first product —
hooks throughout the screen and a React component system at the centre — will
usually be clearer with the UIx adapter, using Hicasso only where its data-first
view model is useful.

## Status

!!! warning "Pre-alpha — two doors this guide spells differently from the code"

    Hicasso is pre-alpha, and parts of this guide are written against an
    intended authoring surface rather than against today's exported
    spellings. Two differences are known and deliberate, and each is
    recorded in the source rather than only here. One of them is an open
    naming question rather than work the code owes the guide: the guide's
    spelling is one candidate among several, and is not the name the door is
    waiting to take.

    The callback form is no longer among them. It was taught here as `h/fn`
    against an exported `hfn`, and both sides now spell it **`h/event`**
    (`rf2-hic-066`, applying the operator's ruling on naming-ledger row 1).

    - **`h/frame`** is exported today as `h/hframe`. A bare `frame` shadows on
      a `:refer` in the same way, and the recommendation on record is to
      retire the verb rather than respell it, leaving `rf/current-frame-id`
      and `rf/capture-frame` as the frame doors they already are.
    - **`h/mount!`** is the root door this guide teaches; the exported door is
      `h/root!`, which takes the frame keyword positionally and carries no
      `:initial-events` option. `h/render!` and `h/unmount!` are as described.

    The other namespaces and verbs the guide names — `h/defview`, `h/sub`,
    `h/defhost`, `h/portal`, `h/as-component`, `h/error-boundary`,
    `h/hydrate!`, the `n/`, `overlay/`, `motion/` and `forms/` surfaces,
    `re-frame.hicasso.server`, and the `ht/` and `hm/` test kits — are
    exported today. [SSR and hydration](18-ssr-and-hydration.md) describes
    shipped behaviour rather than an intended contract.
