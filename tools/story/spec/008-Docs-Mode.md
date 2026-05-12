# Story — `:docs` Mode Pane

> The read-only AutoDocs-equivalent view that sits behind the `:docs`
> mode-tab. Composes header, prose, args, decorators, parameters, and
> tags sections for a single variant. The downstream surface the
> mode-tabs primitive (rf2-9hc8, spec/007) was built to host.

## Why a dedicated docs pane

The render shell already exposes every datum the docs pane needs —
the variant body via the registrar, the resolved arg merge via
`re-frame.story.args/resolve-args`, the decorator stack via
`re-frame.story.decorators/resolve-decorators`, the workspace
registry for prose lookups. What was missing was a **read-only
presentation surface** that gathers them in the same order Storybook
8's MDX / AutoDocs page does, so a reader can scan a variant's
contract without driving the controls panel.

The pane is deliberately read-only: editing happens in the Canvas
(`:dev`) mode's controls panel; switching back from `:docs` restores
any overrides the user had typed. Docs reflects the variant's
declared shape, not transient runtime edits.

## Surface

One namespace, one entry point, plus pure helpers for the JVM test
corpus:

```clojure
(re-frame.story.ui.docs/docs-view variant-id)   ; CLJS Reagent component

;; pure data → data (JVM-testable):
(prose-for-variant variant-id)
  ; → [{:workspace-id ... :body ...} ...]
(args-rows variant-id resolved-args)
  ; → [{:key ... :value ... :doc ...} ...]
(decorator-rows decorator-pack)
  ; → [{:section :hiccup|:frame-setup|:fx-override|:error
  ;     :id ... :doc ...} ...]
(parameter-rows variant-id)
  ; → [{:key :modes|:substrates|:platforms :value ...} ...]
(variant-tags variant-id)
  ; → [<tag> ...]
```

The render shell's `main-pane` calls `docs/docs-view` when the
per-variant mode-tab is `:docs`. Selection is owned by the mode-tabs
primitive; this spec does not touch the chip strip.

## Section composition

The pane renders six sections, top-to-bottom:

| # | Section     | Data source                                                                     | Rendered when                                  |
|---|-------------|---------------------------------------------------------------------------------|------------------------------------------------|
| 1 | Header      | variant id, parent-story id, tags, variant `:doc` (or parent `:doc` fallback)   | always                                         |
| 2 | Prose       | `:body` strings from `:layout :prose` workspaces that reference this variant   | at least one prose block found                 |
| 3 | Args        | `(args/resolve-args variant-id {:cell-overrides nil})` × variant `:argtypes`    | always (renders "no args resolved" if empty)   |
| 4 | Decorators  | `(decorators/resolve-decorators variant-id)`                                    | always (renders "no decorators" if empty)      |
| 5 | Parameters  | variant body's `:modes` / `:substrates` / `:platforms` slots (story fallback)  | always (renders "no … declared" if all empty) |
| 6 | Tags        | sorted variant tags (parent-story fallback)                                     | always (renders "no tags" if empty)            |

### 1. Header

```
:story.counter/loaded
parent story: :story.counter
[dev] [docs] [test]
A counter seeded with a non-zero value.
```

- Variant id as the page `<h1>`.
- Parent story id below (or `"no parent story registered"` if the
  variant id has no namespace).
- Tag chips — the same set rendered in §6, hoisted to the header so
  readers see them at a glance. Each chip clicks through to
  `state/toggle-tag-filter`.
- Variant `:doc` (or parent story `:doc` as fallback) as italic
  blurb beneath the chips.

### 2. Prose

Story v1's schema deliberately does NOT carry a per-variant `:prose`
slot — prose belongs to a workspace, not a variant (a Stage-2
authoring decision per spec/001). The docs pane bridges that:

- Walk every `:layout :prose` workspace.
- For each workspace whose `:content` references this variant via a
  `{:type :variant :id <variant-id>}` item, collect the `:body`
  strings of any `{:type :prose :body "..."}` items in the same
  workspace.
- Render each block in source order, prefixed with the workspace id
  so the reader knows where the prose came from.

If no `:prose` workspace mentions the variant the section is omitted
entirely (no empty-state placeholder — keeps the page clean).

### 3. Args

A three-column table — `key | default | doc` — sorted by arg-key.

- **key.** The arg's keyword.
- **default.** The resolved value via
  `args/resolve-args variant-id {:cell-overrides nil}` — the args
  the variant renders WITHOUT runtime overrides. Read-only docs
  shouldn't reflect the user's transient edits in the controls
  panel.
- **doc.** The docstring pulled from the variant's (or parent
  story's) `:argtypes` entry for the key. Supported shapes:
  - `{:argtypes {:foo {:doc "…"}}}`        → `:doc`
  - `{:argtypes {:foo {:description "…"}}}` → `:description`
    (Storybook compat)
  - `{:argtypes {:foo "…"}}`               → the string itself
  - anything else                          → `—`

### 4. Decorators

Three-column table — `kind | id | doc` — every entry in the
`resolve-decorators` pack in apply order:

1. `:hiccup` entries — outermost (story-level) first, innermost
   (variant-level) last.
2. `:frame-setup` entries — declared order.
3. `:fx-override` entries — declared order (collisions resolve
   last-wins at the runtime, not at presentation).
4. `:error` entries — any unresolved decorators (`:errors` slot
   from the pack), surfaced inline so the reader sees what's
   broken without switching to the trace panel.

### 5. Parameters

re-frame2 does NOT carry Storybook's free-form `:parameters` map on
variant bodies — the same surface area is covered by three explicit
slots: `:modes`, `:substrates`, `:platforms`. The docs page collapses
them into a two-column `key | value` table, falling back to the
parent story's slot when the variant body declares none.

If all three slots are empty the section renders a "no … declared"
placeholder rather than vanishing — parameters are an important
contract surface and silently dropping them would be misleading.

### 6. Tags

Bottom-of-page chip strip — same chips as the header, repeated so a
reader who has scrolled past the table doesn't have to scroll up to
forward-link to the sidebar.

Each chip:

- Carries `data-docs-tag="<tag-name>"` for the Playwright spec.
- Sets `aria-pressed` to reflect the sidebar's current
  `:tag-filter` membership.
- Click dispatches `state/toggle-tag-filter` — the same handler the
  sidebar uses, so the sidebar's tag-row chips and the docs chips
  stay in lockstep.

## Read-only contract

The pane MUST NOT carry any input elements that mutate args /
overrides / modes. Switching `:docs` → `:dev` MUST restore the
canvas as the user left it — same args, same overrides, same modes.

The pane MAY carry buttons that mutate **shell-state filters** (tag
chips forward-link to `state/toggle-tag-filter`) — the pane is
read-only with respect to the *variant*, not to the shell's
sidebar / filter state.

## Test selectors

The Playwright spec drives the pane via `data-test`-prefixed
selectors so visible labels can be reworded without breaking tests:

| Selector                                              | What                                       |
|-------------------------------------------------------|--------------------------------------------|
| `[data-test="story-docs-view"]`                       | The pane root (`<section>` landmark).      |
| `[data-test="story-docs-parent-story"]`               | Parent-story sub-header.                   |
| `[data-test="story-docs-doc-blurb"]`                  | The italic variant-`:doc` blurb.           |
| `[data-test="story-docs-header-tags"]`                | Header tag chip row.                       |
| `[data-test="story-docs-prose-section"]`              | Prose section (omitted if no prose).       |
| `[data-test="story-docs-prose-block"]`                | One prose block from a workspace.          |
| `[data-test="story-docs-args-section"]`               | Args section.                              |
| `[data-test="story-docs-args-table"]`                 | The args table.                            |
| `[data-test="story-docs-args-row"]`                   | One arg row; `data-arg-key="<:key>"`.      |
| `[data-test="story-docs-decorators-section"]`         | Decorators section.                        |
| `[data-test="story-docs-decorators-table"]`           | Decorators table.                          |
| `[data-test="story-docs-decorator-row"]`              | One decorator row; `data-section="hiccup"` |
| `[data-test="story-docs-parameters-section"]`         | Parameters section.                        |
| `[data-test="story-docs-parameters-table"]`           | Parameters table.                          |
| `[data-test="story-docs-parameter-row"]`              | One parameter row; `data-param-key`.       |
| `[data-test="story-docs-tags-section"]`               | Tags section (bottom).                     |
| `[data-test="story-docs-tag-link"]`                   | Bottom tag chip.                           |
| `[data-test="story-docs-tag-chip"]`                   | Header tag chip.                           |
| `data-docs-tag="<tag-name>"`                          | On each chip — match without coupling to visible text. |

## Visual style

Matches the render-shell chrome (rf2-2uwv contrast palette):
`#b0b0b0` inactive foreground, `#dcdcdc` body text, `#1e1e1e`
section background, `#2d2d30` table-header background, monospace
for table content + variant ids, system-ui for prose. The pane
scrolls inside the `<main>` landmark; the strip sits above it
(owned by the mode-tabs primitive).

## Out of scope at v1

- **Per-variant `:prose` slot.** Adding `:prose` to the variant
  schema is its own EDN-shape change — the docs pane piggybacks on
  workspaces for v1, which keeps the schema closed.
- **MDX rendering.** The prose body renders as `pre-wrap` plain
  text. Markdown → hiccup is a separate concern; if a project wants
  rich rendering it registers a `reg-story-panel` with a custom
  view. The docs pane's role is to present what's registered, not
  to introduce a Markdown dependency.
- **Editable docs.** Read-only is the contract; v2 may add an inline
  notes affordance but it would be a new shell-state slot, not a
  mutation of the variant body.
- **Storybook-style story-level docs pages.** Each variant has its
  own docs pane in v1 — the parent-story-level rollup
  (Storybook's `Component.docs`) is a v2 surface. Workspaces already
  cover the multi-variant rollup use-case.

## Foundational status

Per the rf2-9hc8 / rf2-rodx pair: the docs pane is a **leaf** — it
consumes the foundation (registrar, args resolution, decorator
resolution, workspace registry) and surfaces it. It does not
register new artefact kinds, does not add new shell-state slots, and
does not change the mode-tabs chip strip. Removing `docs-view`
restores the placeholder-equivalent empty pane without breaking any
other surface.
