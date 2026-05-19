# 014-Chrome-Features

Normative coverage for four shipped user-facing Story surfaces that
were previously discoverable mainly from code / tests rather than the
spec set:

1. The **schema-validation panel** — the live Spec 010 boundary-
   failure surface per variant.
2. The **sidebar tag-as-badge affordance** — the per-variant tag pills
   on sidebar rows.
3. The **first-visit help overlay** — the modal-style on-boarding
   shown on first mount.
4. The **command palette** — the Cmd/Ctrl-K global navigator over the
   Story registry.

Each is captured as its own section below. Where v1 deliberately
leaves details to implementation, the section calls that out so a
one-shot implementer doesn't infer an unwritten contract.

The chrome-identity tokens these features compose against
(typography / colour / motion / backdrop / glyphs / toolbar
5-cluster) are normalised separately in
[`016-Design-Tokens.md`](016-Design-Tokens.md). This doc captures
**chrome features** (panels, overlays, badges, palette); 016
captures the **design tokens** every feature consumes.

## Schema-validation panel (rf2-dvue)

> **Status:** shipped. Implemented at
> `tools/story/src/re_frame/story/ui/schema_validation.cljc`.
> Differentiator vs Storybook 8: Storybook has no schema-introspection
> equivalent — JSON Schema / PropTypes are descriptive metadata, not
> runtime conformance.

A right-panel surface that, per the active variant, renders two
streams in one place so the user sees "the args you're rendering this
variant with violate the component's contract" immediately.

### Two-section layout

The panel renders the active variant's:

1. **Args violations** — given the variant's resolved args + the
   component's registered schema, walks every `:map` entry and
   reports per-key conformance. Complements the Controls panel's
   widget-derivation: where Controls infers a widget from the schema,
   the Schema panel reports whether the *current* args satisfy it.

2. **Trace violations** — every
   `:rf.error/schema-validation-failure` trace event scoped to the
   variant's frame (per Spec 010 §Validation timing — boundary,
   sub returns, cofx injections, app-db post-handler slices).
   Boundary validation runs in production too; the panel is the one
   place a developer reading a Story sees "this variant is silently
   violating its schema right now."

### Schema lookup (normative order)

The panel resolves the component schema by first-match wins:

1. The variant body's `:schema` slot — explicit per-variant override
   (forward-compatible with the `:rf/schema` Spec 010 slot per
   IMPL-SPEC §9.4).
2. The parent story body's `:schema` slot — story-wide schema.
3. The framework registrar's `:view` metadata for the variant's
   `:component`: `:spec` (Spec 010 canonical) then `:schema` (legacy /
   Story-only alias).

When no schema is on file, the Args section reports "no schema
registered" and the Trace section still surfaces any
`:rf.error/schema-validation-failure` events emitted against the
frame (e.g. from `reg-app-schema` registrations the variant set up,
or from event / cofx specs on dispatched events).

### Trace projection (pure, normative)

The panel projects each trace event into a row. Per
`schema_validation.cljc/project-failure` the row shape is:

```clojure
{:id         <int>              ;; trace event :id (stable per-process key)
 :time       <ms-since-epoch>   ;; from the trace event :time
 :where      <kw>               ;; :event / :sub-return / :cofx / :app-db / :fx-args
 :failing-id <kw|nil>           ;; the artefact id whose :spec failed
 :path       <vector|nil>       ;; only for app-db failures
 :received   <any>              ;; the rejected value
 :explain    <any|nil>          ;; the validator's explanation
 :recovery   <kw|nil>           ;; the spec-defined recovery posture
 :raw        <trace-event>}     ;; the full event for trace deep-dives
```

The `:where` axis is the same enum Spec 010 §Validation order pins:
`:event` / `:sub-return` / `:cofx` / `:app-db` / `:fx-args`. The
panel groups rows by `:where` so the user can scan one failure
category at a time.

### Registration slot

Registers as `:rf.story.panel/schema-validation` via
`reg-story-panel*`, placement `:right`. The render view is
`:rf.story.panel/schema-validation-view`, registered against the
framework view registry. Late-bind discipline: the `install!` call
sits behind the `re-frame.story.config/enabled?` gate; production
builds DCE-prune the require graph so the panel never reaches
production bundles. The pure helpers stay in the bundle but never
run.

### Pure / impure split (CLJC discipline)

The namespace is `.cljc` by design so the pure projection helpers
(`schema-validation-event?`, `project-failure`, `project-failures`,
`args-violations`, `format-explain`) run under both the JVM unit-
test target and the CLJS node-test build. The Reagent rendering,
the panel registration, and the validator lookup-via-late-bind live
under `:cljs`. JVM test coverage is the gate that catches projection
regressions before the Reagent layer.

### Cross-references

- [`spec/010-Schemas.md`](../../../spec/010-Schemas.md) — the
  validation timing the panel filters against and the
  `:rf.error/schema-validation-failure` emission contract.
- [`006-MCP-Surface.md`](./006-MCP-Surface.md) §Schema validation —
  the inventory diagram line this section makes substantive.
- [`tools/causa/spec/005-Schema-Timeline.md`](../../causa/spec/005-Schema-Timeline.md)
  — Causa's temporal surface for the same emission stream (the two
  panels share the same trace events but render different views).

## Sidebar tag-as-badge affordance (rf2-nwiwr)

> **Status:** shipped — Stage 4 polish (per
> [`005-SOTA-Features.md`](./005-SOTA-Features.md) §Sidebar tag-as-
> badge affordance). Implemented at
> `tools/story/src/re_frame/story/ui/sidebar.cljs`.

A row of small colour-coded pills rendered inline to the right of
each variant id in the sidebar tree. The badge row makes the
inclusion-tag vocabulary
([`001-Authoring.md`](./001-Authoring.md) §Inclusion tags) visible at
a glance — a `:test` variant and a `:docs` variant are
distinguishable without expanding the row.

### Vocabulary (the seven canonical tags)

Per [`001-Authoring.md`](./001-Authoring.md) §Inclusion tags + the
`tag->badge-style-key` map in `sidebar.cljs`:

| Tag | Style key |
|---|---|
| `:dev` | `:tag-badge-dev` |
| `:docs` | `:tag-badge-docs` |
| `:test` | `:tag-badge-test` |
| `:screenshot` | `:tag-badge-screenshot` |
| `:experimental` | `:tag-badge-experimental` |
| `:internal` | `:tag-badge-internal` |
| `:agent` | `:tag-badge-agent` |

Unknown tags (project-custom additions outside the canonical seven)
map to `nil` and fall through to the neutral `:tag-badge` style.
Badges are stable-sorted by `name` so the visual order is
deterministic across rows.

### Rendering contract

The renderer (`sidebar.cljs/tag-badges`) emits a horizontal flex row
of pills. Each pill carries:

- the tag's `name` (no leading colon) as visible text,
- `:data-tag` attribute set to the tag's `name` (test-corpus locator,
  stable),
- `:title` set to the full `(str tag)` (the keyword-with-colon form,
  for hover affordance),
- `:data-test` set to `"story-sidebar-tag-badge"`.

The badge row's container carries `:data-test
"story-sidebar-tag-badges"` so the test corpus can locate the row
without walking style maps. A variant with no tags emits no
container (returns `nil`) so the row layout stays tight.

### Pure / impure split

`tag->badge-style-key`, `sorted-tags`, and `tag-badges` are all
public so the JVM + CLJS test corpus can render and inspect the
hiccup directly without booting Reagent. The Reagent component
boundary is implicit — `tag-badges` returns hiccup, the substrate
adapter renders.

### Cross-references

- [`001-Authoring.md`](./001-Authoring.md) §Inclusion tags — the
  vocabulary the badge palette mirrors.
- [`005-SOTA-Features.md`](./005-SOTA-Features.md) §Sidebar tag-as-
  badge affordance — the SOTA inventory entry this section makes
  substantive.

## First-visit help overlay (rf2-381i)

> **Status:** shipped. Implemented at
> `tools/story/src/re_frame/story/ui/help.cljs`. The Static-Build
> contract ([`013-Static-Build.md`](./013-Static-Build.md))
> already references the suppression rule; this section captures the
> rest of the contract.

A modal-style overlay shown on first mount that explains the
playground UI: what `:dev` / `:docs` / `:test` mode-tabs do, what the
sidebar tree means (stories / variants / workspaces), what to click
first, and what each right-panel section shows.

### Auto-open + persistence

- Shown automatically on first mount unless the user has previously
  dismissed it, tracked via `localStorage` under the key
  **`re-frame.story/seen-help-v1`**.
- Bump the trailing `-v<n>` suffix when the help content materially
  changes so returning users see the refreshed copy once.
- localStorage unavailable (private-mode quirks, embedded contexts,
  `file://` in some configs) → degrade to always-show (better to
  over-show than miss the on-boarding).

### Re-open affordance

A `?` chip rendered in the shell chrome (the `help-button`
component). Click → re-open the overlay regardless of the seen flag.

### Dismissal

The overlay closes via any of:

- click on the backdrop,
- press `Escape`,
- click the **"Got it"** button.

Dismissal calls `mark-seen!` which writes the localStorage flag so
subsequent visits skip auto-open. The `reset-seen!` helper exists
for tests + a future "show me the help again" affordance; it is not
currently wired to chrome.

### State scope

Local component state (open / not-open) lives in a Reagent ratom
inside `help-host`. Intentionally NOT in the shell-state atom — the
welcome popup is ephemeral UI, not playground state.

### Static-build suppression

Per [`013-Static-Build.md`](./013-Static-Build.md) §Suppressed
chrome, the auto-open branch is gated on `(not static-mode?)`. The
overlay is for live-playground onboarding; static-export builds
serve a stable artefact a static-doc reader doesn't need to be
on-boarded to.

### Voice + colour

Matches the rest of the Story shell chrome, consuming the canonical
token vocabulary from
[`016-Design-Tokens.md`](016-Design-Tokens.md) §Colour: the panel
ground reads against `:bg-overlay`; body text wears
`:text-primary`; muted labels wear `:text-secondary`; semantic
states (success / warning / danger / info) wear the corresponding
semantic tokens. The token sweep (rf2-i3i5j) replaced the
pre-existing VS-Code Dark+ literals (`#252526` / `#cccccc` / …)
with semantic tokens; all foreground colours meet WCAG AA against
the panel ground per the rf2-2uwv contrast baseline. Tight bulleted
copy — no paragraphs.

### Bundle isolation

Production builds with `re-frame.story.config/enabled?` false never
reach this ns; Closure DCE drops the lot. The localStorage key
constant `seen-key` is the one named export the test corpus reaches
for.

### Cross-references

- [`013-Static-Build.md`](./013-Static-Build.md) §Suppressed chrome —
  the static-mode gate.
- [`003-Render-Shell.md`](./003-Render-Shell.md) — the shell chrome
  the `?` help-button lives in.

## Command palette (rf2-9hc8)

> **Status:** shipped. Implemented at
> `tools/story/src/re_frame/story/ui/command_palette.cljc` (pure
> projection + scoring) and `.../command_palette/view.cljs` (Reagent
> overlay + global shortcut). Differentiator vs Storybook 8: Story's
> palette searches the *complete* re-frame2 registry (variants,
> workspaces, stories, modes, decorators) under one keybinding —
> Storybook ships per-surface searches stitched together.

A floating overlay opened by **Cmd-K / Ctrl-K** that searches every
registered Story entity and routes the selected entry to canvas,
workspace, or toolbar. Closes on `Escape`, scrim click, or selection.

### Searched kinds (the five canonical entry shapes)

Per `command_palette.cljc/searchable-kinds`:

| Kind | Source slot | Selection effect |
|---|---|---|
| `:variant` | registry `:variants` | Select the variant on the canvas; clear workspace. |
| `:workspace` | registry `:workspaces` | Activate the workspace; clear variant selection. |
| `:story` | registry `:stories` | Jump to the story's first registered child variant when one exists; otherwise a no-op (palette still closes). |
| `:mode` | registry `:modes` | Reuse the toolbar mode-toggle so persistence stays in one place. |
| `:decorator` | registry `:decorators` | Registry data only in MVP — selection closes the palette without further effect. |

Unknown registry slots are ignored. Entries derive from a single
`state/registry-snapshot` read at open-time; the palette does not
subscribe to live registry mutations within a single open session.

### Registration projection (pure, normative)

Each registry entry projects to a row of the shape:

```clojure
{:kind        :variant|:workspace|:story|:mode|:decorator
 :kind-label  "Variant"|"Workspace"|...           ;; from kind-labels
 :id          <kw|...>                            ;; the registry key
 :id-label    <string>                            ;; (str id) for keywords, (pr-str) otherwise
 :doc         <string>                            ;; (:doc body) stringified; "" when absent
 :body        <registry-body>}                    ;; the raw registry value
;; :story rows additionally carry :variant-ids — sorted child-variant ids.
```

The projection lives in `entries`; downstream sort/score uses
`:id-label` and `:doc` as the searchable substrate.

### Token-AND scoring

`match-score` splits the (lower-cased, trimmed) query on whitespace
and scores each token via `token-score`. A match requires *every*
token to score; the row's total is the sum plus a small kind-bias
(`:variant 8 :workspace 7 :story 6 :mode 5 :decorator 4`).
Per-token weights (highest wins):

| Match kind | Score |
|---|---|
| Token equals id-label | 240 |
| Token equals kind name | 180 |
| Id starts with token | 140 |
| Id contains token | 110 |
| Doc contains token | 80 |
| Combined text (kind + id + doc) contains token | 60 |
| Token is a subsequence of id | 32 |
| Token is a subsequence of combined text | 18 |

An empty query returns every entry with score 1 — the palette opens
with the full registry visible.

`search` returns the top N (default 30 from the overlay; 20 from the
2-arity helper) entries sorted by descending score, then by
`searchable-kinds` declaration order, then by `:id-label` for
stability.

### Keyboard contract

- **Cmd-K / Ctrl-K** anywhere in the window — toggle open/closed.
  The shortcut predicate (`shortcut-event?`) ignores `Alt` and
  `Shift` modifiers; `Meta` (Mac) and `Ctrl` (Win/Linux) both fire.
- **ArrowDown / ArrowUp** — move the active row; wraps via
  `move-active-index` / `clamp-active-index`.
- **Enter** — apply the active entry through `select-entry!` and
  close.
- **Escape** — close without applying.
- **Scrim click** — close without applying.

The listener is registered in capture phase on `window` so the
shortcut survives focused inputs. Production builds with
`re-frame.story.config/enabled?` false skip the listener install.

### Test affordances

- `[data-test="story-command-palette"]` — the scrim container.
- `[data-test="story-command-palette-input"]` — the search input.
- `[data-test="story-command-palette-result"]` — each result row;
  also carries `:data-kind` (the kind name) and `:data-id` (the
  id-label) so test-corpus selectors can target a specific entry
  without scraping styles.
- `[data-test="story-command-palette-empty"]` — the "no matches"
  row.

The `[data-test=story-command-palette-result]` selector is the
contract row 105 of [`015-Test-Coverage.md`](./015-Test-Coverage.md)
binds against.

### Pure / impure split (CLJC discipline)

`command_palette.cljc` is pure — `entries`, `normalize-query`,
`match-score`, `search`, `clamp-active-index`, `move-active-index`,
and the supporting helpers all run under the JVM test target.
Reagent rendering, the keydown listener, and the `select-entry!`
state writes live in `command_palette/view.cljs` under `:cljs`.
JVM coverage gates projection + scoring regressions before the
Reagent layer.

### What v1 deliberately leaves to implementation

- **Recently-selected / pinned entries.** No history weighting in
  the scorer; every open starts from a cold registry snapshot.
- **Custom action verbs.** The palette navigates / activates; it
  does not host arbitrary commands (e.g. "Run all tests"). Toolbar
  / chrome buttons keep that surface.
- **Multi-kind faceting.** No UI to scope the search to one kind
  (`/variant foo`, etc.) — token-AND on the `kind` name covers the
  common case (`variant counter` matches variant rows that include
  "counter").
- **Result-row icons.** Kind labels carry the affordance; iconify
  is deferred until the chrome palette consolidates.

### Cross-references

- [`015-Test-Coverage.md`](./015-Test-Coverage.md) row 105 — the
  test-corpus contract this section makes substantive.
- [`010-Toolbar.md`](./010-Toolbar.md) — the mode-toggle entry-point
  the `:mode` kind reuses.

## Phase 3 chrome cluster (rf2-38pb9 Storybook ADOPTs)

The Phase 3 cluster lands six umbrella features from the rf2-38pb9
Storybook audit's ADOPT inventory. Each was filed against a sibling
bead and landed across the rf2-rcoht / #1574 / #1582 PRs. The
features are normative chrome: tested by `npm run test:cljs` for the
pure projections and `test:story-feature-load` for the browser
contract.

The cluster shares one identity-bearing constraint: features must
read as **Story chrome** (amber-on-warm-slate, Plex typography, motion
choreography) rather than commodity Storybook chrome — see
[`DESIGN-RATIONALE.md`](DESIGN-RATIONALE.md) §Rejected "Storybook
commodity patterns" for the comparator pass.

### Chrome-visibility hotkeys (rf2-g8l8x + rf2-p3i0t)

> **Status:** shipped. Implemented at
> `tools/story/src/re_frame/story/ui/keybindings.cljs`. Comparator
> baseline: Storybook ships **one** chrome hotkey (a/d toolbar toggle);
> Story ships four under one capture-phase listener with one
> registry of bound keys.

A `{key → handler}` registry that backs four chrome-level muscle-memory
hotkeys: `f` toggles full-screen, `s` toggles the sidebar, `a` toggles
the RHS / addons pane, `t` toggles the toolbar. The single
`window#keydown` capture-phase listener co-exists with the command
palette's `Cmd-K / Ctrl-K` listener (`command_palette/view.cljs` —
the palette is modal, the hotkey registry handles inline chrome
toggles).

#### Discrimination contract

The dispatcher only fires a handler when ALL of the following hold:

- `event.key` is a single lowercase letter present in the
  `bindings` map.
- **No modifier is held.** `Meta` / `Ctrl` / `Alt` press passes
  through to the browser or the palette listener — the chrome
  hotkeys are deliberately modifier-less.
- **Focus is not in an editable region** (`<input>` / `<textarea>` /
  `<select>` / `[contenteditable=true]`) — typing `f` into the
  sidebar search box must not toggle full-screen.

The `dispatch-key?` predicate is pure and JVM-testable: it accepts
`(key, modifier?, editable?)` and returns the discrimination boolean.
The listener itself does the DOM-side feature detection.

#### Persistence

The chrome-visibility toggles persist to localStorage under
`re-frame.story/chrome-visibility` so a refresh keeps the user's
layout intent. Hydration runs once at shell mount via
`keybindings/hydrate!`. The persisted slots are the four boolean
toggles (`:full-screen?` / `:sidebar?` / `:rhs?` / `:toolbar?`);
`:embed?` is deliberately excluded — embed-mode is URL-driven (per
§Embed-mode flag below) and must not survive across navigations.

#### Escape semantics

While full-screen is active, the same listener intercepts `Escape` and
calls `exit-full-screen!` — a public escape handler that clears
`:full-screen?` regardless of prior state. The escape is independent
of the chrome's modal stack (the command palette also closes on
`Escape`); the listener sequence is install-order-stable, and the
palette's modal layer takes priority when both are open.

#### Bundle isolation

Production builds with `re-frame.story.config/enabled?` false never
install the listener — Closure DCE drops the registry, the dispatcher,
and the handler fns. The `ls-key` constant + the pure
`dispatch-key?` predicate stay testable in the JVM target.

#### Cross-references

- [`015-Test-Coverage.md`](./015-Test-Coverage.md) §Chrome-visibility
  hotkeys row — the test-corpus contract this section makes
  substantive.
- §First-visit help overlay — the help-overlay shortcut-table section
  reads `keybindings/shortcut-keys` so the rendered cheat-sheet stays
  in lockstep with the registry.

### Sidebar search-as-you-type (rf2-yngai)

> **Status:** shipped. Implemented at
> `tools/story/src/re_frame/story/ui/sidebar_search.cljc` (pure
> tokenisation + match predicates) and the Reagent input in
> `tools/story/src/re_frame/story/ui/sidebar.cljs`. Comparator
> baseline: Storybook ships in-tree search; Story matches the
> ergonomic, plus differentiates by token-AND scoring and amber-tint
> match-segment highlighting.

A text input above the sidebar tree filters the visible variants /
stories / workspaces by substring match. Different ergonomic from
`Cmd-K` (the command palette is a fuzzy whole-registry jump; the
sidebar search narrows the existing tree in place).

#### Match semantics

Token-AND, case-insensitive, substring per token. The haystack for a
variant is the concatenation of its id-string, its parent story id
(derived from the keyword's namespace), the variant body's `:doc`
prose, and its tags. Empty / blank query → every variant matches
(the filter no-ops). The same token-AND discrimination drives the
command palette's `match-score` (see §Command palette §Token-AND
scoring) so users get consistent narrowing across both surfaces.

#### Highlight contract

Matching segments are tinted amber via `highlight-segments` — a pure
data → data helper that splits a label into a vector of
`{:match? true|false :text "..."}` chunks. The sidebar renders each
chunk inline; matching chunks wear the `accent-amber-soft` ground +
`accent-amber` text token. The pure helper is JVM-testable.

#### Pure / impure split (CLJC discipline)

The namespace is `.cljc` by design — every match helper
(`tokenise`, `match-variant?`, `match-story?`, `filter-grouped-tree`,
`highlight-segments`) runs under both the JVM unit-test target and
the CLJS node-test build. The Reagent input plus the controlled-
state ratom live under `:cljs` in `sidebar.cljs`.

#### Cross-references

- [`015-Test-Coverage.md`](./015-Test-Coverage.md) §Sidebar
  search-as-you-type row.
- §Command palette §Token-AND scoring — the shared discrimination
  contract.

### Loading skeleton (rf2-0s4p1)

> **Status:** shipped. Implemented at
> `tools/story/src/re_frame/story/ui/canvas.cljs` §loading skeleton.
> Comparator baseline: Storybook ships a generic neutral skeleton-row;
> Story ships an **identity-bearing** amber-shimmer-on-warm-slate
> skeleton that reads as "workshop loading" rather than commodity
> placeholder.

A three-bar amber-shimmer skeleton with an inset amber edge that
renders inside the canvas while the variant's four-phase lifecycle
is in `:pre-mount` / `:mounting` / `:loading`. Once the variant has
committed a first render the skeleton is suppressed for the rest of
that session (a hot-reload re-run is brief enough that re-flashing
the skeleton would read as a glitch).

#### Render predicate

`loading-phase?` is pure: `(loading-phase? phase first-rendered?
assertions-recorded?)` returns true when phase is in `#{:pre-mount
:mounting :loading}` AND no first render has been committed AND no
assertion has been recorded against the variant frame. The
`assertions-recorded?` arm closes the regression window where the
`:loaders-complete-when` predicate may declare loaders incomplete
(`:story.counter-matrix/loader-never-completes`) or a loader event
may throw a deterministic rejection
(`:story.counter-matrix/loader-rejects`) — in both paths the runtime
records an assertion and the lifecycle machine stays parked at
`:loading`, but the user view must render. A non-empty assertions
vector is the proof that `run-loaders!` returned; pin the skeleton
off so the variant body takes over.

#### Visual contract

Three skeleton bars (78% / 62% / 44% widths) with an amber-shimmer
linear-gradient cycling at 1400ms cubic-bezier easing; an inset
`accent-amber-deep` edge stroke that matches the canvas-frame
chrome; an uppercase `"loading"` label in amber. Behind
`prefers-reduced-motion: reduce` the shimmer falls back to a static
amber inset edge (no animation).

#### Cross-references

- [`015-Test-Coverage.md`](./015-Test-Coverage.md) §Loading skeleton
  row.
- [`016-Design-Tokens.md`](016-Design-Tokens.md) §Motion — the
  `prefers-reduced-motion` honour-contract.
- [`016-Design-Tokens.md`](016-Design-Tokens.md) §Colour —
  `accent-amber*` and `bg-canvas` tokens.

### Viewport-px indicator chip (rf2-zgu68)

> **Status:** shipped. Implemented at
> `tools/story/src/re_frame/story/ui/canvas.cljs` §viewport-px
> indicator chip. Comparator baseline: Storybook ships a viewport
> dropdown but no in-canvas dimension chip; Story renders the chip
> directly on the canvas so the user reads the dimensions without
> opening the toolbar.

A `pointer-events: none` chip rendered at the canvas bottom-right
that displays the active viewport's pixel dimensions (e.g.
`"375 × 667"`). The chip is suppressed when no viewport mode is
active (the default `:full` preset has no `:width` / `:height` so
`viewport-indicator-text` returns nil).

#### Render contract

`viewport-indicator-text` is pure: `(viewport-indicator-text
{:width 375 :height 667 :label "Mobile"})` returns `"375 × 667"`.
Returns nil when either dimension is missing. The Reagent component
`viewport-indicator` consumes the resolved viewport preset map
produced by `viewport/resolve` and renders the chip; `pointer-events:
none` so it never intercepts hits on the underlying canvas content.

#### Test affordances

- `[data-test="story-canvas-viewport-indicator"]` — the chip
  container.
- `:data-viewport-dims` — the resolved dimensions string for
  test-corpus assertion.

#### Cross-references

- [`015-Test-Coverage.md`](./015-Test-Coverage.md) §Viewport-px
  indicator chip row.
- [`010-Toolbar.md`](010-Toolbar.md) §Viewport cluster — the upstream
  registration that the chip reads.

### Docs-mode table of contents (rf2-8c7tk)

> **Status:** shipped. Implemented at
> `tools/story/src/re_frame/story/ui/docs.cljc` §TOC. Comparator
> baseline: Storybook ships a docs-mode TOC; Story matches the
> ergonomic plus auto-hides more aggressively (≥1024px instead of
> ≥1200px) since the RHS is already present.

A sticky right-edge nav pane that lists the docs-mode sections
(prose / args / decorators / parameters / tags) and scroll-syncs the
active entry via `IntersectionObserver`. Self-elides on viewports
below 1024px.

#### Pure projection

`docs-toc-entries` is the canonical entry table — a vector of maps
`{:id :label :level :conditional?}`. `visible-toc-entries` is pure
data → data: prune entries that don't apply to the variant (the
prose section is the only conditional one — dropped when
`(prose-for-variant variant-id)` returns empty). The variant's `<h1>`
header is intentionally NOT in the TOC list — it sits beside that h1
and would self-reference.

#### Scroll-sync contract

A `IntersectionObserver` with `rootMargin: "-30% 0px -60% 0px"`
watches each registered section's anchor element. The most-recently-
intersected id becomes the active entry; the corresponding button
wears the `accent-amber-soft` ground + `accent-amber` text token
plus `aria-current="location"`. Clicking an entry calls
`scrollIntoView({behavior: "smooth", block: "start"})` on the
anchor element.

#### Test affordances

- `[data-test="story-docs-toc"]` — the nav container.
- `[data-test="story-docs-toc-item"]` — each TOC entry button.
- `:data-toc-target` — the anchor id the entry jumps to.

#### Cross-references

- [`015-Test-Coverage.md`](./015-Test-Coverage.md) §Docs-mode TOC row.
- [`008-Docs-Mode.md`](008-Docs-Mode.md) — the section schema the
  TOC mirrors.

### Embed-mode flag (rf2-pucku)

> **Status:** shipped. Hydration at
> `tools/story/src/re_frame/story/ui/url_state.cljc` §embed-flag.
> Comparator baseline: Storybook ships a `viewMode=story` URL flag
> that hides chrome for blog-embed scenarios; Story matches the
> ergonomic via `?embed=1`.

An optional `?embed=1` query-string flag that suppresses the
chrome (sidebar / toolbar / RHS) so the canvas can be embedded in
blog posts, marketing pages, or design-review docs without the
workshop UI surrounding it. The flag is **URL-driven only** — never
persisted to localStorage (see §Chrome-visibility hotkeys §Persistence
above: `:embed?` is explicitly stripped from the localStorage
persistence slot).

#### Hydration contract

`hydrate-embed-flag!` is one-shot at shell mount. It reads
`embed-flag-from-current-url` and seeds `[:chrome-visibility :embed?]`
exactly once. Subsequent navigations don't re-read the URL — the
flag is a session-start signal, not a watched parameter.

#### Effect

When `:embed?` is true, the shell suppresses the sidebar, toolbar,
and RHS panels (the same surfaces the `f` hotkey toggles when
`:full-screen?` flips), exposing the canvas at full width. The
viewport-px chip (§Viewport-px indicator chip) continues to render
when a viewport mode is active.

#### Cross-references

- [`015-Test-Coverage.md`](./015-Test-Coverage.md) §Embed-mode flag
  row.
- [`013-Static-Build.md`](013-Static-Build.md) — embed mode pairs
  naturally with static builds for design-review artefacts.

## What this doc deliberately doesn't normalise

- **Schema-validation panel widget styling.** Colours, icons, row
  layouts inside the panel follow the shell's design tokens
  ([`016-Design-Tokens.md`](./016-Design-Tokens.md)); the spec
  normalises the projection contract and the registration slot, not
  the pixel-level appearance.
- **Help-overlay body copy.** Treated as ephemeral chrome copy that
  evolves with the playground; the `-v<n>` suffix on `seen-key` is
  the canonical lever for marking copy changes.
- **Tag-palette hex values.** Token names (`:tag-{dev,docs,test,...}-bg`
  / `-fg` etc.) are normative — they live in
  [`016-Design-Tokens.md`](./016-Design-Tokens.md) §Colour. The
  specific hexes resolve through `theme.colors/tokens`; the tokens
  move when the palette does.
- **Command-palette overlay styling.** Panel width, blur, scrim
  alpha, and result-row layout follow the chrome's design tokens
  ([`016-Design-Tokens.md`](./016-Design-Tokens.md)) — `:bg-overlay`
  ground, `:text-primary` body text. The spec normalises the kind
  set, scoring, keyboard contract, and test affordances rather than
  pixel-level appearance.
- **Skeleton bar widths and shimmer cadence.** The §Loading skeleton
  bar widths (78% / 62% / 44%) and the 1400ms shimmer cycle are the
  shipped numbers; the spec locks the three-bar amber-shimmer-on-
  warm-slate contract + the `prefers-reduced-motion` honour rule and
  treats the exact widths / cadence as implementation detail.
- **TOC scroll-sync threshold.** The §Docs-mode TOC's
  `rootMargin: "-30% 0px -60% 0px"` is shipped tuning; the spec
  locks the IntersectionObserver-driven contract and the auto-hide
  ≥1024px threshold, not the exact rootMargin numbers.
- **Hotkey letter assignments.** The §Chrome-visibility hotkeys
  letters (`f` / `s` / `a` / `t`) are aligned with Storybook
  conventions (`a` for "addons") where they don't conflict; the
  spec locks the discrimination contract (no modifier, no editable
  focus) and the registry pattern, not the specific letters — a
  consuming project could re-key via `keybindings/bindings`.
