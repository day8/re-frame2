(ns day8.re-frame2-xray.preload
  "Xray preload — the entry point shadow-cljs's `:devtools/preloads`
  pulls. This namespace is the canonical install path for Xray per
  tools/xray/spec/000-Vision.md §Headline experiences.

  ## What loading this ns does

  1. Registers Xray's :rf.xray/* handlers (subs/events/fxs) — see
     `re-frame2-xray.registry`.
  2. Registers the trace collector callback under
     `:rf.xray/trace-collector` — see `re-frame2-xray.trace-collector`.
  3. Attaches a global Ctrl+Shift+C keydown listener — see
     `re-frame2-xray.keybinding`.
  4. Once the substrate adapter is ready, SEATS the `:rf/xray` frame and —
     unless the host configured `:rf.xray/auto-open? false` — auto-opens the
     full shell into the host app's normal-flow `[data-rf-xray-host]` layout
     host. The seat is unconditional: it is what makes the `:rf.xray/*`
     instruction set registered in step 1 actually WRITABLE, so a host can
     dispatch into Xray (`set-target-frame!`, `focus!`) without ever opening
     the shell (rf2-avi7). Missing host is reported via `console.error` and
     the inspectable Xray status API; startup is not blocked.

  All four are idempotent: re-loading the namespace (shadow-cljs
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
  `rf.interop/debug-enabled?`). The right contract is to keep this preload
  in shadow-cljs's dev-only `:devtools` block; the remaining guards make
  an accidental production load inert rather than useful."
  (:require [re-frame.interop :as rf.interop]
            ;; Time Travel requires the epoch listener and history API;
            ;; deps.edn makes the artefact part of every Xray build.
            [re-frame.epoch]
            [day8.re-frame2-xray.config :as config]
            ;; Shared installation functions are load-inert; only this
            ;; preload's boot block and explicit `core/init!` invoke them.
            [day8.re-frame2-xray.install :as install]
            [day8.re-frame2-xray.keybinding :as keybinding]
            [day8.re-frame2-xray.mount :as mount]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.settings.effects :as settings-effects]))

;; ---- install-helper re-exports -------------------------------------------
;;
;; The callable install primitives live in
;; `day8.re-frame2-xray.install` (an inert-on-load ns). Re-export them
;; here as `def`-aliases so `preload/<helper>` call sites — the Xray
;; test corpus, `test_support/reset-all!`, embed-host tooling — resolve
;; against the same fn values. Identity holds:
;; `(= install/register-trace-collector! preload/register-trace-collector!)`.

(def register-trace-collector!
  "See `day8.re-frame2-xray.install/register-trace-collector!`."
  install/register-trace-collector!)

(def register-epoch-collector!
  "See `day8.re-frame2-xray.install/register-epoch-collector!`."
  install/register-epoch-collector!)

(def install-browser-api-exports!
  "See `day8.re-frame2-xray.install/install-browser-api-exports!`."
  install/install-browser-api-exports!)

(def reset-for-test!
  "See `day8.re-frame2-xray.install/reset-for-test!`. Test-only."
  install/reset-for-test!)

;; ---- side-effecting boot -------------------------------------------------

;; Loading this namespace runs the installation sequence. Each operation
;; is self-guarded, so reloads do not duplicate listeners or mounts.
;;
;; The whole block is gated on `rf.interop/debug-enabled?` so production
;; bundles compiled with `(set! goog.DEBUG false)` strip the entire
;; preload's side-effects via Closure DCE. The keybinding listener,
;; collectors, registry, settings effects, and auto-open all elide together.

(when rf.interop/debug-enabled?
  ;; Settings persistence — load BEFORE registry install
  ;; so the first sub read from the popup's events lands on the
  ;; persisted values, not on the defaults.
  (config/load-settings-from-storage!)
  (registry/register-xray-handlers!)
  (install/register-trace-collector!)
  (install/register-epoch-collector!)
  ;; No view-substrate evidence step here, and none is missing (rf2-l86mm).
  ;; Two predecessors sat in exactly this position. The first claimed the
  ;; donor `re-frame.ui` evidence projection under a stable owner identity,
  ;; because that tier had a single-owner registry a second tool could hold.
  ;; The second was no step at all — `re-frame.freehand.tool` had no registry
  ;; to claim, so the Views panel's subs read it directly. Both are gone with
  ;; the substrates they read. The Hicasso tab's door is a reader on the same
  ;; terms and acquires nothing from here either.
  (install/install-browser-api-exports!)
  (keybinding/attach!)
  ;; Apply the persisted CSS-var + theme-class effects. The shell
  ;; root may not exist yet (auto-open is async) — apply-all! no-ops
  ;; on a missing root; the events handler re-applies on every
  ;; subsequent update.
  (settings-effects/apply-all!)
  ;; Auto-open-on-error watcher — NOT installed here.
  ;; The watcher subscribes to `:rf.xray/issues-ribbon`, a sub that
  ;; reads from `:rf/xray`'s app-db; but `:rf/xray` is seated only once
  ;; the host's `rf/init!` has installed a substrate adapter, which is
  ;; strictly later than this load-time block (see `mount/boot-on-runtime-
  ;; ready!` §Why the frame is seated here). Subscribing
  ;; here would return nil, and `(add-watch nil ...)` throws
  ;; `No protocol method IWatchable.-add-watch defined for type
  ;; null` in test runtimes that never open Xray (Story testbeds).
  ;; The install is driven from two correctness-safe hooks:
  ;;   1. `mount/ensure-xray-frame!` — when Xray first opens, if
  ;;      the persisted setting is on, install (covers the user's
  ;;      `:auto-open-on-error? true` round-trip across reloads).
  ;;   2. `:rf.xray/settings-update` — on flip-on, install; on
  ;;      flip-off, detach (covers the runtime toggle).
  ;; Both paths are idempotent via the `auto-open-watcher` atom.
  (mount/boot-on-runtime-ready!))
