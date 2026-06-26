# boot — Pattern-Boot example

Every app has to wake up before it can run: fetch some config, load a
few things the first screen can't render without, then hand control to
the real UI. The naive way is a chain of side effects stuffed into a
view's mount hook or a top-level `js` block — and it works right up
until a fetch fails, or two of the loads could have run in parallel, or
someone asks "what *state* is the app in right now?" and the only answer
is "somewhere in that callback pyramid."

This example takes the other road. The whole boot sequence is a [state
machine](../../../docs/machines/concepts.md) — `:app/boot` — and that
single decision is the thing worth reading here. Boot *is* a lifecycle:
named stages, a happy path, a failure branch, a retry. That's exactly
the shape a machine is for, so the boot logic becomes one piece of data
you can read top to bottom, rather than control flow scattered across
handlers. It's the canonical shape from
[`spec/Pattern-Boot.md`](../../../spec/Pattern-Boot.md), made runnable.

## What this example demonstrates

The `:app/boot` machine walks four states, plus a failure sink:

```
:configuring ──► :loading-deps ──► :hydrating ──► :ready
       │                │
       └────────────────┴──► :failed
```

Read it as a story:

- **`:configuring`** — fetch app config first, because everything
  downstream needs it. A single [`:spawn`](../../../docs/machines/glossary.md#spawn)ed
  child loader machine GETs `/api/config.json`. On success the boot
  machine advances to `:loading-deps`; on failure, straight to
  `:failed`.
- **`:loading-deps`** — now the parallel phase. Three loads (routes,
  feature flags, initial user) are independent of one another, so
  `:spawn-all` fans out three child loaders at once. The parent only
  moves on when *every* child reports done (`:join :all`). The instant
  any one child fails, `:on-any-failed` routes to `:failed` and the
  surviving siblings are cancelled — no orphaned in-flight requests.
- **`:hydrating`** — the loads are done but their payloads are sitting
  in a staging area. This state promotes them into the canonical
  top-level [app-db](../../../docs/guide/glossary.md#app-db) slices
  (`:config`, `:flags`, `:user`, `:routes`), then self-transitions to
  `:ready` once the writes land.
- **`:ready`** — terminal, and the whole point of the gate: the main
  app [view](../../../docs/guide/glossary.md#view) only mounts once the
  machine reaches this state. Its subscriptions never see a half-loaded
  app-db, because they don't render until the data is all there.
- **`:failed`** — terminal, but re-entrant. The view renders an error
  screen with a retry button; clicking it dispatches
  `[:app/boot [:boot/restart]]` — a real [event](../../../docs/guide/glossary.md#event)
  back into the machine that re-runs the boot from `:configuring`. (Note
  it's `:boot/restart`, *not* the `:rf.machine/start` creation marker:
  that marker is a one-shot birth kick and is inert once the machine
  already exists, so re-boot has to be an ordinary transition.)

The child loader is its own machine, `:boot/loader`: GET a URL, branch
on the [reply](../../../docs/resources/glossary.md#reply-map), report
back. One spec, four instances — config, routes, flags, user — told
apart only by the `:data` each was spawned with. The reusable-child
shape is the second thing worth stealing from this example.

## Why this shape

A few decisions in here are deliberate, and each one is a small lesson
in how the pieces compose.

1. **Boot is a machine, not a view lifecycle.** The whole sequence lives
   in `:app/boot` — not in a view's `:on-mount`, not in a top-level `js`
   side effect, not in a hand-rolled `:dispatch` chain. The view tree's
   `root-view` does exactly one thing: it reads `:app.boot/state` and
   renders the main app, the progress screen, or the failure screen. All
   the loading logic is consolidated in one place instead of smeared
   across every sub-view as a per-component loading skeleton.

2. **Parallel work is `:spawn-all`, sequential work is `:spawn`.** Three
   of the four loads don't depend on each other, so the honest shape is
   `:spawn-all` with `:join :all` — fan out, wait for all, short-circuit
   to `:failed` on the first error — not three sequential `:spawn`s
   pretending to be a pipeline. Config is the one genuinely-sequential
   load (the rest need its result), so it gets a lone `:spawn` up front.
   The grammar lets you say which is which.

3. **One reusable child machine, four instances.** `:boot/loader`
   fetches a single URL and reports its payload. The four instances
   differ only in the `:data` slot they're spawned with — `:parent-id`,
   `:child-id`, `:staging-key`, `:url` — planted by the spawn-spec's
   `:data` function, which can read the parent's snapshot at the moment
   of spawn. Four loaders, zero duplicated fetch logic.

4. **Host config threads through `:data`, never a global.** Here's the
   neat bit. The `:configuring` load returns an `:api-base`; the
   `:promote-staged` action records it into the boot machine's *own*
   `:data` on the way out of `:configuring`. The three `:loading-deps`
   children then read that promoted `:api-base` off the parent's
   post-action snapshot — via their `:data` function — and build their
   own URLs from it. So the boot machine is the *only* place that ever
   reads host config; everything downstream threads it as plain data. No
   action body reaches into a host global. This is the canonical
   Pattern-Boot parameter shape (see
   [`spec/Pattern-Boot.md` §Parameters](../../../spec/Pattern-Boot.md)).

5. **A staging slot carries child payloads to the parent.** You might
   expect each child's completion event to *carry* its loaded payload up
   to the parent. For the single `:spawn` (config), it does. But for
   `:spawn-all`, per [Spec 005](../../../spec/005-StateMachines.md) the
   runtime intercepts the `:on-child-done` / `:on-child-error` events
   purely for join bookkeeping — they're never fed into the parent's
   `:on` lookup, and the synthesised join-resolution event carries no
   per-child data. So the canonical shape for threading loaded payloads
   out of a join is a staging slot in app-db: each child writes its
   result to `[:boot/staging <child-id>]` before signalling done, and
   the parent reads the slot back in `:enter-hydrating`. A happy side
   effect — the loaded data lives in app-db the whole time, so it's
   inspectable in the pair tools and snapshottable for SSR hydration.

6. **No backend, by design.** The example runs standalone — there's no
   server behind it. The four endpoints are served by a per-URL canned
   stub that overrides `:rf.http/managed` on the frame and delegates to
   the framework-shipped `:rf.http/managed-canned-success` (per Spec 014
   §Testing), so the [reply](../../../docs/resources/glossary.md#reply-map)
   shape is exactly what a live server would produce. The stub even adds
   a 60 ms `:dispatch-later` delay on purpose — without it the boot
   resolves in a single drain and you'd never *see* the per-phase
   progress screen.

## Running it

The example builds via shadow-cljs:

```sh
# From the implementation directory:
npx shadow-cljs compile examples/boot

# Then from the repo root:
npm run test:cljs           # Headless boot machine tests
```

Per the test-free examples policy there is no per-example
Playwright spec and no `test/` tree under this example. The headless
regression coverage for the Pattern-Boot trajectory (machine
progression, per-child dependency-resolution identity threading, and
the failure path) lives in `re-frame.boot-cljs-test`
(`implementation/adapters/reagent/test/re_frame/boot_cljs_test.cljs`),
which drives this example's production source and runs under
`npm run test:cljs` alongside the framework gates (see
[`examples/README.md`](../../README.md)).

## Files

```
core.cljs               — mount + boot trigger + demo HTTP stubs
boot.cljs               — :app/boot machine + :boot/loader child machine
views.cljs              — boot-progress + main-app + failure views
schema.cljs             — Malli schemas (BootData, LoaderData, Config, Flags, ...)
index.html
```

## Cross-references

- [`spec/Pattern-Boot.md`](../../../spec/Pattern-Boot.md) — the
  normative pattern doc.
- [`spec/005-StateMachines.md` §Spawn-and-join via
  `:spawn-all`](../../../spec/005-StateMachines.md#spawn-and-join-via-spawn-all) —
  the substrate the boot machine uses for the parallel dependency
  phase.
- [`spec/014-HTTPRequests.md`](../../../spec/014-HTTPRequests.md) —
  `:rf.http/managed` is the fx the child loaders use.
- [`examples/reagent/realworld/`](../realworld/) — the canonical
  app-shell example; this `boot/` example slots in upstream of
  the realworld pattern (a real app would have BOTH).
