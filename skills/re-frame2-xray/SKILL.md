---
name: re-frame2-xray
description: >
  Question-first tour of **Xray**, the re-frame2 devtools panel: how to
  *launch* it (inline in-app panel; the `open-overlay!` fallback for hosts
  with no `[data-rf-xray-host]` layout column; pop-out window; programmatic
  mount; the wired hotkeys), which of its two modes (Dynamic event-spine /
  Static registry browse) applies, and what each tab is for. **Do not use**
  when the user asks the AGENT to inspect or change their running app,
  read-only included (read a sub, snapshot state, walk traces, dispatch) —
  that is `re-frame2-pair`, the agent-facing runtime companion. Nor for
  authoring the host app (`re-frame2`), bootstrapping a project
  (`re-frame2-setup`), or implementing Xray itself (no implementor skill
  exists — the spec is the answer). Trigger phrases: "open Xray", "which Xray
  panel shows…", "Xray Static mode", "browse registered
  machines/routes/schemas", "Xray overlay", "Xray machine inspector", "Xray
  Graph / Resources / Frames / Hicasso tab", "why did this boundary
  re-render".
allowed-tools:
 - Read
 - Grep
 - Glob
---

# re-frame2-xray

One debugging question, one first surface. This skill routes a concrete
question to the one visible Xray mode/tab to open first, says why it is
the first stop, and names the first interaction. Xray is the human-facing
in-app devtools panel for re-frame2 — preloaded into dev builds via
shadow-cljs `:preloads`, auto-opening into the host's
`[data-rf-xray-host]` column, elided entirely from production builds.

## First fork — who is looking?

- **A human looking at the visible Xray panel** — "where do I look?",
 "which tab shows X?", "how do I launch it?" — stays here.
- **The agent inspecting or changing the running app** — "read this sub
 for me", "what's at `[:cart :items]` right now?", "dispatch this and
 tell me what happens" — routes to
 [`re-frame2-pair`](../re-frame2-pair/SKILL.md), **whether or not the
 operation mutates anything**. Pair owns agent runtime access — read-sub,
 get-path, snapshots, trace/epoch reads, DOM/UI reads, and writes alike.
 The boundary is human panel vs agent runtime, not read vs write.

## The route card — question → first surface

Route a panel question by the evidence sought. Answer with the visible
mode/tab, the reason, and the first interaction; name a second lens only
when it is the natural next step. Do not enumerate the tab inventory
unless the user asked for the inventory.

| The evidence sought | First surface | First interaction | Natural next step |
|---|---|---|---|
| What did this one dispatch do — the whole cascade, where it failed, what fx fired | **Dynamic → Epoch** (the default landing tab) | pick the frame, click the event row in the L2 list | **Trace** for raw op ordering |
| What changed in state — is the value I expect actually there | **Dynamic → app-db** | pick the event; changed paths carry inline `← was X` diffs | **Views** for what re-rendered off the change |
| Why did this render — sub change or props, what recomputed | **Dynamic → Views** | pick the event; read the render-cause chips (`← :sub-id` vs `← props`) | **app-db** at the sub's input path |
| Exact raw ordering / payloads the friendly views summarise | **Dynamic → Trace** | pick the event; click a row to expand it | — |
| What this event did to a state machine — transition, guards, actions | **Dynamic → Machine** (event-driven; blank when the event touched no machine) | pick the event | **Static → Machines** to browse a full topology cold |
| What route am I on / what did this event do to routing | **Dynamic → Routes** | pick the event | **Static → Routes** to rank a URL against the registry |
| Server state — what owns it, is it stale, what's in flight, did my mutation's `:reply-to` fire | **Dynamic → Resources** | pick the frame — live instances follow the L1 frame picker, not the epoch | — |
| Where does this value come from — the dependency graph across subs / flows / resources / routes / machines | **Dynamic → Graph** (does not follow the epoch) | flip its own Declared ↔ Realized projection toggle for registered-vs-observed | — |
| Which image loaded which frame; how a frame resolves its registrations | **Dynamic → Frames** (process-global; does not follow the epoch) | open the tab | — |
| Hicasso — which boundaries are mounted, what they read, why one re-rendered, which is hot | **Dynamic → Hicasso** (not epoch-coupled) | open the tab; pick the sub-view (Mounted · Reads · Intents · Why · Advisor · Causal) | — |
| What's registered — machines / routes / schemas / flows / interceptors as catalogues | **Static mode** | flip the L1 mode pill or press `Cmd/Ctrl+Shift+M` | — |
| Schema violations — what fired and when each started | **Dynamic → Epoch** (violations attach inline to the owning step) + the L2 pink-wash | pick the frame; scan the spine for washed rows | registered-schema *catalogue* → **Static → Schemas** |
| SSR hydration mismatches | **Dynamic → Epoch** inline + the auto-open-on-error issues-ribbon signal — there is **no** Hydration tab | pick the washed event | — |
| Anything broken in this epoch? / which epochs are broken? | **Dynamic → Epoch** (per-step ✓/✗ + inline exception cards) / the **L2 pink-wash** — there is **no** Issues tab | pick the frame; scan the spine | — |
| A "panel" remembered from elsewhere — Subscriptions, Effects, Flows, Performance | not a tab — its content lives in the lenses above | see [`references/panels.md` §What's deliberately NOT here](references/panels.md#whats-deliberately-not-here) | — |

The daily path in one line: **choose the frame → choose an event (only
when the question is event-shaped) → start at Epoch for "what happened?"
→ branch to the exact state/render/raw/specialist lens → use Static for
definitions.** The non-epoch surfaces (Graph · Frames · Hicasso) never
pretend to follow the event — route them by structure, not by dispatch.

## The inventory (for explicit "list every tab" requests)

Dynamic mode's L3 tab bar holds **10 tabs**, left-to-right (mnemonics
`e a v t m r s g u h`): **Epoch · app-db · Views · Trace · Machine ·
Routes · Resources · Graph · Frames · Hicasso**. Static mode holds **5**:
**Machines · Routes · Schemas · Flows · Interceptors**, with its own
letters — the same letter can label a tab in each mode, as `m` does for
Dynamic's Machine and Static's Machines.

**Those letters are tooltip hints, not keys.** Each is rendered into its
tab button's `title` — `Trace (t)` — and is read by nothing else. **No
bare letter selects a tab**; keyboard tab-jump is the command palette
(`Cmd/Ctrl+K` → "Open Trace panel" / "Open Machines (Static)"), and from
code it is `focus!`. Watch `s` in particular: it labels the Resources tab
but *is* a live bare key — bound to the Settings popup (§Wired hotkeys),
not to Resources. The compact canonical
inventory — every tab's one-liner and the scope matrix in one place — is
[`references/panels.md`](references/panels.md); load it for an explicit
full-inventory request, not for routine routing.

### Scope model — what actually follows the event

"Dynamic" names the 4-layer shell (L1 ribbon · L2 event list · L3 tab bar
· L4 detail), not a uniform data scope:

- **Six focused-epoch lenses** — Epoch · app-db · Views · Trace ·
 Machine · Routes — rebind when you pick an event in the L2 list.
- **Resources is mixed** — a process-global resource registry plus the
 observed frame's live cache/ledger (follows the L1 frame picker), with
 per-epoch mutation evidence drawn from the trace stream.
- **Graph, Frames and Hicasso do not follow the event.** Graph reads the
 process-global registrar (Declared) or the observed frame (Realized) —
 its Declared ↔ Realized projection toggle is a Graph-local control, NOT
 the L1 Dynamic/Static mode pill (the shipped UI labels the toggle
 static/live; Graph is always a Dynamic tab). Frames enumerates the
 process-global live-frame registry (the `image → frame` model). Hicasso
 re-takes its live evidence on each trace tick. "Select an epoch and
 they update" is false — only the six lenses above rebind.
- **Static's definition catalogues are process-global** — the registrar
 is shared across every frame (Spec 001); the L1 frame picker moves only
 each Static tab's per-frame *live* projections (machine snapshots, the
 flows registry, the app-db-schema side-table, the current-route slice).

## Launching Xray — pick a mode

Four launch surfaces ship: one mount facade with three open verbs
(inline / overlay / window) plus the programmatic `init!`.

| User wants to … | Use | How |
|---|---|---|
| Inspect the runtime while developing locally | **Default true-inline panel** | Add the preload + a `[data-rf-xray-host]` column in the app layout. Xray auto-opens on page load. |
| Mount where the host can't give Xray a layout column (full-screen canvas, no `[data-rf-xray-host]`) | **Overlay (fallback)** | `(xray/open-overlay!)` from CLJS, or `window.day8.re_frame2_xray.open_overlay_BANG_()` from devtools. Floats the shell above the host under `document.body`. The supported fallback — **not** the default path. |
| Put Xray on a second monitor | **Pop-out window** | Click the visible **`⛶` pop-out button** in the panel top-bar's right-icons cluster (the canonical chrome path). Secondary programmatic path: `(xray/popout!)` from CLJS / `window.day8.re_frame2_xray.popout_BANG_()` (call it — note the parens) from a console. |
| Install Xray from code (no preload) | **Programmatic `init!`** + a mount verb | Call `(xray/init! opts)` after `rf/init!` to install the foundation (it does **not** open a panel), then `(xray/open!)` / `(xray/open-overlay!)` / `(xray/popout!)` to make it visible. Idempotent. |
| Deep-link Xray to a tab / epoch / app-db path from code | **`focus!`** | `(xray/focus! {:panel :trace})`, or the fuller `{:frame :panel :epoch-id/:dispatch-id :path}` command. **Navigates an already-installed Xray — it does not install or mount** (preload / `init!` + a mount verb first). The only programmatic tab jump. |
| Browse what's *registered* instead of one dispatch | **Static mode** | Flip the L1 mode pill or press `Cmd/Ctrl+Shift+M`. |
| Have an AI agent inspect the runtime | **re-frame2-pair** | Out of scope here — see §First fork above. |
| Debug a mobile browser | **Out of scope at v1.0** | There is no mobile launch mode (`spec/011-Launch-Modes.md` lock #5). Nothing *refuses* a phone — the mount path carries no user-agent check; the mode simply doesn't exist. |

**Most common launch failure — "the panel never appeared."** Preload in,
page loaded, no inline panel = a **missing layout host**: nothing matched
`[data-rf-xray-host]` when the adapter became ready. Xray fails loudly but
safely — a `console.error` plus the same diagnostic at
**`window.day8.re_frame2_xray.status()`**. First response: check the
console / call `status()`. The three recoveries (add the column · point
the selector · fall back to `open-overlay!`) and the full decision tree
(preload vs `init!`, suppress-auto-open, resize) live in
[`references/launch-modes.md`](references/launch-modes.md); the pop-out
lifecycle is in
[`references/launch-lifecycle.md`](references/launch-lifecycle.md).

### Wired hotkeys

Four hotkey families are wired (three global, one focus-gated). Full
per-key contract + suppression knob:
[`references/launch-lifecycle.md` §Wired hotkeys](references/launch-lifecycle.md#wired-hotkeys).

| Key | Scope | Action |
|---|---|---|
| `Ctrl+Shift+C` | global | Toggle the Xray shell (`Ctrl+Shift` avoids Safari's `Cmd+Shift+C` Inspect collision). |
| `Cmd/Ctrl+Shift+M` | global | Toggle mode — Dynamic ↔ Static. |
| `Cmd/Ctrl+K` | global | Open the command palette (**wired** — don't tell users the K-binding is unavailable); opens the shell first if hidden. |
| `Space` `L` `j` `k` `G` `,`/`s` | focus-gated | Spine + chrome shortcuts, only while the shell is visible and focused. Space = pause/resume LIVE · `L` = snap to LIVE · `j`/`k` = step focused event · `G` = fast-forward to head · `,`/`s` = Settings popup. |

Only these are wired: `Esc` is modal-local, not a spine key; the
**pop-out has no hotkey** (its canonical path is the visible `⛶`
button); no `r`/`R`/`*` time-travel keys are live — the richer keymap in
the spec is normative-future.

## The chrome around the tabs

One line each; load [`references/chrome.md`](references/chrome.md) for
the control-by-control inventory.

- **L1 frame picker** — chooses *which frame* Xray observes, in both
 modes. It moves the per-frame live projections; the process-global
 catalogues read the same in every frame. Always renders; the pin is
 transient (resets on reload); tool frames (`:rf/xray`,
 `:rf/re-frame2-pair`) are filtered out unconditionally — "I can't find
 frame X in the picker" → it's a tool frame.
- **LIVE vs RETRO spine** — the L2 spine live-tails at the head until you
 pick a historical event or pause; `Space` pauses/resumes, `L` snaps back.
- **Time-travel: passive inspect vs explicit rewind** — picking an epoch
 is *passive inspection*: panels rebase, the live frame does NOT move.
 Live rewind is the separate, explicit **`Reset` button** on the L3
 ribbon — `restore-epoch!` reinstalls the focused epoch's WHOLE
 frame-state, both app-db AND runtime-db, not the `:db-after` projection
 alone.
- **Filter pills** — IN / OUT pills + an `N events filtered out` count;
 remove each pill via its trailing `×` (the bulk `Clear Filters` button
 is retired — there is none). Muted event ids are a separate state: the
 L1 `🔇 N` chip opens the mute manager (per-row unmute / `Unmute all`).
 Transient, reset on load.
- **Command palette (`Cmd/Ctrl+K`)** — fuzzy-ranked over six source kinds
 (panel jumps · recent events · frame switch · registered handlers ·
 settings · command verbs), mode-aware.
- **Settings popup (`,` / `s`)** — a 4-tab modal (General · Keybindings ·
 Buffer · Diff). **Density and panel-width are NOT popup controls** —
 density is a boot/`configure!` concern; width is the drag handle. Merge
 order is `defaults < configure! < Settings` (the popup wins at runtime
 for the slots it exposes).
- **Snapshot app-db** — the on-box share helper is the palette verb, and
 it is **redacted by default** (sensitive ⇒ `:rf/redacted`, large ⇒
 `:rf.size/large-elided`). Do not present it as a raw-`app-db` /
 secret-egress path.

## Which reference leaf to load

Routine selection questions are answerable from this body alone. A
deeper question loads at most **one** focused leaf:

| Deep question about… | Load |
|---|---|
| Launch in depth — preload, the `[data-rf-xray-host]` contract, missing-host recovery, `open-overlay!` | [`references/launch-modes.md`](references/launch-modes.md) |
| Driving Xray from code — `init!` opts, the `focus!` deep-link command | [`references/launch-programmatic.md`](references/launch-programmatic.md) |
| Pop-out lifecycle, the full hotkey contract, hidden-state semantics, disabling Xray + production posture | [`references/launch-lifecycle.md`](references/launch-lifecycle.md) |
| The full tab inventory + scope matrix + the Static catalogues (explicit "list every tab") | [`references/panels.md`](references/panels.md) |
| The Epoch cascade, Trace rows, or where issues surface | [`references/panels-epoch.md`](references/panels-epoch.md) |
| app-db sections + diffs, or Views render causes | [`references/panels-state.md`](references/panels-state.md) |
| Machine or Routes activity in depth | [`references/panels-domains.md`](references/panels-domains.md) |
| Resources (server state) in depth | [`references/panels-resources.md`](references/panels-resources.md) |
| Graph, Frames, or Hicasso in depth | [`references/panels-structure.md`](references/panels-structure.md) |
| First-screen chrome in depth — the L1 frame picker, Settings tabs, palette sources, Snapshot redaction, the rewind detail | [`references/chrome.md`](references/chrome.md) |
| The components every panel reuses + the glyph reference | [`references/shared-components.md`](references/shared-components.md) |

## Mental model (for Redux DevTools users)

If you know Redux DevTools (and the React DevTools Profiler), you know
80% of Xray: the L2 spine is the action log, except each row is an
*epoch* — a full cascade (dispatch → cofx → handler → flows → fx → subs →
views), not one reducer call; the Epoch tab is "inspect one action" with
the whole causal chain; Views is the "why did this render?" profiler tied
to the same epoch. Two deliberate inversions: time-travel is **passive by
default** (picking an epoch inspects; the explicit `Reset` button
rewinds — Redux's slider replays into the store, Xray does not), and
**Static mode** is the "what's registered?" catalogue half Redux never
had. Xray is the structural successor to **re-frame-10x** and references
it nowhere; the contract behind that claim is owned by
[`spec/Tool-Pair.md` §Implications for downstream tools](../../spec/Tool-Pair.md#implications-for-downstream-tools).

## Out of scope

- **Deep workflow recipes** (find-wrong-sub walking, redaction-marker
 grammar, click-to-source internals, branch-and-explore). Source of
 truth: [`tools/xray/spec/007-UX-IA.md`](../../tools/xray/spec/007-UX-IA.md)
 and the per-panel specs — the spec is the answer.
- **Agent runtime access** — inspecting or driving the running app on the
 user's behalf, read-only or mutating. Route to
 [`re-frame2-pair`](../re-frame2-pair/SKILL.md) (§First fork above).
- **Implementing Xray** (mount lifecycle internals, panel seams). The
 spec under `tools/xray/spec/` is the answer; no implementor skill
 exists.

## Style guidance

- **Cite the spec, don't paraphrase it.** For normative detail (the mount
 contract, the epoch pump's ordering, the redaction grammar), link the
 relevant `tools/xray/spec/*.md` and quote sparingly.
- **Pre-alpha hedge.** Some surfaces are still stabilising (the Machine
 tab's chart rendering, the Static Machines Sim engine, the schema /
 hydration inline rows when the host wired those features). The Static
 catalogues themselves are full registry browsers, not stubs. Say so and
 point at the spec when asked about an in-progress surface.
- **Don't invent controls.** Only the four hotkey families in §Wired
 hotkeys are live; cite
 [`keybinding.cljs`](../../tools/xray/src/day8/re_frame2_xray/keybinding.cljs)
 when in doubt.

---

*For the full skill-disambiguation matrix (when to use which skill) see
[`skills/README.md` §Skill routing — single source](../README.md#skill-routing--single-source).*
