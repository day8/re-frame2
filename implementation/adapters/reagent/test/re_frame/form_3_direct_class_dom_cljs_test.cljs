(ns re-frame.form-3-direct-class-dom-cljs-test
  "rf2-xccd — a `create-class` result handed DIRECTLY to `reg-view*` must be
  MOUNTED as a React class, not called as a render function.

  `docs/api/re-frame.core.md` advertises `(rf/reg-view* ::panel
  (r/create-class {…}))` — the direct Form-3 shape, with no outer callable
  around the class. Everything downstream of registration treated that value as
  a render fn and ended in `(apply render-fn args)`. A `create-class`
  constructor satisfies `fn?`, so the call succeeded in FORM: React never saw
  the class as a component type, `:reagent-render` never ran under a class
  instance, and `:component-did-mount` / `:component-will-unmount` never fired.

  `re-frame.views/compose-view` now normalizes a recognized Reagent-family class
  into a class-mounting render boundary before anything can call it. The
  constructor object is never modified and never wrapped, so React still
  reconciles it as one component type.

  Browser-only: the discriminating evidence is real React class construction
  with exactly-once mount/unmount ordering, which no headless invocation
  reproduces — calling the wrapper by hand is precisely the mistake under test.
  The `-dom-cljs-test` suffix selects the `:browser-test` build; the
  consolidated node build loads the namespace and takes the no-DOM branch.

  NOT debug-gated: the erroneous `apply` sat outside every
  `rf.interop/debug-enabled?` bracket, so the repair does too — a production
  build mounts the class by the same normalization, with only the source-coord
  annotation and trace emits elided around it.

  `::preinit-panel` is registered AT NAMESPACE LOAD, before any adapter is
  installed, so it exercises the pre-init registration path: the reg-time
  `compose-view` seed is taken against a nil adapter and `view-head` re-derives
  against Reagent on first lookup. `::postinit-panel` is registered inside the
  test, after the fixture has installed the adapter, so the seed is the hit."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [reagent.core :as r]
            [reagent.dom.client :as rdc]
            ["react-dom" :as react-dom]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.core :as rf]
            [re-frame.test-support :as rf.test-support]
            [re-frame.views]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter :async? true :ambient-frame nil}))

(def ^:private frame-id ::direct-class-frame)

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- get-act []
  ;; Synchronous flush — a deftest that is not `async` must not be handed a
  ;; thenable (the same constraint the sibling Form-3 lifecycle fixture records).
  react-dom/flushSync)

(defn- next-microtask []
  ;; Stock Reagent defers real render-reaction disposal to a microtask, so the
  ;; unmount half of the lifecycle log is observed after one turn.
  (js/Promise.resolve nil))

;; The lifecycle log every fixture class writes into. Reset per test.
(defonce ^:private lifecycle (atom []))

(defn- record! [ev] (swap! lifecycle conj ev))

(defn- probe-class
  "A stock-Reagent Form-3 class whose lifecycle methods are the evidence. It is
  handed to `reg-view*` DIRECTLY — no outer callable — which is the shape under
  test."
  [dom-id]
  (r/create-class
    {:display-name "rf2-xccd-direct-class"
     :reagent-render
     (fn [label]
       (record! [:render label])
       [:div {:id dom-id} label])
     :component-did-mount    (fn [_this] (record! [:mount dom-id]))
     :component-will-unmount (fn [_this] (record! [:unmount dom-id]))}))

;; PRE-INIT: top-level registration, evaluated at namespace load with no adapter
;; installed. This is the canonical boot order the head-cache section of
;; `re-frame.views` describes, and the path a direct class must survive.
(rf/reg-view* ::preinit-panel (probe-class "direct-class-preinit"))

(defn- mount-element []
  (let [el (.createElement js/document "div")]
    (.appendChild (.-body js/document) el)
    el))

(defn- text-of [dom-id]
  (some-> (.getElementById js/document dom-id) (.-textContent)))

(defn- mounted-attr [dom-id attr]
  (some-> (.getElementById js/document dom-id) (.getAttribute attr)))

(defn- run-mount-case
  "Mount `[(rf/view view-id) label]` under a frame-provider, run `after-mount`,
  unmount, and run `after-unmount` on the next microtask. Calls `done` once."
  [view-id dom-id label done after-mount after-unmount]
  (let [act-fn (get-act)]
    (if-not (fn? act-fn)
      (do (is true "React flushSync unavailable in this runner") (done))
      (let [el    (mount-element)
            root  (rdc/create-root el)
            done? (atom false)
            done! (fn [] (when (compare-and-set! done? false true) (done)))]
        (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
        (try
          (act-fn #(rdc/render root
                               [rf/frame-provider {:frame frame-id}
                                [(rf/view view-id) label]]))
          (after-mount)
          (act-fn #(rdc/unmount root))
          (-> (next-microtask)
              (.then (fn [_] (after-unmount) nil))
              (.catch (fn [err]
                        (is false (str "direct-class fixture threw: " (pr-str err)))
                        nil))
              (.then (fn [_] (.remove el) (done!))))
          (catch :default e
            (is false (str "direct-class fixture threw: " (pr-str e)))
            (try (act-fn #(rdc/unmount root)) (catch :default _ nil))
            (.remove el)
            (done!)))))))

(defn- setup! []
  (reset! lifecycle [])
  (rf/make-frame {:id frame-id :doc "rf2-xccd direct Form-3 class fixture"}))

(deftest direct-class-registered-before-init-mounts-with-exactly-once-lifecycle
  (testing "a create-class value passed straight to reg-view* at ns-load renders
            its :reagent-render under a real class instance and runs
            component-did-mount / component-will-unmount exactly once"
    (if-not (browser?)
      (is true ":node-test: no DOM — :browser-test exercises the class mount")
      (async done
        (setup!)
        (run-mount-case
          ::preinit-panel "direct-class-preinit" "hello" done
          (fn []
            ;; The whole bug in one assertion: called as a function, the
            ;; constructor never produces this node.
            (is (= "hello" (text-of "direct-class-preinit"))
                "the class's :reagent-render produced the DOM node with its arg")
            (is (= [:render "hello"] (first @lifecycle))
                "the render ran under the class, not as a bare constructor call")
            (is (= 1 (count (filter #(= [:mount "direct-class-preinit"] %) @lifecycle)))
                "component-did-mount fired exactly once")
            (is (empty? (filter #(= :unmount (first %)) @lifecycle))
                "nothing unmounted while still mounted"))
          (fn []
            (is (nil? (text-of "direct-class-preinit"))
                "the node is gone after unmount")
            (is (= 1 (count (filter #(= [:unmount "direct-class-preinit"] %) @lifecycle)))
                "component-will-unmount fired exactly once")))))))

(deftest direct-class-registered-after-init-mounts-with-exactly-once-lifecycle
  (testing "the same direct shape registered AFTER the adapter is installed —
            the reg-time compose-view seed rather than the view-head
            re-derivation — mounts identically"
    (if-not (browser?)
      (is true ":node-test: no DOM — :browser-test exercises the class mount")
      (async done
        (setup!)
        (rf/reg-view* ::postinit-panel (probe-class "direct-class-postinit"))
        (run-mount-case
          ::postinit-panel "direct-class-postinit" "world" done
          (fn []
            (is (= "world" (text-of "direct-class-postinit"))
                "the post-init direct class rendered its arg")
            (is (= 1 (count (filter #(= [:mount "direct-class-postinit"] %) @lifecycle)))
                "component-did-mount fired exactly once"))
          (fn []
            (is (= 1 (count (filter #(= [:unmount "direct-class-postinit"] %) @lifecycle)))
                "component-will-unmount fired exactly once")))))))

;; ---------------------------------------------------------------------------
;; Controls — the two shapes that already worked must be untouched.
;; ---------------------------------------------------------------------------

(deftest form-1-view-still-annotates-its-dom-root
  (testing "an ordinary Form-1 render fn is unaffected by the normalization: it
            still renders and still carries the source-coord annotation the
            hiccup walk stamps on a DOM root"
    (if-not (browser?)
      (is true ":node-test: no DOM — :browser-test exercises the Form-1 control")
      (async done
        (setup!)
        (rf/reg-view* ::form-1-panel
          (fn [label] [:div {:id "direct-class-form-1"} label]))
        (run-mount-case
          ::form-1-panel "direct-class-form-1" "plain" done
          (fn []
            (is (= "plain" (text-of "direct-class-form-1"))
                "the Form-1 view rendered")
            (is (some? (mounted-attr "direct-class-form-1" "data-rf-view"))
                "the Form-1 DOM root still carries the view tag"))
          (fn []
            (is (nil? (text-of "direct-class-form-1"))
                "the Form-1 node unmounted")))))))

(deftest outer-fn-returning-a-class-still-mounts
  (testing "the pre-existing supported shape — an outer callable that RETURNS a
            create-class — is unchanged: it is a plain render fn at registration,
            so the normalization declines it and the annotation walk's
            output-side reagent-class? guard keeps serving it"
    (if-not (browser?)
      (is true ":node-test: no DOM — :browser-test exercises the outer-fn control")
      (async done
        (setup!)
        (rf/reg-view* ::outer-fn-panel
          (fn outer [_label] (probe-class "direct-class-outer")))
        (run-mount-case
          ::outer-fn-panel "direct-class-outer" "outer" done
          (fn []
            (is (= "outer" (text-of "direct-class-outer"))
                "the outer-fn-returned class still mounts and renders")
            (is (= 1 (count (filter #(= [:mount "direct-class-outer"] %) @lifecycle)))
                "its component-did-mount still fires exactly once"))
          (fn []
            (is (= 1 (count (filter #(= [:unmount "direct-class-outer"] %) @lifecycle)))
                "its component-will-unmount still fires exactly once")))))))
