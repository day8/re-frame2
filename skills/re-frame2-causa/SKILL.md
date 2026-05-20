---
name: re-frame2-causa
description: >
  Read-only tour of **Causa** — the re-frame2 devtools panel. Use when the
  user wants to know how to *launch* Causa (in-app inline panel, pop-out
  window, programmatic mount, or the wired hotkeys), which of its 8
  panels surfaces the data they're looking for, or what each panel is
  *for*. Trigger phrases: "open Causa", "where is X in Causa",
  "which Causa panel shows…", "Ctrl+Shift+C", "Causa hotkey",
  "Causa popout", "Causa machine inspector", "Causa issues feed",
  and similar. **Do not use** for: driving Causa programmatically
  from a live REPL (that's `re-frame2-pair`), authoring the host app
  (`re-frame2`), bootstrapping a new project (`re-frame2-setup`), or
  implementing Causa itself (no skill yet — the `causa-implementor`
  sibling is deferred to post-alpha). This skill cites
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
   programmatic entry points, the wired hotkeys.
2. **Which panel shows X?** — a one-line purpose for each of the 8
   L4 panels Causa ships (per spec/021 §11.4).

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
framework didn't already expose. The 8 L4 panels are *presentation* of
an already-structured runtime.

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
| Put Causa on a second monitor with the app full-screen | **Pop-out window** | `(causa/popout!)` from CLJS, or `window.day8.re_frame2_causa.popout_BANG_()` from devtools. |
| Mount Causa from code (no preload, or alternative wiring) | **Programmatic `init!`** | Call `(causa/init! opts)` after `rf/init!`. Idempotent. |
| Have an AI agent inspect the runtime | **re-frame2-pair-mcp** | Configure `tools/re-frame2-pair-mcp/` in the agent host — the raw nREPL pair-programming companion is the AI access path. Out of scope for this skill — see [`tools/re-frame2-pair-mcp/`](../../tools/re-frame2-pair-mcp/). |
| Debug a mobile browser | Not supported | Per `spec/011-Launch-Modes.md` §What this doesn't do — phones refuse to mount. |

For the decision tree in depth (preload vs `init!`, suppress-auto-open
on tool-only pages, the `:rf.causa/layout-host-selector` knob, host-CSS-variable
resize, pop-out lifecycle), see [`references/launch-modes.md`](references/launch-modes.md).

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

## The 8 panels — what each surfaces

<a id="the-13-panels--what-each-surfaces"></a>

Causa's L4 panel row holds **8 lenses on the focused epoch**, fixed in
the order set by spec/021 §11.4: **Event · App-db · Reactive · Trace ·
Machines · Routing · Issues · Chrome A11y**. Cross-epoch signal lives
on the L2 timeline above (badges + stripes); every L4 panel answers
"what happened in **this** epoch?" through its own lens (§021 §1.2 —
binding). When the user asks "where is X?", route to the panel whose
purpose covers it. For per-panel layout, iconography, stripe tokens,
and "open it when…" depth see [`references/panels.md`](references/panels.md).

| Panel | Icon · Stripe | One-line purpose | When you'd open it |
|---|---|---|---|
| **Event** *(hero)* | `⚡` · violet | The six-step handling pipeline for the focused dispatch: DISPATCH → COEFFECTS → HANDLER → EFFECTS RETURNED → EFFECTS APPLIED → FLOWS RECOMPUTED. | Default landing view. "What did this event do?" / "What fx fired?" / "Did the flow recompute?" |
| **App-db** | `◐` · cyan | Two-zone: DIFF (changed paths for this epoch) + STATE (full db at end of epoch via lazy tree). Hover any changed path for downstream-subs popover. | "What just changed in app-db?" / "What's downstream of `[:cart :items]`?" |
| **Reactive** | `◉` · cyan | The reactive cascade as a depth-first DAG: subs recomputed (step 7) + views re-rendered (step 8) with `caused-by ← sub ← path` causation on every leaf. | "Why didn't my view update?" / "Trace the recompute chain for `:cart/total`." / "Which views re-rendered this epoch?" |
| **Trace** | `⬢` · orange | Raw Spec 009 trace events for the focused epoch — one mono row per op, filterable by `[op-type ▾] [tag ▾]`, payload expands inline. | "Show me every raw op in this epoch." / "Is `:rf.fx/*` firing as expected?" |
| **Machines** | `◆` · green | Per-machine xyflow topology + current-state pulse via 4-source precedence walk-back; guards / actions / cancellations in per-canvas footer. | "What state is my checkout machine in?" / "What transition fired?" / "What guards passed/failed?" |
| **Routing** | `🌐` · yellow | Active route tree (textual, `├─ └─`) + this-epoch block (phase, from/to, match, events). Reads `:rf.route/can-leave` / `:can-enter` / `:on-match` / `:url-changed`. | "What route am I on?" / "Did the route change this epoch?" / "What params resolved?" |
| **Issues** | `⚠` · red | Per-epoch errors + warnings + schema violations + a11y violations, unified. Head-fallback to most-recent epoch when the spine is at head. | "Anything broken in this epoch?" / "Show me all schema failures here." / "What warnings fired?" |
| **Chrome A11y** | `✦` · red | Causa's own chrome accessibility dogfood — audit list (rule-id, severity, affected element, remediation hint). Spine-independent. | "Is Causa's own chrome accessible right now?" — meta surface. |

### Retired pre-rebuild panels — where their content lives now

Six panels from the pre-rebuild inventory (Subscriptions, Effects,
Flows, Performance, Schemas, Hydration) are **not** separate L4 panels.
Their content is surfaced through the 8 above (per
[`references/panels.md` §What's deliberately NOT here](references/panels.md#whats-deliberately-not-here)
+ spec/021 §15):

| Retired panel | Where its content lives now |
|---|---|
| **Subscriptions** | **Reactive** (cascade tree, step 7) + **App-db** (downstream-subs hover popover on changed paths) |
| **Effects** (`fx`) | **Event** step 4 (returned) + step 5 (applied) + **Trace** (raw `:rf.fx/*` ops) |
| **Flows** | **Event** step 6 (FLOWS RECOMPUTED) |
| **Performance** | L2 row stripe colours (cross-epoch budget signal) + per-step `:time` inside **Trace** |
| **Schemas** | **Issues** (schema violations land in the unified per-epoch feed) |
| **Hydration** *(SSR)* | **Issues** (hydration mismatches land in the unified per-epoch feed) |

The hero on first open is **Event**. AI integration lives in the
separate `tools/re-frame2-pair-mcp/` jar — Causa itself is the human
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
- **Pre-alpha hedge.** Some surfaces are partial (Machines depends on
  `tools/machines-viz/` xyflow styling that's still landing; Issues
  only populates the schema / hydration rows when the host has those
  features wired). When a user asks about an in-progress surface, say
  so and point at the spec.
- **Don't invent hotkeys.** Only `Ctrl+Shift+C` and `Ctrl+Shift+/` are
  globally wired today. Everything else in
  [`spec/007-UX-IA.md` §Keyboard](../../tools/causa/spec/007-UX-IA.md#keyboard)
  is normative for the future, not for "what works in your build right
  now."
- **Route, don't blur.** If the user wants to drive Causa, point at
  `re-frame2-pair`; if they want to implement it, point at the spec
  and note that no implementor skill exists yet. This skill is a tour.

---

*For the full skill-disambiguation matrix (when to use which skill) see
[`skills/README.md` §Skill routing — single source](../README.md#skill-routing--single-source).*
