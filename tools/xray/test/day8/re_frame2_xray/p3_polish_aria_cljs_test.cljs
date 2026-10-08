(ns day8.re-frame2-xray.p3-polish-aria-cljs-test
  "a11y polish contract tests, in four groups:

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

    - Xray frame-switcher native `<select>` carries an
      explicit `aria-label`.

    - Xray resize-handle carries `aria-valuemax`; the decorative
      `●` REDACTED prefix carries `aria-hidden=\"true\"`.

  All assertions walk the view's hiccup tree by `data-testid` /
  attribute presence rather than mounting to a DOM — same approach
  the `modals-aria-cljs-test` / `shell-cljs-test` files use."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.test-helpers :as rf.test-helpers]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.resize-handle :as resize-handle]
            [day8.re-frame2-xray.test-helpers.modal-trees :as modal-trees]
            [day8.re-frame2-xray.test-helpers.dynamic-shell-tree
             :as dynamic-shell-tree]
            [day8.re-frame2-xray.test-helpers.static-shell-tree
             :as static-shell-tree]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.trace-collector :as trace-collector]))

;; ---- fixture ------------------------------------------------------------

(use-fixtures :each
  ;; `make-xray-runtime-fixture` is the one reset owner: plain-atom adapter
  ;; + the `:runtime` reset tier (sentinels + trace-collector rings +
  ;; persisted settings). (`trace-collector` is required for the
  ;; `seed-trace-for-test!` seeding below.)
  (xray-test-support/make-xray-runtime-fixture {:tier :runtime}))

(defn- xray-setup! []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray}))

;; ---- hiccup walker (mirrors shell-cljs-test) ----------------------------

;; ---- hiccup helpers -----------------------------------------------------
;; Tests call `rf.test-helpers/find-by-testid` directly; there is no Xray
;; walker facade. `hiccup-seq` (depth-first nodes over the expanded tree) is
;; not exposed by test-helpers, so it is a thin wrapper over
;; `rf.test-helpers/expand-tree` for the option/string-leaf filters below.
;; `props` aliases `rf.test-helpers/attrs`.
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

(deftest settings-tab-strip-is-a-labelled-tablist
  (xray-setup!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/settings-open]))
  (let [attrs (props (rf.test-helpers/find-by-testid
                       (rf/with-frame :rf/xray (modal-trees/settings-popup-tree rf/dispatch))
                       "rf-xray-settings-tab-strip"))]
    (is (= ["tablist" true] [(:role attrs) (boolean (seq (:aria-label attrs)))]))))

(deftest settings-tab-buttons-carry-tab-aria
  (xray-setup!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/settings-open]))
  (let [tree    (rf/with-frame :rf/xray (modal-trees/settings-popup-tree rf/dispatch))
        tab-ids [:general :keybindings :buffer :diff]
        aria    (fn [tid]
                  ((juxt :role :aria-selected :id :aria-controls)
                   (props (rf.test-helpers/find-by-testid tree (str "rf-xray-settings-tab-" (name tid))))))]
    (is (= (into {} (for [tid tab-ids]
                      [tid ["tab" (if (= tid :general) "true" "false")
                            (str "rf-xray-settings-tab-button-" (name tid))
                            (str "rf-xray-settings-tabpanel-" (name tid))]]))
           (into {} (for [tid tab-ids] [tid (aria tid)])))
        "each tab is a WAI-ARIA tab controlling its panel; :general is the default")))

(deftest settings-body-is-a-labelled-tabpanel
  (xray-setup!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/settings-open]))
  (let [attrs (props (rf.test-helpers/find-by-testid
                       (rf/with-frame :rf/xray (modal-trees/settings-popup-tree rf/dispatch))
                       "rf-xray-settings-body"))]
    (is (= ["tabpanel" "rf-xray-settings-tabpanel-general" "rf-xray-settings-tab-button-general"]
           ((juxt :role :id :aria-labelledby) attrs))
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
;; (4) frame-switcher aria-label
;; -------------------------------------------------------------------------

(defn- seed-trace! [dispatch-id frame-id]
  ;; Mirrors `frame_switcher_cljs_test/dispatch-trace` — seed the
  ;; trace-bus directly so the cascades sub composes a list with the
  ;; right frame ids without dispatching real events.
  (trace-collector/seed-trace-for-test!
    {:id          dispatch-id
     :op-type     :rf.event
     :operation   :rf.event/dispatched
     :tags        {:rf.event/v       [:app/touch]
                   :frame       frame-id
                   :rf.trace/dispatch-id dispatch-id}}))

(deftest frame-switcher-select-has-aria-label
  (testing "the native <select> picker has an aria-label
            so screen readers announce its purpose on focus. Two
            cascades from distinct frames give it a populated option
            list."
    ;; Seed BEFORE xray-setup! so the sub's first compute reads the
    ;; populated trace-bus atom — mirrors the order frame-switcher's
    ;; own tests use.
    (seed-trace! 1 :rf/default)
    (seed-trace! 2 :app/main)
    (xray-setup!)
    (rf/with-frame :rf/xray
      (let [tree   (static-shell-tree/frame-switcher-tree rf/dispatch)
            picker (rf.test-helpers/find-by-testid tree "rf-xray-ribbon-frame-picker")]
        (is (and (string? (:aria-label (props picker)))
                 (seq (:aria-label (props picker))))
            "frame-switcher <select> carries a non-empty aria-label")))))

;; -------------------------------------------------------------------------
;; (5) resize-handle aria-valuemax
;; -------------------------------------------------------------------------

(deftest resize-handle-has-aria-valuemax
  (testing "the resize handle's separator role requires
            aria-valuemax alongside aria-valuemin and aria-valuenow."
    (xray-setup!)
    (rf/with-frame :rf/xray
      ;; `handle-view` is a Fresco boundary and its `rf.fresco/sub` is
      ;; legal only inside a render, so the node lane drives the
      ;; boundary's PURE inner fn. The announced ceiling comes from the
      ;; shipped helper rather than a literal, because the ceiling's
      ;; derivation is what this row is about.
      (let [tree   (resize-handle/handle-tree
                     480
                     (resize-handle/aria-max-panel-width-px)
                     rf/dispatch)
            attrs  (second tree)]
        (is (number? (:aria-valuemax attrs))
            "aria-valuemax is a number")
        (is (>= (:aria-valuemax attrs) (:aria-valuemin attrs))
            "aria-valuemax >= aria-valuemin")))))

;; -------------------------------------------------------------------------
;; (6) decorative glyph aria-hidden
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
