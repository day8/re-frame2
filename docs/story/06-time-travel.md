# 6. Time-travel in Story

> **What you'll build.** Comfort with the gesture of scrubbing through a variant's epoch history using Causa, which is embedded in the Story shell's right-hand pane by default. You'll learn how frame-per-variant isolation makes per-cell time-travel meaningful, and when *not* to reach for time-travel.
>
> **You should have working before you start.** Chapters 1–4. The chapter assumes you've got at least one variant whose `:play-script` does interesting things — a sequence of dispatches, not just a single assertion. The `:story.counter/clicked-three-times` variant from chapter 3 is fine.

Time-travel debuggers are one of those features the industry has been recurrently rediscovering since the 1960s. Smalltalk had them. Lisp Machines had them. Bret Victor demoed a particularly affecting incarnation in *Inventing on Principle*. Elm's debugger from 2015 made the gesture mainstream in modern web dev; Redux DevTools and re-frame-10x carried the torch into production teams.

The trouble with time-travel debuggers, historically, isn't whether they're *useful* — they're enormously useful, the first ten times you use one. The trouble is that they tend to be *expensive at runtime* (every state must be snapshotted), *fragile under hot-reload* (the stored states get stale), and *difficult to scope* (which thread's history is this, again?).

re-frame2's per-frame epoch buffer addresses each of these. Snapshots are cheap because the substrate is structural-sharing data. Hot-reload preserves the buffer because the buffer's keyed by frame identity, not by code identity. Scoping is per-frame, so there's never ambiguity about "which app-db's history am I looking at."

Story's contribution is to make this *useful in a playground context*: per-variant frame isolation means parallel time-travel is natural. A workspace of four variants is four independent frames — each with its own `app-db`, its own dispatch queue, its own epoch buffer. Rewinding `:empty` doesn't affect `:loaded` or `:clicked-three-times` or `:save-stubbed`. You can rewind one cell while watching the play sequence in another.

## Where the scrubber lives

Time-travel in Story rides [Causa](../causa/03-time-travel.md). Causa is mounted into the Story shell's right-hand pane by default — its L1 ribbon (`◀ ▶ ⏭`) plus L2 event list is the scrubber; the Trace tab is the cascade view; the Event-tab is the actions log. There is no Story-side scrubber UI any more; Story's contribution is the *frame isolation* that makes per-variant time-travel meaningful.

When you select a variant in the sidebar, Story re-opens Causa scoped to that variant's frame — Causa's ribbon, event list, and detail tabs all bind to the selected frame's spine. Click a different variant; Causa rebinds. The RHS chip-row picker lets you swap between Causa's panels (event-detail / app-db / views / trace / machines / routing / issues) without losing the selected variant.

> 📸 **Screenshot needed**: a variant in Canvas mode with Causa embedded in the RHS, showing the L1 epoch-scrubber ribbon and the L2 event list. Annotate (1) the Story shell's main canvas on the left, (2) the Causa RHS panel, (3) the `◀ ▶ ⏭` ribbon at the top of Causa, (4) a selected epoch in the event list, (5) the chip-row picker that swaps between Causa's panel views.
>
> Save as: `/docs/images/story/06-time-travel.png`

(Yes, we know — for re-frame v1 users, this is the part where you brace for the "but where did 10x go?" question. The answer is: Causa is the post-v2 successor. Same gestures; better scoping; embedded in Story instead of hovering as a floating widget. You'll like it once your fingers retrain.)

## What you'd reach for it for

- **"My play sequence asserts something that's wrong at step 4. Why?"** Open the variant in Canvas mode, let the play-script run, scrub backwards in Causa from the failing assertion. The epoch one back from the failure is the state the assertion ran against. Look at `app-db` there; ask whether it matches your mental model. Usually it doesn't, and that's the bug.

- **"I want to capture the canvas at three points in the variant's lifecycle."** Run the play-script; the runtime records one epoch per dispatched event; navigate Causa's L2 event list to each point of interest; screenshot. This is the gesture you'd use for documenting a flow's intermediate states.

- **"I'm tuning a play sequence."** Write the events, walk them in Causa, see what each one did to `app-db`. Edit. Re-run. The buffer is fresh each session — `restore-epoch` resets cleanly between play-script runs.

- **"A variant test failed in CI and I want to reproduce the broken state locally."** Cause's epoch buffer plus the snapshot-identity machinery from chapter 5 lets the CI runner emit "open Story to this variant at epoch N" deep-links. Click; scrub; debug. (This integration is alpha but we mention it for direction.)

## The six failure modes

`restore-epoch` against a variant frame has the same six failure modes as against any other frame (see [Causa chapter 3 — Time-travel](../causa/03-time-travel.md) for the full grammar):

- **Unknown frame** — the variant's frame was destroyed (e.g. by `destroy-variant!`) before `restore-epoch` fired.
- **Unknown epoch** — the epoch id isn't in the buffer (truncated, never recorded).
- **Schema mismatch** — the stored `app-db` no longer conforms to the registered schema (hot-reload changed the shape).
- **Missing handler** — an event in the epoch's chain references a handler that's been unregistered.
- **Version mismatch** — the runtime's domino-version is newer than the stored epoch (rare).
- **Concurrent-drain rejection** — a drain is in flight; restore queued until it settles.

Causa renders the failure mode inline — same red toast, same `:reason` / `:explain` / `:missing-id` / `:expected`-`:got` payload. The runtime's contract is stable; Causa renders against it the same way whether the frame is the host app's or a Story variant's. This is the value of stable cross-host contracts — write one inspector, point it at any frame, the affordances work.

## When time-travel isn't the right tool

Two cases where you should reach for something else:

- **"I want to replay the variant from the start with a tweaked arg."** That's not time-travel; that's *re-run with override*. Edit the arg in the controls panel; the variant re-runs from the start with the new arg. Time-travel is for *examining what already happened*; not for *re-running with changes*. The distinction matters because the user-intent is different: time-travel preserves the past, re-run replaces it.

- **"I want to time-travel the trace bus, not the app-db."** The trace bus is a log, not a state. To re-render the trace from a different angle, use Causa's Trace tab filters (`:op-type`, tag, free-text). The trace is data you query; the app-db is a state you scrub.

The point of time-travel here, as in Causa, is that *the runtime already recorded the state.* Story is just the lens that lets you compare two variants' epoch buffers side-by-side, each in its own frame.

## You should now see

After working through this chapter:

- Selecting a variant in the sidebar should populate the RHS Causa panel with that variant's epoch history.
- Clicking the `◀` button in Causa's ribbon should step backwards through epochs; the canvas re-renders as `app-db` rewinds.
- Switching to a different variant should rebind Causa to the new frame's spine — fresh history, no contamination from the previous variant.
- A workspace with multiple cells should let each cell time-travel independently.

## When it doesn't work

- **Causa's panel is empty / says "no frame selected".** Either your build doesn't have Causa's preload on the classpath (check `re-frame.story.causa-preset/causa-available?` returns true), or you've not selected a variant (the shell needs an active variant to bind Causa against). The graceful degradation here is by design — Story is feature-detect-safe and renders a no-op when Causa's absent.

- **Time-travel buttons are greyed out.** The variant's frame hasn't recorded any epochs yet — usually means the `:events` slot is empty and the `:play-script` hasn't started. Run the play-script and try again.

- **Restoring an old epoch shows the failure-mode toast.** That's working as intended — the contract surfaces *why* the restore failed (schema mismatch, missing handler, etc.). Read the toast's payload; that's your bug. The most common cause in playgrounds is hot-reload changing the schema shape; reset the variant via the *Re-run* button to get a fresh buffer.

- **You can scrub past the boundary into another variant's history.** You can't; each variant frame has its own buffer; Causa is scoped to one at a time. If you think you're seeing cross-variant history, you're probably looking at the host app's default frame, not the variant's frame — check the chip in Causa's header.

## Where we go next

Chapter 7 is the final stretch — multi-substrate rendering. Same variant, three substrates (Reagent, UIx, Helix), three cells side-by-side. The audience for this chapter is narrow (adapter authors, component-library maintainers, projects migrating substrates), but the use case is genuinely interesting: visual diff *across rendering implementations*.

Next: [multi-substrate side-by-side](07-multi-substrate.md).
