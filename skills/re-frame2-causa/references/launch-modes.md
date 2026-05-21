# launch-modes — getting Causa visible

Source of truth: [`tools/causa/spec/011-Launch-Modes.md`](../../../tools/causa/spec/011-Launch-Modes.md).
This leaf is the decision-tree-shaped tour of what the spec normalises.
When a user's question hits a corner this leaf doesn't cover, defer to
the spec doc rather than improvising prose.

## Decision tree

```
Is the host app's dev build running with the Causa preload? ── no ──► §Install the preload
 │
 yes
 │
 Has `rf/init!` run? ── no ──► §Programmatic init!
 │
 yes
 │
 Is there a [data-rf-causa-host] in the page layout?
 │
 ┌──────────────┴──────────────┐
 yes no
 │ │
 ▼ ▼
 Causa auto-opened into the Causa logged the missing-host
 inline host on page load. diagnostic (console.error +
 Toggle with Ctrl+Shift+C. window.day8.re_frame2_causa.status)
 │ │
 ▼ ▼
 Need a second monitor? §Layout host contract (add the column)
 │
 yes
 │
 ▼
 (causa/popout!) — same-origin
 second window, reads the
 opener's runtime atoms directly.
```

## Install the preload

The default path. Add the preload namespace to shadow-cljs's
`:devtools/preloads`:

```clojure
;; shadow-cljs.edn — dev build only
{:builds {:app {:devtools {:preloads [day8.re-frame2-causa.preload]}}}}
```

The preload runs four foundation side-effects (per
[`spec/011-Launch-Modes.md` §Mount lifecycle](../../../tools/causa/spec/011-Launch-Modes.md#mount-lifecycle-rf2-9kkrm)):

1. Register `:rf.causa/*` handlers against the `:rf/causa` frame.
2. Register the trace collector via `register-listener!` under
 `:rf.causa/trace-collector`.
3. Register the epoch collector via `register-epoch-listener!` under
 `:rf.causa/epoch-collector` (no-op when the
 `day8/re-frame2-epoch` artefact is absent).
4. Attach the global keydown listener (one capture-phase handler routing
 `Ctrl+Shift+C` shell-toggle, `Cmd/Ctrl+Shift+M` mode-toggle,
 `Cmd/Ctrl+K` command-palette, and the focus-gated spine keys).

It schedules an auto-open into `[data-rf-causa-host]` once
`current-adapter` is ready. The preload MUST NOT mount synchronously
during namespace load; it MAY schedule a bounded adapter-ready retry.

Idempotency: every step is `defonce`-guarded. shadow-cljs `:after-load`
reruns are safe — no double-attached listeners, no double-mount, no
"already registered" warnings.

## Layout host contract

The host app provides a Causa column in its normal layout. Default
selector: `[data-rf-causa-host]`. Minimal markup (DOM order matters —
`<main>` first, host `<aside>` second, so flex puts the aside on the
right):

```html
<div class="app-shell">
 <main id="app"></main>
 <aside data-rf-causa-host></aside>
</div>
```

```css
:root { --rf-causa-accent: #7C5CFF; } /* brand-accent var */
body { margin: 0; }
.app-shell { display: flex; min-height: 100vh; }
[data-rf-causa-host] {
 flex: 0 0 var(--rf-causa-inline-width, 560px);
 min-width: 320px;
 box-sizing: border-box;
 border-left: 1px solid #2a2a2a;
}
#app { flex: 1; min-width: 0; }
```

The host owns sizing and layout; Causa owns the shell rendered inside
the host. Override the selector before Causa opens:

```clojure
(require '[day8.re-frame2-causa.config :as causa-config])
(causa-config/configure! {:rf.causa/layout-host-selector "#devtools-causa"})
```

### Resizing the host

The recommended CSS reads `--rf-causa-inline-width` for its
`flex-basis`. Two cooperating resize mechanisms:

1. **CSS variable** — host-owned, fixed-point sizing. Set the initial
 width or override per route/per build via the cascade. One
 declaration, no listeners.
 ```css
 :root { --rf-causa-inline-width: 720px; } /* global default */
 .debug-route { --rf-causa-inline-width: 960px; } /* per route */
 ```
2. **Causa drag handle** — auto-injected by Causa on the panel's
 outer edge. Pointer-driven (mouse, touch, pen via pointer events),
 keyboard-navigable, persisted across reloads via
 `configure! :rf.causa/settings :general :panel-width-px`, clamped to
 `[320px, 90vw]`, double-click to reset.

Both mechanisms write the same `flex-basis` slot. Consumers that
prefer the browser-native handle opt out by setting `resize:
horizontal` on the host; Causa detects that via `getComputedStyle` at
render time and yields (no double-handle). Causa MUST NOT set the
variable from CLJS — the host's stylesheet is the single source of
truth for the *initial* width.

### Suppress auto-open

Tool-owned pages that deliberately do not allocate layout space for
Causa (Story-only browser-test canvases, headless probe pages) MAY
suppress only the default page-load open before `rf/init!`:

```clojure
(causa-config/configure! {:rf.causa/auto-open? false})
```

This does not disable Causa. The collectors, browser API, keybinding,
and explicit `open!` / `toggle!` calls remain installed; an explicit
open with no host still emits the actionable missing-host diagnostic.
App dev pages should keep the default `true` posture and provide
`[data-rf-causa-host]`.

### Disable entirely

```clojure
;; Either: remove the preload entry from :devtools/preloads
;; Or: closure-define the disable flag in the dev build
:closure-defines {re-frame.interop/debug-enabled? false}
```

## Programmatic init!

Alternative to the preload — call `(causa/init! opts)` from app code
after `rf/init!`. Idempotent; each underlying side-effect is `defonce`-
guarded so a second call is a no-op.

```clojure
(require '[day8.re-frame2-causa.core :as causa])

(causa/init!
 {:default-frame :app/main ; target-frame for the scrubber
 :theme :dark ; / :light / :high-contrast
 :density :compact ; / :cosy
 :ai-provider {:provider :claude}
 :buffer-depths {:trace 200 :epoch 50}})
```

Pre-alpha posture: `:default-frame` threads through to the
`:rf.causa/set-target-frame` event. The other keys (`:theme`,
`:density`, `:ai-provider`, `:buffer-depths`) are accepted today but
not yet wired at runtime — passing them keeps host code forward-
compatible. See `core.cljs` docstring for the current frontier.

## Pop-out to a second window

Solves "I want Causa on a second monitor while the app runs
full-screen." Same-origin required.

```clojure
(causa/popout!)
;; or, from a devtools console:
;; window.day8.re_frame2_causa.popout_BANG_
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
 `window.opener.causaRuntime` on opener reload.
- No keybinding pre-alpha. The programmatic call is the contract.
- A right-click → `Pop out` affordance on the launcher pill is the
 canonical chrome-side path once that surface lands.

## Wired hotkeys

Four hotkey families have keydown listeners attached today
(`keybinding.cljs`). Three are **global**; the fourth is **focus-gated**
(fires only inside the Causa shell, on non-editable, non-modal targets):

| Key | Scope | What it does |
|---|---|---|
| `Ctrl+Shift+C` | global | Toggle the Causa shell (mount on first press; CSS show/hide thereafter). `Ctrl+Shift` avoids Safari's `Cmd+Shift+C` Inspect collision on macOS. |
| `Cmd/Ctrl+Shift+M` | global | Toggle mode — Dynamic ↔ Static (`:rf.causa/toggle-mode`). Cmd on macOS, Ctrl elsewhere. |
| `Cmd/Ctrl+K` | global | Open the command palette (`:rf.causa/palette-toggle`); opens the shell first if it's hidden. Cmd on macOS, Ctrl elsewhere. |
| `Space` `L` `j` `k` `G` `,`/`s` `Esc` | focus-gated | Spine + chrome shortcuts. Space = pause/resume LIVE · `L` = snap to LIVE · `j`/`k` = step focused event back/forward · `G` (Shift+G) = fast-forward to head · `,` or `s` = Settings popup · `Esc` = clear the focus lens. |

[`spec/007-UX-IA.md` §Keyboard](../../../tools/causa/spec/007-UX-IA.md#keyboard)
catalogues additional shortcuts that remain normative for the future but
are not yet wired. Note: `Cmd/Ctrl+K` **is** wired today (the command
palette) — do not say it was struck. Embed hosts can suppress Causa's
global listeners via `:rf.causa/keybinding-enabled?` (e.g. Story's RHS, so
its own `Cmd/Ctrl+K` palette is not swallowed). Source of truth:
[`keybinding.cljs`](../../../tools/causa/src/day8/re_frame2_causa/keybinding.cljs).

## Hidden-state semantics

When Causa is toggled off (Ctrl+Shift+C while open), the shell stays
mounted with `display: none` on the container. The app receives no
body padding, no viewport overlay, no fixed chrome. Re-open is a
CSS-only `display: block` — no React remount, internal state
(selected tab, scroll, AI conversation) survives. The first paint after
the first toggle hits the <80ms target per
[`spec/007-UX-IA.md` §Animation](../../../tools/causa/spec/007-UX-IA.md).

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
yellow top banner: "Causa is enabled in this build. Disable for
production." Single-click dismiss, remembered for the session.
