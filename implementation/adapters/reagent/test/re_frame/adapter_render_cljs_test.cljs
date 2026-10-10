(ns re-frame.adapter-render-cljs-test
  "The Reagent adapter's `:render` slot follows the React 18+ Root API —
  `(rdc/create-root mount-point)`, then `(rdc/render root tree)`, or, to
  hydrate, react-dom/client's `hydrateRoot` with root options over the
  element Reagent builds — and its unmount thunk unmounts the Root; a raw DOM
  element where the Root belongs throws `root.render is not a function`.
  Spies `reagent.dom.client` through `with-redefs`, and `hydrateRoot` on its
  module, so no DOM."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [reagent.dom.client :as rdc]
            ["react-dom/client" :as react-dom-client]
            [re-frame.adapter.reagent :as rf.adapter.reagent]))

;; ---- helpers ---------------------------------------------------------------

(defn- make-fake-root
  "A fake Root identity; the spies never call into it."
  [tag]
  #js {:rf-test-root-tag tag})

;; ---- non-hydrate path ------------------------------------------------------

(deftest render-uses-create-root-then-render
  (testing "non-hydrate render: (rdc/create-root mount-point) is called
            first; (rdc/render root render-tree) follows; the unmount
            thunk closes over the Root"
    (let [calls         (atom [])
          fake-root     (make-fake-root :non-hydrate)
          fake-mount    #js {:rf-test-mount :non-hydrate}
          fake-tree     [:div "tree"]]
      ;; Stubs cover reagent.dom.client's published arities, which Reagent's
      ;; own internals may call during the rebinding.
      (with-redefs [rdc/create-root   (fn
                                        ([mount-point]   (swap! calls conj [:create-root mount-point])
                                                fake-root)
                                        ([mount-point _] (swap! calls conj [:create-root mount-point])
                                                fake-root))
                    rdc/render        (fn
                                        ([root tree]
                                         (swap! calls conj [:render root tree]) nil)
                                        ([root tree _]
                                         (swap! calls conj [:render root tree]) nil)
                                        ([root tree _ _]
                                         (swap! calls conj [:render root tree]) nil))
                    rdc/hydrate-root  (fn
                                        ([_ _]
                                         (swap! calls conj [:hydrate-root])
                                         (throw (ex-info "hydrate-root must not be called on non-hydrate path" {})))
                                        ([_ _ _]
                                         (swap! calls conj [:hydrate-root])
                                         (throw (ex-info "hydrate-root must not be called on non-hydrate path" {}))))
                    rdc/unmount       (fn [arg]
                                        (swap! calls conj [:unmount arg])
                                        nil)]
        (let [render-fn (:render rf.adapter.reagent/adapter)
              unmount   (render-fn fake-tree fake-mount nil)]
          (unmount)
          ;; `=` on the JS objects is identity.
          (is (= [[:create-root fake-mount] [:render fake-root fake-tree] [:unmount fake-root]]
                 @calls)
              "create-root on the mount point, render on the Root it returned, and the thunk unmounts that Root"))))))

;; ---- hydrate path ----------------------------------------------------------

(deftest render-hydrate-uses-hydrate-root
  (testing "hydrate render: react-dom/client's hydrateRoot returns the Root,
            created over Reagent's own root element and with the spine's root
            options; create-root is NOT called; the unmount thunk closes over
            the Root hydrateRoot returned"
    (let [calls        (atom [])
          hydrated     (atom nil)
          fake-root    (make-fake-root :hydrate)
          fake-mount   #js {:rf-test-mount :hydrate}
          fake-tree    [:section "ssr-tree"]
          hydrate-root (.-hydrateRoot react-dom-client)]
      (set! (.-hydrateRoot react-dom-client)
            (fn [mount-point element options]
              (swap! calls conj [:hydrate-root mount-point])
              (reset! hydrated [element options])
              fake-root))
      (try
        (with-redefs [rdc/create-root  (fn
                                         ([_]   (swap! calls conj [:create-root])
                                                (throw (ex-info "create-root must not be called on hydrate path" {})))
                                         ([_ _] (swap! calls conj [:create-root])
                                                (throw (ex-info "create-root must not be called on hydrate path" {}))))
                      rdc/unmount      (fn [arg]
                                         (swap! calls conj [:unmount arg])
                                         nil)]
          (let [render-fn (:render rf.adapter.reagent/adapter)
                unmount   (render-fn fake-tree fake-mount {:hydrate? true})]
            (unmount)
            (is (= [[:hydrate-root fake-mount] [:unmount fake-root]] @calls)
                "hydrateRoot alone mounts, and the thunk unmounts the Root it returned")
            (let [[element options] @hydrated]
              (is (identical? @#'rdc/reagent-root (some-> element .-type))
                  "the hydrated element is Reagent's root component, as rdc/hydrate-root builds it")
              (is (fn? (some-> options .-onRecoverableError))
                  "the Root is created with the spine's composed reporter"))))
        (finally
          (set! (.-hydrateRoot react-dom-client) hydrate-root))))))
