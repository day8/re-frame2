# counter_with_stories

The canonical worked example for [`tools/story/`](../../../tools/story/)
(`day8/re-frame2-story`). The counter the rest of the guide pivots
around, with the seven Story authoring macros wired up end-to-end:

- `reg-tag`         — `:counter-with-stories/canonical`
- `reg-mode`        — `:Mode.app/dark` + `:Mode.app/light`
- `reg-decorator`   — `:counter-with-stories/log-decorator`
- `reg-story-panel` — `:Panel.counter-with-stories/notes`
- `reg-story`       — `:story.counter`
- `reg-variant`     — four variants (empty / loaded / clicked-three-times / save-stubbed)
- `reg-workspace`   — `:Workspace.counter/all-states` (`:grid`) + `:Workspace.counter/auto-grid` (`:variants-grid`)

The four variants exercise three of the seven canonical
`:rf.assert/*` events (`path-equals`, `sub-equals`, `dispatched?`,
`effect-emitted`) plus the built-in `force-fx-stub` decorator for
the save-flow variant.

## File layout

```
counter_with_stories/
├── README.md                                ; this file
├── core.cljs                                ; entry — hash-routes #/ vs #/stories
├── events.cljs                              ; :counter/initialise, /inc, /dec, /save
├── subs.cljs                                ; :count, :count-doubled, :count-parity
├── views.cljs                               ; counter-card, counter-buttons, parity-badge
├── stories.cljs                             ; the seven reg-* calls
├── elision_demo.cljs                        ; :sensitive? + :large? + event-emit (rf2-vw0to)
├── stories_cljs_test.cljs                   ; integration tests (npm run test:cljs)
├── elision_demo_cljs_test.cljs              ; elision-pipeline tests (npm run test:cljs)
├── counter_with_stories.spec.cjs            ; Playwright smoke (npm run test:examples)
└── index.html                               ; the host page
```

## Privacy + Size elision demo (rf2-vw0to)

The live app embeds an "elision card" underneath the counter. Each
button drives one branch of the privacy + size elision arc the
README markets as a headline feature:

- **Sign in (sensitive)** dispatches `:auth/sign-in` — registered
  with `:sensitive? true` handler metadata as the whole-handler
  privacy escape hatch. The metadata stamps every trace event emitted
  inside the handler's scope with `:sensitive? true` (Causa filters
  those out and surfaces `[● REDACTED N]` in the bottom rail).
- **Upload large avatar (inline)** dispatches `:user/avatar/upload`
  with a 20 kB string in the event payload. Path D does not auto-elide
  unschema'd event-vector blobs; large-value elision is declared with
  schema `{:large? true}` on app-db slots.
- **Set avatar PDF (large)** writes a synthetic blob into
  `[:user/avatar-pdf]` in app-db — a slot declared `:large?` via
  `reg-app-schema`. Walking app-db through
  `rf/elide-wire-value` substitutes the slot with a marker map
  whose `:hint "Avatar PDF blob"` propagates verbatim from the
  schema, orienting AI consumers without forcing a drill-down.
- **Walk app-db through elision** runs the live frame's app-db
  through `rf/elide-wire-value` and logs the result. The
  `:user/avatar-pdf` slot shows up as the marker map; everything
  else passes through.

The example also registers a console-logging
[`event-emit` listener](../../../docs/guide/22-trace-to-datadog.md)
at boot via `rf/register-event-emit-listener!`. Every dispatched
event prints one tight record (`{:event :event-id :frame :time
:outcome :elapsed-ms}`) — the same shape the chapter-22 Datadog
recipe forwards in production. The substrate is **always-on**: it
survives `:advanced` + `goog.DEBUG=false` where the trace surface
DCE's, which is the whole point of the rf2-rirbq carve-out. The
demo's registration is intentionally ungated so visitors can see
the listener fire; production deployments AND the registration
with `(not ^boolean re-frame.interop/debug-enabled?)` per the
chapter-22 recipe.

Tests at [`elision_demo_cljs_test.cljs`](elision_demo_cljs_test.cljs)
assert every branch — schema-driven `:large?`, auto-detect
`:large?`, the `:sensitive?` registration-meta read-back, and the
event-emit listener firing per dispatch.

## Running

The example is wired into the existing test orchestrator and rides
under build id `examples/counter-with-stories`.

### Smoke (Playwright)

```bash
# From implementation/
npm run test:examples
```

The orchestrator at
[`examples/scripts/serve-and-run-examples-tests.cjs`](../../scripts/serve-and-run-examples-tests.cjs)
compiles every example, stages this directory's `index.html`,
serves the output over HTTP on `127.0.0.1:8030`, and runs every
`*.spec.cjs` under `examples/`. The spec navigates between
`/counter-with-stories/#/` and `/counter-with-stories/#/stories`,
verifying both surfaces render.

### Integration (CLJS test)

```bash
# From implementation/
npm run test:cljs
```

`shadow-cljs.edn`'s `node-test` build picks up
`counter_with_stories.stories-cljs-test`. The tests assert every
`reg-*` artefact registered, every variant runs through the
four-phase lifecycle, and every play sequence's assertions pass.

### Single-example interactive loop

```bash
# From implementation/ — once you've run the example orchestrator
# once to stage the index.html.
shadow-cljs watch examples/counter-with-stories
```

Then visit `http://127.0.0.1:8030/counter-with-stories/#/stories` to
hot-reload the Story shell against your edits.

## Bundle isolation

Per [`tools/story/spec/005-SOTA-Features.md` §Production elision](../../../tools/story/spec/005-SOTA-Features.md) and the
Stage-8 sentinel addition to
[`implementation/scripts/check-bundle-isolation.cjs`](../../../implementation/scripts/check-bundle-isolation.cjs),
the counter example's `:advanced` bundle MUST NOT carry any Story
implementation symbols. The contract is enforced by:

- `re-frame.story.config/enabled?` — when set to `false` via
  `:closure-defines`, every `reg-*` macro elides to `nil` and
  `mount-shell!` short-circuits before any DOM call.
- The bundle-isolation grep test checks for the Story-internal
  sentinel set (the `:rf.error/unknown-tag` reason string, the
  `:rf.error/decorator-*` taxonomy) in the
  `out/examples/counter` bundle. The example here counter_with_stories
  is intentionally a DEV build (Story enabled) so the playground
  works; the production-flavoured probe is the plain `counter`
  example.

## See also

- The Story tutorial at [`docs/story/`](../../../docs/story/).
- The Story tool's authoring contract at [`tools/story/spec/`](../../../tools/story/spec/).
- The normative spec at [`spec/007-Stories.md`](../../../spec/007-Stories.md).
- The agent-facing MCP surface at [`tools/story-mcp/`](../../../tools/story-mcp/).
