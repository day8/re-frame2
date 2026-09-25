(ns day8.re-frame2-xray.settings.popup-cljs-test
  "CLJS tests for the Settings popup modal.

  Asserts:
  - Modal renders when `:rf.xray/settings-open?` true
  - Esc dispatches close
  - Each section renders
  - Tab strip switches sections

  Click-time frame-routing tests (the X button / backdrop /
  Esc / tab-button must close the modal even when the dispatch fires
  outside `:rf/xray`'s React-context tier) live in
  `popup_dispatch_routing_cljs_test.cljs` — separate ns because they
  use `cljs.test/async` which requires a different `use-fixtures` shape.

  Uses the same hiccup-walk + plain-atom-fixture pattern as
  `shell_cljs_test.cljs` so the test surface stays Reagent-free
  on the assertion side."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.test-helpers :as rf.test-helpers]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-helpers.modal-trees :as modal-trees]
            [day8.re-frame2-xray.settings.view :as view]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

;; ---- fixture ------------------------------------------------------------

(use-fixtures :each
  ;; `make-xray-runtime-fixture` owns the setup: plain-atom adapter + the
  ;; `:runtime` reset tier (sentinels + trace-collector rings + the
  ;; persisted settings atom).
  (xray-test-support/make-xray-runtime-fixture {:tier :runtime}))

(defn- setup! []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray}))

;; ---- hiccup walker -----------------------------------------------------
;; Thin alias over re-frame.test-helpers.

(def ^:private find-by-testid rf.test-helpers/find-by-testid)

;; ---- Modal short-circuit -----------------------------------------------

(deftest modal-renders-nil-when-closed
  (setup!)
  (rf/with-frame :rf/xray
    (let [rendered (modal-trees/settings-popup-tree)]
      (is (nil? rendered)
          "Modal renders nil when settings-open? is false"))))

(deftest modal-renders-when-open
  (setup!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/settings-open]))
  (rf/with-frame :rf/xray
    (let [rendered (modal-trees/settings-popup-tree)]
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
                       (rf/app-db-value :rf/xray))))
      "modal is open before Esc")
  ;; Simulate the keydown handler running. We don't have a DOM-event
  ;; here so call the dispatch directly — the handler's only side
  ;; effect under Esc is the dispatch, which the integration test in
  ;; the testbed exercises end-to-end.
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/settings-close]))
  (is (false? (boolean (:settings-open?
                        (rf/app-db-value :rf/xray))))
      "modal closes after dispatch"))

;; ---- Each section renders ----------------------------------------------

(deftest general-section-renders
  (setup!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/settings-open])
    (rf/dispatch-sync [:rf.xray/settings-select-tab :general]))
  (rf/with-frame :rf/xray
    (let [rendered (modal-trees/settings-popup-tree)]
      (is (find-by-testid rendered "rf-xray-settings-section-general"))
      (is (find-by-testid rendered "rf-xray-settings-panel-position-right-rail"))
      (is (find-by-testid rendered "rf-xray-settings-auto-open-on-error"))
      ;; There is no text-size slider — defaults suffice. The
      ;; `:general :text-size` slot + the `apply-text-size!` effect
      ;; serve a host-set default.
      (is (nil? (find-by-testid rendered "rf-xray-settings-text-size-input"))
          "No text-size slider in the popup (defaults suffice)")
      ;; The Epoch history slider lives in General, next to the auto-
      ;; open-on-error checkbox. Its slot is `:general :epoch-history`;
      ;; the Buffer section does not carry the slider.
      (is (find-by-testid rendered "rf-xray-settings-epoch-history-input")
          "Epoch history slider renders in General")
      (is (find-by-testid rendered "rf-xray-settings-epoch-history-value")
          "Epoch history numeric readout renders alongside the slider"))))

(deftest general-section-restores-show-unchanged-subs-pin
  (testing "the General tab exposes the 'Always show
            unchanged subs' pin (spec/021 §3.4) for the
            `:general :show-unchanged-subs?` slot, so the pin the spec
            promises has a UI. The controlled checkbox reflects the slot
            and writes via :rf.xray/settings-update."
    (setup!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/settings-open])
      (rf/dispatch-sync [:rf.xray/settings-select-tab :general]))
    (rf/with-frame :rf/xray
      (let [rendered (modal-trees/settings-popup-tree)
            box      (find-by-testid rendered "rf-xray-settings-show-unchanged-subs")]
        (is (some? box) "the show-unchanged-subs checkbox renders")
        (is (= false (:checked (second box)))
            "unchecked by default (slot default OFF)")
        (is (fn? (:on-change (second box)))
            "the checkbox carries an :on-change writer")))
    ;; Flip the pin ON; the controlled checkbox reflects the slot on re-render.
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/settings-update :general :show-unchanged-subs? true]))
    (rf/with-frame :rf/xray
      (let [rendered (modal-trees/settings-popup-tree)
            box      (find-by-testid rendered "rf-xray-settings-show-unchanged-subs")]
        (is (= true (:checked (second box)))
            "the checkbox reflects the flipped-on pin")))))

;; The Epoch history slider's slot is `:general :epoch-history` and its
;; visual home is General; `general-section-renders` above covers it,
;; and the Buffer section does not carry the slider.

(deftest epoch-history-slider-absent-from-buffer-section
  (testing "Epoch history slider does NOT render in the Buffer section;
            it lives in General."
    (setup!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/settings-open])
      (rf/dispatch-sync [:rf.xray/settings-select-tab :buffer]))
    (rf/with-frame :rf/xray
      (let [rendered (modal-trees/settings-popup-tree)]
        (is (find-by-testid rendered "rf-xray-settings-section-buffer"))
        (is (nil? (find-by-testid rendered "rf-xray-settings-epoch-history-input"))
            "Epoch history slider is not in Buffer (it lives in General)")
        (is (nil? (find-by-testid rendered "rf-xray-settings-epoch-history-value"))
            "Epoch history numeric readout is not in Buffer")
        ;; There is no `:buffer :retained-epochs` numeric input: it
        ;; would have no substrate consumer.
        (is (nil? (find-by-testid rendered "rf-xray-settings-buffer-retained-epochs"))
            "No `:buffer :retained-epochs` numeric input")
        ;; There is no `:buffer :app-db/inspector-collapse-threshold`
        ;; numeric input: it would have no runtime consumer (the
        ;; inspector auto-collapses on depth/width). The
        ;; `:events-retained` field writes through to
        ;; `(rf/configure! {:trace-buffer ...})`.
        (is (nil? (find-by-testid rendered "rf-xray-settings-buffer-inspector-collapse"))
            "No `:buffer :app-db/inspector-collapse-threshold` input")
        (is (find-by-testid rendered "rf-xray-settings-buffer-events-retained")
            "Events-retained field renders (wired to :trace-buffer)")))))

;; There is no Filters tab — filter management lives in the
;; top-ribbon pill strip (`filters/pills.cljs`), the per-pill edit
;; popup (`filters/edit_popup.cljs`), and the mute manager modal.

(deftest filters-section-is-gone
  (testing "selecting `:filters` surfaces no section; the body falls
            through to the General section's fallback (default branch
            of the body case)."
    (setup!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/settings-open])
      (rf/dispatch-sync [:rf.xray/settings-select-tab :filters]))
    (rf/with-frame :rf/xray
      (let [rendered (modal-trees/settings-popup-tree)]
        (is (nil? (find-by-testid rendered "rf-xray-settings-section-filters"))
            "No Filters section")
        (is (nil? (find-by-testid rendered "rf-xray-settings-tab-filters"))
            "No Filters tab button in the strip")))))

;; There is no Theme tab — the top-ribbon sun/moon icon is the
;; canonical light/dark affordance. `config/default-settings :theme` is
;; pinned `:light` by `persistence_cljs_test.cljs`'s
;; `defaults-match-spec`.

(deftest editor-override-picker-renders-in-general
  (testing "General tab carries the editor-override picker
            (radio set + Reset button + host-default hint). One radio
            per enumerated editor plus '(project default)' and
            Custom; the custom URI-template input is gated on the
            Custom radio being active."
    (setup!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/settings-open])
      (rf/dispatch-sync [:rf.xray/settings-select-tab :general]))
    (rf/with-frame :rf/xray
      (let [rendered (modal-trees/settings-popup-tree)]
        ;; Picker container.
        (is (find-by-testid rendered "rf-xray-settings-editor-override")
            "editor-override field group renders")
        ;; Enumerated radios + the (project default) + custom radio.
        (is (find-by-testid rendered "rf-xray-settings-editor-override-default"))
        (is (find-by-testid rendered "rf-xray-settings-editor-override-vscode"))
        (is (find-by-testid rendered "rf-xray-settings-editor-override-cursor"))
        (is (find-by-testid rendered "rf-xray-settings-editor-override-windsurf"))
        (is (find-by-testid rendered "rf-xray-settings-editor-override-zed"))
        (is (find-by-testid rendered "rf-xray-settings-editor-override-idea"))
        (is (find-by-testid rendered "rf-xray-settings-editor-override-custom"))
        ;; Reset-to-host button + host-default hint.
        (is (find-by-testid rendered "rf-xray-settings-editor-override-reset"))
        (is (find-by-testid rendered "rf-xray-settings-editor-override-host-default"))
        ;; Custom-template input hidden on a fresh install (no
        ;; override → :default radio is active).
        (is (nil? (find-by-testid rendered "rf-xray-settings-editor-override-custom-input"))
            "custom template input hidden until Custom radio is active")))))

(deftest editor-override-custom-input-surfaces-when-custom-selected
  (testing "selecting Custom (writing `{:custom <tpl>}`
            to the slot) reveals the URI-template input on the next
            render"
    (setup!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/settings-open])
      (rf/dispatch-sync [:rf.xray/settings-select-tab :general])
      (rf/dispatch-sync [:rf.xray/settings-update
                         :general :editor-override {:custom ""}]))
    (rf/with-frame :rf/xray
      (let [rendered (modal-trees/settings-popup-tree)]
        (is (find-by-testid rendered "rf-xray-settings-editor-override-custom-input")
            "custom template input renders once the override is the
             :custom shape")))))

(deftest editor-override-custom-radio-seeds-working-template
  (testing "the Custom radio's seed value (used when the
            user first selects Custom with no prior template) is a
            working vscode-style URI rather than `{:custom \"\"}`.
            With an empty seed, click-to-source would silently no-op
            until the user finished typing the template; the seed makes
            the override resolve to a valid URI immediately so the user
            edits from a known baseline."
    (let [seed @#'view/custom-template-seed]
      (is (string? seed) "the seed is a string")
      (is (not= "" seed)
          "the seed is NOT the empty string (empty templates break
           click-to-source)")
      (is (= "vscode://file/{path}:{line}:{column}" seed)
          "the seed echoes the framework-default :vscode URI shape so
           the chip resolves to a working URI immediately")
      (is (config/valid-editor-override? {:custom seed})
          "the seeded `{:custom <seed>}` shape passes the read-side
           validator"))))

(deftest editor-override-default-radio-is-checked-on-fresh-install
  (testing "with no override, the '(project default)'
            radio is the selected option"
    (setup!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/settings-open])
      (rf/dispatch-sync [:rf.xray/settings-select-tab :general]))
    (rf/with-frame :rf/xray
      (let [rendered (modal-trees/settings-popup-tree)
            default-radio (find-by-testid rendered "rf-xray-settings-editor-override-default")
            vscode-radio  (find-by-testid rendered "rf-xray-settings-editor-override-vscode")]
        (is (true? (:checked (second default-radio)))
            "(project default) radio is checked")
        (is (false? (:checked (second vscode-radio)))
            "VS Code radio is unchecked")))))

(deftest editor-override-enumerated-radio-reflects-active-override
  (testing "writing an enumerated-keyword override
            (`:idea`) flips the checked radio to that option"
    (setup!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/settings-open])
      (rf/dispatch-sync [:rf.xray/settings-select-tab :general])
      (rf/dispatch-sync [:rf.xray/settings-update
                         :general :editor-override :idea]))
    (rf/with-frame :rf/xray
      (let [rendered (modal-trees/settings-popup-tree)
            default-radio (find-by-testid rendered "rf-xray-settings-editor-override-default")
            idea-radio    (find-by-testid rendered "rf-xray-settings-editor-override-idea")]
        (is (false? (:checked (second default-radio))))
        (is (true? (:checked (second idea-radio)))
            "the :idea radio is the checked option after the override
             writes")))))

;; The popup surfaces no `:use-system-colors?` HCM-override checkbox.
;; The settings slot + the `apply-use-system-colors!` effect exist so a
;; UI can expose it; the OS-level `@media (forced-colors: active)`
;; detection works automatically. The slot's behaviour is exercised by
;; the settings effects tests.

(deftest use-system-colors-checkbox-absent-from-general
  (testing "the `:use-system-colors?` checkbox is not surfaced in the
            popup. The slot + the `apply-use-system-colors!` effect
            exist without a UI."
    (setup!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/settings-open])
      (rf/dispatch-sync [:rf.xray/settings-select-tab :general]))
    (rf/with-frame :rf/xray
      (let [rendered (modal-trees/settings-popup-tree)]
        (is (nil? (find-by-testid rendered "rf-xray-settings-use-system-colors"))
            "Use system colors toggle does not render")
        (is (nil? (find-by-testid rendered "rf-xray-settings-section-theme"))
            "There is no Theme section either")))))

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
      (let [rendered (modal-trees/settings-popup-tree)]
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

;; ---- Modal positioning --------------------------------------------------

(deftest backdrop-defaults-to-fixed-positioning
  (testing "with no :rf.xray/modal-positioning slot set, backdrop
            renders position: fixed at the production z-index"
    (setup!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/settings-open]))
    (rf/with-frame :rf/xray
      (let [rendered (modal-trees/settings-popup-tree)
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
      (let [rendered (modal-trees/settings-popup-tree)
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

