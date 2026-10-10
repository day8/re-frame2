(ns re-frame.adapter-client-root-cljs-test
  "The Reagent adapter's reusable client root: `client-root`,
  `render!`, `unmount!`. Pins the call sequence at `reagent.dom.client` by
  spying through `with-redefs` on `create-root` / `render` / `unmount`, and
  on react-dom/client's `hydrateRoot`, which the adapter calls itself to pass
  root options, the way `re-frame.adapter-render-cljs-test` pins the one-shot
  `:render` slot (no DOM; :node-test). The real-DOM half of the contract —
  the same node surviving a re-render, server markup adopted — is
  `re-frame.adapter-client-root-dom-cljs-test`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [reagent.dom.client :as rdc]
            ["react-dom/client" :as react-dom-client]
            [re-frame.frame :as rf.frame]
            [re-frame.substrate.spine :as rf.substrate.spine]
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

(defn- fake-root? [r] (some? (unchecked-get r "rf-test-root-tag")))

(defn- spy-rdc!
  "Install call-recording stubs over `reagent.dom.client`'s `create-root`,
  `render` and `unmount`, and over react-dom/client's `hydrateRoot`, for the
  extent of `body-fn`. `roots` is the queue of fake Roots successive
  constructor calls hand out. Returns the recorded calls.

  `rdc/render` records only renders into a fake Root. The adapter also calls
  it to build the element it hydrates, against a root of its own, and that
  call runs through to Reagent; its 2- and 3-arity bodies re-enter through the
  Var, so every arity passes it on."
  [roots body-fn]
  (let [calls        (atom [])
        queue        (atom roots)
        next!        (fn [] (let [[r] @queue] (swap! queue rest) r))
        render       rdc/render
        hydrate-root (.-hydrateRoot react-dom-client)]
    (set! (.-hydrateRoot react-dom-client)
          (fn [m _element _options] (swap! calls conj [:hydrate-root m]) (next!)))
    (try
      (with-redefs [rdc/create-root  (fn
                                       ([m]   (swap! calls conj [:create-root m]) (next!))
                                       ([m _] (swap! calls conj [:create-root m]) (next!)))
                    rdc/render       (fn
                                       ([r t]     (if (fake-root? r) (swap! calls conj [:render r t]) (render r t)) nil)
                                       ([r t c]   (if (fake-root? r) (swap! calls conj [:render r t]) (render r t c)) nil)
                                       ([r t c s] (if (fake-root? r) (swap! calls conj [:render r t]) (render r t c s)) nil))
                    rdc/unmount      (fn [r] (swap! calls conj [:unmount r]) nil)]
        (body-fn))
      (finally
        (set! (.-hydrateRoot react-dom-client) hydrate-root)))
    @calls))

(defn- closer-child
  "The tree beneath a hydrating root's adoption-window closer, or `tree`
  itself when the closer does not wrap it."
  [tree]
  (if (and (vector? tree)
           (= :r> (first tree))
           (identical? rf.substrate.spine/adoption-window-closer (second tree)))
    (nth tree 3)
    tree))


(defn- of-kind [calls k] (filter #(= k (first %)) calls))

(defn- by-kind
  "The recorded calls grouped as [create-root hydrate-root render]."
  [calls]
  (mapv #(vec (of-kind calls %)) [:create-root :hydrate-root :render]))

;; ---- 1. cold first render, later renders update the same Root -------------

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
      (is (= [[[:create-root mount]]
              []
              [[:render root [:div "v1"]] [:render root [:div "v2"]] [:render root [:div "v3"]]]]
             (by-kind calls))
          "create-root once with the mount point, no hydration, and every render through rdc/render against the SAME Root, in order"))))

;; ---- 2. hydrating first render, later renders update (never re-hydrate) --

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
      (is (= [[]
              [[:hydrate-root mount]]
              [[:render root [:div "v2"]] [:render root [:div "v3"]]]]
             (update (by-kind calls) 2 (partial mapv (fn [[k r t]] [k r (closer-child t)]))))
          "hydrateRoot once, never create-root, and the later renders update the hydrated Root with the plain render op — even when the caller keeps passing {:hydrate? true}")
      (is (every? #(not= (nth % 2) (closer-child (nth % 2))) (of-kind calls :render))
          "every update renders through the window closer the hydration wrapped the tree in"))))

;; ---- 3. explicit unmount is idempotent; a later render mounts afresh -------

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
      (is (= [[[:create-root mount] [:create-root mount]]
              []
              [[:render root-1 [:div "v1"]] [:render root-2 [:div "v2"]]]]
             (by-kind calls))
          "the render after unmount creates a fresh Root and renders into it, not the released one"))))

;; ---- 4. dispose-adapter! releases every still-live handle once -----------

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
      (is (= [[:unmount root-gone] [:unmount root-live]] (of-kind calls :unmount))
          "unmount! released the gone Root, the drain the live one, and the later unmount! calls released nothing again"))))
