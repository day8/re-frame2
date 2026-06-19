# Xray — re-frame2 binding

> The host-app contract for mounting Xray (re-frame2's devtools panel) into a dev build. Assumes you already know what a devtools panel is — this leaf covers the mount strategy, the launch modes, the host-CSS-variable resize contract, the popout entry, and the suppress-auto-open knob. The deep prose lives in `tools/xray/spec/011-Launch-Modes.md`; this leaf is the authoring-side cheat sheet.

## When to load

- Adding Xray to an app's dev build (`shadow-cljs.edn` `:devtools :preloads`).
- Wiring the right-side `[data-rf-xray-host]` layout slot into an app shell.
- Resizing the inline panel via the `--rf-xray-inline-width` CSS variable.
- Suppressing the page-load auto-open on a tool-only canvas (Story-only build, internal dev page).
- Reaching the pop-out from CLJS or a devtools console.
- Choosing between inline / popout / full-shell embedding (per Spec 008) / MCP-only access.

Do **not** load this leaf to learn what Xray is — load `tools/xray/README.md` for the panel inventory and Spec 011 for the full launch-mode treatment.

## The contract in one block

```clojure
;; shadow-cljs.edn (dev build)
{:builds {:app {:devtools {:preloads [day8.re-frame2-xray.preload]}}}}
```

```html
<div class="app-shell">
  <main id="app"></main>
  <aside data-rf-xray-host></aside>
</div>
```

```css
:root { --rf-xray-accent: #7C5CFF; } /* brand-accent var */
.app-shell { display: flex; min-height: 100vh; }
[data-rf-xray-host] {
  flex: 0 0 var(--rf-xray-inline-width, 560px);
  min-width: 320px;
  box-sizing: border-box;
  border-left: 1px solid #2a2a2a;
}
#app { flex: 1; min-width: 0; }
```

That's it. The preload installs Xray's trace + epoch listeners, mounts the shell into the host once the substrate adapter is ready, and auto-opens after `rf/init!`. Production builds (`goog.DEBUG=false`) elide every Xray surface.

## Launch-mode decision tree

Most apps use **inline (default)**. Reach for the others only when the trigger fires:

| Trigger | Mode | How |
|---|---|---|
| Local development; want devtools in the app window | **Inline (default)** | Add the preload + the `[data-rf-xray-host]` host. Xray auto-mounts. |
| Want a second monitor for Xray | **Inline + pop-out** | Inline mount as above, then `(xray/popout!)` from CLJS or `window.day8.re_frame2_xray.popout_BANG_()` from a devtools console. Same JS realm via `window.opener`. |
| Tool-only page that can't reserve right-column real estate (Story-only canvas, internal config UI) | **Suppress auto-open** | `(xray-config/configure! {:rf.xray/auto-open? false})` before `rf/init!`. Xray stays installed; explicit `open!` still works and still warns on missing host. |
| Want to mount the full Xray shell inside another host (Story is the canonical example) | **Full-shell embed via Spec 008** | See `tools/xray/spec/008-Embedding-Contract.md` §Full-shell embed contract. The host surrenders the global chord (`:rf.xray/keybinding-enabled? false`) so its own keybindings reach their handlers. Single-panel embedding is NOT a v1.0 host-facing affordance. |
| Want an AI agent to read / time-travel the running re-frame2 app programmatically | **re-frame2-pair-mcp** | Configure the `tools/re-frame2-pair-mcp/` server in the agent host (`re-frame2-pair-mcp`). Raw nREPL pair-programming companion. UI may or may not be open in the browser. |

Cross-machine debugging and mobile launch are out of scope at v1.0 (see Spec 011 §Default summary, locks #5 and #9).

## Host-CSS-variable contract (`--rf-xray-inline-width`)

The recommended host snippet reads one CSS custom property — `--rf-xray-inline-width` — for its `flex-basis`. **JS-free, host-owned**: Xray itself does not read or write the property; the host's stylesheet does. Override anywhere up the cascade:

```css
/* Global default — every page */
:root { --rf-xray-inline-width: 720px; }

/* Per-route override (e.g. a debugging route that wants more room) */
.debug-route { --rf-xray-inline-width: 960px; }

/* Per-user override via a developer stylesheet */
[data-rf-xray-host] { --rf-xray-inline-width: 380px; }
```

Sizing units are unrestricted (`px`, `rem`, `vw`, `min(...)`, `clamp(...)`, …). The recommended `min-width: 320px` floor prevents the panel from collapsing past readability; remove it if you want unbounded shrink.

The variable is published as `day8.re-frame2-xray.config/default-layout-host-css-var` and the 560px default as `default-layout-host-width`, so tooling can refer to them without forking the string. **Xray MUST NOT introduce a CLJS setter for this property** — the host's stylesheet is the single source of truth.

Xray also auto-injects a drag handle on the panel's outer edge (see `tools/xray/spec/007-UX-IA.md` §Resize affordance). The variable seeds the initial width; a user drag overrides it (persisted across reloads via `configure! :settings :general :panel-width-px`, clamped to `[320px, 90vw]`, double-click to reset). Both mechanisms write the same `flex-basis` slot — no parallel sizing channel. Consumers that prefer the browser-native handle opt out by setting `resize: horizontal` on the host; Xray detects that via `getComputedStyle` and yields (no double-handle).

## Brand-accent CSS variable (`--rf-xray-accent`)

The recommended snippet also publishes a second CSS custom property — `--rf-xray-accent` — on `:root` carrying Xray's brand violet (`#7C5CFF`, matching `theme/tokens.cljc`'s `:accent-violet`). Host stylesheets can read `var(--rf-xray-accent)` anywhere to colour their own dev chrome (resize handles, dock separators, story chips) so it harmonises with Xray without forking the hex. Override on `:root` for a tinted brand variant. Published as `default-accent-css-var` + `default-accent` on the same `config` ns. Same single-source-of-truth rule applies — Xray never sets it from CLJS.

## Mount lifecycle (defonce, single-shell, hot-reload-safe)

- The preload installs **one** shell instance per page lifetime (`defonce` semantics). Hot-reload preserves the mounted DOM; full reload re-installs from scratch.
- Auto-open waits for the substrate adapter (`rf/init!` having installed a substrate adapter like Reagent / UIx / Helix) before mounting. If `[data-rf-xray-host]` is missing at that point, Xray logs an actionable `console.error` plus exposes the same diagnostic through `window.day8.re_frame2_xray.status()` — it does **not** `alert()` and does not block app startup.
- Toggle visibility with `Ctrl+Shift+C`; the shell stays mounted, `display: none` toggles. No React remount.
- Override the host selector before auto-open if needed:
  ```clojure
  (require '[day8.re-frame2-xray.config :as xray-config])
  (xray-config/configure! {:rf.xray/layout-host-selector "#devtools-xray"})
  ```

## When to suppress auto-open

Set `(xray-config/configure! {:rf.xray/auto-open? false})` before `rf/init!` on:

- Story-only browser-test canvases (the page is a test harness; no human reads Xray).
- Internal dev pages whose layout can't accommodate a right column.
- Full-shell-embed pages (per Spec 008) where the host owns shell mount lifecycle — the host's own boot, not Xray's preload-auto-open, drives mount timing.

Suppression only blocks the default page-load open. Explicit `open!`, `toggle!`, and the keybinding still work — and if no host exists when they fire, Xray still emits the missing-host diagnostic. App dev pages should leave auto-open at its `true` default and provide `[data-rf-xray-host]`.

## When pop-out helps

The default inline mount competes with the app for screen real estate. Pop out to a second window when:

- You want a second monitor for Xray.
- The app's own layout is narrow enough that the inline column is uncomfortable.
- You're pairing with a live runtime (`re-frame2-pair`) and want Xray visible while the app gets full window width.

Pop-out uses `window.open` whose JS realm connects to the opener's via `window.opener` — **same atoms, same listeners, same registrar, no protocol cost**. Constraints: same-origin only, no `noopener`/`noreferrer`, closes orphan-cleanly if the opener window closes (Spec 011 §Pop-out for the full handling).

## Frame isolation — `:rf/xray`

Xray's shell idempotently registers `(rf/reg-frame :rf/xray {})` and wraps its view in `[rf/frame-provider-existing {:frame :rf/xray} ...]` (scope-only — the frame already exists). Every `subscribe` / `dispatch` inside the shell resolves to the `:rf/xray` **own** frame; the host app's frame (the inspected **target** frame, whatever its id) is untouched. Xray's own registrations live under the `:rf.xray/*` namespace and operate against `:rf/xray`'s db. The host app keeps its keyspace clean — Xray never writes to the host frame. (Per EP-0002, Xray distinguishes its **own** frame `:rf/xray` from the inspected **target** frame, selected explicitly by host config or the picker; it never falls back to `:rf/default`.)

## See also

- `tools/xray/README.md` — panel inventory, headline experiences, file layout.
- `tools/xray/spec/011-Launch-Modes.md` — normative launch-mode contract, full popout handling.
- `tools/xray/spec/007-UX-IA.md` — five-region layout, keyboard map, density model.
- `tools/xray/spec/008-Embedding-Contract.md` — the full-shell embed contract (Story mounts Xray with `:rf.xray/keybinding-enabled? false`).
- `skills/re-frame-migration/references/xray-replaces-10x.md` — the 10x → Xray migration view, including the keybinding-parity caveat.
