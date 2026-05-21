# The re-frame2 API

This is the human-facing API reference for the ClojureScript implementation of re-frame2. It's organised by **what you're trying to do** rather than by alphabetical surface listing. Each chapter opens with a paragraph on what the surface is *for* — the problem it solves, the shape of the contract — and only then drops into the function tables.

If you want the dense, single-page contract — every signature, every status, every cross-reference — the [normative reference](../../spec/API.md) is still where that lives. This guide is the same surface, walked through by topic, with intuition notes attached.

## What's a "canonical" API?

Every row in this reference is **canonical**: a documented, supported v1 (or post-v1 library) API that downstream apps and tools may rely on. There are no "alpha", "experimental", or "advanced" tiers in this surface. If a row appears here, you can call it; if a row doesn't appear here, it's either internal plumbing or a `post-v1` library scaffold that hasn't shipped yet. The rare internal carve-outs (two macro-helpers re-exposed for pre-split tests) are explicitly out of scope.

## How to read a row

Every row carries:

- a **signature** — the call shape, in Clojure form
- a **status** — `v1`, `v1 (preserved)` (carried unchanged from re-frame v1), `v1 (preserved + extended)` (carried with new arity or behaviour), `post-v1 lib` (designed in v1 but ships in a follow-on library), and a `dev-only` qualifier where the surface is elided in `:advanced` production builds
- an **intuition** — the one-line answer to "what's this for and when do I reach for it?"

Macros and functions are marked `M` or `Fn`. Where a `*` variant exists (`dispatch` / `dispatch*`, `reg-view` / `reg-view*`, etc.) the un-starred form is the macro; the `*` form is the underlying function, useful inside higher-order code where a macro can't sit. The `*` follows Clojure's own `let` / `let*`, `fn` / `fn*` idiom.

## Where surfaces live

The core surfaces live in `re-frame.core`. Per-feature artefacts ship their own public namespace, and consumers require it directly:

| Namespace | Artefact | Use when |
|---|---|---|
| `re-frame.core` | core | Always. The registration / dispatch / subscribe / interceptor / lifecycle / configure surfaces all live here. |
| `re-frame.test-support` | core | Test code. Fixture machinery, `dispatch-sequence`, `assert-path-equals` / `assert-db-equals`, `poll-until`. |
| `re-frame.test-helpers` | core | View-assertion tests. Hiccup-walk, `find-by-testid`, `text-content`, `invoke-handler`, the `testid` authoring helper. |
| `re-frame.schemas` | `day8/re-frame2-schemas` | Schema introspection (the registration macros live in `re-frame.core`). |
| `re-frame.http` | `day8/re-frame2-http` | HTTP verb helpers `get` / `post` / `put` / `delete` / `patch` / `head` / `options`. |
| `re-frame.machines` | `day8/re-frame2-machines` | Direct machine surface (the `reg-machine` macro is re-exported through `re-frame.core`). |
| `re-frame.ssr` | `day8/re-frame2-ssr` | Server-side rendering — `render-to-string`, streaming, head model, error projection. |
| `re-frame.ssr.ring` | `day8/re-frame2-ssr-ring` | Ring host-adapter for SSR. |
| `re-frame.epoch` | `day8/re-frame2-epoch` | Time-travel surfaces — `epoch-history`, `restore-epoch`, `reset-frame-db!`. Re-exported through `re-frame.core` via late-bind. |
| `re-frame.adapter.uix` / `re-frame.adapter.helix` | `day8/re-frame2-uix` / `-helix` | The per-substrate adapter surfaces and hooks. |

The dependency direction is one-way: adapters and feature artefacts depend on core; core never depends on them. Apps load whatever subset they need.

## The chapters

The reference is divided into sixteen chapters. Each is independent — you can land on any of them from a search result and get something useful without reading the others.

The first three are foundational — **Core** (registration, dispatch, subscribe), **Views** (the view registry and the substrate-agnostic ergonomic surface), **Effects and interceptors** (the effect map, the standard interceptors, the context plumbing).

The next six cover feature domains: **State machines**, **Flows**, **Routing**, **HTTP**, **Schemas and data classification**, **SSR**.

The next four cover the operational surfaces: **Testing**, **Instrumentation** (listeners, tracing, epoch, performance), **Registrar** (the query API tools build against), **Lifecycle** (adapter install / dispose, `configure`).

Then **Adapters** (the per-substrate surfaces — Reagent, UIx, Helix), **Story** (post-v1 — variants, workspaces, snapshot identity), and a closing **Removed / not shipped** chapter that says what's gone and what to use instead.

## When to reach for the spec instead

The chapters here are organised for readers; the [normative API reference](../../spec/API.md) is organised for completeness. If you're looking for *every* row at once — a `Ctrl-F` target across the full surface — that's where you want to be. If you're writing a new app and want to know which surfaces *exist* in a given domain, you want a chapter here.

The normative spec docs (`002-Frames.md`, `005-StateMachines.md`, etc.) own the *why* — the design rationale, the alternatives considered, the dispositions. The chapters here cite those when they matter, and stay quiet otherwise.
