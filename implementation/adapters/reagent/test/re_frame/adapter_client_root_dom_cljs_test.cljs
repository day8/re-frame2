(ns re-frame.adapter-client-root-dom-cljs-test
  "rf2-k5r9t — the real-DOM half of the Reagent client-root contract
  (`client-root` / `render!` / `unmount!`). The :node-test twin
  (`re-frame.adapter-client-root-cljs-test`) pins the call sequence at
  `reagent.dom.client` with stubs; this one lets the stock fns run against a
  real React root and reads the outcome off the DOM: one Root serves every
  render through the handle (a second `createRoot` on a live container is
  what React forbids), each render's tree is the one committed, a hydrating
  first render ADOPTS the server node instead of replacing it, and unmount
  reaches React once. (Node identity across a re-render is deliberately NOT
  asserted: stock `reagent.dom.client/render` wraps the tree in a fresh
  component closure on every call, so React remounts the subtree — the same
  as the raw-root shape; what survives a hot reload is the Root and the
  frame, not the DOM nodes.)

  The `reagent.dom.client` constructors are wrapped (call-through spies), so
  each proof also counts them: one `create-root` or one `hydrate-root` per
  handle, and never a second.

  ns ends in `-dom-cljs-test` so shadow-cljs's `:browser-test` discovers it;
  the `:node-test` runner also loads it, where each body gates on
  `(browser?)` and records a documented skip."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [reagent.dom.client :as rdc]
            ["react-dom" :as react-dom]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]))

;; Async tests need the map-form fixture. No frame is involved — the trees
;; below are plain hiccup — so `:ambient-frame nil`.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter :async? true :ambient-frame nil}))

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- host!
  "A fresh container attached to the document (hydration needs a real,
  connected node), optionally pre-filled with server markup."
  [inner-html]
  (let [el (.createElement js/document "div")]
    (when inner-html (set! (.-innerHTML el) inner-html))
    (.appendChild (.-body js/document) el)
    el))

(defn- drop-host! [el]
  (when-let [p (.-parentNode el)] (.removeChild p el)))

(defn- tree [label]
  [:div [:p {:data-testid "rf-client-root-probe"} label]])

(defn- probe [el] (.querySelector el "[data-testid=\"rf-client-root-probe\"]"))

(defn- with-counting-rdc!
  "Run `body-fn` with call-through spies on the three `reagent.dom.client`
  constructors/unmount, returning the counts atom it recorded into. The
  spies are explicit multi-arity, mirroring the published arities
  (create-root 1/2, hydrate-root 2/3, unmount 1): the adapter's lambdas
  compile to DIRECT arity calls on the original multi-arity fns, which a
  variadic `(fn [& args])` rebinding has no slot for."
  [body-fn]
  (let [counts       (atom {:create-root 0 :hydrate-root 0 :unmount 0})
        create-root  rdc/create-root
        hydrate-root rdc/hydrate-root
        unmount      rdc/unmount
        count!       (fn [k] (swap! counts update k inc))]
    (with-redefs [rdc/create-root  (fn
                                     ([m]   (count! :create-root) (create-root m))
                                     ([m o] (count! :create-root) (create-root m o)))
                  ;; The adapter calls the 2-arity; stock Reagent's 2-arity
                  ;; body re-enters its own 3-arity THROUGH THE VAR — i.e.
                  ;; through this spy — so only the entry arity counts.
                  rdc/hydrate-root (fn
                                     ([m t]   (count! :hydrate-root) (hydrate-root m t))
                                     ([m t o] (hydrate-root m t o)))
                  rdc/unmount      (fn [r] (count! :unmount) (unmount r))]
      (body-fn counts))
    @counts))

;; ---- cold mount: one Root, re-renders update the same node --------------

(deftest cold-render-updates-the-same-mounted-node
  (testing "a cold render! creates one Root; a second render! commits the new
            tree through that same Root; unmount! is idempotent"
    (if-not (browser?)
      (is true ":node-test: no DOM — the :browser-test build runs the assertions")
      (let [el (host! nil)
            h  (rf.adapter.reagent/client-root)]
        (with-counting-rdc!
          (fn [counts]
            ;; Wrap in flushSync so the React 19 render commits before we read
            ;; the DOM (mirrors the adapter flush-render DOM proof's mount).
            (react-dom/flushSync (fn [] (rf.adapter.reagent/render! h (tree "v1") el)))
            (is (= "v1" (some-> (probe el) .-textContent)) "first render committed v1")
            (react-dom/flushSync (fn [] (rf.adapter.reagent/render! h (tree "v2") el)))
            (is (= "v2" (some-> (probe el) .-textContent)) "second render committed v2")
            (is (= 1 (.-length (.-children el)))
                "one tree owns the container — the second render replaced, not appended")
            (is (= 1 (:create-root @counts))
                "create-root was called exactly once across the two renders")
            (is (= 0 (:hydrate-root @counts)) "a cold mount never hydrates")
            (react-dom/flushSync (fn [] (rf.adapter.reagent/unmount! h)))
            (is (nil? (probe el)) "unmount! removed the tree")
            (react-dom/flushSync (fn [] (rf.adapter.reagent/unmount! h)))
            (is (= 1 (:unmount @counts)) "the second unmount! reached React no second time")))
        (drop-host! el)))))

;; ---- hydrating mount: adopt the server node, then update it --------------

(deftest hydrating-render-adopts-the-server-node-then-updates-it
  (testing "render! with {:hydrate? true} adopts the server-rendered node
            (same node object, hydrate-root once); a later render! commits
            the new tree with no second hydration and no create-root"
    (if-not (browser?)
      (is true ":node-test: no DOM — the :browser-test build runs the assertions")
      (async done
        (let [el         (host! "<div><p data-testid=\"rf-client-root-probe\">v1</p></div>")
              h          (rf.adapter.reagent/client-root)
              server-p   (probe el)
              counts     (atom nil)]
          (is (some? server-p) "the server node is planted before hydration")
          (reset! counts
                  (with-counting-rdc!
                    (fn [_]
                      (rf.adapter.reagent/render! h (tree "v1") el {:hydrate? true}))))
          ;; Hydration commits on React's schedule — observe after a yield.
          ;; The body is bracketed so a throw still reaches `done`: an
          ;; uncaught error in an async row stalls the WHOLE browser lane
          ;; (shadow.test runs it inside one run-block).
          (js/setTimeout
            (fn []
              (try
              (is (identical? server-p (probe el))
                  "hydrate-root ADOPTED the server node (create-root would mint a new one)")
              (is (= 1 (:hydrate-root @counts)) "hydrate-root was called exactly once")
              (is (= 0 (:create-root @counts)) "a hydrating mount never calls create-root")
              (reset! counts
                      (with-counting-rdc!
                        (fn [_]
                          (react-dom/flushSync
                            (fn [] (rf.adapter.reagent/render! h (tree "v2") el {:hydrate? true}))))))
              (is (= "v2" (some-> (probe el) .-textContent))
                  "the later render committed the new tree through the hydrated Root")
              (is (= 0 (:hydrate-root @counts)) "no second hydration")
              (is (= 0 (:create-root @counts)) "no create-root either")
              (catch :default e
                (is false (str "hydrating render threw: " (pr-str e))))
              (finally
                (try (react-dom/flushSync (fn [] (rf.adapter.reagent/unmount! h)))
                     (catch :default _ nil))
                (drop-host! el)
                (done))))
            50))))))

;; ---- adapter teardown releases still-live handles once -------------------

(deftest destroy-adapter-releases-still-live-handles-once
  (testing "dispose-adapter! unmounts a still-live handle's Root exactly once
            and leaves an already-unmounted handle alone; later unmount!
            calls on either do nothing"
    (if-not (browser?)
      (is true ":node-test: no DOM — the :browser-test build runs the assertions")
      (let [el-live (host! nil)
            el-gone (host! nil)
            live    (rf.adapter.reagent/client-root)
            gone    (rf.adapter.reagent/client-root)]
        ;; Drain Roots earlier suites in the browser bundle may have stranded
        ;; in the adapter's singleton active set, so the unmount counts below
        ;; are this test's own.
        (rf.substrate.adapter/dispose-adapter!)
        (rf.substrate.adapter/install-adapter! rf.adapter.reagent/adapter)
        (with-counting-rdc!
          (fn [counts]
            (react-dom/flushSync (fn [] (rf.adapter.reagent/render! live (tree "live") el-live)))
            (react-dom/flushSync (fn [] (rf.adapter.reagent/render! gone (tree "gone") el-gone)))
            (react-dom/flushSync (fn [] (rf.adapter.reagent/unmount! gone)))
            (is (= 1 (:unmount @counts)) "explicit unmount! released the second handle once")
            (react-dom/flushSync (fn [] (rf.substrate.adapter/dispose-adapter!)))
            (is (= 2 (:unmount @counts))
                "the drain released the still-live handle once and the unmounted one not again")
            (is (nil? (probe el-live)) "the still-live tree is gone from the DOM")
            (react-dom/flushSync (fn [] (rf.adapter.reagent/unmount! live)))
            (react-dom/flushSync (fn [] (rf.adapter.reagent/unmount! gone)))
            (is (= 2 (:unmount @counts)) "later unmount! calls reach React no further time")))
        (drop-host! el-live)
        (drop-host! el-gone)))))
