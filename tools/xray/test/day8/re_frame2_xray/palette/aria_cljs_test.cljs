(ns day8.re-frame2-xray.palette.aria-cljs-test
  "WAI-ARIA contract tests for the Xray command palette.

  The palette is the most-used keyboard surface, so a screen reader
  needs the full combobox/listbox exposure: role=\"dialog\",
  aria-modal, an aria-label on the input, role=\"listbox\",
  role=\"option\", aria-selected and aria-activedescendant.

  These tests pin that contract so a renderer cannot silently drop
  it:

    1. The dialog wrapper carries role=\"dialog\" + aria-modal=\"true\".
    2. The input carries role=\"combobox\" + aria-controls pointing at
       the results list + aria-activedescendant tracking the cursor.
    3. The results <ul> carries role=\"listbox\" + a stable id.
    4. Each result <li> carries role=\"option\" + aria-selected +
       a unique id referenced by aria-activedescendant.

  Hiccup traversal mirrors `spine-filters-cljs-test`'s pattern —
  expand-tree walks function-in-head-position nodes, then a depth-first
  search by data-testid / role / attribute resolves each assertion.

  ## Where the tree comes from

  `palette/ModalView` is an `rf.fresco/defview` BOUNDARY, so it is
  not callable outside a React render window, and `view/palette-view` is
  a PURE fn of its reads' values rather than a fn that reads for itself.
  These rows therefore build the hiccup through
  `test-helpers.palette-tree`, which mirrors the boundary's four reads in
  the same order behind the same open-gate and drives the shipped
  `palette-view` — so what is asserted below is the SHIPPED tree and
  not a parallel fixture.

  `rf/dispatch` is passed explicitly: these rows only
  read attributes and never fire a handler, so the frame-bound door the
  boundary captures is not what they are about. `palette.dispatch-
  routing-cljs-test` owns that, and takes the helper's default instead."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.test-helpers :as rf.test-helpers]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-helpers.palette-tree :as palette-tree]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

(use-fixtures :each
  ;; `make-xray-runtime-fixture` is core `make-reset-runtime-fixture` +
  ;; Xray `reset-all!` in one owner:
  ;; plain-atom adapter + the default `:all` reset tier — install/registry/
  ;; mount idempotency sentinels plus the trace-collector rings.
  (xray-test-support/make-xray-runtime-fixture))

(defn- xray-setup! []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray}))

;; ---- hiccup walker ------------------------------------------------------
;; Tests call `rf.test-helpers/find-by-testid` /
;; `rf.test-helpers/find-by-testid-prefix` directly; there is no Xray walker
;; facade. `props` is a thin alias over `rf.test-helpers/attrs`.

(def ^:private props rf.test-helpers/attrs)

;; ---- (1) dialog wrapper ARIA --------------------------------------------

(deftest dialog-wrapper-has-role-and-aria-modal
  (testing "the palette dialog wrapper is
            announced as a modal dialog (role + aria-modal + aria-label)"
    (xray-setup!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/palette-open]))
    (let [tree   (rf/with-frame :rf/xray (palette-tree/palette-tree rf/dispatch))
          dialog (rf.test-helpers/find-by-testid tree "rf-xray-palette-dialog")]
      (is (some? dialog) "the dialog wrapper renders")
      (is (= "dialog" (:role (props dialog))))
      (is (= "true"   (:aria-modal (props dialog))))
      (is (string? (:aria-label (props dialog)))
          "an accessible name is set (no visible title bar)"))))

;; ---- (2) input combobox semantics ---------------------------------------

(deftest input-is-combobox-with-listbox-controls
  (testing "the search input is a combobox: role=combobox,
            aria-label (the visible label is missing), aria-controls
            pointing at the listbox id, aria-autocomplete=list, and
            aria-expanded reflecting whether results render"
    (xray-setup!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/palette-open]))
    (let [tree  (rf/with-frame :rf/xray (palette-tree/palette-tree rf/dispatch))
          input (rf.test-helpers/find-by-testid tree "rf-xray-palette-input")
          attrs (props input)]
      (is (some? input))
      (is (= "combobox" (:role attrs)))
      (is (string? (:aria-label attrs)))
      (is (string? (:aria-controls attrs))
          ":aria-controls points at the listbox id")
      (is (= "list" (:aria-autocomplete attrs)))
      (is (contains? #{"true" "false"} (:aria-expanded attrs))))))

(deftest input-aria-controls-matches-listbox-id
  (testing "the id the input :aria-controls references is
            the same id the <ul> carries. Without this round-trip the
            screen reader cannot follow combobox -> listbox."
    (xray-setup!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/palette-open]))
    (let [tree    (rf/with-frame :rf/xray (palette-tree/palette-tree rf/dispatch))
          input   (rf.test-helpers/find-by-testid tree "rf-xray-palette-input")
          listbox (rf.test-helpers/find-by-testid tree "rf-xray-palette-list")]
      (is (= (:aria-controls (props input))
             (:id (props listbox)))
          "aria-controls round-trips to listbox id"))))

;; ---- (3) listbox semantics ----------------------------------------------

(deftest listbox-has-role-when-results-render
  (testing "the <ul> picks up role=listbox when results
            are present. The empty-state ul stays presentational so
            the user doesn't get a 'listbox, 0 items' announcement."
    (xray-setup!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/palette-open]))
    (let [tree (rf/with-frame :rf/xray (palette-tree/palette-tree rf/dispatch))
          ul   (rf.test-helpers/find-by-testid tree "rf-xray-palette-list")]
      (is (some? ul) "the list wrapper renders")
      ;; Default palette open populates results (>0 commands registered)
      ;; so the listbox role should be set.
      (is (= "listbox" (:role (props ul)))
          "non-empty results carry the listbox role"))))

;; ---- (4) option rows ----------------------------------------------------

(deftest result-rows-carry-option-role-and-aria-selected
  (testing "each result <li> is role=option with
            aria-selected matching whether the cursor is on it"
    (xray-setup!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/palette-open]))
    (let [tree (rf/with-frame :rf/xray (palette-tree/palette-tree rf/dispatch))
          rows (rf.test-helpers/find-by-testid-prefix tree "rf-xray-palette-row-")]
      (is (seq rows) "the palette renders at least one row")
      (doseq [row rows]
        (let [attrs (props row)]
          (is (= "option" (:role attrs))
              "every result row carries role=option")
          (is (contains? #{"true" "false"} (:aria-selected attrs))
              "every result row carries aria-selected (boolean string)")
          (is (string? (:id attrs))
              "every result row carries a unique id so
               aria-activedescendant can reference it"))))))

(deftest result-row-ids-are-unique
  (testing "each row id is distinct so
            aria-activedescendant always points at exactly one row"
    (xray-setup!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/palette-open]))
    (let [tree (rf/with-frame :rf/xray (palette-tree/palette-tree rf/dispatch))
          rows (rf.test-helpers/find-by-testid-prefix tree "rf-xray-palette-row-")
          ids  (mapv (comp :id props) rows)]
      (is (= (count ids) (count (set ids)))
          "every row id is unique within the rendered list"))))

(deftest active-row-aria-selected-true-matches-cursor
  (testing "the row at cursor position carries
            aria-selected=true; siblings carry false"
    (xray-setup!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/palette-open])
      (rf/dispatch-sync [:rf.xray/palette-cursor-set 0]))
    (let [tree (rf/with-frame :rf/xray (palette-tree/palette-tree rf/dispatch))
          rows (rf.test-helpers/find-by-testid-prefix tree "rf-xray-palette-row-")
          selected (filter #(= "true" (:aria-selected (props %))) rows)]
      (is (= 1 (count selected))
          "exactly one row carries aria-selected=true"))))

(deftest input-aria-activedescendant-points-at-active-row
  (testing "the input's aria-activedescendant points at
            the active row's id. Screen readers track the highlight
            without focus moving off the input."
    (xray-setup!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/palette-open])
      (rf/dispatch-sync [:rf.xray/palette-cursor-set 0]))
    (let [tree     (rf/with-frame :rf/xray (palette-tree/palette-tree rf/dispatch))
          input    (rf.test-helpers/find-by-testid tree "rf-xray-palette-input")
          rows     (rf.test-helpers/find-by-testid-prefix tree "rf-xray-palette-row-")
          active   (some (fn [row]
                           (when (= "true" (:aria-selected (props row)))
                             (:id (props row))))
                         rows)]
      (is (some? active) "an active row was found")
      (is (= active (:aria-activedescendant (props input)))
          "input's aria-activedescendant matches the active row's id"))))
