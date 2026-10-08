(ns day8.re-frame2-xray.modals-aria-cljs-test
  "WAI-ARIA dialog contract tests for the Xray modal surfaces.

  Each modal must carry:
    - role=\"dialog\"
    - aria-modal=\"true\"
    - an accessible name (either aria-label or aria-labelledby
      pointing at a heading id rendered inside the dialog)
    - the focus trap: a function `:ref` (the `a11y/dialog-ref` closure)
      and the `:tab-index` its focus-on-open fallback needs, since a
      labelled dialog with no trap still drops keyboard users

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
  "The dialog node is a modal dialog with an accessible name, and it
  attaches the `dialog-ref` focus trap plus the `:tab-index` its
  focus-on-open fallback needs. `dialog-ref` returns a fresh closure per
  call, so the ref is checked as a fn rather than by identity; a labelled
  modal with no trap is an a11y regression this catches."
  [tree dialog-testid label]
  (let [attrs       (rf.test-helpers/attrs (rf.test-helpers/find-by-testid tree dialog-testid))
        labelled-by (:aria-labelledby attrs)
        label-attr  (:aria-label attrs)]
    (is (= ["dialog" "true" true true]
           [(:role attrs) (:aria-modal attrs) (fn? (:ref attrs)) (contains? attrs :tab-index)])
        (str label ": a modal dialog carrying the focus-trap ref and a focus target"))
    (is (or (and labelled-by
                 (some? (rf.test-helpers/find-by-attr tree :id labelled-by)))
            (and (string? label-attr) (seq label-attr)))
        (str label ": accessible name set — aria-labelledby points at a
              heading id in the same tree, or aria-label is non-empty"))))

;; -------------------------------------------------------------------------
;; (1) Settings popup
;; -------------------------------------------------------------------------

(deftest settings-popup-is-a-labelled-focus-trapped-dialog
  (xray-setup!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/settings-open]))
  (let [tree (rf/with-frame :rf/xray (modal-trees/settings-popup-tree rf/dispatch))]
    (assert-dialog-contract! tree "rf-xray-settings-dialog" "Settings popup")))

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

(deftest mute-manager-is-a-labelled-focus-trapped-dialog
  (xray-setup!)
  (let [tree (rf/with-frame :rf/xray (mute-manager-dialog-tree))]
    (assert-dialog-contract! tree "rf-xray-mute-manager-dialog" "Mute manager")))

;; -------------------------------------------------------------------------
;; (3) Filter edit-popup
;; -------------------------------------------------------------------------

(deftest filter-edit-popup-is-a-labelled-focus-trapped-dialog
  (xray-setup!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/open-edit-popup
                       {:source :add :mode :in :pill {}}]))
  (let [tree (rf/with-frame :rf/xray (modal-trees/edit-popup-tree rf/dispatch))]
    (assert-dialog-contract! tree "rf-xray-edit-popup-dialog" "Filter edit-popup")))

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

(deftest cancellation-cascade-popover-is-a-labelled-focus-trapped-dialog
  (testing "the cancellation-cascade popover carries dialog role +
            aria-modal + accessible name, and the focus trap, on its
            inner dialog wrapper"
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

