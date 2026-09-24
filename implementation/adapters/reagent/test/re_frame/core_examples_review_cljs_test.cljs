(ns re-frame.core-examples-review-cljs-test
  "Interaction and parser pins for the examples/core apps. Examples
   are test-free; this wrapper exercises their production registrations."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [notebook.core :as notebook]
            [reagent.dom.server :as rds]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.core :as rf]
            [re-frame.test-support :as rf.test-support]
            [seven-guis.cells.core :as cells]
            [seven-guis.circle-drawer.core :as drawer]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter :ambient-frame nil}))

(deftest inline-code-keeps-markdown-literal
  (doseq [literal ["**bold**" "*italic*" "[link](https://example.com)"]]
    (is (= [[:p "Before " [:code literal] " after"]]
           (notebook/markdown->hiccup (str "Before `" literal "` after")))))
  (is (= [[:p [:strong "bold"] " and " [:code "*literal*"]]]
         (notebook/markdown->hiccup "**bold** and `*literal*`"))
      "Ordinary markdown outside the code span still renders."))

(defn- formula-value [raw cell-map]
  (cells/evaluate-ast (cells/parse-formula raw) cell-map #{}))

(deftest reciprocal-zero-uses-the-spreadsheet-error
  (doseq [formula ["=(/ 0)" "=(/ (- 2 2))" "=(/ A1)" "=(+ 1 (/ 0))"]]
    (is (= :error/div-by-zero (formula-value formula {})) formula))
  (is (= 0.25 (formula-value "=(/ 4)" {})))
  (is (= 0 (formula-value "=(/ 0 4)" {}))
      "A zero numerator is valid when there is a nonzero divisor."))

(deftest padded-cell-references-read-the-visible-cell
  (let [cell-map {"A1" {:raw "7" :formula? false :ast nil :deps #{}}}]
    (is (= 8 (formula-value "=(+ A01 1)" cell-map)))
    (is (= #{"A1"} (cells/collect-deps (cells/parse-formula "=(+ A001 1)"))))))

(deftest resize-keeps-the-canvas-and-history-inert-until-close
  (let [frame (rf/make-frame {:id ::drawer :platform :client})
        send! #(rf/dispatch-sync % {:frame frame})
        read! #(rf/subscribe-once [%] {:frame frame})]
    (send! [:drawer/initialise])
    (send! [:drawer/add-circle 50 50])
    (send! [:drawer/add-circle 150 50])
    (send! [:drawer/undo])
    (is (boolean (read! :drawer/can-undo?)))
    (is (boolean (read! :drawer/can-redo?)))
    (send! [:drawer/open-dialog 1])
    (testing "Both history controls are unavailable during the resize"
      (is (not (read! :drawer/can-undo?)))
      (is (not (read! :drawer/can-redo?))))
    (let [html (rf/with-frame frame
                 (rds/render-to-static-markup [drawer/drawer-view]))]
      (is (str/includes? html "inert=\"\"")
          "The browser makes the background canvas and its controls inert."))
    (send! [:drawer/dialog-drag 60])
    (send! [:drawer/close-dialog])
    (send! [:drawer/undo])
    (is (= 30 (get-in (rf/app-db-value frame) [:drawer :circles 0 :radius]))
        "Closing commits one resize, which Undo restores in one step.")))
