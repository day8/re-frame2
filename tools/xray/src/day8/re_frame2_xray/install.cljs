(ns day8.re-frame2-xray.install
  "Load-inert install primitives shared by manual and preload startup.

  Requiring this namespace performs no installation. `core/init!` calls
  these functions explicitly; `preload` calls them from its dev-only boot
  block. This separation lets a manual host require and configure Xray
  before any listeners, browser globals, keybindings, or mounts exist.

  The helpers register the trace and epoch collectors and expose the
  browser launch API. Each operation is idempotent; the browser export is
  a no-op without `js/window`, and the preload owns the debug-build gate."
  (:require [goog.object :as gobj]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            ;; Listener registration uses each stream's home namespace so
            ;; the dev-only tooling surface remains independently elidable.
            [re-frame.trace.tooling :as rf.trace.tooling]
            [re-frame.epoch :as rf.epoch]
            [day8.re-frame2-xray.defaults :as defaults]
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
  ;; The Time Travel panel needs one per-settle pump into Xray's app-db.
  (atom false))

(defn register-trace-collector!
  "Register Xray's trace-collector callback under
  `:rf.xray/trace-collector`. Idempotent via the
  `trace-cb-registered?` sentinel — a second call is a silent no-op
  (no warning trace, no replacement). Public so tests can drive
  it directly without `#'`-piercing into private vars."
  []
  (when (compare-and-set! trace-cb-registered? false true)
    (rf.trace.tooling/register-listener! :rf.xray/trace-collector
                                      trace-collector/collect-trace!))
  nil)

(defn register-epoch-collector!
  "Register Xray's epoch-settle pump under `:rf.xray/epoch-collector`.

  On every dequeued event's settle the framework's epoch artefact fires
  this callback with the assembled `:rf/epoch-record` (one record per
  event's run-to-completion, not per drain-settle); the cb dispatches
  `:rf.xray/epoch-recorded` into the `:rf/xray` frame so the
  registry's event handler re-reads `rf/epoch-history` and pumps the
  fresh snapshot into Xray's app-db. The scrubber's
  `:rf.xray/epoch-history` sub then re-fires off the standard
  app-db-write reactive path.

  Before the `:rf/xray` frame is mounted, the callback deliberately does
  nothing. First mount seeds history from the framework ring, so records
  produced during that window are still visible. Idempotent via the
  `epoch-cb-registered?` sentinel."
  []
  (when (compare-and-set! epoch-cb-registered? false true)
    (rf.epoch/register-epoch-listener! :rf.xray/epoch-collector
      (fn [record]
        ;; Pre-mount no-op — see the docstring's §Pre-mount guard.
        ;; Resolved against the framework's frame registry (NOT a
        ;; Xray-side flag) so a teardown / re-register cycle stays
        ;; correctly tracked without our needing extra state.
        (when (rf.frame/frame defaults/default-frame-id)
          ;; Wrap the dispatch in the Xray shell frame so the registry's
          ;; handler writes to Xray's app-db, not the host's. The cb's
          ;; record carries :frame — pass it as the dispatch arg so
          ;; the handler can compare against its target-frame and
          ;; skip updates for non-target frames.
          (rf/with-frame defaults/default-frame-id
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
