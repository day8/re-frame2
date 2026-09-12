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
`:rf.xray/focus-event` write that drives the single-axis spine sub
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
through the spine's own `:rf.xray/focus-event` write surface, not a
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

`mount-shell!` resolves `:frame` against the Xray-internal default and
threads it to `shell-view` as its `:frame-id` opt; `shell-view` opens
its own `[rf/frame-provider {:frame frame-id} ...]` around the 4-layer
chrome, so the full-shell mount adds no outer provider of its own. The
requested own frame is seated on the way through — the host is not asked
to pre-create it. Two embeds given distinct `:frame`s therefore hold
independent shell state (selected tab, mode, focused epoch, modals)
rather than sharing one app-db.

This is a different mechanism from the per-panel `mount-<panel>!`
surface in [`007-UX-IA.md`](./007-UX-IA.md) §Mountable panel contract,
where the mount fn itself opens the `[rf/frame-provider {:frame ...}
...]` wrapper around a panel view that has none. Both render into the
host-supplied mount-point — Xray never sizes its own container, which is
the whole of what `:height` means here. No other host-facing props
exist.

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
 :panel       <tab-id>     ; which L4 tab to surface (one of the 10 below)
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
  :resources :derivation-graph :module-view :fresco}
```

(Ten tabs — all ten Dynamic L4 tabs are focusable. The registry id for
the Routes tab is `:routing` (it RENDERS as "Routes"); a host that prefers
the visible display-noun can pass `:routes`, normalised to `:routing` via
`focus/panel-aliases`. `:derivation-graph` renders as "Graph" and
`:module-view` as "Frames". Focusability and mountability are separate
axes: all ten are focusable, and the three L4-only registry tabs — Graph,
Frames and Fresco — have no standalone `mount-*!` facade. The `:issues`
tab was removed per rf2-gbz39 — issues now surface inline in the Epoch
panel + the L2 event-row pink-wash +
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
| `:dispatch-id` | `:rf.xray/focus-event <id> <frame>` | [`018-Event-Spine.md`](./018-Event-Spine.md) §6 |
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

Embedding is zero-config — drop a `mount-shell!` call into Story / your
own layout and it renders. Xray's *state* must never bleed into the
host's app-db, its subs, or its dispatch queue, and that isolation is
achieved by an internal frame-provider wrapper; see
[`011-Launch-Modes.md`](./011-Launch-Modes.md) for the in-app overlay
context and [`007-UX-IA.md`](./007-UX-IA.md) for shell layout. The
mechanism, locked under rf2-tijr (2026-05-12):

### Three separate things, and reading them as one is the trap (rf2-5rf5)

**This section opened with "Xray's shell mounts inside the host's React
tree" and built the isolation story on it. That premise was false, and it
was false BEFORE the root swap** — `render-panel!`'s `adapter/render` made
its own React root as far back as rf2-tijr. The root swap did not falsify
the sentence; it made an already-misleading sentence conspicuous. Read the
correction as a correction of the embedding contract, not as behaviour
rf2-k97c.3 introduced.

Three things a reader is apt to collapse into one:

| | What it means | Who decides |
|---|---|---|
| **DOM placement** | Where the shell's node sits in the host's document — inside the host's layout host, or in a second window. | The host, via the mount-point / layout host. |
| **React-root ownership** | Which React root renders the shell's tree. **Never the host's.** `open!` / `open-overlay!` / `popout!` paint through a `re-frame.fresco` client root Xray owns; `panels/mount-shell!` and the per-panel `mount-<panel>!` facades call `rf.substrate.adapter/render`, which creates its OWN root at the supplied mount-point. | Xray, always. |
| **Frame selection** | Which frame descendant reads and dispatches resolve to. | Xray, explicitly — see §Own frame vs target frame. |

**DOM containment is not React containment.** React context does not cross
a root boundary, so a host's `[rf/frame-provider …]` is NOT in scope inside
the embed however deeply its node is nested — and there is no host frame for
the shell to fall through to. That is a property of React rather than of
Xray, which is why reading the mount code alone never settles it. It is
measured, not argued: `shell_fresco_boundary_dom_cljs_test`'s row
`w5-the-embed-door-mounts-and-never-touches-the-host-frames-ring` mounts the
embed at a node inside a host Reagent root that is itself under
`[rf/frame-provider {:frame app}]`, drives a real click on the embedded L3
tab bar, and asserts the host frame's epoch history is unchanged in COUNT and
in CONTENTS — beside a control showing the same ring does move for a genuine
application event.

So isolation does not rest on the frame-provider wrapper alone. The wrapper
is what makes the shell's reads and dispatches land in a NAMED frame; the
root boundary is what makes the host's frame unreachable in the first place.
Both hold independently, and the corollary is the rule in
§A tool-owned root must establish frame context deliberately: a root that
inherits nothing must SAY what frame it is in.

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

**Discovery is the FALLBACK, and only runs while the target is still
UNSELECTED (rf2-88f1).** The three sources above are not peers: an
explicit target — from host config or the picker — outranks the
mount-time policy, because discovery *guesses* which frame the operator
is looking at while an explicit target is what the host or the operator
*said*. So first open re-derives a seed frame only when nothing has
chosen one; where a choice is already in the slot, first open preserves
it and re-seeds `:epoch-history` from that frame.

The collision is new, and that is why the ordering had not needed
stating: until the host-config entry points seated `:rf/xray` themselves
(below), no target could be selected *before* first open, so discovery
never met one.
Unordered, the loss was silent both ways — a cold ring resolves to `nil`,
which `:rf.xray/set-target-frame` writes as a full RESET (clearing
`:target-frame`, `[:focus :frame]` and `:epoch-history`), and a warm ring
substitutes whichever frame happens to head the pre-open trace.

**Both host-config entry points — `set-target-frame!` (rf2-88f1) and
`init! {:target-frame …}` (rf2-bitb) — seat `:rf/xray` before they
dispatch.** The own-frame singleton is normally seated the moment the host
runtime is ready, from the preload's readiness loop (rf2-avi7) — but that
loop polls on a 50ms tick, and a host whose boot calls `rf/init!` and
re-orients the target on the same turn reaches the facade inside that
window. `init!` is the stronger case: it is the MANUAL install, the
documented alternative to the preload, so on that route the readiness loop
is not running at all and there is no eventual seat to fall back on.
Without the seat the gesture dispatched into a frame that did not exist yet
and the host got `:rf.error/frame-destroyed` instead of the target it asked
for — and because the rejection happens at dispatch, calling `open!`
immediately afterwards cannot rescue the choice.
The seat is idempotent and is itself a no-op until a substrate adapter is
installed, so it costs a live host nothing. It is the SEAT only — the
first-mount seed/hydrate fan-out stays at first open, where it can still
harvest the pre-open trace and epoch rings. A host MUST NOT reach for
`:rf/xray` itself: dispatching `[:rf.xray/set-target-frame …]` under
`(rf/with-frame :rf/xray …)` reimplements this fn without its seat, and
knowing that `:rf/xray` is Xray's frame — or that its lifecycle is tied to
adapter readiness — is not the host's business.

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

### Adapter resolution in the mount verbs

**Scoped to the mount verbs, and the heading says so deliberately.** It read
plain "Adapter resolution" until rf2-k97c.7, which invited it as a statement
about adapter resolution in Xray generally — it is not. What an adapter must
supply for Xray to work at all is §The minimum host contract below.

Since rf2-k97c.3 Xray does **not** mount through the host adapter's
`:render` at all — its shell is a `re-frame.fresco` boundary painted
through Xray's **own** React root, so the host's render shape no longer
decides whether Xray can paint. **The mount verbs (`open!`,
`open-overlay!`, `popout!`, and therefore the preload auto-open) are
INDIFFERENT to the installed adapter's render shape**: they read
`current-adapter` to learn whether a host has booted at all, never branch
on its `:kind`, and mount on the React-hook substrates (UIx, Fresco)
exactly as on the ratom family.

**This section previously specified a refusal, and rf2-k97c.4 retired
it.** While Xray painted through the host's `:render`, an element-shaped
`render` — which hands the tree to React untouched — took the hiccup
shell as raw CLJS data (fn-as-child console.error plus an uncaught
MapEntry pageerror, rf2-qgfo4). A `react-element-render-kinds` denylist
in `mount.cljs` therefore made the mount verbs refuse those hosts
cleanly: publish an `:unsupported-substrate` diagnostic through the
status API, emit one `console.warn` (not an error — the host app was
healthy), and mount nothing. Two further rulings sat on top of it, and
both are now history rather than requirement: that a Fresco page be
refused on `:rf.adapter/fresco` itself rather than riding the
`:rf.adapter/uix` entry (rf2-zkjd5, superseding rf2-wtznc's premise that
Fresco minted no kind), and that the supported render hosts were the
ratom family until Xray carried a hiccup-capable mount for the rest. The
root swap severed the coupling all of that guarded, so the guard's
precondition can no longer arise.

**`:unsupported-substrate` remains a RESERVED member of the `status`
diagnostic vocabulary and is never produced.** That surface is a public
read a host may key on, so the id is retired by reservation rather than
by deletion, and is not recycled for any other meaning. The
application-facing `:rf.error/hiccup-on-element-render-slot` raised by
the core's `make-render` is a DIFFERENT guard — aimed at application
authors handing hiccup to an element-shaped render slot — and is
unaffected.

Where Xray needs an
imperative escape hatch (canvas refs, mount-lifecycle hooks for large
list virtualisation, etc.) it resolves the active adapter via
`re-frame.substrate.adapter/current-adapter` — which answers the installed
adapter SPEC MAP — and dispatches on its `:kind` key (rf2-kuky.4:
one read, map-shaped; the map-returning `current-adapter-spec` twin is
struck, and with it `current-adapter`'s former keyword-returning
spelling — the keyword was literally the `:kind` of that same map).
These escape-hatch sites are bounded — roughly five
of them across the codebase — and each lives next to the component
that needs it, not in a central shim layer.

## The owned root

Xray owns the React root its shell paints into. That is the architecture,
not an implementation detail of one mount verb, and it is what the rest of
this section is about.

`mount.cljs` holds two `re-frame.fresco` client-root handles as
`defonce`s — one for the inline / overlay shell, one for the pop-out. Two
and not one because `render!` binds its mount-point on the FIRST call
through a handle and updates that same React root on every later one, so a
single handle cannot serve two roots, and the pop-out's root lives in a
different DOCUMENT. `render-shell!` is the one call site that knows Fresco's
door is a `render!` / `unmount!` PAIR on a handle rather than the adapter
contract's render-answers-an-unmount-fn; it closes over the handle and
answers the unmount thunk, so `switch-surface!`, `close!` and `teardown!`
keep the shape they have always had.

What the ownership buys, in one sentence each:

- **The host's render shape stops being Xray's business.** The mount verbs
  read `current-adapter` to learn whether a host has booted at all and never
  branch on its `:kind` — see §Adapter resolution in the mount verbs.
- **Xray's own painting cannot be mistaken for the application's.** A
  Fresco boundary emits no view-render trace, so the leak rf2-tqlmq fixed
  (Xray's shell-render resolving to `:rf/default` and landing in the
  inspected frame's epoch `:renders`) is now structurally absent rather than
  guarded against.
- **Isolation no longer rests on one wrapper.** Per §Three separate things,
  the root boundary makes the host's frame unreachable and the frame-provider
  names the frame the shell uses; neither substitutes for the other.

### A tool-owned root must establish frame context deliberately

**This is the rule most likely to be missed, because missing it does not show
up in a first-paint smoke test.** A root that renders nothing of the host's
inherits none of the host's React context — which is exactly the isolation
above, read from the other side. So the tool must SAY what frame it is in.
Nothing ambient will supply one, and nothing will notice that nothing did.

Concretely: `mount.cljs` renders
`[rf.fresco/frame-provider {:frame shell/default-frame-id} [ShellView …]]`
and passes no `:frame-id`, so `ShellView`'s default IS that frame; the
public `shell-view` callable wraps the same head in a provider naming the
very `:frame-id` it forwards. Both doors honour the rule by construction and
neither may drop it.

Three traps sit around it, and each is recorded because the obvious
alternative is worse:

- **`frame-provider`, never `frame-root`.** `frame-root` is an idempotent
  ENSURE that would REPLACE the frame, silently discarding the seating
  `ensure-xray-frame!` has just done. Scope, not creation — the frame is
  ensured above the provider.
- **An explicitly-framed `rf/subscribe` is not an alternative, and it fails
  SILENTLY.** `(rf/subscribe q {:frame frame-id})` is admitted inside a
  boundary body and answers the right value, but contributes zero collector
  edges — so the shell paints correctly on first render and then never
  re-renders when its slot moves. Nothing errors. Dropping the provider
  instead is LOUD (`:rf.error/no-frame-context`), which is why the provider
  is the contract and the ambient read is the mechanism.
- **Out-of-render dispatches capture the frame; they never name it.** Click
  handlers, raw window listeners and async continuations resolve through a
  captured frame-bound op (`rf/capture-frame`, or a render-time
  `rf/current-frame-id` capture threaded as a `{:frame frame}` opt) — never a
  `:rf/xray` literal and never a bare global `rf/dispatch`. See
  §Parameterized shell frame-id; a guard rejects regressions and its
  `pending-migration` allowlist is empty.

`shell-view-mode-of` is an EXAMPLE OF THIS SHAPE rather than an outstanding
debt: the root swap re-derived its positional `[2 1]` index into a walk for
the `shell/ShellView` head, and the only surviving `[2 1]` under `tools/xray`
is inside the docstring explaining what it used to be.

## The minimum host contract

**What a host adapter must supply for Xray to work on it.** Stated as a
contract rather than as an extension framework: a future browser adapter
that supplies the surface below should get Xray with no Xray-side work, and
Xray commits to asking for nothing more.

Xray asks for the **reactive-container half** of the
[`spec/006-ReactiveSubstrate.md`](../../../spec/006-ReactiveSubstrate.md)
§The adapter API contract — and nothing from the render-side half:

| Required of the adapter | Why Xray needs it |
|---|---|
| `make-state-container` | Xray's shell frame holds its chrome state in one. |
| `read-container` | Every read of the shell frame and of the inspected frame. |
| `replace-container!` | Every `:rf.xray/*` write. |
| `make-derived-value` | The projections every subscription is layered over. |
| `dispose-adapter!` | Teardown, so `teardown!` leaves nothing stranded. |
| A listener surface — `subscribe-container`, or the inline-invalidation fallback spec/006 defines for an adapter that omits it | Xray is a LIVE instrument: it must learn that the inspected frame moved. This is the half of the contract that cannot be faked by polling. |

Plus the two public activation ops in `re-frame.interop`, which Xray reaches
through the shipped Fresco collector rather than open-coding:
`activate-derived-value!` and `add-on-dispose!`. **A watch alone is not
enough on the ratom family** — a `Reaction` captures its sources only through
deref-capture, so a plain deref taken outside a render leaves it watchable,
watched, and notifying nobody. On the React-hook spine `activate-derived-value!`
no-ops, those derived values being push from construction. One code path
therefore serves both families, which is the whole reason the contract can be
stated once.

**What Xray does NOT require, and will not start requiring:**

- **`:render`.** The mount verbs own their root. This is the coupling that
  made Xray refuse element-shaped adapters, and it is severed.
- **`:make-reaction`.** A ratom-family late-bind hook. Xray observes through
  the two activation ops above, which are defined on both families.
- **`:adapter/as-element`.** PREFERRED where it answers — `substrate.cljs`
  reads the installed build's hiccup→element walk so a surviving `reg-view`
  island is rendered by the same build whose in-flight component the frame
  resolver consults — but it is published by the ratom family alone and is
  ROUTED, so it answers `nil` under any other adapter. Fresco's codec passes
  an already-built React element through untouched, which is the documented
  fallback and a REAL path rather than a defensive flourish.
- **Any view-side hook.** Nothing about how the host binds views to state
  reaches Xray.

**The evidence, and its bound.** `acceptance/uix_dom_cljs_test` runs the
epic's six behavioural criteria with the **UIx** adapter installed as the
inspected application's substrate — an element-shaped `:render` Xray cannot
use, never called to paint anything — and
`acceptance/substrate_gap_dom_cljs_test` drives the public `open!` verb on
that same adapter. Six green rows on a host whose render shape is unusable is
what "indifferent to the installed adapter" means in a form a row can witness.

**ONE HONEST EXCEPTION, and it is a door rather than the contract.** The
internal-but-stable per-panel surface — `panels/mount-shell!` and the
`mount-<panel>!` facades enumerated in
[`007-UX-IA.md`](./007-UX-IA.md) §Mountable panel contract — still delegates
to `rf.substrate.adapter/render` with a HICCUP tree, so a host reaching for
*that* door needs a `:render` that accepts hiccup, i.e. the ratom family.
The mount verbs (`open!`, `open-overlay!`, `popout!`, and the preload
auto-open) do not, and they are the host-facing contract. A host on an
element-shaped adapter uses the mount verbs.

## Rejected substrate options (recorded so they are not re-proposed)

The epic rf2-k97c weighed three alternatives to the owned root and did not
take any of them. They are recorded here with their reasons because each is a
natural thing to propose again.

**(a) Make the spine's `:render` accept hiccup.** REJECTED — wrong layer. It
solves a TOOL's problem inside the CORE, adds a hiccup converter to every
application's dependency graph whether or not that application ever loads a
tool, and weakens a deliberate app-author error: the core's `make-render`
raises `:rf.error/hiccup-on-element-render-slot` precisely so an application
author who hands hiccup to an element-shaped render slot is told so.

**(b) Relax spec/006's single-adapter ruling** so Xray could install a second
adapter for itself. REJECTED — unnecessary for either candidate design, and
the cost is badly shaped: it would make container, subscription and disposal
ownership a FRAMEWORK-WIDE problem in order to avoid a TOOL-LOCAL change.
Multiple React roots in one page are ordinary and require no second adapter
installation, which is what the owned root demonstrates.

**(c) A separately booted debugger realm** — an iframe or second window plus a
transport. NOT REJECTED, and the distinction matters: it is a real design with
genuinely stronger isolation than this problem needs. It is kept as FUTURE
WORK rather than refused, and the pop-out's own document is the nearest thing
in the tree today.

## What this doesn't do

- **No host-facing per-panel props vocabulary.** Hosts mount the full
  shell; the shell composes panels internally. The
  `mount-<panel>!` aggregator surface is documented at
  [`007-UX-IA.md`](./007-UX-IA.md) §Mountable panel contract for
  internal use (shell composition, tests, future tools); it carries
  `:frame` universally and `:instance-id` on `mount-app-db-diff!`
  (rf2-2n8q), `mount-managed-fx!` (rf2-5ykm) and `mount-trace!`
  (rf2-pua3) — the three panels whose view accepts it — and is not a
  host-facing embed contract.
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
