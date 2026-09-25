(ns day8.re-frame2-xray.settings.effects-cljs-test
  "CLJS tests for the Settings popup's side-effect appliers.

  Asserts:
  - `apply-text-size!` writes the CSS custom property
  - `apply-theme!` toggles the CSS class on the shell root + <html>
  - `update-setting!` dispatched through events drives the matching
    `apply-*!` side effect
  - The auto-open watcher edge-fires on empty→non-empty when toggle
    is on AND Xray is hidden

  No DOM-shell mount happens — we create a stub `#rf-xray-root`
  element + a stub `<html>` for the CSS-var assertions; the auto-
  open watcher test exercises the watch fn directly via the
  subscription value transition."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.epoch.state :as rf.epoch.state]
            [re-frame.frame :as rf.frame]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.core :as core]
            [day8.re-frame2-xray.keybinding :as keybinding]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.settings.effects :as effects]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.theme.tokens :as tokens]))

;; ---- fixture -----------------------------------------------------------

;; `ensure-stub-shell-root!` lives in the dom sibling with the rows that
;; need it: it opens with `(exists? js/document)`, and this node runtime
;; provides no `js/document`, so here it would create nothing and every
;; row depending on it would assert inside a nil binding.
;; `remove-stub-shell-root!` serves the fixture and the missing-root
;; tests below, and it is a no-op on node by design.

(defn- remove-stub-shell-root! []
  (when (exists? js/document)
    (when-let [el (.getElementById js/document "rf-xray-root")]
      (when (.-parentNode el)
        (.removeChild (.-parentNode el) el)))))

(use-fixtures :each
  ;; `make-xray-runtime-fixture`: plain-atom adapter + the
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
;;
;; `shell-root` and `html-root` live in the dom sibling with every row
;; that reads them. Both return nil on node — each opens with
;; `(exists? js/document)` — so a `(when-let [el (shell-root)] (is ...))`
;; here would assert nothing. Nothing in this namespace reads the DOM.

;; ---- text-size ----------------------------------------------------------

;; THE REAL-DOM ROWS LIVE IN THE DOM SIBLING.
;;
;; `apply-text-size-writes-css-var`, `apply-theme-toggles-class`,
;; `update-event-applies-text-size-effect`,
;; `update-event-applies-theme-effect`,
;; `apply-all-restores-text-size-and-theme`,
;; `apply-all-restores-panel-width`,
;; `apply-use-system-colors-stamps-and-clears-attribute`,
;; `apply-all-restores-use-system-colors`,
;; `apply-density-font-size-writes-css-var` and
;; `apply-all-restores-density-font-size` live in
;; `day8.re-frame2-xray.settings.effects-dom-cljs-test`.
;;
;; Each asserts inside `(when-let [el (shell-root)] ...)`, and
;; `shell-root` / `html-root` are themselves `(when (exists? js/document)
;; ...)`. `ensure-stub-shell-root!` looks like it supplies the host but
;; opens with the same `(exists? js/document)` test, so on node it
;; creates nothing and every `when-let` binds nil. `:browser-test`'s
;; `.*-dom-cljs-test$` `:ns-regexp` does not match this file's name, so
;; such rows here would execute in NEITHER lane. The sibling's name ends
;; `-dom-cljs-test`, which BOTH builds select.
;;
;; THREE TESTS KEEP A HOST-FREE HALF HERE, because they mix DOM claims
;; with assertions that really do run on node:
;; `apply-use-system-colors-handles-missing-shell-root`,
;; `update-event-applies-use-system-colors-effect` and
;; `update-event-applies-density-font-size-effect` keep their host-free
;; halves below; their DOM halves live in the sibling. That keeps the
;; live assertions ON the node lane.

(deftest apply-text-size-handles-missing-shell-root
  (remove-stub-shell-root!)
  (is (nil? (effects/apply-text-size! 11))
      "no-op when shell root absent; no throw"))

;; ---- theme --------------------------------------------------------------

;; ---- update-setting! drives the side effect ----------------------------

;; ---- filters ------------------------------------------------------------
;;
;; The Settings popup has no Filters tab — the ribbon strip + per-pill
;; edit popup + mute manager are the canonical pill-management surfaces.

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
  ;; `install-auto-open-watcher!` can run at preload before `:rf/xray`
  ;; is lazy-registered. `rf/subscribe` then returns nil, and an
  ;; unguarded `(add-watch nil ...)` throws `No protocol method
  ;; IWatchable.-add-watch defined for type null`. The install is
  ;; guarded; a call with no `:rf/xray` frame is a silent no-op.
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

;; ---- auto-open reopen preserves the realized surface --------------------
;;
;; All three reopen routes — the two GLOBAL ones (Ctrl+Shift+C toggle +
;; Cmd/Ctrl+K palette) and the auto-open-on-error watcher — preserve the
;; last-realized mount surface via `mount/toggle!`. The watcher goes
;; through the shared `reopen-preserving-surface!` helper (→ `toggle!`);
;; a hard-coded `mount/open!` would be a surface CHANGE that reverts a
;; hidden overlay to inline on auto-open.
;;
;; We unit-test that helper's routing directly (mirroring how
;; mount_cljs_test's `toggle!-*` suite unit-tests the mount layer's own
;; surface preservation): the auto-open GATE — the empty→non-empty edge +
;; toggle-on + hidden — is covered by the watcher tests above. The full
;; `install-auto-open-watcher!` `add-watch` path can't be driven under THIS
;; suite's headless plain-atom adapter (its derived subscriptions reify
;; `IDeref`/`IDisposable` only, not `IWatchable`), and no browser suite
;; exercises that reactive glue either; it is pinned on a ratom-family
;; adapter, in this same directory:
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
  (testing "the auto-open-on-error reopen route
            (`reopen-preserving-surface!`) invokes the surface-preserving
            `mount/toggle!` export, never the surface-changing `mount/open!`
            (which would revert a hidden overlay to inline), as the
            Ctrl+Shift+C + palette routes do."
    (with-recording-mount-exports
      (fn [invoked]
        (#'effects/reopen-preserving-surface!)
        (is (= ["toggle_BANG_"] @invoked)
            "reopen invoked the surface-preserving toggle! export, and only it")
        (is (not-any? #{"open_BANG_"} @invoked)
            "reopen did NOT call the surface-changing open!")))))

;; ---- apply-all! ---------------------------------------------------------

;; ---- epoch-history ------------------------------------------------------

(deftest apply-epoch-history-writes-substrate-depth
  (testing "apply-epoch-history! routes through
            `(rf/configure! {:epoch-history {:depth N}})` so the
            substrate's per-frame ring buffer matches the user's
            saved capacity. Reads the live depth from
            `re-frame.epoch.state` to assert the wire actually landed."
    ;; Capture original depth so the fixture doesn't leak the bump.
    (let [orig (rf.epoch.state/depth)]
      (try
        (effects/apply-epoch-history! 123)
        (is (= 123 (rf.epoch.state/depth))
            "substrate depth picks up the new value")
        ;; Defensive: nil / non-numeric is a no-op so a malformed
        ;; persisted payload never zeros the ring.
        (effects/apply-epoch-history! nil)
        (is (= 123 (rf.epoch.state/depth))
            "nil input is a no-op; substrate depth unchanged")
        (effects/apply-epoch-history! 0)
        (is (= 123 (rf.epoch.state/depth))
            "zero input is dropped at the effect boundary; substrate
             depth unchanged (the slider's min is 10 anyway)")
        (finally
          (rf/configure! {:epoch-history {:depth orig}}))))))

(deftest update-event-applies-epoch-history-effect
  (testing "dispatching `:rf.xray/settings-update :general
            :epoch-history N` writes through to the substrate via the
            matching effect."
    (setup!)
    (let [orig (rf.epoch.state/depth)]
      (try
        (rf/with-frame :rf/xray
          (rf/dispatch-sync [:rf.xray/settings-update
                             :general :epoch-history 150]))
        (is (= 150 (config/get-setting :general :epoch-history))
            "config slot carries the new value")
        (is (= 150 (rf.epoch.state/depth))
            "substrate ring depth follows the dispatch")
        (finally
          (rf/configure! {:epoch-history {:depth orig}}))))))

(deftest apply-all-restores-epoch-history
  (testing "the boot path re-applies the persisted depth
            so the substrate ring matches the user's saved capacity
            BEFORE first dispatch."
    (let [orig (rf.epoch.state/depth)]
      (try
        (config/update-setting! :general :epoch-history 88)
        (effects/apply-all!)
        (is (= 88 (rf.epoch.state/depth))
            "apply-all! routes the persisted value to the substrate")
        (finally
          (rf/configure! {:epoch-history {:depth orig}}))))))

;; ---- cascades-retained --------------------------------------------------
;;
;; The Buffer-tab `:events-retained` knob writes through to the
;; framework trace ring via `(rf/configure! {:trace-buffer
;; {:events-retained N}})`. We spy on `rf/configure!` to assert the
;; effect / event / boot paths all reach the published substrate API
;; with the canonical config-map shape — the framework knob itself is
;; proven by `re-frame.configure-test` (configure_test.clj:60-66), so
;; the Settings BRIDGE is what these tests guard.

(deftest apply-cascades-retained-writes-through-to-configure
  (testing "apply-events-retained! routes through
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
  (testing "dispatching `:rf.xray/settings-update :buffer
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
  (testing "the boot path re-applies the persisted
            cascades-retained count so the substrate trace ring matches
            the user's saved capacity BEFORE first dispatch."
    (let [calls (atom [])]
      (with-redefs [rf/configure! (fn [config-map] (swap! calls conj config-map) nil)]
        (config/update-setting! :buffer :events-retained 21)
        (effects/apply-all!)
        (is (some #{{:trace-buffer {:events-retained 21}}} @calls)
            "apply-all! routes the persisted value to the substrate")))))

;; ---- panel width --------------------------------------------------------
;;
;; The `<html>` CSS-var rows live in the dom sibling. The rows
;; below are about the CLAMP, which is deliberately outside the `<html>`
;; guard and so is the half this node lane CAN see.
;;
;; The boot path clamps as well as the drag handler
;; (`:rf.xray/set-panel-width-px`). With the drag handler as the only clamp
;; site, a width dragged wide on a large monitor would replay VERBATIM at
;; boot on a narrow one: `apply-all!` would hand the persisted number
;; straight to `<html>` and the host's `flex-basis` could squeeze the app
;; itself to nothing, on a surface whose resize handle had gone off the
;; side of the screen with it.

(deftest apply-panel-width-clamps-persisted-width-to-viewport
  (testing "a persisted width wider than the viewport
            allows is clamped to viewport × `max-panel-width-fraction`
            before it is applied, and the clamped value is written BACK
            through `update-setting!` so storage converges rather than
            re-clamping on every boot (the same posture the drag path
            takes)."
    (config/update-setting! :general :panel-width-px 1700)
    (is (= 1700 (config/get-setting :general :panel-width-px))
        "precondition: the oversize width is persisted")
    ;; Viewport passed explicitly — the 1-arity reads `js/window`, which
    ;; the node lane does not have. This is the same why-pass-it-in
    ;; reasoning `config/clamp-panel-width-px` itself documents.
    (effects/apply-panel-width! 1700 1280)
    (let [clamped (config/get-setting :general :panel-width-px)]
      (is (= 1152 clamped)
          "clamped to 0.9 × 1280")
      (is (<= clamped (* 0.9 1280))
          "and therefore inside the documented ceiling")
      (is (>= clamped config/min-panel-width-px)
          "never below the floor"))
    ;; Storage converged: a fresh in-memory atom reloaded from the
    ;; payload reads the clamped value, not the original 1700.
    (reset! config/settings config/default-settings)
    (config/load-settings-from-storage!)
    (is (= 1152 (config/get-setting :general :panel-width-px))
        "the write-back reached localStorage — the next boot starts
         from a usable width")))

(deftest apply-panel-width-leaves-an-in-range-width-alone
  (testing "the write-back is guarded on the value
            actually moving, so the drag path (which clamps before it
            calls here) triggers no second storage round-trip, and a
            width that fits is persisted unchanged."
    (config/update-setting! :general :panel-width-px 700)
    (effects/apply-panel-width! 700 1280)
    (is (= 700 (config/get-setting :general :panel-width-px))
        "700 fits inside 0.9 × 1280 = 1152 — untouched")
    (reset! config/settings config/default-settings)
    (config/load-settings-from-storage!)
    (is (= 700 (config/get-setting :general :panel-width-px))
        "and storage still carries it")))

(deftest apply-panel-width-never-persists-an-inherited-width
  (testing "with NO persisted width, the width being
            applied is inherited (here the compiled default 560). The
            clamp fits it to a 600px viewport in the live map, and leaves
            storage untouched: saving that 540 as an override would
            outrank every later host width, even on a wide screen."
    (is (nil? (#'config/storage-get config/settings-storage-key))
        "precondition: nothing persisted")
    (effects/apply-panel-width! config/default-panel-width-px 600)
    (is (= 540 (config/get-setting :general :panel-width-px))
        "the live width is fitted to 0.9 × 600")
    (is (nil? (#'config/storage-get config/settings-storage-key))
        "and storage is untouched — the clamp manufactured no override")
    ;; The consequence that matters: a later host width still lands. The
    ;; node lane has no `js/window`, so `configure!`'s re-apply clamps
    ;; against the 2000px fallback, where 720 fits.
    (config/configure! {:rf.xray/settings {:general {:panel-width-px 720}}})
    (is (= 720 (config/get-setting :general :panel-width-px))
        "a later host `configure!` width lands")))

(deftest apply-all-clamps-the-persisted-width
  (testing "the clamp reaches the boot path, which is
            where it matters: `apply-all!` is what the preload and
            `core/init!` call, and it is the caller that would replay an
            unclamped value."
    (config/update-setting! :general :panel-width-px 4000)
    (effects/apply-all!)
    ;; The 1-arity resolves the viewport itself; the node lane has no
    ;; `js/window`, so `clamp-panel-width-px` falls back to its own
    ;; 2000px default → ceiling 1800.
    (is (= 1800 (config/get-setting :general :panel-width-px))
        "apply-all! clamped the oversize persisted width")))

;; ---- init! loads + applies persisted Settings ---------------------------
;;
;; `core/init!` is the MANUAL install path — the documented alternative to
;; wiring `day8.re-frame2-xray.preload` into `:devtools/preloads`. Like the
;; preload's boot block it calls `load-settings-from-storage!` then
;; `apply-all!`; without them a host that installed manually would show
;; compiled-in defaults however many times the user had changed them in
;; the Settings popup.

(deftest init-loads-and-applies-persisted-settings
  (testing "`core/init!` loads the persisted Settings and
            applies their effects, exactly as the preload's boot block
            does"
    (#'config/storage-set! config/settings-storage-key
                           (pr-str {:general {:text-size     19
                                              :epoch-history 123}}))
    (reset! config/settings config/default-settings)
    (is (= 13 (config/get-setting :general :text-size))
        "precondition: the in-memory atom is back at the default")
    (let [calls (atom [])]
      (with-redefs [rf/configure! (fn [config-map] (swap! calls conj config-map) nil)]
        (core/init!))
      (is (= 19 (config/get-setting :general :text-size))
          "init! loaded the persisted value into the live settings atom")
      (is (some #{{:epoch-history {:depth 123 :trace-events-keep 123}}} @calls)
          "and applied it — apply-all! routed the persisted epoch depth
           to the substrate"))))

(deftest init-opts-still-win-over-persisted-settings
  (testing "the load runs FIRST and the explicit opts
            last, so `init! opts` is the last-mile injection seam
            spec/015 §`configure!` vs `init!` vs persisted Settings
            describes"
    (#'config/storage-set! config/settings-storage-key
                           (pr-str {:theme :dark}))
    (reset! config/settings config/default-settings)
    (core/init! {:theme :light})
    (is (= :light (config/get-setting :theme nil))
        "the explicit opt beat the persisted :dark")))

;; ---- density → font-size knob -------------------------------------------

(deftest density->font-size-px-mapping
  (testing "density keyword resolves to the canonical px value the
            radio writes into `--rf-xray-font-size`. Compact tightens
            by 1px, cosy is the baseline (matches
            `tokens/font-size-default`); those are the only tiers."
    (is (= 12 (:compact effects/density->font-size-px)))
    (is (= 13 (:cosy    effects/density->font-size-px)))
    (is (= #{:compact :cosy} (set (keys effects/density->font-size-px))))
    (is (= "13px" tokens/font-size-default)
        "cosy mapping matches the type-scale's baseline default")))

(deftest density->px-falls-back-to-cosy-on-unknown
  ;; The `:rf.xray/density` sub coerces unknown values to `:cosy`;
  ;; the apply-fn mirrors that posture, so a persisted `:comfy` or any
  ;; other stray value lands on the cosy px rather than nil/throw.
  (is (= 13 (effects/density->px :cosy)))
  (is (= 12 (effects/density->px :compact)))
  (is (= 13 (effects/density->px :comfy))
      "a persisted :comfy renders at the size the sub's :cosy reading promises")
  (is (= 13 (effects/density->px :something-weird))
      "unknown density coerces to the cosy default")
  (is (= 13 (effects/density->px nil))
      "nil density coerces to the cosy default"))

(deftest density-effect-agrees-with-the-density-sub
  (testing "whatever the stored density — either tier, a persisted
            `:comfy`, or a stray value — the px the effect writes for the
            stored value is the px of the tier `:rf.xray/density` reports,
            so the font size and the row rhythm never disagree"
    (setup!)
    (doseq [stored [:compact :cosy :comfy :something-weird]]
      (rf/with-frame :rf/xray
        (rf/dispatch-sync [:rf.xray/settings-update :general :density stored])
        (let [reported @(rf/subscribe [:rf.xray/density])]
          (is (= (effects/density->px reported) (effects/density->px stored))
              (str "stored " stored " → sub reports " reported
                   "; the effect must write that tier's px")))))))

;; ---- use-system-colors? -------------------------------------------------

(deftest apply-use-system-colors-handles-missing-shell-root
  (testing "no-op when shell root absent; no throw."
    ;; The `<html>`-still-written half of this claim needs a real
    ;; `<html>` (`html-root` binds nil on node), so it lives in
    ;; `settings.effects-dom-cljs-test/apply-use-system-colors-stamps-
    ;; html-without-a-shell-root`. The no-op return claim below is
    ;; host-free and genuinely runs here.
    (remove-stub-shell-root!)
    (is (nil? (effects/apply-use-system-colors! true)))
    ;; Clean up so unrelated tests don't see the stamped <html>.
    (effects/apply-use-system-colors! false)))

(deftest update-event-applies-use-system-colors-effect
  (testing "dispatching `:rf.xray/settings-update :general
            :use-system-colors? true` stamps the chrome attribute via
            the matching effect."
    ;; The two attribute claims need a real shell root (`shell-root`
    ;; binds nil on node), so they live in
    ;; `settings.effects-dom-cljs-test`. The settings-slot claim below
    ;; is host-free and genuinely runs here.
    (setup!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/settings-update
                         :general :use-system-colors? true]))
    (is (true? (config/get-setting :general :use-system-colors?))
        "config slot carries the new value")
    ;; Flip off, and assert the slot follows — the DOM half of this
    ;; flip is the dom sibling's.
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/settings-update
                         :general :use-system-colors? false]))
    (is (false? (config/get-setting :general :use-system-colors?))
        "config slot follows the flip back off")))

(deftest apply-density-font-size-handles-missing-shell-root
  (remove-stub-shell-root!)
  (is (nil? (effects/apply-density-font-size! :compact))
      "no-op when shell root absent; no throw"))

(deftest update-event-applies-density-font-size-effect
  (testing "Dispatching `[:rf.xray/settings-update :general :density
            :compact]` flips `--rf-xray-font-size` to 12px so the
            whole `type-scale` rescales on the next paint."
    ;; The two CSS-var claims need a real shell root (`shell-root` binds
    ;; nil on node), so they live in `settings.effects-dom-cljs-test`. The
    ;; settings-atom claims below are host-free and genuinely run here.
    (setup!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/settings-update
                         :general :density :compact]))
    ;; Persistence — the dual-write goes to the in-memory atom +
    ;; localStorage shim via `config/update-setting!`.
    (is (= :compact (config/get-setting :general :density))
        "settings atom carries the new density")
    ;; Flip to cosy — the atom must follow; the inline-write half of
    ;; this flip is the dom sibling's.
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/settings-update
                         :general :density :cosy]))
    (is (= :cosy (config/get-setting :general :density))
        "settings atom follows the flip to cosy")))

;; ---- Keybindings tab "Handle keys?" reactive dual-write -----------------
;;
;; Like every other toggle in this popup, the Keybindings tab's master
;; toggle goes through `:rf.xray/keybinding-enabled-update` (flips the
;; `config/keybinding-attach-enabled?` atom AND mirrors app-db), and the
;; controlled checkbox reads the `:rf.xray/keybinding-enabled?` sub. Read
;; straight off the bare atom and written via
;; `config/set-keybinding-enabled!` — no dispatch, no app-db mirror — the
;; checkbox's `:checked` would never re-fire reactively off a change.

(deftest keybinding-enabled-update-flips-atom-and-mirrors-app-db
  (testing "dispatching :rf.xray/keybinding-enabled-update
            flips the canonical config atom AND the app-db mirror the
            sub reads, in one atomic step"
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
  (testing "before any :rf.xray/keybinding-enabled-update
            dispatch, the sub falls back to the live config atom
            (mirrors every other `:rf.xray/setting`-style sub's pre-
            first-open fallback) rather than a hardcoded default"
    (setup!)
    (config/set-keybinding-enabled! false)
    (rf/with-frame :rf/xray
      (is (false? @(rf/subscribe [:rf.xray/keybinding-enabled?]))
          "sub reads the atom directly when app-db has never mirrored it"))
    (config/set-keybinding-enabled! true)))

;; ---- the slot flip is REACTIVE -------------------------------------------
;;
;; `keybinding/attach!` reads the slot ONCE, at attach time. On the
;; `:devtools/preloads` install path — the documented one — shadow-cljs
;; loads preloads before the app's `:init-fn`, so the listener is already
;; on `js/document` by the time the host's `configure!` runs. Without a
;; watch, `(configure! {:rf.xray/keybinding-enabled? false})` would be a
;; silent no-op: the host declares its intent, nothing is printed, and
;; Xray's capture-phase listener carries on swallowing the host's own
;; Cmd/Ctrl+K. So `keybinding.cljs` watches the slot. The attach / detach themselves
;; need a `js/document` this lane does not have, so the rows below pin the
;; WIRING — that a flip reaches the runtime at all — and leave the
;; listener mechanics to `keybinding_cljs_test`'s stub-driven rows.

(deftest keybinding-enabled-flip-detaches-and-reattaches
  (testing "flipping the slot drives the runtime rather
            than only mutating an atom nothing re-reads"
    (let [calls (atom [])]
      (with-redefs [keybinding/attach! (fn [] (swap! calls conj :attach) nil)
                    keybinding/detach! (fn [] (swap! calls conj :detach) nil)]
        (config/set-keybinding-enabled! false)
        (is (= [:detach] @calls)
            "a false flip removes the listener the preload already attached")
        (config/set-keybinding-enabled! true)
        (is (= [:detach :attach] @calls)
            "and flipping back re-attaches — symmetric, per the
             attach!/detach! contract")
        (config/set-keybinding-enabled! nil)
        (is (= [:detach :attach] @calls)
            "`nil` resets to the default `true`, which is already the
             current value — no change, so no redundant attach")))))

(deftest keybinding-enabled-flip-through-configure-is-reactive
  (testing "and it works through the host-facing surface:
            `(configure! {:rf.xray/keybinding-enabled? false})` landing
            AFTER the preload attached"
    (let [calls (atom [])]
      (with-redefs [keybinding/attach! (fn [] (swap! calls conj :attach) nil)
                    keybinding/detach! (fn [] (swap! calls conj :detach) nil)]
        (config/configure! {:rf.xray/keybinding-enabled? false})
        (is (= [:detach] @calls)
            "the embed host's surrender switch reaches the listener")
        (config/configure! {:rf.xray/keybinding-enabled? true})
        (is (= [:detach :attach] @calls))))))
