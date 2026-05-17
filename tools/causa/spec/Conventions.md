# Causa Conventions

Conventions for Causa source organisation that aren't normative re-frame2
spec but are worth pinning so panel-level work stays uniform across the
artefact.

## Panel facade + leaf split

When a panel is split into focused leaves under
`tools/causa/src/day8/re_frame2_causa/panels/`, the split MUST follow the
shape below. Three panels (`app_db_diff`, `subscriptions`,
`mcp_server`) shipped to this convention via the rf2-nb8if remediation
PR; `time_travel` predates it and matches by independent design.

The canonical exemplar is `app_db_diff.cljs` — a small facade body, the
panel's `reg-view` lives in the facade, leaves expose plain functions
plus `install!`. Where the facade view body would be too large to read at
a glance, the facade `reg-view` delegates to a plain Reagent fn from
`<panel>-views.cljs` (as `subscriptions.cljs` does — see "View body
delegation" below).

### Required shape

1. **Facade owns every public `reg-view`.** The panel's externally-named
   `reg-view` calls live in the facade namespace, never in a leaf. The
   facade is the one place a maintainer reads to discover which view
   names a panel registers.

2. **Leaves do not call `reg-view`.** Leaves expose plain Reagent
   functions (e.g. `(defn header [opts] [:header ...])`), pure helpers,
   or `install!` for sub/event registrations. A grep for `reg-view`
   under `panels/<panel>_*.cljs[c]` outside the facade should return
   nothing.

3. **`install!` is idempotent and returns `nil`.** The facade's
   `install!` chains its leaf `install!`s (alphabetical order is not
   required — call order should match the panel's natural dependency
   order: subs before events when events read sub names, etc.) and
   explicitly returns `nil`. Returning the chain's last value (truthy
   or falsy depending on the last `reg-*` form) is forbidden — callers
   should not be able to depend on a result.

   ```clojure
   (defn install!
     "Idempotent install for the <Panel>'s Causa-side registrations."
     []
     (subs/install!)
     (events/install!)
     nil)
   ```

4. **Re-exports are minimal and intentional.** The facade re-exports:
   - `install!` (always, as the panel's installation entry point).
   - View vars that callers (typically `shell.cljs`) reference by name
     (e.g. `app-db-diff/Panel`).

   The facade does **NOT** re-export every leaf's surface. Leaves are
   internal organisation; their `install!` and helpers are reached via
   the facade's `install!` chain or via direct `:require` from sibling
   leaves and per-leaf tests. Re-exporting bulk leaf surfaces (events,
   subs, feed projections, chrome helpers, style tokens) would invert
   the encapsulation the split exists to create.

   For pure-data helper bulk re-exports — the `<panel>_helpers.cljc`
   case where multiple leaves' pure fns roll up into a single stable
   facade for the helpers test — see the existing
   `subscriptions_helpers.cljc` and `mcp_server_helpers.cljc` files.
   That's a separate concern (pure helper aggregation) from the
   panel facade discussed here.

### View body delegation

The facade's `reg-view` body is either:

- **Inline**, when the body is small enough to read alongside the
  facade's intent (e.g. `app_db_diff.cljs` — ~50 hiccup lines on the
  composite-sub deref), OR
- **Delegating** to a single plain Reagent fn from
  `<panel>-views.cljs`, when the body would exceed the facade's
  cohesive scope. The leaf is invoked as a **plain function call**
  (parens), not a Reagent component vector (brackets):

  ```clojure
  (rf/reg-view Panel
    "The Subscriptions panel's root view."
    []
    (views/subscriptions-panel))    ; parens — leaf body inlined
  ```

  **Do not write `[views/subscriptions-panel]` here.** The vector
  form would mount the leaf as a separate plain Reagent fn component;
  it would drop out of the surrounding frame and any `subscribe` /
  `dispatch` inside the leaf would silently route to `:rf/default`
  (Spec 004 §Plain Reagent fns / Spec 006 §706). The plain-call form
  keeps the leaf body executing within the facade reg-view wrapper's
  render — the wrapper IS the in-flight Reagent component, so
  `current-frame` reads `:rf/causa` from React context and the leaf's
  subs/dispatches see the Causa frame. (rf2-043uz pinned this — an
  earlier slash-popover surface never opened because the input-row
  leaf's input-text subscribe was routed to `:rf/default` while the
  dispatch wrote to `:rf/causa`.)

  The same rule applies recursively to sibling leaves the view-leaf
  itself invokes (`chrome` / `feed` / `input` / `conversation` /
  `badges` / `rows` / `chain` / `sections`): if the sibling leaf
  contains a `subscribe` whose key lives on the panel's frame, call
  it with parens, not brackets. Sibling leaves that only `dispatch`
  with an explicit `{:frame :rf/causa}` opt can use either form
  (brackets are fine if a separate render boundary is wanted for
  React-key / memoisation reasons).

  The leaf fn (`subscriptions-panel`) is a plain `defn`, not a
  `reg-view`. The facade is still where the registration happens; the
  leaf is the implementation.

A facade `reg-view` body that imports a leaf-side `reg-view` (i.e. the
leaf calls `reg-view` and the facade `def`-rebinds) is the divergence
remediation explicitly removed; do not re-introduce it.

### Per-leaf smoke tests

Every implementation leaf SHOULD ship at least one smoke test in its own
`<leaf>_cljs_test.cljs[c]` file. ~20 lines is a reasonable target.

- **View leaves** (Reagent fns rendering hiccup): render once, assert no
  throw and a key `data-testid` hook is present in the produced tree.
- **Events leaves** (one or more `reg-event-*` calls inside `install!`):
  call `install!`, dispatch one happy-path event, assert the resulting
  app-db transition.
- **Subs leaves** (one or more `reg-sub` calls inside `install!`): call
  `install!`, read one representative sub, assert its shape.
- **Helper / projection leaves** (pure CLJC fns): call the pure fn,
  assert the output shape.

The smoke test is per-leaf, not per-symbol. Its job is to pin the leaf
as an independently usable unit so a future regression that drops a
`reg-*` form from the facade's `install!` chain (or moves a helper to a
new leaf) is caught at the leaf level, not only at the umbrella level
through downstream "handler not found" failures.

The umbrella `register-causa-handlers!` path remains the integration
contract; per-leaf smoke tests are the unit contract.

## Mount conventions

Conventions for `mount.cljs` — Causa's DOM-side mount machinery. The
normative mount contract lives in
[`011-Launch-Modes.md`](./011-Launch-Modes.md) §Mount lifecycle; this
section pins source-organisation rules that aren't normative spec but
keep the mount surface uniform across the artefact.

### Singleton mount-state per process

Causa mounts **exactly one** in-app shell per browser process via the
`defonce`-guarded `mount-state` atom (`mount.cljs`). A second mount
surface — `popout-state` — covers the optional pop-out window; the two
atoms are independent singletons and do NOT share state. Both survive
shadow-cljs `:after-load` reloads via `defonce`. Production sessions
never tear either down — `teardown!` is test-only.

### `defonce`-across-reload for every mount sentinel

Every mount-adjacent piece of state — `mount-state`, `popout-state`,
the diagnostic atom, the auto-open sentinel, the keybinding sentinel
— is `defonce`-guarded so `:after-load` reruns the preload's side-
effects without re-attaching listeners, replacing trace callbacks, or
re-creating the mount node. The user's currently-open Causa panel
MUST remain open across an `:after-load` with internal state intact
(per `011-Launch-Modes.md` §Idempotency under hot-reload).

### Data-attribute scheme: `data-rf-causa-mode` is the single axis

The mount root carries `data-rf-causa-mode` (`"inline"` / `"overlay"`
/ `"popout"`). The shell node carries the same attribute under the
same name. Per rf2-zkfiz Q1-9 the earlier `data-mode` echo on the
shell was a duplicate axis and is gone — testbeds, browser-test
assertions, and CSS selectors target the rf-causa-prefixed name
everywhere.

### Mount-state `:mode` vocabulary

`mount-state`'s `:mode` slot is exactly `:inline` (the default
true-inline shell from `open!`) or `:overlay` (the legacy/debug
overlay from `open-overlay!`). `popout-state` carries `:mode :popout`
on its own singleton. The two atoms do NOT cross-reference each
other's mode vocabulary. The pre-rf2-sbfb7 `:docked` value (body-
padding dock surface) is gone with the rest of the `dock!` /
`undock!` API.

### `teardown!` is test-only; tests own the keybinding detach

`teardown!` clears both mount singletons and removes their DOM nodes;
it does NOT detach the global `Ctrl+Shift+C` keydown listener that
`preload/init!` attaches via `keybinding/attach!`. The detach lives
in the test fixture because `mount.cljs` cannot require
`keybinding.cljs` (the dependency runs the other way — keybinding
requires mount for `toggle!`). Test suites driving multiple
`teardown!` → re-mount cycles MUST call
`(day8.re-frame2-causa.keybinding/detach!)` themselves between runs
(per rf2-zkfiz Q1-10); production sessions never call `teardown!` so
the listener never leaks.

## Panel-id ordering inside the registry

Per `registry.cljs` the panels' `install!` calls run in alphabetical
panel-id order. When you add a new panel facade, slot its `install!` into
the alphabetical list and keep the comment in lockstep.

## See also

- `tools/causa/spec/017-Test-Coverage-Matrix.md` — feature-level coverage
  matrix for browser gates.
- Repo-root `spec/Conventions.md` — re-frame2-wide conventions
  (`:rf/*` reserved namespaces, reserved app-db keys, etc.). Causa's
  conventions live here; framework conventions live there.
