# Hicasso user guide

Hicasso is re-frame2's native view layer. Views are Hiccup data, subscription
reads are ordinary function calls, and event handlers can remain event vectors.
The runtime turns that data into React elements; app-db, events, subscriptions,
frames, and the event pipeline remain ordinary re-frame2.

This guide explains the Hicasso view model, controlled inputs, forms, routing,
resources, React interop, native components, local UI state, overlays, SSR,
testing, diagnostics, performance, migration, code splitting, and
accessibility. The MkDocs sidebar supplies the chapter order and page
navigation.

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

> **Status.** This guide describes the intended completed Hicasso programme.
> Public names may still change during the remaining naming review. Treat the
> spellings here as the current recommended defaults until that review freezes
> them.
