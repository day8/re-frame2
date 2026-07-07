# chrome — the first-screen navigation primitives around the tabs

Companion to [`panels.md`](panels.md) (the tabs) and
[`launch-modes.md`](launch-modes.md) (getting Xray visible). This leaf owns
the **control-by-control inventory** of the chrome the operator meets
before picking a tab: the LIVE/RETRO spine, time-travel (passive inspect vs
explicit rewind), the filter-pill cluster, the command palette, the
Settings popup.
[`SKILL.md` §The chrome around the tabs](../SKILL.md#the-chrome-around-the-tabs)
is the one-line router; load this when a chrome question needs more.

Source of truth for chrome layout / tokens / animation:
[`007-UX-IA.md`](../../../tools/xray/spec/007-UX-IA.md).

## L1 frame picker

The leftmost L1-ribbon cluster is the **Frame switcher** — the single
contractually-anchored surface every frame-aware feature reaches through
(the L1 picker, the Cmd-K palette's `:palette/select-frame` verb). Its face
carries the currently-selected frame id (e.g. `:rf/default ▾`), not a static
label. It chooses **which frame Xray observes**; every Dynamic tab, the L2
spine, *and* the Static catalogues rebind to the picked frame (the picker is
**mode-independent** — registries are frame-scoped, so pick the frame
whether you're in Dynamic or Static).

- **The control always renders.** On a single-frame app it is a working
  one-entry dropdown (the native select still opens and shows the lone
  frame); only a zero-frame state disables it. The button face shows the
  currently-selected frame id live, falling back to a literal `Frame ▾` only
  at cold start when no frame is pickable yet.
- **It is a TRANSIENT view scope, not a filter and NOT persisted across
  reload.** The pin is written to localStorage within a session but
  `mount.cljs/::reset-transient-filters` clears it on load, so every
  session starts at the head-frame default (per spec/007 §Frame slot
  contract). Don't tell a user their frame choice survives a
  refresh — it doesn't.
- **Tool frames are hidden unconditionally.** `:rf/xray` and
  `:rf/re-frame2-pair` are filtered out of the picker — invariant **I1**:
  Xray observes ANOTHER frame, never itself. The `:show-tool-frames?` config
  slot exists (default `false`) but is **not wired**: the picker's
  `:rf.xray/available-frames` sub hardcodes it off, so tool frames cannot
  currently be revealed from the UI (the spec's "Show tool frames in picker"
  power-user toggle in `018-Event-Spine.md` / `007-UX-IA.md` is
  normative-future, not yet wired). So "I can't find frame X in the picker"
  → it's a tool frame.

Canonical write is `:rf.xray/select-frame <frame-id>` (which re-seeds the
spine's `:target-frame` + `:epoch-history`); the picker sub is
`:rf.xray/current-frame`, the selectable set `:rf.xray/available-frames`.
Spec [`007-UX-IA.md` §Frame slot contract](../../../tools/xray/spec/007-UX-IA.md)
+ §Frame-observation isolation invariants; source
[`frame_switcher.cljs`](../../../tools/xray/src/day8/re_frame2_xray/frame_switcher.cljs).

## LIVE vs RETRO spine

The L2 spine live-tails new events at the head (**LIVE**) until you pick a
historical event or pause, which drops to **RETRO** (inspecting a past
epoch). `Space` pauses/resumes LIVE; `L` snaps back to LIVE; the head row
pulses while live. Spec
[`007-UX-IA.md` §L1](../../../tools/xray/spec/007-UX-IA.md).

## Time-travel: passive inspect vs explicit rewind

The ribbon `[◀ ▶ ⏭]` nav cluster + the L2 list walk history **without
disturbing the live app** — picking an epoch is *passive INSPECTION*
(panels rebase; the live frame does NOT move). Rewind is the *separate,
explicit* **`Reset` button** on the far-right of the L3 tab-bar ribbon:
it dispatches `:rf.xray/reset-to-epoch` against the *observed* app frame +
the focused epoch, reinstalling that frame's WHOLE frame-state — both app-db
AND runtime-db — from the epoch's `:frame-state-after` via
`(rf/restore-epoch! …)` (which installs atomically through
`replace-frame-state!`, not the `:db-after` projection alone, so machine
snapshots / the route slice / elision declarations / SSR metadata revive
alongside app-db) — disabled when no epoch is focused; on the rare framework
failure (epoch aged out) it shows a brief inline flash, never a modal.

This passive-inspect-by-default / rewind-opt-in posture is the load-bearing
inversion from re-frame-10x v1 (the lineage fact lives once in
[`../../shared/tool-pair-surfaces.md` §Supersedes re-frame-10x](../../shared/tool-pair-surfaces.md#supersedes-re-frame-10x)).
Spec [`002-Time-Travel.md`](../../../tools/xray/spec/002-Time-Travel.md)
catalogues a richer keymap — `r` rewind, `R` re-dispatch, `*` pin — that is
**normative for the future, not yet wired**; the `Reset` button is what
works today. Do not teach `r` / `R` / `*` as live keys.

## Filter pills

The L1.5 events ribbon carries IN / OUT pills, a mute set, an `N events
hidden by filters` count, and `Clear Filters`. Pills are transient and
reset on load. Each pill is a typed predicate (`:event-id-pattern` /
`:machine` / `:http-correlation` / `:fx`). Spec
[`020-Filter-Predicates.md`](../../../tools/xray/spec/020-Filter-Predicates.md);
source `src/filters/`. *(These are L1-ribbon filters — distinct from the
Trace panel, which has no filtering.)*

## Command palette (`Cmd/Ctrl+K`)

A fuzzy-ranked surface over six source kinds — **panel jumps · recent
events · frame switch · registered handlers · settings · command verbs**.
Mode-aware (Dynamic vs Static), recency-boosted; `Ctrl+Enter` pops out a
poppable item. Command verbs include Clear trace buffer, Clear epoch
history, Reset redacted-events counter, Snapshot app-db, Toggle theme,
Cycle reduced-motion, Jump to Settings, Toggle mode, Open pop-out (Cycle
display density rides the separate `settings` source). Source
[`palette/sources.cljc`](../../../tools/xray/src/day8/re_frame2_xray/palette/sources.cljc).

## Settings popup (`,` / `s`)

A 4-tab modal — **General · Keybindings · Buffer · Diff**. The current
popup controls are:

- **General** — panel position (right-rail inline / fullscreen overlay),
  auto-open-on-error, epoch-history depth (slider), the per-operator
  editor-override picker, and the show-`:ungrouped` toggle.
- **Buffer** — events-retained (writes through to `(rf/configure!
  {:trace-buffer {:events-retained N}})`) + a destructive Clear-buffer
  button.
- **Diff** — the hiccup-diff fn-ref-changes toggle.
- **Keybindings** — a read-only chord catalogue + the master "Handle
  keys?" switch.

**Density is NOT a popup control** — density is a config / `init!` /
`configure!` concern (the
`:general :density` slot + `:rf.xray/density` sub read the boot default).
**Panel width is NOT a popup control** — width is driven by the **drag
handle** on the panel's outer
edge (double-click to reset), persisted via `:general :panel-width-px`.
(There is no Theme tab — the ribbon theme icon is canonical;
there is no Filters tab — filter UI lives on the L1.5 events
ribbon.)

For the layered config story: `init!` / `configure!` set the **boot-time
defaults** for `:theme` / `:density` / `:buffer-depths` (each supplied opt
is written to the persisted Settings shape and applied immediately at boot,
no reload); for the slots the popup *does* expose (theme via the ribbon
icon, epoch-history, buffer knobs) the popup is the **runtime
user-mutable override** layer. Merge order is `defaults < configure! <
Settings`. Source
[`settings/view.cljs`](../../../tools/xray/src/day8/re_frame2_xray/settings/view.cljs).

## Snapshot app-db (the on-box share helper)

The on-box share helper is the **Snapshot app-db** command-palette verb
(`Cmd/Ctrl+K` →
"Snapshot app-db"): it puts the focused frame's `app-db` on the JS console +
clipboard so a developer can capture the state they're looking at. There is
no Share-URL affordance and no per-cascade EDN export.

**The console and the clipboard are off-box egress sinks** — the same trust
boundary the rest of the Xray / tool egress story treats as sensitive — so
the payload goes through the runtime's safe-egress projection
(`runtime/egress-value`), the same fail-closed, default-redaction path
`get-app-db` uses: `include-sensitive?` and `include-large?` both default
**`false`**, so sensitive slots ship as `:rf/redacted` and large slots as
`:rf.size/large-elided` (per
[`runtime.cljs`](../../../tools/xray/src/day8/re_frame2_xray/runtime.cljs)
`egress-value` / `get-app-db`).

Do **not** tell a user this command drops the *raw* `app-db`, and do not
present it as a way to copy a secret-bearing value off-box: a focused frame
holding `{:auth {:token "secret"}}` (or any owner-classified sensitive
path — a durable app-db path classified by the `:sensitive` commit-plane
effect, a projection-relative `:sensitive` on a subsystem definition; per
EP-0025) is redacted in the snapshot by default. Raw capture is
only available through an explicit trusted-local opt-in consistent with the
privacy vocabulary — the same `--allow-sensitive-reads` (default **OFF**)
plus per-call `:include-sensitive true` posture the AI/MCP read surfaces use
(the re-frame2-pair-mcp boundary; see `tools/re-frame2-pair-mcp/`), never the
command's default. Source
[`palette/sources.cljc`](../../../tools/xray/src/day8/re_frame2_xray/palette/sources.cljc)
(`:snapshot-app-db` command).

## Wired hotkeys

The keydown contract (the four wired hotkey families + guardrails) has a
single home:
[`launch-modes.md` §Wired hotkeys](launch-modes.md#wired-hotkeys). Source of
truth
[`keybinding.cljs`](../../../tools/xray/src/day8/re_frame2_xray/keybinding.cljs).
