(ns day8.re-frame2-xray.init-filter-reset-cljs-test
  "Init policy: reset TRANSIENT filters to unfiltered on every page load;
  persist only DURABLE view prefs (rf2-swclw).

  Xray's L2 filters used to persist across reload via localStorage,
  which silently hid events and made the inspector look broken (rf2-jvghz
  — it fooled even the project author). The decision (Mike, 2026-05-22):
  an inspector's prime directive is to show the truth, so a fresh load
  must never silently carry a stale filter from a past session. The
  three transient exploration filters —

    1. the IN/OUT filter pills  (no localStorage at all — see below)
    2. the muted-event-ids set  (localStorage `xray.spine.muted-event-ids`)
    3. the frame pin            (localStorage `re-frame2.xray.frame-switcher.v1`)

  — do NOT restore on init regardless of what localStorage holds, and
  the stale slots that still EXIST are CLEARED so storage stays honest.
  DURABLE view prefs (the Dynamic ↔ Static mode under `xray.mode`) still
  restore.

  ## Surface 1 reached the end of that argument (rf2-y8doi.27)

  The IN/OUT pills no longer have a localStorage slot to reset. Every
  pill mutation still WROTE one long after reset-on-load had removed
  the only reader, so the store existed purely to be cleared by the
  hook below. `filters/persistence.cljs` was deleted; the pills now
  start at the registry default `{:in [] :out []}` because nothing
  writes them anywhere else.

  That makes the surface-1 half of this suite TRIVIALLY TRUE, and a
  test that cannot fail is worth less than the space it occupies — so
  the scenarios that SEEDED a stale pill set are gone along with the
  seam that made seeding possible. What survives is everything that
  can still go red: surfaces 2 and 3, the durable-mode half, and the
  host-seed contract (rf2-fhtes), which never involved localStorage.

  These tests drive the REAL production init path
  (`mount/ensure-xray-frame!` — the first-mount hook walker) so the
  policy is pinned against the actual boot sequence. `ensure-xray-frame!`
  registers the `:rf/xray` frame then walks the hook table; no DOM mount
  point is required (that happens later in `open!`).

  Node-test has no jsdom, so this ns installs a minimal in-memory
  `js/window.localStorage` stub for the fixture's duration (the same
  pattern as `routing_history_cljs_test`'s window stub). The stub lets us
  SEED stale values pre-boot and assert they neither survive into app-db
  nor linger in storage after the reset hook runs."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.frame-switcher :as frame-switcher]
            [day8.re-frame2-xray.mount :as mount]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.spine-filters :as spine-filters]
            [day8.re-frame2-xray.static.persistence :as static-persistence]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

;; -------------------------------------------------------------------------
;; in-memory localStorage stub (node-test has no jsdom)
;; -------------------------------------------------------------------------

(defn- make-local-storage []
  (let [store (atom {})]
    #js {:getItem    (fn [k] (get @store k nil))
         :setItem    (fn [k v] (swap! store assoc k (str v)) js/undefined)
         :removeItem (fn [k] (swap! store dissoc k) js/undefined)
         :clear      (fn [] (reset! store {}) js/undefined)}))

(defn- install-local-storage! []
  (when-not (exists? js/globalThis.window)
    (set! (.-window js/globalThis) #js {}))
  (set! (.-localStorage js/globalThis.window) (make-local-storage)))

(defn- uninstall-local-storage! []
  ;; Drop the whole window stub we installed so we don't leak a
  ;; localStorage into sibling test namespaces.
  (js-delete js/globalThis "window"))

(def ^:private runtime-fixture
  ;; `make-xray-runtime-fixture` (rf2-vj80u8): plain-atom + the `:all` reset
  ;; tier; `:post-reset` starts every scenario from a clean localStorage so
  ;; the seeding below is the only signal the init path can read.
  (xray-test-support/make-xray-runtime-fixture
    {:post-reset (fn []
                   (spine-filters/clear-raw!)
                   (frame-switcher/clear!)
                   (static-persistence/clear!)
                   ;; rf2-fhtes — the host `:rf.xray/filters` seed atom is
                   ;; process-global and now LOAD-BEARING on the boot path
                   ;; (`::seed-configured-filters`). Clear it before each
                   ;; test so a sibling test's `configure!` cannot leak a
                   ;; seed into the reset-policy scenarios below.
                   (config/set-filter-seed! nil))}))

(defn- with-local-storage-stub
  "Install an in-memory `js/window.localStorage` for the test's duration,
  then run the runtime fixture (whose :init-fn clears the stub to a clean
  slate), then tear the whole window stub down so sibling namespaces see
  the absent window they expect."
  [test-fn]
  (install-local-storage!)
  (try
    (runtime-fixture test-fn)
    (finally
      (uninstall-local-storage!))))

(use-fixtures :each with-local-storage-stub)

(defn- boot!
  "Drive the production init path: register the Xray handlers, then run
  `ensure-xray-frame!` (which registers `:rf/xray` and walks the
  first-mount hook table — the SAME walk a real page load performs)."
  []
  (registry/register-xray-handlers!)
  (mount/ensure-xray-frame!))

(defn- frame-sub [q]
  (rf/with-frame :rf/xray
    @(rf/subscribe q)))

(def ^:private stale-mutes
  "A non-empty mute set, as a past session would have persisted."
  #{:user/mouse-move :ui/tick})

(def ^:private stale-frame
  "A pinned frame, as a past session would have persisted."
  :cart-frame)

;; -------------------------------------------------------------------------
;; (0) sanity: the localStorage stub round-trips (preconditions are real)
;; -------------------------------------------------------------------------

(deftest local-storage-stub-round-trips
  (testing "the in-memory stub backs save!/load so the seeding below is real"
    (spine-filters/save! stale-mutes)
    (is (= stale-mutes (spine-filters/load)))
    (frame-switcher/save! stale-frame)
    (is (= stale-frame (frame-switcher/load)))
    (static-persistence/save! :static)
    (is (= :static (static-persistence/load)))))

;; -------------------------------------------------------------------------
;; (1) IN/OUT filter pills reset to unfiltered on load
;; -------------------------------------------------------------------------

(deftest init-comes-up-with-unfiltered-pills
  (testing "rf2-y8doi.27 — the pills come up unfiltered. This is now
            TRIVIALLY TRUE, there being no pill localStorage to restore
            from, and it is kept only because it is the property the
            whole policy is ABOUT: if a future change reintroduces a
            restore path, this is the assertion that goes red."
    (boot!)
    (is (= {:in [] :out []} (frame-sub [:rf.xray/active-filters]))
        "first paint is fully unfiltered")))

;; -------------------------------------------------------------------------
;; (2) muted-event-ids reset to empty on load
;; -------------------------------------------------------------------------

(deftest init-resets-stale-mutes
  (testing "stale persisted mutes do NOT restore on init"
    (spine-filters/save! stale-mutes)
    (is (= stale-mutes (spine-filters/load))
        "precondition: localStorage holds the stale mute set")
    (boot!)
    (is (= #{} (frame-sub [:rf.xray/muted-event-ids]))
        "init yields an empty mute set despite the stale localStorage value")
    (is (= #{} (spine-filters/load))
        "the stale mute slot is cleared so storage stays honest")))

;; -------------------------------------------------------------------------
;; (3) frame pin resets to unpinned on load
;; -------------------------------------------------------------------------
;;
;; The frame pin lives in localStorage; on init the slot is cleared so a
;; stale pin can never resurface. The `:rf.xray/current-frame` sub does
;; NOT read back as nil after boot — the unrelated `::seed-trace-and-
;; target-frame` first-mount hook seeds the focus frame to the head
;; focusable cascade (here `:rf/default`, since the trace buffer is
;; empty). That seed is the panel-observed frame, not a user pin; the
;; reset signal is the cleared localStorage slot.

(deftest init-resets-stale-frame-pin
  (testing "a stale persisted frame pin does NOT restore on init"
    (frame-switcher/save! stale-frame)
    (is (= stale-frame (frame-switcher/load))
        "precondition: localStorage holds the stale frame pin")
    (boot!)
    (is (nil? (frame-switcher/load))
        "the stale frame-pin slot is cleared so the pin can't resurface")
    (is (not= stale-frame (frame-sub [:rf.xray/current-frame]))
        "the observed frame is NOT the stale pin")))

;; -------------------------------------------------------------------------
;; (4) all three transient filters reset together (the real-load shape)
;; -------------------------------------------------------------------------

(deftest init-resets-all-transient-filters-together
  (testing "a fresh load with every REMAINING transient slot stale comes
            up fully unfiltered (the pill slot no longer exists —
            rf2-y8doi.27)"
    (spine-filters/save! stale-mutes)
    (frame-switcher/save! stale-frame)
    (boot!)
    (is (= {:in [] :out []} (frame-sub [:rf.xray/active-filters])))
    (is (= #{} (frame-sub [:rf.xray/muted-event-ids])))
    (testing "and every surviving transient localStorage slot is cleared"
      (is (= #{} (spine-filters/load)))
      (is (nil? (frame-switcher/load))))))

;; -------------------------------------------------------------------------
;; (5) durable view prefs STILL restore (the policy's other half)
;; -------------------------------------------------------------------------

(deftest init-restores-durable-mode-pref
  (testing "the Dynamic ↔ Static mode is a DURABLE pref — it restores on init"
    (static-persistence/save! :static)
    (is (= :static (static-persistence/load))
        "precondition: localStorage holds the durable :static mode")
    (boot!)
    (is (= :static (frame-sub [:rf.xray/mode]))
        "the durable mode pref restores even as transient filters reset")
    (is (= :static (static-persistence/load))
        "the durable mode slot is NOT cleared")))

(deftest init-restores-durable-mode-alongside-transient-reset
  (testing "durable mode restores WHILE the transient filters reset — both halves of the policy hold in one boot"
    (static-persistence/save! :static)
    (spine-filters/save! stale-mutes)
    (frame-switcher/save! stale-frame)
    (boot!)
    (is (= :static (frame-sub [:rf.xray/mode]))
        "durable mode restored")
    (is (= {:in [] :out []} (frame-sub [:rf.xray/active-filters]))
        "transient pills reset")
    (is (= #{} (frame-sub [:rf.xray/muted-event-ids]))
        "transient mutes reset")
    (is (= #{} (spine-filters/load))
        "transient mute slot cleared")
    (is (nil? (frame-switcher/load))
        "transient frame-pin slot cleared")))

;; -------------------------------------------------------------------------
;; (6) EXPLICIT host filter SEED lands as the boot baseline (rf2-fhtes)
;; -------------------------------------------------------------------------
;;
;; `configure!` accepts `:rf.xray/filters` and the config/spec/API prose
;; promised the seed would hydrate `:active-filters`, but production never
;; called the filters hydrate fn — the real `ensure-xray-frame!` hook
;; table only RESET transient filters and never READ the seed, so a host
;; using the documented key got no error and an unfiltered first paint.
;; The passing persistence tests only proved otherwise by calling that
;; hydrate fn MANUALLY (routing around the hook table), so the gap
;; stayed green. rf2-y8doi.27 deleted the fn and those tests together.
;;
;; These tests drive the REAL production `ensure-xray-frame!` path (the
;; same `boot!` the reset-policy tests above use). The policy: an
;; EXPLICITLY configured seed is the host's opt-in and lands as the boot
;; baseline AFTER the transient reset; a `nil` seed stays fully unfiltered.

(def ^:private host-seed
  "An explicit host-configured seed, as a Story testbed (or any host)
  would ship via `(configure! {:rf.xray/filters …})`."
  {:in  [{:pattern ":order/*"}]
   :out [{:pattern ":mouse-move"}]})

(deftest configured-seed-lands-as-boot-baseline
  (testing "an explicitly configured :rf.xray/filters seed is observed in
            :active-filters after the REAL ensure-xray-frame! path — WITHOUT
            calling filters/hydrate! manually (rf2-fhtes criterion 1)"
    (config/configure! {:rf.xray/filters host-seed})
    ;; Nothing persists pills at all since rf2-y8doi.27, so the
    ;; configured seed is necessarily the only source.
    (boot!)
    (is (= host-seed (frame-sub [:rf.xray/active-filters]))
        "the host seed IS the boot baseline for :active-filters on the real
         production path — the false-hydrate contract is now honoured")))

(deftest no-seed-first-mount-stays-fully-unfiltered
  (testing "with NO host seed, production first mount comes up fully
            unfiltered (rf2-fhtes criterion 2 — nil remains unfiltered)"
    (is (nil? (config/get-filter-seed))
        "precondition: the fixture cleared any seed")
    (boot!)
    (is (= {:in [] :out []} (frame-sub [:rf.xray/active-filters]))
        "no seed → registry-default empty pills → unfiltered first paint")))

;; (`configured-seed-wins-over-stale-localstorage` was REMOVED —
;; rf2-y8doi.27. It seeded a stale pill set into localStorage and
;; asserted the host seed won anyway. There is no longer any way to
;; put a stale pill set into localStorage, so the scenario cannot be
;; constructed: the contest it staged has exactly one contestant now.
;; `configured-seed-lands-as-boot-baseline` above is what remains of
;; it, and it is the half that could ever have gone red.)

(deftest configured-seed-is-not-durable-user-persistence
  (testing "the seed is an explicit boot baseline re-applied each load — it
            is NOT written to localStorage as durable user pills (rf2-fhtes
            criterion 3 — not durable user-filter persistence)"
    (config/configure! {:rf.xray/filters host-seed})
    (boot!)
    (is (= host-seed (frame-sub [:rf.xray/active-filters]))
        "seed is the live baseline in app-db")
    (is (nil? (.getItem js/window.localStorage "re-frame2.xray.filters.v1"))
        "the seed did NOT reach localStorage under the key the deleted
         persistence layer used: the seed hook writes app-db only, so the
         baseline is re-derived from configure! on every load. Read
         through the raw stub rather than a loader, because
         rf2-y8doi.27 removed the loader — and a raw read is the
         stronger instrument anyway, since it would still see a write
         made by any code path at all.")))
