# re-frame2

This is the user guide for the **ClojureScript reference implementation** of the [re-frame2 specification](https://github.com/day8/re-frame2/tree/main/spec).

The guide is organised into five sections.

## Core

The fundamentals every app is built from — the dataflow loop: app-db, events, subscriptions, flows, effects, and views. Start with the [Quickstart](core/quickstart.md), then the [core concepts](core/concepts/index.md).

→ [Core guide](core/README.md)

## Capabilities

Four optional subsystems you reach for when you need them — each with its own guide:

- [**Machines**](machines/index.md) — state machines (statecharts) for modelling a feature's lifecycle.
- [**Resources**](resources/index.md) — cached, declarative server state (the TanStack-Query-shaped capability).
- [**Routing**](routing/index.md) — the URL as ordinary application state.
- [**SSR**](ssr/index.md) — server-side rendering and hydration.

## Examples

Small, complete apps you can read top to bottom and run — from the counter to a full RealWorld app — organised by concept (core, capabilities, patterns, real-apps, substrates).

→ [Browse the examples](https://github.com/day8/re-frame2/tree/main/examples)

## Tools

Dev and inspection tools built on the framework's introspection API:

- [**Story**](story/index.md) — render your views in every state, in isolation (think Storybook).
- [**Xray**](xray/index.md) — a live inspector for events, subscriptions, machines, and time-travel.

## Skills

Claude Code skills for building with re-frame2 — [re-frame-migration](skills/re-frame-migration.md) for porting from re-frame v1, and [re-frame2-pair](skills/re-frame2-pair.md) for pairing on application code.

→ [Skills](skills/index.md)
