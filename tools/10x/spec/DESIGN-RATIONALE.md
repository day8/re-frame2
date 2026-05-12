# DESIGN-RATIONALE

The 13 direction-setting decisions that shape Causa. Each entry
captures:

- **The question** that was being decided.
- **Options considered** that were walked through.
- **The pick** that was locked.
- **The why** — the reasoning trail.
- **Date locked** + the locker.

This doc is the load-bearing artefact of the per-tool spec
convention. It exists so that a future contributor (human or AI) can
read it and not re-ask the same questions. Where the spec text reads
"per lock #N in DESIGN-RATIONALE.md", this is where to come.

The two findings docs in [`findings/`](./findings/) carry the
session-trail prose. This doc is the consolidated set of locks
those sessions produced.

---

## Lock #1 — Name

**Locked 2026-05-11 (Mike).** **Causa.** Package: `day8/re-frame2-causa`.

### Question

What does the re-frame-10x successor get called?

### Options considered

- **`re-frame-10x v2`** — keep the original name, signal continuity.
  The brand has muscle memory. Rejected because v2 is structurally
  new (the data shape is re-frame2's, not v1's; the hero panel is
  different; the substrate is the trace bus + epoch history, not
  v1's parallel ring buffer).
- **`re-frame-debug`** — descriptive. Rejected for being generic and
  un-Googleable.
- **`re-frame2-devtools`** — descriptive. Rejected as above plus
  awkward.
- **`Causa`** — Latin for "cause." Single word. Picks up the
  causality theme directly. The headline reads naturally:
  "Causa shows the cascade your last click triggered."
- **`Cascade`** — works thematically but is already a CSS term and
  blurs in search results.
- **`Domino`** — re-frame's internal model uses "dominoes" for the
  six-phase cascade. Considered, rejected as plural/awkward and as
  it overlaps the framework's existing internal vocabulary.

### Pick

**Causa.** Tagline: *the cascade you can see.*

### Why

- Single word, distinctive, lower-case-friendly.
- Latin root signals "cause" without saying it three times.
- Reads naturally in marketing copy and in the panel's title bar.
- The brand mark (three nodes connected by arrows from one parent)
  illustrates the cascade gesture without spelling the word.
- Search-unique — no existing tool of consequence shares the name.

---

## Lock #2 — AI co-pilot

**Locked 2026-05-11 (Mike).** **Ship v1.0.** Pull-only Q&A + slash
commands. No narration (see lock #10).

### Question

Should Causa ship an AI co-pilot panel at v1.0, or defer to v1.1?

### Options considered

- **Defer to v1.1 (or never).** The AI is a feature, not the pitch;
  Causa earns its keep on observability alone. Lower v1.0 scope.
- **Ship at v1.0 as a full co-pilot.** Q&A + slash commands +
  narration mode (live commentary on the trace stream).
- **Ship at v1.0 in pull-only form.** Q&A + slash commands only; no
  narration.

### Pick

**Ship at v1.0 in pull-only form.** The runtime is structured
enough that an AI can navigate it usefully; not shipping the co-pilot
at v1 leaves the marquee value on the table. The narration-mode
variant is dropped (see lock #10).

### Why

- The five canonical questions (per `Principles.md`) are exactly the
  shape an LLM is good at: walk a graph of structured data, cite
  the chain. Causa without the co-pilot leaves the canonical
  questions partially un-answered for users who'd rather ask than
  click.
- Cost is bounded: the panel is lazy-loaded (<400 KB extra), the
  conversation is ephemeral (no persistence cost), and there are
  no Day8-side services (the user's API key calls the user's
  provider directly).
- Pull-only avoids the "AI debugger that thinks for you" failure
  mode. The model is a navigator pointing at evidence; the user
  verifies (see lock #10 for the narration carve-out).

### Date locked

2026-05-11 (Mike).

### Trail-of-thought citations

- rf2-buor §10.2 ("AI co-pilot — locked: ship at v1.0; open by
  default"). Note: the original lock had "open by default"; lock #8
  below revises that to *collapsed by default*. The shipping-at-v1.0
  half held.

---

## Lock #3 — App-db editing

**Locked 2026-05-11 (Mike).** **Never.** Read-only forever.

### Question

Should the app-db panel offer in-place editing — type a JSON value
into a field, the runtime takes it?

### Options considered

- **In-place edit affordance at v1.0.** Right-click a value, edit it,
  the runtime swallows the change (via `reset-frame-db!`).
- **In-place edit at v1.1.** Defer the affordance but commit to
  shipping it.
- **Never.** App-db is read-only forever; mutations go through
  `dispatch` (right-click → Re-dispatch) or REPL.

### Pick

**Never.** App-db is read-only forever.

### Why

- The runtime is the source of truth. Pokes from the debugger that
  bypass dispatch leave the trace stream missing context — Causa
  panels then can't explain "where did this value come from?"
- Allowing edits without dispatch breaks the causal-graph property
  ("every state change is the consequence of a dispatch"). The
  graph would have to grow a node kind for "user typed a value into
  the panel," and the cascade story would have a discontinuity.
- If the user wants to mutate `app-db`, they have two reasonable
  paths: dispatch an event (via Re-dispatch, the REPL, or a
  one-off `:fx` from a registered event), or do a `reset-frame-db!`
  via the MCP server (which is the explicit "I know what I'm
  doing" path).
- Removing the affordance simplifies the UX: no edit-mode toggle,
  no schema-validation-during-edit, no "did this edit succeed?"
  error surface.

### Date locked

2026-05-11 (Mike).

### Trail-of-thought citations

- rf2-buor §10.4 ("In-place app-db editing — locked: not required").

---

## Lock #4 — Session export

**Locked 2026-05-11 (Mike).** **Never.**

### Question

Should Causa let the user export a session (the trace buffer, the
epoch history, the pinned snapshots) to a file, and import it later?

### Options considered

- **Export + import at v1.0.** A `Save session` button writes a
  JSON / EDN file containing the trace stream and epoch history;
  Causa can open it in a "session viewer" mode.
- **Export-only at v1.0** (no import). The user can hand the file
  to a colleague for context.
- **Never.** If the user reloads, that's a new session; sessions
  are session-scoped.

### Pick

**Never.** Sessions are session-scoped. No export, no import.

### Why

- The serialised state would be enormous and brittle: snapshot
  identities, machine references, sub-graph nodes, source-coord
  chips don't survive JSON round-trip cleanly. The implementation
  cost is real.
- The use case (cross-machine session-sharing for triage) is narrow.
  Where it matters (production-shaped repros), users today take a
  screen recording and a console log — those are sufficient.
- "Open a saved session" requires Causa to render against
  *not-the-live-runtime* — and that fork has its own design surface
  (read-only mode? frozen scrubber? what about cross-frame
  cascades that reference machines no longer registered?). Adding
  it now would balloon scope.
- Aligns with lock #12 (ephemeral conversation) — the privacy bet
  consistently beats the utility bet for Causa's data plane.

### Date locked

2026-05-11 (Mike).

### Trail-of-thought citations

- rf2-buor §10.5 ("Session export / import — locked: not required").

---

## Lock #5 — Mobile

**Locked 2026-05-11 (Mike).** **Desktop only.** No mobile, no
tablet, no phone.

### Question

Does Causa support mobile / tablet / phone viewports?

### Options considered

- **Phone-form-factor Causa.** Pinch-zoom, mobile-first re-layout.
- **Tablet-responsive Causa.** Below 900px, Causa takes full width;
  below 600px, a narrow-mode renders the strip + co-pilot only.
- **Tablet-responsive standalone viewer.** Causa itself stays
  desktop; a separate viewer page serves remote-attached tablets.
- **Desktop only.** Below 600px, Causa refuses to mount with a
  helpful message.

### Pick

**Desktop only.** Below 600px, Causa refuses to mount.

### Why

- Debuggers belong at workstations. The user is reading source,
  copying paths, jumping between editor and browser — none of which
  is good ergonomics on a phone.
- The "tablet for remote inspection" use case is replaced by the
  MCP server (lock #9) — the agent inspects; the human doesn't
  need to render Causa on the tablet.
- Mobile support adds significant UX surface (touch gestures,
  responsive layout, a separate compact density tier) for narrow
  benefit.
- The 600px refusal is user-friendly: instead of cramping the panel,
  Causa says "this is a desktop debugger" and gets out of the way.

### Date locked

2026-05-11 (Mike).

### Trail-of-thought citations

- rf2-buor §10.9 ("Mobile / phone form factor — locked: not needed
  ever").

---

## Lock #6 — MCP timing

**Locked 2026-05-12 (Mike).** **Ship at v1.0.** Via
`tools/10x-mcp/`, mirroring the `tools/pair2-mcp/` pattern.

### Question

When does Causa's MCP server ship — v1.0 or v1.1?

### Options considered

- **Defer to v1.1.** Ship the panel at v1.0; the MCP server later.
  Less scope at v1.0.
- **Ship at v1.0.** Causa-MCP launches alongside the panel.

### Pick

**Ship at v1.0.** Via `tools/10x-mcp/`.

### Why

- `tools/pair2-mcp/` (rf2-5b8e #423, shipped 2026-05-12) is the
  template — fork the project, swap the tool catalogue for Causa's
  surfaces. Most of the implementation cost is already paid.
- Enables remote-attach (one browser debugs another) as a v1.0
  story without paying for a custom WebSocket protocol (lock #9
  rules that out anyway).
- The agent-driven workflow ("Claude, walk me back through the last
  10 epochs that touched `[:cart]`") is high-leverage. Shipping
  Causa-the-panel without Causa-the-agent-surface would mean the
  AI assistant case is only partially served at v1.0.
- The Causa-MCP catalogue is read-mostly (9 read tools, 3 mutate
  tools); the mutate tools mirror the in-panel right-click
  affordances. The user's consent model is the same.

### Date locked

2026-05-12 (Mike). Originally deferred 2026-05-11 (per rf2-buor
§10.7); reversed on 2026-05-12 once pair2-mcp shipped and the
template existed.

### Trail-of-thought citations

- rf2-buor §10.7 (originally deferred).
- rf2-buor notes ("#6 MCP timing LOCKED 2026-05-12 (Mike): MCP AT
  v1.0").

---

## Lock #7 — Hero panel

**Locked 2026-05-12 (Mike).** **Event detail.** Causality graph
demoted to peer + inline mini-graph.

### Question

What's the hero panel — the one users land in on every `Ctrl+Shift+C`?

### Options considered

- **Causality graph as hero.** The original rf2-buor §4.1 pick. The
  graph is visually striking; it's a demo-friendly front door.
- **Event detail as hero.** Land on the most-recent epoch's detail
  panel — event vector, db-diff, fx fired, subs recomputed.
- **A composite "summary" panel.** A custom dashboard view that
  combines the strip, the latest event, the issues count, and a
  mini-graph.

### Pick

**Event detail as hero.** The causality graph is a peer panel —
first-class, sidebar entry, keyboard mnemonic `c`, but not the
front door. The event-detail panel includes an inline mini-graph
(3–5 nodes) when the current epoch has a `:parent-dispatch-id`.

### Why

- The flat detail-and-strip pair answers every one of the five
  canonical questions on its own (per Causa's UX design doc §4).
  The graph view appears in journeys 1 and 2 as a deeper-walk
  option — but in none of them is it the entry point.
- This is the difference between **a graph that's visible because
  the answer is in it** and **a graph that's visible because we
  made it the front door**. The former earns its keep; the latter
  is impressive-but-unused.
- The bet: habit beats demo. Programmers land in the detail panel
  90% of the time; the graph is the second-click answer when the
  first click wasn't enough.
- Low-cost reversal: if usage data later shows programmers spending
  80% of their time in the graph, Causa self-instruments
  panel-view durations and reverts.
- The inline mini-graph keeps the cascade *visible at a glance*
  inside the detail panel — costs 80px of vertical space; gives the
  causal breadcrumb without committing to the full graph view.

### Date locked

2026-05-12 (Mike). Originally rf2-buor §4.1 picked graph-as-hero;
the UX design doc §4 reversed it; Mike locked the reversal
2026-05-12.

### Trail-of-thought citations

- rf2-buor §4.1 + §10.10 (graph-as-hero, "evaluate in practice").
- Causa UX design doc §4 ("The decision: demote the graph; the flat
  event log + causality strip carry the weight").
- rf2-90d5 notes (Mike's 2026-05-12 lock on the demotion).

---

## Lock #8 — AI panel default state

**Locked 2026-05-12 (Mike).** **Collapsed by default.** Subtle
activation cue marks the rail entry.

### Question

When Causa opens, is the AI co-pilot rail open or closed?

### Options considered

- **Open by default.** The marquee-pull argument — Causa's pitch
  hinges on the AI co-pilot being immediately visible.
- **Closed by default.** A subtle cue (the magenta `◇` glyph
  pulsing every 8 seconds in the top strip) marks the rail entry.
- **Open on first use only.** Open for the first session; remember
  the user's close-choice across sessions.

### Pick

**Collapsed by default.** A subtle activation cue (`◇` magenta
glyph in the top strip; pulses every 8 seconds until the user uses
the co-pilot once, then stays static).

### Why

- Causa is a *debugger*, not an AI product. The pull-only co-pilot
  (lock #10) supports the debugging workflow but is not the
  workflow itself. Putting it open by default would imply the
  user came to chat; they didn't, they came to inspect.
- The collapsed state honours the principle of an unobtrusive
  debugger. The user's app and the event detail are the primary
  signal; the co-pilot is a sidecar.
- The activation cue is the discoverability path: the glyph pulses
  gently so a new user notices it, then stops once they've engaged.
- The user can change the default in Settings (Sidebar → Co-pilot
  open by default), but the ship-default is collapsed.

### Date locked

2026-05-12 (Mike). The Causa UX design doc §12.2 originally locked
"open by default" on 2026-05-11; Mike reversed it on 2026-05-12 in
favour of the unobtrusive default.

### Trail-of-thought citations

- Causa UX design doc §12.2 (original "open by default" lock).
- rf2-90d5 notes (the 2026-05-12 reversal).

---

## Lock #9 — Launch modes

**Locked 2026-05-12 (Mike).** **Hybrid.** In-app overlay (`Ctrl+Shift+C`
or launcher pill) + standalone-via-MCP for remote-attach.

### Question

How does Causa launch? In-browser only, or with a remote-attach
mode?

### Options considered

- **(a) In-app only.** DOM injection via `:preloads`; plus a
  same-browser pop-out via `window.opener`. No remote-attach.
- **(b) In-app + custom WebSocket remote-attach.** A standalone
  HTML viewer connects to the running app over a Causa-specific
  WebSocket protocol. Mobile-from-desktop, cross-machine pair-debug.
- **(c) Chrome extension.** The IPC-isolated approach. Out of
  process from the runtime.
- **(d) Hybrid: in-app overlay + MCP for remote-attach.** In-app is
  the primary; the agent-driven case is handled by `tools/10x-mcp/`,
  not by a custom Causa protocol.

### Pick

**(d) Hybrid.** In-app overlay primary; MCP for the agent-driven /
remote case.

### Why

- **No Chrome extension.** The IPC overhead, the sandbox isolation,
  and the manifest-v3 churn aren't worth the marginal "you don't
  have to change build config" benefit.
- **No custom WebSocket remote-attach.** Serialising the runtime
  state across the wire is too costly — snapshot identities,
  machine references, sub-graph nodes, source-coord chips don't
  survive JSON round-trip cleanly. The use cases (mobile-from-desktop,
  cross-machine pair-debug) are too narrow to justify the
  protocol-versioning + security + reconnect-logic surface.
- **MCP handles the remote/agent case.** Causa-MCP (lock #6) is the
  protocol; the agent host owns the network plumbing. The user's
  consent model is already wired.
- **In-app overlay is the right default.** Zero IPC, runtime is one
  closure away, same elision contract as the runtime.
- **`window.opener` pop-out is the second-monitor story.** No
  serialisation cost, no protocol versioning, no reconnect logic;
  same JS realm.

### Date locked

2026-05-12 (Mike).

### Trail-of-thought citations

- rf2-buor §10.3 (original "in-app + same-browser pop-out, no
  Chrome extension, no standalone HTML viewer").
- The MCP-at-v1.0 lock (#6) absorbed the remote-attach concern.

---

## Lock #10 — Narrate mode

**Locked 2026-05-12 (Mike).** **Dropped.** AI is pull-only (Q&A,
slash commands).

### Question

Does the AI co-pilot offer a "narrate mode" — live commentary on
the trace stream, where the model proactively explains what's
happening as dispatches land?

### Options considered

- **Ship narrate mode at v1.0.** Live commentary; the model
  describes each significant trace event in plain English.
- **Ship narrate mode behind a toggle (off by default).** Power
  users can flip it on.
- **Drop narrate mode entirely.** The AI is pull-only — speaks only
  when the user asks.

### Pick

**Drop.** AI is pull-only.

### Why

- **Privacy.** Live commentary means every interesting trace event
  becomes an LLM API call. The user's runtime may contain sensitive
  data — emails, internal handler names, snippets of
  production-shaped repros. Continuous transmission is the wrong
  default.
- **Cost.** Even off-the-shelf-cheap models cost tokens. A typical
  debug session emits hundreds of significant trace events.
  Background narration would burn the user's budget invisibly.
- **UX.** Live commentary is chatter. The user is *debugging*, not
  reading a podcast. The Issues ribbon and the schema-violation
  timeline already surface anomalies passively; the user doesn't
  need a narrator.
- **Failure mode.** A confidently-wrong narration that the user
  doesn't read carefully primes them to believe something untrue.
  Pull-only forces the user to *want* the explanation, which
  forces them to *engage* with the verification step.

The pull-only model retains the co-pilot's value (Q&A on the
canonical five questions, slash commands for power-user shortcuts)
without the narration failure modes.

### Date locked

2026-05-12 (Mike).

### Trail-of-thought citations

- The original rf2-buor §4.3 included "watch / narrate" capabilities
  in the co-pilot description.
- Mike's 2026-05-12 review dropped narrate mode explicitly.

---

## Lock #11 — Source-coord fallback

**Locked 2026-05-12 (Mike).** **Handler-coord fallback** via
`:rf.trace/trigger-handler`. Inline `(?)` annotation when the
dispatch coord is missing.

### Question

When Causa wants to render a click-to-source affordance but the
dispatch's coord is missing (e.g., a synthetic dispatch from a
machine action, an `:fx` dispatch from a registered handler with no
explicit caller coord), what does it render?

### Options considered

- **No fallback.** If the coord is missing, render the chip greyed
  out / unclickable.
- **Best-effort caller-walk.** Causa walks the recent trace stream
  to find the most likely caller (a sibling handler's source).
- **Handler-coord fallback with annotation.** Causa uses the
  registered handler's source coord (always present per Spec 001)
  and renders the chip with a small `(?)` annotation that hovers a
  tooltip explaining the substitution.

### Pick

**Handler-coord fallback** with `(?)` annotation.

### Why

- Every registration carries a source coord (per Spec 001 §
  Source-coordinate capture); the handler's coord is always
  available even when the dispatch's caller is unknown.
- The fallback is *informative*: clicking jumps to the handler that
  ran, which is usually what the user wanted anyway ("show me the
  code that handles this event").
- The `(?)` annotation prevents the user from thinking the coord is
  the *dispatching* code when it's actually the *handling* code.
  Tooltip says: "This coord is the handler's; the dispatch was
  synthesised by `:auth/main` at state `:authing`."
- Greying out the chip is worse — the user can't click through to
  anything, and the surface becomes inert. The fallback at least
  surfaces *some* relevant source.
- Walking the trace stream for a guessed caller is fragile and
  surprises the user when wrong; the handler-coord answer is
  predictable.

### Date locked

2026-05-12 (Mike).

### Trail-of-thought citations

- Surfaced during Causa UX design doc §12.4 ("Source-coord
  click-through fallback").
- Mike's 2026-05-12 lock during the same review pass.

---

## Lock #12 — Conversation persistence

**Locked 2026-05-12 (Mike).** **Ephemeral.** No localStorage, no
save.

### Question

Is the AI co-pilot's conversation history persisted across reloads
/ sessions?

### Options considered

- **Persist to localStorage** (default on). Conversations survive
  reloads; cap at 1000 turns; oldest-evict.
- **Persist with explicit opt-in.** Default off; the user can flip
  a toggle.
- **Persist with explicit opt-out.** Default on; the user can
  disable for a session.
- **Ephemeral always.** Conversations live only in-memory; cleared
  on reload, cleared on Causa close.

### Pick

**Ephemeral always.** No localStorage, no save.

### Why

- **Privacy.** Conversations may contain sensitive app data — user
  emails, internal handler names, production-shaped repros. The
  user's expectation is that what they discuss with the debugger
  stays with the debugger session.
- **Consistency with lock #4 (no session export).** Causa's data
  plane is consistently session-scoped; the AI conversation
  follows the same rule.
- **Simplicity.** No corruption-recovery surface, no version
  migration for conversation shape, no quota management.
- **The Settings → Telemetry section's claim** ("Causa stores
  nothing it doesn't need to") is consistent with ephemeral
  conversation.

A future v1.1+ could add opt-in persistence if users demand it;
ephemeral is the conservative ship-default.

### Date locked

2026-05-12 (Mike).

### Trail-of-thought citations

- Causa UX design doc §12.5 ("Conversation persistence default —
  per-session or persistent-with-opt-out?").
- Mike's 2026-05-12 lock on ephemeral.

---

## Lock #13 — Voice STT

**Locked 2026-05-12 (Mike).** **No at v1.0.** Text-only. No mic
permission prompts.

### Question

Does the AI co-pilot accept voice input (browser STT via the Web
Speech API) at v1.0?

### Options considered

- **Ship voice at v1.0.** Click `🎙`, browser-local STT
  transcribes; the user reviews and submits.
- **Ship voice behind a Settings toggle (off by default).**
- **Defer to v1.1+.** Text only at v1.0.

### Pick

**No at v1.0.** Text only.

### Why

- **Web Speech API quality is patchy.** Different browsers, different
  recognition quality, different language support. A "sometimes
  works" feature damages user trust.
- **Cloud STT adds cost + latency** and a service-side dependency
  Causa doesn't currently have.
- **v1.0 scope is already large.** 16 panels + co-pilot + MCP
  server. Voice is purely additive — shipping later costs nothing.
- **Privacy default.** No mic permission prompts in v1.0
  onboarding (privacy-default-friendly).
- **Niche use case.** Power users keyboard-fluent will type faster
  than they speak. The "hands full / hands-free" case is real but
  small.

Voice can ship in v1.1+ if users demand it. The text input shape
is unchanged by adding voice later; pure additive feature.

### Date locked

2026-05-12 (Mike).

### Trail-of-thought citations

- Causa UX design doc §8 ("Voice (browser STT) — opt-in").
- Causa UX design doc §12.6 ("Voice STT at v1 — ship at v1 or
  defer?").
- rf2-90d5 notes ("#13 Voice STT LOCKED 2026-05-12 (Mike): NO
  VOICE AT v1.0").

---

## Summary table

| # | Question | Pick | Date |
|---|---|---|---|
| 1 | Name | **Causa** (`day8/re-frame2-causa`) | 2026-05-11 |
| 2 | AI co-pilot ship timing | **v1.0 pull-only** | 2026-05-11 |
| 3 | App-db editing | **Never — read-only forever** | 2026-05-11 |
| 4 | Session export | **Never** | 2026-05-11 |
| 5 | Mobile | **Desktop only** | 2026-05-11 |
| 6 | MCP timing | **v1.0 via `tools/10x-mcp/`** | 2026-05-12 |
| 7 | Hero panel | **Event-detail** (graph demoted to peer) | 2026-05-12 |
| 8 | AI panel default state | **Collapsed** with subtle cue | 2026-05-12 |
| 9 | Launch modes | **Hybrid: in-app + MCP** | 2026-05-12 |
| 10 | Narrate mode | **Dropped — AI is pull-only** | 2026-05-12 |
| 11 | Source-coord fallback | **Handler-coord with `(?)` annotation** | 2026-05-12 |
| 12 | Conversation persistence | **Ephemeral** | 2026-05-12 |
| 13 | Voice STT | **No at v1.0** | 2026-05-12 |

The 13 locks together define the v1.0 surface. Anything outside
these decisions is up for design discussion; anything inside is
direction-set and shipped.
