(ns day8.re-frame2-causa.palette.aria-cljs-test
  "WAI-ARIA contract tests for the Causa command palette (rf2-tt7ax).

  The audit (`ai/findings/2026-05-20-causa-story-a11y-audit.md`
  Finding #1) flagged the palette as the most-used keyboard surface
  with the WORST screen-reader exposure — no role=\"dialog\", no
  aria-modal, no aria-label on the input, no role=\"listbox\", no
  role=\"option\", no aria-selected, no aria-activedescendant.

  These tests freeze the post-fix contract so future renderers cannot
  silently regress:

    1. The dialog wrapper carries role=\"dialog\" + aria-modal=\"true\".
    2. The input carries role=\"combobox\" + aria-controls pointing at
       the results list + aria-activedescendant tracking the cursor.
    3. The results <ul> carries role=\"listbox\" + a stable id.
    4. Each result <li> carries role=\"option\" + aria-selected +
       a unique id referenced by aria-activedescendant.

  Hiccup traversal mirrors `spine-filters-cljs-test`'s pattern —
  expand-tree walks function-in-head-position nodes, then a depth-first
  search by data-testid / role / attribute resolves each assertion."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.palette.view :as view]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.test-support :as causa-test-support]))

(defn- causa-init! []
  (causa-test-support/reset-all!))

(use-fixtures :each
  (test-support/reset-runtime-fixture-factory
    {:adapter plain-atom/adapter
     :init-fn causa-init!}))

(defn- causa-setup! []
  (registry/register-causa-handlers!)
  (frame/reg-frame :rf/causa {}))

(declare expand-tree)

(defn- expand-tree [tree]
  (cond
    (and (vector? tree) (fn? (first tree)))
    (expand-tree (apply (first tree) (rest tree)))

    (vector? tree)
    (mapv expand-tree tree)

    (seq? tree)
    (map expand-tree tree)

    :else tree))

(defn- hiccup-seq [tree]
  (let [expanded (expand-tree tree)]
    (tree-seq (some-fn vector? seq?) seq expanded)))

(defn- find-by-testid [tree testid]
  (some (fn [node]
          (when (and (vector? node)
                     (map? (second node))
                     (= testid (:data-testid (second node))))
            node))
        (hiccup-seq tree)))

(defn- find-all-by-testid-prefix [tree prefix]
  (filter (fn [node]
            (when (and (vector? node)
                       (map? (second node)))
              (let [t (:data-testid (second node))]
                (and (string? t)
                     (.startsWith t prefix)))))
          (hiccup-seq tree)))

(defn- props [hiccup]
  (when (and (vector? hiccup) (map? (second hiccup)))
    (second hiccup)))

;; ---- (1) dialog wrapper ARIA --------------------------------------------

(deftest dialog-wrapper-has-role-and-aria-modal
  (testing "rf2-tt7ax / audit #1 — the palette dialog wrapper is
            announced as a modal dialog (role + aria-modal + aria-label)"
    (causa-setup!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/palette-open]))
    (let [tree   (rf/with-frame :rf/causa (view/palette-view))
          dialog (find-by-testid tree "rf-causa-palette-dialog")]
      (is (some? dialog) "the dialog wrapper renders")
      (is (= "dialog" (:role (props dialog))))
      (is (= "true"   (:aria-modal (props dialog))))
      (is (string? (:aria-label (props dialog)))
          "an accessible name is set (no visible title bar)"))))

;; ---- (2) input combobox semantics ---------------------------------------

(deftest input-is-combobox-with-listbox-controls
  (testing "rf2-tt7ax — the search input is a combobox: role=combobox,
            aria-label (the visible label is missing), aria-controls
            pointing at the listbox id, aria-autocomplete=list, and
            aria-expanded reflecting whether results render"
    (causa-setup!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/palette-open]))
    (let [tree  (rf/with-frame :rf/causa (view/palette-view))
          input (find-by-testid tree "rf-causa-palette-input")
          attrs (props input)]
      (is (some? input))
      (is (= "combobox" (:role attrs)))
      (is (string? (:aria-label attrs)))
      (is (string? (:aria-controls attrs))
          ":aria-controls points at the listbox id")
      (is (= "list" (:aria-autocomplete attrs)))
      (is (contains? #{"true" "false"} (:aria-expanded attrs))))))

(deftest input-aria-controls-matches-listbox-id
  (testing "rf2-tt7ax — the id the input :aria-controls references is
            the same id the <ul> carries. Without this round-trip the
            screen reader cannot follow combobox -> listbox."
    (causa-setup!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/palette-open]))
    (let [tree    (rf/with-frame :rf/causa (view/palette-view))
          input   (find-by-testid tree "rf-causa-palette-input")
          listbox (find-by-testid tree "rf-causa-palette-list")]
      (is (= (:aria-controls (props input))
             (:id (props listbox)))
          "aria-controls round-trips to listbox id"))))

;; ---- (3) listbox semantics ----------------------------------------------

(deftest listbox-has-role-when-results-render
  (testing "rf2-tt7ax — the <ul> picks up role=listbox when results
            are present. The empty-state ul stays presentational so
            the user doesn't get a 'listbox, 0 items' announcement."
    (causa-setup!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/palette-open]))
    (let [tree (rf/with-frame :rf/causa (view/palette-view))
          ul   (find-by-testid tree "rf-causa-palette-list")]
      (is (some? ul) "the list wrapper renders")
      ;; Default palette open populates results (>0 commands registered)
      ;; so the listbox role should be set.
      (is (= "listbox" (:role (props ul)))
          "non-empty results carry the listbox role"))))

;; ---- (4) option rows ----------------------------------------------------

(deftest result-rows-carry-option-role-and-aria-selected
  (testing "rf2-tt7ax — each result <li> is role=option with
            aria-selected matching whether the cursor is on it"
    (causa-setup!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/palette-open]))
    (let [tree (rf/with-frame :rf/causa (view/palette-view))
          rows (find-all-by-testid-prefix tree "rf-causa-palette-row-")]
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
  (testing "rf2-tt7ax — each row id is distinct so
            aria-activedescendant always points at exactly one row"
    (causa-setup!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/palette-open]))
    (let [tree (rf/with-frame :rf/causa (view/palette-view))
          rows (find-all-by-testid-prefix tree "rf-causa-palette-row-")
          ids  (mapv (comp :id props) rows)]
      (is (= (count ids) (count (set ids)))
          "every row id is unique within the rendered list"))))

(deftest active-row-aria-selected-true-matches-cursor
  (testing "rf2-tt7ax — the row at cursor position carries
            aria-selected=true; siblings carry false"
    (causa-setup!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/palette-open])
      (rf/dispatch-sync [:rf.causa/palette-cursor-set 0]))
    (let [tree (rf/with-frame :rf/causa (view/palette-view))
          rows (find-all-by-testid-prefix tree "rf-causa-palette-row-")
          selected (filter #(= "true" (:aria-selected (props %))) rows)]
      (is (= 1 (count selected))
          "exactly one row carries aria-selected=true"))))

(deftest input-aria-activedescendant-points-at-active-row
  (testing "rf2-tt7ax — the input's aria-activedescendant points at
            the active row's id. Screen readers track the highlight
            without focus moving off the input."
    (causa-setup!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/palette-open])
      (rf/dispatch-sync [:rf.causa/palette-cursor-set 0]))
    (let [tree     (rf/with-frame :rf/causa (view/palette-view))
          input    (find-by-testid tree "rf-causa-palette-input")
          rows     (find-all-by-testid-prefix tree "rf-causa-palette-row-")
          active   (some (fn [row]
                           (when (= "true" (:aria-selected (props row)))
                             (:id (props row))))
                         rows)]
      (is (some? active) "an active row was found")
      (is (= active (:aria-activedescendant (props input)))
          "input's aria-activedescendant matches the active row's id"))))
