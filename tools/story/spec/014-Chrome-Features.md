# 014-Chrome-Features

Normative coverage for three shipped user-facing Story surfaces that
were previously discoverable mainly from code / tests rather than the
spec set:

1. The **schema-validation panel** — the live Spec 010 boundary-
   failure surface per variant.
2. The **sidebar tag-as-badge affordance** — the per-variant tag pills
   on sidebar rows.
3. The **first-visit help overlay** — the modal-style on-boarding
   shown on first mount.

Each is captured as its own section below. Where v1 deliberately
leaves details to implementation, the section calls that out so a
one-shot implementer doesn't infer an unwritten contract.

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

Matches the rest of the Story shell chrome: `#252526` panel ground,
`#cccccc` body text, `#b0b0b0` muted labels (post rf2-2uwv contrast
fixes — all foreground colours meet WCAG AA against the panel
ground). Tight bulleted copy — no paragraphs.

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

## What this doc deliberately doesn't normalise

- **Schema-validation panel widget styling.** Colours, icons, row
  layouts inside the panel follow the shell's design tokens
  ([`003-Render-Shell.md`](./003-Render-Shell.md)); the spec
  normalises the projection contract and the registration slot, not
  the pixel-level appearance.
- **Help-overlay body copy.** Treated as ephemeral chrome copy that
  evolves with the playground; the `-v<n>` suffix on `seen-key` is
  the canonical lever for marking copy changes.
- **Tag-palette hex values.** Token names (`:tag-badge-dev` etc.)
  are normative; the specific hexes live in `sidebar_styles.cljs`
  and follow the shell's palette (the tokens move when the palette
  does).
