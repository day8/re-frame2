# DESIGN-RATIONALE

The 17 direction-setting decisions that shape Causa. Each entry
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

## Lock #6 — MCP timing (SUPERSEDED 2026-05-19)

**Superseded 2026-05-19 (Mike, rf2-hvl1g).** **Causa-MCP is dropped
entirely.** AI agent access to Causa state already flows via
`tools/re-frame2-pair-mcp/` (which can read the framework-published
Causa runtime API on `day8.re-frame2-causa.runtime`) + Causa's raw
trace bus + Story-MCP. No dedicated causa-mcp jar is built; there is
no `tools/causa-mcp/` artefact.

### Original lock (2026-05-12, Mike, retained for history)

Picked **Ship at v1.0** via `tools/causa-mcp/`, mirroring
`tools/re-frame2-pair-mcp/`. Reasoning: fork the re-frame2-pair-mcp
template, swap the tool catalogue for Causa surfaces, enables
remote-attach via MCP without a custom WebSocket protocol.

### Why reversed

- Causa's runtime API (rf2-crhr8) exposes the same accessors
  (`get-app-db`, `get-epoch-history`, `get-machine-state`, ...) that
  a hypothetical causa-mcp would have wrapped. Any MCP server can
  call those accessors via `eval-cljs` — re-frame2-pair-mcp does so
  today.
- Maintaining a second Node-side MCP jar duplicates the cap
  pipeline, the wire-vocab plumbing, the discover-app handshake, and
  the streaming-subscribe budget for ~zero incremental value over
  re-frame2-pair-mcp + eval-cljs against the runtime API.
- The "two doors" framing in `000-Vision.md` collapses to one:
  Causa is the human surface; re-frame2-pair-mcp is the AI surface
  (reading the same trace bus + epoch history Causa reads).

### Date superseded

2026-05-19 (Mike, rf2-hvl1g).

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
  the primary; the agent-driven case is handled by
  `tools/re-frame2-pair-mcp/` against the framework-published Causa
  runtime API, not by a custom Causa protocol. (Originally pictured
  via a dedicated causa-mcp jar — dropped per rf2-hvl1g, see
  Lock #6 supersedence.)

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

## Lock #14 — Two modes (Runtime + Static)

**Locked 2026-05-19 (Mike).** **Two modes within one tool: Runtime
(event-coupled spine + 4-layer chrome) and Static (event-INDEPENDENT
registry browse + 3-layer chrome).** Chrome silhouette is the
mode signal; the mode pill is the toggle.

### Question

Causa needs to answer two distinct bug-classes — the event-coupled
"what just happened?" cascade (the five canonical questions answered
by the spine / event list / per-tab projections) AND the event-
INDEPENDENT "what's registered? · what would simulate?" browse
(machines / routes / schemas / views / events catalogues). Should
this be a single chrome with a tab pivot, two chromes within one
tool, or two tools entirely?

### Options considered

- **(a) Single chrome with tab pivots.** One 4-layer chrome; add
  "Registry" tabs alongside the existing 7 (Event / App-db / Views /
  Trace / Machines / Routing / Issues). The event-coupled spine
  always sits at L2 even when the user is browsing the schema
  registry — every Registry tab carries L2's "current cascade"
  context whether or not it's relevant.
- **(b) Two modes within one tool — Causa is one package, two
  chromes.** A mode pill at ribbon-left flips the surface; Runtime
  renders the 4-layer chrome (L1 ribbon · L2 event list · L3 tab
  bar · L4 detail), Static drops L2 (event-independent, no spine)
  and renders 3 layers (L1 · L3 · L4). The mode user-state persists
  to localStorage; a global chord toggles. Both chromes share the
  full Causa design language — typography, palette, density,
  spacing grid, glyph vocabulary.
- **(c) Two tools entirely.** Ship `re-frame2-causa-runtime` +
  `re-frame2-causa-static` as separate packages, each with its own
  preload, its own jar, its own install path, its own keybinding.

### Pick

**(b) Two modes within one tool.** Causa is one package; the chrome
silhouette differentiates the modes at a glance; the mode pill is
the toggle, not the indicator.

### Why

- **Event-coupled and event-INDEPENDENT are different bug-classes.**
  The Runtime spine answers the five canonical questions about a
  specific cascade ("who fired this? · what did it write? · which
  views recomputed? · what side-effects fired? · what went wrong?").
  The Static browse answers a different family ("which machines does
  this app define? · what would `/orders/42?tab=ship` route to? ·
  which schemas govern this app-db slice? · which views are
  registered?"). Carrying L2's "current cascade" into a registry
  browse is noise — Static IS event-independent. Option (a) leaks
  spine context into a surface where it has no meaning.
- **The chrome shape IS the mode signal.** Static drops L2 entirely;
  the 3-layer silhouette is unmistakable from the 4-layer Runtime
  silhouette. A trained eye reads the mode without looking at any
  widget. Option (a) gives the user no chrome-level signal that the
  surface has changed; the only signal is which tab is active, and
  the user mis-reads the spine row as "the current cascade" when
  they're actually browsing a registry that has nothing to do with
  it.
- **Four stacked signals telegraph mode at a glance** (per the
  shipped surface):
  1. **Mode pill** at ribbon-left — two-segment radio (`[● Runtime]
     [○ Static]`), 200ms accent-violet cross-fade. Lives in BOTH
     modes — it's the toggle, not the indicator.
  2. **2-px left-edge ribbon stripe** — `:accent-violet` in Runtime,
     `:cyan` (existing palette token) in Static. Zero new tokens.
  3. **Motion dampening** — Runtime ships the LIVE pulse + machine-
     active pulse + 180ms tab fade. Static drops continuous pulses
     entirely and collapses tab fades to instant. Honours
     `prefers-reduced-motion: reduce` via the existing
     `--rf-causa-motion-scale` seam.
  4. **Chrome silhouette** — 4-layer (Runtime) vs 3-layer (Static).
     The silhouette IS the mode.
- **The mode pill is the toggle, not the indicator.** It lives at
  ribbon-left in BOTH modes; clicking either segment dispatches the
  same `:rf.causa/toggle-mode` event the `Cmd-Shift-M` chord (per
  `keybinding.cljs` + 018 §11 Keyboard map) fires. The user reads
  the mode from the chrome shape + stripe colour + motion
  dampening; the pill is where they go to change it.
- **One package, one install, one preload.** Option (c) doubles the
  install surface (two preloads, two keybindings, two persisted-
  state schemas) for zero benefit — the user wants both modes
  available from one Ctrl+Shift+C. Option (b) gets the full
  user-facing isolation (3 vs 4 layers; separate selected-tab slot
  per mode) without the duplicated build / install surface.
- **Cross-mode tab choice is preserved.** The Static-scoped tab
  lives at `:rf.causa.static/selected-tab` (default `:machines`);
  the Runtime-scoped tab lives at the existing `:rf.causa/active-
  tab` (default `:event`). Flipping modes restores the prior
  Runtime tab choice; it doesn't clobber it.

### Cross-reference

The architectural spine for this lock lives in
[`018-Event-Spine.md`](018-Event-Spine.md) §Static surface
(the 3-layer silhouette + the 4 mode signals + the mode-state
lifecycle slots + the localStorage `causa.mode` key + the
`:experimental/static-mode?` feature flag). The concrete Static
sub-tab surfaces are catalogued in [`007-UX-IA.md`](007-UX-IA.md)
§Static mode and (for Machines specifically)
[`003-Machine-Inspector.md`](003-Machine-Inspector.md) §Static
Machines surface — the 4-mode sub-strip
`[Topology][Sim][Instances][Cascade]` with JUMP-to-Runtime
semantics on Instances and Cascade-dimmed-with-tooltip on
Cascade.

### Date locked

2026-05-19 (Mike).

### Trail-of-thought citations

- `ai/findings/2026-05-19-causa-explorer-mode.md` — the session
  trail that walked through (a) / (b) / (c) and surfaced the
  chrome-silhouette-as-mode-signal mechanism.
- `ai/findings/2026-05-19-causa-spec-currency-audit.md` §H7 —
  flagged the missing lock during the spec-currency sweep.
- rf2-o5f5f epic — the implementation umbrella under which the
  Static surface shipped (sub-beads rf2-o5f5f.1 chrome,
  rf2-o5f5f.2 Machines, .3 Routes, .4 Schemas, .5 Views, .6
  Events).

---

## Lock #15 — Two verbs, two homes

**Locked 2026-05-19 (Mike).** **Browse-all in Static; focused-event
in Runtime.** When a cohesive sub-domain (Routes, Machines, Views,
Events, Schemas) has both a browse-all surface and a focused-event
lens, they split across modes — the browse-all lives in Static; the
focused-event lens lives in Runtime. They never cohabit one tab.

### Question

When a cohesive sub-domain (Routes, Machines, Views, Events,
Schemas) has both a browse-all surface (the catalogue of registered
items) and a focused-event lens (this cascade's slice of that
sub-domain), do they cohabit one tab or split across modes?

### Options considered

- **(a) One tab carries both.** The Routes tab (or Machines, or
  Schemas, …) toggles internally between "browse-all" and
  "focused-event" via a sub-control. Mode pill becomes one axis;
  the tab-internal toggle becomes a second axis the user has to
  track.
- **(b) Browse-all in Static; focused-event in Runtime.** The
  browse-all surface is event-INDEPENDENT — it answers "what's
  registered? what would simulate?"; the focused-event lens is
  event-COUPLED — it answers "what did this cascade do to this
  sub-domain?". Each lives where its causal stance fits the chrome
  silhouette (Static = 3-layer event-independent; Runtime = 4-layer
  event-coupled).
- **(c) Only browse-all.** Drop the focused-event lens — let users
  reconstruct the per-cascade slice from the Trace tab.
- **(d) Only focused-event.** Drop the browse-all — let users find
  the registry via REPL or source.

### Pick

**(b) Browse-all in Static; focused-event in Runtime.** The mode
pill is the axis; nothing nested below it.

### Why

- **Browse-all is event-INDEPENDENT; focused-event is
  event-COUPLED.** The two verbs live in different causal stances —
  the chrome silhouette already signals which stance the user is
  in (3-layer Static drops L2 entirely; 4-layer Runtime carries the
  spine). Putting both verbs under one tab forces the user to read
  *two* mode signals: the chrome shape AND the tab-internal toggle.
  Splitting collapses to one signal.
- **A tab-internal toggle creates a third axis.** Causa already
  carries (i) the mode pill (Runtime vs Static) and (ii) the tab
  choice within a mode. A third axis (browse-all vs focused-event
  *within* a tab) is the kind of UX silt that grows over time —
  every cohesive sub-domain would add its own variant, and the user
  has to learn N sub-conventions. Lock #14's "chrome silhouette IS
  the signal" requires one axis below the pill.
- **The pattern generalises.** Routes locked it first (016 Routes
  split); Machines arrived at the same shape de-facto (003 Machine
  Inspector — Static catalogue vs Runtime focused-machine);
  Schemas / Views / Events sub-tabs (rf2-o5f5f.4 / .5 / .6) will
  re-ask the question if the lock is missing. Naming the pattern
  here gives future contributors a single artefact to point at.
- **Static surfaces are the catalogue; Runtime surfaces are the
  lens.** Reading a Static tab is "show me the system as
  registered"; reading a Runtime tab is "show me this cascade's
  slice." The verb-per-mode split mirrors the underlying causal
  shape; the chrome shape already telegraphs it.

### Cross-reference

- Builds on **Lock #14** (Two modes; H7 in the spec-currency
  audit) — this lock is the convention that Lock #14's mode split
  generates whenever a cohesive sub-domain spans both stances.
- First consumer: **016 Routes** (rf2-o5f5f.3) — browse-all
  catalogue in Static, focused-event lens in Runtime. The lock
  cites this surface as the normative reference shape.
- Future consumers: **003 Machine Inspector** (Static catalogue +
  Runtime focused-machine), **Schemas** (rf2-o5f5f.4), **Views**
  (rf2-o5f5f.5), **Events** (rf2-o5f5f.6).

### Date locked

2026-05-19 (Mike).

### Trail-of-thought citations

- `ai/findings/2026-05-19-causa-spec-currency-audit.md` §L3 —
  flagged the missing lock; rf2-o5f5f.3 worker note surfaced the
  pattern as generalisable.
- rf2-vtd5z audit (Causa-JS-devtools lessons) — the source audit
  that drove this session's polish locks.

---

## Lock #16 — machines-viz as its own tool jar

**Locked 2026-05-19 (Mike).** **`tools/machines-viz/` is the
canonical home of the MachineChart primitive; Causa depends on it
and re-exports the public chart API via thin shims.** Multiple
consumers (Causa panel chrome, Story per-variant ribbons, the
read-only viewer page, the user-app drop-in) share one chart
implementation behind a stable jar.

### Question

Where does the MachineChart primitive (the SVG / ELK rendering of a
machine's states + transitions + the active-state highlight) live?

### Options considered

- **(a) Causa-internal.** (Earlier direction, pre-2026-05-19.)
  The chart collapses into Causa with `tools/machines-viz/` as a
  stub. Every consumer pays for Causa's panel chrome to embed the
  chart.
- **(b) `tools/machines-viz/` as its own tool jar.** The canonical
  chart lives at `tools/machines-viz/src/`; Causa depends on it and
  re-exports the public chart API via thin shims for
  embedding-consumer convenience. Story per-variant panels, the
  read-only viewer page, and the user-app drop-in can depend on
  `machines-viz` alone without pulling Causa.
- **(c) Framework-internal** (e.g.
  `implementation/machines/svg.cljc`). The chart ships as part of
  the machines artefact; tools consume it like any other framework
  surface.

### Pick

**(b) `machines-viz` as its own tool jar.** Causa depends on it and
re-exports the public chart API via thin shims.

### Why

- **Multiple consumers need the chart.** Causa's Machines tab
  embeds it inside panel chrome; Story's per-variant ribbons embed
  it inline beside snapshot diffs; the read-only viewer page
  (linkable URL → live machine chart) embeds it standalone; the
  user-app drop-in (an opt-in component for production apps that
  want to surface machine state to non-devtool users) embeds it
  inside the user's own chrome. Option (a) forces every consumer
  to pay for Causa's panel chrome surface; option (b) lets each
  consumer compose the chart into its own chrome.
- **Framework / tool boundary stays clean.** The chart is a
  *visualisation* — a tool concern (rendering a machine for human
  inspection). The framework owns the data plane
  (`reg-machine`, `get-machine-state`, the FSM step semantics).
  Option (c) muddies that boundary by putting a viz primitive
  inside the framework artefact, which then has to grow a
  React/Reagent dependency it doesn't otherwise need. The
  `tools/` tree is exactly where tool-shaped artefacts belong.
- **Per-jar release cadence + bundle-isolation tests.** A
  dedicated tool jar gets the standard tool-jar discipline:
  per-jar version, per-jar deps, per-jar tests, and
  `npm run test:bundle-isolation` guarantees the chart never
  leaks into a production bundle that didn't ask for it. Option
  (a) couples the chart's release to Causa's; option (c) couples
  it to the framework's. Both inflict cadence drag on consumers
  who only want the chart.
- **Causa re-exports the public API via thin shims.** Embedding
  consumers that already depend on Causa (Story per-variant
  ribbons live in the Story jar but the per-variant snapshot diff
  panel inside Causa's Static-Machines tab uses the chart) can
  reach the chart API through Causa's namespace without an extra
  dep declaration. The shim is documented as "this re-exports
  `tools/machines-viz/`; depend on machines-viz directly if you
  don't otherwise depend on Causa."

### Cross-reference

- **H1 (003 Architectural posture update)** — the inversion was
  recorded in spec 003 §Architectural posture during the
  spec-currency audit follow-on.
- **H6 (000 dependency arrow update)** — the dependency-graph
  artefact in 000 was redrawn so Causa → machines-viz (not
  machines-viz → Causa).
- **M8 (008 embed status update)** — 008 Embedding catalogues
  the consumer surfaces; the lock cites the four-consumer set as
  the reason for the inversion.
- **PR #1570 / rf2-o9arp** — the implementation that inverted the
  earlier direction. This lock records the inversion as a
  Mike-locked decision so a future contributor reading 003 + 000
  doesn't see contradicting claims.

### Date locked

2026-05-19 (Mike).

### Trail-of-thought citations

- `ai/findings/2026-05-19-causa-spec-currency-audit.md` §L4 —
  flagged the missing lock and the contradicting spec language.
- rf2-vtd5z audit (Causa-JS-devtools lessons) — the source audit
  that drove this session's polish locks.

---

## Lock #17 — Visual language locks (REJECT patterns)

**Locked 2026-05-19 (Mike) per rf2-vtd5z audit.** **Three REJECTs
as one lock**: (1) no commodity fonts; (2) no
addon-per-concern growth; (3) no pixels-as-first-class. Magic
numbers are reviewable as bugs.

### Question

What does Causa's visual identity actively reject? The
spec-currency audit + Causa-JS-devtools comparison surfaced
three patterns peer tools (TanStack Query Devtools, Vue DevTools,
Vite DevTools) drifted into; the lock names the rejection.

### Options considered

This lock is direction-setting via *what it refuses*, not
between alternatives. The three REJECTs are:

- **(1) No commodity fonts.** Fraunces (display face) for the L1
  ribbon + mode pill + chord callouts; Inter for UI sans;
  JetBrains Mono for data. NOT `system-ui` / `-apple-system` /
  the OS default sans as the chrome face. Peer tools that
  defaulted to `system-ui` lost type identity entirely —
  Fraunces is the typographic signal that "this is Causa, not a
  panel inside Chrome DevTools."
- **(2) No addon-per-concern growth.** One density knob — one
  CSS variable — `--rf-causa-font-size`. Not three different
  size axes per addon. The Cmd-K palette is the cross-tab nav
  primitive; NOT per-tab burger menus / per-tab settings sheets
  / per-tab visibility toggles. Peer tools that grew
  addon-per-concern accumulated UX silt — every new addon added
  its own size knob, its own visibility toggle, its own
  burger; the chrome becomes mostly chrome.
- **(3) No pixels-as-first-class.** Every type / spacing /
  motion value is a token — type-scale resolved via a calc
  anchor from `--rf-causa-font-size`; spacing on the 4-px grid;
  motion via the three duration tiers in Principles.md. Magic
  numbers in CSS or inline styles are reviewable as bugs. Peer
  tools that hard-coded pixels couldn't roll a density tier
  without combing every component; tokens make the surface
  re-themable end-to-end.

### Pick

**All three REJECTs, as one lock.** The lock is direction-setting:
PRs that introduce a commodity font, an addon-per-concern, or a
hard-coded pixel value are reviewable bugs.

### Why

- **Causa's UX is dev-tool-class.** The audit found peer tools
  that drifted to commodity fonts + addon-soup + magic-number
  sprawl as they grew. The drift is gradual and individually
  defensible at each step ("just one more size knob"; "Inter is
  fine"; "this one inline `padding: 7px`"). The lock prevents
  the gradient.
- **Fraunces as the display face is the single strongest
  identity signal.** Peer tools that used `system-ui` were
  indistinguishable from one another and from the host browser
  chrome. Fraunces says "Causa" before the first character is
  read. Inter for UI sans + JetBrains Mono for data are the
  standard pairings — they're not exotic; they're the
  considered choices, not the defaults.
- **One density knob is the right number.** A debugger user
  wants to scale the *whole* surface up or down (small monitor,
  large monitor, projector demo, sharing-screen-in-a-meeting);
  they don't want to scale "the trace timestamps" independently
  of "the event vector" independently of "the tab labels."
  `--rf-causa-font-size` is the single source; everything else
  resolves via calc anchor. Lock #17 says addon authors can't
  introduce per-addon size axes; they must consume the global
  knob.
- **Cmd-K is the nav primitive.** A single palette replaces
  per-tab burger menus / per-tab "more" overflows / per-tab
  settings sheets. Users learn ONE chord, get ALL the surface
  area; addon authors register actions into the palette
  registry, not into per-tab chrome.
- **Tokens make Causa re-themable end-to-end.** The three
  duration tiers in Principles.md (instant / quick / measured)
  + the 4-px spacing grid + the calc-anchored type scale + the
  named palette tokens are the entire surface area. Anything
  outside this set is a bug; anything inside is a knob.

### Cross-reference

- **rf2-vtd5z** — the audit that produced the REJECT patterns
  (Causa-JS-devtools lessons).
- **rf2-5kfxe** — design polish session that consumed all three
  REJECTs as live constraints.
- **rf2-n8i2c** — `--rf-causa-font-size` calc-anchor
  implementation; the single density knob.
- **rf2-ybjkx** — Cmd-K palette as the single nav primitive
  (rather than addon-per-tab burger menus).
- **Principles.md** §Motion — the three duration tiers
  (instant / quick / measured) that motion tokens resolve to.

### Date locked

2026-05-19 (Mike).

### Trail-of-thought citations

- `ai/findings/2026-05-19-causa-js-devtools-lessons.md` — the
  audit doc that catalogued peer-tool drift and surfaced the
  three REJECTs.
- `ai/findings/2026-05-19-causa-spec-currency-audit.md` §L5 —
  flagged the missing lock during the spec-currency sweep.

---

## Summary table

| # | Question | Pick | Date |
|---|---|---|---|
| 1 | Name | **Causa** (`day8/re-frame2-causa`) | 2026-05-11 |
| 2 | AI co-pilot ship timing | **REVERSED 2026-05-17: dropped entirely (rf2-s3vx5)** | 2026-05-17 |
| 3 | App-db editing | **Never — read-only forever** | 2026-05-11 |
| 4 | Session export | **Never** | 2026-05-11 |
| 5 | Mobile | **Desktop only** | 2026-05-11 |
| 6 | MCP timing | **SUPERSEDED 2026-05-19: causa-mcp dropped (rf2-hvl1g) — agent access via re-frame2-pair-mcp + runtime API** | 2026-05-19 |
| 7 | Hero panel | **Event-detail** (graph demoted to peer) | 2026-05-12 |
| 8 | AI panel default state | **SUPERSEDED by #2 reversal (rf2-s3vx5)** | 2026-05-17 |
| 9 | Launch modes | **Hybrid: in-app + MCP** | 2026-05-12 |
| 10 | Narrate mode | **SUPERSEDED by #2 reversal (rf2-s3vx5)** | 2026-05-17 |
| 11 | Source-coord fallback | **Handler-coord with `(?)` annotation** | 2026-05-12 |
| 12 | Conversation persistence | **SUPERSEDED by #2 reversal (rf2-s3vx5)** | 2026-05-17 |
| 13 | Voice STT | **SUPERSEDED by #2 reversal (rf2-s3vx5)** | 2026-05-17 |
| 14 | Two modes (Runtime + Static) | **Two modes within one tool — chrome silhouette IS the signal; pill is the toggle** | 2026-05-19 |
| 15 | Two verbs, two homes | **Browse-all in Static; focused-event in Runtime** | 2026-05-19 |
| 16 | machines-viz home | **`tools/machines-viz/` as own tool jar — Causa depends + re-exports** | 2026-05-19 |
| 17 | Visual language REJECTs | **No commodity fonts · no addon-per-concern · no pixels-as-first-class** | 2026-05-19 |

The 17 locks together define the v1.0 surface. Anything outside
these decisions is up for design discussion; anything inside is
direction-set and shipped.
