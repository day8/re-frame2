(ns re-frame.story.ui.viewport-switcher-cljs-test
  "CLJS-side smoke tests for the viewport switcher chip (rf2-zll4h).

  Runs in shadow's `:node-test` build (the ns name ends in `cljs-test`
  to match `:ns-regexp \"cljs-test$\"`). The JVM gate is covered by
  `re-frame.story.viewport-test`; this ns exercises the CLJS-only
  surfaces:

  - `select!` writes through to shell-state-atom.
  - The chip renders without throwing.
  - Per-story override beats the toolbar selection at resolve time.
  - The chip emits `aria-haspopup` + `aria-expanded` (NOT
    `aria-pressed`) so the toolbar reset assertion is not tripped."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.story :as story]
            #?@(:cljs [[re-frame.story.ui.state :as state]
                       [re-frame.story.ui.viewport-switcher :as vs]
                       [re-frame.story.viewport :as viewport]])))

#?(:cljs
   (defn reset-all! []
     (story/clear-all!)
     (state/reset-shell-state!)
     (vs/close!)
     (when (and (exists? js/window) (.-localStorage js/window))
       (try (.removeItem (.-localStorage js/window) viewport/ls-key)
            (catch :default _ nil)))
     (story/install-canonical-vocabulary!)))

#?(:cljs
   (use-fixtures :each {:before reset-all!}))

;; ---- pure-ish: select! mutations ----------------------------------------

#?(:cljs
   (deftest cljs-select-writes-shell-state
     (testing "select! lands the normalised choice on shell-state-atom"
       (vs/select! :tablet)
       (is (= :tablet (:viewport (state/get-state))))
       (vs/select! :mobile-portrait)
       (is (= :mobile-portrait (:viewport (state/get-state)))))))

#?(:cljs
   (deftest cljs-select-custom-writes-map
     (testing "a custom map persists as a slim {:width :height}"
       (vs/select! {:width 800 :height 600})
       (is (= {:width 800 :height 600} (:viewport (state/get-state)))))))

#?(:cljs
   (deftest cljs-select-drops-unknown
     (testing "an unknown preset coerces to nil — slot is cleared"
       (vs/select! :tablet)
       (is (= :tablet (:viewport (state/get-state))))
       (vs/select! :phablet)
       (is (nil? (:viewport (state/get-state)))))))

;; ---- per-story override resolution --------------------------------------

#?(:cljs
   (deftest cljs-effective-viewport-respects-variant-override
     (testing "rf2-zll4h: per-variant :viewport body slot beats toolbar"
       (story/reg-story* :story.vp-override
         {:doc "viewport override fixture" :component :ignored
          :viewport :desktop})
       (story/reg-variant* :story.vp-override/v
         {:doc "child" :viewport :mobile-portrait})
       (state/swap-state! assoc :viewport :tablet)
       (state/swap-state! assoc :selected-variant :story.vp-override/v)
       (let [eff (vs/effective-viewport)]
         (is (= "Mobile portrait" (:label eff))
             "variant body's :viewport (:mobile-portrait) wins over toolbar (:tablet)"))
       (is (= :mobile-portrait (vs/effective-id))))))

#?(:cljs
   (deftest cljs-effective-viewport-falls-through-to-story
     (testing "variant has no :viewport → parent story's :viewport applies"
       (story/reg-story* :story.vp-story-only
         {:doc "story-level override only" :component :ignored
          :viewport :desktop})
       (story/reg-variant* :story.vp-story-only/v
         {:doc "child"})
       (state/swap-state! assoc :viewport :tablet)
       (state/swap-state! assoc :selected-variant :story.vp-story-only/v)
       (let [eff (vs/effective-viewport)]
         (is (= "Desktop" (:label eff))))
       (is (= :desktop (vs/effective-id))))))

#?(:cljs
   (deftest cljs-effective-viewport-falls-through-to-toolbar
     (testing "no override → toolbar selection takes effect"
       (state/swap-state! assoc :viewport :tablet)
       (let [eff (vs/effective-viewport)]
         (is (= "Tablet" (:label eff)))))))

#?(:cljs
   (deftest cljs-effective-viewport-default-is-full
     (testing "no override + no selection → :full"
       (let [eff (vs/effective-viewport)]
         (is (= "Full" (:label eff)))
         (is (nil? (:width eff)))))))

;; ---- the chip renders without throwing ----------------------------------

#?(:cljs
   (deftest cljs-chip-renders-without-throwing
     (testing "chip-when-enabled returns a hiccup tree (no exceptions)"
       (let [hiccup (vs/chip-when-enabled)]
         (is (some? hiccup))))))

#?(:cljs
   (deftest cljs-chip-uses-aria-haspopup-not-aria-pressed
     (testing "rf2-zll4h reset-gate: chip MUST NOT emit aria-pressed='true'
               by default. The toolbar reset assertion in
               story_feature_load counts [aria-pressed='true'] post-reset
               and demands count === 0."
       (let [hiccup (vs/chip)]
         ;; The chip render returns [:span ... [:button {...}]]; the
         ;; button's attribute map is the second element of the inner
         ;; button vector. Walk the hiccup defensively.
         (let [flat (->> (tree-seq coll? seq hiccup)
                         (filter map?))
               attrs-with-button (filter #(or (:aria-haspopup %)
                                              (:aria-pressed %)) flat)
               aria-pressed-vals (keep :aria-pressed attrs-with-button)
               aria-haspopup-vals (keep :aria-haspopup attrs-with-button)]
           (is (seq aria-haspopup-vals)
               "the chip exposes aria-haspopup for screen readers")
           (is (not-any? #(= "true" %) aria-pressed-vals)
               "no element under the chip is aria-pressed='true' by default"))))))

#?(:cljs
   (deftest cljs-chip-data-attrs
     (testing "chip carries data-test + data-viewport for browser specs"
       (let [hiccup (vs/chip)
             flat   (->> (tree-seq coll? seq hiccup)
                         (filter map?))
             attrs  (filter #(= "story-toolbar-viewport"
                                (:data-test %)) flat)]
         (is (= 1 (count attrs))
             "exactly one chip element carries the data-test handle")
         (is (= "full" (:data-viewport (first attrs)))
             "default render reports :full")))))

;; ---- localStorage hydration ---------------------------------------------

#?(:cljs
   (defn- browser? []
     (and (exists? js/window) (.-localStorage js/window))))

#?(:cljs
   (deftest cljs-hydrate-from-storage-seeds-empty-slot
     (testing "hydrate! seeds the shell slot from persisted localStorage"
       (when (browser?)
         (viewport/save-to-storage! :tablet)
         (state/reset-shell-state!)
         (is (nil? (:viewport (state/get-state))))
         (vs/hydrate!)
         (is (= :tablet (:viewport (state/get-state))))))))

#?(:cljs
   (deftest cljs-hydrate-skips-populated-slot
     (testing "hydrate is idempotent — leaves an already-populated slot alone"
       (when (browser?)
         (viewport/save-to-storage! :tablet)
         (state/swap-state! assoc :viewport :mobile-portrait)
         (vs/hydrate!)
         (is (= :mobile-portrait (:viewport (state/get-state)))
             "populated slot was preserved")))))
