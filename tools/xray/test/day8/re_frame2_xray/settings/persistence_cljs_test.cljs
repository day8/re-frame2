(ns day8.re-frame2-xray.settings.persistence-cljs-test
  "CLJS tests for the Settings popup's localStorage round-trip
  (rf2-9poxq).

  Asserts:
  - Each setting writes through to localStorage
  - `load-settings-from-storage!` reads persisted values back in
  - `reset-settings!` clears both atom + localStorage
  - Malformed payloads degrade silently to defaults
  - `configure! :settings` seeds the live map and NEVER writes storage
  - The payload is a sparse overlay of explicit overrides, never the
    resolved map (rf2-3x7nj.27.1)

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

(defn- reload!
  "Simulate a page reload: discard everything a reload discards — the
  live settings atom AND the `configure!` seed — and keep only storage.
  Resetting the atom alone is NOT a reload: the seed survives in
  process, so a value read back afterwards can come from the seed and
  say nothing about storage (rf2-3x7nj.27.1)."
  []
  (let [payload (storage-payload)]
    (config/reset-settings!)
    (when payload
      (#'config/storage-set! config/settings-storage-key payload))))

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
  (is (nil? (storage-payload))
      "configure! never writes storage — the seed is re-applied on every
       boot instead (rf2-3x7nj.27.1)"))

(deftest configure-settings-partial-merges-with-defaults
  (config/configure! {:rf.xray/settings {:general {:text-size 11}}})
  (is (= 11 (config/get-setting :general :text-size)))
  ;; Other general slots keep their defaults
  (is (= :right-rail (config/get-setting :general :panel-position)))
  (is (= false (config/get-setting :general :auto-open-on-error?)))
  ;; rf2-3f2di B2 — the theme default is now `:light`.
  (is (= :light (config/get-setting :theme nil))))

;; ---- configure! vs persisted Settings merge order -----------------------
;; ---- (rf2-rr2yw3, rf2-y8doi.17) -----------------------------------------
;;
;; Per spec/015-Configuration.md §`configure!` vs `init!` vs persisted
;; Settings: `hardcoded defaults < configure! overrides < persisted
;; Settings overrides`.
;;
;; BOTH ORDERS ARE PINNED BELOW, and the FIRST of the two is the one
;; production actually takes. rf2-rr2yw3 fixed the case where the host's
;; `configure!` lands first and the preload's
;; `load-settings-from-storage!` second, by seeding
;; `configured-settings-seed` for the load to merge the persisted payload
;; over. But on the documented `:devtools/preloads` install path the host
;; CANNOT get in first: shadow-cljs loads preloads ahead of the app's
;; `:init-fn`, and `preload.cljs`'s load-time block calls
;; `load-settings-from-storage!` right there — so the real sequence is
;; LOAD, then `configure!`, and `configure!`'s unconditional `reset!`
;; landed squarely on top of the user's persisted values, delivering the
;; documented order INVERTED on the only path a shipped host takes
;; (rf2-y8doi.17). `configure!` now recomputes the same three-layer merge
;; instead of `reset!`-ing its own seed, which is what makes the two
;; orders agree.

(deftest preload-order-persisted-wins-over-later-configure
  (testing "rf2-y8doi.17 — TRUE preload order. The preload's
            `load-settings-from-storage!` runs FIRST (it is a load-time
            side-effect of the preload namespace) and the host's
            `configure!` SECOND (it runs in the app's `:init-fn`). The
            user's persisted value must still win, and the host's seed
            must still supply the keys the user never persisted."
    ;; A PARTIAL persisted payload — the user has only ever changed the
    ;; text size. Since rf2-3x7nj.27.1 that is the only shape storage
    ;; holds: a sparse overlay of the paths the user wrote.
    (#'config/storage-set! config/settings-storage-key
                           (pr-str {:general {:text-size 20}}))
    ;; 1. The preload's load-time block.
    (config/load-settings-from-storage!)
    (is (= 20 (config/get-setting :general :text-size))
        "precondition: the preload loaded the user's persisted value")
    ;; 2. The host's `:init-fn` — strictly later than the preload.
    (config/configure!
      {:rf.xray/settings {:general {:text-size      15
                                    :panel-position :fullscreen}}})
    (is (= 20 (config/get-setting :general :text-size))
        "the user's persisted 20 still wins over the host's 15, even
         though `configure!` ran AFTER the load")
    (is (= :fullscreen (config/get-setting :general :panel-position))
        "the host's seed still supplies a key the user never persisted")
    (is (= false (config/get-setting :general :auto-open-on-error?))
        "a key neither layer names keeps the compiled-in default")))

(deftest preload-order-leaves-the-persisted-payload-intact
  (testing "rf2-y8doi.17 — and `configure!` arriving after the load must
            not REWRITE storage either: a reload that runs only the
            preload's load (no host `configure!` that session) must
            still find the user's own value."
    (#'config/storage-set! config/settings-storage-key
                           (pr-str {:general {:text-size 20}}))
    (config/load-settings-from-storage!)
    (config/configure! {:rf.xray/settings {:general {:text-size 15}}})
    ;; Next session: in-memory atom fresh, seed cleared, storage only.
    (config/reset-settings!)
    (is (nil? (storage-payload))
        "precondition: reset-settings! cleared storage AND the seed")
    (#'config/storage-set! config/settings-storage-key
                           (pr-str {:general {:text-size 20}}))
    (config/load-settings-from-storage!)
    (is (= 20 (config/get-setting :general :text-size))
        "the user's persisted value survived the earlier `configure!`")))

(deftest configure-settings-does-not-clobber-persisted-user-mutation
  (testing "rf2-rr2yw3 — the OTHER order, which a host reaches by
            ordering its own preload ahead of Xray's: a host that calls
            `configure! {:rf.xray/settings ...}` on every boot must not
            permanently overwrite a user's ALREADY-persisted
            Settings-popup mutation. Reproduces the full two-boot
            sequence: configure! → user popup edit (persists) →
            [reload] → configure! again → load-settings-from-storage!."
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

(deftest configure-settings-never-writes-storage
  (testing "rf2-3x7nj.27.1 — `configure!` on a fresh install leaves
            storage EMPTY. The first-boot write it replaces (rf2-rr2yw3,
            'so the posture survives a reload with no further configure!
            call') bought nothing — the documented host re-seeds on every
            boot — and it persisted the whole resolved map, which then sat
            above every later seed and froze the host's posture at first
            boot."
    (config/configure! {:rf.xray/settings {:general {:text-size 17}}})
    (is (= 17 (config/get-setting :general :text-size))
        "precondition: the seed is live immediately")
    (is (nil? (storage-payload))
        "and nothing reached storage")
    ;; A REAL reload — atom AND seed discarded — and the host's boot call.
    (reload!)
    (config/load-settings-from-storage!)
    (config/configure! {:rf.xray/settings {:general {:text-size 17}}})
    (is (= 17 (config/get-setting :general :text-size))
        "the host's posture is back on the next boot, from its own
         re-applied seed")))

;; ---- the payload is a sparse overlay of explicit overrides -------------
;; ---- (rf2-3x7nj.27.1) ---------------------------------------------------
;;
;; Before the fix `write-storage!` persisted `(pr-str @settings)` — the
;; WHOLE resolved map, compiled-in defaults and host seed included — on
;; every `update-setting!` AND on `configure!`'s first boot. Read back as
;; the top layer, that made every key behave as user-set: after one panel
;; drag (or no user action at all), no later host `configure!` value and
;; no later Xray default reached that browser. Storage now records only
;; the exact paths a user gesture or an `init!` opt wrote.

(defn- two-boots!
  "Boot 1: the host configures `{:theme :dark}` and the user performs
  `gesture!`. Real reload. Boot 2: the host has CHANGED its call to
  `{:theme :light :general {:density :compact}}`; the preload's load and
  the host's `configure!` run in `order` — `:load-first` is the
  `:devtools/preloads` path every shipped host takes, `:configure-first`
  a host that orders its own preload ahead of Xray's."
  [gesture! order]
  (config/configure! {:rf.xray/settings {:theme :dark}})
  (gesture!)
  (reload!)
  (let [boot-2 {:rf.xray/settings {:theme   :light
                                   :general {:density :compact}}}]
    (case order
      :load-first      (do (config/load-settings-from-storage!)
                           (config/configure! boot-2))
      :configure-first (do (config/configure! boot-2)
                           (config/load-settings-from-storage!)))))

(deftest a-later-host-posture-lands-for-every-key-the-user-never-wrote
  (doseq [order [:load-first :configure-first]]
    (testing (str "rf2-3x7nj.27.1 — " order ": one panel drag records that
                   width, and the host's later :light / :compact still land
                   (the whole-map payload read back :dark / :cosy)")
      (config/reset-settings!)
      (two-boots! #(config/update-setting! :general :panel-width-px 700) order)
      (is (= :light (config/get-setting :theme nil))
          "the host's new :theme lands — the user never chose a theme")
      (is (= :compact (config/get-setting :general :density))
          "the host's new :density lands — the user never chose one")
      (is (= 700 (config/get-setting :general :panel-width-px))
          "and the width the user DID drag is kept"))))

(deftest an-override-records-exactly-the-path-written
  (testing "rf2-3x7nj.27.1 — one write on empty storage stores exactly
            that one path; a second write adds its own path beside it"
    (config/update-setting! :general :panel-width-px 700)
    (is (= {:general {:panel-width-px 700}}
           (cljs.reader/read-string (storage-payload)))
        "the payload is exactly the one override, not the resolved map")
    (config/update-setting! :theme nil :dark)
    (is (= {:general {:panel-width-px 700} :theme :dark}
           (cljs.reader/read-string (storage-payload)))
        "a second override lands beside the first, from the payload in
         storage")))

(deftest an-explicit-choice-still-beats-a-later-host-posture
  (doseq [order [:load-first :configure-first]]
    (testing (str "CONTROL (green before and after the fix) — " order ": a
                   theme the user CHOSE outranks the host's later :light")
      (config/reset-settings!)
      (two-boots! #(config/update-setting! :theme nil :dark) order)
      (is (= :dark (config/get-setting :theme nil))
          "the user's own :dark is kept"))))

(deftest a-choice-equal-to-the-default-still-pins
  (testing "CONTROL (green before and after the fix) — the record is WHAT
            WAS WRITTEN, not what differs from the base: a user who picks
            :light, the compiled-in default, keeps it against a later host
            :dark"
    (config/update-setting! :theme nil :light)
    (reload!)
    (config/load-settings-from-storage!)
    (config/configure! {:rf.xray/settings {:theme :dark}})
    (is (= :light (config/get-setting :theme nil))
        "the explicit :light wins over the host's :dark")))
