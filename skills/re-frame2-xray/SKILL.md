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
 "Xray epoch cascade", "where do Xray issues show up", and similar.
 **Do not use** for: driving Xray
 programmatically from a live REPL (that's `re-frame2-pair`), authoring
 the host app (`re-frame2`), bootstrapping a new project
 (`re-frame2-setup`), or implementing Xray itself (no skill yet — the
 `xray-implementor` sibling is deferred to post-alpha). This skill cites
 `tools/xray/spec/*` as the source of truth; where a spec doc has
 an open question, hedge with "see spec/0NN" rather than freezing
 prose.
allowed-tools:
 - Read
 - Grep
 - Glob
---

# re-frame2-xray

A tour skill for **Xray** — the re-frame2 in-app devtools panel. Every
dispatch is a node in a graph of causes; every state delta is a slice you
can scrub; every machine transition lands on a chart; every schema
violation surfaces as an issue you cannot miss.

## Mental model: think in Redux DevTools, map onto Xray

If you know **Redux DevTools** (and **React DevTools Profiler** for the
re-render-cause surfaces), you already know 80% of Xray: it's the same
genus — a state-debugging devtools panel with time-travel and an
inspectable log of state changes — applied to re-frame2's event cascades.
Anchor on that, then note the deliberate divergences.

| Redux DevTools concept | Xray counterpart | Deliberate divergence |
|---|---|---|
| Action log (the left-rail list of dispatched actions) | The **L2 event spine** — one row per dispatched event, live-tailing | Each row is an *epoch* (a full six-domino cascade), not a single reducer call — far richer than an action entry |
| Inspecting one action's state diff | The **Epoch** tab (hero) — the focused dispatch's numbered cascade, DISPATCH → COEFFECTS → HANDLER → FLOWS → SIDE EFFECTS → SUBSCRIPTIONS → VIEWS | Redux shows action + state diff; the Epoch cascade shows the *whole causal chain* (cofx, interceptors, fx, flows, sub recompute, re-render), not just before/after state |
| Time-travel / "jump to state" replay | The **inspect · `Reset`-rewind** chrome | **Passive by default** — picking an epoch rebases the panels but does NOT move `app-db`; moving the live app is the explicit `Reset` button (`restore-epoch` to the focused epoch's `:db-after`). Redux's slider *replays dispatches* into the store; Xray inverts that. |
| State tree inspector | The **app-db** tab — sectioned, lazy-tree, inline diff annotations | Sectioned by reserved area (machines, routes, system-ids…) with downstream-subs hover, not a raw single tree |
| React DevTools Profiler "why did this render?" | The **Views** tab — render-cause chips (`← :sub-id` vs `← props`) on every re-render leaf | Built into the same panel and tied to the epoch, not a separate profiler tab |
| *(no Redux equivalent)* | **Static mode** — event-INDEPENDENT browse of what's *registered* (machines / routes / schemas / flows / interceptors) | Redux has no "what's registered?" surface; this is the registry-catalogue half Xray adds |

Xray is also the structural successor to **re-frame-10x** — re-frame2's
v1-internal predecessor — and depends on or references neither. The single
home for that fact (and the surface-by-surface "what replaces what") is
[`../shared/tool-pair-surfaces.md` §Supersedes re-frame-10x](../shared/tool-pair-surfaces.md#supersedes-re-frame-10x);
cite it rather than restating the lineage here. It matters mainly to
v1-migrating users.

This skill answers three questions, and only three:

1. **How do I launch Xray?** — the inline panel, the pop-out, the
 programmatic entry points, the wired hotkeys, the Dynamic ↔ Static
 mode toggle.
2. **Which tab shows X?** — a one-line purpose for each tab Xray ships,
 across both modes: the 6 Dynamic event-spine tabs (per spec/018 §5 +
 spec/021 §9.1) and the 5 Static registry-browse tabs (per
 spec/007-UX-IA.md §Static mode).
3. **What's the chrome around the tabs for?** — the first-screen
 navigation primitives the user meets immediately: the time-travel
 inspect / `Reset`-rewind, the filter-pill cluster, the command
 palette, and the Settings popup.

Deep workflow recipes (find-wrong-sub, redaction-marker grammar,
click-to-source / open-in-editor internals) are **out of scope** in this
iteration — see the *Out of scope* section below for what to do when one
comes up.

### Which reference leaf to load

This body is a **compact tour/router**: it carries the launch
quick-reference, the mode/tab chooser, and the chrome one-liners. The
maintained detail lives in the reference leaves — load the matching one
when the question needs more than the router gives:

| The question is about… | Load |
|---|---|
| Launch in depth — preload vs `init!`, the `[data-rf-xray-host]` contract, suppress-auto-open, CSS-variable / drag-handle resize, the `open-overlay!` fallback, pop-out lifecycle, the full wired-hotkey contract | [`references/launch-modes.md`](references/launch-modes.md) |
| A tab in depth — per-tab layout, iconography, stripe tokens, the Epoch cascade steps, "open it when…" guidance | [`references/panels.md`](references/panels.md) |
| First-screen chrome in depth — Settings-popup tabs, command-palette sources, the rewind detail, the Snapshot redaction contract | [`references/chrome.md`](references/chrome.md) |
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
Tool-Pair epoch history, the registrar query API) — it adds nothing the
framework didn't already expose. The tabs are *presentation* of an
already-structured runtime.

### Two modes

Xray runs in one of two modes at a time, flipped by the L1 mode pill or
the `Cmd/Ctrl+Shift+M` chord:

- **Dynamic** — the event-coupled spine. A 4-layer chrome (L1 ribbon ·
 L2 event list · L3 tab bar · L4 detail). Every tab is a *lens on the
 one focused event* — pick an event in the L2 list and every tab
 rebinds. This is "what happened in **this** epoch?". 6 tabs.
- **Static** — event-INDEPENDENT browse of what's *registered*. A
 3-layer chrome (no L2 spine — Static has no event focus). Every tab is
 a registry catalogue: every machine, every route, every schema, every
 flow, every interceptor known to the picked frame. This is "what
 exists?", not "what just happened?". 5 tabs.

Same design language, different temperature (per spec/007-UX-IA.md
§Static mode). When the user wants to inspect a *single dispatch*, that's
Dynamic; when they want to browse the *whole registry*, that's Static.

For an AI agent surface against the running app, use `tools/re-frame2-pair-mcp/`
— the raw nREPL pair-programming companion. Xray is the human-facing
panel; re-frame2-pair-mcp is the AI-facing surface.

---

## Launching Xray — pick a mode

Four launch surfaces ship today (one mount facade with three open verbs
— inline / overlay / window — plus the programmatic `init!`). Pick the
one that matches the user's situation.

| User wants to … | Use | How |
|---|---|---|
| Inspect the runtime while developing locally | **Default true-inline panel** | Add the preload + a `[data-rf-xray-host]` column in the app layout. Xray auto-opens on page load. |
| Mount where the host can't give Xray a layout column (full-screen canvas, story-only / prototype host, no `[data-rf-xray-host]`) | **Overlay (fallback)** | `(xray/open-overlay!)` from CLJS, or `window.day8.re_frame2_xray.open_overlay_BANG_()` from devtools. Floats the shell above the host under `document.body` — no layout column needed. The supported fallback for hosts that can't accommodate a right column; **not** the default path (per `spec/011-Launch-Modes.md` + `spec/API.md` §Mount facade). |
| Put Xray on a second monitor with the app full-screen | **Pop-out window** | Click the visible **`⛶` pop-out button** in the panel top-bar's right-icons cluster (the canonical chrome path — it dispatches `:rf.xray/popout-shell`). Or, as the secondary programmatic/devtools path, `(xray/popout!)` from CLJS / `window.day8.re_frame2_xray.popout_BANG_()` (call it — note the parens) from a console. |
| Install Xray from code (no preload, or alternative wiring) | **Programmatic `init!`** + a mount verb | Call `(xray/init! opts)` after `rf/init!` to install the foundation + apply config (it does **not** open a panel), then `(xray/open!)` / `(xray/open-overlay!)` / `(xray/popout!)` to make it visible. Idempotent. |
| Browse what's *registered* instead of one dispatch | **Static mode** | Flip the L1 mode pill or press `Cmd/Ctrl+Shift+M`. Static drops the event spine and shows the 5 registry-browse tabs. |
| Have an AI agent inspect the runtime | **re-frame2-pair-mcp** | Configure `tools/re-frame2-pair-mcp/` in the agent host — the raw nREPL pair-programming companion is the AI access path. Out of scope for this skill — see [`tools/re-frame2-pair-mcp/`](../../tools/re-frame2-pair-mcp/). |
| Debug a mobile browser | Not supported | Per `spec/011-Launch-Modes.md` §What this doesn't do — phones refuse to mount. |

For the decision tree in depth (preload vs `init!`, suppress-auto-open
on tool-only pages, the `:rf.xray/layout-host-selector` knob, host-CSS-variable
resize, the `open-overlay!` no-layout-host fallback, pop-out lifecycle),
see [`references/launch-modes.md`](references/launch-modes.md).

### Wired hotkeys

Four hotkey families have keydown listeners installed in `keybinding.cljs`
today (three global, one focus-gated) — this is the quick-reference; the
full per-key contract + suppression knob lives in
[`references/launch-modes.md` §Wired hotkeys](references/launch-modes.md#wired-hotkeys).
Spec [`007-UX-IA.md` §Keyboard](../../tools/xray/spec/007-UX-IA.md#keyboard)
catalogues a richer map; these are what is actually wired:

| Key | Scope | Action |
|---|---|---|
| `Ctrl+Shift+C` | global | Toggle the Xray shell (`Ctrl+Shift` avoids Safari's `Cmd+Shift+C` Inspect collision). |
| `Cmd/Ctrl+Shift+M` | global | Toggle mode — Dynamic ↔ Static (`:rf.xray/toggle-mode`). |
| `Cmd/Ctrl+K` | global | Open the command palette (`:rf.xray/palette-toggle`); opens the shell first if hidden. |
| `Space` `L` `j` `k` `G` `,`/`s` | focus-gated | Spine + chrome shortcuts — fire only when the shell is visible and focused (non-editable, non-modal). Space = pause/resume LIVE · `L` = snap to LIVE · `j`/`k` = step focused event · `G` = fast-forward to head · `,`/`s` = Settings popup. |

`Cmd/Ctrl+K` **is** wired — do not say it was struck. `Esc` is modal-local,
not a wired spine key. The **pop-out has no hotkey** — its canonical path is
the visible **`⛶` pop-out button** (above), with `(xray/popout!)` as the
secondary programmatic path. Source of truth:
[`keybinding.cljs`](../../tools/xray/src/day8/re_frame2_xray/keybinding.cljs).

---

## The chrome around the tabs

<a id="the-chrome-around-the-tabs"></a>

Beyond the tabs, the first screen carries navigation primitives the user
meets immediately. One line each below — **load
[`references/chrome.md`](references/chrome.md) for the control-by-control
inventory** (Settings-popup tabs, command-palette sources, the Snapshot
redaction contract, the rewind detail) whenever the question needs more
than the one-liner.

- **LIVE vs RETRO spine.** The L2 spine live-tails at the head (**LIVE**)
 until you pick a historical event or pause (**RETRO**); `Space`
 pauses/resumes, `L` snaps back. (chrome.md §LIVE vs RETRO spine.)
- **Time-travel: passive inspect vs explicit rewind.** Picking an epoch is
 *passive INSPECTION* — panels rebase, `app-db` does NOT move; live rewind
 is the *separate, explicit* **`Reset` button** on the L3 ribbon
 (`restore-epoch` to the focused epoch's `:db-after`). No wired `r`/`R`/`*`
 keys — those are spec-future only. (chrome.md §Time-travel.)
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
- **Snapshot app-db (the on-box share helper).** Share-URL + EDN export were
 removed; the surviving helper is the **Snapshot app-db**
 palette verb — and it is **redacted by default** (sensitive ⇒
 `:rf/redacted`, large ⇒ `:rf.size/large-elided`, via
 `runtime/egress-value`). Do **not** present it as a raw-`app-db` /
 secret-egress path. (chrome.md §Snapshot app-db — for the egress contract.)

---

## The tabs — what each surfaces

<a id="the-tabs--what-each-surfaces"></a>

Xray's tabs split across its two modes. When the user asks "where is X?",
first decide *which mode answers it* — Dynamic (about one dispatch) or
Static (about the whole registry) — then route to the tab. For per-tab
layout, iconography, stripe tokens, and "open it when…" depth see
[`references/panels.md`](references/panels.md).

### Dynamic mode — 6 lenses on the focused event

The L3 tab bar holds **6 lenses on the focused event**, in the order set
by spec/018 §5 + spec/021 §9.1 (mnemonics `e a v t m r`): **Epoch ·
app-db · Views · Trace · Machine · Routes**. Cross-epoch
signal lives on the L2 timeline above (badges + stripes); every tab
answers "what happened in **this** epoch?" through its own lens. To
browse a machine's full topology cold (spine-INDEPENDENT — picker +
zoom / pan / fit, regardless of the focused event), flip to **Static
mode** and open its Machines tab.

There is **no Issues tab** — Mike ruled it out (Option
(c), 2026-05-31; `panels/issues_ribbon.cljs` deleted). Issues now surface
inline (see *Where issues surface now* below).

| Tab | Mnem · Icon · Stripe | One-line purpose | When you'd open it |
|---|---|---|---|
| **Epoch** *(hero, default landing · `:order -1`)* | `e` · `⚡` · violet | The focused dispatch's full computational timeline as a **numbered vertical cascade**: DISPATCH → COEFFECT(s) → INTERCEPTOR (conditional) → HANDLER → FLOW(s) → SIDE EFFECTS → SUBSCRIPTIONS → VIEWS. Only present steps render; each step carries a per-step ✓ / ✗ / ⊘ status glyph; exceptions render UNDER the step where they occurred. Supersedes the retired Event panel. | Default landing view. "What did this event do?" / "What fx fired?" / "Where did the cascade fail?" / "Did the flow recompute?" |
| **app-db** | `a` · `◐` · cyan | Sectioned-by-reserved-area: APP STATE (db minus `:rf/*` reserved keys) + one section per machine + per spawned instance + ROUTE + SYSTEM-IDS + PENDING-NAVIGATION + ELISION; each section is a collapsible lazy-tree inspector widget with diff annotations **inline** (`← was X`). Hover any changed path for the downstream-subs popover. (Display label is lowercase **app-db** to match the library's app-db naming; internal tab id `:app-db`.) | "What just changed in app-db?" / "What's downstream of `[:cart :items]`?" |
| **Views** | `v` · `◉` · cyan | The reactive cascade as a depth-first DAG: subs recomputed (SUBSCRIPTIONS) + views re-rendered (VIEWS) with **render-cause chips** on every re-render leaf — `← :sub-id` when a deref'd sub changed value, `← props` when none of the view's own subs changed (the props channel); a first mount carries no cause. (Display label is **Views** — the all-plural-domain-noun convention, Mike-direction 2026-05-21, after a `Reactive → View → Views` rename chain; the internal tab id stays `:views`.) | "Why didn't my view update?" / "Why did this view re-render?" / "Was it a sub or props?" / "Which views re-rendered this epoch?" |
| **Trace** | `t` · `⬢` · orange | Raw Spec 009 trace events for the focused epoch — a single **flat oldest-first row list** (no bands/envelope), each row carrying a **stage column** (DISPATCH·COEFFECT·HANDLER·FLOW·SIDE EFFECTS·SUBSCRIPTIONS·VIEWS) + a **colour-coded left edge** reusing the Epoch badge taxonomy; the focused epoch IS the scope (no filter chips), click any row to expand its payload inline. | "Show me every raw op in this epoch." / "Is `:rf.fx/*` firing as expected?" |
| **Machine** | `m` · `◆` · green | **Event-driven.** Per-machine topology + transition highlight + guards / actions / cancellation cascade for the focused event. BLANK when the focused event had no machine activity; per-machine prev/next walks the spine. (Display label is singular **Machine** — the focused-epoch lens is on one machine; internal tab id `:machines`.) To browse a machine's full topology cold (picker + zoom / pan / fit, spine-INDEPENDENT), use **Static mode**'s Machines tab. | "What did this event do to my machines?" / "What transition fired?" / "What guards passed/failed?" |
| **Routes** | `r` · `🌐` · yellow | Flat focused-event lens: current matched route + params/query/fragment + a **Simulate-URL** input that ranks every registered route, with per-event glyphs `◉ TO` / `◇ FROM` / `● HERE`. Silent when no routes registered. (Display label **Routes**, plural-noun convention; internal tab id `:routing`.) | "What route am I on?" / "Did the route change this epoch?" / "What params resolved?" |

#### Where issues surface now (no Issues tab)

Mike ruled the dedicated Issues TAB out (Option (c),
2026-05-31): `panels/issues_ribbon.cljs` and its aggregate panel were
deleted; the session-wide triage list was consciously dropped. Issues now
surface through **three** always-on inline channels (per
`panels/issues_ribbon_helpers.cljc`, the surviving `.cljc` algebra):

- **Inline in the Epoch cascade** — per-step ✓ / ✗ status glyphs, and the
 shared **"Exception Thrown"** card rendered under the step where the
 exception occurred (handler / interceptor / coeffect / fx / flow
 throws; `:db` schema-fail rollback on the EFFECT HANDLERS `:db` row).
- **L2 event-row pink-wash** — a cascade carrying an issue washes its L2
 timeline row pink, so the spine itself flags trouble.
- **The always-on issues-ribbon signal** — the `:rf.xray/issues-ribbon`
 composite drives the auto-open-on-error watcher (the cross-epoch
 "something is wrong" signal).

So "where are the errors?" routes to the **Epoch tab** (this epoch) + the
**L2 wash** (which epochs) — not a tab.

> **Note — there is no Chrome A11y tab either.** Earlier drafts of this
> skill listed a "Chrome A11y" Dynamic tab. It no longer exists — a11y
> dogfooding is Story's domain (`re-frame.story.ui.chrome-a11y`). Do not
> route a11y questions to a Xray tab.

### Static mode — 5 registry-browse tabs

In Static mode the L3 tab bar holds **5 catalogue lenses** over what's
*registered* in the picked frame (mnemonics mode-scoped — `m` opens the
Static Machines browse, not the Dynamic instance-inspector). Order set by
spec/007-UX-IA.md §Static mode: **Machines · Routes · Schemas · Flows ·
Interceptors**.

| Tab | Mnem | One-line purpose | When you'd open it |
|---|---|---|---|
| **Machines** *(default)* | `m` | Registry browse of every registered machine + topology + a 4-mode sub-strip (incl. the Sim engine). The "show me all my machines" entry point. | "What machines are registered?" / "Browse my checkout machine's chart without picking an event." |
| **Routes** | `r` | Every registered route + a Simulate-URL input (promoted from the Dynamic Routes lens). | "List all my routes." / "Which route would `/orders/42` match?" |
| **Schemas** | `c` | Every registered schema + sample data + jump-to-source. | "What schemas are registered?" / "Show me the shape of `:order/schema`." |
| **Flows** | `f` | Catalogue of every registered flow. | "What flows are registered?" |
| **Interceptors** | `i` | Pure-browse lens over the registered interceptor chains. | "What interceptors run, and in what order?" |

When a user asks "where do I see **all** my registered machines / routes /
schemas / flows / interceptors?" the answer is **Static mode** — Dynamic
tabs only ever show the focused event.

### Retired pre-rebuild panels — where their content lives now

Several pre-rebuild panels (Subscriptions, Effects, Flows, Performance,
Schemas, Hydration, and the old standalone Issues tab) are **not** separate
Dynamic tabs. The four high-drift routes worth keeping in the body:

- **Schemas** (violations) → **Epoch** inline + the L2 pink-wash; the
  *registry* catalogue → **Static → Schemas**.
- **Hydration** (SSR mismatches) → **Epoch** inline + the issues-ribbon
  signal — there is **no** standalone Hydration tab.
- **Flows** → **Epoch** FLOW step; the registry → **Static → Flows**.
- **Effects** (`fx`) → **Epoch** EFFECT HANDLERS step + **Trace** raw ops.

For the full retired-panel → content-home mapping (Subscriptions,
Performance, the rest), see
[`references/panels.md` §What's deliberately NOT here](references/panels.md#whats-deliberately-not-here)
(the single maintained home; + spec/021 §15). The hero on first open is
**Epoch** (`:order -1`). AI integration lives in the separate
`tools/re-frame2-pair-mcp/` jar — Xray itself is the human surface only.

---

## Out of scope (this iteration)

When a user asks about any of the following, this skill does not have
the answer — point them at the spec doc or pair-tool surface and stop
short of improvising.

- **Deep workflow recipes** (find-wrong-sub walker, redaction-marker
 grammar, click-to-source / "open in editor" internals, pop-out lifecycle
 gotchas, branch-and-explore). Source of truth:
 [`tools/xray/spec/007-UX-IA.md`](../../tools/xray/spec/007-UX-IA.md)
 and the per-panel specs (`tools/xray/spec/00N-*.md`). A future
 iteration may codify these as recipes; today the spec is the answer.
 (First-screen chrome — time-travel inspect / `Reset`-rewind, filter
 pills, the command palette, Settings — is **in scope**: §The chrome
 around the tabs above is the router; load
 [`references/chrome.md`](references/chrome.md) for the control-by-control
 detail.)
- **Driving Xray programmatically** (hot-swap a sub via REPL, time-
 travel from CLJS, dispatch into the runtime from a tool). Route to
 the [`re-frame2-pair`](../re-frame2-pair/SKILL.md) skill — Xray
 owns the *seeing*; re-frame2-pair owns the *driving*.
- **Implementing Xray** (panel-facade/leaf split, mount lifecycle
 internals, frame-provider isolation, the epoch pump's contract).
 Source of truth:
 [`tools/xray/spec/011-Launch-Modes.md` §Mount lifecycle](../../tools/xray/spec/011-Launch-Modes.md)
 and the per-panel implementation specs. A `xray-implementor` sibling
 skill is **deferred to post-alpha** until the Xray surface stabilises.
- **The "derivation/process graph" view** (subs / flows / resources /
 routes / machines as one node-and-edge graph — the EP-0014 algebra).
 There is **no shipped Xray tab** that renders that unified graph today.
 The algebra view is an **internal, structured accessor** the framework
 produces for Xray + the conformance fixtures to consume — it ships **no
 public accessor name** and **no `re-frame.core` facade export** (the
 public name is deferred until a third consumer needs it). So: do **not**
 tell a user to "open the derivation-graph tab" or call a public graph
 API — neither exists. What Xray ships *today* is the per-family browse:
 **Static → Machines / Routes / Flows** (registry catalogues) and the
 Dynamic per-epoch lenses. If a user asks for the cross-family graph,
 say it's an internal substrate (not a current panel) and cite
 [`spec/Derivations.md` §Graph inspection — internal but structured](../../spec/Derivations.md#graph-inspection--internal-but-structured).
---

## Style guidance

- **Cite the spec, don't paraphrase it.** When a user asks for normative
 detail (the mount contract, the epoch pump's ordering guarantees, the
 redaction marker's grammar), link to the relevant
 `tools/xray/spec/*.md` and quote sparingly.
- **Pre-alpha hedge.** Some surfaces are partial (the Machine tab
 renders through the shared xyflow styling at
 `panels/machines/xyflow_style.cljs`, still
 stabilising; the inline issue surfaces only populate the schema /
 hydration rows when the host has those features wired; the Static
 Machines Sim engine is still
 stabilising). The Static catalogues themselves (Machines / Routes /
 Schemas / Flows / Interceptors) are full registry browsers, not stubs.
 When a user asks about an in-progress surface, say so and point at the
 spec.
- **Don't invent hotkeys.** Four families are globally wired today —
 `Ctrl+Shift+C` (toggle shell), `Cmd/Ctrl+Shift+M` (mode toggle),
 `Cmd/Ctrl+K` (command palette), plus the focus-gated bare keys
 (`Space`/`L`/`j`/`k`/`G`/`,`/`s`). There is **no** `Ctrl+Shift+/`,
 no wired `r`/`R`/`*` rewind/pin keys (those are spec-only — the
 `Reset` button is the live rewind), `Esc` is modal-local not a wired
 spine key, and `Cmd/Ctrl+K` was **not** struck. Everything else in
 [`spec/007-UX-IA.md` §Keyboard](../../tools/xray/spec/007-UX-IA.md#keyboard)
 is normative for the future, not for "what works in your build right
 now." Cite
 [`keybinding.cljs`](../../tools/xray/src/day8/re_frame2_xray/keybinding.cljs)
 when in doubt.
- **Route, don't blur.** If the user wants to drive Xray, point at
 `re-frame2-pair`; if they want to implement it, point at the spec
 and note that no implementor skill exists yet. This skill is a tour.

---

*For the full skill-disambiguation matrix (when to use which skill) see
[`skills/README.md` §Skill routing — single source](../README.md#skill-routing--single-source).*
