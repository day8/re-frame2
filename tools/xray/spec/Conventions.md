# Xray Conventions

Conventions for Xray source organisation that aren't normative re-frame2
spec but are worth pinning so panel-level work stays uniform across the
artefact.

## Reserved namespace — `:rf.xray/*` (Xray-owned)

Xray is a [**canonical devtool**](../../../spec/Tool-Pair.md) under the
framework's single-root convention, registered framework-distance-zero
alongside `:rf.epoch/*`. Its events, subs, fxs, app-db keys, trace
operations, and boot-time `configure!` keys live under the `:rf.xray/*`
sub-namespace.

**Xray owns the `:rf.xray/*` member set; the framework
[`spec/Conventions.md`](../../../spec/Conventions.md) reserves the
sub-namespace.** The canonical config-key roster + per-key semantics live in
[`015-Configuration.md`](./015-Configuration.md#configuration-keys) — this
document does not re-enumerate them. This mirrors Story's
[`:rf.story.*` carve-out](../../story/spec/Conventions.md): the framework
reserves the segment under its `:rf.*` root; the tool owns the closed member
set.

Third-party libraries MUST NOT register under `:rf.*` (they own their own
top-level prefix per
[framework Conventions §Library-owned prefixes](../../../spec/Conventions.md#library-owned-prefixes)).

## Panel facade + leaf split

When a panel is split into focused leaves under
`tools/xray/src/day8/re_frame2_xray/panels/`, the split MUST follow the
shape below.

**The panel's root view is an `rf.fresco/defview` BOUNDARY, not an
`rf/reg-view` (rf2-k97c.3).** Every shipped panel root is one — the
dynamic facades and the five Static sub-tab panels alike — so read every
rule below in `defview` terms. The SPLIT itself did not change with that
migration: the facade still owns the declaration and leaves still expose
plain functions. What changed is that a boundary is a React function
component rather than a callable, so a facade a Reagent parent mounts
declares an `rf.fresco/as-component` bridge beside the boundary —
`app_db_diff.cljs` and `reactive_panel.cljs` name theirs `Panel-bridge`;
`routing.cljs` keeps the public name `Panel` for the bridge and marks the
boundary `PanelView` private.

The canonical exemplar is `app_db_diff.cljs` — the `defview` body is the
READ and nothing else, handing the values it read to `panel-tree`, a
plain `defn` in the same file that returns the markup. Leaves expose
plain functions plus `install!`. Where the markup fn is large enough to
want its own file it moves to a view leaf and the facade calls it exactly
the same way (as `reactive_panel.cljs` does — see "View body delegation"
below).

### Required shape

1. **Facade owns every public view declaration.** The panel's
   externally-named `rf.fresco/defview`, and the `as-component` bridge
   beside it, live in the facade namespace. The facade is the one place
   a maintainer reads to discover which view names a panel declares.

   The one variant is a **directory-shaped** panel, where the facade is
   a thin entry file over a `panels/<panel>/` directory: there the
   `defview` lives in the directory's `view.cljs` and the facade
   re-exports it under the public name (`epoch_panel.cljs`'s
   `(def Panel view/Panel)` over `panels/epoch/view.cljs`). The rule it
   preserves is the same one — ONE file names the panel's public views,
   and it is the file a reader opens first.

2. **Leaves declare no views.** Leaves expose plain functions (e.g.
   `(defn header [opts] [:header ...])`), pure helpers, or `install!`
   for sub/event registrations. A search for `defview` or `reg-view`
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
     "Idempotent install for the <Panel>'s Xray-side registrations."
     []
     (subs/install!)
     (events/install!)
     nil)
   ```

4. **Re-exports are minimal and intentional.** The facade re-exports:
   - `install!` (always, as the panel's installation entry point).
   - View vars that callers reference by name (e.g.
     `app-db-diff/Panel`), and the `as-component` bridge beside them —
     `panel-registry/reg-l4-tab!` requires a CALLABLE, which a boundary
     is not, so it is the bridge that gets registered.

   The facade does **NOT** re-export every leaf's surface. Leaves are
   internal organisation; their `install!` and helpers are reached via
   the facade's `install!` chain or via direct `:require` from sibling
   leaves and per-leaf tests. Re-exporting bulk leaf surfaces (events,
   subs, feed projections, chrome helpers, style tokens) would invert
   the encapsulation the split exists to create.

   The `<panel>_helpers.cljc` sibling is a separate concern from the
   panel facade discussed here. It exists so a panel's pure data → data
   logic runs under the JVM unit-test target (`clojure -M:test`) as well
   as in the browser, which is why it is `.cljc` and why it holds no
   DOM-touching code — see `app_db_diff_helpers.cljc`.

### View body delegation

The `defview` body is the READ. The markup is a plain function it calls,
living either in the facade beside the boundary (`app_db_diff.cljs`'s
`panel-tree`) or, when it wants its own file, in a view leaf
(`reactive_panel.cljs` → `reactive_panel_view.cljs`). Either way the fn
is invoked as a **plain function call** (parens), not a component vector
(brackets):

```clojure
(rf.fresco/defview Panel
  "The Reactive panel's root."
  [_props]
  (view/reactive-panel (:dispatch (rf/capture-frame))
                       (rf.fresco/sub [:rf.xray/reactive-data])))
```

**Do not write `[view/reactive-panel …]` here.** Under Fresco a plain
function in head position is a **loud error** — the codec's `head-kind`
grades it `:invalid` and raises `:rf.error/fresco-bad-head` (HD-016) —
so the mistake now costs a named refusal rather than a silent
misbehaviour. The rule it enforces is the one that always applied: the
plain call keeps the markup executing inside the boundary's own render
window, where its reads resolve `:rf/xray` from React context. Splitting
it off as its own element puts it outside that window.

Two consequences of the boundary form bite at the call site:

- **`defview` binds no `dispatch` name.** A body that needs one takes
  `(:dispatch (rf/capture-frame))` — core's own door, which Fresco's
  authoring surface deliberately does not duplicate — and threads it to
  the markup fn, so a deferred handler still lands on this Xray
  instance's frame after render scope unwinds. A bare global
  `rf/dispatch` in its place resolves no frame once that scope is gone
  and raises `:rf.error/no-frame-context`; under EP-0002 there is no
  `:rf/default` floor to absorb it.
- **A sibling leaf the markup fn invokes follows the same rule.** Call
  it with parens if it reads through `rf.fresco/sub`. A sibling that
  only dispatches through an explicitly frame-bound dispatcher may be
  its own element if a separate render boundary is wanted for React-key
  or memoisation reasons.

The markup fn is a plain `defn`, never a view declaration. The facade is
still where the declaration happens; the fn is the implementation. Keeping
the markup in a callable fn is also what keeps the unit rows honest — a
boundary's body only runs inside a React render window, so `(Panel)` is
not a callable that answers hiccup, while `(panel-tree …)` is.

### DOM hiccup root (rf2-fkpuv)

**The rule survives the move to Fresco boundaries, and for the same
reason.** Every facade view body MUST produce a hiccup vector with a
**DOM-tag keyword head** (`:div`, `:section`, `:svg`, …) as its root —
not a component head like `[some.ns/SomeComponent {...}]` or a `:<>`
fragment. Per
[main Spec 006 §Source-coord annotation](../../../spec/006-ReactiveSubstrate.md#source-coord-annotation-mandatory)
`data-rf2-source-coord` (+ `data-rf-view`) is stamped on the root DOM
element so pair tools can map a clicked DOM node back to the declaration
site. A non-DOM root is a documented exemption
([Spec 006 §Documented exemption: non-DOM roots](../../../spec/006-ReactiveSubstrate.md#documented-exemption-non-dom-roots)):
the annotation is skipped and pair tools fall back to
`(rf/handler-meta {:source :store :kind :view :id id})`.

What changed is the MECHANISM and one clause, both settled by
[Spec 006 §Cross-host](../../../spec/006-ReactiveSubstrate.md#cross-host)
(rf2-c5w1) rather than here:

- A `defview` registers an alias and never consults the
  `:adapter/wrap-view` late-bind hook the other adapters annotate from,
  so **Fresco stamps from its own codec path** — both attributes built
  once per declaration and merged into the root hiccup's attribute map
  on each body run, under `goog.DEBUG`. The values come from the same
  cross-host formatters, so a boundary's attributes are byte-identical
  to a `reg-view`'s for the same id.
- The stamp lands on a `:tag` root and on nothing else — fragments,
  `[:> …]` crossings and boundary heads are all skipped — which is what
  keeps this section's rule load-bearing rather than cosmetic.
- **Fresco emits no one-shot `console.warn` for a non-DOM root**, and
  that is deliberate: under Fresco a boundary rooted on another
  boundary is ordinary composition, so the warning would fire on
  idiomatic code. Do not expect a console signal to catch a miss here;
  the DOM attribute's absence is the only tell.

When the body **logically** delegates to a single inner component
(e.g. an overlay imported from another artefact such as
`tools/machines-viz/`), wrap the delegation in a
`display: contents` `:div`:

```clojure
(rf.fresco/defview AfterRingsOverlay
  [_props]
  …
  [:div {:data-rf-xray-after-rings-host ""
         :style {:display "contents"}}
   (as-child
     [mv-after-rings/AfterRingsOverlay {...}])])
```

`display: contents` keeps the wrapper visible to the DOM (so the
source-coord attribute has a home) while being neutralised for layout
— the inner component renders as if it were the direct child of the
facade's mount point. The pattern is established by `shell.cljs`'s
`dynamic-chrome` / `surface-composer` (rf2-uu3lp) and the
`machine-after-rings/AfterRingsOverlay` overlay (rf2-fkpuv).

### Per-leaf smoke tests

Every implementation leaf SHOULD ship at least one smoke test in its own
`<leaf>_cljs_test.cljs[c]` file. ~20 lines is a reasonable target.

- **View leaves** (Reagent fns rendering hiccup): render once, assert no
  throw and a key `data-testid` hook is present in the produced tree.
- **Events leaves** (one or more `reg-event` calls inside `install!`):
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

The umbrella `register-xray-handlers!` path remains the integration
contract; per-leaf smoke tests are the unit contract.

### Panel naming — generic `Panel` is the convention (rf2-qiek0)

> **Every panel exports a single public `Panel` view; do NOT
> rename to verbose `EventDetailPanel`-style.**

Each panel's facade owns one public view named exactly `Panel`
(`day8.re-frame2-xray.panels.epoch-panel/Panel`,
`day8.re-frame2-xray.panels.app-db-diff/Panel`, etc. — per
[`API.md`](./API.md) §Panel reg-views).

**One live exception, and it is a spelling rather than a divergence:**
the Static Machines sub-tab declares
`day8.re-frame2-xray.static.machines.panel/panel` — **lowercase**. The
other four Static panels are capitalised `Panel`. The lowercase name is
recorded in [`API.md`](./API.md) §Static-mode Panel reg-views; treat it
as the existing spelling to match when reading that namespace, not as a
second convention to copy into a new panel.

A reader audit
(`ai/findings/2026-05-20-tools-xray-api-review.md` Finding #12)
flagged `Panel` as a generic React idiom (Material UI Panel, Ant
Design Panel) and asked whether the CLJS-side surface should rename
to match the JS-side `EpochPanel`-style names. The decision
locks the bare-`Panel` convention.

**Why bare `Panel` wins.**

1. **Panels are addressed by tab-key, not class name.** The 4-layer
   shell mounts by L3 tab id, and the inventory is the registry's
   (`panel-registry/tabs-for-mode :dynamic`) rather than a literal
   switch — see [`018-Event-Spine.md`](./018-Event-Spine.md) §5
   (`:epoch` · `:app-db` · `:views` · `:trace` · `:machines` ·
   `:routing` · `:resources` · `:derivation-graph` · `:module-view` ·
   `:fresco` — post rf2-5gl5r after the Event/Handler tab
   retirement, post rf2-gbz39 after the Issues tab removal) per
   [`021-Dynamic-Panel-Designs.md`](./021-Dynamic-Panel-Designs.md).
   The view symbol name is internal plumbing; the tab-key is the
   addressable identity.

2. **The namespace already establishes context.** A consumer reading
   `day8.re-frame2-xray.panels.epoch-panel/Panel` sees the
   qualifying context in the namespace segment; renaming the var to
   `EpochPanel` doubles the context and adds ceremony without
   buying clarity.

3. **Host-side collision is a non-issue.** Hosts that mount Xray
   embed the **full shell** per
   [`008-Embedding-Contract.md`](./008-Embedding-Contract.md) §Full-shell
   embed contract — there is no host-facing single-panel embed
   surface. The `Panel` symbol is not in the host's import graph;
   collision with Material UI Panel or Ant Design Panel cannot
   occur.

4. **JS-side names are an adapter concern.** The JS surface camelCases
   what CLJS kebab-cases (per [`API.md`](./API.md) §Public JS API);
   `EventDetailPanel` on the JS side is the adapter's idiom, not a
   CLJS-side spelling the convention propagates.

5. **The split is consistent across every registered panel.**
   Renaming one would force renaming all of them; the per-panel
   facade shape (per §Panel facade + leaf split above) is uniform
   precisely because the leaf-view symbol name is uniform. The set
   is the registry's, not a literal — one `Panel` view per id in
   `panel-registry/tab-ids-for-mode :dynamic` (enumerated in point 1
   above), so a tab added or retired never leaves a count behind.

**Consequence.** New panels added under
`tools/xray/src/day8/re_frame2_xray/panels/<panel>/` MUST follow
the convention: a single public view named `Panel` in the
facade, no verbose `<Panel>Panel` rename. Hosts addressing a panel
do so via tab-key dispatch (the shell's `tab-by-id :dynamic` registry
lookup — the literal `case` this replaced is gone per rf2-2moh1, as
point 1 above records), never by importing the `Panel` symbol
directly.

## Mount conventions

Conventions for `mount.cljs` — Xray's DOM-side mount machinery. The
normative mount contract lives in
[`011-Launch-Modes.md`](./011-Launch-Modes.md) §Mount lifecycle; this
section pins source-organisation rules that aren't normative spec but
keep the mount surface uniform across the artefact.

### Singleton mount-state per process

Xray mounts **exactly one** in-app shell per browser process via the
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
re-creating the mount node. The user's currently-open Xray panel
MUST remain open across an `:after-load` with internal state intact
(per `011-Launch-Modes.md` §Idempotency under hot-reload).

### Data-attribute scheme: `data-rf-xray-mode` is the single axis

The mount root carries `data-rf-xray-mode` (`"inline"` / `"overlay"`
/ `"popout"`). The shell node carries the same attribute under the
same name. Per rf2-zkfiz Q1-9 the earlier `data-mode` echo on the
shell was a duplicate axis and is gone — testbeds, browser-test
assertions, and CSS selectors target the rf-xray-prefixed name
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
`keybinding/attach!` installs. There is no `preload/init!` — both boot
paths call `attach!` directly: `preload.cljs`'s load-time side-effecting
block (the whole block gated on `rf.interop/debug-enabled?`, so a
production bundle strips it), and `core/init!` for a host wiring Xray
manually instead of through `:devtools/preloads`. The detach lives
in the test fixture because `mount.cljs` cannot require
`keybinding.cljs` (the dependency runs the other way — keybinding
requires mount for `toggle!`). Test suites driving multiple
`teardown!` → re-mount cycles MUST call
`(day8.re-frame2-xray.keybinding/detach!)` themselves between runs
(per rf2-zkfiz Q1-10); production sessions never call `teardown!` so
the listener never leaks.

## Panel-id ordering inside the registry

**There is no ordering rule, and the alphabetical one this section used
to state was never the order `registry.cljs` runs.** Registration order
is cosmetic — re-frame resolves a declared `:inputs` list lazily at
subscribe time, and dispatch targets the same way — so the calls are
grouped to read top-down by dependency story instead. Add a new panel's
`install!` wherever that story reads best, and do not re-derive an
ordering rule from a comment in the registry that still claims one.

## Setter-naming axis

Xray's `config.cljc` exposes ~12 writer fns that mutate the
process-global atoms backing `configure!` (and the persisted Settings
map). Without a published rule, the verbs drift — `set-` is the
catch-all, `update-` reads as a synonym, `reset-` overlaps with
`clear-`, and shape discipline (how many args, what does `nil` mean)
varies per-author. This § pins the rule so the next writer lands in
the right vocabulary without rereading the audit
(`ai/findings/2026-05-20-tools-xray-api-review.md` §Finding 8).

The rule mirrors the framework's own tear-down vocabulary fix
(rf2-k6xyr Finding #1 — the framework's `clear-` / `destroy-` /
`dispose-` mess) one level up: same axis (writers), same need to
pick the right verb per action.

### Verb axis — pick one of four

| Verb prefix | Semantics | When to use |
|---|---|---|
| `set-` | **Replace** the slot's value with the new value. | The default writer. Single-slot, single-value writes. Examples: `set-editor!`, `set-project-root!`, `set-egress-profile!`, `set-auto-open!`. |
| `update-` | **Compose** a partial update into a nested slot. | Use when the writer takes a path-and-value pair (or section / key / value triple) and merges into an existing map. Example: `update-setting!` (`[section key value]` triple — writes one knob without replacing the whole `:settings` map). |
| `reset-` | **Back to the documented default.** | Use when the writer takes no value arg and restores the slot to its hard-coded default. Examples: `reset-settings!` (whole map back to `default-settings`), `reset-suppressed-count!` (counter back to `0`). |
| `clear-` | **To `nil` / empty.** | Use when the writer wipes the slot. Distinct from `reset-` — `clear-` leaves no value (which the consumer reads as "absent"); `reset-` writes the documented default value back. v1 has no `clear-` setters; reserve the verb for slots where "absent" is semantically different from "default". |

The four verbs are **disjoint** — picking the wrong one is a
review-flag-able mistake. A reader scanning `config.cljc` should be
able to predict each writer's behaviour from its name alone.

### Shape discipline

Every setter SHOULD honour these constraints:

1. **One or two args max** at the public boundary. `set-foo!` takes a
   single value; `update-setting!`'s triple is the upper bound (path +
   value). Writers that need more state get factored into a map arg
   or a pure builder.

2. **`nil` resets to default.** Passing `nil` to any `set-*` writer
   MUST restore the slot's documented default — `set-editor! nil`
   restores `:vscode`, `set-project-root! nil` restores `nil`, etc.
   This makes `(set-foo! nil)` a documented equivalent to
   `(reset-foo!)` for the common case and lets `configure!`'s
   absent-key contract compose with the per-key setters trivially.

3. **Return value is unspecified.** Setters write atoms (side-effecting
   reset!) and may return the new value, the old value, or `nil`.
   Callers MUST NOT depend on the return shape — the contract is the
   side-effect on the atom, not the value handed back. Side-effect
   orchestration (e.g. `set-egress-profile!`'s retroactive-scrub
   callbacks on a reveal → redact narrowing, per rf2-lqmje) lives
   inside the setter body and is not visible from the signature.

4. **Idempotency.** Calling a setter twice with the same value is
   structurally a no-op (the second `reset!` writes the same value).
   No setter installs a one-shot listener or arms a one-time effect;
   listener installation is the orchestrator's job, not a writer's.

### Length discipline

Setter names track the underlying `configure!` key, which carries the
namespacing convention (see [`015-Configuration.md`](./015-Configuration.md)
§Configuration keys). The longer-key setters
(`set-filters-auto-hide-error-overrides!` at 38 chars,
`set-layout-host-selector!` at 25) are the necessary cost of clarity;
abbreviation is forbidden —
`set-lhs!` reads as line-noise. The compact spelling already lives at
the `configure!` key (`:rf.xray/layout-host-selector` is the
user-facing surface; the setter is internal-host helper).

### Cross-references

- [`API.md`](./API.md) §Public CLJS API — the canonical list of
  user-facing setters; `configure!` is the preferred host entry
  point, per-key setters are documented escape hatches.
- [`015-Configuration.md`](./015-Configuration.md) §Configuration
  keys — the host-facing keys each setter writes (one-to-one
  mapping).
- Framework `spec/Conventions.md` §Tear-down verbs (rf2-k6xyr) —
  the parent rule that governs `clear-` / `destroy-` / `dispose-`
  at the framework level. Xray's setter axis is the same shape
  one level up.

## UI text

Xray is an information-dense devtool. Every pixel of chrome competes
with the data the developer is here to inspect. UI text is **silent by
default**.

### The rule

Prose appears only when:

1. An affordance is **genuinely non-obvious** AND has no iconographic
   alternative.
2. The user is in **a state they couldn't otherwise know about** (e.g.
   "filter is hiding 12 events" — invisible from the data alone).

Worked example of "non-obvious affordance with iconographic alternative":
the panel resize handle (rf2-x8h9y; spec at
[`007-UX-IA.md` §Resize affordance](./007-UX-IA.md#resize-affordance))
ships with no label, no tooltip, no "drag to resize" prose. Discovery is
via `cursor: col-resize` on hover — the cursor change is the
iconographic signal, and prose would be redundant chrome.

### Banned phrasings

- **Panel subheads** that restate the panel title.
- **Empty-state explainers** ("X will appear here when Y happens" —
  prefer terse "No X." or absent).
- **"Click X to Y" narration** — every list is clickable; every chip is
  interactive; users discover this. Narration wastes pixels and risks
  lying (see PR #1435 for a confirmed broken claim that bit users).
- **Roadmap text in chrome** ("future: ...", "v2 will add ...") — keep
  vision in spec, not in ship-time UI (see PR #1436 for the Telemetry
  section removal — chrome must not pretend to control something that
  does not exist).
- **Internal bead IDs / spec citations** in tooltips (see rf2-6lp7k).

### Tooltip discipline

Tooltips carry **shortcuts and disambiguation** — not descriptions.

- Good: `"Re-run (R)"` — names the keybinding.
- Good: `"Auto-filter pattern. Glob: my/* matches all keys under :my"` —
  disambiguates the syntax.
- Bad: `"Click this button to open the settings"` — narrates the obvious.

### Empty-state pattern

- **Tier 1 (preferred):** absent. Empty pane is the empty state.
- **Tier 2:** one terse line ("No traces.") when complete absence is
  jarring (e.g. zero-height panel).
- **Tier 3 (banned):** narrated explainer.

### When you must add text

Three questions before shipping any UI prose:

1. Would removing this line confuse a future reader who knows the data
   model?
2. Does the text describe something the user can't deduce from layout
   + affordance + data?
3. Does it survive the "earn its keep" test: deletion would create a
   real comprehension gap?

If you can't answer "yes" to ALL THREE, delete the text.

### Audit cadence

Per text-audit findings (`ai/findings/2026-05-18-xray-text-audit.md` —
local-only working substrate; not committed), sweep recurring patterns
when reviewing PRs:

- New panel subhead? — flag.
- "Click X to Y" string? — flag.
- "X will appear here when Y happens" empty state? — flag.

Cleanups under this policy: PR #1435 (back-link narration removal),
PR #1436 (Telemetry section removal), PR #1437 (pre-spine cascade
empty-state removal), PR #1439 (7-pattern text-audit cluster).

### See also

- [`Principles.md`](./Principles.md) — Xray's load-bearing principles;
  silent-by-default is the UI-text expression of information-density.
- [`000-Vision.md`](./000-Vision.md) — Xray's claim; the cascade you
  can see, not the cascade you can read narration about.

## Docstrings state the present contract, not the change history

(rf2-ee38b.2) Source docstrings and section comments describe what the
code does **now** — the present-state contract. Superseded-design
rationale and bead lineage belong in commit messages and bead notes, not
in the source the next reader must scan to learn the current behaviour.

- **Good:** "Reads focus off the spine `:rf.xray/focus` — the single
  source of truth."
- **Avoid:** "This used to read `:selected-dispatch-id` (rf2-aaa), then
  Mike changed it to the spine on 2026-05-19 (rf2-bbb) because the old
  design double-wrote two slots…"

A single bead reference is fine when it points a reader at the
authoritative context (`per rf2-xxx`); a multi-paragraph archaeology of
how the code reached its current shape is not. When editing a file,
collapse any "this replaced X because…" passage you touch to a one-line
present-tense statement. This is a judgement pass per file, never a bulk
find-replace over the ~1.3K `rf2-*` references tree-wide.

## See also

- `tools/xray/spec/017-Test-Coverage-Matrix.md` — feature-level coverage
  matrix for browser gates.
- Repo-root `spec/Conventions.md` — re-frame2-wide conventions
  (`:rf/*` reserved namespaces, reserved app-db keys, etc.). Xray's
  conventions live here; framework conventions live there.
