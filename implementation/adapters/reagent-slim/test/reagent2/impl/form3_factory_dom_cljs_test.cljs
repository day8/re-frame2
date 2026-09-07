(ns reagent2.impl.form3-factory-dom-cljs-test
  "rf2-oyrj — the per-instance Form-3 FACTORY mount witness.

  WHAT IT PROVES. `FORM-3.md` §\"A complete example\" documents the
  supported Form-3 shape as a plain factory `defn` whose body returns a
  `create-class` result:

      (defn google-map [_initial-props]
        (let [el-ref (atom nil) ...]
          (r/create-class {...})))

  and the section under it states that each `[google-map ...]` mount
  owns its own closure atoms. That is the adapter's principal
  imperative-widget recipe (maps, charts, popovers, cleanup-owning
  widgets).

  The defect: `reagent2.impl.component/wrap-render` classified the
  factory's OUTPUT with a bare `fn?` test. A reagent-slim class made by
  `create-class*` IS a JS function, so it took the Form-2 branch and the
  CLASS CONSTRUCTOR was cached as `cljsRenderFn` and applied with the
  render args. No Form-3 instance was mounted and its render/lifecycle
  methods were never reached. `reagent2.dom.server/emit-render-fn`
  mirrored the same mistake at the static-markup factory-output
  boundary — the class dispatch there covered only a class sitting
  directly in the hiccup HEAD.

  WHY A REAL MOUNT. A test that hand-invokes `new` on the returned class
  (or walks its prototype) proves the class is well formed, not that the
  renderer mounts one. Only a `react-dom/client` root driving the
  ordinary `[factory args...]` hiccup path exercises the classification
  seam this bug lives in, so the live assertions here go through
  `rdc/render`.

  TEST-ONLY. The ns ends in `-dom-cljs-test` so shadow-cljs's
  `:browser-test` discovers it for the real-DOM assertions; the
  `:node-test` runner also loads it (`cljs-test$` matches), where the
  live bodies gate on `(browser?)` and no-op cleanly. The
  static-markup deftest is NOT gated — `render-to-static-markup` is
  pure string building, so it runs on both lanes."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [reagent2.core :as r]
            [reagent2.dom.client :as rdc]
            [reagent2.dom.server :as server]
            ["react-dom" :as react-dom]))

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- make-mount-node! []
  (when (browser?)
    (.createElement js/document "div")))

;; ---------------------------------------------------------------------------
;; The probe factory. Deliberately the documented shape: a plain `defn`
;; whose body closes over per-mount state and returns `create-class`.
;; `lifecycle` is passed IN so each deftest owns its own log; the closure
;; token proves per-mount closure ownership.

(defn- make-probe-factory
  "Returns a Form-3 FACTORY fn. Each invocation of the returned factory
  (i.e. each mount of `[factory ...]`) creates a fresh closure token and
  a fresh class, exactly as `FORM-3.md`'s google-map recipe does."
  [lifecycle]
  (fn probe-factory [_initial-label]
    (let [token (gensym "instance")]
      (r/create-class
        {:display-name "form3-factory-probe"
         :reagent-render
         (fn [label] [:div {:class "factory-panel"} label])
         :component-did-mount
         (fn [_this] (swap! lifecycle conj [:mount token]))
         :component-will-unmount
         (fn [_this] (swap! lifecycle conj [:unmount token]))}))))

(defn- phases [lifecycle phase]
  (filterv #(= phase (first %)) @lifecycle))

;; ---------------------------------------------------------------------------

(deftest factory-returned-class-mounts-updates-and-unmounts
  (testing "reagent-slim — a plain factory returning create-class mounts its class, keeps closure identity across an update, and runs mount/unmount exactly once (rf2-oyrj)"
    (if-not (browser?)
      (is true ":node-test: no DOM — :browser-test runner exercises the assertion")
      (let [lifecycle  (atom [])
            factory    (make-probe-factory lifecycle)
            mount-node (make-mount-node!)
            root       (rdc/create-root mount-node)]
        (try
          ;; Initial mount. Pre-fix this rendered nothing usable: the
          ;; class CONSTRUCTOR was applied as a Form-2 inner render fn.
          (react-dom/flushSync (fn [] (rdc/render root [factory "hello"])))
          (is (= "hello" (.-textContent mount-node))
              "initial mount rendered the class's :reagent-render output")
          (is (some? (.querySelector mount-node ".factory-panel"))
              "the class's own render produced the panel element")
          (is (= 1 (count (phases lifecycle :mount)))
              ":component-did-mount fired exactly once — the class really mounted")

          (let [token-after-mount (second (first (phases lifecycle :mount)))]
            ;; Update with fresh args. Same class type → React reconciles
            ;; in place: no remount, closure token unchanged.
            (react-dom/flushSync (fn [] (rdc/render root [factory "updated"])))
            (is (= "updated" (.-textContent mount-node))
                "the update reached the class's render with the fresh arg")
            (is (= 1 (count (phases lifecycle :mount)))
                "no second :component-did-mount — the factory's class instance was reused")

            (rdc/unmount root)
            (is (= 1 (count (phases lifecycle :unmount)))
                ":component-will-unmount fired exactly once")
            (is (= token-after-mount (second (first (phases lifecycle :unmount))))
                "unmount saw the SAME closure token as mount — per-mount closure ownership held"))
          (finally
            (try (rdc/unmount root) (catch :default _ nil))))))))

(deftest sibling-factory-instances-own-separate-closures
  (testing "reagent-slim — two sibling mounts of one factory each own a separate closure and class (rf2-oyrj)"
    (if-not (browser?)
      (is true ":node-test: no DOM — :browser-test runner exercises the assertion")
      (let [lifecycle  (atom [])
            factory    (make-probe-factory lifecycle)
            mount-node (make-mount-node!)
            root       (rdc/create-root mount-node)]
        (try
          (react-dom/flushSync
            (fn [] (rdc/render root [:div [factory "a"] [factory "b"]])))
          (is (= "ab" (.-textContent mount-node))
              "both sibling factory mounts rendered")
          (let [tokens (mapv second (phases lifecycle :mount))]
            (is (= 2 (count tokens))
                "two :component-did-mount calls — one per sibling mount")
            (is (= 2 (count (set tokens)))
                "the two siblings own DISTINCT closure tokens (FORM-3.md: each mount gets its own atoms)"))
          (finally
            (try (rdc/unmount root) (catch :default _ nil))))))))

(deftest genuine-form-2-and-direct-class-head-unchanged
  (testing "reagent-slim — the Form-2 inner-fn path and a direct class head still behave (rf2-oyrj control)"
    (if-not (browser?)
      (is true ":node-test: no DOM — :browser-test runner exercises the assertion")
      (let [setup-calls (atom 0)
            form-2      (fn form-2-probe [_label]
                          (swap! setup-calls inc)
                          (fn [label] [:div {:class "form2"} "f2-" label]))
            direct      (r/create-class
                          {:display-name "direct-class-head"
                           :reagent-render (fn [label] [:div {:class "direct"} "d-" label])})
            mount-node  (make-mount-node!)
            root        (rdc/create-root mount-node)]
        (try
          (react-dom/flushSync (fn [] (rdc/render root [form-2 "x"])))
          (is (= "f2-x" (.-textContent mount-node))
              "Form-2 control: inner fn cached and called with the args")
          (react-dom/flushSync (fn [] (rdc/render root [form-2 "y"])))
          (is (= "f2-y" (.-textContent mount-node))
              "Form-2 control: cached inner fn recalled with fresh args")
          (is (= 1 @setup-calls)
              "Form-2 control: the setup fn ran exactly once (inner fn was cached, not re-derived)")

          (react-dom/flushSync (fn [] (rdc/render root [direct "z"])))
          (is (= "d-z" (.-textContent mount-node))
              "direct class head control: a create-class result in HEAD position still mounts")
          (finally
            (try (rdc/unmount root) (catch :default _ nil))))))))

(deftest static-markup-of-a-factory-emits-content-without-lifecycle
  (testing "reagent-slim — render-to-static-markup of a factory-returned class emits the class's markup and runs no lifecycle (rf2-oyrj)"
    ;; NOT browser-gated: the static serializer is pure string building,
    ;; so this assertion runs on the :node-test lane too.
    (let [lifecycle (atom [])
          factory   (make-probe-factory lifecycle)
          html      (server/render-to-static-markup [factory "hello"])]
      (is (re-find #"hello" html)
          "static markup carried the class's :reagent-render content")
      (is (re-find #"factory-panel" html)
          "static markup carried the class's own element, not a Form-2 misread")
      (is (empty? @lifecycle)
          "no lifecycle ran under static markup (matches renderToStaticMarkup)"))))
