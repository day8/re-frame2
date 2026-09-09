# re-frame.hicasso.motion

The optional motion module. One head, and the module owns exactly one thing about
an animation: **retention**.

```clojure
(:require [re-frame.hicasso :as h]
          [re-frame.hicasso.motion :as motion])
```

Motion belongs to CSS, to the compositor and to the host. CSS declares the
transition, the compositor interpolates it, and a native host owns the high-rate
mechanics. Exactly one gap sits between those owners and is not one CSS can close:
React removes a node the instant its data leaves app-db, and a node that is gone
cannot fade. So `presence` keeps that node for a stated `:timeout-ms` and applies
the phase the author wrote on it. That is the whole module.

There is **no** easing, spring or keyframe API, no timeline or transition
orchestrator, no `transitionend` subscription, and no gesture, drag or
motion-value state. Those belong to the platform, and the way to reach them is
`h/defhost`. This is not an animation system and is not on its way to becoming
one.

This page is the manifest-tracked index of the module's public vars; the marker
keywords and the phase table are taught in
[Motion and presence](../core/hicasso/12-motion-and-presence.md).

## The head

### `presence`

- **Kind**: Var (view)
- **Signature**:
  ```clojure
  [motion/presence {:timeout-ms ms} keyed-child …]
  ```
- **Description**: Retains exiting **keyed** children for `:timeout-ms`, merging
  each child's own `::motion/mounting` / `::motion/unmounting` override map into
  it while it is in that phase — into an element's attributes, or into a view's
  props, the same map either way.
  - It inserts **no wrapper node** and stamps no `data-*`: every child it renders
    is the author's own node with the author's own attributes merged.
  - `:timeout-ms` is **mandatory**. It is the retention length and the hard
    terminal bound at once, so a child leaves on time whether or not any CSS ran.
  - Per-frame work is zero: a transition costs one timer per outstanding deadline
    and nothing between frames. A key that returns while it is exiting cancels —
    it goes back to present on the node it already had, with no remount and no
    restarted exit.
- **Example**:
  ```clojure
  (h/defview toast-tray [_]
    [motion/presence {:timeout-ms 300}
     (for [t (h/sub [:toasts/visible])]
       [:div.toast {:key                (:id t)
                    ::motion/unmounting {:class "toast toast--exit"
                                         :inert true :aria-hidden true}}
        (:message t)])])
  ```

## See also

- [Motion and presence](../core/hicasso/12-motion-and-presence.md) — the chapter
  that governs the surface, and the marker keywords
- [Hicasso API reference](../core/hicasso/api-reference.md) — the full contract
- [`re-frame.hicasso`](re-frame.hicasso.md) — the door
