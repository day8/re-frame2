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
            [day8.re-frame2-xray.settings.view :as view]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

;; ---- fixture ------------------------------------------------------------

(defn- xray-init! []
  ;; rf2-sdqsla — `reset-runtime!` folds sentinel + trace-collector +
  ;; settings reset into one call.
  (xray-test-support/reset-runtime!))

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
    (let [rendered (popup/Modal)]
      (is (find-by-testid rendered "rf-xray-settings-section-general"))
      (is (find-by-testid rendered "rf-xray-settings-panel-position-right-rail"))
      (is (find-by-testid rendered "rf-xray-settings-auto-open-on-error"))
      ;; UX cleanup 2026-05-27: the text-size slider was removed —
      ;; defaults suffice. The `:general :text-size` slot + the
      ;; `apply-text-size!` effect remain so any host-set default still
      ;; lands; only the UI surface went away.
      (is (nil? (find-by-testid rendered "rf-xray-settings-text-size-input"))
          "Text-size slider removed from the popup (defaults suffice)")
      ;; rf2-pu9sb originally moved the Epoch history slider from
      ;; General to Buffer. That move was reverted 2026-05-27 per Mike
      ;; — the slider is back in General, sitting next to the auto-
      ;; open-on-error checkbox. The slot stays `:general
      ;; :epoch-history`; the Buffer section no longer carries the
      ;; slider.
      (is (find-by-testid rendered "rf-xray-settings-epoch-history-input")
          "Epoch history slider renders in General (relocated back from Buffer)")
      (is (find-by-testid rendered "rf-xray-settings-epoch-history-value")
          "Epoch history numeric readout renders alongside the slider"))))

;; rf2-pu9sb moved the Epoch history slider from General to Buffer;
;; that move was reverted 2026-05-27 per Mike. The slot stays
;; `:general :epoch-history`; only the visual home moved. The
;; coverage now lives in `general-section-renders` above; the Buffer
;; section no longer carries the slider.

(deftest epoch-history-slider-absent-from-buffer-section
  (testing "Epoch history slider does NOT render in the Buffer section
            after the 2026-05-27 revert; it lives in General."
    (setup!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/settings-open])
      (rf/dispatch-sync [:rf.xray/settings-select-tab :buffer]))
    (rf/with-frame :rf/xray
      (let [rendered (popup/Modal)]
        (is (find-by-testid rendered "rf-xray-settings-section-buffer"))
        (is (nil? (find-by-testid rendered "rf-xray-settings-epoch-history-input"))
            "Epoch history slider is no longer in Buffer (relocated back to General)")
        (is (nil? (find-by-testid rendered "rf-xray-settings-epoch-history-value"))
            "Epoch history numeric readout is no longer in Buffer")
        ;; The dead `:buffer :retained-epochs` numeric input had no
        ;; substrate consumer and was removed in the rf2-pu9sb cleanup.
        (is (nil? (find-by-testid rendered "rf-xray-settings-buffer-retained-epochs"))
            "Dead `:buffer :retained-epochs` numeric input is gone")
        ;; rf2-5u03ig — the `:buffer :app-db/inspector-collapse-threshold`
        ;; numeric input had no runtime consumer (the inspector already
        ;; auto-collapses on depth/width) and was removed. The
        ;; `:cascades-retained` field stays — it now writes through to
        ;; `(rf/configure! :trace-buffer ...)`.
        (is (nil? (find-by-testid rendered "rf-xray-settings-buffer-inspector-collapse"))
            "Inert `:buffer :app-db/inspector-collapse-threshold` input is gone")
        (is (find-by-testid rendered "rf-xray-settings-buffer-cascades-retained")
            "Cascades-retained field stays (now wired to :trace-buffer)")))))

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

(deftest editor-override-picker-renders-in-general
  (testing "rf2-dudqz — General tab carries the editor-override picker
            (radio set + Reset button + host-default hint). One radio
            per enumerated editor plus '(project default)' and
            Custom; the custom URI-template input is gated on the
            Custom radio being active."
    (setup!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/settings-open])
      (rf/dispatch-sync [:rf.xray/settings-select-tab :general]))
    (rf/with-frame :rf/xray
      (let [rendered (popup/Modal)]
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
  (testing "rf2-dudqz — selecting Custom (writing `{:custom <tpl>}`
            to the slot) reveals the URI-template input on the next
            render"
    (setup!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/settings-open])
      (rf/dispatch-sync [:rf.xray/settings-select-tab :general])
      (rf/dispatch-sync [:rf.xray/settings-update
                         :general :editor-override {:custom ""}]))
    (rf/with-frame :rf/xray
      (let [rendered (popup/Modal)]
        (is (find-by-testid rendered "rf-xray-settings-editor-override-custom-input")
            "custom template input renders once the override is the
             :custom shape")))))

(deftest editor-override-custom-radio-seeds-working-template
  (testing "rf2-rc35g — the Custom radio's seed value (used when the
            user first selects Custom with no prior template) is a
            working vscode-style URI rather than `{:custom \"\"}`.
            Pre-fix, click-to-source silently no-op'd until the user
            finished typing the template; the seed makes the override
            resolve to a valid URI immediately so the user edits from
            a known baseline."
    (let [seed @#'view/custom-template-seed]
      (is (string? seed) "the seed is a string")
      (is (not= "" seed)
          "the seed is NOT the empty string (rf2-rc35g — empty
           templates break click-to-source)")
      (is (= "vscode://file/{path}:{line}:{column}" seed)
          "the seed echoes the framework-default :vscode URI shape so
           the chip resolves to a working URI immediately")
      (is (config/valid-editor-override? {:custom seed})
          "the seeded `{:custom <seed>}` shape passes the read-side
           validator (rf2-a1tv6)"))))

(deftest editor-override-default-radio-is-checked-on-fresh-install
  (testing "rf2-dudqz — with no override, the '(project default)'
            radio is the selected option"
    (setup!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/settings-open])
      (rf/dispatch-sync [:rf.xray/settings-select-tab :general]))
    (rf/with-frame :rf/xray
      (let [rendered (popup/Modal)
            default-radio (find-by-testid rendered "rf-xray-settings-editor-override-default")
            vscode-radio  (find-by-testid rendered "rf-xray-settings-editor-override-vscode")]
        (is (true? (:checked (second default-radio)))
            "(project default) radio is checked")
        (is (false? (:checked (second vscode-radio)))
            "VS Code radio is unchecked")))))

(deftest editor-override-enumerated-radio-reflects-active-override
  (testing "rf2-dudqz — writing an enumerated-keyword override
            (`:idea`) flips the checked radio to that option"
    (setup!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/settings-open])
      (rf/dispatch-sync [:rf.xray/settings-select-tab :general])
      (rf/dispatch-sync [:rf.xray/settings-update
                         :general :editor-override :idea]))
    (rf/with-frame :rf/xray
      (let [rendered (popup/Modal)
            default-radio (find-by-testid rendered "rf-xray-settings-editor-override-default")
            idea-radio    (find-by-testid rendered "rf-xray-settings-editor-override-idea")]
        (is (false? (:checked (second default-radio))))
        (is (true? (:checked (second idea-radio)))
            "the :idea radio is the checked option after the override
             writes")))))

;; rf2-ou3pn moved the `:use-system-colors?` HCM-override checkbox
;; from the retired Theme tab into General → Power user; the UI
;; surface was then removed entirely 2026-05-27 (UX cleanup pass).
;; The settings slot + the `apply-use-system-colors!` effect remain
;; so a future UI can re-expose if needed; the OS-level
;; `@media (forced-colors: active)` detection still works
;; automatically. No deftest covers the checkbox now — the slot's
;; behaviour is exercised by the substrate-level theme effects
;; tests; the cosmetic surface is the only thing that went away.

(deftest use-system-colors-checkbox-absent-from-general
  (testing "2026-05-27 UX cleanup — the `:use-system-colors?` checkbox
            is no longer surfaced in the popup. The slot + the
            `apply-use-system-colors!` effect remain; only the UI went."
    (setup!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/settings-open])
      (rf/dispatch-sync [:rf.xray/settings-select-tab :general]))
    (rf/with-frame :rf/xray
      (let [rendered (popup/Modal)]
        (is (nil? (find-by-testid rendered "rf-xray-settings-use-system-colors"))
            "Use system colors toggle no longer renders")
        (is (nil? (find-by-testid rendered "rf-xray-settings-section-theme"))
            "Theme section is also gone (retired by rf2-ou3pn earlier)")))))

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

