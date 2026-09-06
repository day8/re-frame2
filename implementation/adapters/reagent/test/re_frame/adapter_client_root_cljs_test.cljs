(ns re-frame.adapter-client-root-cljs-test
  "rf2-k5r9t — the Reagent adapter's reusable client root: `client-root`,
  `render!`, `unmount!`. Pins the call sequence at `reagent.dom.client` by
  spying through `with-redefs` on `create-root` / `render` / `hydrate-root`
  / `unmount`, the way `re-frame.adapter-render-cljs-test` pins the one-shot
  `:render` slot (no DOM; :node-test). The real-DOM half of the contract —
  the same node surviving a re-render, server markup adopted — is
  `re-frame.adapter-client-root-dom-cljs-test`.

  The five behaviours the bead's acceptance names:

    1. an allocated handle does no DOM work;
    2. a cold first render calls create-root once; later renders reuse the
       IDENTICAL Root through the plain render op and call neither
       constructor again;
    3. a hydrating first render calls hydrate-root once; a hydrated root is
       updated with the plain render op, never hydrated again;
    4. explicit unmount is idempotent — the underlying unmount is reached
       once — and a render after it mounts afresh;
    5. `dispose-adapter!` releases every still-live handle exactly once,
       an already-unmounted handle is not released again, and neither is
       released a second time by a later `unmount!`.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [reagent.dom.client :as rdc]
            [re-frame.frame :as rf.frame]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.adapter.reagent :as rf.adapter.reagent]))

;; Cold-start fixture (mirrors the slim dispose-drain pins): the unit under
;; test spans render, unmount and the adapter drain, so install the adapter
;; here and wipe frames so the drain's sub-cache walk sees an empty registry.
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

(defn- spy-rdc!
  "Install call-recording stubs over the four `reagent.dom.client` fns for
  the extent of `body-fn`. `roots` is the queue of fake Roots successive
  constructor calls hand out. Returns the recorded calls."
  [roots body-fn]
  (let [calls (atom [])
        queue (atom roots)
        next! (fn [] (let [[r] @queue] (swap! queue rest) r))]
    (with-redefs [rdc/create-root  (fn
                                     ([m]   (swap! calls conj [:create-root m]) (next!))
                                     ([m _] (swap! calls conj [:create-root m]) (next!)))
                  rdc/render       (fn
                                     ([r t]     (swap! calls conj [:render r t]) nil)
                                     ([r t _]   (swap! calls conj [:render r t]) nil)
                                     ([r t _ _] (swap! calls conj [:render r t]) nil))
                  rdc/hydrate-root (fn
                                     ([m t]   (swap! calls conj [:hydrate-root m t]) (next!))
                                     ([m t _] (swap! calls conj [:hydrate-root m t]) (next!)))
                  rdc/unmount      (fn [r] (swap! calls conj [:unmount r]) nil)]
      (body-fn))
    @calls))

(defn- of-kind [calls k] (filter #(= k (first %)) calls))

;; ---- 1. inert allocation ---------------------------------------------------

(deftest client-root-does-no-dom-work
  (testing "allocating a handle touches none of the Root API"
    (let [calls (spy-rdc! [] (fn [] (rf.adapter.reagent/client-root)))]
      (is (empty? calls) "client-root is inert: no create/hydrate/render/unmount"))))

;; ---- 2. cold first render, later renders update the same Root -------------

(deftest cold-first-render-creates-once-later-renders-update-the-same-root
  (testing "first render! creates the Root once; the next two renders reuse
            the identical Root through rdc/render and call no constructor"
    (let [root  (fake-root :cold)
          mount #js {:rf-test-mount :cold}
          calls (spy-rdc! [root (fake-root :never)]
                  (fn []
                    (let [h (rf.adapter.reagent/client-root)]
                      (rf.adapter.reagent/render! h [:div "v1"] mount)
                      (rf.adapter.reagent/render! h [:div "v2"] mount)
                      (rf.adapter.reagent/render! h [:div "v3"] mount))))]
      (is (= [[:create-root mount]] (of-kind calls :create-root))
          "create-root called exactly once, with the mount point")
      (is (empty? (of-kind calls :hydrate-root))
          "a cold mount never hydrates")
      (is (= [[:render root [:div "v1"]]
              [:render root [:div "v2"]]
              [:render root [:div "v3"]]]
             (of-kind calls :render))
          "every render goes through rdc/render against the SAME Root, in order")
      (is (every? #(identical? root (second %)) (of-kind calls :render))
          "later renders update the identical Root object the first render created")
      (is (= :create-root (ffirst calls))
          "the Root exists before the first render into it"))))

;; ---- 3. hydrating first render, later renders update (never re-hydrate) --

(deftest hydrating-first-render-hydrates-once-later-renders-update
  (testing "render! with {:hydrate? true} hydrates once; later renders update
            the hydrated Root through rdc/render and never hydrate again"
    (let [root  (fake-root :hydrated)
          mount #js {:rf-test-mount :hydrated}
          calls (spy-rdc! [root (fake-root :never)]
                  (fn []
                    (let [h (rf.adapter.reagent/client-root)]
                      (rf.adapter.reagent/render! h [:div "ssr"] mount {:hydrate? true})
                      (rf.adapter.reagent/render! h [:div "v2"] mount {:hydrate? true})
                      (rf.adapter.reagent/render! h [:div "v3"] mount))))]
      (is (= [[:hydrate-root mount [:div "ssr"]]] (of-kind calls :hydrate-root))
          "hydrate-root called exactly once, with the mount point and the first tree")
      (is (empty? (of-kind calls :create-root))
          "a hydrating mount never calls create-root")
      (is (= [[:render root [:div "v2"]] [:render root [:div "v3"]]]
             (of-kind calls :render))
          "the two later renders update the hydrated Root with the plain render op —
           even when the caller keeps passing {:hydrate? true}"))))

;; ---- 4. explicit unmount is idempotent; a later render mounts afresh -------

(deftest unmount-is-idempotent-and-a-later-render-mounts-afresh
  (testing "unmount! twice reaches rdc/unmount once; render! afterwards
            creates a new Root rather than rendering into the released one"
    (let [root-1 (fake-root :first)
          root-2 (fake-root :second)
          mount  #js {:rf-test-mount :again}
          calls  (spy-rdc! [root-1 root-2]
                   (fn []
                     (let [h (rf.adapter.reagent/client-root)]
                       (rf.adapter.reagent/render! h [:div "v1"] mount)
                       (rf.adapter.reagent/unmount! h)
                       (rf.adapter.reagent/unmount! h)
                       (rf.adapter.reagent/render! h [:div "v2"] mount))))]
      (is (= [[:unmount root-1]] (of-kind calls :unmount))
          "the underlying unmount is reached exactly once for the first Root")
      (is (= [[:create-root mount] [:create-root mount]] (of-kind calls :create-root))
          "the render after unmount creates a fresh Root")
      (is (= [[:render root-1 [:div "v1"]] [:render root-2 [:div "v2"]]]
             (of-kind calls :render))
          "the post-unmount render goes into the NEW Root, not the released one"))))

;; ---- 5. dispose-adapter! releases every still-live handle once -----------

(deftest dispose-adapter-releases-live-handles-once
  (testing "the drain releases each still-live handle's Root exactly once;
            an already-unmounted handle is not released again; a later
            unmount! on either handle reaches React no further time"
    (let [root-live (fake-root :live)
          root-gone (fake-root :gone)
          calls     (spy-rdc! [root-live root-gone]
                      (fn []
                        (let [live (rf.adapter.reagent/client-root)
                              gone (rf.adapter.reagent/client-root)]
                          (rf.adapter.reagent/render! live [:div "live"] #js {})
                          (rf.adapter.reagent/render! gone [:div "gone"] #js {})
                          (rf.adapter.reagent/unmount! gone)
                          (rf.substrate.adapter/dispose-adapter!)
                          ;; Post-drain: both handles are already released.
                          (rf.adapter.reagent/unmount! live)
                          (rf.adapter.reagent/unmount! gone))))]
      (is (= 1 (count (filter #(identical? root-live (second %)) (of-kind calls :unmount))))
          "the still-live handle's Root was released exactly once (by the drain)")
      (is (= 1 (count (filter #(identical? root-gone (second %)) (of-kind calls :unmount))))
          "the explicitly-unmounted handle's Root was released exactly once (by unmount!)")
      (is (= 2 (count (of-kind calls :unmount)))
          "no Root was released a second time by the later unmount! calls"))))
