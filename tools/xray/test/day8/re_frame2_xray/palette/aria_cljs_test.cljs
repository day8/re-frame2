(ns day8.re-frame2-xray.palette.aria-cljs-test
  "WAI-ARIA contract of the Xray command palette: a modal dialog holding a
  combobox input that controls a listbox of option rows, with
  aria-activedescendant tracking the cursor.

  `palette/ModalView` is a fresco boundary, so the tree comes from
  `test-helpers.palette-tree`, which mirrors the boundary's reads and
  drives the shipped pure `view/palette-view`."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.test-helpers :as rf.test-helpers]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-helpers.palette-tree :as palette-tree]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

(use-fixtures :each (xray-test-support/make-xray-runtime-fixture))

(defn- open-palette-tree []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray})
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/palette-open])
    (palette-tree/palette-tree rf/dispatch)))

(defn- props-of [tree testid]
  (rf.test-helpers/attrs (rf.test-helpers/find-by-testid tree testid)))

(deftest dialog-input-and-listbox-carry-the-combobox-contract
  (let [tree   (open-palette-tree)
        dialog (props-of tree "rf-xray-palette-dialog")
        input  (props-of tree "rf-xray-palette-input")
        ul     (props-of tree "rf-xray-palette-list")]
    (is (= ["dialog" "true"] ((juxt :role :aria-modal) dialog)))
    (is (string? (:aria-label dialog)))
    (is (= ["combobox" "list"] ((juxt :role :aria-autocomplete) input)))
    (is (string? (:aria-label input)))
    (is (contains? #{"true" "false"} (:aria-expanded input)))
    (is (= "listbox" (:role ul)) "non-empty results carry the listbox role")
    (is (string? (:id ul)))
    (is (= (:id ul) (:aria-controls input))
        "aria-controls round-trips to the listbox id")))

(deftest input-aria-activedescendant-points-at-active-row
  (let [tree     (open-palette-tree)
        input    (props-of tree "rf-xray-palette-input")
        rows     (map rf.test-helpers/attrs
                      (rf.test-helpers/find-by-testid-prefix tree "rf-xray-palette-row-"))
        selected (filter #(= "true" (:aria-selected %)) rows)]
    (is (= 1 (count selected)) "exactly one row carries aria-selected=true")
    (is (= (:id (first selected)) (:aria-activedescendant input)))
    (is (= #{"option"} (set (map :role rows))))
    (is (every? #{"true" "false"} (map :aria-selected rows)))
    (is (every? string? (map :id rows)))
    (is (apply distinct? (map :id rows)) "row ids are unique")))
