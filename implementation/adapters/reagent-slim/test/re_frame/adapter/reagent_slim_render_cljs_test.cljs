(ns re-frame.adapter.reagent-slim-render-cljs-test
  "The slim adapter's `:render` slot follows the React 18+ Root API —
  `(rdc/create-root mount-point)`, then `(rdc/render root tree)`, or
  `(rdc/hydrate-root mount-point tree)` — and its unmount thunk unmounts the
  Root, never the mount point. Spies `reagent2.dom.client` through
  `with-redefs`, so no DOM. The Reagent twin is
  `re-frame.adapter-render-cljs-test`."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [reagent2.dom.client :as rdc]
            [re-frame.adapter.reagent-slim :as rf.adapter.reagent-slim]
            [re-frame.substrate.spine :as rf.substrate.spine]))

;; ---- helpers ---------------------------------------------------------------

(defn- make-fake-root
  "A fake Root identity; the spies never call into it."
  [tag]
  #js {:rf-test-root-tag tag})

;; ---- non-hydrate path ------------------------------------------------------

(deftest render-uses-create-root-then-render
  (testing "non-hydrate render: (rdc/create-root mount-point) is called
            first; (rdc/render root render-tree) follows; the unmount
            thunk closes over the Root (parity with the bridge's
            pin)"
    (let [calls      (atom [])
          fake-root  (make-fake-root :non-hydrate)
          fake-mount #js {:rf-test-mount :non-hydrate}
          fake-tree  [:div "tree"]]
      ;; Stubs cover reagent2.dom.client's published arities.
      (with-redefs [rdc/create-root  (fn
                                       ([mount-point]   (swap! calls conj [:create-root mount-point])
                                               fake-root)
                                       ([mount-point _] (swap! calls conj [:create-root mount-point])
                                               fake-root))
                    rdc/render       (fn [root tree]
                                       (swap! calls conj [:render root tree])
                                       nil)
                    rdc/hydrate-root (fn
                                       ([_ _]
                                        (swap! calls conj [:hydrate-root])
                                        (throw (ex-info "hydrate-root must not be called on non-hydrate path" {})))
                                       ([_ _ _]
                                        (swap! calls conj [:hydrate-root])
                                        (throw (ex-info "hydrate-root must not be called on non-hydrate path" {}))))
                    rdc/unmount      (fn [arg]
                                       (swap! calls conj [:unmount arg])
                                       nil)]
        (let [render-fn (:render rf.adapter.reagent-slim/adapter)
              unmount   (render-fn fake-tree fake-mount nil)]
          (unmount)
          ;; `=` on the JS objects is identity.
          (is (= [[:create-root fake-mount] [:render fake-root fake-tree] [:unmount fake-root]]
                 @calls)
              "create-root on the mount point, render on the Root it returned, and the thunk unmounts that Root"))))))

;; ---- hydrate path ----------------------------------------------------------

(defn- closer-child
  "The tree beneath a hydrating root's adoption-window closer, or `tree`
  itself when the closer does not wrap it."
  [tree]
  (if (and (vector? tree)
           (= :r> (first tree))
           (identical? rf.substrate.spine/adoption-window-closer (second tree)))
    (nth tree 3)
    tree))

(deftest render-hydrate-uses-hydrate-root
  (testing "hydrate render: (rdc/hydrate-root mount-point render-tree)
            returns the Root; create-root / render are NOT called; the
            unmount thunk closes over the Root from hydrate-root (parity
            with the bridge's pin)"
    (let [calls      (atom [])
          hydrated   (atom nil)
          fake-root  (make-fake-root :hydrate)
          fake-mount #js {:rf-test-mount :hydrate}
          fake-tree  [:section "ssr-tree"]]
      (with-redefs [rdc/create-root  (fn
                                       ([_]   (swap! calls conj [:create-root])
                                              (throw (ex-info "create-root must not be called on hydrate path" {})))
                                       ([_ _] (swap! calls conj [:create-root])
                                              (throw (ex-info "create-root must not be called on hydrate path" {}))))
                    rdc/render       (fn [_ _]
                                       (swap! calls conj [:render])
                                       (throw (ex-info "render must not be called on hydrate path" {})))
                    rdc/hydrate-root (fn
                                       ([mount-point tree]
                                        (swap! calls conj [:hydrate-root mount-point tree])
                                        fake-root)
                                       ([mount-point tree options]
                                        (swap! calls conj [:hydrate-root mount-point (closer-child tree)])
                                        (reset! hydrated [tree options])
                                        fake-root))
                    rdc/unmount      (fn [arg]
                                       (swap! calls conj [:unmount arg])
                                       nil)]
        (let [render-fn (:render rf.adapter.reagent-slim/adapter)
              unmount   (render-fn fake-tree fake-mount {:hydrate? true})]
          (unmount)
          (is (= [[:hydrate-root fake-mount fake-tree] [:unmount fake-root]] @calls)
              "hydrate-root alone mounts, and the thunk unmounts the Root it returned")
          (let [[tree options] @hydrated]
            (is (not= tree (closer-child tree))
                "the tree reached React wrapped in the adoption-window closer")
            (is (fn? (some-> options .-onRecoverableError))
                "with the spine's composed reporter in the root options")))))))
