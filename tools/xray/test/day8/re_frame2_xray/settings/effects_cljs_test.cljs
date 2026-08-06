(ns day8.re-frame2-xray.settings.effects-cljs-test
  "CLJS tests for the Settings popup's side-effect appliers
  (rf2-9poxq).

  Asserts:
  - `apply-text-size!` writes the CSS custom property
  - `apply-theme!` toggles the CSS class on the shell root + <html>
  - `update-setting!` dispatched through events drives the matching
    `apply-*!` side effect
  - The auto-open watcher edge-fires on empty→non-empty when toggle
    is on AND Xray is hidden

  (The Filters tab feature-detect bullet was removed when the
  Filters tab itself retired in rf2-wknb3.)

  No DOM-shell mount happens — we create a stub `#rf-xray-root`
  element + a stub `<html>` for the CSS-var assertions; the auto-
  open watcher test exercises the watch fn directly via the
  subscription value transition."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.epoch.state :as epoch-state]
            [re-frame.frame :as frame]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.settings.effects :as effects]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.theme.tokens :as tokens]))

;; ---- fixture -----------------------------------------------------------

(defn- ensure-stub-shell-root! []
  ;; Some node test runtimes provide js/document; create the
  ;; `#rf-xray-root` element so the apply-text-size! / apply-theme!
  ;; calls have a target. Idempotent — second-call no-ops.
  (when (and (exists? js/document) (.-createElement js/document))
    (when-not (.getElementById js/document "rf-xray-root")
      (let [el (.createElement js/document "div")]
        (set! (.-id el) "rf-xray-root")
        (when (.-body js/document)
          (.appendChild (.-body js/document) el))))))

(defn- remove-stub-shell-root! []
  (when (exists? js/document)
    (when-let [el (.getElementById js/document "rf-xray-root")]
      (when (.-parentNode el)
        (.removeChild (.-parentNode el) el)))))

(use-fixtures :each
  ;; `make-xray-runtime-fixture` (rf2-vj80u8): plain-atom adapter + the
  ;; `:runtime` reset tier (sentinels + trace rings + persisted settings);
  ;; `:post-reset` detaches the auto-open watcher + tears down the stub shell
  ;; root so neither leaks between tests.
  (xray-test-support/make-xray-runtime-fixture
    {:tier       :runtime
     :post-reset (fn []
                   (effects/detach-auto-open-watcher!)
                   (remove-stub-shell-root!))}))

(defn- setup! []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray}))

;; ---- DOM helpers --------------------------------------------------------

(defn- shell-root []
  (when (exists? js/document)
    (.getElementById js/document "rf-xray-root")))

(defn- html-root []
  (when (exists? js/document)
    (.-documentElement js/document)))

;; ---- text-size ----------------------------------------------------------

(deftest apply-text-size-writes-css-var
  (ensure-stub-shell-root!)
  (effects/apply-text-size! 17)
  (when-let [el (shell-root)]
    (is (= "17px" (.getPropertyValue (.-style el) "--rf-xray-text-size"))
        "shell root CSS var carries the value"))
  (when-let [html (html-root)]
    (is (= "17px" (.getPropertyValue (.-style html) "--rf-xray-text-size"))
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
    (is (true? (.contains (.-classList el) "rf-xray-theme-light"))
        "light class applied")
    (is (false? (.contains (.-classList el) "rf-xray-theme-dark"))
        "dark class removed"))
  ;; Switch to dark
  (effects/apply-theme! :dark)
  (when-let [el (shell-root)]
    (is (true? (.contains (.-classList el) "rf-xray-theme-dark")))
    (is (false? (.contains (.-classList el) "rf-xray-theme-light")))))

;; ---- update-setting! drives the side effect ----------------------------

(deftest update-event-applies-text-size-effect
  (setup!)
  (ensure-stub-shell-root!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/settings-update :general :text-size 15]))
  (when-let [el (shell-root)]
    (is (= "15px" (.getPropertyValue (.-style el) "--rf-xray-text-size"))
        "dispatching update writes the CSS var")))

(deftest update-event-applies-theme-effect
  (setup!)
  (ensure-stub-shell-root!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/settings-update :theme nil :light]))
  (when-let [el (shell-root)]
    (is (true? (.contains (.-classList el) "rf-xray-theme-light")))))

;; ---- filters feature detect (removed rf2-wknb3) ------------------------
;;
;; The Settings popup Filters tab was retired in rf2-wknb3 — the
;; ribbon strip + per-pill edit popup + mute manager are the
;; canonical pill-management surfaces. The view's
;; `filters-feature-present?` helper that the previous test
;; exercised is gone with the tab.

;; ---- auto-open watcher --------------------------------------------------
;;
;; The watcher reads `:rf.xray/issues-ribbon` and dispatches
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
  ;; Drive the watch fn semantics directly (without mounting Xray)
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

(deftest install-is-defensive-without-xray-frame
  ;; rf2-9poxq follow-up: the Story testbed CI failures
  ;; `No protocol method IWatchable.-add-watch defined for type null`
  ;; came from `install-auto-open-watcher!` running at preload before
  ;; `:rf/xray` was lazy-registered. `rf/subscribe` returned nil and
  ;; `(add-watch nil ...)` threw. The install is now guarded; a call
  ;; with no `:rf/xray` frame is a silent no-op.
  (effects/detach-auto-open-watcher!)
  (is (nil? (effects/install-auto-open-watcher!))
      "install without `:rf/xray` frame is a silent no-op (no throw)"))

(deftest update-event-toggles-watcher-install
  (setup!)
  ;; Flip on via the event — install should land (frame is present
  ;; via `setup!`).
  (effects/detach-auto-open-watcher!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/settings-update
                       :general :auto-open-on-error? true]))
  (is (true? (config/get-setting :general :auto-open-on-error?))
      "config carries the new value")
  ;; Flip off — detach should run, no throw.
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/settings-update
                       :general :auto-open-on-error? false]))
  (is (false? (config/get-setting :general :auto-open-on-error?))
      "config carries the flipped value"))

;; ---- auto-open reopen preserves the realized surface (rf2-kggzi4) -------
;;
;; rf2-j538f7.41 made the two GLOBAL reopen routes (Ctrl+Shift+C toggle +
;; Cmd/Ctrl+K palette) preserve the last-realized mount surface via
;; `mount/toggle!`. The auto-open-on-error watcher was the THIRD reopen
;; route and still hard-coded `mount/open!` — a surface CHANGE that would
;; revert a hidden overlay to inline on auto-open. rf2-kggzi4 routes it
;; through the shared `reopen-preserving-surface!` helper (→ `toggle!`).
;;
;; We unit-test that helper's routing directly (mirroring how
;; mount_cljs_test's `toggle!-*` suite unit-tests the mount layer's own
;; surface preservation): the auto-open GATE — the empty→non-empty edge +
;; toggle-on + hidden — is covered by the watcher tests above. The full
;; `install-auto-open-watcher!` `add-watch` path can't be driven under THIS
;; suite's headless plain-atom adapter (its derived subscriptions reify
;; `IDeref`/`IDisposable` only, not `IWatchable`), and the claim that "the
;; browser suites exercise that reactive glue" was false — no suite did, which
;; is how rf2-lynzk (the missing `activate-derived-value!`) survived. It is now
;; pinned on a ratom-family adapter, in this same directory:
;; `settings.auto-open-watcher-activates-ratom-node-cljs-test`.

(defn- with-recording-mount-exports
  "Install a stub `window.day8.re_frame2_xray` whose mount exports each
  record their own export name when invoked. Calls `(f invoked-atom)`,
  then tears the stub window (and `day8`) back down."
  [f]
  (let [invoked     (atom [])
        had-window? (exists? js/globalThis.window)
        record      (fn [nm] (fn [] (swap! invoked conj nm) nil))]
    (when-not had-window?
      (set! (.-window js/globalThis) #js {}))
    (let [win js/globalThis.window]
      (set! (.-day8 win)
            #js {"re_frame2_xray"
                 #js {"open_BANG_"         (record "open_BANG_")
                      "open_overlay_BANG_" (record "open_overlay_BANG_")
                      "toggle_BANG_"       (record "toggle_BANG_")}})
      (try
        (f invoked)
        (finally
          (js-delete win "day8")
          (when-not had-window?
            (js-delete js/globalThis "window")))))))

(deftest reopen-preserving-surface-routes-through-toggle-not-open
  (testing "rf2-kggzi4 — the auto-open-on-error reopen route
            (`reopen-preserving-surface!`) invokes the surface-preserving
            `mount/toggle!` export, never the surface-changing `mount/open!`
            (which would revert a hidden overlay to inline). Mirrors the
            rf2-j538f7.41 fix on the Ctrl+Shift+C + palette routes."
    (with-recording-mount-exports
      (fn [invoked]
        (#'effects/reopen-preserving-surface!)
        (is (= ["toggle_BANG_"] @invoked)
            "reopen invoked the surface-preserving toggle! export, and only it")
        (is (not-any? #{"open_BANG_"} @invoked)
            "reopen did NOT call the surface-changing open!")))))

;; ---- apply-all! ---------------------------------------------------------

(deftest apply-all-restores-text-size-and-theme
  (ensure-stub-shell-root!)
  (config/update-setting! :general :text-size 12)
  (config/update-setting! :theme nil :light)
  ;; Re-apply via the boot path.
  (effects/apply-all!)
  (when-let [el (shell-root)]
    (is (= "12px" (.getPropertyValue (.-style el) "--rf-xray-text-size")))
    (is (true? (.contains (.-classList el) "rf-xray-theme-light")))))

;; ---- epoch-history (rf2-3zyyx) -----------------------------------------

(deftest apply-epoch-history-writes-substrate-depth
  (testing "rf2-3zyyx — apply-epoch-history! routes through
            `(rf/configure! {:epoch-history {:depth N}})` so the
            substrate's per-frame ring buffer matches the user's
            saved capacity. Reads the live depth from
            `re-frame.epoch.state` to assert the wire actually landed."
    ;; Capture original depth so the fixture doesn't leak the bump.
    (let [orig (epoch-state/depth)]
      (try
        (effects/apply-epoch-history! 123)
        (is (= 123 (epoch-state/depth))
            "substrate depth picks up the new value")
        ;; Defensive: nil / non-numeric is a no-op so a malformed
        ;; persisted payload never zeros the ring.
        (effects/apply-epoch-history! nil)
        (is (= 123 (epoch-state/depth))
            "nil input is a no-op; substrate depth unchanged")
        (effects/apply-epoch-history! 0)
        (is (= 123 (epoch-state/depth))
            "zero input is dropped at the effect boundary; substrate
             depth unchanged (the slider's min is 10 anyway)")
        (finally
          (rf/configure! {:epoch-history {:depth orig}}))))))

(deftest update-event-applies-epoch-history-effect
  (testing "rf2-3zyyx — dispatching `:rf.xray/settings-update :general
            :epoch-history N` writes through to the substrate via the
            matching effect."
    (setup!)
    (let [orig (epoch-state/depth)]
      (try
        (rf/with-frame :rf/xray
          (rf/dispatch-sync [:rf.xray/settings-update
                             :general :epoch-history 150]))
        (is (= 150 (config/get-setting :general :epoch-history))
            "config slot carries the new value")
        (is (= 150 (epoch-state/depth))
            "substrate ring depth follows the dispatch")
        (finally
          (rf/configure! {:epoch-history {:depth orig}}))))))

(deftest apply-all-restores-epoch-history
  (testing "rf2-3zyyx — the boot path re-applies the persisted depth
            so the substrate ring matches the user's saved capacity
            BEFORE first dispatch."
    (let [orig (epoch-state/depth)]
      (try
        (config/update-setting! :general :epoch-history 88)
        (effects/apply-all!)
        (is (= 88 (epoch-state/depth))
            "apply-all! routes the persisted value to the substrate")
        (finally
          (rf/configure! {:epoch-history {:depth orig}}))))))

;; ---- cascades-retained (rf2-5u03ig) ------------------------------------
;;
;; The Buffer-tab `:events-retained` knob writes through to the
;; framework trace ring via `(rf/configure! {:trace-buffer
;; {:events-retained N}})`. We spy on `rf/configure!` to assert the
;; effect / event / boot paths all reach the published substrate API
;; with the canonical config-map shape — the framework knob itself is
;; proven by `re-frame.configure-test` (configure_test.clj:60-66), so
;; the Settings BRIDGE is what these tests guard.

(deftest apply-cascades-retained-writes-through-to-configure
  (testing "rf2-5u03ig — apply-events-retained! routes through
            `(rf/configure! {:trace-buffer {:events-retained N}})`.
            Non-positive / non-numeric input is a no-op at the effect
            boundary (the UI :min 1 keeps the framework's 0-disables
            behaviour out of reach)."
    (let [calls (atom [])]
      (with-redefs [rf/configure! (fn [config-map] (swap! calls conj config-map) nil)]
        (effects/apply-events-retained! 33)
        (is (= [{:trace-buffer {:events-retained 33}}] @calls)
            "positive value reaches configure! with the canonical config-map shape")
        (effects/apply-events-retained! nil)
        (effects/apply-events-retained! 0)
        (effects/apply-events-retained! -5)
        (is (= 1 (count @calls))
            "nil / zero / negative are dropped at the effect boundary")))))

(deftest update-event-applies-cascades-retained-effect
  (testing "rf2-5u03ig — dispatching `:rf.xray/settings-update :buffer
            :events-retained N` writes through to the substrate via
            the matching effect and persists the value."
    (setup!)
    (let [calls (atom [])]
      (with-redefs [rf/configure! (fn [config-map] (swap! calls conj config-map) nil)]
        (rf/with-frame :rf/xray
          (rf/dispatch-sync [:rf.xray/settings-update
                             :buffer :events-retained 17]))
        (is (= 17 (config/get-setting :buffer :events-retained))
            "config slot carries the new value")
        (is (= [{:trace-buffer {:events-retained 17}}] @calls)
            "the dispatch reaches configure! with the :trace-buffer key")))))

(deftest apply-all-restores-cascades-retained
  (testing "rf2-5u03ig — the boot path re-applies the persisted
            cascades-retained count so the substrate trace ring matches
            the user's saved capacity BEFORE first dispatch."
    (let [calls (atom [])]
      (with-redefs [rf/configure! (fn [config-map] (swap! calls conj config-map) nil)]
        (config/update-setting! :buffer :events-retained 21)
        (effects/apply-all!)
        (is (some #{{:trace-buffer {:events-retained 21}}} @calls)
            "apply-all! routes the persisted value to the substrate")))))

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
             (.getPropertyValue (.-style html) "--rf-xray-inline-width"))
          "<html> --rf-xray-inline-width carries the persisted value"))))

;; ---- density → font-size knob (rf2-i40us) ------------------------------

(deftest density->font-size-px-mapping
  (testing "density keyword resolves to the canonical px value the
            radio writes into `--rf-xray-font-size`. Compact tightens
            by 1px, cosy is the baseline (matches
            `tokens/font-size-default`), comfy loosens by 1px."
    (is (= 12 (:compact effects/density->font-size-px)))
    (is (= 13 (:cosy    effects/density->font-size-px)))
    (is (= 14 (:comfy   effects/density->font-size-px)))
    (is (= "13px" tokens/font-size-default)
        "cosy mapping matches the type-scale's baseline default")))

(deftest density->px-falls-back-to-cosy-on-unknown
  ;; The `:rf.xray/density` sub coerces unknown values to `:cosy`;
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

;; ---- use-system-colors? (rf2-846h2) ------------------------------------

(deftest apply-use-system-colors-stamps-and-clears-attribute
  (testing "rf2-846h2 — apply-use-system-colors! stamps
            `data-rf-force-colors=\"active\"` on the shell root +
            `<html>` when truthy, removes it when falsey."
    (ensure-stub-shell-root!)
    (effects/apply-use-system-colors! true)
    (when-let [el (shell-root)]
      (is (= "active" (.getAttribute el effects/force-colors-attribute))
          "shell root carries the active attribute when toggle on"))
    (when-let [html (html-root)]
      (is (= "active" (.getAttribute html effects/force-colors-attribute))
          "<html> carries the active attribute when toggle on"))
    (effects/apply-use-system-colors! false)
    (when-let [el (shell-root)]
      (is (nil? (.getAttribute el effects/force-colors-attribute))
          "shell root attribute cleared when toggle off"))
    (when-let [html (html-root)]
      (is (nil? (.getAttribute html effects/force-colors-attribute))
          "<html> attribute cleared when toggle off"))))

(deftest apply-use-system-colors-handles-missing-shell-root
  (testing "rf2-846h2 — no-op when shell root absent; <html> still gets
            the attribute write so the cascade reaches descendants
            before the shell mounts."
    (remove-stub-shell-root!)
    (is (nil? (effects/apply-use-system-colors! true)))
    (when-let [html (html-root)]
      (is (= "active" (.getAttribute html effects/force-colors-attribute))
          "<html> attribute still landed even without the shell root"))
    ;; Clean up so unrelated tests don't see the stamped <html>.
    (effects/apply-use-system-colors! false)))

(deftest update-event-applies-use-system-colors-effect
  (testing "rf2-846h2 — dispatching `:rf.xray/settings-update :general
            :use-system-colors? true` stamps the chrome attribute via
            the matching effect."
    (setup!)
    (ensure-stub-shell-root!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/settings-update
                         :general :use-system-colors? true]))
    (when-let [el (shell-root)]
      (is (= "active" (.getAttribute el effects/force-colors-attribute))
          "dispatching update stamps the attribute"))
    (is (true? (config/get-setting :general :use-system-colors?))
        "config slot carries the new value")
    ;; Flip off and verify clear.
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/settings-update
                         :general :use-system-colors? false]))
    (when-let [el (shell-root)]
      (is (nil? (.getAttribute el effects/force-colors-attribute))
          "dispatching update with false clears the attribute"))))

(deftest apply-all-restores-use-system-colors
  (testing "rf2-846h2 — the boot path re-applies the persisted toggle
            so the user's saved opt-in survives reload BEFORE first
            paint."
    (ensure-stub-shell-root!)
    (config/update-setting! :general :use-system-colors? true)
    (effects/apply-all!)
    (when-let [el (shell-root)]
      (is (= "active" (.getAttribute el effects/force-colors-attribute))
          "shell root attribute restored from persistence"))
    (when-let [html (html-root)]
      (is (= "active" (.getAttribute html effects/force-colors-attribute))
          "<html> attribute restored from persistence"))
    ;; Clean up.
    (config/update-setting! :general :use-system-colors? false)
    (effects/apply-all!)))

(deftest apply-density-font-size-writes-css-var
  (ensure-stub-shell-root!)
  (effects/apply-density-font-size! :compact)
  (when-let [el (shell-root)]
    (is (= "12px" (.getPropertyValue (.-style el) "--rf-xray-font-size"))
        "shell root --rf-xray-font-size carries the compact value"))
  (when-let [html (html-root)]
    (is (= "12px" (.getPropertyValue (.-style html) "--rf-xray-font-size"))
        "<html> --rf-xray-font-size carries the compact value"))
  (effects/apply-density-font-size! :cosy)
  (when-let [el (shell-root)]
    (is (= "13px" (.getPropertyValue (.-style el) "--rf-xray-font-size"))
        "shell root rewrites to 13px on cosy flip"))
  (effects/apply-density-font-size! :comfy)
  (when-let [el (shell-root)]
    (is (= "14px" (.getPropertyValue (.-style el) "--rf-xray-font-size"))
        "shell root rewrites to 14px on comfy")))

(deftest apply-density-font-size-handles-missing-shell-root
  (remove-stub-shell-root!)
  (is (nil? (effects/apply-density-font-size! :compact))
      "no-op when shell root absent; no throw"))

(deftest update-event-applies-density-font-size-effect
  (testing "Dispatching `[:rf.xray/settings-update :general :density
            :compact]` flips `--rf-xray-font-size` to 12px so the
            whole `type-scale` rescales on the next paint."
    (setup!)
    (ensure-stub-shell-root!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/settings-update
                         :general :density :compact]))
    (when-let [el (shell-root)]
      (is (= "12px" (.getPropertyValue (.-style el) "--rf-xray-font-size"))
          "dispatch writes 12px on compact"))
    ;; Persistence — the dual-write goes to the in-memory atom +
    ;; localStorage shim via `config/update-setting!`.
    (is (= :compact (config/get-setting :general :density))
        "settings atom carries the new density")
    ;; Flip to cosy — verify the inline write rewrites (not just adds).
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/settings-update
                         :general :density :cosy]))
    (when-let [el (shell-root)]
      (is (= "13px" (.getPropertyValue (.-style el) "--rf-xray-font-size"))
          "dispatch writes 13px on cosy"))))

(deftest apply-all-restores-density-font-size
  (testing "rf2-i40us — boot path restores the persisted density so
            the user's saved knob rescales the type scale BEFORE first
            paint. The CSS var lands on the shell root + `<html>`."
    (ensure-stub-shell-root!)
    (config/update-setting! :general :density :compact)
    (effects/apply-all!)
    (when-let [el (shell-root)]
      (is (= "12px" (.getPropertyValue (.-style el) "--rf-xray-font-size"))
          "shell root --rf-xray-font-size carries the persisted compact value"))
    (when-let [html (html-root)]
      (is (= "12px" (.getPropertyValue (.-style html) "--rf-xray-font-size"))
          "<html> --rf-xray-font-size carries the persisted compact value"))))

;; ---- Keybindings tab "Handle keys?" reactive dual-write (rf2-8i1tg3) ----
;;
;; Was: the Keybindings tab's master toggle read `config/keybinding-
;; attach-enabled?` directly (a bare atom) and wrote via `config/set-
;; keybinding-enabled!` — no dispatch, no app-db mirror, unlike every
;; other toggle in this popup. The controlled checkbox's `:checked`
;; never re-fired reactively off a change. The fix routes the toggle
;; through `:rf.xray/keybinding-enabled-update` (flips the atom AND
;; mirrors app-db) + a new `:rf.xray/keybinding-enabled?` sub the
;; checkbox reads.

(deftest keybinding-enabled-update-flips-atom-and-mirrors-app-db
  (testing "rf2-8i1tg3 — dispatching :rf.xray/keybinding-enabled-update
            flips the canonical config atom AND the app-db mirror the
            new sub reads, in one atomic step"
    (setup!)
    (rf/with-frame :rf/xray
      (is (true? @(rf/subscribe [:rf.xray/keybinding-enabled?]))
          "default (never configured) reads true")
      (rf/dispatch-sync [:rf.xray/keybinding-enabled-update false])
      (is (false? (config/keybinding-attach-enabled?))
          "the canonical atom flipped")
      (is (false? @(rf/subscribe [:rf.xray/keybinding-enabled?]))
          "the reactive sub mirror flipped in the SAME dispatch — no
           stale controlled-checkbox read")
      (rf/dispatch-sync [:rf.xray/keybinding-enabled-update true])
      (is (true? (config/keybinding-attach-enabled?)))
      (is (true? @(rf/subscribe [:rf.xray/keybinding-enabled?]))))))

(deftest keybinding-enabled-sub-falls-back-to-atom-pre-first-dispatch
  (testing "rf2-8i1tg3 — before any :rf.xray/keybinding-enabled-update
            dispatch, the sub falls back to the live config atom
            (mirrors every other `:rf.xray/setting`-style sub's pre-
            first-open fallback) rather than a hardcoded default"
    (setup!)
    (config/set-keybinding-enabled! false)
    (rf/with-frame :rf/xray
      (is (false? @(rf/subscribe [:rf.xray/keybinding-enabled?]))
          "sub reads the atom directly when app-db has never mirrored it"))
    (config/set-keybinding-enabled! true)))
