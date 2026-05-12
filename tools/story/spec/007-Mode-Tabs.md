# Story — Mode-Tabs Primitive

> The render-shell's top-of-canvas `:dev` / `:docs` / `:test`
> switcher. The chrome-level primitive that exposes the three
> canonical views every 2026 component playground is expected to
> ship. The contract rf2-9hc8 implements; the surface rf2-rodx
> (`:docs`) and rf2-qmjo (`:test`) build on.

## Why a mode-tabs primitive

Story already carries every substrate the three canonical modes need:

- `:tags` (per-variant) — `:dev` / `:docs` / `:test` are canonical
  tags.
- `:prose` workspace — Markdown-equivalent narrative content.
- Autodocs panel — args table, decorator stack, parameters, tags.
- `run-variant-as-test` — programmatic test harness.

What was missing through the Stage-4 — Stage-6 push: a **chrome-level
switcher** that exposes them as the three canonical views a Storybook-8
class playground ships. The mode-tabs primitive (rf2-9hc8) is that
switcher.

Two beads consume the primitive:

- **rf2-rodx — `:docs` mode view.** Read-only AutoDocs-equivalent
  presentation of the variant's `:prose` workspace + autodocs panel.
  Replaces the `:docs` placeholder.
- **rf2-qmjo — `:test` mode view.** In-canvas aggregated pass/fail
  summary for the variant's interactions / assertions. Replaces the
  `:test` placeholder.

A third bead — rf2-p0mv — specifies the chrome-level toolbar that
consumes `reg-mode` tuples (theme / viewport / locale chips). The
mode-tabs primitive and the toolbar are independent UX axes: tabs
switch the variant's render mode, the toolbar overrides global
options inside the canvas pane.

## The three canonical mode-tabs

| Tab id | Label    | Renders                                                          | Owner bead |
|--------|----------|-------------------------------------------------------------------|------------|
| `:dev` | Canvas   | The interactive variant render — Story v1's default canvas pane. | rf2-9hc8 (already shipped) |
| `:docs`| Docs     | AutoDocs-equivalent: prose + args table + decorator stack + parameters + tags. | rf2-rodx |
| `:test`| Tests    | Aggregated pass/fail summary of `:play` / `:assertions` for the variant. | rf2-qmjo |

`:dev` is the default — selecting a variant without prior selection
renders the canvas, preserving Story v1 behaviour.

## Surface

The primitive ships as one Reagent component plus a small public API
in `re-frame.story.ui.mode-tabs`:

```clojure
(mode-tabs-strip variant-id)        ; the chip strip
(select-mode-tab! variant-id tab)   ; programmatic switch
(load-mode-tab! variant-id)         ; read persisted value
(save-mode-tab! variant-id tab)     ; persist
(hydrate-from-storage! variant-id)  ; idempotent state-from-LS seed
```

State helpers in `re-frame.story.ui.state` (pure data → data,
JVM-testable):

```clojure
mode-tabs            ; [:dev :docs :test]
mode-tab-labels      ; {:dev "Canvas" :docs "Docs" :test "Tests"}
default-mode-tab     ; :dev
(valid-mode-tab? tab)
(active-mode-tab state variant-id) ; → :dev|:docs|:test
(set-active-mode-tab state variant-id tab)
```

## Shell integration

The render shell's `main-pane` renders the chip strip at the top of
the `<main>` landmark when a single variant is selected. Workspaces
(which enumerate multiple variants) do **not** show the mode-tabs
strip — mode-tabs is a per-variant axis; the workspace layout switcher
is a separate concern.

```
┌────────────────────────────────────────┐
│ Canvas │ Docs │ Tests │                │   ← mode-tabs strip
├────────────────────────────────────────┤
│                                        │
│   <selected mode's pane renders>       │
│                                        │
└────────────────────────────────────────┘
```

`:dev` → existing canvas / hot-reload / decorators path (unchanged
from Story v1).
`:docs` → the docs pane (placeholder until rf2-rodx).
`:test` → the tests pane (placeholder until rf2-qmjo).

## Per-variant selection + localStorage persistence

Selection is **per-variant** — switching variants does not clear
another variant's mode-tab. The shell state's `:active-mode-tab` slot
holds the in-memory map `{variant-id → tab}`.

Persistence is via **localStorage**, one key per variant:

```
re-frame.story/active-mode-tab/:story.counter/loaded   → "test"
```

The key prefix is `re-frame.story/active-mode-tab/` followed by
`(str variant-id)`. Mirrors the help overlay's
`re-frame.story/seen-help-v1` key shape and reuses the same
defensive `safe-local-storage` pattern — a localStorage failure
degrades silently to in-memory-only state.

`hydrate-from-storage!` runs on the first render of a variant's strip
and seeds the in-memory slot if the localStorage record exists and
the in-memory slot is empty. Idempotent — safe on every render.

## Accessibility

The chip strip is a `role="tablist"`; each chip is a `<button
role="tab">`. The active chip carries `aria-selected="true"` plus
`aria-current="page"`. The strip itself carries
`aria-label="Story mode"` so screen-reader users can jump to it via
the landmark navigator.

## Test surface

The Playwright spec `examples/reagent/counter_with_stories/
counter_with_stories.spec.cjs` exercises the primitive end-to-end
(section 3b): asserts the three chips render, that each click flips
`aria-selected`, that the matching placeholder renders below the
strip, that the selection persists to localStorage, and that a
full page-reload + variant re-select re-hydrates the persisted tab.

Selectors:

- The strip: `[data-test="story-mode-tabs"]`
- Each chip: `[data-mode-tab="dev|docs|test"]`
- Placeholders: `[data-test="story-docs-placeholder"]`,
  `[data-test="story-tests-placeholder"]`

## Visual style

Matches the existing render-shell chrome (rf2-2uwv contrast fixes):
`#b0b0b0` inactive foreground, white active foreground, `#1e1e1e`
active background, `#2d2d30` strip background, `1px solid #444`
borders, monospace 11px.

## Out-of-scope at this primitive

- **The `:docs` pane content.** A placeholder div ships now; rf2-rodx
  ships the real AutoDocs view.
- **The `:test` pane content.** A placeholder div ships now; rf2-qmjo
  ships the aggregated pass/fail view.
- **Workspace mode-tabs.** Workspaces enumerate multiple variants;
  mixing mode-tabs into the workspace pane conflates two axes. If
  workspaces grow a switcher it lives in a separate spec.
- **Mode-tab registration.** The three tabs are canonical and fixed
  at v1. If extensibility is needed later (`reg-story-mode-tab` for
  third-party panes) it lands as a v2 follow-up — for now the primitive
  is a closed enum.

## Foundational status

Per rf2-9hc8 this primitive is **foundational** — three other beads
depend on it. The contract guarantees:

1. The chip strip renders above the canvas pane whenever a single
   variant is selected.
2. Selection is per-variant and persists across reload.
3. `:dev` preserves Story v1's exact existing behaviour — no
   regression to the canvas / hot-reload / decorator paths.
4. The `:docs` / `:test` panes are pluggable: rf2-rodx / rf2-qmjo
   replace the placeholders without touching the chip strip, the
   state slot, or the localStorage key shape.
