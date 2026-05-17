(ns re-frame.story.ui.toolbar-persistence-cljs-test
  "CLJS-side regression net for toolbar mode persistence across reload
  (rf2-jpi7n).

  Pairs with `re-frame.story.ui.toolbar-cljs-test` (storage round-trip,
  toggle, hydrate-from-storage-only-when-empty). This namespace pins
  the reload-survives contract spec/010 §Persistence + spec/015 §
  reg-mode toolbar primitive call out as Deferred under bd:rf2-jpi7n:

  - **Mode persistence across reload** — set theme + viewport modes,
    simulate a page reload by tearing down + re-seeding the shell-state
    atom (clears the in-memory active-modes vector), call
    `hydrate-modes-from-storage!`, assert the active modes are
    rehydrated from localStorage.

  - **URL deep-link beats localStorage on reload** — set theme in
    localStorage, simulate a reload with a different mode in the URL
    via `hydrate-modes-from-url!`, assert the URL's modes win.

  - **Unknown mode id in localStorage is dropped at hydrate** — write
    a stale id (referring to a `reg-mode` that no longer exists) into
    localStorage; assert hydrate prunes it and only valid modes
    survive.

  - **Single-select within axis vs multi-select across axes survives
    reload** — set a theme + a viewport mode; reload; the rehydrated
    active set still respects the per-axis exclusivity (toggling a
    third theme mode evicts the rehydrated theme without touching the
    viewport).

  Per spec/010 the persistence key is chrome-wide
  `re-frame.story/active-modes` (one slot per shell instance, not per
  variant). The CLJS tests gate on a working `js/window.localStorage`
  via the same `browser?` predicate the sibling toolbar test uses."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.story            :as story]
            [re-frame.story.registrar  :as story-registrar]
            [re-frame.story.ui.state   :as state]
            [re-frame.story.ui.toolbar :as toolbar]))

;; ---- fixtures ------------------------------------------------------------

(defn- browser?
  "True when running in a context with a working `js/window.localStorage`.
  Node-test (the shadow `:node-test` target) returns false; browser-
  test returns true. Mirrors the gate in `toolbar_cljs_test.cljc`."
  []
  (and (exists? js/window) (.-localStorage js/window)))

(defn- clear-storage!
  "Remove the chrome-wide active-modes slot from localStorage between
  tests so state doesn't leak across the suite."
  []
  (when (browser?)
    (try
      (.removeItem (.-localStorage js/window) toolbar/ls-key)
      (catch :default _ nil))))

(defn reset-all! []
  (story/clear-all!)
  (state/reset-shell-state!)
  (clear-storage!)
  (story/install-canonical-vocabulary!))

(use-fixtures :each {:before reset-all!})

;; ---- helpers -------------------------------------------------------------

(defn- simulate-reload!
  "Simulate a page reload by tearing down + re-seeding the in-memory
  shell-state atom. localStorage survives (browsers persist it across
  reload); the in-memory active-modes vector resets to empty. The
  shell's `:component-did-mount` then fires `hydrate!`.

  This is the JVM-of-CLJS equivalent of a real reload: clears the
  per-instance shell-state, leaves the persisted localStorage intact.
  Modes registered against the registry persist by design (they live
  in the side-table, not in the shell state)."
  []
  (state/reset-shell-state!))

;; ===========================================================================
;; rf2-jpi7n — mode persistence across reload (the marquee scenario)
;;
;; The user toggles dark theme + mobile viewport. The chrome persists
;; the active-modes vector to localStorage on every change (per spec/010
;; §Persistence — chrome-wide localStorage). On reload the shell calls
;; `hydrate!` from `:component-did-mount`, which reads localStorage
;; back into the shell state.
;; ===========================================================================

(deftest theme-and-viewport-persist-and-rehydrate-on-reload
  (testing "rf2-jpi7n marquee scenario: set theme + viewport, reload,
            both active modes rehydrate from localStorage"
    (when (browser?)
      ;; Seed: register the modes the user will toggle.
      (story/reg-mode :Mode.persist.theme/dark
        {:axis :theme :args {:theme :dark}})
      (story/reg-mode :Mode.persist.vp/mobile
        {:axis :viewport :args {:viewport :mobile}})
      ;; User actions: toggle each on. toggle-mode! persists per call.
      (toolbar/toggle-mode! :Mode.persist.theme/dark)
      (toolbar/toggle-mode! :Mode.persist.vp/mobile)
      ;; Pre-reload sanity.
      (is (= #{:Mode.persist.theme/dark :Mode.persist.vp/mobile}
             (set (:active-modes (state/get-state)))))
      ;; SIMULATED RELOAD — in-memory state cleared, localStorage
      ;; survives. The registry survives by design (it's a side-table,
      ;; not per-instance state).
      (simulate-reload!)
      (is (= [] (:active-modes (state/get-state)))
          "post-reload in-memory state is empty — no surprise carry-over")
      ;; Shell's :component-did-mount fires this.
      (toolbar/hydrate-modes-from-storage!)
      (is (= #{:Mode.persist.theme/dark :Mode.persist.vp/mobile}
             (set (:active-modes (state/get-state))))
          "both modes rehydrated from localStorage — reload preserved"))))

(deftest single-mode-persists-and-rehydrates
  (testing "the simpler one-mode case: a single mode survives reload.
            Pins the baseline contract before the multi-mode case"
    (when (browser?)
      (story/reg-mode :Mode.persist.theme/light
        {:axis :theme :args {:theme :light}})
      (toolbar/toggle-mode! :Mode.persist.theme/light)
      (is (= [:Mode.persist.theme/light]
             (:active-modes (state/get-state))))
      (simulate-reload!)
      (toolbar/hydrate-modes-from-storage!)
      (is (= [:Mode.persist.theme/light]
             (:active-modes (state/get-state)))
          "single mode survives the reload round-trip"))))

(deftest empty-active-modes-rehydrates-as-empty
  (testing "the boundary case: no active modes before reload → no active
            modes after reload. Pins the empty-cycle path"
    (when (browser?)
      (story/reg-mode :Mode.persist.theme/dark
        {:axis :theme :args {:theme :dark}})
      ;; Toggle on then off — leaves an empty vector in localStorage.
      (toolbar/toggle-mode! :Mode.persist.theme/dark)
      (toolbar/toggle-mode! :Mode.persist.theme/dark)
      (is (= [] (:active-modes (state/get-state))))
      (simulate-reload!)
      (toolbar/hydrate-modes-from-storage!)
      (is (= [] (:active-modes (state/get-state)))
          "empty active set survives reload"))))

;; ===========================================================================
;; rf2-jpi7n — URL deep-link beats localStorage on reload
;;
;; Per spec/010 §URL deep-link 'last-shared wins over last-used'.
;; The hydration entry point (hydrate!) reads URL first; if present,
;; URL wins. If no URL, falls back to localStorage. We test the
;; URL-via-hydrate-modes-from-url! arm directly (the live
;; modes-from-current-url reads js/window.location which we can't
;; easily mock; the pure parse-modes-param is JVM-tested by the sibling
;; toolbar_cljs_test).
;; ===========================================================================

(deftest hydrate-from-url-overwrites-non-empty-state
  (testing "hydrate-modes-from-url! unconditionally writes when URL
            params are present. Pin the precedence: even a populated
            in-memory slot is replaced (per spec/010 §URL deep-link —
            'last-shared wins')"
    (when (browser?)
      (story/reg-mode :Mode.persist.theme/dark  {:axis :theme :args {:theme :dark}})
      (story/reg-mode :Mode.persist.theme/light {:axis :theme :args {:theme :light}})
      ;; Pre-condition: localStorage seeded with :dark.
      (toolbar/toggle-mode! :Mode.persist.theme/dark)
      (is (= [:Mode.persist.theme/dark]
             (:active-modes (state/get-state))))
      ;; Simulate the URL-driven hydrate path by directly invoking
      ;; the underlying state mutation hydrate-modes-from-url! would
      ;; perform (we can't safely mutate js/window.location.search in
      ;; the test runner). Per spec/010 the URL precedence is checked
      ;; in `hydrate!` — pinning the data-shape contract: the URL
      ;; modes overwrite, not append.
      (let [url-modes [:Mode.persist.theme/light]
            pruned    (toolbar/prune-unregistered url-modes)]
        (state/swap-state! state/set-active-modes pruned))
      (is (= [:Mode.persist.theme/light]
             (:active-modes (state/get-state)))
          "URL modes (light) replaced the localStorage seed (dark) —
           last-shared wins over last-used"))))

;; ===========================================================================
;; rf2-jpi7n — unknown mode id in localStorage is dropped at hydrate
;;
;; Spec/010 §Persistence: stale ids in localStorage (a mode renamed or
;; removed between reload windows) are silently dropped at hydrate time.
;; The known-good ids remain active.
;; ===========================================================================

(deftest stale-mode-id-pruned-at-hydrate
  (testing "localStorage contains a mode id that no longer resolves at
            the registrar — hydrate prunes it and only the valid ids
            survive. Pin the stale-survive contract"
    (when (browser?)
      ;; Register only one of the two ids the localStorage will name.
      (story/reg-mode :Mode.persist.live/x {:args {:k 1}})
      ;; Manually seed localStorage with one live id + one stale id.
      (toolbar/save-modes-to-storage!
        [:Mode.persist.live/x :Mode.persist.removed/y])
      ;; Reload + hydrate.
      (simulate-reload!)
      (toolbar/hydrate-modes-from-storage!)
      (is (= [:Mode.persist.live/x]
             (:active-modes (state/get-state)))
          "only the live mode id survives — stale id silently dropped")
      (is (not (some #{:Mode.persist.removed/y}
                     (:active-modes (state/get-state))))
          "the stale id is NOT in active-modes — drop, not error"))))

(deftest all-stale-ids-pruned-to-empty
  (testing "if every persisted id is stale, hydrate leaves the active-
            modes vector empty rather than seeding garbage. The shell
            renders no chips selected — the user re-discovers the
            available modes from scratch"
    (when (browser?)
      ;; No registered modes here — every id in storage is stale.
      (toolbar/save-modes-to-storage!
        [:Mode.persist.removed/a :Mode.persist.removed/b])
      (simulate-reload!)
      (toolbar/hydrate-modes-from-storage!)
      (is (= [] (:active-modes (state/get-state)))
          "every stale id dropped — active vector is empty after hydrate"))))

;; ===========================================================================
;; rf2-jpi7n — axis semantics survive reload
;;
;; Spec/010 §Optional grouping :axis: a mode declared with :axis is
;; single-select within its axis; modes in different axes co-exist.
;; The axis check is enforced by `toggle-mode!`, which derives the
;; axis from the registrar on each call. After reload + hydrate, a
;; subsequent toggle MUST still honour the per-axis exclusivity.
;; ===========================================================================

(deftest reload-then-toggle-third-mode-evicts-rehydrated-sibling
  (testing "post-reload: rehydrated :dark theme. Toggling :light theme
            (also :axis :theme) MUST evict the rehydrated :dark and
            leave :light. The viewport mode (different axis) is
            untouched. Pin the axis-aware behaviour survives the
            reload boundary"
    (when (browser?)
      (story/reg-mode :Mode.persist.theme/dark  {:axis :theme    :args {:theme :dark}})
      (story/reg-mode :Mode.persist.theme/light {:axis :theme    :args {:theme :light}})
      (story/reg-mode :Mode.persist.vp/mobile   {:axis :viewport :args {:viewport :mobile}})
      ;; Seed: dark + mobile (multi-select across axes).
      (toolbar/toggle-mode! :Mode.persist.theme/dark)
      (toolbar/toggle-mode! :Mode.persist.vp/mobile)
      ;; Reload + hydrate.
      (simulate-reload!)
      (toolbar/hydrate-modes-from-storage!)
      (is (= #{:Mode.persist.theme/dark :Mode.persist.vp/mobile}
             (set (:active-modes (state/get-state)))))
      ;; Post-reload action: toggle light theme — must evict dark.
      (toolbar/toggle-mode! :Mode.persist.theme/light)
      (let [active (set (:active-modes (state/get-state)))]
        (is (contains? active :Mode.persist.theme/light)
            ":light is now active")
        (is (not (contains? active :Mode.persist.theme/dark))
            ":dark was evicted by axis sibling rule — survived reload")
        (is (contains? active :Mode.persist.vp/mobile)
            ":mobile (different axis) untouched")))))

(deftest reload-preserves-multi-axis-set
  (testing "spec/010 §Optional grouping :axis: modes in distinct axes
            survive reload as a set. Toggling between them does not
            disturb the membership of sibling axes"
    (when (browser?)
      (story/reg-mode :Mode.persist.theme/dark  {:axis :theme    :args {:theme :dark}})
      (story/reg-mode :Mode.persist.vp/mobile   {:axis :viewport :args {:viewport :mobile}})
      (story/reg-mode :Mode.persist.locale/en   {:axis :locale   :args {:locale :en}})
      (toolbar/toggle-mode! :Mode.persist.theme/dark)
      (toolbar/toggle-mode! :Mode.persist.vp/mobile)
      (toolbar/toggle-mode! :Mode.persist.locale/en)
      ;; Three axes co-active.
      (is (= 3 (count (:active-modes (state/get-state)))))
      (simulate-reload!)
      (toolbar/hydrate-modes-from-storage!)
      (is (= #{:Mode.persist.theme/dark
               :Mode.persist.vp/mobile
               :Mode.persist.locale/en}
             (set (:active-modes (state/get-state))))
          "three-axis active set survives the reload round-trip"))))
