# 6. Time-travel in Story

Time-travel in Story rides [Causa](../causa/03-time-travel.md). Causa is mounted into the Story shell's right-hand pane by default (rf2-sgdd3) — its L1 ribbon (`◀ ▶ ⏭`) + L2 event list are the scrubber; the Trace tab is the cascade view; the Event-tab cascade view is the actions log. There is no Story-side scrubber UI any more; Story's contribution is the *frame isolation* that makes per-variant time-travel meaningful.

## Why per-variant matters

Storybook's interaction recorder runs sequentially against one canvas. To compare two scenarios you re-mount the page.

Story's frame-per-variant isolation makes parallel time-travel natural. A workspace of four variants is four independent frames — each has its own `app-db`, its own dispatch queue, its own epoch buffer. Rewinding *empty* doesn't affect *loaded* or *clicked-three-times* or *save-stubbed*. You can rewind one cell while watching the play sequence in another.

When you select a variant in the sidebar, Story re-opens Causa scoped to that variant's frame — Causa's ribbon, event list, and detail tabs all bind to the selected frame's spine.

## What you'd reach for it for

- **"My play sequence asserts something that's wrong at step 4. Why?"** — open the variant in Canvas mode, run the play step-by-step, scrub backwards in Causa from the failing assertion. The epoch one back from the failure is the state the assertion ran against.
- **"I want to capture the canvas at three points in the variant's lifecycle."** — `:play` it; the runtime records an epoch per step; navigate Causa's L2 event list to each, screenshot.
- **"I'm tuning a play sequence."** — write the events, walk them in Causa, see what each one did to `app-db`. Edit. Re-run. The buffer is fresh each session.

## The six failure modes, again

`restore-epoch` against a variant frame has the same six failure modes as against the host app's frame (see [Causa chapter 3](../causa/03-time-travel.md)):

- Unknown frame
- Unknown epoch
- Schema mismatch
- Missing handler
- Version mismatch
- Concurrent-drain rejection

Causa renders the failure mode inline — same red toast, same `:reason` / `:explain` / `:missing-id` / `:expected`-`:got` payload. The runtime's contract is stable; Causa renders against it the same whether the frame is the host app's or a Story variant's.

## When time-travel isn't the right tool

Two cases:

- **"I want to replay the variant from the start with a tweaked arg."** That's not time-travel; that's *re-run with override*. Edit the arg in the controls panel; the variant re-runs from the start with the new arg. Time-travel is for *examining what already happened*, not for re-running with changes.
- **"I want to time-travel the trace bus, not the app-db."** The trace bus is a log, not a state. To re-render the trace from a different angle, use Causa's Trace tab filters (the same `:op-type` / tag / free-text filters Causa offers across all hosts).

The point of time-travel here, as in Causa, is that *the runtime already recorded the state*. Story is just the lens that lets you compare two variants' epoch buffers side-by-side, each in its own frame.

Next: [multi-substrate side-by-side](07-multi-substrate.md).
