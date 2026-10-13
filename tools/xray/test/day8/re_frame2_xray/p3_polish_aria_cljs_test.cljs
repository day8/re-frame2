(ns day8.re-frame2-xray.p3-polish-aria-cljs-test
  "a11y polish contract tests, in three groups:

    - Xray shell root carries `role=\"region\"` +
      `aria-label`; L4 detail-panel carries `role=\"tabpanel\"` +
      `aria-labelledby` linking back to the active L3 tab button.
      Mirrored on the Static surface's L4 panel.

    - Settings popup inner tabs render as a WAI-ARIA tab
      group: tab-strip wrapper has `role=\"tablist\"` +
      `aria-label`, each tab button has `role=\"tab\"` +
      `aria-selected` + `id` + `aria-controls`; the body wrapper has
      `role=\"tabpanel\"` + `id` + `aria-labelledby` pointing at the
      active tab. General's epoch-history `<label>` ↔ `<input>` pair
      carries `:html-for` ↔ `:id`.

    - The decorative `●` REDACTED prefix carries `aria-hidden=\"true\"`.

  The frame-switcher picker's aria-label and the resize handle's
  aria-valuemax are asserted beside the rest of their markup, in
  `frame-switcher-cljs-test` and `resize-handle-cljs-test`.

  All assertions walk the view's hiccup tree by `data-testid` /
  attribute presence rather than mounting to a DOM — same approach
  the `modals-aria-cljs-test` / `shell-cljs-test` files use."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.test-helpers :as rf.test-helpers]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-helpers.modal-trees :as modal-trees]
            [day8.re-frame2-xray.test-helpers.dynamic-shell-tree
             :as dynamic-shell-tree]
            [day8.re-frame2-xray.test-helpers.static-shell-tree
             :as static-shell-tree]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

;; ---- fixture ------------------------------------------------------------

(use-fixtures :each
  (xray-test-support/make-xray-runtime-fixture {:tier :runtime}))

(defn- xray-setup! []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray}))

;; ---- hiccup helpers -----------------------------------------------------
;; `hiccup-seq`: depth-first nodes over the expanded tree.
(defn- hiccup-seq [tree]
  (tree-seq (some-fn vector? seq?) seq (rf.test-helpers/expand-tree tree)))

(def ^:private props rf.test-helpers/attrs)

;; -------------------------------------------------------------------------
;; (1) shell root landmark
;; -------------------------------------------------------------------------

(deftest shell-root-is-a-labelled-region-landmark
  ;; AT users reach the shell through the landmark cycle.
  (xray-setup!)
  (rf/with-frame :rf/xray
    (let [attrs (props (rf.test-helpers/find-by-testid (dynamic-shell-tree/shell-view-tree) "rf-xray-shell"))]
      (is (= ["region" "Xray devtools"] [(:role attrs) (:aria-label attrs)])))))

;; -------------------------------------------------------------------------
;; (2) Dynamic L3 tabs + L4 tabpanel id round-trip
;; -------------------------------------------------------------------------

(deftest runtime-tabs-and-panel-close-the-aria-loop
  (xray-setup!)
  (rf/with-frame :rf/xray
    (let [tree  (dynamic-shell-tree/shell-view-tree)
          tab   (props (rf.test-helpers/find-by-testid tree "rf-xray-tab-epoch"))
          panel (props (rf.test-helpers/find-by-testid tree "rf-xray-detail-panel-epoch"))]
      (is (= ["rf-xray-tab-button-epoch" "rf-xray-tabpanel-epoch"
              "tabpanel" "rf-xray-tabpanel-epoch" "rf-xray-tab-button-epoch"]
             [(:id tab) (:aria-controls tab)
              (:role panel) (:id panel) (:aria-labelledby panel)])
          "the default tab controls the panel, which is labelled by the tab"))))

(deftest static-tabs-and-panel-close-the-aria-loop
  (xray-setup!)
  (rf/with-frame :rf/xray
    (let [tree  (static-shell-tree/surface-tree)
          tab   (props (rf.test-helpers/find-by-testid tree "rf-xray-static-tab-machines"))
          panel (props (rf.test-helpers/find-by-testid tree "rf-xray-static-detail-panel-machines"))]
      (is (= ["rf-xray-static-tab-button-machines" "rf-xray-static-tabpanel-machines"
              "tabpanel" "rf-xray-static-tab-button-machines"]
             [(:id tab) (:aria-controls tab) (:role panel) (:aria-labelledby panel)])
          "the Static surface mirrors the Dynamic loop on its default tab"))))

;; -------------------------------------------------------------------------
;; (3) Settings tab strip ARIA + tabpanel
;; -------------------------------------------------------------------------

(deftest settings-tabs-form-a-labelled-wai-aria-tab-group
  (xray-setup!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/settings-open]))
  (let [tree    (rf/with-frame :rf/xray (modal-trees/settings-popup-tree rf/dispatch))
        attrs   #(props (rf.test-helpers/find-by-testid tree %))
        strip   (attrs "rf-xray-settings-tab-strip")
        tab-ids [:general :keybindings :buffer :diff]]
    (is (= ["tablist" true] [(:role strip) (boolean (seq (:aria-label strip)))])
        "the strip is a labelled tablist")
    (is (= (into {} (for [tid tab-ids]
                      [tid ["tab" (if (= tid :general) "true" "false")
                            (str "rf-xray-settings-tab-button-" (name tid))
                            (str "rf-xray-settings-tabpanel-" (name tid))]]))
           (into {} (for [tid tab-ids]
                      [tid ((juxt :role :aria-selected :id :aria-controls)
                            (attrs (str "rf-xray-settings-tab-" (name tid))))])))
        "each tab is a WAI-ARIA tab controlling its panel; :general is the default")
    (is (= ["tabpanel" "rf-xray-settings-tabpanel-general" "rf-xray-settings-tab-button-general"]
           ((juxt :role :id :aria-labelledby) (attrs "rf-xray-settings-body")))
        "the body is the active tab's panel, labelled by that tab")))

(deftest settings-epoch-history-label-associates-with-input
  (testing "the epoch-history slider's <input :id> matches a
            <label :html-for> so clicking the label focuses the input
            — epoch-history is the slider in General that carries the
            documented `:html-for` ↔ `:id` pair."
    (xray-setup!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/settings-open]))
    (let [tree  (rf/with-frame :rf/xray (modal-trees/settings-popup-tree rf/dispatch))
          input (rf.test-helpers/find-by-testid tree "rf-xray-settings-epoch-history-input")
          ;; The label sits in the same <div> field; find by html-for.
          label (some (fn [node]
                        (when (and (vector? node)
                                   (map? (second node))
                                   (= "rf-xray-settings-epoch-history-input"
                                      (:html-for (second node))))
                          node))
                      (hiccup-seq tree))]
      (is (= ["rf-xray-settings-epoch-history-input" true]
             [(:id (props input)) (some? label)])
          "clicking the label focuses the input"))))

;; -------------------------------------------------------------------------
;; (4) decorative glyph aria-hidden
;; -------------------------------------------------------------------------

(deftest redacted-glyph-is-aria-hidden
  (testing "the leading `●` glyph on the REDACTED
            indicator carries aria-hidden so AT does not announce the
            unicode name."
    (xray-setup!)
    ;; Force the counter positive so the indicator renders.
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/note-sensitive-suppressed {:rf/default 1}]))
    (rf/with-frame :rf/xray
      (let [tree      (dynamic-shell-tree/shell-view-tree)
            indicator (rf.test-helpers/find-by-testid tree "rf-xray-redacted-indicator")
            ;; the glyph is the first <span> child carrying aria-hidden
            glyph     (some (fn [node]
                              (when (and (vector? node)
                                         (= :span (first node))
                                         (map? (second node))
                                         (= "true" (:aria-hidden (second node))))
                                node))
                            (hiccup-seq indicator))]
        (is (some? glyph)
            "the decorative `●` glyph carries aria-hidden=\"true\"")))))
