(ns re-frame.adapter-render-cljs-test
  "Per rf2-fn5rk — the Reagent adapter's `render` slot must follow the
  React 18+ Root API: `(rdc/create-root mount-point)` first, then
  `(rdc/render root render-tree)` — NOT `(rdc/render mount-point tree)`
  directly. The pre-fix code passed a raw DOM element where the Root
  was required, causing
  `TypeError: root.render is not a function` at Causa mount time.

  This test pins the call sequence by spying through `with-redefs` on
  `reagent.dom.client`'s `create-root` / `render` / `hydrate-root` /
  `unmount` fns. It does NOT touch a real DOM (no jsdom in
  :node-test); it asserts the adapter wires the Root API correctly.

  Test surface: the private `:render` slot on the adapter map plus the
  spy stubs let us confirm the contract:

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
            [reagent.dom.client :as rdc]
            [re-frame.adapter.reagent :as reagent-adapter]))

;; ---- helpers ---------------------------------------------------------------

(defn- mk-fake-root
  "A fake Root identity — just an object with a `.unmount` method we
  never actually call (the spy replaces `rdc/unmount`). Using `(js-obj)`
  keeps the value JS-shaped without depending on React being present."
  [tag]
  #js {:rf-test-root-tag tag})

;; ---- non-hydrate path ------------------------------------------------------

(deftest render-uses-create-root-then-render
  (testing "non-hydrate render: (rdc/create-root mount-point) is called
            first; (rdc/render root render-tree) follows; the unmount
            thunk closes over the Root (rf2-fn5rk)"
    (let [calls         (atom [])
          fake-root     (mk-fake-root :non-hydrate)
          fake-mount    #js {:rf-test-mount :non-hydrate}
          fake-tree     [:div "tree"]]
      ;; Stubs are multi-arity to mirror reagent.dom.client's API
      ;; (create-root: 1/2, render: 2/3/4, hydrate-root: 2/3). The
      ;; with-redefs rebinding replaces the var's value with a plain fn;
      ;; if we only provide a single arity, real Reagent code calling
      ;; the multi-arity form during the rebinding scope would throw.
      ;; Only the lowest arities are exercised by our adapter today, but
      ;; covering the published arities keeps the stubs robust against
      ;; passes through reagent internals.
      (with-redefs [rdc/create-root   (fn
                                        ([mp]   (swap! calls conj [:create-root mp])
                                                fake-root)
                                        ([mp _] (swap! calls conj [:create-root mp])
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
        (let [render-fn (:render reagent-adapter/adapter)
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
                NOT the mount-point — this is the React 18 contract
                the bug violated")
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
            unmount thunk closes over the Root from hydrate-root
            (rf2-fn5rk)"
    (let [calls       (atom [])
          fake-root   (mk-fake-root :hydrate)
          fake-mount  #js {:rf-test-mount :hydrate}
          fake-tree   [:section "ssr-tree"]]
      (with-redefs [rdc/create-root  (fn
                                       ([_]   (swap! calls conj [:create-root])
                                              (throw (ex-info "create-root must not be called on hydrate path" {})))
                                       ([_ _] (swap! calls conj [:create-root])
                                              (throw (ex-info "create-root must not be called on hydrate path" {}))))
                    rdc/render       (fn
                                       ([_ _]
                                        (swap! calls conj [:render])
                                        (throw (ex-info "render must not be called on hydrate path" {})))
                                       ([_ _ _]
                                        (swap! calls conj [:render])
                                        (throw (ex-info "render must not be called on hydrate path" {})))
                                       ([_ _ _ _]
                                        (swap! calls conj [:render])
                                        (throw (ex-info "render must not be called on hydrate path" {}))))
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
        (let [render-fn (:render reagent-adapter/adapter)
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
  (testing "regression pin for rf2-fn5rk — the pre-fix code called
            `(rdc/render mount-point render-tree)` which threw
            `TypeError: root.render is not a function` in real React
            18. This test asserts the mount-point never appears as
            the first arg to rdc/render."
    (let [render-calls (atom [])
          fake-root    (mk-fake-root :regression)
          fake-mount   #js {:rf-test-mount :regression}]
      (with-redefs [rdc/create-root  (fn
                                       ([_]   fake-root)
                                       ([_ _] fake-root))
                    rdc/render       (fn
                                       ([root tree]
                                        (swap! render-calls conj [root tree]))
                                       ([root tree _]
                                        (swap! render-calls conj [root tree]))
                                       ([root tree _ _]
                                        (swap! render-calls conj [root tree])))
                    rdc/hydrate-root (fn
                                       ([_ _]   fake-root)
                                       ([_ _ _] fake-root))
                    rdc/unmount      (fn [_] nil)]
        (let [render-fn (:render reagent-adapter/adapter)]
          (render-fn [:div] fake-mount nil)
          (is (= 1 (count @render-calls)))
          (let [[root tree] (first @render-calls)]
            (is (not (identical? fake-mount root))
                "rdc/render's first arg is NEVER the raw mount-point
                — that was the bug")
            (is (identical? fake-root root)
                "rdc/render's first arg is the Root from create-root")
            (is (= [:div] tree)
                "the render-tree is passed through unchanged")))))))
