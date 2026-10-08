(ns re-frame.story.ui.backgrounds-switcher-cljs-test
  "The backgrounds switcher chip on CLJS: `select!` writes shell state,
  the effective background resolves override > toolbar, and the chip is a
  menu button (`aria-haspopup`, never `aria-pressed`, which the toolbar
  reset gate counts). The pure preset logic is in
  `re-frame.story.backgrounds-test`."
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

;; ---- select! -------------------------------------------------------------

#?(:cljs
   (deftest cljs-select-drops-unknown
     (testing "a preset is written; an unknown preset clears the slot"
       (rf.story.ui.backgrounds-switcher/select! :dark)
       (is (= :dark (:background (rf.story.ui.state/get-state))))
       (rf.story.ui.backgrounds-switcher/select! :neon)
       (is (nil? (:background (rf.story.ui.state/get-state)))))))

;; ---- per-story override resolution --------------------------------------

#?(:cljs
   (deftest cljs-effective-background-respects-variant-override
     (testing "per-variant :background body slot beats story and toolbar"
       (rf.story/reg-story* :story.bg-override
         {:doc "background override fixture" :component :ignored
          :background :paper})
       (rf.story/reg-variant* :story.bg-override/v
         {:doc "child" :background :midnight})
       (rf.story.ui.state/swap-state! assoc :background :dark)
       (rf.story.ui.state/swap-state! assoc :selected-variant :story.bg-override/v)
       (is (= "Midnight" (:label (rf.story.ui.backgrounds-switcher/effective-background))))
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
       (is (= :paper (rf.story.ui.backgrounds-switcher/effective-id))))))

#?(:cljs
   (deftest cljs-effective-background-follows-the-extends-chain
     (testing "`:background` is world context, which spec/017
               §`:extends` inherits — an `:extends` child is framed on its
               parent's background, the one the compiled plan carries, not
               on the toolbar selection"
       (rf.story/reg-story* :story.bg-extends
         {:doc "names no background" :component :ignored})
       (rf.story/reg-variant* :story.bg-extends/base
         {:doc "parent" :background :midnight})
       (rf.story/reg-variant* :story.bg-extends/child
         {:doc "child" :extends :story.bg-extends/base})
       (rf.story/reg-variant* :story.bg-extends/own
         {:doc "grandchild with its own" :extends :story.bg-extends/child
          :background :paper})
       (rf.story.ui.state/swap-state! assoc :background :dark)
       (rf.story.ui.state/swap-state! assoc :selected-variant :story.bg-extends/child)
       (is (= :midnight (rf.story.ui.backgrounds-switcher/effective-id))
           "the parent's background, not the toolbar's")
       (is (= (get-in (rf.story/variant-plan :story.bg-extends/child) [:world :background])
              (rf.story.ui.backgrounds-switcher/effective-id))
           "the background the compiled plan carries")
       (rf.story.ui.state/swap-state! assoc :selected-variant :story.bg-extends/own)
       (is (= :paper (rf.story.ui.backgrounds-switcher/effective-id))
           "the nearest declaration on the chain wins"))))

#?(:cljs
   (deftest cljs-effective-background-falls-through-to-toolbar
     (testing "no override → toolbar selection takes effect"
       (rf.story.ui.state/swap-state! assoc :background :dark)
       (is (= "Dark" (:label (rf.story.ui.backgrounds-switcher/effective-background)))))))

;; ---- the chip ------------------------------------------------------------

#?(:cljs
   (deftest cljs-chip-data-attrs
     (testing "the chip carries its browser-spec hooks and is a menu
               button: aria-haspopup, and no aria-pressed='true' anywhere
               under it"
       (let [flat  (->> (tree-seq coll? seq (rf.story.ui.backgrounds-switcher/chip))
                        (filter map?))
             attrs (filter #(= "story-toolbar-backgrounds" (:data-test %)) flat)]
         (is (= [["light" "menu"]]
                (mapv (juxt :data-background :aria-haspopup) attrs))
             "one chip element; default render reports :light")
         (is (not-any? #(= "true" (:aria-pressed %)) flat))))))
