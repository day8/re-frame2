---
name: re-frame2-xray
description: >
 Read-only tour of **Xray** — the re-frame2 devtools panel. Use when the
 user wants to know how to *launch* Xray (in-app inline panel, the
 overlay fallback for hosts with no layout column / full-screen-canvas /
 no `[data-rf-xray-host]` — `open-overlay!`, pop-out window, programmatic
 mount, or the wired hotkeys), which of its two
 modes (Dynamic event-spine / Static registry browse) and which tab
 surfaces the data they're looking for, or what each tab is *for*.
 Trigger phrases: "open Xray", "where is X in Xray",
 "which Xray panel shows…", "Xray Static mode", "browse registered
 machines/routes/schemas in Xray", "Ctrl+Shift+C", "Xray hotkey",
 "Xray mode toggle", "Xray popout", "Xray overlay",
 "Xray open-overlay!", "open Xray with no layout host / no
 [data-rf-xray-host] / full-screen canvas", "Xray machine inspector",
 "Xray epoch cascade", "where do Xray issues show up", "Xray Graph
 tab", "Xray derivation/process graph", "where does this value come
 from in Xray", "Xray Resources tab", "Xray Modules tab", "Xray
 module-view", "what images/frames are installed in Xray", "which image
 loaded this frame", "how does this frame resolve its registrations",
 and similar.
 **Do not use** for: driving Xray
 programmatically from a live REPL (that's `re-frame2-pair`), authoring
 the host app (`re-frame2`), bootstrapping a new project
 (`re-frame2-setup`), or implementing Xray itself (no implementor skill
 exists).
allowed-tools:
 - Read
 - Grep
 - Glob
---

# re-frame2-xray

A tour skill for **Xray** — the re-frame2 in-app devtools panel.

## Mental model: think in Redux DevTools, map onto Xray

If you know **Redux DevTools** (and **React DevTools Profiler** for the
re-render-cause surfaces), you know 80% of Xray: same genus — a
state-debugging devtools panel with time-travel and an inspectable log of
state changes — applied to re-frame2's event pipeline runs. Anchor on that,
then note the deliberate divergences.

| Redux DevTools concept | Xray counterpart | Deliberate divergence |
|---|---|---|
| Action log (the left-rail list of dispatched actions) | The **L2 event spine** — one row per dispatched event, live-tailing | Each row is an *epoch* (a full six-domino cascade), not a single reducer call — far richer than an action entry |
| Inspecting one action's state diff | The **Epoch** tab (hero) — the focused dispatch's numbered cascade, DISPATCH → COEFFECT(s) → EVENT HANDLER → FLOW(s) → EFFECT HANDLERS → SUBSCRIPTIONS → VIEWS | Redux shows action + state diff; the Epoch cascade shows the *whole causal chain* (cofx, interceptors, fx, flows, sub recompute, re-render), not just before/after state |
| Time-travel / "jump to state" replay | The **inspect · `Reset`-rewind** chrome | **Passive by default** — picking an epoch rebases the panels but does NOT move the live frame; live rewind is the explicit `Reset` button (see chrome.md §Time-travel). Redux's slider *replays dispatches* into the store; Xray inverts that. |
| State tree inspector | The **app-db** tab — sectioned, lazy-tree, inline diff annotations | Sectioned by reserved area (machines, routes, system-ids…) with downstream-subs hover, not a raw single tree |
| React DevTools Profiler "why did this render?" | The **Views** tab — render-cause chips (`← :sub-id` vs `← props`) on every re-render leaf | Built into the same panel and tied to the epoch, not a separate profiler tab |
| *(no Redux equivalent)* | **Static mode** — event-INDEPENDENT browse of what's *registered* (machines / routes / schemas / flows / interceptors) | Redux has no "what's registered?" surface; this is the registry-catalogue half Xray adds |

Xray is also the structural successor to **re-frame-10x** (re-frame2's
v1-internal predecessor) and references it nowhere — matters mainly to
v1-migrating users. The lineage fact + surface-by-surface "what replaces
what" lives once in
[`../shared/tool-pair-surfaces.md` §Supersedes re-frame-10x](../shared/tool-pair-surfaces.md#supersedes-re-frame-10x);
cite it, don't restate.

This skill answers three questions, and only three:

1. **How do I launch Xray?** — inline panel, pop-out, programmatic entry
 points, wired hotkeys, the Dynamic ↔ Static mode toggle.
2. **Which tab shows X?** — a one-line purpose per tab across both modes
 (9 Dynamic event-spine + 5 Static registry-browse).
3. **What's the chrome around the tabs for?** — time-travel inspect /
 `Reset`-rewind, the filter pills, the command palette, the Settings popup.

Deep workflow recipes (find-wrong-sub, redaction-marker grammar,
click-to-source / open-in-editor internals) are **out of scope** —
see *Out of scope* below.

### Which reference leaf to load

This body is a **compact tour/router** (launch quick-reference, mode/tab
chooser, chrome one-liners). Maintained detail lives in the reference
leaves — load the matching one when the question needs more than the
router gives:

| The question is about… | Load |
|---|---|
| Launch in depth — preload vs `init!`, the `[data-rf-xray-host]` contract, suppress-auto-open, CSS-variable / drag-handle resize, the **missing-host diagnostic + recovery** (`status()`), the `open-overlay!` fallback, pop-out lifecycle, the full wired-hotkey contract | [`references/launch-modes.md`](references/launch-modes.md) |
| A tab in depth — per-tab layout, iconography, stripe tokens, the Epoch cascade steps, "open it when…" guidance | [`references/panels.md`](references/panels.md) |
| First-screen chrome in depth — the **L1 frame picker**, Settings-popup tabs, command-palette sources, the rewind detail, the Snapshot redaction contract | [`references/chrome.md`](references/chrome.md) |
| The components every L4 panel reuses + the glyph/icon reference | [`references/shared-components.md`](references/shared-components.md) |
| The re-frame-10x lineage / surface-by-surface "what replaces what" | [`../shared/tool-pair-surfaces.md`](../shared/tool-pair-surfaces.md#supersedes-re-frame-10x) |

---

## What Xray is

An in-app true-inline devtools panel for re-frame2 applications, preloaded
into dev builds via shadow-cljs `:preloads`. The host app provides a
right-side `[data-rf-xray-host]` column in its normal layout; Xray
auto-opens there once the substrate adapter is ready. Production builds
elide the entire surface through the universal `interop/debug-enabled?`
gate — zero bytes ship to consumers.

Xray consumes re-frame2's instrumentation surface (Spec 009 trace bus,
Tool-Pair epoch history, the registrar query API) — adding nothing the
framework didn't already expose. The tabs are *presentation* of an
already-structured runtime.

### Two modes

Xray runs in one of two modes at a time, flipped by the L1 mode pill or
the `Cmd/Ctrl+Shift+M` chord:

- **Dynamic** — the event-coupled spine (4-layer chrome: L1 ribbon · L2
 event list · L3 tab bar · L4 detail). Every tab is a *lens on the one
 focused event* — pick an event in the L2 list and every tab rebinds.
 "What happened in **this** epoch?". 9 tabs (core 6 + cross-feature
 Resources + Graph + Modules).
- **Static** — event-INDEPENDENT browse of what's *registered* (3-layer
 chrome, no L2 spine). Every tab is a registry catalogue **for the picked
 frame** — a frame draws its registrations from its resolved image (the
 EP-0023 `image -> frame` model). "What exists?", not "what just
 happened?". 5 tabs.

Inspect a *single dispatch* → Dynamic; browse the *whole registry* →
Static. For an AI agent surface against the running app, use
`tools/re-frame2-pair-mcp/` — Xray is the human-facing panel;
re-frame2-pair-mcp is the AI-facing one.

---

## Launching Xray — pick a mode

Four launch surfaces ship today: one mount facade with three open verbs
(inline / overlay / window) plus the programmatic `init!`. Pick the one
that matches the user's situation.

| User wants to … | Use | How |
|---|---|---|
| Inspect the runtime while developing locally | **Default true-inline panel** | Add the preload + a `[data-rf-xray-host]` column in the app layout. Xray auto-opens on page load. |
| Mount where the host can't give Xray a layout column (full-screen canvas, story-only / prototype host, no `[data-rf-xray-host]`) | **Overlay (fallback)** | `(xray/open-overlay!)` from CLJS, or `window.day8.re_frame2_xray.open_overlay_BANG_()` from devtools. Floats the shell above the host under `document.body` — no layout column needed. The supported fallback for hosts that can't accommodate a right column; **not** the default path (per `spec/011-Launch-Modes.md` + `spec/API.md` §Mount facade). |
| Put Xray on a second monitor with the app full-screen | **Pop-out window** | Click the visible **`⛶` pop-out button** in the panel top-bar's right-icons cluster (the canonical chrome path — it dispatches `:rf.xray/popout-shell`). Or, as the secondary programmatic/devtools path, `(xray/popout!)` from CLJS / `window.day8.re_frame2_xray.popout_BANG_()` (call it — note the parens) from a console. |
| Install Xray from code (no preload, or alternative wiring) | **Programmatic `init!`** + a mount verb | Call `(xray/init! opts)` after `rf/init!` to install the foundation + apply config (it does **not** open a panel), then `(xray/open!)` / `(xray/open-overlay!)` / `(xray/popout!)` to make it visible. Idempotent. |
| Browse what's *registered* instead of one dispatch | **Static mode** | Flip the L1 mode pill or press `Cmd/Ctrl+Shift+M`. Static drops the event spine and shows the 5 registry-browse tabs. |
| Have an AI agent inspect the runtime | **re-frame2-pair-mcp** | Configure `tools/re-frame2-pair-mcp/` in the agent host — the raw nREPL pair-programming companion is the AI access path. Out of scope for this skill — see [`tools/re-frame2-pair-mcp/`](../../tools/re-frame2-pair-mcp). |
| Debug a mobile browser | Not supported | Per `spec/011-Launch-Modes.md` §What this doesn't do — phones refuse to mount. |

**Most common launch failure — "the panel never appeared."** Preload in,
page loaded, but no inline panel = a **missing layout host**: no element
matched `[data-rf-xray-host]` when the adapter became ready. Xray fails
loudly but safely — it logs a `console.error` and exposes the same
diagnostic at **`window.day8.re_frame2_xray.status()`**. First response:
check the console / call `status()`. The three recoveries (add the column ·
point the selector · fall back to `open-overlay!`), the full `status()` map
shape, and the distinct dev-build posture banner are in
[`references/launch-modes.md` §Missing host](references/launch-modes.md).

For the decision tree in depth (preload vs `init!`, suppress-auto-open,
the `:rf.xray/layout-host-selector` knob, host-CSS-variable resize, the
`open-overlay!` fallback, pop-out lifecycle), see
[`references/launch-modes.md`](references/launch-modes.md).

### Wired hotkeys

Four hotkey families have keydown listeners installed in `keybinding.cljs`
today (three global, one focus-gated). Quick-reference below; full per-key
contract + suppression knob in
[`references/launch-modes.md` §Wired hotkeys](references/launch-modes.md#wired-hotkeys).
Spec [`007-UX-IA.md` §Keyboard](../../tools/xray/spec/007-UX-IA.md#keyboard)
catalogues a richer map; these are what is actually wired:

| Key | Scope | Action |
|---|---|---|
| `Ctrl+Shift+C` | global | Toggle the Xray shell (`Ctrl+Shift` avoids Safari's `Cmd+Shift+C` Inspect collision). |
| `Cmd/Ctrl+Shift+M` | global | Toggle mode — Dynamic ↔ Static (`:rf.xray/toggle-mode`). |
| `Cmd/Ctrl+K` | global | Open the command palette (`:rf.xray/palette-toggle`); opens the shell first if hidden. |
| `Space` `L` `j` `k` `G` `,`/`s` | focus-gated | Spine + chrome shortcuts — fire only when the shell is visible and focused (non-editable, non-modal). Space = pause/resume LIVE · `L` = snap to LIVE · `j`/`k` = step focused event · `G` = fast-forward to head · `,`/`s` = Settings popup. |

`Cmd/Ctrl+K` **is** wired — don't tell users the K-binding is unavailable.
`Esc` is modal-local,
not a wired spine key. The **pop-out has no hotkey** — its canonical path is
the visible **`⛶` pop-out button** (above), with `(xray/popout!)` as the
secondary programmatic path. Source of truth:
[`keybinding.cljs`](../../tools/xray/src/day8/re_frame2_xray/keybinding.cljs).

---

## The tabs — what each surfaces

<a id="the-tabs--what-each-surfaces"></a>

Tabs split across the two modes. On "where is X?", first decide *which
mode answers it* — Dynamic (one dispatch) or Static (the whole registry)
— then route to the tab. For per-tab layout, iconography, stripe tokens,
and "open it when…" depth see [`references/panels.md`](references/panels.md).

### Dynamic mode — 9 lenses on the focused event

The L3 tab bar holds **9 lenses on the focused event**, left-to-right
(mnemonics `e a v t m r s g u`): **Epoch · app-db · Views · Trace ·
Machine · Routes · Resources · Graph · Modules** — the core 6 spine lenses
(spec/018 §5 + spec/021 §9.1) plus the cross-feature Resources / Graph /
Modules. Every tab answers "what happened in **this** epoch?"; cross-epoch
signal lives on the L2 timeline (badges + stripes). There is **no Issues
tab** (see *Where issues surface now* below).

Each row below is the one-line purpose + when-to-open; **depth for every
tab lives in [`references/panels.md`](references/panels.md)** under the
matching § heading.

| Tab | Mnem · Icon · Stripe | One-line purpose | When you'd open it |
|---|---|---|---|
| **Epoch** *(hero, default landing · `:order -1`)* | `e` · `⚡` · violet | The focused dispatch's full computational timeline as a **numbered vertical cascade** (rendered step labels per `epoch/badge.cljc`): DISPATCH → RECORDABLE COEFFECTS (conditional) → COEFFECT(s) → INTERCEPTORS (authored chain, conditional) → INTERCEPTOR (exception-only, phase-split around the handler) → EVENT HANDLER → FLOW(s) → EFFECT HANDLERS → SUBSCRIPTIONS → VIEWS. Only present steps render; each carries a ✓ / ✗ / ⊘ glyph; exceptions render under their step. Depth: panels.md §Epoch. | Default landing. "What did this event do?" / "What fx fired?" / "Where did the cascade fail?" / "Did the flow recompute?" |
| **app-db** | `a` · `◐` · cyan | State sectioned by reserved area (APP STATE + per-machine + per-spawned + ROUTE + SYSTEM-IDS + …), each a collapsible lazy-tree with **inline** diff (`← was X`); hover a changed path for the downstream-subs popover. (Display label lowercase **app-db**; tab id `:app-db`.) Depth: panels.md §app-db. | "What just changed in app-db?" / "What's downstream of `[:cart :items]`?" |
| **Views** | `v` · `◉` · cyan | The reactive cascade (SUBSCRIPTIONS + VIEWS) as a depth-first DAG with **render-cause chips** on every re-render leaf — `← :sub-id` (a deref'd sub changed) vs `← props` (the props channel); a first mount carries no cause. (Tab id `:views`.) Depth: panels.md §Views. | "Why didn't my view update?" / "Why did this re-render — sub or props?" / "Which views re-rendered this epoch?" |
| **Trace** | `t` · `⬢` · orange | Raw Spec 009 trace events for the focused epoch — a **flat oldest-first row list**, each row carrying a **stage column** (DISPATCH·COEFFECT·EVENT HANDLER·FLOW·EFFECT HANDLERS·SUBSCRIPTIONS·VIEWS) + a colour-coded left edge reusing the Epoch badge taxonomy; the focused epoch IS the scope, click a row to expand. Depth: panels.md §Trace. | "Show me every raw op in this epoch." / "Is `:rf.fx/*` firing as expected?" |
| **Machine** | `m` · `◆` · green | **Event-driven.** Per-machine topology + transition highlight + guards / actions / cancellation for the focused event; BLANK when the event had no machine activity. To browse a machine's **full topology cold** (picker + zoom / pan / fit), use **Static mode**'s Machines tab. (Singular **Machine**; tab id `:machines`.) Depth: panels.md §Machine. | "What did this event do to my machines?" / "What transition fired?" / "What guards passed/failed?" |
| **Routes** | `r` · `🌐` · yellow | Focused-event lens: current matched route + params/query/fragment + a **Simulate-URL** input ranking every registered route, with `◉ TO` / `◇ FROM` overlay markers; silent when no routes registered. (Display label **Routes**; tab id `:routing`.) Depth: panels.md §Routes. | "What route am I on?" / "Did the route change this epoch?" / "What params resolved?" |
| **Resources** | `s` · cross-feature | The declarative server-state lens (Spec 016) — static resource registry, per-frame live instances (state · owners · freshness), the in-flight work ledger, the scope-resolution timeline, and the EP-0016 mutation-completion evidence (`:reply-to` continuations · descriptor-level scoped-invalidation). **Read-only**, redaction-aware; decoupled (no `:require` on the resources artefact). Depth: panels.md §Resources. | "Where's my server state, what owns it, is it stale?" / "What's in flight?" / "Did my mutation's `:reply-to` fire and invalidate the right scopes?" |
| **Graph** | `g` · cross-feature (violet) | Xray's UI over the **EP-0014 derivation/process graph** — every declared fact + process across all five families (subscriptions, flows, resources, routes, machines) as one node-and-edge graph over the frame fold. A per-panel **Declared ↔ Realized** projection toggle (a Graph-local control, **NOT** the L1 Dynamic/Static mode pill — Graph is always a Dynamic tab) flips registration-derived vs observed. Depth (incl. the authority-axis + internal-accessor caveats): panels.md §Graph. | "Where does this value come from / when is it evaluated / where does it live / who owns it?" — across families |
| **Modules** *(tab-bar label **Frames** · `:order 9`)* | `u` · cross-feature | Xray's UI over the EP-0023 **`image -> frame`** model — the **FRAMES** view: each live frame as an execution context carrying its resolved image (`[kind id]` descriptors) + how it resolves `(kind id)` lookups. L4-only registry tab (focusable, **no** standalone `mount-*!` facade — there is no `mount-module-view!`). Depth: panels.md §Modules. | "What frames exist, and which image loaded each?" / "How does this frame resolve its registrations?" |

#### Where issues surface now (no Issues tab)

**No Issues tab, no session-wide triage list.** Issues surface through
three always-on inline channels: **this epoch** → Epoch-cascade per-step
✓/✗ + the shared "Exception Thrown" card; **which epochs** → L2 event-row
pink-wash; **cross-epoch watcher** → the `:rf.xray/issues-ribbon` signal
(auto-open-on-error). Detail: [`references/panels.md` §Issues](references/panels.md).
(A11y is **not** a Xray tab — it is Story's domain, `re-frame.story.ui.chrome-a11y`.)

### Static mode — 5 registry-browse tabs

**5 catalogue lenses** over what's *registered* in the picked frame
(mnemonics mode-scoped — `m` here opens the Static Machines browse, not the
Dynamic instance-inspector). Order per spec/007-UX-IA.md §Static mode:
**Machines · Routes · Schemas · Flows · Interceptors**.

> **The browse axis is `image -> frame`, full stop** — no realm / app /
> module browse dimension; image assembly + frame isolation are the whole
> composition story (EP-0023, `tools/xray/spec/007-UX-IA.md` §Static mode).

| Tab | Mnem | One-line purpose (+ the question it answers) |
|---|---|---|
| **Machines** *(default)* | `m` | Registry browse of every registered machine + topology + a 4-mode sub-strip (incl. the Sim engine) — "browse my checkout machine's chart without picking an event." |
| **Routes** | `r` | Every registered route + a Simulate-URL input (promoted from the Dynamic Routes lens) — "which route would `/orders/42` match?" |
| **Schemas** | `c` | Every registered schema + sample data + jump-to-source — "show me the shape of `:order/schema`." |
| **Flows** | `f` | Catalogue of every registered flow. |
| **Interceptors** | `i` | Pure-browse lens over the registered interceptor chains — "what runs, and in what order?" |

When a user asks "where do I see **all** my registered machines / routes /
schemas / flows / interceptors?" the answer is **Static mode** — Dynamic
tabs only ever show the focused event.

### Where familiar panels' content lives (these are not separate tabs)

Subscriptions, Effects, Flows, Performance, Schemas, Hydration, and Issues
are **not** separate Dynamic tabs. The four high-drift routes worth keeping
in the body:

- **Schemas** (violations) → **Epoch** inline + the L2 pink-wash; the
  *registry* catalogue → **Static → Schemas**.
- **Hydration** (SSR mismatches) → **Epoch** inline + the issues-ribbon
  signal — there is **no** standalone Hydration tab.
- **Flows** → **Epoch** FLOW step; the registry → **Static → Flows**.
- **Effects** (`fx`) → **Epoch** EFFECT HANDLERS step + **Trace** raw ops.

For the full panel → content-home mapping (Subscriptions,
Performance, the rest), see
[`references/panels.md` §What's deliberately NOT here](references/panels.md#whats-deliberately-not-here)
(the single maintained home; + spec/021 §15). The hero on first open is
**Epoch** (`:order -1`).

---

## The chrome around the tabs

<a id="the-chrome-around-the-tabs"></a>

Beyond the tabs, the first screen carries navigation primitives the user
meets immediately — one line each below. **Load
[`references/chrome.md`](references/chrome.md) for the control-by-control
inventory** (Settings-popup tabs, command-palette sources, the Snapshot
redaction contract, the rewind detail) when the question needs more than
the one-liner.

- **L1 frame picker.** The Frame control on the L1 ribbon chooses *which
 frame* Xray observes — every tab and the spine rebind to it
 (mode-independent: it scopes both Dynamic and Static). It **always
 renders**; on a single-frame app it is a working one-entry dropdown (only a
 zero-frame state disables it). The button face shows the currently-selected
 frame id live (e.g. `:rf/default ▾`). The pin is a **transient view
 scope** — not persisted across reload (resets to the head-frame default).
 Tool frames (`:rf/xray` itself, `:rf/re-frame2-pair`) are **filtered out of
 the picker unconditionally** — invariant **I1**: Xray observes ANOTHER
 frame, never itself; they cannot currently be revealed from the UI. So "I
 can't find frame X in the picker" → it's a tool frame. (chrome.md §L1 frame
 picker.)
- **LIVE vs RETRO spine.** The L2 spine live-tails at the head (**LIVE**)
 until you pick a historical event or pause (**RETRO**); `Space`
 pauses/resumes, `L` snaps back. (chrome.md §LIVE vs RETRO spine.)
- **Time-travel: passive inspect vs explicit rewind.** Picking an epoch is
 *passive INSPECTION* — panels rebase, the live frame does NOT move; live
 rewind is the *separate, explicit* **`Reset` button** on the L3 ribbon
 (`restore-epoch!` reinstalls the focused epoch's WHOLE frame-state — both
 app-db AND runtime-db — via `replace-frame-state!`, not the `:db-after`
 projection alone). No wired `r`/`R`/`*` keys — those are spec-future only.
 (chrome.md §Time-travel.)
- **Filter pills.** The L1.5 events ribbon carries IN / OUT pills, a mute
 set, an `N hidden by filters` count, and `Clear Filters`; transient,
 reset on load. (chrome.md §Filter pills.)
- **Command palette (`Cmd/Ctrl+K`).** A fuzzy-ranked surface over six
 source kinds (panel jumps · recent events · frame switch · registered
 handlers · settings · command verbs), mode-aware. (chrome.md §Command
 palette — for the full command-verb list.)
- **Settings popup (`,` / `s`).** A 4-tab modal (General · Keybindings ·
 Buffer · Diff). **Density and panel-width are NOT popup controls** —
 density is a boot/`configure!` concern; width is the drag handle. Merge
 order is `defaults < configure! < Settings`. (chrome.md §Settings popup —
 for the per-tab control inventory + the layered-config story.)
- **Snapshot app-db (the on-box share helper).** The on-box share helper is
 the **Snapshot app-db**
 palette verb — and it is **redacted by default** (sensitive ⇒
 `:rf/redacted`, large ⇒ `:rf.size/large-elided`, via
 `runtime/egress-value`). There is no Share-URL or EDN-export affordance; do
 **not** present Snapshot app-db as a raw-`app-db` /
 secret-egress path. (chrome.md §Snapshot app-db — for the egress contract.)

---

## Out of scope

For the following, this skill does not have the answer — point at the spec
doc or pair-tool surface, don't improvise.

- **Deep workflow recipes** (find-wrong-sub walker, redaction-marker
 grammar, click-to-source / "open in editor" internals, pop-out lifecycle
 gotchas, branch-and-explore). Source of truth:
 [`tools/xray/spec/007-UX-IA.md`](../../tools/xray/spec/007-UX-IA.md)
 and the per-panel specs (`tools/xray/spec/00N-*.md`) — the spec is the
 answer.
 (First-screen chrome — time-travel inspect / `Reset`-rewind, filter
 pills, the command palette, Settings — is **in scope**: §The chrome
 around the tabs above is the router; load
 [`references/chrome.md`](references/chrome.md) for the detail.)
- **Driving Xray programmatically** (hot-swap a sub via REPL, time-
 travel from CLJS, dispatch into the runtime from a tool). Route to
 the [`re-frame2-pair`](../re-frame2-pair/SKILL.md) skill — Xray
 owns the *seeing*; re-frame2-pair owns the *driving*.
- **Implementing Xray** (panel-facade/leaf split, mount lifecycle
 internals, `frame-provider {:frame :rf/xray}` isolation, the epoch pump's contract).
 Source of truth:
 [`tools/xray/spec/011-Launch-Modes.md` §Mount lifecycle](../../tools/xray/spec/011-Launch-Modes.md)
 and the per-panel implementation specs. There is no Xray-implementor
 skill — the spec is the answer.
- **Deep derivation-graph workflow recipes** (reading large cross-family
 graphs, the Declared↔Realized diffing workflow, the off-box egress
 grammar). The **Graph** Dynamic tab itself is **in scope** (§The tabs is
 the router); deferred here is the how-to-debug-with-it recipe layer. Cite
 [`spec/Derivations.md`](../../spec/Derivations.md) +
 [`docs/EP/EP-0014-derivation-and-process-algebra.md`](../../docs/EP/EP-0014-derivation-and-process-algebra.md)
 for the algebra contract.
---

## Style guidance

- **Cite the spec, don't paraphrase it.** When a user asks for normative
 detail (the mount contract, the epoch pump's ordering guarantees, the
 redaction marker's grammar), link to the relevant
 `tools/xray/spec/*.md` and quote sparingly.
- **Pre-alpha hedge.** Some surfaces are partial: the Machine tab renders
 through the shared machines-viz **MachineChart** via
 `panels/machine_canvas.cljs` (still stabilising); the inline issue surfaces
 only populate the schema / hydration rows when the host wired those
 features; the Static Machines Sim engine is still stabilising. The Static
 catalogues themselves (Machines / Routes / Schemas / Flows / Interceptors)
 are full registry browsers, not stubs. When a user asks about an
 in-progress surface, say so and point at the spec.
- **Don't invent hotkeys.** Only the four families in §Wired hotkeys (above)
 are wired; everything else in
 [`spec/007-UX-IA.md` §Keyboard](../../tools/xray/spec/007-UX-IA.md#keyboard)
 is normative-future, not live. The full per-key contract + guardrails (no
 wired `r`/`R`/`*`, `Esc` modal-local, `Cmd/Ctrl+K` **is** wired) live in
 [`references/launch-modes.md` §Wired hotkeys](references/launch-modes.md#wired-hotkeys);
 cite `keybinding.cljs` when in doubt.
- **Route, don't blur.** If the user wants to drive Xray, point at
 `re-frame2-pair`; if they want to implement it, point at the spec
 and note that no implementor skill exists yet. This skill is a tour.

---

*For the full skill-disambiguation matrix (when to use which skill) see
[`skills/README.md` §Skill routing — single source](../README.md#skill-routing--single-source).*
