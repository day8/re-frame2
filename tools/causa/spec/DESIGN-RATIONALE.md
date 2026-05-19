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

## Lock #2 — AI co-pilot (REVERSED 2026-05-17)

**Reversed 2026-05-17 (Mike): drop entirely.** Earlier locks shipped a
pull-only Q&A + slash-command rail at v1.0; that surface has been
removed. AI integration lives in `tools/re-frame2-pair-mcp/` instead; Causa is
the human surface only. Removal recorded under bead rf2-s3vx5.

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
  below 600px, a narrow-mode renders the strip only.
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
`tools/causa-mcp/`, mirroring the `tools/re-frame2-pair-mcp/` pattern.

### Question

When does Causa's MCP server ship — v1.0 or v1.1?

### Options considered

- **Defer to v1.1.** Ship the panel at v1.0; the MCP server later.
  Less scope at v1.0.
- **Ship at v1.0.** Causa-MCP launches alongside the panel.

### Pick

**Ship at v1.0.** Via `tools/causa-mcp/`.

### Why

- `tools/re-frame2-pair-mcp/` (rf2-5b8e #423, shipped 2026-05-12) is the
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
§10.7); reversed on 2026-05-12 once re-frame2-pair-mcp shipped and the
template existed.

### Trail-of-thought citations

- rf2-buor §10.7 (originally deferred).
- rf2-buor notes ("#6 MCP timing LOCKED 2026-05-12 (Mike): MCP AT
  v1.0").

---

## Lock #7 — Hero panel

**Locked 2026-05-12 (Mike).** **Event detail.** Causality graph
demoted to peer + inline mini-graph.

**Updated 2026-05-19 (Mike, rf2-y0z5b).** Causality surface dropped
entirely — the popover, the ELK+SVG graph primitive, and the `c` key
binding are all gone. Event detail remains hero; cascade lineage is
inspected via the Event tab + Trace tab tags, not a dedicated graph.

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

## Lock #8 — AI panel default state (SUPERSEDED 2026-05-17)

**Superseded 2026-05-17 (Mike): the AI co-pilot rail has been
removed entirely (see Lock #2 reversal).** AI integration lives in
`tools/re-frame2-pair-mcp/`. Removal recorded under bead rf2-s3vx5.

---

## Lock #9 — Launch modes

**Locked 2026-05-12 (Mike); refined 2026-05-16.** **Hybrid.** In-app
same-runtime UI with a true inline host by default, optional same-runtime
pop-out/overlay affordances, plus standalone-via-MCP for remote-attach.

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
  the primary; the agent-driven case is handled by `tools/causa-mcp/`,
  not by a custom Causa protocol.

### Pick

**(d) Hybrid.** In-app same-runtime UI primary; MCP for the agent-driven /
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
- **In-app same-runtime UI is the right default.** The current product
  decision makes this a true inline layout host rather than an overlay:
  zero IPC, runtime is one closure away, same elision contract as the
  runtime, and host controls remain visible/clickable.
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

## Lock #10 — Narrate mode (SUPERSEDED 2026-05-17)

**Superseded 2026-05-17 (Mike): the AI co-pilot rail has been
removed entirely (see Lock #2 reversal).** AI integration lives in
`tools/re-frame2-pair-mcp/`. Removal recorded under bead rf2-s3vx5.

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

## Lock #12 — Conversation persistence (SUPERSEDED 2026-05-17)

**Superseded 2026-05-17 (Mike): the AI co-pilot rail has been
removed entirely (see Lock #2 reversal).** AI integration lives in
`tools/re-frame2-pair-mcp/`. Removal recorded under bead rf2-s3vx5.

---

## Lock #13 — Voice STT (SUPERSEDED 2026-05-17)

**Superseded 2026-05-17 (Mike): the AI co-pilot rail has been
removed entirely (see Lock #2 reversal).** AI integration lives in
`tools/re-frame2-pair-mcp/`. Removal recorded under bead rf2-s3vx5.

---

## Summary table

| # | Question | Pick | Date |
|---|---|---|---|
| 1 | Name | **Causa** (`day8/re-frame2-causa`) | 2026-05-11 |
| 2 | AI co-pilot ship timing | **REVERSED 2026-05-17: dropped entirely (rf2-s3vx5)** | 2026-05-17 |
| 3 | App-db editing | **Never — read-only forever** | 2026-05-11 |
| 4 | Session export | **Never** | 2026-05-11 |
| 5 | Mobile | **Desktop only** | 2026-05-11 |
| 6 | MCP timing | **v1.0 via `tools/causa-mcp/`** | 2026-05-12 |
| 7 | Hero panel | **Event-detail** (graph demoted to peer) | 2026-05-12 |
| 8 | AI panel default state | **SUPERSEDED by #2 reversal (rf2-s3vx5)** | 2026-05-17 |
| 9 | Launch modes | **Hybrid: in-app + MCP** | 2026-05-12 |
| 10 | Narrate mode | **SUPERSEDED by #2 reversal (rf2-s3vx5)** | 2026-05-17 |
| 11 | Source-coord fallback | **Handler-coord with `(?)` annotation** | 2026-05-12 |
| 12 | Conversation persistence | **SUPERSEDED by #2 reversal (rf2-s3vx5)** | 2026-05-17 |
| 13 | Voice STT | **SUPERSEDED by #2 reversal (rf2-s3vx5)** | 2026-05-17 |

The 13 locks together define the v1.0 surface. Anything outside
these decisions is up for design discussion; anything inside is
direction-set and shipped.
