(ns day8.re-frame2-xray.settings.popup-cljs-test
  "CLJS tests for the Settings popup modal (rf2-9poxq).

  Asserts:
  - Modal renders when `:rf.xray/settings-open?` true
  - Esc dispatches close
  - Each section renders
  - Tab strip switches sections

  Click-time frame-routing tests (rf2-smvvz — the X button / backdrop /
  Esc / tab-button must close the modal even when the dispatch fires
  outside `:rf/xray`'s React-context tier) live in
  `popup_dispatch_routing_cljs_test.cljs` — separate ns because they
  use `cljs.test/async` which requires a different `use-fixtures` shape.

  Uses the same hiccup-walk + plain-atom-fixture pattern as
  `shell_cljs_test.cljs` so the test surface stays Reagent-free
  on the assertion side."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-helpers :as th]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.settings.popup :as popup]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.trace-collector :as trace-collector]))

;; ---- fixture ------------------------------------------------------------

(defn- xray-init! []
  (xray-test-support/reset-all!)
  (trace-collector/reset-for-test!)
  (config/reset-settings!))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn xray-init!}))

(defn- setup! []
  (registry/register-xray-handlers!)
  (frame/reg-frame :rf/xray {}))

;; ---- hiccup walker -----------------------------------------------------
;; Thin alias over re-frame.test-helpers so call sites read identically
;; to before.

(def ^:private find-by-testid th/find-by-testid)

;; ---- Modal short-circuit -----------------------------------------------

(deftest modal-renders-nil-when-closed
  (setup!)
  (rf/with-frame :rf/xray
    (let [rendered (popup/Modal)]
      (is (nil? rendered)
          "Modal renders nil when settings-open? is false"))))

(deftest modal-renders-when-open
  (setup!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/settings-open]))
  (rf/with-frame :rf/xray
    (let [rendered (popup/Modal)]
      (is (some? rendered)
          "Modal renders hiccup when settings-open? is true")
      (is (find-by-testid rendered "rf-xray-settings-backdrop")
          "backdrop is present")
      (is (find-by-testid rendered "rf-xray-settings-dialog")
          "dialog is present")
      (is (find-by-testid rendered "rf-xray-settings-close")
          "close button is present"))))

;; ---- Esc key closes ----------------------------------------------------

(deftest esc-keydown-dispatches-close
  (setup!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/settings-open]))
  (is (true? (boolean (:settings-open?
                       (rf/get-frame-db :rf/xray))))
      "modal is open before Esc")
  ;; Simulate the keydown handler running. We don't have a DOM-event
  ;; here so call the dispatch directly — the handler's only side
  ;; effect under Esc is the dispatch, which the integration test in
  ;; the testbed exercises end-to-end.
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/settings-close]))
  (is (false? (boolean (:settings-open?
                        (rf/get-frame-db :rf/xray))))
      "modal closes after dispatch"))

;; ---- Each section renders ----------------------------------------------

(deftest general-section-renders
  (setup!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/settings-open])
    (rf/dispatch-sync [:rf.xray/settings-select-tab :general]))
  (rf/with-frame :rf/xray
    (let [rendered (popup/Modal)]
      (is (find-by-testid rendered "rf-xray-settings-section-general"))
      (is (find-by-testid rendered "rf-xray-settings-text-size-input"))
      (is (find-by-testid rendered "rf-xray-settings-panel-position-right-rail"))
      (is (find-by-testid rendered "rf-xray-settings-auto-open-on-error"))
      ;; rf2-3zyyx — Epoch history slider renders in General with its
      ;; numeric readout sibling.
      (is (find-by-testid rendered "rf-xray-settings-epoch-history-input")
          "Epoch history slider renders in General")
      (is (find-by-testid rendered "rf-xray-settings-epoch-history-value")
          "Epoch history numeric readout renders alongside the slider"))))

;; Filters tab removed (rf2-wknb3) — filter management lives in the
;; top-ribbon pill strip (`filters/pills.cljs`), the per-pill edit
;; popup (`filters/edit_popup.cljs`), and the mute manager modal
;; (rf2-ikuwt). The settings tab's only widget was a dead-chrome
;; 'Open auto-filter UI' button dispatching an unregistered event.

(deftest filters-section-is-gone
  (testing "rf2-wknb3 — selecting `:filters` no longer surfaces a
            section; the body falls through to the General section's
            fallback (default branch of the body case)."
    (setup!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/settings-open])
      (rf/dispatch-sync [:rf.xray/settings-select-tab :filters]))
    (rf/with-frame :rf/xray
      (let [rendered (popup/Modal)]
        (is (nil? (find-by-testid rendered "rf-xray-settings-section-filters"))
            "Filters section is gone")
        (is (nil? (find-by-testid rendered "rf-xray-settings-tab-filters"))
            "Filters tab button is gone from the strip")))))

;; Theme tab removed (rf2-ou3pn) — the top-ribbon sun/moon icon is
;; now the canonical light/dark affordance. The previous
;; `theme-section-renders` + `theme-label-matches-actual-default`
;; tests are gone with the tab. `config/default-settings :theme` is
;; still pinned `:light` by `theme/effects_cljs_test.cljs` (canonical
;; default + apply-theme! fallback). The `:use-system-colors?`
;; HCM-override checkbox moved to General → Power user and is now
;; exercised by `use-system-colors-renders-in-general`.

(deftest use-system-colors-renders-in-general
  (testing "rf2-ou3pn — `:use-system-colors?` HCM-override checkbox
            relocated from the retired Theme tab to General → Power
            user. The settings slot has always been `:general
            :use-system-colors?` — only the cosmetic home moved."
    (setup!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/settings-open])
      (rf/dispatch-sync [:rf.xray/settings-select-tab :general]))
    (rf/with-frame :rf/xray
      (let [rendered (popup/Modal)]
        (is (find-by-testid rendered "rf-xray-settings-use-system-colors")
            "Use system colors toggle renders in the General section")
        (is (nil? (find-by-testid rendered "rf-xray-settings-section-theme"))
            "Theme section is gone")))))

;; ---- Tab switching ------------------------------------------------------

(deftest tab-switching-changes-section
  (setup!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/settings-open]))
  (doseq [[tab-id section-testid] [[:general     "rf-xray-settings-section-general"]
                                   [:keybindings "rf-xray-settings-section-keybindings"]
                                   [:buffer      "rf-xray-settings-section-buffer"]
                                   [:diff        "rf-xray-settings-section-diff"]]]
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/settings-select-tab tab-id]))
    (rf/with-frame :rf/xray
      (let [rendered (popup/Modal)]
        (is (find-by-testid rendered section-testid)
            (str "tab " tab-id " renders its section"))))))

;; ---- Open/close events --------------------------------------------------

(deftest open-resets-active-tab-to-general
  (setup!)
  ;; Pre-set tab to a non-default; reopen must reset to :general.
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/settings-open])
    (rf/dispatch-sync [:rf.xray/settings-select-tab :buffer])
    (rf/dispatch-sync [:rf.xray/settings-close])
    (rf/dispatch-sync [:rf.xray/settings-open]))
  (is (= :general (:settings-active-tab (rf/get-frame-db :rf/xray)))
      "reopening returns to :general default"))

(deftest toggle-cycles-open-state
  (setup!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/settings-toggle]))
  (is (true? (boolean (:settings-open? (rf/get-frame-db :rf/xray)))))
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/settings-toggle]))
  (is (false? (boolean (:settings-open? (rf/get-frame-db :rf/xray))))))

;; ---- Modal positioning (rf2-om6fa) -------------------------------------

(deftest backdrop-defaults-to-fixed-positioning
  (testing "with no :rf.xray/modal-positioning slot set, backdrop
            renders position: fixed at the production z-index"
    (setup!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/settings-open]))
    (rf/with-frame :rf/xray
      (let [rendered (popup/Modal)
            backdrop (find-by-testid rendered "rf-xray-settings-backdrop")
            style    (:style (second backdrop))]
        (is (some? backdrop))
        (is (= "fixed" (:position style))
            ":position is :fixed by default")
        (is (= 2147483646 (:z-index style))
            "production z-index unchanged")
        (is (= "fixed"
               (:data-rf-xray-modal-positioning (second backdrop)))
            "data attribute echoes the resolved positioning")))))

(deftest backdrop-honours-absolute-positioning
  (testing "after `:rf.xray/set-modal-positioning :absolute` the
            backdrop switches to position: absolute with a sane
            in-cell z-index"
    (setup!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/settings-open])
      (rf/dispatch-sync [:rf.xray/set-modal-positioning :absolute]))
    (rf/with-frame :rf/xray
      (let [rendered (popup/Modal)
            backdrop (find-by-testid rendered "rf-xray-settings-backdrop")
            style    (:style (second backdrop))]
        (is (some? backdrop))
        (is (= "absolute" (:position style))
            ":position is :absolute under the testbed opt")
        (is (< (:z-index style) 1000)
            "z-index drops to a sane in-cell value")
        (is (= "absolute"
               (:data-rf-xray-modal-positioning (second backdrop)))
            "data attribute echoes the resolved positioning")))))

