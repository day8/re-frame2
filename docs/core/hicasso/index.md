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

!!! warning "Pre-alpha — four places where this guide runs ahead of the code"

    Hicasso is pre-alpha, and this guide is written against the intended
    authoring surface rather than against today's exported spellings. Four
    differences are known and deliberate, and each is recorded in the source
    rather than only here.

    - **`h/fn` and `h/frame`** are exported today as `h/hfn` and `h/hframe`.
      A bare `fn` or `frame` would shadow `cljs.core` on a `:refer`, and
      choosing the final spelling is an open naming decision.
    - **`h/mount!`** is the root door this guide teaches; the exported door is
      `h/root!`, which takes the frame keyword positionally and carries no
      `:initial-events` option. `h/render!` and `h/unmount!` are as described.
    - **`h/hydrate!`** is not exported. The hydrating root is built and
      witnessed, but it is held off the public door until a server-render
      counterpart exists to produce the bytes it would adopt.
    - **`re-frame.hicasso.server`**, and the `server/render` that
      [SSR and hydration](18-ssr-and-hydration.md) is written against, do not
      exist yet. Read that chapter as the intended contract.

    The other namespaces and verbs the guide names — `h/defview`, `h/sub`,
    `h/defhost`, `h/portal`, `h/as-component`, `h/error-boundary`, the `n/`,
    `overlay/`, `motion/` and `forms/` surfaces, and the `ht/` and `hm/` test
    kits — are exported today.
