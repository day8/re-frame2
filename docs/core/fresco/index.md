# Fresco user guide

Fresco is re-frame2's native view layer. Views are Hiccup data, subscription
reads are ordinary function calls, and event handlers can remain event vectors.
The runtime turns that data into React elements; app-db, events, subscriptions,
effects, frames, and the event pipeline are ordinary re-frame2, as the Core
guide teaches them. This guide covers what changes at the view layer.

Alongside the chapters are five lookup pages. The [API
reference](api-reference.md) lists every public name with its signature; the
[Cookbook](cookbook.md) has whole recipes you can copy;
[Troubleshooting](troubleshooting.md) starts from a symptom or an error id;
[The escape ladder](escape-ladder.md) says when to leave the interpreted model
and what each step costs; and the [Glossary](glossary.md) defines the
Fresco-specific terms the chapters use.

## When Fresco fits

Use Fresco when you want re-frame2's data-oriented model to continue through
the view tree:

- markup remains inspectable Hiccup data
- a view reads subscriptions with `h/sub` where it needs them
- common event handlers remain event vectors rather than opaque closures
- a frame remains explicit across rendering, callbacks, testing, and tools

## When to use another corpus or adapter

Pure business logic and HTTP work with no Fresco view belong in the Core,
async, or resources guides.

A Reagent application still using re-frame v1 event shapes should complete the
core migration before applying the Fresco migration. A React-first product —
hooks throughout the screen and a React component system at the centre — will
usually be clearer with the UIx adapter, using Fresco only where its data-first
view model is useful.

## Status

!!! info "Pre-alpha"

    Fresco is pre-alpha, and this guide is written against what ships. Every
    namespace and function it names is exported today, and a CI gate checks
    every Fresco function a code sample names against the source that
    defines it.
