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

  - **URL beats localStorage on mount** — the mount-hydration order
    (`hydrate-modes-from-storage!` then the url-state engine's
    `apply-parsed-to-state`) is exercised through the SINGLE canonical
    ownership path (rf2-96y71s): the localStorage seed lands first, the
    URL parse (`rf.story.share/parse-params`) + apply then authoritatively
    overrides it. The toolbar no longer reads the URL itself.

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
            [re-frame.story             :as rf.story]
            [re-frame.story.registrar   :as rf.story.registrar]
            [re-frame.story.share        :as rf.story.share]
            [re-frame.story.ui.state     :as rf.story.ui.state]
            [re-frame.story.ui.toolbar   :as rf.story.ui.toolbar]
            [re-frame.story.ui.url-state :as rf.story.ui.url-state]))

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
      (.removeItem (.-localStorage js/window) rf.story.ui.toolbar/ls-key)
      (catch :default _ nil))))

(defn reset-all! []
  (rf.story/clear-all!)
  (rf.story.ui.state/reset-shell-state!)
  (clear-storage!)
  (rf.story/install-canonical-vocabulary!))

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
  (rf.story.ui.state/reset-shell-state!))

(defn- modes-url-search
  "Build the canonical `?modes=...` search string for `mode-ids` via the
  PRODUCTION encoder `rf.story.share/build-params` — the same wire form the live
  share URL emits — so the test round-trips through the real codec rather
  than hand-assembling tokens (and avoids the `(name kw)`-drops-namespace
  trap)."
  [mode-ids]
  (str "?" (first (rf.story.share/build-params {:active-modes mode-ids}))))

(defn- mount-hydrate-modes!
  "Compose the shell-mount `:active-modes` hydration through the SINGLE
  documented ownership path (rf2-96y71s), exactly as `shell/shell`'s
  `:component-did-mount` does, but with the URL search supplied as data
  (`url-search`, e.g. \"?modes=Mode.app%2Fdark\" or \"\") so the test
  drives both the localStorage seed and the URL authority without
  touching `js/window.location`:

    1. `rf.story.ui.toolbar/hydrate-modes-from-storage!` — localStorage FALLBACK
       seeds `:active-modes` (idempotent, pruned against the registrar).
    2. The url-state engine then folds the parsed `rf.story.share/parse-params`
       shape via `rf.story.ui.url-state/apply-parsed-to-state` — the SINGLE
       authoritative URL writer. A present `modes=` overwrites the seed;
       an omitted `modes=` (but other params present) authoritatively
       CLEARS `:active-modes` to []; a fully-empty search (`\"\"`) means
       no URL state — `apply-parsed-to-state` is NOT run, so the
       localStorage seed survives (the URL-over-localStorage precedence).

  `url-search` is the raw `location.search` string; `\"\"` / nil ⇒ no
  URL params at all (fresh mount). Mirrors `shell/hydrate-url-state!`'s
  `parse-current-url` gate: empty search ⇒ skip the apply."
  [url-search]
  (rf.story.ui.toolbar/hydrate-modes-from-storage!)
  (when (and (string? url-search) (seq url-search))
    (let [usp    (js/URLSearchParams. url-search)
          getter {"variant"    (.get usp "variant")
                  "workspace"  (.get usp "workspace")
                  "mode-tab"   (.get usp "mode-tab")
                  "modes"      (.get usp "modes")
                  "viewport"   (.get usp "viewport")
                  "background" (.get usp "background")
                  "tag-filter" (.get usp "tag-filter")
                  "overrides"  (.get usp "overrides")
                  "substrate"  (.get usp "substrate")}
          parsed (rf.story.share/parse-params getter)]
      (rf.story.ui.state/swap-state! rf.story.ui.url-state/apply-parsed-to-state parsed {}))))

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
      (rf.story/reg-mode :Mode.persist.theme/dark
        {:axis :theme :args {:theme :dark}})
      (rf.story/reg-mode :Mode.persist.vp/mobile
        {:axis :viewport :args {:viewport :mobile}})
      ;; User actions: toggle each on. toggle-mode! persists per call.
      (rf.story.ui.toolbar/toggle-mode! :Mode.persist.theme/dark)
      (rf.story.ui.toolbar/toggle-mode! :Mode.persist.vp/mobile)
      ;; Pre-reload sanity.
      (is (= #{:Mode.persist.theme/dark :Mode.persist.vp/mobile}
             (set (:active-modes (rf.story.ui.state/get-state)))))
      ;; SIMULATED RELOAD — in-memory state cleared, localStorage
      ;; survives. The registry survives by design (it's a side-table,
      ;; not per-instance state).
      (simulate-reload!)
      (is (= [] (:active-modes (rf.story.ui.state/get-state)))
          "post-reload in-memory state is empty — no surprise carry-over")
      ;; Shell's :component-did-mount fires this.
      (rf.story.ui.toolbar/hydrate-modes-from-storage!)
      (is (= #{:Mode.persist.theme/dark :Mode.persist.vp/mobile}
             (set (:active-modes (rf.story.ui.state/get-state))))
          "both modes rehydrated from localStorage — reload preserved"))))

(deftest single-mode-persists-and-rehydrates
  (testing "the simpler one-mode case: a single mode survives reload.
            Pins the baseline contract before the multi-mode case"
    (when (browser?)
      (rf.story/reg-mode :Mode.persist.theme/light
        {:axis :theme :args {:theme :light}})
      (rf.story.ui.toolbar/toggle-mode! :Mode.persist.theme/light)
      (is (= [:Mode.persist.theme/light]
             (:active-modes (rf.story.ui.state/get-state))))
      (simulate-reload!)
      (rf.story.ui.toolbar/hydrate-modes-from-storage!)
      (is (= [:Mode.persist.theme/light]
             (:active-modes (rf.story.ui.state/get-state)))
          "single mode survives the reload round-trip"))))

(deftest empty-active-modes-rehydrates-as-empty
  (testing "the boundary case: no active modes before reload → no active
            modes after reload. Pins the empty-cycle path"
    (when (browser?)
      (rf.story/reg-mode :Mode.persist.theme/dark
        {:axis :theme :args {:theme :dark}})
      ;; Toggle on then off — leaves an empty vector in localStorage.
      (rf.story.ui.toolbar/toggle-mode! :Mode.persist.theme/dark)
      (rf.story.ui.toolbar/toggle-mode! :Mode.persist.theme/dark)
      (is (= [] (:active-modes (rf.story.ui.state/get-state))))
      (simulate-reload!)
      (rf.story.ui.toolbar/hydrate-modes-from-storage!)
      (is (= [] (:active-modes (rf.story.ui.state/get-state)))
          "empty active set survives reload"))))

;; ===========================================================================
;; rf2-96y71s — modes=, omitted modes=, and localStorage fallback all
;; compose through ONE documented ownership path.
;;
;; The toolbar no longer reads the URL. Mount hydration is:
;;   1. rf.story.ui.toolbar/hydrate-modes-from-storage!  (localStorage FALLBACK)
;;   2. rf.story.ui.url-state/apply-parsed-to-state       (the SINGLE URL authority)
;; `mount-hydrate-modes!` composes exactly that with the URL search
;; supplied as data. These three tests pin the precedence end-to-end
;; against the canonical share/url-state path — no manual simulation of
;; the parser, no second URL reader.
;; ===========================================================================

(deftest mount-url-modes-beat-localstorage
  (testing "rf2-96y71s — a URL carrying `modes=` overrides the
            localStorage seed: the localStorage hydrator seeds :dark
            first, then apply-parsed-to-state writes the URL's :light.
            Last-shared wins over last-used — through ONE path."
    (when (browser?)
      (rf.story/reg-mode :Mode.persist.theme/dark  {:axis :theme :args {:theme :dark}})
      (rf.story/reg-mode :Mode.persist.theme/light {:axis :theme :args {:theme :light}})
      ;; localStorage seeded with :dark (last-used).
      (rf.story.ui.toolbar/save-modes-to-storage! [:Mode.persist.theme/dark])
      (simulate-reload!)
      ;; Mount with a URL that carries modes=...light (last-shared).
      (mount-hydrate-modes! (modes-url-search [:Mode.persist.theme/light]))
      (is (= [:Mode.persist.theme/light]
             (:active-modes (rf.story.ui.state/get-state)))
          "URL modes (light) replaced the localStorage seed (dark)"))))

(deftest mount-omitted-modes-clears-localstorage-seed
  (testing "rf2-96y71s / rf2-fkmnh — a URL that carries OTHER params but
            OMITS `modes=` is authoritative: it CLEARS the localStorage
            seed to [] (the URL is the source of truth for the full
            share surface). A share link like ?variant=foo restores the
            DEFAULT (no modes) chrome for the recipient."
    (when (browser?)
      (rf.story/reg-mode :Mode.persist.theme/dark {:axis :theme :args {:theme :dark}})
      (rf.story.ui.toolbar/save-modes-to-storage! [:Mode.persist.theme/dark])
      (simulate-reload!)
      ;; Mount with a populated URL that has NO modes= param.
      (mount-hydrate-modes! "?variant=story.counter/loaded")
      (is (= [] (:active-modes (rf.story.ui.state/get-state)))
          "omitted modes= cleared the localStorage seed — URL authoritative"))))

(deftest mount-no-url-falls-back-to-localstorage
  (testing "rf2-96y71s — a fresh mount with NO URL state at all preserves
            the localStorage seed (apply-parsed-to-state is not run when
            the search is empty). This is the intentional last-used
            fallback that survives ONLY when the URL carries nothing."
    (when (browser?)
      (rf.story/reg-mode :Mode.persist.theme/dark {:axis :theme :args {:theme :dark}})
      (rf.story/reg-mode :Mode.persist.vp/mobile  {:axis :viewport :args {:viewport :mobile}})
      (rf.story.ui.toolbar/save-modes-to-storage!
        [:Mode.persist.theme/dark :Mode.persist.vp/mobile])
      (simulate-reload!)
      ;; Mount with NO URL params — localStorage is the only source.
      (mount-hydrate-modes! "")
      (is (= #{:Mode.persist.theme/dark :Mode.persist.vp/mobile}
             (set (:active-modes (rf.story.ui.state/get-state))))
          "no URL state ⇒ localStorage seed survives (last-used fallback)"))))

(deftest mount-url-modes-prune-is-url-authoritative
  (testing "rf2-96y71s — URL-derived modes ride apply-parsed-to-state
            verbatim (the canonical writer does not prune against the
            registrar — same discipline as every other URL-owned slot).
            A stale localStorage seed, by contrast, IS pruned by the
            localStorage hydrator before the URL apply overrides it."
    (when (browser?)
      (rf.story/reg-mode :Mode.persist.theme/dark {:axis :theme :args {:theme :dark}})
      ;; localStorage seed carries a stale id; the storage hydrator prunes it.
      (rf.story.ui.toolbar/save-modes-to-storage!
        [:Mode.persist.theme/dark :Mode.persist.removed/zzz])
      (simulate-reload!)
      ;; URL carries the live :dark only.
      (mount-hydrate-modes! (modes-url-search [:Mode.persist.theme/dark]))
      (is (= [:Mode.persist.theme/dark] (:active-modes (rf.story.ui.state/get-state)))
          "URL modes win; the stale localStorage id never surfaces"))))

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
      (rf.story/reg-mode :Mode.persist.live/x {:args {:k 1}})
      ;; Manually seed localStorage with one live id + one stale id.
      (rf.story.ui.toolbar/save-modes-to-storage!
        [:Mode.persist.live/x :Mode.persist.removed/y])
      ;; Reload + hydrate.
      (simulate-reload!)
      (rf.story.ui.toolbar/hydrate-modes-from-storage!)
      (is (= [:Mode.persist.live/x]
             (:active-modes (rf.story.ui.state/get-state)))
          "only the live mode id survives — stale id silently dropped")
      (is (not (some #{:Mode.persist.removed/y}
                     (:active-modes (rf.story.ui.state/get-state))))
          "the stale id is NOT in active-modes — drop, not error"))))

(deftest all-stale-ids-pruned-to-empty
  (testing "if every persisted id is stale, hydrate leaves the active-
            modes vector empty rather than seeding garbage. The shell
            renders no chips selected — the user re-discovers the
            available modes from scratch"
    (when (browser?)
      ;; No registered modes here — every id in storage is stale.
      (rf.story.ui.toolbar/save-modes-to-storage!
        [:Mode.persist.removed/a :Mode.persist.removed/b])
      (simulate-reload!)
      (rf.story.ui.toolbar/hydrate-modes-from-storage!)
      (is (= [] (:active-modes (rf.story.ui.state/get-state)))
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
      (rf.story/reg-mode :Mode.persist.theme/dark  {:axis :theme    :args {:theme :dark}})
      (rf.story/reg-mode :Mode.persist.theme/light {:axis :theme    :args {:theme :light}})
      (rf.story/reg-mode :Mode.persist.vp/mobile   {:axis :viewport :args {:viewport :mobile}})
      ;; Seed: dark + mobile (multi-select across axes).
      (rf.story.ui.toolbar/toggle-mode! :Mode.persist.theme/dark)
      (rf.story.ui.toolbar/toggle-mode! :Mode.persist.vp/mobile)
      ;; Reload + hydrate.
      (simulate-reload!)
      (rf.story.ui.toolbar/hydrate-modes-from-storage!)
      (is (= #{:Mode.persist.theme/dark :Mode.persist.vp/mobile}
             (set (:active-modes (rf.story.ui.state/get-state)))))
      ;; Post-reload action: toggle light theme — must evict dark.
      (rf.story.ui.toolbar/toggle-mode! :Mode.persist.theme/light)
      (let [active (set (:active-modes (rf.story.ui.state/get-state)))]
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
      (rf.story/reg-mode :Mode.persist.theme/dark  {:axis :theme    :args {:theme :dark}})
      (rf.story/reg-mode :Mode.persist.vp/mobile   {:axis :viewport :args {:viewport :mobile}})
      (rf.story/reg-mode :Mode.persist.locale/en   {:axis :locale   :args {:locale :en}})
      (rf.story.ui.toolbar/toggle-mode! :Mode.persist.theme/dark)
      (rf.story.ui.toolbar/toggle-mode! :Mode.persist.vp/mobile)
      (rf.story.ui.toolbar/toggle-mode! :Mode.persist.locale/en)
      ;; Three axes co-active.
      (is (= 3 (count (:active-modes (rf.story.ui.state/get-state)))))
      (simulate-reload!)
      (rf.story.ui.toolbar/hydrate-modes-from-storage!)
      (is (= #{:Mode.persist.theme/dark
               :Mode.persist.vp/mobile
               :Mode.persist.locale/en}
             (set (:active-modes (rf.story.ui.state/get-state))))
          "three-axis active set survives the reload round-trip"))))
