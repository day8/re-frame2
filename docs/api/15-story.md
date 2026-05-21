# 15 — Story / variants / workspaces (post-v1)

Story is the post-v1 library that turns *registered variants* into a navigable, queryable, snapshot-identified surface for design review, visual regression, and pair-programming workflows. A **variant** is a view + arguments + setup — a single rendered state worth pinning to a URL. A **story** is a cluster of variants. A **workspace** is a grid view of variants you've assembled for side-by-side review.

Story is **post-v1 lib** — the design lives in [Spec 007](../../spec/007-Stories.md) and ships in `day8/re-frame2-story`. The surface below is what the library exposes; the rest of Story (UI shell, MCP server, recorder, QR-share, time-travel mini-player) is documented in the [Story guide](../story/index.md).

This chapter is a reference. If you want narrative — "what's the workflow, what does the shell look like, how do I record a variant" — start at the [Story guide](../story/index.md).

## Registration

| API | M/Fn | Signature | Status | Intuition |
|---|---|---|---|---|
| `reg-story` | M | `(reg-story id metadata)` | post-v1 lib | Register a story (a cluster of variants under one heading). |
| `reg-variant` | M | `(reg-variant id metadata)` | post-v1 lib | Register one variant — view, args, setup events, decorators. |
| `reg-workspace` | M | `(reg-workspace id metadata)` | post-v1 lib | Register a workspace — a curated grid of variants for side-by-side review. |
| `reg-tag` | M | `(reg-tag id metadata)` | post-v1 lib | Register a tag (free-form classification — `#{:auth-required :empty-state :error}`). Tags filter the variant catalogue. |
| `reg-decorator` | M | `(reg-decorator id metadata)` | post-v1 lib | Register a decorator — a function that wraps a variant's render (locale, theme, mock-API context). |
| `reg-story-panel` | M | `(reg-story-panel id metadata)` | post-v1 lib | Register a custom panel in the Story shell — the inspection / control panes that sit beside the rendered variant. |

## Running variants

| API | M/Fn | Signature | Status | Intuition |
|---|---|---|---|---|
| `run-variant` | Fn | `(run-variant variant-id)` → result map | post-v1 lib | Materialise the variant — run its setup events, render its view, return the result map. One-shot; no live updates. |
| `watch-variant` | Fn | `(watch-variant variant-id)` → live-updating result map | post-v1 lib | Like `run-variant` but the result map updates live as `app-db` changes. Use for live shells; use `run-variant` for one-shot screenshots. |
| `reset-variant` | Fn | `(reset-variant variant-id)` | post-v1 lib | Reset the variant's frame to its initial setup. The Story shell calls this when the user clicks "reset" on a variant. |

## Discovery

| API | M/Fn | Signature | Status | Intuition |
|---|---|---|---|---|
| `variants-with-tags` | Fn | `(variants-with-tags tag-set)` → seq of variant ids | post-v1 lib | Filter the catalogue. `(variants-with-tags #{:cart :empty-state})` returns every cart-related empty-state variant. |
| `snapshot-identity` | Fn | `(snapshot-identity variant-id)` → `{:variant-id ... :content-hash "..."}` | post-v1 lib | The variant's snapshot identity — the variant id plus a content-hash over its setup. Used by QR-share and the Story recorder to identify what the user is looking at without leaking the variant's args. |
| `story-view` | Fn | `(story-view variant-id)` → hiccup | post-v1 lib | Render the variant directly to hiccup. Use in custom shells that need to embed variants without the full Story chrome. |

## See also

- [Spec 007 — Stories](../../spec/007-Stories.md) — the normative source.
- [Story guide](../story/index.md) — narrative coverage of the workflow, the shell, the recorder, and the MCP integration.
- [Guide ch.15 — Testing](../guide/15-testing.md) — the assertion-shaped `:rf.assert/*` event family that pairs with `reg-variant`'s `:play` block.
