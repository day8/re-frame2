# re-frame.fresco.motion

Use this module when children should animate as they leave. React removes a node
the instant its data leaves `app-db`, and a node that is gone cannot fade, so
`presence` keeps exiting children for a stated `:timeout-ms` and applies the phase
attributes you wrote on them. CSS declares the transition and the browser runs it.

It is an optional namespace: `re-frame.fresco` does not require it, so an
application that never requires it carries none of its code. It is ClojureScript
only; the namespace does not exist on the JVM.

```clojure
(:require [re-frame.fresco :as h]
          [re-frame.fresco.motion :as motion])
```

Retention is the whole module. There is no easing, spring or keyframe API, no
timeline or transition orchestrator, no `transitionend` subscription, and no
gesture, drag or motion-value state; reach those through a React component with
`h/defhost`. [Motion and presence](../core/fresco/12-motion-and-presence.md) teaches
the marker keywords and the phase table.

## The head

### `presence`

- **Kind**: component (Fresco head)
- **Signature**:
  ```clojure
  [motion/presence {:timeout-ms ms} keyed-child …]
  ```
- **Description**: Keeps exiting keyed children rendered for `:timeout-ms`, and
  merges each child's own `::motion/mounting` or `::motion/unmounting` map into it
  while it is in that phase: into an element's attributes, or into a view's props,
  the same map either way.
    - Every child must be a hiccup vector with a `:key` in its props map: the key is
      how `presence` recognises a child across renders.
    - Write the phase maps on the child you hand to `presence`, not inside a child
      view's body, where `presence` cannot see them. A view child receives the map
      merged into its props, under names you choose:
      `[toast-card {:key id :toast t ::motion/unmounting {:exiting? true}}]`.
    - It inserts no wrapper node and adds no `data-*` attribute: every child it
      renders is your own node with your own attributes merged.
    - `:timeout-ms` is required. It is both the retention length and a hard upper
      bound, so a child leaves on time whether or not any CSS ran.
    - It does no per-frame work: a transition costs one timer per outstanding
      deadline. A key that returns while it is exiting goes back to present on the
      node it already had, with no remount and no restarted exit.
    - `::motion/mounting` applies while a child is entering, but an enter animation
      driven by it alone can race the first paint. Animate entry with a CSS
      animation on insertion or `@starting-style` instead.
    - It dispatches no events of its own when a child enters or leaves.
    - A phase map is merged over the child's own attributes or props and wins where
      they overlap, so a `:class` in it replaces the child's `:class`. `:key` and
      `:ref` in a phase map are ignored.
    - `nil` and `false` children render nothing and need no key, so
      `(when show? [:div.banner {:key :banner} …])` is a legal child.
    - While the page is hydrating, a child already on screen starts in the present
      phase, so its `::motion/mounting` map is not applied over server-rendered
      markup.
- **Errors**:
    - `:rf.error/fresco-presence-timeout-required` when `:timeout-ms` is missing or
      not a positive number.
    - `:rf.error/fresco-presence-child-unkeyed` on a child that is not a hiccup
      vector with a `:key`.
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

- [Fresco API reference](../core/fresco/api-reference.md) — every Fresco name, with
  the chapter that teaches it.
- [`re-frame.fresco`](re-frame.fresco.md) — `h/defview` and `h/defhost`.
