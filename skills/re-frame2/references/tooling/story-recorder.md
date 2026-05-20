# Story Test Codegen — record-as-`:play`

> Recording canvas interactions and pasting the captured trace into a `:play` body. Assumes you already know what record-and-save UX is (Storybook 9's marquee feature) — this leaf covers re-frame2's specific recorder surface and the canvas-as-fixture pattern that makes it work.

## When to load this leaf

- A variant's `:play` body needs to grow but you'd rather drive the canvas than hand-author the event vectors.
- You're scripting an MCP agent that calls `start-recording!` / `stop-recording!` on the variant frame.
- You're explaining why this is one screenful of code in re-frame2 vs Storybook's Testing-Library translation layer.

Do **not** load this leaf to learn what a story is, or to author a variant body from scratch — see `stories.md` first. Load it for: the recorder's public surface, the filter layers, and the snippet that drops out.

## Canvas-as-fixture — why the recorder is trivial here

A variant's frame is already a self-contained fixture: phase-1 loaders seed remote-data, phase-2 `:events` reach the pre-render state, the canvas renders against that frame's app-db. Every interaction (click, type, route) lands as a `dispatch` on the variant's router; the trace bus already projects those dispatches with `:event/dispatched` emissions per Spec 009 §Listener contract.

So the recorder is one filter on the existing emit stream, scoped to the recording's target frame, and the output shape is the exact vector the runtime will re-dispatch under `:play`. No DOM-event capture, no Testing-Library translation, no page-object layer.

## Public surface

```clojure
;; In re-frame.story
(story/start-recording!  variant-id)   ; idle → recording; returns recorder state
(story/stop-recording!)                ; recording → captured; returns state map
(story/recording?)                     ; boolean
(story/recorder-state)                 ; read-only view; observe transitions
(story/clear-recording!)               ; captured → idle; drop the trace
(story/gen-play-snippet events opts)   ; pure data → string snippet
```

`gen-play-snippet` opts: `:variant-id` (required keyword id), `:doc` (optional docstring), `:extends` (variant id to inherit `:component` / `:args` / `:decorators` from), `:alias` (form alias, default `story`). The returned string is `read-string`-able and round-trips through the registrar.

## Four filter layers

The trace-bus callback short-circuits unless a recording is in flight, so it's free to leave installed. When recording, four filters apply (in order):

1. **Op-type** — only `:event/dispatched` emissions qualify (`:fx`, `:sub`, `:view`, `:cofx` traffic is dropped).
2. **Frame scope** — emission `:frame` must match the recording's target variant. Typing in another canvas while a recording is active is dropped.
3. **Event vocabulary** — `:rf.assert/*` events and Story-internal helpers (`:rf.story/*`, `:re-frame.story.*`) are filtered. Recorded `:play` bodies capture user intent; assertions get added by hand afterwards.
4. **Sensitivity** — events whose handler is registered `:sensitive? true` (auth, 2FA, password change, API-key rotation) replace the event vector with the placeholder `[:rf/redacted]` instead of riding the raw payload into the snippet. The temporal position survives; the secret never lands in `:play` source. See the next section for details and the authoring rule.

## Sensitive events — record-but-redact

A handler registered `:sensitive? true` (an auth flow, a 2FA verify, a password change) still appears in the recording, but as the placeholder vector `[:rf/redacted]` rather than the verbatim event payload. Per rf2-hdadz (pragmatic stance, 2026-05-14): the row's temporal position survives so the dev can see "click → auth happened → click", but the credential / PII / auth-token never rides into the snippet text.

```clojure
;; A recording that includes a sensitive dispatch lands like this:
[[:counter/inc]
 [:rf/redacted]                ;; placeholder for an :auth/login dispatch
 [:counter/inc]]
```

Properties:

- **Round-trips cleanly.** `[:rf/redacted]` is a well-formed event vector; `read-string` survives. Re-playing the snippet finds no handler for `:rf/redacted`, so dispatch raises a clean `:rf.error/handler-not-found` rather than a malformed-event-vector error — the dev sees they need to replace the placeholder before re-play works.
- **The redaction counter still bumps.** The recording overlay's REDACTED indicator shows "N rows redacted" alongside the placeholders themselves, so the dev knows how many slots are pasteholders even before scrolling.
- **`:rf.privacy/show-sensitive? true` keeps the verbatim event.** In-box debug only; never enable for snippets that ride into source control. Set via `(story/configure! {:rf.privacy/show-sensitive? true})` early in dev boot.

Authoring rule: do NOT publish a `:play` body containing `[:rf/redacted]` slots into committed source — they're recordings of credential flows, not reproducible tests. Either hand-author the equivalent dispatch with a synthetic credential, or scope the recording away from the sensitive step.

## Worked example — recorded `:play` body

The author starts with a `happy-path` variant. They want a new variant that exercises three increments and a `:by 7`. They click `REC` in the toolbar (right of the strip, just before `[reset]`), drive the canvas, click `REC` again. The save-as-variant modal shows the generated form:

```clojure
(story/reg-variant :story.counter/recorded-739221
  {:extends :story.counter/happy-path
   :play [[:counter/inc]
          [:counter/inc]
          [:counter/inc]
          [:counter/by 7]]})
```

The author edits the id (`recorded-739221` → `triple-inc-then-seven`), adds a `:doc`, adds the assertions they want by hand:

```clojure
(story/reg-variant :story.counter/triple-inc-then-seven
  {:doc     "Three increments then by-7 lands on ten."
   :extends :story.counter/happy-path
   :play [[:counter/inc]
          [:counter/inc]
          [:counter/inc]
          [:counter/by 7]
          [:rf.assert/path-equals [:count] 10]]})
```

Paste into the stories namespace. Done.

## Common gotchas — recorder-specific

- **Recorder is dev-only; elides under `:advanced`.** Like every Story registration, the recorder ns + UI + API are gated by `:rf.story/enabled?`. Production builds drop the trace-bus listener and the public API stubs return `nil`.
- **Cross-frame dispatches are dropped, not buffered.** If the user types in another canvas mid-recording, those events do not land in the trace and re-replaying them is not possible. Scope a recording to one variant frame at a time.
- **Assertions are not auto-recorded.** The third filter layer explicitly drops `:rf.assert/*`. Authored assertions are a deliberate act — the recorder captures *what happened*, the author decides *what to assert about it*.
- **`:extends` is the canonical extension point for recorded variants.** The recorder generates an `:extends` link to the source variant rather than re-emitting `:component` / `:args` / `:decorators`. Edit the id and add assertions; do not duplicate the parent's body.
- **One trace-bus callback, process-wide.** The shell installs it at mount, removes at unmount. No per-variant listener registration; the callback's filters do the routing.

## MCP — `record-as-variant`

The story-mcp `record-as-variant` tool calls the same public surface through the Tool-Pair bridge: `start-recording!` → drive interactions (programmatic dispatches or human-in-canvas) → `stop-recording!` → `gen-play-snippet` → snippet returned as the tool's structured output. See `story-mcp-loop.md` for the agent self-healing loop that uses this.

**The MCP path inherits the same four-filter pipeline, including layer 4 (sensitivity).** `record-as-variant` does not — and must not — bypass `:sensitive?` redaction: the tool's structured output is shipped over an MCP transport to an agent process, which is a wire boundary, so sensitive payloads must never appear in the returned `:play` body. The tool also never accepts a `:rf.privacy/show-sensitive? true` override at call time. If a recording session captured any sensitive events, the response carries the same `[:rf/redacted]` placeholders the in-canvas overlay shows, plus a metadata count of redactions for the agent to surface to the human.

Authoring rule for tools that consume `gen-play-snippet` output (or call `record-as-variant` directly): treat any `[:rf/redacted]` slot as a non-reproducible step. Do not auto-commit a `:play` body containing `[:rf/redacted]` into source control; either ask the human to hand-author the equivalent dispatch with a synthetic credential, or rescope the recording to avoid the sensitive step. See [`../cross-cutting/privacy-and-elision.md`](../cross-cutting/privacy-and-elision.md) §Story recorder for the rf2-hdadz normative contract.

## Deeper material

- Capture boundary, public API, MCP wiring rationale → `tools/story/spec/005-SOTA-Features.md` §Test Codegen.
- Trace-bus listener primitive → `tools/story/spec/003-Render-Shell.md` §Trace bus, and Spec 009 §Listener contract.
- Variant body shape (where the recorded `:play` lands) → `stories.md` (sibling leaf).
- MCP self-healing loop → `story-mcp-loop.md` (sibling leaf).

---

*Derived from `tools/story/spec/005-SOTA-Features.md` §Test Codegen (rf2-5fc15) and `re-frame.story.recorder` source @ main. Re-verify after recorder API or filter-layer changes.*
