# 011-Launch-Modes

A developer reaches a running re-frame2 app in two complementary ways.
Only the first is Xray:

1. **In-app true-inline panel** — Xray, and Xray's whole job. It
   preloads into the dev build, waits for the substrate adapter, then
   mounts into an app-provided right-side layout host
   (`[data-rf-xray-host]` by default). The panel participates in normal
   layout, so app controls remain visible and clickable to the left.

2. **Standalone, out-of-process** — the programmer/AI story, owned by
   `re-frame2-pair.runtime` + `tools/re-frame2-pair-mcp/`, NOT by Xray
   (rf2-7htk7). An agent running on the user's machine drives the
   running browser session over MCP; Xray's panel may or may not be
   open, and loading Xray neither provides nor implies this seam.

The two modes share the **same data substrate** (the trace bus +
epoch history) because each reads the framework directly — neither is
layered on the other. The same hard rule applies to both: **the
runtime is the source of truth**; the tool observes; mutations are
explicit and user-confirmed.

This is lock #9 in [`DESIGN-RATIONALE.md`](./DESIGN-RATIONALE.md): a
**hybrid** approach. No Chrome extension; no custom WebSocket
remote-attach protocol; the in-app posture covers the local case
and MCP covers the remote case.

## In-app true-inline panel

### Layout host contract

The host app MUST provide a Xray layout host in its normal page
layout. Default selector:

```css
[data-rf-xray-host]
```

Minimal host markup (note the DOM order: `<main>` first, host
`<aside>` second — flex flow lays the aside to the right of the app
column):

```html
<div class="app-shell">
  <main id="app"></main>
  <aside data-rf-xray-host></aside>
</div>
```

Minimal layout (the `var(...)` reading of `--rf-xray-inline-width`
is the supported host-resize knob — see §Resizing the inline host
below):

```css
:root { --rf-xray-accent: #539bf5; } /* brand-accent var (rf2-9ovfb) — see below */
body { margin: 0; }
.app-shell { display: flex; min-height: 100vh; }
[data-rf-xray-host] {
  flex: 0 0 var(--rf-xray-inline-width, 560px);
  min-width: 320px;
  box-sizing: border-box;              /* the 1px border-left lives inside
                                          the documented width — without
                                          it, the host renders 1px wider
                                          than the var(...) value */
  border-left: 1px solid #2a2a2a;     /* visual separator on the app side */
}
#app { flex: 1; min-width: 0; }
```

The user-draggable resize handle is auto-injected by Xray
(rf2-70u8q; see [`007-UX-IA.md` §Resize affordance](./007-UX-IA.md#resize-affordance)).
Consumers do not wire `resize: horizontal` / `overflow: auto`; the
handle's styles and behaviour are Xray-owned.

The host owns sizing and layout. Xray owns the shell rendered inside
the host. For the framework-side Tool-Pair surfaces Xray consumes
(trace bus, epoch history, registrar queries) see
[`spec/Tool-Pair.md` §The Xray renderer](../../../spec/Tool-Pair.md#the-xray-renderer).
Hosts may override the selector before Xray opens:

```clojure
(require '[day8.re-frame2-xray.config :as xray-config])
(xray-config/configure! {:rf.xray/layout-host-selector "#devtools-xray"})
```

### Resizing the inline host

Per `rf2-um813`, the recommended host snippet reads a single CSS
custom property — `--rf-xray-inline-width` — for its `flex-basis`,
so developers can resize the inline Xray panel without forking the
host rule or falling back to overlay / body-padding dock modes.

The contract is **JS-free** and **host-owned**: Xray itself does not
read the property; the host's stylesheet does. The host's
`[data-rf-xray-host]` rule uses the variable with a default fallback
that matches Xray's recommended default (560px per rf2-9ovfb):

```css
[data-rf-xray-host] {
  flex: 0 0 var(--rf-xray-inline-width, 560px);
  min-width: 320px;
  box-sizing: border-box;
  border-left: 1px solid #2a2a2a;
}
```

To resize, override the property anywhere up the cascade — the closest
declaration wins as usual:

```css
/* Global default — every page in the app */
:root { --rf-xray-inline-width: 720px; }

/* Per-route override (e.g. a debugging route that wants more room) */
.debug-route { --rf-xray-inline-width: 960px; }

/* Per-user override via a developer stylesheet */
[data-rf-xray-host] { --rf-xray-inline-width: 380px; }
```

Sizing units are unrestricted (`px`, `rem`, `vw`, `min(...)`, `clamp(...)`,
…) — the variable is plugged straight into `flex-basis`. The
`min-width: 320px` floor in the recommended rule prevents the panel
from collapsing past readability when the developer specifies a small
value; remove the floor if you want truly unbounded shrink.

App content to the left (`#app { flex: 1; min-width: 0 }`) stays in
normal flow regardless of the inline width — Xray never overlays the
app, never claims a hit-test region outside its host, and never
mutates `body` padding in the default true-inline mode. This holds
during resize as well: changing the property triggers a flex reflow,
the host shrinks/grows, the app reflows to fill the remainder, and
nothing in the page becomes unclickable.

The property name and default are published as
`day8.re-frame2-xray.config/default-layout-host-css-var` and
`default-layout-host-width` so tooling and story-mode chrome can
refer to them without forking the string. Xray MUST NOT introduce
a runtime API that sets the property
from CLJS — the host's stylesheet is the single source of truth for
sizing; introducing a CLJS setter would split that source.

### User-draggable resize

The recommended host snippet ships two complementary resize
mechanisms, both writing the same `flex-basis` slot:

1. **CSS variable** (host-owned, fixed-point sizing) — the
   `--rf-xray-inline-width` property described above. The host's
   stylesheet sets the *initial* width and any cascade-level
   overrides (per-route, per-user, per-build). One declaration, no
   pointer events, no runtime cost. This is the path for "the team
   agreed Xray should default to 720px on the debug route."
2. **Xray drag handle** (user-controlled, auto-injected, persisted) —
   per rf2-70u8q + [`007-UX-IA.md` §Resize affordance](./007-UX-IA.md#resize-affordance),
   Xray mounts a polished handle on the panel's outer edge as soon
   as the shell renders. Pointer-driven (mouse, touch, pen unified
   via pointer events; `touch-action: none`), keyboard-navigable
   (arrow keys for fine resize, Shift for coarse, Home/End for
   clamps, Enter/Space to reset), width clamps to `[320px, 90vw]`,
   persists across reloads in the Settings slot `[:general :panel-width-px]`
   (written at runtime by the handle via the `:rf.xray/settings-update`
   event; a host boot default can bulk-set it with the one-arg map
   `configure!`, `{:rf.xray/settings {:general {:panel-width-px <px>}}}`),
   double-click to reset. This is the path for "I want a bit more room for the
   Epoch panel right now."

The two cooperate cleanly. The variable establishes the initial
size; a drag overrides it (and persists); reload restores the
persisted width unless a fresh `--rf-xray-inline-width` override up
the cascade has shifted the default.

##### Yield-to-consumer

Some teams prefer the browser-native handle (`resize: horizontal` +
`overflow: auto` on the host). Xray MUST detect that at render time
via `getComputedStyle(host).resize` and render no handle of its own
when the value is `"horizontal"` or `"both"` — the consumer wins, no
double-handle. The zero-config path (drop in `<aside>` and let Xray
inject) is the recommended default; the opt-out is the explicit
`resize:` declaration on the host. No `configure!` knob, no preload
flag.

### Inline-style cascade contract

Where a future pointer-capture implementation does write to
`--rf-xray-inline-width` from JS, the **write surface is constrained**.
Xray MUST NOT assert default values as inline styles on `<html>` (or
on the layout host element). Inline declarations beat author-normal
selectors in the CSS cascade — a default written inline would silently
shadow any `:root { --rf-xray-inline-width: ... }` the embedding app
has declared, breaking the host's right to override per §Resizing the
inline host above.

The inline-style write is reserved for **explicit user resize
gestures**. On boot, and on any "reset to default" path, the
implementation MUST `removeProperty` (or equivalent clear) on
`<html>` rather than `setProperty` a default — so the author-normal
cascade remains the source of truth for defaults, and the inline
slot is occupied only when (and for as long as) the user has expressed
an explicit size.

> Cascade trap that motivated this rule: an early resize-handle
> implementation (rf2-x8h9y) pinned the 560px default inline on
> `<html>` at boot. Test fixtures and host stylesheets that set the
> variable via `:root` were silently shadowed, producing reproducible
> Playwright failures that took hours to root-cause (rf2-6fqr5 /
> PR #1472). The rule above is the fix in spec form.

If the selector cannot be found after the substrate adapter is ready,
Xray MUST fail loudly but safely: `console.error` with the selector
and snippet above, plus the same diagnostic exposed through
`window.day8.re_frame2_xray.status()`. It MUST NOT use `alert()` and
MUST NOT block host app startup.

### Brand-accent CSS variable

Per `rf2-9ovfb`, the recommended host snippet publishes a second CSS
custom property — `--rf-xray-accent` — set on `:root` to Xray's
brand accent (`#539bf5` — GitHub blue, matching `theme/tokens.cljc`'s
`:accent` and `spec/007-UX-IA.md` §Colour system). Host
applications can read this variable from anywhere in their own
stylesheet to colour dev chrome that should harmonise with Xray:

```css
/* example: a resize-handle inset ring that matches Xray */
.my-resize-handle:active {
  box-shadow: inset 0 0 0 1px var(--rf-xray-accent);
}

/* example: a Story chip pill tinted with the brand accent at 35% */
.my-story-chip {
  background: rgb(from var(--rf-xray-accent) r g b / 0.35);
}
```

Hosts that want a tinted brand variant (e.g. an experimental theme,
or a colour-blind-friendly fork) override the property on `:root` or
any ancestor of the consumer rule:

```css
:root { --rf-xray-accent: #5570FF; }  /* swap violet → indigo */
```

The property name and default are published as
`day8.re-frame2-xray.config/default-accent-css-var` and
`default-accent` so tooling and docs generators can refer to them
without forking the string. As with
`--rf-xray-inline-width`, Xray MUST NOT introduce a CLJS API that
*sets* this property from the runtime — the host's stylesheet is the
single source of truth.

### Install

```clojure
;; shadow-cljs.edn dev build:
{:builds {:app {:devtools {:preloads [day8.re-frame2-xray.preload]}}}}
```

The preload registers Xray's listeners under `register-listener!`
and `register-epoch-listener!` and installs the browser API/keybinding; then,
once `rf/init!` has installed a substrate adapter, it seats the `:rf/xray`
frame and auto-opens into the configured layout host.

Tool-owned pages that deliberately do not allocate app layout real
estate for Xray (for example Story-only browser-test canvases) MAY
suppress only the default page-load open before `rf/init!`:

```clojure
(require '[day8.re-frame2-xray.config :as xray-config])
(xray-config/configure! {:rf.xray/auto-open? false})
```

This does not disable Xray. The collectors, browser API, keybinding,
and explicit `open!` / `toggle!` calls remain installed; if an explicit
open has no host, it MUST still emit the normal actionable missing-host
diagnostic. **The `:rf/xray` frame is still seated on adapter readiness**
(§Mount lifecycle), so such a page can drive Xray by dispatch — which is
what a tool-owned page embedding Xray's panels itself typically does. App
dev pages should keep the default `true` posture and provide
`[data-rf-xray-host]`.

### Disable

Remove the `:preloads` entry, or:

```clojure
:closure-defines {re-frame.interop/debug-enabled? false}
```

…to force-disable in dev.

### Launch

| Action | How |
|---|---|
| Auto-open | Page load after `rf/init!`, when `[data-rf-xray-host]` exists |
| Suppress auto-open on tool-only pages | `(xray-config/configure! {:rf.xray/auto-open? false})` before `rf/init!` |
| Hide/show | `Ctrl+Shift+C` |
| Legacy overlay debug mode | `window.day8.re_frame2_xray.open_overlay_BANG_()` |
| Close | `Esc` or `Ctrl+Shift+C` again |
| Pop out to second window | `window.day8.re_frame2_xray.popout_BANG_()` |

Per `rf2-sbfb7` the body-padding dock surface (`dock!` / `undock!`) and
the imperative inline-panel surface (`mount-inline-panel!` /
`unmount-inline-panel!`) were removed. The true-inline default and the
`popout!` window cover the dock use case; full-shell embedding (e.g.
Xray-as-Story-RHS) is enumerated in
[`008-Embedding-Contract.md`](./008-Embedding-Contract.md).

### Closed state

When Xray is hidden, the mount node remains in place (the inline node
in the layout host, or the overlay node under `document.body`) with
`display: none`. The app does not receive body padding, viewport
overlays, or fixed-position chrome as part of the default developer
experience. `Ctrl+Shift+C` shows the existing shell again; no React
remount is required.

The global show/hide route — the `Ctrl+Shift+C` toggle, the command
palette's "show the shell first" step, and the auto-open-on-error
watcher's reopen (see [`016-Auxiliary-Panels.md` §Auto-open-on-error
semantics](./016-Auxiliary-Panels.md)) — is surface-**preserving**: it
reopens whatever physical surface the shell was last realized on. A
hidden overlay reopens as the overlay (a CSS-only show under
`document.body`); a hidden inline shell reopens inline; the first-ever
toggle, with nothing yet mounted, defaults to the canonical inline
surface. Only the explicit `open!` / `open-overlay!` verbs CHANGE the
physical surface. So the generic reopen never silently re-parents an
overlay back inline, and — with no layout host — never fails an inline
lookup that would strand the overlay hidden.

Hosts MAY add their own launcher affordance if they want a visible
button, but that affordance is host chrome, not Xray's default launch
contract. Xray's built-in `open-overlay!` debug surface remains an
optional debug mode and must not be described as the primary path.

### Pop-out to a second window

**Launch affordances.** The canonical chrome-side path is a **visible
pop-out button** (`⛶`) in the panel top-bar / chrome ribbon's
right-icons cluster (`data-testid="rf-xray-icon-popout"`). Clicking it
dispatches `:rf.xray/popout-shell`, which lowers — via the
`:rf.xray.fx/popout-shell` effect — to `popout!`; the event/fx bridge
keeps the shell view free of a direct mount dependency, mirroring the
`✕` close button's `:rf.xray/close-shell` → `:rf.xray.fx/hide-shell`
bridge. The programmatic entry — `(xray/popout!)` from CLJS, or
`window.day8.re_frame2_xray.popout_BANG_()` from a devtools console —
is the secondary path. No keybinding is wired pre-alpha. (Pre-`rf2-czcg5`
the only chrome path was an indirect Settings → panel-position →
`:popout` radio; that radio has been removed — the visible button is
the chrome launch, not a panel-position.)

Mechanism: `window.open` whose JS realm is connected to the opener's
via `window.opener`. The pop-out renders into the new window but
**reads and dispatches against the opener's runtime atoms directly**
— no `BroadcastChannel`, no `postMessage`, no structured-clone
serialisation. Same JS realm, no protocol cost.

#### The pop-out has its own keyboard (rf2-61i5)

Runtime state crosses the realm boundary; **DOM key events do not**. A
keydown made while focus is in the pop-out window is delivered only to
listeners on the POP-OUT document, so the opener-document listener
`keybinding/attach!` installs can never see it. A pop-out therefore gets
**exactly one capture-phase `keydown` listener on its own document**,
installed by `popout!` and removed by `teardown-popout-state!` — the
single disposal path that serves an external window close, `teardown!`,
and reopen alike, so listeners cannot accumulate across open/close
cycles.

It is the SAME keyboard map, not a second one. `keybinding` exposes one
canonical handler parameterised by a **surface**; only three answers
differ between the opener and the pop-out:

| | Opener | Pop-out |
|---|---|---|
| "is this surface's shell visible?" | `mount/visible?` (reads `mount-state`) | always — the listener's lifetime *is* the pop-out shell's lifetime |
| show the shell before opening the palette | `mount/toggle!` | no-op — it is already visible |
| owns the `Ctrl+Shift+C` shell chord | yes | no |

Three consequences are normative:

- **Spine keys work in the pop-out with no inline shell open.**
  `mount/visible?` reports on the opener's in-app shell, so reusing it
  in the pop-out would leave `Space` / `L` / `j` / `k` / `Shift+G` /
  `,` / `s` dead for exactly the user who moved Xray to a second
  monitor.
- **`Cmd/Ctrl+K` opens the palette in the pop-out without mounting,
  showing, hiding or reparenting the opener's shell.** The palette's
  open state lives on `:rf/xray`, which both windows render, so it
  appears wherever a shell is on screen; what must not happen is a
  change to the OPENER's mount state.
- **`Ctrl+Shift+C` stays opener-owned.** It shows/hides the opener's
  in-app shell, a surface that does not exist in the pop-out document.
  Pressed in the pop-out it is left entirely alone — not dispatched and
  not `preventDefault`ed — so it falls through to the browser like any
  unbound key. This is the *operating* chord; the separate decision that
  **no chord LAUNCHES a pop-out pre-alpha** (above) is untouched.

Every other binding — the mode chord, the settings keys, the
editor-hint `Esc` path, and the repeat / editable / activatable / modal
guards — is shared verbatim and dispatches on `:rf/xray` exactly as it
does from the opener.

Mount reaches this listener through an **injected installer slot**
rather than a require: `keybinding` already requires `mount` (for
`visible?` / `toggle!`), so the hook is pushed down at load time
instead of pulled up, which would be a cycle. An unregistered slot
degrades to the pre-`rf2-61i5` behaviour — a pop-out with no keyboard —
and the installer re-reads `:rf.xray/keybinding-enabled?` at install
time, so an embed host that suppressed Xray's global keyboard gets no
pop-out listener either.

**Pop-out REFLECTS the opener's already-running instance; it must not
RESET it (rf2-n4p5it).** `popout!` calls `mount/ensure-xray-frame!`
with no arg — the SAME default `frame-id` the inline shell already
seeded. `ensure-xray-frame!` gates its first-mount hook fan-out (trace-
buffer seed, `:target-frame` + `:epoch-history` seed, transient-filter
reset, column-width hydrate, mode hydrate, auto-open-watcher install)
behind a `seeded-frame-ids` run-once guard keyed on `frame-id`: the
FIRST call for a frame-id runs every hook; every subsequent call for
that SAME frame-id is a no-op on the hook side (the frame
(re-)registration itself stays idempotent via `make-frame`'s surgical-
update-on-re-register semantics regardless). Without the guard, a
pop-out re-ran `::seed-trace-and-target-frame`, which re-derives the
seed frame from the CURRENT head focusable event-bundle and
re-dispatches `:rf.xray/set-target-frame` — reverting `:target-frame`
(and the `:epoch-history` ring keyed on it) back to the head frame even
when the user had already picked a different frame via the L1
switcher, jumping the inline shell's App-DB / Epoch panels off the
user's choice the instant the shell was popped out.

**Styling (the second-window stylesheet hand-off).** The pop-out window
is a distinct `document` whose `<head>` does NOT inherit the opener's
injected Xray stylesheet. The shell's inline styles and class rules all
resolve colours through `var(--rf-xray-…)` custom properties, so the
pop-out **MUST** carry Xray's stylesheet + the `:root --rf-xray-*`
custom-property definitions (and any font links) so the shell renders
identically to the inline panel rather than unstyled. Per `rf2-czcg5`
the implementation injects the full global-styles set into the pop-out's
own document (`theme/global-styles/install-into!` — fonts, motion seam,
React-Flow base sheet, the per-theme `:root` palette blocks, grain) and
mirrors the persisted theme class onto the pop-out's `<html>` so the
matching `.rf-xray-theme-*` palette (not merely the `:root` light
default) resolves. The single accent token rides in those theme palette
blocks, so the inject + theme-class write keep accent and theme in sync
in the pop-out window. The opener-gone overlay deliberately keeps reading
literal `dark-palette` hex (not `var(--rf-xray-*)`) so it remains legible
as a broken-opener fallback even on the path where injection never ran or
the substrate tree threw before the vars resolved.

Constraints inherited from the `window.opener` posture:

- Same-origin required. The pop-out window must not be opened with
  `noopener` / `noreferrer`.
- If the user closes the opener window, the pop-out becomes
  orphaned. Pop-out detects this via `window.opener.closed` and
  shows a clean "opener gone — close this window" overlay. Per
  `rf2-h3ekl` the implementation is a sibling DOM node to the shell
  root (`#rf-xray-popout-opener-gone-overlay`,
  `data-testid="rf-xray-popout-opener-gone-overlay"`) plus a
  `setInterval` watchdog that polls `window.opener` every 500ms;
  the overlay matches Xray's visual language (token-derived
  surface / text / accent colours, sans-stack typography) and uses
  plain DOM rather than the substrate render tree so it remains
  operable if the broken opener has caused a substrate throw
  mid-render. The watchdog self-clears on first reveal; the
  overlay is also torn down by `teardown-popout-state!` (test path)
  and on the popout's own `pagehide` / `unload`.
- The pop-out can't survive a hard reload of the opener — atoms get
  garbage-collected, and so does everything else Xray runs for the
  pop-out, because it ALL lives in the opener's JS realm: the render
  tree, the `popout-state` atom, and the watchdog timer above (its
  `setInterval` is registered on the OPENER's window). Re-bootstrapping
  on opener reload by re-reading `window.opener.xrayRuntime` remains
  normative-future (see the status note below): the pop-out does NOT
  re-bootstrap today. It announces instead. Per `rf2-uong`
  `popout!` registers a `pagehide` listener on the OPENER window
  (`register-opener-reload-announcer!`) which reveals the same
  opener-gone overlay while the opener's realm is still alive and
  still holds the pop-out's DOM handle. The reveal persists because
  the pop-out's own document is not reloaded. The user closes the
  stale pop-out and opens a fresh one.

  The announcer is `pagehide` ONLY — never `unload` / `beforeunload`,
  which would make the DEVELOPER'S OWN APPLICATION WINDOW ineligible
  for the back/forward cache. It ignores a `persisted` pagehide (a
  bfcache freeze a back-navigation can resume) and guards on pop-out
  window identity, as the external-close cleanup below does.

> **Status (rf2-9vm0): the `window.opener.xrayRuntime` handle in the last
> bullet is normative-future — nothing in `tools/xray/src` sets or reads
> it.** This marker is scoped to that HANDLE alone and to nothing else in
> this section. Pop-out itself **ships**, by the mechanism stated above:
> `window.open` plus same-JS-realm reads and dispatches against the
> opener's runtime atoms, with no `postMessage`, `BroadcastChannel`,
> shared worker or query-param layer. That description is accurate and
> normative — see `mount.cljs` `popout!`, `install-opener-gone-overlay!`
> and `start-opener-gone-watchdog!`. Census at tip over `tools/xray/src`:
> zero hits for `xrayRuntime` both line-oriented and whitespace-collapsed,
> against an `opener` control returning 79 and a `popout` control
> returning 165.
>
> **The bullet's second claim — the re-bootstrap — is RESOLVED, and not
> the way it was written (rf2-uong).** The reload case was real: with no
> handle there is no re-bootstrap, and `opener-gone?` is `(or (nil?
> opener) (.-closed opener))` — a same-origin hard reload leaves
> `window.opener` live (a WindowProxy survives navigation, now fronting
> the NEW realm) and `.closed` false, so the watchdog never fired and the
> pop-out kept rendering against the previous realm's atoms: silently
> stale rather than visibly broken.
>
> **Widening the predicate would not have fixed it, which is why the
> shipped answer is neither of the two shapes first proposed.** The
> watchdog does not survive the event it would be asked to detect: its
> `setInterval` timer is registered on the OPENER's window, so the reload
> that makes the pop-out stale also destroys the watchdog. After the
> reload nothing of Xray's is left running to evaluate any predicate.
> Detecting it after the fact would require Xray code in the POP-OUT's own
> realm, which today runs none — the pop-out document is opened blank and
> rendered into from the opener — so it would mean injecting a `<script>`.
>
> **What ships instead is an opener-side announcement**, described in the
> bullet above: the opener reveals the existing overlay at `pagehide`,
> while its realm is still alive and still holds the pop-out's DOM handle.
> No new pop-out-realm surface, no re-bootstrap machinery, and the
> overlay's copy now names a reload as well as a close. The
> `window.opener.xrayRuntime` handle stays normative-future per the marker
> above; nothing in the shipped behaviour depends on it.

Solves the "I want Xray on a second monitor while the app runs
full-screen" use case.

### Animation

Default inline launch is normal document layout: Xray renders inside
the host's right column and the app remains visible to the left. The
default hide/show operation is a CSS display swap on the mount node
and MUST respect `prefers-reduced-motion`.

Overlay-specific slide/dim affordances are allowed only for optional
debug modes. They MUST NOT be used to approximate the default
true-inline host contract.

### Mount lifecycle (rf2-9kkrm)

The in-app panel's first paint is paid after substrate readiness, not
at preload namespace load. The preload does foundation work first,
then auto-opens into the app-provided host when `current-adapter` is
available. The lifecycle is normative.

**Two-phase boot.** Loading the preload namespace runs the
**foundation** side-effects only:

1. Register Xray's `:rf.xray/*` handlers (subs / events / fxs)
   against the `:rf/xray` frame.
2. Register the trace collector via
   [`re-frame.trace/register-listener!`](../../../spec/009-Instrumentation.md)
   under `:rf.xray/trace-collector`.
3. Register the epoch collector via
   [`re-frame.epoch/register-epoch-listener!`](../../../spec/Tool-Pair.md#facade-vs-home-verb-the-dce-tier-rule)
   under `:rf.xray/epoch-collector` (the `:epoch`-stream home verb —
   canonical devtools attach via the home-namespace verbs, not the
   `rf/register-listener!` facade, so the preload never requires
   `re-frame.core`). `day8/re-frame2-epoch` is a hard Xray dependency, so
   the artefact is always present in an Xray build.
4. Attach a global `Ctrl+Shift+C` keydown listener on
   `document`.
5. Schedule a bounded substrate-adapter readiness probe. On readiness it
   MUST **seat the `:rf/xray` frame**, and MUST then open the default
   true-inline shell unless `:rf.xray/auto-open?` is false.

**Seating is unconditional; opening is not (rf2-avi7).** Step 1 registers
Xray's whole instruction set, but `:rf/xray` is an ordinary frame, so
`rf/make-frame` needs an installed substrate adapter and cannot run at
preload namespace load (`:rf.error/no-adapter-installed`). Adapter readiness
is therefore the earliest moment the frame can exist, and the probe of step 5
MUST seat it there whether or not it goes on to open — a host is entitled to
drive Xray purely by dispatch (`set-target-frame!`, `focus!`, the
`:rf.xray/*` events) without ever showing the shell.

Binding the seat to the open instead leaves Xray **addressable but not
writable** for the whole window between preload and first open: a dispatch
into an unseated `:rf/xray` recovers-but-emits `:rf.error/frame-destroyed`
per [Spec 002 §Run-to-completion](../../../spec/002-Frames.md), drops the
host's intent, and — because a `frame-destroyed` source-coord resolves out of
the `[:event id]` registry — names the handler's registration site rather
than the caller. For a host that suppresses auto-open the window never closes
at all. Seating stays idempotent, so a later `open!` re-seats as a no-op.

What remains lazy is the **first-mount seed/hydrate pass** (`:trace-buffer`
and `:epoch-history`, per rf2-1barg / rf2-boyc2):
it harvests the history the user produced *before* opening Xray, so it MUST
fire on first open, not at seating.

There is no view-evidence acquire step, and none is missing (rf2-l86mm).
Two predecessors sat at exactly this position: the first claimed the
donor `re-frame.ui` single-owner evidence registry, and the second was
no step at all, `re-frame.freehand.tool` being a READER with no registry
to claim. Both are gone with the substrates they read — see spec/021
§3.4.1. The Hicasso tab's door is a reader on the same terms and
acquires nothing here either.

The preload MUST NOT mount the shell synchronously during namespace
load. It MAY schedule a bounded adapter-ready retry. Once the adapter
is ready, it MUST find the configured layout host and mount the shell
there. If the host is missing, it MUST emit the diagnostic described in
§Layout host contract and leave the app running. If the installed
adapter is a React-element substrate (UIx / Hicasso — hosts whose
`:render` cannot take the hiccup shell, per
[`008-Embedding-Contract.md`](./008-Embedding-Contract.md) §Adapter
resolution), it MUST refuse the mount with the
`:unsupported-substrate` diagnostic (status API + one `console.warn`)
and leave the app running (rf2-qgfo4). **A Hicasso host is refused on its
OWN kind (rf2-zkjd5)**: `re-frame.hicasso.substrate` ships
`:kind :rf.adapter/hicasso` and the install chapter teaches it as the
default, so the refusal fires on `:rf.adapter/hicasso`. A Hicasso app that
installs UIx instead reports `:rf.adapter/uix` and is refused on that
entry. This supersedes rf2-wtznc, which recorded a Hicasso that minted no
adapter kind at all.

**The foundation side-effects fire on the preload path only (rf2-5w06uu).**
The two-phase boot above runs at the load of
`day8.re-frame2-xray.preload` (the `:devtools/preloads` entry) — the
zero-config convenience path that SHOULD self-install. The MANUAL
alternative — `(require '[day8.re-frame2-xray.core])` then `configure!`
→ `init!`/`open!` — MUST be inert until the host explicitly calls
`init!`/`open!`. Requiring the `core` facade (or any namespace
transitively required to reach `configure!`/`init!`) MUST NOT run any
of the foundation side-effects: no handler / trace / epoch registration,
no browser-global install, no keybinding attach, no auto-open scheduled. Concretely, `core` MUST NOT `:require` a namespace
whose load performs the installation side-effects — the callable install
primitives live in a side-effect-free-on-load namespace
(`day8.re-frame2-xray.install`) that both `core/init!` and the
`preload` boot block invoke; only the `preload` namespace's top-level
boot block fires them at load. This separation is what makes the
documented `configure!`-before-auto-open ordering reliable: a host that
sets `:rf.xray/auto-open? false` or `:rf.xray/keybinding-enabled? false`
via `core/configure!` BEFORE calling `init!` wins deterministically,
because nothing fired merely from requiring the facade. `core/init!`
then performs the manual install explicitly (register handlers →
register trace collector → register epoch collector → attach keybinding,
the same boot order as the foundation phase), and auto-open remains a
preload-only concern — `init!` is open-explicit (the host calls `open!`).

**Boot order.** Within the preload's foundation phase the side-
effects MUST run in the order **register-handlers → register-
trace-cb → register-epoch-listener → attach-keybinding**. The keybinding
listener is attached last so that, in the unlikely race where the
user presses `Ctrl+Shift+C` mid-load, the handlers required by the
shell render are already in the registry when the mount fires.

Within the mount phase the order MUST be **find-layout-host →
create-mount-node-in-host → substrate-render → mark-visible**. The
substrate adapter's `:render` slot is the canonical mount path
(per [`spec/006-ReactiveSubstrate.md`](../../../spec/006-ReactiveSubstrate.md)
§Render contract) — Xray MUST NOT bypass the adapter and call
React directly. The render call returns an unmount fn which the
mount machinery MUST retain for `teardown!`.

**Subsequent toggles.** Once mounted, the shell's container stays
in the DOM for the rest of the page's lifetime. Close MUST be a
CSS-only `display: none` on the mount-node — never an unmount.
Re-open MUST be a CSS-only `display: block`. This is what
preserves the <80ms first-paint on every toggle after the first
(per §Animation above): subsequent paints reuse the existing
React tree, the existing subscriptions, the existing local UI
state. A re-mount would discard internal panel state (current tab,
scroll position, selected epoch, AI-rail conversation) and miss
the toggle-paint target.

**Idempotency under hot-reload.** Every piece of mount-adjacent
state — the registration sentinels, the keybinding sentinel, the
mount-state singleton — MUST be `defonce`-guarded so that
shadow-cljs `:after-load` reruns the preload's side-effects
without double-attaching the listener, replacing the trace
callback (which would emit a console warning per
[`spec/009-Instrumentation.md`](../../../spec/009-Instrumentation.md)
§Trace callbacks), or re-creating the mount node. The mount-state
itself MUST survive reload — the user's currently-open Xray
panel MUST remain open across an `:after-load`, with its internal
state intact. This is the hot-reload story for rf2-iw5ym's
reactive-container parity: trace-buffer and mount-state share the
same "outlast the namespace reload" posture.

**Before-mount probe.** Host code (re-frame app code, tests,
adjacent tools) MUST be able to ask "is Xray currently mounted /
currently visible?" **without** forcing a mount. The mount API
exposes two read-only predicates for this purpose:

- `mounted?` — `true` iff the shell has been mounted at least once
  in the current page lifetime. May be `true` while `visible?` is
  `false` (the user opened then closed Xray).
- `visible?` — `true` iff the shell exists *and* its container is
  currently displayed (`display != none`).

Calling either predicate MUST be side-effect-free: no DOM mutation,
no substrate render, no allocation of mount-state. The probes are
the contract surface for "is Xray loaded?" introspection — tools
that decorate their output when Xray is open (e.g. story-mode
re-dispatch chips) read `visible?` and degrade gracefully when
Xray is closed or absent.

**Unmount semantics.** Production sessions never tear the shell
down — the shell lives for the page's lifetime once mounted, and
`Ctrl+Shift+C` close is a CSS hide, not an unmount. The
`teardown!` operation is **test-only**: it MUST invoke the
substrate adapter's unmount fn (returned by `:render` at mount
time), MUST remove the mount-node from its layout host, and MUST
reset the mount-state singleton to `nil` so the next test starts
from a clean slate. The unmount fn MUST be invoked inside a
swallow-errors guard — substrate adapters MAY throw on a
double-unmount and `teardown!` is the test fixture's last-chance
cleanup, not a contract-checking call site.

Per `rf2-yudol` the teardown contract covers **both** mount
singletons, not just the in-app shell:

- `mount-state` — the in-app shell (above).
- `popout-state` — the optional second-window shell. `teardown!`
  MUST invoke the popout's substrate unmount, attempt to close the
  popout window (silently tolerating "already closed"), and reset
  the singleton to `nil`. A leaked `popout-state` would short-
  circuit the next `popout!` and return a stale state map whose
  `:window` is already closed.

(The third pre-existing singleton — `inline-mounts` for the
imperative `mount-inline-panel!` debug API — went away under
`rf2-sbfb7` together with the debug API itself; full-shell embedding
under [`008-Embedding-Contract.md`](./008-Embedding-Contract.md)
covers the remaining host use cases and does not touch the mount
singleton surface.)

**Popout external-close cleanup.** Per `rf2-yudol` `popout!` MUST
register `pagehide` / `unload` listeners on the popout window so
that when the user closes the popout externally, the opener-side
`popout-state` singleton is cleared. Without this, a subsequent
`popout!` would short-circuit on the stale singleton whose
`:window` has `.closed = true` and Xray would never re-render
into a new window. The handler MUST verify the identity of the
window it was registered for (compared to the current
`popout-state.:window`) before clearing — a stale handler that
fires after a fresh `popout!` has replaced the singleton MUST NOT
nuke the new state.

**Production posture.** The whole foundation block MUST be gated
on a single dev-only sentinel (the framework's
`interop/debug-enabled?` flag, per
[`spec/009-Instrumentation.md`](../../../spec/009-Instrumentation.md)
§Production elision) so Closure DCE strips every side-effect from
production bundles compiled with `(set! goog.DEBUG false)`. The
mount module itself carries no elision logic — the call-site gate
in the preload is sufficient. If the preload is mistakenly
included in a production bundle, the trace registration is a no-op
(the framework elides the trace surface) and the mount call fails
silently because `current-adapter` is unset; the fallback is
graceful, not catastrophic.

### Epoch pump (rf2-yp92j)

The foundation phase's third step registers an
[`:epoch`-stream](../../../spec/009-Instrumentation.md#register-epoch-listener--assembled-epoch-listener)
`re-frame.epoch/register-epoch-listener!` callback (the epoch home verb — canonical
devtools attach via the home-namespace verbs, per [Tool-Pair §the DCE tier
rule](../../../spec/Tool-Pair.md#facade-vs-home-verb-the-dce-tier-rule)) under the
key `:rf.xray/epoch-collector`. Where the trace
collector buffers raw events for panel-side projections (per
[`013-Trace-Consumer.md`](./013-Trace-Consumer.md)), the epoch-collector serves
a different job: it is the **reactive pump** that keeps Xray's
cached epoch-history snapshot consistent with the framework's. Xray
cannot subscribe directly against `(rf/epoch-history target)` —
the framework atom backs a side-effecting read, not a reactive
source. Routing each settle through Xray's `:rf/xray` frame is
what makes the
[`:rf.xray/epoch-history`](./014-Registry-Catalogue.md#shared-infrastructure)
sub re-fire when the host appends an epoch. This subsection pins
the callback's contract.

**Registration key and signature.** The callback MUST be registered
under the keyword `:rf.xray/epoch-collector` (the
`:rf.xray/` namespace per
[`spec/Conventions.md`](../../../spec/Conventions.md)) with the
signature `(fn [record] ...)` where `record` is a fully-assembled
`:rf/epoch-record` per
[Spec-Schemas](../../../spec/Spec-Schemas.md#rfepoch-record). The
key is reserved — host code MUST NOT register a competing callback
under the same id (a duplicate registration would replace the
collector and silence Xray's epoch-driven panels).

**Trigger.** The callback fires for each committed record — once per
**dequeued event**, after the framework has appended the assembled
record to its per-frame `epoch-history` ring buffer. The callback runs
synchronously on the framework's emit call stack, per
[Spec 009 §Listener invocation rules](../../../spec/009-Instrumentation.md#listener-invocation-rules)
— there is no batching, no debounce, no background delivery. A
multi-event drain yields one callback invocation per settled event:
a parent event and the `:fx [[:dispatch …]]` child it queued settle
as two epochs, so the collector fires twice. The collector ALSO
re-fires with a corrected same-`:epoch-id` record when a post-settle
render / sub-run / unmount back-fills into an already-settled epoch
(this re-sync is the collector's whole purpose — Xray's epoch-driven
panels cache `epoch-history` at settle time), and it fires for
synthetic records with no dequeued event (`:rf.epoch/db-replaced`, the
terminal `:halted-destroy`). The collector therefore reconciles on
`:epoch-id`; it does NOT treat each invocation as a distinct event.

**What the callback does.** On every invocation the callback MUST
re-enter the runtime under the `:rf/xray` frame binding (via
`rf/with-frame`) and dispatch
`[:rf.xray/epoch-recorded (:frame record)]`. The event handler is
registered against `:rf/xray` (per
[`014-Registry-Catalogue.md` §Shared infrastructure](./014-Registry-Catalogue.md#shared-infrastructure))
and is responsible for the no-op-vs-update decision: when the
record's `:frame` does not match the currently-selected target
frame, the handler returns `db` unchanged; when it matches, the
handler re-reads `(rf/epoch-history target)` and writes the fresh
vector into Xray's app-db. Re-reading rather than threading the
record's contents through the dispatch arg keeps the snapshot
consistent with the framework's own view — the record is the
trigger, not the payload.

**Ordering guarantees.** The callback receives records in
**emission order** — the order in which the framework finished
draining each cascade. Per
[Spec 009 §Listener invocation rules](../../../spec/009-Instrumentation.md#listener-invocation-rules),
each listener sees events in the runtime's emit order; no
re-ordering occurs between framework emit and the collector body.
Ordering *across* sibling listeners (other tools that register
their own `register-epoch-listener!` callbacks alongside Xray) is **not
contract** — the same rule that applies to `register-listener!`
applies here. Xray's handler MUST NOT depend on the relative
order of Xray's invocation versus any other tool's.

**Frame-scoping.** The dispatch MUST be wrapped in
`(rf/with-frame :rf/xray ...)` so the resulting event handler
writes to Xray's own app-db, not the host frame's. The wrap is
load-bearing: without it, the dispatch would resolve in the
outermost-dispatch frame (typically the host's `:rf/default` per
[Spec 002 §Frame resolution](../../../spec/002-Frames.md)) and
Xray's `:rf.xray/epoch-recorded` handler — registered only
against `:rf/xray` — would miss entirely. The record's `:frame`
field (the *host* frame whose drain settled) is passed as the
dispatch arg so the handler can compare against its target-frame
sub and skip work for non-target frames; it MUST NOT be confused
with the dispatch's effective frame binding (`:rf/xray`).

**Backpressure.** None. The collector body is fire-and-forget per
[Spec 009 §Listener invocation rules](../../../spec/009-Instrumentation.md#listener-invocation-rules)
— the dispatched event enters the `:rf/xray` frame's event queue
and the callback returns immediately. The framework's emit path
MUST NOT block on Xray's dispatch draining. If the `:rf/xray`
queue is busy when an epoch settles, the new dispatch enqueues
behind the existing work and the framework moves on. Xray MUST
NOT introduce a back-pressure throttle on the framework's emit
path; the framework's epoch-cb fan-out is fire-and-forget and any
back-pressure attempt would violate
[`Principles.md`](./Principles.md) §Observation only — no new
runtime surfaces.

**No drop semantics.** Unlike the trace bus's bounded ring (per
[`013-Trace-Consumer.md`](./013-Trace-Consumer.md) §Eviction policy), the
epoch pump does **not** drop callbacks under load. Every settle
fires the callback; every callback dispatches into `:rf/xray`.
The framework's own `epoch-history` ring buffer is the only
lossy substrate in this chain — when its depth (default 50, per
[Tool-Pair](../../../spec/Tool-Pair.md)) is exceeded the oldest
epoch evicts, and Xray's next re-read picks up the post-eviction
vector. Xray MUST NOT maintain its own deeper epoch retention;
the framework's `epoch-history` is the source of truth and
Xray's cache is a pure mirror.

**Exception isolation.** An exception thrown inside the callback
body MUST be caught by the framework's epoch-cb fan-out (per
[Spec 009 §`register-epoch-listener!` invocation rules](../../../spec/009-Instrumentation.md#register-epoch-listener--assembled-epoch-listener))
and MUST NOT propagate to the framework or to other registered
epoch listeners. Xray's collector body is small (it only wraps a
dispatch); the realistic failure mode is the dispatched event
handler throwing, and that runs inside the `:rf/xray` frame's
own drain — its exception is the responsibility of the
re-frame2 error catalogue, not the epoch-cb surface.

**Idempotency.** The registration is gated by a `defonce`
sentinel (`epoch-cb-registered?`) so shadow-cljs `:after-load`
reruns of the preload do NOT re-register the callback. A
re-registration under the same key would be a no-op at the
framework level (the same-key replacement semantics) but the
sentinel suppresses it explicitly to keep the preload's
side-effect surface auditable. Test fixtures MAY call
`reset-for-test!` to drop the sentinel and drive multiple
registration cycles; production code MUST NOT.

**Epoch artefact is a hard Xray dependency.** `day8/re-frame2-epoch`
is optional *for a host app*, but it is a **hard dependency of Xray**
(xray `deps.edn`): the epoch-collector registers via the
`re-frame.epoch/register-epoch-listener!` home verb, a compile-time
dependency, so the artefact is always on the classpath in an
Xray-enabled build and the "absent-artefact" case cannot arise for a
compiled Xray. (This is why the home verb is safe here where the
`rf/register-listener! :epoch` facade — which degrades to a silent
no-op when epoch is absent — would be needed by code that must tolerate
its absence; see [Tool-Pair §the DCE tier
rule](../../../spec/Tool-Pair.md#facade-vs-home-verb-the-dce-tier-rule).)
Xray's time-travel panel still renders an empty state when
`(empty? (rf/epoch-history ...))` — but that reflects a target frame
with **no epochs recorded yet**, not an absent artefact.

**Frame-destroy handling.** When the host frame whose drain
produced an epoch is later destroyed (per
[Spec 002 §Destroy](../../../spec/002-Frames.md)), the framework
emits a one-shot
`:rf.epoch.cb/silenced-on-frame-destroy` trace event for each
`(frame, cb-id)` pair whose previously-firing callback has gone
silent (per
[Tool-Pair §Surface behaviour against destroyed frames](../../../spec/Tool-Pair.md#surface-behaviour-against-destroyed-frames)).
Xray MAY surface this trace in the event log (it flows through
the trace bus like any other event) but MUST NOT take any
additional action on it — the silencing is the framework's
contract surface; Xray's role is read-only observation. If the
destroyed frame was Xray's selected target, the
`:rf.xray/epoch-history` sub returns the last-cached vector
until the user selects a different target frame.

**Unmount cancellation.** Xray's `teardown!` operation (test-
only, per §Mount lifecycle) MUST NOT unregister the epoch
callback — the callback is registered at preload time, not at
mount time, and the preload's foundation phase persists across
shell unmounts. Test fixtures driving teardown across runs MAY
call `re-frame.epoch/unregister-epoch-listener!` for the
`:rf.xray/epoch-collector` key to unwire the pump; the
sentinel-based registration will then re-fire on the next
preload reload. Production sessions never tear down.

**Production elision.** Per
[`Principles.md`](./Principles.md) §Production elision is
non-negotiable, the entire foundation block (including the
epoch-collector registration) is gated on
`re-frame.interop/debug-enabled?` at the preload's call site.
Production builds compiled with `(set! goog.DEBUG false)` strip
the registration, the callback body, and the `:rf/xray`
event-queue entries entirely — no per-settle dispatch fires in
production. The framework's epoch surface elides under the same
gate (per
[Spec 009 §Production builds](../../../spec/009-Instrumentation.md#production-builds-zero-overhead-zero-code))
so even an accidentally-included preload would find the
register-epoch-listener! call resolve to a no-op.

## Standalone — the agent attaches to the app, not to Xray

### Mechanism

Programmer/AI access to a running app uses `tools/re-frame2-pair-mcp/` —
an stdio JSON-RPC MCP server launched by the agent host (Claude Code,
Cursor, etc.) as a subprocess. It connects over nREPL to the running
shadow-cljs build (which is connected to the user's browser) and drives
the `re-frame2-pair.runtime` preload, which reads the framework's own
instrumentation surfaces directly.

**This launch mode does not involve Xray.** Xray's preload installs the
human panel and nothing else — no agent seam, no discovery sentinel
(rf2-7htk7). A build that wants both lists both preloads; neither
implies the other. The mode is documented here because it is the other
way a developer reaches a running app, not because Xray mediates it.

The data path:

```
AI agent
  ↓ (MCP / stdio)
re-frame2-pair-mcp (Node process)
  ↓ (nREPL / bencode)
shadow-cljs JVM
  ↓ (cljs-eval / WebSocket)
browser running the user's re-frame2 app (with re-frame2-pair.runtime preloaded)
```

The agent sees the **same trace bus and epoch history** Xray-the-panel
sees, because both read the framework, not each other. Tool calls are
read-mostly; writes (`restore-epoch`, `replace-app-db`, `dispatch`) are
gated behind the server's `--allow-writes` flag and confirmed by the
agent host (typically Claude Code's tool-permission prompt).

(Per rf2-hvl1g — closure 2026-05-19 — there is no dedicated
`tools/xray-mcp/` jar. See DESIGN-RATIONALE.md Lock #6 supersedence.)

### Remote-attach

The remote case: developer A's machine runs Claude Code; developer
A's browser runs the app. With MCP attached, A's AI assistant can
query the runtime. This works **whether or not Xray's panel is
open** in the browser.

The "developer A's browser, developer B's Claude" case is **not
directly supported** at v1.0. re-frame2-pair-mcp connects to
`127.0.0.1:<nrepl-port>` by default; cross-machine MCP requires
agent-host configuration (SSH tunnels, port-forwarding) that lives
outside Xray's scope. MCP is the protocol; the network plumbing is
the user's.

### Why not a Chrome extension

Considered and rejected. The IPC overhead, the sandbox isolation,
and the manifest-v3 churn aren't worth the marginal "you don't have
to change build config" benefit. The in-app posture is the right one
for re-frame2 (lock #9, option (a) considered).

### Why not a custom WebSocket remote-attach protocol

Considered and rejected. Serialising the runtime state across the
wire is too costly — snapshot identities, machine references,
sub-graph nodes, source-coord chips don't survive JSON round-trip
cleanly. The use cases (mobile-from-desktop, cross-machine
pair-debug) are too narrow to justify the protocol-versioning,
security, and reconnect-logic surface (lock #9, option (b)
considered).

The remote case is handled by MCP (which already pays for those
costs in the agent-host ecosystem) — not by a custom Xray protocol.

### Why not a VS Code panel

Considered and rejected. Editor-embed surface is a category mistake:
debuggers belong at workstations alongside the app, not inside the
editor. Where editor integrations are useful (jump-to-source,
re-dispatch-from-IDE), they go through MCP — not through a
VS-Code-specific extension.

## Coexistence

The panel and re-frame2-pair-mcp can run simultaneously without
conflict:

- The trace bus emits once; both subscribers (panel + MCP server's
  trace listener) see every event.
- The epoch-history surface is read-mostly from both.
- Mutations from re-frame2-pair-mcp are tagged
  `:origin :re-frame2-pair-mcp`; mutations from the panel's
  re-dispatch affordance are tagged `:origin :xray`. Both are
  distinguishable in the event log.

A common workflow: the developer has the panel open for direct
inspection; the AI assistant operates on the same runtime via MCP
in parallel. The panel surfaces the agent's actions
(the `:origin :re-frame2-pair-mcp` colour-coding is visible in the
strip and event log).

## What this doesn't do

- **No Chrome extension** (rejected, see above).
- **No VS Code panel** (rejected, see above).
- **No custom WebSocket remote-attach** (replaced by MCP).
- **No standalone HTML viewer** at v1.0. re-frame2-pair-mcp replaces
  this — if the agent needs to render a viewer-like surface, it
  composes re-frame2-pair-mcp tool calls against its own
  `re-frame2-pair.runtime` preload (rf2-7htk7 — Xray publishes no
  runtime seam for it to call).
- **No mobile launch mode** (lock #5).
- **No tablet-responsive standalone viewer** at v1.0. An earlier
  design proposed one; the lock-#9 hybrid retires the use case to
  MCP.

## Default summary

| User scenario | Mode |
|---|---|
| Working locally; want to inspect the runtime | In-app panel (`Ctrl+Shift+C`) |
| Want a second monitor for Xray | In-app panel + `(xray/popout!)` |
| Want my AI to inspect / time-travel programmatically | **re-frame2-pair-mcp** (raw nREPL over MCP) |
| Want to debug a colleague's browser | Out of scope at v1.0 |
| Want to debug a mobile browser | Out of scope at v1.0 |

The 95% of cases — local development with in-app inspection — is
solved by the in-app true-inline panel. **re-frame2-pair-mcp** (raw nREPL over
MCP, sibling artefact at `tools/re-frame2-pair-mcp/`) covers the agent-driven
case. The remaining 5% (cross-machine, mobile) is explicitly out of
scope at v1.0.

Note: a dedicated **xray-mcp** path was originally envisaged but
dropped per rf2-hvl1g (closure 2026-05-19) — see
[`000-Vision.md`](000-Vision.md) §What it isn't ("two doors, no
compromises") and DESIGN-RATIONALE.md Lock #6 supersedence. The
duplicate Xray-side runtime seam went the same way (rf2-7htk7): agents
reach the app through re-frame2-pair's own preload; Xray is the human-only
observability surface. The split is intentional and load-bearing.

## Vision — re-frame2-pair raw-nREPL launch path

When a `re-frame2-pair-mcp` session is active against the same shadow-cljs
build that loaded Xray's preload, the agent can dispatch (via raw
Clojure eval) commands that drive Xray's panel through the existing
`day8.re-frame2-xray.core` namespace:

```clojure
;; Agent eval, via re-frame2-pair-mcp:
(require '[day8.re-frame2-xray.core :as xray])
(xray/open!)
(xray/target-frame :app/main)
(xray/focus-cascade <dispatch-id>)
(xray/select-tab :machines)
```

The agent uses the same primitives Xray's chrome uses. No curated
MCP facade in front; whatever the agent wants to do, it does by
evaluating Clojure against the runtime. This is the **two doors**
split in practice — Xray is the human surface; re-frame2-pair-mcp is
the AI access path; both read the same instrumentation; neither owns
a curated middle layer.

## Vision — coordination across multi-instance Xray

When a developer has multiple browser tabs each running a re-frame2
app with Xray loaded, each instance has its own preload + listener
registration + atom state. The instances do not coordinate today.

Future: a **broadcast channel** (`BroadcastChannel` API, same-origin)
lets instances share:

- **Filter posture** — IN/OUT pills set in one instance propagate to
  siblings.
- **Selected tab** — switching to Machines in one instance switches
  in siblings (opt-in via Settings → Multi-instance → "Sync tab
  selection").
- **Pinned snapshots** — pinning an epoch in one instance reflects the
  pin label in siblings as a hint (without sharing the pin store; pins
  remain per-instance for the rewind-fidelity reason in
  [`002-Time-Travel.md`](002-Time-Travel.md)).

The broadcast channel is opt-in (default off); same-origin only;
session-scoped (cleared on tab close). Surfaces in Settings →
Multi-instance with a status indicator showing how many sibling
instances are currently broadcasting.
