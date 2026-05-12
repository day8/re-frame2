(ns day8.re-frame2-causa.preload
  "Causa preload — the entry point shadow-cljs's `:devtools/preloads`
  pulls. This namespace is the canonical install path for Causa per
  rf2-n6x4q + tools/causa/spec/000-Vision.md §Headline experiences.

  ## What loading this ns does

  1. Registers Causa's :rf.causa/* handlers (subs/events/fxs) — see
     `re-frame2-causa.registry`.
  2. Registers the trace collector callback under
     `:rf.causa/trace-collector` — see `re-frame2-causa.trace-bus`.
  3. Attaches a global Ctrl+Shift+C keydown listener — see
     `re-frame2-causa.keybinding`.

  All three are idempotent: re-loading the namespace (shadow-cljs
  `:after-load`) re-runs the side-effects but each step
  `defonce`-guards its own state. The net effect is no double-
  registration, no double-listener, no shell re-mount.

  ## Why the preload doesn't mount the shell

  The shell mounts on first Ctrl+Shift+C, not at preload time. Per
  spec/007-UX-IA.md §The default landing view the first paint is
  <80ms because the substrate render runs lazily — we don't pay the
  React-tree construction cost until the user opens Causa.

  ## Production posture

  Loading this preload from a production build is a hard mistake —
  Causa is dev-tier per tools/README.md's bundle-isolation contract.
  But if it does happen, the trace-callback registration is a no-op
  in production (the framework's trace surface elides via
  `interop/debug-enabled?`), the keybinding listener attaches but
  finds an empty `current-adapter` when the user hits Ctrl+Shift+C,
  and the mount fails silently. The fallback is graceful, not
  catastrophic — but the right answer is to keep the preload out of
  production builds via shadow-cljs's `:dev`-only `:devtools` block."
  (:require [re-frame.core :as rf]
            [re-frame.interop :as interop]
            [day8.re-frame2-causa.keybinding :as keybinding]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.trace-bus :as trace-bus]))

;; ---- registrations -------------------------------------------------------

(defonce ^:private trace-cb-registered?
  ;; Idempotency sentinel for the trace-callback registration. Cf.
  ;; `re-frame.trace/register-trace-cb!`: passing the same id twice
  ;; replaces the callback. The replacement is harmless but emits a
  ;; warning trace on every reload that pollutes the dev console;
  ;; this sentinel suppresses re-registration on `:after-load`.
  (atom false))

(defn register-trace-collector!
  "Register Causa's trace-collector callback under
  `:rf.causa/trace-collector`. Idempotent via the
  `trace-cb-registered?` sentinel — a second call is a silent no-op
  (no warning trace, no replacement). Public so tests can drive
  it directly without `#'`-piercing into private vars."
  []
  (when (compare-and-set! trace-cb-registered? false true)
    (rf/register-trace-cb! :rf.causa/trace-collector
                           trace-bus/collect-trace!))
  nil)

(defn reset-for-test!
  "Reset the preload's idempotency sentinel so test fixtures can drive
  multiple load cycles. Test-only — never call from production code."
  []
  (reset! trace-cb-registered? false)
  nil)

;; ---- side-effecting boot -------------------------------------------------

;; Loading this namespace runs the foundation's three side effects.
;; Idempotency: each side-effect is self-guarded (see keybinding/
;; attach!, registry/register-causa-handlers!, the trace-cb sentinel
;; above), so the load order is `:after-load`-safe.
;;
;; The whole block is gated on `interop/debug-enabled?` so production
;; bundles compiled with `(set! goog.DEBUG false)` strip the entire
;; preload's side-effects via Closure DCE. The keybinding listener,
;; the trace-callback registration, and the Causa registry all elide
;; together — production builds carry zero Causa runtime cost beyond
;; the unused require chain (which itself is candidates for further
;; tree-shaking).

(when interop/debug-enabled?
  (registry/register-causa-handlers!)
  (register-trace-collector!)
  (keybinding/attach!))
