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

Five lookup surfaces follow the chapters. The [API
reference](api-reference.md) carries every public name with the signature it
ships with; the [Cookbook](cookbook.md) carries whole recipes you can copy;
[Troubleshooting](troubleshooting.md) starts from a symptom or a complaint id;
[The escape ladder](escape-ladder.md) gives the criteria for going outside the
interpreted model and what each rung costs; and the [Glossary](glossary.md)
defines the Hicasso-specific terms the chapters use.

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

!!! info "Pre-alpha"

    Hicasso is pre-alpha, and this guide is written against what ships. Every
    namespace and verb it names is exported today, and a gate reads each
    verb in every fenced sample against the source that defines it, so a
    sample here cannot name a spelling the door does not carry. One name is
    provisional: `h/hframe` is spelled that way because a bare `frame` would
    shadow on a `:refer`, and the recommendation on record is to retire the
    verb once core's own frame doors are legal inside a body. Until then it is
    what ships, and what the guide teaches. [SSR and
    hydration](18-ssr-and-hydration.md) describes shipped behaviour rather
    than an intended contract.
