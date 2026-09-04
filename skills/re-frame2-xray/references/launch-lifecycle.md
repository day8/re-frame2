# launch-lifecycle — pop-out, hotkeys, hidden state, production posture

Source of truth: [`tools/xray/spec/011-Launch-Modes.md`](../../../tools/xray/spec/011-Launch-Modes.md).
Sibling leaves: [`launch-modes.md`](launch-modes.md) (getting Xray
visible) and [`launch-programmatic.md`](launch-programmatic.md)
(`init!` / `focus!`).

What the shell does once it is up — the second-window pop-out, the wired
keydown families, what "hidden" actually means — and how Xray leaves a
production build.

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
- The pop-out can't survive a hard reload of the opener — the opener's
 atoms are garbage-collected and the pop-out is left reading a dead
 runtime. **There is no re-bootstrap.** (`spec/011-Launch-Modes.md`
 §Pop-out has the pop-out re-reading a `window.opener.xrayRuntime` handle
 on opener reload; nothing in `tools/xray/src` ever sets that handle, so
 it is normative-future.) Close the pop-out and open a fresh one from the
 reloaded opener.
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

## Disable entirely

Two distinct levers — pick the one that matches your intent:

- **Xray-specific disable** — remove Xray's preload entry from
  `:devtools/preloads`. The framework's instrumentation surface (trace
  bus, epoch history) stays live for other tools; only Xray's foundation
  block (collector/epoch-cb registration, keybinding, browser API,
  auto-open) is gone.

- **Build-wide debug elision** — set `goog.DEBUG false`. This is the
  canonical CLJS production flag; it eliminates every branch that is
  *written* behind a `debug-enabled?` check — which inside Xray means the
  preload's boot block and nothing else (§Production posture).
  `re-frame.interop/debug-enabled?` is an alias of
  `goog.DEBUG` (`(def ^boolean debug-enabled? "@define {boolean}"
  ^boolean goog/DEBUG)`), so you closure-define `goog.DEBUG`, **not**
  `re-frame.interop/debug-enabled?` directly.

```edn
;; shadow-cljs.edn — build-wide debug elision (production posture)
{:builds {:app {:target           :browser
                :compiler-options {:closure-defines {goog.DEBUG false}}}}}
```

## Production posture

**The preload's foundation block is the only `goog.DEBUG`-gated path in
Xray.** It is wrapped in `(when rf.interop/debug-enabled? …)`, so a build
compiled with `goog.DEBUG false` strips its side-effects — the trace
collector registration, the epoch-cb registration, the browser-API
exports, the keybinding listener, the auto-open call.

**The programmatic `init!` path is not behind that gate**, and neither are
the mount verbs. `init!` installs the foundation unconditionally, and
`open!` gates only on a substrate adapter being present — which every app
that called `rf/init!` has, production included. A host that installs Xray
from app code owns its exclusion: see [`launch-programmatic.md` §Keeping
the manual path out of
production](launch-programmatic.md#keeping-the-manual-path-out-of-production).

**`npm run test:elision` is not proof about Xray.** It compiles
`re-frame.elision-probe` under `:advanced` twice (`goog.DEBUG` false and
true) and greps for dev-only string sentinels drawn from `re-frame.*`
namespaces. No Xray namespace is rooted or grepped, so a green run attests
the *framework's* elision and says nothing about whether Xray reached a
given bundle.

**There is no in-build "Xray is enabled" warning banner.** A non-elided
build gives no posture signal of its own — the tell that Xray shipped is
simply that its surface is *there*: the panel mounts, the chrome renders,
and `Ctrl+Shift+C` toggles it. So "would I notice Xray in production?"
resolves to the build posture above, not to a runtime warning — and since
no gate in the repo greps a release bundle for Xray, the check that settles
it is your own: grep your release output for `rf-xray-root` or `rf.xray`,
both of which survive Closure as string literals.
(`spec/007-UX-IA.md` §Production posture does describe a dismissable
yellow banner for this case. Nothing in `tools/xray/src` implements it —
it is normative-future, in the same class as the unwired keymap under
§Wired hotkeys. Don't teach it as a control the operator will see.)
