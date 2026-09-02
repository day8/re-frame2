(ns re-frame.adapter.reagent-slim-client-root-cljs-test
  "reagent-slim twin of `re-frame.adapter-client-root-cljs-test` (rf2-k5r9t).
  The published slim artefact ships its adapter at the canonical
  `re-frame.adapter.reagent` ns, so the `client-root` / `render!` /
  `unmount!` trio must behave identically over `reagent2.dom.client`: this
  pins the same five behaviours by spying on the slim Root API through
  `with-redefs` (no DOM; :node-test).

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [reagent2.dom.client :as rdc]
            [re-frame.frame :as frame]
            [re-frame.substrate.adapter :as adapter]
            [re-frame.adapter.reagent-slim :as slim]))

(defn- fresh-slim [test-fn]
  (reset! frame/frames {})
  ;; Drain Roots earlier suites stranded in the slim adapter's singleton
  ;; active set (see the Reagent twin), so the counts are this test's own.
  (with-redefs [rdc/unmount (fn [_] nil)]
    (adapter/reset-lifecycle-state-for-tests!)
    (adapter/install-adapter! slim/adapter)
    (adapter/dispose-adapter!))
  (adapter/reset-lifecycle-state-for-tests!)
  (adapter/install-adapter! slim/adapter)
  (test-fn)
  (reset! frame/frames {})
  (adapter/reset-lifecycle-state-for-tests!))

(use-fixtures :each fresh-slim)

(defn- fake-root [tag]
  #js {:rf-test-root-tag tag :unmount (fn [] nil)})

(defn- spy-rdc!
  "Call-recording stubs over the slim Root API for the extent of `body-fn`
  (`reagent2.dom.client`'s published arities: create-root 1/2, render 2,
  hydrate-root 2/3, unmount 1). Returns the recorded calls."
  [roots body-fn]
  (let [calls (atom [])
        queue (atom roots)
        next! (fn [] (let [[r] @queue] (swap! queue rest) r))]
    (with-redefs [rdc/create-root  (fn
                                     ([m]   (swap! calls conj [:create-root m]) (next!))
                                     ([m _] (swap! calls conj [:create-root m]) (next!)))
                  rdc/render       (fn [r t] (swap! calls conj [:render r t]) nil)
                  rdc/hydrate-root (fn
                                     ([m t]   (swap! calls conj [:hydrate-root m t]) (next!))
                                     ([m t _] (swap! calls conj [:hydrate-root m t]) (next!)))
                  rdc/unmount      (fn [r] (swap! calls conj [:unmount r]) nil)]
      (body-fn))
    @calls))

(defn- of-kind [calls k] (filter #(= k (first %)) calls))

(deftest client-root-does-no-dom-work
  (testing "allocating a handle touches none of the Root API"
    (is (empty? (spy-rdc! [] (fn [] (slim/client-root)))))))

(deftest cold-first-render-creates-once-later-renders-update-the-same-root
  (testing "first render! creates once; later renders reuse the identical Root"
    (let [root  (fake-root :cold)
          mount #js {:rf-test-mount :cold}
          calls (spy-rdc! [root (fake-root :never)]
                  (fn []
                    (let [h (slim/client-root)]
                      (slim/render! h [:div "v1"] mount)
                      (slim/render! h [:div "v2"] mount)
                      (slim/render! h [:div "v3"] mount))))]
      (is (= [[:create-root mount]] (of-kind calls :create-root)))
      (is (empty? (of-kind calls :hydrate-root)))
      (is (= [[:render root [:div "v1"]] [:render root [:div "v2"]] [:render root [:div "v3"]]]
             (of-kind calls :render)))
      (is (every? #(identical? root (second %)) (of-kind calls :render))))))

(deftest hydrating-first-render-hydrates-once-later-renders-update
  (testing "render! with {:hydrate? true} hydrates once; later renders update"
    (let [root  (fake-root :hydrated)
          mount #js {:rf-test-mount :hydrated}
          calls (spy-rdc! [root (fake-root :never)]
                  (fn []
                    (let [h (slim/client-root)]
                      (slim/render! h [:div "ssr"] mount {:hydrate? true})
                      (slim/render! h [:div "v2"] mount {:hydrate? true})
                      (slim/render! h [:div "v3"] mount))))]
      (is (= [[:hydrate-root mount [:div "ssr"]]] (of-kind calls :hydrate-root)))
      (is (empty? (of-kind calls :create-root)))
      (is (= [[:render root [:div "v2"]] [:render root [:div "v3"]]] (of-kind calls :render))))))

(deftest unmount-is-idempotent-and-a-later-render-mounts-afresh
  (testing "unmount! twice reaches rdc/unmount once; render! afterwards mounts afresh"
    (let [root-1 (fake-root :first)
          root-2 (fake-root :second)
          mount  #js {:rf-test-mount :again}
          calls  (spy-rdc! [root-1 root-2]
                   (fn []
                     (let [h (slim/client-root)]
                       (slim/render! h [:div "v1"] mount)
                       (slim/unmount! h)
                       (slim/unmount! h)
                       (slim/render! h [:div "v2"] mount))))]
      (is (= [[:unmount root-1]] (of-kind calls :unmount)))
      (is (= [[:create-root mount] [:create-root mount]] (of-kind calls :create-root)))
      (is (= [[:render root-1 [:div "v1"]] [:render root-2 [:div "v2"]]] (of-kind calls :render))))))

(deftest dispose-adapter-releases-live-handles-once
  (testing "the drain releases each still-live handle once; nothing is released twice"
    (let [root-live (fake-root :live)
          root-gone (fake-root :gone)
          calls     (spy-rdc! [root-live root-gone]
                      (fn []
                        (let [live (slim/client-root)
                              gone (slim/client-root)]
                          (slim/render! live [:div "live"] #js {})
                          (slim/render! gone [:div "gone"] #js {})
                          (slim/unmount! gone)
                          (adapter/dispose-adapter!)
                          (slim/unmount! live)
                          (slim/unmount! gone))))]
      (is (= 1 (count (filter #(identical? root-live (second %)) (of-kind calls :unmount)))))
      (is (= 1 (count (filter #(identical? root-gone (second %)) (of-kind calls :unmount)))))
      (is (= 2 (count (of-kind calls :unmount)))))))
