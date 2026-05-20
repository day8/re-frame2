(ns day8.re-frame2-causa.settings.popup-cljs-test
  "CLJS tests for the Settings popup modal (rf2-9poxq).

  Asserts:
  - Modal renders when `:rf.causa/settings-open?` true
  - Esc dispatches close
  - Each section renders
  - Tab strip switches sections

  Click-time frame-routing tests (rf2-smvvz — the X button / backdrop /
  Esc / tab-button must close the modal even when the dispatch fires
  outside `:rf/causa`'s React-context tier) live in
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
            [day8.re-frame2-causa.config :as config]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.settings.popup :as popup]
            [day8.re-frame2-causa.settings.view :as view]
            [day8.re-frame2-causa.test-support :as causa-test-support]
            [day8.re-frame2-causa.trace-bus :as trace-bus]))

;; ---- fixture ------------------------------------------------------------

(defn- causa-init! []
  (causa-test-support/reset-all!)
  (trace-bus/clear-buffer!)
  (config/reset-settings!))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn causa-init!}))

(defn- setup! []
  (registry/register-causa-handlers!)
  (frame/reg-frame :rf/causa {}))

;; ---- hiccup walker -----------------------------------------------------
;; Thin alias over re-frame.test-helpers so call sites read identically
;; to before.

(def ^:private find-by-testid th/find-by-testid)

;; ---- Modal short-circuit -----------------------------------------------

(deftest modal-renders-nil-when-closed
  (setup!)
  (rf/with-frame :rf/causa
    (let [rendered (popup/Modal)]
      (is (nil? rendered)
          "Modal renders nil when settings-open? is false"))))

(deftest modal-renders-when-open
  (setup!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/settings-open]))
  (rf/with-frame :rf/causa
    (let [rendered (popup/Modal)]
      (is (some? rendered)
          "Modal renders hiccup when settings-open? is true")
      (is (find-by-testid rendered "rf-causa-settings-backdrop")
          "backdrop is present")
      (is (find-by-testid rendered "rf-causa-settings-dialog")
          "dialog is present")
      (is (find-by-testid rendered "rf-causa-settings-close")
          "close button is present"))))

;; ---- Esc key closes ----------------------------------------------------

(deftest esc-keydown-dispatches-close
  (setup!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/settings-open]))
  (is (true? (boolean (:settings-open?
                       (rf/get-frame-db :rf/causa))))
      "modal is open before Esc")
  ;; Simulate the keydown handler running. We don't have a DOM-event
  ;; here so call the dispatch directly — the handler's only side
  ;; effect under Esc is the dispatch, which the integration test in
  ;; the testbed exercises end-to-end.
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/settings-close]))
  (is (false? (boolean (:settings-open?
                        (rf/get-frame-db :rf/causa))))
      "modal closes after dispatch"))

;; ---- Each section renders ----------------------------------------------

(deftest general-section-renders
  (setup!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/settings-open])
    (rf/dispatch-sync [:rf.causa/settings-select-tab :general]))
  (rf/with-frame :rf/causa
    (let [rendered (popup/Modal)]
      (is (find-by-testid rendered "rf-causa-settings-section-general"))
      (is (find-by-testid rendered "rf-causa-settings-text-size-input"))
      (is (find-by-testid rendered "rf-causa-settings-panel-position-right-rail"))
      (is (find-by-testid rendered "rf-causa-settings-auto-open-on-error"))
      ;; rf2-3zyyx — Epoch history slider renders in General with its
      ;; numeric readout sibling.
      (is (find-by-testid rendered "rf-causa-settings-epoch-history-input")
          "Epoch history slider renders in General")
      (is (find-by-testid rendered "rf-causa-settings-epoch-history-value")
          "Epoch history numeric readout renders alongside the slider"))))

(deftest filters-section-renders
  (setup!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/settings-open])
    (rf/dispatch-sync [:rf.causa/settings-select-tab :filters]))
  (rf/with-frame :rf/causa
    (let [rendered (popup/Modal)]
      (is (find-by-testid rendered "rf-causa-settings-section-filters"))
      ;; The filters ns is loaded (rf2-ak4ms landed) — the section
      ;; surfaces the open button rather than the install hint.
      (is (find-by-testid rendered "rf-causa-settings-filters-open")
          "open button renders when filters feature is present")
      (is (nil? (find-by-testid rendered "rf-causa-settings-filters-install-hint"))
          "install hint hidden when filters feature is present"))))

(deftest theme-section-renders
  (setup!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/settings-open])
    (rf/dispatch-sync [:rf.causa/settings-select-tab :theme]))
  (rf/with-frame :rf/causa
    (let [rendered (popup/Modal)]
      (is (find-by-testid rendered "rf-causa-settings-section-theme"))
      (is (find-by-testid rendered "rf-causa-settings-theme-dark"))
      (is (find-by-testid rendered "rf-causa-settings-theme-light"))
      ;; rf2-846h2 — the Theme section also houses the operator-side
      ;; "Use system colors" opt-in checkbox so HCM-style chrome is
      ;; reachable without flipping the OS-level switch.
      (is (find-by-testid rendered "rf-causa-settings-use-system-colors")
          "Use system colors toggle renders in the Theme section"))))

;; ---- Tab switching ------------------------------------------------------

(deftest tab-switching-changes-section
  (setup!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/settings-open]))
  (doseq [[tab-id section-testid] [[:general "rf-causa-settings-section-general"]
                                   [:filters "rf-causa-settings-section-filters"]
                                   [:theme   "rf-causa-settings-section-theme"]]]
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/settings-select-tab tab-id]))
    (rf/with-frame :rf/causa
      (let [rendered (popup/Modal)]
        (is (find-by-testid rendered section-testid)
            (str "tab " tab-id " renders its section"))))))

;; ---- Open/close events --------------------------------------------------

(deftest open-resets-active-tab-to-general
  (setup!)
  ;; Pre-set tab to filters
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/settings-open])
    (rf/dispatch-sync [:rf.causa/settings-select-tab :filters])
    (rf/dispatch-sync [:rf.causa/settings-close])
    (rf/dispatch-sync [:rf.causa/settings-open]))
  (is (= :general (:settings-active-tab (rf/get-frame-db :rf/causa)))
      "reopening returns to :general default"))

(deftest toggle-cycles-open-state
  (setup!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/settings-toggle]))
  (is (true? (boolean (:settings-open? (rf/get-frame-db :rf/causa)))))
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/settings-toggle]))
  (is (false? (boolean (:settings-open? (rf/get-frame-db :rf/causa))))))

;; ---- Modal positioning (rf2-om6fa) -------------------------------------

(deftest backdrop-defaults-to-fixed-positioning
  (testing "with no :rf.causa/modal-positioning slot set, backdrop
            renders position: fixed at the production z-index"
    (setup!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/settings-open]))
    (rf/with-frame :rf/causa
      (let [rendered (popup/Modal)
            backdrop (find-by-testid rendered "rf-causa-settings-backdrop")
            style    (:style (second backdrop))]
        (is (some? backdrop))
        (is (= "fixed" (:position style))
            ":position is :fixed by default")
        (is (= 2147483646 (:z-index style))
            "production z-index unchanged")
        (is (= "fixed"
               (:data-rf-causa-modal-positioning (second backdrop)))
            "data attribute echoes the resolved positioning")))))

(deftest backdrop-honours-absolute-positioning
  (testing "after `:rf.causa/set-modal-positioning :absolute` the
            backdrop switches to position: absolute with a sane
            in-cell z-index"
    (setup!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/settings-open])
      (rf/dispatch-sync [:rf.causa/set-modal-positioning :absolute]))
    (rf/with-frame :rf/causa
      (let [rendered (popup/Modal)
            backdrop (find-by-testid rendered "rf-causa-settings-backdrop")
            style    (:style (second backdrop))]
        (is (some? backdrop))
        (is (= "absolute" (:position style))
            ":position is :absolute under the testbed opt")
        (is (< (:z-index style) 1000)
            "z-index drops to a sane in-cell value")
        (is (= "absolute"
               (:data-rf-causa-modal-positioning (second backdrop)))
            "data attribute echoes the resolved positioning")))))

