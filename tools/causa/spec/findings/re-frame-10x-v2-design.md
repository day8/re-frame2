# Causa — Design From a Blank Slate

> Bead **rf2-buor (P2 research).** Working draft for Mike to iterate on. **Not normative; not a port.** The exercise: imagine re-frame-10x's successor with re-frame2's instrumentation as the substrate and 2026-class devtools expectations as the bar. Be bold; flag what's speculative. (Historical working title: "re-frame-10x v2"; the rename to Causa was locked 2026-05-11.)
>
> Companion docs: [`re-frame-2-story-feature-set.md`](re-frame-2-story-feature-set.md) (rf2-m6tu) and [`re-frame-2-story-sota-refinement.md`](rf2-94b0). Substrate this consumes: [Spec 009 Instrumentation](../spec/009-Instrumentation.md), [Spec Tool-Pair](../spec/Tool-Pair.md), [Spec 002 Frames](../spec/002-Frames.md), [Spec 005 Machines](../spec/005-StateMachines.md), [Spec 013 Flows](../spec/013-Flows.md), [Spec 011 SSR](../spec/011-SSR.md), [Spec 010 Schemas](../spec/010-Schemas.md), [Spec 012 Routing](../spec/012-Routing.md), [Spec Dynamic-Architecture](../spec/Dynamic-Architecture.md).

## TL;DR — one paragraph

**Causa** (the locked name; was working title for re-frame-10x v2) is a re-frame2-native debugger that treats every drain cycle as a **causal narrative** — an event, the cascade it triggered, the state delta it produced, the subs and views that recomputed, the effects that fired, the machines that transitioned, the schema checks that ran. The marquee surface is a **causality graph** keyed by `:dispatch-id` / `:parent-dispatch-id`, scrubbable across epochs, navigable across frames, with click-to-source on every node and a built-in AI co-pilot that can answer "why did this fire?" in plain English. v1's 10x organised around the epoch panel; v2 organises around the *story* a cascade tells — and reuses re-frame2's pre-folded `:rf/epoch-record` projections (`:sub-runs` / `:renders` / `:effects`) so the framework does the work and the tool renders it. Machine state-charts, flow heatmaps, schema timelines, hydration diff, performance ribbon, and the AI co-pilot are all panels over the same trace bus; one substrate, many views.

---

## §1 Problem statement

### What Causa is for

**A debugger that explains the runtime to the human in the runtime's own terms.** When a re-frame app misbehaves, the debugger should let the programmer ask — and answer — five canonical questions:

1. **Why did this event fire?** (causal-ancestor walk)
2. **What did that event change?** (`app-db` diff + flow recomputes + machine transitions)
3. **Why is this subscription returning the wrong value?** (input-chain trace + cache state)
4. **Why is this view re-rendering?** (subscription invalidation chain + render-key attribution)
5. **What's currently broken?** (errors, warnings, schema violations, hydration mismatches — surfaced as one feed)

v1 of 10x answered #1–#4 partially and #5 not really. The bar in 2026 is **all five, answerable in seconds, with click-to-source for every artefact**.

### Who uses it

- **The re-frame programmer mid-feature.** Building, breaking, fixing. Wants the trace ribbon in their face but unobtrusive; wants click-to-source from anywhere; wants to scrub.
- **The re-frame programmer reading an unfamiliar codebase.** "Why does clicking *Save* eventually re-render this card three modules over?" Wants a causal graph.
- **The on-call programmer triaging a production-shaped repro.** Wants to load a session, scrub, find the divergence.
- **The AI agent driving the runtime via re-frame-pair / Story / MCP surfaces.** Doesn't open the UI itself but consumes the same data through the same primitives — Causa and the agent surfaces are siblings, not parent/child.

### What jobs it does that nothing else does well

- **Causal cascades across frames.** Redux DevTools is single-store; XState Inspector is per-machine; React DevTools is component-shaped. None of them shows the full cascade: dispatch → handler → fx → re-dispatch → machine transition → flow recompute → sub recompute → render. re-frame2 sees all of it; Causa *shows* all of it.
- **Time-travel that survives schema evolution.** re-frame2's `restore-epoch` has six named failure modes (per [Tool-Pair §Time-travel](../spec/Tool-Pair.md#time-travel-epoch-snapshots-and-undo)). Causa surfaces those failures structurally — "this rewind would break because schema `:auth` tightened since the snapshot."
- **Live machine state on a stately-quality chart, embedded in the same surface as event traces.** Stately Inspector is great; it's also a separate tab in a separate tool. Causa unifies it with the event log so a click on `:rf.machine/transition` jumps to the chart with the source state pre-highlighted.
- **SSR/hydration debugging.** The render-tree hash diff per epoch, server-vs-client view side-by-side. No JS debugger does this because none of the JS frameworks make hydration mismatches structurally observable.

**One-line pitch:** *Causa is a causality-graph debugger that answers "why did this happen?" for re-frame2 apps, built on Spec 009's trace bus and Tool-Pair's epoch contract.*

---

## §2 Lessons from the original re-frame-10x

### What v1 did well — preserve

- **Epoch as the unit of debugging.** v1's biggest correct call: organise UI around drain-settle boundaries, not raw trace events. The mental model — "every dispatch produces an epoch; an epoch tells a story" — is load-bearing. v2 keeps this and goes further (cascades that span epochs via `:parent-dispatch-id`).
- **In-app, not Chrome-extension.** v1 lives in the DOM, talks to the runtime via direct function calls, has zero IPC latency. Chrome extensions pay for sandbox isolation in latency and capability. v2 inherits the in-app posture.
- **Single keyboard shortcut to open.** `Ctrl+Shift+X` is muscle memory now. Keep it.
- **The five-panel skeleton** — app-db / subs / event / trace / timing — covers the right surfaces; v2 grows it but doesn't rip it out.
- **`app-db-follows-events?` mode.** The "scroll through epochs and watch app-db change" gesture is the single highest-value 10x affordance. v2 makes it the default.
- **`:closure-defines` elision.** Zero production bytes. Non-negotiable; v2 inherits the contract (and gets it free from Spec 009's universal `interop/debug-enabled?` gate).

### Where v1 hit limits — improve

- **Single-frame assumption baked into the data shape.** v1 has `(rf/get-app-db)` and `(rf/sub :status)` — no frame parameter. Multi-frame apps fall off the model. v2 is frame-first: every panel has a frame picker; the epoch buffer is per-frame; the causality graph spans frames.
- **No causal correlation across dispatch cascades.** v1 shows a flat event list; figuring out that event B was dispatched by event A's `:fx [[:dispatch [...]]]` requires reading the event handler. v2 has `:dispatch-id` / `:parent-dispatch-id` (per [Spec 009 §Dispatch correlation](../spec/009-Instrumentation.md#dispatch-correlation-dispatch-id--parent-dispatch-id)) — causality is data, not detective work.
- **App-db diff is shallow and slow on big dbs.** v1's diff highlights changed keys but doesn't handle deep nested change well; on a 50MB app-db it's noticeable. v2 uses structural-sharing diff (PersistentHashMap pointer-equality at each level) — O(changed paths), not O(db size).
- **Subscription view is hard to read at scale.** v1 lists every sub run with timing; on a complex app this is hundreds of rows. v2 collapses by sub-id with hit/miss/recompute counts, expands to per-run on click, and uses `:rf/epoch-record`'s `:sub-runs` projection (per [Tool-Pair §Time-travel](../spec/Tool-Pair.md#time-travel-epoch-snapshots-and-undo)) so the framework does the folding.
- **The trace panel is a firehose.** v1's raw trace stream is hard to filter; the search is substring-y; long sessions get sluggish. v2 has structured filters (`:op-type`, `:operation`, `:frame`, `:dispatch-id`, `:origin`) routed off Spec 009's stable vocabulary, plus a saved-filter library.
- **No notion of "what's broken right now."** v1 has no error feed. Failed dispatches surface as a console.error if you happen to be looking. v2 has a permanent **issues ribbon** (errors + warnings + schema violations + hydration mismatches) that flashes on new entries.
- **Pop-out window is half-broken.** Cross-window input issues, focus-stealing, doesn't survive reload. v2's "pop out" is a real second window driven by `BroadcastChannel` (same browser, in-process).
- **Settings persistence in localStorage gets corrupted.** Multiple GitHub issues. v2 stores settings under a schema'd shape with a `:rf/version` stamp; corruption triggers a clean reset with a user-visible notice.

### Known pain points — fix

- **"Why did this event fire?" requires reading the source.** Causal-ancestor walk is the single largest UX win in v2.
- **"Why is this sub returning stale?"** v1 shows the value but not the input chain. v2: click a sub, see its layer-1/2/3 inputs and their values, walk upward.
- **"Why is this view re-rendering?"** v1 shows `:view/render` events. v2 attributes via `:render-key` ([Spec 004 §Render-tree primitives](../spec/004-Views.md#render-tree-primitives)) and shows the subscription invalidation that triggered the re-render.
- **Hot-reload churn.** Every save re-registers handlers; v1's event log fills with `:registry/handler-replaced` noise. v2 has a "hide registry churn" toggle (default on during dev sessions).
- **`dispatch-sync` inside a handler errors are confusing.** v2 surfaces them as a first-class **issue card** with a fix-it suggestion ("convert to `:fx [[:dispatch [...]]]`").

### Features that piled up but never landed — consider

From scanning v1's issue tracker and the wiki:

- **External event timeline integration** (network requests, RAF, long tasks) — v2 has the **performance ribbon** (§4).
- **Subscription dependency graph viz** — v2 has the **subscription graph panel** (§6).

### Performance constraints

v1's tracing cost is real on hot dispatches. v2 inherits Spec 009's compile-time elision (zero prod cost), but at *dev* time we need to be careful:

- **Trace buffer depth defaults to 200** (per Spec 009). Causa's UI never reads from anywhere else for "recent history" — no parallel ring buffer. Configurable to 1000 for deep sessions.
- **Epoch history depth defaults to 50** (per Tool-Pair). Configurable. Causa shows the depth and a "fill ratio" so users know if they're aging out epochs faster than they're inspecting them.
- **Sub-cache panel** never holds references to cached values longer than the rendering frame (avoid extending sub lifetimes via the debugger).
- **App-db viewer** virtualises deep trees (lazy expansion past 100 keys).
- **The causality graph view** caps at the last 200 dispatches by default (matches trace buffer); deeper views require explicit "load older."

---

## §3 What re-frame2 unlocks

The framework grew capabilities since v1 that fundamentally change what's debuggable. Each row below is "new in re-frame2 → new tooling story Causa must tell."

| re-frame2 capability | New tooling story |
|---|---|
| **Multi-frame** ([002](../spec/002-Frames.md)) | Per-frame panels with a frame picker; cross-frame causality graph; the same sub-id can have different values in different frames and the tool must show this. |
| **Machines** ([005](../spec/005-StateMachines.md)) | Stately-quality state-chart per machine, live-highlighted; transition log keyed by machine; `:spawn-all` parallel-child viz; `:after` timer countdown indicators; microstep replay. |
| **Flows** ([013](../spec/013-Flows.md)) | Flow dependency graph; per-flow recompute count; "this flow ran *N* times this session" heatmap; the `:rf.flow/skip` (rf2-719e value-equal recompute suppression) badge so devs can see when flows are correctly *not* running. |
| **Source-coord stamping** ([001](../spec/001-Registration.md), [006](../spec/006-ReactiveSubstrate.md)) | **Click-to-source everywhere.** Every registered id, every DOM node (via `data-rf2-source-coord`), every machine guard/action/transition (via `:rf.machine/source-coords`). Source location surfaced as copyable `file:line` chips — the user opens the file in their editor of choice. No protocol-handler dependency. |
| **Trace bus** ([009](../spec/009-Instrumentation.md)) | The substrate of everything. Open shape, stable vocabulary, `:op-type` discriminator. Causa does not invent its own trace shape — it consumes Spec 009. |
| **Epoch history + `:rf/epoch-record` projections** ([Tool-Pair](../spec/Tool-Pair.md)) | First-class time-travel; per-frame epoch scrubber; pre-folded `:sub-runs` / `:renders` / `:effects` so the tool doesn't refold the raw trace. |
| **`reset-frame-db!`** ([Tool-Pair §Pair-tool writes](../spec/Tool-Pair.md#pair-tool-writes--state-injection)) | "Edit app-db live" affordance — type a JSON value into the panel, the runtime takes it. Records a synthetic epoch so the change is undoable. |
| **Six named restore failures** | Structured "this rewind won't work because X" rather than a silent no-op. |
| **`register-epoch-listener!`** | The assembled-per-cascade listener is what Causa routes off; cheaper than raw-stream re-folding. |
| **Tool-Pair surface** | Causa *and* re-frame-pair *and* Story consume the same primitives. No 10x dependency from the agent tools. Causa is a peer, not a parent. |
| **Schemas (Malli)** ([010](../spec/010-Schemas.md)) | Real-time schema-violation feed with five named recovery modes; "the schema for this path is X" tooltip on every app-db key; live `app-schemas-digest` shown so devs notice schema drift between dev and SSR. |
| **SSR + hydration** ([011](../spec/011-SSR.md)) | Hydration-mismatch debugger — server render tree vs client render tree, structural diff, click-to-source on the divergent node. The `:rf.ssr/hydration-mismatch` trace is the entry point. |
| **Routing** ([012](../spec/012-Routing.md)) | URL ↔ frame state visualisation; the `:rf/route` slice rendered as a breadcrumb above the app-db tree; nav-token timeline showing stale-result suppression in flight. |
| **`:origin` opt on dispatch** | Filter the event log by actor ("show me only the dispatches Claude issued this session"). Pair-tool / Story / human dispatches are all distinguishable. |
| **Performance API (`rf:*` `User Timing` measures)** | The performance ribbon reads `PerformanceObserver` directly — no re-frame2 API call needed; works in prod too if the opt-in flag is on. |
| **Production-elision verifier** | Causa can run `npm run test:elision` in-process and show "your build will ship *N* bytes of dev-only sentinels" — i.e. detect leaks before deploy. |

The architectural shift: in v1, 10x had to *invent* observability surfaces because the runtime had few. In v2, the runtime emits structured data for every interesting moment; 10x's job is *presentation*.

---

## §4 Headline experiences (the marquee features)

Seven bold things. Each one is something a programmer should stop and say "wait, what?" at.

### 4.1 Causality graph view

The hero feature. A directed graph where:

- **Nodes** are dispatches (one per `:event/dispatched` trace), effects (one per `:fx` outcome), and significant traces (machine transitions, schema violations, hydration mismatches).
- **Edges** are causal: `:parent-dispatch-id` links a child dispatch to its parent; an effect node's edge points to the dispatch it ran inside; a machine transition node's edge points to the event that triggered it.
- **Colours** encode `:op-type`. Errors are red and pulse for 3 seconds when they land. Pair-tool dispatches (`:origin :pair`) get a violet halo.
- **Click a node** → side panel shows the trace event, its `:tags`, the source coords, the affected sub-cache slots, the resulting `app-db` diff (if it was a dispatch).
- **Drag a time range** at the top → graph filters to that range; the rest of 10x synchronises.
- **A "find root cause" button** on any node walks upward until it hits a dispatch with no `:parent-dispatch-id` and highlights the path.

*What it literally looks like:* a vertical timeline on the left (each tick is a dispatch); horizontal swimlanes per frame; arrows from parent dispatches to children; colour-coded dots for fx-handled / machine-transition / schema-violation; a faint background ribbon showing INP per frame. The whole graph is laid out top-down (older → newer); errors get a thick red border; the currently-selected epoch's nodes are slightly larger.

*What I'm trading away:* this view is dense. Programmers used to a linear event log will need a moment. The mitigation is the **causality strip** — a flat horizontal version pinned at the top of every panel, so the graph is always one click away but never in the way.

### 4.2 Time-travel scrubber

A horizontal scrubber pinned to the bottom of the window. Drag left → app-db reverts; drag right → state advances. Scope is the current session — if the user reloads, that's a new session and the scrubber starts fresh.

- **Per-epoch step** — every dispatch produces an epoch you can scrub to; the app-db tree, the event detail panel, and the causality graph all rebase live as you move.
- **Pinned snapshots** — at any epoch, click a pin icon to capture a labelled snapshot to the scrubber's chip strip. Useful for "this is the state I want to come back to."
- **Schema-mismatch handling**: if a restore would fail (per the six failure modes in [Tool-Pair §Time-travel](../spec/Tool-Pair.md#time-travel-epoch-snapshots-and-undo)), the scrubber shows the failure as an overlay with a "try anyway" affordance that loads the snapshot via `reset-frame-db!` (which bypasses cascade and schema-validates against the *current* schemas).

*Trade-off:* the scrubber is bounded by `epoch-history` depth (default 50). Deeper sessions need to bump the depth in settings; beyond that, older epochs age out.

### 4.3 AI co-pilot panel

A pinned-right panel: a chat input above a scrollable result area. The co-pilot reads the same surfaces every other panel reads — `epoch-history`, `(rf/get-frame-db ...)`, `(rf/registrations ...)`, the trace bus — and answers programmer questions about the running app in natural language. Examples:

- *"Why did `:checkout/submit` fire?"* → walks `:parent-dispatch-id` upward; returns "dispatched by `:fx [[:dispatch ...]]` inside `:cart/finalise` (events.cljs:213). That ran because of `:user/clicked-checkout` at 10:43:21. Want me to open the source?"
- *"What's the current state of the auth flow?"* → reads `[:rf/machines :auth/login-flow]`; returns the snapshot; links to the machine inspector with that state highlighted.
- *"Show me every dispatch that touched `[:cart :items]`."* → walks epoch history; diffs each `:db-after`; returns the filtered list.
- *"Why is this view re-rendering when I click that button?"* → traces dispatch → db change → sub invalidations → re-renders; attributes by `:render-key`.

The co-pilot is **frame-aware** (knows which frame the human is looking at), **epoch-aware** (knows the scrubber position), and **registrar-aware** (can read every registration's `:doc`, `:spec`, source coords).

Calls the user's chosen LLM via their own API key, configured in Settings → AI Provider. The system prompt is wired with `(rf/registrations ...)` / `(rf/handler-meta ...)` / `(rf/app-schemas-digest ...)` shape so the model inherits framework conventions without being told. Default provider: Claude; swappable to OpenAI / Gemini / local Ollama / custom via a provider abstraction.

Open by default (per UX §12.2 lock). Toggled via `Ctrl+Shift+/`; user can close the rail and Causa remembers the choice across the session.

*Trade-off:* an AI panel in a debugger feels gimmicky if it's bolted on. The play is to make the co-pilot *cheaper than reading the source*. If it's not faster than `Ctrl+F`, it dies. The bar: 5 seconds from question to actionable answer for the five canonical questions in §1.

### 4.4 Machine inspector with live state-chart highlighting

Embedded XState-Inspector-quality visualisation. Built on the same primitives `tools/machines-viz/` will use ([per `tools/README.md`](../tools/README.md)). The relationship: Causa *embeds* machines-viz's chart component as a panel (registered via the cross-tool embedding contract, mirroring how Story embeds 10x's epoch panel — see §9).

What you see:

- A directional state-chart of the active machine. Nodes are states (compound states nested visually). Edges are transitions, labelled with their event id.
- The **current state pulses softly**. Compound states' active child is highlighted recursively.
- **Hover an edge** → see the guard and action functions; click to jump to source.
- **`:spawn` / `:spawn-all` spawned children appear as smaller machines next to their parent**, each with their own state.
- **`:after` timers show a countdown ring** on the source state.
- **Transition history** ribbon below the chart: a scrubbable list of the last *N* `:rf.machine/transition` events; clicking one rewinds the chart to that microstep.
- **Source-coord stamping** ([rf2-8bp3](../spec/Tool-Pair.md#state-machine-source-coord-stamping-rf2-8bp3)) means every clickable element jumps to source.

*Trade-off:* this is dense if the machine is large. We adopt Stately's expand/collapse compound-state idiom; we also auto-pan to the active state.

### 4.5 App-db panel — slice-centric, not tree-centric

Real app-dbs run 1–50MB. Rendering the whole tree on every dispatch is wrong: it competes for canvas real estate, virtualisation only partly helps, and **it's not what programmers actually want**. Programmers want to see **the slices that changed in this event**, plus a few slices they've pinned for watching.

So the default view is exactly that — a stack of focused slice-mini-panels:

```
┌─ Slice 1: [:cart :items]  (modified) ─────────────┐
│  before:  [{:id 7 :qty 1}]                        │
│  after:   [{:id 7 :qty 1} {:id 22 :qty 1}]        │
└────────────────────────────────────────────────────┘

┌─ Slice 2: [:cart :totals :gross]  (added) ────────┐
│  added:   $48.00                                  │
└────────────────────────────────────────────────────┘

┌─ Pinned slices ───────────────────────────────────┐
│  [:user :auth :status]           :authenticated   │
│  [:nav :route]                   :app/cart        │
└────────────────────────────────────────────────────┘

[Show full app-db tree ▸]
```

Properties:

- **Touched-slices** come from `:rf/epoch-record`'s precomputed changed-paths set. Each mini-panel renders the path's `:db-before` + `:db-after` values side-by-side, with colour-coding (green added, yellow modified, red removed).
- **Pinned slices** are user-watched paths. Right-click any value in the runtime → "Pin this slice"; the path is persisted to localStorage and rendered in this list across sessions.
- **Reserved keys** (`:rf/machines`, `:rf/route`, `:rf/system-ids`, `:rf/pending-navigation`) get a clearly-marked `[runtime]` group at the bottom — they're informational; pinned-slice etiquette suggests not pinning them.
- **Show full app-db tree ▸** is the escape hatch. Clicking expands the panel to the full canvas, renders the tree as a virtualised lazy-expand collapsible (only first 100 keys at each level eager-render). Includes a search input + persistent breadcrumb. Used rarely; the slice-centric view answers most questions.
- **Read-only.** In-place editing was considered and rejected (§10.4 lock) — the runtime is the source of truth.

Why this works for any app-db size: the panel **never tries to render the whole tree by default**. Slice mini-panels are bounded by the size of the touched path (typically a few keys deep). A 50MB app-db with two touched slices renders the same as a 100KB one with two touched slices.

### 4.6 Schema-violation timeline (Spec 010 surfacing)

A horizontal timeline along the bottom of the issues feed, one row per registered schema. When a schema validation fails:

- A dot appears on that schema's row at the timestamp.
- Colour encodes recovery: skip-handler / skip-fx / rollback-db / replaced-with-default / re-raised (the [Spec 010 §Per-step recovery](../spec/010-Schemas.md#per-step-recovery) categories).
- Click the dot → side panel shows `:where`, `:path`, `:value`, the Malli explanation.
- **Hover the dot** → tooltip shows a one-line cause ("at `[:auth :email]`, expected `:string`, got `nil`").

This is the kind of thing programmers don't realise is happening — silent schema violations are real bugs in disguise. Surfacing them temporally makes them impossible to ignore.

### 4.7 Hydration mismatch debugger (Spec 011 surfacing)

Only visible when an SSR hydration runs. Shows:

- A side-by-side render: server's render tree (deserialised from the payload) and the client's first render tree.
- **Divergent nodes pulse red.** The server tree marks where the client's tree took a different shape; structural diff (per the [Spec 011 §Hydration equivalence rule](../spec/011-SSR.md#hydration-equivalence-rule-canonical)).
- The **render-tree hash** is shown for both sides at every parent so the divergence is bisectable.
- A drop-down picks the failing node; the right pane shows source coords for the divergent view registration.

*Trade-off:* this only matters to SSR apps. We collapse to a one-line "no hydration mismatches" indicator otherwise.

---

## §5 Information architecture

### Where Causa lives — **in-app, with one pop-out mode**

**Default:** in-app DOM injection (v1's posture). One JS bundle pulled in via `:preloads` in dev; toggled via `Ctrl+Shift+X`. Zero IPC; the runtime is one closure away.

**Pop-out — separate window, same browser.** Triggered by a button; opens a `window.open` whose JS realm is connected to the opener's via the `window.opener` reference. The pop-out renders into the new window but **reads and dispatches against the opener's runtime atoms directly** — no `BroadcastChannel`, no `postMessage`, no structured-clone serialisation. The pop-out's React tree is its own root inside the new window; Reagent reactions are re-established on top of the opener's source atoms via deref. Solves the "I want Causa on a second monitor while the app runs full-screen" use case.

Constraints inherited from the `window.opener` posture:

- Same-origin required. The pop-out window must not be opened with `noopener` / `noreferrer`.
- If the user closes the opener window, the pop-out becomes orphaned (the runtime is gone). Pop-out detects this via `window.opener.closed` and shows a clean "opener gone — close this window" overlay.
- The pop-out can't survive a hard reload of the opener — atoms it was reading get garbage-collected. Pop-out re-bootstraps on opener reload by re-reading `window.opener.causaRuntime` (or whichever public handle Causa exposes).

We **do not** ship a Chrome extension. The IPC overhead, the sandbox isolation, and the manifest-v3 churn aren't worth the marginal "you don't have to change build config" benefit. The in-app posture is the right one for re-frame2.

We **do not** ship a standalone HTML viewer / remote-attach over WebSocket either. Serialising the runtime state across the wire is too costly (snapshot identities, machine references, sub-graph nodes, source-coord chips — none of these survive a JSON round-trip cleanly), and the use cases (mobile-from-desktop, cross-machine pair-debug) are too narrow to justify the protocol-versioning + security + reconnect-logic surface. The in-app + window.opener pop-out story covers the 95% case.

We also **do not** ship a VS Code panel — no editor-embed surface in any tier. Editor integrations go through the MCP channel (per §10.7, deferred decision) when they're needed.

### Default landing view

When a programmer hits `Ctrl+Shift+X`, they see:

- **The causality strip** pinned across the top: the last 20 dispatches as horizontal pills, the rightmost one in flight.
- **The current epoch's event detail** centred in the canvas: event vector, source, the slices it touched (rendered as slice mini-panels per §4.5), effects emitted, machines transitioned, schema status.
- **The AI co-pilot rail** open by default on the right (per §4.3 + UX §12.2).
- **The issues feed** as a collapsed strip at the bottom, with a count badge.
- **The frame picker** in the top-right (showing the active frame; click to switch).
- **The pinned-slices list** in a left-rail strip (collapsed unless the user has pinned any) — always-visible watched paths and their current values.

The default is "what just happened, in this frame, right now." No graph, no scrubber, no full app-db tree — those are one click away.

### Frame switching

The frame picker is a dropdown of `(rf/frame-ids)` (per [Spec 002 §Public registrar query API](../spec/002-Frames.md#the-public-registrar-query-api)). Switching:

- Re-binds every panel's frame context.
- The scrubber rebases on the new frame's `epoch-history`.
- The issues feed filters to the new frame (with a "show all frames" toggle).
- The causality graph spans frames regardless (cross-frame cascades are interesting), but greys out non-current-frame nodes.

### Navigation idiom — vertical sidebar over horizontal tabs

10x v1's horizontal tab bar limits panel names to short keywords. v2 uses a **left sidebar with icons + labels**, expandable. This scales better as panels grow (we end up with ~14 panels by v1.0). The sidebar collapses to icons-only on narrow screens; the panel area fills the rest.

---

## §6 Specific panels — the canonical surface

| Panel | What it does | Data source | Key affordances |
|---|---|---|---|
| **Causality graph** | The hero view (§4.1). Visualises dispatch cascades as a directed graph. | `register-epoch-listener!` for new edges; `(rf/trace-buffer {:op-type :event})` for backfill. | Click-node-to-detail; drag-time-window; find-root-cause; filter-by-`:origin`. |
| **Causality strip** | The horizontal flat version of the causality graph, pinned at the top of every panel. | Same as above. | Click an event pill → jump to the full causality graph at that node. |
| **Event log** | The canonical 10x v1 panel. Per-frame list of dispatched events, oldest-first or newest-first. | `(rf/trace-buffer {:op-type :event :frame current-frame})`. | Click → expand to show cofx, fx, db-diff. Group-by-cascade. Right-click → "re-dispatch." |
| **App-db inspector** | The current value of `app-db`, with diff highlight (§4.5). Read-only. | `(rf/get-frame-db frame-id)` + epoch's `:db-before`. | Collapse-to-changed; bookmark a path. |
| **Subscription graph** | A force-directed graph of sub dependencies for the active frame; layer-1/2/3 grouping. | `(rf/sub-cache frame-id)` (CLJS-only, per Tool-Pair). | Click a sub → see inputs / outputs / cached value; "what subs read from this app-db path?" |
| **Effect log** | Every fx invocation across the trace buffer. | `(rf/trace-buffer {:op-type :fx})` + `:rf/epoch-record :effects` projection. | Filter by fx-id; show outcome (`:ok`/`:error`/`:skipped-on-platform`); inspect args. |
| **Trace timeline** | Raw trace events with timing, performance-API-style. | `(rf/trace-buffer)` + Spec 009 `User Timing` measures. | Structured filter; saved-filter library; export-as-JSON. |
| **Machine inspector** | Stately-quality state-chart per machine (§4.4). Embeds `tools/machines-viz/`. | `(rf/machines)` + per-machine snapshot at `[:rf/machines <id>]` + `:rf.machine/transition` traces. | Live highlight; transition history scrub; source-coord jump. |
| **Flow graph** | A DAG of registered flows; per-flow recompute heatmap. | `(rf/registrations :flow)` + `:rf.flow/*` traces (per Spec 013). | "How often did this flow skip?"; "what triggered the last recompute?"; mark a flow as dormant. |
| **Performance ribbon** | INP, long tasks, layout shifts, re-render counts, per epoch. | `PerformanceObserver` watching `rf:*` entries + browser's own `event` / `layout-shift` entries. | Hover an epoch → see its INP; spike-detect; "show me the longest 10 cascades this session." |
| **Schema violation timeline** | (§4.6). | `(rf/trace-buffer {:operation :rf.error/schema-validation-failure})`. | Drill-down to Malli explanation; jump-to-`reg-app-schema` source. |
| **Issues ribbon** | A unified feed of errors + warnings + schema violations + hydration mismatches. | All `:op-type :error` / `:warning` traces (including `:operation :rf.ssr/hydration-mismatch`, which carries `:op-type :error`). | "Mark resolved"; "snooze for this session"; click → causality-graph rewind to that moment. |
| **Hydration debugger** | (§4.7). | `:rf.ssr/hydration-mismatch` traces + payload + first client render-tree hash. | Side-by-side; click-to-source on divergent node. |
| **AI co-pilot** | (§4.3). Chat over the runtime; reads epochs / app-db / handlers; cites source coords. | The registrar + epoch history + trace buffer + user's question + LLM provider (default Claude). | Chat; saved prompts; provider switching; open-by-default rail. |
| **Routing inspector** | The `:rf/route` slice as a breadcrumb + nav-token timeline. | `(rf/sub :rf/route)` + `:rf.route.nav-token/*` traces. | "Why did this nav fail?"; pending-navigation surfacing. |
| **Settings / filters** | A panel for configuring depths, default frame, theme, AI provider key, etc. | localStorage with schema'd shape (per §2 lessons). | "Reset to defaults" — never gets stuck. |
| **Time-travel scrubber** | Pinned at the bottom of the window; (§4.2). Session-local (no export/import). | `(rf/epoch-history frame-id)` + `restore-epoch` + `reset-frame-db!`. | Drag-to-rewind; pin a labelled snapshot; schema-mismatch overlay. |

That's 16 panels. v1 had ~6. The growth is structural — re-frame2 emits more, and Causa surfaces it.

---

## §7 Visual + interaction design heuristics

### Density gradient — default vs expert mode

10x v1's UI is dense. v2 has a **density slider** (compact / cosy / comfortable). Default to cosy. The causality graph and the machine chart have their own visual-detail toggles independent of the global density.

A **"glance mode" toggle** collapses everything to:

- The causality strip pinned at the top (last 20 dispatches).
- The issues ribbon (always visible if there are issues).
- The frame picker.

Glance mode is for "I'm not actively debugging but I want the runtime to whisper at me when something is wrong."

### Typography

- A single variable-weight sans for UI chrome (Inter or system-ui).
- A monospaced font for data display (JetBrains Mono / IBM Plex Mono / system mono).
- App-db trees, trace events, source coords — all monospace.
- Headers, panel chrome, labels — sans.

Font size: 13/14px default; configurable. Resist tiny.

### Colour and theme

Dark by default (the v1 community already prefers dark). Light theme ships at v1.0; a `prefers-color-scheme` toggle picks initial state. High-contrast mode is a v1.1 commitment.

Encoding:
- **Red** = errors, schema violations, hydration mismatches.
- **Yellow** = warnings, schema-replaced-with-default.
- **Green** = success, additions.
- **Blue** = info, modifications.
- **Violet** = pair-tool / AI-issued dispatches (`:origin :pair` / `:claude`).
- **Grey** = neutral, registry churn, hidden-by-filter.

The palette is **WCAG AA contrast against both backgrounds**. We don't rely on colour alone (icons + text labels accompany every coloured marker).

### Keyboard navigation

Every panel is navigable without the mouse.

- `?` opens a key-binding cheat-sheet overlay.
- `Ctrl+Shift+X` toggles 10x.
- `Ctrl+K` opens a command palette (jump-to-panel, jump-to-event, jump-to-source).
- `j`/`k` next/previous in any list view (event log, scrubber, trace timeline, issues feed).
- `[`/`]` previous/next epoch (mirrors the scrubber).
- `Esc` collapses popovers.

The command palette is the spine. It's how power-users move.

### Screen-reader story

The trace timeline and the event log are the most-accessible-by-default — they're text-heavy. The causality graph and the machine chart need ARIA `aria-label`s on every interactive node and a **textual alternative view** (a focus-ordered list rendering of the graph with the same hierarchical relationships). v1.0 ships the alt-view for causality; the machine chart's alt-view is a v1.1 commitment (it's harder — hierarchical compound states need careful ARIA structure).

### Mobile

Does Causa consider mobile use? **Mostly no, with a narrow exception.**

10x is a debugger and debugging happens at a workstation. We don't make the in-app 10x panel responsive to <600px viewports.

The exception: the **remote-attach viewer** (§5) running on a tablet, observing a session running on a phone via WebSocket. This is a 5%-use-case but the standalone HTML viewer should at least be tablet-friendly (>=900px). v1.0 ships tablet-responsive viewer; phone-form-factor 10x is explicitly out of scope.

### Animation discipline

Animation is communicating, not decorating.

- Diff flashes: 400ms tween.
- Causality graph edge entries: 250ms ease-out.
- Errors: a single 600ms pulse on entry, no continuous animation.
- Machine-chart state pulse: 1.2s slow heartbeat (subtle).
- Everything else: instant.

A **"reduced motion"** toggle (respecting `prefers-reduced-motion`) kills all animation; we don't ship a non-toggleable shake-or-pulse-anywhere.

---

## §8 Deployment + delivery

### Chrome extension vs in-app

**In-app (default), with a same-browser pop-out via `window.opener`.** No Chrome extension at any tier; no remote-attach over WebSocket. The reasons (recap):

- Zero IPC latency vs the runtime.
- No manifest-v3 churn.
- Same elision contract as the runtime — `goog.DEBUG=false` strips Causa entirely.
- The same-browser pop-out covers the "I want Causa on a second monitor" use case via `window.opener` direct reference — no serialisation cost, no protocol versioning, no reconnect logic.
- Remote-attach (cross-machine, mobile-from-desktop) is out of scope per §10.3 — too costly to serialise the runtime state across a network wire.

### Bundle size budget

The elision contract (Spec 009 §Production builds) gives us a hard guarantee: **0 bytes in production**, full stop. CI's `npm run test:elision` job (which Causa inherits — its sentinels join the runtime's) blocks any leak.

In dev mode, the bundle target is:

- Core Causa (UI shell + the 16 panels): **<1.5 MB minified, <500 KB gzipped.** Less than v1's bundle by a wide margin via aggressive code-splitting per-panel.
- The AI co-pilot panel: **<400 KB extra**, lazy-loaded on first open.
- The machine inspector chart: **<300 KB extra**, lazy-loaded.

We achieve this via shadow-cljs's per-output target slicing — every panel is a separate module; the sidebar loads metadata only; opening a panel kicks the relevant module.

### Performance overhead budget

Acceptable cost on the running app:

- **Trace bus emission overhead:** measured in microseconds per event (per Spec 009). Causa adds <2µs per emit (one listener invocation + a `cons` onto a transient batch).
- **Causality graph live updates:** debounced to 16ms (one rAF). A burst of 1000 events still updates the graph once per frame.
- **App-db diff:** O(changed paths) via PersistentHashMap pointer-eq. Negligible on dbs <10MB; bounded by the change set.
- **Rendering:** every panel virtualises long lists; nothing renders >200 rows at once; the causality graph caps at the last 200 dispatches.

The hard rule: **opening Causa must not change observable INP** on a typical app. If it does, we cut features until it doesn't.

### Install / enable / disable

```clojure
;; In shadow-cljs.edn dev build:
{:builds {:app {:devtools {:preloads [day8.re-frame2-causa.preload]}}}}
```

That's it. The preload registers under `register-listener!` and `register-epoch-listener!`, mounts a hidden DOM root, listens for `Ctrl+Shift+X`. No code change in the app itself.

Disabling: remove the `:preloads` entry, or set `:closure-defines {re-frame.interop/debug-enabled? false}` to force-disable even in dev.

We follow v1's preload convention exactly — muscle memory transfer is free.

---

## §9 Cross-tool cohesion

### Story embeds 10x's epoch panel (already decided)

From [`re-frame-2-story-feature-set.md`](re-frame-2-story-feature-set.md) §6.7 and §4.4: Story registers a story-panel that embeds Causa's epoch panel as the variant's observability ribbon.

**The embedding contract** (this design): every Causa panel exports a **`Panel`** React component (or hiccup-fn equivalent) that accepts:

```clojure
{:frame    :story.auth/login          ;; which frame to observe
 :compact? true                       ;; reduced chrome — no sidebar, no header
 :height   320                        ;; in pixels, optional
 :scope    {:dispatch-id-prefix ...}} ;; optional filter to restrict the observation window
```

Story's `reg-story-panel` wires the embedded panel into the variant page. The contract is: the Causa panel **knows it's embedded** (so it doesn't claim Ctrl+Shift+X, doesn't open its own pop-out, doesn't show a "close" button), but otherwise renders identically.

Causa exposes the **embedded epoch panel** at v1.0. The embedded causality graph and embedded machine inspector ship at v1.1 (so Story can embed them too if it wants).

### machines-viz overlap

`tools/machines-viz/` and Causa's machine inspector both render state-charts. The relationship: **machines-viz owns the chart component; Causa embeds it** as one of its 17 panels. Same direction as Story → 10x.

The contract: `machines-viz` exports a `MachineChart` component that accepts a machine-id and a frame-id, renders the chart, handles the live-highlight. Causa's machine inspector panel is a thin wrapper that adds the transition-history ribbon and the source-coord jump affordance. machines-viz can be used without Causa (programmer who only wants a chart); Causa *depends* on machines-viz for the chart rendering.

### re-frame2-pair overlap

re-frame2-pair is AI-driven (an LLM integration via nREPL). Causa is human-driven with an embedded AI co-pilot. **Where they meet:**

- Both consume the same Spec 009 / Tool-Pair surfaces. Neither depends on the other.
- The **AI co-pilot panel in Causa** (§4.3) uses the same primitives as the pair tool — `(rf/registrations ...)`, `(rf/epoch-history ...)`, `(rf/get-frame-db ...)`, `register-listener!`. The difference is the user surface: Causa lives in the browser; re-frame2-pair lives in the editor / REPL.
- A future enhancement: Causa's co-pilot delegates to a running re-frame2-pair nREPL session if one is detected. The co-pilot becomes a thin chat shell over the pair tool's full capability. v2 commitment (per §10.8, still open), not v1.

### MCP surface

> **NOTE (2026-05-19, rf2-hvl1g).** The `causa-mcp` jar described
> below was dropped — see DESIGN-RATIONALE.md Lock #6 supersedence.
> AI agent access flows through `tools/re-frame2-pair-mcp/` against
> the framework-published Causa runtime API instead. The text below
> is preserved as historical design lineage.

**Causa ships `tools/causa-mcp/`** (a separate jar, mirroring `tools/story-mcp/`). The MCP server exposes:

- `get-trace-buffer` — slice of the trace stream by filter.
- `get-epoch-history` — per-frame epoch history.
- `get-app-db` — current value at a frame, optionally at a path.
- `get-machine-state` — current snapshot for a named machine.
- `get-issues` — recent errors/warnings/schema-violations.
- `restore-epoch` — rewind a frame.
- `reset-frame-db` — inject state.
- `dispatch` — fire an event (with `:origin :mcp`).

This is largely **the same surface as `tools/story-mcp/`**, because Story's MCP and Causa's MCP both ultimately surface the Tool-Pair contract. The split exists for jar-bundle isolation and per-tool release cadence; the tools converge on a common MCP-tool vocabulary. v1.1.

We *don't* duplicate the pair tool's MCP — re-frame2-pair's MCP (if it ships one) is editor-focused; 10x's MCP is debugger-focused. Both can coexist; agents pick the one their workflow needs.

---

## §10 Open design questions for Mike

Each as a §X.Y with my recommendation + the alternative.

### §10.1 Name

**Locked 2026-05-11: Causa.** Artefact `day8/re-frame2-causa`. Tagline: *"the cascade you can see."*

### §10.2 AI co-pilot

**Locked 2026-05-11: ship at v1.0; open by default.** The runtime is structured enough that an AI can navigate it; not shipping the co-pilot at v1 leaves the marquee value on the table. Panel hits Claude via the user's API key (no service-side dependency); swappable provider abstraction supports OpenAI / Gemini / local Ollama. Earlier "drop entirely" call was reverted same day — co-pilot is back as one of the marquee features.

### §10.3 Deployment posture

**Locked 2026-05-11: in-app DOM injection + same-browser pop-out via `window.opener`.** No Chrome extension at any tier. No standalone HTML viewer / remote-attach over WebSocket — the serialisation cost (runtime atoms, reactions, sub-graph, machine references) is too high, and the use cases (mobile-from-desktop, cross-machine pair-debug) too narrow. BroadcastChannel was considered for the pop-out wire but hits the same structured-clone problem; `window.opener` direct reference is the clean answer (same JS realm, no serialisation).

### §10.4 In-place app-db editing affordance

**Locked 2026-05-11: not required.** The runtime is the source of truth; pokes at app-db from the debugger are out of scope. App-db panel is read-only.

### §10.5 Session export / import

**Locked 2026-05-11: not required.** If the user reloads, that's a new session. Scrubber scope is the current session only.

### §10.6 Embedded vs free-standing relationship with machines-viz

**Locked 2026-05-11: Causa embeds machines-viz's chart.** §9 covers this. Both ship from `tools/` with per-jar cadence (per [`tools/README.md`](../tools/README.md)); machines-viz is `re-frame2-machines-viz`; Causa deps in it at a `~> 1.x` version range.

### §10.7 MCP shipping at v1.0 vs v1.1

**Deferred 2026-05-11.** Decision punted to a later read.

### §10.8 re-frame2-pair delegation in co-pilot

**Open.** With co-pilot back (§10.2), this is on the table again. Recommendation: defer to v2 — the co-pilot has its own chat via direct LLM API; re-frame2-pair delegation is an "if a re-frame2-pair nREPL session is running, route through it" optimisation, not blocking for v1. Awaiting Mike's call.

### §10.9 Mobile / phone form factor

**Locked 2026-05-11: not needed ever.** Tablet-responsive standalone viewer at v1.0; no phone-form-factor Causa, no future commitment. Debuggers belong at workstations.

### §10.10 Causality graph as hero feature (raised in self-criticism)

**Locked 2026-05-11: ship as hero; evaluate in practice.** Risk acknowledged (visually striking ≠ habit-forming; programmers may keep reaching for the flat event log). Mitigation is the always-pinned causality strip (flat horizontal version) — graph is one click away but the strip carries everyday weight. v1.0 launches with the graph as the hero; if user behaviour shows the strip is doing the real work, demote the graph from hero to one of the 16 panels in v1.1.

---

## §11 Roadmap

### v1.0 — "the cascade you can see"

The thesis ships: causality graph (hero), AI co-pilot (open-by-default rail), time-travel scrubber (session-local), machine inspector (via machines-viz), slice-centric app-db panel (read-only), schema violation timeline, issues ribbon. All 16 panels listed in §6. Same-browser pop-out via `window.opener`. WCAG AA contrast. Reduced-motion respect.

Explicit non-goals at v1.0: Chrome extension, standalone HTML viewer / remote-attach over WebSocket (out of scope per §10.3), VS Code panel (never — editor integrations go via MCP if/when it ships), MCP server (deferred per §10.7), machine-inspector alt-view, mobile phone form factor (out of scope per §10.9), in-place app-db editing (out of scope per §10.4), session export/import (out of scope per §10.5), fork-from-here.

### v1.1 — "extend the reach"

- Embedded causality graph + embedded machine inspector for Story.
- Pop-out 2-monitor mode polished.
- High-contrast theme.
- Machine inspector alt-view (screen-reader).
- Per-frame error policy surfacing (showing the `:on-error` registered for each frame).

(MCP server is on the table for v1.1 but the call is deferred — see §10.7.)

### v2.0 — "the editor partner"

- re-frame2-pair delegation in the co-pilot (per §10.8, still open).
- Editor-driven debugging (Cursor / Claude Code can request Causa to focus a frame, run a query, via the MCP channel when it ships).
- Programmable saved-filter library (write your own filter functions, save them, share them).
- Differential `app-db` snapshots — a "this app-db diverges from baseline" mode for property testing.

---

## §12 Voice + naming

### Name

**Locked:** **Causa**. Artefact `day8/re-frame2-causa`. Tagline: *"the cascade you can see."*

### Visual identity

- A single mark: an arrow forking from one node into three nodes (the cascade). Works at favicon size; works as the launch button in Causa itself.
- The default theme is dark with a violet accent (echoing `:origin :pair` violet — every cause has an origin).
- One typeface (Inter or system-ui) plus one monospace (JetBrains Mono / system mono).
- No mascots, no skeuomorphism, no cute.

### Voice

The README, the tagline, the marketing copy: **direct, observational, programmer-to-programmer.**

> *"You dispatched. Then five things happened. Causa shows you which one was the one."*

Not "magical." Not "AI-powered" as the headline (the AI is a feature, not the pitch). Not "revolutionary." Tools that hype themselves get used briefly; tools that respect their users get used daily.

Compare:

- **Bad:** "AI-Powered Debugging for Modern Front-Ends: Causa Reimagines What's Possible."
- **Good:** "Open Causa. Click the event you didn't expect. Read what fired it. Close Causa."

---

## Self-criticism

Where this design is speculative or weak:

- **The causality graph as the hero feature is a launch-and-learn call.** Risk: programmers keep using the flat event log because that's what they know, and the graph becomes a pretty-but-unused tab. Mitigation: the causality strip (the flat horizontal version) is always pinned, so the graph is always one click away but never blocking. v1.0 ships with the graph as the hero; v1.1 demotes if usage data shows the strip is doing the real work. (Decision locked in §10.10.)
- **The AI co-pilot is a bet on 2026 maturity.** If Claude / OpenAI / etc. APIs stop being cheap-and-fast, or if the model can't actually do the five canonical question types reliably, the panel becomes vaporware. Mitigation: the co-pilot is a panel, not a foundation — the rest of Causa functions if it's disabled.
- **16 panels is a lot.** v1 had ~6 and was already complex. The mitigation is the sidebar IA (§5) plus the command palette, but discoverability of less-used panels (flow graph, routing inspector) is a real risk.
- **The remote-attach mode requires a WebSocket endpoint in the running app**, which is dev-only but still real code we're asking the runtime to ship. It needs to be opt-in (a `(rf/configure :remote-debug {:enabled? true :port 9710})` knob); we haven't fully spec'd the wire format.
- **The performance ribbon's INP attribution to specific cascades is heuristic.** Browser INP is measured per user-interaction; mapping that to a re-frame2 cascade requires correlating timestamps. There will be edge cases where the attribution is wrong.
- **The 16 panels listed in §6 are mostly real but a few are conditional.** "Routing inspector" is real (Spec 012 emits the traces). "Hydration debugger" only fires for SSR apps. The default landing-view UX needs to gracefully handle "this app doesn't use most of these panels" (auto-hide empty panels from the sidebar; show them under a "more" pull-out).

---

## Closing — the bar

The bar this design sets: a programmer hits `Ctrl+Shift+X`, sees the cascade their last click triggered, asks "why?", and has the answer in 5 seconds. Causa earns its keep at that bar.

If a panel doesn't help answer "why?" or "what happened?" or "what's wrong?", it doesn't ship. We could pile features (the rolling Storybook MCP / a11y / coverage / theme story is real for component workshops; Causa's lane is *debugging the runtime*). Restraint is what keeps the tool habit-forming instead of impressive.

The headline isn't "we built a better trace panel." The headline is "we built the debugger re-frame2 deserves — one that explains the runtime to the human in the runtime's own terms, with an AI co-pilot for when explaining isn't enough."

— end of design draft —
