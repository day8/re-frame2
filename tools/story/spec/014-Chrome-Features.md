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
