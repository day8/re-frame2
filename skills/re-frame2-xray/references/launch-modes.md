# launch-modes — getting Xray visible

Source of truth: [`tools/xray/spec/011-Launch-Modes.md`](../../../tools/xray/spec/011-Launch-Modes.md).
This leaf is the decision-tree-shaped tour of what the spec normalises;
on a corner it doesn't cover, defer to the spec doc.

## Decision tree

```
Is the host app's dev build running with the Xray preload? ── no ──► §Install the preload
 │
 yes
 │
 Has `rf/init!` run? ── no ──► §Programmatic init!
 │
 yes
 │
 Is there a [data-rf-xray-host] in the page layout?
 │
 ┌──────────────┴──────────────┐
 yes no
 │ │
 ▼ ▼
 Xray auto-opened into the Xray logged the missing-host
 inline host on page load. diagnostic (console.error +
 Toggle with Ctrl+Shift+C. window.day8.re_frame2_xray.status)
 │ │
 ▼ ┌────────────┴────────────┐
 Need a second monitor? Can the host give Xray a layout column?
 │ │
 yes ┌─────────┴─────────┐
 │ yes no
 ▼ │ │
 Click the ⛶ top-bar pop-out ▼ ▼
 button (canonical) — same-origin §Layout host §Overlay fallback
 second window reading the opener's contract (open-overlay!) —
 atoms directly; (xray/popout!) (add the column) floats above the host,
 is the secondary code path. no column needed.
```

## Install the preload

The default path. Add the preload namespace to shadow-cljs's
`:devtools/preloads`:

```clojure
;; shadow-cljs.edn — dev build only
{:builds {:app {:devtools {:preloads [day8.re-frame2-xray.preload]}}}}
```

The preload runs five foundation side-effects (per
[`spec/011-Launch-Modes.md` §Mount lifecycle](../../../tools/xray/spec/011-Launch-Modes.md);
matching the `init!` docstring's enumeration in `core.cljs`):

1. Register `:rf.xray/*` handlers against the `:rf/xray` frame.
2. Register the trace collector via `register-listener!` under
 `:rf.xray/trace-collector`.
3. Register the epoch collector via `register-epoch-listener!` under
 `:rf.xray/epoch-collector` (no-op when the
 `day8/re-frame2-epoch` artefact is absent).
4. Install the `window.day8.re_frame2_xray.*` browser-API exports
 (`install-browser-api-exports!`) — the console verbs `open_BANG_` /
 `open_overlay_BANG_` / `popout_BANG_` the devtools paths below invoke.
5. Attach the global keydown listener (one capture-phase handler routing
 `Ctrl+Shift+C` shell-toggle, `Cmd/Ctrl+Shift+M` mode-toggle,
 `Cmd/Ctrl+K` command-palette, and the focus-gated spine keys).

It schedules an auto-open into `[data-rf-xray-host]` once
`current-adapter` is ready. The preload MUST NOT mount synchronously
during namespace load; it MAY schedule a bounded adapter-ready retry.

Idempotency: every step is `defonce`-guarded. shadow-cljs `:after-load`
reruns are safe — no double-attached listeners, no double-mount, no
"already registered" warnings.

## Layout host contract

The host app provides a Xray column in its normal layout. Default
selector: `[data-rf-xray-host]`. Minimal markup (DOM order matters —
`<main>` first, host `<aside>` second, so flex puts the aside on the
right):

```html
<div class="app-shell">
 <main id="app"></main>
 <aside data-rf-xray-host></aside>
</div>
```

```css
:root { --rf-xray-accent: #7C5CFF; } /* brand-accent var */
body { margin: 0; }
.app-shell { display: flex; min-height: 100vh; }
[data-rf-xray-host] {
 flex: 0 0 var(--rf-xray-inline-width, 560px);
 min-width: 320px;
 box-sizing: border-box;
 border-left: 1px solid #2a2a2a;
}
#app { flex: 1; min-width: 0; }
```

The host owns sizing and layout; Xray owns the shell rendered inside
the host. Override the selector before Xray opens:

```clojure
(require '[day8.re-frame2-xray.config :as xray-config])
(xray-config/configure! {:rf.xray/layout-host-selector "#devtools-xray"})
```

### Resizing the host

The recommended CSS reads `--rf-xray-inline-width` for its
`flex-basis`. Two cooperating resize mechanisms:

1. **CSS variable** — host-owned, fixed-point sizing. Set the initial
 width or override per route/per build via the cascade. One
 declaration, no listeners.
 ```css
 :root { --rf-xray-inline-width: 720px; } /* global default */
 .debug-route { --rf-xray-inline-width: 960px; } /* per route */
 ```
2. **Xray drag handle** — auto-injected by Xray on the panel's
 outer edge. Pointer-driven (mouse, touch, pen via pointer events),
 keyboard-navigable, persisted across reloads in the Settings slot
 `[:general :panel-width-px]` (written at runtime by the resize handle
 via the dedicated `:rf.xray/set-panel-width-px` event — it dual-writes
 the same `:general :panel-width-px` slot the `:rf.xray/settings-update`
 event uses), clamped to `[320px, 90vw]`,
 double-click to reset. Hosts that want a boot-time default can bulk-set
 the slot through the one-arg map `configure!`:
 `(xray-config/configure! {:rf.xray/settings {:general {:panel-width-px 720}}})`.

Both mechanisms write the same `flex-basis` slot. Consumers that
prefer the browser-native handle opt out by setting `resize:
horizontal` on the host; Xray detects that via `getComputedStyle` at
render time and yields (no double-handle). Xray MUST NOT set the
variable from CLJS — the host's stylesheet is the single source of
truth for the *initial* width.

### Suppress auto-open

Tool-owned pages that deliberately do not allocate layout space for
Xray (Story-only browser-test canvases, headless probe pages) MAY
suppress only the default page-load open before `rf/init!`:

```clojure
(xray-config/configure! {:rf.xray/auto-open? false})
```

This does not disable Xray. The collectors, browser API, keybinding,
and explicit `open!` / `toggle!` calls remain installed; an explicit
open with no host still emits the actionable missing-host diagnostic.
App dev pages should keep the default `true` posture and provide
`[data-rf-xray-host]`.

### Disable entirely

Two distinct levers — pick the one that matches your intent:

- **Xray-specific disable** — remove Xray's preload entry from
  `:devtools/preloads`. The framework's instrumentation surface (trace
  bus, epoch history) stays live for other tools; only Xray's foundation
  block (collector/epoch-cb registration, keybinding, browser API,
  auto-open) is gone.

- **Build-wide debug elision** — set `goog.DEBUG false`. This is the
  canonical CLJS production flag; it gates *every* dev-only branch,
  including Xray's. `re-frame.interop/debug-enabled?` is an alias of
  `goog.DEBUG` (`(def ^boolean debug-enabled? "@define {boolean}"
  ^boolean goog/DEBUG)`), so you closure-define `goog.DEBUG`, **not**
  `re-frame.interop/debug-enabled?` directly.

```edn
;; shadow-cljs.edn — build-wide debug elision (production posture)
{:builds {:app {:target           :browser
                :compiler-options {:closure-defines {goog.DEBUG false}}}}}
```

## Missing host — diagnostic + recovery

The single most common "Xray didn't open" cause: the preload ran but no
element matched the layout-host selector when the substrate adapter became
ready. Per [`spec/011-Launch-Modes.md`](../../../tools/xray/spec/011-Launch-Modes.md)
Xray **must fail loudly but safely** — it MUST NOT `alert()` and MUST NOT
block host app startup. The same diagnostic lands in two places (source
[`mount.cljs`](../../../tools/xray/src/day8/re_frame2_xray/mount.cljs)
`missing-host-diagnostic` / `report-diagnostic!`):

1. **`console.error`** — names the expected selector + the host snippet to
   add.
2. **`window.day8.re_frame2_xray.status()`** — the inspectable mount/API
   status map (wired by `install.cljs`, defined at `mount.cljs` `status`):

   ```clojure
   {:mounted?      false
    :visible?      false
    :mode          nil
    :diagnostic    {:ok? false
                    :reason :missing-layout-host
                    :selector "[data-rf-xray-host]"
                    :message "Xray default launch requires an app-provided …"
                    :snippet  "…the recommended host markup…"}
    :host-selector "[data-rf-xray-host]"
    :auto-open?    true}
   ```

An explicit `(xray/open!)` / `(xray/toggle!)` against a missing host
**returns the same diagnostic map** (and logs the `console.error`) rather
than throwing — so a programmatic caller can branch on `:ok?`.

Three recoveries, in order of preference:

- **Add the `[data-rf-xray-host]` column** to your app layout (§Layout host
  contract above) — the default fix.
- **Point the selector** at an element you already have:
  `(xray-config/configure! {:rf.xray/layout-host-selector "#my-host"})`
  before Xray opens.
- **Fall back to the overlay** — `(xray/open-overlay!)` (§Overlay fallback
  below) when the host genuinely cannot give a column.

Distinct from this is the **dev-build posture banner** — a dismissable
yellow "Xray is enabled in this build" top banner shown when a non-elided
dev build runs in production-like conditions (§Production posture below).
That is a *warning to elide for production*, not the missing-host failure;
don't conflate the two.

## Overlay fallback (open-overlay!)

For hosts that **cannot accommodate a right column** — a full-screen
canvas, a story-only or prototype host, any page with no
`[data-rf-xray-host]` — the supported fallback is the overlay mount verb.
It floats the shell above the host under `document.body`, so it needs no
layout column.

```clojure
(require '[day8.re-frame2-xray.core :as xray])
(xray/open-overlay!)
;; or, from a devtools console:
;; window.day8.re_frame2_xray.open_overlay_BANG_()
```

`open-overlay!` is one of the mount facade's three open verbs
(`open!` inline · `open-overlay!` modal overlay · `popout!` window — per
[`spec/API.md` §Mount facade](../../../tools/xray/spec/API.md), three
distinct mount surfaces, not modal variants of one shape). It is the
**optional, non-default** path: the inline panel is the canonical
developer experience, and per
[`spec/011-Launch-Modes.md`](../../../tools/xray/spec/011-Launch-Modes.md)
the overlay "remains an optional debug mode and must not be described as
the primary path." Prefer the `[data-rf-xray-host]` column when the host
can give one; reach for `open-overlay!` only when it can't. Source
[`mount.cljs`](../../../tools/xray/src/day8/re_frame2_xray/mount.cljs)
(`open-overlay!`), exported via
[`core.cljs`](../../../tools/xray/src/day8/re_frame2_xray/core.cljs).

## Programmatic init!

Alternative to the preload's **foundation block** — call `(xray/init! opts)`
from app code after `rf/init!`. Idempotent; each underlying side-effect is
`defonce`-guarded so a second call is a no-op.

`init!` installs the foundation and applies config; it does **not** show a
panel. It registers the `:rf.xray/*` handlers, the trace + epoch collectors,
the `window.day8.re_frame2_xray.*` browser-API exports, and the keybinding
listener, then threads each supplied opt to its backing
surface (per the `init!` docstring in
[`core.cljs`](../../../tools/xray/src/day8/re_frame2_xray/core.cljs)).
Unlike the preload it does **not** schedule the page-load auto-open, so after
`init!` you must call one of the three mount verbs to make Xray visible:

- `(xray/open!)` — inline panel into the `[data-rf-xray-host]` column.
- `(xray/open-overlay!)` — overlay above `document.body` for hosts with no
 layout column (§Overlay fallback above).
- `(xray/popout!)` — same-origin second window (§Pop-out below). In-app, the
 visible `⛶` top-bar button is the canonical chrome equivalent; this verb is
 the secondary programmatic path.

(`Ctrl+Shift+C` also mounts the shell on first press — so init!-then-hotkey
works too.)

```clojure
(require '[day8.re-frame2-xray.core :as xray])

(xray/init!
 {:target-frame :app/main ; observed frame for the spine
 :theme :dark ; / :light (settings persist)
 :density :compact ; / :cosy (settings persist)
 :buffer-depths {:epoch 50}}) ; per-frame ring depth

(xray/open!) ; make it visible — init! installs but does not show
```

All four opts are wired today (the recognised set is exactly
`:target-frame :theme :density :buffer-depths`, per the `init!` docstring
in [`core.cljs`](../../../tools/xray/src/day8/re_frame2_xray/core.cljs)):

- `:target-frame` dispatches `:rf.xray/set-target-frame`.
- `:theme` / `:density` write the persisted Settings shape and apply the
 matching class / font-size immediately (no reload).
- `:buffer-depths` honours `{:epoch <n>}` only — it drives the substrate's
 per-frame ring (depth + trace-keep to the same `n`). A `:trace` axis is
 **silently dropped** (folded into the one `:epoch` knob).

Unknown opt keys are silently ignored for forward-compat — so an `init!`
that worked against a newer Xray won't break an older one. (There is **no**
`:ai-provider` opt — AI access is the separate `re-frame2-pair-mcp` MCP
server (Node/npm), not an `init!` knob.) See the `core.cljs` `init!` docstring for the
authoritative per-opt contract.

## Pop-out to a second window

Solves "I want Xray on a second monitor while the app runs
full-screen." Same-origin required.

**Launch affordances.** The canonical chrome-side path is the visible
**`⛶` pop-out button** (`data-testid="rf-xray-icon-popout"`) in the panel
top-bar / chrome-ribbon's right-icons cluster. Clicking it dispatches
`:rf.xray/popout-shell`, which lowers — via the `:rf.xray.fx/popout-shell`
effect — to `mount/popout!` (the event/fx bridge keeps the shell view free
of a direct mount dependency, mirroring the `✕` close button's
`:rf.xray/close-shell` → `:rf.xray.fx/hide-shell` bridge; spec/011 §Pop-out +
spec/018 §3). The programmatic entry is the **secondary** path:

```clojure
(xray/popout!)
;; or, from a devtools console (note the call parens — the API installs
;; popout_BANG_ as a FUNCTION; without () you just evaluate the fn object
;; and nothing opens):
;; window.day8.re_frame2_xray.popout_BANG_()
```

Mechanism: `window.open` whose JS realm is connected to the opener's
via `window.opener`. The pop-out renders into the new window but
**reads and dispatches against the opener's runtime atoms directly** —
no `BroadcastChannel`, no `postMessage`, no structured-clone
serialisation cost.

Caveats inherited from the `window.opener` posture:

- Same-origin required; do not open with `noopener` / `noreferrer`.
- If the user closes the opener, the pop-out becomes orphaned. Pop-out
 detects this via `window.opener.closed` and shows a clean
 "opener gone — close this window" overlay.
- The pop-out can't survive a hard reload of the opener — atoms get
 garbage-collected; the pop-out re-bootstraps via
 `window.opener.xrayRuntime` on opener reload.
- No keybinding is wired pre-alpha. The visible `⛶` top-bar button is the
 canonical chrome launch; the programmatic call is the secondary path.

## Wired hotkeys

Four hotkey families have keydown listeners attached today
(`keybinding.cljs`). Three are **global**; the fourth is **focus-gated**
(fires only inside the Xray shell, on non-editable, non-modal targets):

| Key | Scope | What it does |
|---|---|---|
| `Ctrl+Shift+C` | global | Toggle the Xray shell (mount on first press; CSS show/hide thereafter). `Ctrl+Shift` avoids Safari's `Cmd+Shift+C` Inspect collision on macOS. |
| `Cmd/Ctrl+Shift+M` | global | Toggle mode — Dynamic ↔ Static (`:rf.xray/toggle-mode`). Cmd on macOS, Ctrl elsewhere. |
| `Cmd/Ctrl+K` | global | Open the command palette (`:rf.xray/palette-toggle`); opens the shell first if it's hidden. Cmd on macOS, Ctrl elsewhere. |
| `Space` `L` `j` `k` `G` `,`/`s` | focus-gated | Spine + chrome shortcuts. Space = pause/resume LIVE · `L` = snap to LIVE · `j`/`k` = step focused event back/forward · `G` (Shift+G) = fast-forward to head · `,` or `s` = Settings popup. (`Esc` is **not** a wired spine key — it is a modal-local close handler owned by the palette / Settings popup, plus one global case: the shell-level listener dismisses the open-in-editor hint toast when it is open; otherwise Esc falls through to the host.) |

[`spec/007-UX-IA.md` §Keyboard](../../../tools/xray/spec/007-UX-IA.md#keyboard)
catalogues additional shortcuts that remain normative for the future but
are not yet wired. Note: `Cmd/Ctrl+K` **is** wired today (the command
palette) — don't tell users the K-binding is unavailable. Embed hosts can suppress Xray's
global listeners via `:rf.xray/keybinding-enabled?` (e.g. Story's RHS, so
its own `Cmd/Ctrl+K` palette is not swallowed). Source of truth:
[`keybinding.cljs`](../../../tools/xray/src/day8/re_frame2_xray/keybinding.cljs).

## Hidden-state semantics

When Xray is toggled off (Ctrl+Shift+C while open), the shell stays
mounted with `display: none` on the container. The app receives no
body padding, no viewport overlay, no fixed chrome. Re-open is a
CSS-only `display: block` — no React remount, internal state
(selected tab, scroll, selected epoch) survives. The first paint after
the first toggle hits the <80ms target per
[`spec/007-UX-IA.md` §Animation](../../../tools/xray/spec/007-UX-IA.md).

`teardown!` is **test-only** and tears down both mount singletons
(`mount-state` for the in-app shell, `popout-state` for the pop-out).
Production sessions never tear down.

## Production posture

The preload's foundation block is gated on `re-frame.interop/debug-
enabled?`. Production builds compiled with `(set! goog.DEBUG false)`
strip every side-effect — the trace collector registration, the
epoch-cb registration, the keybinding listener, the mount call. CI
verifies via `npm run test:elision`.

A non-elided dev build running in production-like conditions shows a
yellow top banner: "Xray is enabled in this build. Disable for
production." Single-click dismiss, remembered for the session.
