(ns re-frame.adapter.uix-client-root-dom-cljs-test
  "rf2-kuky.56 — the real-DOM half of the UIx client-root contract
  (`client-root` / `render!` / `unmount!`, Spec 006 §The client root). The
  node twin (`re-frame.adapter.uix-client-root-cljs-test`) covers inert
  allocation and the element-slot guard; this one lets the shared React
  spine mount real Roots and reads the outcome off the DOM.

  WHAT THE DOM PROVES THAT A SPY CANNOT HERE. The spine mounts through the
  `react-dom/client` MODULE, so there are no Vars to `with-redefs` (the
  Reagent twin's technique). Instead each proof is read off the committed
  tree:

    1. NODE IDENTITY across a re-render is the create-once proof AND the
       Fragment-wrapper proof at once. The spine wraps every tree in a
       Fragment beside the after-render sentinel; a second `createRoot` on
       the same container, or an update rendered through a DIFFERENT
       wrapper shape, both remount the subtree and mint a new node. The
       node surviving with new text is only possible if one Root
       reconciled the same top element — which is exactly the risk
       rf2-kuky.56 named for the `:update!` path.
    2. A hydrating first render ADOPTING the server node (same node
       object) is the hydrate-once proof — `createRoot` would replace it.
    3. Unmount idempotence and mount-afresh-after-release are read off the
       container's emptiness and the node minted by the next render.

  ns ends in `-dom-cljs-test` so shadow-cljs's `:browser-test` discovers
  it; the `:node-test` runner also loads it, where each body gates on
  `(browser?)` and records a documented skip."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            ["react-dom" :as react-dom]
            [uix.core :as uix :refer-macros [defui $]]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.test-support :as rf.test-support]))

;; Async map-form fixture (the hydration row yields to React's schedule).
;; No frame is involved — the trees below are bare UIx components — so
;; `:ambient-frame nil`.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.uix/adapter :async? true :ambient-frame nil}))

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

(defui Probe [{:keys [label]}]
  ($ :div ($ :p {:data-testid "rf-uix-client-root-probe"} label)))

(defn- tree [label] ($ Probe {:label label}))

(defn- probe [el] (.querySelector el "[data-testid=\"rf-uix-client-root-probe\"]"))

;; ---- cold mount: one Root, re-renders update the SAME node ----------------

(deftest cold-render-updates-the-same-mounted-node
  (testing "a cold render! creates one Root; later render!s commit new trees
            through that same Root, reconciling the IDENTICAL DOM node"
    (if-not (browser?)
      (is true ":node-test: no DOM — the :browser-test build runs the assertions")
      (let [el (host! nil)
            h  (rf.adapter.uix/client-root)]
        (react-dom/flushSync (fn [] (rf.adapter.uix/render! h (tree "v1") el)))
        (let [node-1 (probe el)]
          (is (= "v1" (some-> node-1 .-textContent)) "first render committed v1")
          (react-dom/flushSync (fn [] (rf.adapter.uix/render! h (tree "v2") el)))
          (is (= "v2" (some-> (probe el) .-textContent)) "second render committed v2")
          (is (identical? node-1 (probe el))
              "the SAME node was updated — one Root, and the update rendered
               through the same Fragment wrapper (a second createRoot or a
               shifted child position would have remounted the subtree)")
          (react-dom/flushSync (fn [] (rf.adapter.uix/render! h (tree "v3") el)))
          (is (= "v3" (some-> (probe el) .-textContent)) "third render committed v3")
          (is (identical? node-1 (probe el)) "and still the same node")
          (is (= 1 (.-length (.-children el)))
              "one tree owns the container — updates replaced, never appended"))
        (react-dom/flushSync (fn [] (rf.adapter.uix/unmount! h)))
        (is (nil? (probe el)) "unmount! removed the tree")
        (drop-host! el)))))

;; ---- unmount is idempotent; a later render! mounts afresh -----------------

(deftest unmount-is-idempotent-and-a-later-render-mounts-afresh
  (testing "a second unmount! is a no-op, and a render! after release mints a
            NEW Root rather than rendering into the released one"
    (if-not (browser?)
      (is true ":node-test: no DOM — the :browser-test build runs the assertions")
      (let [el (host! nil)
            h  (rf.adapter.uix/client-root)]
        (react-dom/flushSync (fn [] (rf.adapter.uix/render! h (tree "v1") el)))
        (let [node-1 (probe el)]
          (react-dom/flushSync (fn [] (rf.adapter.uix/unmount! h)))
          (is (nil? (probe el)) "the first unmount! released the Root")
          (is (nil? (react-dom/flushSync (fn [] (rf.adapter.uix/unmount! h))))
              "the second unmount! is a no-op returning nil — it does not throw
               and does not reach React a second time")
          (react-dom/flushSync (fn [] (rf.adapter.uix/render! h (tree "v2") el)))
          (is (= "v2" (some-> (probe el) .-textContent))
              "a render! after release mounts afresh")
          (is (not (identical? node-1 (probe el)))
              "into a NEW Root — the released one is not reused"))
        (react-dom/flushSync (fn [] (rf.adapter.uix/unmount! h)))
        (drop-host! el)))))

;; ---- adapter teardown releases still-live handles once --------------------

(deftest destroy-adapter-releases-still-live-handles-once
  (testing "dispose-adapter! releases a still-live handle's Root, leaves an
            already-unmounted handle alone, and a render! after the drain
            mounts afresh"
    (if-not (browser?)
      (is true ":node-test: no DOM — the :browser-test build runs the assertions")
      (let [el-live (host! nil)
            el-gone (host! nil)
            live    (rf.adapter.uix/client-root)
            gone    (rf.adapter.uix/client-root)]
        (react-dom/flushSync (fn [] (rf.adapter.uix/render! live (tree "live") el-live)))
        (react-dom/flushSync (fn [] (rf.adapter.uix/render! gone (tree "gone") el-gone)))
        (react-dom/flushSync (fn [] (rf.adapter.uix/unmount! gone)))
        (is (nil? (probe el-gone)) "explicit unmount! released the second handle")
        (react-dom/flushSync (fn [] (rf.substrate.adapter/dispose-adapter!)))
        (is (nil? (probe el-live))
            "the drain released the still-live handle's Root")
        (is (nil? (react-dom/flushSync (fn [] (rf.adapter.uix/unmount! live))))
            "a later unmount! on the drained handle is a no-op, not a double
             release — liveness is read off the active set, not the handle")
        (is (nil? (react-dom/flushSync (fn [] (rf.adapter.uix/unmount! gone))))
            "and likewise for the already-unmounted one")
        ;; A render! after the drain mounts afresh (the fixture reinstalls the
        ;; adapter per test, so re-install here for the post-drain render).
        (rf.substrate.adapter/install-adapter! rf.adapter.uix/adapter)
        (react-dom/flushSync (fn [] (rf.adapter.uix/render! live (tree "again") el-live)))
        (is (= "again" (some-> (probe el-live) .-textContent))
            "a render! after dispose-adapter! mounts afresh")
        (react-dom/flushSync (fn [] (rf.adapter.uix/unmount! live)))
        (drop-host! el-live)
        (drop-host! el-gone)))))

;; ---- hydrating mount: adopt the server node, then update it --------------

(deftest hydrating-render-adopts-the-server-node-then-updates-it
  (testing "render! with {:hydrate? true} adopts the server-rendered node
            (same node object); a later render! commits the new tree with no
            second hydration, and the adopted node survives"
    (if-not (browser?)
      (is true ":node-test: no DOM — the :browser-test build runs the assertions")
      (async done
        (let [el       (host! "<div><p data-testid=\"rf-uix-client-root-probe\">v1</p></div>")
              h        (rf.adapter.uix/client-root)
              server-p (probe el)]
          (is (some? server-p) "the server node is planted before hydration")
          (rf.adapter.uix/render! h (tree "v1") el {:hydrate? true})
          ;; Hydration commits on React's schedule — observe after a yield.
          ;; The body is bracketed so a throw still reaches `done`: an uncaught
          ;; error in an async row stalls the WHOLE browser lane.
          (js/setTimeout
            (fn []
              (try
                (is (identical? server-p (probe el))
                    "hydrateRoot ADOPTED the server node (createRoot would have
                     minted a new one)")
                (react-dom/flushSync
                  (fn [] (rf.adapter.uix/render! h (tree "v2") el {:hydrate? true})))
                (is (= "v2" (some-> (probe el) .-textContent))
                    "the later render committed the new tree through the
                     hydrated Root")
                (is (identical? server-p (probe el))
                    "and updated the ADOPTED node — never hydrated again, and
                     never re-created, even though the caller kept passing
                     {:hydrate? true}")
                (catch :default e
                  (is false (str "hydrating render threw: " (pr-str e))))
                (finally
                  (try (react-dom/flushSync (fn [] (rf.adapter.uix/unmount! h)))
                       (catch :default _ nil))
                  (drop-host! el)
                  (done))))
            50))))))

;; ---- the element-slot guard covers the UPDATE path too --------------------

(deftest a-later-render-with-hiccup-is-refused-too
  (testing "the element-slot guard is on the update path, not only the mount:
            a live handle handed hiccup raises the same structured error and
            leaves the committed tree untouched"
    (if-not (browser?)
      (is true ":node-test: no DOM — the :browser-test build runs the assertions")
      (let [el (host! nil)
            h  (rf.adapter.uix/client-root)]
        (react-dom/flushSync (fn [] (rf.adapter.uix/render! h (tree "v1") el)))
        (is (= "v1" (some-> (probe el) .-textContent)) "the live tree is committed")
        (let [thrown (try (rf.adapter.uix/render! h [:div "hiccup"] el) nil
                          (catch :default e e))]
          (is (= :rf.error/hiccup-on-element-render-slot
                 (:rf.error/id (ex-data thrown)))
              "a LATER render! refuses CLJS data exactly as the first one does"))
        (is (= "v1" (some-> (probe el) .-textContent))
            "and the refused update left the committed tree alone")
        (react-dom/flushSync (fn [] (rf.adapter.uix/unmount! h)))
        (drop-host! el)))))
