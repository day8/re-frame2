(ns day8.re-frame2-causa.modals-aria-cljs-test
  "WAI-ARIA dialog contract tests for the six Causa modal surfaces
  (rf2-7389r — audit finding #3).

  Each modal must carry:
    - role=\"dialog\"
    - aria-modal=\"true\"
    - an accessible name (either aria-label or aria-labelledby
      pointing at a heading id rendered inside the dialog)

  The audit caught six modals shipping ZERO modal a11y between them.
  This file freezes the post-fix contract so the next renderer cannot
  silently regress.

  ## Surfaces under test

    1. Settings popup  (`settings/view/popup-view`)
    2. Share modal     (`share-modal/share-dialog`)
    3. Mute manager    (`spine-filters/dialog`)
    4. Filter edit-popup (`filters/edit-popup/popup-view`)
    5. Cancellation-cascade popover — exercised via the Popover
       reg-view's body (it renders a [:div {:role \"dialog\" ...}])
    6. App-DB segment-inspector popover — same shape as #5

  Tests render each view function directly (no shadow DOM, no Reagent
  mount) and walk the returned hiccup, asserting the ARIA attribute
  map on the dialog element. Mirrors the pattern used by
  `spine-filters-cljs-test` and `palette/aria-cljs-test`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.filters.edit-popup :as edit-popup]
            [day8.re-frame2-causa.panels.app-db-segment-inspector
             :as segment-inspector]
            [day8.re-frame2-causa.panels.cancellation-cascade
             :as cancellation-cascade]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.settings.view :as settings-view]
            [day8.re-frame2-causa.share-modal :as share-modal]
            [day8.re-frame2-causa.spine-filters :as spine-filters]
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

;; -------------------------------------------------------------------------
;; (1) Settings popup
;; -------------------------------------------------------------------------

(deftest settings-popup-carries-dialog-contract
  (causa-setup!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/settings-open]))
  (let [tree (rf/with-frame :rf/causa (settings-view/popup-view))]
    (assert-dialog-contract! tree "rf-causa-settings-dialog"
                             "Settings popup")))

(deftest settings-popup-close-button-has-aria-label
  (testing "rf2-7389r + audit #14 — Settings ✕ button accessibility name"
    (causa-setup!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/settings-open]))
    (let [tree  (rf/with-frame :rf/causa (settings-view/popup-view))
          close (find-by-testid tree "rf-causa-settings-close")]
      (is (string? (:aria-label (props close)))
          "Settings close ✕ carries an aria-label"))))

;; -------------------------------------------------------------------------
;; (2) Share modal
;; -------------------------------------------------------------------------

(deftest share-modal-carries-dialog-contract
  (causa-setup!)
  (let [tree (rf/with-frame :rf/causa (share-modal/share-dialog))]
    (assert-dialog-contract! tree "rf-causa-share-modal-dialog"
                             "Share modal")))

;; -------------------------------------------------------------------------
;; (3) Mute manager
;; -------------------------------------------------------------------------

(deftest mute-manager-carries-dialog-contract
  (causa-setup!)
  (let [tree (rf/with-frame :rf/causa (spine-filters/dialog))]
    (assert-dialog-contract! tree "rf-causa-mute-manager-dialog"
                             "Mute manager")))

;; -------------------------------------------------------------------------
;; (4) Filter edit-popup
;; -------------------------------------------------------------------------

(deftest filter-edit-popup-carries-dialog-contract
  (causa-setup!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/open-edit-popup
                       {:source :add :mode :in :pill {}}]))
  (let [tree (rf/with-frame :rf/causa (edit-popup/popup-view))]
    (assert-dialog-contract! tree "rf-causa-edit-popup-dialog"
                             "Filter edit-popup")))

;; -------------------------------------------------------------------------
;; (5) + (6) Popovers — cancellation-cascade + segment-inspector
;; -------------------------------------------------------------------------
;;
;; These two use `reg-view` to gate on an `:open?` slot. The reg-view
;; macro defs the symbol; invoking it directly under `with-frame`
;; resolves subscribes against `:rf/causa` the same way the shell
;; mount does.

(deftest cancellation-cascade-popover-carries-dialog-contract
  (testing "rf2-7389r — the cancellation-cascade popover (audit
            findings #3 + #19) carries dialog role + aria-modal +
            accessible name on its inner dialog wrapper"
    (causa-setup!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/cancellation-cascade-open
                         {:dispatch-id :test-dispatch-id}]))
    (let [tree (rf/with-frame :rf/causa (cancellation-cascade/Popover))]
      (is (some? tree) "Popover renders when open")
      (when tree
        (assert-dialog-contract!
          tree
          "rf-causa-cancellation-cascade-popover-dialog"
          "Cancellation-cascade popover")))))

(deftest segment-inspector-popover-carries-dialog-contract
  (testing "rf2-7389r — the App-DB segment-inspector popover carries
            dialog role + aria-modal + accessible name"
    (causa-setup!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/open-segment-inspector []]))
    (let [tree (rf/with-frame :rf/causa (segment-inspector/Popup))]
      (is (some? tree) "Popup renders when open")
      (when tree
        (assert-dialog-contract!
          tree
          "rf-causa-segment-inspector-dialog"
          "Segment inspector popover")))))
