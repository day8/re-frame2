(ns day8.re-frame2-xray.install
  "Side-effect-free Xray install helpers (rf2-5w06uu).

  This namespace holds the *callable* install primitives Xray's two
  install paths share — the trace/epoch collector registrations and
  the browser-API export installer — WITHOUT performing any work at
  namespace-load time. Loading this ns is inert; the side-effects fire
  only when a caller invokes one of the helpers below.

  ## Why this split exists

  Before rf2-5w06uu the foundation registrations lived in
  `day8.re-frame2-xray.preload`, whose top-level
  `(when interop/debug-enabled? …)` boot block runs them on load. But
  the user-facing facade `day8.re-frame2-xray.core` requires `preload`
  to reach `init!`'s registration helpers — so a host that chose the
  *manual* `require core → configure! → init!` path got the full
  preload behaviour (settings load, handler/collector registration,
  browser globals, keybinding attach, auto-open) merely by requiring
  `core`, defeating `configure!`-before-auto-open ordering and boot
  flags like `:rf.xray/auto-open? false`.

  Splitting the inert helpers here lets:

  - `core` require *this* ns (load-inert) instead of `preload`, so
    requiring the manual facade no longer fires preload side-effects.
  - `preload` require this ns and call these helpers from its
    side-effecting boot block — the zero-config `:devtools/preloads`
    path keeps auto-installing exactly as before.

  ## Production posture

  As with the preload, the registration/install work is no-op-safe in
  production: the trace surface elides via `interop/debug-enabled?`,
  the browser-API export guards on `js/window`, and the framework's
  epoch listener registration is itself a no-op when the epoch artefact
  is absent. But the right answer is still to keep Xray out of
  production builds via shadow-cljs's `:dev`-only `:devtools` block."
  (:require [goog.object :as gobj]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            ;; rf2-qwm0a — the public-tooling listener surface
            ;; (`register-listener!` etc.) lives in
            ;; `re-frame.trace.tooling` for production-DCE reasons.
            ;; Xray's trace-collector registration below targets the
            ;; tooling sibling directly. The two consumers of this ns
            ;; (preload + core) are both dev-only, so this require is
            ;; excluded from production bundles.
            [re-frame.trace.tooling :as trace-tooling]
            [day8.re-frame2-xray.mount :as mount]
            [day8.re-frame2-xray.trace-collector :as trace-collector]))

;; ---- registrations -------------------------------------------------------

(defonce ^:private trace-cb-registered?
  ;; Idempotency sentinel for the trace-callback registration. Cf.
  ;; `re-frame.trace/register-listener!`: passing the same id twice
  ;; replaces the callback. The replacement is harmless but emits a
  ;; warning trace on every reload that pollutes the dev console;
  ;; this sentinel suppresses re-registration on `:after-load`.
  (atom false))

(defonce ^:private epoch-cb-registered?
  ;; Idempotency sentinel for the epoch-callback registration. Same
  ;; rationale as `trace-cb-registered?`. Phase 3 (rf2-t53ze) — the
  ;; Time Travel panel needs a per-settle pump from
  ;; `(rf/register-listener! :epoch …)` into Xray's app-db so the scrubber's
  ;; subscriptions re-fire when the framework appends an epoch.
  (atom false))

(defn register-trace-collector!
  "Register Xray's trace-collector callback under
  `:rf.xray/trace-collector`. Idempotent via the
  `trace-cb-registered?` sentinel — a second call is a silent no-op
  (no warning trace, no replacement). Public so tests can drive
  it directly without `#'`-piercing into private vars."
  []
  (when (compare-and-set! trace-cb-registered? false true)
    (trace-tooling/register-listener! :rf.xray/trace-collector
                                      trace-collector/collect-trace!))
  nil)

(defn register-epoch-collector!
  "Register Xray's epoch-settle pump under `:rf.xray/epoch-collector`.

  On every drain-settle the framework's epoch artefact fires this
  callback with the assembled `:rf/epoch-record`; the cb dispatches
  `:rf.xray/epoch-recorded` into the `:rf/xray` frame so the
  registry's event handler re-reads `rf/epoch-history` and pumps the
  fresh snapshot into Xray's app-db. The scrubber's
  `:rf.xray/epoch-history` sub then re-fires off the standard
  app-db-write reactive path.

  ## Pre-mount guard (rf2-1barg)

  The preload runs at app boot but `:rf/xray` is lazy-registered by
  `mount.cljs/open!` on the first Ctrl+Shift+C keypress. Host
  dispatches that fire BEFORE the user opens Xray would otherwise
  flow through this cb and dispatch into a frame that doesn't yet
  exist — the runtime would emit `:rf.error/frame-destroyed` and the
  record would be lost. The `frame/frame :rf/xray` guard makes
  pre-mount cbs a silent no-op; `ensure-xray-frame!` seeds the
  panel's `:epoch-history` slot with the framework's current
  `(rf/epoch-history target)` snapshot at first open, so the
  pre-mount window's records still surface.

  Idempotent via the `epoch-cb-registered?` sentinel. No-op when the
  `day8/re-frame2-epoch` artefact is not on the classpath
  (`(rf/register-listener! :epoch …)` is itself a no-op in that case)."
  []
  (when (compare-and-set! epoch-cb-registered? false true)
    (rf/register-listener! :epoch :rf.xray/epoch-collector
      (fn [record]
        ;; Pre-mount no-op — see the docstring's §Pre-mount guard.
        ;; Resolved against the framework's frame registry (NOT a
        ;; Xray-side flag) so a teardown / re-register cycle stays
        ;; correctly tracked without our needing extra state.
        (when (frame/frame :rf/xray)
          ;; Wrap the dispatch in :rf/xray so the registry's handler
          ;; writes to Xray's app-db, not the host's. The cb's
          ;; record carries :frame — pass it as the dispatch arg so
          ;; the handler can compare against its target-frame and
          ;; skip updates for non-target frames.
          (rf/with-frame :rf/xray
            (rf/dispatch [:rf.xray/epoch-recorded (:frame record)]))))))
  nil)

(defn reset-for-test!
  "Reset the install helpers' idempotency sentinels so test fixtures can
  drive multiple load cycles. Test-only — never call from production
  code."
  []
  (reset! trace-cb-registered? false)
  (reset! epoch-cb-registered? false)
  nil)

;; ---- public browser API exports -----------------------------------------

(defn- ensure-js-object!
  [parent key]
  (or (gobj/get parent key)
      (let [obj #js {}]
        (gobj/set parent key obj)
        obj)))

(defn- install-api-on!
  [obj]
  (gobj/set obj "open_BANG_" mount/open!)
  (gobj/set obj "open_overlay_BANG_" mount/open-overlay!)
  (gobj/set obj "close_BANG_" mount/close!)
  (gobj/set obj "toggle_BANG_" mount/toggle!)
  (gobj/set obj "popout_BANG_" mount/popout!)
  (gobj/set obj "status" mount/status)
  nil)

(defn install-browser-api-exports!
  "Expose the dev-only Xray launch API on the browser global object.

  The preload is the namespace shadow-cljs actually loads into host
  dev bundles; the facade namespace (`day8.re-frame2-xray.core`) may
  be absent from apps that only install Xray via `:devtools/preloads`.
  Export on `window.day8.re_frame2_xray` for preload-only bundles, and
  augment `window.day8.re_frame2_xray.core` only when Closure has
  already created that real namespace object. Never pre-create `core`:
  doing so races `goog.provide` in browser-test and fails with
  \"Namespace already declared\"."
  []
  (when (exists? js/window)
    (let [day8  (ensure-js-object! js/window "day8")
          xray (ensure-js-object! day8 "re_frame2_xray")]
      (install-api-on! xray)
      (when-let [core (gobj/get xray "core")]
        (install-api-on! core))))
  nil)
