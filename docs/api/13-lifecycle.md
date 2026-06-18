# 13 — Lifecycle

This chapter is about the surfaces that bring a re-frame2 process up and take it down. Three distinct lanes meet at startup, and almost every confusion here is a lane confusion — so this chapter keeps them apart on purpose:

| Lane | Who owns it | The surfaces | Where it's documented |
|---|---|---|---|
| **Application boot** | App authors | `init!`, then explicit `reg-frame` / `make-frame` + `frame-provider`; `configure!` to tune | [Application boot](#application-boot) (below) |
| **Frame startup** | App authors (per frame) | `reg-frame` / `make-frame` and the `:on-create` event the new frame runs | [01 — Core §Frames](01-core.md#frames-the-scoping-primitive), [Pattern — Boot](../../spec/Pattern-Boot.md) |
| **Adapter-author internals** | Adapter authors | `install-adapter!`, `destroy-adapter!`, `current-adapter`, `current-adapter-spec`, `adapter-disposed?`, the adapter-spec map | [For adapter authors](#for-adapter-authors) (below) |

An app author learns one boot sentence — **install an adapter with `init!`, then create your frame(s) explicitly** — and nothing else. The adapter-author surfaces (install / dispose / inspect, and the spec-map slots) are *not* peer choices beside `init!`; they sit one layer down and an ordinary app never touches them. The frame-startup lane is a third thing again: it's about what a *single frame* does when it comes alive (`:on-create`), independent of which substrate the process installed. Keep the three apart and the lifecycle reads cleanly.

## Application boot

This is the only lane an app author needs. It has exactly two steps: install the substrate adapter once, then create your app's frame(s) explicitly. Frame creation is always under your control — `init!` deliberately creates none.

```clojure
(rf/init! reagent/adapter)                          ;; 1. install the substrate (once)

(rf/reg-frame :app/main {:on-create [:app/boot]})   ;; 2. create your frame explicitly
;; …then establish it at your root with `frame-provider` (see the bootstrap pattern below).
```

Step 2's `:on-create` is the seam into the **frame-startup** lane — the first event a new frame runs. For trivial apps it seeds app-db; for real apps it's the head of a boot sequence ([Pattern — Boot](../../spec/Pattern-Boot.md) is the canonical recipe, from chained events up to a dedicated boot state machine). The frames concept guide walks the whole app-boot shape end to end: [Frames: isolated worlds](../guide/concepts/frames.md).

### `init!`

- **Kind**: function
- **Signature**:
  ```clojure
  (init! adapter-map)
  ```
- **Description**: The idempotent boot. Required arg: the adapter spec map. Each adapter ns exports an `adapter` Var; consumers require the ns and pass the Var, e.g. `(rf/init! reagent/adapter)`. Calling `(init!)` with no args raises a language-level `ArityException` at compile / load time — the no-arg arity was cut so the missing-adapter mistake surfaces before runtime. Calling `(init! nil)` or `(init! :reagent)` raises `:rf.error/no-adapter-specified` at runtime. `init!` installs adapters and runtime capabilities only; per [EP-0002](../../spec/002-Frames.md#frame-target-resolution--the-carried-invariant) it does **not** create or guarantee any frame — you register your app frame explicitly (`reg-frame`) and establish it at your root.
- **Example**:
  ```clojure
  (:require [re-frame.adapter.reagent :as reagent-adapter])

  (rf/init! reagent-adapter/adapter)
  ```
- **In the wild**: [counter](https://github.com/day8/re-frame2/tree/main/examples/reagent/counter)

After `init!`, create your frame(s) — that's the next subsection.

## Frame startup — the second boot step

`init!` installs host capability and creates **no** frame; per [EP-0002](../../spec/002-Frames.md#frame-target-resolution--the-carried-invariant) frame ownership is always explicit. So the app author's second move is to create the frame and establish it at the root:

- **`reg-frame`** / **`make-frame`** create a frame atomically and run its `:on-create` event synchronously — the frame's startup lifecycle. Both are rowed in [01 — Core §Frames](01-core.md#frames-the-scoping-primitive).
- **`frame-provider`** establishes a created frame at a point in the view tree, so every bare `dispatch` / `subscribe` underneath resolves against it.

The `:on-create` event is where the frame's own startup logic lives — seed app-db for a trivial app, or kick a boot sequence for a real one. That sequence is its own pattern, not a lifecycle surface: see [Pattern — Boot](../../spec/Pattern-Boot.md) for the chained-events and boot-state-machine forms, and [Frames: isolated worlds](../guide/concepts/frames.md) for the app-author narrative (including why `init!` doesn't create a frame for you).

## For adapter authors

Everything below is the **adapter-author lane**. An ordinary app never calls these — `init!` (above) drives them for you. Reach here only when you are *writing* a substrate adapter or a custom boot pipeline, or building tooling that has to inspect the install slot. The per-substrate surface tables live in [14 — Adapters](14-adapters.md); the normative contract is [Spec 006 — Reactive Substrate](../../spec/006-ReactiveSubstrate.md).

### `install-adapter!`

- **Kind**: function
- **Signature**:
  ```clojure
  (install-adapter! adapter-map)
  ```
- **Description**: Must be called before any frame is created. **Lower-level than `init!`**; ordinary apps call `init!` instead. Use it when you're writing a custom boot pipeline that has additional steps between adapter-install and first-frame creation.

### `destroy-adapter!`

- **Kind**: function
- **Signature**:
  ```clojure
  (destroy-adapter!)
  ```
- **Description**: Tear down the installed adapter. Calls the adapter spec's `:dispose-adapter!` fn (if present), clears the install slot so a new adapter can install, and flips the `adapter-disposed?` breadcrumb. Symmetric with `install-adapter!` and with `destroy-frame!` — same `destroy-` verb-cluster (lifecycle boundary).

The adapter-spec **map key** `:dispose-adapter!` is an internal contract slot adapters implement; ignore it unless you're authoring an adapter.

### What an adapter ships

The adapter spec is a map with three keys:

| Slot | What it does |
|---|---|
| `:make-state-container` | The substrate's reactive primitive. Reagent ships `r/atom`; UIx and Helix ship hook-shaped containers. |
| `:render` | The substrate's mount fn. Reagent ships `r/render`; UIx ships `uix/render-root`; Helix ships its render. |
| `:dispose-adapter!` | The teardown hook. Adapter-specific cleanup; called by `destroy-adapter!`. |

Most app code never sees the spec map — you just pass the adapter's `adapter` Var into `init!` (the application-boot lane) and forget about it.

### Inspection

These reads answer "what's installed right now?" — for tooling and adapter-author code, not for app boot.

#### `current-adapter`

- **Kind**: function
- **Signature**:
  ```clojure
  (current-adapter) → discriminator keyword
  ```
- **Description**: "What substrate am I on?" Answers `:rf.adapter/reagent` / `:rf.adapter/reagent-slim` / `:rf.adapter/uix` / `:rf.adapter/helix` / `:rf.adapter/plain-atom` / `:rf.adapter/ssr` / `:custom` — or `nil` when no adapter is installed. For predicate / branch code.

#### `current-adapter-spec`

- **Kind**: function
- **Signature**:
  ```clojure
  (current-adapter-spec) → installed adapter spec map
  ```
- **Description**: "Give me the adapter fns to call." The value passed to `(rf/init! ...)`, or `nil` when no adapter is installed. Use for tools / routing / identity checks across the install / dispose lifecycle. For the discriminator keyword, use `current-adapter`.

#### `adapter-disposed?`

- **Kind**: function
- **Signature**:
  ```clojure
  (adapter-disposed?) → boolean
  ```
- **Description**: "Was the adapter torn down?" Returns `true` iff the most recent lifecycle event was a successful `destroy-adapter!` and no subsequent `install-adapter!` has fired. `false` for never-installed (fresh process) AND after a fresh install. Read-only — the breadcrumb is owned by the install / destroy pair. Use to distinguish `:rf.error/no-adapter-installed` (fresh process) from `:rf.error/adapter-disposed` (torn down).

The split between `current-adapter` (keyword) and `current-adapter-spec` (map) is principled. The keyword is for switch-like code that branches on substrate identity. The spec map is for code that needs to call adapter fns or compare adapter identity across reinstalls.

### The disposed-vs-never-installed distinction

These two states surface as different errors because they have different recovery paths:

| State | Detection | Typical fix |
|---|---|---|
| Never installed | `(current-adapter)` returns `nil`; `(adapter-disposed?)` returns `false` | Call `(rf/init! adapter)`. |
| Disposed | `(current-adapter)` returns `nil`; `(adapter-disposed?)` returns `true` | A new `(rf/init! adapter)` is required. Tooling that observed the previous install needs to re-attach. |

The two states share the "no current adapter" surface but answer different questions about the process's history. Per [006 §Disposed-vs-never-installed](../../spec/006-ReactiveSubstrate.md#disposed-vs-never-installed).

## Configuration (application-boot lane)

Back in the app-author lane: the `configure` fn is application boot's adjacent surface — process-level data knobs you typically set once at boot, possibly tweak in tests, and rarely touch in app code. (It is *not* an adapter-author surface — it tunes the runtime, not the substrate.)

### `configure`

- **Kind**: function
- **Signature**:
  ```clojure
  (configure key opts)
  ```
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
  (rf/configure! {:trace-buffer {:cascades-retained 200}})
  (rf/reg-frame :app/main {:on-create [:app/boot]})        ;; register the app frame
  (rdom/render
    [rf/frame-provider {:frame :app/main}                  ;; establish it at the root
     [views/root]]
    (js/document.getElementById "app")))
```

That's the whole boot, and it stays inside the **application-boot** lane end to end: `init!` installs the adapter and runtime capabilities (it creates no frame); the side-effecting requires register the handlers / subs / routes into the registrar; `reg-frame` + `frame-provider` create the app frame and establish it at the root, so every bare `dispatch` / `subscribe` under the tree resolves against it (and the frame's `:on-create` `[:app/boot]` runs the **frame-startup** lane); `configure` tunes the runtime; the substrate's render fn mounts the root view. No adapter-author surface appears — `init!` stands in for the whole install lane.

## See also

App-boot and frame-startup lanes (what app authors read):

- [01 — Core](01-core.md) — `reg-frame` / `make-frame` / `configure` rowed in registration and configuration.
- [Frames: isolated worlds](../guide/concepts/frames.md) — the app-boot narrative, and why `init!` doesn't create a frame.
- [Pattern — Boot](../../spec/Pattern-Boot.md) — the `:on-create` boot sequence, from chained events to a boot state machine.
- [counter example](https://github.com/day8/re-frame2/tree/main/examples/reagent/counter) — the minimal `init!` + `reg-frame` + `frame-provider` boot.

Adapter-author lane (what substrate authors read):

- [14 — Adapters](14-adapters.md) — per-substrate surface tables.
- [02 — Views](02-views.md) — adapter-side surfaces (UIx / Helix hooks, the shared React Context).
- [Spec 006 — Reactive Substrate](../../spec/006-ReactiveSubstrate.md) — the normative adapter contract.
