# 3. Recorder + Test Codegen

> **What you'll build.** A `:play-script` body for a counter-increment scenario by clicking *record*, tapping the increment button three times in the canvas, clicking *stop*, and pasting the generated EDN into a `reg-variant`. You'll come out with a working `:story.counter/clicked-three-times` variant that asserts both *the events dispatched* and *the resulting app-db*, all without typing the assertion by hand.
>
> **You should have working before you start.** Chapter 1 finished — at least one variant rendering. Ideally the counter variant from chapter 1, since the recording gestures only make sense if there's something you can interact with.

This is the hero chapter. Storybook 9's headline feature is the *interaction-test recorder* — record clicks and form fills on a canvas, get a `play` function back. The feature reception was, if you read the launch comments, frankly enormous; it was the single thing they could have shipped that they did ship, and it got the most attention of anything in the 9.x line.

We're going to do the same gesture. Story's incarnation is — and we'll defend this claim — *cleaner*. The captured body is EDN, not TypeScript. The output pastes directly into a `reg-variant` form. Round-trips through MCP, through visual-regression services, through agent input pipelines. Same data shape Story already uses for hand-authored `:play-script` slots. Diffs cleanly. Doesn't require a TypeScript runtime to consume. We didn't get here by being cleverer than the Storybook team — we got here because re-frame2 already names everything that the recorder needs to name (dispatches are data, events are data, fx are data) and TypeScript has to invent the names.

Enough preamble. Let's record something.

## What it does, in one minute

1. Open a variant in *Canvas* mode. (We'll use the counter variant from chapter 1 — `:story.counter/empty`.)
2. Click *record* on the canvas toolbar. The shell starts capturing.
3. Interact with the component — click the *+* button three times.
4. Click *stop*.
5. A modal opens. Inside, the **generated `:play-script` body**:

   ```clojure
   {:play-script
    [[:dispatch-sync [:counter/initialise 0]]
     [:dispatch-sync [:counter/inc]]
     [:dispatch-sync [:counter/inc]]
     [:dispatch-sync [:counter/inc]]
     [:dispatch-sync [:rf.assert/path-equals [:count] 3]]]}
   ```

6. *Copy*. Paste into your stories namespace. Save. The recorded interaction is now a canonical variant body.

That's the whole gesture. The recorder doesn't capture mouse coordinates or DOM-level pixel events; it captures the *re-frame2 events your interactions produced*, plus an inferred `:rf.assert/path-equals` row reflecting the final `app-db` shape. The output is portable, readable, and parses by every other tool that consumes Story variants.

> 📸 **Screenshot needed**: the recorder modal showing a generated `:play-script` body in EDN. Annotate (1) the modal title, (2) the EDN body in the left pane, (3) the live preview run on the right pane, (4) the *Copy* button, (5) the *Edit before pasting* toggle.
>
> Save as: `/docs/images/story/03-recorder-modal.png`

## How the capture works

Story's recorder registers a `:rf.story.recorder/*` listener on the trace bus while recording. Every dispatched event that *originated from canvas interaction* — as distinct from setup events the variant's own `:events` slot fires — is captured into the buffer.

The recorder distinguishes capture-source events from setup events through a runtime stamp on every dispatch identifying its provenance. Setup-phase events carry `:rf.story/source :variant-events`; canvas-interaction events carry `:rf.story/source :user`. The recorder filters on `:user` only. This is the bit that lets you record over a non-trivial variant — say, one that loads test fixture data via `:events` and *then* lets you interact — without the recorder picking up the fixture loads.

When recording stops, the buffer is shape-mapped into the canonical `:play-script` form:

- Pure dispatches become `[:dispatch-sync <event-vec>]` step rows.
- DOM-level events captured through the recorder's `:entries` stream (clicks on selectors, typed text in inputs) become `[:click selector]` / `[:type selector text]` / `[:wait ms]` step rows in the richer DSL.
- An inferred `:rf.assert/path-equals` row lands at the end reflecting the variant's terminal `app-db` shape. (You can disable inference per-recording.)

The output is human-readable EDN. Storybook 9's TypeScript recorder produces `await canvas.getByRole('button').click(); await expect(...).toHaveTextContent('2');` — Story's recorder produces step-tuples in EDN. The EDN survives serialisation, diffs cleanly, and round-trips through every other tool in the Story / story-mcp stack.

## A worked example

Open the canvas for `:story.counter/empty` (the variant from chapter 1). Click *record* on the canvas toolbar (the chip with the dot).

Click the counter's *+* button three times. Watch the count tick: 1, 2, 3.

Click *stop*. The modal opens; the left pane has the EDN; the right pane runs the captured script against a throwaway frame and shows the result. Click *Copy*.

Open your `stories.cljs`. Add a new variant:

```clojure
(story/reg-variant :story.counter/clicked-three-times
  {:doc         "Counter after three increments from zero."
   :events      [[:counter/initialise 0]]
   :play-script [[:dispatch-sync [:counter/inc]]
                 [:dispatch-sync [:counter/inc]]
                 [:dispatch-sync [:counter/inc]]
                 [:dispatch-sync [:rf.assert/path-equals [:count] 3]]
                 [:dispatch-sync [:rf.assert/dispatched? [:counter/inc]]]]
   :tags        #{:dev :docs :test}
   :substrates  #{:reagent}})
```

Save. The Story shell hot-reloads; the new variant appears in the sidebar; click it; it auto-runs; you should see a green status pill at the top of the *Tests* tab.

**You should now see:** a new `clicked-three-times` variant under `story.counter`; clicking it shows a counter starting at zero (after the `:events` phase) and the *Tests* tab — auto-run on entry — reports five passing assertions.

There is a subtle but important detail here that's worth dwelling on. Notice that the three `:counter/inc` events live in `:play-script`, not in `:events`. We chose this on purpose: the `:rf.assert/dispatched?` assertion's accumulator is only wired during phase-4 (`:play-script`), not during phase-2 (`:events`). If we'd put the increments in `:events`, the dispatch-trace listener wouldn't have observed them, and `:rf.assert/dispatched?` would fail. The recorder gets this right because it captures from the `:user`-source phase — but the *general rule* matters for hand-authored variants: **assertions about dispatches only see what happens during play, not what happens during setup**. We didn't try to be clever and make this transparent because the cleverness would have hidden a real semantic distinction.

## Inference and friction

The recorder is *opinionated* about what's worth keeping:

- **Idle settle.** Sequences that the runtime quiesces between are recorded as one settle boundary, not a flood of fine-grained events. A click that dispatches three events asynchronously through the same router gets coalesced.

- **Sensitive-flagged events.** Events whose handler is metadata-tagged `:sensitive? true` are recorded with elided payload (a `:rf/redacted` marker in their place). The privacy contract from [Guide 23a — Privacy + Secrets](../guide/23a-privacy-secrets.md) applies to the recorder too; you can't accidentally bake a password into a `:play-script` by recording a login flow.

- **Multi-click coalescing.** Clicking *+1* five times produces five `[:dispatch-sync [:counter/inc]]` rows. Stable. Predictable. No `5× [:counter/inc]` coalescing — we deliberated on this and decided against it, because the coalesced form makes diffs less readable when the recording later needs editing.

You can edit the recording before pasting. The modal's left pane is the EDN; the right pane is a preview run against a throwaway frame, so you see what the play would do *before* committing it. If the recorder picked up an extra interaction you didn't mean (a stray hover that fired a dispatch), delete that row from the EDN and watch the preview re-run.

## Why EDN-first matters

Three things you get for free from the fact that the recorder's output is data:

- **Round-trips through MCP.** An agent host can call `start-recording!`, `stop-recording!`, get the EDN body back via `gen-play-snippet`, hand it to `reg-variant*`. The end-to-end is *generate a test from interaction* without leaving the agent's tool catalogue. Storybook 9's TypeScript output requires the agent to either run TS or hand-translate; both are friction.

- **Visual-regression diffs are stable.** The play body is data — sorted, formatted, comparable. Diff-ing two recordings of the same scenario after a refactor surfaces *what changed in the interaction*, not what changed in the formatting. (TypeScript whitespace and import order changes pollute every TS recording's diff.)

- **"Save current canvas state" falls out for free.** Tweak the controls on an existing variant, click *save as new variant…* at the bottom of the controls panel, and Story emits a `(reg-variant ...)` form pinned to the current selection — args, mode overrides, cell-local edits collapsed into one snapshot. Same modal, same EDN-paste flow:

  ```clojure
  ;; Click 'save as new variant…' after dragging :n up to 7 in :dark mode →
  (story/reg-variant :story.counter/saved-739221
    {:extends :story.counter/loaded
     :args    {:label "Counter" :n 7 :theme :dark}})
  ```

The `:extends` slot is the inheritance shape; the saved variant inherits everything from the named parent variant except where it overrides. You can save a chain of these without copy-pasting the world.

## When the recorder isn't the right tool

Three escape routes you'll reach for now and then:

- **A multi-step setup the user wouldn't do interactively.** Reach for the variant's `:events` slot directly — pure setup belongs there, with the play-script describing only the interactive segment. Don't try to "record yourself doing setup"; setup is data, not interaction.

- **An assertion the inferred ones don't cover.** The recorder infers `:rf.assert/path-equals` against terminal `app-db` shape. For `:rf.assert/sub-equals`, `:rf.assert/state-is`, or any of the other five canonical assertions, edit the captured EDN by hand. The pre-paste preview confirms the edit still runs.

- **A test that crosses frame boundaries.** Recorder captures one variant's frame. Cross-frame assertions belong in `cljs.test` against the result map from `run-variant`. (Most tests don't need this; mention it for completeness.)

The recorder is for the common case: *I just clicked through this; please remember what I did*. The escape hatches are there for the harder cases without polluting the common path.

## The MCP write surface — agent self-healing

Now we come to a use case that didn't exist five years ago and is going to matter more every year. The agent-facing surface is the same gesture in reverse. An agent host calls `reg-variant*` with an EDN body; Story validates against the schema; the variant lands in the registry; the canvas can render it. Combined with the recorder's stop emission, an agent can run the loop:

1. Generate a candidate variant body (from a prose description, from a screenshot, from a code-read).
2. Register it.
3. Watch the variant render in the canvas (or read the rendered hiccup off the result map, programmatically).
4. Read failures (`read-assertions`).
5. Refine and re-register.

That's the **agent self-healing loop**. It's the reason variant bodies are EDN to begin with.

The agent surface lives at [`tools/story-mcp/`](https://github.com/day8/re-frame2/tree/main/tools/story-mcp) — a stdio JSON-RPC server exposing the dev / docs / testing / write tool categories. The Write surface is gated behind `--allow-writes` at startup so an agent running with read-only intent can't accidentally register variants in production-class environments. The contract is documented at [`tools/story-mcp/spec/API.md`](https://github.com/day8/re-frame2/blob/main/tools/story-mcp/spec/API.md).

We don't expect every reader to be wiring agent loops to their playground today; we *do* expect every reader to be doing this within a couple of years, and we want the substrate to already support it when the time comes. The EDN-first choice is what makes it possible.

## Recorder API surface

For completeness, six facade entries on `re-frame.story`:

| Fn | Signature | Purpose |
|---|---|---|
| `start-recording!` | `(start-recording! variant-id)` | Begin recording dispatched events against `variant-id`'s frame. |
| `stop-recording!` | `(stop-recording!)` | Stop the in-flight recording; return captured events. |
| `clear-recording!` | `(clear-recording!)` | Drop the buffer + return the recorder to idle. |
| `recording?` | `(recording?)` | Is a recording in flight? |
| `recorder-state` | `(recorder-state)` | Read-only view of the current recorder state map. |
| `gen-play-snippet` | `(gen-play-snippet events opts)` | Pure codegen: render a captured event vector as a `(reg-variant ...)` EDN snippet. |

The richer DOM-capture-aware translator (translates the recorder's `:entries` stream into `:click` / `:type` / `:wait` steps) is exported by `re-frame.story.recorder.play-export`; the facade carries only the simpler event → `:dispatch-sync` projection. Most readers will only ever interact with the chrome's *record* / *stop* buttons; the API entries are there for when you want to script recordings from your own tooling.

## You should now see

After working through this chapter:

- The canvas toolbar has a *record* chip; clicking it changes its appearance (a recording-in-progress indicator).
- Interacting with the canvas while recording captures the dispatches.
- Clicking *stop* opens a modal with a complete EDN `:play-script` body.
- Pasting that body into a new `reg-variant` form gives you a working variant whose *Tests* tab auto-runs and reports green.

## When it doesn't work

- **You click *record*, interact, click *stop*, and the modal is empty.** Most common cause: your interactions didn't produce any `:rf.story/source :user` events. The recorder filters out setup-phase dispatches, and it filters out dispatches that don't carry the source stamp. If your view dispatches through some bespoke channel that bypasses the trace bus, the recorder won't see it. Switch the view to the canonical `(dispatch ...)` from `reg-view` and re-record.

- **The modal opens but the inferred assertion expects the wrong value.** The recorder reads `app-db`'s shape *at the moment you click stop*. If your interactions left async work in flight (a debounced save, a network call), the inferred assertion captures the in-flight value. Either wait for the operation to settle before clicking stop, or edit the assertion by hand.

- **You paste the recorded body and the variant says "no assertions recorded" in *Tests* mode.** Easy mistake: you pasted the recorder's `{:play-script [...]}` map inside an existing variant's `{:events [...]}` slot. The `:play-script` is a top-level slot on `reg-variant`, not nested inside `:events`. The two slots have completely different runtime semantics (setup vs play); they're not interchangeable.

- **The recorded body references events that aren't registered.** The view's button is dispatching a different event than you think, or the event handler isn't loaded into the build (typo in `:require` chains is the usual culprit). Open Causa's *Event* tab and check what's actually firing.

## Where we go next

Chapter 4 climbs up a level: instead of one variant in one cell, we mount *several* variants on one page as a *workspace*. We'll cover the args editor on the right (live cell-overrides that let you scrub a variant's args without authoring a new variant), and Modes — the Chromatic-style saved-tuple primitive that pivots a whole grid against a chrome-level theme/viewport/locale switch.

Next: [workspaces + args editor](04-workspaces.md).
