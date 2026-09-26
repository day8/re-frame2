# re-frame.fresco.native

Use these two hooks in a React island — a UIx `defui` or a plain React function
component mounted inside a Fresco tree through `h/defhost` or `[:>]` — to read
subscriptions and dispatch in the Fresco frame the island is mounted under. Write a
region as an island when it needs React itself: its own hooks, a vendor widget
that keeps its own state, or pointer handling that updates on every move. An
island fed only by props needs neither hook.

It is an optional namespace: nothing in `re-frame.fresco` requires it, so an
application with no island carries none of it. It is ClojureScript only: on the JVM
the namespace loads but defines neither hook.

```clojure
(:require [re-frame.fresco.native :as n])
```

React's own hooks are used directly through `["react"]`; this namespace wraps none
of them and supplies only the frame. Both hooks are real React hooks, so call them
unconditionally at the top level of the component, and both raise
`:rf.error/no-frame-context` when rendered outside every frame.

Both read the frame from React context only — the nearest `frame-provider` or
`frame-root` above — so a `with-frame` scope around a synchronous render does not
reach them. The UIx adapter's [`use-sub` and
`use-frame`](re-frame.adapter.uix.md#hooks) read the same context, so a component
written against either pair finds the same frame.
[Islands](../core/fresco/10-native-tier.md) teaches them in full.

## The hooks

### `use-sub`

- **Kind**: function (React hook)
- **Signature**:
  ```clojure
  (n/use-sub query-v) → current sub value
  ```
- **Description**: Returns the current value of the subscription `query-v`, read in
  the frame the island is mounted under, and re-renders the component when it
  changes. It is the island's counterpart to `h/sub`, except that each `use-sub`
  call is its own React store subscription, where all of a `defview` body's
  `h/sub` reads share the view's one.
    - It gives `useSyncExternalStore` the same `subscribe` and `getSnapshot` a Fresco
      view reading the same query gets, so it wakes on the same commit and shows up
      in Xray alongside the views' reads.
    - A re-render that changes no read does not re-subscribe, and unmount releases
      what mount acquired, including under StrictMode's double mount.
    - An update it observes is a blocking update, as React requires for an external
      store, so it is not transition-aware and is not a way to read a
      promise-driven resource.
    - An unregistered query reads `nil` and emits `:rf.error/no-such-sub`, and a
      subscription whose body throws reads `nil` and emits `:rf.error/sub-exception`,
      as `h/sub` does.
- **Example**:
  ```clojure
  (defui ticker [{:keys [sym]}]
    ($ :span (n/use-sub [:quote/price sym])))
  ```

### `use-frame`

- **Kind**: function (React hook)
- **Signature**:
  ```clojure
  (n/use-frame) → {:frame … :dispatch … :dispatch-sync … :subscribe …}
  ```
- **Description**: Returns the frame-locked ops map for the frame the island is
  mounted under, `{:frame :dispatch :dispatch-sync :subscribe}` — the same map
  `rf/capture-frame` returns.
    - It takes no argument and always reads the surrounding tree's frame. For a
      named frame, call `(rf/capture-frame frame-id)` directly.
    - The map is the same object on every render while the frame stays the same, so
      it is safe in effect deps and safe to close over. Destroy the frame and create
      another under the same id, and the next render gets the new frame's ops; a
      callback still holding the old ops does not reach the new frame: the call is
      dropped and emits `:rf.error/frame-destroyed` rather than throwing.
- **Example**:
  ```clojure
  (defui col-resizer [_]
    (let [{:keys [dispatch]} (n/use-frame)]
      ($ :div {:on-pointer-up (fn [_] (dispatch [:col/commit]))})))
  ```

## See also

- [Fresco API reference](../core/fresco/api-reference.md) — every Fresco name, with
  the chapter that teaches it.
- [`re-frame.fresco`](re-frame.fresco.md) — `h/defhost` and `h/sub`.
