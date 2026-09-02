# re-frame.hicasso.native

The two React hooks that join a React **island** to the Hicasso frame it is
mounted in — and nothing else.

```clojure
(:require [re-frame.hicasso.native :as n])
```

An island is a UIx `defui` or a raw React function component, mounted through
`h/defhost` or `[:>]`. It requires this namespace only when it needs Hicasso
state, and nothing in `re-frame.hicasso` requires it, so an application with no
island carries none of it. React's own hooks are reached by direct `["react"]`
interop and none are wrapped here: what React cannot supply is the frame, so the
frame is all these two supply.

Both are **real React hooks** — top level of the component, unconditional — and
both refuse with `:rf.error/no-frame-context` when rendered outside every frame.
The islands themselves are taught in
[The native tier](../core/hicasso/10-native-tier.md).

## The hooks

### `use-sub`

- **Kind**: function (React hook)
- **Signature**:
  ```clojure
  (n/use-sub query-v)
  ```
- **Description**: The current value of the subscription `query-v` names, read
  under the frame this island is mounted in. The island counterpart to `h/sub` —
  and one call per read, so two calls are **two** subscriptions where a `defview`
  body's several `h/sub` reads are one.
  - It hands `useSyncExternalStore` the same `subscribe` and `getSnapshot` a
    boundary reading this key gets, so the read builds the same cell, joins the
    same reader membership and residue census, wakes on the same commit, and
    appears in the same `re-frame.hicasso.tool` rosters Xray reads.
  - A re-render that changed no read performs no re-subscribe; unmount releases
    what mount acquired, StrictMode's double mount included.
  - A commit observed through it is a **blocking** update — React's rule for an
    external store — so nothing here is transition-aware, and it is not a door to
    a promise-driven resource.
- **Example**:
  ```clojure
  (defui ticker [{:keys [sym]}]
    ($ :span (n/use-sub [:quote/price sym])))
  ```

### `use-frame`

- **Kind**: function (React hook)
- **Signature**:
  ```clojure
  (n/use-frame)
  ```
- **Description**: Frame-locked operations for the frame this island is mounted
  in — `rf/capture-frame`'s bundle,
  `{:frame :dispatch :dispatch-sync :subscribe}`.
  - The frame is the surrounding tree's, the one React context the boundary shell
    reads, and no argument reaches another; for a **named** frame call
    `(rf/capture-frame frame-id)` directly.
  - The map is the same object on every render under one frame **incarnation**, so
    it is safe in effect deps and safe to close over. Destroy the frame and
    recreate it under the same id and the next render gets the successor's ops,
    while a callback still holding the predecessor's is refused by core's
    `:rf.error/frame-destroyed` fence.
- **Example**:
  ```clojure
  (defui col-resizer [_]
    (let [{:keys [dispatch]} (n/use-frame)]
      ($ :div {:on-pointer-up (fn [_] (dispatch [:col/commit]))})))
  ```

## See also

- [The native tier](../core/hicasso/10-native-tier.md) — islands, and when to
  reach for one
- [Hicasso API reference](../core/hicasso/api-reference.md) — the full contract
- [`re-frame.hicasso`](re-frame.hicasso.md) — the door, including `h/defhost`
