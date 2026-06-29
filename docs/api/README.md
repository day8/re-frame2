# The re-frame2 API

This is the human-facing API reference for the ClojureScript implementation of re-frame2. It is organised **one document per namespace** — each file is named for the namespace it covers and lists every public surface you get from `(:require [that.namespace …])`, grouped by category.

Start with [`re-frame.core`](re-frame.core.md) — it is what you require to make an app exist at all (registration, dispatch/subscribe, views, effects, frames, lifecycle). Everything else is a feature or substrate namespace you add when you need it.

## Where surfaces live

| Namespace | Artefact | What's in it |
|---|---|---|
| [`re-frame.core`](re-frame.core.md) | core | The whole `rf/` facade — registration, dispatch/subscribe, views, effects & interceptors, frames, lifecycle & configure, instrumentation/listeners, registrar queries, and the feature-registration re-exports. |
| [`re-frame.schemas`](re-frame.schemas.md) | `day8/re-frame2-schemas` | Schema introspection, validation entry points, validator-extension seams, classification walkers. |
| [`re-frame.flows`](re-frame.flows.md) | core | Flow teardown + introspection (`reg-flow` is on the `rf/` facade). |
| [`re-frame.http`](re-frame.http.md) | `day8/re-frame2-http` | Managed-HTTP verb helpers and the `:rf.http/*` fx surface. |
| [`re-frame.machines`](re-frame.machines.md) | `day8/re-frame2-machines` | The state-machine engine, query and transition surfaces, and the `:rf.machine/*` fx. |
| [`re-frame.routing`](re-frame.routing.md) | `day8/re-frame2-routing` | Routing helpers and the `:rf.route/*` / `:rf.nav/*` surfaces. |
| [`re-frame.resources`](re-frame.resources.md) | `day8/re-frame2-resources` | Declarative server-state — `reg-resource` / `reg-mutation` and the `:rf.resource/*` / `:rf.mutation/*` surfaces. Optional, post-v1. |
| [`re-frame.ssr`](re-frame.ssr.md) | `day8/re-frame2-ssr` | Server-side rendering — render primitives, the head model, error projection. |
| [`re-frame.ssr.ring`](re-frame.ssr.ring.md) | `day8/re-frame2-ssr-ring` | Ring host-adapter for SSR. |
| [`re-frame.epoch`](re-frame.epoch.md) | `day8/re-frame2-epoch` | Time-travel — epoch history, restore, replace (re-exported through `rf/` via late-bind). |
| [`re-frame.performance`](re-frame.performance.md) | core | Performance instrumentation. |
| [`re-frame.adapter.reagent`](re-frame.adapter.reagent.md) | core | The inline default substrate adapter. |
| [`re-frame.adapter.uix`](re-frame.adapter.uix.md) | `day8/re-frame2-uix` | UIx substrate adapter + hooks. |
| [`re-frame.adapter.helix`](re-frame.adapter.helix.md) | `day8/re-frame2-helix` | Helix substrate adapter + hooks. |
| [`re-frame.test-support`](re-frame.test-support.md) | core | Fixtures, `dispatch-sequence`, `poll-until`, the registrar snapshot/restore helpers. |
| [`re-frame.test-helpers`](re-frame.test-helpers.md) | core | View-assertion helpers (hiccup-walk, `find-by-testid`, …). |

The dependency direction is one-way: adapters and feature artefacts depend on core; core never depends on them. Apps load whatever subset they need.

## Reading a row

Every entry carries a **Kind** (`function` / `macro` / `fx` / `sub` / `event`), a **Signature**, a **Description**, and — where one exists — an **Example** drawn from real usage. A facade re-export (e.g. `reg-machine` on `rf/`) appears briefly in [`re-frame.core`](re-frame.core.md) with the full contract in its feature namespace doc.

The dense, normative single-page contract lives in [the spec API](../../spec/API.md); this reference is the same surface walked through by namespace.
