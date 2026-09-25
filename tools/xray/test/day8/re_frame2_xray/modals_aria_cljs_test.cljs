(ns day8.re-frame2-xray.modals-aria-cljs-test
  "WAI-ARIA dialog contract tests for the Xray modal surfaces.

  Each modal must carry:
    - role=\"dialog\"
    - aria-modal=\"true\"
    - an accessible name (either aria-label or aria-labelledby
      pointing at a heading id rendered inside the dialog)

  This file pins that contract so a renderer cannot silently regress
  it.

  ## Surfaces under test

    1. Settings popup  (`settings/view/popup-tree`, driven through
       `test-helpers.modal-trees/settings-popup-tree` — the popup's root
       is a Fresco boundary, not a callable)
    2. Mute manager    (`spine-filters/dialog-tree`)
    3. Filter edit-popup (`filters/edit-popup/popup-view`, driven
       through `test-helpers.modal-trees/edit-popup-tree` — the popup's
       root is a Fresco boundary, not a callable)
    4. Cancellation-cascade popover — exercised via its `popover-tree`
       (it renders a [:div {:role \"dialog\" ...}]); the view itself is a
       Fresco boundary, so the tree fn is the door

  Tests render each view function directly (no shadow DOM, no Reagent
  mount) and walk the returned hiccup, asserting the ARIA attribute
  map on the dialog element. Mirrors the pattern used by
  `spine-filters-cljs-test` and `palette/aria-cljs-test`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.test-helpers :as rf.test-helpers]
            [day8.re-frame2-xray.panels.cancellation-cascade
             :as cancellation-cascade]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-helpers.modal-trees :as modal-trees]
            [day8.re-frame2-xray.spine-filters :as spine-filters]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

(use-fixtures :each
  ;; `make-xray-runtime-fixture` is core `make-reset-runtime-fixture` + Xray
  ;; `reset-all!` in one owner: plain-atom adapter + the default `:all` reset
  ;; tier — install/registry/mount idempotency sentinels plus the
  ;; trace-collector rings.
  (xray-test-support/make-xray-runtime-fixture))

(defn- xray-setup! []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray}))

;; ---- hiccup walk helpers ------------------------------------------------
;; The assertions below call `rf.test-helpers/find-by-testid`,
;; `rf.test-helpers/find-by-attr` (keyed on :id) and `rf.test-helpers/attrs`
;; directly; there is no Xray walker facade.

(defn- assert-dialog-contract!
  "Common assertions: the dialog node must carry role + aria-modal,
  and either aria-label or aria-labelledby resolving to a real id
  in the same tree."
  [tree dialog-testid label]
  (let [dialog (rf.test-helpers/find-by-testid tree dialog-testid)
        attrs  (rf.test-helpers/attrs dialog)]
    (is (some? dialog) (str label ": dialog wrapper renders"))
    (is (= "dialog" (:role attrs))
        (str label ": role=\"dialog\""))
    (is (= "true" (:aria-modal attrs))
        (str label ": aria-modal=\"true\""))
    (let [labelled-by (:aria-labelledby attrs)
          label-attr  (:aria-label attrs)]
      (is (or (and labelled-by
                   (some? (rf.test-helpers/find-by-attr tree :id labelled-by)))
              (and (string? label-attr) (seq label-attr)))
          (str label ": accessible name set — either aria-labelledby
                points at a heading id rendered in the same tree, or
                aria-label carries a non-empty string")))))

(defn- assert-dialog-focus-ref!
  "Assert the dialog node actually ATTACHES the focus-trap ref + the
  `tab-index=\"-1\"` fallback target.

  `assert-dialog-contract!` proves the modal is *labelled*, but a
  labelled dialog with NO focus trap passes that gate. `dialog-ref`
  (the WAI-ARIA APG capture/trap/restore closure) is wired via the
  dialog node's `:ref`. Each `dialog-ref` call returns a FRESH closure
  so identity comparison is impossible — instead we assert the dialog
  node carries a `:ref` that is a function, plus the
  `tab-index=\"-1\"` (or `0`) the ref's focus-on-open fallback
  requires. A renderer that ships role/aria-modal but drops the `:ref`
  fails here rather than staying green."
  [tree dialog-testid label]
  (let [dialog (rf.test-helpers/find-by-testid tree dialog-testid)
        attrs  (rf.test-helpers/attrs dialog)]
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
  (let [tree (rf/with-frame :rf/xray (modal-trees/settings-popup-tree rf/dispatch))]
    (assert-dialog-contract! tree "rf-xray-settings-dialog"
                             "Settings popup")))

(deftest settings-popup-close-button-has-aria-label
  (testing "Settings ✕ button accessibility name"
    (xray-setup!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/settings-open]))
    (let [tree  (rf/with-frame :rf/xray (modal-trees/settings-popup-tree rf/dispatch))
          close (rf.test-helpers/find-by-testid tree "rf-xray-settings-close")]
      (is (string? (:aria-label (rf.test-helpers/attrs close)))
          "Settings close ✕ carries an aria-label"))))

;; -------------------------------------------------------------------------
;; (2) Mute manager
;; -------------------------------------------------------------------------
;;
;; The mute manager is a FRESCO BOUNDARY behind an `as-component` bridge:
;; `spine-filters/Modal` answers an interop vector, not a tree to walk, and
;; the boundary's body may only run inside a React render window. The
;; shipped markup is `spine-filters/dialog-tree`, which is PURE OF ITS
;; ARGUMENTS; the door below reproduces `ModalView`'s reads exactly — same
;; query vectors, same order — so both rows below assert on the hiccup the
;; boundary renders. Same shape as
;; `cancellation-cascade-popover-tree` further down this file.

(defn- mute-manager-dialog-tree
  "The mute manager's dialog markup for the current state. Mirrors
  `spine-filters/ModalView`'s reads.

  It does NOT mirror the boundary's `:rf.xray/mute-manager-open?` gate,
  deliberately: these two rows are about the dialog's ARIA markup, not
  its visibility, so this always answers a tree."
  []
  (spine-filters/dialog-tree
    rf/dispatch
    {:muted       @(rf/subscribe [:rf.xray/muted-event-ids])
     :positioning @(rf/subscribe [:rf.xray/modal-positioning])}))

(deftest mute-manager-carries-dialog-contract
  (xray-setup!)
  (let [tree (rf/with-frame :rf/xray (mute-manager-dialog-tree))]
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
  (let [tree (rf/with-frame :rf/xray (modal-trees/edit-popup-tree rf/dispatch))]
    (assert-dialog-contract! tree "rf-xray-edit-popup-dialog"
                             "Filter edit-popup")))

;; -------------------------------------------------------------------------
;; (4) Popover — cancellation-cascade
;; -------------------------------------------------------------------------
;;
;; It gates on an `:open?` slot, and it is a FRESCO BOUNDARY behind an
;; `as-component` bridge: the var answers an interop vector, not a tree to
;; walk, and a boundary's body may only run inside a React render window.
;; The helper below reproduces the boundary's own gate and reads exactly -
;; same gate, same order, same query vectors - so every row below asserts
;; on the hiccup the boundary renders.

(defn- cancellation-cascade-popover-tree
  "The cancellation-cascade popover's markup for the current state:
  nil while closed, the dialog otherwise. Mirrors
  `cancellation-cascade/PopoverView`'s gate and reads."
  []
  (when @(rf/subscribe [:rf.xray/cancellation-cascade-popover-open?])
    (cancellation-cascade/popover-tree
      {:cascade     @(rf/subscribe [:rf.xray/cancellation-cascade-for-focused-event])
       :positioning @(rf/subscribe [:rf.xray/modal-positioning])
       :expanded?   @(rf/subscribe [:rf.xray/cancellation-cascade-expanded?])})))

(deftest cancellation-cascade-popover-carries-dialog-contract
  (testing "the cancellation-cascade popover carries dialog role +
            aria-modal + accessible name on its inner dialog wrapper"
    (xray-setup!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/cancellation-cascade-open
                         {:dispatch-id :test-dispatch-id}]))
    (let [tree (rf/with-frame :rf/xray (cancellation-cascade-popover-tree))]
      (is (some? tree) "Popover renders when open")
      (when tree
        (assert-dialog-contract!
          tree
          "rf-xray-cancellation-cascade-popover-dialog"
          "Cancellation-cascade popover")))))

;; -------------------------------------------------------------------------
;; Per-modal focus-ref wiring
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
  (let [tree (rf/with-frame :rf/xray (modal-trees/settings-popup-tree rf/dispatch))]
    (assert-dialog-focus-ref! tree "rf-xray-settings-dialog"
                              "Settings popup")))

(deftest mute-manager-attaches-focus-ref
  (xray-setup!)
  (let [tree (rf/with-frame :rf/xray (mute-manager-dialog-tree))]
    (assert-dialog-focus-ref! tree "rf-xray-mute-manager-dialog"
                              "Mute manager")))

(deftest filter-edit-popup-attaches-focus-ref
  (xray-setup!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/open-edit-popup
                       {:source :add :mode :in :pill {}}]))
  (let [tree (rf/with-frame :rf/xray (modal-trees/edit-popup-tree rf/dispatch))]
    (assert-dialog-focus-ref! tree "rf-xray-edit-popup-dialog"
                              "Filter edit-popup")))

(deftest cancellation-cascade-popover-attaches-focus-ref
  (xray-setup!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/cancellation-cascade-open
                       {:dispatch-id :test-dispatch-id}]))
  (let [tree (rf/with-frame :rf/xray (cancellation-cascade-popover-tree))]
    (is (some? tree) "Popover renders when open")
    (when tree
      (assert-dialog-focus-ref!
        tree "rf-xray-cancellation-cascade-popover-dialog"
        "Cancellation-cascade popover"))))
