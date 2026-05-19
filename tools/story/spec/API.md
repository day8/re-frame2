# Story — Public API

> The consolidated public API surface for `day8/re-frame2-story` —
> every `reg-*`, every fn, every fx-id, every cofx-id. Each entry
> cross-links to the capability doc where the contract is spelled
> out in full.

## Registration macros

All under `re-frame.story`. All DCE under `:advanced` (see
[`005-SOTA-Features.md`](005-SOTA-Features.md) §Production elision).

| Macro | Signature | Spec |
|---|---|---|
| `reg-story` | `(reg-story id metadata)` | [`001-Authoring.md`](001-Authoring.md) §reg-story |
| `reg-variant` | `(reg-variant id metadata)` | [`001-Authoring.md`](001-Authoring.md) §reg-variant |
| `reg-workspace` | `(reg-workspace id metadata)` | [`001-Authoring.md`](001-Authoring.md) §reg-workspace |
| `reg-decorator` | `(reg-decorator id metadata)` | [`001-Authoring.md`](001-Authoring.md) §reg-decorator |
| `reg-story-panel` | `(reg-story-panel id metadata)` | [`001-Authoring.md`](001-Authoring.md) §reg-story-panel + [`003-Render-Shell.md`](003-Render-Shell.md) §Panel registration contract |
| `reg-tag` | `(reg-tag id metadata)` | [`001-Authoring.md`](001-Authoring.md) §reg-tag |
| `reg-mode` | `(reg-mode id metadata)` | [`001-Authoring.md`](001-Authoring.md) §reg-mode |

### Combined `reg-story` Form B

`(reg-story id {:variants {...} ...})` desugars at macro-expansion
time to N independent `reg-variant` calls plus the bare `reg-story`.
See [`001-Authoring.md`](001-Authoring.md) §Combined form (Form B)
desugaring.

## Programmatic runtime

All under `re-frame.story`.

| Fn | Signature | Spec |
|---|---|---|
| `run-variant` | `(run-variant variant-id)` / `(run-variant variant-id opts)` | [`002-Runtime.md`](002-Runtime.md) §Programmatic API |
| `reset-variant` | `(reset-variant variant-id)` | [`002-Runtime.md`](002-Runtime.md) §Programmatic API |
| `watch-variant` | `(watch-variant variant-id)` | [`002-Runtime.md`](002-Runtime.md) §Programmatic API |
| `unwatch-variant` | `(unwatch-variant variant-id)` | [`002-Runtime.md`](002-Runtime.md) §Programmatic API |
| `destroy-variant!` | `(destroy-variant! variant-id)` | [`002-Runtime.md`](002-Runtime.md) §Per-variant frame allocation |
| `variants-with-tags` | `(variants-with-tags tags)` | [`002-Runtime.md`](002-Runtime.md) §Programmatic API |
| `variants-of` | `(variants-of story-id)` | [`006-MCP-Surface.md`](006-MCP-Surface.md) §Story's public read primitives |
| `variant->edn` | `(variant->edn variant-id)` | [`002-Runtime.md`](002-Runtime.md) §Programmatic API |
| `workspace->edn` | `(workspace->edn workspace-id)` | [`002-Runtime.md`](002-Runtime.md) §Programmatic API |
| `snapshot-identity` | `(snapshot-identity variant-id)` / `(snapshot-identity variant-id opts)` | [`002-Runtime.md`](002-Runtime.md) §Snapshot-identity computation |
| `read-assertions` | `(read-assertions variant-id)` | [`004-Assertions.md`](004-Assertions.md) |
| `assertions-passing?` | `(assertions-passing? result)` | [`004-Assertions.md`](004-Assertions.md) |
| `variant-share-url` | `(variant-share-url variant-id base-url opts)` | [`005-SOTA-Features.md`](005-SOTA-Features.md) §QR code in share menu |

## Registry queries

| Fn | Signature | Returns |
|---|---|---|
| `registrations` | `(registrations kind)` | All registrations for `kind` (Story kinds: `:story`, `:variant`, `:workspace`, `:story-panel`, `:tag`, `:mode`, `:decorator`). |
| `handler-meta` | `(handler-meta kind id)` | The registered body for `id`. |
| `ids` | `(ids kind)` | All registered ids of `kind`. |
| `registered?` | `(registered? kind id)` | Boolean. |
| `list-tags` | `(list-tags)` | All registered tags (canonical + project). |
| `list-modes` | `(list-modes)` | All registered modes. |
| `canonical-tags` | `canonical-tags` | The seven canonical tags as a set. |
| `canonical-assertion-ids` | `(canonical-assertion-ids)` | The seven `:rf.assert/*` event ids. |
| `registered-substrates` | `(registered-substrates)` | CLJS-only. The substrate set as registered via `register-substrate!`. |
| `variant-substrates` | `(variant-substrates variant-id)` | The substrate set for a specific variant (intersect of registered + variant's `:substrates`). |

## Write helpers (used by MCP write surface and hot-reload tooling)

All under `re-frame.story`. The `*`-suffix runtime helpers are
public; their unsuffixed macro counterparts cover authored cases.

| Fn | Signature | Purpose |
|---|---|---|
| `reg-story*` | `(reg-story* id body)` | Programmatic story registration. |
| `reg-variant*` | `(reg-variant* id body)` | Programmatic variant registration. |
| `reg-workspace*` | `(reg-workspace* id body)` | Programmatic workspace registration. |
| `reg-mode*` | `(reg-mode* id body)` | Programmatic mode registration. |
| `reg-story-panel*` | `(reg-story-panel* id body)` | Programmatic panel registration. |
| `reg-decorator*` | `(reg-decorator* id body)` | Programmatic decorator registration. |
| `reg-tag*` | `(reg-tag* id body)` | Programmatic tag registration. |
| `unregister!` | `(unregister! kind id)` | Remove a registration. |
| `clear-kind!` | `(clear-kind! kind)` | Remove all of a kind. |
| `clear-all!` | `(clear-all!)` | Reset Story state entirely. |

## Effects (fx) registered by Story

| Fx id | Payload | Notes |
|---|---|---|
| `:story/set-arg` | `{:variant <id> :key <k> :value <v>}` | Dispatched by control widgets when args change. |
| `:story/run-play` | `{:variant <id>}` | Run the play sequence (used by play-stepper). |
| `:story/reset` | `{:variant <id>}` | Reset variant to post-events baseline. |
| `:story/save-layout-as` | `{:workspace <id> :body <transit>}` | Persist the active layout as a registered workspace. |

## Coeffects (cofx) registered by Story

| Cofx id | Shape | Notes |
|---|---|---|
| `:story/active-modes` | `[<mode-id> ...]` | The chrome-toolbar's active mode-set (rf2-p0mv). See [`010-Toolbar.md`](010-Toolbar.md). |
| `:story/active-args` | `{<arg-key> <value>}` | Deep-merge of all active modes' `:args`. See [`010-Toolbar.md`](010-Toolbar.md). |
| `:story/substrate` | `:reagent`, `:uix`, ... | The active substrate. |

## Canonical assertion events

The seven `:rf.assert/*` events register at Story load. All record
into `:assertions` rather than throwing — see
[`004-Assertions.md`](004-Assertions.md) §Record-don't-throw.

| Event id | Payload | Semantics |
|---|---|---|
| `:rf.assert/path-equals` | `[path expected]` | `(= (get-in @app-db path) expected)` |
| `:rf.assert/path-matches` | `[path malli-schema]` | `(m/validate schema (get-in @app-db path))` |
| `:rf.assert/sub-equals` | `[sub-vec expected]` | `(= @(subscribe sub-vec) expected)` |
| `:rf.assert/dispatched?` | `[event-vec]` | Was this event dispatched against this frame? |
| `:rf.assert/state-is` | `[machine-id state]` | Active state of `reg-machine` machine-id is state. |
| `:rf.assert/no-warnings` | `[]` | No `:rf.warn/*` events seen during play. |
| `:rf.assert/effect-emitted` | `[fx-id]` or `[fx-id pred]` | Did the variant's drain emit fx-id? `pred`, when present, is a unary fn `(pred fx-id) → truthy?` — see [`004-Assertions.md`](004-Assertions.md) §`:rf.assert/effect-emitted` payload shape. |

## Shell lifecycle

| Fn | Signature | Spec |
|---|---|---|
| `mount-shell!` | `(mount-shell! mount-point opts)` | [`003-Render-Shell.md`](003-Render-Shell.md) §Shell lifecycle |
| `unmount-shell!` | `(unmount-shell!)` | [`003-Render-Shell.md`](003-Render-Shell.md) §Shell lifecycle |
| `active-shell` | `(active-shell)` | [`003-Render-Shell.md`](003-Render-Shell.md) §Shell lifecycle |

## Theme tokens (`re-frame.story.theme.*`)

The chrome's design-token namespaces. The token maps are the public
contract third-party Story-panel authors honour — call sites consume
tokens, never raw hex / font-family / duration / shadow literals.
See [`016-Design-Tokens.md`](016-Design-Tokens.md) for the full
contracts.

| Namespace | Public surface | Purpose |
|---|---|---|
| `re-frame.story.theme.typography` | `sans-stack`, `mono-stack`, `display-stack`, `type-scale`, `weights`, `inject-font-faces!` | IBM Plex Sans + IBM Plex Mono stacks plus the type-scale / weights maps; `inject-font-faces!` injects `local()`-only `@font-face` rules at shell mount. See [`016-Design-Tokens.md`](016-Design-Tokens.md) §Typography. |
| `re-frame.story.theme.colors` | `tokens` | Semantic colour map (`:bg-1` / `:bg-2` / `:bg-3` / `:bg-canvas` / `:bg-overlay` / `:text-primary` / `:text-secondary` / `:text-tertiary` / `:accent-amber` / `:accent-amber-soft` / `:accent-amber-deep` / `:border-subtle` / `:danger` / `:danger-bg` / `:tag-*-bg` / `:tag-*-fg` / …). See [`016-Design-Tokens.md`](016-Design-Tokens.md) §Colour. |
| `re-frame.story.theme.motion` | `durations`, `easings`, `transitions` | Duration / easing maps plus pre-composed `transitions` for chrome surfaces; consumes `--motion-scale` CSS variable for global motion sensitivity. See [`016-Design-Tokens.md`](016-Design-Tokens.md) §Motion. |
| `re-frame.story.theme.depth` | `shadows` | Elevation shadow scale (`:elev-1` / `:elev-2` / `:elev-3` / …). See [`016-Design-Tokens.md`](016-Design-Tokens.md) §Depth. |
| `re-frame.story.theme.glyphs` | `story-glyph`, `variant-glyph`, `workspace-glyph`, `chevron-right`, `external-link` | Inline-SVG glyph fns for the three sidebar row types plus utility glyphs. Each fn accepts an optional pixel size; SVG draws via `currentColor` so CSS controls colour. See [`016-Design-Tokens.md`](016-Design-Tokens.md) §Iconography. |

### Token contract

- **No raw `font-family` at call sites.** Chrome consumes
  `sans-stack` / `mono-stack` / `display-stack` from
  `theme.typography`; raw `font-family` literals are banned (rf2-2rwdc
  AC#5).
- **No raw hex literals at call sites.** Chrome consumes
  `(:token-name colors/tokens)`; raw `#xxxxxx` literals are banned
  (rf2-i3i5j AC#3).
- **No raw `transition` literals at call sites.** Chrome consumes
  `(:row motion/transitions)` etc.; raw `transition` strings are
  banned (rf2-3lt89 follow-on sweep).
- **`prefers-reduced-motion: reduce` is honoured.** Chrome motion
  falls back to static states behind the user-agent media query.

## Chrome-host surface

The Story chrome's per-panel mount lifecycle plus the bridges-only
wiring helper. The contract Story consumes from Causa is
`panels/mount-<panel>!` (each panel a separate fn on
`day8.re-frame2-causa.panels`); Story owns the panel-host that drives
the lifecycle.

| Surface | Where it lives | Purpose |
|---|---|---|
| `causa-embed-panel` | `re-frame.story.ui.causa-embed` | The RHS Causa-host Reagent component. Renders the chip-row picker plus the Causa panel-host `<div>` that one of `panels/mount-<panel>!` mounts into. Feature-detect-safe: renders a graceful no-op when Causa's preload is not on the classpath. See [`003-Render-Shell.md`](003-Render-Shell.md) §Causa per-panel embed. |
| `mount-fn-for` | `re-frame.story.ui.causa-embed` | Pure dispatch: `(mount-fn-for panel-id)` returns the Causa `mount-<panel>!` fn for `panel-id` (one of `:event-detail` / `:app-db` / `:views` / `:trace` / `:machines` / `:routing` / `:issues`), or nil for an unknown id. Compile-time symbol resolution via a `case` dispatch — no runtime namespace walk. See [`003-Render-Shell.md`](003-Render-Shell.md) §The contract — `panels/mount-<panel>!`. |
| `popout-full-shell!` | `re-frame.story.ui.causa-embed` | Pop out the full Causa 4-layer shell into a second window via `day8.re-frame2-causa.mount/popout!`. Gated on `causa-preset/causa-available?` so the chip remains a graceful no-op when Causa's preload is not on the build. |
| `causa-preset/wire-cross-host!` | `re-frame.story.causa-preset` | Bridges-only host-wiring helper. Called by the shell on every variant selection; threads through Causa's host-installation hooks (project-root propagation, keybinding installation) but does NOT mount Causa — the embed's panel-host owns the per-panel mount. See [`003-Render-Shell.md`](003-Render-Shell.md) §`wire-cross-host!` — bridges-only, no mount. |
| `causa-preset/causa-available?` | `re-frame.story.causa-preset` | Pure predicate: true when Causa's preload is on the build (the preload namespace resolved at compile time). The chip-row, popout, and `wire-cross-host!` all check this — Story is feature-detect-safe and degrades gracefully when Causa is absent. |
| `causa-preset/propagate-project-root!` | `re-frame.story.causa-preset` | Bridges Story's `:project-root` from `configure!` into Causa's slot so Causa-as-RHS source-coord chips share the same on-disk root (rf2-r1uod; symmetric to shop's rf2-6jyf6). |
| `keybindings/bindings` | `re-frame.story.ui.keybindings` | The canonical `{key → handler}` table for the chrome-visibility hotkeys (`f` / `s` / `a` / `t`). Public so the help overlay's cheat-sheet section and the `015-Test-Coverage.md` matrix row can both walk the table. See [`014-Chrome-Features.md`](014-Chrome-Features.md) §Chrome-visibility hotkeys. |
| `keybindings/shortcut-keys` | `re-frame.story.ui.keybindings` | Pure data → data: the sorted list of bound keys. Consumed by the first-visit help overlay so the rendered shortcut table stays in lockstep with the registry. |
| `keybindings/install!` / `keybindings/uninstall!` | `re-frame.story.ui.keybindings` | Install / teardown the single `window#keydown` capture-phase listener that backs the hotkey registry. Idempotent; no listener leak across re-mounts. Production builds with `re-frame.story.config/enabled?` false never install. |

## Configuration

| Var / fn | Notes |
|---|---|
| `goog-define :rf.story/enabled?` | Compile-time DCE flag; `true` in dev, `false` in `:advanced`. See [`005-SOTA-Features.md`](005-SOTA-Features.md). |
| `configure!` | `(configure! {:global-args {...} :editor :vscode :project-root "..." :trace/show-sensitive? false})` — set global config at boot. `:project-root` is bridged into Causa's slot via `re-frame.story.causa-preset/propagate-project-root!` so Causa-as-RHS source-coord chips share the same on-disk root (rf2-r1uod; symmetric to shop's rf2-6jyf6). |

## Cross-references

- [`000-Vision.md`](000-Vision.md) — what Story is and isn't.
- [`001-Authoring.md`](001-Authoring.md) — the macros in full,
  with worked examples.
- [`002-Runtime.md`](002-Runtime.md) — the four-phase lifecycle and
  programmatic runtime.
- [`003-Render-Shell.md`](003-Render-Shell.md) — the UI shell.
- [`004-Assertions.md`](004-Assertions.md) — assertion vocabulary +
  play sequence.
- [`005-SOTA-Features.md`](005-SOTA-Features.md) — panels + v1/v1.1/v2
  ship lists + production elision.
- [`006-MCP-Surface.md`](006-MCP-Surface.md) — MCP boundary.
- [`007-Mode-Tabs.md`](007-Mode-Tabs.md) — the per-variant `:dev` /
  `:docs` / `:test` mode-tab contract and persistence.
- [`008-Docs-Mode.md`](008-Docs-Mode.md) — Docs pane projection and
  metadata sections.
- [`009-Test-Mode.md`](009-Test-Mode.md) — Test pane auto-run +
  summary surface.
- [`010-Toolbar.md`](010-Toolbar.md) — toolbar chrome + mode toggle.
- 011 + 012 — RETIRED per rf2-sgdd3 (actions panel + scrubber +
  trace panel deleted in favour of embedded Causa). See
  `003-Render-Shell.md` §Right-hand pane for the post-rf2-sgdd3 RHS
  contract.
- [`013-Static-Build.md`](013-Static-Build.md) — static-export
  surface + suppression rules.
- [`014-Chrome-Features.md`](014-Chrome-Features.md) — schema-validation
  panel, sidebar tag-as-badge, first-visit help overlay, command palette,
  Phase 3 chrome cluster (hotkeys / sidebar-search / skeleton /
  viewport-px / docs-TOC / embed-mode).
- [`015-Test-Coverage.md`](015-Test-Coverage.md) — browser-feature
  coverage matrix.
- [`016-Design-Tokens.md`](016-Design-Tokens.md) — chrome-identity
  typography / colour / motion / depth / iconography / toolbar 5-cluster
  token contracts.
- [`Principles.md`](Principles.md) — design principles.
- [`DESIGN-RATIONALE.md`](DESIGN-RATIONALE.md) — why each call was
  made.
- [`tools/story-mcp/spec/API.md`](../../story-mcp/spec/API.md) — the
  MCP jar's tool surface.
