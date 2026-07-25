# D001 — Product name and public namespace

Status: **Ruled**
Ruling: **Freehand / `re-frame.freehand`, alias `v`, with no second public door**

Sources: [Codex product spine](../codex-design.md), especially “Design at a
glance”; [Fable dossier](../fable-design.md), opening note and §1.

## Decision

Which product name and namespace own the single public entry point?

The ruling selects **Freehand** and `re-frame.freehand`. This is not cosmetic once
files move out of `re-frame.ui`:
namespace identity will appear in source, qualified data keywords, compiler
findings, manifests, generated documentation, examples, and AI context.

The decision must produce one name, not a preferred name plus a permanent alias.
The absorption ruling says `re-frame.ui` is deleted, so the migration must not
leave two public doors into the same substrate.

## Constraints already settled

- This is one re-frame-native substrate with interpreted and compiled modes.
- `re-frame.ui` is a donor and temporary migration surface, not the new name.
- The normal source alias should remain the short, readable `v`.
- Public data and diagnostic ids need a namespace that can survive the donor's
  deletion.
- Pre-alpha is the inexpensive moment for a clean rename.

## Options

### A. `Freehand` / `re-frame.freehand`

```clojure
(ns app.views
  (:require [re-frame.freehand :as v]))
```

Consequences:

- Distinctive and searchable in prose, source, traces, and package indexes.
- Communicates that this is a designed authoring surface, not merely a generic
  `view` namespace.
- Naturally supports small qualified edges such as `re-frame.freehand.test`,
  `re-frame.freehand.form`, and `re-frame.freehand.controls` without suggesting
  separate products.
- Slightly longer when written unaliased; normal code pays none of that because it
  uses `v`.

### B. `Freehand` / `re-frame.view`

Consequences:

- Short and literal.
- Reads as though it were the sole generic view API in re-frame, which obscures the
  distinction from adapters and makes search results noisy.
- Product name and namespace no longer reinforce one another.
- Qualified keywords such as `:re-frame.view/value` are less recognizable as
  belonging to this particular substrate.

### C. Choose a different product name before implementation

Consequences:

- Still cheap in pre-alpha and legitimate if “Freehand” fails a naming or ecosystem
  check.
- Blocks namespace moves, documentation, and stable diagnostic ids until resolved.
- Reopens work already aligned across both designs; it should happen only for a
  concrete collision or a clearly better name, not indefinite naming exploration.

### D. Publish both namespaces

Consequences:

- Makes migration appear easy but creates two imports, two documentation paths,
  ambiguous generated code, and an alias that becomes difficult to delete.
- Recreates in miniature the two-product ambiguity the absorption ruling removed.

## Recommendation

The selected option is **Freehand** and **`re-frame.freehand`**, with `v` as the
documented alias.
Use `re-frame.freehand.host` and `re-frame.freehand.test` only where a separate edge
namespace materially clarifies host-only or test-only capabilities. Compiler and
runtime implementation namespaces remain internal.

Do not publish `re-frame.view` as an alias. Any future rename would be one
coordinated pre-alpha change, not a second door.

This ruling unlocks namespace ownership for absorbed code, the final spelling of
reserved projection keywords, diagnostic ids, manifest schemas, specs, examples,
and the generated AI context sheet.
