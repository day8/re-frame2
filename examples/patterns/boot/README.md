# Booting an app, step by step

Before this app can show its real UI, it has to wake up: fetch config,
load the few things the first screen needs, then hand over. This example
runs that whole startup in front of you. You watch a progress screen step
through its phases — configuring, loading, hydrating — and then the real
app appears. If a load fails, you get an error screen with a **Retry**
button instead. It runs on its own; there's no server to set up.

The startup sequence is a [state
machine](../../../docs/machines/concepts.md) called `:app/boot`, and
that's the idea worth taking away:

> **Startup is a lifecycle, not a pile of callbacks.**

The usual way to boot is a chain of side effects in a view's mount hook
or a top-level `js` block. It works until a fetch fails, two loads could
have run in parallel, or you ask "what state is the app in right now?" —
and the only answer is "somewhere in that callback pyramid." A machine is
a better fit. Boot *is* a lifecycle: named stages, a happy path, a
failure branch, a retry. As a machine, the boot logic is one piece of
data you read top to bottom — not control flow scattered across handlers.
It's the canonical shape from
[`spec/Pattern-Boot.md`](../../../spec/Pattern-Boot.md), made runnable.

## What this example demonstrates

The `:app/boot` machine moves through four states, plus a failure state:

```
:configuring ──► :loading-deps ──► :hydrating ──► :ready
       │                │
       └────────────────┴──► :failed
```

Read it as a story:

- **`:configuring`** — fetch config first, because everything else
  needs it. One [`:spawn`](../../../docs/machines/glossary.md#spawn)ed
  child loader GETs `/api/config.json`. On success the machine moves to
  `:loading-deps`; on failure, to `:failed`.
- **`:loading-deps`** — the parallel step. Three loads (routes, feature
  flags, user) don't depend on each other, so `:spawn-all` starts three
  child loaders at once. The machine waits for *every* child to finish
  (`:join :all`). If any one fails, `:on-any-failed` goes to `:failed`
  and cancels the rest — no requests left dangling.
- **`:hydrating`** — the loads are done, but the data is sitting in a
  staging area. This state copies it into the real top-level
  [app-db](../../../docs/core/glossary.md#app-db) keys (`:config`,
  `:flags`, `:user`, `:routes`), then moves to `:ready`.
- **`:ready`** — done. The main app
  [view](../../../docs/core/glossary.md#view) mounts only now. Its
  subscriptions never see a half-loaded app-db, because they don't
  render until the data is all there.
- **`:failed`** — done, but you can retry. The view shows an error
  screen with a retry button. Clicking it dispatches
  `[:app/boot [:boot/restart]]` — a real
  [event](../../../docs/core/glossary.md#event) that re-runs the boot
  from `:configuring`. (It's `:boot/restart`, *not* the
  `:rf.machine/start` creation marker. That marker only kicks a machine
  to life once; it does nothing on an existing machine, so re-boot has
  to be an ordinary transition.)

The child loader is its own machine, `:boot/loader`: GET a URL, branch
on the [reply](../../../docs/resources/glossary.md#reply-map), report
back. One machine, four instances — config, routes, flags, user — told
apart only by the `:data` each was spawned with. That reusable child is
the second pattern worth taking from this example.

## Why this shape

A few choices here are deliberate. Each one shows how the pieces fit
together.

1. **Boot is a machine, not a view lifecycle.** The whole sequence lives
   in `:app/boot` — not in a view's `:on-mount`, not in a top-level `js`
   side effect, not in a hand-rolled `:dispatch` chain. The view tree's
   `root-view` does one thing: it reads `:app.boot/state` and renders
   the main app, the progress screen, or the failure screen. The loading
   logic stays in one place instead of being spread across every
   sub-view as a per-component loading skeleton.

2. **Parallel work is `:spawn-all`, sequential work is `:spawn`.** Three
   of the four loads don't depend on each other, so they use `:spawn-all`
   with `:join :all` — fan out, wait for all, jump to `:failed` on the
   first error. Config is the one load the others need, so it gets its
   own `:spawn` up front. The grammar lets you say which is which.

3. **One reusable child machine, four instances.** `:boot/loader`
   fetches one URL and reports its result. The four instances differ
   only in the `:data` they're spawned with — `:parent-id`, `:child-id`,
   `:staging-key`, `:url` — set by the spawn-spec's `:data` function,
   which can read the parent's snapshot at the moment of spawn. Four
   loaders, no duplicated fetch logic.

4. **Host config flows through `:data`, never a global.** The
   `:configuring` load returns an `:api-base`. The `:promote-staged`
   action records it into the boot machine's *own* `:data` on the way
   out of `:configuring`. The three `:loading-deps` children then read
   that `:api-base` off the parent's snapshot — via their `:data`
   function — and build their own URLs from it. So the boot machine is
   the *only* place that reads host config; everything below it gets the
   value as plain data. No action reaches into a host global. This is
   the canonical Pattern-Boot parameter shape (see
   [`spec/Pattern-Boot.md` §Parameters](../../../spec/Pattern-Boot.md#parameters)).

5. **A staging slot carries child results to the parent.** You might
   expect each child's done event to *carry* its loaded data up to the
   parent. For the single `:spawn` (config), it does. But `:spawn-all`
   is different. Per [Spec 005](../../../spec/005-StateMachines.md), the
   runtime reads the `:on-child-done` / `:on-child-error` events only to
   track the join — they never reach the parent's `:on` table, and the
   join-resolution event carries no per-child data. So the canonical way
   to thread loaded data out of a join is a staging slot in app-db: each
   child writes its result to `[:boot/staging <staging-key>]` before
   signalling done, and the whole slot is read back on entry to
   `:hydrating` (by the `:boot/apply-hydration` handler that
   `:enter-hydrating` dispatches).
   This also means the loaded data lives in app-db the whole time — so
   it's visible in the pair tools and snapshottable for SSR hydration.

6. **No backend, by design.** The example runs on its own — there's no
   server behind it. The four endpoints are served by a per-URL canned
   stub. It overrides `:rf.http/managed` on the frame and delegates to
   the framework-shipped `:rf.http/managed-canned-success` (per Spec 014
   §Testing), so the [reply](../../../docs/resources/glossary.md#reply-map)
   shape is exactly what a live server would produce. The stub also adds
   a 60 ms `:dispatch-later` delay on purpose. Without it the boot
   finishes in a single drain and you'd never *see* the progress screen
   step through its phases.

## Running it

```sh
shadow-cljs watch examples/boot
```

Then open the dev server it prints.

## Files

```
core.cljs               — mount + boot trigger + demo HTTP stubs
boot.cljs               — :app/boot machine + :boot/loader child machine
views.cljs              — boot-progress + main-app + failure views
schema.cljs             — Malli schemas (BootData, LoaderData, Config, Flags, ...)
index.html
```

## Cross-references

- [`examples/real-apps/realworld_http/`](../../real-apps/realworld_http/) — the canonical
  app-shell example. This `boot/` example runs first, before the
  realworld app shows. A real app would have both.
