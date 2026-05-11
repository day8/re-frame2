# Dropped from v1 (10x-specific surfaces with no re-frame2 equivalent)

The v1 `re-frame-pair` skill carried a few surfaces that have no direct re-frame2 equivalent today. They have been **dropped** rather than ported:

- **`subs/live` (10x's "currently subscribed query vectors" view)** — replaced by `subs/cache` (`rf/sub-cache`), which is the public Tool-Pair-pinned shape `{query-v {:value v :ref-count n}}`. Same need, different surface.
- **10x's internal epoch-buffer accessor + ring-rollover detection** — gone; replaced by `(rf/epoch-history frame-id)` which is bounded and self-describing (size = `(count history)`, depth = `(:depth (epoch/current-config))`).
- **10x's internal undo / step-back navigation** — gone; replaced by first-class `(rf/restore-epoch frame-id epoch-id)` with six documented failure modes.
- **`re-com-debug-disabled` heuristic** — kept (re-com is still a valid source-coord source), but the source-coord story now leads with re-frame2's own `:annotate-dom?` annotation; re-com's `data-rc-src` is a fallback rather than the only path.
- **`trace-enabled?` discovery check** — replaced by `interop/debug-enabled?` (the `goog.DEBUG` mirror per Spec 009 §Production builds). Same gate, framework-canonical name.
- **Version-floor enforcement against re-frame-10x / re-com / re-frame** — gone (no re-frame-10x dependency; re-com is optional; re-frame2's version is implicit in the loaded ns).

If during real-world use a surface re-frame2 currently lacks would unblock a recipe (e.g. successful-fx attribution in `:effects` projection, or a stable `:render-key` shape per rf2-t5tx), file a `bd` bead against the spec rather than working around in this skill.
