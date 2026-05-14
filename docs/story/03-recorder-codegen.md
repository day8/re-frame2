# 3. Recorder + Test Codegen

This is the hero chapter. Storybook 9's headline feature is the *interaction-test recorder* — record clicks and form fills on a canvas; out comes a play body. Story's incarnation is cleaner: the captured body is **EDN**, not TypeScript; the same map shape Story already uses for hand-authored `:play` slots.

## What it does, in one minute

1. Open a variant in Canvas mode.
2. Click *record* on the canvas toolbar. The shell starts capturing.
3. Interact with the component — click buttons, type into inputs, submit, hover. Causa's trace bus is the substrate; every dispatch lands in the recorder's buffer.
4. Click *stop*.
5. A modal opens. Inside, the **generated `:play` body**:
   ```clojure
   {:play [[:counter/initialise 0]
           [:counter/increment]
           [:counter/increment]
           [:rf.assert/path-equals [:count] 2]]}
   ```
6. *Copy*, paste into your stories namespace, save. The recorded interaction is now a canonical test.

That's the whole gesture. The recorder doesn't capture mouse coordinates or DOM-level events; it captures **the re-frame2 events your interactions produced** plus the resulting assertions. The output is portable, readable, and parses by every other tool that consumes Story variants.

## How the capture works

Story's recorder registers a `:rf.story.recorder/*` listener on the trace bus while recording. Every dispatched event that *originated from canvas interaction* (as distinct from setup events the variant's own `:events` slot fires) is captured into the buffer.

The recorder distinguishes capture-source events from setup events through the trace event's `:dispatch-source` tag — a runtime stamp on every dispatch identifying its provenance. Setup-phase events carry `:rf.story/source :variant-events`; canvas-interaction events carry `:rf.story/source :user`. The recorder filters on `:user` only.

When recording stops, the buffer is shape-mapped into the canonical `:play` form:

- Pure dispatches become bare event vectors.
- Dispatches that produced asserter-relevant `app-db` changes are followed by inferred `:rf.assert/path-equals` rows. (You can disable inference per-recording.)
- Long click sequences against the same target are coalesced when adjacent.

The output is human-readable. Storybook 9's TypeScript recorder produces `await canvas.getByRole('button').click(); await expect(...).toHaveTextContent('2');` — Story's recorder produces three EDN tuples. The EDN survives serialisation; the TypeScript surface doesn't.

## Inference and friction

The recorder is *opinionated* about what's worth keeping:

- **Idle settle** — sequences that the runtime quiesces between are recorded as one settle boundary, not a flood of fine-grained events.
- **Sensitive-flagged events** — events whose handler is metadata-tagged `:sensitive? true` are recorded with elided payload (a `:rf/redacted` marker in their place). The privacy contract from [Guide 23a](../guide/23a-privacy-secrets.md) applies to the recorder too.
- **Multi-click coalescing** — clicking *+1* five times produces five `[:counter/increment]` rows. Stable. Predictable. (No "5 × [:counter/increment]" coalescing — it'd break diff readability when the recording later needs editing.)

You can edit the recording before pasting. The modal's left pane is the EDN; the right pane is a preview run against a throwaway frame, so you see what the play would do *before* committing it.

## Why EDN-first matters

Three things you get for free:

- **Round-trips through MCP.** An agent host can call `recorder/start`, `recorder/stop`, get the EDN body back, hand it to `register-variant`. The end-to-end is *generate a test from interaction* without leaving the agent's tool catalogue. Storybook 9's TypeScript output requires the agent to either run TS, or hand-translate.
- **Visual-regression diffs are stable.** The play body is data — sorted, formatted, comparable. Diff-ing two recordings of the same scenario after a refactor surfaces *what changed in the interaction*, not what changed in the formatting.
- **The "save current canvas state" affordance falls out for free.** Tweak the controls on an existing variant, click *save as new variant…* at the bottom of the controls panel, and Story emits a `(reg-variant ...)` form pinned to the current selection — args, mode overrides, cell-local edits collapsed into one snapshot. Same modal, same EDN-paste flow.
  ```clojure
  ;; Click 'save as new variant…' after dragging :n up to 7 in a :dark mode →
  (story/reg-variant :story.counter/saved-739221
    {:extends :story.counter/happy-path
     :args    {:label "Counter" :n 7 :theme :dark}})
  ```

## When the recorder isn't the right tool

Three escape routes:

- **A multi-step setup the user wouldn't do interactively.** Reach for the variant's `:events` slot directly — pure setup belongs there, with the play sequence describing only the interactive segment.
- **An assertion the inferred ones don't cover.** The recorder infers `:rf.assert/path-equals`; for `:rf.assert/sub-equals`, `:rf.assert/state-is`, or any of the other five canonical assertions, edit the captured EDN. The pre-paste preview confirms the edit works.
- **A test that crosses frame boundaries.** Recorder captures one variant's frame. Cross-frame assertions belong in `cljs.test` against the result map from `run-variant`.

The recorder is for the common case: *I just clicked through this; please remember what I did*. The escape hatches are there for the harder cases without polluting the common path.

## MCP write surface

The agent-facing surface is the same gesture in reverse. An agent host calls `register-variant` with an EDN body; Story validates against the schema; the variant lands in the registry; the canvas can render it. Combined with the recorder's stop emission, an agent can run the loop:

1. Generate a candidate variant body (from a prose description, from a screenshot, from a code-read).
2. Register it.
3. Watch the variant render in the canvas.
4. Read failures (`read-failures`).
5. Refine and re-register.

That's the **agent self-healing loop**. It's the reason variant bodies are EDN to begin with.

The agent surface lives at [`tools/story-mcp/`](https://github.com/day8/re-frame2/tree/main/tools/story-mcp) — a stdio JSON-RPC server exposing nineteen tools across Dev / Docs / Testing / Write categories. The Write surface is gated behind `--allow-writes` at startup. The contract is in [`tools/story-mcp/README.md`](https://github.com/day8/re-frame2/blob/main/tools/story-mcp/README.md).

Next: [workspaces + args editor](04-workspaces.md).
