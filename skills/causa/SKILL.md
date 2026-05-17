---
name: causa
description: >
  Read-only tour of **Causa** — the re-frame2 devtools panel. Use when the
  user wants to know how to *launch* Causa (in-app inline panel, pop-out
  window, programmatic mount, or the two wired hotkeys), which of its 16
  panels surfaces the data they're looking for, or what each panel is
  *for*. Trigger phrases: "open Causa", "where is X in Causa",
  "which Causa panel shows…", "Ctrl+Shift+C", "Causa hotkey",
  "Causa popout", "Causa hydration debugger", "Causa schema timeline",
  "Causa machine inspector", and similar. **Do not use** for: driving
  Causa programmatically from a live REPL (that's `re-frame-pair2`),
  authoring the host app (`re-frame2`), bootstrapping a new project
  (`re-frame2-setup`), or implementing Causa itself (no skill yet — the
  `causa-implementor` sibling is deferred to post-alpha). This skill
  cites `tools/causa/spec/*` as the source of truth; where a spec doc
  has an open question, hedge with "see spec/0NN" rather than freezing
  prose.
allowed-tools:
  - Read
  - Grep
  - Glob
---

# causa

A tour skill for **Causa** — the re-frame2 in-app devtools panel. Causa is
the structural successor to re-frame-10x: where v1 organised debugging
around the *epoch panel*, Causa organises it around the *story a cascade
tells*. Every dispatch is a node in a graph of causes; every state delta
is a slice you can scrub; every machine transition lands on a chart; every
schema violation surfaces as an issue you cannot miss.

This skill answers two questions, and only two:

1. **How do I launch Causa?** — the inline panel, the pop-out, the
   programmatic entry points, the wired hotkeys.
2. **Which panel shows X?** — a one-line purpose for each of the 16
   panels Causa ships.

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
framework didn't already expose. The 16 panels are *presentation* of an
already-structured runtime.

For an AI agent surface against the running app, use `tools/pair2-mcp/`
— the raw nREPL pair-programming companion. Causa is the human-facing
panel; pair2-mcp is the AI-facing surface.

---

## Launching Causa — pick a mode

Three launch modes ship today. Pick the one that matches the user's
situation.

| User wants to … | Use | How |
|---|---|---|
| Inspect the runtime while developing locally | **Default true-inline panel** | Add the preload + a `[data-rf-causa-host]` column in the app layout. Causa auto-opens on page load. |
| Put Causa on a second monitor with the app full-screen | **Pop-out window** | `(causa/popout!)` from CLJS, or `window.day8.re_frame2_causa.popout_BANG_()` from devtools. |
| Mount Causa from code (no preload, or alternative wiring) | **Programmatic `init!`** | Call `(causa/init! opts)` after `rf/init!`. Idempotent. |
| Have an AI agent inspect the runtime | **pair2-mcp** | Configure `tools/pair2-mcp/` in the agent host — the raw nREPL pair-programming companion is the AI access path. Out of scope for this skill — see [`tools/pair2-mcp/`](../../tools/pair2-mcp/). |
| Debug a mobile browser | Not supported | Per `spec/011-Launch-Modes.md` §What this doesn't do — phones refuse to mount. |

For the decision tree in depth (preload vs `init!`, suppress-auto-open
on tool-only pages, the `:layout/host-selector` knob, host-CSS-variable
resize, pop-out lifecycle), see [`reference/launch-modes.md`](reference/launch-modes.md).

### Wired hotkeys

Only one hotkey is wired in `keybinding.cljs` pre-alpha. Spec
[`007-UX-IA.md` §Keyboard](../../tools/causa/spec/007-UX-IA.md#keyboard)
catalogues a richer global / navigation / panel-jump map, but only this
one has a global keydown listener installed today:

| Key | Action |
|---|---|
| `Ctrl+Shift+C` | Toggle the Causa panel (mount on first press; CSS show/hide after). |

The remaining keys catalogued in the spec (`?`, `,`, panel-jump
mnemonics, scrubber controls, event-action shortcuts) require focus
inside Causa and are not global; some are explicit follow-on work. The
command-palette `Ctrl+K` was never wired and was struck under rf2-27zh2
(Cluster C cleanup) — do not suggest it as a launch path. The pop-out
has no hotkey; use `(causa/popout!)` or the future right-click affordance
on the launcher pill.

---

## The 15 panels — what each surfaces

The sidebar lists 15 panels in three groups (always-active, conditional,
dormant). When the user asks "where is X?", route to the panel whose
purpose covers it. For more detail on each — group membership, dormant
state, activity badges, deeper "open it when…" guidance — see
[`reference/panels.md`](reference/panels.md).

| Panel | One-line purpose | When you'd open it |
|---|---|---|
| **Event detail** *(hero)* | The six-domino cascade for the selected dispatch: event vector, diff, fx fired, subs recomputed, renders, duration. | Default landing view. "What happened in this dispatch?" |
| **Time travel** | Bottom-rail scrubber over per-frame `epoch-history`; passive scrub rebases view, explicit rewind calls `restore-epoch`. | "Walk me through the last N dispatches." |
| **App-db** | Slice-centric diff: changed slices for the selected epoch + pinned live slices + reserved-key inspector. | "What in app-db just changed?" / "Show me when `[:cart :items]` last moved." |
| **Causality** | Vertical directed graph keyed by `:dispatch-id` / `:parent-dispatch-id`. Non-dispatch traces attach as flags inside the parent node. | "This cascade is more than two hops" / "I'm triaging a session with 30+ events." |
| **Subscriptions** | Registered subs + their invalidation chains + cache status + last-recomputed values. | "Why didn't my view update?" / "Trace the recompute chain for `:cart/total`." |
| **Effects** *(fx)* | Registered fxs + per-fx invocations + outcome status + stub indicator. | "Which fx fired in this epoch?" / "Did `:http/get` get skipped?" |
| **Trace** | Raw event ribbon — every trace event in the buffer as a timestamped row, filterable along the 13-axis Spec 009 vocabulary. | "Grep the full trace stream." / "Show me every `:rf.fx/*` event in the last minute." |
| **Machines** | State-chart per registered machine (embeds `tools/machines-viz/`) + transition-history ribbon. Pre-alpha: chart is a placeholder pending the viz impl. | "What state is my checkout machine in?" / "What transition fired?" |
| **Flows** | Registered flows + per-flow inputs / output path / live recomputation indicator. | "Is this flow recomputing?" / "Which inputs feed this flow?" |
| **Routes** | Registered routes + active `:rf/route` slice + recent navigation history. | "What route am I on?" / "Show me the last few navigations." |
| **Performance** | Per-cascade duration capture, perf-tier colour (`<16ms` / `16–50` / `50–100` / `>100`), budget-warning markers. | "Which cascades blew the INP budget?" |
| **Issues** | Unified feed: errors + warnings + schema violations + hydration mismatches. Top-strip badge mirrors count. | "Anything broken?" / "Show me all schema failures." |
| **Schemas** | One row per registered schema; coloured dot per failure with recovery-mode mapping. | "Has any schema violated this session?" / "When did `:user/profile` start failing?" |
| **Hydration** *(dormant)* | Server-vs-client render-tree side-by-side with divergent node flagged and hash-bisector path highlighted. Dormant `◌` until the first `:rf.ssr/hydration-mismatch` trace lands. | "My SSR hydration is mismatching" — only visible when SSR runs. |
| **MCP** | Live feed of MCP-server activity: tool calls in flight, recent results, per-origin colouring. | "What is my agent doing right now?" / "Did the MCP call land?" |

The hero on first open is **Event detail**. AI integration lives in
the separate `tools/pair2-mcp/` jar — Causa itself is the human
surface only.

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
  the [`re-frame-pair2`](../re-frame-pair2/SKILL.md) skill — Causa
  owns the *seeing*; pair2 owns the *driving*.
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
- **Pre-alpha hedge.** Some panels are partial (Machines depends on
  `tools/machines-viz/` which is a placeholder pre-alpha; Schemas /
  Hydration only render when the relevant feature is wired into the
  host). When a user asks about an in-progress surface, say so and
  point at the spec.
- **Don't invent hotkeys.** Only `Ctrl+Shift+C` and `Ctrl+Shift+/` are
  globally wired today. Everything else in
  [`spec/007-UX-IA.md` §Keyboard](../../tools/causa/spec/007-UX-IA.md#keyboard)
  is normative for the future, not for "what works in your build right
  now."
- **Route, don't blur.** If the user wants to drive Causa, point at
  `re-frame-pair2`; if they want to implement it, point at the spec
  and note that no implementor skill exists yet. This skill is a tour.

---

*For the full skill-disambiguation matrix (when to use which skill) see
[`skills/README.md` §Skill routing — single source](../README.md#skill-routing--single-source).*
