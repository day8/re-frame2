(ns day8.re-frame2-causa.init-filter-reset-cljs-test
  "Init policy: reset TRANSIENT filters to unfiltered on every page load;
  persist only DURABLE view prefs (rf2-swclw).

  Causa's L2 filters used to persist across reload via localStorage,
  which silently hid events and made the inspector look broken (rf2-jvghz
  — it fooled even the project author). The decision (Mike, 2026-05-22):
  an inspector's prime directive is to show the truth, so a fresh load
  must never silently carry a stale filter from a past session. The
  three transient exploration filters —

    1. the IN/OUT filter pills  (localStorage `re-frame2.causa.filters.v1`)
    2. the muted-event-ids set  (localStorage `causa.spine.muted-event-ids`)
    3. the frame pin            (localStorage `re-frame2.causa.frame-switcher.v1`)

  — do NOT restore on init regardless of what localStorage holds, and
  their stale slots are CLEARED so storage stays honest. DURABLE view
  prefs (the Dynamic ↔ Static mode under `causa.mode`) still restore.

  These tests drive the REAL production init path
  (`mount/ensure-causa-frame!` — the first-mount hook walker) so the
  policy is pinned against the actual boot sequence. `ensure-causa-frame!`
  registers the `:rf/causa` frame then walks the hook table; no DOM mount
  point is required (that happens later in `open!`).

  Node-test has no jsdom, so this ns installs a minimal in-memory
  `js/window.localStorage` stub for the fixture's duration (the same
  pattern as `routing_history_cljs_test`'s window stub). The stub lets us
  SEED stale values pre-boot and assert they neither survive into app-db
  nor linger in storage after the reset hook runs."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.filters.persistence :as filters-persistence]
            [day8.re-frame2-causa.frame-switcher :as frame-switcher]
            [day8.re-frame2-causa.mount :as mount]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.spine-filters :as spine-filters]
            [day8.re-frame2-causa.static.persistence :as static-persistence]
            [day8.re-frame2-causa.test-support :as causa-test-support]))

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

(defn- causa-init! []
  (causa-test-support/reset-all!)
  ;; Start every scenario from a clean localStorage so the seeding below
  ;; is the only signal the init path can read.
  (filters-persistence/clear!)
  (spine-filters/clear-raw!)
  (frame-switcher/clear!)
  (static-persistence/clear!))

(def ^:private runtime-fixture
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn causa-init!}))

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
  "Drive the production init path: register the Causa handlers, then run
  `ensure-causa-frame!` (which registers `:rf/causa` and walks the
  first-mount hook table — the SAME walk a real page load performs)."
  []
  (registry/register-causa-handlers!)
  (mount/ensure-causa-frame!))

(defn- frame-sub [q]
  (rf/with-frame :rf/causa
    @(rf/subscribe q)))

(def ^:private stale-pills
  "A non-empty IN/OUT pill set, as a past session would have persisted."
  {:in  [{:pattern ":auth/*"}]
   :out [{:pattern ":mouse-move"}]})

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
    (filters-persistence/save! stale-pills)
    (is (= stale-pills (filters-persistence/load)))
    (spine-filters/save! stale-mutes)
    (is (= stale-mutes (spine-filters/load)))
    (frame-switcher/save! stale-frame)
    (is (= stale-frame (frame-switcher/load)))
    (static-persistence/save! :static)
    (is (= :static (static-persistence/load)))))

;; -------------------------------------------------------------------------
;; (1) IN/OUT filter pills reset to unfiltered on load
;; -------------------------------------------------------------------------

(deftest init-resets-stale-filter-pills
  (testing "stale persisted IN/OUT pills do NOT restore on init"
    (filters-persistence/save! stale-pills)
    (is (= stale-pills (filters-persistence/load))
        "precondition: localStorage holds the stale pills")
    (boot!)
    (is (= {:in [] :out []} (frame-sub [:rf.causa/active-filters]))
        "init yields unfiltered pills despite the stale localStorage value")
    (is (= {:in [] :out []} (filters-persistence/load))
        "the stale pill slot is cleared so storage stays honest")))

;; -------------------------------------------------------------------------
;; (2) muted-event-ids reset to empty on load
;; -------------------------------------------------------------------------

(deftest init-resets-stale-mutes
  (testing "stale persisted mutes do NOT restore on init"
    (spine-filters/save! stale-mutes)
    (is (= stale-mutes (spine-filters/load))
        "precondition: localStorage holds the stale mute set")
    (boot!)
    (is (= #{} (frame-sub [:rf.causa/muted-event-ids]))
        "init yields an empty mute set despite the stale localStorage value")
    (is (= #{} (spine-filters/load))
        "the stale mute slot is cleared so storage stays honest")))

;; -------------------------------------------------------------------------
;; (3) frame pin resets to unpinned on load
;; -------------------------------------------------------------------------
;;
;; The frame pin lives in localStorage; on init the slot is cleared so a
;; stale pin can never resurface. The `:rf.causa/current-frame` sub does
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
    (is (not= stale-frame (frame-sub [:rf.causa/current-frame]))
        "the observed frame is NOT the stale pin")))

;; -------------------------------------------------------------------------
;; (4) all three transient filters reset together (the real-load shape)
;; -------------------------------------------------------------------------

(deftest init-resets-all-transient-filters-together
  (testing "a fresh load with ALL transient slots stale comes up fully unfiltered"
    (filters-persistence/save! stale-pills)
    (spine-filters/save! stale-mutes)
    (frame-switcher/save! stale-frame)
    (boot!)
    (is (= {:in [] :out []} (frame-sub [:rf.causa/active-filters])))
    (is (= #{} (frame-sub [:rf.causa/muted-event-ids])))
    (testing "and every transient localStorage slot is cleared"
      (is (= {:in [] :out []} (filters-persistence/load)))
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
    (is (= :static (frame-sub [:rf.causa/mode]))
        "the durable mode pref restores even as transient filters reset")
    (is (= :static (static-persistence/load))
        "the durable mode slot is NOT cleared")))

(deftest init-restores-durable-mode-alongside-transient-reset
  (testing "durable mode restores WHILE the transient filters reset — both halves of the policy hold in one boot"
    (static-persistence/save! :static)
    (filters-persistence/save! stale-pills)
    (spine-filters/save! stale-mutes)
    (frame-switcher/save! stale-frame)
    (boot!)
    (is (= :static (frame-sub [:rf.causa/mode]))
        "durable mode restored")
    (is (= {:in [] :out []} (frame-sub [:rf.causa/active-filters]))
        "transient pills reset")
    (is (= #{} (frame-sub [:rf.causa/muted-event-ids]))
        "transient mutes reset")
    (is (= {:in [] :out []} (filters-persistence/load))
        "transient pill slot cleared")
    (is (= #{} (spine-filters/load))
        "transient mute slot cleared")
    (is (nil? (frame-switcher/load))
        "transient frame-pin slot cleared")))
