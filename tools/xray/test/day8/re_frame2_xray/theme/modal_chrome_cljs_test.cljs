(ns day8.re-frame2-xray.theme.modal-chrome-cljs-test
  "The backdrop's click-outside dismiss, read off `modal-chrome`'s hiccup.

  A press and release on different elements click their nearest common
  ancestor, so a text selection dragged out of the dialog and released
  over the backdrop clicks the backdrop. Only a click whose press also
  began on the backdrop dismisses. The handlers are driven with stub
  events, so no DOM is needed; `modal-chrome-dom-cljs-test` covers the
  focus contract under a real React root."
  (:require [cljs.test :refer-macros [deftest is]]
            [day8.re-frame2-xray.theme.modal-chrome :as modal-chrome]))

(defn- backdrop
  "The backdrop's props, with `on-dismiss` counting into `dismissed`."
  [dismissed]
  (second (modal-chrome/modal-chrome
            {:positioning    :fixed
             :backdrop-style {}
             :dialog-style   {}
             :on-dismiss     #(swap! dismissed inc)
             :label          "Witness"
             :dialog-ref     false}
            [:p "body"])))

(defn- event
  "A mouse event stub whose `target` is `target` and whose
  `currentTarget` is `current`."
  [target current]
  #js {:target target :currentTarget current :stopPropagation (fn [])})

(defn- press-then-click!
  "Press on `press-target`, then click the backdrop — the click lands on
  the backdrop itself whichever element the press began on."
  [props press-target]
  (let [backdrop-node #js {}]
    ((:on-mouse-down props) (event (or press-target backdrop-node) backdrop-node))
    ((:on-click props) (event backdrop-node backdrop-node))))

(deftest a-press-inside-the-dialog-released-over-the-backdrop-does-not-dismiss
  (let [dismissed (atom 0)
        props     (backdrop dismissed)]
    (press-then-click! props #js {})
    (is (= 0 @dismissed) "a selection dragged out of the dialog keeps it open")
    (press-then-click! props nil)
    (is (= 1 @dismissed) "a press and click both on the backdrop dismiss")))

(deftest a-click-with-no-recorded-press-dismisses
  ;; A click with no mousedown before it (a synthetic click) still
  ;; dismisses, and a press inside counts for the one click it precedes.
  (let [dismissed (atom 0)
        props     (backdrop dismissed)
        node      #js {}]
    ((:on-click props) (event node node))
    ((:on-mouse-down props) (event #js {} node))
    ((:on-click props) (event node node))
    ((:on-click props) (event node node))
    (is (= 2 @dismissed))))
