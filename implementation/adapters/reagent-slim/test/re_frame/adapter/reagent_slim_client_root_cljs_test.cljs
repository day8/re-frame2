(ns re-frame.adapter.reagent-slim-client-root-cljs-test
  "reagent-slim twin of `re-frame.adapter-client-root-cljs-test`.
  The published slim artefact ships its adapter at the canonical
  `re-frame.adapter.reagent` ns, so the `client-root` / `render!` /
  `unmount!` trio must behave identically over `reagent2.dom.client`: this
  pins the same behaviours by spying on the slim Root API through
  `with-redefs` (no DOM; :node-test)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [reagent2.dom.client :as rdc]
            [re-frame.frame :as rf.frame]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.adapter.reagent-slim :as rf.adapter.reagent-slim]))

(defn- with-fresh-slim-adapter [test-fn]
  (reset! rf.frame/frames {})
  ;; Drain Roots earlier suites stranded in the slim adapter's singleton
  ;; active set (see the Reagent twin), so the counts are this test's own.
  (with-redefs [rdc/unmount (fn [_] nil)]
    (rf.substrate.adapter/reset-lifecycle-state-for-tests!)
    (rf.substrate.adapter/install-adapter! rf.adapter.reagent-slim/adapter)
    (rf.substrate.adapter/dispose-adapter!))
  (rf.substrate.adapter/reset-lifecycle-state-for-tests!)
  (rf.substrate.adapter/install-adapter! rf.adapter.reagent-slim/adapter)
  (test-fn)
  (reset! rf.frame/frames {})
  (rf.substrate.adapter/reset-lifecycle-state-for-tests!))

(use-fixtures :each with-fresh-slim-adapter)

(defn- make-fake-root [tag]
  #js {:rf-test-root-tag tag :unmount (fn [] nil)})

(defn- record-root-api-calls
  "Call-recording stubs over the slim Root API for the extent of `body-fn`
  (`reagent2.dom.client`'s published arities: create-root 1/2, render 2,
  hydrate-root 2/3, unmount 1). Returns the recorded calls."
  [root-sequence body-fn]
  (let [calls (atom [])
        remaining-roots (atom root-sequence)
        next-root! (fn []
                     (let [[root] @remaining-roots]
                       (swap! remaining-roots rest)
                       root))]
    (with-redefs [rdc/create-root  (fn
                                     ([mount-point]
                                      (swap! calls conj [:create-root mount-point])
                                      (next-root!))
                                     ([mount-point _options]
                                      (swap! calls conj [:create-root mount-point])
                                      (next-root!)))
                  rdc/render       (fn [root render-tree]
                                     (swap! calls conj [:render root render-tree])
                                     nil)
                  rdc/hydrate-root (fn
                                     ([mount-point render-tree]
                                      (swap! calls conj
                                             [:hydrate-root mount-point render-tree])
                                      (next-root!))
                                     ([mount-point render-tree _options]
                                      (swap! calls conj
                                             [:hydrate-root mount-point render-tree])
                                      (next-root!)))
                  rdc/unmount      (fn [root]
                                     (swap! calls conj [:unmount root])
                                     nil)]
      (body-fn))
    @calls))

(defn- calls-of-kind [calls call-kind]
  (filter #(= call-kind (first %)) calls))

(defn- by-kind
  "The recorded calls grouped as [create-root hydrate-root render]."
  [calls]
  (mapv #(vec (calls-of-kind calls %)) [:create-root :hydrate-root :render]))

(deftest cold-first-render-creates-once-later-renders-update-the-same-root
  (testing "first render! creates once; later renders reuse the identical Root"
    (let [root  (make-fake-root :cold)
          mount #js {:rf-test-mount :cold}
          calls (record-root-api-calls [root (make-fake-root :never)]
                  (fn []
                    (let [h (rf.adapter.reagent-slim/client-root)]
                      (rf.adapter.reagent-slim/render! h [:div "v1"] mount)
                      (rf.adapter.reagent-slim/render! h [:div "v2"] mount)
                      (rf.adapter.reagent-slim/render! h [:div "v3"] mount))))]
      (is (= [[[:create-root mount]]
              []
              [[:render root [:div "v1"]] [:render root [:div "v2"]] [:render root [:div "v3"]]]]
             (by-kind calls))))))

(deftest hydrating-first-render-hydrates-once-later-renders-update
  (testing "render! with {:hydrate? true} hydrates once; later renders update"
    (let [root  (make-fake-root :hydrated)
          mount #js {:rf-test-mount :hydrated}
          calls (record-root-api-calls [root (make-fake-root :never)]
                  (fn []
                    (let [h (rf.adapter.reagent-slim/client-root)]
                      (rf.adapter.reagent-slim/render! h [:div "ssr"] mount {:hydrate? true})
                      (rf.adapter.reagent-slim/render! h [:div "v2"] mount {:hydrate? true})
                      (rf.adapter.reagent-slim/render! h [:div "v3"] mount))))]
      (is (= [[]
              [[:hydrate-root mount [:div "ssr"]]]
              [[:render root [:div "v2"]] [:render root [:div "v3"]]]]
             (by-kind calls))))))

(deftest unmount-is-idempotent-and-a-later-render-mounts-afresh
  (testing "unmount! twice reaches rdc/unmount once; render! afterwards mounts afresh"
    (let [root-1 (make-fake-root :first)
          root-2 (make-fake-root :second)
          mount  #js {:rf-test-mount :again}
          calls  (record-root-api-calls [root-1 root-2]
                   (fn []
                     (let [h (rf.adapter.reagent-slim/client-root)]
                       (rf.adapter.reagent-slim/render! h [:div "v1"] mount)
                       (rf.adapter.reagent-slim/unmount! h)
                       (rf.adapter.reagent-slim/unmount! h)
                       (rf.adapter.reagent-slim/render! h [:div "v2"] mount))))]
      (is (= [[:unmount root-1]] (calls-of-kind calls :unmount)))
      (is (= [[[:create-root mount] [:create-root mount]]
              []
              [[:render root-1 [:div "v1"]] [:render root-2 [:div "v2"]]]]
             (by-kind calls))))))

(deftest dispose-adapter-releases-live-handles-once
  (testing "the drain releases each still-live handle once; nothing is released twice"
    (let [root-live (make-fake-root :live)
          root-gone (make-fake-root :gone)
          calls     (record-root-api-calls [root-live root-gone]
                      (fn []
                        (let [live (rf.adapter.reagent-slim/client-root)
                              gone (rf.adapter.reagent-slim/client-root)]
                          (rf.adapter.reagent-slim/render! live [:div "live"] #js {})
                          (rf.adapter.reagent-slim/render! gone [:div "gone"] #js {})
                          (rf.adapter.reagent-slim/unmount! gone)
                          (rf.substrate.adapter/dispose-adapter!)
                          (rf.adapter.reagent-slim/unmount! live)
                          (rf.adapter.reagent-slim/unmount! gone))))]
      ;; `gone` is unmounted by hand before the drain releases `live`.
      (is (= [[:unmount root-gone] [:unmount root-live]]
             (calls-of-kind calls :unmount))))))
