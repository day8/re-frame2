(ns re-frame.ui.root-teardown-dom-cljs-test
  "rf2-vxgfnd.62 — the ROOT teardown proof under REAL React + real DOM.

  `reactive-root-teardown-cljs-test` graft-checks the window logic on both
  hosts and `root-teardown-wiring-cljs-test` pins the client kernel with a fake
  root; this file closes the loop end-to-end: a real `ui/mount` then
  `ui/unmount!`, asserting the mounted ViewCell followed the 03 §4 dead-cell
  lifecycle. It proves the load-bearing runtime assumption of the whole
  approach — that React fires each cell's layout-effect cleanup SYNCHRONOUSLY
  inside `root.unmount()`, so the teardown window catches the real cells and a
  real root unmount ends `:unmounted {:proof :host-teardown}` → `:dead` rather
  than stuck at the transient `:disconnected {:reason :unknown}`.

  Browser-only bodies — the `-dom-cljs-test$` suffix opts this file into the
  `:browser-test` build; `:node-test` loads it too, where the DOM body gates on
  `(browser?)` and no-ops. The UIx function-component spine is the watchable
  substrate the other browser fixtures use; here it simply provides a real
  React mount surface for a compiled `defview`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            ["react-dom" :as react-dom]
            [re-frame.core :as rf]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui :refer [defview frame-provider sub]]
            [re-frame.ui.client :as client]
            [re-frame.ui.reactive :as reactive]
            ;; Load-bearing: the CLJS emitter wraps a sub-bearing view in
            ;; `viewcell/render`, so a consumer must load the runtime.
            [re-frame.ui.viewcell]))

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
   {:adapter uix-adapter/adapter :ambient-frame nil})
  (fn [f]
    (reactive/reset-scheduler!)
    (client/reset-live-roots!)
    (try (f)
         (finally (reactive/reset-scheduler!) (client/reset-live-roots!)))))

(def ^:private frame-kw :rt.dom/frame)

(defn- container [] (js/document.createElement "div"))

;; One sub-bearing view → one ViewCell under the mounted root.
(defview leaf [_] [:span.leaf (str (sub [:rt.dom/n]))])

(defn- register-app! []
  (rf/make-frame {:id frame-kw :doc "root-teardown DOM probe frame"})
  (rf/reg-sub :rt.dom/n (fn [db _] (:n db)))
  (rf/reg-event :rt.dom/seed (fn [_ _] {:db {:n 7}})))

(deftest real-root-unmount-proves-cell-unmounted-then-dead
  (if-not (browser?)
    (is true ":node — no DOM; the :browser-test runner exercises the DOM body")
    (do
      (register-app!)
      (rf/dispatch-sync [:rt.dom/seed] {:frame frame-kw})
      (let [c    (container)
            root (react-dom/flushSync
                  #(ui/mount [frame-provider {:frame frame-kw} [leaf {}]]
                             c {:root-id :rt.dom/root}))
            cells (reactive/current-live-cells)
            cell  (first cells)]
        (testing "the sub-bearing view mounted one connected ViewCell"
          (is (= "7" (.-textContent (.querySelector c ".leaf"))))
          (is (= 1 (count cells)) "exactly one live ViewCell under the root")
          (is (= :connected (reactive/lifecycle cell))))
        (testing "a REAL root unmount proves the cell :unmounted → :dead"
          (react-dom/flushSync #(ui/unmount! root))
          (is (= :dead (reactive/lifecycle cell))
              "not stuck at the transient :unknown — the host teardown upgraded it")
          (is (= :unmounted (:reason (peek (reactive/intervals cell))))
              "the interval carries the :unmounted reason")
          (is (= :host-teardown (:proof (peek (reactive/intervals cell))))
              "…proven by the host teardown (03 §4)"))
        (testing "the container is re-mountable — the claim was released"
          (is (= #{} (client/live-root-ids))))))))

(deftest real-root-unmount-isolates-a-sibling-root
  (if-not (browser?)
    (is true ":node — no DOM; the :browser-test runner exercises the DOM body")
    (do
      (register-app!)
      (rf/dispatch-sync [:rt.dom/seed] {:frame frame-kw})
      (let [ca    (container)
            cb    (container)
            root-a (react-dom/flushSync
                    #(ui/mount [frame-provider {:frame frame-kw} [leaf {}]]
                               ca {:root-id :rt.dom/root-a}))
            root-b (react-dom/flushSync
                    #(ui/mount [frame-provider {:frame frame-kw} [leaf {}]]
                               cb {:root-id :rt.dom/root-b}))]
        ;; two roots, two cells; root B still renders after root A unmounts
        (is (= 2 (count (reactive/current-live-cells))) "two live cells")
        (let [before (vec (reactive/current-live-cells))]
          (react-dom/flushSync #(ui/unmount! root-a))
          (testing "root A's cell is dead; root B's cell is untouched"
            (let [alive (filter #(= :connected (reactive/lifecycle %)) before)
                  dead  (filter #(= :dead (reactive/lifecycle %)) before)]
              (is (= 1 (count dead)) "exactly one cell died")
              (is (= 1 (count alive)) "exactly one cell still connected")
              (is (= "7" (.-textContent (.querySelector cb ".leaf")))
                  "root B still renders")))
          (react-dom/flushSync #(ui/unmount! root-b)))))))
