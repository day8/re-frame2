# Story — Actions Panel

> The chronological per-variant log of user-action dispatches and
> dispatch-shaped fx invocations.  Filters the variant's trace buffer
> to the two channels that answer "what did the user do?" without the
> reader mentally splitting six-domino cascades apart.  Companion to
> the six-domino trace panel ([`003-Render-Shell.md` §Trace bus
> consumption](003-Render-Shell.md#trace-bus-consumption)).  Per
> rf2-5yriz; cross-references the SOTA audit's F3 finding
> (`ai/findings/story-sota-audit-2026-05-13.md`).

## Why a dedicated panel

The six-domino trace panel already captures every event on the bus —
strictly more than Storybook's `action('clicked')`-instrumented
Actions panel — but it groups events by `:dispatch-id` and renders
each cascade as one row with six sub-cells.  That shape is right for
"trace the cascade caused by this dispatch"; it is wrong for "show me
the next five things the user does."  The Actions panel is the cheap
filter that answers the latter question without rebuilding the trace
buffer.

Storybook's Actions panel only surfaces handlers the author manually
wired via `action('name')`.  Story's panel sees EVERYTHING on the
trace bus automatically — no `action()` calls, no per-component
instrumentation.  Forget to wire it and the panel still works.  The
panel's header copy says so.

## Filter contract

The panel reads the variant's trace buffer (the same
`re-frame.story.ui.trace/buffers` ratom the six-domino panel reads,
populated by the listener wired at variant selection in
`re-frame.story.ui.shell/ensure-listeners-for-variant!`) and keeps
two classes of trace events:

1. **Dispatch events** — every event whose `:op-type` is `:event` AND
   `:operation` is `:event/dispatched` (per [`spec/009 §:op-type`
   vocabulary](../../../spec/009-Instrumentation.md)).  This covers
   the canonical "an event landed on the router" channel — root
   dispatches AND descendant dispatches fired from inside a handler.

2. **Dispatch-shaped fx-handled events** — every event whose
   `:op-type` is `:fx` AND `:operation` is `:rf.fx/handled` AND whose
   `:tags :fx-id` is one of `#{:dispatch :dispatch-later
   :dispatch-sync}`.  This covers the moment a handler RETURNS a
   dispatch-shaped fx — distinct from the dispatch eventually landing
   on the router.  Both moments are meaningful: the fx-emit is "the
   handler asked"; the `:event/dispatched` is "the router accepted".

Every other trace event in the buffer (handler `:run-end` markers,
`:event/do-fx`, every effect, every sub-recompute, every render,
machine transitions, flow events, errors, warnings) is filtered out.

### The filter predicate

```clojure
(defn action-event?
  [{:keys [op-type operation tags]}]
  (or (and (= op-type :event)
           (= operation :event/dispatched))
      (and (= op-type :fx)
           (= operation :rf.fx/handled)
           (contains? #{:dispatch :dispatch-later :dispatch-sync}
                      (:fx-id tags)))))
```

### Row projection

Each surviving trace event projects to a row map:

| Slot | Source | Notes |
|---|---|---|
| `:id` | `:id` | Stable per-process trace id; used as React `:key`. |
| `:class` | derived | `:dispatch` or `:fx-dispatch`. |
| `:time` | `:time` | `interop/now-ms` wall-clock ms; rendered via `format-timestamp`. |
| `:event-id` | `:tags :event-id` (or first of `:tags :event`) | The event id for both classes. |
| `:event` | `:tags :event` | The full event vector when present. |
| `:args` | `:tags :fx-args` | For `:fx-dispatch` rows only — `:dispatch` carries a vector, `:dispatch-later` carries a map under `:event`. |
| `:dispatch-id` | `:tags :dispatch-id` | Cascade correlation; same key the six-domino panel groups by. |
| `:origin` | `:tags :origin` | Per Spec 009 §Dispatch origin tagging; lets tooling dispatches stay distinguishable. |
| `:fx-id` | `:tags :fx-id` | `nil` for `:dispatch` rows. |
| `:source` | `:rf.trace/trigger-handler :source-coord` | The in-scope handler's registration coord (rf2-lf84g); the "open in editor" hook rf2-evgf5 wraps this slot. |
| `:raw` | the trace event | Escape hatch for renderers that want full fidelity. |

Pure data → data; lives in `re-frame.story.ui.actions/project-rows`
and is `.cljc` so the JVM test corpus exercises it without booting
Reagent.

## Pause / clear semantics

Two affordances sit in the panel header.

### Pause

Pause is per-variant.  `(actions/pause! variant-id)` flips the
panel's render boolean for `variant-id` to `true` AND snapshots the
current trace buffer.  While paused:

- The panel renders against the snapshot taken at pause-time, not
  the live buffer.
- The underlying trace listener continues to capture new events —
  the buffer keeps growing.  We pause RENDERING, not CAPTURE.
- Unpausing drops the snapshot and the panel renders the live
  buffer again.  Any events captured while paused are now visible.

Pause is per-variant so the user can freeze actions for `:story.a/x`
while watching `:story.b/y` live.

### Clear

`(actions/clear! variant-id)` calls
`re-frame.story.ui.trace/clear-buffer!` against the variant's buffer
— this is the same primitive the six-domino panel would call, so
both panels stay coherent (clearing actions also clears trace).  The
operation is destructive.

Clear also unpauses the variant — leaving the panel paused on a now-
empty snapshot is confusing UX.

## Auto-scroll behaviour

The panel renders rows oldest-first inside a fixed-height scrollable
host (`overflow-y: auto`, `max-height: 200px`).  Newest entries
appear at the bottom.  Stage 1 (this spec) does NOT implement
"sticky to bottom unless the user has scrolled up" — the
scrollable host scrolls naturally and the user retains full
control.  v1.1 may add a `requestAnimationFrame`-driven auto-stick-
to-bottom; the contract here is "rows render in chronological order
and the host scrolls", which is enough for the canonical UX without
locking in browser-specific scroll-anchor behaviour.

## Source-coord click-to-source

Each row carries the row map's `:source` slot — the
`:rf.trace/trigger-handler` coord stamped by `emit!` whenever a
handler is in scope (per [`spec/009 §Source-coord stamping`](../../../spec/009-Instrumentation.md)).  The current panel surfaces the
coord on the row's `title` attribute as a `file:line` hover tooltip.
When rf2-evgf5 ("Open in editor") lands, the click-handler wraps the
same row slot — no retrofit needed on the panel's data shape.

## Placement and wiring

The panel is a built-in chrome panel, NOT a `reg-story-panel`
registration.  It sits in the right-pane stack alongside controls /
scrubber / trace, wired directly by `re-frame.story.ui.shell/
right-panel`:

```
┌────────────┐
│ controls   │
│ scrubber   │
│ trace      │
│ actions    │   <-- new (rf2-5yriz)
│ <reg-*>    │   <-- registered panels via reg-story-panel
└────────────┘
```

Visibility flows through the shell state's `:panel-visibility` map
under the `:actions` key.  Default true.  Users can toggle the panel
via the same panel-list affordance Stage 6 adds for registered
panels.

Built-in chrome panels (controls, scrubber, trace, actions) are
wired directly because they are always-present and have no late-bind
contract.  This mirrors how the existing trace panel ships.

## DOM contract

The panel renders a `<div role="region" aria-label="Actions">` with
`tabindex="0"` (per rf2-xc65 a11y rules) and `data-test="story-
actions-panel"` so the browser-side Playwright spec can anchor on
it.  Header carries `data-test="story-actions-pause"` and `data-
test="story-actions-clear"` on the two buttons; each row carries
`data-test="story-actions-row"`, `data-row-class="dispatch"`
or `"fx-dispatch"`, and `data-event-id="<event-id>"` so the spec
can assert specific event ids landed.

## Elision

The panel's namespace lives in `tools/story/src/re_frame/story/ui/
actions.cljc`.  The pure helpers are CLJC; the rendering, the
pause/clear ratoms, and the listener-wiring are CLJS-only.  The
shell's `right-panel` only reaches `actions/panel` via a `(when
config/enabled? ...)`-gated mount call upstream — production builds
short-circuit before any panel fn is reached.  Closure DCEs the lot.

## Test coverage

`tools/story/test/re_frame/story/ui/actions_cljs_test.cljc` covers:

- `action-event?` classifies dispatches + dispatch-shaped fx-handled
  emits and rejects every other op-type/operation combination.
- `project-rows` returns the right shape given a trace-buffer
  snapshot (one row per surviving event; chronological order
  preserved).
- `pause!` / `unpause!` / `toggle-pause!` round-trip; `paused?`
  reflects the state.
- `clear!` calls `trace/clear-buffer!` and unpauses.
- `format-timestamp` pads sub-second millis to 3 digits.
- The panel component is a function and renders hiccup for a
  registered variant.

## Differentiator restatement

| Axis | Storybook 8 | Story (rf2-5yriz) |
|---|---|---|
| Capture | manual `action('name')` per handler | automatic — every dispatch + dispatch-fx on the bus |
| Surface | the panel | the panel |
| Per-variant scope | yes (story) | yes (variant frame) |
| Cascade context | none | `:dispatch-id` slot on every row; clickable in v1.1 to filter to one cascade |
| Source-coord | none | `:rf.trace/trigger-handler` on every row; v1.1 click-to-source |
| Pause / clear | yes | yes |

The differentiator is the capture axis: Story is zero-config.

## Cross-references

- [`003-Render-Shell.md` §Trace bus consumption](003-Render-Shell.md#trace-bus-consumption)
  — the sibling trace panel reads the same buffer.
- [`005-SOTA-Features.md`](005-SOTA-Features.md) §Actions logger —
  the SOTA-tracking line item this panel closes.
- [`spec/009-Instrumentation.md`](../../../spec/009-Instrumentation.md)
  §`:op-type` vocabulary — the canonical names this panel filters
  on.
- `ai/findings/story-sota-audit-2026-05-13.md` §F3 — the audit
  finding that spawned rf2-5yriz.
