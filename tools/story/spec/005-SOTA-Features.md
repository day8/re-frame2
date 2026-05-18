# Story — SOTA Features

> The `force-fx-stub` headline (one primitive replaces the
> Storybook addon parade); the layout-debug overlay trio; a11y
> axe-core panel; per-variant QR share; multi-substrate side-by-side
> rendering; the Causa epoch panel embed contract (stub); the v1.1
> deferrals (perf ribbon, design tokens); production elision under
> `:advanced` (`:rf.story/enabled?` sentinel pattern). The contract
> Stage 6 implements + the production hygiene rules that apply across
> stages.

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
with `reg-fx` can be stubbed in three lines of variant body.

Set up a variant that fails the checkout HTTP call:

```clojure
(story/reg-variant :story.checkout/network-failure
  {:extends :story.checkout/happy-path
   :decorators [[:force-fx-stub :http/managed
                 (constantly :rf.http/failed)]]})
```

Or fails analytics. Or websocket. Or geolocation. Or storage. Or
navigation. Or anything else you `reg-fx`'d in your app:

```clojure
(story/reg-variant :story.checkout/analytics-down
  {:extends :story.checkout/happy-path
   :decorators [[:force-fx-stub :analytics/track
                 (constantly nil)]]})

(story/reg-variant :story.dashboard/ws-disconnected
  {:extends :story.dashboard/happy-path
   :decorators [[:force-fx-stub :ws/send
                 (constantly :ws/closed)]]})

(story/reg-variant :story.profile/geo-denied
  {:extends :story.profile/happy-path
   :decorators [[:force-fx-stub :geo/locate
                 (constantly {:status :denied})]]})
```

Same primitive. Same three-line decorator. Same variant body shape.
No new dependency per fx kind, no new mental model per addon.

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
  {:decorators [[:force-fx-stub :http {:status :pending}]]
   :events     [[:auth/initialise]
                [:auth/login-pressed]]})
```

The decorator accepts an fx-id and a response value (or a function
of the fx's argument, for variants that need to inspect the dispatch
payload before responding). It applies at frame creation; the stub
is per-frame and per-variant, so two variants of the same story can
stub the same fx with different responses without cross-talk.

For the full decorator surface — the `:fx-override` kind, the
`:response` / `:fn` slots, the `:rf.assert/effect-emitted`
interaction — see [`004-Assertions.md`](004-Assertions.md)
§`force-fx-stub` interaction and [`002-Runtime.md`](002-Runtime.md)
§Decorator composition.

### Test-mode integration

A `:test`-tagged variant that stubs an fx behaves exactly like one
that doesn't. `run-variant` returns the same
`{:frame :app-db :assertions :rendered-hiccup :elapsed-ms}` shape;
the stubbed response participates in the variant's effective state
the same way a real fx response would. Stories-as-tests pick up
"the analytics pipeline is dead" coverage for free.

## v1 panels (must-ship)

### a11y (axe-core) panel

axe-core integration runs against the rendered DOM:
- Inline in the canvas — violations list appears as a sidebar panel.
- CI hook — variants tagged `:a11y` (or just `:test`) run axe under
  `run-variant`; violations append to `:assertions` as
  `:rf.assert/a11y` failures.

Phase 1 §3.1 #3 names this "the cheapest win in the space."

### Six-domino trace (via embedded Causa)

Per [`003-Render-Shell.md`](003-Render-Shell.md) §Right-hand pane,
Causa is mounted as the RHS primary inspector by default (rf2-sgdd3).
Causa's Trace tab renders the six dominoes (event → handler → fx →
effect → subscription → re-render) live, scoped to the selected
variant's frame. Story's per-variant `trace-buffer` listener stays
in place to feed the schema-validation panel; Causa runs its own
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

A variant declares `:substrates #{:reagent :uix :helix}` (subset,
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

### QR code in share menu

Per Phase 2 §5.2 #6. Tiny implementation, high signal. Adds `qr-code`
dep. Surfaces the share affordance: tap the QR on a phone, get the
current variant URL with all cell-local overrides encoded.

### Third-party network egress (rf2-20w5i)

Story is a **developer-session tool**, not a production runtime — but
the v1 SOTA features it ships (QR share, a11y panel) must not leak
the dev's variant state or load remote-hosted code without the dev's
consent. Per rf2-20w5i (security audit, 2026-05-14):

| Pre-fix endpoint | Post-fix |
|---|---|
| Per-variant share popover sourced its QR image from a third-party service, carrying the full share URL (variant state + author-typed cell-overrides) as a query param. | **Eliminated** — local SVG generation via `qrcode-generator` (npm, MIT, ~52 KB unpacked, zero deps). The encoder lives in `re-frame.story.qr/qr-svg-string`; the popover splices the SVG inline via `:dangerouslySetInnerHTML`. No network request fires when the popover opens. |
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
round-trip through it. With the QR rendered locally, that URL never
leaves the dev's machine.

Production builds (`:rf.story/enabled?` false under `:advanced`) elide
the entire Story UI shell; the vendored QR encoder and the a11y panel
are both reachable only from the disabled tree, so Closure DCE drops
them. The bundle-isolation contract
(`scripts/check-bundle-isolation.cjs`) verifies the Story sentinels
are absent from `examples/counter`'s release bundle, which
transitively covers `qrcode-generator` and the a11y panel.
Static-build deploys (rf2-8wgpm, see
[`013-Static-Build.md`](013-Static-Build.md)) carry the QR encoder
into the static playground; the axe-core opt-in prompt rides into the
static site too, so a visitor running an a11y scan there sees the
same consent dialog before any CDN load.

### Causa epoch panel embed (stub + contract)

Story registers Causa's existing epoch / time-travel panel as a story
panel (Causa is the structural successor to re-frame-10x, per
[`tools/causa/spec/DESIGN-RATIONALE.md`](../../causa/spec/DESIGN-RATIONALE.md)
Lock #1):

```clojure
(story/reg-story-panel :rf.story/causa-epoch
  {:doc       "Causa's epoch buffer for the active variant."
   :title     "Epochs (Causa)"
   :placement :bottom
   :render    :day8.re-frame2-causa.panels.time-travel/Panel})
```

The view is consumed from `day8/re-frame2-causa` (per the
`tools/causa/` alpha-phase line in
[`tools/README.md`](../../README.md)). Story's panel is the
**adapter**; Causa stays its own artefact, on its own release cadence.

Story's `:rf.story/causa-epoch` registration ships with v1 but the
panel only activates if `day8/re-frame2-causa` is on the classpath
(per the late-bind hook in spec/002). If Causa is absent, the sidebar
entry hides. The Causa artefact owns the actual view; Story owns the
*integration*. See [`DESIGN-RATIONALE.md`](DESIGN-RATIONALE.md)
§causa-embed.

### Test Codegen — record-as-`:play` (rf2-5fc15)

Storybook 9's killer feature is the record-and-save workflow: the
user interacts with the canvas, the tool watches the event bus, and
on 'stop' the user gets a code snippet they paste into the variant.
Story's `:play` body is **already** a vector of EDN event vectors
(per [`004-Assertions.md`](004-Assertions.md) §Play sequence
execution), so the captured trace IS the codegen output — no
Testing-Library/page-object translation layer needed.

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
(story/reg-variant :story.counter/recorded-739221
  {:extends :story.counter/happy-path
   :play [[:counter/inc]
          [:counter/inc]
          [:counter/by 7]]})
```

#### Capture boundary

The recorder consumes Story's existing trace-bus listener primitive
(per [`003-Render-Shell.md`](003-Render-Shell.md) §Trace bus + the
`re-frame.trace/register-trace-cb!` API per Spec 009 §Listener
contract). One process-wide callback installed at shell mount; per
emit it short-circuits when no recording is in flight, so leaving it
installed is free.

Three filter layers:

1. **Op-type** — only `:event/dispatched` emissions qualify (skip
   `:fx` / `:sub` / `:view` / `:cofx` traffic).
2. **Frame scope** — the emission's `:frame` tag must match the
   recording's target variant. Cross-frame dispatches (typing in
   another canvas while a recording is active) are dropped.
3. **Event vocabulary** — `:rf.assert/*` events and Story-internal
   helpers (`:rf.story/*`, `:re-frame.story.*`) are filtered. Authored
   assertions are deliberate; recorded `:play` bodies capture user
   intent, and assertions get added by hand.

#### Mid-recording assertion insertion (rf2-39u9e)

The recorder filters `:rf.assert/*` events off the trace bus
(assertions are authored, not observed). To make assertion authoring
as fast as the recording itself, the recording overlay carries an
`+ assert` button next to `stop`. Click opens a small modal picker
that enumerates the canonical seven `:rf.assert/*` ids from
`re-frame.story.recorder/assertion-vocabulary` (one-click pick) and
prompts for the assertion's EDN-typed payload fields. A live preview
renders the event vector that will land in the captured `:play`
body; clicking 'insert' appends it inline alongside the dispatched
events.

The picker doesn't pause recording — the user can keep clicking the
canvas after inserting. The captured `:play` body comes out with
assertions interleaved exactly where the user wanted them:

```clojure
(story/reg-variant :story.counter/recorded
  {:extends :story.counter/happy-path
   :play [[:counter/inc]
          [:counter/inc]
          [:rf.assert/sub-equals [:counter] 2]      ; inserted via picker
          [:counter/by 7]
          [:rf.assert/path-equals [:n] 9]]})        ; inserted via picker
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
(gen-play-snippet events opts)        ; render `(reg-variant ... :play [...])`

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
- `:alias`      (optional) — short alias for the form (default `story`).

Pure data → string; the emitted form is `read-string`-able and
round-trips through re-frame's registrar machinery.

#### Why this is structurally simpler than Storybook's recorder

Storybook 9 records DOM events (`click`, `fill`, `select`) and emits
Testing Library calls (`fireEvent.click(...)`, `await userEvent.fill(...)`),
which then have to translate back through React's reconciliation. Story
records re-frame events directly: every user interaction in a Story
canvas eventually lands as a `dispatch` on the variant's router, and
the trace bus already projects those dispatches with `:event/dispatched`
emissions per Spec 009. Capturing the right value is one filter on the
existing emit; the output shape is the exact vector the runtime will
re-dispatch under `:play`. The recorder is one screenful of code.

#### MCP wiring (adjacent bead)

The story-mcp `record-as-variant` tool consumes the same recorder
state through the cross-process Tool-Pair bridge (per
[`006-MCP-Surface.md`](006-MCP-Surface.md)): the agent calls
`start-recording!`, drives interactions via the existing MCP write
surface (or asks the user to interact with the canvas), calls
`stop-recording!`, and the snippet emitted by `gen-play-snippet` is
returned as the tool's structured output for the agent to write
back to the user's stories namespace. That bead is filed separately
as a P2 follow-up; this spec locks the recorder's runtime contract
so the MCP side can build against a stable surface.

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
(story/reg-variant :story.counter/saved-739221
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
- `:alias`      (optional) — short alias for the form (default `story`).

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
in the recorded `:play` body.

#### MCP wiring

The agent-facing path mirrors the Test Codegen flow: a story-mcp
`save-current-as-variant` tool dispatches
`:rf.story/save-current-as-variant` against the live Story process
through the Tool-Pair bridge (per
[`006-MCP-Surface.md`](006-MCP-Surface.md)), reads the snippet from
the resulting dialog state, and returns the EDN form as structured
output. Filed as a separate P3 follow-up; this spec locks the
runtime contract so the MCP side can build against a stable surface.

## v1.1 deferrals

### Live performance ribbon

FPS / INP / long tasks / CLS / memory pressure / Reagent-render
profiling at 50ms refresh; non-trivial implementation (requires
`PerformanceObserver`, frame-loop sampler, Reagent profile hooks).
Deferred to first follow-up release. Per Phase 2 §5.2 #1.

### Design-token panel

Style-Dictionary-shaped tokens emitted by upstream (re-com or host
design system) surfaced as a `reg-story-panel`. Iff upstream emits
tokens. Per Phase 2 §5.2 #5. Stage 6 ships the panel shell;
activation is conditional on token emission upstream.

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

Click sets `window.location.href` to a URI-scheme handler the OS
dispatches to the configured editor:

| Editor (config key) | URI scheme |
|---|---|
| `:vscode` (default) | `vscode://file/<path>:<line>:<column>` |
| `:cursor`           | `cursor://file/<path>:<line>:<column>` |
| `:idea`             | `idea://open?file=<path>&line=<line>&column=<column>` |
| `{:custom <tpl>}`   | user template with `{path}` / `{file}` / `{line}` / `{column}` placeholders |

The host sets the preference via `(story/configure! {:editor :cursor})`
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
(story/configure! {:project-root "C:/Users/me/code/my-app"})
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
`re-frame.source-coords.editor-uri/editor-uri` 3-arg form; Causa
consumes the same helper and will plumb its own knob in a follow-up.

##### Bridge to Causa-as-RHS (rf2-r1uod)

When Causa is mounted as Story's RHS inspector (per [rf2-sgdd3](../README.md#stage-9-rf2-sgdd3-causa-as-rhs)),
the Causa-side source-coord chips (Event lens Handler / Dispatch /
Interceptors, Trace tab rows, Issues ribbon) read their on-disk root
from Causa's `day8.re-frame2-causa.config/project-root` slot — NOT
from Story's `re-frame.story.config/project-root`. To keep `:project-
root` a single-source-of-truth setting the host only writes once,
`(story/configure! {:project-root <path>})` is bridged into Causa's
slot by `re-frame.story.causa-preset/propagate-project-root!`:

- The propagator fires from two seams: (1) `story/configure!` after
  `set-project-root!` lands (the common case — Causa's preload runs
  before the testbed `run` fn), and (2) `causa-preset/ensure-causa-
  mounted!` as defense-in-depth (lazy-load / hot-reload edge).
- One-way (`story → causa`). Hosts that want Causa pointed at a
  different on-disk root than Story call `causa-config/configure!`
  directly AFTER `story/configure!` to override the bridge.
- Feature-detect-safe: when Causa is not on the classpath the
  propagator returns nil without touching the wire.

Symmetric to shop's [rf2-6jyf6](https://github.com/day8/re-frame2/pull/1493) —
Causa's standalone testbeds (shop) seed `:project-root` directly via
`causa-config/configure!`; Story testbeds with Causa-as-RHS seed via
`story/configure! :project-root` and let the bridge propagate.

The shared URI builder lives at `re-frame.source-coords.editor-uri`
under the core artefact and is CLJC-portable; Causa's mirror
affordance (`day8.re-frame2-causa.open-in-editor`) consumes the same
helper. The matrix above is the canonical list for *both* tools — see
[`tools/causa/spec/007-UX-IA.md` §Editor protocol matrix](../../causa/spec/007-UX-IA.md#editor-protocol-matrix)
for Causa's keyboard-side surface and the Settings-modal hook. Story
and Causa keep independent config keys (`:rf.story/editor` vs
`:rf.causa/editor`) so a host can route each tool to a different
editor.

## Production elision under `:advanced`

### Sentinel pattern

Story uses the same PRESENT-in-control / ABSENT-in-release pattern
[Spec 009](../../../spec/009-Instrumentation.md) locks for
instrumentation:

```clojure
;; compile-time flag (CLJS goog-define)
(goog-define ^:dynamic *enabled?* true)

;; macros expand conditionally
(defmacro reg-story [id metadata]
  (if *enabled?*
    `(re-frame.story.impl/reg-story* ~id ~metadata)
    `nil))
```

Under `:advanced` compile with
`closure-defines {'re-frame.story.config/*enabled?*' false}`,
every Story registration form expands to `nil` and the entire
`re-frame.story.impl` ns becomes unreachable from the main namespace
graph — Closure DCE removes it wholesale.

### What gets DCE'd

- All variant / story / workspace / mode / panel registrations.
- The `tools.story.registry` side-table.
- The render shell (`tools.story.ui.*`).
- The trace / a11y / perf panels.
- The play-runner.
- The control-derivation logic.

### What survives

- The fn body of `re-frame.story/run-variant` (and friends), but with
  no registrations to act on it returns an empty result map. This is
  a feature: production code that *accidentally* calls `run-variant`
  does not throw, it returns empty.
- Public Var stubs (so `(:require [re-frame.story :as story])`
  doesn't break a prod build that imports a stories ns
  conditionally).

### Cross-library `:extends` under elision

All registrations are dev-only. In a production build:

- Lib A's `reg-variant` calls expand to `nil`.
- Lib B's `reg-variant :extends :A/x` calls expand to `nil` too.
- No `:extends` resolution happens at all.

There is therefore no production-build failure mode for cross-library
`:extends`. Dev builds (with `*enabled?*` true) resolve `:extends` at
registration time; an unresolved parent raises
`:rf.error/extends-unknown` synchronously.

### Verification

`scripts/check-bundle-isolation.cjs` (per
[`tools/README.md`](../../README.md)) gets a Story sentinel: any
`re-frame.story.impl.*` symbol surviving in a `:advanced` build
output is a leak. Stage 8 (examples + guide) wires the example
builds against this sentinel.

### Bundle-size comparison (rf2-xgay8)

Measured 2026-05-13 on `tools/story/testbeds/counter_with_stories` via
`shadow-cljs release`:

- Counter with Story enabled (dev-bundle-equivalent, `:advanced`):
  **+118 KiB gzipped** (+453 KiB raw) over the no-Story baseline.
  That's the JS Story would add to a consumer's bundle if shipped
  to production.
- Counter with Story disabled (`:advanced` +
  `re-frame.story.config/*enabled?*=false`): **0 additional bytes**.
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
per `docs/the-mayor-method.md`; the headline numbers and methodology
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
| 7. Six-domino trace panel per variant via `register-trace-cb!` | Stage 6 |
| 8. Causa epoch panel embedded as `reg-story-panel` | Stage 6 |
| 9. Story portability — `run-variant` returns `{:frame :app-db :assertions :rendered-hiccup :elapsed-ms}` | Stage 3 |
| 10. EDN-first variant artefact (no `:render` fn-slot, round-trippable) | Stage 2 |
| 11. Inclusion tags (seven canonical + `!`-prefix removal) | Stage 2 |
| 12. Workspace grid + transit-shareable layouts | Stage 4 |
| Layout-debug overlay trio (measure / outline / pseudo) | Stage 6 |
| `reg-mode` saved-tuple primitive | Stage 2 |
| `:variants-grid` workspace layout | Stage 4 |
| Per-variant QR code in share menu | Stage 6 |
| Multi-substrate side-by-side pane (substrate-failures inline) | Stage 6 |
| Causa epoch panel embed (stub + contract) | Stage 6 |
| Test Codegen — record canvas dispatches as `:play` (rf2-5fc15) | Stage 6 |

## v1.1 ship list (first follow-up)

| Item | Stage |
|---|---|
| Live performance ribbon (FPS, INP, long tasks, CLS, memory, Reagent profiling) | Stage 6 (deferred) |
| Design-token panel (conditional on upstream token emission) | Stage 6 (deferred) |
| MCP write surface (`register-variant`, `unregister-variant`) | Stage 7 (deferred) |
| "Open in editor" per variant (rf2-evgf5) | shipped — Stage 6 + Stage 8 |
| Per-variant autodocs panel polish | Stage 6 (deferred) |
| Sidebar tag-as-badge affordance on variant rows (rf2-nwiwr) | shipped — Stage 4 polish; normative contract in [`014-Chrome-Features.md`](014-Chrome-Features.md) §Sidebar tag-as-badge affordance |

## v2 ship list (post-1.0 deferred)

| Item | Source |
|---|---|
| Subscription topology visualiser (using `sub-topology`) | Phase 1 Tier 3 |
| Per-substrate variant filtering (deep substrate-divergence audit) | Phase 1 Tier 3 |
| Static export (render the whole story tool to a static site) | Phase 1 Tier 3 |
| Custom story panels by third parties (the v1 panels exhaust the v1 set) | Phase 1 Tier 3 |
| Remote Storybook federation (multi-host composition) | Phase 1 §2.2 |
| App-db snapshot diff (data-space visual regression) | Phase 2 §5.2 #7 |
| oEmbed URLs for Notion / static-doc inlining | Phase 2 §5.2 #6 (deferred) |
| BackstopJS-style pixel scrubber UI | Phase 2 §2.1 (out of scope; data-space scrubber via Causa suffices) |

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
- **Statechart visualisation engine.** Deferred to
  `day8/re-frame2-machines-viz`.
- **Pixel-scrubber UI (BackstopJS slider).** Data-space scrubber via
  Causa's epoch panel covers the same UX better.
- **BackstopJS-style baseline storage.** Services handle baselines.
- **First-party SSR rendering pipeline.** Owned by Spec 011 +
  `day8/re-frame2-ssr`.
- **MCP server in-process.** Separate-jar split — see
  [`006-MCP-Surface.md`](006-MCP-Surface.md).
- **Built-in pixel diff under `:test` tag.** Stories-as-tests are
  state-space tests, not pixel-space tests.
- **Full Causa reimplementation.** Story embeds Causa's epoch panel
  (the structural successor to re-frame-10x); does not own a parallel
  implementation.
