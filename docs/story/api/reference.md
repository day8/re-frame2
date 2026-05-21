# Reference

The complete symbol table for Story's public surface, organised by namespace and section for `Ctrl-F` use. Every row carries a signature, a status, and a one-line intuition — the same shape as the topical chapters, but flat and exhaustive. Reach for the topical chapters when you want context and prose around the contract; reach for this page when you know what you're looking for and just want the row.

Surfaces fall into the facade plus seven sub-namespaces. The facade carries every user-callable surface — registrations, runtime, recorder, configure!, shell-mount, privacy primitives. The sub-namespaces are public but called from chrome bootstrap, the shell, or the Causa preset, not from authored story bodies.

For the topical walk-through with intuition notes and use-when prose, see [Registration](registration.md), [Play scripts](play-script.md), [Runtime](runtime.md), and [MCP surface](mcp-surface.md). For the index of *what* this reference covers (and what it deliberately omits — Story-internal chrome composers, the URL-state hydration helpers, the theme-token maps consumed only by chrome), see [the index](index.md#what-canonical-means-here).

## `re-frame.story`

The canonical facade. Every user-callable surface lives here.

### Registration — macros

| Symbol | Signature | Status | Intuition |
|---|---|---|---|
| `reg-story` | `(reg-story id metadata)` | v1 (dev-only) | Register a story (a cluster of variants). |
| `reg-variant` | `(reg-variant id metadata)` | v1 (dev-only) | Register one variant — view, args, setup events, decorators, `:play-script`. |
| `reg-workspace` | `(reg-workspace id metadata)` | v1 (dev-only) | Register a workspace — a curated grid of variants. |
| `reg-decorator` | `(reg-decorator id metadata)` | v1 (dev-only) | Register a decorator. Three kinds: `:hiccup` / `:frame-setup` / `:fx-override`. |
| `reg-story-panel` | `(reg-story-panel id metadata)` | v1 (dev-only) | Register a custom panel in the Story chrome. Five placement slots. |
| `reg-tag` | `(reg-tag id metadata)` | v1 (dev-only) | Register a tag. The seven canonical tags auto-install. |
| `reg-mode` | `(reg-mode id metadata)` | v1 (dev-only) | Register a mode — a saved tuple of args the chrome toggles into. |

### Registration — `*`-suffix runtime helpers

| Symbol | Signature | Status | Intuition |
|---|---|---|---|
| `reg-story*` | `(reg-story* id body)` | v1 (dev-only) | Programmatic story registration. |
| `reg-variant*` | `(reg-variant* id body)` | v1 (dev-only) | Programmatic variant registration. The MCP write surface's `register-variant` tool routes here. |
| `reg-workspace*` | `(reg-workspace* id body)` | v1 (dev-only) | Programmatic workspace registration. |
| `reg-mode*` | `(reg-mode* id body)` | v1 (dev-only) | Programmatic mode registration. |
| `reg-story-panel*` | `(reg-story-panel* id body)` | v1 (dev-only) | Programmatic panel registration. |
| `reg-decorator*` | `(reg-decorator* id body)` | v1 (dev-only) | Programmatic decorator registration. |
| `reg-tag*` | `(reg-tag* id body)` | v1 (dev-only) | Programmatic tag registration. |
| `unregister!` | `(unregister! kind id)` | v1 (dev-only) | Remove a single id under `kind`. |
| `clear-kind!` | `(clear-kind! kind)` | v1 (dev-only) | Remove every registration of `kind`. |
| `clear-all!` | `(clear-all!)` | v1 (dev-only) | Reset every Story registration. Resets the auto-install gate. |
| `install-canonical-vocabulary!` | `(install-canonical-vocabulary!)` | v1 (dev-only) | Idempotent explicit boot. Auto-install path is canonical; this is retained for hosts that want a literal boot step. |

### Global args + decorators

| Symbol | Signature | Status | Intuition |
|---|---|---|---|
| `configure!` | `(configure! opts)` → nil | v1 (dev-only) | Top-level config. Map keyed by `:rf.story/*` and `:rf.privacy/*`. |
| `reg-global-decorator` | `(reg-global-decorator id body)` / `(reg-global-decorator id body ref-args)` | v1 (dev-only) | Register a decorator AND opt it into the global stack in one call. |
| `unreg-global-decorator!` | `(unreg-global-decorator! id)` | v1 (dev-only) | Remove `id` from the global-decorators vector. |
| `global-decorators` | `(global-decorators)` → vec | v1 (dev-only) | Current ordered vector of global-decorator references. |

### Built-in decorator `*-id` Vars

| Symbol | Status | Intuition |
|---|---|---|
| `force-fx-stub-id` | v1 (dev-only) | Universal fx-mocking primitive — HTTP, websockets, analytics, storage, navigation. |
| `layout-debug-measure-id` | v1 (dev-only) | Storybook-style layout measure overlay. |
| `layout-debug-outline-id` | v1 (dev-only) | Pesticide-style coloured outlines. |
| `layout-debug-pseudo-id` | v1 (dev-only) | Pseudo-state forcing (`:hover` / `:focus` / `:active` / `:visited`). |

### Programmatic runtime

| Symbol | Signature | Status | Intuition |
|---|---|---|---|
| `run-variant` | `(run-variant variant-id)` / `(run-variant variant-id opts)` → map | v1 (dev-only) | Materialise the variant — run four-phase lifecycle, return result map. |
| `reset-variant` | `(reset-variant variant-id)` | v1 (dev-only) | Reset the variant to its post-events baseline. |
| `watch-variant` | `(watch-variant variant-id)` / `(watch-variant variant-id callback)` | v1 (dev-only) | Live-updating result map. |
| `unwatch-variant` | `(unwatch-variant variant-id)` | v1 (dev-only) | Stop the live update channel. Idempotent. |
| `destroy-variant!` | `(destroy-variant! variant-id)` | v1 (dev-only) | Tear down the variant's frame. Symmetric with allocation. |
| `execute-play!` | `(execute-play! variant-id)` → vec | v1 (dev-only) | Re-run only phase 4 against current `app-db`. |
| `lifecycle-state` | `(lifecycle-state variant-id)` → keyword | v1 (dev-only) | Current lifecycle-machine state. |
| `variant-frames` | `(variant-frames)` → set | v1 (dev-only) | Set of variant-ids currently allocated as frames. |
| `variant-frame?` | `(variant-frame? variant-id)` → bool | v1 (dev-only) | Predicate. |
| `resolve-args` | `(resolve-args variant-id)` / `(resolve-args variant-id opts)` → map | v1 (dev-only) | The effective args map (five-layer precedence). |
| `resolve-decorators` | `(resolve-decorators variant-id)` / `(resolve-decorators variant-id opts)` → map | v1 (dev-only) | The resolved decorator stack classified by kind. |
| `variants-of` | `(variants-of story-id)` → seq | v1 (dev-only) | Variant ids whose namespaced id-prefix matches `story-id`. |
| `variants-by-story` | `(variants-by-story)` → map | v1 (dev-only) | Map from parent-story-id to variant ids. |
| `variants-with-tags` | `(variants-with-tags tag-set)` → seq | v1 (dev-only) | Filter the catalogue by tag intersection. |
| `variant-substrates` | `(variant-substrates variant-id)` → set | v1 (dev-only) | The substrate set for a specific variant. |
| `variant->edn` | `(variant->edn variant-id)` → map | v1 (dev-only) | Variant body as serialisable EDN. |
| `workspace->edn` | `(workspace->edn workspace-id)` → map | v1 (dev-only) | Workspace body as serialisable EDN. |
| `snapshot-identity` | `(snapshot-identity variant-id)` / `(snapshot-identity variant-id opts)` → map | v1 (dev-only) | `{:variant-id ... :content-hash "..."}`. QR-share + visual-regression keying. |
| `variant-share-url` | `(variant-share-url variant-id)` / `(variant-share-url variant-id base-url opts)` → string | v1 (dev-only) | Sharable URL — encodes active modes + cell-overrides + substrate. |
| `static-mode?` | `(static-mode?)` → bool | v1 (dev-only) | True iff Story is running in static-export mode. |

### Registry queries

| Symbol | Signature | Status | Intuition |
|---|---|---|---|
| `registrations` | `(registrations kind)` → seq | v1 (dev-only) | All registrations for `kind`. |
| `handler-meta` | `(handler-meta kind id)` → any | v1 (dev-only) | Registered body for `id`. |
| `ids` | `(ids kind)` → seq | v1 (dev-only) | All registered ids of `kind`. |
| `registered?` | `(registered? kind id)` → bool | v1 (dev-only) | Predicate. |
| `all-kinds-with-counts` | `(all-kinds-with-counts)` → map | v1 (dev-only) | Map from each kind → registration count. |
| `list-tags` | `(list-tags)` → seq | v1 (dev-only) | All registered tags. |
| `list-modes` | `(list-modes)` → seq | v1 (dev-only) | All registered modes. |
| `canonical-tags` | Var (set) | v1 (dev-only) | The seven canonical tags. |
| `canonical-axes` | Var | v1 (dev-only) | The four canonical axes (audience / lifecycle / quality / status). |
| `canonical-status-values` | Var | v1 (dev-only) | Status-axis tag values. |
| `canonical-role-values` | Var | v1 (dev-only) | Role-axis tag values. |
| `tags-by-axis` | `(tags-by-axis)` → map | v1 (dev-only) | Tags keyed by axis. |
| `tags-without-axis` | `(tags-without-axis)` → seq | v1 (dev-only) | Tags not registered against any axis. |
| `tags-default-excluded` | `(tags-default-excluded)` → set | v1 (dev-only) | Sidebar tag-filter default exclusions. |
| `tag->axis-index` | `(tag->axis-index)` → map | v1 (dev-only) | Map from tag-id → axis. |
| `registered-substrates` | `(registered-substrates)` → set | v1 (dev-only) | Registered substrate ids (CLJS-only). |

### Assertions

| Symbol | Signature | Status | Intuition |
|---|---|---|---|
| `read-assertions` | `(read-assertions variant-id)` → vec | v1 (dev-only) | Current `:rf.story/assertions` vector. |
| `assertions-passing?` | `(assertions-passing? result)` → bool | v1 (dev-only) | Project assertions vector → single boolean. |
| `canonical-assertion-ids` | `(canonical-assertion-ids)` → set | v1 (dev-only) | The seven canonical `:rf.assert/*` event-ids. |

### Recorder

| Symbol | Signature | Status | Intuition |
|---|---|---|---|
| `start-recording!` | `(start-recording! variant-id)` | v1 (dev-only) | Begin capturing canvas-dispatched events. |
| `stop-recording!` | `(stop-recording!)` → vec | v1 (dev-only) | Stop + return captured events. |
| `clear-recording!` | `(clear-recording!)` | v1 (dev-only) | Drop the buffer + return to idle. |
| `recording?` | `(recording?)` → bool | v1 (dev-only) | Predicate. |
| `recorder-state` | `(recorder-state)` → map | v1 (dev-only) | Read-only view of recorder state. |
| `gen-play-snippet` | `(gen-play-snippet events opts)` → string | v1 (dev-only) | Render captured events as a `(reg-variant ...)` EDN snippet. |

### Privacy primitives

| Symbol | Signature | Status | Intuition |
|---|---|---|---|
| `add-marks` | `(add-marks variant-id marks-map)` | v1 (dev-only) | Merge marks additively. Re-export of `re-frame.core/add-marks`. |
| `set-marks` | `(set-marks variant-id marks-map)` | v1 (dev-only) | Replace marks wholesale. Re-export of `re-frame.core/set-marks`. |

### Substrate registration

| Symbol | Signature | Status | Intuition |
|---|---|---|---|
| `register-substrate!` | `(register-substrate! substrate-id render-fn)` | v1 (dev-only) | Register a substrate render fn (CLJS-only). |

### Shell lifecycle (CLJS-only)

| Symbol | Signature | Status | Intuition |
|---|---|---|---|
| `mount-shell!` | `(mount-shell! mount-point opts)` | v1 (dev-only) | Mount the Story shell. Production short-circuits before any DOM call. |
| `unmount-shell!` | `(unmount-shell!)` | v1 (dev-only) | Unmount the shell. Idempotent. |
| `active-shell` | `(active-shell)` → map / nil | v1 (dev-only) | Inspectable handle on the active shell. |

### Stage

| Symbol | Use |
|---|---|
| `stage` | Var. The current Story development stage marker. |

## `re-frame.story.recorder.play-export`

The rich DOM-capture-aware `:play-script` translator. Sub-namespace require — the facade exposes only the simpler `gen-play-snippet` projection.

| Symbol | Signature | Status | Intuition |
|---|---|---|---|
| `recording->play-script` | `(recording->play-script entries opts)` → map | v1 (dev-only) | Translate captured `:entries` into a normalised `:play-script` body map. |
| `render-play-script` | `(render-play-script body)` → string | v1 (dev-only) | Render the `:play-script` map to EDN. |
| `render-variant-form` | `(render-variant-form variant-id metadata)` → string | v1 (dev-only) | Render a full `(reg-variant ...)` form to EDN. |

## `re-frame.story.ui.causa-embed`

The Causa-RHS embed component. Reach here from the embed component or the Causa preset, rarely from app code.

| Symbol | Kind | Audience | Intuition |
|---|---|---|---|
| `causa-embed-panel` | Reagent component | `user-app` (rare) / `chrome-shell` | The RHS Causa-host Reagent component. Renders the chip-row picker plus the Causa panel-host `<div>`. Feature-detect-safe — graceful no-op when Causa's preload is not on the classpath. |
| `mount-fn-for` | Pure dispatch fn | `chrome-shell` | `(mount-fn-for panel-id)` returns the Causa `mount-<panel>!` fn for `panel-id` (one of `:event-detail` / `:app-db` / `:views` / `:trace` / `:machines` / `:routing` / `:issues`), or nil for an unknown id. Compile-time symbol resolution. |
| `popout-full-shell!` | User-callable lifecycle | `user-app` | Pop out the full Causa 4-layer shell into a second window. Gated on `causa-preset/causa-available?` so the chip is a graceful no-op when Causa's preload is absent. |

## `re-frame.story.causa-preset`

The chrome / Causa bridge.

| Symbol | Kind | Audience | Intuition |
|---|---|---|---|
| `wire-cross-host!` | Internal bridge | `chrome-shell` | Bridges-only host-wiring helper called by the shell on every variant selection. Threads through Causa's host-installation hooks (project-root, keybinding) but does NOT mount Causa. |
| `causa-available?` | Pure predicate | `user-app` / `chrome-shell` | True when Causa's preload is on the build. The chip-row, popout, and `wire-cross-host!` all check this. |
| `propagate-project-root!` | Internal bridge | `chrome-shell` | Bridges Story's `:rf.story/project-root` from `configure!` into Causa's `:rf.causa/project-root` slot so Causa-as-RHS source-coord chips share the same on-disk root. |

## `re-frame.story.theme.*`

The design-token namespaces. Public for third-party Story-panel authors; chrome consumes tokens, not raw literals. The full per-namespace contracts live in [`016-Design-Tokens.md`](https://github.com/day8/re-frame2/blob/main/tools/story/spec/016-Design-Tokens.md).

| Namespace | Surface | Purpose |
|---|---|---|
| `re-frame.story.theme.typography` | `sans-stack`, `mono-stack`, `display-stack`, `type-scale`, `weights`, `inject-font-faces!` | IBM Plex Sans + Mono stacks. Inject `local()`-only `@font-face` rules at shell mount. |
| `re-frame.story.theme.colors` | `tokens` | Semantic colour map (`:bg-1` / `:text-primary` / `:accent-amber` / `:danger` / `:tag-*-bg` / ...). |
| `re-frame.story.theme.motion` | `durations`, `easings`, `transitions` | Duration / easing maps + pre-composed transitions. Honours `prefers-reduced-motion`. |
| `re-frame.story.theme.depth` | `shadows` | Elevation shadow scale (`:elev-1` / `:elev-2` / ...). |
| `re-frame.story.theme.glyphs` | `story-glyph`, `variant-glyph`, `workspace-glyph`, `chevron-right`, `external-link` | Inline-SVG glyph fns. Draws via `currentColor`. |

### Token contract

- **No raw `font-family` at call sites.** Chrome consumes `sans-stack` / `mono-stack` / `display-stack`; raw `font-family` literals are banned.
- **No raw hex literals at call sites.** Chrome consumes `(:token-name colors/tokens)`; raw `#xxxxxx` literals are banned.
- **No raw `transition` literals at call sites.** Chrome consumes `(:row motion/transitions)`; raw `transition` strings are banned.
- **`prefers-reduced-motion: reduce` is honoured.** Chrome motion falls back to static states behind the user-agent media query.

## `re-frame.story.ui.keybindings`

The chrome's keybinding registry + installer pair.

| Symbol | Kind | Audience | Intuition |
|---|---|---|---|
| `bindings` | Pure data table | `pure-data-for-help` | Canonical `{key → handler}` table for the chrome-visibility hotkeys (`f` / `s` / `a` / `t`). |
| `shortcut-keys` | Pure data → data | `pure-data-for-help` | The sorted list of bound keys. Consumed by the first-visit help overlay. |
| `install!` | Installer | `chrome-shell` | Install the single `window#keydown` capture-phase listener. Idempotent. |
| `uninstall!` | Installer | `chrome-shell` | Symmetric teardown. |

## Effects + coeffects registered by Story

| Fx id | Payload | Notes |
|---|---|---|
| `:story/set-arg` | `{:variant <id> :key <k> :value <v>}` | Dispatched by control widgets. |
| `:story/run-play` | `{:variant <id>}` | Run the play sequence. |
| `:story/reset` | `{:variant <id>}` | Reset to post-events baseline. |
| `:story/save-layout-as` | `{:workspace <id> :body <transit>}` | Persist active layout as a workspace. |

| Cofx id | Shape | Notes |
|---|---|---|
| `:story/active-modes` | `[<mode-id> ...]` | Chrome-toolbar's active mode-set. |
| `:story/active-args` | `{<arg-key> <value>}` | Deep-merge of all active modes' `:args`. |
| `:story/substrate` | `:reagent` / `:uix` / `:helix` | Active substrate. |

## Canonical assertion events

The seven `:rf.assert/*` events the auto-install registers at first `reg-*`.

| Event id | Payload | Semantics |
|---|---|---|
| `:rf.assert/path-equals` | `[path expected]` | `(= (get-in @app-db path) expected)` |
| `:rf.assert/path-matches` | `[path malli-schema]` | `(m/validate schema (get-in @app-db path))` |
| `:rf.assert/sub-equals` | `[sub-vec expected]` | `(= @(subscribe sub-vec) expected)` |
| `:rf.assert/dispatched?` | `[event-vec]` | Was this event dispatched during phase-4? |
| `:rf.assert/state-is` | `[machine-id state]` | Active state of `reg-machine` machine-id is state. |
| `:rf.assert/no-warnings` | `[]` | No `:rf.warn/*` events seen during play. |
| `:rf.assert/effect-emitted` | `[fx-id]` / `[fx-id pred]` | Did the variant's drain emit fx-id? Optional unary `pred` over the fx-id keyword. |

## What this reference deliberately omits

Several surfaces are **publicly visible** in the CLJS source but explicitly *not part of the contract*. They're documented in the [developer-internal spec](https://github.com/day8/re-frame2/blob/main/tools/story/spec/API.md) for Story's maintainers; this reference omits them on purpose.

- **`re-frame.story.ui.url-state` engine.** `url-from-state`, `params-from-state`, `embed-flag-from-current-url`, `hydrate-embed-flag!` — chrome-internal URL surfaces. The facade exposes `variant-share-url`; the other two URL surfaces (address-bar URL, embed flag) live in this sub-ns and are consumed by the shell's bootstrap.
- **Story's late-bind shims.** `re-frame.story.late-bind` — the indirection that breaks circular requires between the registrar (consumer) and the canonical installer (producer).
- **Panel-mount aggregators.** Story's shell calls `mount-<panel>!` aggregators internally; they're not part of the host-facing embed contract.
- **`re-frame.story.config` atom handles.** Every state setter writes to a `defonce` atom; the atoms are reachable from CLJS-default-public visibility. The setters in `configure!` are the canonical write path.

If you find yourself reading source for a Story-internal symbol because the chapters don't list it, the answer is almost always: the spec considers that surface internal, and a future minor release may rename or `^:private`-mark it. Reach for the documented surfaces in the chapters above instead.

## See also

- [Index](index.md) — the navigation map for the four chapters in this folder.
- [Registration](registration.md) — the seven `reg-*` macros + `*`-fn partners.
- [Play scripts](play-script.md) — the `:play-script` grammar + the canonical seven `:rf.assert/*` events.
- [Runtime](runtime.md) — `configure!`, `run-variant`, `mount-shell!`, the registry-query family.
- [MCP surface](mcp-surface.md) — the Story-MCP boundary, wire-elision discipline, write-surface gating.
- [Normative spec — `tools/story/spec/API.md`](https://github.com/day8/re-frame2/blob/main/tools/story/spec/API.md) — the developer-internal source of truth.
