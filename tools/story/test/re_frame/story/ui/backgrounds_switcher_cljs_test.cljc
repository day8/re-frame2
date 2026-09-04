(ns re-frame.story.ui.backgrounds-switcher-cljs-test
  "CLJS-side smoke tests for the backgrounds switcher chip (rf2-zll4h).

  Coverage mirrors `viewport_switcher_cljs_test`:

  - `select!` writes through to shell-state-atom.
  - The chip renders without throwing.
  - Per-story override beats the toolbar selection at resolve time.
  - The chip emits `aria-haspopup` rather than `aria-pressed` so the
    toolbar reset assertion is not tripped."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.story :as rf.story]
            #?@(:cljs [[re-frame.story.backgrounds :as rf.story.backgrounds]
                       [re-frame.story.ui.backgrounds-switcher :as rf.story.ui.backgrounds-switcher]
                       [re-frame.story.ui.state :as rf.story.ui.state]])))

#?(:cljs
   (defn reset-all! []
     (rf.story/clear-all!)
     (rf.story.ui.state/reset-shell-state!)
     (rf.story.ui.backgrounds-switcher/close!)
     (when (and (exists? js/window) (.-localStorage js/window))
       (try (.removeItem (.-localStorage js/window) rf.story.backgrounds/ls-key)
            (catch :default _ nil)))
     (rf.story/install-canonical-vocabulary!)))

#?(:cljs
   (use-fixtures :each (fn [t] (reset-all!) (t))))

;; ---- pure-ish: select! mutations ----------------------------------------

#?(:cljs
   (deftest cljs-select-writes-shell-state
     (testing "select! lands the normalised choice on shell-state-atom"
       (rf.story.ui.backgrounds-switcher/select! :dark)
       (is (= :dark (:background (rf.story.ui.state/get-state))))
       (rf.story.ui.backgrounds-switcher/select! :midnight)
       (is (= :midnight (:background (rf.story.ui.state/get-state)))))))

#?(:cljs
   (deftest cljs-select-custom-writes-hex
     (testing "a custom hex persists as a trimmed string"
       (rf.story.ui.backgrounds-switcher/select! "#abc123")
       (is (= "#abc123" (:background (rf.story.ui.state/get-state)))))))

#?(:cljs
   (deftest cljs-select-drops-unknown
     (testing "unknown preset → slot cleared"
       (rf.story.ui.backgrounds-switcher/select! :dark)
       (is (= :dark (:background (rf.story.ui.state/get-state))))
       (rf.story.ui.backgrounds-switcher/select! :neon)
       (is (nil? (:background (rf.story.ui.state/get-state)))))))

;; ---- per-story override resolution --------------------------------------

#?(:cljs
   (deftest cljs-effective-background-respects-variant-override
     (testing "rf2-zll4h: per-variant :background body slot beats toolbar"
       (rf.story/reg-story* :story.bg-override
         {:doc "background override fixture" :component :ignored
          :background :paper})
       (rf.story/reg-variant* :story.bg-override/v
         {:doc "child" :background :midnight})
       (rf.story.ui.state/swap-state! assoc :background :dark)
       (rf.story.ui.state/swap-state! assoc :selected-variant :story.bg-override/v)
       (let [eff (rf.story.ui.backgrounds-switcher/effective-background)]
         (is (= "Midnight" (:label eff))
             "variant :background (:midnight) wins over toolbar (:dark)"))
       (is (= :midnight (rf.story.ui.backgrounds-switcher/effective-id))))))

#?(:cljs
   (deftest cljs-effective-background-falls-through-to-story
     (testing "no variant override → parent story's :background applies"
       (rf.story/reg-story* :story.bg-story-only
         {:doc "story-level override only" :component :ignored
          :background :paper})
       (rf.story/reg-variant* :story.bg-story-only/v
         {:doc "child"})
       (rf.story.ui.state/swap-state! assoc :background :dark)
       (rf.story.ui.state/swap-state! assoc :selected-variant :story.bg-story-only/v)
       (let [eff (rf.story.ui.backgrounds-switcher/effective-background)]
         (is (= "Paper" (:label eff))))
       (is (= :paper (rf.story.ui.backgrounds-switcher/effective-id))))))

#?(:cljs
   (deftest cljs-effective-background-falls-through-to-toolbar
     (testing "no override → toolbar selection takes effect"
       (rf.story.ui.state/swap-state! assoc :background :dark)
       (let [eff (rf.story.ui.backgrounds-switcher/effective-background)]
         (is (= "Dark" (:label eff)))))))

#?(:cljs
   (deftest cljs-effective-background-default-is-light
     (testing "no override + no selection → :light"
       (let [eff (rf.story.ui.backgrounds-switcher/effective-background)]
         (is (= "Light" (:label eff)))
         (is (= "#ffffff" (:color eff)))))))

;; ---- the chip renders without throwing ----------------------------------

#?(:cljs
   (deftest cljs-chip-renders-without-throwing
     (testing "chip-when-enabled returns a hiccup tree"
       (let [hiccup (rf.story.ui.backgrounds-switcher/chip-when-enabled)]
         (is (some? hiccup))))))

#?(:cljs
   (deftest cljs-chip-uses-aria-haspopup-not-aria-pressed
     (testing "rf2-zll4h reset-gate: chip MUST NOT emit aria-pressed='true'"
       (let [hiccup (rf.story.ui.backgrounds-switcher/chip)]
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
       (let [hiccup (rf.story.ui.backgrounds-switcher/chip)
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
       (rf.story.backgrounds/save-to-storage! :dark)
       (rf.story.ui.state/reset-shell-state!)
       (is (nil? (:background (rf.story.ui.state/get-state))))
       (rf.story.ui.backgrounds-switcher/hydrate!)
       (is (= :dark (:background (rf.story.ui.state/get-state)))))))

#?(:cljs
   (deftest cljs-hydrate-skips-populated-slot
     (when (browser?)
       (rf.story.backgrounds/save-to-storage! :dark)
       (rf.story.ui.state/swap-state! assoc :background :midnight)
       (rf.story.ui.backgrounds-switcher/hydrate!)
       (is (= :midnight (:background (rf.story.ui.state/get-state)))
           "populated slot was preserved"))))
