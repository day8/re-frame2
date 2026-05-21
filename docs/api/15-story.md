# 15 — Story / variants / workspaces (post-v1)

Story is the post-v1 library that turns *registered variants* into a navigable, queryable, snapshot-identified surface for design review, visual regression, and pair-programming workflows. A **variant** is a view + arguments + setup — a single rendered state worth pinning to a URL. A **story** is a cluster of variants. A **workspace** is a grid view of variants you've assembled for side-by-side review.

Story is **post-v1 lib** — the design lives in [Spec 007](../../spec/007-Stories.md) and ships in `day8/re-frame2-story`. The surface below is what the library exposes; the rest of Story (UI shell, MCP server, recorder, QR-share, time-travel mini-player) is documented in the [Story guide](../story/index.md).

This chapter is a reference. If you want narrative — "what's the workflow, what does the shell look like, how do I record a variant" — start at the [Story guide](../story/index.md).

## Registration

### `reg-story`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-story id metadata)
  ```
- **Status**: post-v1 lib
- **Description**: Register a story (a cluster of variants under one heading).

### `reg-variant`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-variant id metadata)
  ```
- **Status**: post-v1 lib
- **Description**: Register one variant — view, args, setup events, decorators.

### `reg-workspace`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-workspace id metadata)
  ```
- **Status**: post-v1 lib
- **Description**: Register a workspace — a curated grid of variants for side-by-side review.

### `reg-tag`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-tag id metadata)
  ```
- **Status**: post-v1 lib
- **Description**: Register a tag (free-form classification — `#{:auth-required :empty-state :error}`). Tags filter the variant catalogue.

### `reg-decorator`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-decorator id metadata)
  ```
- **Status**: post-v1 lib
- **Description**: Register a decorator — a function that wraps a variant's render (locale, theme, mock-API context).

### `reg-story-panel`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-story-panel id metadata)
  ```
- **Status**: post-v1 lib
- **Description**: Register a custom panel in the Story shell — the inspection / control panes that sit beside the rendered variant.

## Running variants

### `run-variant`

- **Kind**: function
- **Signature**:
  ```clojure
  (run-variant variant-id) → result map
  ```
- **Status**: post-v1 lib
- **Description**: Materialise the variant — run its setup events, render its view, return the result map. One-shot; no live updates.

### `watch-variant`

- **Kind**: function
- **Signature**:
  ```clojure
  (watch-variant variant-id) → live-updating result map
  ```
- **Status**: post-v1 lib
- **Description**: Like `run-variant` but the result map updates live as `app-db` changes. Use for live shells; use `run-variant` for one-shot screenshots.

### `reset-variant`

- **Kind**: function
- **Signature**:
  ```clojure
  (reset-variant variant-id)
  ```
- **Status**: post-v1 lib
- **Description**: Reset the variant's frame to its initial setup. The Story shell calls this when the user clicks "reset" on a variant.

## Discovery

### `variants-with-tags`

- **Kind**: function
- **Signature**:
  ```clojure
  (variants-with-tags tag-set) → seq of variant ids
  ```
- **Status**: post-v1 lib
- **Description**: Filter the catalogue. `(variants-with-tags #{:cart :empty-state})` returns every cart-related empty-state variant.

### `snapshot-identity`

- **Kind**: function
- **Signature**:
  ```clojure
  (snapshot-identity variant-id) → {:variant-id ... :content-hash "..."}
  ```
- **Status**: post-v1 lib
- **Description**: The variant's snapshot identity — the variant id plus a content-hash over its setup. Used by QR-share and the Story recorder to identify what the user is looking at without leaking the variant's args.

### `story-view`

- **Kind**: function
- **Signature**:
  ```clojure
  (story-view variant-id) → hiccup
  ```
- **Status**: post-v1 lib
- **Description**: Render the variant directly to hiccup. Use in custom shells that need to embed variants without the full Story chrome.

## See also

- [Spec 007 — Stories](../../spec/007-Stories.md) — the normative source.
- [Story guide](../story/index.md) — narrative coverage of the workflow, the shell, the recorder, and the MCP integration.
- [Guide ch.15 — Testing](../guide/15-testing.md) — the assertion-shaped `:rf.assert/*` event family that pairs with `reg-variant`'s `:play` block.
