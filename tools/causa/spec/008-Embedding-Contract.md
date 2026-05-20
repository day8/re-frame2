# 008-Embedding-Contract

Causa's default integration is an app-provided true-inline layout host
(`[data-rf-causa-host]`) described in
[`011-Launch-Modes.md`](./011-Launch-Modes.md). This doc covers the
**full-shell embed contract** — the canonical shape Story (per
[`spec/Tool-Pair.md`](../../../spec/Tool-Pair.md) §RHS) uses to mount
the entire 4-layer Causa shell as its right-hand-side observability
surface.

Single-panel embedding as a host-facing affordance is **not part of
the v1.0 contract**. Hosts that want per-panel mount fns reach for
the `day8.re-frame2-causa.panels/mount-<panel>!` surface enumerated in
[`007-UX-IA.md`](./007-UX-IA.md) §Mountable panel contract — that
surface is internal-but-stable (the shell composes panels through it;
tests mount panels through it) rather than a host-facing embed
contract with its own props vocabulary.

## Full-shell embed contract (Causa-as-Story-RHS)

When a host mounts the **full Causa shell** as its right-hand-side
observability surface, the host MUST surrender Causa's global
keybinding capture so its own shortcuts (typically `Cmd/Ctrl+K` for
the host's command palette) are not swallowed by Causa's capture-phase
listener:

```clojure
(causa-config/configure! {:rf.causa/keybinding-enabled? false})
```

The slot is documented in [`015-Configuration.md`](./015-Configuration.md)
§`:rf.causa/keybinding-enabled?`. Per rf2-4eyik (rf2-q7who Thread A —
embed-contract gap discovered via rf2-drprn). With the slot at `false`
Causa's `keybinding/attach!` short-circuits and no global listener
lands on `js/document`; the host's own bindings reach their handlers
unimpeded. Causa's other surfaces — the in-shell ribbon buttons,
explicit `(mount/open!)` / `(mount/toggle!)` calls, the `:rf.causa/*`
event surface — remain fully usable; only the window-level keystroke
capture is suppressed.

Hosts whose lifecycle places the `configure!` call BEFORE Causa's
preload runs (boot-time configuration) need nothing further — the
slot flip wins the read at attach time. Hosts whose mount lifecycle
runs AFTER the preload (Story's `ensure-causa-mounted!` fires at
variant-selection time) MUST additionally call
`day8.re-frame2-causa.keybinding/detach!` AFTER the slot flip:

```clojure
(causa-config/configure! {:rf.causa/keybinding-enabled? false})
(causa-keybinding/detach!)
```

`detach!` is idempotent and safe to call when nothing is attached
(no-op). Per rf2-ycrt2 (rf2-q7who.1 runtime follow-on) — the slot
declares intent but is read only at attach time; without `detach!`
the listener Causa's preload already installed under the default-true
posture stays on `js/document` and continues consuming keypresses.
The full API contract for `detach!` is documented in
[`015-Configuration.md`](./015-Configuration.md) §`keybinding/detach!`.

## Embed props inventory

The full-shell embed exposes exactly two host-visible props:

| Prop | Required | Default | Meaning |
|---|---|---|---|
| `:frame` | no | `:rf/causa` (Causa-internal default) | The frame the shell's frame-provider wraps. Hosts that need the embedded shell to read a non-default Causa-internal frame pass this through `mount-shell!`'s `opts`; in practice the default is what every shipped host uses. The shell's frame-picker UI is the canonical way to choose which *host* frame Causa observes — that selection lives in `:rf.causa/target-frame` inside `:rf/causa`'s db. |
| `:height` | no | host-CSS owned | Causa does not read a height prop. The host's stylesheet sizes the mount-point container (typically via `--rf-causa-inline-width` for inline-host width and the host's flex / grid rules for height). Listed here because hosts often think of "height" as part of the embed contract; the contract is "the host owns it". |

Both props are honoured by the **frame-provider convention** (the
`mount-<panel>!` surface from [`007-UX-IA.md`](./007-UX-IA.md)
§Mountable panel contract: every mount fn opens with
`[rf/frame-provider {:frame ...} ...]` and renders into the
host-supplied mount-point — Causa never sizes its own container). No
other host-facing props exist.

## What the host owns

When Causa is embedded full-shell, the host (Story) owns:

- **Layout.** Where the Causa shell goes on the page, its surrounding
  chrome, its size.
- **Lifecycle.** Mount / unmount of the shell. Causa's mount fn
  returns an unmount fn so the host owns teardown.
- **Frame selection.** The host selects which host frame Causa
  observes via Causa's own frame-picker UI; the host does not
  re-bind the frame from outside.
- **Keybinding capture.** Per the contract above, the host owns
  global keystrokes; Causa's chord listener is detached.

What Causa owns:

- **Shell contents.** The 4-layer chrome and every panel inside it.
- **Internal state** (selected tab, scrubber position, expand /
  collapse state, filter settings) — local to the shell instance,
  persisted via Causa's own localStorage slots.
- **Live updates** from the trace bus / epoch history.

## State isolation (Option-C frame-provider)

Causa's shell mounts **inside the host's React tree** so embedding is
zero-config — drop a `mount-shell!` call into Story / your own layout
and it renders. But Causa's *state* must never bleed into the host's
app-db, its subs, or its dispatch queue. That isolation is achieved
by an internal frame-provider wrapper; see
[`011-Launch-Modes.md`](./011-Launch-Modes.md) for the in-app overlay
context and [`007-UX-IA.md`](./007-UX-IA.md) for shell layout. The
mechanism, locked under rf2-tijr (2026-05-12):

### Frame-provider wraps the shell

Every Causa mount fn (the master `mount-shell!` and every per-panel
`mount-<panel>!` per [`007-UX-IA.md`](./007-UX-IA.md) §Mountable panel
contract) opens with an internal `[rf/frame-provider {:frame
:rf/causa} ...]`. Descendant subscriptions and dispatches re-anchor
to the `:rf/causa` frame, *not* the host's `:rf/default` (or whatever
frame the host's tree is providing). Consequences:

- **App-db isolated.** `:rf.causa/buffer-cleared` writes touch
  `:rf/causa`'s db; the host app-db is untouched.
- **Subs isolated.** A panel sub like `:rf.causa/trace-buffer` reads
  `:rf/causa`'s db.
- **Dispatches isolated.** Events fired from inside the shell run on
  `:rf/causa`'s event queue and interceptor chain.
- **Machines isolated.** Causa's machines live in `:rf/causa` and
  don't share state with host machines.

Host code never sees `:rf/causa`; the wrapper is an implementation
detail of the mount-fn surface. Story (and any other host) embeds
Causa with no awareness of the frame split.

### Registry-key isolation via `:rf.causa/*` prefix

The registrar is **process-global** — frames isolate state but share
the registrar's `{kind id}` keyspace. Causa avoids collisions by
namespacing every event-id, sub-id, fx-id, and cofx-id under
`:rf.causa/*`. A host registering `:user/login` and Causa registering
`:rf.causa/select-tab` cannot stamp on each other; the prefix is the
contract.

The convention is enforced by code review and by the registry
namespace docstring (see `tools/causa/src/day8/re_frame2_causa/registry.cljs`).

### Adapter resolution

Causa renders pure hiccup; all four supported substrates (Reagent,
Reagent-slim, UIx, Helix) accept the same hiccup shape, so the
component code itself is substrate-agnostic. Where Causa needs an
imperative escape hatch (canvas refs, mount-lifecycle hooks for large
list virtualisation, etc.) it resolves the active adapter via
`re-frame.substrate.adapter/current-adapter` and dispatches on the
returned keyword. These escape-hatch sites are bounded — roughly five
of them across the codebase — and each lives next to the component
that needs it, not in a central shim layer.

## What this doesn't do

- **No host-facing per-panel props vocabulary.** Hosts mount the full
  shell; the shell composes panels internally. The
  `mount-<panel>!` aggregator surface is documented at
  [`007-UX-IA.md`](./007-UX-IA.md) §Mountable panel contract for
  internal use (shell composition, tests, future tools); it carries
  one `opts` key — `:frame` — and is not a host-facing embed contract.
- **No two-way binding.** The host doesn't push state into Causa
  beyond the configure! slots; Causa doesn't push state back to the
  host.
- **No standalone styling overrides.** The embedded shell uses
  Causa's theme tokens. The host can wrap the shell in a container
  that overrides CSS variables (`--rf-causa-font-size`,
  `--rf-causa-accent`, `--rf-causa-inline-width`, …) but cannot
  patch shell internals.
- **No security boundary.** Causa runs in the host page's JS realm.
  If the host is untrusted, do not embed Causa.

## Future: third-party panels

v1.0 is **first-party panels only.** No plugin API, no panel registry.
Third-party-extensible panels are a v2.0 design discussion.

The current contract leaves room: every panel is already a
self-contained component with a `Panel` reg-view + `install!` shape
(per [`Conventions.md`](./Conventions.md) §Panel facade + leaf split).
A future plugin registry would `:require` a third-party namespace and
register it under a new sidebar entry with the same `Panel` shape.

No commitment is made about the third-party plugin surface shape —
the embedding contract above is for the **canonical first-party
shell**, not for any future third-party kind.

## Vision — Story ↔ Causa preset round-tripping

**Bug class:** "I built a Story variant that captures a specific
debugging posture (filters set, tab selected, pinned epoch); when
someone else opens that story, they should land in the same posture."

The full-shell embed contract above covers the **structural** wiring
Story uses to mount Causa. The next-step affordance is **deep preset
round-tripping**: when a Story variant declares `{:causa/preset {…}}`,
Causa restores **the full visible state** on mount:

- **Selected tab** (`:tab :machines`).
- **Active filters** (`:filters {:in […] :out […]}`).
- **Focused machine** + **selected instance** (for Machines tab
  embeds).
- **Pinned cascade** (`:pinned-dispatch-id <id>`) — restored if the
  cascade is still in the trace buffer at story mount; otherwise
  surfaced as a "pinned cascade aged out — re-run to recapture" hint.
- **Settings sub-state** — density, theme override per story.

The preset is **per-Story** (not per-Causa-instance); each story
carries its own preset; switching stories switches the preset.

## Vision — per-story Causa state snapshots via share-URL

Story already supports share-URLs that round-trip the story state.
Causa extends this: when a developer pins an interesting debugging
posture in a Story variant, the share-URL captures:

- The Story variant (existing).
- The Causa preset (above).
- A **trace snapshot** — the last N cascades up to and including the
  pinned focused cascade, serialised into the URL fragment (when small
  enough) or fetched from a session-local cache when the URL refers
  to a recent same-session pin.

The recipient opens the share-URL → Story renders the variant → Causa
mounts with the preset → the trace snapshot is loaded into Causa's
read-only buffer → they see exactly what the sender saw.

The snapshot is **read-only** (rewinds work; new dispatches do not
mutate the snapshot — the story's app-db is the source of truth). Lock
#4 (no session export) is preserved by scope: this is a Story-shared
state, not a free-standing Causa export.
