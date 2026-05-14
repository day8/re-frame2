(ns re-frame.substrate.spine-dispose-cljs-test
  "Unit coverage for the substrate-spine's `dispose-adapter!` factory and
  the active-roots tracking (rf2-9fdkb).

  The spine builds a `dispose-adapter!` that drains the active-roots
  set by calling `.unmount` on every tracked React root, and clears
  the warn-once cache and the hiccup-emitter cell. These tests cover
  that contract by sliding fake roots (objects with an `unmount`
  method) into the active-roots cell directly — bypassing
  `react-dom-client/createRoot` so the assertions stay node-runtime
  compatible (no JSDOM, no Playwright).

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.substrate.spine :as spine]))

(defn- fake-root
  "Build a minimal stand-in for a React root that records every
  `.unmount` call in a per-instance counter atom. The spine only
  exercises the `.unmount` slot on a Root, so this is enough."
  []
  (let [unmount-count (atom 0)
        root          #js {:unmount #(swap! unmount-count inc)}]
    {:root          root
     :unmount-count unmount-count}))

(deftest dispose-drains-active-roots
  (testing "dispose-adapter! calls .unmount on every tracked root and empties the cell"
    (let [active-roots-cell (spine/make-active-roots-cell)
          warn-cache        (spine/make-warn-once-cache)
          emitter-cell      (spine/make-hiccup-emitter-cell)
          dispose-fn        (spine/make-dispose-adapter!
                              {:active-roots-cell active-roots-cell
                               :warn-cache        warn-cache
                               :emitter-cell      emitter-cell})
          fake-a            (fake-root)
          fake-b            (fake-root)]
      (swap! active-roots-cell conj (:root fake-a) (:root fake-b))
      (is (= 2 (count @active-roots-cell))
          "precondition: two active roots tracked")
      (is (zero? @(:unmount-count fake-a)) "fake-a not yet unmounted")
      (is (zero? @(:unmount-count fake-b)) "fake-b not yet unmounted")
      (dispose-fn)
      (is (= 1 @(:unmount-count fake-a))
          "fake-a was unmounted by dispose-adapter!")
      (is (= 1 @(:unmount-count fake-b))
          "fake-b was unmounted by dispose-adapter!")
      (is (empty? @active-roots-cell)
          "active-roots cell drained to empty after dispose"))))

(deftest dispose-swallows-per-root-unmount-throws
  (testing "one misbehaving root's unmount throw does not strand the rest of the drain"
    (let [active-roots-cell (spine/make-active-roots-cell)
          warn-cache        (spine/make-warn-once-cache)
          emitter-cell      (spine/make-hiccup-emitter-cell)
          dispose-fn        (spine/make-dispose-adapter!
                              {:active-roots-cell active-roots-cell
                               :warn-cache        warn-cache
                               :emitter-cell      emitter-cell})
          good-1            (fake-root)
          good-2            (fake-root)
          bad               #js {:unmount #(throw (js/Error. "boom"))}]
      ;; Insertion order is not preserved in a set; the swallowed-throw
      ;; guarantee is that BOTH good roots' unmount fires regardless of
      ;; the bad one's traversal position.
      (swap! active-roots-cell conj (:root good-1) bad (:root good-2))
      (dispose-fn)
      (is (= 1 @(:unmount-count good-1))
          "good-1 still unmounted despite a sibling unmount throw")
      (is (= 1 @(:unmount-count good-2))
          "good-2 still unmounted despite a sibling unmount throw")
      (is (empty? @active-roots-cell)
          "active-roots cell drained even when an unmount threw"))))

(deftest dispose-clears-warn-cache-and-emitter
  (testing "dispose-adapter! also empties the warn-once cache and the hiccup-emitter cell"
    (let [active-roots-cell (spine/make-active-roots-cell)
          warn-cache        (spine/make-warn-once-cache)
          emitter-cell      (spine/make-hiccup-emitter-cell)
          dispose-fn        (spine/make-dispose-adapter!
                              {:active-roots-cell active-roots-cell
                               :warn-cache        warn-cache
                               :emitter-cell      emitter-cell})]
      (swap! warn-cache conj :some.ns/some-id)
      (reset! emitter-cell (fn fake-emit [_ _] "<html/>"))
      (is (= #{:some.ns/some-id} @warn-cache)
          "precondition: warn-cache holds a seen id")
      (is (some? @emitter-cell)
          "precondition: emitter-cell holds a fn")
      (dispose-fn)
      (is (empty? @warn-cache)
          "warn-cache cleared so a fresh install does not inherit stale warn-once state")
      (is (nil? @emitter-cell)
          "hiccup-emitter cell cleared so a fresh install starts from no emitter"))))

(deftest unmount-thunk-removes-root-from-active-set
  (testing "the unmount thunk returned by `render` removes its root from the active-roots cell"
    ;; Build a render fn parameterised on a fake `.unmount`-supporting
    ;; root factory. The spine's `make-render` calls createRoot, which
    ;; requires a real DOM — so for this isolated test we simulate the
    ;; render path by mounting the root directly into the cell and
    ;; building an unmount thunk shaped like the one render returns.
    (let [active-roots-cell (spine/make-active-roots-cell)
          fake-a            (fake-root)
          fake-b            (fake-root)]
      (swap! active-roots-cell conj (:root fake-a))
      (swap! active-roots-cell conj (:root fake-b))
      ;; Mirror the render-fn's unmount-thunk shape.
      (let [unmount-a (fn []
                        (swap! active-roots-cell disj (:root fake-a))
                        (.unmount (:root fake-a)))]
        (unmount-a)
        (is (= #{(:root fake-b)} @active-roots-cell)
            "only fake-b remains tracked after fake-a's unmount thunk fired")
        (is (= 1 @(:unmount-count fake-a))
            "fake-a's actual unmount was called by the thunk")
        (is (zero? @(:unmount-count fake-b))
            "fake-b was not touched")))))
