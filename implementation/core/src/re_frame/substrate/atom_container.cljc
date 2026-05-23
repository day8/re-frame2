(ns re-frame.substrate.atom-container
  "Shared `clojure.core/atom`-backed container primitives for the
  non-reactive substrate adapters (plain-atom, test-react).

  Per Spec 006 §The adapter API contract the container quartet
  (`make-state-container` / `read-container` / `replace-container!` /
  `subscribe-container`) is identical for any adapter whose state slot is
  a bare atom — there is no React-shaped reactivity to layer in. Both the
  plain-atom (JVM / SSR) and test-react (unit-test) adapters reference
  these directly in their adapter maps so the byte-identical quartet lives
  in one place.

  Note `make-derived-value` is NOT shared — plain-atom reifies
  `IDisposable` under CLJS for sub-cache ref-count symmetry, while
  test-react deliberately does not (rf2-pyp3n). That divergence is real
  and stays per-adapter.")

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
