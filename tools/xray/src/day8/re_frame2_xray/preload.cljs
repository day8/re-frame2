(ns day8.re-frame2-xray.preload
  "Xray preload — the entry point shadow-cljs's `:devtools/preloads`
  pulls. This namespace is the canonical install path for Xray per
  rf2-n6x4q + tools/xray/spec/000-Vision.md §Headline experiences.

  ## What loading this ns does

  1. Registers Xray's :rf.xray/* handlers (subs/events/fxs) — see
     `re-frame2-xray.registry`.
  2. Registers the trace collector callback under
     `:rf.xray/trace-collector` — see `re-frame2-xray.trace-bus`.
  3. Attaches a global Ctrl+Shift+C keydown listener — see
     `re-frame2-xray.keybinding`.
  4. Auto-opens the full shell into the host app's normal-flow
     `[data-rf-xray-host]` layout host once the substrate adapter is
     ready, unless the host configured `:rf.xray/auto-open? false`
     before adapter readiness. Missing host is reported via
     `console.error` and the inspectable Xray status API; startup is
     not blocked.

  All three are idempotent: re-loading the namespace (shadow-cljs
  `:after-load`) re-runs the side-effects but each step
  `defonce`-guards its own state. The net effect is no double-
  registration, no double-listener, no shell re-mount.

  ## Why the preload waits for adapter readiness

  The shell cannot mount synchronously at preload namespace load:
  shadow-cljs preloads run before the host calls `rf/init!`, so no
  substrate adapter is installed yet. The preload schedules a bounded
  readiness probe and mounts after the host runtime exists. Subsequent
  hide/show remains a CSS-only toggle, preserving the <80ms repaint
  target in spec/007-UX-IA.md §The default landing view.

  ## Production posture

  Loading this preload from a production build is a hard mistake —
  Xray is dev-tier per tools/README.md's bundle-isolation contract.
  But if it does happen, the trace-callback registration is a no-op
  in production (the framework's trace surface elides via
  `interop/debug-enabled?`), the keybinding listener attaches but
  finds an empty `current-adapter` when the user hits Ctrl+Shift+C,
  and the mount fails silently. The fallback is graceful, not
  catastrophic — but the right answer is to keep the preload out of
  production builds via shadow-cljs's `:dev`-only `:devtools` block."
  (:require [goog.object :as gobj]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            ;; Pull `re-frame.epoch` into the dev classpath via the
            ;; Xray preload (rf2-1barg). The Xray Time Travel panel
            ;; reads epoch records via `rf/epoch-history` +
            ;; `rf/register-epoch-listener!`; those wrappers late-bind into
            ;; `re-frame.epoch`'s seed table. When the host example
            ;; omits the artefact (the counter example does, by
            ;; design — it's the smallest reference app), the wrappers
            ;; degrade silently to `[]` / no-op and the panel sits
            ;; empty even though the user has just opened Xray
            ;; specifically to look at epoch history. Loading the
            ;; epoch artefact as part of Xray's preload anchors the
            ;; integration: every Xray-enabled build has working
            ;; time-travel without the host having to add a separate
            ;; dependency. Bundle-isolation still holds — Xray's
            ;; preload is dev-only and is excluded from production
            ;; bundles by the `:devtools/preloads` shadow-cljs gate.
            [re-frame.epoch]
            ;; rf2-qwm0a — the public-tooling listener surface
            ;; (`register-listener!` etc.) lives in
            ;; `re-frame.trace.tooling` for production-DCE reasons.
            ;; Xray's trace-collector registration below targets the
            ;; tooling sibling directly. The preload is dev-only
            ;; (gated by shadow-cljs `:devtools/preloads`) so this
            ;; require is excluded from production bundles.
            [re-frame.trace.tooling :as trace-tooling]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.keybinding :as keybinding]
            [day8.re-frame2-xray.mount :as mount]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.settings.effects :as settings-effects]
            ;; rf2-8xzoe.4 (F-4) — pull the Xray-runtime accessor
            ;; namespace into the preload classpath. The `:require` is the
            ;; load: `day8.re-frame2-xray.runtime` installs its
            ;; `js/globalThis.__day8_re_frame2_xray_runtime` sentinel as
            ;; a top-level side effect (gated on `interop/debug-enabled?`).
            ;; Any attached MCP server (re-frame2-pair-mcp today) reads
            ;; that sentinel as its preload probe; the runtime rides
            ;; Xray-the-panel's preload, so no separate `:preloads`
            ;; entry is required on the consumer side.
            [day8.re-frame2-xray.runtime]
            [day8.re-frame2-xray.trace-bus :as trace-bus]))

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
  ;; `rf/register-epoch-listener!` into Xray's app-db so the scrubber's
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
                                      trace-bus/collect-trace!))
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
  (`rf/register-epoch-listener!` is itself a no-op in that case)."
  []
  (when (compare-and-set! epoch-cb-registered? false true)
    (rf/register-epoch-listener! :rf.xray/epoch-collector
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
  "Reset the preload's idempotency sentinels so test fixtures can drive
  multiple load cycles. Test-only — never call from production code."
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

;; ---- side-effecting boot -------------------------------------------------

;; Loading this namespace runs the foundation's three side effects.
;; Idempotency: each side-effect is self-guarded (see keybinding/
;; attach!, registry/register-xray-handlers!, the trace-cb sentinel
;; above), so the load order is `:after-load`-safe.
;;
;; The whole block is gated on `interop/debug-enabled?` so production
;; bundles compiled with `(set! goog.DEBUG false)` strip the entire
;; preload's side-effects via Closure DCE. The keybinding listener,
;; the trace-callback registration, and the Xray registry all elide
;; together — production builds carry zero Xray runtime cost beyond
;; the unused require chain (which itself is candidates for further
;; tree-shaking).

(when interop/debug-enabled?
  ;; Settings persistence (rf2-9poxq) — load BEFORE registry install
  ;; so the first sub read from the popup's events lands on the
  ;; persisted values, not on the defaults.
  (config/load-settings-from-storage!)
  (registry/register-xray-handlers!)
  (register-trace-collector!)
  (register-epoch-collector!)
  (install-browser-api-exports!)
  (keybinding/attach!)
  ;; Apply the persisted CSS-var + theme-class effects. The shell
  ;; root may not exist yet (auto-open is async) — apply-all! no-ops
  ;; on a missing root; the events handler re-applies on every
  ;; subsequent update.
  (settings-effects/apply-all!)
  ;; Auto-open-on-error watcher (rf2-9poxq) — NOT installed here.
  ;; The watcher subscribes to `:rf.xray/issues-ribbon`, a sub that
  ;; reads from `:rf/xray`'s app-db; but `:rf/xray` is
  ;; lazy-registered by `mount/ensure-xray-frame!` on first open
  ;; (see mount.cljs §Why here, not at preload time). Eagerly
  ;; subscribing here returned nil and `(add-watch nil ...)` threw
  ;; `No protocol method IWatchable.-add-watch defined for type
  ;; null` in test runtimes that never opened Xray (Story
  ;; testbeds). The install is now driven from two correctness-
  ;; safe hooks:
  ;;   1. `mount/ensure-xray-frame!` — when Xray first opens, if
  ;;      the persisted setting is on, install (covers the user's
  ;;      `:auto-open-on-error? true` round-trip across reloads).
  ;;   2. `:rf.xray/settings-update` — on flip-on, install; on
  ;;      flip-off, detach (covers the runtime toggle).
  ;; Both paths are idempotent via the `auto-open-watcher` atom.
  (mount/auto-open-inline!))
