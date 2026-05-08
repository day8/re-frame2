# 08 — From re-frame v1

If you've been writing re-frame for a while — say, since 2015, when re-frame v0.something was already a real thing — most of re-frame2 will feel familiar. The dominoes are still six. `app-db` is still one atom. `reg-event-db` and `reg-event-fx` still take an id and a handler. `subscribe` and `dispatch` still do what they always did.

This chapter is about *what changed* and *whether you should care*. Most of you can adopt re-frame2 with very little code change. Some of you will want to take advantage of new features. A few of you will care about the philosophical shift this version embodies.

## What didn't change

Most of re-frame2 is re-frame v1 with cleaner edges. The unchanged parts:

- **`app-db` is still a Reagent atom holding a single map.** Same shape, same conventions. Code that does `@(rf/subscribe [:foo])` still works.
- **The six dominoes.** Event dispatch → event handler → effect handling → query → view → DOM. Same pipeline, same semantics.
- **Pure handlers.** `reg-event-db` and `reg-event-fx` still take pure functions. The argument shapes are unchanged.
- **Subscriptions, including `:<-` chained subs.** The signal-graph composition rules are the same.
- **Effects-as-data.** The effect map keeps the `:db` slot and the `:fx` slot. v1's top-level `:dispatch`/`:dispatch-later`/`:dispatch-n` shorthands fold into `:fx` — see M-8 in [`spec/MIGRATION.md`](../../spec/MIGRATION.md). The `:fx` shape `[[fx-id args] ...]` is unchanged. The HTTP fx becomes the framework-shipped `:rf.http/managed` (see [chapter 06](06-doing-http-requests.md)); apps that hand-rolled their own `:http` keep working but should adopt managed for the closed-set failure shapes, retry, and reply addressing.
- **Reagent for the view layer.** Hiccup is hiccup. Form-1, Form-2, and Form-3 components all still work.
- **The event queue and run-to-completion semantics.** The drain still runs to completion before the view re-renders.
- **`re-frame-10x`, `re-frame-test`, `re-frame-undo`, and friends** — they still work. The trace API they consume is preserved.
- **`re-frame.core` is still the namespace.** Your `(:require [re-frame.core :as rf])` lines don't change.

If you took a re-frame v1 codebase and pointed it at re-frame2 with no other changes, **most of it would still work**. There's a small set of breaking changes, and they're all repairable by an automated migration; we'll get to those in a moment.

## What changed (the small things)

These are the friction-removing changes you'll notice on day one.

### Registration metadata

The middle slot of `reg-event-*` (and `reg-sub`, `reg-fx`, etc.) used to be the interceptors vector:

```clojure
;; v1
(rf/reg-event-fx :foo
  [interceptor-1 interceptor-2]                     ;; interceptors slot
  (fn [m event] ...))
```

In re-frame2 it can also be a metadata map. The metadata map carries reflection keys (`:doc`, `:spec`, `:tags`); the interceptor chain stays in the positional vector slot — `:interceptors` is **not** a metadata-map key:

```clojure
;; re-frame2 — new, optional. Metadata for reflection; interceptors positional.
(rf/reg-event-fx :foo
  {:doc  "What this event does."
   :spec FooEventSchema
   :tags #{:auth :critical}}
  [interceptor-1 interceptor-2]
  (fn my-foo-handler [m] ...))
```

All three forms — bare `(id handler)`, `(id [interceptors] handler)`, and `(id metadata [interceptors] handler)` — work. The function dispatches on the type of each non-id argument: a map is metadata, a vector is interceptors. Putting `:interceptors` inside the metadata-map is an authoring mistake; the runtime emits `:rf.warning/interceptors-in-metadata-map` and the chain is silently ignored. (See [Conventions §`:interceptors` is positional, not metadata](../../spec/Conventions.md#interceptors-is-positional-not-metadata-reg-event-).)

The new form gets you:

- A docstring queryable from the registry.
- An optional Malli schema, validated in dev.
- A name for the handler function (better stack traces, better tooling).
- Tags for filtering (useful for storybook-ish tooling).

You can adopt it gradually. Convert events that you're already touching for other reasons; leave the rest alone.

### Two-arg dispatch

In v1, dispatching to a non-default frame required an alternate function:

```clojure
;; v1
(rf/dispatch-with [:foo] some-frame-thing)
```

In re-frame2, every dispatch takes an opts map:

```clojure
(rf/dispatch [:foo])                          ;; default frame, no opts
(rf/dispatch [:foo] {:frame :todo})           ;; targeted frame
(rf/dispatch [:foo] {:fx-overrides {:http :stub}})  ;; for tests
```

`dispatch-with` and `dispatch-sync-with` are gone. The migration agent translates them to the two-arg form mechanically. If you weren't using them, nothing changes.

### `reg-event-fx` handler signatures

In v1, `reg-event-fx` handlers always took two args:

```clojure
(rf/reg-event-fx :foo
  (fn [{:keys [db]} [_ args]]                 ;; cofx + event vector
    ...))
```

In re-frame2, the single-argument form is also accepted, with the event vector available as `(:event m)`:

```clojure
(rf/reg-event-fx :foo
  (fn [{:keys [db event]}]                    ;; everything in one map
    (let [[_ args] event]
      ...)))
```

Both forms are first-class. The migration agent doesn't change existing two-arg handlers; the one-arg form is opt-in.

### Frame metadata on the cofx

`reg-event-fx` handlers receive a cofx map with a few new keys:

```clojure
(fn [{:keys [db event frame trace-id source]}]
  ;; :db        — same as before
  ;; :event     — the event vector (matches the destructured second arg in two-arg form)
  ;; :frame     — the frame this event is dispatched in (always present)
  ;; :trace-id  — optional, for tooling correlation
  ;; :source    — optional, e.g. :ui, :timer, :http, :machine, :repl
  ...)
```

These keys are *additive*. Existing handlers that don't use them are unaffected.

### Source coordinates and named handlers

`reg-event-*` and other registration macros now capture source coordinates (file, line, ns) and the registered function's name. This shows up in:

- Stack traces (instead of `fn` you see `handler-cart-item-remove`).
- 10x panels (registrations now have file paths to navigate to).
- AI tools (introspection produces meaningful coords).

You don't need to do anything to opt in; this happens automatically. To take fullest advantage, *name your handler functions*:

```clojure
;; v1 idiom
(rf/reg-event-db :cart.item/remove
  (fn [db [_ id]]                             ;; anonymous
    (update-in db [:cart :items] remove-by-id id)))

;; re-frame2 idiom
(rf/reg-event-db :cart.item/remove
  (fn handler-cart-item-remove [db [_ id]]    ;; named
    (update-in db [:cart :items] remove-by-id id)))
```

The name shows up in stack traces and tooling. The migration agent can do this rewrite for you on demand.

## What changed (the bigger things)

These are the load-bearing additions. They're optional — single-frame apps that don't use them are unaffected — but they're the reason for re-frame2 existing as a separate version.

### Frames

Multi-instance state, with shared handlers. See [chapter 04](04-views-and-frames.md). If you're a single-frame app, you'll mostly just notice that the default frame is now called `:rf/default` and `app-db` lives inside it.

In v1, there was effectively one frame, but it wasn't a first-class concept; `app-db` was a top-level Reagent atom. In re-frame2, `app-db` is a property of a frame. For most apps this is a relabelling; for apps that wanted multi-instance behaviour, it's a structural enabler.

The migration agent handles the renamings. Your code that does `@rf/app-db` is updated to `(rf/get-frame-db :rf/default)` — the new accessor returns the `app-db` value (a plain map), not a deref-able container. (Direct access to `re-frame.db/app-db` was always off-contract; it's even more so now.)

### Registered views

`reg-view` is new. It's the canonical way to declare a view in re-frame2. See [chapter 04](04-views-and-frames.md).

Plain Reagent functions still work. They get a runtime warning if rendered inside a non-default frame's subtree (because their `dispatch`/`subscribe` will silently target the default frame, which is rarely what you want). For single-frame apps, the warning never fires.

The migration agent doesn't convert plain Reagent fns to `reg-view`; that's a stylistic upgrade you opt into. For new code, prefer `reg-view`.

### State machines

The xstate-flavoured pattern in [chapter 05](05-state-machines.md). Entirely opt-in. No existing code is affected.

### Server-side rendering

[Chapter 07](07-server-side.md). Also entirely opt-in for clients-only apps; the architecture changes that make SSR possible are present whether or not you use SSR. If you don't, you don't notice them.

### Schemas everywhere (CLJS reference)

`:spec` metadata on every registration. Optional — apps without schemas continue to work — but recommended. See [Spec 010](../../spec/010-Schemas.md).

### Construction prompts

A new artefact ([Construction Prompts](../../spec/Construction-Prompts.md)) that AI agents use to scaffold new code. For human readers, it's a handy reference for "how do I add an X?" with idiomatic answers. For AI assistants in your codebase, it's the convention they read to produce code that fits your codebase's style.

### Flows — the `on-changes` interceptor, registered and toggleable

If you used v1's `on-changes` interceptor — "when these in-paths change, compute and write to that out-path" — re-frame2 has the same compute-on-input-change semantics, but as a **registered runtime artefact** (not wired into individual events) that you can register and clear via two reserved fx-ids.

```clojure
(rf/reg-flow
  {:id     :rectangle/area
   :inputs [[:width] [:height]]
   :output (fn [w h] (* w h))
   :path   [:area]})
```

A flow is what `on-changes` always pointed at — but as a first-class registered concept rather than an interceptor wired into a specific event's chain. Toggle it on with `[:rf.fx/reg-flow {...}]` from any event handler; toggle it off with `[:rf.fx/clear-flow id]`. Wizard steps that need a derivation only when active, feature gates that turn computations on for some users, advanced-mode-only state — all the cases `on-changes` couldn't reach because interceptors are statically wired.

Flows are explicitly a **niche convenience** — not a sub replacement, not a new dataflow paradigm. Most derived values stay subs; flows are the "I need this in `app-db`" escape hatch (per [chapter 04 §Computed values as state](04-views-and-frames.md#computed-values-as-state--the-flow-escape-hatch)). v1's `on-changes` migrates to `reg-flow` mechanically (M-21 in the migration rules); apps without `on-changes` are unaffected. Full contract: [Spec 013](../../spec/013-Flows.md).

### Three v1 surfaces removed (plus a few interceptors)

re-frame2 drops three large v1 surfaces, plus several smaller interceptor helpers. Each removal has a defined replacement; the migration agent ([MIGRATION.md](../../spec/MIGRATION.md)) handles the mechanical cases and flags the judgment-call ones.

**`^:flush-dom` event-vector metadata** is gone. v1 supported a reader-tag on dispatched event vectors that forced a DOM repaint between handlers — used for "show modal, then run a synchronous block." The modern equivalent is `:dispatch-later {:ms 0 :dispatch <event-vec>}`, which schedules through the host clock and yields one render tick before the next handler. Mechanical rewrite (M-16 in the migration rules).

**`reg-global-interceptor` / `clear-global-interceptor`** are gone. v1 had process-wide interceptors firing across every dispatch. v2 narrows the interceptor surface to two layers: frame-level (declared in `reg-frame` metadata) and handler-level. Cross-frame interceptors violate frame isolation — the new boundary is the frame, not the process. Single-frame apps migrate mechanically by moving the interceptor to the default frame's `:interceptors` vector. Multi-frame apps go to human review (the rewrite depends on intent — "every frame," "every event," or "originally just the default frame on accident"). M-17 in the migration rules.

**`reg-sub-raw`** is gone. The substrate now has explicit answers for every legitimate v1 use: subs that read app-db are `reg-sub`; subs that bridge a non-Reagent reactive source go through the Spec 006 adapter contract; subs that managed reaction lifecycle become state machines (entry/exit/data are the lifecycle hooks now). The remaining v1 patterns — subs with side effects, subs that hold state outside app-db — are anti-patterns re-frame2 deliberately removes, because they violate the revertibility contract. M-18 in the migration rules.

**Five standard interceptors removed** — `debug` (replaced by trace + 10x), `trim-v` (replaced by the canonical `[<id> <map>]` event shape, M-19), `on-changes` (replaced by flows, see above), `enrich` (replaced by flows + schemas), and `after` (redundant with `->interceptor`). The retained set is `inject-cofx`, `path`, `unwrap`, plus the `->interceptor` primitive. M-21 in the migration rules. If you really want one of the dropped helpers, copy the v1 implementation locally — they're each a few lines.

If your code uses any of these, the migration agent will find them and either rewrite or flag for human review. None of them are silent breakages — every call site is identifiable.

### The structured error contract

[Spec 009 §Error contract](../../spec/009-Instrumentation.md) defines structured error trace events: `:rf.error/handler-exception`, `:rf.error/schema-validation-failure`, `:rf.error/machine-action-exception`, and the closed `:rf.http/*` failure-category set ([Spec 014](../../spec/014-HTTPRequests.md)). These ride the same trace stream that 10x and re-frame-pair already consume. You hook in via the trace callback surface:

```clojure
(rf/register-trace-cb! ::sentry-fwd
  (fn [trace-event]
    (when (= :error (:op-type trace-event))
      (sentry/capture (sentry-shape trace-event)))))
```

In v1, errors propagated unstructured. In re-frame2, every error has a category, a payload, a recovery hint, and rides the trace stream. Tools can categorise; monitoring can integrate; users can decide. Production builds elide the trace surface entirely via `interop/debug-enabled?`, so the cost is dev-only.

## How to migrate

You have three options.

### Option 1: Just upgrade

Change the dependency. Run your tests. *Most things work*. The handful of breakages will surface as failing tests or runtime errors; fix them per the migration rules in [MIGRATION.md](../../spec/MIGRATION.md).

This is the recommended path for most apps. The migration story is designed to be small. Real-world re-frame v1 codebases have, in our experience, a single-digit number of fixes per ~10k lines of code, all minor.

### Option 2: Run the migration agent

[MIGRATION.md](../../spec/MIGRATION.md) is structured as an AI-agent prompt. Hand it to an AI coding assistant in your codebase, point at your project, and it will:

1. Identify all locations that need migration.
2. Apply the mechanical rules (Type A — automatic).
3. Flag locations that need human review (Type B — semantic, agent halts and asks).
4. Run your tests after each set of changes.
5. Report what changed and what still needs attention.

This is the AI-driven migration story. It's how re-frame2 keeps "mechanical upgrade" as a real property: the agent does the mechanical part; the human does the judgment-call part.

The agent doesn't add new features (`reg-view`, schemas, frames). It just translates your existing code to compile and run against re-frame2.

### Option 3: Modernise as you go

Don't migrate up-front. Just upgrade the dependency. As you touch each event handler, sub, view in the normal course of work, take the opportunity to:

- Add `:doc` and `:spec` metadata.
- Convert plain Reagent fns to `reg-view`.
- Adopt frames if you have a use case (you'll know).
- Convert ad-hoc state-flag logic to a state machine if it's now non-trivial.

Six months later, your codebase is mostly re-frame2-shaped, without ever having done a "migration" as a project.

## What stays the same in your daily workflow

If you migrate, here's what you keep doing exactly as before:

- **REPL-driven development.** Hot reload still works the same way. Re-evaluating a `reg-event-db` form still replaces the registered handler.
- **`re-frame-10x` for debugging.** Same panel, same trace stream. New trace events show up automatically (machine transitions, structured errors, hydration events) without you needing to teach 10x about them.
- **Testing with `re-frame-test` or your own helpers.** `run-test-sync` is preserved as a compatibility wrapper. For new tests, the v1 testing API ([Spec 008](../../spec/008-Testing.md)) is shaped for re-frame2 directly: `with-frame`, `dispatch-sync`, `compute-sub`, etc.
- **Your build setup.** shadow-cljs, figwheel, lein-cljsbuild — they all still work. re-frame2 is dependency-bumpable.
- **Your team's conventions.** The dynamic story is unchanged. Code reviews still ask the same questions. Onboarding still uses the same mental model.

## What you gain

The TL;DR if you're considering whether to upgrade:

- **Cleaner code over time.** The metadata-map registration form is genuinely nicer. So is `reg-view`. So is `reg-frame`.
- **Optional but available power.** Frames, state machines, SSR, schemas — there when you need them, invisible when you don't.
- **AI assistance that works.** Construction Prompts, Spec-Schemas, the queryable registry — these turn AI coding assistants into much more reliable collaborators in your re-frame2 codebase.
- **A path to other-language ports.** If your team has a TypeScript or Python client elsewhere, the re-frame2 spec is implementable in those hosts. The pattern is portable; you can have the same shape across stacks.

The cost: the migration itself, which for most codebases is small. The risk: some breakage during migration that you'd rather not deal with on a deadline. The fix: see option 3 — just upgrade the dependency, run tests, fix breakage as it surfaces. You don't have to take all of it at once.

## A final note

re-frame v1 is being maintained, not deprecated. Apps that don't want to migrate can stay on v1 indefinitely. The reasons to migrate are to gain new capabilities (multi-frame, SSR, AI-amenability) and to position for the future. None of them are "v1 is going away."

But re-frame2 is where new development happens. New features, new tooling integrations, new ports to other languages — those land here. v1's stability is real; v2's momentum is real.

Migrate when you have a reason to.
