(ns day8.re-frame2-xray.preload-decoupling-cljs-test
  "rf2-5w06uu — requiring the manual facade `day8.re-frame2-xray.core`
  must NOT trigger preload side-effects before the host's
  `configure!` / `init!`.

  ## The bug

  `core.cljs` used to `(:require [day8.re-frame2-xray.preload …])`, and
  `preload.cljs`'s top-level `(when interop/debug-enabled? …)` boot block
  registers the trace + epoch collectors, installs the browser-API
  globals, attaches the Ctrl+Shift+C keybinding, applies persisted
  settings, and schedules auto-open. So a host that chose the MANUAL
  `require core → configure! → init!/open!` integration got the full
  zero-config preload behaviour merely by requiring `core` — defeating
  `configure!`-before-auto-open ordering and boot flags like
  `:rf.xray/auto-open? false` / `:rf.xray/keybinding-enabled? false`.

  ## The fix under test

  The callable install primitives moved to the inert-on-load
  `day8.re-frame2-xray.install` ns; `core` requires THAT (not `preload`).
  Requiring / touching the `core` facade's manual surface is now inert —
  the install side-effects fire only when the host calls `core/init!`
  (or `core/open!`). The zero-config `:devtools/preloads` path still
  auto-installs (the preload boot block invokes the same `install/*`
  helpers + `keybinding/attach!`).

  ## Why node-test

  The trace + epoch listener registrations are observable via the
  framework's listener registries directly. The keybinding attach +
  browser-global install need a `js/document` / `js/window`; node-test
  has neither, so — mirroring `keybinding_cljs_test` — we install
  hand-rolled stubs for the duration of the relevant tests and restore
  the absent binding afterwards. `can-stub?` skips the stub-driven
  bodies cleanly on the `:browser-test` host (where the globals are
  non-configurable)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.epoch.state :as rf.epoch.state]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace :as rf.trace]
            [re-frame.trace.tooling :as rf.trace.tooling]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.core :as core]
            [day8.re-frame2-xray.install :as install]
            [day8.re-frame2-xray.keybinding :as keybinding]
            [day8.re-frame2-xray.mount :as mount]
            [day8.re-frame2-xray.preload :as preload]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.trace-collector :as trace-collector]))

;; ---- world reset ---------------------------------------------------------
;;
;; The install side-effects are defonce-guarded and the framework
;; listener registries are process-scoped, so a faithful "fresh boot"
;; baseline clears them all before each test. This lets us assert that
;; touching the manual facade does NOT re-install, then that the explicit
;; entrypoint DOES.

(defn- clear-world! []
  (install/reset-for-test!)
  (registry/reset-for-test!)
  (trace-collector/reset-for-test!)
  (rf.trace.tooling/clear-listeners!)
  (rf.epoch.state/reset-listeners!)
  (config/reset-settings!)
  ;; teardown! resets mount + auto-open-state; detach! drops any keydown
  ;; listener left attached by a neighbouring test.
  (mount/teardown!)
  (try (keybinding/detach!) (catch :default _ nil)))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
     :init-fn clear-world!}))

;; ---- observability helpers ----------------------------------------------

(defn- trace-collector-registered?
  "Behaviourally probe whether Xray's trace collector is registered: the
  framework's listener map is private, so we clear Xray's buffer, emit a
  synthetic framework trace event, and check whether it landed in the
  buffer. Delivery ⇒ the collector is wired; no delivery ⇒ it isn't.
  Mirrors `preload_cljs_test`'s observable-contract approach."
  []
  (trace-collector/reset-for-test!)
  (rf.trace/emit! :info :rf.test/registration-probe {:source :test})
  (let [delivered? (pos? (count (trace-collector/buffer-for-test)))]
    (trace-collector/reset-for-test!)
    delivered?))

(defn- epoch-collector-registered?
  "True iff Xray's epoch collector is in the framework's epoch-listener
  map (the epoch state exposes a public listeners snapshot)."
  []
  (contains? (rf.epoch.state/listeners-snapshot) :rf.xray/epoch-collector))

;; ---- stub js/document + js/window ----------------------------------------
;;
;; Mirrors keybinding_cljs_test's rf2-higwg stub-and-detect pattern.

(defn- can-stub? [prop]
  (let [marker (js-obj "rf2-5w06uu-marker" true)
        prior  (when (exists? (js* "goog.global[~{}]" prop))
                 (js* "goog.global[~{}]" prop))]
    (js* "goog.global[~{}] = ~{}" prop marker)
    (let [installed? (identical? (js* "goog.global[~{}]" prop) marker)]
      (if (some? prior)
        (js* "goog.global[~{}] = ~{}" prop prior)
        (when installed? (js-delete js/goog.global prop)))
      installed?)))

(defn- mk-stub-document []
  (let [listeners (atom [])]
    {:doc       (js-obj "addEventListener"
                        (fn [type handler use-capture]
                          (swap! listeners conj {:type type :handler handler
                                                 :use-capture use-capture}))
                        "removeEventListener"
                        (fn [type handler use-capture]
                          (swap! listeners
                                 (fn [xs]
                                   (vec (remove #(and (= type (:type %))
                                                      (identical? handler (:handler %))
                                                      (= use-capture (:use-capture %)))
                                                xs))))))
     :listeners listeners}))

(defn- with-stub-dom*
  "Run `f` with `js/document` + `js/window` stubbed (a fresh keydown-
  listener tracker on document, a plain object as window). Restores the
  prior bindings afterwards. No-op on a host that won't let us stub
  (real browser)."
  [f]
  (when (and (can-stub? "document") (can-stub? "window"))
    (let [{:keys [doc listeners]} (mk-stub-document)
          win                     (js-obj)]
      (set! js/document doc)
      (set! js/window win)
      (try
        (f {:keydown-listeners listeners :window win})
        (finally
          (try (keybinding/detach!) (catch :default _ nil))
          (js-delete js/goog.global "document")
          (js-delete js/goog.global "window"))))))

;; ---- (1) manual facade is inert until init! ------------------------------

(deftest requiring-core-and-touching-config-is-inert
  (testing "rf2-5w06uu — the manual `core` facade's config surface does NOT
            register the trace/epoch collectors (the manual require/use path
            is side-effect-free until init!/open!)"
    ;; Baseline: clear-world! left the registries empty.
    (is (not (trace-collector-registered?))
        "no trace collector registered at baseline")
    (is (not (epoch-collector-registered?))
        "no epoch collector registered at baseline")
    ;; Exercise the manual facade's config surface — the host's
    ;; configure!-at-boot path. Before the fix, `core`'s require of
    ;; `preload` had already run the boot block; now these are inert.
    (core/configure! {:rf.xray/auto-open? false
                      :rf.xray/keybinding-enabled? false})
    (core/set-auto-open! false)
    (core/set-egress-profile! :rf.egress/local-redacted)
    (is (not (trace-collector-registered?))
        "configure! did NOT register the trace collector")
    (is (not (epoch-collector-registered?))
        "configure! did NOT register the epoch collector")
    (is (not (mount/mounted?))
        "configure! did NOT mount/auto-open the shell")))

(deftest init!-performs-the-manual-install
  (testing "rf2-5w06uu — core/init! explicitly performs the documented
            manual install: trace + epoch collectors registered"
    (is (not (trace-collector-registered?)) "clean before init!")
    (is (not (epoch-collector-registered?)) "clean before init!")
    (core/init!)
    (is (trace-collector-registered?)
        "init! registered the trace collector (framework emit lands in buffer)")
    (is (epoch-collector-registered?)
        "init! registered the epoch collector")))

(deftest init!-does-not-auto-open
  (testing "rf2-5w06uu — the manual init! path does NOT auto-open the shell
            (auto-open is a zero-config preload concern; the manual path is
            open-explicit via core/open!)"
    (core/init!)
    (is (not (mount/mounted?))
        "init! did not mount the shell — host must call open! explicitly")))

(deftest init!-installs-browser-api-exports-for-late-bound-actions
  (testing "rf2-xxo3zz — manual core/init! installs the browser-API exports on
            window.day8.re_frame2_xray.*, the SAME exports the preload boot
            block installs. The palette pop-out fx (palette/events §mount-popout!
            → popout_BANG_) and the Settings panel-position effect
            (settings/effects/apply-panel-position! → open_BANG_ / open_overlay_BANG_,
            visible-shell? → status) late-bind their mount calls through these
            exports to break the mount→shell→palette/settings→mount require cycle.
            Before this fix init! registered every handler but left the exports
            uninstalled, so those late-bound actions silently no-op'd under the
            manual install path while the preload path worked."
    (with-stub-dom*
      (fn [{:keys [window]}]
        ;; Baseline: clear-world! ran in the fixture; the stub window is a
        ;; bare object with no day8 namespace yet.
        (is (nil? (aget window "day8"))
            "no browser exports before init!")
        (core/init!)
        (let [day8 (aget window "day8")
              xray (when day8 (aget day8 "re_frame2_xray"))]
          (is (some? xray)
              "init! installed window.day8.re_frame2_xray")
          ;; The exact export names the late-bind call sites resolve:
          (is (fn? (aget xray "popout_BANG_"))
              "palette Ctrl+Enter pop-out (mount-popout! → popout_BANG_) resolves")
          (is (fn? (aget xray "open_BANG_"))
              "Settings panel-position :right-rail (apply-panel-position! → open_BANG_) resolves")
          (is (fn? (aget xray "open_overlay_BANG_"))
              "Settings panel-position :fullscreen (apply-panel-position! → open_overlay_BANG_) resolves")
          (is (fn? (aget xray "status"))
              "Settings visible-shell? probe (status export) resolves"))))))

;; ---- (2) configure! before init! wins deterministically ------------------

(deftest configure!-before-init!-suppresses-keybinding
  (testing "rf2-5w06uu — core/configure! {:rf.xray/keybinding-enabled? false}
            BEFORE init! wins: init!'s keybinding/attach! reads the config
            slot the host already set and does NOT attach the keydown
            listener."
    (with-stub-dom*
      (fn [{:keys [keydown-listeners]}]
        ;; Host boot-time config first.
        (core/configure! {:rf.xray/keybinding-enabled? false})
        (is (= false (config/keybinding-attach-enabled?))
            "the boot-time config slot is set before init!")
        ;; Now the manual install.
        (core/init!)
        (is (not (keybinding/attached?))
            "init! honoured the pre-set keybinding-enabled? false")
        (is (empty? @keydown-listeners)
            "no keydown listener attached to document")))))

(deftest configure!-keybinding-enabled-true-attaches-on-init!
  (testing "rf2-5w06uu — control: with keybinding enabled (default), init!
            DOES attach under a DOM, proving the suppression above is the
            config slot's effect, not a stub artefact."
    (with-stub-dom*
      (fn [{:keys [keydown-listeners]}]
        (core/configure! {:rf.xray/keybinding-enabled? true})
        (core/init!)
        (is (keybinding/attached?)
            "init! attached the keydown listener when enabled")
        (is (= 1 (count @keydown-listeners))
            "exactly one keydown listener on document")))))

(deftest configure!-auto-open-false-is-honoured-at-boot
  (testing "rf2-5w06uu — :rf.xray/auto-open? false set via configure! before
            boot wins: the preload's boot-on-runtime-ready! records the
            disabled diagnostic rather than mounting. rf2-avi7 — it still
            SEATS `:rf/xray`; only the OPEN is suppressed."
    (core/configure! {:rf.xray/auto-open? false})
    (is (= false (config/auto-open-enabled?))
        "auto-open slot is off before any auto-open attempt")
    (mount/boot-on-runtime-ready!)
    (is (not (mount/mounted?))
        "boot-on-runtime-ready! did not mount when auto-open? false")
    (is (= :auto-open-disabled (get-in (mount/status) [:diagnostic :reason]))
        "auto-open short-circuited to the disabled diagnostic")))

;; ---- (3) the zero-config :devtools/preloads path still auto-installs ------

(deftest preload-boot-helpers-still-install
  (testing "rf2-5w06uu — the zero-config preload path still works: invoking
            the install helpers + keybinding/attach! (exactly what the
            preload boot block does) registers the collectors, installs the
            browser globals, and attaches the keybinding."
    (with-stub-dom*
      (fn [{:keys [keydown-listeners window]}]
        (is (not (trace-collector-registered?)) "clean before boot")
        (is (not (epoch-collector-registered?)) "clean before boot")
        ;; Mirror preload.cljs's boot block (minus the debug-gate, which
        ;; is true under node-test).
        (registry/register-xray-handlers!)
        (install/register-trace-collector!)
        (install/register-epoch-collector!)
        (install/install-browser-api-exports!)
        (keybinding/attach!)
        (is (trace-collector-registered?)
            "preload boot registered the trace collector")
        (is (epoch-collector-registered?)
            "preload boot registered the epoch collector")
        (is (keybinding/attached?)
            "preload boot attached the keydown listener")
        (is (= 1 (count @keydown-listeners))
            "one keydown listener on document")
        ;; Browser globals installed under window.day8.re_frame2_xray.
        (let [day8 (aget window "day8")
              xray (when day8 (aget day8 "re_frame2_xray"))]
          (is (some? xray) "window.day8.re_frame2_xray installed")
          (is (fn? (aget xray "toggle_BANG_"))
              "the toggle! launch API is exported on the global"))))))

;; ---- (2b) install-browser-api-exports! `core` branch (rf2-3t7rs8) --------
;;
;; rf2-3t7rs8 finding 2: install-browser-api-exports! (install.cljs:156-173)
;; exports onto `window.day8.re_frame2_xray` and CONDITIONALLY augments an
;; EXISTING `window.day8.re_frame2_xray.core`, explicitly NEVER pre-creating
;; `core` (pre-creating it races `goog.provide` in browser-test with a
;; "Namespace already declared" failure). The pre-existing coverage above
;; only asserted the top-level `toggle_BANG_` export — neither conditional
;; `core` branch was pinned. These two cases close that gap without relying
;; on real browser globals (they drive the stub window directly).

(deftest install-browser-api-exports-does-not-create-core-when-absent
  (testing "rf2-3t7rs8 — install-browser-api-exports! must NOT pre-create
            `core`. With no pre-existing core object on the stub window,
            `re_frame2_xray.core` stays absent after install (the
            `when-let [core …]` guard skips the augment branch). This is
            load-bearing: pre-creating core would race goog.provide and
            fail browser-test with 'Namespace already declared'."
    (with-stub-dom*
      (fn [{:keys [window]}]
        ;; Baseline: clear-world! ran; the stub window is bare.
        (is (nil? (aget window "day8")) "no day8 namespace before install")
        (install/install-browser-api-exports!)
        (let [day8 (aget window "day8")
              xray (when day8 (aget day8 "re_frame2_xray"))]
          (is (some? xray) "top-level export object created")
          (is (fn? (aget xray "toggle_BANG_"))
              "top-level launch API still installed")
          (is (nil? (aget xray "core"))
              "core was NOT pre-created — the absent-core branch is a
               no-op, never a `goog.provide`-racing object creation"))))))

(deftest install-browser-api-exports-augments-preexisting-core
  (testing "rf2-3t7rs8 — when Closure has ALREADY created the real
            `window.day8.re_frame2_xray.core` namespace object, install!
            augments it in place with the launch API (the `when-let
            [core …]` branch fires). The existing object identity is
            preserved (no replacement) and the exact exports the facade
            relies on land on it."
    (with-stub-dom*
      (fn [{:keys [window]}]
        ;; Model Closure having already declared the core namespace:
        ;; pre-seed window.day8.re_frame2_xray.core with a marker so we
        ;; can prove the SAME object is augmented (not replaced).
        (let [day8        (js-obj)
              xray        (js-obj)
              core        (js-obj "rf2-3t7rs8-marker" true)]
          (aset xray "core" core)
          (aset day8 "re_frame2_xray" xray)
          (aset window "day8" day8)
          (install/install-browser-api-exports!)
          (let [core' (-> (aget window "day8")
                          (aget "re_frame2_xray")
                          (aget "core"))]
            (is (identical? core core')
                "the pre-existing core object is augmented in place, not
                 replaced — its identity (and the marker) survives")
            (is (true? (aget core' "rf2-3t7rs8-marker"))
                "the pre-existing marker is preserved (augment, not clobber)")
            (is (fn? (aget core' "toggle_BANG_"))
                "toggle_BANG_ landed on the pre-existing core object")
            (is (fn? (aget core' "status"))
                "status landed on the pre-existing core object")
            ;; And the top-level export is unconditionally present too.
            (is (fn? (aget xray "toggle_BANG_"))
                "the top-level export still installs alongside the
                 core augment")))))))

(deftest install-ns-load-is-side-effect-free
  (testing "rf2-5w06uu — the install ns is load-inert: its re-exports are
            identity-equal to the preload re-exports (one shared backing
            fn), so requiring either reaches the same callable helpers and
            neither load registers anything on its own."
    ;; Identity holds — preload re-exports the install fns, and core calls
    ;; them; one shared backing fn value.
    (is (identical? install/register-trace-collector!
                    preload/register-trace-collector!)
        "preload re-exports install/register-trace-collector!")
    (is (identical? install/register-epoch-collector!
                    preload/register-epoch-collector!)
        "preload re-exports install/register-epoch-collector!")
    ;; clear-world! ran in the fixture; nothing registered itself just by
    ;; the test ns loading install/core/preload.
    (is (not (trace-collector-registered?))
        "no trace collector registered purely by ns load")
    (is (not (epoch-collector-registered?))
        "no epoch collector registered purely by ns load")))
