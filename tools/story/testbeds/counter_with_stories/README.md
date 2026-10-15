# counter_with_stories

The canonical worked example for [`tools/story/`](../../../../tools/story/)
(`day8/re-frame2-story`). The counter the rest of the guide pivots
around, with 7 of the 9 Story authoring macros wired up
end-to-end (the `reg-fragment` / `reg-check` composition cohort is not
exercised here):

- `reg-tag`         — `:counter-with-stories/canonical`
- `reg-mode`        — `:Mode.app/dark` + `:Mode.app/light`
- `reg-decorator`   — `:counter-with-stories/log-decorator`
- `reg-story-panel` — `:Panel.counter-with-stories/notes`
- `reg-story`       — `:story.counter` (+ three sibling fixture parents)
- `reg-variant`     — five exemplary `:story.counter` variants (empty / loaded / clicked-three-times / save-stubbed / events-only-loaded), plus the gate fixtures below
- `reg-workspace`   — `:Workspace.counter/all-states` (`:grid`) + `:Workspace.counter/auto-grid` (`:variants-grid`) + prose / tabs / custom layout fixtures

## Two layers in one namespace

`stories.cljs` stacks two surfaces, banner-demarcated in the source:

- exemplary block — the `:story.counter` parent, its 5 teaching
  variants, and the all-states / auto-grid workspaces. This is the
  small, copyable reference a consumer reads to learn the authoring
  shapes. The 4 canonical variants author the documented assertion
  surface — the `:assert-db` checkpoint sugar for app-db equality plus
  the explicit `[:assert [:rf.assert/…]]` form for the assertions the
  sugar does not cover (`sub-equals`, `dispatched?`, `effect-emitted`)
  — alongside the built-in `force-fx-stub` decorator for the save-flow
  variant. The fifth variant, `events-only-loaded`, pins the canonical
  events-only loader-body shape (no `:loaders`, no
  `:loaders-complete-when`, no `:frame-setup` decorators) that takes the
  `:pre-mount → :ready` lifecycle fast path.
- gate-fixture block — the `:story.counter-diagnostics`,
  `:story.counter-matrix`, and `:story.counter-play-script` sibling
  parents and their roughly 30 variants: deliberate failure / matrix
  / CI fixtures that keep Story's diagnostics, feature-load gate,
  schema / a11y / recorder / isolation surfaces, and the CI-as-test
  runner under continuous coverage. These are not teaching variants.
  (Splitting them into a sibling fixtures file is tracked separately.)

## File layout

```
counter_with_stories/
├── README.md                                ; this file
├── core.cljs                                ; entry — hash-routes #/ vs #/stories
├── events.cljs                              ; :counter/initialise, /inc, /dec, /save
├── subs.cljs                                ; :count, :count-doubled, :count-parity
├── views.cljs                               ; counter-card, counter-buttons, parity-badge
├── stories.cljs                             ; seven of the nine reg-* macros (no fragment/check)
├── elision_demo.cljs                        ; :sensitive + :large + event-emit
├── story_static.cljs                        ; static-export entry (:story-static/counter-with-stories)
├── stories_cljs_test.cljs                   ; integration tests (npm run test:cljs)
├── elision_demo_cljs_test.cljs              ; elision-pipeline tests (npm run test:cljs)
├── index.html                               ; the host page
└── story_static.index.html                  ; the static export's host page
```

## Privacy + size elision demo

The live app embeds an "elision card" underneath the counter. Each
button drives one branch of the privacy + size elision arc the
README markets as a headline feature:

- **Sign in (sensitive)** dispatches `:auth/sign-in`, whose
  registration classifies the payload's `:password` path
  (`{:sensitive [[:password]]}`, the registration-owned classification
  of [Spec 015](../../../../spec/015-Data-Classification.md)). The
  dispatched-event trace carries `:password :rf/redacted`, so Xray and
  the Story recorder see the sentinel while the handler receives the raw
  value. Handler-metadata `:sensitive? true` would not redact: it plays
  no part in privacy.
- **Upload large avatar (inline)** dispatches `:user.avatar/upload`
  with a 20 kB string in the event payload. Path D removed runtime
  size auto-elision, so an unschema'd / unclassified event-vector blob
  rides through the listener unchanged; large-value egress is declared,
  not auto-detected — classify app-db slots via the commit-plane `:large`
  effect (a `reg-event` returning `:large` alongside `:db`).
- **Set avatar PDF (large)** writes a synthetic blob into
  `[:user/avatar-pdf]` in app-db — a slot classified `:large` on the
  testbed's `:rf/default` frame by the `:counter/classify-avatar-large`
  event (the EP-0025 commit-plane `:large` effect — durable app-db egress
  rides the effects, not a schema prop, not a frame annotation). Walking
  app-db through `re-frame.elision/elide-wire-value` substitutes the slot with a marker
  map carrying `:reason :effect` (the commit-plane classification provenance).
- **Walk app-db through elision** runs the live frame's app-db
  through `re-frame.elision/elide-wire-value` and logs the result. The
  `:user/avatar-pdf` slot shows up as the marker map; everything
  else passes through.

The example also registers a console-logging listener at boot,
directly on the implementation-tier `re-frame.event-emit` registry
(`register-event-listener!` in [`elision_demo.cljs`](elision_demo.cljs)),
because the demo exists to show the raw record that the registration
classification and the wire walker act on. Every processed event
prints one tight record (`{:event :event-id :frame :time :outcome
:elapsed-ms}`), and the substrate survives `:advanced` +
`goog.DEBUG=false` where the trace surface is elided. The
registration is intentionally ungated so visitors can see the
listener fire. It is not how an app observes production: that is a
sink, registered with `rf/register-observability-sink!` and named in
a frame's `:observability` config or the
`(rf/configure! {:observability …})` process default, which delivers
the projected record — see
[Consuming production telemetry](../../../../docs/core/observability.md#consuming-production-telemetry-declare-a-sink).

Tests at [`elision_demo_cljs_test.cljs`](elision_demo_cljs_test.cljs)
assert every branch — commit-plane-classified `:large` app-db elision
(`:reason :effect`), unschema'd inline payloads riding through raw, the
registration classification redacting the sign-in `:password`, and the
event-emit listener firing per dispatch.

## Running

The build id is `:examples/counter-with-stories`.

### Interactive loop

```bash
# From implementation/
npm run dev -- :examples/counter-with-stories
```

Then visit `http://localhost:8042/#/stories` to hot-reload the Story
shell against your edits (`#/` is the live counter). The dev server
serves this directory ahead of the build output, so `index.html`
needs no staging.

### What covers it

All commands run from `implementation/`.

- **Unit tests** — `npm run test:cljs`. Its `node-test` build runs
  [`stories_cljs_test.cljs`](stories_cljs_test.cljs) and
  [`elision_demo_cljs_test.cljs`](elision_demo_cljs_test.cljs). The
  first runs the four canonical `:story.counter` variants, three
  diagnostic fixtures and two matrix fixtures through the Story
  runtime and checks each result, and pins the matrix variant set;
  the second covers the elision demo above.
- **Compile sweep** — `node scripts/check-examples-compile.cjs`
  compiles this build alongside every other `:examples/*` and
  `:testbeds/*` build; `--list` prints the roster.
- **Story browser gates** — `npm run test:story-feature-load` and
  `npm run test:story-play-scripts` compile this build, stage this
  directory's `index.html` and drive the Story shell in Playwright;
  `npm run test:story-static` builds and loads the
  `:story-static/counter-with-stories` export.

## Bundle isolation

Per [`tools/story/spec/005-SOTA-Features.md` §Production elision](../../../../tools/story/spec/005-SOTA-Features.md) and
[`implementation/scripts/check-bundle-isolation.cjs`](../../../../implementation/scripts/check-bundle-isolation.cjs),
the counter example's `:advanced` bundle MUST NOT carry any Story
implementation symbols. The contract is enforced by:

- `re-frame.story.config/enabled?` — when set to `false` via
  `:closure-defines`, every `reg-*` macro elides to `nil` and
  `mount-shell!` short-circuits before any DOM call.
- the bundle-isolation grep test checks for the Story-internal
  sentinel set (the `:rf.error/unknown-tag` reason string, the
  `:rf.error/decorator-*` taxonomy) in the
  `out/examples/counter` bundle. The example here counter_with_stories
  is intentionally a dev build (Story enabled) so the playground
  works; the production-flavoured probe is the plain `counter`
  example.

## See also

- the Story tutorial at [`docs/story/`](../../../../docs/story/)
- the Story tool's authoring contract at [`tools/story/spec/`](../../../../tools/story/spec/)
- the normative spec at [`spec/007-Stories.md`](../../../../spec/007-Stories.md)
- the agent-facing MCP surface at [`tools/story-mcp/`](../../../../tools/story-mcp/)
