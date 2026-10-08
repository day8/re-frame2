(ns day8.re-frame2-xray.settings.popup-cljs-test
  "CLJS tests for the Settings popup modal, driven through
  `test-helpers.modal-trees` — the node lane's door onto the pure
  `view/popup-tree`. The click-time routing rows live in
  `popup_dispatch_routing_cljs_test.cljs`, because `cljs.test/async` needs
  the map-shape fixture; the boundary's own paint and gate are the browser
  lane's `settings_fresco_boundary_dom_cljs_test`."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.test-helpers :refer [find-all-by-attr find-by-testid]]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-helpers.modal-trees :as modal-trees]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

(use-fixtures :each
  (xray-test-support/make-xray-runtime-fixture {:tier :runtime}))

(defn- setup! []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray}))

;; ---- sections -----------------------------------------------------------

(deftest tab-switching-changes-section
  (setup!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/settings-open]))
  (doseq [[tab-id testids] [[:general     ["rf-xray-settings-section-general"
                                           "rf-xray-settings-panel-position-right-rail"
                                           "rf-xray-settings-auto-open-on-error"
                                           "rf-xray-settings-epoch-history-input"
                                           "rf-xray-settings-epoch-history-value"]]
                            [:keybindings ["rf-xray-settings-section-keybindings"]]
                            [:buffer      ["rf-xray-settings-section-buffer"
                                           "rf-xray-settings-buffer-events-retained"]]
                            [:diff        ["rf-xray-settings-section-diff"]]]]
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/settings-select-tab tab-id])
      (let [rendered (modal-trees/settings-popup-tree)]
        (is (= [] (remove #(find-by-testid rendered %) testids))
            (str "tab " tab-id " renders its section and controls"))))))

(deftest general-section-restores-show-unchanged-subs-pin
  ;; spec/021 §3.4 promises this pin a UI: a controlled checkbox that
  ;; reflects the `:general :show-unchanged-subs?` slot and writes it.
  (setup!)
  (let [box #(rf/with-frame :rf/xray
               (second (find-by-testid (modal-trees/settings-popup-tree)
                                       "rf-xray-settings-show-unchanged-subs")))]
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/settings-open]))
    (let [attrs (box)]
      (is (= [false true] [(:checked attrs) (fn? (:on-change attrs))])
          "unchecked by default, with an :on-change writer"))
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/settings-update :general :show-unchanged-subs? true]))
    (is (true? (:checked (box))) "the checkbox reflects the flipped-on pin")))

(deftest editor-override-radio-follows-the-slot
  ;; Exactly one radio is checked for each slot shape, and the
  ;; URI-template input shows only while Custom is the checked one.
  (setup!)
  (doseq [[override checked] [[nil :default] [:idea :idea] [{:custom ""} :custom]]]
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/settings-update :general :editor-override override])
      (rf/dispatch-sync [:rf.xray/settings-open])
      (let [rendered (modal-trees/settings-popup-tree)]
        (is (= [[(str "rf-xray-settings-editor-override-" (name checked))] (= :custom checked)]
               [(->> (find-all-by-attr rendered :name "rf-xray-settings-editor-override")
                     (filter (comp :checked second))
                     (mapv (comp :data-testid second)))
                (some? (find-by-testid rendered "rf-xray-settings-editor-override-custom-input"))])
            (pr-str override))))))

;; ---- open / close -------------------------------------------------------

(deftest open-resets-active-tab-to-general
  (setup!)
  ;; Pre-set tab to a non-default; reopen must reset to :general.
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/settings-open])
    (rf/dispatch-sync [:rf.xray/settings-select-tab :buffer])
    (rf/dispatch-sync [:rf.xray/settings-close])
    (rf/dispatch-sync [:rf.xray/settings-open]))
  (is (= :general (:settings-active-tab (rf/app-db-value :rf/xray)))
      "reopening returns to :general default"))

(deftest toggle-cycles-open-state
  (setup!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/settings-toggle]))
  (is (true? (boolean (:settings-open? (rf/app-db-value :rf/xray)))))
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/settings-toggle]))
  (is (false? (boolean (:settings-open? (rf/app-db-value :rf/xray))))))

;; ---- modal positioning --------------------------------------------------

(deftest backdrop-defaults-to-fixed-positioning
  (setup!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/settings-open])
    (let [[_ attrs] (find-by-testid (modal-trees/settings-popup-tree)
                                    "rf-xray-settings-backdrop")]
      (is (= ["fixed" 2147483646 "fixed"]
             [(:position (:style attrs))
              (:z-index (:style attrs))
              (:data-rf-xray-modal-positioning attrs)])))))

(deftest backdrop-honours-absolute-positioning
  ;; Story testbeds confine the backdrop to the shell cell.
  (setup!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/settings-open])
    (rf/dispatch-sync [:rf.xray/set-modal-positioning :absolute])
    (let [[_ attrs] (find-by-testid (modal-trees/settings-popup-tree)
                                    "rf-xray-settings-backdrop")]
      (is (= ["absolute" "absolute"]
             [(:position (:style attrs))
              (:data-rf-xray-modal-positioning attrs)]))
      (is (< (:z-index (:style attrs)) 1000)))))
