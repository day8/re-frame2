# Story — Render Shell

> The UI: sidebar, canvas, controls, workspaces, scrubber, trace; the
> five workspace layouts (`:grid` / `:prose` / `:variants-grid` /
> `:tabs` / `:custom`); hot-reload decorator fingerprinting; the
> `mount-shell!` / `unmount-shell!` / `active-shell` lifecycle. The
> contract Stage 4 implements.

See [`007-Mode-Tabs.md`](007-Mode-Tabs.md) for the `:dev` / `:docs` /
`:test` mode-tabs primitive that sits at the top of the canvas pane
(rf2-9hc8).

## UI shell substrate

Story's own UI shell (sidebar, control panel, trace ribbon, embedded
10x panel, etc.) renders using **Reagent** at v1, sourced from
`implementation/adapters/reagent/`. The UI shell namespaces live under
`tools.story.ui.*`.

### Rationale

- **Reagent is stable and dogfood-neutral.** The Story UI shell
  exercises the same re-frame primitives Story stories exercise; using
  a substrate the rest of the codebase already validates avoids a
  self-hosting bias.
- **reagent-slim is still landing.** The slim rewrite is in active
  implementation. Story should not block on it; once reagent-slim is
  GA Story can migrate (Stage 8 may revisit).
- **The UI shell is itself one app's worth of views; substrate
  switches are cheap if done early.** Reagent's API surface is the
  most well-trodden; reducing risk during the initial Stage 4
  render-shell push.

### Bundle implications

The `tools/story/deps.edn` declares `reagent/reagent` (Maven coord)
and `day8/re-frame2-reagent` (the adapter, via `:local/root`).
Together these load Reagent v2 (per `feedback_target_reagent_v2`) in
the dev tool's runtime. In a `:advanced` build of the host app, Story
DCEs entirely (see [`005-SOTA-Features.md`](005-SOTA-Features.md) §DCE
contract) so neither dep reaches production.

## Workspace layouts

A `reg-workspace` body declares `:layout`; the render shell hosts five
canonical layouts:

| Layout | Source data | UX |
|---|---|---|
| `:grid` | explicit `:variants` list | Responsive grid; per-card width/height; drag-resize. |
| `:prose` | `:content` blocks (`{:type :prose :body "md..."}` or `{:type :variant :id ...}`) | Markdown narrative with embedded variants. |
| `:variants-grid` | implicit — enumerates variants from the registry for one parent story | devcards-style "all states at once" view. |
| `:tabs` | explicit `:variants` list | Tab strip; one variant active at a time. |
| `:custom` | `:render <view-id>` | Hosts an arbitrary registered view. |

The `:variants-grid` layout (Phase 2 SOTA addition) renders every
variant of a single parent story side-by-side; it differs from `:grid`
(which renders an explicit `:variants` list) by enumerating variants
from the registry.

## Shell lifecycle

```clojure
(mount-shell! mount-point opts)        ; attach the Story chrome
(unmount-shell!)                       ; detach
(active-shell)                         ; => {:mount-point ... :workspace ...} or nil
```

Hot-reload preserves shell state: a re-mount calls into the same shell
node and reseats the workspace. Decorator fingerprinting tracks
whether the decorator stack used by a mounted variant has changed at
the registry level; stale variants re-mount.

## Multi-substrate side-by-side rendering

For a variant declaring `:substrates #{:reagent :uix :helix
:reagent-slim}` (or any subset; default = the host frame's adapter),
the render shell renders each substrate **inline** in its own pane.

- Each per-substrate render runs in a try/catch boundary.
- A substrate-specific failure renders inline alongside the other
  substrates' healthy renders — it is **not** auto-skipped.
- The failure record is appended to the variant's `:assertions` list,
  tagged with the substrate that failed.

The rationale for inline-rendering substrate failures is documented in
[`DESIGN-RATIONALE.md`](DESIGN-RATIONALE.md) §inline-substrate-failures.

## Workspace persistence (both modes)

Workspace layouts persist **both** ways:

- **Default: local-storage.** Interactive rearrangements (drag-resize
  panes, reorder variants in a grid) auto-save to local storage keyed
  by `[workspace-id breakpoint]`. Persistence is per-user, per-browser.
- **Save-as registered artefact.** A "Save layout as `:Workspace.x/y`"
  button serialises the current layout to transit (full
  `reg-workspace` body), re-registers it under the chosen id, and
  exports the transit blob for cross-machine sharing. Other users
  `(rf/reg-workspace ...)` the transit body to consume the layout
  durably.

Stage 4 (render shell) wires the local-storage save on every layout
change; Stage 4 also adds the "Save layout" affordance.
`tools.story.workspace.transit/workspace->edn` returns the serialised
form. Rationale: see [`DESIGN-RATIONALE.md`](DESIGN-RATIONALE.md)
§both-workspace-persistence.

## Trace bus consumption

Each variant's frame gets a trace callback registered at variant-mount:

```clojure
(rf/register-trace-cb! variant-id
  (fn [trace-event]
    (swap! (variant-trace-buffer variant-id) conj trace-event)))
```

The trace panel
(`tools/story/src/tools/story/panels/trace.cljs`) subscribes to
`(rf/subscribe [:story/trace-events variant-id])` and renders the
six-domino sequence (event → handler → fx → effect → subscription →
re-render). Per Phase 1 §4.3 this is the debugging UX no JS tool can
match.

## Panel registration contract

The `reg-story-panel` surface is the **single hook** through which
tooling embeds itself into the Story chrome — no new registry kind,
no parallel mounting protocol. Five rules govern panel hosting:

1. **`:render` is a `:view` id.** The shell renders the panel by
   calling `(rf/view <render-id>)` and invoking the resolved fn with
   the current `variant-id`. Late-bind: the actual view can register
   from a different artefact (e.g. `day8/re-frame2-causa` for the epoch
   panel) so long as the same `:view` id is registered before the user
   opens the panel.

2. **Placement is one of five slots.** `:right` / `:left` / `:bottom`
   / `:top` host the panel inline; `:modal` opens it over the canvas.
   Each placement is an independent host; multiple panels at the same
   placement stack in id order.

3. **Visibility flows through `:panel-visibility`.** The shell state's
   `:panel-visibility` map (keyed by panel id) is the on/off switch.
   Unspecified → visible; explicit `false` → hidden. Users toggle via
   the chrome's panel-list affordance.

4. **Author calls `reg-story-panel` from anywhere on the classpath.**
   The built-in panels (`:rf.story.panel/a11y` /
   `:rf.story.panel/layout-debug` / `:rf.story.panel/epoch`) register
   from inside `install-canonical-vocabulary!`; third-party tooling
   (e.g. Causa's epoch view, future statechart-viz panels) registers
   from its own boot.

5. **The Causa embed.** The built-in `:rf.story.panel/epoch` panel
   registers against a STUB view. The Causa library
   ([`tools/causa/`](../../causa/)) registers the live view under the
   same `:rf.story.panel/epoch-view` id when present; the shell's
   late-bind `rf/view` lookup picks Causa's view automatically. If
   Causa is absent the stub renders documenting the contract.

See [`006-MCP-Surface.md`](006-MCP-Surface.md) for how the agent
surface re-uses this contract.

## Source-coord stamping flow (UI side)

The story tool's "Open in editor" affordance (v1.1) reads `:source`
off the variant registry record. The play-runner copies the `:source`
of each `:play` event into the corresponding `:assertions` record so
failure cards link back to source.

## Namespace layout

```
tools/story/
├── deps.edn
├── README.md
├── spec/                                        ; this folder
├── src/
│   ├── re_frame/
│   │   └── story.cljs                           ; public ns — reg-* macros + run-variant
│   │   └── story.clj                            ; macro impl (the dev/prod expand split)
│   └── tools/
│       └── story/
│           ├── impl/                            ; private — what the macros call
│           │   ├── reg.cljs                     ; reg-story*, reg-variant*, etc.
│           │   ├── schema.cljs                  ; :rf/variant Malli schema
│           │   └── extends.cljs                 ; :extends resolution
│           ├── registry.cljs                    ; side-table; query helpers
│           ├── runtime/
│           │   ├── frame.cljs                   ; per-variant frame allocation
│           │   ├── args.cljs                    ; effective-args resolution
│           │   ├── decorators.cljs              ; composition order
│           │   ├── loaders.cljs                 ; 4-phase lifecycle
│           │   ├── play.cljs                    ; play-runner + assertion recorder
│           │   ├── snapshot-id.cljs             ; content-hash
│           │   └── source-coord.cljs            ; source-stamp pipe
│           ├── render/
│           │   ├── shell.cljs                   ; sidebar / canvas / panels
│           │   ├── variants_grid.cljs           ; :variants-grid layout
│           │   ├── controls.cljs                ; auto-derived controls
│           │   ├── multi_substrate.cljs         ; side-by-side substrate panes
│           │   └── time_travel.cljs             ; epoch scrubber (10x embed adapter)
│           ├── panels/
│           │   ├── trace.cljs                   ; six-domino panel
│           │   ├── a11y.cljs                    ; axe-core integration
│           │   ├── perf.cljs                    ; live ribbon (v1.1)
│           │   ├── layout_debug.cljs            ; measure / outline / pseudo
│           │   ├── design_tokens.cljs           ; v1.1, conditional
│           │   └── docs.cljs                    ; autodocs from :doc + schemas
│           ├── share/
│           │   ├── qr.cljs                      ; QR code generator
│           │   └── transit.cljs                 ; workspace transit export
│           ├── workspace/
│           │   ├── grid.cljs
│           │   ├── prose.cljs
│           │   └── transit.cljs
│           └── ui/
│               ├── widgets.cljs                 ; controls widgets
│               ├── theme.cljs
│               └── routing.cljs                 ; story-tool URL surface
└── test/
    └── tools/
        └── story/
            └── ...
```

The macro-emitting layer is `re-frame.story` (the user-facing ns); all
internal implementation lives under `tools.story.*`. Public ns names
match the convention from `re-frame.adapter.reagent` /
`re-frame.ssr`.
