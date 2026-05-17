(ns re-frame.story.ui.backgrounds-switcher-cljs-test
  "CLJS-side smoke tests for the backgrounds switcher chip (rf2-zll4h).

  Coverage mirrors `viewport_switcher_cljs_test`:

  - `select!` writes through to shell-state-atom.
  - The chip renders without throwing.
  - Per-story override beats the toolbar selection at resolve time.
  - The chip emits `aria-haspopup` rather than `aria-pressed` so the
    toolbar reset assertion is not tripped."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.story :as story]
            #?@(:cljs [[re-frame.story.backgrounds :as backgrounds]
                       [re-frame.story.ui.backgrounds-switcher :as bs]
                       [re-frame.story.ui.state :as state]])))

#?(:cljs
   (defn reset-all! []
     (story/clear-all!)
     (state/reset-shell-state!)
     (bs/close!)
     (when (and (exists? js/window) (.-localStorage js/window))
       (try (.removeItem (.-localStorage js/window) backgrounds/ls-key)
            (catch :default _ nil)))
     (story/install-canonical-vocabulary!)))

#?(:cljs
   (use-fixtures :each {:before reset-all!}))

;; ---- pure-ish: select! mutations ----------------------------------------

#?(:cljs
   (deftest cljs-select-writes-shell-state
     (testing "select! lands the normalised choice on shell-state-atom"
       (bs/select! :dark)
       (is (= :dark (:background (state/get-state))))
       (bs/select! :midnight)
       (is (= :midnight (:background (state/get-state)))))))

#?(:cljs
   (deftest cljs-select-custom-writes-hex
     (testing "a custom hex persists as a trimmed string"
       (bs/select! "#abc123")
       (is (= "#abc123" (:background (state/get-state)))))))

#?(:cljs
   (deftest cljs-select-drops-unknown
     (testing "unknown preset → slot cleared"
       (bs/select! :dark)
       (is (= :dark (:background (state/get-state))))
       (bs/select! :neon)
       (is (nil? (:background (state/get-state)))))))

;; ---- per-story override resolution --------------------------------------

#?(:cljs
   (deftest cljs-effective-background-respects-variant-override
     (testing "rf2-zll4h: per-variant :background body slot beats toolbar"
       (story/reg-story* :story.bg-override
         {:doc "background override fixture" :component :ignored
          :background :paper})
       (story/reg-variant* :story.bg-override/v
         {:doc "child" :background :midnight})
       (state/swap-state! assoc :background :dark)
       (state/swap-state! assoc :selected-variant :story.bg-override/v)
       (let [eff (bs/effective-background)]
         (is (= "Midnight" (:label eff))
             "variant :background (:midnight) wins over toolbar (:dark)"))
       (is (= :midnight (bs/effective-id))))))

#?(:cljs
   (deftest cljs-effective-background-falls-through-to-story
     (testing "no variant override → parent story's :background applies"
       (story/reg-story* :story.bg-story-only
         {:doc "story-level override only" :component :ignored
          :background :paper})
       (story/reg-variant* :story.bg-story-only/v
         {:doc "child"})
       (state/swap-state! assoc :background :dark)
       (state/swap-state! assoc :selected-variant :story.bg-story-only/v)
       (let [eff (bs/effective-background)]
         (is (= "Paper" (:label eff))))
       (is (= :paper (bs/effective-id))))))

#?(:cljs
   (deftest cljs-effective-background-falls-through-to-toolbar
     (testing "no override → toolbar selection takes effect"
       (state/swap-state! assoc :background :dark)
       (let [eff (bs/effective-background)]
         (is (= "Dark" (:label eff)))))))

#?(:cljs
   (deftest cljs-effective-background-default-is-light
     (testing "no override + no selection → :light"
       (let [eff (bs/effective-background)]
         (is (= "Light" (:label eff)))
         (is (= "#ffffff" (:color eff)))))))

;; ---- the chip renders without throwing ----------------------------------

#?(:cljs
   (deftest cljs-chip-renders-without-throwing
     (testing "chip-when-enabled returns a hiccup tree"
       (let [hiccup (bs/chip-when-enabled)]
         (is (some? hiccup))))))

#?(:cljs
   (deftest cljs-chip-uses-aria-haspopup-not-aria-pressed
     (testing "rf2-zll4h reset-gate: chip MUST NOT emit aria-pressed='true'"
       (let [hiccup (bs/chip)]
         (let [flat (->> (tree-seq coll? seq hiccup)
                         (filter map?))
               attrs-with-button (filter #(or (:aria-haspopup %)
                                              (:aria-pressed %)) flat)
               aria-pressed-vals (keep :aria-pressed attrs-with-button)
               aria-haspopup-vals (keep :aria-haspopup attrs-with-button)]
           (is (seq aria-haspopup-vals))
           (is (not-any? #(= "true" %) aria-pressed-vals)
               "no element under the chip is aria-pressed='true' by default"))))))

#?(:cljs
   (deftest cljs-chip-data-attrs
     (testing "chip carries data-test + data-background for browser specs"
       (let [hiccup (bs/chip)
             flat   (->> (tree-seq coll? seq hiccup)
                         (filter map?))
             attrs  (filter #(= "story-toolbar-backgrounds"
                                (:data-test %)) flat)]
         (is (= 1 (count attrs)))
         (is (= "light" (:data-background (first attrs)))
             "default render reports :light")))))

;; ---- localStorage hydration ---------------------------------------------

#?(:cljs
   (defn- browser? []
     (and (exists? js/window) (.-localStorage js/window))))

#?(:cljs
   (deftest cljs-hydrate-from-storage-seeds-empty-slot
     (when (browser?)
       (backgrounds/save-to-storage! :dark)
       (state/reset-shell-state!)
       (is (nil? (:background (state/get-state))))
       (bs/hydrate!)
       (is (= :dark (:background (state/get-state)))))))

#?(:cljs
   (deftest cljs-hydrate-skips-populated-slot
     (when (browser?)
       (backgrounds/save-to-storage! :dark)
       (state/swap-state! assoc :background :midnight)
       (bs/hydrate!)
       (is (= :midnight (:background (state/get-state)))
           "populated slot was preserved"))))
