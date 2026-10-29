(ns re-frame.adapter.uix-client-root-dom-cljs-test
  "Client roots update in place, adopt server DOM, and release subscriptions on teardown."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            ["react-dom" :as react-dom]
            [uix.core :refer-macros [defui $]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.test-support :as rf.test-support]
            [re-frame.adapter.react-test-support :refer [browser?]]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.uix/adapter :async? true :ambient-frame nil}))

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
          (is (= ["v2" true 1]
                 [(some-> (probe el) .-textContent) (identical? node-1 (probe el))
                  (.-length (.-children el))])
              "still the same node, and one tree owns the container — updates replaced, never appended"))
        (react-dom/flushSync (fn [] (rf.adapter.uix/unmount! h)))
        (is (nil? (probe el)) "unmount! removed the tree")
        (drop-host! el)))))

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
          (is (= ["v2" false] [(some-> (probe el) .-textContent) (identical? node-1 (probe el))])
              "a render! after release mounts afresh, into a NEW Root"))
        (react-dom/flushSync (fn [] (rf.adapter.uix/unmount! h)))
        (drop-host! el)))))

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
        (is (= [nil nil]
               [(react-dom/flushSync (fn [] (rf.adapter.uix/unmount! live)))
                (react-dom/flushSync (fn [] (rf.adapter.uix/unmount! gone)))])
            "a later unmount! on the drained or the already-unmounted handle is a no-op — liveness is read off the active set, not the handle")
        (rf.substrate.adapter/install-adapter! rf.adapter.uix/adapter)
        (react-dom/flushSync (fn [] (rf.adapter.uix/render! live (tree "again") el-live)))
        (is (= "again" (some-> (probe el-live) .-textContent))
            "a render! after dispose-adapter! mounts afresh")
        (react-dom/flushSync (fn [] (rf.adapter.uix/unmount! live)))
        (drop-host! el-live)
        (drop-host! el-gone)))))

(def ^:private sub-frame ::sub-frame)

;; The teardown must meet a committed subscription, not only a bare root.
(defui SubProbe []
  (let [n (rf.adapter.uix/use-sub [::n] {:frame sub-frame})]
    ($ :div ($ :p {:data-testid "rf-uix-client-root-probe"} (str "n=" n)))))

(deftest destroy-adapter-over-a-mounted-use-sub-returns-nil
  (testing "rf/destroy-adapter! over a still-mounted root holding a committed
            use-sub returns nil, unmounts the root and rebuilds nothing"
    (if-not (browser?)
      (is true ":node-test: no DOM — the :browser-test build runs the assertions")
      (let [el (host! nil)
            h  (rf.adapter.uix/client-root)]
        (rf/make-frame {:id sub-frame})
        (rf/reg-event ::seed (fn [_ _] {:db {:n 1}}))
        (rf/reg-event ::inc (fn [{:keys [db]} _] {:db (update db :n inc)}))
        (rf/reg-sub ::n (fn [db _] (:n db)))
        (rf/dispatch-sync [::seed] {:frame sub-frame})
        (react-dom/flushSync (fn [] (rf.adapter.uix/render! h ($ SubProbe) el)))
        (is (= "n=1" (some-> (probe el) .-textContent))
            "precondition: the use-sub rendered")
        (react-dom/flushSync (fn [] (rf/dispatch-sync [::inc] {:frame sub-frame})))
        (is (= "n=2" (some-> (probe el) .-textContent))
            "precondition: the hook's store subscription is COMMITTED — a dispatch
             re-rendered it, so its reacquisition callback is armed on the
             cached reaction the drain is about to dispose")
        (let [outcome (try (react-dom/flushSync (fn [] (rf/destroy-adapter!)))
                           (catch :default e e))]
          (is (= [nil nil {}]
                 [outcome (probe el) @(:sub-cache (rf.frame/frame sub-frame))])
              (str "[destroy-adapter!-result probe sub-cache]: the drain returned without"
                   " throwing, unmounted the root and rebuilt nothing — got " (pr-str outcome))))
        (drop-host! el)))))

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
          ;; Yield to hydration; finally must reach done even when an assertion throws.
          (js/setTimeout
            (fn []
              (try
                (is (identical? server-p (probe el))
                    "hydrateRoot ADOPTED the server node (createRoot would have
                     minted a new one)")
                (react-dom/flushSync
                  (fn [] (rf.adapter.uix/render! h (tree "v2") el {:hydrate? true})))
                (is (= ["v2" true] [(some-> (probe el) .-textContent) (identical? server-p (probe el))])
                    "the later render updated the ADOPTED node — never hydrated again or re-created, though the caller kept passing {:hydrate? true}")
                (catch :default e
                  (is false (str "hydrating render threw: " (pr-str e))))
                (finally
                  (try (react-dom/flushSync (fn [] (rf.adapter.uix/unmount! h)))
                       (catch :default _ nil))
                  (drop-host! el)
                  (done))))
            50))))))

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
          (is (= [:rf.error/hiccup-on-element-render-slot "v1"]
                 [(:rf.error/id (ex-data thrown)) (some-> (probe el) .-textContent)])
              "a LATER render! refuses CLJS data as the first one does, leaving the committed tree alone"))
        (react-dom/flushSync (fn [] (rf.adapter.uix/unmount! h)))
        (drop-host! el)))))
