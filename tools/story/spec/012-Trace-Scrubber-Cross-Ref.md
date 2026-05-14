# Story — Trace × Scrubber Cross-Reference

> The cross-reference between the scrubber (time-travel) and the trace
> panel (six-domino cascade view). Scrubbing to an epoch filters the
> trace panel to events at-or-before that epoch and highlights the
> cascade whose post-effects produced it. The combination no JS
> playground ships (rf2-sxwvf).

## Why

Story already has two independent surfaces over the same per-variant
frame:

- **The scrubber** (`re-frame.story.ui.scrubber`) reads the variant's
  `epoch-history` and exposes a slider that scrubs forward/back through
  epochs, committing via `restore-epoch` on release.
- **The trace panel** (`re-frame.story.ui.trace`) registers a trace
  listener against the variant's frame and renders the captured events
  as six-domino cascades (per
  [`re-frame.trace.projection/group-cascades`](../../../implementation/core/src/re_frame/trace/projection.cljc)).

Until this surface they rendered independently. Tying them so "scrub to
epoch N, see the trace that led to N + highlight the cascade that
landed N" is a combination Storybook / Histoire / Ladle can't ship —
they have no concept of a trace bus or an epoch buffer.

This surface adds the cross-reference *without* a new shell-state slot
— consumes the scrubber's existing per-variant `selections` ratom and
the trace panel's existing `buffers` ratom. The render path derefs
both and pipes the result through pure-data helpers.

## Contract

### Selection state

The scrubber maintains a per-variant `selections` defonce ratom (CLJS
side, in `re-frame.story.ui.scrubber`):

```clojure
{variant-id → (r/atom <selected-epoch-id-or-nil>)}
```

The value held is the epoch's **stable `:epoch-id`** — NOT the
slider's slot index. An epoch-id is unique within a frame's history
(per
[`implementation/epoch/src/re_frame/epoch.cljc`](../../../implementation/epoch/src/re_frame/epoch.cljc));
an index can silently re-point at a different record when the ring
buffer's depth cap evicts an older epoch.

Selection lifecycle:

| Trigger                                 | Action                                       |
|-----------------------------------------|----------------------------------------------|
| Slider release on epoch N (`on-mouse-up`) | `reset!` selection → N's `:epoch-id`       |
| Pinned-snapshot chip click              | `reset!` selection → chip's `:epoch-id`     |
| "release" button click                  | `reset!` selection → `nil`                  |
| Shell unmount / variant teardown        | `drop-selection!` removes the entry        |
| Default (no scrub in flight)            | `nil`                                       |

When the selection is `nil` the trace panel renders the full buffer
without filtering or highlighting — the existing v1 behaviour.

### Filter (cascades visible)

When the selection is non-nil the trace panel computes a `cap`
event-id from the selected epoch's `:trace-events`:

```clojure
(scrubber/max-trace-event-id-for-epoch variant-id selected-epoch-id)
```

`cap` is the maximum `:id` among the trace events captured for that
epoch. Per [`spec/009-Instrumentation`](../../../spec/009-Instrumentation.md)
§`:id`, event ids are a process-monotonic integer counter — `:id 42`
was definitely emitted after every event with `:id ≤ 41`.

The trace panel pipes the full cascade list through
`xref/filter-cascades-up-to`:

- Cascade's max event-id `≤ cap` → visible.
- Cascade's max event-id `> cap` → hidden (emitted after the
  selected epoch settled).
- Cascade whose every event carries no `:id` (degenerate) → visible.

When `cap` is `nil` (no scrub, synthetic epoch with empty
`:trace-events`) the filter is the identity.

### Highlight (cascade that produced the epoch)

The trace panel also computes the **cascade-id of the selected epoch**
via `scrubber/cascade-id-for-epoch`, which delegates to
`xref/cascade-id-for-epoch`:

- Walks the selected epoch's `:trace-events` for the first available
  `:tags :dispatch-id` (or `:parent-dispatch-id` as a fallback).
- Per [rf2-g6ih4](../../../spec/009-Instrumentation.md) the framework
  stamps `:dispatch-id` on every in-drain event, so the resolution
  succeeds for any non-degenerate epoch.

Each visible cascade is then rendered with a `selected?` flag —
`true` iff the cascade's `:dispatch-id` equals the selected epoch's
cascade-id. The selected row carries:

- The `:row-selected` style (amber outline + left border + tinted
  background).
- A `data-selected="true"` attribute on the `<div>` so browser tests
  can assert the cross-reference fired.

### Scrub note

When a scrub is active, the trace panel renders a single italic line
above the cascade table:

```
scrubbed to epoch <id> — N later cascade(s) hidden
```

`N` is `(- (count all-cascades) (count visible-cascades))`. The note
surfaces *why* some cascades dropped out so the user doesn't read
'fewer cascades' as 'something blew up'.

The note carries `data-test="story-trace-scrub-note"` so browser
tests can pivot on it.

## Implementation files

| Purpose                                                              | File                                                          |
|----------------------------------------------------------------------|---------------------------------------------------------------|
| Pure-data helpers — cascade-id resolution + filter + match predicate | `tools/story/src/re_frame/story/ui/scrubber_xref.cljc`        |
| Selection ratom + epoch-history projection wrappers                  | `tools/story/src/re_frame/story/ui/scrubber.cljs`             |
| Trace-panel derefs + cascade-row highlight rendering                 | `tools/story/src/re_frame/story/ui/trace.cljs`                |
| Shell teardown (drop selection on variant unmount)                   | `tools/story/src/re_frame/story/ui/shell.cljs`                |
| JVM unit tests for the pure helpers                                  | `tools/story/test/re_frame/story_scrubber_xref_test.clj`      |
| Browser smoke                                                        | `tools/story/testbeds/counter_with_stories/counter_with_stories.spec.cjs` §11b |

The `.cljc` split keeps the cross-reference predicates JVM-testable —
per the standing `feedback_jvm_interop_must_work.md` rule — while the
ratom + Reagent / DOM wiring stays in `.cljs`.

## Pure-data API (JVM + CLJS)

```clojure
;; ns: re-frame.story.ui.scrubber-xref

(cascade-id-from-trace-events epoch-record) ;; → cascade-id-or-nil

(cascade-id-for-epoch history epoch-id)     ;; → cascade-id-or-nil
;; history :: vector of :rf/epoch-record (oldest-first), per
;; epoch/epoch-history.

(max-trace-event-id-for-epoch history epoch-id) ;; → cap-event-id-or-nil

(filter-cascades-up-to cascades cap)        ;; → visible-cascades
;; cascades :: vector of cascade records, per group-cascades.
;; cap nil ⇒ identity.

(cascade-matches-selected-epoch? cascade selected-cascade-id) ;; → boolean
```

Both wrappers in `re-frame.story.ui.scrubber` (`cascade-id-for-epoch`,
`max-trace-event-id-for-epoch`) accept a `variant-id` rather than a
history vector and call `epoch/epoch-history` themselves. They exist
so the trace panel doesn't need to know about the framework's epoch
namespace.

## Out of scope (deferred)

- Per-event annotations (user notes on specific trace points).
- Diff visualisation between two scrubber positions.
- Persistent replay sessions (Causa Lock-12 is ephemeral; same here).
- Click-on-cascade → scrub to its epoch (the inverse direction — the
  bead specifies the scrubber→trace direction only; the inverse adds
  complexity for marginal UX gain at v1).

## Elision

Per [`005-SOTA-Features.md`](005-SOTA-Features.md) §Elision, the entire
Story UI is gated on `re-frame.story.config/enabled?`; the scrubber and
trace panels are gated on `re-frame.interop/debug-enabled?` (the same
goog-define as `epoch-history`). Production CLJS builds short-circuit
before the cross-reference code is reachable.

## Cross-references

- [`003-Render-Shell.md`](003-Render-Shell.md) §Trace bus consumption —
  parent contract.
- [`005-SOTA-Features.md`](005-SOTA-Features.md) §Six-domino trace panel —
  parent SOTA differentiator.
- [`spec/009-Instrumentation.md`](../../../spec/009-Instrumentation.md)
  §Dispatch correlation — `:dispatch-id` stamping rule.
- [`implementation/epoch/src/re_frame/epoch.cljc`](../../../implementation/epoch/src/re_frame/epoch.cljc)
  §`:rf/epoch-record` — record shape.
