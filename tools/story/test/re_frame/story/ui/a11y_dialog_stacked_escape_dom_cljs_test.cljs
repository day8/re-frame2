(ns re-frame.story.ui.a11y-dialog-stacked-escape-dom-cljs-test
  "rf2-3x7nj.29.6 — with two focus-trapped dialogs stacked, one Escape
  closes only the TOP one.

  The recorder stacks them on purpose: the save dialog's export button
  opens the export dialog on top without closing the save dialog. Every
  trap listens for Escape on the window, and `stopPropagation` does not stop
  other listeners on the same target, so one Escape used to close both, and
  the save dialog's recording went with it.

  The rows mount real traps through `reagent.dom.client` and dispatch a
  real `keydown` on the window.

  `-dom-cljs-test$` puts this namespace in `:browser-test` AND in
  `:node-test`; the rows need a DOM, so in Node they say so rather than
  passing quietly."
  (:require [cljs.test :refer-macros [deftest is testing]]
            ["react-dom" :as react-dom]
            [reagent.dom.client :as rdc]
            [re-frame.story.ui.a11y-dialog :as rf.story.ui.a11y-dialog]))

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- escape! []
  (.dispatchEvent js/window
                  (js/KeyboardEvent. "keydown" #js {:key "Escape" :bubbles true :cancelable true})))

(defn- dialog [closed id]
  [rf.story.ui.a11y-dialog/focus-trap
   {:on-close #(swap! closed conj id)}
   [:div {:role "dialog" :aria-label (name id)}
    [:button (name id)]]])

(defn- render! [root tree]
  (react-dom/flushSync (fn [] (rdc/render root tree))))

(deftest one-escape-closes-only-the-top-dialog
  (testing "rf2-3x7nj.29.6: the save dialog is open and the export dialog
            opens ON TOP of it. Escape closes the export dialog and leaves the
            save dialog open; the next Escape, with the export dialog gone,
            closes the save dialog"
    (if-not (browser?)
      (is true ":node-test — no DOM; :browser-test runs this row")
      (let [node   (js/document.createElement "div")
            _      (js/document.body.appendChild node)
            root   (rdc/create-root node)
            closed (atom [])]
        (try
          (render! root [:div (dialog closed :save)])
          (render! root [:div (dialog closed :save) (dialog closed :export)])
          (escape!)
          (is (= [:export] @closed)
              "only the top dialog closed — before the fix both did")
          (render! root [:div (dialog closed :save)])
          (escape!)
          (is (= [:export :save] @closed)
              "with the top dialog gone, Escape reaches the one beneath it")
          (render! root [:div])
          (escape!)
          (is (= [:export :save] @closed)
              "control: with no dialog open, Escape closes nothing")
          (finally
            (react-dom/flushSync (fn [] (.unmount root)))
            (.remove node)))))))
