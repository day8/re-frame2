(ns day8.re-frame2-xray.preload-decoupling-cljs-test
  "Requiring the manual facade `day8.re-frame2-xray.core`
  must NOT trigger preload side-effects before the host's
  `configure!` / `init!`.

  ## Why it matters

  `preload.cljs`'s top-level `(when interop/debug-enabled? …)` boot block
  registers the trace + epoch collectors, installs the browser-API
  globals, attaches the Ctrl+Shift+C keybinding, applies persisted
  settings, and schedules auto-open. Were `core.cljs` to
  `(:require [day8.re-frame2-xray.preload …])`, a host that chose the
  MANUAL `require core → configure! → init!/open!` integration would get
  the full zero-config preload behaviour merely by requiring `core` —
  defeating `configure!`-before-auto-open ordering and boot flags like
  `:rf.xray/auto-open? false` / `:rf.xray/keybinding-enabled? false`.

  ## The contract under test

  The callable install primitives live in the inert-on-load
  `day8.re-frame2-xray.install` ns; `core` requires THAT (not `preload`).
  Requiring / touching the `core` facade's manual surface is inert —
  the install side-effects fire only when the host calls `core/init!`
  (or `core/open!`). The zero-config `:devtools/preloads` boot block
  invokes the same `install/*` helpers + `keybinding/attach!`, so the
  manual-install rows below cover what both paths install.

  ## Why node-test

  The trace + epoch listener registrations are observable via the
  framework's listener registries directly. The keybinding attach +
  browser-global install need a `js/document` / `js/window`; node-test
  has neither, so — mirroring `keybinding_cljs_test` — we install
  hand-rolled stubs for the duration of the relevant tests and restore
  the absent binding afterwards. `can-stub?` skips the stub-driven
  bodies cleanly on the `:browser-test` host (where the globals are
  non-configurable)."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
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
;; Mirrors keybinding_cljs_test's stub-and-detect pattern.

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
  ;; The manual facade's config surface is side-effect-free until init!:
  ;; were `core` to require `preload`, the boot block would already have run.
  (core/configure! {:rf.xray/auto-open? false
                    :rf.xray/keybinding-enabled? false})
  (core/set-auto-open! false)
  (core/set-egress-profile! :rf.egress/local-redacted)
  (is (= [false false false]
         [(trace-collector-registered?) (epoch-collector-registered?) (mount/mounted?)])
      "no collector registered and no shell mounted"))

(deftest init!-performs-the-manual-install
  (is (= [false false] [(trace-collector-registered?) (epoch-collector-registered?)])
      "CONTROL — clean before init!")
  (core/init!)
  (is (= [true true] [(trace-collector-registered?) (epoch-collector-registered?)])
      "init! registered the trace and epoch collectors"))

(deftest init!-installs-browser-api-exports-for-late-bound-actions
  ;; The palette pop-out fx and the Settings panel-position effect late-bind
  ;; their mount calls through these exports, so without them those actions
  ;; would silently no-op under the manual install path.
  (with-stub-dom*
    (fn [{:keys [window]}]
      (core/init!)
      (let [xray (aget (aget window "day8") "re_frame2_xray")]
        (is (= [true true true true]
               (mapv #(fn? (aget xray %)) ["popout_BANG_" "open_BANG_" "open_overlay_BANG_" "status"]))
            "the exact export names the late-bind call sites resolve")))))

;; ---- (2) configure! before init! wins deterministically ------------------

(deftest configure!-before-init!-suppresses-keybinding
  ;; init!'s attach! reads the slot the host set first.
  (with-stub-dom*
    (fn [{:keys [keydown-listeners]}]
      (core/configure! {:rf.xray/keybinding-enabled? false})
      (core/init!)
      (is (= [false []] [(keybinding/attached?) @keydown-listeners])))))

(deftest configure!-keybinding-enabled-true-attaches-on-init!
  ;; The control: the suppression above is the slot's effect, not the stub's.
  (with-stub-dom*
    (fn [{:keys [keydown-listeners]}]
      (core/configure! {:rf.xray/keybinding-enabled? true})
      (core/init!)
      (is (= [true 1] [(keybinding/attached?) (count @keydown-listeners)])))))

;; ---- (2b) install-browser-api-exports! `core` branch --------------------
;;
;; install-browser-api-exports! (install.cljs) exports onto
;; `window.day8.re_frame2_xray` and CONDITIONALLY augments an
;; EXISTING `window.day8.re_frame2_xray.core`, explicitly NEVER pre-creating
;; `core` (pre-creating it races `goog.provide` in browser-test with a
;; "Namespace already declared" failure). `init!-installs-browser-api-exports-for-late-bound-actions`
;; asserts the top-level exports; these two cases pin both arms of the
;; conditional `core` branch without relying on real browser globals (they
;; drive the stub window directly).

(deftest install-browser-api-exports-does-not-create-core-when-absent
  ;; Pre-creating `core` would race goog.provide and fail browser-test with
  ;; "Namespace already declared".
  (with-stub-dom*
    (fn [{:keys [window]}]
      (install/install-browser-api-exports!)
      (let [xray (aget (aget window "day8") "re_frame2_xray")]
        (is (= [true nil] [(fn? (aget xray "toggle_BANG_")) (aget xray "core")])
            "the launch API installs and core stays absent")))))

(deftest install-browser-api-exports-augments-preexisting-core
  ;; When Closure has already declared the core namespace object, install
  ;; augments that same object in place.
  (with-stub-dom*
    (fn [{:keys [window]}]
      (let [day8 (js-obj)
            xray (js-obj)
            core (js-obj)]
        (aset xray "core" core)
        (aset day8 "re_frame2_xray" xray)
        (aset window "day8" day8)
        (install/install-browser-api-exports!)
        (let [core' (-> (aget window "day8") (aget "re_frame2_xray") (aget "core"))]
          (is (= [true true true true]
                 [(identical? core core') (fn? (aget core' "toggle_BANG_"))
                  (fn? (aget core' "status")) (fn? (aget xray "toggle_BANG_"))])
              "the same core object gains the launch API beside the top-level export"))))))

