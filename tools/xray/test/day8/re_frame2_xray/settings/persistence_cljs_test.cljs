(ns day8.re-frame2-xray.settings.persistence-cljs-test
  "CLJS tests for the Settings popup's localStorage round-trip
  (rf2-9poxq).

  Asserts:
  - Each setting writes through to localStorage
  - `load-settings-from-storage!` reads persisted values back in
  - `reset-settings!` clears both atom + localStorage
  - Malformed payloads degrade silently to defaults
  - `configure! :settings` bulk-replace round-trips

  Drives the in-memory atom + the localStorage shim directly so the
  test stays substrate-independent."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [cljs.reader]
            [day8.re-frame2-xray.config :as config]))

;; ---- fixtures ----------------------------------------------------------

(use-fixtures :each
  {:before (fn [] (config/reset-settings!))
   :after  (fn [] (config/reset-settings!))})

(defn- storage-payload
  "Reach into the same storage shim the config ns writes through. Under
  Node tests the shim degrades to an in-process atom; under a real
  browser the shim hits `window.localStorage` — same code path either
  way so the round-trip assertions are runtime-independent."
  []
  (#'config/storage-get config/settings-storage-key))

;; ---- defaults ----------------------------------------------------------

(deftest defaults-match-spec
  (testing "default settings match the bead's locked decisions"
    (is (= 13     (config/get-setting :general :text-size)))
    (is (= :right-rail
              (config/get-setting :general :panel-position)))
    (is (= false  (config/get-setting :general :auto-open-on-error?)))
    ;; rf2-3f2di B2 — theme default flipped dark → light to match the
    ;; authoritative reference's light-by-default render.
    (is (= :light (config/get-setting :theme nil)))
    ;; rf2-3zyyx — epoch-history default matches the substrate's
    ;; `re-frame.epoch.state/default-depth` so a fresh install carries
    ;; the same ring depth Xray observed before the knob was surfaced.
    (is (= 50 (config/get-setting :general :epoch-history)))))

;; ---- per-setting round-trip --------------------------------------------

(deftest text-size-round-trips
  (config/update-setting! :general :text-size 16)
  (is (= 16 (config/get-setting :general :text-size)))
  (is (some? (storage-payload)) "localStorage payload populated")
  ;; Reset in-memory atom then reload — should pick up 16, not 13.
  (reset! config/settings config/default-settings)
  (is (= 13 (config/get-setting :general :text-size))
      "atom-only reset returns to default")
  (config/load-settings-from-storage!)
  (is (= 16 (config/get-setting :general :text-size))
      "reload from localStorage restores 16"))

(deftest panel-position-round-trips
  ;; rf2-czcg5 — `:popout` was dropped as a panel-position (pop-out now
  ;; launches from the chrome ⛶ button). `:fullscreen` is the remaining
  ;; non-default position; round-trip it through localStorage.
  (config/update-setting! :general :panel-position :fullscreen)
  (is (= :fullscreen (config/get-setting :general :panel-position)))
  (reset! config/settings config/default-settings)
  (config/load-settings-from-storage!)
  (is (= :fullscreen (config/get-setting :general :panel-position))))

(deftest auto-open-on-error-round-trips
  (config/update-setting! :general :auto-open-on-error? true)
  (is (true? (config/get-setting :general :auto-open-on-error?)))
  (reset! config/settings config/default-settings)
  (config/load-settings-from-storage!)
  (is (true? (config/get-setting :general :auto-open-on-error?))))

(deftest theme-round-trips
  (config/update-setting! :theme nil :light)
  (is (= :light (config/get-setting :theme nil)))
  (reset! config/settings config/default-settings)
  (config/load-settings-from-storage!)
  (is (= :light (config/get-setting :theme nil))))

(deftest epoch-history-round-trips
  ;; rf2-3zyyx — the Epoch history slider persists the depth through
  ;; the same localStorage path every other :general knob uses; on
  ;; reload the substrate cap is restored via `apply-epoch-history!`
  ;; (separate test in effects_cljs_test).
  (config/update-setting! :general :epoch-history 200)
  (is (= 200 (config/get-setting :general :epoch-history)))
  (reset! config/settings config/default-settings)
  (is (= 50 (config/get-setting :general :epoch-history))
      "atom-only reset returns to default")
  (config/load-settings-from-storage!)
  (is (= 200 (config/get-setting :general :epoch-history))
      "reload from localStorage restores 200"))

(deftest legacy-telemetry-key-is-silently-dropped
  ;; rf2-jh9ws: settings persisted from prior sessions with a
  ;; `:telemetry` key (the section was removed because no telemetry
  ;; endpoint exists) must not break load. The per-section merge in
  ;; `load-settings-from-storage!` only knows the surviving slots, so
  ;; the legacy key falls on the floor without throwing.
  (#'config/storage-set! config/settings-storage-key
                         (pr-str {:general   {:text-size 15}
                                  :theme     :light
                                  :telemetry {:opt-in? true}}))
  (reset! config/settings config/default-settings)
  (config/load-settings-from-storage!)
  (is (= 15 (config/get-setting :general :text-size))
      "known slots load cleanly")
  (is (= :light (config/get-setting :theme nil))
      "known slots load cleanly")
  (is (nil? (:telemetry @config/settings))
      "legacy :telemetry key is silently dropped"))

;; ---- reset ------------------------------------------------------------

(deftest reset-clears-everything
  (config/update-setting! :general :text-size 18)
  ;; rf2-3f2di B2 — flip away from the new `:light` default so reset has
  ;; a non-default value to revert.
  (config/update-setting! :theme nil :dark)
  (config/reset-settings!)
  (is (= 13 (config/get-setting :general :text-size)))
  (is (= :light (config/get-setting :theme nil)))
  (is (nil? (storage-payload))
      "localStorage payload cleared on reset"))

;; ---- robustness --------------------------------------------------------

(deftest malformed-payload-degrades-to-defaults
  (#'config/storage-set! config/settings-storage-key
                         "this is not valid edn { {{")
  (reset! config/settings config/default-settings)
  (config/load-settings-from-storage!)
  ;; No throw; atom remains at defaults.
  (is (= 13 (config/get-setting :general :text-size))))

(deftest unknown-section-update-is-rejected
  (config/update-setting! :totally-unknown :slot 99)
  (is (= 13 (config/get-setting :general :text-size))
      "unknown section is a no-op — defaults remain intact"))

;; ---- bulk configure! :settings ----------------------------------------

(deftest configure-settings-bulk-replaces
  ;; rf2-jh9ws: legacy `:telemetry` key in the bulk-config map is
  ;; silently dropped — known slots round-trip; unknown slots fall
  ;; on the floor.
  (config/configure! {:rf.xray/settings {:general   {:text-size 15
                                                      :panel-position :fullscreen
                                                      :auto-open-on-error? true}
                                          :theme     :light
                                          :telemetry {:opt-in? true}}})
  (is (= 15 (config/get-setting :general :text-size)))
  (is (= :fullscreen (config/get-setting :general :panel-position)))
  (is (true? (config/get-setting :general :auto-open-on-error?)))
  (is (= :light (config/get-setting :theme nil)))
  (is (nil? (:telemetry @config/settings))
      "legacy :telemetry key dropped by per-section merge")
  (is (some? (storage-payload))
      "bulk configure round-trips to localStorage"))

(deftest configure-settings-partial-merges-with-defaults
  (config/configure! {:rf.xray/settings {:general {:text-size 11}}})
  (is (= 11 (config/get-setting :general :text-size)))
  ;; Other general slots keep their defaults
  (is (= :right-rail (config/get-setting :general :panel-position)))
  (is (= false (config/get-setting :general :auto-open-on-error?)))
  ;; rf2-3f2di B2 — the theme default is now `:light`.
  (is (= :light (config/get-setting :theme nil))))

;; ---- configure! vs persisted Settings merge order (rf2-rr2yw3) --------
;;
;; Per spec/015-Configuration.md §`configure!` vs `init!` vs persisted
;; Settings: `hardcoded defaults < configure! overrides < persisted
;; Settings overrides`. The documented boot order is host `configure!`
;; THEN the preload's `load-settings-from-storage!`. Before the fix,
;; `configure! {:rf.xray/settings ...}` unconditionally `reset!`ed AND
;; persisted the bulk-config map, so a host calling `configure!` on
;; EVERY boot (the documented pattern) permanently overwrote whatever
;; the user had mutated via the Settings popup on the previous session
;; — the very next `load-settings-from-storage!` call read back
;; exactly what `configure!` had just clobbered localStorage with.

(deftest configure-settings-does-not-clobber-persisted-user-mutation
  (testing "rf2-rr2yw3 — a host that calls `configure!
            {:rf.xray/settings ...}` on every boot must not permanently
            overwrite a user's ALREADY-persisted Settings-popup
            mutation. Reproduces the full two-boot sequence in the
            documented order: configure! → user popup edit (persists)
            → [reload] → configure! again → load-settings-from-
            storage!."
    ;; Boot 1: host configures a default; user tweaks the setting via
    ;; the popup, which persists through `update-setting!`.
    (config/configure! {:rf.xray/settings {:general {:text-size 15}}})
    (config/update-setting! :general :text-size 20)
    (is (= 20 (config/get-setting :general :text-size))
        "precondition: the user's popup edit is live")
    ;; Boot 2 (page reload): the host calls `configure!` again with the
    ;; SAME default, then the preload runs
    ;; `load-settings-from-storage!` — the documented order.
    (config/configure! {:rf.xray/settings {:general {:text-size 15}}})
    (config/load-settings-from-storage!)
    (is (= 20 (config/get-setting :general :text-size))
        "the user's persisted 20 survives a second `configure!` call —
         configure!'s 15 does NOT clobber it")))

(deftest configure-settings-fresh-install-still-seeds-storage
  (testing "rf2-rr2yw3 — on a genuinely fresh install (no persisted
            payload yet) `configure!`'s posture STILL persists
            immediately, so it survives a reload even with no further
            `configure!` call in that later session (spec/015 §606's
            original 'persists immediately' guarantee, now scoped to
            the empty-storage case only)"
    (config/configure! {:rf.xray/settings {:general {:text-size 17}}})
    (is (some? (storage-payload))
        "a fresh install's configure! call still writes through")
    ;; Reset the in-memory atom only (simulate a reload with no host
    ;; configure! call this time) and reload from storage.
    (reset! config/settings config/default-settings)
    (config/load-settings-from-storage!)
    (is (= 17 (config/get-setting :general :text-size))
        "the host's posture survives the reload via the fresh-install
         persist, with no second configure! call needed")))
