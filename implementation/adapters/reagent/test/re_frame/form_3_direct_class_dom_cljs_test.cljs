(ns re-frame.form-3-direct-class-dom-cljs-test
  "Pins the ADVERTISED direct Form-3 shape: a `create-class` result
  handed straight to `reg-view*`, with no outer callable around it, as
  `docs/api/re-frame.core.md` documents and `core.cljc`'s function contract
  names. The two other Form-3 fixtures both register an outer fn that
  RETURNS a class.

  A reading of the source suggests the shape breaks: `build-frame-aware-view`
  ends in an unconditional `(apply render-fn args)`, a `create-class`
  constructor satisfies `fn?`, and the annotation walk's `reagent-class?`
  guard inspects the render's OUTPUT rather than the registered INPUT. On
  STOCK Reagent the shape mounts correctly all the same, and this asserts
  what that reading says would be lost: rendered props from
  `:reagent-render` under a real class instance, and exactly-once
  `:component-did-mount` / `:component-will-unmount`.

  Stock Reagent only. `reagent-slim` builds its class differently (it tags the
  constructor `cljsReagentClass` and installs `prototype.render` rather than
  `prototype.reagentRender`, and its constructor calls `React.Component` against
  `this` and returns `this`), so nothing here speaks to that substrate.

  Browser-only: the evidence is real React class construction and lifecycle
  ordering, which no headless invocation reproduces — calling the wrapper by
  hand would mistake a constructor call for a mount. The `-dom-cljs-test`
  suffix selects the `:browser-test` build; the consolidated node build loads
  the namespace and takes the no-DOM branch.

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


(defn- run-mount-case
  "Mount `[(rf/view view-id) label]` under a frame-provider, run `after-mount`,
  unmount, and run `after-unmount` on the next microtask. Calls `done` once."
  [view-id dom-id label done after-mount after-unmount]
  (let [act-fn (get-act)]
    (if-not (fn? act-fn)
      (do (is (fn? act-fn) "React flushSync missing: a broken React 19 floor, not a skip") (done))
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
  (rf/make-frame {:id frame-id :doc "direct Form-3 class fixture"}))

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
            ;; The whole contract in one assertion: called as a function,
            ;; the constructor would never produce this node.
            (is (= ["hello" [:render "hello"] 1 []]
                   [(text-of "direct-class-preinit") (first @lifecycle)
                    (count (filter #(= [:mount "direct-class-preinit"] %) @lifecycle))
                    (filterv #(= :unmount (first %)) @lifecycle)])
                "the class's :reagent-render produced the DOM node with its arg, under the class rather than as a bare constructor call; did-mount fired once and nothing unmounted"))
          (fn []
            (is (= [nil 1]
                   [(text-of "direct-class-preinit")
                    (count (filter #(= [:unmount "direct-class-preinit"] %) @lifecycle))])
                "the node is gone after unmount, and component-will-unmount fired exactly once")))))))

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
            (is (= ["world" 1]
                   [(text-of "direct-class-postinit")
                    (count (filter #(= [:mount "direct-class-postinit"] %) @lifecycle))])
                "the post-init direct class rendered its arg, and component-did-mount fired exactly once"))
          (fn []
            (is (= 1 (count (filter #(= [:unmount "direct-class-postinit"] %) @lifecycle)))
                "component-will-unmount fired exactly once")))))))
