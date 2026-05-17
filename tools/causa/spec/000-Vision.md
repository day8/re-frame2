# 000-Vision: Causa — the re-frame2 devtools surface

## Why it exists

When a re-frame app misbehaves, the programmer should be able to ask
five canonical questions and answer them in seconds, with click-to-source
on every artefact:

1. **Why did this event fire?** (causal-ancestor walk)
2. **What did that event change?** (`app-db` diff + flow recomputes + machine transitions)
3. **Why is this subscription returning the wrong value?** (input-chain trace + cache state)
4. **Why is this view re-rendering?** (subscription invalidation chain + render-key attribution)
5. **What's currently broken?** (errors, warnings, schema violations, hydration mismatches — surfaced as one feed)

v1 of re-frame-10x answered #1–#4 partially and #5 not really. Causa
answers all five, in seconds, with click-to-source for every artefact.

re-frame2's instrumentation surface (Spec 009 trace bus, Tool-Pair
epoch history, source-coord stamping, machine and flow registries)
emits structured data for every interesting moment. Causa's job is
*presentation*: render the runtime to the human in the runtime's own
terms.

## What it is

A re-frame2-native devtools surface that runs **in-app**, preloaded into
dev builds. The shell is a **4-layer chrome** (ribbon · event list ·
tab bar · detail panel) — see
[`018-Event-Spine.md`](018-Event-Spine.md) for the full architectural
contract.

Every open lands the user on the latest cascade. The Event tab
(default) shows the event vector, source, handler return, db writes,
fx, and the fx-handlers that ran — the five canonical questions
answer themselves on first paint; deeper investigation is one tab away
(`a` App-db · `v` Views · `t` Trace · `m` Machines · `i` Issues) or one
keypress away (`c` Causality popover from any tab).

The single axis is **`:rf.causa/focus`** — pick an event in the list,
every dependent panel rebinds. No two-axis selection; no `(peek
history)` reads in panels.

## What it isn't

- **Not** the AI surface. There is no in-Causa AI co-pilot panel, no
  Causa-MCP server, no LLM chat rail. AI access to the running
  re-frame2 runtime goes through `tools/pair2-mcp/` (raw nREPL over
  MCP) — the agent reads the same instrumentation Causa reads, not a
  Causa-curated facade. **Causa is the human-only observability
  surface; pair2-mcp is the AI access path.** Two doors, no
  compromises.
- **Not** a port of re-frame-10x v1. The chrome is 10x-shaped at the
  layer level (events at the top, detail at the bottom, scrubber via
  ribbon+list, keyboard-first) but the substrate is re-frame2's enriched
  trace bus, multi-frame model, machine and flow registries, and
  source-coord stamping.
- **Not** a registrar, dispatch, effect, or component layer. Causa is a
  downstream consumer of re-frame2's existing surfaces — per the
  feedback that downstream EPs must not add new registries (this is
  not even an EP; it is a tool).
- **Not** writeable into `app-db`. Read-only forever. The runtime is
  the source of truth; pokes from the debugger are out of scope (lock
  #3 in `DESIGN-RATIONALE.md`).
- **Not** a session recorder. No export, no import. Sessions are
  ephemeral (lock #4).
- **Not** a mobile surface. Desktop only; viewports below 600px refuse
  to mount (lock #5).
- **Not** a Chrome extension. In-app DOM injection plus a same-browser
  pop-out via `window.opener`. Remote-attach lives over MCP, not over
  a custom WebSocket protocol (lock #9).
- **Not** part of any production bundle. Per the bundle-isolation
  contract in `tools/README.md`, dependency arrows flow tool →
  implementation; Causa is invisible to consumer apps after elision.
- **Not** a Stately editor / exporter. No machine→xstate-json bridge;
  no Stately compatibility. Causa is the canonical rendering surface
  for re-frame2 machines.

## What re-frame2 unlocks

Each row is "new in re-frame2 → new tooling story Causa must tell."

| re-frame2 capability | Causa surface |
|---|---|
| **Multi-frame** (Spec 002) | Per-frame inspection; single-select frame picker in ribbon; cross-frame causality via Causality popover. |
| **Machines** (Spec 005) | Stately-quality state-chart per machine; transition log; `:after`-timer countdown; `:invoke-all` parallel-child viz; UC1 interactive simulation; UC2 multi-instance Mode A/B/C; XState-parity supervision tree. ELK+SVG primitive ships Causa-internal (no separate `tools/machines-viz/`). |
| **Flows** (Spec 013) | Surfaced in Views tab when a flow's downstream sub recomputed (under "Re-rendered" group). |
| **Source-coord stamping** (Spec 001 + 006) | Click-to-source on every node, view, machine guard, transition. |
| **Trace bus** (Spec 009) | The substrate of everything. Causa does not invent its own trace shape. |
| **Epoch history + `:rf/epoch-record` projections** (Tool-Pair) | First-class time-travel via the ribbon's `[◀ ▶ ⏭]` nav + the event list (L2). |
| **Six named restore failures** | Structured "this rewind won't work because X" rather than a silent no-op. |
| **`register-epoch-cb!`** | The per-cascade listener routes the Event tab + Issues tab + Causality popover. |
| **Schemas (Malli)** (Spec 010) | Schema-violation rows in the Issues tab. |
| **SSR + hydration** (Spec 011) | Hydration-mismatch rows in the Issues tab. |
| **Routing** (Spec 012) | Route is a sub-tree of app-db — surfaced in App-db tab; nav-token timeline via Trace tab when `event` chip ON. |
| **`:origin` opt on dispatch** | Filter by actor via ribbon IN/OUT pills. |
| **Data classification** (Spec 015) | Path-marked sensitive + large rendering: `[● REDACTED N]` magenta opaque + `[● ELIDED N]` yellow drillable. See [`018-Event-Spine.md`](018-Event-Spine.md) §12 for the per-surface rendering contract. |
| **Production-elision verifier** | Causa runs `npm run test:elision` and warns before deploy if a dev sentinel leaked. |

## The 6-tab inventory

The legacy 16-panel sidebar is dead. Causa now ships a **6-tab detail
panel** (Layer 3 of the 4-layer chrome). Each tab is one projection of
the focused event; selection in the L2 event list rebinds every tab.

| # | Tab | Mnem | What it shows | Spec |
|---|---|---|---|---|
| 1 | **Event** | `e` | Whole event vector + arg-map + source · handler return · db writes · fx vector · fx-handlers that ran. **Folds in the old Effects tab content.** | [`016-Auxiliary-Panels.md`](016-Auxiliary-Panels.md) §Event-detail panel + [`018-Event-Spine.md`](018-Event-Spine.md) §5.1 |
| 2 | **App-db** | `a` | Diff `:db-before` vs `:db-after` — slice-first · pinned watches · full-tree disclosure. | [`004-App-DB-Diff.md`](004-App-DB-Diff.md) + [`018-Event-Spine.md`](018-Event-Spine.md) §5.2 |
| 3 | **Views** | `v` | Per-view rows: mounted / re-rendered / unmounted groups; **subs nested under each view row** showing return values. **Replaces the old Subs panel.** | [`012-Views.md`](012-Views.md) |
| 4 | **Trace** | `t` | Raw multi-axis trace stream filtered to the focused cascade; trace-type toggle row + IN/OUT pills + sensible defaults. | [`013-Trace-Bus.md`](013-Trace-Bus.md) + [`018-Event-Spine.md`](018-Event-Spine.md) §5.3 |
| 5 | **Machines** | `m` | UC1 (definition + sim) + UC2 (Mode A/B/C dynamic instances); supervision tree. **MachineChart is now Causa-internal (`tools/machines-viz/` collapse).** | [`003-Machine-Inspector.md`](003-Machine-Inspector.md) |
| 6 | **Issues** | `i` | JS exceptions + schema violations + sensitive-data warnings + hydration mismatches + perf-budget overruns + app console errors/warns. | [`016-Auxiliary-Panels.md`](016-Auxiliary-Panels.md) §Issues ribbon + [`018-Event-Spine.md`](018-Event-Spine.md) §5.4 |

**Causality is a popover, not a tab** — invoke via `c` key from any
tab. See [`018-Event-Spine.md`](018-Event-Spine.md) §10.

### What this inventory drops (vs the legacy 16 panels)

| Dropped | Reason | Lives in |
|---|---|---|
| **AI co-pilot panel** | Causa is the human surface; AI access goes via `tools/pair2-mcp/` over raw nREPL | `tools/pair2-mcp/` (sibling tool, not a Causa panel) |
| **MCP Server panel** | `tools/causa-mcp/` artefact is dropped entirely; no Causa-curated MCP surface | nowhere (dropped) |
| **Subscriptions panel** | Subs are nested under the views that consumed them; the view (not the sub) is the natural unit developers reason about | Views tab (`v`), per-row nested-subs |
| **Effects panel** | The "fx handlers that ran" content folds into the fattened Event tab | Event tab (`e`), "fx handlers that ran" block |
| **Performance panel** | Chrome DevTools' Performance tab renders the framework's `rf:event:*` / `rf:sub:*` / `rf:fx:*` / `rf:render:*` / `rf:cascade:*` User-Timing entries natively in the Timings track. Causa stops duplicating a surface Chrome does better. | Chrome DevTools → Performance tab |
| **Causality graph as a tab** | Causality is consulted, not browsed — transient popover triggered by `c` from any tab fits the usage pattern better than an always-mounted tab | `c`-key popover (centred 640×480 floating overlay; see [`018-Event-Spine.md`](018-Event-Spine.md) §10) |
| **Flows panel** | Flows surface when their downstream subs recompute — fold into the Views tab's "Re-rendered" group | Views tab (`v`) |
| **Routes panel** | Route state is a sub-tree of app-db; navigation timeline lives in Trace tab | App-db tab (`a`) + Trace tab (`t`) |
| **Schemas panel** | Schema violations are issues | Issues tab (`i`) |
| **Hydration panel** | Hydration mismatches are issues (SSR-only) | Issues tab (`i`) |
| **Time-travel panel** | The scrubber is now the ribbon `[◀ ▶ ⏭]` cluster + the event list (L2). No separate panel. | L1 ribbon nav cluster + L2 event list |
| **Settings panel** | Settings is a modal popup anchored to the ribbon `⚙` icon — transient overlay, not a tab | Modal popup (`,` or `s` or click `⚙`); see [`018-Event-Spine.md`](018-Event-Spine.md) §9 |

**Tab count trajectory:** 16 (legacy) → 8 (Effects folded in round-1) →
6 (round-2: Subs folded into Views; Performance dropped; Causality
folded into popover). Six is the smaller number that better fits the
chrome.

See [`018-Event-Spine.md`](018-Event-Spine.md) for the full 4-layer
chrome + spine-binding architectural contract.

## The bar

A programmer hits `Ctrl+Shift+C`, sees the cascade their last click
triggered in the Event tab — `:rf.causa/focus` already points at the
freshest event — asks "why?", and either reads the answer in the
panel (event vector, source coord, db writes, fx outcomes) or presses
`c` to see the causality graph. Five seconds.

If a tab doesn't help answer "why?" or "what happened?" or "what's
wrong?", it doesn't ship. Restraint is what keeps the tool
habit-forming instead of impressive.

## Audience

- **The re-frame programmer mid-feature.** Wants the event list in
  their face but unobtrusive; wants click-to-source from anywhere;
  wants to scrub.
- **The programmer reading an unfamiliar codebase.** "Why does clicking
  Save eventually re-render this card three modules over?" — wants the
  causality popover (`c`) and the Views tab.
- **The on-call programmer triaging a production-shaped repro.** Loads
  the app, scrubs via `[◀ ▶ ⏭]` + L2, finds the divergence.
- **The AI agent driving the runtime.** Doesn't open the UI; consumes
  the same data through the same primitives via `tools/pair2-mcp/`
  over raw nREPL. Causa and pair2-mcp are siblings; neither owns a
  registry; neither writes to app-db.

## Where Causa fits

Causa is one of two downstream consumers of the same re-frame2
instrumentation surface (the "two doors" split — see What it isn't):

```
┌──────────────────────────────────────────────────────────────────────┐
│  HUMAN SURFACE                            AI / AGENT SURFACE          │
│                                                                       │
│  ┌────────────┐                          ┌────────────┐               │
│  │   Causa    │                          │ pair2-mcp  │               │
│  │  (this)    │                          │ (raw nREPL)│               │
│  └─────┬──────┘                          └──────┬─────┘               │
│        │                                        │                     │
│        └──── reads ──┬─── Spec 009 trace bus ───┴──── reads ──┐       │
│                      │   + Tool-Pair projections              │       │
│                      │   + Source-coord stamping              │       │
│                      │   + registries (sub/fx/cofx/machine)   │       │
│                      ▼                                        ▼       │
│                  ┌──────────────────────────────────────────┐         │
│                  │       implementation/ (the runtime)      │         │
│                  └──────────────────────────────────────────┘         │
└──────────────────────────────────────────────────────────────────────┘
```

**Causa and pair2-mcp are SIBLINGS.** Both read the same
instrumentation; neither owns a registry; neither writes to app-db.
The split is intentional and load-bearing:

- **Causa = human surface.** Visual chrome, keyboard-first power-user
  UX, painted UI, virtualisation, animation. The five-canonical-questions
  answer themselves on first paint.
- **pair2-mcp = AI surface.** Raw nREPL over MCP. Whatever the agent
  wants to do, it does by evaluating Clojure against the runtime — no
  curated AI-shaped facade in front.

This kills the false middle: a curated *Causa-MCP* would be a tax on
both surfaces (it duplicates pair2-mcp's responsibility and degrades
Causa's freedom to evolve its UI without breaking an AI contract).
Drop it.

**Story (`tools/story/`)** — the component playground — embeds Causa's
Event tab as a per-variant observability ribbon (per the embedding
contract in [`008-Embedding-Contract.md`](008-Embedding-Contract.md)).

The dependency arrows: Causa → `implementation/machines/` (directly;
no `tools/machines-viz/` hop). Story → Causa → `implementation/`. No
cycles, no shared registries, no parent/child relationships among the
tools.

## Status

Pre-alpha. Substantial rewrite from the legacy 16-panel sidebar to the
4-layer chrome (see [`018-Event-Spine.md`](018-Event-Spine.md)). All
direction-setting decisions are locked — see
[`DESIGN-RATIONALE.md`](DESIGN-RATIONALE.md). Implementation work is
sequenced after spec ratification; the bead matrix carries the
sequencing.
