# 13 — Lifecycle

This chapter is about the surfaces that bring a re-frame2 process up and take it down. The core of it is **adapter selection at boot** — `init!` takes an adapter spec (Reagent / UIx / Helix / Plain Atom / SSR) and installs it; everything else hangs off that. The right call shape (`(rf/init! reagent/adapter)`, not `(rf/init!)`) is enforced at the language level so the missing-adapter mistake surfaces at compile / load time rather than as a runtime confusion.

There's also a small inspection surface for "what's currently installed?" — `current-adapter`, `current-adapter-spec`, `adapter-disposed?` — and the symmetric `destroy-adapter!` for tear-down. Together these are the lifecycle vocabulary.

## Adapter selection

### `init!`

- **Kind**: function
- **Signature**:
  ```clojure
  (init! adapter-map)
  ```
- **Status**: v1
- **Description**: The idempotent boot. Required arg: the adapter spec map. Each adapter ns exports an `adapter` Var; consumers require the ns and pass the Var, e.g. `(rf/init! reagent/adapter)`. Calling `(init!)` with no args raises a language-level `ArityException` at compile / load time — the no-arg arity was cut so the missing-adapter mistake surfaces before runtime. Calling `(init! nil)` or `(init! :reagent)` raises `:rf.error/no-adapter-specified` at runtime. Ensures `:rf/default` frame is present.

### `install-adapter!`

- **Kind**: function
- **Signature**:
  ```clojure
  (install-adapter! adapter-map)
  ```
- **Status**: v1
- **Description**: Must be called before any frame is created. **Lower-level than `init!`**; most consumers call `init!` instead. Use it when you're writing a custom boot pipeline that has additional steps between adapter-install and first-frame creation.

### `destroy-adapter!`

- **Kind**: function
- **Signature**:
  ```clojure
  (destroy-adapter!)
  ```
- **Status**: v2
- **Description**: Tear down the installed adapter. Calls the adapter spec's `:dispose-adapter!` fn (if present), clears the install slot so a new adapter can install, and flips the `adapter-disposed?` breadcrumb. Symmetric with `install-adapter!` and with `destroy-frame!` — same `destroy-` verb-cluster (lifecycle boundary).

The adapter-spec **map key** `:dispose-adapter!` is an internal contract slot adapters implement; ignore it unless you're authoring an adapter.

### What an adapter ships

The adapter spec is a map with three keys:

| Slot | What it does |
|---|---|
| `:make-state-container` | The substrate's reactive primitive. Reagent ships `r/atom`; UIx and Helix ship hook-shaped containers. |
| `:render` | The substrate's mount fn. Reagent ships `r/render`; UIx ships `uix/render-root`; Helix ships its render. |
| `:dispose-adapter!` | The teardown hook. Adapter-specific cleanup; called by `destroy-adapter!`. |

Most app code never sees the spec map — you just pass the adapter's `adapter` Var into `init!` and forget about it.

## Inspection

### `current-adapter`

- **Kind**: function
- **Signature**:
  ```clojure
  (current-adapter) → discriminator keyword
  ```
- **Status**: v1
- **Description**: "What substrate am I on?" Answers `:rf.adapter/reagent` / `:rf.adapter/reagent-slim` / `:rf.adapter/uix` / `:rf.adapter/helix` / `:rf.adapter/plain-atom` / `:rf.adapter/ssr` / `:custom` — or `nil` when no adapter is installed. For predicate / branch code.

### `current-adapter-spec`

- **Kind**: function
- **Signature**:
  ```clojure
  (current-adapter-spec) → installed adapter spec map
  ```
- **Status**: v1
- **Description**: "Give me the adapter fns to call." The value passed to `(rf/init! ...)`, or `nil` when no adapter is installed. Use for tools / routing / identity checks across the install / dispose lifecycle. For the discriminator keyword, use `current-adapter`.

### `adapter-disposed?`

- **Kind**: function
- **Signature**:
  ```clojure
  (adapter-disposed?) → boolean
  ```
- **Status**: v1
- **Description**: "Was the adapter torn down?" Returns `true` iff the most recent lifecycle event was a successful `destroy-adapter!` and no subsequent `install-adapter!` has fired. `false` for never-installed (fresh process) AND after a fresh install. Read-only — the breadcrumb is owned by the install / destroy pair. Use to distinguish `:rf.error/no-adapter-installed` (fresh process) from `:rf.error/adapter-disposed` (torn down).

The split between `current-adapter` (keyword) and `current-adapter-spec` (map) is principled. The keyword is for switch-like code that branches on substrate identity. The spec map is for code that needs to call adapter fns or compare adapter identity across reinstalls.

## The disposed-vs-never-installed distinction

These two states surface as different errors because they have different recovery paths:

| State | Detection | Typical fix |
|---|---|---|
| Never installed | `(current-adapter)` returns `nil`; `(adapter-disposed?)` returns `false` | Call `(rf/init! adapter)`. |
| Disposed | `(current-adapter)` returns `nil`; `(adapter-disposed?)` returns `true` | A new `(rf/init! adapter)` is required. Tooling that observed the previous install needs to re-attach. |

The two states share the "no current adapter" surface but answer different questions about the process's history. Per [006 §Disposed-vs-never-installed](../../spec/006-ReactiveSubstrate.md#disposed-vs-never-installed-rf2-6wxys).

## Configuration

The `configure` fn is the lifecycle's adjacent surface — process-level data knobs that you typically set once at boot, possibly tweak in tests, and rarely touch in app code.

### `configure`

- **Kind**: function
- **Signature**:
  ```clojure
  (configure key opts)
  ```
- **Status**: v1
- **Description**: Runtime config. One of three orthogonal configuration surfaces — `configure` for process-level data knobs; `set-!` / `install-!` for adapter-pluggable hooks; per-frame metadata for frame-scoped overrides. The vocabulary of keys lives in [01 — Core §Configure keys](01-core.md#runtime-configuration-configure).

The three configuration surfaces — `configure`, the `set-!` / `install-!` setters (`set-schema-validator!`, etc.), and per-frame metadata — are deliberately separate. Each answers a different question: `configure` for data knobs (depth, threshold, grace period); the setters for hook-shaped pluggability (which validator to use, which printer); per-frame metadata for frame-scoped overrides (which projector, which `:fx-overrides`).

Full rationale: [Conventions §Configuration surfaces](../../spec/Conventions.md#configuration-surfaces-configure-vs-set--vs-per-frame-metadata).

## Bootstrap pattern

```clojure
(ns my.app
  (:require [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent]
            [reagent.dom :as rdom]
            [my.app.views :as views]
            [my.app.events]      ;; side-effecting requires for handler registration
            [my.app.subs]
            [my.app.routes]))

(defn ^:export main []
  (rf/init! reagent/adapter)
  (rf/configure :sub-cache {:grace-period-ms 100})
  (rdom/render [views/root] (js/document.getElementById "app")))
```

That's the whole boot. `init!` installs the adapter and primes `:rf/default`; the side-effecting requires register the handlers / subs / routes into the registrar; `configure` tunes the runtime; the substrate's render fn mounts the root view.

## See also

- [01 — Core](01-core.md) — `reg-frame` / `make-frame` / `configure` rowed in registration and configuration.
- [02 — Views](02-views.md) — adapter-side surfaces (UIx / Helix hooks, the shared React Context).
- [14 — Adapters](14-adapters.md) — per-substrate surface tables.
- [Spec 006 — Reactive Substrate](../../spec/006-ReactiveSubstrate.md) — the adapter contract.
