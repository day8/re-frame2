(ns re-frame.adapter-unmount-idempotent-cljs-test
  "rf2-k5r9t — Spec 006 §`render` says the returned unmount-fn is IDEMPOTENT
  (`(fn [] nil)` — idempotent; releases all resources). The shared
  `track-active-root!` tail behind every React-shaped adapter's `render`
  removed the root from the active set and called the underlying unmount
  op on EVERY call, so a second call reached React again. This pins the
  contract at the adapter's `:render` slot, spying on
  `reagent.dom.client/unmount` the way `re-frame.adapter-render-cljs-test`
  does (no DOM; :node-test).

  Two shapes, because the drain is the other caller of that unmount op:

    1. thunk, thunk — the second explicit call is a no-op.
    2. drain, thunk — a root `dispose-adapter!` already released is not
       released again by its own thunk afterwards.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [reagent.dom.client :as rdc]
            [re-frame.frame :as rf.frame]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.adapter.reagent :as rf.adapter.reagent]))

;; Cold-start fixture (mirrors the slim dispose-drain pins): the unit under
;; test is the render/unmount/dispose trio, so install the adapter here and
;; wipe frames so the drain's sub-cache walk sees an empty registry.
(defn- fresh-reagent [test-fn]
  (reset! rf.frame/frames {})
  ;; The adapter's active-root set is a namespace-level singleton, and
  ;; earlier suites in the shared bundle strand fake Roots in it (the
  ;; one-shot `:render` pins never unmount theirs). Drain them first, with
  ;; the host unmount stubbed so a stale fake Root goes quietly, so every
  ;; count below is this test's own.
  (with-redefs [rdc/unmount (fn [_] nil)]
    (rf.substrate.adapter/reset-lifecycle-state-for-tests!)
    (rf.substrate.adapter/install-adapter! rf.adapter.reagent/adapter)
    (rf.substrate.adapter/dispose-adapter!))
  (rf.substrate.adapter/reset-lifecycle-state-for-tests!)
  (rf.substrate.adapter/install-adapter! rf.adapter.reagent/adapter)
  (test-fn)
  (reset! rf.frame/frames {})
  (rf.substrate.adapter/reset-lifecycle-state-for-tests!))

(use-fixtures :each fresh-reagent)

(defn- fake-root [tag]
  #js {:rf-test-root-tag tag :unmount (fn [] nil)})

(deftest unmount-thunk-reaches-react-once
  (testing "calling the render slot's unmount thunk twice invokes
            rdc/unmount exactly once (Spec 006 §render: idempotent)"
    (let [unmounts (atom [])
          root     (fake-root :once)]
      (with-redefs [rdc/create-root  (fn ([_] root) ([_ _] root))
                    rdc/render       (fn ([_ _] nil) ([_ _ _] nil) ([_ _ _ _] nil))
                    rdc/hydrate-root (fn ([_ _] root) ([_ _ _] root))
                    rdc/unmount      (fn [r] (swap! unmounts conj r) nil)]
        (let [unmount ((:render rf.adapter.reagent/adapter) [:div "x"] #js {} nil)]
          (unmount)
          (is (= [root] @unmounts) "first call unmounts the Root")
          (unmount)
          (is (= [root] @unmounts)
              "second call is a no-op — the underlying unmount is NOT reached again"))))))

(deftest drained-root-is-not-unmounted-again-by-its-thunk
  (testing "dispose-adapter! releases a still-live root once; its thunk
            called afterwards does not reach rdc/unmount a second time"
    (let [unmounts (atom [])
          root     (fake-root :drained)]
      (with-redefs [rdc/create-root  (fn ([_] root) ([_ _] root))
                    rdc/render       (fn ([_ _] nil) ([_ _ _] nil) ([_ _ _ _] nil))
                    rdc/hydrate-root (fn ([_ _] root) ([_ _ _] root))
                    rdc/unmount      (fn [r] (swap! unmounts conj r) nil)]
        (let [unmount ((:render rf.adapter.reagent/adapter) [:div "x"] #js {} nil)]
          (rf.substrate.adapter/dispose-adapter!)
          (is (= [root] @unmounts) "the drain released the live root once")
          (unmount)
          (is (= [root] @unmounts)
              "the thunk of an already-drained root is a no-op"))))))
