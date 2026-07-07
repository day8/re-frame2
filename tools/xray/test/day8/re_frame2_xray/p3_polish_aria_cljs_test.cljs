(ns day8.re-frame2-xray.p3-polish-aria-cljs-test
  "P3 a11y polish contract tests — bundles four audit-derived beads
  (rf2-w03x1 audit findings #4, #5, #16, #17, #18, #22, #25, #26).

    - rf2-plajx — Xray shell root carries `role=\"region\"` +
      `aria-label`; L4 detail-panel carries `role=\"tabpanel\"` +
      `aria-labelledby` linking back to the active L3 tab button.
      Mirrored on the Static surface's L4 panel.

    - rf2-h4mnh — Settings popup inner tabs render as a WAI-ARIA tab
      group: tab-strip wrapper has `role=\"tablist\"` +
      `aria-label`, each tab button has `role=\"tab\"` +
      `aria-selected` + `id` + `aria-controls`; the body wrapper has
      `role=\"tabpanel\"` + `id` + `aria-labelledby` pointing at the
      active tab. Numeric `<label>` ↔ `<input>` pairs in General +
      Buffer carry `:html-for` ↔ `:id`.

    - rf2-lbutp — Xray frame-switcher native `<select>` carries an
      explicit `aria-label`. Story multi-substrate grid exposes a
      labelled `role=\"group\"` with per-cell `role=\"region\"`.

    - rf2-vxpq1 — Xray resize-handle carries `aria-valuemax`;
      decorative glyphs (●/○ tab markers, ● REDACTED prefix, 🎯
      focus chip) carry `aria-hidden=\"true\"`; Static placeholder
      cards drop from `<h1>` to `<h2>`.

  All assertions walk the view's hiccup tree by `data-testid` /
  attribute presence rather than mounting to a DOM — same approach
  the existing `modals-aria-cljs-test` / `shell-cljs-test` files
  use."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.resize-handle :as resize-handle]
            [day8.re-frame2-xray.settings.view :as settings-view]
            [day8.re-frame2-xray.shell :as shell]
            [day8.re-frame2-xray.frame-switcher :as frame-switcher]
            [day8.re-frame2-xray.static.shell :as static-shell]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.trace-collector :as trace-collector]))

;; ---- fixture ------------------------------------------------------------

(defn- xray-init! []
  ;; rf2-sdqsla — `reset-runtime!` folds sentinel + trace-collector +
  ;; settings reset into one call.
  (xray-test-support/reset-runtime!))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn xray-init!}))

(defn- xray-setup! []
  (registry/register-xray-handlers!)
  (frame/reg-frame :rf/xray {}))

;; ---- hiccup walker (mirrors shell-cljs-test) ----------------------------

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

(defn- find-by-id [tree id]
  (some (fn [node]
          (when (and (vector? node)
                     (map? (second node))
                     (= id (:id (second node))))
            node))
        (hiccup-seq tree)))

(defn- find-all-with-pred [tree pred]
  (filterv pred (hiccup-seq tree)))

(defn- props [hiccup]
  (when (and (vector? hiccup) (map? (second hiccup)))
    (second hiccup)))

;; -------------------------------------------------------------------------
;; (1) rf2-plajx — shell root landmark
;; -------------------------------------------------------------------------

(deftest shell-root-is-a-labelled-region-landmark
  (testing "rf2-plajx — Xray shell root carries role=\"region\" +
            aria-label so AT users can navigate to it via landmark
            cycle. The overlay was previously a bare <div>."
    (xray-setup!)
    (rf/with-frame :rf/xray
      (let [tree  (shell/shell-view)
            shell (find-by-testid tree "rf-xray-shell")
            attrs (props shell)]
        (is (some? shell) "shell root mounts")
        (is (= "region" (:role attrs))
            "shell carries role=\"region\"")
        (is (and (string? (:aria-label attrs))
                 (seq (:aria-label attrs)))
            "shell carries a non-empty aria-label")
        (is (= "Xray devtools" (:aria-label attrs))
            "the published accessible name is \"Xray devtools\"")))))

;; -------------------------------------------------------------------------
;; (2) rf2-plajx — Dynamic L3 tabs + L4 tabpanel id round-trip
;; -------------------------------------------------------------------------

(deftest runtime-tabs-and-panel-close-the-aria-loop
  (testing "rf2-plajx — Dynamic L3 tab buttons carry stable `:id` +
            `:aria-controls`; the L4 detail-panel carries
            `:role=\"tabpanel\"` + `:id` + `:aria-labelledby` resolving
            back to the active tab's id."
    (xray-setup!)
    (rf/with-frame :rf/xray
      (let [tree         (shell/shell-view)
            active-tab   :epoch ;; default (post rf2-5gl5r — supersedes :event)
            tab-button   (find-by-testid tree (str "rf-xray-tab-" (name active-tab)))
            tab-attrs    (props tab-button)
            expected-id  (str "rf-xray-tab-button-" (name active-tab))
            panel-id     (str "rf-xray-tabpanel-" (name active-tab))
            panel        (find-by-testid tree (str "rf-xray-detail-panel-" (name active-tab)))
            panel-attrs  (props panel)]
        (is (= expected-id (:id tab-attrs))
            "tab button id matches the documented shape")
        (is (= panel-id (:aria-controls tab-attrs))
            "tab button's aria-controls points at the panel id")
        (is (= "tabpanel" (:role panel-attrs))
            "L4 detail-panel carries role=\"tabpanel\"")
        (is (= panel-id (:id panel-attrs))
            "L4 panel id matches the tab's aria-controls")
        (is (= expected-id (:aria-labelledby panel-attrs))
            "L4 panel's aria-labelledby resolves back to the active tab")))))

(deftest static-tabs-and-panel-close-the-aria-loop
  (testing "rf2-plajx — Static L4 panel mirrors the Dynamic pattern."
    (xray-setup!)
    (rf/with-frame :rf/xray
      (let [tree        (static-shell/surface)
            active-tab  :machines ;; the default Static tab
            tab-button  (find-by-testid tree (str "rf-xray-static-tab-" (name active-tab)))
            tab-attrs   (props tab-button)
            expected-id (str "rf-xray-static-tab-button-" (name active-tab))
            panel-id    (str "rf-xray-static-tabpanel-" (name active-tab))
            panel       (find-by-testid tree (str "rf-xray-static-detail-panel-" (name active-tab)))
            panel-attrs (props panel)]
        (is (= expected-id (:id tab-attrs))
            "Static tab button id matches the documented shape")
        (is (= panel-id (:aria-controls tab-attrs))
            "Static tab button's aria-controls points at the panel id")
        (is (= "tabpanel" (:role panel-attrs))
            "Static L4 panel carries role=\"tabpanel\"")
        (is (= expected-id (:aria-labelledby panel-attrs))
            "Static L4 panel's aria-labelledby resolves back to the active tab")))))

;; -------------------------------------------------------------------------
;; (3) rf2-h4mnh — Settings tab strip ARIA + tabpanel
;; -------------------------------------------------------------------------

(deftest settings-tab-strip-is-a-labelled-tablist
  (testing "rf2-h4mnh — the Settings tab strip wrapper carries
            role=\"tablist\" + aria-label."
    (xray-setup!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/settings-open]))
    (let [tree   (rf/with-frame :rf/xray (settings-view/popup-view rf/dispatch))
          strip  (find-by-testid tree "rf-xray-settings-tab-strip")
          attrs  (props strip)]
      (is (= "tablist" (:role attrs))
          "Settings tab strip is a tablist")
      (is (and (string? (:aria-label attrs)) (seq (:aria-label attrs)))
          "Settings tab strip has a non-empty aria-label"))))

(deftest settings-tab-buttons-carry-tab-aria
  (testing "rf2-h4mnh — every Settings tab button has role=\"tab\" +
            aria-selected reflecting the active tab + stable `:id` +
            `:aria-controls` pointing at the body's tabpanel id."
    (xray-setup!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/settings-open]))
    (let [tree     (rf/with-frame :rf/xray (settings-view/popup-view rf/dispatch))
          ;; Theme tab removed rf2-ou3pn; Filters tab removed
          ;; rf2-wknb3. The four remaining tabs each carry the full
          ;; WAI-ARIA tab contract.
          tab-ids  [:general :keybindings :buffer :diff]]
      (doseq [tid tab-ids]
        (let [button (find-by-testid tree
                       (str "rf-xray-settings-tab-" (name tid)))
              attrs  (props button)]
          (is (= "tab" (:role attrs))
              (str "tab " tid " carries role=\"tab\""))
          (is (contains? #{"true" "false"} (:aria-selected attrs))
              (str "tab " tid " carries aria-selected as a string"))
          (is (= (str "rf-xray-settings-tab-button-" (name tid))
                 (:id attrs))
              (str "tab " tid " carries the documented id"))
          (is (= (str "rf-xray-settings-tabpanel-" (name tid))
                 (:aria-controls attrs))
              (str "tab " tid " carries aria-controls pointing at "
                   "its body tabpanel"))))
      ;; Default active tab is :general; selected reflects that.
      (let [general (find-by-testid tree "rf-xray-settings-tab-general")
            buffer  (find-by-testid tree "rf-xray-settings-tab-buffer")]
        (is (= "true" (:aria-selected (props general)))
            "active tab (:general) carries aria-selected=\"true\"")
        (is (= "false" (:aria-selected (props buffer)))
            "inactive tab (:buffer) carries aria-selected=\"false\"")))))

(deftest settings-body-is-a-labelled-tabpanel
  (testing "rf2-h4mnh — Settings body carries role=\"tabpanel\" + an
            id matching the active tab's aria-controls + an
            aria-labelledby resolving back to the active tab button."
    (xray-setup!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/settings-open]))
    (let [tree  (rf/with-frame :rf/xray (settings-view/popup-view rf/dispatch))
          body  (find-by-testid tree "rf-xray-settings-body")
          attrs (props body)]
      (is (= "tabpanel" (:role attrs))
          "body wrapper is a tabpanel")
      (is (= "rf-xray-settings-tabpanel-general" (:id attrs))
          "body id matches the active tab's tabpanel id")
      (is (= "rf-xray-settings-tab-button-general"
             (:aria-labelledby attrs))
          "body aria-labelledby resolves back to the active tab"))))

(deftest settings-epoch-history-label-associates-with-input
  (testing "rf2-h4mnh — the epoch-history slider's <input :id> matches a
            <label :html-for> so clicking the label focuses the input.

            (Originally exercised on the text-size slider; the
            text-size slider was retired in the 2026-05-27 UX cleanup
            — epoch-history is the surviving slider in General that
            carries the documented `:html-for` ↔ `:id` pair.)"
    (xray-setup!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/settings-open]))
    (let [tree  (rf/with-frame :rf/xray (settings-view/popup-view rf/dispatch))
          input (find-by-testid tree "rf-xray-settings-epoch-history-input")
          ;; The label sits in the same <div> field; find by html-for.
          label (some (fn [node]
                        (when (and (vector? node)
                                   (map? (second node))
                                   (= "rf-xray-settings-epoch-history-input"
                                      (:html-for (second node))))
                          node))
                      (hiccup-seq tree))]
      (is (= "rf-xray-settings-epoch-history-input" (:id (props input)))
          "input carries the documented id")
      (is (some? label)
          "a <label html-for=...> matches the input's id"))))

;; -------------------------------------------------------------------------
;; (4) rf2-lbutp — frame-switcher aria-label
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
  (testing "rf2-lbutp — the native <select> picker has an aria-label
            so screen readers announce its purpose on focus. The
            picker only renders when ≥2 frames are present; seed two
            cascades from distinct frames to surface it."
    ;; Seed BEFORE xray-setup! so the sub's first compute reads the
    ;; populated trace-bus atom — mirrors the order frame-switcher's
    ;; own tests use.
    (seed-trace! 1 :rf/default)
    (seed-trace! 2 :app/main)
    (xray-setup!)
    (rf/with-frame :rf/xray
      (let [tree   (frame-switcher/frame-switcher-view nil)
            picker (find-by-testid tree "rf-xray-ribbon-frame-picker")]
        (is (some? picker)
            "the <select> picker renders when ≥2 frames are present")
        (is (and (string? (:aria-label (props picker)))
                 (seq (:aria-label (props picker))))
            "frame-switcher <select> carries a non-empty aria-label")))))

;; -------------------------------------------------------------------------
;; (5) rf2-vxpq1 — resize-handle aria-valuemax
;; -------------------------------------------------------------------------

(deftest resize-handle-has-aria-valuemax
  (testing "rf2-vxpq1 — the resize handle's separator role requires
            aria-valuemax alongside aria-valuemin and aria-valuenow."
    (xray-setup!)
    (rf/with-frame :rf/xray
      (let [tree   (resize-handle/Handle :inline)
            attrs  (second tree)]
        (is (some? (:aria-valuemax attrs))
            "aria-valuemax is set")
        (is (number? (:aria-valuemax attrs))
            "aria-valuemax is a number")
        (is (>= (:aria-valuemax attrs) (:aria-valuemin attrs))
            "aria-valuemax >= aria-valuemin")
        (is (some? (:aria-valuemin attrs)) "aria-valuemin is set")
        (is (some? (:aria-valuenow attrs)) "aria-valuenow is set")))))

;; -------------------------------------------------------------------------
;; (6) rf2-vxpq1 — decorative glyph aria-hidden
;; -------------------------------------------------------------------------

(deftest redacted-glyph-is-aria-hidden
  (testing "rf2-vxpq1 — the leading `●` glyph on the REDACTED
            indicator carries aria-hidden so AT does not announce the
            unicode name."
    (xray-setup!)
    ;; Force the counter positive so the indicator renders.
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/note-sensitive-suppressed :rf/default]))
    (rf/with-frame :rf/xray
      (let [tree      (shell/shell-view)
            indicator (find-by-testid tree "rf-xray-redacted-indicator")
            ;; the glyph is the first <span> child carrying aria-hidden
            glyph     (some (fn [node]
                              (when (and (vector? node)
                                         (= :span (first node))
                                         (map? (second node))
                                         (= "true" (:aria-hidden (second node))))
                                node))
                            (hiccup-seq indicator))]
        (is (some? indicator) "REDACTED indicator renders when count > 0")
        (is (some? glyph)
            "the decorative `●` glyph carries aria-hidden=\"true\"")))))

(deftest runtime-tab-has-no-decorative-glyph
  (testing "rf2-ad7zx.16 (supersedes rf2-vxpq1) — the L3 tab strip is now
            the Figma button-bar (filled-accent active button + white
            text), NOT a radio-glyph row. The previous `●/○` decorative
            glyph (which had to carry aria-hidden so AT heard only the
            label) is GONE entirely — selection is signalled by the
            button fill + colour, not a unicode circle. So the tab button
            carries NO decorative glyph at all, and AT users hear only
            the visible label by construction."
    (xray-setup!)
    (rf/with-frame :rf/xray
      (let [tree      (shell/shell-view)
            event-tab (find-by-testid tree "rf-xray-tab-event")
            glyph     (some (fn [node]
                              (when (and (vector? node)
                                         (= :span (first node))
                                         (map? (second node))
                                         (= "true" (:aria-hidden (second node))))
                                node))
                            (hiccup-seq event-tab))
            tab-text  (apply str (filter string? (hiccup-seq event-tab)))]
        (is (nil? glyph)
            "L3 tab carries no aria-hidden decorative-glyph span")
        (is (not (re-find #"[◉○●]" tab-text))
            "L3 tab carries no radio-circle glyph in its text")))))

;; rf2-vxpq1's Static placeholder <h2>-not-<h1> test was removed in
;; rf2-sdqsla: the rf2-o5f5f roll-out is complete (every Static sub-tab
;; ships a real panel), so `placeholder-card` no longer exists and the
;; test was vacuous (no placeholder ever mounts).
