(ns day8.re-frame2-causa.settings.persistence-cljs-test
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
            [day8.re-frame2-causa.config :as config]))

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
    (is (= :dark  (config/get-setting :theme nil)))))

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
  (config/update-setting! :general :panel-position :popout)
  (is (= :popout (config/get-setting :general :panel-position)))
  (reset! config/settings config/default-settings)
  (config/load-settings-from-storage!)
  (is (= :popout (config/get-setting :general :panel-position))))

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
  (config/update-setting! :theme nil :light)
  (config/reset-settings!)
  (is (= 13 (config/get-setting :general :text-size)))
  (is (= :dark (config/get-setting :theme nil)))
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
  (config/configure! {:rf.causa/settings {:general   {:text-size 15
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
  (config/configure! {:rf.causa/settings {:general {:text-size 11}}})
  (is (= 11 (config/get-setting :general :text-size)))
  ;; Other general slots keep their defaults
  (is (= :right-rail (config/get-setting :general :panel-position)))
  (is (= false (config/get-setting :general :auto-open-on-error?)))
  (is (= :dark (config/get-setting :theme nil))))
