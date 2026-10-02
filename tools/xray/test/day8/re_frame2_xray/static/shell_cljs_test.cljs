(ns day8.re-frame2-xray.static.shell-cljs-test
  "CLJS wiring + render tests for Xray's Static surface scaffold.

  ## What's under test

    1. Mode-state lives on `:rf.xray/mode` (default `:dynamic`);
       `:rf.xray/set-mode` writes a specific mode; `:rf.xray/
       toggle-mode` flips between modes. Both attach the
       `:rf.xray.static/persist-mode` fx so the value round-trips
       through localStorage.

    2. localStorage round-trip — the persisted slot survives a frame
       reset (the hydrate path in `mount/ensure-xray-frame!`
       restores the value via `:rf.xray/set-mode`).

    3. Static shell renders the 3-layer chrome (ribbon · tab-bar ·
       detail panel) with 5 sub-tabs (Machines / Routes / Schemas /
       Flows / Interceptors), each mounting a real panel.

    4. `:rf.xray.static/select-tab` flips the Static-scoped tab
       slot (does NOT clobber the Dynamic `:rf.xray/selected-tab`).

    5. Sub-tab routing — clicking a Static tab swaps the detail
       panel; an unknown tab id is rejected by the event handler.

    6. The mode control renders as a native `<select>` whose `:value`
       and `data-active-mode` track the active mode.

  ## Pure hiccup walk

  Same approach as `shell_cljs_test.cljs` — we walk the view's
  hiccup tree by `data-testid` rather than mounting to a real DOM."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.test-helpers :as rf.test-helpers]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.static.mode-pill :as mode-pill]
            [day8.re-frame2-xray.static.persistence :as static-persistence]
            [day8.re-frame2-xray.static.shell :as static-shell]
            [day8.re-frame2-xray.test-helpers.static-shell-tree
             :as static-shell-tree]
            [day8.re-frame2-xray.test-helpers.dynamic-shell-tree
             :as dynamic-shell-tree]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

;; ---- fixture ------------------------------------------------------------

(use-fixtures :each
  ;; `make-xray-runtime-fixture` owns the reset (plain-atom + `:all` tier,
  ;; which resets the trace-collector rings too); `:post-reset` carries the
  ;; suppressed-count + static-persistence slate.
  (xray-test-support/make-xray-runtime-fixture
    {:post-reset (fn []
                   (config/reset-suppressed-count!)
                   (static-persistence/clear!))}))

;; ---- hiccup walker (mirrors shell_cljs_test) ----------------------------

;; Tests call `rf.test-helpers/find-by-testid` directly; there is no Xray
;; walker facade.

;; ---- helpers ------------------------------------------------------------

(defn- xray-setup! []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray}))

(defn- frame-sub [q]
  (rf/with-frame :rf/xray
    @(rf/subscribe q)))

(defn- frame-dispatch [ev]
  (rf/with-frame :rf/xray
    (rf/dispatch-sync ev)))

;; -------------------------------------------------------------------------
;; (1) mode-state lifecycle — set / toggle
;; -------------------------------------------------------------------------

(deftest set-mode-writes-the-slot
  (testing ":rf.xray/set-mode :static lands :static on the slot"
    (xray-setup!)
    (frame-dispatch [:rf.xray/set-mode :static])
    (is (= :static (frame-sub [:rf.xray/mode])))
    (frame-dispatch [:rf.xray/set-mode :dynamic])
    (is (= :dynamic (frame-sub [:rf.xray/mode])))))

(deftest set-mode-normalises-unknown-values
  (testing "string mode values normalise to their keyword; unknown values
            normalise to :dynamic"
    (xray-setup!)
    (frame-dispatch [:rf.xray/set-mode "static"])
    (is (= :static (frame-sub [:rf.xray/mode]))
        "string 'static' normalises to :static")
    ;; From :static rather than the :dynamic default, so an unknown value
    ;; that was IGNORED would leave :static here instead of :dynamic.
    (frame-dispatch [:rf.xray/set-mode :nonsense])
    (is (= :dynamic (frame-sub [:rf.xray/mode]))
        "unknown keyword → :dynamic")))

(deftest toggle-mode-flips-dynamic-and-static
  (testing ":rf.xray/toggle-mode flips between modes idempotently"
    (xray-setup!)
    (is (= :dynamic (frame-sub [:rf.xray/mode])) "starts on :dynamic")
    (frame-dispatch [:rf.xray/toggle-mode])
    (is (= :static (frame-sub [:rf.xray/mode])))
    (frame-dispatch [:rf.xray/toggle-mode])
    (is (= :dynamic (frame-sub [:rf.xray/mode])))
    (frame-dispatch [:rf.xray/toggle-mode])
    (is (= :static (frame-sub [:rf.xray/mode])))))

;; -------------------------------------------------------------------------
;; (2) localStorage persistence — round-trip
;; -------------------------------------------------------------------------

(deftest persistence-mode-codec-normalises-and-round-trips
  (testing "static.persistence/normalise-mode coerces keywords + strings"
    (is (= :dynamic (static-persistence/normalise-mode :dynamic)))
    (is (= :static  (static-persistence/normalise-mode :static)))
    (is (= :dynamic (static-persistence/normalise-mode "dynamic")))
    (is (= :static  (static-persistence/normalise-mode "static")))
    (is (= :dynamic (static-persistence/normalise-mode nil)))
    (is (= :dynamic (static-persistence/normalise-mode :nonsense)))
    (is (= :dynamic (static-persistence/normalise-mode "junk"))))
  (testing "->raw / <-raw lossless on canonical values"
    (is (= :dynamic (static-persistence/<-raw (static-persistence/->raw :dynamic))))
    (is (= :static  (static-persistence/<-raw (static-persistence/->raw :static))))))

;; The two real-storage rows — `persistence-load-default-empty-slot` and
;; `persistence-fx-installed-by-set-mode` — live in
;; `day8.re-frame2-xray.static.shell-dom-cljs-test`. Here, wrapped in
;; `(when (and (exists? js/window) (.-localStorage js/window)) ...)`,
;; they would execute in NEITHER lane: the guard is FALSE under
;; `:node-test` (no jsdom in any dependency list), and `:browser-test`'s
;; `.*-dom-cljs-test$` `:ns-regexp` never loads this file at all. A
;; namespace ending `-dom-cljs-test` is selected by BOTH builds, so the
;; rows run for real in the browser and stay inert on node behind
;; `ls/available?`.

;; -------------------------------------------------------------------------
;; (3) Static surface — 3-layer chrome render
;; -------------------------------------------------------------------------

(deftest static-surface-renders-three-layers
  (testing "Static shell renders ribbon · tab-bar · detail panel
            (NO L2 event list — Static is event-INDEPENDENT)"
    (xray-setup!)
    (rf/with-frame :rf/xray
      (let [tree (static-shell-tree/surface-tree)]
        (is (some? (rf.test-helpers/find-by-testid tree "rf-xray-static-surface"))
            "Static surface envelope present")
        (is (some? (rf.test-helpers/find-by-testid tree "rf-xray-static-ribbon"))
            "L1 ribbon present")
        (is (some? (rf.test-helpers/find-by-testid tree "rf-xray-static-tab-bar"))
            "L3 tab bar present")
        ;; default tab is :machines → detail panel testid carries the tab name
        (is (some? (rf.test-helpers/find-by-testid tree "rf-xray-static-detail-panel-machines"))
            "L4 detail panel present (default :machines tab)")
        ;; CRITICAL: no L2 event list in Static mode
        (is (nil? (rf.test-helpers/find-by-testid tree "rf-xray-event-list"))
            "no L2 event list (Static is event-INDEPENDENT)")))))

(deftest static-ribbon-mounts-mode-pill-frame-picker-and-right-icons
  (testing "Static ribbon carries the mode pill at left + the L1 frame
            picker + right icons cluster (Settings · Close). The frame
            picker is mode-INDEPENDENT — Static registrations are
            frame-scoped, so the picker mounts in both modes. Dynamic's
            spine-coupled nav / filter clusters remain hidden (Static
            has no spine)."
    (xray-setup!)
    (rf/with-frame :rf/xray
      (let [tree (static-shell-tree/surface-tree)]
        (is (some? (rf.test-helpers/find-by-testid tree "rf-xray-mode-pill"))
            "mode pill present at ribbon-left")
        ;; L1 frame picker mounts in Static (the picker collapses to a
        ;; flat label when only one frame is available — match either
        ;; the `<select>` or the label fallback).
        (is (or (some? (rf.test-helpers/find-by-testid tree "rf-xray-ribbon-frame-picker"))
                (some? (rf.test-helpers/find-by-testid tree "rf-xray-ribbon-frame")))
            "L1 frame picker present (picker `<select>` or label fallback)")
        (is (some? (rf.test-helpers/find-by-testid tree "rf-xray-static-ribbon-icons"))
            "right icons cluster present")
        (is (some? (rf.test-helpers/find-by-testid tree "rf-xray-static-icon-settings"))
            "settings icon present")
        (is (some? (rf.test-helpers/find-by-testid tree "rf-xray-static-icon-close"))
            "close icon present")
        ;; Spine-coupled clusters MUST NOT mount in Static surface
        (is (nil? (rf.test-helpers/find-by-testid tree "rf-xray-ribbon-nav"))
            "no nav cluster")
        (is (nil? (rf.test-helpers/find-by-testid tree "rf-xray-ribbon-filters"))
            "no filter pills")))))

;; -------------------------------------------------------------------------
;; (4) Static tab inventory — 5 sub-tabs, each mounting a real panel
;; -------------------------------------------------------------------------

(def ^:private expected-static-tab-ids
  ;; There is no :views or :events tab: that information is in the
  ;; source code.
  [:machines :routes :schemas :flows :interceptors])

(deftest static-tab-bar-renders-five-tabs-with-tablist-aria
  (testing "Static tab-bar uses the canonical ARIA tab pattern
            (role='tablist' on the container, role='tab' on each
            button, aria-selected matching the active state)"
    (xray-setup!)
    (rf/with-frame :rf/xray
      (let [tree    (static-shell-tree/surface-tree)
            tab-bar (rf.test-helpers/find-by-testid tree "rf-xray-static-tab-bar")
            attrs   (second tab-bar)]
        (is (= "tablist" (:role attrs))
            "container carries role='tablist'")
        (is (string? (:aria-label attrs))
            "container has an accessible name"))
      (let [tree (static-shell-tree/surface-tree)]
        (doseq [tab-id expected-static-tab-ids]
          (let [btn   (rf.test-helpers/find-by-testid tree (str "rf-xray-static-tab-" (name tab-id)))
                attrs (second btn)]
            (is (= "tab" (:role attrs))
                (str "tab " tab-id " carries role='tab'"))
            (is (= (if (= tab-id :machines) "true" "false")
                   (:aria-selected attrs))
                (str "tab " tab-id " aria-selected matches the active tab"))))))))

;; Every Static sub-tab (:machines :routes :schemas :flows :interceptors)
;; ships a real panel, and the shell has no `placeholder-card`. The mount
;; rows are machines/panel_cljs_test's
;; `static-shell-mounts-machines-panel-on-machines-tab` and routes/
;; panel_cljs_test's `static-shell-routes-tab-is-in-inventory`; there is no
;; unfilled-tab placeholder path to cover.

;; -------------------------------------------------------------------------
;; (5) Static tab routing — selection + isolation
;; -------------------------------------------------------------------------

(deftest static-select-tab-flips-the-slot
  (testing ":rf.xray.static/select-tab writes the Static-scoped slot"
    (xray-setup!)
    (is (= :machines (frame-sub [:rf.xray.static/selected-tab]))
        "default is :machines")
    (frame-dispatch [:rf.xray.static/select-tab :routes])
    (is (= :routes (frame-sub [:rf.xray.static/selected-tab])))
    (frame-dispatch [:rf.xray.static/select-tab :flows])
    (is (= :flows (frame-sub [:rf.xray.static/selected-tab]))))
  (testing "and ignores ids not in the inventory — guards against typos /
            drift between the tab panels and this scaffold. From :flows
            rather than the :machines default, so an unknown id that RESET
            the slot instead of leaving it would read :machines here"
    (frame-dispatch [:rf.xray.static/select-tab :not-a-tab])
    (is (= :flows (frame-sub [:rf.xray.static/selected-tab]))
        "unknown tab id is rejected; slot stays on :flows")))

(deftest static-tab-isolated-from-dynamic-tab
  (testing "Dynamic and Static tab choices are independent —
            switching one does NOT clobber the other"
    (xray-setup!)
    (frame-dispatch [:rf.xray/select-tab :machines])
    (frame-dispatch [:rf.xray.static/select-tab :flows])
    (is (= :machines (frame-sub [:rf.xray/selected-tab]))
        "Dynamic tab unchanged")
    (is (= :flows (frame-sub [:rf.xray.static/selected-tab]))
        "Static tab landed independently")))

;; -------------------------------------------------------------------------
;; (6) Mode-signal mechanism — the Static ribbon carries NO left-edge stripe
;; -------------------------------------------------------------------------

(deftest static-ribbon-has-no-left-edge-stripe
  (testing "the Static ribbon must NOT paint a left-edge
            accent stripe. The DYNAMIC ribbon paints none — a 2-px blue
            `border-left` is not in the Figma authority, pinned by
            `chrome-ribbon-has-no-left-edge-stripe` in the Dynamic shell
            suite — and the Static ribbon mirrors that ribbon, so the
            two surfaces must agree. This is the Static mirror of that
            pin."
    (xray-setup!)
    (rf/with-frame :rf/xray
      (let [tree   (static-shell-tree/surface-tree)
            ribbon (rf.test-helpers/find-by-testid tree "rf-xray-static-ribbon")
            style  (:style (second ribbon))]
        (is (some? ribbon)
            "CONTROL — the Static ribbon root is in the walked tree")
        (is (some? (:border-bottom style))
            "CONTROL — the ribbon's OTHER border is present, so a nil
             :border-left is an absence and not an unread style map")
        (is (nil? (:border-left style))
            "Static ribbon root has no :border-left in its inline style")))))

;; -------------------------------------------------------------------------
;; (7) Mode dropdown — compact single-select
;; -------------------------------------------------------------------------
;;
;; The mode control is a compact `<select>` dropdown rather than a radio
;; pill — mode is an occasional-use control. No ribbon paints an accent
;; stripe, so the dropdown's own `data-active-mode` and the chrome
;; silhouette carry the mode signal. Both `<option>` testids + the
;; `data-active-mode` attribute make the inventory + active-mode
;; assertable.

(deftest mode-dropdown-renders-both-options
  (testing "mode control is a single-select dropdown with Dynamic +
            Static options"
    (xray-setup!)
    (rf/with-frame :rf/xray
      (let [tree (static-shell-tree/mode-pill-tree rf/dispatch)]
        (is (some? (rf.test-helpers/find-by-testid tree "rf-xray-mode-pill"))
            "the select control is present")
        (is (= :select (first tree)) "the control is a native <select>")
        (is (some? (rf.test-helpers/find-by-testid tree "rf-xray-mode-pill-dynamic"))
            "Dynamic option present")
        (is (some? (rf.test-helpers/find-by-testid tree "rf-xray-mode-pill-static"))
            "Static option present")))))

(deftest mode-dropdown-reflects-active-mode
  (testing "the dropdown's :value + data-active-mode track the live mode"
    (xray-setup!)
    (rf/with-frame :rf/xray
      (let [attrs (second (static-shell-tree/mode-pill-tree rf/dispatch))]
        (is (= "dynamic" (:value attrs)))
        (is (= "dynamic" (:data-active-mode attrs)))))
    ;; flip to Static and re-render
    (frame-dispatch [:rf.xray/set-mode :static])
    (rf/with-frame :rf/xray
      (let [attrs (second (static-shell-tree/mode-pill-tree rf/dispatch))]
        (is (= "static" (:value attrs)))
        (is (= "static" (:data-active-mode attrs)))))))

(deftest mode-dropdown-label-helper
  (testing "the pure mode-label helper maps each mode to its display text"
    (is (= "Dynamic" (mode-pill/mode-label :dynamic)))
    (is (= "Static"  (mode-pill/mode-label :static)))
    (is (= "Dynamic" (mode-pill/mode-label :nonsense))
        "unknown modes fall back to the Dynamic label")))

;; -------------------------------------------------------------------------
;; (8) Surface composer — shell.cljs dispatches Dynamic vs Static
;; -------------------------------------------------------------------------

(deftest surface-composer-renders-the-arm-for-the-active-mode
  (testing "with mode :static, the composer renders the Static surface
            (Static mode is unconditionally available).

            The Dynamic composer is a BOUNDARY, so it heads
            `[static-shell/surface {}]` DIRECTLY, and the node lane's door
            (`test-helpers.dynamic-shell-tree`) composes the Static arm
            from `test-helpers.static-shell-tree`, which drives the
            shell's own `*-tree` fns. So `rf-xray-static-surface` is
            reachable, and reaching it is not hollow: the testid,
            the flex column and the `data-rf-xray-mode` attribute all
            come from `static-shell/surface-tree`, the shipped
            definition. The Static surface's own first PAINT is
            W1 in `static/shell_fresco_boundary_dom_cljs_test`."
    (xray-setup!)
    (frame-dispatch [:rf.xray/set-mode :static])
    (rf/with-frame :rf/xray
      (let [tree (dynamic-shell-tree/surface-composer-tree)]
        (is (some? (rf.test-helpers/find-by-testid tree "rf-xray-static-surface"))
            "the Static arm mounts the Static surface")
        (is (nil? (rf.test-helpers/find-by-testid tree "rf-xray-ribbon"))
            "Dynamic ribbon does NOT mount")
        (is (nil? (rf.test-helpers/find-by-testid tree "rf-xray-event-list"))
            "Dynamic L2 event list does NOT mount"))))
  (testing "with mode :dynamic, the composer renders the Dynamic chrome
            (Static mode is unconditionally available)"
    (frame-dispatch [:rf.xray/set-mode :dynamic])
    (rf/with-frame :rf/xray
      (let [tree (dynamic-shell-tree/surface-composer-tree)]
        (is (some? (rf.test-helpers/find-by-testid tree "rf-xray-ribbon"))
            "Dynamic ribbon mounts")
        (is (nil? (rf.test-helpers/find-by-testid tree "rf-xray-static-surface"))
            "Static surface does NOT mount")))))

;; -------------------------------------------------------------------------
;; (9) Static tab inventory — pure-data shape
;; -------------------------------------------------------------------------

(deftest static-tab-inventory-shape
  (testing "tab inventory carries id/label/mnem/panel and preserves
            canonical order"
    (is (= [:machines :routes :schemas :flows :interceptors]
           (mapv :id (static-shell/tabs)))
        "5 tabs in canonical order")
    (doseq [{:keys [id label mnem panel]} (static-shell/tabs)]
      (is (keyword? id) (str "id is keyword for " id))
      (is (string? label) (str "label is a string for " id))
      (is (and (string? mnem) (= 1 (count mnem)))
          (str "mnem is one character for " id))
      (is (fn? panel) (str "panel is a callable view fn for " id)))))
