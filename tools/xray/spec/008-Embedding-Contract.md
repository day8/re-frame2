# 008-Embedding-Contract

Xray's default integration is an app-provided true-inline layout host
(`[data-rf-xray-host]`) described in
[`011-Launch-Modes.md`](./011-Launch-Modes.md). This doc covers the
**full-shell embed contract** — the canonical shape Story (per
[`spec/Tool-Pair.md`](../../../spec/Tool-Pair.md) §RHS) uses to mount
the entire 4-layer Xray shell as its right-hand-side observability
surface.

Single-panel embedding as a host-facing affordance is **not part of
the v1.0 contract**. Hosts that want per-panel mount fns reach for
the `day8.re-frame2-xray.panels/mount-<panel>!` surface enumerated in
[`007-UX-IA.md`](./007-UX-IA.md) §Mountable panel contract — that
surface is internal-but-stable (the shell composes panels through it;
tests mount panels through it) rather than a host-facing embed
contract with its own props vocabulary.

## Embeddable event spine — `mount-event-spine!` (rf2-9k43e)

The per-panel mount family (`007-UX-IA.md` §Mountable panel contract)
covers the L4 *detail* surfaces. It is joined by one **L2 spine** mount:

```clojure
(day8.re-frame2-xray.panels/mount-event-spine! mount-point opts) → unmount-fn
```

`mount-event-spine!` mounts the **same `shell/event-list` reg-view the
full 4-layer shell composes at L2** — the recent-events timeline
(single-line rows, latest-on-bottom) that IS Xray's canonical scrubber.
It is **not a parallel spine**: it reuses the full-shell component
verbatim, so the embedded spine inherits the row anatomy, the issue-row
wash (rf2-b8guz), the relative-time chips, virtualisation, the
ribbon-driven filters, and — load-bearing — the row-click →
`:rf.xray/focus-cascade` write that drives the single-axis spine sub
`:rf.xray/focus` (`018-Event-Spine.md` §4 + §6).

It honours the same shape as every other mount fn — installs handlers,
ensures the `:rf/xray` frame, wraps the view in `[rf/frame-provider
{:frame :rf/xray} …]` (scope-only — the frame is ensured above, not
created by the wrapper), returns an `unmount`. Per `018-Event-Spine.md`
§4 the list owns its own height via `:rf.xray/events-list-height-px`;
the host caps the visible band through its mount-point CSS (the contract
is "the host owns the container size" — §Embed props inventory).

### Why a spine mount exists — in-place past-event navigation

A host that embeds **only** an L4 detail panel (e.g. Story's RHS, which
hosts one chip-selected panel at a time) surfaces only the
final/focused event's cascade — there is no way to navigate a variant's
PAST events in-place; the workaround was the full-shell pop-out
(below). `mount-event-spine!` is the **compact in-place affordance**:
a host mounts the spine ALONGSIDE a focus-keyed detail panel in the
SAME `:rf/xray` frame; clicking a past event in the spine re-binds
`:rf.xray/focus`, and the sibling panel re-renders against the chosen
epoch IN-PLACE. The full-shell pop-out remains the deep-history escape
hatch (§Full-shell embed contract); the spine mount is the compact
in-place navigator, not a second full shell.

The spine carries no host-facing props beyond the shared `:frame` opt
(see §Frame-provider opt in `panels.cljs`'s ns docstring); focus flows
through the spine's own `:rf.xray/focus-cascade` write surface, not a
new prop vocabulary.

## Full-shell embed contract (Xray-as-Story-RHS)

When a host mounts the **full Xray shell** as its right-hand-side
observability surface, the host MUST surrender Xray's global
keybinding capture so its own shortcuts (typically `Cmd/Ctrl+K` for
the host's command palette) are not swallowed by Xray's capture-phase
listener:

```clojure
(xray-config/configure! {:rf.xray/keybinding-enabled? false})
```

The slot is documented in [`015-Configuration.md`](./015-Configuration.md)
§`:rf.xray/keybinding-enabled?`. Per rf2-4eyik (rf2-q7who Thread A —
embed-contract gap discovered via rf2-drprn). With the slot at `false`
Xray's `keybinding/attach!` short-circuits and no global listener
lands on `js/document`; the host's own bindings reach their handlers
unimpeded. Xray's other surfaces — the in-shell ribbon buttons,
explicit `(mount/open!)` / `(mount/toggle!)` calls, the `:rf.xray/*`
event surface — remain fully usable; only the window-level keystroke
capture is suppressed.

Hosts whose lifecycle places the `configure!` call BEFORE Xray's
preload runs (boot-time configuration) need nothing further — the
slot flip wins the read at attach time. Hosts whose mount lifecycle
runs AFTER the preload (Story's `ensure-xray-mounted!` fires at
variant-selection time) MUST additionally call
`day8.re-frame2-xray.keybinding/detach!` AFTER the slot flip:

```clojure
(xray-config/configure! {:rf.xray/keybinding-enabled? false})
(xray-keybinding/detach!)
```

`detach!` is idempotent and safe to call when nothing is attached
(no-op). Per rf2-ycrt2 (rf2-q7who.1 runtime follow-on) — the slot
declares intent but is read only at attach time; without `detach!`
the listener Xray's preload already installed under the default-true
posture stays on `js/document` and continues consuming keypresses.
The full API contract for `detach!` is documented in
[`015-Configuration.md`](./015-Configuration.md) §`keybinding/detach!`.

## Embed props inventory

The full-shell embed exposes exactly two host-visible props:

| Prop | Required | Default | Meaning |
|---|---|---|---|
| `:frame` | no | `:rf/xray` (Xray-internal default) | The frame the shell's frame-provider wraps. Hosts that need the embedded shell to read a non-default Xray-internal frame pass this through `mount-shell!`'s `opts`; in practice the default is what every shipped host uses. The shell's frame-picker UI is the canonical way to choose which *host* frame Xray observes — that selection lives in `:rf.xray/target-frame` inside `:rf/xray`'s db. |
| `:height` | no | host-CSS owned | Xray does not read a height prop. The host's stylesheet sizes the mount-point container (typically via `--rf-xray-inline-width` for inline-host width and the host's flex / grid rules for height). Listed here because hosts often think of "height" as part of the embed contract; the contract is "the host owns it". |

Both props are honoured by the **frame-provider convention** (the
`mount-<panel>!` surface from [`007-UX-IA.md`](./007-UX-IA.md)
§Mountable panel contract: every mount fn opens with
`[rf/frame-provider {:frame ...} ...]` and renders into the
host-supplied mount-point — Xray never sizes its own container). No
other host-facing props exist.

## What the host owns

When Xray is embedded full-shell, the host (Story) owns:

- **Layout.** Where the Xray shell goes on the page, its surrounding
  chrome, its size.
- **Lifecycle.** Mount / unmount of the shell. Xray's mount fn
  returns an unmount fn so the host owns teardown.
- **Frame selection.** The host selects which host frame Xray
  observes via Xray's own frame-picker UI; the host does not
  re-bind the frame from outside.
- **Keybinding capture.** Per the contract above, the host owns
  global keystrokes; Xray's chord listener is detached.

What Xray owns:

- **Shell contents.** The 4-layer chrome and every panel inside it.
- **Internal state** (selected tab, scrubber position, expand /
  collapse state, filter settings) — local to the shell instance,
  persisted via Xray's own localStorage slots.
- **Live updates** from the trace bus / epoch history.

## Host-facing focus API (rf2-crtmq)

The embed contract above is **structural** — how a host mounts the
shell + surrenders keybinding capture. The complementary surface is
**focus**: a host (Story) directing an already-embedded Xray surface
to focus a specific panel + epoch + cascade + app-db path, driven from
a narrative beat, a failed assertion, a canvas inspect command, or a
docs/test link.

This is a **small one-way focus command, not a two-way embedding
protocol** (StoryUI decision register §D3). It preserves the ownership
boundary:

- **Story owns the narrative / action** — it builds the command (which
  panel, which epoch, which path) plus opaque `:source` provenance, and
  calls `focus!`.
- **Xray owns the diagnostic state + panel semantics** — it receives
  the command and routes each field to the canonical `:rf.xray/*` spine
  / tab / path / frame event. Xray decides what each focus *means*.

It introduces **no second Xray runtime model.** Every command field
maps to an EXISTING write surface; the API is a thin composer over
them.

### Entry point

`day8.re-frame2-xray.focus/focus!` (re-exported as
`day8.re-frame2-xray.core/focus!`). Two arities, exactly §D3's worked
shape:

```clojure
(core/focus! command)             ; the command's own :frame scopes
(core/focus! host-frame command)  ; host-frame becomes :frame when the
                                  ; command omits one (explicit wins)
```

`focus!` fires into Xray's own `:rf/xray` shell frame (via
`re-frame.core/with-frame defaults/default-frame-id` — the same
no-surrounding-frame seam `runtime.cljs` mutations and
`spine-filters/hydrate!` use). The host never names Xray's internal
frame; the channel is the command, not the frame split.

This is **separate from open-full-Xray.** Mounting / opening /
popping-out the whole shell stays in `mount.cljs` (`open!` /
`open-overlay!` / `popout!`); `focus!` assumes the surface is already
embedded and only focuses-a-panel-on-a-beat. Per §D3:
"opening/pop-out of the full Xray shell is current; focusing a
panel/beat/path is [the new surface]."

### Command shape (the contract)

```clojure
{:frame       <frame-id>   ; the HOST frame Xray should observe (optional)
 :panel       <tab-id>     ; which L4 tab to surface (one of the 6 below)
 :epoch-id    <epoch-id>   ; settling epoch to pin the spine to
 :dispatch-id <id>         ; cascade root to pin the spine to
 :path        [<k> ...]    ; app-db path to highlight in the App-db panel
 :source      {...}        ; OPAQUE provenance — Xray echoes it back, never reads it
 :sync?       <bool>}      ; control: dispatch-sync (test rigs / same-tick flows)
```

Every field is optional; an empty command is a well-formed no-op focus.
`:panel` accepts the canonical tab ids. The **shipped**
`day8.re-frame2-xray.focus/valid-panels` set (also re-exported as
`core/valid-focus-panels`) is the single source of truth for this
enum — this doc must match it exactly, not hand-restate a divergent
list. It mirrors the live Dynamic L4 tab registry
(`panel-registry/tab-ids-for-mode :dynamic`), one entry per shipped tab
(rf2-1sddi6 / rf2-7ed9ms — a cross-check test fails the build if the two
ever drift):

```
#{:epoch :app-db :views :trace :machines :routing
  :resources :derivation-graph :module-view}
```

(Nine tabs — all nine Dynamic L4 tabs are focusable. The registry id for
the Routes tab is `:routing` (it RENDERS as "Routes"); a host that prefers
the visible display-noun can pass `:routes`, normalised to `:routing` via
`focus/panel-aliases`. `:derivation-graph` renders as "Graph" and
`:module-view` as "Modules". The `:issues` tab was removed per rf2-gbz39 —
issues now surface inline in the Epoch panel + the L2 event-row pink-wash +
the always-on issues ribbon signal, so `:issues` is no longer a focusable
panel. A host that validates a focus command against `:issues` gets
`{:ok? false :reason :unknown-panel}` from `focus!`.)

(internal registry keys per [`007-UX-IA.md`](./007-UX-IA.md) §The
4-layer chrome L3). The `:source` map is host-agnostic provenance —
§D3's worked shape is `{:kind :story/assertion :variant/id … :assertion/id …}`,
but Xray treats it as opaque, which is what keeps the channel
host-agnostic: it carries Story's intent without Xray knowing it's
Story.

### Field → canonical write surface

| Command field | Canonical Xray write | Owner |
|---|---|---|
| `:frame`       | `:rf.xray/select-frame <frame-id>` | [`007-UX-IA.md`](./007-UX-IA.md) §Frame slot contract |
| `:panel`       | `:rf.xray/select-tab <tab-id>`     | spine tab slot |
| `:epoch-id`    | `:rf.xray/focus-epoch <epoch-id>`  | [`018-Event-Spine.md`](./018-Event-Spine.md) §6 |
| `:dispatch-id` | `:rf.xray/focus-cascade <id> <frame>` | [`018-Event-Spine.md`](./018-Event-Spine.md) §6 |
| `:path`        | `:rf.xray/focus-slice-path <path>` | App-db panel slice focus |

Dispatch order is **frame-first** so the per-frame epoch ring re-seeds
(`:rf.xray/set-frame` clears the pinned dispatch-id + re-seeds
`:epoch-history`) before any epoch / cascade pin resolves; then the
spine pin, then the tab + path. When BOTH `:dispatch-id` and
`:epoch-id` are supplied the cascade pin wins (it carries the frame and
the spine derives the settling epoch from it); `:epoch-id` alone is the
lighter selector for callers that only have an epoch.

### Return shape

`focus!` returns a data-shaped result mirroring `runtime.cljs`'s
`{:ok? …}` idiom:

```clojure
{:ok? true  :applied [[:rf.xray/select-frame :checkout] …] :source {…}}
{:ok? false :reason :unknown-panel :given :app-bd :valid #{…} :hint "…"}
```

Unknown panel is the **one rejected case** — a typo'd selector would
otherwise silently land the L4 unknown-tab stub. Every other field is
permissive (a missing epoch / evicted cascade degrades through the
spine's existing placeholder UX, not an error).

### Status

`CURRENT` (rf2-crtmq). The Story-UI **consumption** of this API —
wiring narrative beats / assertion rows to call `focus!` — is owned by
the StoryUI render-shell work
([`../../story/spec/020-Story-UI-Inspector-And-Xray.md`](../../story/spec/020-Story-UI-Inspector-And-Xray.md)
§2.1) and is NOT part of this contract. The command + entry point are
the contract; how a host invokes it is the host's surface.

#### Second consumer: the testbed step-driver runner

The shared Xray testbed runner (`tools/xray/testbeds/runner/core.cljs`)
is a second host-side consumer of `focus!`, beyond Story. Its
"you-see-the-result" contract (rf2-w3ver): on each step it pins Xray
onto the just-settled **child** epoch so whatever panel the operator is
watching renders that step's record. Two idioms this consumer
establishes are normative for any host doing post-step focus-pinning:

- **Pin the epoch, NOT the tab.** The runner's focus command carries
  `:frame` + `:epoch-id` and **omits `:panel`** — so every per-epoch
  panel (App-db per-epoch-delta, the edn-inspector widget, Views,
  Routing, Machine Inspector) pivots onto the step's record while the
  operator's chosen L4 tab is preserved. Sending `:panel :epoch` would
  yank the operator off the tab they are watching; a focus-pinning host
  that wants to follow a stream of events without hijacking the tab
  drops `:panel`.
- **Pin the HEAD to stay LIVE.** Focusing the latest (head) epoch each
  step keeps the spine in `:live` mode (the `:rf.xray/focus-epoch`
  reducer derives `:live` for the head dispatch-id; see
  [`018-Event-Spine.md`](./018-Event-Spine.md) §6) — so repeated
  per-step focus never pins the spine into `:retro`.

The runner registers the focus via `re-frame.core/register-epoch-
listener!` (fired post-settle, so it observes the async child epoch the
`[:run-step n]` handler's `:dispatch` fx produces, not the `:step`-only
parent epoch) rather than calling `focus!` synchronously in the event
handler. A focus-pinning host that wants "show me the result of what I
just dispatched" follows the same post-settle-listener shape.

## State isolation (Option-C frame-provider)

Xray's shell mounts **inside the host's React tree** so embedding is
zero-config — drop a `mount-shell!` call into Story / your own layout
and it renders. But Xray's *state* must never bleed into the host's
app-db, its subs, or its dispatch queue. That isolation is achieved
by an internal frame-provider wrapper; see
[`011-Launch-Modes.md`](./011-Launch-Modes.md) for the in-app overlay
context and [`007-UX-IA.md`](./007-UX-IA.md) for shell layout. The
mechanism, locked under rf2-tijr (2026-05-12):

### Frame-provider wraps the shell

Every Xray mount fn (the master `mount-shell!` and every per-panel
`mount-<panel>!` per [`007-UX-IA.md`](./007-UX-IA.md) §Mountable panel
contract) opens with an internal `[rf/frame-provider {:frame
<frame-id>} ...]`. Descendant subscriptions and dispatches re-anchor
to that frame, *not* the host's `:rf/default` (or whatever frame the
host's tree is providing). Consequences:

- **App-db isolated.** `:rf.xray/buffer-cleared` writes touch the
  shell frame's db; the host app-db is untouched.
- **Subs isolated.** A panel sub like `:rf.xray/trace-buffer` reads
  the shell frame's db.
- **Dispatches isolated.** Events fired from inside the shell run on
  the shell frame's event queue and interceptor chain.
- **Machines isolated.** Xray's machines live in the shell frame and
  don't share state with host machines.

Host code never sees the shell frame; the wrapper is an
implementation detail of the mount-fn surface. Story (and any other
host) embeds Xray with no awareness of the frame split.

### Own frame vs target frame (EP-0002, rf2-bd4div)

Xray holds **two distinct frame concepts**, and the carried-invariant
([EP-0002](../../../docs/EP/EP-0002-frame-target-resolution.md) /
[`spec/002-Frames.md` §Frame target resolution](../../../spec/002-Frames.md))
keeps them strictly separate:

| Frame | Meaning | Source |
|---|---|---|
| **own frame** (`defaults/default-frame-id`, `:rf/xray`) | Where the shell's OWN chrome state lives — selected tab, focused epoch, theme, modal/scrubber state, the frame picker's selection. | A fixed singleton (parameterized per instance, below). Mounted explicitly by the frame-provider wrapper above. |
| **target frame** (`:rf.xray/target-frame` slot inside `:rf/xray`) | The HOST app frame Xray inspects — what the App-db / Machine / Routes / scrubber panels observe. | Selected by host config (`init! {:target-frame …}` / `set-target-frame!`), the frame picker, or the mount-time discovery policy. |

The **target frame is NOT defaulted to `:rf/default`.** Under the
carried invariant `:rf/default` is an ordinary id, never an
absence-repair fallback. The target starts **UNSELECTED** (`nil`) and
becomes selected only by one of the three sources above:

- **host config** — `(xray/init! {:target-frame :app/main})` or
  `(xray/set-target-frame! :app/main)`;
- **the frame picker** — the operator-driven ribbon dropdown
  (`:rf.xray/set-target-frame`);
- **the mount-time discovery policy** — `spine/focusable-head-frame-id`
  uniquely resolves the head app cascade's frame at first open. This is
  the operator-present interactive tier (Tool-Pair §Operating-frame
  resolution); it is **unique resolution, not synthesis** — when no
  focusable cascade exists the target stays UNSELECTED.

When the target is unselected (`:rf.xray/target-frame` → `nil`,
`:rf.xray/observed-frame` → `nil`), the panels read `nil`'s app-db
(itself `nil`) and render their unselected-target state; the frame
picker prompts a choice. `set-target-frame! nil` resets to UNSELECTED —
it no longer resets *through* `:rf/default`. The own-frame singleton
(`:rf/xray`) is **distinct** from the inspected-target migration and is
unchanged: it remains the explicit mount frame for the shell's chrome.

`init!` accepts `:target-frame` (the inspected-host opt); the legacy
`:default-frame` opt is **retired** (pre-alpha, no shim) because it
conflated own-frame and target-frame and read like the ambient
`:rf/default` fallback EP-0002 removes.

### Parameterized shell frame-id — N isolated instances (rf2-1w07r)

The shell frame is **parameterized**, not a hard singleton. `shell-
view` takes a `:frame-id` opt; `ensure-xray-frame!` takes an optional
`frame-id`. Both default to `defaults/default-frame-id` (`:rf/xray`) —
the **production singleton** path passes nothing and the in-app shell
behaves exactly as before.

Testbeds that mount **N shells side-by-side** (the panel-gallery
`:variants-grid`, a Story workspace) pass a DISTINCT `:frame-id` per
cell. Each cell's app-db — focused epoch, selected tab, theme, modal
open-state — is then fully isolated: driving one shell does not move
the others. This is the framework-native pattern the per-panel gallery
mounts already prove (each resolves its frame from the Story per-
variant `frame-provider` via React context); the parameterized shell
brings the full chrome onto the same footing.

`defaults/default-frame-id` (`:rf/xray`) is the **only** permitted bare
`:rf/xray` literal in the render tree. Every out-of-render dispatch
(affordance click handlers, raw window listeners, components rendered
outside their own provider) resolves to the surrounding **instance**
frame via a captured frame-bound op (`reg-view`'s injected `dispatch` /
`subscribe`, which the macro expands over a `capture-frame` that captures
the render frame) — never a literal and never a bare global
`rf/dispatch`. For a deeply-fanned tree of plain `defn` renderers (e.g.
the Trace / Epoch / Machine panels), the canonical idiom is a
**render-time `(rf/current-frame-id)` capture** in each leaf renderer
(the helper runs inside the panel's `reg-view` render, so
`current-frame-id` resolves through the React-context tier), passed as a
per-call `{:frame frame}` opt — cleaner than threading a `dispatch-fn`
through every intermediate fn. For ops that fire after the dynamic frame
context unwinds (async clipboard / `setTimeout` continuations, held
watcher subscriptions), capture a `(rf/capture-frame)` once and call its
`:dispatch` / `:subscribe` — the frame api survives the async boundary. The
de-singleton sweep (rf2-1w07r EPIC,
closed via rf2-nesy9) applied this end-to-end: every Xray panel, modal,
and static surface now captures its instance frame, and the
`:rf/xray`-literal / global-dispatch guard's `pending-migration`
allowlist is empty. The few production-singleton seams (trace-collector
`note-suppressed!`, per-feature `hydrate!` init) have no surrounding
render/event frame, so they target the shell via the named
`defaults/default-frame-id` Var. (The share-URL on-load restore was
another such seam until rf2-nugvv removed the whole share surface.) A `:rf/xray`-literal /
global-dispatch guard rejects regressions (see
[`017-Test-Coverage-Matrix.md`](./017-Test-Coverage-Matrix.md)).

Handlers register **globally once** under `:rf.xray/*` (the registrar
is process-global — see the next section). A second shell instance does
NOT re-register handlers; only its frame-id for app-db isolation
threads through `ensure-xray-frame!`'s first-mount seed hooks.

### Registry-key isolation via `:rf.xray/*` prefix

The registrar is **process-global** — frames isolate state but share
the registrar's `{kind id}` keyspace. Xray avoids collisions by
namespacing every event-id, sub-id, fx-id, and cofx-id under
`:rf.xray/*`. A host registering `:user/login` and Xray registering
`:rf.xray/select-tab` cannot stamp on each other; the prefix is the
contract.

The convention is enforced by code review and by the registry
namespace docstring (see `tools/xray/src/day8/re_frame2_xray/registry.cljs`).

### Adapter resolution

Xray renders pure hiccup; all four supported substrates (Reagent,
Reagent-slim, UIx, Helix) accept the same hiccup shape, so the
component code itself is substrate-agnostic. Where Xray needs an
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
- **No two-way binding.** Beyond the `configure!` slots and the
  one-way **focus command** (§Host-facing focus API — the host pushes
  a focus *intent*, not arbitrary state, and Xray owns what it means),
  the host doesn't push state into Xray; Xray never pushes state back
  to the host.
- **No standalone styling overrides.** The embedded shell uses
  Xray's theme tokens. The host can wrap the shell in a container
  that overrides CSS variables (`--rf-xray-font-size`,
  `--rf-xray-accent`, `--rf-xray-inline-width`, …) but cannot
  patch shell internals.
- **No security boundary.** Xray runs in the host page's JS realm.
  If the host is untrusted, do not embed Xray.

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

## Vision — Story ↔ Xray preset round-tripping

**Bug class:** "I built a Story variant that captures a specific
debugging posture (filters set, tab selected, pinned epoch); when
someone else opens that story, they should land in the same posture."

The full-shell embed contract above covers the **structural** wiring
Story uses to mount Xray. The next-step affordance is **deep preset
round-tripping**: when a Story variant declares `{:xray/preset {…}}`,
Xray restores **the full visible state** on mount:

- **Selected tab** (`:tab :machines`).
- **Active filters** (`:filters {:in […] :out […]}`).
- **Focused machine** + **selected instance** (for Machines tab
  embeds).
- **Pinned cascade** (`:pinned-dispatch-id <id>`) — restored if the
  cascade is still in the trace buffer at story mount; otherwise
  surfaced as a "pinned cascade aged out — re-run to recapture" hint.
- **Settings sub-state** — density, theme override per story.

The preset is **per-Story** (not per-Xray-instance); each story
carries its own preset; switching stories switches the preset.

## Vision — per-story Xray state snapshots via share-URL

Story already supports share-URLs that round-trip the story state.
Xray extends this: when a developer pins an interesting debugging
posture in a Story variant, the share-URL captures:

- The Story variant (existing).
- The Xray preset (above).
- A **trace snapshot** — the last N cascades up to and including the
  pinned focused cascade, serialised into the URL fragment (when small
  enough) or fetched from a session-local cache when the URL refers
  to a recent same-session pin.

The recipient opens the share-URL → Story renders the variant → Xray
mounts with the preset → the trace snapshot is loaded into Xray's
read-only buffer → they see exactly what the sender saw.

The snapshot is **read-only** (rewinds work; new dispatches do not
mutate the snapshot — the story's app-db is the source of truth). Lock
#4 (no session export) is preserved by scope: this is a Story-shared
state, not a free-standing Xray export.
