(ns re-frame.story.ui.toolbar-persistence-dom-cljs-test
  "Toolbar mode persistence across reload (spec/010 §Persistence): set
  modes, drop the in-memory shell state, hydrate from localStorage, and
  check what came back — including stale-id pruning, per-axis exclusivity
  after reload, and the URL-over-localStorage mount precedence.

  Every row needs a real `window.localStorage`, so the namespace ends
  `-dom-cljs-test` to reach `:browser-test`. `:node-test` loads it too
  (its `cljs-test$` regexp matches the suffix), so each row answers the
  node lane with a stated skip assertion rather than running empty."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.story             :as rf.story]
            [re-frame.story.share        :as rf.story.share]
            [re-frame.story.ui.state     :as rf.story.ui.state]
            [re-frame.story.ui.toolbar   :as rf.story.ui.toolbar]
            [re-frame.story.ui.url-state :as rf.story.ui.url-state]))

;; ---- fixtures ------------------------------------------------------------

(defn- browser?
  "True under `:browser-test`, false under `:node-test`."
  []
  (and (exists? js/window) (.-localStorage js/window)))

(def ^:private skip-msg
  "skipped: no localStorage (node lane — see ns docstring)")

(defn- clear-storage! []
  (when (browser?)
    (try
      (.removeItem (.-localStorage js/window) rf.story.ui.toolbar/ls-key)
      (catch :default _ nil))))

(defn reset-all! []
  (rf.story/clear-all!)
  (rf.story.ui.state/reset-shell-state!)
  (clear-storage!)
  (rf.story/install-canonical-vocabulary!))

;; `:after` matters because these rows reach a REAL `localStorage`. The
;; slot is chrome-wide, so the browser lane runs every namespace on ONE
;; page and a `Mode.persist.*` id left behind here would still be in
;; storage when the next namespace hydrates.
(use-fixtures :each {:before reset-all! :after clear-storage!})

;; ---- helpers -------------------------------------------------------------

(defn- simulate-reload!
  "Drop the in-memory shell state; localStorage and the mode registry
  survive, as they do across a real reload."
  []
  (rf.story.ui.state/reset-shell-state!))

(defn- modes-url-search
  "The `?modes=...` search for `mode-ids`, through the production encoder."
  [mode-ids]
  (str "?" (first (rf.story.share/build-params {:active-modes mode-ids}))))

(defn- mount-hydrate-modes!
  "Run the shell-mount `:active-modes` hydration with `url-search` in the
  address bar: the localStorage fallback first, then the URL hydrator
  through `apply-parsed-to-state`. The page's own URL is restored after."
  [url-search]
  (rf.story.ui.toolbar/hydrate-modes-from-storage!)
  (let [loc  (.-location js/window)
        page (str (.-pathname loc) (.-search loc) (.-hash loc))]
    (.replaceState (.-history js/window) nil "" (str (.-pathname loc) url-search))
    (try
      (rf.story.ui.url-state/hydrate-from-url!
        rf.story.ui.state/shell-state-atom
        (fn [state parsed]
          (rf.story.ui.url-state/apply-parsed-to-state state parsed {})))
      (finally
        (.replaceState (.-history js/window) nil "" page)))))

;; ---- mode persistence across reload --------------------------------------

(deftest theme-and-viewport-persist-and-rehydrate-on-reload
  (testing "set theme + viewport, reload, both rehydrate from localStorage"
    (if-not (browser?)
      (is true skip-msg)
      (do
        (rf.story/reg-mode :Mode.persist.theme/dark
          {:axis :theme :args {:theme :dark}})
        (rf.story/reg-mode :Mode.persist.vp/mobile
          {:axis :viewport :args {:viewport :mobile}})
        (rf.story.ui.toolbar/toggle-mode! :Mode.persist.theme/dark)
        (rf.story.ui.toolbar/toggle-mode! :Mode.persist.vp/mobile)
        (simulate-reload!)
        ;; Teeth: hydrate only seeds an EMPTY slot, so a reload that left
        ;; the modes in memory would pass the final assertion for nothing.
        (is (= [] (:active-modes (rf.story.ui.state/get-state)))
            "post-reload in-memory state is empty")
        (rf.story.ui.toolbar/hydrate-modes-from-storage!)
        (is (= #{:Mode.persist.theme/dark :Mode.persist.vp/mobile}
               (set (:active-modes (rf.story.ui.state/get-state))))
            "both modes rehydrated from localStorage")))))

(deftest single-mode-persists-and-rehydrates
  (testing "a single mode survives reload"
    (if-not (browser?)
      (is true skip-msg)
      (do
        (rf.story/reg-mode :Mode.persist.theme/light
          {:axis :theme :args {:theme :light}})
        (rf.story.ui.toolbar/toggle-mode! :Mode.persist.theme/light)
        (simulate-reload!)
        (rf.story.ui.toolbar/hydrate-modes-from-storage!)
        (is (= [:Mode.persist.theme/light]
               (:active-modes (rf.story.ui.state/get-state))))))))

(deftest empty-active-modes-rehydrates-as-empty
  (testing "a mode toggled on then off leaves nothing to rehydrate"
    (if-not (browser?)
      (is true skip-msg)
      (do
        (rf.story/reg-mode :Mode.persist.theme/dark
          {:axis :theme :args {:theme :dark}})
        (rf.story.ui.toolbar/toggle-mode! :Mode.persist.theme/dark)
        (rf.story.ui.toolbar/toggle-mode! :Mode.persist.theme/dark)
        (simulate-reload!)
        (rf.story.ui.toolbar/hydrate-modes-from-storage!)
        (is (= [] (:active-modes (rf.story.ui.state/get-state))))))))

;; ---- URL vs localStorage at mount ----------------------------------------
;;
;; The toolbar does not read the URL. Mount hydration seeds from
;; localStorage, then `url-state/hydrate-from-url!` — the single URL
;; authority — applies any URL state over it.

(deftest mount-url-modes-beat-localstorage
  (testing "a URL carrying `modes=` overrides the localStorage seed:
            last-shared wins over last-used"
    (if-not (browser?)
      (is true skip-msg)
      (do
        (rf.story/reg-mode :Mode.persist.theme/dark  {:axis :theme :args {:theme :dark}})
        (rf.story/reg-mode :Mode.persist.theme/light {:axis :theme :args {:theme :light}})
        (rf.story.ui.toolbar/save-modes-to-storage! [:Mode.persist.theme/dark])
        (simulate-reload!)
        (mount-hydrate-modes! (modes-url-search [:Mode.persist.theme/light]))
        (is (= [:Mode.persist.theme/light]
               (:active-modes (rf.story.ui.state/get-state))))))))

(deftest mount-empty-search-keeps-localstorage-seed
  (testing "a mount with no URL params at all is not URL state:
            `parse-current-url` returns nil, nothing is applied, and the
            localStorage seed survives"
    (if-not (browser?)
      (is true skip-msg)
      (do
        (rf.story/reg-mode :Mode.persist.theme/dark {:axis :theme :args {:theme :dark}})
        (rf.story.ui.toolbar/save-modes-to-storage! [:Mode.persist.theme/dark])
        (simulate-reload!)
        (mount-hydrate-modes! "")
        (is (= [:Mode.persist.theme/dark] (:active-modes (rf.story.ui.state/get-state))))))))

;; ---- unknown mode id in localStorage is dropped at hydrate ---------------

(deftest stale-mode-id-pruned-at-hydrate
  (testing "a persisted id that no longer resolves at the registrar is
            silently dropped; the valid ids survive"
    (if-not (browser?)
      (is true skip-msg)
      (do
        (rf.story/reg-mode :Mode.persist.live/x {:args {:k 1}})
        (rf.story.ui.toolbar/save-modes-to-storage!
          [:Mode.persist.live/x :Mode.persist.removed/y])
        (simulate-reload!)
        (rf.story.ui.toolbar/hydrate-modes-from-storage!)
        (is (= [:Mode.persist.live/x]
               (:active-modes (rf.story.ui.state/get-state))))))))

(deftest all-stale-ids-pruned-to-empty
  (testing "if every persisted id is stale, hydrate leaves the active set empty"
    (if-not (browser?)
      (is true skip-msg)
      (do
        (rf.story.ui.toolbar/save-modes-to-storage!
          [:Mode.persist.removed/a :Mode.persist.removed/b])
        (simulate-reload!)
        (rf.story.ui.toolbar/hydrate-modes-from-storage!)
        (is (= [] (:active-modes (rf.story.ui.state/get-state))))))))

;; ---- axis semantics survive reload --------------------------------------

(deftest reload-then-toggle-third-mode-evicts-rehydrated-sibling
  (testing "after reload, toggling :light evicts the rehydrated :dark (same
            axis) and leaves :mobile (another axis) alone"
    (if-not (browser?)
      (is true skip-msg)
      (do
        (rf.story/reg-mode :Mode.persist.theme/dark  {:axis :theme    :args {:theme :dark}})
        (rf.story/reg-mode :Mode.persist.theme/light {:axis :theme    :args {:theme :light}})
        (rf.story/reg-mode :Mode.persist.vp/mobile   {:axis :viewport :args {:viewport :mobile}})
        (rf.story.ui.toolbar/toggle-mode! :Mode.persist.theme/dark)
        (rf.story.ui.toolbar/toggle-mode! :Mode.persist.vp/mobile)
        (simulate-reload!)
        (rf.story.ui.toolbar/hydrate-modes-from-storage!)
        (rf.story.ui.toolbar/toggle-mode! :Mode.persist.theme/light)
        (is (= #{:Mode.persist.theme/light :Mode.persist.vp/mobile}
               (set (:active-modes (rf.story.ui.state/get-state)))))))))

(deftest reload-preserves-multi-axis-set
  (testing "modes on three distinct axes survive reload as a set"
    (if-not (browser?)
      (is true skip-msg)
      (do
        (rf.story/reg-mode :Mode.persist.theme/dark  {:axis :theme    :args {:theme :dark}})
        (rf.story/reg-mode :Mode.persist.vp/mobile   {:axis :viewport :args {:viewport :mobile}})
        (rf.story/reg-mode :Mode.persist.locale/en   {:axis :locale   :args {:locale :en}})
        (rf.story.ui.toolbar/toggle-mode! :Mode.persist.theme/dark)
        (rf.story.ui.toolbar/toggle-mode! :Mode.persist.vp/mobile)
        (rf.story.ui.toolbar/toggle-mode! :Mode.persist.locale/en)
        (simulate-reload!)
        (rf.story.ui.toolbar/hydrate-modes-from-storage!)
        (is (= #{:Mode.persist.theme/dark
                 :Mode.persist.vp/mobile
                 :Mode.persist.locale/en}
               (set (:active-modes (rf.story.ui.state/get-state)))))))))
