# Story — SOTA Features

> The `force-fx-stub` headline (one primitive replaces the
> Storybook addon parade); the layout-debug overlay trio; a11y
> axe-core panel; multi-substrate side-by-side rendering; the Xray
> epoch panel embed contract; the v1.1 deferrals (perf ribbon,
> design tokens); production elision under `:advanced`
> (`re-frame.story.config/enabled?` sentinel pattern). The contract Stage 6
> implements + the production hygiene rules that apply across stages.

See [`019-Story-UI-Controls-And-View-States.md`](019-Story-UI-Controls-And-View-States.md)
§3 for the Story-UI placement of save-current-state-as-variant (this
spec owns the affordance; `019` places it coherently in Controls and
keeps it distinct from generated-failure promotion in
[`021-Story-UI-Test-And-Evidence.md`](021-Story-UI-Test-And-Evidence.md)
§3).

## `force-fx-stub` — mock anything, not just the network

**Don't mock just the network. Mock anything.**

Storybook ships a separate addon for each thing you want to fake:
HTTP via MSW (`msw-storybook-addon`, 2.3M weekly downloads),
analytics via bespoke decorators or `msw`-extensions, websockets via
yet another addon, storage via shim libraries, navigation via
another. Each is a single-purpose plugin the user has to find,
install, configure, and learn — and each one carves out its own
mental model for "what does mocking look like for *this* concern?"

Story has it in **one primitive**: any effect handler you registered
with `reg-fx` can be stubbed with one decorator in the variant body.
The stub **suppresses** the effect and records the call. It never
answers. That makes it the *freeze* tool: the canvas shows the state
the app is in while the effect is outstanding, or dead.

Set up a variant whose analytics pipeline is down:

```clojure
(story/reg-variant :story.checkout/analytics-down
  {:extends    :story.checkout/happy-path
   :decorators [[:rf.story/force-fx-stub :analytics/track {}]]})
```

Absorbing *is* "down": every `:analytics/track` the checkout emits is
recorded, and none leaves the page. The same decorator suppresses a
websocket send, a geolocation request, a storage write, a navigation,
or anything else you `reg-fx`'d in your app. Same primitive, same
variant body shape. No new dependency per fx kind, no new mental
model per addon.

A state that needs a *reply* is authored with a surface that delivers
one. A failed checkout request replies through `:network`, the
managed-HTTP affordance
([`017-Testing-Story.md`](017-Testing-Story.md) §The network surface):

```clojure
(story/reg-variant :story.checkout/network-failure
  {:extends :story.checkout/happy-path
   :network {[:post "/api/checkout"]
             {:reply {:failure {:kind :rf.http/http-5xx :status 503}}}}})
```

Any other effect replies through the app's own event. Dispatch it in
`:setup`:

```clojure
(story/reg-variant :story.dashboard/ws-disconnected
  {:extends :story.dashboard/happy-path
   :setup   [[:dispatch [:dashboard/ws-closed {:code 1006}]]]})
```

or redirect the effect with first-class `:fx-overrides` to a
registered stub fx that dispatches it:

```clojure
(rf/reg-fx :story.stub/geo-denied
  (fn [_ctx {:keys [on-error]}]
    (rf/dispatch (conj on-error {:status :denied}))))

(story/reg-variant :story.profile/geo-denied
  {:extends      :story.profile/happy-path
   :fx-overrides {:geo/locate :story.stub/geo-denied}})
```

A reply authored this way exercises what the app does with that reply,
the state transition downstream of it. It does not prove the request
would have produced that reply.

### Why this is an architectural win, not a feature

This isn't a feature competition; it's an architectural win. The
asymmetry is structural:

- **Storybook** translates "mock the network" into "mock everything
  you care about" via a parade of single-purpose addons because the
  framework has no shared abstraction for "the side-effecting thing
  this component triggers." Each integration (HTTP, ws, analytics,
  geo, storage) reaches into the host app through a different seam,
  so each one needs its own addon.
- **re-frame2** already names that thing: it's an *effect*, every
  effect has a handler registered via `reg-fx`, and every handler is
  a function the runtime calls. Stubbing it is a one-liner because
  the seam already exists.

The lesson generalises across the spec: one well-chosen primitive
beats a parade of single-purpose addons. Story leans into this
wherever a Storybook integration is really "give me a seam I can
hijack" — `force-fx-stub` is the headline example, but the same
pattern shows up in `reg-decorator` (one mechanism, many concerns)
and `reg-story-panel` (one extension point, many panels).

### Authoring contract

`force-fx-stub` is a built-in `:fx-override` decorator (per
[`001-Authoring.md`](001-Authoring.md) §reg-decorator). The variant
body cites it the same way it cites any other decorator:

```clojure
(story/reg-variant :story.auth.login-form/loading
  {:decorators [[:rf.story/force-fx-stub :http {:status :pending}]]
   :setup      [[:auth/initialise]
                [:auth/login-pressed]]})
```

The id is `:rf.story/force-fx-stub` (the value of
`re-frame.story.fx-stubs/force-fx-stub-id`). A bare `:force-fx-stub`
names no registered decorator, so the run refuses before any phase
(see [`017-Testing-Story.md`](017-Testing-Story.md) §The effect-override
surface).

The decorator takes an fx-id and a response. The response is
DATA: the stub records it beside each call's payload in the per-frame
stub-call log and returns nothing, so no reply event fires and nothing
reaches app-db. It applies at frame creation; the stub is per-frame
and per-variant, so two variants of the same story can stub the same
fx with different recorded responses without cross-talk.

For the full decorator surface — the `:fx-override` kind, the
`:response` slot, the `:rf.assert/effect-emitted`
interaction — see [`004-Assertions.md`](004-Assertions.md)
§`force-fx-stub` interaction and [`002-Runtime.md`](002-Runtime.md)
§Decorator composition order.

### Test-mode integration

A `:test`-tagged variant that stubs an fx runs like one that
doesn't. `run-variant` returns the same
`{:frame :app-db :assertions :elapsed-ms}` shape. The stubbed fx still
counts as emitted for `:rf.assert/effect-emitted`, which checks the
fx-id only and sees neither the payload nor the response. Each call,
with its payload and the recorded response, is in the stub-call log
(`re-frame.story.frames/stub-call-log-for`). Stories-as-tests pick up
"the analytics pipeline is dead" coverage for free.

## v1 panels (must-ship)

### a11y (axe-core) panel

axe-core integration runs against the rendered DOM:
- Inline in the canvas — violations list appears as a sidebar panel.
- CI hook — variants tagged `:a11y` (or just `:test`) run axe under
  `run-variant`; violations append to `:assertions` as
  `:rf.assert/a11y` failures.

Phase 1 §3.1 #3 names this "the cheapest win in the space."

Two panels share the engine and the CDN opt-in, but scope differently:

| Panel id                          | Scope (CSS selector)            | Concern                                                                                                    |
|-----------------------------------|---------------------------------|------------------------------------------------------------------------------------------------------------|
| `:rf.story.panel/a11y`            | `[data-rf-story-variant-root]`  | Variant-author concern. The default. Per rf2-qgms1 the canvas stamps the variant root so the scan excludes Story chrome (sidebar, toolbar, panels). |
| `:rf.story.panel/chrome-a11y`     | `[data-rf-story-root]`          | Story-chrome concern (rf2-18t6p). Dogfoods axe-core against the Story shell itself — variant authors never need to see these violations; the Story project does. |

The two panels share `a11y/ensure-axe-loaded!` + `a11y/cdn-opt-in?` so
one consent decision approves both. State is independent: chrome
violations don't pollute the per-variant panel and vice versa.

### Six-domino trace (via embedded Xray)

Per [`003-Render-Shell.md`](003-Render-Shell.md) §Right-hand pane,
Xray is mounted as the RHS primary inspector by default (rf2-sgdd3).
Xray's Trace tab renders the six dominoes (event → handler → fx →
effect → subscription → re-render) live, scoped to the selected
variant's frame. Story's per-variant `trace-buffer` listener stays
in place to feed the schema-validation panel; Xray runs its own
trace-cb registration for its UI.

This is the debugging UX no JS tool can match — re-frame's structured
trace has no JS analogue.

### Layout-debug overlay trio

DOM-mutating utilities, framework-agnostic, default-on as an opt-in
panel:

- **Measure.** Rulers + gap visualisation on hover (Storybook's
  addon-measure equivalent).
- **Outline.** Pesticide-style outline of every DOM node.
- **Pseudo-state forcing.** `:hover`, `:focus`, `:active`, `:visited`
  via class-swap; pairs with visual-regression for state-coverage
  snapshots.

Per Phase 2 §5.2 #2. Cheap to ship; framework-agnostic; all three
together are best-in-class. See
[`DESIGN-RATIONALE.md`](DESIGN-RATIONALE.md) §phase-2-SOTA-additions.

### Multi-substrate side-by-side rendering

A variant declares `:substrates #{:reagent :uix}` (subset,
default = the host frame's adapter); the multi-substrate pane renders
each substrate side-by-side. Substrate-specific failures render inline
(per [`003-Render-Shell.md`](003-Render-Shell.md) §Multi-substrate).

This is unique to re-frame2 — the multi-substrate goal in the EPs
makes the side-by-side rendering a natural fit; no JS tool can ship
this.

### `reg-mode` saved-tuple primitive

The Chromatic-style mode primitive (saved tuples of global args)
lands in v1 — not v1.1 — because the implementation cost is small
(it's a saved `args` map plus a snapshot-identity contribution) and
the agent-integration benefit is large (MCP can iterate variants ×
modes without combinatorial registration).

See [`001-Authoring.md`](001-Authoring.md) §reg-mode for the macro
surface and [`002-Runtime.md`](002-Runtime.md) §Args resolution
precedence for the merge order.

### `:variants-grid` workspace layout

Per Phase 2 §5.2 #4. devcards-style multi-variant viewing has no JS
competitor; the implementation cost is layout-only. See
[`003-Render-Shell.md`](003-Render-Shell.md) §Workspace layouts.

### Share URL (retired QR popover)

Per Phase 2 §5.2 #6 plus rf2-ymnfx Issue B (2026-05-27). The
per-variant share URL is the surviving artefact — encoded by
`re-frame.story.share/variant-share-url`, kept live in the browser's
address bar by `re-frame.story.ui.url-state` (pushState / popstate).
Cmd-L / Cmd-A / Cmd-C copies it; pasting it into another browser
reproduces the exact cell (variant + modes + cell-overrides +
viewport + background + tag-filter + substrate).

The original Storybook v10-style affordance was a small share button
on each variant's title row opening a popover with the URL + a QR
code. That UI was first hardened against off-box egress (rf2-20w5i —
local SVG QR generation via the vendored `qrcode-generator` npm
package replacing a third-party QR-image service) and then retired
entirely (rf2-ymnfx Issue B) because the URL was already live in the
address bar; the popover added nothing beyond what Cmd-L does for
free. The URL builder + parser remain because `url-state` writes
through them and the public `story/variant-share-url` facade is the
embed-code entry point (see [`Tutorial-Embed.md`](Tutorial-Embed.md)).

### Third-party network egress (rf2-20w5i)

Story is a **developer-session tool**, not a production runtime — but
the v1 SOTA features it ships (a11y panel) must not leak the dev's
variant state or load remote-hosted code without the dev's consent.
Per rf2-20w5i (security audit, 2026-05-14):

| Pre-fix endpoint | Post-fix |
|---|---|
| Per-variant share popover sourced its QR image from a third-party service, carrying the full share URL (variant state + author-typed cell-overrides) as a query param. | **Eliminated.** First (rf2-20w5i) replaced the third-party fetch with a local SVG encoder (`qrcode-generator` npm). Then (rf2-ymnfx Issue B) retired the popover outright because the share URL is already the browser's address bar — Cmd-L / Cmd-A / Cmd-C copies it. The vendored npm dep retired with the popover. |
| a11y panel injected `axe.min.js` from a public CDN at first panel open, unconditionally. | **Gated behind explicit opt-in.** First scan triggers a consent prompt explaining the egress; clicking 'enable axe-core + scan' persists the approval in `localStorage` (`:rf.story.a11y/cdn-opt-in`). The `<script>` carries SRI `integrity` + `crossorigin="anonymous"` so a compromised mirror fails closed. |

Why isn't axe-core vendored as a static `:require`? The audit's
preferred fix (`:require ["axe-core" :as axe]`) trips Closure
:advanced's strict ECMAScript parser on axe-core's UMD wrapper
(`function te(e){return(te=...)(e)}` reads as a duplicate
block-scoped declaration). Until shadow-cljs upgrades Closure or
axe-core ships a clean ESM build, the alternative fallback the audit
allows (axe-core opt-in CDN + SRI) lands here. The share-URL leak —
the High-severity finding — is eliminated outright; the a11y panel's
CDN load is now default-OFF and explicit, with tamper-detection.

The share URL itself is constructed locally
(`re-frame.story.share/variant-share-url`) and carries no app-db
payload — only variant identity + author-declared `cell-overrides`
round-trip through it. The URL never leaves the dev's machine; with
the share popover retired (rf2-ymnfx Issue B) there is no UI
affordance that could serialise it for off-box transport.

Production app builds (`re-frame.story.config/enabled?` false under `:advanced`) elide
the entire Story UI shell; the a11y panel is reachable only from the
disabled tree, so Closure DCE drops it. The bundle-isolation contract
(`scripts/check-bundle-isolation.cjs`) verifies the Story sentinels
are absent from `examples/counter`'s release bundle, which
transitively covers the a11y panel. Static-build deploys (rf2-8wgpm,
see [`013-Static-Build.md`](013-Static-Build.md)) carry the axe-core
opt-in prompt into the static site too, so a visitor running an a11y
scan there sees the same consent dialog before any CDN load.

### Xray epoch panel embed (per-panel mount)

Story embeds Xray's epoch panel (Xray is the structural successor to
re-frame-10x, per
[`tools/xray/spec/DESIGN-RATIONALE.md`](../../xray/spec/DESIGN-RATIONALE.md)
Lock #1) through the RHS per-panel mount catalogue in
`re-frame.story.ui.xray-embed` — NOT through `reg-story-panel`. The
single private embed descriptor there (rf2-jf87oq) lists the six chip
panels (`:epoch` default, `:app-db`, `:views`, `:trace`, `:machines`,
`:routing`) plus the non-chip `:event-spine` band, each bound to its
`day8.re-frame2-xray.panels/mount-<panel>!` fn; see
[`003-Render-Shell.md`](003-Render-Shell.md) §Right-hand pane and
§The contract — `panels/mount-<panel>!`. (A
`(reg-story-panel :rf.story/xray-epoch …)` form survives only as a
test-fixture stub in `story_test.clj` / `story_mcp_boundary_test.clj`;
nothing in `src/` registers it.)

The panels are consumed from `day8/re-frame2-xray` (per the
`tools/xray/` line in [`tools/README.md`](../../README.md)). Story owns
the *integration*; Xray stays its own artefact.

The embed ships with v1 and always activates: `day8/re-frame2-xray` is
a declared Story dependency (rf2-r8trk), so it is on the classpath by
construction and there is no absent-Xray path to hide behind. See
[`DESIGN-RATIONALE.md`](DESIGN-RATIONALE.md) §xray-embed and
§xray-is-a-declared-dependency.

### Test Codegen — record-as-`:script` (rf2-5fc15 + rf2-0wrud + rf2-7mj4z)

Storybook 9's killer feature is the record-and-save workflow: the
user interacts with the canvas, the tool watches the event bus, and
on 'stop' the user gets a code snippet they paste into the variant.
Story's `:script` body is **already** a sequence of EDN tagged
steps (per [`004-Assertions.md`](004-Assertions.md) §Play sequence
execution), so the captured trace IS the codegen output — no
Testing-Library/page-object translation layer needed.

The codegen emits the PUBLIC `:script` authoring slot (rf2-7mj4z — the
recorder no longer emits the transitional `:play-script` spelling), and
per rf2-0wrud (2026-05-20) each captured event vector is wrapped as
`[:dispatch-sync <event-vec>]` by `gen-play-snippet`.

The recorder is a chrome-wide toolbar affordance. A REC chip sits at
the right of the [toolbar](010-Toolbar.md), just before `[reset]`:

```
[chip] [chip]   ●dark ●mobile         [● REC 7]  [reset]
```

Click toggles. With a variant selected and no recording in flight,
clicking starts capture against that variant's frame. With a recording
in flight, clicking stops + opens a save-as-variant modal carrying the
generated `(reg-variant ...)` form:

```clojure
(rf.story/reg-variant :story.counter/recorded-739221
  {:extends :story.counter/happy-path
   :script  {:auto-run? true
             :script [[:dispatch-sync [:counter/inc]]
                      [:dispatch-sync [:counter/inc]]
                      [:dispatch-sync [:counter/by 7]]]}})
```

#### Capture boundary

The recorder consumes Story's existing trace-bus listener primitive
(per [`003-Render-Shell.md`](003-Render-Shell.md) §Trace bus + the
`re-frame.trace.tooling/register-listener!` API per Spec 009 §Listener
contract). One process-wide callback installed at shell mount; per
emit it short-circuits when no recording is in flight, so leaving it
installed is free.

Three filter layers:

1. **Op-type** — only `:rf.event/dispatched` emissions qualify (skip
   `:rf.fx` / `:rf.sub` / `:rf.view` / `:rf.cofx` traffic).
2. **Frame scope** — the emission's `:frame` tag must match the
   recording's target variant. Cross-frame dispatches (typing in
   another canvas while a recording is active) are dropped.
3. **Event vocabulary** — `:rf.assert/*` events and Story-internal
   helpers (`:rf.story/*`, `:re-frame.story.*`) are filtered. Authored
   assertions are deliberate; recorded `:script` bodies capture
   user intent, and assertions get added by hand.

#### Mid-recording assertion insertion (rf2-39u9e)

The recorder filters `:rf.assert/*` events off the trace bus
(assertions are authored, not observed). To make assertion authoring
as fast as the recording itself, the recording overlay carries an
`+ assert` button next to `stop`. Click opens a small modal picker
that enumerates the canonical seven `:rf.assert/*` ids from
`re-frame.story.recorder/assertion-vocabulary` (one-click pick) and
prompts for the assertion's EDN-typed payload fields. A live preview
renders the event vector that will land in the captured
`:script` body; clicking 'insert' appends it inline alongside the
dispatched events.

The picker doesn't pause recording — the user can keep clicking the
canvas after inserting. The captured `:script` body comes out
with assertions interleaved exactly where the user wanted them:

```clojure
(rf.story/reg-variant :story.counter/recorded
  {:extends :story.counter/happy-path
   :script
   {:auto-run? true
    :script [[:dispatch-sync [:counter/inc]]
             [:dispatch-sync [:counter/inc]]
             [:dispatch-sync [:rf.assert/sub-equals [:counter] 2]]    ; inserted via picker
             [:dispatch-sync [:counter/by 7]]
             [:dispatch-sync [:rf.assert/path-equals [:n] 9]]]})      ; inserted via picker
```

The picker's vocabulary list is data — `assertion-vocabulary` — so
the canonical seven (and any future additions to spec/004) can be
extended in one place. The pure helpers (`make-assertion`,
`append-assertion`, `insert-assertion!`) live in
`re-frame.story.recorder` so JVM tests cover the event-vector
construction without going through the modal.

#### Public API

```clojure
;; In re-frame.story
(start-recording!  variant-id)        ; returns recorder state
(stop-recording!)                     ; returns captured state map
(recording?)                          ; boolean
(recorder-state)                      ; current state — read-only view
(clear-recording!)                    ; drop captured trace, return to idle
(gen-play-snippet events opts)        ; render `(reg-variant ... :script {:script [...]})`

;; Mid-recording assertion insertion (rf2-39u9e)
recorder/assertion-vocabulary        ; data — the 7 canonical entries
(recorder/make-assertion id payload)  ; pure — build the event vec
(recorder/insert-assertion! id pl)    ; impure — append to :events
```

`opts` for `gen-play-snippet`:

- `:variant-id` (required) — keyword id for the new variant.
- `:doc`        (optional) — docstring.
- `:extends`    (optional) — variant id to `:extends` from (carries
                              `:component`, `:args`, `:decorators`).
- `:alias`      (optional) — short alias for the form (default `rf.story`, the canonical `re-frame.story` alias, so the form pastes and runs verbatim).

Pure data → string; the emitted form is `read-string`-able and
round-trips through re-frame's registrar machinery.

#### Why this is structurally simpler than Storybook's recorder

Storybook 9 records DOM events (`click`, `fill`, `select`) and emits
Testing Library calls (`fireEvent.click(...)`, `await userEvent.fill(...)`),
which then have to translate back through React's reconciliation. Story
records re-frame events directly: every user interaction in a Story
canvas eventually lands as a `dispatch` on the variant's router, and
the trace bus already projects those dispatches with `:rf.event/dispatched`
emissions per Spec 009. Capturing the right value is one filter on the
existing emit; the output shape is the exact vector the runtime will
re-dispatch (wrapped as `[:dispatch-sync <vec>]`) under
`:script`. The recorder is one screenful of code.

#### MCP wiring — retired (rf2-5saz7)

Story-MCP's `record-as-variant` bridge over these primitives was
retired: the tool blocked the server's only stdio dispatch loop for its
whole capture window, so no MCP client could drive a dispatch during
the recording and the advertised capture path was transport-unreachable
(the headless JVM has no browser bridge either). Recording a
live browser canvas — the user interacting with a running app — is
pair-owned: the pair evaluates the same recorder primitives in the
attached CLJS runtime (spec/006 §Two surfaces, one live door), where
the recorder and the interaction share a runtime. This
spec locks the recorder's runtime contract for its in-process/browser
consumers.

#### Recorder sub-system map (rf2-oaxgh)

The `re-frame.story.recorder` namespace is **a facade over five
cohesive sub-systems** plus parallel namespaces under
`re-frame.story.recorder.*` for the DOM-capture layer and the
`:script` v2 export pipeline. MCP tool builders, hot-reload
tooling, and bespoke test integrations consume specific sub-systems;
this section documents the boundary so a power user reads the right
ns rather than scanning the facade alone.

The seven sub-systems and their public boundaries:

| Sub-system | Lives in | Public surface | Audience |
|---|---|---|---|
| Trace-listener install + capture loop | `re-frame.story.recorder` | `install-trace-listener!` / `remove-trace-listener!` / `trace-listener` callback + `record-event!` (called by the listener). Filter chain per `recordable-event?`. | `chrome-shell` (one process-wide install at shell mount); `mcp-tool` (drives `record-event!` directly to bypass the trace bus). |
| Recorder atom + state machine | `re-frame.story.recorder` | `start-recording!` / `stop-recording!` / `toggle!` / `clear!` / `recording?` / `current-state`. Holds `{:recording? :variant-id :events :cofx :entries :started-ms}`. | `user-app` (facade re-exports); `mcp-tool`. |
| Mid-recording assertion picker | `re-frame.story.recorder` | `assertion-vocabulary` (the seven canonical `:rf.assert/*` ids + payload field specs); `make-assertion` (pure: build the event vector); `append-assertion` (pure: state → state); `insert-assertion!` (impure: write through the atom). | `chrome-shell` (the picker modal); `mcp-tool` (write-time assertion authoring without the modal). |
| DOM-capture entries | `re-frame.story.recorder.dom-capture` (CLJS-only) | `install!` / `remove!` (capture-phase listener pair); `record-dom-click!` / `record-dom-type!` / `record-dom-submit!` (write the `:dom/click` / `:dom/type` / `:dom/submit` shapes through the recorder's `record-dom-event!`); the pure predicates `dom-event?` / `dom-event-kinds` live in `re-frame.story.recorder`. | `chrome-shell` (paired with the trace-listener install at mount). |
| Review-dialog | `re-frame.story.recorder` + `re-frame.story.review-dialog` | `open-dialog` / `close-dialog` / `initial-dialog-state`. State-only — the rendering lives in `re-frame.story.ui.recorder-export-dialog`. | `chrome-shell` (the modal that opens on stop). |
| `:script` snippet codegen | `re-frame.story.recorder` | `gen-play-snippet` (pure: events + opts → EDN string). Re-exported on the facade as `story/gen-play-snippet`. Emits `(reg-variant ... :script {:script [[:dispatch-sync <ev>] ...]})` — the PUBLIC `:script` slot per rf2-7mj4z; each event wrapped as `[:dispatch-sync <ev>]` per rf2-0wrud. | `user-app` (the copy-and-paste form); `mcp-tool` (Pair drives it via `eval-cljs` in the attached CLJS runtime). |
| Rich DOM-aware `:script` export | `re-frame.story.recorder.play-export` + `re-frame.story.recorder.play-export-events` + `re-frame.story.recorder.selector` | The DOM-capture-aware translator that maps `:entries` (with DOM-capture timestamps) into `:click` / `:type` / `:wait` steps + auto-assert tail; `render-variant-form` emits the public `:script` slot (rf2-7mj4z). The translator entry `recording->script-body` IS re-exported on the facade as `story/recording->script-body` (the runtime counterpart to `gen-play-snippet`); the render-to-EDN fns (`render-script-body` / `render-variant-form`) stay sub-namespace-only. | `chrome-shell`; `mcp-tool` (Pair drives it via `eval-cljs` in the attached CLJS runtime — the headless story-mcp jar has no recorder tool). |

Three architectural observations follow from the map:

1. **The recorder atom is the single point of coupling.** The trace-
   listener, the DOM-capture layer, the assertion picker, and the
   review-dialog all write to the same `{:recording? :variant-id
   :events :cofx :entries :started-ms}` map. Sub-systems that don't need atom access
   (`gen-play-snippet`, `recordable-event?`, `make-assertion`,
   `assertion-vocabulary`) are pure data → data; CLJ-testable on the
   JVM without a process-wide install.
2. **The facade exposes the headline entries only** (seven surfaces:
   `start-recording!` / `stop-recording!` / `clear-recording!` /
   `recording?` / `recorder-state` / `gen-play-snippet` /
   `recording->script-body`). The first six are the recorder-lifecycle
   + simple text-codegen surfaces; the seventh, `recording->script-body`,
   is re-exported from `re-frame.story.recorder.play-export` as the
   runtime data→data counterpart `gen-play-snippet` (the play body a
   programmatic re-registration writes under `:script`). MCP tool builders and
   hot-reload tooling that need the assertion-picker, the DOM-capture
   entries, or the render-to-EDN fns `:require`
   `re-frame.story.recorder` (or its `play-export` sub-ns) directly —
   the sub-ns IS the contract for the rest of the power-user surface.
3. **The rich DOM-aware translator is the power-user surface.**
   `gen-play-snippet` emits the simple-projection public `:script` body
   (each captured event wrapped as `[:dispatch-sync <ev>]`). The
   recorder's `play-export` sub-namespace emits the richer DSL that
   maps DOM-capture entries into `:click` / `:type` / `:wait` steps;
   both round-trip through the recorder's `:events` / `:entries`
   capture (rf2-d5u89).

The sub-system map is **not a refactor target** — each sub-system is
small, and the atom-as-coupling-point
is intentional (the recorder is a single piece of UX, not five
independent features). The map exists so consumers know which
sub-system to `:require` for a specific contract.

### Save current canvas state as variant (rf2-one3t)

Storybook 9's second story-from-UI surface (per the SB9 parity audit
at `ai/findings/story-storybook9-parity-20260513.md` §2.2): tweak
controls on an existing story, click 'Save', and the change writes
back to source as a new exported story. SB9's implementation
auto-writes the source file via a Vite plugin (format-on-save
respects project Prettier config). Story takes the
review-then-commit path instead — same as Test Codegen — because
auto-writing entangles the playground with the project's editor
config, source-control conflicts, and CI hooks.

The save flow is a **controls-panel button**: with a variant focused,
clicking 'save as new variant…' captures the live effective args (per
[`002-Runtime.md`](002-Runtime.md) §Args resolution) and surfaces an
EDN `(reg-variant ...)` form in a modal. The user reviews, edits the
new variant id inline, and copies the form to the clipboard for
pasting into their stories namespace:

```clojure
(rf.story/reg-variant :story.counter/saved-739221
  {:extends :story.counter/happy-path
   :args    {:label "Counter"
             :n     7
             :theme :dark}})
```

The captured args are the **resolved** effective args — the five-layer
precedence chain (global < story < modes < variant < cell-overrides
per [`002-Runtime.md`](002-Runtime.md) §Args resolution) collapses to a
single snapshot map. Tweaking a control then saving produces a variant
that re-renders with the exact state the user was looking at, with no
extra plumbing.

#### Public API

```clojure
;; In re-frame.story.save-variant
(snapshot-args                 variant-id)           ; pure args snapshot
(snapshot-args                 variant-id opts)      ; with :active-modes, :cell-overrides
(gen-variant-snippet           opts)                 ; render (reg-variant ...)
(save-current-as-variant!)                           ; impure trigger — uses focused variant
(save-current-as-variant!      {:variant-id ...})    ; explicit target

;; Event surface — dispatchable from agent / chrome contexts
(rf/dispatch [:rf.story/save-current-as-variant])
(rf/dispatch [:rf.story/save-current-as-variant {:variant-id ...}])
```

`opts` for `gen-variant-snippet`:

- `:variant-id` (required) — keyword id for the new variant.
- `:extends`    (optional) — source variant id (pins `:component`,
                              `:decorators`, non-overridden args).
- `:args`       (required) — the captured args map.
- `:doc`        (optional) — docstring.
- `:alias`      (optional) — short alias for the form (default `rf.story`, the canonical `re-frame.story` alias, so the form pastes and runs verbatim).

Pure data → string; the emitted form is `read-string`-able and
round-trips through re-frame's registrar machinery. Args keys render
in sorted order for determinism.

#### Why this is structurally simpler than Storybook's save-as

Storybook 9 inspects the project's source tree at build time, parses
the existing CSF file's AST, splices a new exported story object into
the module, and re-writes the source via a Vite plugin that honours
the project's Prettier config. The plugin has to handle TypeScript
type imports, named exports, default exports, `meta` objects,
control-spec types, and decorator chains — all in JavaScript text
form. Story's save-as is one snapshot of the args atom + one EDN
pretty-printer. No AST. No source-file write. No format-on-save
config. The user pastes the snippet; the project's editor handles
the formatting via the editor's own re-frame / Clojure tooling.

The `:rf.story/save-current-as-variant` event id sits under the
`:rf.story/*` reserved namespace (per
[spec/Conventions.md](../../../spec/Conventions.md) §Reserved namespaces)
and is filtered by the Test Codegen recorder's `recordable-event?`
predicate — a save dispatched during an active recording never appears
in the recorded `:script` body.

#### MCP wiring

The agent-facing path mirrors the Test Codegen flow — and, because
the save-as dialog is live Story-shell state, it is pair-owned: the
pair dispatches `:rf.story/save-current-as-variant` in the attached
browser runtime, reads the snippet from the resulting dialog state,
and returns the EDN form (per
[`006-MCP-Surface.md`](006-MCP-Surface.md) §Two surfaces, one live
door). Filed as a separate P3 follow-up; this spec locks the
runtime contract so the agent side can build against a stable
surface.

## Design-system v1 ship list (chrome-identity differentiators)

Beyond the panel features above, Story's v1 ships a **chrome-identity
design system** as a SOTA differentiator. Per the rf2-38pb9 audit
verdict, these are surfaces where Story is AHEAD of Storybook — not
parity adds, but competitive proof that Story's chrome is crafted
rather than commodity. The positive locks live in
[`016-Design-Tokens.md`](016-Design-Tokens.md); the rejections of the
Storybook commodity alternatives live in
[`DESIGN-RATIONALE.md`](DESIGN-RATIONALE.md) §Rejected
(rf2-aezbb / rf2-nz4av).

| Item | Source | Stage |
|---|---|---|
| Typography tokens — IBM Plex Sans + Plex Mono cascade; zero-raw-font-family contract | F4 / rf2-x6zw5 | Stage 4 |
| Colour tokens — warm-slate substrate + amber accent palette (vs Storybook cold-grey + pink) | F5 / rf2-kvzkg | Stage 4 |
| Motion contract — six duration tokens + five easing curves, a staggered shell-mount entrance (0 / 60 / 120 / 180ms), a 180ms overlay fade, an 80ms chip-press rebound, and a `prefers-reduced-motion` clamp to `0.01ms`; motion-as-language. The tab fade, the diff-flash and the `--motion-scale` variable this row used to name were never built (rf2-zxsd7) | F6 / rf2-1smrl | Stage 4 |
| Gradient mesh + grain backdrop — anti-flat-chrome composition | F7 / rf2-4kqvw | Stage 4 |
| Sidebar glyph rhythm — 5 SVG glyphs (story=◆, variant=●, workspace=▦, chevron, external-link); amber-diamond per-row; amber-active row border | F8 / rf2-ck4x5 | Stage 4 polish |
| 6-cluster toolbar — MODES \| DATA \| VIEW \| DEBUG \| SHARE \| REC (SHARE added by rf2-ba86n.16) with token hairlines + small-caps cluster labels + accent-amber-deep active-chip border | F9 / rf2-sbluk | Stage 4 polish |
| Xray-in-Story per-panel embed — Xray's RHS panels (`:app-db`, `:epoch`, `:trace`, `:machines`, `:views`, `:routing` — post rf2-5gl5r `:epoch` supersedes the retired `:event-detail`; rf2-gbz39 dropped `:issues` with the Xray Issues tab) mounted under Story's chrome | F1+F2+F3 | Stage 6 |
| Phase 3 chrome surfaces — density knob, command palette, settings modal, polish sweep | rf2-38pb9 cluster | Stage 6 polish |

These ship inside Story's v1 envelope; they are not deferrals. The
density knob, palette nav, motion tiers, Fraunces-display
experiments, and other Phase 3 chrome surfaces sit under the last row
and land in the Stage 6 polish window alongside the v1 panel set.

Read together with the v1 ship list table below: the panel-feature
rows are the *capability* surface; the design-system rows are the
*identity* surface. Both are v1.

## v1.1 deferrals

### Live performance ribbon

FPS / INP / long tasks / CLS / memory pressure / Reagent-render
profiling at 50ms refresh; non-trivial implementation (requires
`PerformanceObserver`, frame-loop sampler, Reagent profile hooks).
Deferred to first follow-up release. Per Phase 2 §5.2 #1.

### Design-token panel

Style-Dictionary-shaped tokens emitted by upstream (re-com or host
design system) surfaced as a `reg-story-panel`. Iff upstream emits
tokens. Per Phase 2 §5.2 #5. No panel shell ships; the whole feature
stays deferred pending token emission upstream.

### Per-variant autodocs panel polish

Autodocs panel from `:doc` + schemas + variant table — the basic
panel ships at v1; the polish (cross-link navigation, hover preview,
prose-injection points) is v1.1.

### "Open in editor" per variant (rf2-evgf5 — shipped)

Reads the `:source` slot stamped at registration time and opens the
variant's defining file:line in the configured editor.

The affordance renders as an `open` chip next to:

- The variant title in the canvas header (reads the variant body's
  `:source` slot — stamped at `reg-variant` time per spec/001).
- Each failing `:test` mode row's failure detail (reads the
  assertion record's `:source` slot per spec/004).

Click PREFERS the dev-server endpoint (Option B, rf2-wn3bh — see
[§Open-in-editor launch modes](#open-in-editor-launch-modes-rf2-wn3bh--option-b-dev-server-endpoint)
below) and FALLS BACK to a URI-scheme handler the OS dispatches to the
configured editor (the `editor://` table below):

| Editor (config key) | URI scheme |
|---|---|
| `:vscode` (default) | `vscode://file/<path>:<line>:<column>` |
| `:cursor`           | `cursor://file/<path>:<line>:<column>` |
| `:idea`             | `idea://open?file=<path>&line=<line>&column=<column>` |
| `{:custom <tpl>}`   | user template with `{path}` / `{file}` / `{line}` / `{column}` placeholders |

The host sets the preference via `(story/configure! {:rf.story/editor :cursor})`
at boot. Unknown keywords fall back to `:vscode` so a typo still
yields a clickable URI rather than a no-op. Source-coords without
`:file` hide the chip entirely.

#### Project-root prefix (rf2-zfy1e)

Source-coords stamped at registration time are classpath-relative
(per [spec/001-Registration.md §Source-coordinate capture](../../../spec/001-Registration.md#source-coordinate-capture-cljs-reference) the macro captures the form-meta
`:file` slot, e.g. `"src/app/views.cljs"`). Editor URI handlers
resolve `<path>` against the filesystem, so a relative path fails
with *"Path does not exist"*. The host plumbs the on-disk root via:

```clojure
(story/configure! {:rf.story/project-root "C:/Users/me/code/my-app"})
```

The 'Open' chip prepends the root to the source-coord file before
the URI ships:

```
vscode://file/C:/Users/me/code/my-app/src/app/views.cljs:42:7
```

Default is `nil` — when unset, the file string ships verbatim (v1
behaviour). Source-coords whose `:file` is already absolute (leading
`/`, drive-letter prefix, or `file:` URI) pass through unchanged
regardless of the root setting so a caller that already has an
absolute coord isn't double-prefixed. The prefix lives in the shared
`re-frame.source-coords.editor-uri/editor-uri` 3-arg form; Xray
consumes the same helper and will plumb its own knob in a follow-up.

#### Open-in-editor launch modes (rf2-wn3bh — Option B dev-server endpoint)

Jump-to-source has TWO launch paths; the chip + the
`:rf.story/open-in-editor` event PREFER the first and FALL BACK to
the second. The mechanism is additive — B never removes the URI path.
This is the JS-ecosystem standard (Vite `/__open-in-editor`,
react-dev-utils, Next). See
[`spec/Tool-Pair.md` §Open-in-editor launch modes](../../../spec/Tool-Pair.md)
for the cross-tool contract.

1. **Dev-server endpoint (preferred).** A shadow-cljs `:dev-http` Ring
   `:handler` — `re-frame.testbed.open-in-editor-server/handler`, a
   **JVM-only `.clj`** server fn — answers
   `POST /__rf-open-in-editor?file=<…>&line=<n>&column=<c>`. The
   endpoint is **POST-only +
   loopback-guarded by design** — it launches the developer's editor on
   a local path, so a `GET`/`HEAD` drive-by must never trigger a launch
   (rejected 405; the historic Vite / react-dev-utils CVE class). It
   resolves the (classpath-relative) `:file` against the live
   source-paths **at runtime on the dev machine** (the runtime twin of
   `re-frame.source-coords/absolutise-file`, rf2-wvsxg — so it works for
   the JAR / in-jar / odd-classpath cases the compile-time bake cannot
   reach), then launches the editor via the `launch-editor` npm package
   (dev-only dependency; handles every OS + editor). Story's client
   open-seam (`open-in-editor/open-coord!` → the shared
   `re-frame.source-coords.open-endpoint/open-coord!`) `fetch`es the
   endpoint on its own origin; `:rf.story/editor` rides as the `editor=`
   hint. Zero-config jump-to-source — **no `:rf.story/project-root`
   needed, no absolute path baked into the bundle.**

2. **`editor://` URI (fallback).** When no dev server answers (static
   Story export, non-shadow host, production inspection, network error,
   or a non-2xx reply), `open-coord!` falls back to building the
   `editor://` URI (the table above, with `:rf.story/project-root`
   prepended + rf2-wvsxg absolutisation) and navigating it via
   `Location.assign`. This is the only path `:rf.story/project-root`
   participates in — the fallback knob for hosts without a dev server.

**Bundle isolation.** The server handler is a `.clj` — never part of any
CLJS/browser build (including the static Story export), so it cannot
leak. The client seam (`re-frame.source-coords.open-endpoint`) is
referenced only from the dev-only open-seam and DCEs out of release
bundles. `launch-editor` is a `devDependency`.

##### Bridge to Xray-as-RHS (rf2-r1uod)

When Xray is mounted as Story's RHS inspector (per rf2-sgdd3),
the Xray-side source-coord chips (Event lens Handler / Dispatch /
Interceptors, Trace tab rows, Issues ribbon) read their on-disk root
from Xray's `day8.re-frame2-xray.config/project-root` slot — NOT
from Story's `re-frame.story.config/project-root`. To keep
project-root a single-source-of-truth setting the host only writes
once, `(story/configure! {:rf.story/project-root <path>})` is bridged
into Xray's slot by `re-frame.story.xray-preset/propagate-project-root!`:

- The propagator fires from two seams: (1) `story/configure!` after
  `set-project-root!` lands (the common case — Xray's preload runs
  before the testbed `run` fn), and (2) `xray-preset/wire-cross-host!`
  on every variant selection as defense-in-depth (lazy-load /
  hot-reload edge).
- One-way (`story → xray`). Hosts that want Xray pointed at a
  different on-disk root than Story call `xray-config/configure!`
  directly AFTER `story/configure!` to override the bridge.
- The bridge reaches Xray's `configure!` through a declared
  `:require` (rf2-r8trk), not a runtime namespace probe. The only
  no-op case is Story having no `:rf.story/project-root` configured.

Symmetric to shop's [rf2-6jyf6](https://github.com/day8/re-frame2/pull/1493) —
Xray's standalone testbeds (shop) seed Xray's project-root directly
via `xray-config/configure!`; Story testbeds with Xray-as-RHS seed
via `story/configure! :rf.story/project-root` and let the bridge
propagate.

The shared URI builder lives at `re-frame.source-coords.editor-uri`
under the core artefact and is CLJC-portable; Xray's mirror
affordance (`day8.re-frame2-xray.open-in-editor`) consumes the same
helper. The matrix above is the canonical list for *both* tools — see
[`tools/xray/spec/007-UX-IA.md` §Editor protocol matrix](../../xray/spec/007-UX-IA.md#editor-protocol-matrix)
for Xray's keyboard-side surface and the Settings-modal hook. Story
and Xray keep independent config keys (`:rf.story/editor` vs
`:rf.xray/editor`) so a host can route each tool to a different
editor.

## Production elision under `:advanced`

### Sentinel pattern

Story uses the same PRESENT-in-control / ABSENT-in-release pattern
[Spec 009](../../../spec/009-Instrumentation.md) locks for
instrumentation:

```clojure
;; CLJS compile-time flag, in re-frame.story.config:
(goog-define ^boolean enabled? true)

;; Every registration macro emits a guard into the CLJS program.
;; Schematic expansion; the real macro also stamps source coordinates:
(when re-frame.story.config/enabled?
  (re-frame.story/reg-story* :story.example/counter {:doc "Counter"}))
```

Under `:advanced` compile with
`:closure-defines {re-frame.story.config/enabled? false}`. Closure folds
the emitted guards to `nil` and removes unreachable registration-side
code. The macro does not read a dynamically bound JVM flag to decide its
expansion. `:advanced` alone leaves `enabled?` at its default `true`;
static Story playgrounds intentionally keep Story enabled and use the
separate `re-frame.story.config/static-mode?` define.

### What gets DCE'd

- All variant / story / workspace / mode / panel registrations.
- Unreachable Story registrar/runtime dependencies.
- The render shell (`re-frame.story.ui.*`) when not otherwise reachable.
- The trace / a11y / perf panels.
- The play-runner.
- The control-derivation logic.

### What survives

- A reachable `re-frame.story/run-variant` call returns a resolved
  promise of the no-registration result (`:app-db {}`, `:assertions []`,
  zero elapsed time), not a synchronous `{}` and not an unknown-variant
  exception. Unreferenced public functions need not survive DCE.

### Cross-library `:extends` under elision

All registrations are dev-only. In a production build:

- Lib A's guarded `reg-variant` calls are eliminated.
- Lib B's guarded `reg-variant` calls with `:extends :A/x` are too.
- No `:extends` resolution happens at all.

There is therefore no production-build failure mode for cross-library
`:extends`. Enabled builds store the raw registration and resolve
`:extends` at plan construction, with the missing-parent diagnostic
specified in [`017-Testing-Story.md`](017-Testing-Story.md) §Composition.

### Verification

`implementation/scripts/check-bundle-isolation.cjs` (per
[`tools/README.md`](../../README.md)) checks live registrar/decorator
sentinels are absent from the counter consumer that does not import
Story, and present in its Story-enabled positive control. This verifies
the no-import dependency boundary. The separate disabled-registration
consumer is exercised by `tools/story/bench/bundle-size.cjs`; its
historical measurement below is not a current CI result.

### Bundle-size comparison (rf2-xgay8)

Measured 2026-05-13 on `tools/story/testbeds/counter_with_stories` via
`shadow-cljs release`:

- Counter with Story enabled (dev-bundle-equivalent, `:advanced`):
  **+118 KiB gzipped** (+453 KiB raw) over the no-Story baseline.
  That's the JS Story would add to a consumer's bundle if shipped
  to production.
- Counter with Story disabled (`:advanced` +
  `re-frame.story.config/enabled?=false`): **0 additional bytes**.
  Elision verified by `scripts/check-bundle-isolation.cjs`.

For reference, Storybook 9's `storybook` core package alone is
~35 MB unpacked (npm registry, 9.1.20), and a working SB9 project
is typically tens to hundreds of MB on disk. Storybook is a
separate dev-server with manager + preview iframes; Story is a
runtime registry that mounts inside the consumer's existing app —
the categories differ, but the consumer-cost answer is "~118 KiB
gz in dev / 0 bytes in prod" either way.

One-shot measurement; not a CI gate. Rerun via
`node tools/story/bench/bundle-size.cjs` from `implementation/`.
Companion findings doc (with the full SB9 comparison table and
sources): `ai/findings/story-bundle-vs-sb9-20260513.md` (local-only
per `docs/the-mayor-method/`; the headline numbers and methodology
are committed here + in the bench script).

## v1 ship list (high-confidence + must-ships)

Per Phase 2 §5.1's converged ship list (12 items) + additional
phase-2 SOTA adds that are cheap.

| Item | Stage |
|---|---|
| 1. MCP server + component manifest | Stage 7 |
| 2. a11y (axe-core) panel inline + CI hook | Stage 6 |
| 3. Play-style scripted interactions + `:rf.assert/*` vocabulary | Stages 2, 5 |
| 4. External visual-regression integration via `snapshot-identity` hook | Stage 3 |
| 5. Three-level args + auto-derived controls from Spec 010 schemas | Stages 2, 4 |
| 6. `force-fx-stub` — universal-fx mocking primitive (replaces the Storybook addon parade; see §`force-fx-stub`) | Stage 2 |
| 7. Six-domino trace panel per variant via `register-listener!` | Stage 6 |
| 8. Xray epoch panel embedded via the RHS per-panel mount catalogue (`ui/xray_embed`) | Stage 6 |
| 9. Story portability — `run-variant` returns the unified run-result (`:status` verdict plus `:frame` / `:app-db` / `:assertions` / `:elapsed-ms` / `:snapshot` …) | Stage 3 |
| 10. EDN-first variant artefact (no `:render` fn-slot, round-trippable) | Stage 2 |
| 11. Inclusion tags (seven canonical + `!`-prefix removal) | Stage 2 |
| 12. Workspace grid + transit-shareable layouts | Stage 4 |
| Layout-debug overlay trio (measure / outline / pseudo) | Stage 6 |
| `reg-mode` saved-tuple primitive | Stage 2 |
| `:variants-grid` workspace layout | Stage 4 |
| Per-variant share URL (address-bar surface; retired QR popover) | Stage 6 |
| Multi-substrate side-by-side pane (substrate-failures inline) | Stage 6 |
| Xray epoch panel embed (per-panel mount) | Stage 6 |
| Test Codegen — record canvas dispatches as `:script` (rf2-5fc15) | Stage 6 |

## v1.1 ship list (first follow-up)

| Item | Stage |
|---|---|
| Live performance ribbon (FPS, INP, long tasks, CLS, memory, Reagent profiling) | Stage 6 (deferred) |
| Design-token panel (conditional on upstream token emission) | Stage 6 (deferred) |
| MCP write surface (`register-variant`, `unregister-variant`) | shipped — gated in `story-mcp` behind `:rf.story-mcp/allow-writes?` (closed by default); contract in [`../../story-mcp/spec/003-Write-Surface-Gating.md`](../../story-mcp/spec/003-Write-Surface-Gating.md) |
| "Open in editor" per variant (rf2-evgf5) | shipped — Stage 6 + Stage 8 |
| Per-variant autodocs panel polish | Stage 6 (deferred) |
| Sidebar tag-as-badge affordance on variant rows (rf2-nwiwr) | shipped — Stage 4 polish; normative contract in [`014-Chrome-Features.md`](014-Chrome-Features.md) §Sidebar tag-as-badge affordance |

## v2 ship list (post-1.0 deferred)

| Item | Source |
|---|---|
| Subscription topology visualiser (using `sub-topology`) | Phase 1 Tier 3 |
| Per-substrate variant filtering (deep substrate-divergence audit) | Phase 1 Tier 3 |
| Custom story panels by third parties (the v1 panels exhaust the v1 set) | Phase 1 Tier 3 |
| Remote Storybook federation (multi-host composition) | Phase 1 §2.2 |
| App-db snapshot diff (data-space visual regression) | Phase 2 §5.2 #7 |
| oEmbed URLs for Notion / static-doc inlining | Phase 2 §5.2 #6 (deferred) |
| BackstopJS-style pixel scrubber UI | Phase 2 §2.1 (out of scope; data-space scrubber via Xray suffices) |

> **Pulled forward to v1:** static export ("render the whole story tool
> to a static site", formerly a Phase 1 Tier 3 v2 item) shipped in v1 as
> `story:build` (rf2-8wgpm) — see [`013-Static-Build.md`](013-Static-Build.md).
> The v2 remnant is the per-story-HTML `--docs`-equivalent (SEO /
> link-preview), which 013 keeps out of the v1 SPA scope.

## What we deliberately don't ship

Phase 1 §5.7 + Phase 2 §5 + the non-goals section of
[`000-Vision.md`](000-Vision.md). Each is named with rationale so
contributors have a clear "no" list. See
[`DESIGN-RATIONALE.md`](DESIGN-RATIONALE.md) §Rejected for the full
list with reasoning; the short version:

- **CSF Factories (JS) — we use EDN-first.** EDN-first variant
  bodies are strictly stronger than CSF Factories.
- **First-party visual-regression service — we use the
  `snapshot-identity` hook.** Pixel capture is downstream.
- **Component-co-located fixtures (React Cosmos `.fixture.tsx`
  files).** re-frame2's registered artefacts are the canonical
  structure mechanism.
- **Statechart visualisation engine.** Owned by
  `day8/re-frame2-machines-viz`; reaches Story via Xray's `[Machines]`
  chip (`panels/mount-machine-inspector!`) in the per-panel embed —
  not a dedicated Story panel.
- **Pixel-scrubber UI (BackstopJS slider).** Data-space scrubber via
  Xray's epoch panel covers the same UX better.
- **BackstopJS-style baseline storage.** Services handle baselines.
- **First-party SSR rendering pipeline.** Owned by Spec 011 +
  `day8/re-frame2-ssr`.
- **MCP server in-process.** Separate-jar split — see
  [`006-MCP-Surface.md`](006-MCP-Surface.md).
- **Built-in pixel diff under `:test` tag.** Stories-as-tests are
  state-space tests, not pixel-space tests.
- **Full Xray reimplementation.** Story embeds Xray's epoch panel
  (the structural successor to re-frame-10x); does not own a parallel
  implementation.
