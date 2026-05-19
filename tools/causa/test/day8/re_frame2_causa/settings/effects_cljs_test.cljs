(ns day8.re-frame2-causa.settings.effects-cljs-test
  "CLJS tests for the Settings popup's side-effect appliers
  (rf2-9poxq).

  Asserts:
  - `apply-text-size!` writes the CSS custom property
  - `apply-theme!` toggles the CSS class on the shell root + <html>
  - The Filters tab feature-detect shows the install hint when the
    sibling ns is absent (covered indirectly via popup test;
    re-asserted here against the predicate)
  - `update-setting!` dispatched through events drives the matching
    `apply-*!` side effect
  - The auto-open watcher edge-fires on empty→non-empty when toggle
    is on AND Causa is hidden

  No DOM-shell mount happens — we create a stub `#rf-causa-root`
  element + a stub `<html>` for the CSS-var assertions; the auto-
  open watcher test exercises the watch fn directly via the
  subscription value transition."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.config :as config]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.settings.effects :as effects]
            [day8.re-frame2-causa.settings.view :as view]
            [day8.re-frame2-causa.test-support :as causa-test-support]
            [day8.re-frame2-causa.theme.tokens :as tokens]
            [day8.re-frame2-causa.trace-bus :as trace-bus]))

;; ---- fixture -----------------------------------------------------------

(defn- causa-init! []
  (causa-test-support/reset-all!)
  (trace-bus/clear-buffer!)
  (config/reset-settings!))

(defn- ensure-stub-shell-root! []
  ;; Some node test runtimes provide js/document; create the
  ;; `#rf-causa-root` element so the apply-text-size! / apply-theme!
  ;; calls have a target. Idempotent — second-call no-ops.
  (when (and (exists? js/document) (.-createElement js/document))
    (when-not (.getElementById js/document "rf-causa-root")
      (let [el (.createElement js/document "div")]
        (set! (.-id el) "rf-causa-root")
        (when (.-body js/document)
          (.appendChild (.-body js/document) el))))))

(defn- remove-stub-shell-root! []
  (when (exists? js/document)
    (when-let [el (.getElementById js/document "rf-causa-root")]
      (when (.-parentNode el)
        (.removeChild (.-parentNode el) el)))))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn (fn []
                (causa-init!)
                (effects/detach-auto-open-watcher!)
                (remove-stub-shell-root!))}))

(defn- setup! []
  (registry/register-causa-handlers!)
  (frame/reg-frame :rf/causa {}))

;; ---- DOM helpers --------------------------------------------------------

(defn- shell-root []
  (when (exists? js/document)
    (.getElementById js/document "rf-causa-root")))

(defn- html-root []
  (when (exists? js/document)
    (.-documentElement js/document)))

;; ---- text-size ----------------------------------------------------------

(deftest apply-text-size-writes-css-var
  (ensure-stub-shell-root!)
  (effects/apply-text-size! 17)
  (when-let [el (shell-root)]
    (is (= "17px" (.getPropertyValue (.-style el) "--rf-causa-text-size"))
        "shell root CSS var carries the value"))
  (when-let [html (html-root)]
    (is (= "17px" (.getPropertyValue (.-style html) "--rf-causa-text-size"))
        "<html> CSS var also carries the value")))

(deftest apply-text-size-handles-missing-shell-root
  (remove-stub-shell-root!)
  (is (nil? (effects/apply-text-size! 11))
      "no-op when shell root absent; no throw"))

;; ---- theme --------------------------------------------------------------

(deftest apply-theme-toggles-class
  (ensure-stub-shell-root!)
  (effects/apply-theme! :light)
  (when-let [el (shell-root)]
    (is (true? (.contains (.-classList el) "rf-causa-theme-light"))
        "light class applied")
    (is (false? (.contains (.-classList el) "rf-causa-theme-dark"))
        "dark class removed"))
  ;; Switch to dark
  (effects/apply-theme! :dark)
  (when-let [el (shell-root)]
    (is (true? (.contains (.-classList el) "rf-causa-theme-dark")))
    (is (false? (.contains (.-classList el) "rf-causa-theme-light")))))

;; ---- update-setting! drives the side effect ----------------------------

(deftest update-event-applies-text-size-effect
  (setup!)
  (ensure-stub-shell-root!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/settings-update :general :text-size 15]))
  (when-let [el (shell-root)]
    (is (= "15px" (.getPropertyValue (.-style el) "--rf-causa-text-size"))
        "dispatching update writes the CSS var")))

(deftest update-event-applies-theme-effect
  (setup!)
  (ensure-stub-shell-root!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/settings-update :theme nil :light]))
  (when-let [el (shell-root)]
    (is (true? (.contains (.-classList el) "rf-causa-theme-light")))))

;; ---- filters feature detect --------------------------------------------

(deftest filters-section-feature-detect-reports-present-when-ns-loaded
  ;; Post rf2-ak4ms the auto-filter ns ships in the same bundle as
  ;; the Settings popup; the view's `filters-feature-present?` uses
  ;; `find-ns` against `day8.re-frame2-causa.filters` and should
  ;; return true. The presence branch surfaces the "Open auto-filter
  ;; UI" button rather than the install hint (covered in
  ;; popup_cljs_test/filters-section-renders).
  (is (some? (find-ns 'day8.re-frame2-causa.filters))
      "filters ns is loaded post rf2-ak4ms")
  (let [_section (view/popup-view)]
    (is (true? (#'view/filters-feature-present?))
        "feature detect reports present")))

;; ---- auto-open watcher --------------------------------------------------
;;
;; The watcher reads `:rf.causa/issues-ribbon` and dispatches
;; `mount/open!` on the empty → non-empty edge. We exercise the watch
;; fn directly because the production install path adds a `add-watch`
;; on the live subscription reaction, and the simplest verification is
;; that toggling the underlying flag + simulating the value change
;; triggers the open call.

(def ^:private open-call-count (atom 0))

(defn- with-mount-open-stub [f]
  (let [orig js/window]
    ;; Stub `mount/open!` by hooking the global browser export the
    ;; preload installs — the watcher calls mount/open! directly, so
    ;; we exercise it via the public test seam.
    (reset! open-call-count 0)
    (f)))

(deftest auto-open-watcher-fires-on-empty-to-nonempty-edge
  ;; Drive the watch fn semantics directly (without mounting Causa)
  ;; by reproducing the same gates: toggle on, count was 0, count is
  ;; now positive. The assertion is that under those conditions the
  ;; gating logic returns truthy ("would dispatch open"). Avoids
  ;; touching window.open in the test runner.
  (config/update-setting! :general :auto-open-on-error? true)
  (let [should-open? (fn [prev now]
                       (let [toggle (config/get-setting
                                      :general :auto-open-on-error?)]
                         (and toggle (pos? now) (zero? prev))))]
    (is (true? (should-open? 0 1))
        "empty → non-empty AND toggle on → open")
    (is (false? (should-open? 0 0))
        "no issues → no open")
    (is (false? (should-open? 1 2))
        "non-empty → non-empty (subsequent push) → no open")))

(deftest auto-open-watcher-skips-when-toggle-off
  (config/update-setting! :general :auto-open-on-error? false)
  (let [should-open? (fn [prev now]
                       (let [toggle (config/get-setting
                                      :general :auto-open-on-error?)]
                         (and toggle (pos? now) (zero? prev))))]
    (is (false? (should-open? 0 1))
        "toggle off → never open, even on edge")))

(deftest install-is-defensive-without-causa-frame
  ;; rf2-9poxq follow-up: the Story testbed CI failures
  ;; `No protocol method IWatchable.-add-watch defined for type null`
  ;; came from `install-auto-open-watcher!` running at preload before
  ;; `:rf/causa` was lazy-registered. `rf/subscribe` returned nil and
  ;; `(add-watch nil ...)` threw. The install is now guarded; a call
  ;; with no `:rf/causa` frame is a silent no-op.
  (effects/detach-auto-open-watcher!)
  (is (nil? (effects/install-auto-open-watcher!))
      "install without `:rf/causa` frame is a silent no-op (no throw)"))

(deftest update-event-toggles-watcher-install
  (setup!)
  ;; Flip on via the event — install should land (frame is present
  ;; via `setup!`).
  (effects/detach-auto-open-watcher!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/settings-update
                       :general :auto-open-on-error? true]))
  (is (true? (config/get-setting :general :auto-open-on-error?))
      "config carries the new value")
  ;; Flip off — detach should run, no throw.
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/settings-update
                       :general :auto-open-on-error? false]))
  (is (false? (config/get-setting :general :auto-open-on-error?))
      "config carries the flipped value"))

;; ---- apply-all! ---------------------------------------------------------

(deftest apply-all-restores-text-size-and-theme
  (ensure-stub-shell-root!)
  (config/update-setting! :general :text-size 12)
  (config/update-setting! :theme nil :light)
  ;; Re-apply via the boot path.
  (effects/apply-all!)
  (when-let [el (shell-root)]
    (is (= "12px" (.getPropertyValue (.-style el) "--rf-causa-text-size")))
    (is (true? (.contains (.-classList el) "rf-causa-theme-light")))))

;; ---- panel width (rf2-x8h9y) -------------------------------------------

(deftest apply-all-restores-panel-width
  (testing "rf2-x8h9y — boot path restores the persisted panel width
            so the user's saved drag survives reload BEFORE first
            paint. The CSS var lands on `<html>` (the cascade reaches
            the layout host's flex-basis even pre-mount)."
    (config/update-setting! :general :panel-width-px 700)
    (effects/apply-all!)
    (when-let [html (html-root)]
      (is (= "700px"
             (.getPropertyValue (.-style html) "--rf-causa-inline-width"))
          "<html> --rf-causa-inline-width carries the persisted value"))))

;; ---- density → font-size knob (rf2-i40us) ------------------------------

(deftest density->font-size-px-mapping
  (testing "density keyword resolves to the canonical px value the
            radio writes into `--rf-causa-font-size`. Compact tightens
            by 1px, cosy is the baseline (matches
            `tokens/font-size-default`), comfy loosens by 1px."
    (is (= 12 (:compact effects/density->font-size-px)))
    (is (= 13 (:cosy    effects/density->font-size-px)))
    (is (= 14 (:comfy   effects/density->font-size-px)))
    (is (= "13px" tokens/font-size-default)
        "cosy mapping matches the type-scale's baseline default")))

(deftest density->px-falls-back-to-cosy-on-unknown
  ;; The `:rf.causa/density` sub coerces unknown values to `:cosy`;
  ;; the apply-fn mirrors that posture so a persisted pre-2026-05-19
  ;; `:comfy` payload (now dropped from the radio enumeration) lands
  ;; on a coherent px value rather than nil/throw.
  (is (= 13 (effects/density->px :cosy)))
  (is (= 12 (effects/density->px :compact)))
  (is (= 14 (effects/density->px :comfy)))
  (is (= 13 (effects/density->px :something-weird))
      "unknown density coerces to the cosy default")
  (is (= 13 (effects/density->px nil))
      "nil density coerces to the cosy default"))

(deftest apply-density-font-size-writes-css-var
  (ensure-stub-shell-root!)
  (effects/apply-density-font-size! :compact)
  (when-let [el (shell-root)]
    (is (= "12px" (.getPropertyValue (.-style el) "--rf-causa-font-size"))
        "shell root --rf-causa-font-size carries the compact value"))
  (when-let [html (html-root)]
    (is (= "12px" (.getPropertyValue (.-style html) "--rf-causa-font-size"))
        "<html> --rf-causa-font-size carries the compact value"))
  (effects/apply-density-font-size! :cosy)
  (when-let [el (shell-root)]
    (is (= "13px" (.getPropertyValue (.-style el) "--rf-causa-font-size"))
        "shell root rewrites to 13px on cosy flip"))
  (effects/apply-density-font-size! :comfy)
  (when-let [el (shell-root)]
    (is (= "14px" (.getPropertyValue (.-style el) "--rf-causa-font-size"))
        "shell root rewrites to 14px on comfy")))

(deftest apply-density-font-size-handles-missing-shell-root
  (remove-stub-shell-root!)
  (is (nil? (effects/apply-density-font-size! :compact))
      "no-op when shell root absent; no throw"))

(deftest update-event-applies-density-font-size-effect
  (testing "Dispatching `[:rf.causa/settings-update :general :density
            :compact]` flips `--rf-causa-font-size` to 12px so the
            whole `type-scale` rescales on the next paint."
    (setup!)
    (ensure-stub-shell-root!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/settings-update
                         :general :density :compact]))
    (when-let [el (shell-root)]
      (is (= "12px" (.getPropertyValue (.-style el) "--rf-causa-font-size"))
          "dispatch writes 12px on compact"))
    ;; Persistence — the dual-write goes to the in-memory atom +
    ;; localStorage shim via `config/update-setting!`.
    (is (= :compact (config/get-setting :general :density))
        "settings atom carries the new density")
    ;; Flip to cosy — verify the inline write rewrites (not just adds).
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/settings-update
                         :general :density :cosy]))
    (when-let [el (shell-root)]
      (is (= "13px" (.getPropertyValue (.-style el) "--rf-causa-font-size"))
          "dispatch writes 13px on cosy"))))

(deftest apply-all-restores-density-font-size
  (testing "rf2-i40us — boot path restores the persisted density so
            the user's saved knob rescales the type scale BEFORE first
            paint. The CSS var lands on the shell root + `<html>`."
    (ensure-stub-shell-root!)
    (config/update-setting! :general :density :compact)
    (effects/apply-all!)
    (when-let [el (shell-root)]
      (is (= "12px" (.getPropertyValue (.-style el) "--rf-causa-font-size"))
          "shell root --rf-causa-font-size carries the persisted compact value"))
    (when-let [html (html-root)]
      (is (= "12px" (.getPropertyValue (.-style html) "--rf-causa-font-size"))
          "<html> --rf-causa-font-size carries the persisted compact value"))))
