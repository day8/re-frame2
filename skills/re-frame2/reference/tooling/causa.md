# Causa — re-frame2 binding

> The host-app contract for mounting Causa (re-frame2's devtools panel) into a dev build. Assumes you already know what a devtools panel is — this leaf covers the mount strategy, the launch modes, the host-CSS-variable resize contract, the popout entry, and the suppress-auto-open knob. The deep prose lives in `tools/causa/spec/011-Launch-Modes.md`; this leaf is the authoring-side cheat sheet.

## When to load this leaf

- Adding Causa to an app's dev build (`shadow-cljs.edn` `:devtools :preloads`).
- Wiring the right-side `[data-rf-causa-host]` layout slot into an app shell.
- Resizing the inline panel via the `--rf-causa-inline-width` CSS variable.
- Suppressing the page-load auto-open on a tool-only canvas (Story-only build, internal dev page).
- Reaching the pop-out from CLJS or a devtools console.
- Choosing between inline / popout / declarative embedding (per Spec 008) / MCP-only access.

Do **not** load this leaf to learn what Causa is — load `tools/causa/README.md` for the panel inventory and Spec 011 for the full launch-mode treatment.

## The contract in one block

```clojure
;; shadow-cljs.edn (dev build)
{:builds {:app {:devtools {:preloads [day8.re-frame2-causa.preload]}}}}
```

```html
<div class="app-shell">
  <main id="app"></main>
  <aside data-rf-causa-host></aside>
</div>
```

```css
:root { --rf-causa-accent: #7C5CFF; } /* brand-accent var (rf2-9ovfb) */
.app-shell { display: flex; min-height: 100vh; }
[data-rf-causa-host] {
  flex: 0 0 var(--rf-causa-inline-width, 560px);
  min-width: 320px;
  box-sizing: border-box;
  border-left: 1px solid #2a2a2a;
  resize: horizontal;
  overflow: auto;
}
#app { flex: 1; min-width: 0; }
```

That's it. The preload installs Causa's trace + epoch listeners, mounts the shell into the host once the substrate adapter is ready, and auto-opens after `rf/init!`. Production builds (`goog.DEBUG=false`) elide every Causa surface.

## Launch-mode decision tree

Most apps use **inline (default)**. Reach for the others only when the trigger fires:

| Trigger | Mode | How |
|---|---|---|
| Local development; want devtools in the app window | **Inline (default)** | Add the preload + the `[data-rf-causa-host]` host. Causa auto-mounts. |
| Want a second monitor for Causa | **Inline + pop-out** | Inline mount as above, then `(causa/popout!)` from CLJS or `window.day8.re_frame2_causa.popout_BANG_()` from a devtools console. Same JS realm via `window.opener`. |
| Tool-only page that can't reserve right-column real estate (Story-only canvas, internal config UI) | **Suppress auto-open** | `(causa-config/configure! {:launch/auto-open? false})` before `rf/init!`. Causa stays installed; explicit `open!` still works and still warns on missing host. |
| Want to embed a single Causa panel inside the app's own layout (e.g. an embedded epoch scrubber in a debug screen) | **Declarative embedding via Spec 008** | See `tools/causa/spec/008-Embedding-Contract.md` for the `Panel` component shape. Not for "I want the whole panel"; only for "I want one slice of Causa as an app component." |
| Want an AI agent to read / time-travel Causa surfaces programmatically | **Causa-MCP** | Configure the `tools/causa-mcp/` server in the agent host (`re-frame2-causa-mcp`). The 18-tool catalogue at `tools/causa-mcp/spec/004-Tools-Catalogue.md` is the contract. UI may or may not be open in the browser. |

Cross-machine debugging and mobile launch are out of scope at v1.0 (see Spec 011 §Default summary, locks #5 and #9).

## Host-CSS-variable contract (`--rf-causa-inline-width`)

The recommended host snippet reads one CSS custom property — `--rf-causa-inline-width` — for its `flex-basis`. **JS-free, host-owned**: Causa itself does not read or write the property; the host's stylesheet does. Override anywhere up the cascade:

```css
/* Global default — every page */
:root { --rf-causa-inline-width: 720px; }

/* Per-route override (e.g. a debugging route that wants more room) */
.debug-route { --rf-causa-inline-width: 960px; }

/* Per-user override via a developer stylesheet */
[data-rf-causa-host] { --rf-causa-inline-width: 380px; }
```

Sizing units are unrestricted (`px`, `rem`, `vw`, `min(...)`, `clamp(...)`, …). The recommended `min-width: 320px` floor prevents the panel from collapsing past readability; remove it if you want unbounded shrink.

The variable is published as `day8.re-frame2-causa.config/default-layout-host-css-var` and the 560px default as `default-layout-host-width` (bumped 420 → 560 under `rf2-9ovfb`) so tooling can refer to them without forking the string. **Causa MUST NOT introduce a CLJS setter for this property** — the host's stylesheet is the single source of truth.

The recommended host snippet also enables a browser-native drag handle (`resize: horizontal` + `overflow: auto`). The variable seeds the initial width; a user drag overrides it for the page lifetime. Both mechanisms write the same `flex-basis` slot — no parallel sizing channel.

## Brand-accent CSS variable (`--rf-causa-accent`)

Per `rf2-9ovfb`, the same recommended snippet publishes a second CSS custom property — `--rf-causa-accent` — on `:root` carrying Causa's brand violet (`#7C5CFF`, matching `theme/tokens.cljc`'s `:accent-violet`). Host stylesheets can read `var(--rf-causa-accent)` anywhere to colour their own dev chrome (resize handles, dock separators, story chips) so it harmonises with Causa without forking the hex. Override on `:root` for a tinted brand variant. Published as `default-accent-css-var` + `default-accent` on the same `config` ns. Same single-source-of-truth rule applies — Causa never sets it from CLJS.

## Mount lifecycle (defonce, single-shell, hot-reload-safe)

- The preload installs **one** shell instance per page lifetime (`defonce` semantics). Hot-reload preserves the mounted DOM; full reload re-installs from scratch.
- Auto-open waits for the substrate adapter (`rf/init!` having installed a substrate adapter like Reagent / UIx / Helix) before mounting. If `[data-rf-causa-host]` is missing at that point, Causa logs an actionable `console.error` plus exposes the same diagnostic through `window.day8.re_frame2_causa.status()` — it does **not** `alert()` and does not block app startup.
- Toggle visibility with `Ctrl+Shift+C`; the shell stays mounted, `display: none` toggles. No React remount.
- Override the host selector before auto-open if needed:
  ```clojure
  (require '[day8.re-frame2-causa.config :as causa-config])
  (causa-config/configure! {:layout/host-selector "#devtools-causa"})
  ```

## When to suppress auto-open

Set `(causa-config/configure! {:launch/auto-open? false})` before `rf/init!` on:

- Story-only browser-test canvases (the page is a test harness; no human reads Causa).
- Internal dev pages whose layout can't accommodate a right column.
- Embed-via-Spec-008 pages where the app composes specific Causa panels into its own layout, not the whole shell.

Suppression only blocks the default page-load open. Explicit `open!`, `toggle!`, and the keybinding still work — and if no host exists when they fire, Causa still emits the missing-host diagnostic. App dev pages should leave auto-open at its `true` default and provide `[data-rf-causa-host]`.

## When pop-out helps

The default inline mount competes with the app for screen real estate. Pop out to a second window when:

- You want a second monitor for Causa.
- The app's own layout is narrow enough that the inline column is uncomfortable.
- You're pairing with a live runtime (`re-frame-pair2`) and want Causa visible while the app gets full window width.

Pop-out uses `window.open` whose JS realm connects to the opener's via `window.opener` — **same atoms, same listeners, same registrar, no protocol cost**. Constraints: same-origin only, no `noopener`/`noreferrer`, closes orphan-cleanly if the opener window closes (Spec 011 §Pop-out for the full handling).

## Frame isolation — `:rf/causa`

Causa's shell wraps in `[rf/frame-provider {:frame :rf/causa} ...]`. Every `subscribe` / `dispatch` inside the shell resolves to the `:rf/causa` frame; the host app's `:rf/default` is untouched. Causa's own registrations live under the `:rf.causa/*` namespace and operate against `:rf/causa`'s db. The host app keeps its keyspace clean — Causa never writes to `:rf/default`.

## See also

- `tools/causa/README.md` — panel inventory, headline experiences, file layout.
- `tools/causa/spec/011-Launch-Modes.md` — normative launch-mode contract, full popout handling.
- `tools/causa/spec/007-UX-IA.md` — five-region layout, keyboard map, density model.
- `tools/causa/spec/008-Embedding-Contract.md` — the declarative `Panel` shape for embedding one panel inside the app.
- `tools/causa-mcp/spec/004-Tools-Catalogue.md` — the 18-tool MCP catalogue.
- `skills/re-frame-migration/reference/causa-replaces-10x.md` — the 10x → Causa migration view, including the keybinding-parity caveat.
