# `section-grouping` — patch-list → path-headed cluster sections (rf2-qeous)

> **Type:** Reference (`tools/mcp-base/spec/`)
> Projects a flat diff patch-list into path-headed cluster **sections** — the same sections-per-cluster decomposition Xray's panel renderer ships (rf2-gfxmk Phase 1 of rf2-abts7), but computed from the patch list rather than the annotated tree. [`diff-encode.md`](diff-encode.md) consumes this to shape the `:db-after` marker's `:sections` slot.

This doc is one of eleven per-namespace contracts indexed from [`README.md`](README.md). See also: [`vocab.md`](vocab.md), [`sensitive.md`](sensitive.md), [`elision.md`](elision.md), [`args.md`](args.md), [`diff-encode.md`](diff-encode.md), [`overflow.md`](overflow.md), [`cap.md`](cap.md), [`cursor.md`](cursor.md), [`envelope.md`](envelope.md), [`descriptor-manifest.md`](descriptor-manifest.md).

## Scope

`section-grouping` owns:

- `group-patches-into-sections` — the flat-patch-list → sections projection.
- `sections->patches` — the lossless inverse (flatten sections back to a replayable patch list).
- `default-opts` (`{:max-coalesce-depth 3}`) — the tunable cluster-coalescence knob, mirroring Xray's defaults.

`section-grouping` does NOT own:

- The patch grammar itself — that's [`diff-encode.md`](diff-encode.md) (`patch-schema`).
- The `:section-path` / `:section-kind` / `:patches` section grammar's Malli schema — that's `diff-encode`'s `section-schema` (pinned at the encode/decode boundary).
- The annotated-tree engine + Xray's panel `sections-per-cluster` pass — those live in `tools/xray/.../diff/`; mcp-base cannot pull Xray in (the dep arrow is tool → mcp-base, never the reverse), so it projects from patches instead.

## Why operate on patches, not the annotated tree

The patches already carry path + op + value — every signal needed to head a cluster. Operating on patches keeps mcp-base dep-free (no Xray pull-in), round-trips losslessly (each section's `:patches` is a subset of the flat list; concatenating them reconstructs the path-ordered list that `apply-patches` replays unchanged), and loses no fidelity vs the annotated-tree projection for the agent-query use case — both produce the same N path-headed clusters; only the per-cluster body shape differs (patches here vs annotated subtree there).

## Shape

**Input:** a flat patch vector (the output of `diff-encode/collect-patches`). A patch is `[path :assoc v]` (3-tuple) or `[path :dissoc]` (2-tuple).

**Output:** a sorted vector of sections —

```clojure
[{:section-path [:cart :items]
  :section-kind :modified
  :patches      [[[:cart :items 0 :qty] :assoc 2]
                 [[:cart :items 0 :discount] :assoc 0.1]]}
 {:section-path [:checkout :state]
  :section-kind :modified
  :patches      [[[:checkout :state] :assoc :paying]]}]
```

- `:section-path` — the cluster's breadcrumb: the shared ancestor for multi-patch clusters; the patch's parent for promoted singletons; the patch's own path for top-level singletons; `[]` for whole-DB replacement.
- `:section-kind` — one of `:added` / `:removed` / `:modified`.
- `:patches` — a sub-sequence of the input list.

## Algorithm

Mirrors the Xray pass (`section_grouping.cljc` §3.1.1), recast over patches:

1. **Trivial cases first.** Empty patches → `[]`. A single root-path `:assoc` (`[[] :assoc <full-db>]`, the `reset-frame-db!` signature) → one `[]`-headed `:modified` section.

2. **Sort by path-as-`pr-str`.** Makes the cluster algorithm deterministic and reorder-tolerant. (`collect-patches` already emits in walk order; the sort pins it.)

3. **Coalesce siblings.** Walk the sorted patches; for each, take the longest common prefix with the running cluster. Fold the patch in when that prefix is non-empty AND both paths sit within `max-coalesce-depth` (default 3) levels of it (narrowing the cluster prefix to the common ancestor). Otherwise start a new cluster. Root coalescence (empty common prefix) is reserved for the whole-DB case — two unrelated root-key changes each get their own cluster rather than collapsing to a `[]`-headed section that would defeat the breadcrumb premise.

4. **Promote singletons to parent.** A cluster with exactly one patch at a path deeper than 1 segment heads at the patch's *parent* (a change to `[:user :prefs :theme]` heads as `[:user :prefs]`). Top-level singletons (path length 1) keep their full path.

5. **Classify each section** (`:section-kind`):
   - all `:dissoc` → `:removed`.
   - all `:assoc` AND every patch is exactly one segment deeper than `:section-path` (a newly-introduced container) → `:added`.
   - otherwise → `:modified` (the conservative default; a mix of inserts / changes / deletes). A more precise `:added` vs `:modified` split would need `:db-before` to detect "was this path previously absent?" — patches alone can't tell, so the rule errs toward `:modified`.

## Ordering

Sections sort by `:section-path` (lexicographic, `pr-str`-keyed) — stable across re-renders, so the same cascade always produces the same section order.

## Cost

- Sort: O(N log N) where N = patch count.
- Coalesce / promote / classify: O(N).

All passes are linear in patch count — negligible vs the walk that produced the patches. Pure data → data; `.cljc`.

## Round-trip invariant

`sections->patches` is the lossless inverse of `group-patches-into-sections` — `(apply-patches db-before (sections->patches (group-patches-into-sections patches)))` reproduces the value `(apply-patches db-before patches)` produces (modulo within-section order, which is stable per the sort). `diff-encode`'s decoder relies on this: it flattens `:sections` → patch list, then replays.

## See also

- [`README.md`](README.md) — the per-namespace index this doc is part of.
- [`diff-encode.md`](diff-encode.md) — the diff transform that produces the patch list this ns groups, and the `section-schema` Malli gate that pins the section shape at the wire boundary.
- rf2-qeous — the bead that landed the path-headed cluster projection.
- rf2-gfxmk / rf2-abts7 — Xray's panel `sections-per-cluster` decomposition this projection mirrors.
