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
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-helpers :as th]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.settings.popup :as popup]
            [day8.re-frame2-xray.settings.view :as view]
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

(deftest filters-section-renders
  (setup!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/settings-open])
    (rf/dispatch-sync [:rf.xray/settings-select-tab :filters]))
  (rf/with-frame :rf/xray
    (let [rendered (popup/Modal)]
      (is (find-by-testid rendered "rf-xray-settings-section-filters"))
      ;; The filters ns is loaded (rf2-ak4ms landed) — the section
      ;; surfaces the open button rather than the install hint.
      (is (find-by-testid rendered "rf-xray-settings-filters-open")
          "open button renders when filters feature is present")
      (is (nil? (find-by-testid rendered "rf-xray-settings-filters-install-hint"))
          "install hint hidden when filters feature is present"))))

(deftest theme-section-renders
  (setup!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/settings-open])
    (rf/dispatch-sync [:rf.xray/settings-select-tab :theme]))
  (rf/with-frame :rf/xray
    (let [rendered (popup/Modal)]
      (is (find-by-testid rendered "rf-xray-settings-section-theme"))
      (is (find-by-testid rendered "rf-xray-settings-theme-dark"))
      (is (find-by-testid rendered "rf-xray-settings-theme-light"))
      ;; rf2-846h2 — the Theme section also houses the operator-side
      ;; "Use system colors" opt-in checkbox so HCM-style chrome is
      ;; reachable without flipping the OS-level switch.
      (is (find-by-testid rendered "rf-xray-settings-use-system-colors")
          "Use system colors toggle renders in the Theme section"))))

(deftest theme-label-matches-actual-default
  (testing "rf2-pfx4u — the `(default)` annotation in the Theme section
            labels MUST sit next to the actually-default option per
            `config/default-settings`, not be hard-coded against Dark.
            The default is `:light` (Figma authority + `config.cljc`
            + `effects/apply-theme!` fallback), so the Light label
            carries `(default)` and the Dark label does NOT.

            Asserted by walking the radio's parent `<label>` and
            asking `text-content` for the visible copy. The parent
            label is the SECOND ancestor of the `<input>` — the
            tree shape is `[:label ... [:input {...}] \"Dark\"]`."
    (setup!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/settings-open])
      (rf/dispatch-sync [:rf.xray/settings-select-tab :theme]))
    (rf/with-frame :rf/xray
      (let [rendered    (popup/Modal)
            section     (find-by-testid rendered "rf-xray-settings-section-theme")
            ;; the visible copy of each radio's row is the concatenation
            ;; of strings in the section that share its sibling line;
            ;; checking via `text-content` on the section is the
            ;; simplest faithful read of what the operator sees.
            section-txt (th/text-content section)
            default-theme (:theme config/default-settings)]
        ;; the canonical default must be `:light` (Figma authority).
        ;; Pinning this here means a future flip of the default
        ;; surfaces as a deliberate edit to this test, not as a silent
        ;; label drift.
        (is (= :light default-theme)
            "config/default-settings :theme is :light (Figma authority)")
        ;; the `(default)` annotation sits next to Light, not Dark.
        (is (re-find #"Light \(default\)" section-txt)
            "Light option is labelled `Light (default)` (rf2-pfx4u)")
        (is (not (re-find #"Dark \(default\)" section-txt))
            "Dark option is NOT labelled `Dark (default)` — the prior stale label is gone (rf2-pfx4u)")
        ;; Dark still surfaces as a `Dark` label (base copy preserved
        ;; — only the `(default)` marker moved). Use a substring check
        ;; rather than `\bDark\b` because the section's text-content
        ;; concatenates sibling strings without separators (the radio
        ;; rows render adjacent, so the joined leaves read `DarkLight`).
        (is (str/includes? section-txt "Dark")
            "Dark option still labelled `Dark` (without the default marker)")))))

;; ---- Tab switching ------------------------------------------------------

(deftest tab-switching-changes-section
  (setup!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/settings-open]))
  (doseq [[tab-id section-testid] [[:general "rf-xray-settings-section-general"]
                                   [:filters "rf-xray-settings-section-filters"]
                                   [:theme   "rf-xray-settings-section-theme"]]]
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/settings-select-tab tab-id]))
    (rf/with-frame :rf/xray
      (let [rendered (popup/Modal)]
        (is (find-by-testid rendered section-testid)
            (str "tab " tab-id " renders its section"))))))

;; ---- Open/close events --------------------------------------------------

(deftest open-resets-active-tab-to-general
  (setup!)
  ;; Pre-set tab to filters
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/settings-open])
    (rf/dispatch-sync [:rf.xray/settings-select-tab :filters])
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

