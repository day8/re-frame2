# launch-modes — getting Xray visible

Source of truth: [`tools/xray/spec/011-Launch-Modes.md`](https://github.com/day8/re-frame2/blob/main/tools/xray/spec/011-Launch-Modes.md).
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

Two branches of that tree live in sibling leaves: **§Programmatic init!**
and **§Programmatic focus** in
[`launch-programmatic.md`](launch-programmatic.md), and **§Pop-out**,
**§Wired hotkeys** and **§Production posture** in
[`launch-lifecycle.md`](launch-lifecycle.md).

## Install the preload

The default path. Add the preload namespace to shadow-cljs's
`:devtools/preloads`:

```clojure
;; shadow-cljs.edn — dev build only
{:builds {:app {:devtools {:preloads [day8.re-frame2-xray.preload]}}}}
```

The preload runs five foundation side-effects (per
[`spec/011-Launch-Modes.md` §Mount lifecycle](https://github.com/day8/re-frame2/blob/main/tools/xray/spec/011-Launch-Modes.md);
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
:root { --rf-xray-accent: #539bf5; } /* brand-accent var */
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

## Missing host — diagnostic + recovery

The single most common "Xray didn't open" cause: the preload ran but no
element matched the layout-host selector when the substrate adapter became
ready. Per [`spec/011-Launch-Modes.md`](https://github.com/day8/re-frame2/blob/main/tools/xray/spec/011-Launch-Modes.md)
Xray **must fail loudly but safely** — it MUST NOT `alert()` and MUST NOT
block host app startup. The same diagnostic lands in two places (source
[`mount.cljs`](https://github.com/day8/re-frame2/blob/main/tools/xray/src/day8/re_frame2_xray/mount.cljs)
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
[`spec/API.md` §Mount facade](https://github.com/day8/re-frame2/blob/main/tools/xray/spec/API.md), three
distinct mount surfaces, not modal variants of one shape). It is the
**optional, non-default** path: the inline panel is the canonical
developer experience, and per
[`spec/011-Launch-Modes.md`](https://github.com/day8/re-frame2/blob/main/tools/xray/spec/011-Launch-Modes.md)
the overlay "remains an optional debug mode and must not be described as
the primary path." Prefer the `[data-rf-xray-host]` column when the host
can give one; reach for `open-overlay!` only when it can't. Source
[`mount.cljs`](https://github.com/day8/re-frame2/blob/main/tools/xray/src/day8/re_frame2_xray/mount.cljs)
(`open-overlay!`), exported via
[`core.cljs`](https://github.com/day8/re-frame2/blob/main/tools/xray/src/day8/re_frame2_xray/core.cljs).

