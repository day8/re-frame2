# Story Test Codegen — record-as-`:script`

> Recording canvas interactions and pasting the captured trace into a `:script` body. Assumes you already know what record-and-save UX is (Storybook 9's marquee feature) — this leaf covers re-frame2's specific recorder surface and the canvas-as-fixture pattern that makes it work.

> **Mental model: think in Storybook, map onto Story.** The recorder is Story's answer to Storybook 9's "record canvas interactions → CSF" feature. The difference is the output shape: Storybook emits a Testing-Library code translation; Story emits a pure-EDN `:script` step sequence with no DOM-event capture and no page-object layer. Picture the Storybook record-and-save flow, then map: `REC` toggle → `start-recording!` / `stop-recording!`; the generated CSF story → a `(reg-variant …)` form with an `:extends` link and a `:script` body. Full concept table in `stories.md` §Mental model.

## When to load

- A variant's `:script` body needs to grow but you'd rather drive the canvas than hand-author the event vectors.
- You're driving the recorder from an agent session — a `re-frame2-pair` session calling `start-recording!` / `stop-recording!` on the variant frame through `eval-cljs`.
- You're explaining why this is one screenful of code in re-frame2 vs Storybook's Testing-Library translation layer.

Do **not** load this leaf to learn what a story is, or to author a variant body from scratch — see `stories.md` first. Load it for: the recorder's public surface, the filter layers, and the snippet that drops out.

## Canvas-as-fixture — why the recorder is trivial here

A variant's frame is already a self-contained fixture: phase-1 loaders seed remote-data, phase-2 `:setup` reaches the pre-render state, the canvas renders against that frame's app-db. Every interaction (click, type, route) lands as a `dispatch` on the variant's router; the trace bus already projects those dispatches as `:op-type :rf.event` + `:operation :rf.event/dispatched` emissions per Spec 009 §Listener contract.

So the recorder is one filter on the existing emit stream, scoped to the recording's target frame, and the output shape is the exact tagged-step sequence the runtime will replay as the variant's phase-4 `:script` (spec/017 §Public vocabulary). The codegen emits the PUBLIC `:script` authoring slot directly — the only spelling the registrar accepts (the retired `:play-script` key fails shape validation). No DOM-event capture, no Testing-Library translation, no page-object layer.

## Public surface

```clojure
;; In re-frame.story
(rf.story/start-recording!  variant-id)   ; idle → recording; returns recorder state
(rf.story/stop-recording!)                ; recording → captured; returns state map
(rf.story/recording?)                     ; boolean
(rf.story/recorder-state)                 ; read-only view; observe transitions
(rf.story/clear-recording!)               ; captured → idle; drop the trace
(rf.story/gen-play-snippet events opts)   ; pure data → string snippet
```

`gen-play-snippet` opts: `:variant-id` (required keyword id), `:doc` (optional docstring), `:extends` (variant id to inherit `:component` / `:args` / `:decorators` from), `:alias` (form alias, default `rf.story`, the canonical `re-frame.story` alias, so the form pastes into a namespace that requires `[re-frame.story :as rf.story]`). The returned string is `read-string`-able and round-trips through the registrar.

## Four filter layers

The trace-bus callback short-circuits unless a recording is in flight, so it's free to leave installed. When recording, four filters apply (in order):

1. **Op-type** — only `:op-type :rf.event` + `:operation :rf.event/dispatched` emissions qualify (`:rf.fx`, `:rf.sub`, `:rf.view`, cofx traffic is dropped).
2. **Frame scope** — emission `:frame` must match the recording's target variant. Typing in another canvas while a recording is active is dropped.
3. **Event vocabulary** — `:rf.assert/*` events and Story-internal helpers (`:rf.story/*`, `:re-frame.story.*`) are filtered. Recorded `:script` bodies capture user intent; assertions get added by hand afterwards.
4. **Sensitivity** — the recorder captures the event vector the dispatched trace carries, and the framework has already projected it through the event's registration: each payload path the registration's `:sensitive` metadata names (auth, 2FA, password change, API-key rotation) reads `:rf/redacted`, and `[[]]` redacts the whole payload (`[:auth/login :rf/redacted]`). The row keeps its position and its event id; the secret never lands in `:script` source. Separately, a trace event carrying a top-level `:sensitive? true` (`re-frame.privacy/sensitive?`, re-exported as `rf/sensitive?`) is replaced by the whole-row placeholder `[:rf/redacted]` — but no framework emitter stamps a dispatched event that way, so that placeholder appears only when the code emitting the trace supplies the tag. Handler-meta `{:sensitive? true}` does nothing. See the next section for the owner table and the authoring rule.

## Sensitive events — record-but-redact

> **CRITICAL — what keeps a secret out of a recording.** The event's **registration** does. A `reg-event` whose `:sensitive` metadata names the secret's payload paths has those paths read `:rf/redacted` on the dispatched trace before the recorder sees it, so the row records with its event id and the named paths redacted (`:sensitive [[]]` records `[:auth/login :rf/redacted]`). Marking a `reg-event` handler `{:sensitive? true}` does **nothing** — the runtime does not consult it, so a handler-meta-only "sensitive" login event ships its credentials verbatim into the `:script` body. Nor does the recorder's whole-row placeholder protect an unclassified event: it needs a top-level `:sensitive? true` on the trace event (`(rf/sensitive? trace-event)`), and no framework emitter stamps one on a dispatched event. Authors who rely on the handler-meta flag will leak secrets.

The privacy contract classifies at the **owner of the data** — the three-owner model is defined once in [`../cross-cutting/privacy-and-elision.md` §The three-owner table](../cross-cutting/privacy-and-elision.md#where-you-declare-it-the-three-owner-table). The recorder-specific fact is **which of those owner declarations stamp the top-level trace `:sensitive?` the recorder gates on**:

| Owner declaration | Stamps top-level trace `:sensitive?`? | Recorder behaviour |
|---|---|---|
| Durable app-db `:sensitive` **classification effect** (returned alongside `:db`) | **No** — it classifies an app-db path, which the dispatched event vector does not carry | Row kept, payload verbatim — classify the payload at its registration too |
| Registration `:sensitive [[:path]]` | **No** — the trace event's named payload paths already read `:rf/redacted`, so nothing is stamped | Row kept, the named paths `:rf/redacted` |
| Handler-meta `{:sensitive? true}` | **No** — not honoured; ignored | Row kept, payload verbatim — **do not rely on this** |

So to keep a login / 2FA / API-key secret out of a recording, classify the secret's *path* in the submit handler's registration `:sensitive` metadata, which keeps the row and redacts the named paths inside it; a durable app-db copy of the secret is classified separately, by the writing event's `:sensitive` classification effect (see the three-owner table). The contract is **fail-open** — a path you never classify ships its row verbatim.

> **Surfaces that do not exist.** The non-existent-API list (`redact-interceptor` / `add-marks` / frame `:sensitive {:app-db …}` and what to use instead) is owned by [`../cross-cutting/privacy-and-elision.md` §Surfaces that do not exist](../cross-cutting/privacy-and-elision.md#surfaces-that-do-not-exist--use-the-model) — reaching for any of them is a wrong turn; classify the secret's *path* at its owner instead.

A classified dispatch still appears in the recording, in position, with its event id and its classified paths redacted. The row's temporal position survives so the dev can see "click → auth happened → click", but the credential / PII / auth-token never rides into the snippet text:

```clojure
;; A recording that includes a registration-sensitive dispatch lands like this:
[[:counter/inc]
 [:auth/login {:user "ada" :password :rf/redacted}]   ;; registered :sensitive [[:password]]
 [:counter/inc]]
```

**Payload redaction vs the whole-row placeholder.** The redacted payload above is the framework's projection of the trace, not a recorder substitution, so lifting Story's egress profile to `:rf.egress/local-raw` (below) does not reveal it; a whole-payload registration `:sensitive [[]]` records as `[:auth/login :rf/redacted]`. The single-element placeholder `[:rf/redacted]` is the recorder's own substitution of the *whole event vector*, event id included, applied only when the dispatched trace event carries a top-level `:sensitive? true` — which none of the declarations in the table above stamps, so it appears only when the code emitting the trace supplies that tag.

Properties of the whole-row placeholder:

- **Round-trips cleanly.** `[:rf/redacted]` is a well-formed event vector; `read-string` survives. Re-playing the snippet finds no handler for `:rf/redacted`, so dispatch raises a clean `:rf.error/no-such-handler` rather than a malformed-event-vector error — the dev sees they need to replace the placeholder before re-play works.
- **The redaction counter still bumps.** The recording overlay's REDACTED indicator shows "N rows redacted" alongside the placeholders themselves, so the dev knows how many slots are pasteholders even before scrolling.
- **Revealing the verbatim event is a deliberate trusted-local opt-in.** In-box debug only; never enable for snippets that ride into source control. Under EP-0015 this is the per-(tool, frame) `:rf.egress/local-raw` posture — local tools default to `:rf.egress/local-redacted`, and lifting that to raw is an operator act that is itself trace-visible (auditable). Story exposes this as its on-box dev-UI egress profile: `(rf.story/configure! {:rf.story/egress-profile :rf.egress/local-raw})` flips the recorder (and Story's other value-bearing surfaces) to the trusted-local boundary; the redacting default is `:rf.egress/local-redacted`, and narrowing back retroactively scrubs the per-variant buffers. There is no process-global on/off privacy toggle — the question is always *which boundary is this?*

Authoring rule: do NOT publish a `:script` body containing `:rf/redacted` (a redacted payload value or a `[:rf/redacted]` placeholder row) into committed source — it records a credential flow, not a reproducible test. Hand-author the equivalent dispatch with a synthetic credential, or scope the recording away from the sensitive step. And do NOT reach for handler-meta `{:sensitive? true}` — it's a no-op; classify the secret's payload *path* in the submit handler's registration `:sensitive` metadata, which is what redacts it in the recorded row (a durable app-db copy is classified separately, by the writing event's `:sensitive` classification effect). Full contract: [`../cross-cutting/privacy-and-elision.md`](../cross-cutting/privacy-and-elision.md).

## Worked example — recorded `:script` body

The author starts with a `happy-path` variant. They want a new variant that exercises three increments and a `:by 7`. They click `REC` in the toolbar (right of the strip, just before `[reset]`), drive the canvas, click `REC` again. The save-as-variant modal shows the generated form — the codegen emits the PUBLIC `:script` slot (an inner `{:auto-run? true :script [...]}` PlaySpec map), each captured event wrapped as a `[:dispatch-sync <ev>]` step:

```clojure
(rf.story/reg-variant :story.counter/recorded-739221
  {:extends :story.counter/happy-path
   :script  {:auto-run? true
             :script    [[:dispatch-sync [:counter/inc]]
                         [:dispatch-sync [:counter/inc]]
                         [:dispatch-sync [:counter/inc]]
                         [:dispatch-sync [:counter/by 7]]]}})
```

The author edits the id (`recorded-739221` → `triple-inc-then-seven`), adds a `:doc`, adds the assertions they want by hand:

```clojure
(rf.story/reg-variant :story.counter/triple-inc-then-seven
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

## Driving the recorder from an agent session

There is **no headless MCP recorder tool** (`tools/story-mcp/spec/002-Tool-Registry.md` §What's NOT in the registry): the story-mcp server has a single stdio dispatch loop, so a server-side recorder would block it for the whole capture window, no MCP client could drive an event *during* the recording, and the capture would always come back empty. Interactive canvas recording is performed **through Pair in the attached CLJS runtime**: in a `re-frame2-pair` session, drive `re-frame.story/start-recording!` → interact with the canvas (or dispatch programmatically) → `stop-recording!` → `gen-play-snippet` via `eval-cljs` against the live browser heap, where the recorder and the interaction actually share a runtime. Author the resulting `:script` body here; drive the capture from a `re-frame2-pair` session. Recording and promotion are two different captures: the recorder captures a live interaction and emits a `:script` body as data for you to paste, while promotion captures a finished run as a run artifact and registers a regression variant from it (`story-mcp-loop.md` §Three recipes). `story-mcp-loop.md` §Which host to use is the rule that sends recording to the browser.

Authoring rule for tools consuming `gen-play-snippet` output: treat any step carrying `:rf/redacted` (a redacted payload value or a `[:rf/redacted]` placeholder row) as non-reproducible — do not auto-commit a `:script` body containing one; ask the human to hand-author the equivalent dispatch with a synthetic credential, or rescope the recording. Normative contract: [`../cross-cutting/privacy-and-elision.md` §Where you declare it: the three-owner table](../cross-cutting/privacy-and-elision.md#where-you-declare-it-the-three-owner-table).

## Deeper material

- Capture boundary, public API, MCP wiring rationale → `tools/story/spec/005-SOTA-Features.md` §Test Codegen.
- Trace-bus listener primitive → `tools/story/spec/003-Render-Shell.md` §Trace bus, and Spec 009 §Listener contract.
- Variant body shape (where the recorded `:script` lands) → `stories.md` (sibling leaf).
- Which host to use, the Story-MCP authoring side, the three recipes (promotion among them), and the run-side handoff to `re-frame2-pair`, whose session runs the live loop by calling `re-frame.story/run-variant` through `eval-cljs` (the story-mcp `run-variant` / `read-failures` tools are allow-listed by no skill) → `story-mcp-loop.md` (sibling leaf).

---

*Derived from `tools/story/spec/005-SOTA-Features.md` §Test Codegen and `re-frame.story.recorder` source @ main. Re-verify after recorder API or filter-layer changes.*
