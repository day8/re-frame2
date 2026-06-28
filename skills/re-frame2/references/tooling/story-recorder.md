# Story Test Codegen — record-as-`:script`

> Recording canvas interactions and pasting the captured trace into a `:script` body. Assumes you already know what record-and-save UX is (Storybook 9's marquee feature) — this leaf covers re-frame2's specific recorder surface and the canvas-as-fixture pattern that makes it work.

> **Mental model: think in Storybook, map onto Story.** The recorder is Story's answer to Storybook 9's "record canvas interactions → CSF" feature. The difference is the output shape: Storybook emits a Testing-Library code translation; Story emits a pure-EDN `:script` step sequence with no DOM-event capture and no page-object layer. Picture the Storybook record-and-save flow, then map: `REC` toggle → `start-recording!` / `stop-recording!`; the generated CSF story → a `(reg-variant …)` form with an `:extends` link and a `:script` body. Full concept table in `stories.md` §Mental model.

## When to load

- A variant's `:script` body needs to grow but you'd rather drive the canvas than hand-author the event vectors.
- You're scripting an MCP agent that calls `start-recording!` / `stop-recording!` on the variant frame.
- You're explaining why this is one screenful of code in re-frame2 vs Storybook's Testing-Library translation layer.

Do **not** load this leaf to learn what a story is, or to author a variant body from scratch — see `stories.md` first. Load it for: the recorder's public surface, the filter layers, and the snippet that drops out.

## Canvas-as-fixture — why the recorder is trivial here

A variant's frame is already a self-contained fixture: phase-1 loaders seed remote-data, phase-2 `:setup` reaches the pre-render state, the canvas renders against that frame's app-db. Every interaction (click, type, route) lands as a `dispatch` on the variant's router; the trace bus already projects those dispatches as `:op-type :rf.event` + `:operation :rf.event/dispatched` emissions per Spec 009 §Listener contract.

So the recorder is one filter on the existing emit stream, scoped to the recording's target frame, and the output shape is the exact tagged-step sequence the runtime will replay as the variant's phase-4 `:script` (spec/017 §Public vocabulary). The codegen emits the PUBLIC `:script` authoring slot directly — the recorder no longer emits the transitional `:play-script` spelling (the registrar still accepts and lowers `:play-script` if you hand-write it, but the authored/public target is `:script`). No DOM-event capture, no Testing-Library translation, no page-object layer.

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

1. **Op-type** — only `:op-type :rf.event` + `:operation :rf.event/dispatched` emissions qualify (`:rf.fx`, `:rf.sub`, `:rf.view`, cofx traffic is dropped).
2. **Frame scope** — emission `:frame` must match the recording's target variant. Typing in another canvas while a recording is active is dropped.
3. **Event vocabulary** — `:rf.assert/*` events and Story-internal helpers (`:rf.story/*`, `:re-frame.story.*`) are filtered. Recorded `:script` bodies capture user intent; assertions get added by hand afterwards.
4. **Sensitivity** — events the runtime has stamped `:sensitive?` on the *emitted trace event* (auth, 2FA, password change, API-key rotation) replace the event vector with the placeholder `[:rf/redacted]` instead of riding the raw payload into the snippet. The temporal position survives; the secret never lands in `:script` source. The recorder keys off the trace event's top-level `:sensitive?` field (`re-frame.privacy/sensitive?`, re-exported as `rf/sensitive?`) — **NOT** handler metadata. Handler-meta `{:sensitive? true}` was removed from the runtime and does nothing. See the next section for what actually stamps that flag, plus the authoring rule.

## Sensitive events — record-but-redact

> **CRITICAL — what makes an event sensitive.** The recorder suppresses a row **iff the runtime stamped `:sensitive? true` on the emitted trace event** (it checks `(rf/sensitive? trace-event)`, i.e. the event's top-level `:sensitive?` field). That stamp is **path-based**, never handler-based. Marking a `reg-event` handler `{:sensitive? true}` does **nothing** — the runtime removed that annotation and no longer consults it, so a handler-meta-only "sensitive" login event ships its credentials verbatim into the `:script` body. Authors who rely on the old handler-meta flag will leak secrets.

The privacy contract classifies at the **owner of the data** — the three-owner model is defined once in [`../cross-cutting/privacy-and-elision.md` §The three-owner table](../cross-cutting/privacy-and-elision.md#where-you-declare-it-the-three-owner-table). The recorder-specific fact is **which of those owner declarations stamp the top-level trace `:sensitive?` the recorder gates on**:

| Owner declaration | Stamps top-level trace `:sensitive?`? | Recorder behaviour |
|---|---|---|
| Durable app-db `:sensitive` **classification effect** (returned alongside `:db`) | **Yes** — the sensitive handler scope is stamped | Row redacted → `[:rf/redacted]` |
| Registration `:sensitive [[:path]]` | **Yes** — the event-emit record is stamped for the named payload paths | Row redacted → `[:rf/redacted]` |
| Handler-meta `{:sensitive? true}` | **No** — removed; ignored | Row kept, payload verbatim — **do not rely on this** |

So to get a recorded login / 2FA / API-key flow suppressed, classify the secret's *path* at its owner (see the three-owner table): a durable app-db secret via the writing event's `:sensitive` classification effect, or a payload-only secret in the submit handler's registration `:sensitive` metadata. Either route stamps the trace event and triggers whole-row redaction. The contract is **fail-open** — a path you never classify ships its row verbatim.

> **Retired surfaces.** Earlier guidance taught `rf/redact-interceptor [[:path]]` (a positional payload scrubber), `re-frame.marks` / `rf/add-marks` (imperative app-db marks), and a frame `:sensitive {:app-db …}` durable annotation. All are **removed** — `redact-interceptor` is replaced by registration `:sensitive` metadata; the marks API and the frame annotation by the `:sensitive` classification effect a handler returns alongside `:db`. Calling the removed names no longer resolves to a public var.

When the runtime *has* stamped the event sensitive, it still appears in the recording — as the placeholder vector `[:rf/redacted]` rather than the verbatim event payload. The row's temporal position survives so the dev can see "click → auth happened → click", but the credential / PII / auth-token never rides into the snippet text:

```clojure
;; A recording that includes a schema-sensitive dispatch lands like this:
[[:counter/inc]
 [:rf/redacted]                ;; whole-row placeholder for an :auth/login dispatch
 [:counter/inc]]
```

**Whole-row placeholder vs payload-only redaction.** The recorder substitutes the *whole event vector* with the single-element placeholder `[:rf/redacted]`, not a redacted-payload vector. A *payload-only* redaction (only named keys scrubbed, e.g. `[:auth/login {:password :rf/redacted}]`) can appear on the trace surface when a registration's `:sensitive` paths name individual payload keys; that row may not be whole-row-suppressed. Whole-row `[:rf/redacted]` is the recorder's own substitution, applied when the trace event is stamped `:sensitive?` — which a durable app-db `:sensitive` classification effect on a focused handler does, as does a whole-payload registration `:sensitive [[]]`.

Properties of the whole-row placeholder:

- **Round-trips cleanly.** `[:rf/redacted]` is a well-formed event vector; `read-string` survives. Re-playing the snippet finds no handler for `:rf/redacted`, so dispatch raises a clean `:rf.error/handler-not-found` rather than a malformed-event-vector error — the dev sees they need to replace the placeholder before re-play works.
- **The redaction counter still bumps.** The recording overlay's REDACTED indicator shows "N rows redacted" alongside the placeholders themselves, so the dev knows how many slots are pasteholders even before scrolling.
- **Revealing the verbatim event is a deliberate trusted-local opt-in.** In-box debug only; never enable for snippets that ride into source control. Under EP-0015 this is the per-(tool, frame) `:rf.egress/local-raw` posture — local tools default to `:rf.egress/local-redacted`, and lifting that to raw is an operator act that is itself trace-visible (auditable). Story exposes this as its on-box dev-UI egress profile: `(story/configure! {:rf.story/egress-profile :rf.egress/local-raw})` flips the recorder (and Story's other value-bearing surfaces) to the trusted-local boundary; the redacting default is `:rf.egress/local-redacted`, and narrowing back retroactively scrubs the per-variant buffers. There is no process-global on/off privacy toggle — the question is always *which boundary is this?*

Authoring rule: do NOT publish a `:script` body containing `[:rf/redacted]` slots into committed source — they record credential flows, not reproducible tests. Hand-author the equivalent dispatch with a synthetic credential, or scope the recording away from the sensitive step. And do NOT reach for handler-meta `{:sensitive? true}` — it's a no-op; classify the secret's *path* at its owner (durable app-db secret → writing event's `:sensitive` classification effect; payload-only secret → submit handler's registration `:sensitive` metadata) so the runtime stamps the trace event the recorder gates on. Full contract: [`../cross-cutting/privacy-and-elision.md`](../cross-cutting/privacy-and-elision.md).

## Worked example — recorded `:script` body

The author starts with a `happy-path` variant. They want a new variant that exercises three increments and a `:by 7`. They click `REC` in the toolbar (right of the strip, just before `[reset]`), drive the canvas, click `REC` again. The save-as-variant modal shows the generated form — the codegen emits the PUBLIC `:script` slot (an inner `{:auto-run? true :script [...]}` PlaySpec map), each captured event wrapped as a `[:dispatch-sync <ev>]` step:

```clojure
(story/reg-variant :story.counter/recorded-739221
  {:extends :story.counter/happy-path
   :script  {:auto-run? true
             :script    [[:dispatch-sync [:counter/inc]]
                         [:dispatch-sync [:counter/inc]]
                         [:dispatch-sync [:counter/inc]]
                         [:dispatch-sync [:counter/by 7]]]}})
```

The author edits the id (`recorded-739221` → `triple-inc-then-seven`), adds a `:doc`, adds the assertions they want by hand:

```clojure
(story/reg-variant :story.counter/triple-inc-then-seven
  {:doc     "Three increments then by-7 lands on ten."
   :extends :story.counter/happy-path
   :script  {:auto-run? true
             :script    [[:dispatch-sync [:counter/inc]]
                         [:dispatch-sync [:counter/inc]]
                         [:dispatch-sync [:counter/inc]]
                         [:dispatch-sync [:counter/by 7]]
                         [:dispatch-sync [:rf.assert/path-equals [:count] 10]]]}})
```

Paste into the stories namespace. Done. (The bare-vector shorthand `:script [[:dispatch-sync …] …]` desugars to `{:script <vector> :auto-run? true}` — the recorder emits the explicit map so the `:auto-run?` flag is visible at the paste site.)

## Common gotchas — recorder-specific

- **Recorder is dev-only; elides under `:advanced`.** Like every Story registration, the recorder ns + UI + API are gated by `:rf.story/enabled?`. Production builds drop the trace-bus listener and the public API stubs return `nil`.
- **Cross-frame dispatches are dropped, not buffered.** If the user types in another canvas mid-recording, those events do not land in the trace and re-replaying them is not possible. Scope a recording to one variant frame at a time.
- **Assertions are not auto-recorded.** The third filter layer explicitly drops `:rf.assert/*`. Authored assertions are a deliberate act — the recorder captures *what happened*, the author decides *what to assert about it*.
- **`:extends` is the canonical extension point for recorded variants.** The recorder generates an `:extends` link to the source variant rather than re-emitting `:component` / `:args` / `:decorators`. Edit the id and add assertions; do not duplicate the parent's body.
- **One trace-bus callback, process-wide.** The shell installs it at mount, removes at unmount. No per-variant listener registration; the callback's filters do the routing.

## MCP — `record-as-variant`

The story-mcp `record-as-variant` tool calls the same public surface through the Tool-Pair bridge: `start-recording!` → drive interactions (programmatic dispatches or human-in-canvas) → `stop-recording!` → `gen-play-snippet` → snippet returned as the tool's structured output. `record-as-variant` is a **run-side tool owned by the `re-frame2-pair` skill** (it captures a cascade against a live runtime) — this authoring skill is not allow-listed for it. Author the recorded `:script` body here; drive the capture from a `re-frame2-pair` session. See `story-mcp-loop.md` for the author/refine vs run-side split and the handoff recipe.

**The MCP path inherits the same four-filter pipeline, including layer 4 (sensitivity).** `record-as-variant` does not — and must not — bypass `:sensitive?` redaction: the tool's structured output is shipped over an MCP transport to an agent process, which is a wire boundary, so sensitive payloads must never appear in the returned `:script` body. The tool also never accepts a `:rf.privacy/show-sensitive? true` override at call time. If a recording session captured any sensitive events, the response carries the same `[:rf/redacted]` placeholders the in-canvas overlay shows, plus a metadata count of redactions for the agent to surface to the human.

Authoring rule for tools consuming `gen-play-snippet` output (or calling `record-as-variant`): treat any `[:rf/redacted]` slot as a non-reproducible step — do not auto-commit a `:script` body containing one; ask the human to hand-author the equivalent dispatch with a synthetic credential, or rescope the recording. Normative contract: [`../cross-cutting/privacy-and-elision.md`](../cross-cutting/privacy-and-elision.md) §Story recorder.

## Deeper material

- Capture boundary, public API, MCP wiring rationale → `tools/story/spec/005-SOTA-Features.md` §Test Codegen.
- Trace-bus listener primitive → `tools/story/spec/003-Render-Shell.md` §Trace bus, and Spec 009 §Listener contract.
- Variant body shape (where the recorded `:script` lands) → `stories.md` (sibling leaf).
- Story-MCP author/refine side + the run-side handoff to `re-frame2-pair` (which owns `record-as-variant` / `run-variant` / `read-failures`) → `story-mcp-loop.md` (sibling leaf).

---

*Derived from `tools/story/spec/005-SOTA-Features.md` §Test Codegen and `re-frame.story.recorder` source @ main. Re-verify after recorder API or filter-layer changes.*
