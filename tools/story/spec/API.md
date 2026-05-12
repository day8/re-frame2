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
| `handlers` | `(handlers kind)` | All registrations for `kind` (Story kinds: `:story`, `:variant`, `:workspace`, `:story-panel`, `:tag`, `:mode`, `:decorator`). |
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
| `:rf.assert/effect-emitted` | `[fx-id]` (optional `pred`) | Did the variant's drain emit fx-id? |

## Shell lifecycle

| Fn | Signature | Spec |
|---|---|---|
| `mount-shell!` | `(mount-shell! mount-point opts)` | [`003-Render-Shell.md`](003-Render-Shell.md) §Shell lifecycle |
| `unmount-shell!` | `(unmount-shell!)` | [`003-Render-Shell.md`](003-Render-Shell.md) §Shell lifecycle |
| `active-shell` | `(active-shell)` | [`003-Render-Shell.md`](003-Render-Shell.md) §Shell lifecycle |

## Configuration

| Var / fn | Notes |
|---|---|
| `goog-define :rf.story/enabled?` | Compile-time DCE flag; `true` in dev, `false` in `:advanced`. See [`005-SOTA-Features.md`](005-SOTA-Features.md). |
| `configure!` | `(configure! {:global-args {...}})` — set global args at boot. |

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
- [`Principles.md`](Principles.md) — design principles.
- [`DESIGN-RATIONALE.md`](DESIGN-RATIONALE.md) — why each call was
  made.
- [`tools/story-mcp/spec/API.md`](../../story-mcp/spec/API.md) — the
  MCP jar's tool surface.
