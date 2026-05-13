# Story — `:test` Mode Pane

> The in-canvas aggregated test-runner view that sits behind the
> `:test` mode-tab. Runs the variant's `:play` sequence + the seven
> canonical `:rf.assert/*` events; surfaces a status badge, per-row
> pass/fail/skip, collapsible failure detail, and a re-run button.
> The downstream surface the mode-tabs primitive (rf2-9hc8, spec/007)
> was built to host. Mirrors the docs-pane (spec/008) shape.

## Why a dedicated test pane

re-frame2-story already ships the programmatic test harness:
`run-variant-as-test` (and the more general `run-variant` →
`:assertions` round-trip per spec/004) drives a variant through its
four-phase lifecycle and accumulates the seven canonical
`:rf.assert/*` records on the variant's frame. What was missing
through the Stage-4 — Stage-6 push: a **chrome-level surface** that
runs that harness on demand and renders the aggregated pass/fail
summary inside the playground — the Storybook 8 "Tests" tab
equivalent.

The pane is deliberately a *reader* of the existing runtime — it
does not introduce a parallel test framework, a parallel assertion
vocabulary, or a parallel result schema. Every record it renders
came out of `run-variant`'s `:assertions` slot exactly as it was
recorded by `re-frame.story.assertions` (spec/004 §Canonical
assertion vocabulary). The pane's job is to:

1. Trigger a `run-variant` against the active variant.
2. Read the `:assertions` vector off the result map.
3. Render the records as a scannable summary + per-row table.
4. Offer a re-run button that re-fires the lifecycle.

## Surface

Three companion namespaces (split per rf2-8n2fz — see `pure.cljc`,
`state.cljs`, `view.cljs` under `src/re_frame/story/ui/test_mode/`):

```clojure
(re-frame.story.ui.test-mode.view/test-view variant-id)   ; CLJS Reagent component

;; pure data → data (JVM-testable) — under re-frame.story.ui.state:
(aggregate-summary assertions)
  ; → {:total <n> :passed <n> :failed <n>
  ;    :skipped <n> :all-passed? <bool>}
  ; Lives in shell-state (not test-mode.pure) per rf2-khmon so the
  ; sidebar / chrome-level test widget can share one canonical fold
  ; without a require cycle.

;; pure data → data (JVM-testable) — under re-frame.story.ui.test-mode.pure:
(variant-has-tests? variant-id)
  ; → boolean — true iff the variant body declares a non-empty :play
(assertion-row assertion)
  ; → {:assertion :rf.assert/path-equals
  ;    :status   :pass|:fail|:skip
  ;    :label    "<:assertion-id> <pr-str payload>"
  ;    :detail   {:expected ... :actual ... :reason ...}}
(format-elapsed-ms ms)
  ; → "12 ms" / "1.2 s"
(format-timestamp-ms ms)
  ; → ISO-8601-ish "HH:mm:ss" for the last-run badge
```

The render shell's `main-pane` calls `test-mode.view/test-view` when
the per-variant mode-tab is `:test`. Selection is owned by the
mode-tabs primitive; this spec does not touch the chip strip.

## Section composition

The pane renders four sections, top-to-bottom:

| # | Section          | Content                                                                  | Rendered when                                  |
|---|------------------|--------------------------------------------------------------------------|------------------------------------------------|
| 1 | Header           | variant id + parent-story id + run/elapsed status                        | always                                         |
| 2 | Summary badge    | overall pass/fail/skip count + "all passed" / "<n> failed" status pill  | a run has executed                             |
| 3 | Per-test rows    | one row per `:assertions` record — green/red/grey + collapsible detail   | a run has executed AND `:assertions` non-empty |
| 4 | Empty state      | "No tests registered for this variant" + link to testing recipes         | `:play` slot is empty                          |

### 1. Header

```
:story.counter/loaded
parent story: :story.counter
[ Re-run ]            ran 12:04:23 · 18 ms
```

- Variant id as the page `<h1>`.
- Parent story id below.
- A **Re-run** button on the left dispatches a fresh
  `run-variant` call against the variant, re-allocates the frame, and
  swaps the result into the pane's local state. On first mount the
  pane auto-runs the variant once so the user lands on the result;
  subsequent visits to the `:test` tab show the most recent result
  until Re-run is clicked.
- A last-run timestamp + elapsed duration on the right, both pulled
  from the result map's `:elapsed-ms` slot and the pane's own
  capture of `(interop/now-ms)` at run completion.

### 2. Summary badge

A status pill — green when every assertion passed, red when any
failed, grey when the variant ran but recorded zero assertions
(possible when `:play` is non-empty but contains only non-assertion
event dispatches). The pill text is `"N passed"`, `"N failed of M"`,
or `"no assertions recorded"`.

The pill is followed by a key:

```
✓ 3 passed  ✗ 0 failed  ⊘ 0 skipped
```

`:skipped` is the count of records carrying
`:assertion :rf.assert/skipped` — re-frame2's runtime doesn't
currently emit this id, but the count slot stays open so future
spec/004 additions (skip-on-condition) flow through without a pane
refactor.

### 3. Per-test rows

A three-column table — `status | assertion | detail` — one row per
record in the result's `:assertions` vector, in execution order
(the order `re-frame.story.assertions/record!` appended them).

- **status.** A coloured glyph: `●` green for `:passed? true`, `●`
  red for `:passed? false`. Failures are highlighted both by colour
  and by the row's background.
- **assertion.** The canonical id + the formatted `:payload`
  (Storybook's "assertion label"). Example:
  `:rf.assert/path-equals [[:count] 7]`.
- **detail.** For a passing row, the cell is empty; for a failing
  row, a collapsible disclosure that surfaces `:expected`, `:actual`,
  and `:reason` from the assertion record. The disclosure is closed
  by default; click expands.

Source-coord (the `:source` slot per spec/004) is rendered as the
collapsible's footer when present, so a failing row tells the reader
exactly which file/line declared the assertion.

### 4. Empty state

When `(variant-has-tests? variant-id)` is false — the variant body's
`:play` slot is empty or absent — the pane skips the run entirely
and renders a placeholder:

```
No tests registered for this variant

Add a :play slot to register assertions.
See: skills/re-frame2/reference/cross-cutting/testing.md
```

The link forward-points to the canonical testing recipes leaf so a
reader knows where to look. The pane renders it as an absolute
GitHub URL (`https://github.com/day8/re-frame2/blob/main/skills/...`)
so it resolves from the dev server, the deployed playground, and an
embedded preview alike — a relative path would resolve relative to
whichever hash route the playground is on (`#/stories/...`), which
isn't the repository root.

The pane MUST NOT call `run-variant` in this branch — the runtime
is happy to short-circuit on an empty `:play`, but skipping the
call also skips frame allocation, which keeps the pane cheap for
"browse-only" sessions.

## Run-on-mount + re-run semantics

- **First mount.** The pane records a single run on first mount per
  variant (per pane lifecycle, not per page-lifecycle — re-mounting
  the `:test` pane fires a fresh run). The runtime's
  `:passed-through?` semantics (per spec/004 §Record-don't-throw)
  guarantee a run never throws; failures land in the assertions
  vector and the pane renders them.
- **Re-run.** Re-run dispatches `reset-variant` (per
  spec/002 §Programmatic API) — the existing runtime entry that
  tears down + re-allocates the variant frame and re-runs the four-
  phase lifecycle. Re-run is debounced via a `:running?` flag on the
  pane's local state so a fast double-click can't fire two parallel
  runs.
- **Switching variants.** Switching to a different variant's `:test`
  tab re-runs (each variant carries its own result slot in the
  pane's local state, keyed by variant id).

## Read-only contract — same as the docs pane

The pane MUST NOT carry any input elements that mutate args /
overrides / modes. Switching `:test` → `:dev` MUST restore the
canvas as the user left it — same args, same overrides, same modes.

The Re-run button mutates **runtime state** (re-allocates the
variant frame) but not the variant's authoring shape. The shell's
controls / sidebar / mode picker / panel-visibility state is
untouched.

## Test selectors

The Playwright spec drives the pane via `data-test`-prefixed
selectors so visible labels can be reworded without breaking tests:

| Selector                                              | What                                       |
|-------------------------------------------------------|--------------------------------------------|
| `[data-test="story-test-view"]`                       | The pane root (`<section>` landmark).      |
| `[data-test="story-test-parent-story"]`               | Parent-story sub-header.                   |
| `[data-test="story-test-rerun"]`                      | The Re-run button.                         |
| `[data-test="story-test-status-pill"]`                | The pass/fail status pill.                 |
| `[data-test="story-test-counts"]`                     | The "✓ N passed · ✗ N failed · ⊘ N skipped" key. |
| `[data-test="story-test-elapsed"]`                    | Last-run elapsed duration.                 |
| `[data-test="story-test-timestamp"]`                  | Last-run timestamp.                        |
| `[data-test="story-test-table"]`                      | The per-test rows table.                   |
| `[data-test="story-test-row"]`                        | One assertion row; `data-status="pass\|fail\|skip"` + `data-assertion="<:id>"`. |
| `[data-test="story-test-row-detail"]`                 | The collapsible detail for a failing row.  |
| `[data-test="story-test-empty"]`                      | Empty-state placeholder.                   |

## Visual style

Matches the render-shell chrome (rf2-2uwv contrast palette):
`#b0b0b0` inactive foreground, `#dcdcdc` body text, `#1e1e1e`
section background, `#2d2d30` table-header background, monospace
for table content + variant ids, system-ui for prose. Pass /
fail are the standard `#4ec9b0` (green) / `#f48771` (red); skip
is `#9a9a9a`. The pane scrolls inside the `<main>` landmark; the
strip sits above it (owned by the mode-tabs primitive).

## Out of scope at v1

- **Per-assertion timing.** Each record carries an `:elapsed-ms`
  slot (per spec/004) but the v1 row renderer surfaces only the
  total. Per-assertion timing flows through the same data path
  if/when a v2 detail-tab wants it.
- **Coverage / pass-rate trending.** The pane shows the latest run
  only. A "last N runs" rollup is a separate surface and would
  hang off the trace panel, not the `:test` mode.
- **Hot-reload re-fire on the per-variant pane.** If the variant's
  `:play` slot changes on hot-reload, the **per-variant `:test` pane**
  keeps the previous run's record until the user clicks Re-run. The
  mode-tabs primitive's `:hot-reload-tick` watcher is intentionally
  not wired through the per-variant pane — re-run is an explicit
  action there. The **chrome-level test widget** carries the watch-
  mode toggle (rf2-z1h0f, below) — that surface IS wired to a drift
  detector and auto-re-runs the testable set as a whole.

## Chrome-level test widget + sidebar status dots (rf2-q0irb)

Storybook 9's headline 9.0 feature is the Vitest-driven test widget
that aggregates per-story status across the whole sidebar. The
`:test` pane above is the per-variant view; the chrome-level
aggregation surface is the **test widget** that sits at the foot of
the sidebar plus the **status dots** that render next to each
testable variant row.

Both surfaces read from one slot in the shell state — `:test-runs`,
a `{variant-id → {:status :pass|:fail|:running|:pending, :passed,
:failed, :skipped, :total, :ran-at-ms, :elapsed-ms}}` map. The
`:test` mode pane and the chrome widget both write into it; the
sidebar's per-variant row reads its dot's colour from it.

### Surface

```clojure
;; pure data → data (JVM-testable, lives in re-frame.story.ui.state):
(mark-test-running   state variant-id)       ; → state'
(record-test-run     state variant-id summary) ; summary is the
                                              ; aggregate-summary shape
                                              ; from this spec.
(clear-test-run      state variant-id)       ; → state'
(variant-test-status state variant-id)       ; → :pass|:fail|:running|:pending
(test-summary        state variant-ids)      ; → {:total :passed :failed
                                              ;    :running :pending
                                              ;    :all-green?}
(testable-variant-ids id->body)              ; → vector of variant-ids
                                              ; whose :tags contains :test
                                              ; AND :play is non-empty.
```

The `re-frame.story.ui.sidebar` namespace renders the chrome widget
and the per-variant dots. `(sidebar/status-dot status)` and
`(sidebar/test-widget shell registry)` are public so the test corpus
can render them directly.

### Testable variants

The widget aggregates over **testable** variants — variants whose
`:tags` set contains `:test` AND whose `:play` slot is non-empty. A
variant tagged `:test` but without any assertions to run is excluded
so the headline counts don't mislead. Per-variant dots follow the
same filter: a `:dev`-only variant or an empty-`:play` `:test`
variant renders no dot.

### Status semantics

- `:pass` — every assertion in the last run passed (and at least one assertion).
- `:fail` — at least one assertion failed.
- `:running` — `run-variant` is currently in flight.
- `:pending` — no run recorded yet, **or** the last run produced zero
  assertions (the variant ran but produced no signal — the dot stays
  grey rather than green).

`:all-green?` on the chrome widget's summary is true only when every
testable variant has a recorded green run — a sea of `:pending`
counts as "not green yet", not "all green".

### Run all

The widget's **Run all** button drives `run-variant` over every
testable variant. Runs dispatch in parallel — each variant runs in
its own frame per Spec 002 §Programmatic API, so concurrent runs
don't cross-contaminate `app-db`. Per-variant runs from the
`:test` pane's Re-run button fold into the same `:test-runs` slot,
so the chrome widget and the pane stay in sync.

### Test selectors

| Selector                                              | What                                  |
|-------------------------------------------------------|---------------------------------------|
| `[data-test="story-test-widget"]`                     | The widget root.                      |
| `[data-test="story-test-widget-headline"]`            | "Tests · N/M" headline strip.         |
| `[data-test="story-test-widget-counts"]`              | "✓ N · ✗ M · ▸ K · ○ L" count chips.  |
| `[data-test="story-test-widget-run-all"]`             | The Run all button.                   |
| `[data-test="story-test-widget-watch-toggle"]`        | The watch-mode eye-icon chip; `aria-pressed` reflects the on/off state, `data-state` reads "on" / "off". |
| `[data-test="story-test-widget-empty"]`               | "no :test variants" empty state.      |
| `[data-test="story-sidebar-dot"]`                     | One per testable variant row; `data-status="pass\|fail\|running\|pending"`. |

### Watch mode (rf2-z1h0f)

Storybook 9's Vitest addon ships a watch-mode toggle (eye icon) that
re-runs the changed stories on file save. Story's parity surface is
the **watch chip** beneath the count chips. It toggles a boolean
(`:test-watch-mode?`) in the shell state. When on, the shell's poll
detector computes a snapshot-identity content-hash per testable
variant and compares it against the recorded `:test-content-hashes`
slot; any drift dispatches `sidebar/watch-rerun!` for the affected
variants — the sidebar dots transit through `:running` to the new
`:pass` / `:fail` exactly as if the user had clicked Re-run.

The detection signal is the variant's snapshot-identity content
hash (per `re-frame.story.identity/snapshot-identity` + IMPL-SPEC
§5.6) — the hash captures `:play` / `:events` / `:loaders` / args
/ decorators / parent-story slice, so a change to any of those
produces a fresh hash. Cosmetic edits (docstring, `:source` coords)
do not contribute.

Toggle-off clears `:test-content-hashes` so the next toggle-on
seeds a fresh baseline from the current registry (no spurious
re-run on toggle-on). Toggle-on without subsequent changes is a
no-op once the baseline lands.

The detector shares the existing 500ms `setInterval` with the
fingerprint poll — one cadence, two detectors. Under `static-
mode?` the poll skips entirely (no dev-time `reg-*` mutations
will land); watch mode has nothing to detect in that build.

### Out of scope

- **Failing-row scroll-into-view.** Clicking a red dot does not yet
  scroll the sidebar to the failing variant or auto-select it. The
  user clicks the row manually.
- **Coverage rollups.** The widget shows the latest run only; a
  "last N runs" rollup is a separate surface.
- **Watch mode scoped to a single variant.** The v1 watch detector
  re-runs every drifted testable variant — it is not yet scoped to
  the currently-selected variant. A v2 'watch only selected' affordance
  would gate the rerun set; the existing `watch-rerun!` entry point
  already accepts an arbitrary variant-id seq.

## Foundational status

Per the rf2-9hc8 / rf2-rodx / rf2-qmjo / rf2-q0irb tetrad: the
`:test` pane and the chrome widget are **leaves** — they consume
the foundation (the four-phase runtime, the seven canonical
`:rf.assert/*` events, the `:assertions` record schema) and surface
it. They do not register new artefact kinds; the only new shell-
state slot is `:test-runs`, which is a pure derivation of
`run-variant` results keyed by variant id. Removing them restores
the placeholder-equivalent empty pane and the dot-free sidebar
without breaking any other surface.
