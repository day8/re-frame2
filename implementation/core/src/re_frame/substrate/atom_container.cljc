(ns re-frame.substrate.atom-container
  "`clojure.core/atom`-backed container primitives for the non-reactive
  plain-atom substrate adapter.

  Per Spec 006 §The adapter API contract the container quartet
  (`make-state-container` / `read-container` / `replace-container!` /
  `subscribe-container`) is identical for any adapter whose state slot is
  a bare atom — there is no React-shaped reactivity to layer in. The
  plain-atom (JVM / SSR) adapter references these directly in its adapter
  map.

  `make-derived-value` is NOT here: what a derived value implements is
  per-adapter — plain-atom reifies `IDisposable` under CLJS for sub-cache
  ref-count symmetry.")

(defn make-state-container [initial-value]
  (atom initial-value))

(defn read-container [container]
  @container)

(defn replace-container! [container new-value]
  (reset! container new-value)
  nil)

(defn subscribe-container
  "Watch `container`; invoke `(on-change prev next)` on every change.
  Returns a zero-arg unsubscribe thunk. The watch key is gensym'd so
  multiple subscriptions on the same container don't collide."
  [container on-change]
  (let [k (gensym "rf-atom-container-sub-")]
    (add-watch container k (fn [_ _ prev nu] (on-change prev nu)))
    (fn unsubscribe [] (remove-watch container k))))
