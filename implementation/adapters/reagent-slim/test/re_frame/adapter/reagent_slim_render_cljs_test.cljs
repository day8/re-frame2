(ns re-frame.adapter.reagent-slim-render-cljs-test
  "reagent-slim render-path coverage parity with the Reagent adapter's
  `re-frame.adapter-render-cljs-test` (rf2-yrb8r). The slim adapter's
  `render` slot must follow the React 18+ Root API exactly as the bridge
  does — `(rdc/create-root mount-point)` first, then `(rdc/render root
  render-tree)` — NOT `(rdc/render mount-point tree)` directly. The same
  bug the bridge guards against (rf2-fn5rk: `TypeError: root.render is
  not a function`) would bite slim too, because slim is positioned as a
  drop-in Reagent replacement and routes through `reagent2.dom.client`
  with the identical Root-API shape.

  This pins the call sequence by spying through `with-redefs` on
  `reagent2.dom.client`'s `create-root` / `render` / `hydrate-root` /
  `unmount` fns. It does NOT touch a real DOM (no jsdom in :node-test);
  it asserts the slim adapter wires the Root API correctly.

  Test surface — the private `:render` slot on the slim adapter map plus
  the spy stubs confirm:

    1. Non-hydrate path: create-root is called exactly once with the
       mount-point; render is called exactly once with the resulting
       Root + the render-tree (in that order).
    2. The returned unmount thunk calls unmount with the Root (not the
       mount-point).
    3. Hydrate path: hydrate-root is called once with the mount-point +
       render-tree; create-root + render are NOT called; the unmount
       thunk calls unmount with the Root returned by hydrate-root.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [reagent2.dom.client :as rdc]
            [re-frame.adapter.reagent-slim :as reagent-slim-adapter]))

;; ---- helpers ---------------------------------------------------------------

(defn- mk-fake-root
  "A fake Root identity — just an object with a marker tag. The spy
  replaces `rdc/unmount` so its real method is never invoked. Using
  `#js {...}` keeps the value JS-shaped without depending on React being
  present in the :node-test runtime."
  [tag]
  #js {:rf-test-root-tag tag})

;; ---- non-hydrate path ------------------------------------------------------

(deftest render-uses-create-root-then-render
  (testing "non-hydrate render: (rdc/create-root mount-point) is called
            first; (rdc/render root render-tree) follows; the unmount
            thunk closes over the Root (parity with the bridge's
            rf2-fn5rk pin)"
    (let [calls      (atom [])
          fake-root  (mk-fake-root :non-hydrate)
          fake-mount #js {:rf-test-mount :non-hydrate}
          fake-tree  [:div "tree"]]
      ;; Stubs are multi-arity to mirror reagent2.dom.client's published
      ;; API (create-root: 1/2, render: 2, hydrate-root: 2/3). Only the
      ;; lowest arities are exercised by the slim adapter today, but
      ;; covering the published arities keeps the stubs robust.
      (with-redefs [rdc/create-root  (fn
                                       ([mp]   (swap! calls conj [:create-root mp])
                                               fake-root)
                                       ([mp _] (swap! calls conj [:create-root mp])
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
        (let [render-fn (:render reagent-slim-adapter/adapter)
              unmount   (render-fn fake-tree fake-mount nil)]
          (is (fn? unmount)
              "render returns an unmount thunk")
          ;; Pre-unmount: exactly create-root + render, in order.
          (is (= 2 (count @calls))
              (str "expected 2 calls after render, got " (count @calls)
                   " — " (pr-str @calls)))
          (let [[c1 c2] @calls]
            (is (= [:create-root fake-mount] c1)
                "first call is (create-root mount-point)")
            (is (= :render (first c2))
                "second call is (render …)")
            (is (identical? fake-root (second c2))
                "render's first arg is the Root from create-root,
                NOT the mount-point — the React 18 contract")
            (is (= fake-tree (nth c2 2))
                "render's second arg is the render-tree"))
          ;; Unmount: the thunk calls (rdc/unmount root) — NOT the
          ;; mount-point.
          (unmount)
          (let [last-call (last @calls)]
            (is (= :unmount (first last-call))
                "unmount thunk invoked rdc/unmount")
            (is (identical? fake-root (second last-call))
                "unmount thunk passed the Root to rdc/unmount,
                NOT the mount-point")))))))

;; ---- hydrate path ----------------------------------------------------------

(deftest render-hydrate-uses-hydrate-root
  (testing "hydrate render: (rdc/hydrate-root mount-point render-tree)
            returns the Root; create-root / render are NOT called; the
            unmount thunk closes over the Root from hydrate-root (parity
            with the bridge's rf2-fn5rk pin)"
    (let [calls      (atom [])
          fake-root  (mk-fake-root :hydrate)
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
                                       ([mp tree]
                                        (swap! calls conj [:hydrate-root mp tree])
                                        fake-root)
                                       ([mp tree _]
                                        (swap! calls conj [:hydrate-root mp tree])
                                        fake-root))
                    rdc/unmount      (fn [arg]
                                       (swap! calls conj [:unmount arg])
                                       nil)]
        (let [render-fn (:render reagent-slim-adapter/adapter)
              unmount   (render-fn fake-tree fake-mount {:hydrate? true})]
          (is (fn? unmount))
          (is (= 1 (count @calls))
              (str "expected exactly 1 call (hydrate-root) before unmount, got "
                   (count @calls) " — " (pr-str @calls)))
          (is (= [:hydrate-root fake-mount fake-tree] (first @calls))
              "hydrate-root called once with (mount-point render-tree)")
          (unmount)
          (let [last-call (last @calls)]
            (is (= :unmount (first last-call)))
            (is (identical? fake-root (second last-call))
                "unmount thunk passes the Root returned by hydrate-root")))))))

;; ---- regression pin --------------------------------------------------------

(deftest render-does-not-pass-mount-point-to-rdc-render
  (testing "regression pin (parity with rf2-fn5rk) — the slim render slot
            must NEVER call `(rdc/render mount-point render-tree)`; the
            mount-point may only reach create-root / hydrate-root, never
            rdc/render's first arg"
    (let [render-calls (atom [])
          fake-root    (mk-fake-root :regression)
          fake-mount   #js {:rf-test-mount :regression}]
      (with-redefs [rdc/create-root  (fn
                                       ([_]   fake-root)
                                       ([_ _] fake-root))
                    rdc/render       (fn [root tree]
                                       (swap! render-calls conj [root tree]))
                    rdc/hydrate-root (fn
                                       ([_ _]   fake-root)
                                       ([_ _ _] fake-root))
                    rdc/unmount      (fn [_] nil)]
        (let [render-fn (:render reagent-slim-adapter/adapter)]
          (render-fn [:div] fake-mount nil)
          (is (= 1 (count @render-calls)))
          (let [[root tree] (first @render-calls)]
            (is (not (identical? fake-mount root))
                "rdc/render's first arg is NEVER the raw mount-point")
            (is (identical? fake-root root)
                "rdc/render's first arg is the Root from create-root")
            (is (= [:div] tree)
                "the render-tree is passed through unchanged")))))))
