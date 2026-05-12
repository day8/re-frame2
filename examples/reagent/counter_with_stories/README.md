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
├── stories_cljs_test.cljs                   ; integration tests (npm run test:cljs)
├── counter_with_stories.spec.cjs            ; Playwright smoke (npm run test:examples)
└── index.html                               ; the host page
```

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

- The guide chapter at [`docs/guide/20-stories.md`](../../../docs/guide/20-stories.md).
- The Story tool's authoring contract at [`tools/story/spec/`](../../../tools/story/spec/).
- The normative spec at [`spec/007-Stories.md`](../../../spec/007-Stories.md).
- The agent-facing MCP surface at [`tools/story-mcp/`](../../../tools/story-mcp/).
