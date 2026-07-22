# Freehand user guide (draft)

This is a proposed end-user guide for **Freehand** (`re-frame.freehand`, alias
`v`). It is derived from the ratified design in `docs/design/freehand/` and
shaped after the existing `docs/core/re-frame.ui/` guide.

**Voice:** friendly senior developer — simple and direct, tight but not terse.
Full sentences; a short *why* after instructions; progressive depth. Not a
telegram dump, not a marketing essay. Would it sound natural at 11pm?

**Authoring standard:** when this graduates into `docs/`, follow
[`docs/core/AUTHORING.md`](../../../core/AUTHORING.md) — one job per page,
reader-first order (goal → code → why), day-one stop before depth, unhappy-path
tables, “when not,” no page chrome navigation, no reader-facing `spec/` links.

## Status

| Item | State |
|---|---|
| Source of truth | Product spine + D001–D021 (design record) |
| Implementation | Incomplete — guide is aspirational |
| Published mkdocs | Not wired; lives under `ai/findings/` as a working draft |
| Audience | App authors, library authors, AI assistants writing Freehand views |

## Suggested mkdocs nav (sections)

When this graduates into `docs/`, use sectioned navigation — not a flat peer list:

```text
Start here
  index.md
  mental-model.md
  vs-reagent.md          # optional; Reagent habits
  install.md             # target boot / require / status table
  build-a-view.md

Everyday authoring
  reactivity-and-ownership.md
  state.md
  forms.md
  events-and-handlers.md
  debugging.md

Structure & libraries
  composition.md         # structure + spreads + theming/parts
  accessibility.md
  semantic-controllers.md  # optional library pattern only

Performance
  compilation.md

Host & browser
  host-boundaries.md     # leaves, behaviors, ->react, error-boundary
  js-libraries.md        # worked recipes (Framer, GSAP, charts)
  presence.md
  ssr.md

Ship & live with it
  testing.md
  adoption.md
  limits-and-escapes.md
```

## Reading order

1. [index.md](index.md) — what Freehand is (peer view layer, benefits, modes)  
2. [mental-model.md](mental-model.md) — four shifts  
3. [vs-reagent.md](vs-reagent.md) — optional if you know Reagent  
4. [install.md](install.md) — target require / adapter / mount (aspirational until ship)  
5. [build-a-view.md](build-a-view.md) — end-to-end counter  

Then **fork** by need (not a linear sequel):

- Real app → forms, events, debugging  
- Library author → composition (incl. theming), accessibility, semantic-controllers  
- Integrate / JS widgets → host-boundaries, js-libraries, adoption  
- Hot path → debugging first, then compilation  

## Page inventory

### Start here

| File | Role |
|---|---|
| `index.md` | Landing: peer view layer, re-frame-native benefits, mode table |
| `mental-model.md` | Conceptual shifts; frames, roots, and the DOM |
| `vs-reagent.md` | Habit comparison with Reagent (not the migration checklist) |
| `install.md` | Target boot: require, adapter, preflight, mount, API status table |
| `build-a-view.md` | Progressive tutorial (+ keyed list, mount into DOM) |

### Everyday authoring

| File | Role |
|---|---|
| `reactivity-and-ownership.md` | Invalidation, commit ownership, keys, levers |
| `state.md` | sub, props, A/B/C field-state ladder |
| `forms.md` | Form-slice, touched/errors, seed-merge, submit |
| `events-and-handlers.md` | Vectors, projections, fields, typing/Xray, door, callback identity |
| `debugging.md` | inspect-boundary, hot-views, orphans, perf ladder before compile |

### Structure & libraries

| File | Role |
|---|---|
| `composition.md` | Structure ladder + `spread-safe`/`spread` + theming/parts (D018) |
| `accessibility.md` | Native-first a11y, names/roles, presence exit, provable-only diagnostics |
| `semantic-controllers.md` | Optional controllers; lifecycle; storage; table; library sketch |

### Performance

| File | Role |
|---|---|
| `compilation.md` | `{:compiled true}`, check, placement, closed vocabulary limits |

### Host & browser

| File | Role |
|---|---|
| `host-boundaries.md` | Leaves, behaviors, commands, `->react`, top layer, **error-boundary** |
| `js-libraries.md` | Host recipes: presence first, Framer leaves, GSAP behaviors, hook leaves |
| `presence.md` | Enter/exit: phases, overrides, CSS/a11y, tests, non-goals |
| `ssr.md` | Request frame, roots, hydrate, client-only, route-link |

### Ship & live with it

| File | Role |
|---|---|
| `testing.md` | Structural → frame → mounted; fixtures, settle, presence clock |
| `adoption.md` | Incremental Freehand next to existing adapters |
| `limits-and-escapes.md` | Walls and recoveries |

## Design sources

- `docs/design/freehand/codex-design.md` — product spine
- `docs/design/freehand/fable-design.md` — worked examples and fitness
- `docs/design/freehand/decisions/` — D001–D021
- `docs/core/re-frame.ui/` — tutorial voice and sectioning inspiration

## Maintenance

When a surface graduates into `spec/` or the API lands, update the matching guide
page and remove speculative install notes. **Authors** verify against the design
record and specs; **reader pages** stay a standalone corpus — repeat what the
reader needs, do not link into `spec/` (per AUTHORING.md).

**No page chrome for navigation.** mkdocs supplies sidebar, prev/next, and toc.
Do **not** put reading-order tables, “Start here” page lists, “Going further”
chapter farms, “See also,” or “Where to go next” on pages. Cross-link **only**
inline when the sentence needs a related contract right there — never a map of
the guide.

**Tutorial shape (AUTHORING):**

- Opening = reader problem in one or two sentences (not a mini-TOC of the page).  
- Happy path code early; explanation after.  
- Concept pages: day-one stop (checklist or hard break); depth continues on the
  same page under plain content headings (not a nav section).  
- Unhappy path as symptom → recovery table where readers can get stuck.  
- “When not” when a surface can be misapplied.  
- Depth (controllers, closed compile grammar, host) earned after the stop.

**Consolidation rules (current):**

- Theming/parts live on `composition.md` — do not revive a separate theming page.  
- Error boundaries live on `host-boundaries.md` — do not revive a separate errors page.  
- `js-libraries.md` is recipes for the host section, not a second host contract.  
- `vs-reagent.md` (habits) and `adoption.md` (migration) stay separate.  
- Primary term is **view layer**; adapter/substrate = how a view layer plugs in.

Page inventory and draft reading order live in this README only.
