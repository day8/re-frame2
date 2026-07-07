(ns day8.re-frame2-xray.modals-aria-cljs-test
  "WAI-ARIA dialog contract tests for the Xray modal surfaces
  (rf2-7389r — audit finding #3).

  Each modal must carry:
    - role=\"dialog\"
    - aria-modal=\"true\"
    - an accessible name (either aria-label or aria-labelledby
      pointing at a heading id rendered inside the dialog)

  The audit caught the modals shipping ZERO modal a11y between them.
  This file freezes the post-fix contract so the next renderer cannot
  silently regress.

  rf2-nugvv (2026-06-04) — the Share modal surface is removed (its sole
  UI entry point, the Machine panel's Share button, was retired), so it
  drops out of this contract set.

  ## Surfaces under test

    1. Settings popup  (`settings/view/popup-view`)
    2. Mute manager    (`spine-filters/dialog`)
    3. Filter edit-popup (`filters/edit-popup/popup-view`)
    4. Cancellation-cascade popover — exercised via the Popover
       reg-view's body (it renders a [:div {:role \"dialog\" ...}])
    5. App-DB segment-inspector popover — same shape as #4

  Tests render each view function directly (no shadow DOM, no Reagent
  mount) and walk the returned hiccup, asserting the ARIA attribute
  map on the dialog element. Mirrors the pattern used by
  `spine-filters-cljs-test` and `palette/aria-cljs-test`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-xray.filters.edit-popup :as edit-popup]
            [day8.re-frame2-xray.panels.app-db-segment-inspector
             :as segment-inspector]
            [day8.re-frame2-xray.panels.cancellation-cascade
             :as cancellation-cascade]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.settings.view :as settings-view]
            [day8.re-frame2-xray.spine-filters :as spine-filters]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

(defn- xray-init! []
  (xray-test-support/reset-all!))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn xray-init!}))

(defn- xray-setup! []
  (registry/register-xray-handlers!)
  (frame/reg-frame :rf/xray {}))

;; ---- hiccup walk helpers ------------------------------------------------

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

(defn- props [hiccup]
  (when (and (vector? hiccup) (map? (second hiccup)))
    (second hiccup)))

(defn- assert-dialog-contract!
  "Common assertions: the dialog node must carry role + aria-modal,
  and either aria-label or aria-labelledby resolving to a real id
  in the same tree."
  [tree dialog-testid label]
  (let [dialog (find-by-testid tree dialog-testid)
        attrs  (props dialog)]
    (is (some? dialog) (str label ": dialog wrapper renders"))
    (is (= "dialog" (:role attrs))
        (str label ": role=\"dialog\""))
    (is (= "true" (:aria-modal attrs))
        (str label ": aria-modal=\"true\""))
    (let [labelled-by (:aria-labelledby attrs)
          label-attr  (:aria-label attrs)]
      (is (or (and labelled-by
                   (some? (find-by-id tree labelled-by)))
              (and (string? label-attr) (seq label-attr)))
          (str label ": accessible name set — either aria-labelledby
                points at a heading id rendered in the same tree, or
                aria-label carries a non-empty string")))))

(defn- assert-dialog-focus-ref!
  "rf2-dkmnm (audit finding #3 follow-on) — assert the dialog node
  actually ATTACHES the focus-trap ref + the `tab-index=\"-1\"`
  fallback target.

  `assert-dialog-contract!` proves the modal is *labelled*, but a
  labelled dialog with NO focus trap passes that gate. `dialog-ref`
  (the WAI-ARIA APG capture/trap/restore closure) is wired via the
  dialog node's `:ref`. Each `dialog-ref` call returns a FRESH closure
  so identity comparison is impossible — instead we assert the dialog
  node carries a `:ref` that is a function, plus the
  `tab-index=\"-1\"` (or `0`) the ref's focus-on-open fallback
  requires. A renderer that shipped role/aria-modal but dropped the
  `:ref` would now fail here rather than staying green."
  [tree dialog-testid label]
  (let [dialog (find-by-testid tree dialog-testid)
        attrs  (props dialog)]
    (is (some? dialog) (str label ": dialog wrapper renders"))
    (is (fn? (:ref attrs))
        (str label ": dialog node attaches a :ref (the a11y/dialog-ref
              focus-trap callback) — a labelled modal with no trap is
              an a11y regression"))
    (is (contains? attrs :tab-index)
        (str label ": dialog node carries :tab-index so dialog-ref's
              focus-on-open fallback (focus the root when there is no
              focusable child) has a target"))))

;; -------------------------------------------------------------------------
;; (1) Settings popup
;; -------------------------------------------------------------------------

(deftest settings-popup-carries-dialog-contract
  (xray-setup!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/settings-open]))
  (let [tree (rf/with-frame :rf/xray (settings-view/popup-view rf/dispatch))]
    (assert-dialog-contract! tree "rf-xray-settings-dialog"
                             "Settings popup")))

(deftest settings-popup-close-button-has-aria-label
  (testing "rf2-7389r + audit #14 — Settings ✕ button accessibility name"
    (xray-setup!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/settings-open]))
    (let [tree  (rf/with-frame :rf/xray (settings-view/popup-view rf/dispatch))
          close (find-by-testid tree "rf-xray-settings-close")]
      (is (string? (:aria-label (props close)))
          "Settings close ✕ carries an aria-label"))))

;; -------------------------------------------------------------------------
;; (2) Mute manager
;; -------------------------------------------------------------------------

(deftest mute-manager-carries-dialog-contract
  (xray-setup!)
  (let [tree (rf/with-frame :rf/xray (spine-filters/dialog rf/dispatch))]
    (assert-dialog-contract! tree "rf-xray-mute-manager-dialog"
                             "Mute manager")))

;; -------------------------------------------------------------------------
;; (3) Filter edit-popup
;; -------------------------------------------------------------------------

(deftest filter-edit-popup-carries-dialog-contract
  (xray-setup!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/open-edit-popup
                       {:source :add :mode :in :pill {}}]))
  (let [tree (rf/with-frame :rf/xray (edit-popup/popup-view rf/dispatch))]
    (assert-dialog-contract! tree "rf-xray-edit-popup-dialog"
                             "Filter edit-popup")))

;; -------------------------------------------------------------------------
;; (4) + (5) Popovers — cancellation-cascade + segment-inspector
;; -------------------------------------------------------------------------
;;
;; These two use `reg-view` to gate on an `:open?` slot. The reg-view
;; macro defs the symbol; invoking it directly under `with-frame`
;; resolves subscribes against `:rf/xray` the same way the shell
;; mount does.

(deftest cancellation-cascade-popover-carries-dialog-contract
  (testing "rf2-7389r — the cancellation-cascade popover (audit
            findings #3 + #19) carries dialog role + aria-modal +
            accessible name on its inner dialog wrapper"
    (xray-setup!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/cancellation-cascade-open
                         {:dispatch-id :test-dispatch-id}]))
    (let [tree (rf/with-frame :rf/xray (cancellation-cascade/Popover))]
      (is (some? tree) "Popover renders when open")
      (when tree
        (assert-dialog-contract!
          tree
          "rf-xray-cancellation-cascade-popover-dialog"
          "Cancellation-cascade popover")))))

(deftest segment-inspector-popover-carries-dialog-contract
  (testing "rf2-7389r — the App-DB segment-inspector popover carries
            dialog role + aria-modal + accessible name"
    (xray-setup!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/open-segment-inspector []]))
    (let [tree (rf/with-frame :rf/xray (segment-inspector/Popup))]
      (is (some? tree) "Popup renders when open")
      (when tree
        (assert-dialog-contract!
          tree
          "rf-xray-segment-inspector-dialog"
          "Segment inspector popover")))))

;; -------------------------------------------------------------------------
;; Per-modal focus-ref wiring (rf2-dkmnm — audit finding #3 follow-on)
;; -------------------------------------------------------------------------
;;
;; The contract tests above prove each modal is LABELLED
;; (role + aria-modal + accessible name). They do NOT prove the modal
;; actually attaches the focus-trap. A renderer could ship a perfectly
;; labelled dialog with no `a11y/dialog-ref` and every contract test
;; would stay green while keyboard users fell out of the trap. These
;; tests close that hole by asserting each dialog node carries a
;; function `:ref` (the dialog-ref closure) + the tab-index its
;; focus-on-open fallback needs — one assertion per surface so a
;; regression names the exact modal that lost its trap.

(deftest settings-popup-attaches-focus-ref
  (xray-setup!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/settings-open]))
  (let [tree (rf/with-frame :rf/xray (settings-view/popup-view rf/dispatch))]
    (assert-dialog-focus-ref! tree "rf-xray-settings-dialog"
                              "Settings popup")))

(deftest mute-manager-attaches-focus-ref
  (xray-setup!)
  (let [tree (rf/with-frame :rf/xray (spine-filters/dialog rf/dispatch))]
    (assert-dialog-focus-ref! tree "rf-xray-mute-manager-dialog"
                              "Mute manager")))

(deftest filter-edit-popup-attaches-focus-ref
  (xray-setup!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/open-edit-popup
                       {:source :add :mode :in :pill {}}]))
  (let [tree (rf/with-frame :rf/xray (edit-popup/popup-view rf/dispatch))]
    (assert-dialog-focus-ref! tree "rf-xray-edit-popup-dialog"
                              "Filter edit-popup")))

(deftest cancellation-cascade-popover-attaches-focus-ref
  (xray-setup!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/cancellation-cascade-open
                       {:dispatch-id :test-dispatch-id}]))
  (let [tree (rf/with-frame :rf/xray (cancellation-cascade/Popover))]
    (is (some? tree) "Popover renders when open")
    (when tree
      (assert-dialog-focus-ref!
        tree "rf-xray-cancellation-cascade-popover-dialog"
        "Cancellation-cascade popover"))))

(deftest segment-inspector-popover-attaches-focus-ref
  (xray-setup!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/open-segment-inspector []]))
  (let [tree (rf/with-frame :rf/xray (segment-inspector/Popup))]
    (is (some? tree) "Popup renders when open")
    (when tree
      (assert-dialog-focus-ref!
        tree "rf-xray-segment-inspector-dialog"
        "Segment inspector popover"))))
