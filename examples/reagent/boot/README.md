# boot — Pattern-Boot example

A runnable demonstration of the canonical re-frame2 application
boot shape, per [`spec/Pattern-Boot.md`](../../../spec/Pattern-Boot.md).

## What this example demonstrates

A single boot state machine (`:app/boot`) owns the application's
initialisation graph. The boot sequence is:

```
:configuring ──► :loading-deps ──► :hydrating ──► :ready
       │                │
       └────────────────┴──► :failed
```

- `:configuring` — a single `:spawn`d child loader fetches
  `/api/config.json` via `:rf.http/managed`. On success, the boot
  machine advances to `:loading-deps`; on failure, to `:failed`.
- `:loading-deps` — `:spawn-all` fans out THREE parallel child
  loaders (routes, feature flags, initial user). The parent reaches
  the next state only when EVERY child reports done (`:join :all`).
  Any child failure routes to `:failed` immediately
  (`:on-any-failed`), cancelling the surviving siblings via the
  standard `:spawn-all` cancel-on-decision cascade.
- `:hydrating` — promotes the staged child payloads from
  `[:boot/staging ...]` into the canonical top-level slices
  (`:config`, `:flags`, `:user`, `:routes`), self-transitions to
  `:ready` once the writes land.
- `:ready` — terminal. The main view only mounts after the boot
  machine reaches this state.
- `:failed` — terminal. The view renders an error screen with a
  retry button; the retry dispatches `[:app/boot [:rf/start]]`
  back into the boot machine.

## Key Pattern-Boot decisions

1. **Pre-mount state machine, not view lifecycle.** The boot logic
   lives in the `:app/boot` machine — not in a view's `:on-mount`,
   not in top-level `js` side effects, not in a chained `:dispatch`
   sequence. The view tree only renders the main app once the
   boot machine reaches `:ready`.

2. **`:spawn-all` for parallel dependencies.** Three of the four
   loads are independent (routes / flags / user); the canonical
   shape is `:spawn-all` with `:join :all`, not three sequential
   `:spawn`s. The parent reaches `:hydrating` once the join
   resolves; any single failure short-circuits to `:failed`.

3. **One reusable child machine, four instances.** The
   `:boot/loader` machine fetches a single URL and reports back.
   The four instances are distinguished by their `:data` slot, set
   via the spawn-spec `:data` fn (Pattern-AsyncEffect mechanism 2).

4. **Staging slot for child-to-parent data flow.** Per Spec 005,
   the runtime intercepts `:on-child-done` / `:on-child-error` for
   `:spawn-all` join bookkeeping — these events are NOT fed into
   the parent's `:on` lookup. The canonical Pattern-Boot shape
   for threading loaded payloads from children to the parent is a
   staging slot in app-db (`[:boot/staging <child-id>]`): each
   child writes its payload before dispatching the join-completion
   event; the parent reads the slot in `:enter-hydrating`.

5. **Demo stubs via `:rf.http/managed-canned-success`.** The
   example runs standalone — no backend. The four mocked endpoints
   are served by a per-URL canned-stub override on the default
   frame, per Spec 014 §Testing.

## Running it

The example is wired into the standard shadow-cljs and Playwright
infrastructure:

```sh
# From the implementation directory:
npx shadow-cljs compile examples/boot

# Then from the repo root:
npm run test:examples       # Playwright smoke
npm run test:cljs           # Headless boot machine tests
```

## Files

```
core.cljs               — mount + boot trigger + demo HTTP stubs
boot.cljs               — :app/boot machine + :boot/loader child machine
views.cljs              — boot-progress + main-app + failure views
schema.cljs             — Malli schemas (BootSnapshot, Config, Flags, ...)
test/boot/boot_test.cljs — headless tests (machine progression, deps
                          resolution, failure path)
index.html
boot.spec.cjs           — Playwright smoke
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
