(ns day8.re-frame2-xray.test-support
  "Single test-fixture entry-point for Xray's process-global reset.

  Previously every test fixture called both `preload/reset-for-test!`
  and `registry/reset-for-test!` — adding a new sentinel-bearing ns
  meant updating ~30 fixtures. This ns folds those calls into one
  helper so panel additions don't ripple through the test corpus.

  Targets the inert `day8.re-frame2-xray.install` ns for the collector
  sentinels (rf2-5w06uu) rather than `preload`, so a fixture that only
  needs the sentinel reset does not drag in `preload`'s side-effecting
  boot block.

  ## The three reset surfaces (rf2-sdqsla)

  A fixture has up to three process-global surfaces to clear:

    1. install / registry idempotency SENTINELS — `reset-sentinels!`.
       Safe at any point; just flips the `defonce` re-install flags so
       a re-`register-xray-handlers!` actually re-runs.
    2. the trace-collector RINGS — `reset-all!` adds this. The rings
       are process-global `defonce` atoms the sentinel reset does NOT
       touch, so a fixture that forgot the (historically separate)
       `trace-collector/reset-for-test!` leaked trace rows into the
       next test (order-dependent bleed).
    3. settings / localStorage STATE — `reset-runtime!` adds this on
       top.

  Use the SMALLEST one that fits: `reset-sentinels!` for a fixture that
  resets MID-setup (host frame already registered — clearing the rings
  there would wipe the live frame's recording config), `reset-all!`
  for a clean-slate fixture that resets BEFORE registering frames, and
  `reset-runtime!` when the persisted settings also need wiping.

  **Test-only — never call from production code.** Per rf2-kmhvg
  cluster item 3e (audit rf2-i0veg §3e)."
  (:require [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.install :as install]
            [day8.re-frame2-xray.mount :as mount]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.trace-collector :as trace-collector]
            ;; rf2-e8330v (xxo3zz F3) — per-panel test-override seams.
            ;; Production registration installs NO `-for-test` ids; tests
            ;; opt into the override surface via `install-test-overrides!`.
            [day8.re-frame2-xray.panels.derivation-graph :as derivation-graph]
            [day8.re-frame2-xray.panels.machine-after-rings :as after-rings]
            [day8.re-frame2-xray.panels.machine-inspector :as machine-inspector]
            [day8.re-frame2-xray.panels.resources :as resources]
            [day8.re-frame2-xray.panels.routing :as routing]
            [day8.re-frame2-xray.static.flows.panel :as static-flows-panel]
            [day8.re-frame2-xray.static.interceptors.panel :as static-interceptors-panel]
            [day8.re-frame2-xray.static.machines.panel :as static-machines-panel]
            [day8.re-frame2-xray.static.schemas.panel :as static-schemas-panel]))

(defn reset-sentinels!
  "Reset ONLY the install + registry + mount idempotency sentinels, in
  dependency order:

      install/reset-for-test!  ; trace-cb + epoch-cb reg flags
      registry/reset-for-test! ; per-panel reg-event/reg-sub flags
      mount/reset-for-test!    ; ensure-xray-frame! run-once guard (rf2-n4p5it)

  Does NOT touch the trace-collector rings or the settings atom — safe
  to call MID-setup (after frames are registered) because it only flips
  re-install flags. Test-only. Returns nil."
  []
  (install/reset-for-test!)
  (registry/reset-for-test!)
  (mount/reset-for-test!)
  nil)

(defn reset-all!
  "`reset-sentinels!` PLUS the trace-collector rings
  (`trace-collector/reset-for-test!`). The rings are process-global
  `defonce` atoms the sentinel reset does NOT touch — clearing them
  here closes the cross-test trace-bleed gap (rf2-sdqsla).

  Call at the START of a fixture, BEFORE registering frames — clearing
  the rings wipes the per-frame recording config, so a mid-setup call
  (host frame already registered) must use `reset-sentinels!` instead.

  Does NOT touch the settings atom — use `reset-runtime!` for that.
  Test-only. Returns nil."
  []
  (reset-sentinels!)
  (trace-collector/reset-for-test!)
  nil)

(defn reset-runtime!
  "`reset-all!` PLUS the persisted Settings atom + localStorage payload
  (`config/reset-settings!`). The full clean-slate a runtime-mounting
  fixture wants: process-global sentinels, trace rings, AND config /
  storage state. Test-only. Returns nil."
  []
  (reset-all!)
  (config/reset-settings!)
  nil)

;; ---- test-only override seam orchestrator (rf2-e8330v / xxo3zz F3) -------

(defn install-test-overrides!
  "Install EVERY panel's test-only override seam in one call.

  Production `register-xray-handlers!` installs NO ids ending in
  `-for-test` and no `(or override …)` branches in the data subs — the
  override seam is split out per panel (xxo3zz F3 / rf2-e8330v). A test
  that drives panel data via the `:rf.xray/set-*-override-for-test`
  events (or the machine-inspector `set-epoch-history-for-test` /
  `set-focus-epoch-id-for-test` seeding events) calls this AFTER
  `register-xray-handlers!` to:

    1. register the `:rf.xray/set-*-override-for-test` events + companion
       `*-override` subs, and
    2. RE-register the affected production data subs to layer the
       override read on top (`(or override (real …))`).

  Idempotent (re-frame's registrar replaces in place). Order-independent
  — re-frame resolves `:<-` chains lazily. **Test-only — never call from
  production code.** Returns nil."
  []
  (routing/install-test-overrides!)
  (resources/install-test-overrides!)
  (derivation-graph/install-test-overrides!)
  (after-rings/install-test-overrides!)
  (machine-inspector/install-test-overrides!)
  (static-machines-panel/install-test-overrides!)
  (static-flows-panel/install-test-overrides!)
  (static-interceptors-panel/install-test-overrides!)
  (static-schemas-panel/install-test-overrides!)
  nil)
