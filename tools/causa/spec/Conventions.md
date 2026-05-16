# Causa Conventions

Conventions for Causa source organisation that aren't normative re-frame2
spec but are worth pinning so panel-level work stays uniform across the
artefact.

## Panel facade + leaf split

When a panel is split into focused leaves under
`tools/causa/src/day8/re_frame2_causa/panels/`, the split MUST follow the
shape below. Four panels (`app_db_diff`, `ai_co_pilot`, `subscriptions`,
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
   names a panel registers. (Counter-example before remediation:
   `ai-co-pilot-views.cljs` contained three `reg-view` forms and the
   facade re-bound them via `def view views/view`. That re-bind is now
   gone.)

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
     (e.g. `app-db-diff/app-db-diff-view`,
     `ai-co-pilot/ai-co-pilot-rail`).

   The facade does **NOT** re-export every leaf's surface. Leaves are
   internal organisation; their `install!` and helpers are reached via
   the facade's `install!` chain or via direct `:require` from sibling
   leaves and per-leaf tests. Re-exporting bulk leaf surfaces (events,
   subs, feed projections, chrome helpers, style tokens) would invert
   the encapsulation the split exists to create.

   For pure-data helper bulk re-exports — the `<panel>_helpers.cljc`
   case where multiple leaves' pure fns roll up into a single stable
   facade for the helpers test — see the existing
   `subscriptions_helpers.cljc`, `ai_co_pilot_helpers.cljc`, and
   `mcp_server_helpers.cljc` files. That's a separate concern (pure
   helper aggregation) from the panel facade discussed here.

### View body delegation

The facade's `reg-view` body is either:

- **Inline**, when the body is small enough to read alongside the
  facade's intent (e.g. `app_db_diff.cljs` — ~50 hiccup lines on the
  composite-sub deref), OR
- **Delegating** to a single plain Reagent fn from
  `<panel>-views.cljs`, when the body would exceed the facade's
  cohesive scope:

  ```clojure
  (rf/reg-view subscriptions-view
    "The Subscriptions panel's root view."
    []
    [views/subscriptions-panel])
  ```

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
