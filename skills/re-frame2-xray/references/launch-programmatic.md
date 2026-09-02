# launch-programmatic — driving Xray from code

Source of truth: [`tools/xray/spec/011-Launch-Modes.md`](../../../tools/xray/spec/011-Launch-Modes.md)
plus the `core.cljs` docstrings. Sibling leaves:
[`launch-modes.md`](launch-modes.md) (getting Xray visible) and
[`launch-lifecycle.md`](launch-lifecycle.md) (pop-out, hotkeys, posture).

Two verbs, and they do different jobs. `init!` installs Xray's foundation
from app code instead of from the preload; `focus!` points an
already-mounted Xray at something. Neither one mounts a panel.

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
 layout column ([§Overlay fallback](launch-modes.md#overlay-fallback-open-overlay)).
- `(xray/popout!)` — same-origin second window
 ([§Pop-out](launch-lifecycle.md#pop-out-to-a-second-window)). In-app, the
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

## Programmatic focus (focus!)

`init!` and the mount verbs get Xray *visible*. `focus!` is the separate
verb that says **where to look** — deep-link an already-mounted Xray to a
tab, an epoch, an event bundle, or an app-db path from code (a Story
narrative beat, a failed assertion, a docs link, your own REPL).

**It navigates; it does not install or mount.** `focus!` assumes a host
has already mounted Xray — it is not a launch verb, and it will not open a
hidden shell. Reach it after the preload (or `init!`) plus a mount verb.

```clojure
(require '[day8.re-frame2-xray.core :as xray])

(xray/focus! {:panel :trace})              ; just flip the tab
(xray/focus! {:frame    :app/checkout      ; observe this host frame
              :panel    :app-db            ; surface this L4 tab
              :epoch-id 42                 ; pin the spine to this epoch
              :path     [:checkout :state]}) ; highlight this app-db path
```

Every field is optional — an empty command `{}` is a well-formed no-op.
The command keys, per
[`focus.cljc`](../../../tools/xray/src/day8/re_frame2_xray/focus.cljc):

| Key | Meaning |
|---|---|
| `:frame` | The **host** frame Xray should observe. Omit to keep the current scope. |
| `:panel` | Which Dynamic L4 tab to surface (one of the ten ids below). |
| `:epoch-id` | Settling epoch to pin the spine to. |
| `:dispatch-id` | Event-bundle root to pin the spine to — an alternative to `:epoch-id`; passing both is fine. |
| `:path` | app-db path to highlight in the app-db panel. |
| `:source` | **Opaque** provenance echoed back in the result. Xray never reads into it — it is the host's own intent context. |
| `:sync?` | Control key, never translated to a dispatch: fires `dispatch-sync` instead of `dispatch` (test rigs, same-tick host flows). |

Valid `:panel` ids are the ten live Dynamic L4 tabs, re-exported as
`xray/valid-focus-panels`: `:epoch` `:app-db` `:views` `:trace`
`:machines` `:routing` `:resources` `:derivation-graph` `:module-view`
`:hicasso`. These are internal **ids**, not the visible labels — the tab
that renders as "Routes" is `:routing`, "Graph" is `:derivation-graph`,
"Frames" is `:module-view`. Because a host naturally reaches for the
visible noun, `:routes` is accepted as an alias and normalises to
`:routing`.

Two arities, per the `focus!` docstring:

```clojure
(xray/focus! command)             ; the command's own :frame scopes it
(xray/focus! host-frame command)  ; positional frame, merged in as :frame
                                  ;   (an explicit command :frame wins)
```

The translated events fire in a fixed order — `:frame` first (so the
per-frame epoch ring is re-seeded before any epoch pin lands), then
`:dispatch-id` / `:epoch-id`, then `:panel` and `:path`.

`focus!` returns a data-shaped result rather than throwing:

```clojure
{:ok? true :applied [[:rf.xray/select-frame :app/checkout] ...] :source nil}
```

**An unknown `:panel` is the one rejected case** — it would otherwise
silently land the L4 unknown-tab stub, so it comes back
`{:ok? false :reason :unknown-panel :given <id> :valid #{...} :hint "..."}`.
Every other field is permissive: a missing epoch or an evicted event
bundle degrades through the spine's existing placeholder UX, not an error.

**This is the only programmatic tab jump.** The tab mnemonics are tooltip
letters, not keys ([§Wired hotkeys](launch-lifecycle.md#wired-hotkeys)) — a
caller who wants a tab from code
uses `focus!`, and a user who wants one from the keyboard uses the
command palette.

