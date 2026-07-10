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
  (:require [re-frame.interop :as interop]
            ;; Pull `re-frame.epoch` into the dev classpath via the
            ;; Xray preload. The Xray Time Travel panel
            ;; reads epoch records via `rf/epoch-history` and its
            ;; epoch-collector attaches via the epoch home verb
            ;; `re-frame.epoch/register-epoch-listener!` (per the Tool-Pair
            ;; DCE tier rule; see install.cljs). When the host example
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
            [day8.re-frame2-xray.config :as config]
            ;; The inert, callable install helpers (trace/epoch
            ;; collector registration + browser-API exports) live in
            ;; `day8.re-frame2-xray.install`, a namespace whose LOAD
            ;; performs no side-effects. The preload's side-effecting
            ;; boot block below calls them; the manual facade (`core`)
            ;; requires `install` directly, so requiring the facade
            ;; does not drag in this preload's load-time side-effects.
            [day8.re-frame2-xray.install :as install]
            [day8.re-frame2-xray.keybinding :as keybinding]
            [day8.re-frame2-xray.mount :as mount]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.settings.effects :as settings-effects]
            ;; Pull the Xray-runtime accessor namespace into the
            ;; preload classpath. The `:require` is the
            ;; load: `day8.re-frame2-xray.runtime` installs its
            ;; `js/globalThis.__day8_re_frame2_xray_runtime` sentinel as
            ;; a top-level side effect (gated on `interop/debug-enabled?`).
            ;; Any attached MCP server (re-frame2-pair-mcp today) reads
            ;; that sentinel as its preload probe; the runtime rides
            ;; Xray-the-panel's preload, so no separate `:preloads`
            ;; entry is required on the consumer side.
            [day8.re-frame2-xray.runtime]))

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
  ;; Settings persistence — load BEFORE registry install
  ;; so the first sub read from the popup's events lands on the
  ;; persisted values, not on the defaults.
  (config/load-settings-from-storage!)
  (registry/register-xray-handlers!)
  (install/register-trace-collector!)
  (install/register-epoch-collector!)
  (install/install-browser-api-exports!)
  (keybinding/attach!)
  ;; Apply the persisted CSS-var + theme-class effects. The shell
  ;; root may not exist yet (auto-open is async) — apply-all! no-ops
  ;; on a missing root; the events handler re-applies on every
  ;; subsequent update.
  (settings-effects/apply-all!)
  ;; Auto-open-on-error watcher — NOT installed here.
  ;; The watcher subscribes to `:rf.xray/issues-ribbon`, a sub that
  ;; reads from `:rf/xray`'s app-db; but `:rf/xray` is
  ;; lazy-registered by `mount/ensure-xray-frame!` on first open
  ;; (see mount.cljs §Why here, not at preload time). Subscribing
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
  (mount/auto-open-inline!))
