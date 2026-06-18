# Boot, Config, And Adapters

Status: draft finding.

## Crowding Signal

Boot has a clean central idea: install the substrate adapter, then create and
mount explicit frames. The public namespace also exposes lower-level adapter
operations and host/platform configuration, so the front door looks busier than
the model.

Current adjacent surfaces:

- `(rf/init! adapter)`
- `(rf/install-adapter! adapter)`
- `(rf/destroy-adapter!)`
- `(rf/current-adapter)`
- `(rf/current-adapter-spec)`
- `(rf/adapter-disposed?)`
- `(rf/configure! :epoch-history opts)`
- `(rf/configure! :elision opts)`
- `(rf/init-platform :server)` / `(rf/init-platform :client)`
- explicit frame creation via `make-frame` or `reg-frame`

Implementation evidence:

- `implementation/core/src/re_frame/core.cljc:2989-3019` implements
  `configure!` as a keyed dispatcher.
- `implementation/core/src/re_frame/core.cljc:3023-3051` exposes adapter
  install, dispose, and current-adapter reads.
- `implementation/core/src/re_frame/core.cljc:3067-3094` implements `init!` and
  states that it does not create a default frame.
- `implementation/core/src/re_frame/core.cljc:3101-3123` exposes
  `init-platform`.
- `docs/api/13-lifecycle.md` and `docs/api/14-adapters.md` teach `init!` for
  normal app boot and explicit frames for app state.
- `spec/008-Testing.md:700-742` uses `make-frame` directly for tests, often
  without needing a substrate adapter at all.

## Observed Use Cases

1. Browser app boot: install Reagent/UIx/Helix adapter once, create a frame,
   and mount under a provider.

2. Hot reload: `init!` should be idempotent and frame creation should be under
   caller control.

3. Unit tests: often create frames without installing any view adapter.

4. Adapter tests: need direct install/dispose/current-adapter operations.

5. SSR: needs explicit server/client platform and request-local frame creation.

6. Tooling: may need to read the current adapter, but should not guide normal
   app authors through install internals.

7. Runtime tuning: epoch history and elision are configured independently.

## Proposed Cleanup

Teach only this app boot shape:

```clojure
(rf/init! reagent/adapter)

(defonce frame
  (rf/make-frame {:id :app/main
                  :images [app-image]
                  :on-create [:app/init]}))
```

`init!` installs host capability. It does not create frames. Frame ownership is
explicit.

Move these to an adapter-author or advanced namespace section:

- `install-adapter!`
- `destroy-adapter!`
- `current-adapter`
- `current-adapter-spec`
- `adapter-disposed?`

Make `configure!` one map-shaped operation rather than a key plus opts family:

```clojure
(rf/configure! {:epoch-history {:depth 100}
                :elision       {:mode :public}})
```

Fold platform into adapter/host configuration where possible. If
`init-platform` must remain for SSR tests, document it as host setup, not as
normal app boot.

## Why This Is Better

A user should have to learn one boot sentence: initialize the adapter, then
create explicit frames. Everything else is substrate authoring, testing, or
host configuration.

The re-frame2 ethos has been moving away from ambient defaults. Boot should
reflect that. `init!` should not smell like it might create a hidden default
frame, and adapter internals should not sit beside the everyday startup call as
though they are peer choices.

## Fresh consolidation pass additions (2026-06-18)

**Schema validator-extension cluster: bless the bundle, demote the singletons**
(empirical lens). `re-frame.schemas` offers four ways to install validation fns
at boot - three single-fn setters `set-schema-validator!` / `set-schema-explainer!`
/ `set-schema-printer!`, plus the atomic bundle `set-schema-fns!` that does all
three. API.md notes the bundle is "the honest one-call substitute-Malli boot
pattern (so they never drift mid-boot)" - i.e. the singletons are the drift-prone
path the bundle exists to replace. All four are used (set-schema-validator! 68,
set-schema-fns! 43, set-schema-printer! 29, set-schema-explainer! 9), so this is
duplicate-idiom, not dead surface. Bless `set-schema-fns!`; demote the singletons.

## Implementation

- **Vehicle: docs/beads first, + one decision bead** if `configure!` moves from
  keyed-arity to map shape. No EP.
- Beads: (1) docs - the one boot sentence (init! adapter, then explicit frames);
  (2) move adapter internals (install-adapter! / current-adapter / ...) to an
  adapter-author section; (3) bless set-schema-fns!, demote the three schema
  setter singletons; (4) decision - configure! map shape.
- Behaviour-preserving; independent of the EP work.
