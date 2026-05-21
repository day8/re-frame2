---
name: re-frame2-causa
description: >
 Read-only tour of **Causa** — the re-frame2 devtools panel. Use when the
 user wants to know how to *launch* Causa (in-app inline panel, pop-out
 window, programmatic mount, or the wired hotkeys), which of its two
 modes (Dynamic event-spine / Static registry browse) and which tab
 surfaces the data they're looking for, or what each tab is *for*.
 Trigger phrases: "open Causa", "where is X in Causa",
 "which Causa panel shows…", "Causa Static mode", "browse registered
 machines/routes/schemas in Causa", "Ctrl+Shift+C", "Causa hotkey",
 "Causa mode toggle", "Causa popout", "Causa machine inspector",
 "Causa issues feed", and similar. **Do not use** for: driving Causa
 programmatically from a live REPL (that's `re-frame2-pair`), authoring
 the host app (`re-frame2`), bootstrapping a new project
 (`re-frame2-setup`), or implementing Causa itself (no skill yet — the
 `causa-implementor` sibling is deferred to post-alpha). This skill cites
 `tools/causa/spec/*` as the source of truth; where a spec doc has
 an open question, hedge with "see spec/0NN" rather than freezing
 prose.
allowed-tools:
 - Read
 - Grep
 - Glob
---

# re-frame2-causa

A tour skill for **Causa** — the re-frame2 in-app devtools panel. Causa is
the structural successor to re-frame-10x: where v1 organised debugging
around the *epoch panel*, Causa organises it around the *story a cascade
tells*. Every dispatch is a node in a graph of causes; every state delta
is a slice you can scrub; every machine transition lands on a chart; every
schema violation surfaces as an issue you cannot miss.

This skill answers two questions, and only two:

1. **How do I launch Causa?** — the inline panel, the pop-out, the
 programmatic entry points, the wired hotkeys, the Dynamic ↔ Static
 mode toggle.
2. **Which tab shows X?** — a one-line purpose for each tab Causa ships,
 across both modes: the 8 Dynamic event-spine tabs (per spec/018 §5)
 and the 5 Static registry-browse tabs (per spec/007-UX-IA.md §Static
 mode).

Workflow procedures (find-wrong-sub, scrub-bad-epoch, click-to-source,
redaction-indicator semantics) are **out of scope** in this iteration —
see the *Out of scope* section below for what to do when one comes up.

---

## What Causa is

An in-app true-inline devtools panel for re-frame2 applications, preloaded
into dev builds via shadow-cljs `:preloads`. The host app provides a
right-side `[data-rf-causa-host]` column in its normal layout; Causa
auto-opens there once the substrate adapter is ready. Production builds
elide the entire surface through the universal `interop/debug-enabled?`
gate — zero bytes ship to consumers.

Causa consumes re-frame2's instrumentation surface (Spec 009 trace bus,
Tool-Pair epoch history, the registrar query API) — it adds nothing the
framework didn't already expose. The tabs are *presentation* of an
already-structured runtime.

### Two modes

Causa runs in one of two modes at a time, flipped by the L1 mode pill or
the `Cmd/Ctrl+Shift+M` chord:

- **Dynamic** — the event-coupled spine. A 4-layer chrome (L1 ribbon ·
 L2 event list · L3 tab bar · L4 detail). Every tab is a *lens on the
 one focused event* — pick an event in the L2 list and every tab
 rebinds. This is "what happened in **this** epoch?". 8 tabs.
- **Static** — event-INDEPENDENT browse of what's *registered*. A
 3-layer chrome (no L2 spine — Static has no event focus). Every tab is
 a registry catalogue: every machine, every route, every schema, every
 flow, every interceptor known to the picked frame. This is "what
 exists?", not "what just happened?". 5 tabs.

Same design language, different temperature (per spec/007-UX-IA.md
§Static mode). When the user wants to inspect a *single dispatch*, that's
Dynamic; when they want to browse the *whole registry*, that's Static.

For an AI agent surface against the running app, use `tools/re-frame2-pair-mcp/`
— the raw nREPL pair-programming companion. Causa is the human-facing
panel; re-frame2-pair-mcp is the AI-facing surface.

---

## Launching Causa — pick a mode

Three launch modes ship today. Pick the one that matches the user's
situation.

| User wants to … | Use | How |
|---|---|---|
| Inspect the runtime while developing locally | **Default true-inline panel** | Add the preload + a `[data-rf-causa-host]` column in the app layout. Causa auto-opens on page load. |
| Put Causa on a second monitor with the app full-screen | **Pop-out window** | `(causa/popout!)` from CLJS, or `window.day8.re_frame2_causa.popout_BANG_` from devtools. |
| Mount Causa from code (no preload, or alternative wiring) | **Programmatic `init!`** | Call `(causa/init! opts)` after `rf/init!`. Idempotent. |
| Browse what's *registered* instead of one dispatch | **Static mode** | Flip the L1 mode pill or press `Cmd/Ctrl+Shift+M`. Static drops the event spine and shows the 5 registry-browse tabs. |
| Have an AI agent inspect the runtime | **re-frame2-pair-mcp** | Configure `tools/re-frame2-pair-mcp/` in the agent host — the raw nREPL pair-programming companion is the AI access path. Out of scope for this skill — see [`tools/re-frame2-pair-mcp/`](../../tools/re-frame2-pair-mcp/). |
| Debug a mobile browser | Not supported | Per `spec/011-Launch-Modes.md` §What this doesn't do — phones refuse to mount. |

For the decision tree in depth (preload vs `init!`, suppress-auto-open
on tool-only pages, the `:rf.causa/layout-host-selector` knob, host-CSS-variable
resize, pop-out lifecycle), see [`references/launch-modes.md`](references/launch-modes.md).

### Wired hotkeys

Four hotkey families have global keydown listeners installed in
`keybinding.cljs` today. Spec
[`007-UX-IA.md` §Keyboard](../../tools/causa/spec/007-UX-IA.md#keyboard)
catalogues a richer map; these are what is actually wired:

| Key | Scope | Action |
|---|---|---|
| `Ctrl+Shift+C` | global | Toggle the Causa shell (mount on first press; CSS show/hide after). `Ctrl+Shift` deliberately avoids Safari's `Cmd+Shift+C` Inspect collision. |
| `Cmd/Ctrl+Shift+M` | global | Toggle mode — Dynamic ↔ Static (`:rf.causa/toggle-mode`). Cmd on macOS, Ctrl elsewhere. |
| `Cmd/Ctrl+K` | global | Open the command palette (`:rf.causa/palette-toggle`); opens the shell first if hidden. Cmd on macOS, Ctrl elsewhere. |
| `Space` `L` `j` `k` `G` `,`/`s` `Esc` | focus-gated | Spine + chrome shortcuts. These bare keys fire **only** when the Causa shell is visible AND the keydown target is inside the shell AND not an editable field (and not inside a modal). Space = pause/resume LIVE · `L` = snap to LIVE · `j`/`k` = step the focused event back/forward · `G` (Shift+G) = fast-forward to head · `,` or `s` = Settings popup · `Esc` = clear the focus lens. |

`Cmd/Ctrl+K` **is** wired — do not say it was struck. The pop-out has no
hotkey; use `(causa/popout!)` or the future right-click affordance on the
launcher pill. Embed hosts (Story RHS, third-party tool surfaces) can
suppress Causa's global listeners via `:rf.causa/keybinding-enabled?`.

Source of truth:
[`keybinding.cljs`](../../tools/causa/src/day8/re_frame2_causa/keybinding.cljs).

---

## The tabs — what each surfaces

<a id="the-tabs--what-each-surfaces"></a>

Causa's tabs split across its two modes. When the user asks "where is X?",
first decide *which mode answers it* — Dynamic (about one dispatch) or
Static (about the whole registry) — then route to the tab. For per-tab
layout, iconography, stripe tokens, and "open it when…" depth see
[`references/panels.md`](references/panels.md).

### Dynamic mode — 8 lenses on the focused event

The L3 tab bar holds **8 lenses on the focused event**, in the order set
by spec/018 §5 (mnemonics `e a v t m c r i`): **Event · App DB · View ·
Trace · Machines · Machines Canvas · Routing · Issues**. Cross-epoch
signal lives on the L2 timeline above (badges + stripes); every tab
answers "what happened in **this** epoch?" through its own lens — except
**Machines Canvas**, which is spine-INDEPENDENT (it browses a picked
machine's full topology regardless of the focused event).

| Tab | Mnem · Icon · Stripe | One-line purpose | When you'd open it |
|---|---|---|---|
| **Event** *(hero)* | `e` · `⚡` · violet | The six-step handling pipeline for the focused dispatch: DISPATCH → COEFFECTS → HANDLER → EFFECTS RETURNED → EFFECTS APPLIED → FLOWS RECOMPUTED. | Default landing view. "What did this event do?" / "What fx fired?" / "Did the flow recompute?" |
| **App DB** | `a` · `◐` · cyan | Two-zone: DIFF (changed paths for this epoch) + STATE (full db at end of epoch via lazy tree). Hover any changed path for downstream-subs popover. | "What just changed in app-db?" / "What's downstream of `[:cart :items]`?" |
| **View** | `v` · `◉` · cyan | The reactive cascade as a depth-first DAG: subs recomputed (step 7) + views re-rendered (step 8) with `caused-by ← sub ← path` causation on every leaf. (Display label is **View**, renamed from `Views`/`Reactive`; the internal tab id stays `:views`.) | "Why didn't my view update?" / "Trace the recompute chain for `:cart/total`." / "Which views re-rendered this epoch?" |
| **Trace** | `t` · `⬢` · orange | Raw Spec 009 trace events for the focused epoch — one mono row per op, filterable by `[op-type ▾] [tag ▾]`, payload expands inline. | "Show me every raw op in this epoch." / "Is `:rf.fx/*` firing as expected?" |
| **Machines** | `m` · `◆` · green | **Event-driven.** Per-machine topology + transition highlight + guards / actions / cancellation cascade for the focused event. BLANK when the focused event had no machine activity; per-machine prev/next walks the spine. | "What did this event do to my machines?" / "What transition fired?" / "What guards passed/failed?" |
| **Machines Canvas** | `c` · `◆` · green | **Spine-INDEPENDENT canvas browser.** Master-detail: machine picker on the left, interactive Chart adapter on the right (zoom / pan / fit + keyboard). Always shows the picked machine's *full* topology, not just this event. | "What does my checkout machine look like overall?" / "Show me the whole state chart, not just this transition." |
| **Routing** | `r` · `🌐` · yellow | Flat focused-event lens: current matched route + params/query/fragment + a **Simulate-URL** input that ranks every registered route, with per-event glyphs `◆ HERE` / `◆ FROM` / `◆ TO`. Silent when no routes registered. | "What route am I on?" / "Did the route change this epoch?" / "What params resolved?" |
| **Issues** | `i` · `⚠` · red | Per-epoch errors + warnings + schema violations + hydration mismatches + perf-budget overruns + app console errors, unified. Head-fallback to most-recent epoch when the spine is at head. | "Anything broken in this epoch?" / "Show me all schema failures here." / "What warnings fired?" |

> **Note — there is no Chrome A11y tab.** Earlier drafts of this skill
> listed a "Chrome A11y" Dynamic tab. It no longer exists — a11y
> dogfooding is Story's domain (`re-frame.story.ui.chrome-a11y`). Do not
> route a11y questions to a Causa tab.

### Static mode — 5 registry-browse tabs

In Static mode the L3 tab bar holds **5 catalogue lenses** over what's
*registered* in the picked frame (mnemonics mode-scoped — `m` opens the
Static Machines browse, not the Dynamic instance-inspector). Order set by
spec/007-UX-IA.md §Static mode: **Machines · Routes · Schemas · Flows ·
Interceptors**.

| Tab | Mnem | One-line purpose | When you'd open it |
|---|---|---|---|
| **Machines** *(default)* | `m` | Registry browse of every registered machine + topology + a 4-mode sub-strip (incl. the Sim engine). The "show me all my machines" entry point. | "What machines are registered?" / "Browse my checkout machine's chart without picking an event." |
| **Routes** | `r` | Every registered route + a Simulate-URL input (promoted from the Dynamic Routing lens). | "List all my routes." / "Which route would `/orders/42` match?" |
| **Schemas** | `c` | Every registered schema + sample data + jump-to-source. | "What schemas are registered?" / "Show me the shape of `:order/schema`." |
| **Flows** | `f` | Catalogue of every registered flow. | "What flows are registered?" |
| **Interceptors** | `i` | Pure-browse lens over the registered interceptor chains. | "What interceptors run, and in what order?" |

When a user asks "where do I see **all** my registered machines / routes /
schemas / flows / interceptors?" the answer is **Static mode** — Dynamic
tabs only ever show the focused event.

### Retired pre-rebuild panels — where their content lives now

Six panels from the pre-rebuild inventory (Subscriptions, Effects,
Flows, Performance, Schemas, Hydration) are **not** separate Dynamic
tabs. Their content is surfaced through the Dynamic 8 (per
[`references/panels.md` §What's deliberately NOT here](references/panels.md#whats-deliberately-not-here)
+ spec/021 §15) — and the registry catalogues live in Static mode:

| Retired panel | Where its content lives now |
|---|---|
| **Subscriptions** | **View** (cascade tree, step 7) + **App DB** (downstream-subs hover popover on changed paths) |
| **Effects** (`fx`) | **Event** step 4 (returned) + step 5 (applied) + **Trace** (raw `:rf.fx/*` ops) |
| **Flows** | **Event** step 6 (FLOWS RECOMPUTED), per event · **Static → Flows** for the registry catalogue |
| **Performance** | L2 row stripe colours (cross-epoch budget signal) + per-step `:time` inside **Trace** |
| **Schemas** | **Issues** (schema violations, per event) · **Static → Schemas** for the registry catalogue |
| **Hydration** *(SSR)* | **Issues** (hydration mismatches land in the unified per-epoch feed) |

The hero on first open is **Event** (Dynamic mode). AI integration lives
in the separate `tools/re-frame2-pair-mcp/` jar — Causa itself is the
human surface only.

---

## Out of scope (this iteration)

When a user asks about any of the following, this skill does not have
the answer — point them at the spec doc or pair-tool surface and stop
short of improvising.

- **Workflow recipes** (find-wrong-sub, scrub-bad-epoch,
 redaction-indicator semantics, click-to-source / "open in editor"
 details, pop-out lifecycle gotchas). Source of truth:
 [`tools/causa/spec/007-UX-IA.md`](../../tools/causa/spec/007-UX-IA.md)
 and the per-panel specs (`tools/causa/spec/00N-*.md`). A future
 iteration may codify these as recipes; today the spec is the answer.
- **Driving Causa programmatically** (hot-swap a sub via REPL, time-
 travel from CLJS, dispatch into the runtime from a tool). Route to
 the [`re-frame2-pair`](../re-frame2-pair/SKILL.md) skill — Causa
 owns the *seeing*; re-frame2-pair owns the *driving*.
- **Implementing Causa** (panel-facade/leaf split, mount lifecycle
 internals, frame-provider isolation, the epoch pump's contract).
 Source of truth:
 [`tools/causa/spec/011-Launch-Modes.md` §Mount lifecycle](../../tools/causa/spec/011-Launch-Modes.md#mount-lifecycle-rf2-9kkrm)
 and the per-panel implementation specs. A `causa-implementor` sibling
 skill is **deferred to post-alpha** until the Causa surface stabilises.
---

## Style guidance

- **Cite the spec, don't paraphrase it.** When a user asks for normative
 detail (the mount contract, the epoch pump's ordering guarantees, the
 redaction marker's grammar), link to the relevant
 `tools/causa/spec/*.md` and quote sparingly.
- **Pre-alpha hedge.** Some surfaces are partial (the Machines tabs
 render through the shared xyflow styling at
 `panels/machines/xyflow_style.cljs` + `panels/machines_canvas/`, still
 stabilising; Issues only populates the schema / hydration rows when the
 host has those features wired; several Static tabs carry placeholder
 beads). When a user asks about an in-progress surface, say so and point
 at the spec.
- **Don't invent hotkeys.** Four families are globally wired today —
 `Ctrl+Shift+C` (toggle shell), `Cmd/Ctrl+Shift+M` (mode toggle),
 `Cmd/Ctrl+K` (command palette), plus the focus-gated bare keys
 (`Space`/`L`/`j`/`k`/`G`/`,`/`s`/`Esc`). There is **no** `Ctrl+Shift+/`
 and `Cmd/Ctrl+K` was **not** struck. Everything else in
 [`spec/007-UX-IA.md` §Keyboard](../../tools/causa/spec/007-UX-IA.md#keyboard)
 is normative for the future, not for "what works in your build right
 now." Cite
 [`keybinding.cljs`](../../tools/causa/src/day8/re_frame2_causa/keybinding.cljs)
 when in doubt.
- **Route, don't blur.** If the user wants to drive Causa, point at
 `re-frame2-pair`; if they want to implement it, point at the spec
 and note that no implementor skill exists yet. This skill is a tour.

---

*For the full skill-disambiguation matrix (when to use which skill) see
[`skills/README.md` §Skill routing — single source](../README.md#skill-routing--single-source).*
